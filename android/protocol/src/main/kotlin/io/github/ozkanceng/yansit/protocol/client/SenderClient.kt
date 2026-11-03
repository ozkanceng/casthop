package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.ProtocolError
import io.github.ozkanceng.yansit.protocol.ProtocolException
import java.io.IOException

/** Tahtanın adresi. [fingerprint] QR'dan veya kayıtlı eşleştirmeden gelir; PIN ile ilk bağlantıda `null`. */
public data class Endpoint(val host: String, val port: Int, val fingerprint: Bytes?)

/** Kimlik doğrulama yöntemi (PROTOCOL.md §7.1.1). */
public sealed interface Credentials {
    public val method: Int

    public data class QrToken(val token: Bytes) : Credentials {
        override val method: Int get() = AuthMethod.QR_TOKEN
    }

    public data class Pin(val pin: String) : Credentials {
        override val method: Int get() = AuthMethod.PIN
    }

    public data class Paired(val keyId: Bytes, val pairKey: Bytes) : Credentials {
        override val method: Int get() = AuthMethod.PAIRED
    }
}

/** HELLO'da bildirilen gönderici bilgileri. */
public data class DeviceInfo(
    val name: String,
    val deviceId: Bytes,
    val appVersion: String,
    val videoCodecs: List<Int>,
    val audioCodecs: List<Int>? = null,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
)

/** Kabul edilmiş oturum: CONTROL kanalı ve WELCOME içeriği. */
public class Session internal constructor(
    public val endpoint: Endpoint,
    public val control: ControlChannel,
    public val welcome: ControlMessage.Welcome,
) : AutoCloseable {
    /** Bu oturumda doğrulanmış parmak izi (PIN akışında kaydedilmek üzere). */
    public val fingerprint: Bytes get() = control.fingerprint

    override fun close() {
        control.close()
    }
}

public sealed interface HelloOutcome {
    public data class Accepted(val session: Session) : HelloOutcome

    public data class Rejected(val reason: Int, val retryAfterMs: Long?) : HelloOutcome
}

/** ATTACH reddedildi ([status] REJECT nedenidir). */
public class AttachRejectedException(public val status: Int) : IOException("ATTACH reddedildi: $status")

/** Gönderici tarafı el sıkışmaları: HELLO, ATTACH, RESUME (PROTOCOL.md §4, §7.1). Engelleyici G/Ç kullanır. */
public object SenderClient {
    private const val FIRST_MESSAGE_TIMEOUT_MS = 10_000

    /**
     * Yeni oturum ister. Tahtada başka bir yansıtma sürüyorsa [onPending] çağrılır ve tahtadaki onay
     * beklenir (en fazla PENDING_APPROVAL süresi + 5 sn).
     */
    @Throws(IOException::class, ProtocolException::class)
    public fun hello(
        endpoint: Endpoint,
        credentials: Credentials,
        device: DeviceInfo,
        requestPairing: Boolean = false,
        onPending: (timeoutMs: Long) -> Unit = {},
    ): HelloOutcome {
        val channel = open(endpoint)
        try {
            val challenge = expectChallenge(channel)
            val clientNonce = Auth.randomBytes(ControlCodec.ID_SIZE)
            val secret = when (credentials) {
                is Credentials.QrToken -> credentials.token.toByteArray()
                is Credentials.Pin -> Auth.pinSecret(credentials.pin)
                is Credentials.Paired -> credentials.pairKey.toByteArray()
            }
            val proof = Auth.authProof(secret, credentials.method, challenge.serverNonce, clientNonce,
                channel.fingerprint, device.deviceId)
            channel.send(
                ControlMessage.Hello(
                    version = 1,
                    deviceName = device.name,
                    deviceId = device.deviceId,
                    appVersion = device.appVersion,
                    authMethod = credentials.method,
                    clientNonce = clientNonce,
                    authProof = proof,
                    keyId = (credentials as? Credentials.Paired)?.keyId,
                    videoCodecs = device.videoCodecs,
                    audioCodecs = device.audioCodecs,
                    maxWidth = device.maxWidth,
                    maxHeight = device.maxHeight,
                    maxFps = device.maxFps,
                    requestPairing = if (requestPairing) true else null,
                ),
            )
            while (true) {
                when (val msg = channel.receive() ?: throw IOException("tahta bağlantıyı kapattı")) {
                    is ControlMessage.Welcome -> {
                        channel.readTimeoutMs = 0
                        return HelloOutcome.Accepted(Session(endpoint, channel, msg))
                    }
                    is ControlMessage.Reject -> {
                        channel.close()
                        return HelloOutcome.Rejected(msg.reason, msg.retryAfterMs)
                    }
                    is ControlMessage.PendingApproval -> {
                        channel.readTimeoutMs = (msg.timeoutMs + 5_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        onPending(msg.timeoutMs)
                    }
                    else -> throw ProtocolException(ProtocolError.MALFORMED_TLV, "beklenmeyen yanıt: ${msg.msgType}")
                }
            }
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    /** Oturuma VIDEO veya AUDIO bağlantısı ekler. */
    @Throws(IOException::class, ProtocolException::class)
    public fun attach(session: Session, kind: Int): MediaChannel {
        val endpoint = session.endpoint.copy(fingerprint = session.fingerprint)
        val tls = PinnedTls.connect(endpoint.host, endpoint.port, endpoint.fingerprint)
        val channel = ControlChannel(tls)
        try {
            channel.readTimeoutMs = FIRST_MESSAGE_TIMEOUT_MS
            val challenge = expectChallenge(channel)
            val w = session.welcome
            channel.send(ControlMessage.Attach(w.sessionId, kind,
                Auth.attachProof(w.attachKey, w.sessionId, kind, challenge.serverNonce)))
            val result = channel.receive() as? ControlMessage.AttachResult
                ?: throw ProtocolException(ProtocolError.MALFORMED_TLV, "ATTACH_RESULT bekleniyordu")
            if (result.status != 0) throw AttachRejectedException(result.status)
            tls.socket.soTimeout = 0
            return MediaChannel(tls, kind)
        } catch (e: Exception) {
            tls.close()
            throw e
        }
    }

    /** Kopan CONTROL bağlantısını oturumu kaybetmeden yeniler (RESUME penceresi 10 sn). */
    @Throws(IOException::class, ProtocolException::class)
    public fun resume(session: Session): HelloOutcome {
        val endpoint = session.endpoint.copy(fingerprint = session.fingerprint)
        val channel = open(endpoint)
        try {
            val challenge = expectChallenge(channel)
            val w = session.welcome
            channel.send(ControlMessage.Resume(w.sessionId,
                Auth.resumeProof(w.resumeToken, w.sessionId, challenge.serverNonce)))
            return when (val msg = channel.receive()) {
                is ControlMessage.Welcome -> {
                    channel.readTimeoutMs = 0
                    HelloOutcome.Accepted(Session(endpoint, channel, msg))
                }
                is ControlMessage.Reject -> {
                    channel.close()
                    HelloOutcome.Rejected(msg.reason, msg.retryAfterMs)
                }
                else -> throw ProtocolException(ProtocolError.MALFORMED_TLV, "RESUME yanıtı bekleniyordu")
            }
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    private fun open(endpoint: Endpoint): ControlChannel {
        val channel = ControlChannel(PinnedTls.connect(endpoint.host, endpoint.port, endpoint.fingerprint))
        channel.readTimeoutMs = FIRST_MESSAGE_TIMEOUT_MS
        return channel
    }

    private fun expectChallenge(channel: ControlChannel): ControlMessage.Challenge {
        val msg = channel.receive() ?: throw IOException("tahta bağlantıyı kapattı")
        val challenge = msg as? ControlMessage.Challenge
            ?: throw ProtocolException(ProtocolError.MALFORMED_TLV, "CHALLENGE bekleniyordu")
        if (1 !in challenge.minVersion..challenge.maxVersion) {
            throw ProtocolException(ProtocolError.UNSUPPORTED_VERSION, "${challenge.minVersion}..${challenge.maxVersion}")
        }
        return challenge
    }
}
