#pragma once

#include <QObject>
#include <QSettings>
#include <QString>

namespace yansit {

/**
 * Tahtada kalıcı ayarlar (`~/.config/Yansit/yansit-receiver.conf`). Komut satırı seçenekleri bunları o çalışma
 * için geçersiz kılar. Otomatik başlatma XDG autostart dosyasıyla yapılır (`~/.config/autostart`).
 */
class ReceiverSettings : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString boardName READ boardName WRITE setBoardName NOTIFY changed)
    Q_PROPERTY(bool audioEnabled READ audioEnabled WRITE setAudioEnabled NOTIFY changed)
    Q_PROPERTY(QString scaling READ scaling WRITE setScaling NOTIFY changed)
    Q_PROPERTY(QString theme READ theme WRITE setTheme NOTIFY changed)
    Q_PROPERTY(bool autostart READ autostart WRITE setAutostart NOTIFY changed)
    Q_PROPERTY(int port READ port WRITE setPort NOTIFY changed)
    Q_PROPERTY(bool compatibleVideo READ compatibleVideo WRITE setCompatibleVideo NOTIFY changed)

public:
    explicit ReceiverSettings(QObject* parent = nullptr);

    /// Boşsa bilgisayar adı kullanılır.
    QString boardName() const;
    void setBoardName(const QString& name);
    bool audioEnabled() const;
    void setAudioEnabled(bool on);
    /// "fit" (kenar boşluklu, varsayılan) | "fill" (kırparak doldur).
    QString scaling() const;
    void setScaling(const QString& mode);
    /// "dark" (varsayılan) | "light".
    QString theme() const;
    void setTheme(const QString& theme);
    /// Oturum açılınca Yansıt'ı başlat.
    bool autostart() const;
    void setAutostart(bool on);
    /// Değişiklik yeniden başlatınca geçerli olur.
    int port() const;
    void setPort(int port);

    /// Draws video on the CPU, for boards where GL rendering is broken.
    bool compatibleVideo() const;
    void setCompatibleVideo(bool on);

    /// XDG autostart dosyasının yolu.
    static QString autostartFile();

signals:
    void changed();

private:
    void write(const QString& key, const QVariant& value);
    QSettings m_settings;
};

} // namespace yansit
