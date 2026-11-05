#pragma once

#include <filesystem>
#include <string>

namespace yansit::net {

enum class FileAccess {
    /// Readable by every user of the computer (certificate).
    Shared,
    /// Only the current user: mode 0600, or on Windows a protected DACL with a single entry.
    Private,
};

/// Writes through a temporary file, so readers see the old or the new content. Throws std::runtime_error.
void writeFileAtomic(const std::filesystem::path& path, const std::string& data, FileAccess access);

/// Creates the directory and its parents, then limits the directory itself to the current user.
void createPrivateDirectory(const std::filesystem::path& dir);

/// True when no other user is granted access to the file or directory.
bool isPrivateToUser(const std::filesystem::path& path);

} // namespace yansit::net
