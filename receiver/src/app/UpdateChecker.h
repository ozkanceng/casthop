#pragma once

#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QUrl>

#include <cstdint>
#include <functional>
#include <optional>

namespace yansit {

class ReceiverSettings;

/// Sürüm karşılaştırma için saf SemVer (major.minor.patch + isteğe bağlı ön-sürüm eki).
struct SemVer {
    int major = 0;
    int minor = 0;
    int patch = 0;
    bool prerelease = false;

    static std::optional<SemVer> parse(const QString& raw);
    /// `candidate`, `current`'tan daha yeniyse true döner. Kararlı kurulumlara ön-sürüm önerilmez.
    static bool isNewer(const SemVer& candidate, const SemVer& current);
};

/// `version.json` manifestosundan tek bir platform kanalı için çözümlenmiş güncelleme bilgisi.
struct UpdateRelease {
    QString version;
    QString url;
    QString notes;
    QString publishedAt;
};

/// `version.json` ayrıştırıcısı (yalnız https:// bağlantıları kabul eder). Birim testlerde doğrudan çağrılır.
std::optional<UpdateRelease> parseUpdateManifest(const QByteArray& json, const QString& channel, bool preferTurkish);

/**
 * Arka planda yeni CastHop sürümünü kontrol eder (varsayılan: `https://ozkanceng.github.io/casthop/version.json`).
 *
 * - Hiçbir cihaz veya kullanıcı kimliği göndermez.
 * - Günde en fazla bir kez otomatik denetler; çevrimdışı ağda sessiz kalır.
 * - `YANSIT_UPDATE_URL` ile yerel test adresi verilebilir (yalnız geliştirme/test amaçlı).
 */
class UpdateChecker : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool updateAvailable READ updateAvailable NOTIFY changed)
    Q_PROPERTY(bool checking READ checking NOTIFY changed)
    Q_PROPERTY(QString currentVersion READ currentVersion CONSTANT)
    Q_PROPERTY(QString latestVersion READ latestVersion NOTIFY changed)
    Q_PROPERTY(QString releaseNotes READ releaseNotes NOTIFY changed)
    Q_PROPERTY(QString downloadUrl READ downloadUrl NOTIFY changed)
    Q_PROPERTY(QString installKind READ installKind CONSTANT)
    Q_PROPERTY(QString updateCommand READ updateCommand CONSTANT)
    Q_PROPERTY(QString statusMessage READ statusMessage NOTIFY changed)
    Q_PROPERTY(bool commandCopied READ commandCopied NOTIFY changed)

public:
    using OpenUrlFn = std::function<bool(const QUrl&)>;
    using CopyTextFn = std::function<void(const QString&)>;

    explicit UpdateChecker(ReceiverSettings& settings, const QString& currentVersion, bool preferTurkish,
                           OpenUrlFn openUrl = {}, CopyTextFn copyText = {}, QObject* parent = nullptr);

    bool updateAvailable() const { return m_updateAvailable && !m_sessionDismissed; }
    bool checking() const { return m_checking; }
    QString currentVersion() const { return m_currentVersion; }
    QString latestVersion() const { return m_latestVersion; }
    QString releaseNotes() const { return m_releaseNotes; }
    QString downloadUrl() const { return m_downloadUrl; }
    /// "pardus-portable" | "pardus-deb" | "windows"
    QString installKind() const { return m_installKind; }
    QString updateCommand() const { return m_updateCommand; }
    QString statusMessage() const { return m_statusMessage; }
    bool commandCopied() const { return m_commandCopied; }

    /// Otomatik denetim açıksa ve son denetimden 24 saat geçtiyse arka planda kontrol başlatır.
    Q_INVOKABLE void checkIfDue();
    /// Kullanıcının Ayarlar'dan elle başlattığı kontrol: sonucu `statusMessage` ile bildirir.
    Q_INVOKABLE void checkNow();
    /// Mevcut oturum boyunca üst şerit bildirimini gizler.
    Q_INVOKABLE void dismissForSession();
    /// Bulunan sürümü kalıcı olarak atlar; daha yeni bir sürüm çıkınca yeniden görünür.
    Q_INVOKABLE void skipVersion();
    /// İndirme/kurulum sayfasını sistem tarayıcısında açar (yalnız https://).
    Q_INVOKABLE bool openDownloadUrl();
    /// Güncelleme terminal komutunu panoya kopyalar.
    Q_INVOKABLE void copyUpdateCommand();

signals:
    void changed();

private:
    void startRequest(bool manual);
    void handleReply(QNetworkReply* reply, bool manual);
    static QString detectInstallKind();
    static QString buildUpdateCommand(const QString& installKind);
    static QString manifestChannel(const QString& installKind);
    static QUrl manifestUrl();

    ReceiverSettings& m_settings;
    QString m_currentVersion;
    bool m_preferTurkish = true;
    QString m_installKind;
    QString m_updateCommand;
    OpenUrlFn m_openUrl;
    CopyTextFn m_copyText;
    QNetworkAccessManager m_network;
    QPointer<QNetworkReply> m_activeReply;

    bool m_checking = false;
    bool m_updateAvailable = false;
    bool m_sessionDismissed = false;
    bool m_commandCopied = false;
    QString m_latestVersion;
    QString m_releaseNotes;
    QString m_downloadUrl;
    QString m_statusMessage;
};

} // namespace yansit
