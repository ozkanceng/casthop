#pragma once

#include "protocol/Control.h"
#include "session/PairingManager.h"

#include <chrono>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace yansit::session {

/// Ağ katmanının bağlantı kimliği (her TCP bağlantısı için benzersiz).
using ConnId = std::uint64_t;

/// WELCOME'da bildirilen alıcı yetenekleri.
struct ReceiverInfo {
    std::string name;
    std::uint16_t displayWidth = 1920;
    std::uint16_t displayHeight = 1080;
    std::uint8_t displayRefresh = 60;
    std::vector<protocol::CodecSupport> videoDecoders;
    std::vector<protocol::CodecSupport> audioDecoders;
    bool audioEnabled = false;
    /// WELCOME rotated_frames: the screen turns FrameFlags::Rotated pictures back.
    bool rotatedFrames = false;
};

/// Bağlı göndericinin HELLO'dan gelen bilgileri.
struct SenderInfo {
    std::string deviceName;
    protocol::Id16 deviceId{};
    std::string appVersion;
    std::uint8_t authMethod = 0;
    std::vector<std::uint8_t> videoCodecs;
    std::vector<std::uint8_t> audioCodecs;
    std::uint16_t maxWidth = 0;
    std::uint16_t maxHeight = 0;
    std::uint8_t maxFps = 0;
};

/// Ağ katmanına yaptırılacak işler: önce `send` sırasıyla gönderilir, ardından `close` bağlantıları kapatılır.
struct Actions {
    std::vector<std::pair<ConnId, protocol::ControlMessage>> send;
    std::vector<ConnId> close;

    void merge(Actions other);
    bool empty() const { return send.empty() && close.empty(); }
};

/**
 * Tek-gönderici oturum durum makinesi (PROTOCOL.md §4, §7.1): HELLO kabulü, devralma onayı,
 * ATTACH, RESUME penceresi ve oturum sonu.
 *
 * Ağdan ve Qt'den bağımsızdır; zaman dışarıdan verilir. Her çağrı yapılacak gönderim/kapatma
 * işlerini döndürür, durum sorgu fonksiyonlarıyla okunur.
 */
class SessionManager {
public:
    using Clock = std::chrono::steady_clock;

    struct Policy {
        std::chrono::milliseconds approvalTimeout{std::chrono::seconds(20)};
        std::chrono::milliseconds resumeWindow{std::chrono::seconds(10)};
    };

    enum class MediaSlot { None, Video, Audio };

    struct Session {
        protocol::Id16 sessionId{};
        SenderInfo sender;
        protocol::Key32 attachKey{};
        protocol::Key32 resumeToken{};
        std::optional<ConnId> control;
        std::optional<ConnId> video;
        std::optional<ConnId> audio;
        /// CONTROL koptuysa ne zaman (RESUME penceresi başlangıcı).
        std::optional<Clock::time_point> controlLostAt;
    };

    struct Pending {
        ConnId conn = 0;
        protocol::Hello hello;
        Clock::time_point deadline;
    };

    SessionManager(PairingManager& pairing, ReceiverInfo info) : SessionManager(pairing, std::move(info), Policy{}) {}
    SessionManager(PairingManager& pairing, ReceiverInfo info, Policy policy);

    void setReceiverInfo(ReceiverInfo info) { m_info = std::move(info); }
    const ReceiverInfo& receiverInfo() const { return m_info; }
    const Policy& policy() const { return m_policy; }

    /// Etkin oturum (bağlı veya RESUME penceresinde).
    const std::optional<Session>& session() const { return m_session; }
    /// Tahtada onay bekleyen ikinci gönderici.
    const std::optional<Pending>& pending() const { return m_pending; }
    /// Bu bağlantı etkin oturumun hangi medya akışı?
    MediaSlot mediaSlotOf(ConnId conn) const;
    bool isControl(ConnId conn) const { return m_session && m_session->control == conn; }

    /// Yeni CONTROL bağlantısındaki HELLO.
    Actions onHello(ConnId conn, const protocol::Hello& hello, const protocol::Id16& serverNonce,
                    const protocol::Key32& certFingerprint, Clock::time_point now);
    /// Tahtadaki devralma diyaloğunun sonucu.
    Actions resolvePending(bool accept);
    /// Yeni bağlantıdaki ATTACH.
    Actions onAttach(ConnId conn, const protocol::Attach& attach, const protocol::Id16& serverNonce);
    /// Yeni CONTROL bağlantısındaki RESUME.
    Actions onResume(ConnId conn, const protocol::Resume& resume, const protocol::Id16& serverNonce);
    /// Oturum CONTROL bağlantısından gelen BYE.
    Actions onBye(ConnId conn);
    /// Bir bağlantı kapandı (uzak taraf, zaman aşımı veya hata).
    Actions onConnectionClosed(ConnId conn, Clock::time_point now);
    /// Tahta tarafından oturumu bitirir (kullanıcı durdurdu / uygulama kapanıyor).
    Actions endSession(std::uint8_t byeReason);
    /// Zaman aşımlarını işler: onay süresi, RESUME penceresi.
    Actions tick(Clock::time_point now);

private:
    Actions startSession(ConnId conn, const protocol::Hello& hello, bool authenticatedByPairKey);
    Actions closeSession(std::optional<std::uint8_t> byeReason);
    protocol::Welcome makeWelcome(const Session& s) const;
    static protocol::Reject reject(std::uint8_t reason, std::optional<std::chrono::milliseconds> retry = {});

    PairingManager& m_pairing;
    ReceiverInfo m_info;
    Policy m_policy;
    std::optional<Session> m_session;
    std::optional<Pending> m_pending;
};

SenderInfo senderInfoFrom(const protocol::Hello& hello);

} // namespace yansit::session
