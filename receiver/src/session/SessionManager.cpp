#include "session/SessionManager.h"

#include "protocol/Crypto.h"

#include <algorithm>

namespace yansit::session {

using namespace protocol;

void Actions::merge(Actions other) {
    for (auto& s : other.send)
        send.push_back(std::move(s));
    for (ConnId c : other.close)
        close.push_back(c);
}

SenderInfo senderInfoFrom(const Hello& hello) {
    SenderInfo s;
    s.deviceName = hello.deviceName;
    s.deviceId = hello.deviceId;
    s.appVersion = hello.appVersion;
    s.authMethod = hello.authMethod;
    s.videoCodecs = hello.videoCodecs;
    s.audioCodecs = hello.audioCodecs.value_or(std::vector<std::uint8_t>{});
    s.maxWidth = hello.maxWidth;
    s.maxHeight = hello.maxHeight;
    s.maxFps = hello.maxFps;
    return s;
}

SessionManager::SessionManager(PairingManager& pairing, ReceiverInfo info, Policy policy)
    : m_pairing(pairing), m_info(std::move(info)), m_policy(policy) {}

SessionManager::MediaSlot SessionManager::mediaSlotOf(ConnId conn) const {
    if (!m_session)
        return MediaSlot::None;
    if (m_session->video == conn)
        return MediaSlot::Video;
    if (m_session->audio == conn)
        return MediaSlot::Audio;
    return MediaSlot::None;
}

Reject SessionManager::reject(std::uint8_t reason, std::optional<std::chrono::milliseconds> retry) {
    Reject r;
    r.reason = reason;
    if (retry && retry->count() > 0)
        r.retryAfterMs = std::uint32_t(std::min<std::int64_t>(retry->count(), 0xFFFFFFFF));
    return r;
}

Welcome SessionManager::makeWelcome(const Session& s) const {
    Welcome w;
    w.version = 1;
    w.receiverName = m_info.name;
    w.sessionId = s.sessionId;
    w.displayWidth = m_info.displayWidth;
    w.displayHeight = m_info.displayHeight;
    w.displayRefresh = m_info.displayRefresh;
    w.videoDecoders = m_info.videoDecoders;
    w.audioDecoders = m_info.audioDecoders;
    w.attachKey = s.attachKey;
    w.resumeToken = s.resumeToken;
    w.audioEnabled = m_info.audioEnabled;
    w.rotatedFrames = m_info.rotatedFrames;
    return w;
}

Actions SessionManager::onHello(ConnId conn, const Hello& hello, const Id16& serverNonce,
                                const Key32& certFingerprint, Clock::time_point now) {
    Actions a;
    const auto refuse = [&](Reject r) {
        a.send.emplace_back(conn, r);
        a.close.push_back(conn);
        return a;
    };

    if (hello.version != 1)
        return refuse(reject(RejectReason::VersionUnsupported));
    // Zaten biri onay bekliyorsa kimlik bilgisini harcamadan reddet.
    if (m_pending)
        return refuse(reject(RejectReason::BusyDenied));

    const auto verdict = m_pairing.verifyHello(hello, serverNonce, certFingerprint, now);
    switch (verdict.outcome) {
    case PairingManager::Outcome::Accepted:
        break;
    case PairingManager::Outcome::RateLimited:
        return refuse(reject(RejectReason::RateLimited, verdict.retryAfter));
    case PairingManager::Outcome::LockedOut:
        return refuse(reject(RejectReason::LockedOut, verdict.retryAfter));
    case PairingManager::Outcome::AuthFailed:
    case PairingManager::Outcome::UnsupportedMethod:
        return refuse(reject(RejectReason::AuthFailed));
    }

    const bool byPairKey = verdict.trusted.has_value();
    if (m_session) {
        // Eşleştirilmiş aynı cihaz kendi eski oturumunu onaysız devralır (örn. uygulama yeniden başladı).
        if (byPairKey && m_session->sender.deviceId == hello.deviceId) {
            a = closeSession(ByeReason::Takeover);
            a.merge(startSession(conn, hello, byPairKey));
            return a;
        }
        m_pending = Pending{conn, hello, now + m_policy.approvalTimeout};
        PendingApproval p;
        p.timeoutMs = std::uint32_t(m_policy.approvalTimeout.count());
        a.send.emplace_back(conn, p);
        return a;
    }
    return startSession(conn, hello, byPairKey);
}

Actions SessionManager::startSession(ConnId conn, const Hello& hello, bool authenticatedByPairKey) {
    Session s;
    s.sessionId = randomArray<16>();
    s.sender = senderInfoFrom(hello);
    s.attachKey = randomArray<32>();
    s.resumeToken = randomArray<32>();
    s.control = conn;

    Welcome w = makeWelcome(s);
    if (hello.requestPairing.value_or(false) && !authenticatedByPairKey) {
        const TrustedSender t = m_pairing.createPairing(hello);
        w.pairKey = t.pairKey;
        w.keyId = t.keyId;
    }
    m_session = std::move(s);

    Actions a;
    a.send.emplace_back(conn, std::move(w));
    return a;
}

Actions SessionManager::closeSession(std::optional<std::uint8_t> byeReason) {
    Actions a;
    if (!m_session)
        return a;
    Session s = std::move(*m_session);
    m_session.reset();
    if (s.control) {
        if (byeReason) {
            Bye b;
            b.reason = *byeReason;
            a.send.emplace_back(*s.control, b);
        }
        a.close.push_back(*s.control);
    }
    if (s.video)
        a.close.push_back(*s.video);
    if (s.audio)
        a.close.push_back(*s.audio);
    return a;
}

Actions SessionManager::resolvePending(bool accept) {
    Actions a;
    if (!m_pending)
        return a;
    Pending p = std::move(*m_pending);
    m_pending.reset();
    if (!accept) {
        a.send.emplace_back(p.conn, reject(RejectReason::BusyDenied));
        a.close.push_back(p.conn);
        return a;
    }
    a = closeSession(ByeReason::Takeover);
    a.merge(startSession(p.conn, p.hello, p.hello.authMethod == AuthMethod::Paired));
    return a;
}

Actions SessionManager::onAttach(ConnId conn, const Attach& attach, const Id16& serverNonce) {
    Actions a;
    const auto result = [&](std::uint8_t status) {
        AttachResult r;
        r.status = status;
        a.send.emplace_back(conn, r);
        if (status != 0)
            a.close.push_back(conn);
        return a;
    };

    // Kontrol bağlantısı yokken (RESUME penceresi) eski attach_key geçersizdir: önce RESUME gelmeli.
    if (!m_session || m_session->sessionId != attach.sessionId || !m_session->control)
        return result(RejectReason::SessionUnknown);
    const Key32 expected = attachProof(m_session->attachKey, attach.sessionId, attach.streamKind, serverNonce);
    if (!constantTimeEqual(expected, attach.proof))
        return result(RejectReason::AuthFailed);

    std::optional<ConnId>* slot = nullptr;
    if (attach.streamKind == StreamKind::Video)
        slot = &m_session->video;
    else if (attach.streamKind == StreamKind::Audio && m_info.audioEnabled)
        slot = &m_session->audio;
    else
        return result(RejectReason::Malformed);

    if (*slot && **slot != conn)
        a.close.push_back(**slot);
    *slot = conn;
    return result(0);
}

Actions SessionManager::onResume(ConnId conn, const Resume& resume, const Id16& serverNonce) {
    Actions a;
    const bool known = m_session && m_session->sessionId == resume.sessionId &&
                       constantTimeEqual(resumeProof(m_session->resumeToken, resume.sessionId, serverNonce),
                                         resume.proof);
    if (!known) {
        a.send.emplace_back(conn, reject(RejectReason::SessionUnknown));
        a.close.push_back(conn);
        return a;
    }

    Session& s = *m_session;
    // Gönderici kopmayı alıcıdan önce fark etmiş olabilir: eski CONTROL hâlâ açıksa kapat.
    if (s.control && *s.control != conn)
        a.close.push_back(*s.control);
    // Medya bağlantıları yeni attach_key ile yeniden kurulur.
    if (s.video)
        a.close.push_back(*s.video);
    if (s.audio)
        a.close.push_back(*s.audio);
    s.video.reset();
    s.audio.reset();
    s.control = conn;
    s.controlLostAt.reset();
    s.attachKey = randomArray<32>();
    s.resumeToken = randomArray<32>();
    a.send.emplace_back(conn, makeWelcome(s));
    return a;
}

Actions SessionManager::onBye(ConnId conn) {
    if (isControl(conn))
        return closeSession(std::nullopt);
    Actions a;
    if (m_pending && m_pending->conn == conn)
        m_pending.reset();
    a.close.push_back(conn);
    return a;
}

Actions SessionManager::onConnectionClosed(ConnId conn, Clock::time_point now) {
    Actions a;
    if (m_pending && m_pending->conn == conn) {
        m_pending.reset();
        return a;
    }
    if (!m_session)
        return a;
    Session& s = *m_session;
    if (s.control == conn) {
        s.control.reset();
        s.controlLostAt = now;
        if (s.video)
            a.close.push_back(*s.video);
        if (s.audio)
            a.close.push_back(*s.audio);
        s.video.reset();
        s.audio.reset();
    } else if (s.video == conn) {
        s.video.reset();
    } else if (s.audio == conn) {
        s.audio.reset();
    }
    return a;
}

Actions SessionManager::endSession(std::uint8_t byeReason) {
    Actions a = closeSession(byeReason);
    if (m_pending) {
        a.send.emplace_back(m_pending->conn, reject(RejectReason::BusyDenied));
        a.close.push_back(m_pending->conn);
        m_pending.reset();
    }
    return a;
}

Actions SessionManager::tick(Clock::time_point now) {
    Actions a;
    if (m_pending && now >= m_pending->deadline) {
        a.send.emplace_back(m_pending->conn, reject(RejectReason::BusyTimeout));
        a.close.push_back(m_pending->conn);
        m_pending.reset();
    }
    if (m_session && m_session->controlLostAt && now - *m_session->controlLostAt >= m_policy.resumeWindow)
        a.merge(closeSession(std::nullopt));
    return a;
}

} // namespace yansit::session
