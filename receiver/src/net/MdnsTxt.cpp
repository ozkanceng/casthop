#include "net/MdnsPublisher.h"

#include "protocol/Qr.h"

namespace yansit::net {

std::vector<std::string> mdnsTxt(const std::string& name, const protocol::Key32& fingerprint) {
    const protocol::ByteView prefix(fingerprint.data(), 16);
    return {"v=1", "name=" + name, "fp=" + protocol::base64UrlNoPad(prefix)};
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

} // namespace yansit::net
