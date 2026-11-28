#include "net/MdnsPublisher.h"

#include "protocol/Qr.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <string_view>

namespace yansit::net {

namespace {

std::string asciiLowerTrimmed(std::string_view s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front())))
        s.remove_prefix(1);
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back())))
        s.remove_suffix(1);
    std::string out(s.size(), '\0');
    std::transform(s.begin(), s.end(), out.begin(),
                   [](unsigned char c) { return char(std::tolower(c)); });
    return out;
}

bool isValidIpv4Host(std::string_view s) {
    int parts = 0;
    std::size_t i = 0;
    while (i < s.size()) {
        if (parts >= 4)
            return false;
        const std::size_t start = i;
        int val = 0;
        while (i < s.size() && s[i] != '.') {
            if (s[i] < '0' || s[i] > '9')
                return false;
            val = val * 10 + (s[i] - '0');
            if (val > 255)
                return false;
            ++i;
        }
        const std::size_t len = i - start;
        if (len == 0 || (len > 1 && s[start] == '0'))
            return false;
        ++parts;
        if (i < s.size()) {
            if (s[i] != '.' || i + 1 == s.size())
                return false;
            ++i;
        }
    }
    return parts == 4;
}

} // namespace

std::vector<std::string> mdnsTxt(const std::string& name, const protocol::Key32& fingerprint,
                                 const std::vector<std::string>& hosts) {
    const protocol::ByteView prefix(fingerprint.data(), 16);
#ifdef _WIN32
    constexpr const char* kKind = "kind=pc";
#else
    constexpr const char* kKind = "kind=board";
#endif
    std::vector<std::string> out{"v=1", "name=" + name, "fp=" + protocol::base64UrlNoPad(prefix), kKind};
    std::string ipList;
    int added = 0;
    for (const std::string& h : hosts) {
        if (added >= 4)
            break;
        if (!isValidIpv4Host(h))
            continue;
        if (!ipList.empty())
            ipList.push_back(',');
        ipList += h;
        ++added;
    }
    if (!ipList.empty())
        out.push_back("ip=" + ipList);
    return out;
}

std::string mdnsInstanceName(const std::string& name) {
    constexpr std::size_t kMax = 63;
    if (name.size() <= kMax)
        return name.empty() ? std::string("CastHop") : name;
    std::size_t cut = kMax;
    // UTF-8 devam baytlarında (10xxxxxx) kesme: karakter başına geri git.
    while (cut > 0 && (static_cast<unsigned char>(name[cut]) & 0xC0) == 0x80)
        --cut;
    return name.substr(0, cut);
}

std::string shortBoardId(const protocol::Key32& fingerprint) {
    static constexpr char kHex[] = "0123456789ABCDEF";
    std::string id(4, '0');
    id[0] = kHex[(fingerprint[0] >> 4) & 0x0F];
    id[1] = kHex[fingerprint[0] & 0x0F];
    id[2] = kHex[(fingerprint[1] >> 4) & 0x0F];
    id[3] = kHex[fingerprint[1] & 0x0F];
    return id;
}

bool isGenericHostName(const std::string& hostName) {
    const std::string lower = asciiLowerTrimmed(hostName);
    if (lower.empty())
        return true;
    static constexpr std::array<std::string_view, 12> kGeneric = {
        "etap",     "etap-pc",   "etap23",  "pardus", "pardus-etap", "localhost",
        "debian",   "ubuntu",    "linux",   "pc",     "desktop",     "casthop",
    };
    return std::find(kGeneric.begin(), kGeneric.end(), lower) != kGeneric.end();
}

std::string defaultBoardName(const std::string& hostName, const protocol::Key32& fingerprint) {
    std::string trimmed = hostName;
    while (!trimmed.empty() && std::isspace(static_cast<unsigned char>(trimmed.front())))
        trimmed.erase(trimmed.begin());
    while (!trimmed.empty() && std::isspace(static_cast<unsigned char>(trimmed.back())))
        trimmed.pop_back();
    const std::string suffix = shortBoardId(fingerprint);
    if (trimmed.empty())
        return "CastHop-" + suffix;
    if (isGenericHostName(trimmed))
        return trimmed + "-" + suffix;
    return trimmed;
}

} // namespace yansit::net
