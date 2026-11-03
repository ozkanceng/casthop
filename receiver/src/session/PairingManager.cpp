#include "session/PairingManager.h"

#include "protocol/Crypto.h"

#include <algorithm>
#include <cstdio>

namespace yansit::session {

using namespace protocol;

std::string generatePin() {
    // Ret örneklemesi: 2^32'nin 10^6'ya tam bölünen en büyük katının altındaki değerler kabul edilir.
    constexpr std::uint32_t range = 1000000;
    constexpr std::uint32_t limit = 0xFFFFFFFFu - (0xFFFFFFFFu % range);
    std::uint32_t v = 0;
    do {
        auto b = randomArray<4>();
        v = getU32(b.data());
    } while (v >= limit);
    char buf[8];
    std::snprintf(buf, sizeof buf, "%06u", unsigned(v % range));
    return buf;
}

PairingManager::PairingManager(Policy policy, Clock::time_point now) : m_policy(policy) {
    m_token = randomArray<16>();
    m_tokenIssued = now;
    m_pin = generatePin();
}

void PairingManager::notifyCredentials() {
    if (onCredentialsChanged)
        onCredentialsChanged();
}

void PairingManager::rotateToken(Clock::time_point now) {
    m_token = randomArray<16>();
    m_tokenIssued = now;
}

void PairingManager::rotatePin() {
    m_pin = generatePin();
    m_pinFailures = 0;
}

bool PairingManager::tick(Clock::time_point now) {
    if (now - m_tokenIssued >= m_policy.tokenLifetime) {
        rotateToken(now);
        notifyCredentials();
        return true;
    }
    return false;
}

PairingManager::Verdict PairingManager::verifyHello(const Hello& hello, const Id16& serverNonce,
                                                    const Key32& certFingerprint, Clock::time_point now) {
    while (!m_recentHellos.empty() && now - m_recentHellos.front() >= m_policy.helloWindow)
        m_recentHellos.pop_front();
    if (int(m_recentHellos.size()) >= m_policy.maxHellosPerWindow) {
        const auto retry = std::chrono::duration_cast<std::chrono::milliseconds>(m_recentHellos.front() +
                                                                                 m_policy.helloWindow - now);
        return {Outcome::RateLimited, retry, std::nullopt};
    }
    m_recentHellos.push_back(now);

    const auto expected = [&](ByteView secret) {
        return authProof(secret, hello.authMethod, serverNonce, hello.clientNonce, certFingerprint, hello.deviceId);
    };

    switch (hello.authMethod) {
    case AuthMethod::QrToken: {
        if (!constantTimeEqual(expected(m_token), hello.authProof))
            return {Outcome::AuthFailed, std::nullopt, std::nullopt};
        rotateToken(now);
        notifyCredentials();
        return {Outcome::Accepted, std::nullopt, std::nullopt};
    }
    case AuthMethod::Pin: {
        if (m_pinLockedUntil && now < *m_pinLockedUntil) {
            return {Outcome::LockedOut,
                    std::chrono::duration_cast<std::chrono::milliseconds>(*m_pinLockedUntil - now), std::nullopt};
        }
        m_pinLockedUntil.reset();
        const ByteView pinBytes(reinterpret_cast<const std::uint8_t*>(m_pin.data()), m_pin.size());
        if (!constantTimeEqual(expected(pinBytes), hello.authProof)) {
            if (++m_pinFailures >= m_policy.maxPinFailures) {
                rotatePin();
                m_pinLockedUntil = now + m_policy.pinLockout;
                notifyCredentials();
                return {Outcome::LockedOut, m_policy.pinLockout, std::nullopt};
            }
            return {Outcome::AuthFailed, std::nullopt, std::nullopt};
        }
        rotatePin();
        notifyCredentials();
        return {Outcome::Accepted, std::nullopt, std::nullopt};
    }
    case AuthMethod::Paired: {
        if (!hello.keyId)
            return {Outcome::AuthFailed, std::nullopt, std::nullopt};
        const auto it = std::find_if(m_trusted.begin(), m_trusted.end(),
                                     [&](const TrustedSender& t) { return t.keyId == *hello.keyId; });
        if (it == m_trusted.end() || it->deviceId != hello.deviceId ||
            !constantTimeEqual(expected(it->pairKey), hello.authProof))
            return {Outcome::AuthFailed, std::nullopt, std::nullopt};
        if (it->deviceName != hello.deviceName) {
            it->deviceName = hello.deviceName;
            if (onTrustedChanged)
                onTrustedChanged();
        }
        return {Outcome::Accepted, std::nullopt, *it};
    }
    default:
        return {Outcome::UnsupportedMethod, std::nullopt, std::nullopt};
    }
}

TrustedSender PairingManager::createPairing(const Hello& hello) {
    // Aynı cihazın eski eşleştirmeleri silinir: cihaz başına tek anahtar.
    std::erase_if(m_trusted, [&](const TrustedSender& t) { return t.deviceId == hello.deviceId; });
    TrustedSender t{randomArray<16>(), randomArray<32>(), hello.deviceId, hello.deviceName};
    m_trusted.push_back(t);
    if (onTrustedChanged)
        onTrustedChanged();
    return t;
}

bool PairingManager::removeTrusted(const Id16& keyId) {
    const auto n = std::erase_if(m_trusted, [&](const TrustedSender& t) { return t.keyId == keyId; });
    if (n > 0 && onTrustedChanged)
        onTrustedChanged();
    return n > 0;
}

} // namespace yansit::session
