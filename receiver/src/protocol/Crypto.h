#pragma once

#include "protocol/Bytes.h"

#include <initializer_list>

namespace yansit::protocol {

Key32 sha256(ByteView data);

/// HMAC-SHA256(key, parts[0] || parts[1] || ...).
Key32 hmacSha256(ByteView key, std::initializer_list<ByteView> parts);

/// Sabit zamanlı karşılaştırma (uzunluklar farklıysa false).
bool constantTimeEqual(ByteView a, ByteView b);

/// Kriptografik rastgele baytlar (OpenSSL RAND_bytes). Başarısızlıkta std::runtime_error.
void randomBytes(std::span<std::uint8_t> out);

template <std::size_t N>
std::array<std::uint8_t, N> randomArray() {
    std::array<std::uint8_t, N> a{};
    randomBytes(a);
    return a;
}

// Kimlik kanıtları (PROTOCOL.md §7.1.1).
Key32 authProof(ByteView secret, std::uint8_t authMethod, const Id16& serverNonce, const Id16& clientNonce,
                const Key32& receiverCertFp, const Id16& deviceId);
Key32 attachProof(const Key32& attachKey, const Id16& sessionId, std::uint8_t streamKind, const Id16& serverNonce);
Key32 resumeProof(const Key32& resumeToken, const Id16& sessionId, const Id16& serverNonce);

} // namespace yansit::protocol
