#include "app/Platform.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QLoggingCategory>
#include <QObject>
#include <QSaveFile>
#include <QSocketNotifier>
#include <QStandardPaths>

#include <csignal>
#include <unistd.h>

Q_LOGGING_CATEGORY(lcPlatform, "yansit.platform")

namespace yansit::platform {

namespace {

int g_signalPipe[2] = {-1, -1};

void onSignal(int) {
    const char c = 1;
    [[maybe_unused]] const auto n = ::write(g_signalPipe[1], &c, 1);
}

const QString kLegacyEntry = QStringLiteral("io.github.ozkanceng.yansit.receiver.desktop");

QString entryName() { return QLatin1String(kDesktopId) + QStringLiteral(".desktop"); }

QString autostartFile(const QString& configHome) { return configHome + QStringLiteral("/autostart/") + entryName(); }

QString autostartFile() {
    return autostartFile(QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation));
}

bool writeAutostart(const QString& path, const QString& command) {
    QDir().mkpath(QFileInfo(path).absolutePath());
    QSaveFile f(path);
    if (!f.open(QIODevice::WriteOnly)) {
        qCWarning(lcPlatform) << "otomatik başlatma dosyası yazılamadı:" << path;
        return false;
    }
    f.write("[Desktop Entry]\nType=Application\nName=CastHop\nName[en]=CastHop\n"
            "Icon=io.github.ozkanceng.yansit.receiver\nExec=\"" + command.toUtf8() + "\"\n"
            "X-GNOME-Autostart-enabled=true\nNoDisplay=false\n");
    if (!f.commit()) {
        qCWarning(lcPlatform) << "otomatik başlatma dosyası yazılamadı:" << path;
        return false;
    }
    return true;
}

/// An autostart entry the user turned off in the desktop's startup settings.
bool turnedOff(const QByteArray& entry) {
    for (const QByteArray& line : entry.split('\n')) {
        const QByteArray l = line.trimmed();
        if (l == "Hidden=true" || l == "X-GNOME-Autostart-enabled=false")
            return true;
    }
    return false;
}

} // namespace

void prepareRuntime() {}

void attachParentConsole() {}

void onQuitRequested(QObject* context, std::function<void()> quit) {
    // SIGINT/SIGTERM through a self-pipe: only async-signal-safe calls run in the handler.
    if (::pipe(g_signalPipe) != 0)
        return;
    auto* notifier = new QSocketNotifier(g_signalPipe[0], QSocketNotifier::Read, context);
    QObject::connect(notifier, &QSocketNotifier::activated, context, [notifier, quit = std::move(quit)] {
        char c;
        [[maybe_unused]] const auto n = ::read(g_signalPipe[0], &c, 1);
        notifier->setEnabled(false);
        quit();
    });
    struct sigaction sa {};
    sa.sa_handler = onSignal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
}

QString gstPluginFile(const char* plugin) { return QStringLiteral("libgst%1.so").arg(QLatin1String(plugin)); }

QStringList defaultGstPluginDirs() {
    return {QStringLiteral("/usr/lib/x86_64-linux-gnu/gstreamer-1.0"), QStringLiteral("/usr/lib/aarch64-linux-gnu/gstreamer-1.0")};
}

bool autostartEnabled() { return QFile::exists(autostartFile()); }

bool setAutostartEnabled(bool on, const QString& command) {
    const QString path = autostartFile();
    if (!on) {
        if (QFile::remove(path))
            return true;
        qCWarning(lcPlatform) << "otomatik başlatma dosyası silinemedi:" << path;
        return false;
    }
    return writeAutostart(path, command);
}

bool migrateLegacyEntries(const QString& dataHome, const QStringList& dataDirs, const QString& configHome,
                          const QString& command) {
    bool changed = false;
    const QString legacyMenu = dataHome + QStringLiteral("/applications/") + kLegacyEntry;
    if (QFile::exists(legacyMenu)) {
        // Without a CastHop entry, the old one is the only way to start the program from the menu.
        bool replaced = QFile::exists(dataHome + QStringLiteral("/applications/") + entryName());
        for (const QString& dir : dataDirs)
            replaced = replaced || QFile::exists(dir + QStringLiteral("/applications/") + entryName());
        if (replaced && QFile::remove(legacyMenu)) {
            qCInfo(lcPlatform) << "eski menü kaydı kaldırıldı:" << legacyMenu;
            changed = true;
        }
    }
    const QString legacyAutostart = configHome + QStringLiteral("/autostart/") + kLegacyEntry;
    QFile old(legacyAutostart);
    if (old.open(QIODevice::ReadOnly)) {
        const bool on = !turnedOff(old.readAll());
        old.close();
        if ((!on || writeAutostart(autostartFile(configHome), command)) && QFile::remove(legacyAutostart)) {
            qCInfo(lcPlatform) << "eski otomatik başlatma kaydı taşındı:" << legacyAutostart;
            changed = true;
        }
    }
    return changed;
}

void migrateLegacyEntries(const QString& command) {
    const QString dataHome = QStandardPaths::writableLocation(QStandardPaths::GenericDataLocation);
    QStringList dataDirs = QStandardPaths::standardLocations(QStandardPaths::GenericDataLocation);
    dataDirs.removeAll(dataHome);
    migrateLegacyEntries(dataHome, dataDirs, QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation),
                         command);
}

} // namespace yansit::platform
