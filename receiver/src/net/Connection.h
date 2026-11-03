#pragma once

#include "common/Clock.h"
#include "protocol/Control.h"
#include "protocol/Frame.h"
#include "session/SessionManager.h"

#include <QObject>
#include <QPointer>
#include <QSslSocket>
#include <QTimer>

#include <cstdint>
#include <functional>

namespace yansit::net {

/**
 * Tek bir TLS bağlantısı: çerçeve ayrıştırma, CHALLENGE, ilk-mesaj ve sessizlik zaman aşımları.
 * Rol (CONTROL / medya) ilk mesaja göre üst katman tarafından belirlenir.
 */
class Connection : public QObject {
    Q_OBJECT

public:
    /// Pending: CONTROL bağlantısı, tahtada devralma onayı bekliyor (sessizlik zaman aşımı yok).
    enum class Role { Unknown, Pending, Control, Media };

    struct Handlers {
        /// Çözülmüş CONTROL mesajı (alış anı, µs).
        std::function<void(Connection&, protocol::ControlMessage, std::uint64_t)> control;
        /// VIDEO/AUDIO çerçevesi; yalnız Media rolündeyken çağrılır.
        std::function<void(Connection&, const protocol::FrameHeader&, protocol::Bytes&&, std::uint64_t)> media;
        /// Bağlantı kapandı (bir kez). Ardından nesne silinir.
        std::function<void(Connection&)> closed;
    };

    static constexpr int kFirstMessageTimeoutMs = 10000;
    static constexpr int kControlSilenceTimeoutMs = 5000;

    /// `socket` şifreli olmalı; sahipliği alınır. CHALLENGE hemen gönderilir.
    Connection(session::ConnId id, QSslSocket* socket, std::string receiverName, Handlers handlers,
               QObject* parent = nullptr);
    ~Connection() override;

    session::ConnId id() const { return m_id; }
    const protocol::Id16& serverNonce() const { return m_serverNonce; }
    Role role() const { return m_role; }
    void setRole(Role role);
    QString peer() const { return m_peer; }

    void sendControl(const protocol::ControlMessage& msg);
    /// Bekleyen yazmaları gönderip kapatır (en geç 2 sn).
    void closeGracefully();
    /// Hemen kapatır.
    void abort();
    /// Geri çağrıları bırakır (sahip nesne yok edilirken).
    void detachHandlers() { m_handlers = {}; }

private:
    void onReadyRead();
    void handleFrame(protocol::FrameParser::Frame&& frame);
    void protocolViolation(const char* what);
    void finish();

    session::ConnId m_id;
    QPointer<QSslSocket> m_socket;
    Handlers m_handlers;
    protocol::Id16 m_serverNonce{};
    protocol::FrameParser m_parser;
    Role m_role = Role::Unknown;
    std::uint32_t m_sendSeq = 0;
    QTimer m_deadline;
    QString m_peer;
    bool m_closing = false;
    bool m_finished = false;
};

} // namespace yansit::net
