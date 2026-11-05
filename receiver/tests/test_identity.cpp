#include "net/Identity.h"
#include "net/SecureFile.h"

#include <gtest/gtest.h>

#ifndef _WIN32
#include <sys/stat.h>
#endif

#include <fstream>
#include <iterator>
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
    EXPECT_TRUE(isPrivateToUser(dir.path / "id" / "key.pem"));
    EXPECT_TRUE(isPrivateToUser(dir.path / "id"));
#ifndef _WIN32
    struct stat st {};
    ASSERT_EQ(::stat((dir.path / "id" / "key.pem").c_str(), &st), 0);
    EXPECT_EQ(st.st_mode & 0777, 0600u);
    ASSERT_EQ(::stat((dir.path / "id").c_str(), &st), 0);
    EXPECT_EQ(st.st_mode & 0777, 0700u);
#endif

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

TEST(SecureFile, AtomicWriteReplacesContentAndKeepsAccess) {
    TempDir dir;
    createPrivateDirectory(dir.path);
    const fs::path file = dir.path / "secret.json";
    writeFileAtomic(file, "first", FileAccess::Private);
    writeFileAtomic(file, "second", FileAccess::Private);
    EXPECT_TRUE(isPrivateToUser(file));
    std::ifstream in(file, std::ios::binary);
    std::string content((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    EXPECT_EQ(content, "second");
    EXPECT_FALSE(fs::exists(dir.path / "secret.json.tmp"));

    const fs::path shared = dir.path / "cert.pem";
    writeFileAtomic(shared, "cert", FileAccess::Shared);
    EXPECT_FALSE(isPrivateToUser(shared));
}
