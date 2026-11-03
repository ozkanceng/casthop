#include "protocol/Crypto.h"
#include "session/PairingManager.h"

#include <gtest/gtest.h>
#include <set>

using namespace yansit::protocol;
using yansit::session::generatePin;
using yansit::session::PairingManager;
using namespace std::chrono_literals;

namespace {

using Clock = PairingManager::Clock;
const Clock::time_point T0{};

Id16 serverNonce() { return Id16{1, 2, 3}; }
Key32 certFp() { return sha256(Bytes{'c', 'e', 'r', 't'}); }

Hello helloWith(std::uint8_t method, ByteView secret, std::optional<Id16> keyId = std::nullopt) {
    Hello h;
    h.deviceName = "Tablet";
    h.deviceId = Id16{9, 9, 9};
    h.appVersion = "0.1.0";
    h.authMethod = method;
    h.clientNonce = Id16{7, 7};
    h.keyId = keyId;
    h.videoCodecs = {Codec::H264};
    h.maxWidth = 1920;
    h.maxHeight = 1080;
    h.maxFps = 60;
    h.authProof = authProof(secret, method, serverNonce(), h.clientNonce, certFp(), h.deviceId);
    return h;
}

ByteView pinBytes(const std::string& pin) { return {reinterpret_cast<const std::uint8_t*>(pin.data()), pin.size()}; }

} // namespace

TEST(Pairing, QrTokenIsSingleUse) {
    PairingManager pm(T0);
    int changes = 0;
    pm.onCredentialsChanged = [&] { ++changes; };
    const Id16 token = pm.qrToken();
    const Hello h = helloWith(AuthMethod::QrToken, token);
    EXPECT_EQ(pm.verifyHello(h, serverNonce(), certFp(), T0).outcome, PairingManager::Outcome::Accepted);
    EXPECT_NE(pm.qrToken(), token);
    EXPECT_EQ(changes, 1);
    EXPECT_EQ(pm.verifyHello(h, serverNonce(), certFp(), T0 + 1s).outcome, PairingManager::Outcome::AuthFailed);
}

TEST(Pairing, ProofIsBoundToCertificateAndNonce) {
    PairingManager pm(T0);
    const Hello h = helloWith(AuthMethod::QrToken, pm.qrToken());
    Key32 otherFp = certFp();
    otherFp[0] ^= 1;
    EXPECT_EQ(pm.verifyHello(h, serverNonce(), otherFp, T0).outcome, PairingManager::Outcome::AuthFailed);
    EXPECT_EQ(pm.verifyHello(h, Id16{4}, certFp(), T0).outcome, PairingManager::Outcome::AuthFailed);
    EXPECT_EQ(pm.verifyHello(h, serverNonce(), certFp(), T0).outcome, PairingManager::Outcome::Accepted);
}

TEST(Pairing, TokenExpires) {
    PairingManager pm(T0);
    const Id16 token = pm.qrToken();
    EXPECT_FALSE(pm.tick(T0 + 9min));
    EXPECT_TRUE(pm.tick(T0 + 10min));
    EXPECT_NE(pm.qrToken(), token);
}

TEST(Pairing, PinLockoutAfterFiveFailures) {
    PairingManager pm(T0);
    const std::string pin = pm.pin();
    const std::string wrong = pin == "000000" ? "000001" : "000000";
    auto t = T0;
    for (int i = 0; i < 4; ++i) {
        t += 2s; // hız sınırına takılmamak için
        EXPECT_EQ(pm.verifyHello(helloWith(AuthMethod::Pin, pinBytes(wrong)), serverNonce(), certFp(), t).outcome,
                  PairingManager::Outcome::AuthFailed);
    }
    t += 2s;
    auto v = pm.verifyHello(helloWith(AuthMethod::Pin, pinBytes(wrong)), serverNonce(), certFp(), t);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::LockedOut);
    EXPECT_EQ(v.retryAfter, std::chrono::milliseconds(30s));
    EXPECT_NE(pm.pin(), pin) << "kilitlenince PIN yenilenmeli";

    // Kilit süresince doğru PIN bile reddedilir.
    const std::string fresh = pm.pin();
    v = pm.verifyHello(helloWith(AuthMethod::Pin, pinBytes(fresh)), serverNonce(), certFp(), t + 10s);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::LockedOut);
    EXPECT_EQ(v.retryAfter, std::chrono::milliseconds(20s));

    v = pm.verifyHello(helloWith(AuthMethod::Pin, pinBytes(fresh)), serverNonce(), certFp(), t + 31s);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::Accepted);
    EXPECT_NE(pm.pin(), fresh) << "PIN tek kullanımlık";
}

TEST(Pairing, GlobalRateLimit) {
    PairingManager pm(T0);
    const Hello bad = helloWith(AuthMethod::QrToken, Id16{});
    for (int i = 0; i < 10; ++i)
        EXPECT_EQ(pm.verifyHello(bad, serverNonce(), certFp(), T0 + i * 100ms).outcome,
                  PairingManager::Outcome::AuthFailed);
    auto v = pm.verifyHello(helloWith(AuthMethod::QrToken, pm.qrToken()), serverNonce(), certFp(), T0 + 1s);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::RateLimited);
    EXPECT_EQ(v.retryAfter, std::chrono::milliseconds(9s));
    v = pm.verifyHello(helloWith(AuthMethod::QrToken, pm.qrToken()), serverNonce(), certFp(), T0 + 10s);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::Accepted);
}

TEST(Pairing, PairedSenderReconnectsWithKey) {
    PairingManager pm(T0);
    int trustedChanges = 0;
    pm.onTrustedChanged = [&] { ++trustedChanges; };
    const Hello first = helloWith(AuthMethod::QrToken, pm.qrToken());
    ASSERT_EQ(pm.verifyHello(first, serverNonce(), certFp(), T0).outcome, PairingManager::Outcome::Accepted);
    const auto t = pm.createPairing(first);
    EXPECT_EQ(trustedChanges, 1);

    const Hello again = helloWith(AuthMethod::Paired, t.pairKey, t.keyId);
    const auto v = pm.verifyHello(again, serverNonce(), certFp(), T0 + 1s);
    EXPECT_EQ(v.outcome, PairingManager::Outcome::Accepted);
    ASSERT_TRUE(v.trusted.has_value());
    EXPECT_EQ(v.trusted->deviceId, first.deviceId);

    // Başka cihaz aynı anahtarı kullanamaz.
    Hello stolen = again;
    stolen.deviceId = Id16{1};
    stolen.authProof = authProof(t.pairKey, 3, serverNonce(), stolen.clientNonce, certFp(), stolen.deviceId);
    EXPECT_EQ(pm.verifyHello(stolen, serverNonce(), certFp(), T0 + 2s).outcome, PairingManager::Outcome::AuthFailed);

    // Yeniden eşleştirme eskisini değiştirir; silinen anahtar reddedilir.
    const auto t2 = pm.createPairing(first);
    EXPECT_EQ(pm.trustedSenders().size(), 1u);
    EXPECT_EQ(pm.verifyHello(again, serverNonce(), certFp(), T0 + 3s).outcome, PairingManager::Outcome::AuthFailed);
    EXPECT_TRUE(pm.removeTrusted(t2.keyId));
    EXPECT_TRUE(pm.trustedSenders().empty());
}

TEST(Pairing, UnsupportedMethod) {
    PairingManager pm(T0);
    EXPECT_EQ(pm.verifyHello(helloWith(9, Bytes{1}), serverNonce(), certFp(), T0).outcome,
              PairingManager::Outcome::UnsupportedMethod);
}

TEST(Pin, SixDigitsAndVaried) {
    std::set<std::string> seen;
    for (int i = 0; i < 200; ++i) {
        const auto p = generatePin();
        ASSERT_EQ(p.size(), 6u);
        for (char c : p)
            ASSERT_TRUE(c >= '0' && c <= '9');
        seen.insert(p);
    }
    EXPECT_GT(seen.size(), 190u);
}
