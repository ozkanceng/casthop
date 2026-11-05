#include "net/SecureFile.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>

namespace yansit::net {

void writeFileAtomic(const std::filesystem::path& path, const std::string& data, FileAccess access) {
    const std::filesystem::path tmp = path.string() + ".tmp";
    const mode_t mode = access == FileAccess::Private ? 0600 : 0644;
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
    std::filesystem::rename(tmp, path);
}

void createPrivateDirectory(const std::filesystem::path& dir) {
    std::filesystem::create_directories(dir);
    std::filesystem::permissions(dir, std::filesystem::perms::owner_all, std::filesystem::perm_options::replace);
}

bool isPrivateToUser(const std::filesystem::path& path) {
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && (st.st_mode & 077) == 0;
}

} // namespace yansit::net
