#include "app/ReceiverSettings.h"

#include "app/Platform.h"

#include <QCoreApplication>
#include <QHostInfo>

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

bool ReceiverSettings::autostart() const { return platform::autostartEnabled(); }

void ReceiverSettings::setAutostart(bool on) {
    if (on == autostart())
        return;
    // The portable binary cannot start without the environment its launcher sets up.
    QString command = qEnvironmentVariable("YANSIT_LAUNCHER");
    if (command.isEmpty())
        command = QCoreApplication::applicationFilePath();
    if (platform::setAutostartEnabled(on, command))
        emit changed();
}

} // namespace yansit
