#pragma once

#include "protocol/Control.h"

#include <chrono>
#include <deque>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace yansit::session {

/// Kalıcı eşleştirilmiş gönderici (auth_method = 3).
struct TrustedSender {
    protocol::Id16 keyId{};
    protocol::Key32 pairKey{};
    protocol::Id16 deviceId{};
    std::string deviceName;
};

/**
 * Alıcı tarafı kimlik doğrulama politikası (PROTOCOL.md §7.1): tek kullanımlık QR token'ı ve PIN,
 * PIN kaba kuvvet kilidi, genel HELLO hız sınırı, kalıcı eşleştirme anahtarları.
 *
 * Zaman dışarıdan verilir (test edilebilirlik); ağ veya Qt bağımlılığı yoktur.
 */
class PairingManager {
public:
    using Clock = std::chrono::steady_clock;

    struct Policy {
        std::chrono::milliseconds tokenLifetime{std::chrono::minutes(10)};
        int maxPinFailures = 5;
        std::chrono::milliseconds pinLockout{std::chrono::seconds(30)};
        int maxHellosPerWindow = 10;
        std::chrono::milliseconds helloWindow{std::chrono::seconds(10)};
    };

    enum class Outcome { Accepted, AuthFailed, RateLimited, LockedOut, UnsupportedMethod };

    struct Verdict {
        Outcome outcome = Outcome::AuthFailed;
        std::optional<std::chrono::milliseconds> retryAfter;
        /// Kabulde, eşleştirme ile girildiyse kayıtlı gönderici.
        std::optional<TrustedSender> trusted;
    };

    explicit PairingManager(Clock::time_point now) : PairingManager(Policy{}, now) {}
    PairingManager(Policy policy, Clock::time_point now);

    const protocol::Id16& qrToken() const { return m_token; }
    /// 6 hanelik PIN, baştaki sıfırlar dahil.
    const std::string& pin() const { return m_pin; }

    /// Token süresi dolduysa yeniler. QR yenilenmesi gerekiyorsa true.
    bool tick(Clock::time_point now);

    /// HELLO'yu doğrular. Başarılı QR/PIN kimlik bilgileri tek kullanımlıktır ve hemen yenilenir.
    Verdict verifyHello(const protocol::Hello& hello, const protocol::Id16& serverNonce,
                        const protocol::Key32& certFingerprint, Clock::time_point now);

    /// Kabul edilmiş bir HELLO için yeni kalıcı eşleştirme oluşturur ve kaydeder.
    TrustedSender createPairing(const protocol::Hello& hello);

    const std::vector<TrustedSender>& trustedSenders() const { return m_trusted; }
    void setTrustedSenders(std::vector<TrustedSender> list) { m_trusted = std::move(list); }
    bool removeTrusted(const protocol::Id16& keyId);

    /// QR token'ı veya PIN değiştiğinde çağrılır (bekleme ekranını yenilemek için).
    std::function<void()> onCredentialsChanged;
    /// Güvenilir gönderici listesi değiştiğinde çağrılır (kalıcı depolama için).
    std::function<void()> onTrustedChanged;

private:
    void rotateToken(Clock::time_point now);
    void rotatePin();
    void notifyCredentials();

    Policy m_policy;
    protocol::Id16 m_token{};
    Clock::time_point m_tokenIssued;
    std::string m_pin;
    int m_pinFailures = 0;
    std::optional<Clock::time_point> m_pinLockedUntil;
    std::deque<Clock::time_point> m_recentHellos;
    std::vector<TrustedSender> m_trusted;
};

/// [0, 999999] aralığında düzgün dağılımlı 6 haneli PIN.
std::string generatePin();

} // namespace yansit::session
