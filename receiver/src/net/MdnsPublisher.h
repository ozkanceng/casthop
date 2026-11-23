#pragma once

#include "protocol/Bytes.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace yansit::net {

inline constexpr const char* kMdnsServiceType = "_yansit._tcp";

/// PROTOCOL.md §8: `v=1`, `name=<ad>`, `fp=<parmak izinin ilk 16 baytı, base64url>`.
std::vector<std::string> mdnsTxt(const std::string& name, const protocol::Key32& fingerprint);

/// DNS-SD örnek adı en fazla 63 bayt; UTF-8 karakter ortasından kesilmez.
std::string mdnsInstanceName(const std::string& name);

/// Sertifika parmak izinin ilk 2 baytından 4 haneli kalıcı büyük-harf hex kimlik (örn. "A4F2").
/// mDNS TXT `fp=` ilk 16 baytı taşıdığından keşif, eşleşme ve bekleme ekranında birebir aynıdır.
std::string shortBoardId(const protocol::Key32& fingerprint);

/// ETAP/Pardus imajlarındaki genel varsayılan makine adları mı? (örn. "etap", "pardus", "localhost").
bool isGenericHostName(const std::string& hostName);

/// Özel ad girilmemişse genel makine adlarını parmak izi sonekiyle benzersizleştirir (örn. "etap-A4F2").
std::string defaultBoardName(const std::string& hostName, const protocol::Key32& fingerprint);

/**
 * `_yansit._tcp` hizmetini yayınlar (yalnız bulma kolaylığı; kimlik doğrulama yine PIN/eşleştirmedir). Linux'ta Avahi
 * (`MdnsAvahi.cpp`): daemon yoksa sessizce bekler, ad çakışmasında önerilen alternatif adı kullanır. Windows'ta
 * `DnsServiceRegister` (`MdnsWindows.cpp`). Yayın başarısız olsa da QR akışı etkilenmez.
 */
class MdnsPublisher {
public:
    MdnsPublisher();
    ~MdnsPublisher();
    MdnsPublisher(const MdnsPublisher&) = delete;
    MdnsPublisher& operator=(const MdnsPublisher&) = delete;

    /// Yayını başlatır veya içeriğini günceller (ad/port/parmak izi değişince).
    void publish(const std::string& name, std::uint16_t port, const protocol::Key32& fingerprint);
    void stop();

private:
    class Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace yansit::net
