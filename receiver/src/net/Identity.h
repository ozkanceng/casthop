#pragma once

#include "protocol/Bytes.h"

#include <filesystem>
#include <string>

namespace yansit::net {

/// Alıcının kalıcı TLS kimliği (PROTOCOL.md §2): kendinden imzalı ECDSA P-256 sertifika, 10 yıl.
struct Identity {
    std::string certificatePem;
    std::string privateKeyPem;
    /// SHA-256(DER sertifika) — QR'daki `f` ve kimlik kanıtlarındaki `receiver_cert_fp`.
    protocol::Key32 fingerprint{};
};

/// Yeni anahtar çifti ve sertifika üretir. Başarısızlıkta std::runtime_error.
Identity generateIdentity(const std::string& commonName);

/// PEM sertifikanın parmak izi. Geçersiz PEM'de std::runtime_error.
protocol::Key32 certificateFingerprint(const std::string& certificatePem);

/**
 * `dir/cert.pem` + `dir/key.pem` varsa ve birbiriyle eşleşiyorsa yükler; yoksa veya bozuksa yenisini
 * üretip yazar (anahtar dosyası 0600). Dizin yoksa oluşturulur.
 */
Identity loadOrCreateIdentity(const std::filesystem::path& dir, const std::string& commonName);

} // namespace yansit::net
