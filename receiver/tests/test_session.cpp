#include "protocol/Crypto.h"
#include "session/SessionManager.h"

#include <algorithm>
#include <gtest/gtest.h>

using namespace yansit::protocol;
using namespace yansit::session;
using namespace std::chrono_literals;

namespace {

using Clock = SessionManager::Clock;
const Clock::time_point T0{};

const Id16 kNonce{5, 5, 5};
Key32 certFp() { return sha256(Bytes{'f', 'p'}); }

Hello hello(std::uint8_t method, ByteView secret, Id16 deviceId, std::optional<Id16> keyId = std::nullopt,
            bool requestPairing = false) {
    Hello h;
    h.deviceName = "Tablet";
    h.deviceId = deviceId;
    h.appVersion = "0.1.0";
    h.authMethod = method;
    h.clientNonce = Id16{1};
    h.keyId = keyId;
    h.videoCodecs = {Codec::H264};
    h.maxWidth = 1920;
    h.maxHeight = 1080;
    h.maxFps = 60;
    if (requestPairing)
        h.requestPairing = true;
    h.authProof = authProof(secret, method, kNonce, h.clientNonce, certFp(), deviceId);
    return h;
}

ReceiverInfo info(bool audio = false) {
    ReceiverInfo i;
    i.name = "Tahta";
    i.videoDecoders = {{Codec::H264, CodecSupport::Hardware | CodecSupport::Preferred}};
    i.audioEnabled = audio;
    return i;
}

template <typename T>
const T* sentTo(const Actions& a, ConnId conn) {
    for (const auto& [c, m] : a.send)
        if (c == conn)
            if (const T* p = std::get_if<T>(&m))
                return p;
    return nullptr;
}

bool closes(const Actions& a, ConnId conn) {
    return std::find(a.close.begin(), a.close.end(), conn) != a.close.end();
}

ByteView pinBytes(const std::string& pin) { return {reinterpret_cast<const std::uint8_t*>(pin.data()), pin.size()}; }

struct Fixture : ::testing::Test {
    PairingManager pairing{T0};
    SessionManager sm{pairing, info(), SessionManager::Policy{20s, 10s}};

    /// QR ile oturum açar, WELCOME'u döndürür.
    Welcome open(ConnId conn, Id16 device = Id16{0xA}, bool requestPairing = false) {
        const Actions a = sm.onHello(conn, hello(AuthMethod::QrToken, pairing.qrToken(), device, {}, requestPairing),
                                     kNonce, certFp(), T0);
        const Welcome* w = sentTo<Welcome>(a, conn);
        EXPECT_NE(w, nullptr);
        EXPECT_TRUE(a.close.empty());
        return w ? *w : Welcome{};
    }

    Actions attach(ConnId conn, const Welcome& w, std::uint8_t kind) {
        Attach at;
        at.sessionId = w.sessionId;
        at.streamKind = kind;
        at.proof = attachProof(w.attachKey, w.sessionId, kind, kNonce);
        return sm.onAttach(conn, at, kNonce);
    }
};

} // namespace

TEST_F(Fixture, QrHelloStartsSessionWithFreshKeys) {
    const Welcome w = open(1);
    EXPECT_EQ(w.receiverName, "Tahta");
    EXPECT_EQ(w.videoDecoders.size(), 1u);
    EXPECT_FALSE(w.pairKey.has_value());
    ASSERT_TRUE(sm.session());
    EXPECT_EQ(sm.session()->sessionId, w.sessionId);
    EXPECT_TRUE(sm.isControl(1));
    EXPECT_NE(w.attachKey, w.resumeToken);
}

TEST_F(Fixture, BadProofIsRejectedAndClosed) {
    Hello h = hello(AuthMethod::QrToken, pairing.qrToken(), Id16{0xA});
    h.authProof[0] ^= 1;
    const Actions a = sm.onHello(1, h, kNonce, certFp(), T0);
    const Reject* r = sentTo<Reject>(a, 1);
    ASSERT_NE(r, nullptr);
    EXPECT_EQ(r->reason, RejectReason::AuthFailed);
    EXPECT_TRUE(closes(a, 1));
    EXPECT_FALSE(sm.session());
}

TEST_F(Fixture, UnsupportedVersionIsRejected) {
    Hello h = hello(AuthMethod::QrToken, pairing.qrToken(), Id16{0xA});
    h.version = 2;
    const Actions a = sm.onHello(1, h, kNonce, certFp(), T0);
    ASSERT_NE(sentTo<Reject>(a, 1), nullptr);
    EXPECT_EQ(sentTo<Reject>(a, 1)->reason, RejectReason::VersionUnsupported);
}

TEST_F(Fixture, PinLockoutCarriesRetryAfter) {
    for (int i = 0; i < 4; ++i) {
        const Actions a = sm.onHello(i + 1, hello(AuthMethod::Pin, pinBytes("000000x"), Id16{0xA}), kNonce, certFp(),
                                     T0 + std::chrono::seconds(i * 3));
        EXPECT_EQ(sentTo<Reject>(a, i + 1)->reason, RejectReason::AuthFailed);
    }
    const Actions a = sm.onHello(9, hello(AuthMethod::Pin, pinBytes("000000x"), Id16{0xA}), kNonce, certFp(), T0 + 15s);
    const Reject* r = sentTo<Reject>(a, 9);
    ASSERT_NE(r, nullptr);
    EXPECT_EQ(r->reason, RejectReason::LockedOut);
    EXPECT_EQ(r->retryAfterMs, 30000u);
}

TEST_F(Fixture, SecondSenderWaitsForApprovalAndTimesOut) {
    open(1);
    const Actions a = sm.onHello(2, hello(AuthMethod::Pin, pinBytes(pairing.pin()), Id16{0xB}), kNonce, certFp(), T0);
    const PendingApproval* p = sentTo<PendingApproval>(a, 2);
    ASSERT_NE(p, nullptr);
    EXPECT_EQ(p->timeoutMs, 20000u);
    EXPECT_FALSE(closes(a, 2));
    ASSERT_TRUE(sm.pending());
    EXPECT_EQ(sm.pending()->hello.deviceId, (Id16{0xB}));

    // Üçüncü gönderici: biri zaten bekliyor.
    const Actions third =
        sm.onHello(3, hello(AuthMethod::QrToken, pairing.qrToken(), Id16{0xC}), kNonce, certFp(), T0 + 1s);
    EXPECT_EQ(sentTo<Reject>(third, 3)->reason, RejectReason::BusyDenied);

    EXPECT_TRUE(sm.tick(T0 + 19s).empty());
    const Actions t = sm.tick(T0 + 20s);
    EXPECT_EQ(sentTo<Reject>(t, 2)->reason, RejectReason::BusyTimeout);
    EXPECT_TRUE(closes(t, 2));
    EXPECT_FALSE(sm.pending());
    EXPECT_TRUE(sm.isControl(1));
}

TEST_F(Fixture, ApprovedTakeoverEndsOldSession) {
    const Welcome old = open(1);
    attach(10, old, StreamKind::Video);
    sm.onHello(2, hello(AuthMethod::Pin, pinBytes(pairing.pin()), Id16{0xB}), kNonce, certFp(), T0);

    const Actions a = sm.resolvePending(true);
    const Bye* bye = sentTo<Bye>(a, 1);
    ASSERT_NE(bye, nullptr);
    EXPECT_EQ(bye->reason, ByeReason::Takeover);
    EXPECT_TRUE(closes(a, 1));
    EXPECT_TRUE(closes(a, 10));
    const Welcome* w = sentTo<Welcome>(a, 2);
    ASSERT_NE(w, nullptr);
    EXPECT_NE(w->sessionId, old.sessionId);
    EXPECT_TRUE(sm.isControl(2));
    EXPECT_EQ(sm.session()->sender.deviceId, (Id16{0xB}));
}

TEST_F(Fixture, DeniedTakeoverKeepsOldSession) {
    open(1);
    sm.onHello(2, hello(AuthMethod::Pin, pinBytes(pairing.pin()), Id16{0xB}), kNonce, certFp(), T0);
    const Actions a = sm.resolvePending(false);
    EXPECT_EQ(sentTo<Reject>(a, 2)->reason, RejectReason::BusyDenied);
    EXPECT_TRUE(closes(a, 2));
    EXPECT_FALSE(closes(a, 1));
    EXPECT_TRUE(sm.isControl(1));
}

TEST_F(Fixture, PairedSameDeviceTakesOverWithoutApproval) {
    const Welcome first = open(1, Id16{0xA}, /*requestPairing=*/true);
    ASSERT_TRUE(first.pairKey && first.keyId);
    ASSERT_EQ(pairing.trustedSenders().size(), 1u);

    const Actions a = sm.onHello(2, hello(AuthMethod::Paired, *first.pairKey, Id16{0xA}, first.keyId, true), kNonce,
                                 certFp(), T0 + 1s);
    EXPECT_EQ(sentTo<Bye>(a, 1)->reason, ByeReason::Takeover);
    const Welcome* w = sentTo<Welcome>(a, 2);
    ASSERT_NE(w, nullptr);
    // Eşleştirme anahtarıyla gelen bir istek yeni anahtar üretmez.
    EXPECT_FALSE(w->pairKey.has_value());
    EXPECT_EQ(pairing.trustedSenders().size(), 1u);
}

TEST_F(Fixture, PairedOtherDeviceStillNeedsApproval) {
    const Welcome first = open(1, Id16{0xA}, true);
    sm.endSession(ByeReason::ReceiverClosing);
    open(5, Id16{0xB});
    const Actions a = sm.onHello(2, hello(AuthMethod::Paired, *first.pairKey, Id16{0xA}, first.keyId), kNonce, certFp(),
                                 T0 + 1s);
    EXPECT_NE(sentTo<PendingApproval>(a, 2), nullptr);
}

TEST_F(Fixture, AttachValidatesProofAndKind) {
    const Welcome w = open(1);
    Attach bad;
    bad.sessionId = w.sessionId;
    bad.streamKind = StreamKind::Video;
    bad.proof = attachProof(w.attachKey, w.sessionId, StreamKind::Video, Id16{9});
    Actions a = sm.onAttach(10, bad, kNonce);
    EXPECT_EQ(sentTo<AttachResult>(a, 10)->status, RejectReason::AuthFailed);
    EXPECT_TRUE(closes(a, 10));

    a = attach(11, w, StreamKind::Audio); // ses kapalı
    EXPECT_EQ(sentTo<AttachResult>(a, 11)->status, RejectReason::Malformed);

    a = attach(12, w, StreamKind::Video);
    EXPECT_EQ(sentTo<AttachResult>(a, 12)->status, 0);
    EXPECT_TRUE(a.close.empty());
    EXPECT_EQ(sm.mediaSlotOf(12), SessionManager::MediaSlot::Video);

    // Aynı tür için ikinci bağlantı eskisinin yerini alır.
    a = attach(13, w, StreamKind::Video);
    EXPECT_TRUE(closes(a, 12));
    EXPECT_EQ(sm.mediaSlotOf(13), SessionManager::MediaSlot::Video);
    EXPECT_EQ(sm.mediaSlotOf(12), SessionManager::MediaSlot::None);
}

TEST_F(Fixture, AttachToUnknownSessionFails) {
    Welcome w = open(1);
    w.sessionId[0] ^= 1;
    const Actions a = attach(10, w, StreamKind::Video);
    EXPECT_EQ(sentTo<AttachResult>(a, 10)->status, RejectReason::SessionUnknown);
}

TEST_F(Fixture, ControlLossClosesMediaAndResumeRestores) {
    const Welcome w = open(1);
    attach(10, w, StreamKind::Video);

    Actions a = sm.onConnectionClosed(1, T0 + 1s);
    EXPECT_TRUE(closes(a, 10));
    ASSERT_TRUE(sm.session());
    EXPECT_FALSE(sm.session()->control);

    // RESUME penceresinde eski attach_key ile ATTACH reddedilir.
    EXPECT_EQ(sentTo<AttachResult>(attach(11, w, StreamKind::Video), 11)->status, RejectReason::SessionUnknown);

    Resume r;
    r.sessionId = w.sessionId;
    r.proof = resumeProof(w.resumeToken, w.sessionId, kNonce);
    a = sm.onResume(2, r, kNonce);
    const Welcome* w2 = sentTo<Welcome>(a, 2);
    ASSERT_NE(w2, nullptr);
    EXPECT_EQ(w2->sessionId, w.sessionId);
    EXPECT_NE(w2->resumeToken, w.resumeToken);
    EXPECT_NE(w2->attachKey, w.attachKey);
    EXPECT_TRUE(sm.isControl(2));

    // Eski resume token tek kullanımlık.
    EXPECT_EQ(sentTo<Reject>(sm.onResume(3, r, kNonce), 3)->reason, RejectReason::SessionUnknown);

    EXPECT_EQ(sentTo<AttachResult>(attach(12, *w2, StreamKind::Video), 12)->status, 0);
    EXPECT_TRUE(sm.tick(T0 + 60s).empty());
}

TEST_F(Fixture, ResumeWhileOldControlStillOpenReplacesIt) {
    const Welcome w = open(1);
    attach(10, w, StreamKind::Video);
    Resume r;
    r.sessionId = w.sessionId;
    r.proof = resumeProof(w.resumeToken, w.sessionId, kNonce);
    const Actions a = sm.onResume(2, r, kNonce);
    EXPECT_TRUE(closes(a, 1));
    EXPECT_TRUE(closes(a, 10));
    EXPECT_NE(sentTo<Welcome>(a, 2), nullptr);
    // Ağ katmanı eski bağlantıların kapandığını bildirir; oturum etkilenmemeli.
    EXPECT_TRUE(sm.onConnectionClosed(1, T0).empty());
    EXPECT_TRUE(sm.isControl(2));
}

TEST_F(Fixture, ResumeWindowExpiryEndsSession) {
    open(1);
    sm.onConnectionClosed(1, T0);
    EXPECT_TRUE(sm.tick(T0 + 9s).empty());
    EXPECT_TRUE(sm.session());
    sm.tick(T0 + 10s);
    EXPECT_FALSE(sm.session());
}

TEST_F(Fixture, ByeFromSenderEndsSessionSilently) {
    const Welcome w = open(1);
    attach(10, w, StreamKind::Video);
    const Actions a = sm.onBye(1);
    EXPECT_TRUE(closes(a, 1));
    EXPECT_TRUE(closes(a, 10));
    EXPECT_EQ(sentTo<Bye>(a, 1), nullptr);
    EXPECT_FALSE(sm.session());
}

TEST_F(Fixture, ReceiverEndSessionSendsByeAndRejectsPending) {
    open(1);
    sm.onHello(2, hello(AuthMethod::Pin, pinBytes(pairing.pin()), Id16{0xB}), kNonce, certFp(), T0);
    const Actions a = sm.endSession(ByeReason::ReceiverClosing);
    EXPECT_EQ(sentTo<Bye>(a, 1)->reason, ByeReason::ReceiverClosing);
    EXPECT_EQ(sentTo<Reject>(a, 2)->reason, RejectReason::BusyDenied);
    EXPECT_FALSE(sm.session());
    EXPECT_FALSE(sm.pending());
}

TEST_F(Fixture, PendingSenderDisconnectClearsPending) {
    open(1);
    sm.onHello(2, hello(AuthMethod::Pin, pinBytes(pairing.pin()), Id16{0xB}), kNonce, certFp(), T0);
    sm.onConnectionClosed(2, T0 + 1s);
    EXPECT_FALSE(sm.pending());
    EXPECT_TRUE(sm.resolvePending(true).empty());
    EXPECT_TRUE(sm.isControl(1));
}
