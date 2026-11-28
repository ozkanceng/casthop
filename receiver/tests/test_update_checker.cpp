#include "app/UpdateChecker.h"

#include <gtest/gtest.h>

namespace {

using yansit::SemVer;

TEST(UpdateChecker, ParsesAndComparesSemVer) {
    const auto v042 = SemVer::parse(QStringLiteral("0.4.2"));
    const auto v043 = SemVer::parse(QStringLiteral("v0.4.3"));
    const auto v0410 = SemVer::parse(QStringLiteral("0.4.10"));
    const auto v043rc = SemVer::parse(QStringLiteral("0.4.3-rc1"));
    ASSERT_TRUE(v042.has_value());
    ASSERT_TRUE(v043.has_value());
    ASSERT_TRUE(v0410.has_value());
    ASSERT_TRUE(v043rc.has_value());

    EXPECT_TRUE(SemVer::isNewer(*v043, *v042));
    EXPECT_TRUE(SemVer::isNewer(*v0410, *v043));
    EXPECT_FALSE(SemVer::isNewer(*v042, *v042));
    EXPECT_FALSE(SemVer::isNewer(*v042, *v043));
    // Kararlı kullanıcıya ön-sürüm önerilmez; ön-sürüm kullanıcıya kararlı sürüm önerilir.
    EXPECT_FALSE(SemVer::isNewer(*v043rc, *v042));
    EXPECT_TRUE(SemVer::isNewer(*v043, *v043rc));

    EXPECT_FALSE(SemVer::parse(QStringLiteral("not-a-version")).has_value());
    EXPECT_FALSE(SemVer::parse(QStringLiteral("0.4")).has_value());
}

TEST(UpdateChecker, ParsesChannelManifestAndLanguage) {
    const QByteArray json = R"({
      "schema": 1,
      "latest": "0.5.0",
      "publishedAt": "2026-12-01",
      "releaseUrl": "https://github.com/ozkanceng/casthop/releases/latest",
      "notes": {
        "tr": "Türkçe notlar",
        "en": "English notes"
      },
      "channels": {
        "pardus": {
          "version": "0.5.1",
          "url": "https://ozkanceng.github.io/casthop/#board"
        },
        "windows": {
          "version": "0.5.0",
          "url": "http://insecure.example/evil.exe"
        }
      }
    })";

    const auto pardus = yansit::parseUpdateManifest(json, QStringLiteral("pardus"), /*preferTurkish=*/true);
    ASSERT_TRUE(pardus.has_value());
    EXPECT_EQ(pardus->version, QStringLiteral("0.5.1"));
    EXPECT_EQ(pardus->url, QStringLiteral("https://ozkanceng.github.io/casthop/#board"));
    EXPECT_EQ(pardus->notes, QStringLiteral("Türkçe notlar"));

    // Güvensiz http:// URL güvenli GitHub Releases bağlantısına düşer; İngilizce seçimi çalışır.
    const auto win = yansit::parseUpdateManifest(json, QStringLiteral("windows"), /*preferTurkish=*/false);
    ASSERT_TRUE(win.has_value());
    EXPECT_EQ(win->version, QStringLiteral("0.5.0"));
    EXPECT_EQ(win->url, QStringLiteral("https://github.com/ozkanceng/casthop/releases/latest"));
    EXPECT_EQ(win->notes, QStringLiteral("English notes"));

    EXPECT_FALSE(yansit::parseUpdateManifest(QByteArray("{bad json"), QStringLiteral("pardus"), true).has_value());
}

} // namespace
