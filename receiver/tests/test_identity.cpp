#include "net/Identity.h"

#include <gtest/gtest.h>

#include <sys/stat.h>

#include <fstream>
#include <random>

using namespace yansit::net;
namespace fs = std::filesystem;

namespace {

struct TempDir {
    fs::path path;
    TempDir() {
        std::random_device rd;
        path = fs::temp_directory_path() / ("yansit-identity-" + std::to_string(rd()));
    }
    ~TempDir() {
        std::error_code ec;
        fs::remove_all(path, ec);
    }
};

void overwrite(const fs::path& p, const std::string& s) {
    std::ofstream out(p, std::ios::binary | std::ios::trunc);
    out << s;
}

} // namespace

TEST(Identity, GeneratedFingerprintMatchesPem) {
    const Identity id = generateIdentity("Yansıt test");
    EXPECT_NE(id.certificatePem.find("BEGIN CERTIFICATE"), std::string::npos);
    EXPECT_NE(id.privateKeyPem.find("BEGIN PRIVATE KEY"), std::string::npos);
    EXPECT_EQ(certificateFingerprint(id.certificatePem), id.fingerprint);
    EXPECT_NE(generateIdentity("Yansıt test").fingerprint, id.fingerprint);
}

TEST(Identity, InvalidPemThrows) { EXPECT_THROW(certificateFingerprint("not a cert"), std::runtime_error); }

TEST(Identity, PersistsAndReloads) {
    TempDir dir;
    const Identity first = loadOrCreateIdentity(dir.path / "id", "Tahta");
    struct stat st {};
    ASSERT_EQ(::stat((dir.path / "id" / "key.pem").c_str(), &st), 0);
    EXPECT_EQ(st.st_mode & 0777, 0600u);
    ASSERT_EQ(::stat((dir.path / "id").c_str(), &st), 0);
    EXPECT_EQ(st.st_mode & 0777, 0700u);

    const Identity again = loadOrCreateIdentity(dir.path / "id", "Tahta");
    EXPECT_EQ(again.fingerprint, first.fingerprint);
    EXPECT_EQ(again.privateKeyPem, first.privateKeyPem);
}

TEST(Identity, CorruptOrMismatchedFilesAreRegenerated) {
    TempDir dir;
    const Identity first = loadOrCreateIdentity(dir.path, "Tahta");

    overwrite(dir.path / "cert.pem", "garbage");
    const Identity second = loadOrCreateIdentity(dir.path, "Tahta");
    EXPECT_NE(second.fingerprint, first.fingerprint);
    EXPECT_EQ(certificateFingerprint(second.certificatePem), second.fingerprint);

    // Başka bir kimliğin anahtarı: sertifikayla eşleşmez.
    overwrite(dir.path / "key.pem", generateIdentity("x").privateKeyPem);
    const Identity third = loadOrCreateIdentity(dir.path, "Tahta");
    EXPECT_NE(third.fingerprint, second.fingerprint);
    EXPECT_EQ(loadOrCreateIdentity(dir.path, "Tahta").fingerprint, third.fingerprint);
}
