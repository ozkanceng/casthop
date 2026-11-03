#include "protocol/Qr.h"

#include <stdexcept>

namespace yansit::protocol {

namespace {

bool isIPv4(const std::string& s) {
    int parts = 0;
    std::size_t i = 0;
    while (i <= s.size()) {
        std::size_t j = s.find('.', i);
        if (j == std::string::npos)
            j = s.size();
        const std::string p = s.substr(i, j - i);
        if (p.empty() || p.size() > 3 || (p.size() > 1 && p[0] == '0'))
            return false;
        for (char c : p)
            if (c < '0' || c > '9')
                return false;
        if (std::stoi(p) > 255)
            return false;
        ++parts;
        i = j + 1;
    }
    return parts == 4;
}

} // namespace

std::string base64UrlNoPad(ByteView data) {
    static constexpr char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    out.reserve((data.size() * 4 + 2) / 3);
    std::size_t i = 0;
    for (; i + 3 <= data.size(); i += 3) {
        const std::uint32_t v = (std::uint32_t(data[i]) << 16) | (std::uint32_t(data[i + 1]) << 8) | data[i + 2];
        out.push_back(table[(v >> 18) & 63]);
        out.push_back(table[(v >> 12) & 63]);
        out.push_back(table[(v >> 6) & 63]);
        out.push_back(table[v & 63]);
    }
    const std::size_t rest = data.size() - i;
    if (rest == 1) {
        const std::uint32_t v = std::uint32_t(data[i]) << 16;
        out.push_back(table[(v >> 18) & 63]);
        out.push_back(table[(v >> 12) & 63]);
    } else if (rest == 2) {
        const std::uint32_t v = (std::uint32_t(data[i]) << 16) | (std::uint32_t(data[i + 1]) << 8);
        out.push_back(table[(v >> 18) & 63]);
        out.push_back(table[(v >> 12) & 63]);
        out.push_back(table[(v >> 6) & 63]);
    }
    return out;
}

std::string percentEncode(std::string_view utf8) {
    static constexpr char hex[] = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : utf8) {
        const bool unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                                c == '-' || c == '_' || c == '.' || c == '~';
        if (unreserved) {
            out.push_back(char(c));
        } else {
            out.push_back('%');
            out.push_back(hex[c >> 4]);
            out.push_back(hex[c & 0x0F]);
        }
    }
    return out;
}

std::string buildQrUri(const QrPayload& p) {
    if (p.hosts.empty() || p.hosts.size() > kMaxQrHosts)
        throw std::invalid_argument("1–4 adres gerekli");
    if (p.port == 0)
        throw std::invalid_argument("port 0 olamaz");
    std::string hosts;
    for (const auto& h : p.hosts) {
        if (!isIPv4(h))
            throw std::invalid_argument("geçersiz IPv4: " + h);
        if (!hosts.empty())
            hosts.push_back(',');
        hosts += h;
    }
    return "yansit://c?v=1&h=" + hosts + "&p=" + std::to_string(p.port) + "&t=" + base64UrlNoPad(p.token) +
           "&f=" + base64UrlNoPad(p.certFingerprint) + "&n=" + percentEncode(p.name);
}

} // namespace yansit::protocol
