#include "net/Connection.h"

#include "protocol/Crypto.h"
#include "protocol/Errors.h"

#include <QHostAddress>
#include <QLoggingCategory>

Q_LOGGING_CATEGORY(lcConn, "yansit.net")

namespace yansit::net {

using namespace protocol;

Connection::Connection(session::ConnId id, QSslSocket* socket, std::string receiverName, Handlers handlers,
                       QObject* parent)
    : QObject(parent), m_id(id), m_socket(socket), m_handlers(std::move(handlers)),
      m_serverNonce(randomArray<16>()) {
    socket->setParent(this);
    socket->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    m_peer = socket->peerAddress().toString();

    connect(socket, &QSslSocket::readyRead, this, &Connection::onReadyRead);
    connect(socket, &QSslSocket::disconnected, this, &Connection::finish);
    connect(socket, &QSslSocket::errorOccurred, this, [this](QAbstractSocket::SocketError err) {
        if (err != QAbstractSocket::RemoteHostClosedError)
            qCDebug(lcConn) << "bağlantı" << m_id << "hata:" << err;
        finish();
    });

    m_deadline.setSingleShot(true);
    connect(&m_deadline, &QTimer::timeout, this, [this] {
        qCInfo(lcConn) << "bağlantı" << m_id << (m_role == Role::Unknown ? "ilk mesaj gelmedi" : "sessiz kaldı");
        abort();
    });
    m_deadline.start(kFirstMessageTimeoutMs);

    Challenge c;
    c.minVersion = 1;
    c.maxVersion = 1;
    c.serverNonce = m_serverNonce;
    if (!receiverName.empty())
        c.receiverName = std::move(receiverName);
    sendControl(c);

    // Sokette zaten bekleyen veri olabilir (istemci CHALLENGE'ı beklemeden yazmışsa).
    if (socket->bytesAvailable() > 0)
        QMetaObject::invokeMethod(this, &Connection::onReadyRead, Qt::QueuedConnection);
}

Connection::~Connection() = default;

void Connection::setRole(Role role) {
    m_role = role;
    if (role == Role::Control)
        m_deadline.start(kControlSilenceTimeoutMs);
    else
        m_deadline.stop(); // medya bağlantısı CONTROL'e bağlı yaşar; PAUSE sırasında sessiz kalabilir
}

void Connection::sendControl(const ControlMessage& msg) {
    if (!m_socket || m_finished)
        return;
    const Bytes payload = encodeControl(msg);
    const Bytes frame = encodeFrame(FrameType::Control, 0, monotonicUs(), m_sendSeq++, payload);
    m_socket->write(reinterpret_cast<const char*>(frame.data()), qint64(frame.size()));
}

void Connection::closeGracefully() {
    if (m_closing || m_finished || !m_socket)
        return;
    m_closing = true;
    m_deadline.stop();
    m_socket->flush();
    m_socket->disconnectFromHost();
    if (m_socket && m_socket->state() != QAbstractSocket::UnconnectedState)
        QTimer::singleShot(2000, this, &Connection::abort);
}

void Connection::abort() {
    m_closing = true;
    if (m_socket)
        m_socket->abort();
    finish();
}

void Connection::finish() {
    if (m_finished)
        return;
    m_finished = true;
    m_closing = true;
    m_deadline.stop();
    if (m_handlers.closed)
        m_handlers.closed(*this);
    deleteLater();
}

void Connection::protocolViolation(const char* what) {
    qCInfo(lcConn) << "bağlantı" << m_id << "protokol ihlali:" << what;
    if (m_role == Role::Control || m_role == Role::Pending) {
        Bye b;
        b.reason = ByeReason::Malformed;
        sendControl(b);
    } else if (m_role == Role::Unknown) {
        Reject r;
        r.reason = RejectReason::Malformed;
        sendControl(r);
    }
    closeGracefully();
}

void Connection::onReadyRead() {
    if (!m_socket)
        return;
    while (!m_closing && m_socket && m_socket->bytesAvailable() > 0) {
        const QByteArray chunk = m_socket->read(256 * 1024);
        try {
            m_parser.feed(ByteView(reinterpret_cast<const std::uint8_t*>(chunk.constData()), std::size_t(chunk.size())));
        } catch (const ProtocolException& e) {
            // Çerçeve sınırları kayboldu: yanıt göndermeden kapat.
            qCInfo(lcConn) << "bağlantı" << m_id << "geçersiz çerçeve:" << QLatin1String(errorName(e.error()));
            abort();
            return;
        }
        while (!m_closing) {
            auto frame = m_parser.next();
            if (!frame)
                break;
            handleFrame(std::move(*frame));
        }
    }
}

void Connection::handleFrame(FrameParser::Frame&& frame) {
    const std::uint64_t recvUs = monotonicUs();
    if (m_role == Role::Control)
        m_deadline.start(kControlSilenceTimeoutMs);

    if (frame.header.type != FrameType::Control) {
        if (m_role != Role::Media)
            return protocolViolation("CONTROL dışı çerçeve");
        if (m_handlers.media)
            m_handlers.media(*this, frame.header, std::move(frame.payload), recvUs);
        return;
    }

    if (m_role == Role::Media)
        return protocolViolation("medya bağlantısında CONTROL");

    ControlMessage msg;
    try {
        msg = decodeControl(frame.payload);
    } catch (const ProtocolException& e) {
        qCInfo(lcConn) << "bağlantı" << m_id << "geçersiz mesaj:" << QLatin1String(errorName(e.error()));
        return protocolViolation("CONTROL ayrıştırılamadı");
    }
    if (m_handlers.control)
        m_handlers.control(*this, std::move(msg), recvUs);
}

} // namespace yansit::net
