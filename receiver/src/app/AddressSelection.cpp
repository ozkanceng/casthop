#include "app/AddressSelection.h"

#include <algorithm>

namespace yansit {

namespace {

int kindRank(AddressCandidate::Kind kind) {
    switch (kind) {
    case AddressCandidate::Kind::Ethernet:
        return 0;
    case AddressCandidate::Kind::Wifi:
        return 1;
    case AddressCandidate::Kind::Other:
        return 2;
    case AddressCandidate::Kind::Virtual:
    case AddressCandidate::Kind::Loopback:
        return 3;
    }
    return 3;
}

bool isVirtualName(const QString& name) {
    static const char* const prefixes[] = {"docker", "veth", "virbr", "br-", "vmnet", "vboxnet", "lxc", "tun", "tap", "wg"};
    for (const char* p : prefixes) {
        if (name.startsWith(QLatin1String(p)))
            return true;
    }
    return false;
}

} // namespace

bool isShareablePrivateIPv4(const QHostAddress& address) {
    if (address.protocol() != QAbstractSocket::IPv4Protocol)
        return false;
    const quint32 ip = address.toIPv4Address();
    const auto inNet = [ip](quint32 net, int prefix) {
        const quint32 mask = prefix == 0 ? 0 : ~quint32(0) << (32 - prefix);
        return (ip & mask) == (net & mask);
    };
    return inNet(0x0A000000, 8)        // 10.0.0.0/8
           || inNet(0xAC100000, 12)    // 172.16.0.0/12
           || inNet(0xC0A80000, 16)    // 192.168.0.0/16
           || inNet(0x64400000, 10);   // 100.64.0.0/10 (CGNAT, bazı hotspot'lar)
}

QList<QHostAddress> selectAddresses(const QList<AddressCandidate>& candidates, int maxCount) {
    QList<AddressCandidate> usable;
    for (const auto& c : candidates) {
        if (!c.up || !c.running)
            continue;
        if (c.kind == AddressCandidate::Kind::Loopback || c.kind == AddressCandidate::Kind::Virtual)
            continue;
        if (isVirtualName(c.interfaceName))
            continue;
        if (!isShareablePrivateIPv4(c.address))
            continue;
        usable.append(c);
    }

    std::stable_sort(usable.begin(), usable.end(), [](const AddressCandidate& a, const AddressCandidate& b) {
        const int ra = kindRank(a.kind), rb = kindRank(b.kind);
        if (ra != rb)
            return ra < rb;
        return a.interfaceName < b.interfaceName;
    });

    QList<QHostAddress> result;
    for (const auto& c : usable) {
        if (result.size() >= maxCount)
            break;
        if (!result.contains(c.address))
            result.append(c.address);
    }
    return result;
}

} // namespace yansit
