#include "app/UpdateChecker.h"

#include "app/ReceiverSettings.h"

#include <QDateTime>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLoggingCategory>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QTimer>

Q_LOGGING_CATEGORY(lcUpdate, "yansit.update")

namespace yansit {

namespace {

constexpr qint64 kCheckIntervalSecs = 24 * 60 * 60; // 24 saat
constexpr int kRequestTimeoutMs = 4000;
constexpr qint64 kMaxManifestBytes = 64 * 1024;
constexpr const char* kDefaultManifestUrl = "https://ozkanceng.github.io/casthop/version.json";
constexpr const char* kFallbackReleaseUrl = "https://github.com/ozkanceng/casthop/releases/latest";

bool isSafeDownloadUrl(const QString& raw) {
    const QUrl url(raw.trimmed());
    if (!url.isValid() || url.host().isEmpty())
        return false;
    return url.scheme().compare(QLatin1String("https"), Qt::CaseInsensitive) == 0;
}

} // namespace

std::optional<SemVer> SemVer::parse(const QString& raw) {
    static const QRegularExpression kPattern(QStringLiteral(R"(^\s*v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\s*$)"));
    const QRegularExpressionMatch m = kPattern.match(raw);
    if (!m.hasMatch())
        return std::nullopt;
    bool ok1 = false, ok2 = false, ok3 = false;
    SemVer v;
    v.major = m.captured(1).toInt(&ok1);
    v.minor = m.captured(2).toInt(&ok2);
    v.patch = m.captured(3).toInt(&ok3);
    v.prerelease = !m.captured(4).isEmpty();
    if (!ok1 || !ok2 || !ok3)
        return std::nullopt;
    return v;
}

bool SemVer::isNewer(const SemVer& candidate, const SemVer& current) {
    // Kararlı bir kurulumu ön-sürüme (rc/beta/dev) yükseltme önerisi yapma.
    if (candidate.prerelease && !current.prerelease)
        return false;
    if (candidate.major != current.major)
        return candidate.major > current.major;
    if (candidate.minor != current.minor)
        return candidate.minor > current.minor;
    if (candidate.patch != current.patch)
        return candidate.patch > current.patch;
    // Aynı x.y.z numarasında kararlı sürüm, ön-sürümden yenidir (0.4.3 > 0.4.3-rc1).
    return current.prerelease && !candidate.prerelease;
}

std::optional<UpdateRelease> parseUpdateManifest(const QByteArray& json, const QString& channel, bool preferTurkish) {
    if (json.isEmpty() || json.size() > kMaxManifestBytes)
        return std::nullopt;
    QJsonParseError err{};
    const QJsonDocument doc = QJsonDocument::fromJson(json, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject())
        return std::nullopt;

    const QJsonObject root = doc.object();
    const QJsonObject channels = root.value(QStringLiteral("channels")).toObject();
    const QJsonObject ch = channels.value(channel).toObject();

    QString version = ch.value(QStringLiteral("version")).toString().trimmed();
    if (version.isEmpty())
        version = root.value(QStringLiteral("latest")).toString().trimmed();
    if (!SemVer::parse(version))
        return std::nullopt;

    QString url = ch.value(QStringLiteral("url")).toString().trimmed();
    if (url.isEmpty())
        url = root.value(QStringLiteral("releaseUrl")).toString().trimmed();
    if (!isSafeDownloadUrl(url))
        url = QString::fromLatin1(kFallbackReleaseUrl);

    const QJsonObject notesObj = root.value(QStringLiteral("notes")).toObject();
    const QString trNotes = notesObj.value(QStringLiteral("tr")).toString().trimmed();
    const QString enNotes = notesObj.value(QStringLiteral("en")).toString().trimmed();
    const QString notes = preferTurkish ? (trNotes.isEmpty() ? enNotes : trNotes) : (enNotes.isEmpty() ? trNotes : enNotes);

    UpdateRelease rel;
    rel.version = version;
    rel.url = url;
    rel.notes = notes;
    rel.publishedAt = root.value(QStringLiteral("publishedAt")).toString().trimmed();
    return rel;
}

UpdateChecker::UpdateChecker(ReceiverSettings& settings, const QString& currentVersion, bool preferTurkish,
                             OpenUrlFn openUrl, CopyTextFn copyText, QObject* parent)
    : QObject(parent),
      m_settings(settings),
      m_currentVersion(currentVersion),
      m_preferTurkish(preferTurkish),
      m_installKind(detectInstallKind()),
      m_updateCommand(buildUpdateCommand(m_installKind)),
      m_openUrl(std::move(openUrl)),
      m_copyText(std::move(copyText)) {
    m_network.setTransferTimeout(kRequestTimeoutMs);
}

QString UpdateChecker::detectInstallKind() {
#ifdef _WIN32
    return QStringLiteral("windows");
#else
    return qEnvironmentVariableIsSet("YANSIT_LAUNCHER") ? QStringLiteral("pardus-portable")
                                                        : QStringLiteral("pardus-deb");
#endif
}

QString UpdateChecker::buildUpdateCommand(const QString& installKind) {
    if (installKind == QLatin1String("pardus-portable"))
        return QStringLiteral("wget -nv -O- https://ozkanceng.github.io/casthop/kur | sh");
    if (installKind == QLatin1String("pardus-deb"))
        return QStringLiteral(
            "wget -qO /tmp/casthop_amd64.deb https://github.com/ozkanceng/casthop/releases/latest/download/casthop_amd64.deb && sudo apt install -y /tmp/casthop_amd64.deb");
    return {};
}

QString UpdateChecker::manifestChannel(const QString& installKind) {
    return installKind == QLatin1String("windows") ? QStringLiteral("windows") : QStringLiteral("pardus");
}

QUrl UpdateChecker::manifestUrl() {
    const QString overrideUrl = qEnvironmentVariable("YANSIT_UPDATE_URL").trimmed();
    if (!overrideUrl.isEmpty()) {
        const QUrl custom(overrideUrl);
        if (custom.isValid() && (custom.scheme() == QLatin1String("https") || custom.scheme() == QLatin1String("http")
                                 || custom.scheme() == QLatin1String("file"))) {
            return custom;
        }
    }
    return QUrl(QString::fromLatin1(kDefaultManifestUrl));
}

void UpdateChecker::checkIfDue() {
    if (!m_settings.autoCheckUpdates())
        return;
    if (qEnvironmentVariable("YANSIT_NO_UPDATE_CHECK") == QLatin1String("1"))
        return;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    const qint64 last = m_settings.lastUpdateCheckEpoch();
    // Test URL'si verilmişse bekleme süresini atla.
    if (qEnvironmentVariableIsEmpty("YANSIT_UPDATE_URL") && last > 0 && (now - last) < kCheckIntervalSecs)
        return;
    startRequest(/*manual=*/false);
}

void UpdateChecker::checkNow() {
    m_sessionDismissed = false;
    startRequest(/*manual=*/true);
}

void UpdateChecker::startRequest(bool manual) {
    if (m_checking)
        return;
    const QUrl url = manifestUrl();
    if (!url.isValid())
        return;

    m_checking = true;
    if (manual)
        m_statusMessage = tr("Checking for updates…");
    emit changed();

    QNetworkRequest req(url);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("CastHop-UpdateCheck"));
    req.setTransferTimeout(kRequestTimeoutMs);

    QNetworkReply* reply = m_network.get(req);
    m_activeReply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, reply, manual] { handleReply(reply, manual); });
}

void UpdateChecker::handleReply(QNetworkReply* reply, bool manual) {
    reply->deleteLater();
    if (m_activeReply == reply)
        m_activeReply = nullptr;

    m_checking = false;

    if (reply->error() != QNetworkReply::NoError) {
        qCDebug(lcUpdate) << "güncelleme denetimi atlandı:" << reply->errorString();
        if (manual)
            m_statusMessage = tr("Could not check for updates. Check the internet connection.");
        emit changed();
        return;
    }

    const QByteArray payload = reply->read(kMaxManifestBytes + 1);
    if (payload.size() > kMaxManifestBytes) {
        if (manual)
            m_statusMessage = tr("Could not read update information.");
        emit changed();
        return;
    }

    m_settings.setLastUpdateCheckEpoch(QDateTime::currentSecsSinceEpoch());

    const auto release = parseUpdateManifest(payload, manifestChannel(m_installKind), m_preferTurkish);
    const auto current = SemVer::parse(m_currentVersion);
    if (!release || !current) {
        if (manual)
            m_statusMessage = tr("Could not read update information.");
        emit changed();
        return;
    }

    const auto candidate = SemVer::parse(release->version);
    if (candidate && SemVer::isNewer(*candidate, *current)) {
        const bool skipped = !manual && m_settings.skippedUpdateVersion() == release->version;
        m_latestVersion = release->version;
        m_releaseNotes = release->notes;
        m_downloadUrl = release->url;
        m_updateAvailable = !skipped;
        if (manual)
            m_settings.setSkippedUpdateVersion({});
        m_statusMessage = tr("Update available: v%1").arg(release->version);
        qCInfo(lcUpdate) << "yeni sürüm bulundu:" << release->version << "(mevcut:" << m_currentVersion << ")";
    } else {
        m_updateAvailable = false;
        m_latestVersion = release->version;
        m_releaseNotes.clear();
        m_downloadUrl = release->url;
        if (manual)
            m_statusMessage = tr("CastHop is up to date (v%1).").arg(m_currentVersion);
    }
    emit changed();
}

void UpdateChecker::dismissForSession() {
    if (m_sessionDismissed)
        return;
    m_sessionDismissed = true;
    emit changed();
}

void UpdateChecker::skipVersion() {
    if (!m_latestVersion.isEmpty())
        m_settings.setSkippedUpdateVersion(m_latestVersion);
    m_updateAvailable = false;
    m_sessionDismissed = true;
    emit changed();
}

bool UpdateChecker::openDownloadUrl() {
    const QString target = isSafeDownloadUrl(m_downloadUrl) ? m_downloadUrl : QString::fromLatin1(kFallbackReleaseUrl);
    if (!m_openUrl)
        return false;
    return m_openUrl(QUrl(target));
}

void UpdateChecker::copyUpdateCommand() {
    if (m_updateCommand.isEmpty() || !m_copyText)
        return;
    m_copyText(m_updateCommand);
    m_commandCopied = true;
    emit changed();
    QTimer::singleShot(2500, this, [this] {
        if (!m_commandCopied)
            return;
        m_commandCopied = false;
        emit changed();
    });
}

} // namespace yansit
