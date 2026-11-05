#include "net/SecureFile.h"

#include <windows.h>

#include <aclapi.h>
#include <sddl.h>

#include <memory>
#include <stdexcept>
#include <vector>

namespace yansit::net {

namespace {

constexpr int kMoveAttempts = 10;
constexpr DWORD kMoveRetryMs = 50;

std::string utf8(const std::filesystem::path& path) {
    const std::u8string text = path.u8string();
    return std::string(reinterpret_cast<const char*>(text.data()), text.size());
}

[[noreturn]] void fail(const char* what, const std::filesystem::path& path, DWORD error) {
    throw std::runtime_error(std::string(what) + ": " + utf8(path) + ": Windows hatası " + std::to_string(error));
}

[[noreturn]] void fail(const char* what) {
    throw std::runtime_error(std::string(what) + ": Windows hatası " + std::to_string(GetLastError()));
}

struct LocalFreeDeleter {
    void operator()(void* p) const { LocalFree(p); }
};

struct HandleCloser {
    void operator()(void* h) const { CloseHandle(h); }
};

/// TOKEN_USER of this process. The SID inside stays valid as long as the buffer.
std::vector<BYTE> currentUserToken() {
    HANDLE raw = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &raw))
        fail("işlem belirteci açılamadı");
    const std::unique_ptr<void, HandleCloser> token(raw);
    DWORD size = 0;
    GetTokenInformation(token.get(), TokenUser, nullptr, 0, &size);
    std::vector<BYTE> buffer(size);
    if (size == 0 || !GetTokenInformation(token.get(), TokenUser, buffer.data(), size, &size))
        fail("kullanıcı kimliği okunamadı");
    return buffer;
}

PSID sidOf(std::vector<BYTE>& token) { return reinterpret_cast<TOKEN_USER*>(token.data())->User.Sid; }

using SecurityDescriptor = std::unique_ptr<void, LocalFreeDeleter>;

/// Protected DACL with one entry: nothing is inherited and only the current user has access.
SecurityDescriptor privateDescriptor(bool directory) {
    auto token = currentUserToken();
    LPWSTR sidText = nullptr;
    if (!ConvertSidToStringSidW(sidOf(token), &sidText))
        fail("kullanıcı kimliği çevrilemedi");
    const std::unique_ptr<wchar_t, LocalFreeDeleter> sid(sidText);
    const std::wstring sddl = std::wstring(directory ? L"D:P(A;OICI;FA;;;" : L"D:P(A;;FA;;;") + sid.get() + L")";
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &descriptor, nullptr))
        fail("erişim listesi kurulamadı");
    return SecurityDescriptor(descriptor);
}

/// Antivirus and readers of the old file hold it open for short moments.
void moveIntoPlace(const std::filesystem::path& from, const std::filesystem::path& to) {
    DWORD error = ERROR_SUCCESS;
    for (int attempt = 0; attempt < kMoveAttempts; ++attempt) {
        if (MoveFileExW(from.c_str(), to.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
            return;
        error = GetLastError();
        if (error != ERROR_ACCESS_DENIED && error != ERROR_SHARING_VIOLATION)
            break;
        Sleep(kMoveRetryMs);
    }
    DeleteFileW(from.c_str());
    fail("yazılamadı", to, error);
}

} // namespace

void writeFileAtomic(const std::filesystem::path& path, const std::string& data, FileAccess access) {
    std::filesystem::path tmp = path;
    tmp += L".tmp";
    DeleteFileW(tmp.c_str());

    SecurityDescriptor descriptor;
    SECURITY_ATTRIBUTES attributes{};
    attributes.nLength = sizeof(attributes);
    if (access == FileAccess::Private) {
        descriptor = privateDescriptor(false);
        attributes.lpSecurityDescriptor = descriptor.get();
    }
    // The DACL is set when the file is created, so the secret is never on disk with wider access.
    const HANDLE file = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, &attributes, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE)
        fail("yazılamadı", tmp, GetLastError());

    std::size_t offset = 0;
    DWORD error = ERROR_SUCCESS;
    while (offset < data.size() && error == ERROR_SUCCESS) {
        const std::size_t left = data.size() - offset;
        const DWORD chunk = left > 0x40000000 ? 0x40000000 : DWORD(left);
        DWORD written = 0;
        if (WriteFile(file, data.data() + offset, chunk, &written, nullptr))
            offset += written;
        else
            error = GetLastError();
    }
    if (error == ERROR_SUCCESS && !FlushFileBuffers(file))
        error = GetLastError();
    CloseHandle(file);
    if (error != ERROR_SUCCESS) {
        DeleteFileW(tmp.c_str());
        fail("yazılamadı", tmp, error);
    }
    moveIntoPlace(tmp, path);
}

void createPrivateDirectory(const std::filesystem::path& dir) {
    std::filesystem::create_directories(dir);
    const SecurityDescriptor descriptor = privateDescriptor(true);
    BOOL present = FALSE;
    BOOL defaulted = FALSE;
    PACL dacl = nullptr;
    if (!GetSecurityDescriptorDacl(descriptor.get(), &present, &dacl, &defaulted) || !present)
        fail("erişim listesi okunamadı", dir, GetLastError());
    std::wstring name = dir.wstring();
    const DWORD error = SetNamedSecurityInfoW(name.data(), SE_FILE_OBJECT,
                                              DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, nullptr,
                                              nullptr, dacl, nullptr);
    if (error != ERROR_SUCCESS)
        fail("erişim listesi yazılamadı", dir, error);
}

bool isPrivateToUser(const std::filesystem::path& path) {
    PACL dacl = nullptr;
    PSECURITY_DESCRIPTOR raw = nullptr;
    if (GetNamedSecurityInfoW(path.c_str(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, nullptr, nullptr, &dacl, nullptr,
                              &raw) != ERROR_SUCCESS)
        return false;
    const SecurityDescriptor descriptor(raw);
    SECURITY_DESCRIPTOR_CONTROL control = 0;
    DWORD revision = 0;
    if (!GetSecurityDescriptorControl(raw, &control, &revision) || !(control & SE_DACL_PROTECTED))
        return false;
    if (!dacl || dacl->AceCount != 1)
        return false;
    void* ace = nullptr;
    if (!GetAce(dacl, 0, &ace) || static_cast<ACE_HEADER*>(ace)->AceType != ACCESS_ALLOWED_ACE_TYPE)
        return false;
    auto token = currentUserToken();
    return EqualSid(&static_cast<ACCESS_ALLOWED_ACE*>(ace)->SidStart, sidOf(token)) != FALSE;
}

} // namespace yansit::net
