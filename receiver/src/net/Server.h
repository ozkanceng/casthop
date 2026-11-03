#pragma once

#include "net/Identity.h"

#include <QSslConfiguration>
#include <QTcpServer>

#include <functional>

class QSslSocket;

namespace yansit::net {

inline constexpr quint16 kDefaultPort = 47800;
inline constexpr const char* kAlpn = "yansit/1";

/// TLS 1.3 (yalnız) dinleyici. Şifreleme ve ALPN denetimi tamamlanan soketleri `onEncrypted`'a verir.
class Server : public QTcpServer {
    Q_OBJECT

public:
    /// Kimlik doğrulamadan önce (TLS + ilk mesaj aşaması) aynı anda tutulan en fazla bağlantı.
    static constexpr int kMaxHandshaking = 16;
    static constexpr int kHandshakeTimeoutMs = 10000;

    explicit Server(const Identity& identity, QObject* parent = nullptr);

    /// Soketin sahipliği alıcıya geçer.
    std::function<void(QSslSocket*)> onEncrypted;

    const QSslConfiguration& tlsConfiguration() const { return m_tls; }

protected:
    void incomingConnection(qintptr socketDescriptor) override;

private:
    QSslConfiguration m_tls;
    int m_handshaking = 0;
};

} // namespace yansit::net
