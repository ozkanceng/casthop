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

QString autostartFile() {
    return QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation) +
           QStringLiteral("/autostart/io.github.ozkanceng.yansit.receiver.desktop");
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

} // namespace yansit::platform
