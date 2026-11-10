#ifndef _WIN32

#include "app/Platform.h"

#include <QDir>
#include <QFile>
#include <QTemporaryDir>

#include <gtest/gtest.h>

namespace {

const QString kLegacy = QStringLiteral("io.github.ozkanceng.yansit.receiver.desktop");
const QString kEntry = QStringLiteral("io.github.ozkanceng.casthop.receiver.desktop");

void writeFile(const QString& path, const QByteArray& text) {
    QDir().mkpath(path.left(path.lastIndexOf(QLatin1Char('/'))));
    QFile f(path);
    ASSERT_TRUE(f.open(QIODevice::WriteOnly));
    f.write(text);
}

QByteArray readFile(const QString& path) {
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray();
}

struct Dirs {
    QTemporaryDir root;
    QString dataHome = root.path() + QStringLiteral("/home/.local/share");
    QString system = root.path() + QStringLiteral("/usr/share");
    QString config = root.path() + QStringLiteral("/home/.config");

    QString legacyMenu() const { return dataHome + QStringLiteral("/applications/") + kLegacy; }
    QString legacyAutostart() const { return config + QStringLiteral("/autostart/") + kLegacy; }
    QString autostart() const { return config + QStringLiteral("/autostart/") + kEntry; }
    bool migrate() const {
        return yansit::platform::migrateLegacyEntries(dataHome, {system}, config, QStringLiteral("/usr/bin/casthop"));
    }
};

} // namespace

TEST(LegacyEntries, OldMenuEntryGoesOnceCastHopIsInstalled) {
    Dirs d;
    writeFile(d.legacyMenu(), "[Desktop Entry]\nName=Yansıt Alıcı\nExec=/home/t/.local/opt/yansit/yansit-receiver\n");
    writeFile(d.system + QStringLiteral("/applications/") + kEntry, "[Desktop Entry]\nName=CastHop\n");
    EXPECT_TRUE(d.migrate());
    EXPECT_FALSE(QFile::exists(d.legacyMenu()));
}

TEST(LegacyEntries, OldMenuEntryStaysWithoutAReplacement) {
    Dirs d;
    writeFile(d.legacyMenu(), "[Desktop Entry]\nName=Yansıt Alıcı\n");
    EXPECT_FALSE(d.migrate());
    EXPECT_TRUE(QFile::exists(d.legacyMenu()));
}

TEST(LegacyEntries, AutostartMovesToTheNewNameAndThisProgram) {
    Dirs d;
    writeFile(d.legacyAutostart(), "[Desktop Entry]\nName=Yansıt Alıcı\nExec=\"/usr/bin/yansit-receiver\"\n");
    EXPECT_TRUE(d.migrate());
    EXPECT_FALSE(QFile::exists(d.legacyAutostart()));
    const QByteArray entry = readFile(d.autostart());
    EXPECT_TRUE(entry.contains("\nName=CastHop\n")) << entry.constData();
    EXPECT_TRUE(entry.contains("\nExec=\"/usr/bin/casthop\"\n")) << entry.constData();
}

TEST(LegacyEntries, AutostartTurnedOffStaysOff) {
    Dirs d;
    writeFile(d.legacyAutostart(), "[Desktop Entry]\nName=Yansıt Alıcı\nX-GNOME-Autostart-enabled=false\n");
    EXPECT_TRUE(d.migrate());
    EXPECT_FALSE(QFile::exists(d.legacyAutostart()));
    EXPECT_FALSE(QFile::exists(d.autostart()));
}

TEST(LegacyEntries, NothingToDo) {
    Dirs d;
    EXPECT_FALSE(d.migrate());
    EXPECT_FALSE(QFile::exists(d.autostart()));
}

#endif
