#pragma once

#include "protocol/Bytes.h"

#include <cstdint>
#include <string>
#include <vector>

struct AvahiThreadedPoll;
struct AvahiClient;
struct AvahiEntryGroup;

namespace yansit::net {

inline constexpr const char* kMdnsServiceType = "_yansit._tcp";

/// PROTOCOL.md §8: `v=1`, `name=<ad>`, `fp=<parmak izinin ilk 16 baytı, base64url>`.
std::vector<std::string> mdnsTxt(const std::string& name, const protocol::Key32& fingerprint);

/// DNS-SD örnek adı en fazla 63 bayt; UTF-8 karakter ortasından kesilmez.
std::string mdnsInstanceName(const std::string& name);

/**
 * `_yansit._tcp` hizmetini Avahi ile yayınlar (yalnız bulma kolaylığı; kimlik doğrulama yine PIN/eşleştirmedir).
 * Avahi kendi iş parçacığında çalışır; daemon yoksa sessizce bekler ve gelince yayınlar. Ad çakışmasında
 * Avahi'nin önerdiği alternatif ad kullanılır. Yayın başarısız olsa da QR akışı etkilenmez.
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

    // Avahi geri çağrıları (iç kullanım; Avahi iş parçacığında, kilit altında çağrılır).
    void onClientState(AvahiClient* client, int state);
    void onGroupState(AvahiEntryGroup* group, int state);

private:
    void registerService(AvahiClient* client);

    AvahiThreadedPoll* m_poll = nullptr;
    AvahiClient* m_client = nullptr;
    AvahiEntryGroup* m_group = nullptr;
    std::string m_instance;
    std::uint16_t m_port = 0;
    std::vector<std::string> m_txt;
    bool m_wanted = false;
};

} // namespace yansit::net
