#include "app/DiagnosticLog.h"

#include "app/Platform.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QStandardPaths>

#include <gst/gst.h>

#include <cstdio>
#include <mutex>

namespace yansit {

namespace {

QFile* logFile = nullptr;
std::mutex logMutex;
QtMessageHandler previousHandler = nullptr;

void writeMessage(QtMsgType type, const QMessageLogContext& context, const QString& message) {
    if (previousHandler)
        previousHandler(type, context, message);
    if (type == QtDebugMsg)
        return;
    const QByteArray line = QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz ")).toUtf8() +
                            (context.category ? QByteArray(context.category) + ": " : QByteArray()) + message.toUtf8() + '\n';
    std::lock_guard lock(logMutex);
    if (logFile) {
        logFile->write(line);
        logFile->flush();
    }
}

} // namespace

QString diagnosticLogPath() {
    return QStandardPaths::writableLocation(QStandardPaths::GenericCacheLocation) + QStringLiteral("/yansit/receiver.log");
}

void installDiagnosticLog() {
    const QString path = diagnosticLogPath();
    QDir().mkpath(path.section(QLatin1Char('/'), 0, -2));
    QFile::remove(path + QStringLiteral(".1"));
    QFile::rename(path, path + QStringLiteral(".1"));
    auto* file = new QFile(path);
    if (!file->open(QIODevice::WriteOnly | QIODevice::Text)) {
        std::fprintf(stderr, "günlük dosyası açılamadı: %s\n", qPrintable(path));
        delete file;
        return;
    }
    logFile = file; // stays open for the whole process: messages can arrive until exit
    previousHandler = qInstallMessageHandler(writeMessage);
}

QString pluginLoadError(const QString& fileName) {
    QStringList dirs = qEnvironmentVariable("GST_PLUGIN_SYSTEM_PATH_1_0").split(QDir::listSeparator(), Qt::SkipEmptyParts);
    if (dirs.isEmpty())
        dirs = platform::defaultGstPluginDirs();
    for (const QString& dir : dirs) {
        const QString path = dir + QLatin1Char('/') + fileName;
        if (!QFile::exists(path))
            continue;
        GError* error = nullptr;
        GstPlugin* plugin = gst_plugin_load_file(path.toUtf8().constData(), &error);
        if (plugin) {
            gst_object_unref(plugin);
            return QStringLiteral("%1 yüklendi").arg(path);
        }
        const QString message = QString::fromUtf8(error ? error->message : "bilinmeyen hata");
        g_clear_error(&error);
        return message;
    }
    return QStringLiteral("%1 bulunamadı: %2").arg(fileName, dirs.join(QDir::listSeparator()));
}

} // namespace yansit
