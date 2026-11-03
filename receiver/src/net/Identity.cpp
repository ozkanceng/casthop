#include "net/Identity.h"

#include "protocol/Crypto.h"

#include <openssl/bio.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <fstream>
#include <memory>
#include <sstream>
#include <stdexcept>

namespace yansit::net {

namespace {

struct Free {
    void operator()(EVP_PKEY* p) const { EVP_PKEY_free(p); }
    void operator()(X509* p) const { X509_free(p); }
    void operator()(BIO* p) const { BIO_free(p); }
    void operator()(BIGNUM* p) const { BN_free(p); }
    void operator()(X509_EXTENSION* p) const { X509_EXTENSION_free(p); }
};
template <typename T>
using Ptr = std::unique_ptr<T, Free>;

[[noreturn]] void fail(const char* what) { throw std::runtime_error(std::string("TLS kimliği: ") + what); }

void check(int ok, const char* what) {
    if (ok != 1)
        fail(what);
}

std::string bioToString(BIO* bio) {
    char* data = nullptr;
    const long len = BIO_get_mem_data(bio, &data);
    return std::string(data, std::size_t(len));
}

Ptr<BIO> memBio(const std::string& s) {
    Ptr<BIO> bio(BIO_new_mem_buf(s.data(), int(s.size())));
    if (!bio)
        fail("BIO");
    return bio;
}

Ptr<X509> readCert(const std::string& pem) {
    auto bio = memBio(pem);
    Ptr<X509> cert(PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr));
    if (!cert)
        fail("sertifika okunamadı");
    return cert;
}

protocol::Key32 fingerprintOf(X509* cert) {
    unsigned char* der = nullptr;
    const int len = i2d_X509(cert, &der);
    if (len <= 0)
        fail("DER kodlama");
    const auto fp = protocol::sha256(protocol::ByteView(der, std::size_t(len)));
    OPENSSL_free(der);
    return fp;
}

void addExtension(X509* cert, int nid, const char* value) {
    X509V3_CTX ctx;
    X509V3_set_ctx_nodb(&ctx);
    X509V3_set_ctx(&ctx, cert, cert, nullptr, nullptr, 0);
    Ptr<X509_EXTENSION> ext(X509V3_EXT_conf_nid(nullptr, &ctx, nid, value));
    if (!ext)
        fail("uzantı");
    check(X509_add_ext(cert, ext.get(), -1), "uzantı ekleme");
}

std::string readFile(const std::filesystem::path& p) {
    std::ifstream in(p, std::ios::binary);
    if (!in)
        return {};
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

/// Geçici dosyaya verilen izinle yazar ve atomik olarak yerine taşır.
void writeFileAtomic(const std::filesystem::path& p, const std::string& data, mode_t mode) {
    const std::filesystem::path tmp = p.string() + ".tmp";
    ::unlink(tmp.c_str());
    const int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode);
    if (fd < 0)
        throw std::runtime_error("yazılamadı: " + tmp.string() + ": " + std::strerror(errno));
    std::size_t off = 0;
    while (off < data.size()) {
        const ssize_t n = ::write(fd, data.data() + off, data.size() - off);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            const int err = errno;
            ::close(fd);
            ::unlink(tmp.c_str());
            throw std::runtime_error("yazılamadı: " + tmp.string() + ": " + std::strerror(err));
        }
        off += std::size_t(n);
    }
    if (::fsync(fd) != 0 || ::close(fd) != 0) {
        ::unlink(tmp.c_str());
        throw std::runtime_error("yazılamadı: " + tmp.string());
    }
    std::filesystem::rename(tmp, p);
}

} // namespace

Identity generateIdentity(const std::string& commonName) {
    Ptr<EVP_PKEY> key(EVP_EC_gen("P-256"));
    if (!key)
        fail("anahtar üretimi");

    Ptr<X509> cert(X509_new());
    if (!cert)
        fail("X509_new");
    check(X509_set_version(cert.get(), X509_VERSION_3), "sürüm");

    // 127 bit pozitif rastgele seri numarası.
    auto serialBytes = protocol::randomArray<16>();
    serialBytes[0] &= 0x7F;
    Ptr<BIGNUM> bn(BN_bin2bn(serialBytes.data(), int(serialBytes.size()), nullptr));
    if (!bn || !BN_to_ASN1_INTEGER(bn.get(), X509_get_serialNumber(cert.get())))
        fail("seri numarası");

    // Saat farkına tolerans için bir gün geriden başlar.
    if (!X509_gmtime_adj(X509_getm_notBefore(cert.get()), -24L * 3600) ||
        !X509_time_adj_ex(X509_getm_notAfter(cert.get()), 3650, 0, nullptr))
        fail("geçerlilik süresi");

    X509_NAME* name = X509_get_subject_name(cert.get());
    check(X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_UTF8,
                                     reinterpret_cast<const unsigned char*>(commonName.c_str()), -1, -1, 0),
          "CN");
    check(X509_set_issuer_name(cert.get(), name), "issuer");
    check(X509_set_pubkey(cert.get(), key.get()), "pubkey");

    addExtension(cert.get(), NID_basic_constraints, "critical,CA:FALSE");
    addExtension(cert.get(), NID_key_usage, "critical,digitalSignature");
    addExtension(cert.get(), NID_ext_key_usage, "serverAuth");
    addExtension(cert.get(), NID_subject_key_identifier, "hash");

    if (X509_sign(cert.get(), key.get(), EVP_sha256()) <= 0)
        fail("imzalama");

    Ptr<BIO> certBio(BIO_new(BIO_s_mem()));
    Ptr<BIO> keyBio(BIO_new(BIO_s_mem()));
    if (!certBio || !keyBio)
        fail("BIO");
    check(PEM_write_bio_X509(certBio.get(), cert.get()), "sertifika PEM");
    check(PEM_write_bio_PrivateKey(keyBio.get(), key.get(), nullptr, nullptr, 0, nullptr, nullptr), "anahtar PEM");

    Identity id;
    id.certificatePem = bioToString(certBio.get());
    id.privateKeyPem = bioToString(keyBio.get());
    id.fingerprint = fingerprintOf(cert.get());
    return id;
}

protocol::Key32 certificateFingerprint(const std::string& certificatePem) {
    auto cert = readCert(certificatePem);
    return fingerprintOf(cert.get());
}

Identity loadOrCreateIdentity(const std::filesystem::path& dir, const std::string& commonName) {
    const auto certPath = dir / "cert.pem";
    const auto keyPath = dir / "key.pem";

    Identity id;
    id.certificatePem = readFile(certPath);
    id.privateKeyPem = readFile(keyPath);
    if (!id.certificatePem.empty() && !id.privateKeyPem.empty()) {
        auto certBio = memBio(id.certificatePem);
        auto keyBio = memBio(id.privateKeyPem);
        Ptr<X509> cert(PEM_read_bio_X509(certBio.get(), nullptr, nullptr, nullptr));
        Ptr<EVP_PKEY> key(PEM_read_bio_PrivateKey(keyBio.get(), nullptr, nullptr, nullptr));
        // Süresi dolmuş sertifika yenilenir (eşleştirmeler yeniden yapılmalı).
        if (cert && key && X509_check_private_key(cert.get(), key.get()) == 1 &&
            X509_cmp_current_time(X509_get0_notAfter(cert.get())) > 0) {
            id.fingerprint = fingerprintOf(cert.get());
            return id;
        }
    }

    std::filesystem::create_directories(dir);
    std::filesystem::permissions(dir, std::filesystem::perms::owner_all, std::filesystem::perm_options::replace);
    id = generateIdentity(commonName);
    // Önce anahtar: sertifika yazılmadan çökme olursa bir sonraki açılışta eşleşmeyen çift yeniden üretilir.
    writeFileAtomic(keyPath, id.privateKeyPem, 0600);
    writeFileAtomic(certPath, id.certificatePem, 0644);
    return id;
}

} // namespace yansit::net
