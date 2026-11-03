#pragma once

#include "media/VideoPipeline.h"
#include "net/Identity.h"
#include "session/PairingManager.h"
#include "session/SessionManager.h"

#include <QHash>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QStringList>
#include <QTimer>
#include <QVariantMap>

#include <chrono>
#include <memory>
#include <optional>

class QSslSocket;

namespace yansit {

namespace net {
class Connection;
class Server;
} // namespace net

class NetInfo;

/**
 * Alıcının çekirdeği: TLS sunucusu, bağlantılar, oturum durum makinesi ve video boru hattını
 * birbirine bağlar. Arayüzden bağımsızdır (başsız modda da çalışır); QML özellikleri bilgi amaçlıdır.
 */
class ReceiverService : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString stateName READ stateName NOTIFY stateChanged)
    Q_PROPERTY(QString receiverName READ receiverName CONSTANT)
    Q_PROPERTY(int port READ port CONSTANT)
    Q_PROPERTY(QString qrUri READ qrUri NOTIFY credentialsChanged)
    Q_PROPERTY(QString qrImageSource READ qrImageSource NOTIFY credentialsChanged)
    Q_PROPERTY(QString pin READ pin NOTIFY credentialsChanged)
    Q_PROPERTY(QStringList addresses READ addresses NOTIFY credentialsChanged)
    Q_PROPERTY(QString senderName READ senderName NOTIFY stateChanged)
    Q_PROPERTY(QString pendingSenderName READ pendingSenderName NOTIFY pendingChanged)
    Q_PROPERTY(int pendingTimeoutMs READ pendingTimeoutMs NOTIFY pendingChanged)
    Q_PROPERTY(bool videoAvailable READ videoAvailable CONSTANT)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
    Q_PROPERTY(QVariantMap stats READ stats NOTIFY statsChanged)

public:
    enum class State { Starting, Waiting, Connecting, Streaming, Paused, Reconnecting, Failed };

    struct Options {
        QString name;
        quint16 port = 47800;
        /// Kalıcı veriler (TLS kimliği, eşleştirmeler).
        QString dataDir;
        media::VideoPipeline::SinkMode sinkMode = media::VideoPipeline::SinkMode::Gl;
        std::chrono::milliseconds approvalTimeout{std::chrono::seconds(20)};
        /// Boş değilse QR'daki adresler bunlar olur (test/özel ağ).
        QStringList qrHosts;
        /// Boş değilse eşleştirme bilgileri her değişimde bu JSON dosyasına yazılır (otomasyon).
        QString pairingFile;
        /// Boş değilse her oturum sonunda istatistik satırı (JSON Lines) eklenir.
        QString statsFile;
        std::uint16_t displayWidth = 1920;
        std::uint16_t displayHeight = 1080;
        std::uint8_t displayRefresh = 60;
    };

    explicit ReceiverService(Options options, QObject* parent = nullptr);
    ~ReceiverService() override;

    /// Kimliği yükler ve dinlemeye başlar. Başarısızsa errorMessage dolar.
    bool start();
    /// Etkin oturuma BYE(RECEIVER_CLOSING) gönderir ve bağlantıları kapatır.
    void shutdown();

    State state() const { return m_state; }
    QString stateName() const;
    QString receiverName() const { return m_options.name; }
    int port() const { return m_options.port; }
    QString qrUri() const { return m_qrUri; }
    /// QrImageProvider kaynağı; QR yoksa boş.
    QString qrImageSource() const;
    QString pin() const;
    QStringList addresses() const { return m_addresses; }
    QString senderName() const;
    QString pendingSenderName() const;
    int pendingTimeoutMs() const { return int(m_options.approvalTimeout.count()); }
    bool videoAvailable() const { return m_options.sinkMode == media::VideoPipeline::SinkMode::Gl; }
    QString errorMessage() const { return m_error; }
    QVariantMap stats() const { return m_statsMap; }
    const protocol::Key32& fingerprint() const { return m_identity.fingerprint; }

    media::VideoPipeline* video() const { return m_video; }

    Q_INVOKABLE void setVideoItem(QObject* item);
    Q_INVOKABLE void approvePending();
    Q_INVOKABLE void denyPending();
    Q_INVOKABLE void stopSession();

signals:
    void stateChanged();
    void credentialsChanged();
    void pendingChanged();
    void errorChanged();
    void statsChanged();

private:
    void accept(QSslSocket* socket);
    void onControl(net::Connection& c, protocol::ControlMessage msg, std::uint64_t recvUs);
    void onFirstMessage(net::Connection& c, protocol::ControlMessage&& msg);
    void onSessionMessage(net::Connection& c, protocol::ControlMessage&& msg, std::uint64_t recvUs);
    void onMedia(net::Connection& c, const protocol::FrameHeader& header, protocol::Bytes&& payload,
                 std::uint64_t recvUs);
    void onClosed(net::Connection& c);
    void apply(session::Actions actions);
    void refreshState();
    void tick();
    void sendReport();
    void sendToControl(const protocol::ControlMessage& msg);
    void updateCredentials();
    void writePairingFile();
    void finishSessionStats();
    void loadTrusted();
    void saveTrusted();
    void setError(const QString& message);
    session::ReceiverInfo receiverInfo() const;

    Options m_options;
    net::Identity m_identity;
    std::unique_ptr<session::PairingManager> m_pairing;
    std::unique_ptr<session::SessionManager> m_sessions;
    net::Server* m_server = nullptr;
    NetInfo* m_net = nullptr;
    media::VideoPipeline* m_video = nullptr;
    QHash<session::ConnId, QPointer<net::Connection>> m_conns;
    session::ConnId m_nextId = 0;
    QTimer m_tickTimer;
    QTimer m_reportTimer;

    State m_state = State::Starting;
    QString m_error;
    QString m_qrUri;
    QStringList m_addresses;
    QVariantMap m_statsMap;
    bool m_hadPending = false;

    // Etkin oturumun izlenen bilgileri.
    std::optional<protocol::Id16> m_sessionId;
    std::uint64_t m_sessionStartUs = 0;
    std::optional<std::uint64_t> m_firstFrameUs;
    bool m_paused = false;
    std::uint64_t m_lastReportUs = 0;
    std::uint64_t m_sessionC2dSumUs = 0;
    std::uint64_t m_sessionC2dCount = 0;
    std::uint32_t m_senderBitrate = 0;
    std::uint16_t m_senderFpsX100 = 0;
    int m_videoWidth = 0;
    int m_videoHeight = 0;
};

} // namespace yansit
