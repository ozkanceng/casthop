#include "protocol/Crypto.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <memory>
#include <stdexcept>
#include <string_view>

namespace yansit::protocol {

namespace {

ByteView label(std::string_view s) { return {reinterpret_cast<const std::uint8_t*>(s.data()), s.size()}; }

} // namespace

Key32 sha256(ByteView data) {
    Key32 out{};
    unsigned int len = 0;
    if (EVP_Digest(data.data(), data.size(), out.data(), &len, EVP_sha256(), nullptr) != 1 || len != out.size())
        throw std::runtime_error("SHA-256 başarısız");
    return out;
}

Key32 hmacSha256(ByteView key, std::initializer_list<ByteView> parts) {
    std::unique_ptr<EVP_MAC, decltype(&EVP_MAC_free)> mac(EVP_MAC_fetch(nullptr, "HMAC", nullptr), &EVP_MAC_free);
    if (!mac)
        throw std::runtime_error("HMAC bulunamadı");
    std::unique_ptr<EVP_MAC_CTX, decltype(&EVP_MAC_CTX_free)> ctx(EVP_MAC_CTX_new(mac.get()), &EVP_MAC_CTX_free);
    char digest[] = "SHA256";
    OSSL_PARAM params[] = {OSSL_PARAM_construct_utf8_string("digest", digest, 0), OSSL_PARAM_construct_end()};
    // Boş anahtar için geçerli bir işaretçi gerekir.
    static const std::uint8_t empty = 0;
    if (!ctx || EVP_MAC_init(ctx.get(), key.empty() ? &empty : key.data(), key.size(), params) != 1)
        throw std::runtime_error("HMAC başlatılamadı");
    for (const auto& p : parts) {
        if (!p.empty() && EVP_MAC_update(ctx.get(), p.data(), p.size()) != 1)
            throw std::runtime_error("HMAC güncellenemedi");
    }
    Key32 out{};
    std::size_t len = 0;
    if (EVP_MAC_final(ctx.get(), out.data(), &len, out.size()) != 1 || len != out.size())
        throw std::runtime_error("HMAC tamamlanamadı");
    return out;
}

bool constantTimeEqual(ByteView a, ByteView b) {
    return a.size() == b.size() && CRYPTO_memcmp(a.data(), b.data(), a.size()) == 0;
}

void randomBytes(std::span<std::uint8_t> out) {
    if (!out.empty() && RAND_bytes(out.data(), static_cast<int>(out.size())) != 1)
        throw std::runtime_error("RAND_bytes başarısız");
}

Key32 authProof(ByteView secret, std::uint8_t authMethod, const Id16& serverNonce, const Id16& clientNonce,
                const Key32& receiverCertFp, const Id16& deviceId) {
    const std::uint8_t method[] = {authMethod};
    return hmacSha256(secret, {label("yansit-auth-v1"), method, serverNonce, clientNonce, receiverCertFp, deviceId});
}

Key32 attachProof(const Key32& attachKey, const Id16& sessionId, std::uint8_t streamKind, const Id16& serverNonce) {
    const std::uint8_t kind[] = {streamKind};
    return hmacSha256(attachKey, {label("yansit-attach-v1"), sessionId, kind, serverNonce});
}

Key32 resumeProof(const Key32& resumeToken, const Id16& sessionId, const Id16& serverNonce) {
    return hmacSha256(resumeToken, {label("yansit-resume-v1"), sessionId, serverNonce});
}

} // namespace yansit::protocol
