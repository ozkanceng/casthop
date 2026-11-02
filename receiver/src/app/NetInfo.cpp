#include "app/NetInfo.h"

#include "app/AddressSelection.h"

#include <QHostInfo>
#include <QNetworkInterface>

namespace yansit {

namespace {

constexpr int kPollIntervalMs = 5000;

AddressCandidate::Kind toKind(QNetworkInterface::InterfaceType type) {
    switch (type) {
    case QNetworkInterface::Ethernet:
        return AddressCandidate::Kind::Ethernet;
    case QNetworkInterface::Wifi:
        return AddressCandidate::Kind::Wifi;
    case QNetworkInterface::Loopback:
        return AddressCandidate::Kind::Loopback;
    case QNetworkInterface::Virtual:
        return AddressCandidate::Kind::Virtual;
    default:
        return AddressCandidate::Kind::Other;
    }
}

} // namespace

NetInfo::NetInfo(QObject* parent) : QObject(parent), m_hostName(QHostInfo::localHostName()) {
    refresh();
    m_pollTimer.setInterval(kPollIntervalMs);
    connect(&m_pollTimer, &QTimer::timeout, this, &NetInfo::refresh);
    m_pollTimer.start();
}

void NetInfo::refresh() {
    QList<AddressCandidate> candidates;
    const auto interfaces = QNetworkInterface::allInterfaces();
    for (const auto& iface : interfaces) {
        const auto flags = iface.flags();
        for (const auto& entry : iface.addressEntries()) {
            AddressCandidate c;
            c.interfaceName = iface.name();
            c.kind = toKind(iface.type());
            c.up = flags.testFlag(QNetworkInterface::IsUp);
            c.running = flags.testFlag(QNetworkInterface::IsRunning);
            c.address = entry.ip();
            candidates.append(c);
        }
    }

    QStringList next;
    for (const auto& addr : selectAddresses(candidates))
        next.append(addr.toString());

    if (next != m_addresses) {
        m_addresses = next;
        emit addressesChanged();
    }
}

} // namespace yansit
