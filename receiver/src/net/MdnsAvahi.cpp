#include "net/MdnsPublisher.h"

#include <avahi-client/client.h>
#include <avahi-client/publish.h>
#include <avahi-common/alternative.h>
#include <avahi-common/error.h>
#include <avahi-common/malloc.h>
#include <avahi-common/strlst.h>
#include <avahi-common/thread-watch.h>

#include <QLoggingCategory>

Q_LOGGING_CATEGORY(lcMdns, "yansit.mdns")

namespace yansit::net {

class MdnsPublisher::Impl {
public:
    ~Impl() { stop(); }

    void publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint);
    void stop();

private:
    static void clientCallback(AvahiClient* c, AvahiClientState state, void* self) {
        static_cast<Impl*>(self)->onClientState(c, int(state));
    }
    static void groupCallback(AvahiEntryGroup* g, AvahiEntryGroupState state, void* self) {
        static_cast<Impl*>(self)->onGroupState(g, int(state));
    }

    // Avahi geri çağrıları (Avahi iş parçacığında, kilit altında çağrılır).
    void onClientState(AvahiClient* client, int state);
    void onGroupState(AvahiEntryGroup* group, int state);
    void registerService(AvahiClient* client);

    AvahiThreadedPoll* m_poll = nullptr;
    AvahiClient* m_client = nullptr;
    AvahiEntryGroup* m_group = nullptr;
    std::string m_instance;
    std::uint16_t m_port = 0;
    std::vector<std::string> m_txt;
    bool m_wanted = false;
};

MdnsPublisher::MdnsPublisher() : m_impl(std::make_unique<Impl>()) {}

MdnsPublisher::~MdnsPublisher() = default;

void MdnsPublisher::publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint) {
    m_impl->publish(name, port, fingerprint);
}

void MdnsPublisher::stop() { m_impl->stop(); }

void MdnsPublisher::Impl::publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint) {
    if (!m_poll) {
        m_poll = avahi_threaded_poll_new();
        if (!m_poll) {
            qCWarning(lcMdns) << "Avahi başlatılamadı";
            return;
        }
        m_instance = mdnsInstanceName(name);
        m_port = port;
        m_txt = mdnsTxt(name, fingerprint);
        m_wanted = true;
        int error = 0;
        // NO_FAIL: daemon yoksa veya yeniden başlarsa istemci bekler, gelince S_RUNNING'e geçer.
        m_client = avahi_client_new(avahi_threaded_poll_get(m_poll), AVAHI_CLIENT_NO_FAIL, clientCallback, this, &error);
        if (!m_client) {
            // D-Bus/avahi-daemon yoksa beklenen durum: QR ve PIN akışı etkilenmez.
            qCInfo(lcMdns) << "mDNS yayını yok (Avahi):" << avahi_strerror(error);
            avahi_threaded_poll_free(m_poll);
            m_poll = nullptr;
            return;
        }
        avahi_threaded_poll_start(m_poll);
        return;
    }
    avahi_threaded_poll_lock(m_poll);
    m_instance = mdnsInstanceName(name);
    m_port = port;
    m_txt = mdnsTxt(name, fingerprint);
    m_wanted = true;
    if (m_group)
        avahi_entry_group_reset(m_group);
    if (m_client && avahi_client_get_state(m_client) == AVAHI_CLIENT_S_RUNNING)
        registerService(m_client);
    avahi_threaded_poll_unlock(m_poll);
}

void MdnsPublisher::Impl::stop() {
    if (!m_poll)
        return;
    avahi_threaded_poll_stop(m_poll);
    m_wanted = false;
    if (m_group)
        avahi_entry_group_free(m_group);
    m_group = nullptr;
    if (m_client)
        avahi_client_free(m_client);
    m_client = nullptr;
    avahi_threaded_poll_free(m_poll);
    m_poll = nullptr;
}

void MdnsPublisher::Impl::registerService(AvahiClient* client) {
    if (!m_wanted)
        return;
    if (!m_group) {
        m_group = avahi_entry_group_new(client, groupCallback, this);
        if (!m_group) {
            qCWarning(lcMdns) << "Avahi grup oluşturulamadı:" << avahi_strerror(avahi_client_errno(client));
            return;
        }
    }
    if (!avahi_entry_group_is_empty(m_group))
        return;
    AvahiStringList* txt = nullptr;
    for (auto it = m_txt.rbegin(); it != m_txt.rend(); ++it)
        txt = avahi_string_list_add(txt, it->c_str());
    // Yalnız IPv4: protokol ve QR IPv4 adresleri kullanır.
    int rc = avahi_entry_group_add_service_strlst(m_group, AVAHI_IF_UNSPEC, AVAHI_PROTO_INET, AvahiPublishFlags(0),
                                                  m_instance.c_str(), kMdnsServiceType, nullptr, nullptr, m_port, txt);
    avahi_string_list_free(txt);
    if (rc == AVAHI_ERR_COLLISION) {
        char* alt = avahi_alternative_service_name(m_instance.c_str());
        m_instance = alt;
        avahi_free(alt);
        avahi_entry_group_reset(m_group);
        registerService(client);
        return;
    }
    if (rc < 0) {
        qCWarning(lcMdns) << "hizmet eklenemedi:" << avahi_strerror(rc);
        return;
    }
    rc = avahi_entry_group_commit(m_group);
    if (rc < 0)
        qCWarning(lcMdns) << "yayın başlatılamadı:" << avahi_strerror(rc);
}

void MdnsPublisher::Impl::onClientState(AvahiClient* client, int state) {
    switch (AvahiClientState(state)) {
    case AVAHI_CLIENT_S_RUNNING:
        registerService(client);
        break;
    case AVAHI_CLIENT_S_COLLISION:
    case AVAHI_CLIENT_S_REGISTERING:
        if (m_group)
            avahi_entry_group_reset(m_group);
        break;
    case AVAHI_CLIENT_FAILURE:
        qCInfo(lcMdns) << "Avahi bağlantısı koptu; mDNS yayını yok (QR çalışır)";
        break;
    case AVAHI_CLIENT_CONNECTING:
        break;
    }
}

void MdnsPublisher::Impl::onGroupState(AvahiEntryGroup* group, int state) {
    switch (AvahiEntryGroupState(state)) {
    case AVAHI_ENTRY_GROUP_ESTABLISHED:
        qCInfo(lcMdns) << "mDNS yayını:" << QString::fromStdString(m_instance) << kMdnsServiceType << m_port;
        break;
    case AVAHI_ENTRY_GROUP_COLLISION: {
        char* alt = avahi_alternative_service_name(m_instance.c_str());
        m_instance = alt;
        avahi_free(alt);
        avahi_entry_group_reset(group);
        registerService(avahi_entry_group_get_client(group));
        break;
    }
    case AVAHI_ENTRY_GROUP_FAILURE:
        qCWarning(lcMdns) << "mDNS yayını başarısız:"
                          << avahi_strerror(avahi_client_errno(avahi_entry_group_get_client(group)));
        break;
    case AVAHI_ENTRY_GROUP_UNCOMMITED:
    case AVAHI_ENTRY_GROUP_REGISTERING:
        break;
    }
}

} // namespace yansit::net
