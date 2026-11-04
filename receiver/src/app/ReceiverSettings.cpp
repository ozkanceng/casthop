#include "app/ReceiverSettings.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QHostInfo>
#include <QLoggingCategory>
#include <QSaveFile>
#include <QStandardPaths>

Q_LOGGING_CATEGORY(lcSettings, "yansit.settings")

namespace yansit {

ReceiverSettings::ReceiverSettings(QObject* parent) : QObject(parent) {}

void ReceiverSettings::write(const QString& key, const QVariant& value) {
    if (m_settings.value(key) == value)
        return;
    m_settings.setValue(key, value);
    m_settings.sync();
    emit changed();
}

QString ReceiverSettings::boardName() const {
    const QString n = m_settings.value(QStringLiteral("boardName")).toString().trimmed();
    return n.isEmpty() ? QHostInfo::localHostName() : n;
}

void ReceiverSettings::setBoardName(const QString& name) {
    // Protokol dizesi en fazla 255 bayt UTF-8 (PROTOCOL.md §7).
    QString n = name.trimmed();
    while (n.toUtf8().size() > 255)
        n.chop(1);
    write(QStringLiteral("boardName"), n);
}

bool ReceiverSettings::audioEnabled() const { return m_settings.value(QStringLiteral("audio"), true).toBool(); }
void ReceiverSettings::setAudioEnabled(bool on) { write(QStringLiteral("audio"), on); }

QString ReceiverSettings::scaling() const {
    const QString v = m_settings.value(QStringLiteral("scaling")).toString();
    return v == QLatin1String("fill") ? v : QStringLiteral("fit");
}
void ReceiverSettings::setScaling(const QString& mode) {
    write(QStringLiteral("scaling"), mode == QLatin1String("fill") ? mode : QStringLiteral("fit"));
}

QString ReceiverSettings::theme() const {
    const QString v = m_settings.value(QStringLiteral("theme")).toString();
    return v == QLatin1String("light") ? v : QStringLiteral("dark");
}
void ReceiverSettings::setTheme(const QString& theme) {
    write(QStringLiteral("theme"), theme == QLatin1String("light") ? theme : QStringLiteral("dark"));
}

int ReceiverSettings::port() const {
    const int p = m_settings.value(QStringLiteral("port"), 47800).toInt();
    return p > 0 && p <= 65535 ? p : 47800;
}
void ReceiverSettings::setPort(int port) {
    if (port > 0 && port <= 65535)
        write(QStringLiteral("port"), port);
}

bool ReceiverSettings::compatibleVideo() const { return m_settings.value(QStringLiteral("compatibleVideo"), false).toBool(); }
void ReceiverSettings::setCompatibleVideo(bool on) { write(QStringLiteral("compatibleVideo"), on); }

QString ReceiverSettings::autostartFile() {
    return QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation) +
           QStringLiteral("/autostart/io.github.ozkanceng.yansit.receiver.desktop");
}

bool ReceiverSettings::autostart() const { return QFile::exists(autostartFile()); }

void ReceiverSettings::setAutostart(bool on) {
    if (on == autostart())
        return;
    const QString path = autostartFile();
    if (on) {
        QDir().mkpath(QFileInfo(path).absolutePath());
        QSaveFile f(path);
        if (!f.open(QIODevice::WriteOnly)) {
            qCWarning(lcSettings) << "otomatik başlatma dosyası yazılamadı:" << path;
            return;
        }
        const QByteArray exec = QCoreApplication::applicationFilePath().toUtf8();
        f.write("[Desktop Entry]\nType=Application\nName=Yansıt Alıcı\nName[en]=Yansıt Receiver\n"
                "Icon=io.github.ozkanceng.yansit.receiver\nExec=\"" + exec + "\"\n"
                "X-GNOME-Autostart-enabled=true\nNoDisplay=false\n");
        if (!f.commit()) {
            qCWarning(lcSettings) << "otomatik başlatma dosyası yazılamadı:" << path;
            return;
        }
    } else if (!QFile::remove(path)) {
        qCWarning(lcSettings) << "otomatik başlatma dosyası silinemedi:" << path;
        return;
    }
    emit changed();
}

} // namespace yansit
