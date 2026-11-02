#include "app/AddressSelection.h"

#include <gtest/gtest.h>

using yansit::AddressCandidate;
using yansit::isShareablePrivateIPv4;
using yansit::selectAddresses;
using Kind = AddressCandidate::Kind;

namespace {

AddressCandidate make(const char* iface, Kind kind, const char* ip, bool up = true, bool running = true) {
    AddressCandidate c;
    c.interfaceName = QString::fromLatin1(iface);
    c.kind = kind;
    c.up = up;
    c.running = running;
    c.address = QHostAddress(QString::fromLatin1(ip));
    return c;
}

QStringList asStrings(const QList<QHostAddress>& list) {
    QStringList out;
    for (const auto& a : list)
        out.append(a.toString());
    return out;
}

} // namespace

TEST(PrivateIPv4, AcceptsRfc1918AndCgnat) {
    EXPECT_TRUE(isShareablePrivateIPv4(QHostAddress("10.1.2.3")));
    EXPECT_TRUE(isShareablePrivateIPv4(QHostAddress("172.16.0.1")));
    EXPECT_TRUE(isShareablePrivateIPv4(QHostAddress("172.31.255.254")));
    EXPECT_TRUE(isShareablePrivateIPv4(QHostAddress("192.168.43.1")));
    EXPECT_TRUE(isShareablePrivateIPv4(QHostAddress("100.64.0.5")));
}

TEST(PrivateIPv4, RejectsPublicLoopbackLinkLocalAndIPv6) {
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("8.8.8.8")));
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("172.32.0.1")));
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("127.0.0.1")));
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("169.254.10.10")));
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("fe80::1")));
    EXPECT_FALSE(isShareablePrivateIPv4(QHostAddress("fd00::1")));
}

TEST(SelectAddresses, OrdersEthernetBeforeWifiBeforeOther) {
    const QList<AddressCandidate> in = {
        make("usb0", Kind::Other, "10.42.0.50"),
        make("wlp2s0", Kind::Wifi, "192.168.1.20"),
        make("enp3s0", Kind::Ethernet, "10.0.5.7"),
    };
    EXPECT_EQ(asStrings(selectAddresses(in)), (QStringList{"10.0.5.7", "192.168.1.20", "10.42.0.50"}));
}

TEST(SelectAddresses, SkipsDownVirtualLoopbackAndPublic) {
    const QList<AddressCandidate> in = {
        make("lo", Kind::Loopback, "127.0.0.1"),
        make("docker0", Kind::Ethernet, "172.17.0.1"),
        make("virbr0", Kind::Other, "192.168.122.1"),
        make("br0", Kind::Virtual, "192.168.50.1"),
        make("enp3s0", Kind::Ethernet, "192.168.0.10", /*up=*/true, /*running=*/false),
        make("wlp2s0", Kind::Wifi, "192.168.0.11", /*up=*/false, /*running=*/false),
        make("eth1", Kind::Ethernet, "85.10.20.30"),
        make("wlan0", Kind::Wifi, "192.168.43.120"),
    };
    EXPECT_EQ(asStrings(selectAddresses(in)), (QStringList{"192.168.43.120"}));
}

TEST(SelectAddresses, LimitsCountAndRemovesDuplicates) {
    const QList<AddressCandidate> in = {
        make("eth0", Kind::Ethernet, "10.0.0.1"), make("eth1", Kind::Ethernet, "10.0.0.2"),
        make("eth2", Kind::Ethernet, "10.0.0.1"), make("eth3", Kind::Ethernet, "10.0.0.3"),
        make("eth4", Kind::Ethernet, "10.0.0.4"), make("eth5", Kind::Ethernet, "10.0.0.5"),
    };
    EXPECT_EQ(asStrings(selectAddresses(in, 4)), (QStringList{"10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"}));
}

TEST(SelectAddresses, EmptyInputGivesEmptyOutput) {
    EXPECT_TRUE(selectAddresses({}).isEmpty());
}
