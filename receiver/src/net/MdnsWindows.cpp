// The DNS-SD declarations of windns.h are newer than the first Windows 10 SDK. The minimum Qt asks for must not hide
// them: the functions are looked up at run time, so systems without them still start and only lose discovery.
#undef NTDDI_VERSION
#define NTDDI_VERSION 0x0A00000C

#include "net/MdnsPublisher.h"

#include <winsock2.h>

#include <windows.h>

#include <windns.h>

#include <QLoggingCategory>
#include <QString>

#include <chrono>
#include <condition_variable>
#include <iterator>
#include <mutex>
#include <vector>

Q_LOGGING_CATEGORY(lcMdns, "yansit.mdns")

namespace yansit::net {

namespace {

constexpr auto kCompletionWait = std::chrono::seconds(3);
/// DNS_REQUEST_PENDING of winerror.h, which not every SDK defines.
constexpr DWORD kRequestPending = 9506;

/// dnsapi.dll stays loaded: a late completion callback must still find its code.
struct DnsSdApi {
    decltype(&DnsServiceConstructInstance) construct = nullptr;
    decltype(&DnsServiceFreeInstance) freeInstance = nullptr;
    decltype(&DnsServiceRegister) registerService = nullptr;
    decltype(&DnsServiceDeRegister) deregisterService = nullptr;

    DnsSdApi() {
        const HMODULE dll = LoadLibraryExW(L"dnsapi.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!dll)
            return;
        construct = reinterpret_cast<decltype(construct)>(GetProcAddress(dll, "DnsServiceConstructInstance"));
        freeInstance = reinterpret_cast<decltype(freeInstance)>(GetProcAddress(dll, "DnsServiceFreeInstance"));
        registerService = reinterpret_cast<decltype(registerService)>(GetProcAddress(dll, "DnsServiceRegister"));
        deregisterService = reinterpret_cast<decltype(deregisterService)>(GetProcAddress(dll, "DnsServiceDeRegister"));
    }

    bool available() const { return construct && freeInstance && registerService && deregisterService; }
};

const DnsSdApi& api() {
    static const DnsSdApi instance;
    return instance;
}

/// The API keeps pointers into this until its last callback, so it has a fixed address.
struct Registration {
    DNS_SERVICE_REGISTER_REQUEST request{};
    PDNS_SERVICE_INSTANCE instance = nullptr;
    std::mutex mutex;
    std::condition_variable changed;
    int completions = 0;
};

/// Runs on a system thread pool thread, once for the registration and once for the deregistration.
VOID WINAPI onComplete(DWORD status, PVOID context, PDNS_SERVICE_INSTANCE result) {
    auto* registration = static_cast<Registration*>(context);
    if (result && result != registration->instance)
        api().freeInstance(result);
    if (status == ERROR_SUCCESS)
        qCInfo(lcMdns) << "mDNS işlemi tamamlandı";
    else
        qCInfo(lcMdns) << "mDNS işlemi başarısız, durum" << status;
    std::lock_guard lock(registration->mutex);
    ++registration->completions;
    registration->changed.notify_all();
}

std::wstring localHostName() {
    wchar_t name[256];
    DWORD size = DWORD(std::size(name));
    if (!GetComputerNameExW(ComputerNameDnsHostname, name, &size))
        return L"casthop.local";
    return std::wstring(name, size) + L".local";
}

/// A dot would start a new DNS label here, and the API takes a plain name. The TXT record keeps the exact name.
std::wstring serviceName(const std::string& instance) {
    return QString::fromStdString(instance).replace(QLatin1Char('.'), QLatin1Char('-')).toStdWString() + L"." +
           QString::fromLatin1(kMdnsServiceType).toStdWString() + L".local";
}

} // namespace

class MdnsPublisher::Impl {
public:
    ~Impl() { stop(); }

    void publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint) {
        stop();
        if (!api().available()) {
            qCInfo(lcMdns) << "mDNS yayını yok: bu Windows sürümünde DnsServiceRegister bulunmuyor (QR çalışır)";
            return;
        }

        std::vector<std::wstring> keys;
        std::vector<std::wstring> values;
        for (const std::string& entry : mdnsTxt(name, fingerprint)) {
            const std::size_t split = entry.find('=');
            keys.push_back(QString::fromStdString(entry.substr(0, split)).toStdWString());
            values.push_back(QString::fromStdString(entry.substr(split + 1)).toStdWString());
        }
        std::vector<PCWSTR> keyPointers;
        std::vector<PCWSTR> valuePointers;
        for (std::size_t i = 0; i < keys.size(); ++i) {
            keyPointers.push_back(keys[i].c_str());
            valuePointers.push_back(values[i].c_str());
        }

        const std::wstring service = serviceName(mdnsInstanceName(name));
        const std::wstring host = localHostName();
        auto registration = std::make_unique<Registration>();
        // No address: the DNS Client service answers with the addresses of each interface.
        registration->instance = api().construct(service.c_str(), host.c_str(), nullptr, nullptr, port, 0, 0,
                                                 DWORD(keys.size()), keyPointers.data(), valuePointers.data());
        if (!registration->instance) {
            qCInfo(lcMdns) << "mDNS yayını yok: hizmet kaydı kurulamadı";
            return;
        }
        registration->request.Version = DNS_QUERY_REQUEST_VERSION1;
        registration->request.InterfaceIndex = 0;
        registration->request.pServiceInstance = registration->instance;
        registration->request.pRegisterCompletionCallback = onComplete;
        registration->request.pQueryContext = registration.get();
        registration->request.unicastEnabled = FALSE;

        const DWORD status = api().registerService(&registration->request, nullptr);
        if (status != kRequestPending) {
            qCInfo(lcMdns) << "mDNS yayını yok: DnsServiceRegister durumu" << status;
            api().freeInstance(registration->instance);
            return;
        }
        qCInfo(lcMdns) << "mDNS yayını:" << QString::fromStdWString(service) << port;
        m_registration = std::move(registration);
    }

    void stop() {
        if (!m_registration)
            return;
        Registration* registration = m_registration.release();
        {
            // Registration probes the network first; removing it before it ends would leave the callback pending.
            std::unique_lock lock(registration->mutex);
            registration->changed.wait_for(lock, kCompletionWait, [&] { return registration->completions >= 1; });
        }
        const DWORD status = api().deregisterService(&registration->request, nullptr);
        const int expected = status == kRequestPending ? 2 : 1;
        bool finished = false;
        {
            std::unique_lock lock(registration->mutex);
            finished = registration->changed.wait_for(lock, kCompletionWait,
                                                      [&] { return registration->completions >= expected; });
        }
        // Without its last callback the memory may still be used. It is small, and Windows ends the registration
        // with the process.
        if (finished) {
            api().freeInstance(registration->instance);
            delete registration;
        } else {
            qCInfo(lcMdns) << "mDNS kaydı zamanında kapanmadı";
        }
    }

private:
    std::unique_ptr<Registration> m_registration;
};

MdnsPublisher::MdnsPublisher() : m_impl(std::make_unique<Impl>()) {}

MdnsPublisher::~MdnsPublisher() = default;

void MdnsPublisher::publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint) {
    m_impl->publish(name, port, fingerprint);
}

void MdnsPublisher::stop() { m_impl->stop(); }

} // namespace yansit::net
