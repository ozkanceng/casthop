#include "net/Server.h"

#include <QLoggingCategory>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QTimer>

#include <unistd.h>

#include <memory>
#include <stdexcept>

Q_DECLARE_LOGGING_CATEGORY(lcConn)

namespace yansit::net {

Server::Server(const Identity& identity, QObject* parent) : QTcpServer(parent) {
    if (!QSslSocket::supportsSsl())
        throw std::runtime_error("Qt TLS desteği yok (OpenSSL arka ucu yüklenemedi)");

    const QSslCertificate cert(QByteArray::fromStdString(identity.certificatePem), QSsl::Pem);
    const QSslKey key(QByteArray::fromStdString(identity.privateKeyPem), QSsl::Ec, QSsl::Pem, QSsl::PrivateKey);
    if (cert.isNull() || key.isNull())
        throw std::runtime_error("TLS kimliği Qt tarafından yüklenemedi");

    m_tls = QSslConfiguration::defaultConfiguration();
    m_tls.setProtocol(QSsl::TlsV1_3OrLater);
    m_tls.setLocalCertificate(cert);
    m_tls.setPrivateKey(key);
    m_tls.setPeerVerifyMode(QSslSocket::VerifyNone);
    m_tls.setAllowedNextProtocols({QByteArray(kAlpn)});
}

void Server::incomingConnection(qintptr socketDescriptor) {
    if (m_handshaking >= kMaxHandshaking) {
        ::close(int(socketDescriptor));
        return;
    }
    auto* socket = new QSslSocket(this);
    if (!socket->setSocketDescriptor(socketDescriptor)) {
        delete socket;
        return;
    }
    socket->setSslConfiguration(m_tls);
    ++m_handshaking;

    auto done = std::make_shared<bool>(false);
    const auto settle = [this, done] {
        if (*done)
            return false;
        *done = true;
        --m_handshaking;
        return true;
    };
    const auto drop = [socket, settle] {
        if (settle())
            socket->deleteLater();
    };

    QTimer::singleShot(kHandshakeTimeoutMs, socket, [socket, settle] {
        if (settle()) {
            socket->abort();
            socket->deleteLater();
        }
    });
    connect(socket, &QSslSocket::disconnected, this, drop);
    connect(socket, &QSslSocket::errorOccurred, this, drop);
    connect(socket, &QSslSocket::encrypted, this, [this, socket, settle] {
        if (!settle())
            return;
        disconnect(socket, nullptr, this, nullptr);
        if (socket->sslConfiguration().nextNegotiatedProtocol() != kAlpn ||
            socket->sessionProtocol() != QSsl::TlsV1_3) {
            qCInfo(lcConn) << "ALPN/TLS sürümü uyuşmuyor:" << socket->peerAddress().toString();
            socket->abort();
            socket->deleteLater();
            return;
        }
        socket->setParent(nullptr);
        if (onEncrypted)
            onEncrypted(socket);
        else
            socket->deleteLater();
    });
    socket->startServerEncryption();
}

} // namespace yansit::net
