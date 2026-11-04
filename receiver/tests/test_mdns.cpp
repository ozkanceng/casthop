#include "net/MdnsPublisher.h"
#include "protocol/Qr.h"

#include <gtest/gtest.h>

using namespace yansit::net;

TEST(Mdns, TxtRecordCarriesVersionNameAndFingerprintPrefix) {
    yansit::protocol::Key32 fp{};
    for (std::size_t i = 0; i < fp.size(); ++i)
        fp[i] = std::uint8_t(i);
    const auto txt = mdnsTxt("7-A Sınıfı", fp);
    ASSERT_EQ(txt.size(), 3u);
    EXPECT_EQ(txt[0], "v=1");
    EXPECT_EQ(txt[1], "name=7-A Sınıfı");
    // İlk 16 bayt, base64url dolgusuz: 22 karakter.
    EXPECT_EQ(txt[2], "fp=" + yansit::protocol::base64UrlNoPad(yansit::protocol::ByteView(fp.data(), 16)));
    EXPECT_EQ(txt[2].size(), 3u + 22u);
}

TEST(Mdns, InstanceNameIsTruncatedOnCharacterBoundary) {
    EXPECT_EQ(mdnsInstanceName(""), "CastHop");
    EXPECT_EQ(mdnsInstanceName("Tahta"), "Tahta");
    std::string longName(62, 'a');
    longName += "ğ"; // 2 bayt: 62 + 2 = 64 > 63 → "ğ" bölünmeden atılır
    EXPECT_EQ(mdnsInstanceName(longName), std::string(62, 'a'));
}

TEST(Mdns, PublisherWithoutDaemonDoesNotFail) {
    // CI'da avahi-daemon yok: NO_FAIL istemcisi bekler; başlatma/durdurma çökmeden çalışmalı.
    MdnsPublisher p;
    yansit::protocol::Key32 fp{};
    p.publish("Tahta", 47800, fp);
    p.publish("Tahta 2", 47800, fp);
    p.stop();
}
