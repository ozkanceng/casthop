#include "app/ReceiverService.h"

#include "app/NetInfo.h"
#include "common/Clock.h"
#include "net/Connection.h"
#include "net/MdnsPublisher.h"
#include "net/SecureFile.h"
#include "net/Server.h"
#include "protocol/Qr.h"

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLoggingCategory>
#include <QSslSocket>

#include <filesystem>
#include <type_traits>

Q_LOGGING_CATEGORY(lcRecv, "yansit.receiver")

namespace yansit {

using namespace protocol;
using session::Actions;
using session::ConnId;
using session::SessionManager;
using Role = net::Connection::Role;

namespace {

constexpr int kTickIntervalMs = 250;
constexpr int kReportIntervalMs = 500;

SessionManager::Clock::time_point steadyNow() { return SessionManager::Clock::now(); }

QString hex(ByteView b) { return QString::fromStdString(toHex(b)); }

template <std::size_t N>
std::optional<std::array<std::uint8_t, N>> arrayFromHex(const QString& s) {
    try {
        const Bytes b = fromHex(s.toStdString());
        if (b.size() != N)
            return std::nullopt;
        return toArray<N>(b);
    } catch (const std::invalid_argument&) {
        return std::nullopt;
    }
}

/// UTF-16 keeps user names outside the ANSI code page intact on Windows.
std::filesystem::path toPath(const QString& path) { return std::filesystem::path(path.toStdU16String()); }

/// These files hold the pairing token and keys. QFile permissions would do nothing on Windows.
bool writePrivateJson(const QString& path, const QByteArray& data) {
    try {
        net::writeFileAtomic(toPath(path), data.toStdString(), net::FileAccess::Private);
        return true;
    } catch (const std::exception&) {
        return false;
    }
}

} // namespace

ReceiverService::ReceiverService(Options options, QObject* parent) : QObject(parent), m_options(std::move(options)) {
    m_video = new media::VideoPipeline(m_options.sinkMode, this);
    m_audio = new media::AudioPipeline(audioSinkMode(), this);
    connect(m_video, &media::VideoPipeline::glRenderFailed, this, &ReceiverService::switchToCpuRenderer);
    connect(m_video, &media::VideoPipeline::keyframeNeeded, this, [this](std::uint8_t reason) {
        KeyframeRequest r;
        r.reason = reason;
        sendToControl(r);
    });
    connect(m_video, &media::VideoPipeline::firstFrameRendered, this, [this] {
        if (!m_sessionId || m_firstFrameUs)
            return;
        m_firstFrameUs = monotonicUs();
        qCInfo(lcRecv) << "ilk kare çizildi:" << (*m_firstFrameUs - m_sessionStartUs) / 1000 << "ms";
        refreshState();
    });
    connect(m_video, &media::VideoPipeline::videoSizeChanged, this, [this](int w, int h) {
        qCInfo(lcRecv) << "video boyutu:" << QStringLiteral("%1x%2").arg(w).arg(h);
        m_videoWidth = w;
        m_videoHeight = h;
        emit videoSizeChanged();
    });

    m_tickTimer.setInterval(kTickIntervalMs);
    connect(&m_tickTimer, &QTimer::timeout, this, &ReceiverService::tick);
    m_reportTimer.setInterval(kReportIntervalMs);
    connect(&m_reportTimer, &QTimer::timeout, this, &ReceiverService::sendReport);
}

ReceiverService::~ReceiverService() {
    if (m_mdns)
        m_mdns->stop();
    // Bağlantıların kapanma geri çağrıları yok edilen nesneye ulaşmasın.
    for (const auto& c : std::as_const(m_conns))
        if (c)
            c->detachHandlers();
    m_conns.clear();
}

session::ReceiverInfo ReceiverService::receiverInfo() const {
    session::ReceiverInfo info;
    info.name = m_options.name.toStdString();
    info.displayWidth = m_options.displayWidth;
    info.displayHeight = m_options.displayHeight;
    info.displayRefresh = m_options.displayRefresh;
    const auto h264 = media::availableDecoders(Codec::H264);
    if (!h264.empty())
        info.videoDecoders.push_back(
            {Codec::H264, std::uint8_t(CodecSupport::Preferred | (h264.front().hardware ? CodecSupport::Hardware : 0))});
    // H.265 yalnız donanımla çözülebiliyorsa sunulur: yazılımda 1080p60 HEVC eski tahtalarda yetişmez (RISKS R5).
    // YANSIT_ALLOW_SW_HEVC=1 yazılım çözücüyü de sunar (testler).
    const auto h265 = media::availableDecoders(Codec::H265);
    if (!h265.empty() && (h265.front().hardware || qEnvironmentVariableIntValue("YANSIT_ALLOW_SW_HEVC") == 1))
        info.videoDecoders.push_back({Codec::H265, std::uint8_t(h265.front().hardware ? CodecSupport::Hardware : 0)});
    const auto audioMode = audioSinkMode();
    if (media::AudioPipeline::opusAvailable(audioMode))
        info.audioDecoders.push_back({Codec::Opus, CodecSupport::Preferred});
    if (media::AudioPipeline::aacAvailable(audioMode))
        info.audioDecoders.push_back({Codec::AacLc, 0});
    info.audioEnabled = m_options.audio && !info.audioDecoders.empty();
    return info;
}

bool ReceiverService::start() {
    const QString identityDir = QDir(m_options.dataDir).filePath(QStringLiteral("identity"));
    try {
        m_identity = net::loadOrCreateIdentity(toPath(identityDir), m_options.name.toStdString());
    } catch (const std::exception& e) {
        setError(tr("Security certificate could not be created: %1").arg(QString::fromUtf8(e.what())));
        return false;
    }

    m_pairing = std::make_unique<session::PairingManager>(steadyNow());
    loadTrusted();
    emit trustedChanged();
    m_pairing->onCredentialsChanged = [this] { updateCredentials(); };
    m_pairing->onTrustedChanged = [this] {
        saveTrusted();
        emit trustedChanged();
    };

    const session::ReceiverInfo info = receiverInfo();
    if (info.videoDecoders.empty()) {
#ifdef Q_OS_WIN
        setError(tr("No H.264 video decoder found. Reinstall CastHop."));
#else
        setError(tr("No H.264 video decoder found. Install the gstreamer1.0-libav package."));
#endif
        return false;
    }
    m_sessions = std::make_unique<SessionManager>(*m_pairing, info,
                                                  SessionManager::Policy{m_options.approvalTimeout,
                                                                         std::chrono::seconds(10)});

    try {
        m_server = new net::Server(m_identity, this);
    } catch (const std::exception& e) {
        setError(QString::fromUtf8(e.what()));
        return false;
    }
    m_server->onEncrypted = [this](QSslSocket* s) { accept(s); };
    if (!m_server->listen(QHostAddress::AnyIPv4, m_options.port)) {
        setError(tr("Port %1 is in use. Is another copy of CastHop already running?").arg(m_options.port));
        return false;
    }

    m_net = new NetInfo(this);
    connect(m_net, &NetInfo::addressesChanged, this, &ReceiverService::updateCredentials);
    updateCredentials();

    // mDNS yalnız bulma kolaylığı; başarısızlığı QR akışını etkilemez.
    m_mdns = std::make_unique<net::MdnsPublisher>();
    m_mdns->publish(m_options.name.toStdString(), m_options.port, m_identity.fingerprint);

    m_tickTimer.start();
    m_reportTimer.start();
    m_state = State::Waiting;
    emit stateChanged();
    qCInfo(lcRecv) << "dinleniyor: port" << m_options.port << "parmak izi" << hex(m_identity.fingerprint);
    return true;
}

void ReceiverService::shutdown() {
    if (m_sessions)
        apply(m_sessions->endSession(ByeReason::ReceiverClosing));
    m_tickTimer.stop();
    m_reportTimer.stop();
    if (m_server)
        m_server->close();
    m_video->stop();
    m_audio->stop();
}

void ReceiverService::setError(const QString& message) {
    qCCritical(lcRecv).noquote() << message;
    m_error = message;
    m_state = State::Failed;
    emit errorChanged();
    emit stateChanged();
}

QString ReceiverService::stateName() const {
    switch (m_state) {
    case State::Starting:
        return QStringLiteral("starting");
    case State::Waiting:
        return QStringLiteral("waiting");
    case State::Connecting:
        return QStringLiteral("connecting");
    case State::Streaming:
        return QStringLiteral("streaming");
    case State::Paused:
        return QStringLiteral("paused");
    case State::Reconnecting:
        return QStringLiteral("reconnecting");
    case State::Failed:
        return QStringLiteral("failed");
    }
    return {};
}

QString ReceiverService::qrImageSource() const {
    if (m_qrUri.isEmpty())
        return {};
    return QStringLiteral("image://qr/") + QString::fromLatin1(m_qrUri.toUtf8().toHex());
}

QString ReceiverService::pin() const { return m_pairing ? QString::fromStdString(m_pairing->pin()) : QString(); }

QString ReceiverService::senderName() const {
    if (!m_sessions || !m_sessions->session())
        return {};
    return QString::fromStdString(m_sessions->session()->sender.deviceName);
}

QString ReceiverService::pendingSenderName() const {
    if (!m_sessions || !m_sessions->pending())
        return {};
    return QString::fromStdString(m_sessions->pending()->hello.deviceName);
}

void ReceiverService::setVideoItem(QObject* item) { m_video->setVideoItem(item); }

media::AudioPipeline::SinkMode ReceiverService::audioSinkMode() const {
    return m_options.sinkMode == media::VideoPipeline::SinkMode::Fake ? media::AudioPipeline::SinkMode::Fake
                                                                      : media::AudioPipeline::SinkMode::Device;
}

QString ReceiverService::renderer() const {
    switch (m_video->sinkMode()) {
    case media::VideoPipeline::SinkMode::Gl:
        return QStringLiteral("gl");
    case media::VideoPipeline::SinkMode::Cpu:
        return QStringLiteral("cpu");
    case media::VideoPipeline::SinkMode::Fake:
        break;
    }
    return {};
}

void ReceiverService::switchToCpuRenderer(const QString& reason) {
    if (m_video->sinkMode() != media::VideoPipeline::SinkMode::Gl)
        return;
    qCWarning(lcRecv) << "CPU çizime geçiliyor:" << reason;
    m_video->useCpuSink();
    emit rendererChanged();
}

void ReceiverService::accept(QSslSocket* socket) {
    const ConnId id = ++m_nextId;
    net::Connection::Handlers h;
    h.control = [this](net::Connection& c, ControlMessage m, std::uint64_t t) { onControl(c, std::move(m), t); };
    h.media = [this](net::Connection& c, const FrameHeader& hd, Bytes&& p, std::uint64_t t) {
        onMedia(c, hd, std::move(p), t);
    };
    h.closed = [this](net::Connection& c) { onClosed(c); };
    auto* conn = new net::Connection(id, socket, m_options.name.toStdString(), std::move(h), this);
    m_conns.insert(id, conn);
    qCDebug(lcRecv) << "yeni bağlantı" << id << conn->peer();
}

void ReceiverService::onControl(net::Connection& c, ControlMessage msg, std::uint64_t recvUs) {
    if (c.role() == Role::Unknown)
        onFirstMessage(c, std::move(msg));
    else
        onSessionMessage(c, std::move(msg), recvUs);
}

void ReceiverService::onFirstMessage(net::Connection& c, ControlMessage&& msg) {
    const ConnId id = c.id();
    if (auto* hello = std::get_if<Hello>(&msg)) {
        qCInfo(lcRecv) << "HELLO:" << QString::fromStdString(hello->deviceName) << "yöntem" << hello->authMethod
                       << c.peer();
        Actions a = m_sessions->onHello(id, *hello, c.serverNonce(), m_identity.fingerprint, steadyNow());
        if (m_sessions->isControl(id))
            c.setRole(Role::Control);
        else if (m_sessions->pending() && m_sessions->pending()->conn == id)
            c.setRole(Role::Pending);
        apply(std::move(a));
    } else if (auto* resume = std::get_if<Resume>(&msg)) {
        Actions a = m_sessions->onResume(id, *resume, c.serverNonce());
        if (m_sessions->isControl(id)) {
            c.setRole(Role::Control);
            qCInfo(lcRecv) << "oturum sürdürüldü";
        }
        apply(std::move(a));
    } else if (auto* attach = std::get_if<Attach>(&msg)) {
        Actions a = m_sessions->onAttach(id, *attach, c.serverNonce());
        if (m_sessions->mediaSlotOf(id) != SessionManager::MediaSlot::None)
            c.setRole(Role::Media);
        apply(std::move(a));
    } else {
        Reject r;
        r.reason = RejectReason::Malformed;
        c.sendControl(r);
        c.closeGracefully();
    }
}

void ReceiverService::onSessionMessage(net::Connection& c, ControlMessage&& msg, std::uint64_t recvUs) {
    const ConnId id = c.id();
    if (std::holds_alternative<Bye>(msg)) {
        qCInfo(lcRecv) << "gönderici BYE:" << std::get<Bye>(msg).reason;
        apply(m_sessions->onBye(id));
        return;
    }
    if (!m_sessions->isControl(id))
        return; // onay bekleyen bağlantı: BYE dışında bir şey beklenmiyor

    std::visit(
        [&](auto& m) {
            using T = std::decay_t<decltype(m)>;
            if constexpr (std::is_same_v<T, Ping>) {
                Pong p;
                p.pingId = m.pingId;
                p.t0Us = m.t0Us;
                p.t1Us = recvUs;
                p.t2Us = monotonicUs();
                c.sendControl(p);
                if (m.offsetUs) {
                    m_video->setClockOffset(*m.offsetUs);
                    m_audio->setClockOffset(*m.offsetUs);
                }
            } else if constexpr (std::is_same_v<T, StreamConfig>) {
                qCInfo(lcRecv) << "akış:" << m.streamKind << "codec" << m.codec << m.width.value_or(0) << "x"
                               << m.height.value_or(0) << "@" << m.fps.value_or(0);
            } else if constexpr (std::is_same_v<T, SenderStats>) {
                m_senderBitrate = m.bitrateBps;
                m_senderFpsX100 = m.fpsX100;
            } else if constexpr (std::is_same_v<T, Pause>) {
                m_paused = true;
                refreshState();
            } else if constexpr (std::is_same_v<T, Unpause>) {
                m_paused = false;
                refreshState();
            } else {
                qCDebug(lcRecv) << "beklenmeyen CONTROL mesajı yok sayıldı";
            }
        },
        msg);
}

void ReceiverService::onMedia(net::Connection& c, const FrameHeader& header, Bytes&& payload, std::uint64_t recvUs) {
    const auto slot = m_sessions->mediaSlotOf(c.id());
    if (slot == SessionManager::MediaSlot::Video && header.type == FrameType::Video)
        m_video->pushFrame(header, std::move(payload), recvUs);
    else if (slot == SessionManager::MediaSlot::Audio && header.type == FrameType::Audio)
        m_audio->pushFrame(header, std::move(payload), recvUs);
}

void ReceiverService::onClosed(net::Connection& c) {
    m_conns.remove(c.id());
    if (m_sessions)
        apply(m_sessions->onConnectionClosed(c.id(), steadyNow()));
}

void ReceiverService::apply(Actions actions) {
    for (const auto& [id, msg] : actions.send)
        if (auto c = m_conns.value(id))
            c->sendControl(msg);
    for (ConnId id : actions.close)
        if (auto c = m_conns.value(id))
            c->closeGracefully();
    refreshState();
}

void ReceiverService::sendToControl(const ControlMessage& msg) {
    if (!m_sessions || !m_sessions->session() || !m_sessions->session()->control)
        return;
    if (auto c = m_conns.value(*m_sessions->session()->control))
        c->sendControl(msg);
}

void ReceiverService::refreshState() {
    if (!m_sessions)
        return;
    const auto& s = m_sessions->session();

    // Oturum değişimi: yeni oturumda boru hattı sıfırdan başlar.
    const std::optional<Id16> current = s ? std::optional<Id16>(s->sessionId) : std::nullopt;
    if (current != m_sessionId) {
        if (m_sessionId)
            finishSessionStats();
        m_sessionId = current;
        m_video->reset();
        m_audio->reset();
        m_firstFrameUs.reset();
        m_paused = false;
        m_sessionC2dSumUs = m_sessionC2dCount = 0;
        m_sessionAudioLatencySumUs = m_sessionAudioLatencyCount = 0;
        m_senderBitrate = 0;
        m_senderFpsX100 = 0;
        m_videoWidth = m_videoHeight = 0;
        m_lastReportUs = monotonicUs();
        m_sessionStartUs = m_lastReportUs;
        if (current)
            qCInfo(lcRecv) << "oturum başladı:" << senderName();
        else
            qCInfo(lcRecv) << "oturum bitti";
    }

    State next = State::Waiting;
    if (m_state == State::Failed)
        next = State::Failed;
    else if (s && !s->control)
        next = State::Reconnecting;
    else if (s && m_paused)
        next = State::Paused;
    else if (s && m_firstFrameUs)
        next = State::Streaming;
    else if (s)
        next = State::Connecting;
    if (next != m_state) {
        m_state = next;
        emit stateChanged();
    }

    const bool hasPending = m_sessions->pending().has_value();
    if (hasPending != m_hadPending) {
        m_hadPending = hasPending;
        emit pendingChanged();
    }
}

void ReceiverService::tick() {
    if (!m_sessions)
        return;
    m_pairing->tick(steadyNow());
    const bool hadSession = m_sessions->session().has_value();
    const Actions a = m_sessions->tick(steadyNow());
    if (!a.empty())
        apply(a);
    else if (hadSession && !m_sessions->session())
        refreshState(); // the RESUME window ran out with no connection left to close
}

void ReceiverService::sendReport() {
    const std::uint64_t now = monotonicUs();
    const std::uint64_t intervalUs = std::max<std::uint64_t>(now - m_lastReportUs, 1);
    m_lastReportUs = now;
    const media::VideoPipeline::Stats st = m_video->takeStats();
    const media::AudioPipeline::Stats at = m_audio->takeStats();
    // Dudak senkronu: ses, görüntünün ekrana çıktığı anda çalınsın (çizim gecikmesi + bir ekran karesi).
    if (st.c2dCount > 0) {
        const std::uint64_t frameUs = 1'000'000 / std::max<std::uint8_t>(m_options.displayRefresh, 1);
        m_audio->setTargetLatencyUs(st.c2dSumUs / st.c2dCount + frameUs);
    }
    if (!m_sessions || !m_sessions->session() || !m_sessions->session()->control)
        return;

    ReceiverReport r;
    r.intervalMs = std::uint16_t(std::min<std::uint64_t>(intervalUs / 1000, 0xFFFF));
    r.framesReceived = st.framesReceived;
    r.framesRendered = st.framesRendered;
    r.framesDropped = st.framesDropped;
    r.bytesReceived = std::uint32_t(std::min<std::uint64_t>(st.bytesReceived, 0xFFFFFFFF));
    r.c2rAvgUs = st.c2rCount ? std::uint32_t(st.c2rSumUs / st.c2rCount) : 0;
    r.c2rMinUs = st.c2rMinUs;
    r.c2rMaxUs = st.c2rMaxUs;
    r.c2dAvgUs = st.c2dCount ? std::uint32_t(std::min<std::uint64_t>(st.c2dSumUs / st.c2dCount, 0xFFFFFFFF)) : 0;
    r.decodeBacklog = st.backlog;
    r.audioLate = std::uint16_t(std::min<std::uint32_t>(at.late, 0xFFFF));
    m_sessionAudioLatencySumUs += at.latencySumUs;
    m_sessionAudioLatencyCount += at.latencyCount;
    sendToControl(r);

    m_sessionC2dSumUs += st.c2dSumUs;
    m_sessionC2dCount += st.c2dCount;

    QVariantMap map;
    map.insert(QStringLiteral("latencyMs"), st.c2dCount ? int(r.c2dAvgUs / 1000) : -1);
    map.insert(QStringLiteral("fps"), double(st.framesRendered) * 1e6 / double(intervalUs));
    map.insert(QStringLiteral("bitrateKbps"), int(double(st.bytesReceived) * 8e3 / double(intervalUs)));
    map.insert(QStringLiteral("dropped"), int(m_video->totalDropped()));
    map.insert(QStringLiteral("decoder"), m_video->decoderName());
    map.insert(QStringLiteral("hardware"), m_video->hardwareDecoding());
    map.insert(QStringLiteral("width"), m_videoWidth);
    // Gecikme deseni ekrandaysa (tools/latency) gerçek uçtan uca ölçüm.
    map.insert(QStringLiteral("e2eMs"), st.e2eCount ? int(st.e2eSumUs / st.e2eCount / 1000) : -1);
    map.insert(QStringLiteral("e2eP95Ms"), int(m_video->e2eP95Us() / 1000));
    map.insert(QStringLiteral("height"), m_videoHeight);
    m_statsMap = map;
    emit statsChanged();
}

void ReceiverService::finishSessionStats() {
    if (m_options.statsFile.isEmpty())
        return;
    QJsonObject o;
    o.insert(QStringLiteral("session_id"), hex(*m_sessionId));
    o.insert(QStringLiteral("frames_received"), qint64(m_video->totalReceived()));
    o.insert(QStringLiteral("frames_rendered"), qint64(m_video->totalRendered()));
    o.insert(QStringLiteral("frames_dropped"), qint64(m_video->totalDropped()));
    o.insert(QStringLiteral("first_frame_ms"),
             m_firstFrameUs ? QJsonValue(double(*m_firstFrameUs - m_sessionStartUs) / 1000.0) : QJsonValue());
    o.insert(QStringLiteral("c2d_avg_ms"),
             m_sessionC2dCount ? QJsonValue(double(m_sessionC2dSumUs) / double(m_sessionC2dCount) / 1000.0)
                               : QJsonValue());
    o.insert(QStringLiteral("decoder"), m_video->decoderName());
    o.insert(QStringLiteral("hardware"), m_video->hardwareDecoding());
    if (const auto e2e = m_video->e2eAvgUs()) {
        o.insert(QStringLiteral("e2e_avg_ms"), double(e2e) / 1000.0);
        o.insert(QStringLiteral("e2e_p95_ms"), double(m_video->e2eP95Us()) / 1000.0);
    }
    o.insert(QStringLiteral("audio_received"), qint64(m_audio->totalReceived()));
    o.insert(QStringLiteral("audio_played"), qint64(m_audio->totalPlayed()));
    o.insert(QStringLiteral("audio_late"), qint64(m_audio->totalLate()));
    o.insert(QStringLiteral("audio_latency_ms"),
             m_sessionAudioLatencyCount
                 ? QJsonValue(double(m_sessionAudioLatencySumUs) / double(m_sessionAudioLatencyCount) / 1000.0)
                 : QJsonValue());
    QFile f(m_options.statsFile);
    if (f.open(QIODevice::Append | QIODevice::WriteOnly)) {
        f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
        f.write("\n");
    } else {
        qCWarning(lcRecv) << "istatistik dosyası yazılamadı:" << m_options.statsFile;
    }
}

void ReceiverService::updateCredentials() {
    QStringList hosts = m_options.qrHosts;
    if (hosts.isEmpty() && m_net)
        hosts = m_net->addresses();
    while (hosts.size() > int(kMaxQrHosts))
        hosts.removeLast();
    m_addresses = hosts;

    QrPayload qr;
    for (const QString& h : hosts)
        qr.hosts.push_back(h.toStdString());
    qr.port = m_options.port;
    qr.token = m_pairing->qrToken();
    qr.certFingerprint = m_identity.fingerprint;
    qr.name = m_options.name.toStdString();
    try {
        m_qrUri = qr.hosts.empty() ? QString() : QString::fromStdString(buildQrUri(qr));
    } catch (const std::invalid_argument& e) {
        qCWarning(lcRecv) << "QR üretilemedi:" << e.what();
        m_qrUri.clear();
    }
    emit credentialsChanged();
    writePairingFile();
}

void ReceiverService::writePairingFile() {
    if (m_options.pairingFile.isEmpty())
        return;
    QJsonObject o;
    o.insert(QStringLiteral("uri"), m_qrUri);
    o.insert(QStringLiteral("pin"), pin());
    o.insert(QStringLiteral("port"), m_options.port);
    o.insert(QStringLiteral("fingerprint"), hex(m_identity.fingerprint));
    o.insert(QStringLiteral("token"), QString::fromStdString(base64UrlNoPad(m_pairing->qrToken())));
    if (!writePrivateJson(m_options.pairingFile, QJsonDocument(o).toJson(QJsonDocument::Compact)))
        qCWarning(lcRecv) << "eşleştirme dosyası yazılamadı:" << m_options.pairingFile;
}

void ReceiverService::loadTrusted() {
    QFile f(QDir(m_options.dataDir).filePath(QStringLiteral("trusted.json")));
    if (!f.open(QIODevice::ReadOnly))
        return;
    const QJsonArray arr = QJsonDocument::fromJson(f.readAll()).array();
    std::vector<session::TrustedSender> list;
    for (const QJsonValue& v : arr) {
        const QJsonObject o = v.toObject();
        const auto keyId = arrayFromHex<16>(o.value(QStringLiteral("key_id")).toString());
        const auto pairKey = arrayFromHex<32>(o.value(QStringLiteral("pair_key")).toString());
        const auto deviceId = arrayFromHex<16>(o.value(QStringLiteral("device_id")).toString());
        if (!keyId || !pairKey || !deviceId)
            continue;
        list.push_back({*keyId, *pairKey, *deviceId, o.value(QStringLiteral("device_name")).toString().toStdString()});
    }
    m_pairing->setTrustedSenders(std::move(list));
}

void ReceiverService::saveTrusted() {
    QJsonArray arr;
    for (const auto& t : m_pairing->trustedSenders()) {
        QJsonObject o;
        o.insert(QStringLiteral("key_id"), hex(t.keyId));
        o.insert(QStringLiteral("pair_key"), hex(t.pairKey));
        o.insert(QStringLiteral("device_id"), hex(t.deviceId));
        o.insert(QStringLiteral("device_name"), QString::fromStdString(t.deviceName));
        arr.append(o);
    }
    QDir().mkpath(m_options.dataDir);
    if (!writePrivateJson(QDir(m_options.dataDir).filePath(QStringLiteral("trusted.json")), QJsonDocument(arr).toJson()))
        qCWarning(lcRecv) << "eşleştirmeler kaydedilemedi";
}

void ReceiverService::setReceiverName(const QString& name) {
    const QString n = name.trimmed();
    if (n.isEmpty() || n == m_options.name)
        return;
    m_options.name = n;
    if (m_sessions)
        m_sessions->setReceiverInfo(receiverInfo());
    if (m_mdns)
        m_mdns->publish(n.toStdString(), m_options.port, m_identity.fingerprint);
    if (m_pairing)
        updateCredentials();
    emit receiverNameChanged();
}

void ReceiverService::setAudioEnabled(bool on) {
    if (on == m_options.audio)
        return;
    m_options.audio = on;
    if (m_sessions)
        m_sessions->setReceiverInfo(receiverInfo());
}

QVariantList ReceiverService::trustedSenders() const {
    QVariantList out;
    if (!m_pairing)
        return out;
    for (const auto& t : m_pairing->trustedSenders()) {
        QVariantMap m;
        m.insert(QStringLiteral("keyId"), hex(t.keyId));
        m.insert(QStringLiteral("name"), QString::fromStdString(t.deviceName));
        out.append(m);
    }
    return out;
}

void ReceiverService::forgetSender(const QString& keyIdHex) {
    if (!m_pairing)
        return;
    if (const auto id = arrayFromHex<16>(keyIdHex))
        m_pairing->removeTrusted(*id);
}

void ReceiverService::approvePending() {
    if (!m_sessions || !m_sessions->pending())
        return;
    const ConnId id = m_sessions->pending()->conn;
    Actions a = m_sessions->resolvePending(true);
    if (auto c = m_conns.value(id); c && m_sessions->isControl(id))
        c->setRole(Role::Control);
    apply(std::move(a));
}

void ReceiverService::denyPending() {
    if (m_sessions)
        apply(m_sessions->resolvePending(false));
}

void ReceiverService::stopSession() {
    if (m_sessions)
        apply(m_sessions->endSession(ByeReason::UserStop));
}

} // namespace yansit
