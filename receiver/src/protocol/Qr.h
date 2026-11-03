#pragma once

#include "protocol/Bytes.h"

#include <string>
#include <vector>

namespace yansit::protocol {

/// Bekleme ekranı QR içeriği (PROTOCOL.md §8).
struct QrPayload {
    std::vector<std::string> hosts; ///< 1–4 IPv4 adresi, öncelik sırasıyla
    std::uint16_t port = 0;
    Id16 token{};
    Key32 certFingerprint{};
    std::string name;
};

inline constexpr std::size_t kMaxQrHosts = 4;

/// `yansit://c?v=1&h=...&p=...&t=...&f=...&n=...` üretir. Geçersiz girdide std::invalid_argument.
std::string buildQrUri(const QrPayload& payload);

std::string base64UrlNoPad(ByteView data);
std::string percentEncode(std::string_view utf8);

} // namespace yansit::protocol
