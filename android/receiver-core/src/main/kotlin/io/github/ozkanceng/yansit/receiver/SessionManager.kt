package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.StreamKind

/** Network layer connection id, unique per TCP connection. */
public typealias ConnId = Long

/** Receiver capabilities announced in WELCOME. */
public data class ReceiverInfo(
    val name: String,
    val displayWidth: Int = 1920,
    val displayHeight: Int = 1080,
    val displayRefresh: Int = 60,
    val videoDecoders: List<CodecSupport> = emptyList(),
    val audioDecoders: List<CodecSupport> = emptyList(),
    val audioEnabled: Boolean = false,
)

/** What HELLO said about the connected sender. */
public data class SenderInfo(
    val deviceName: String,
    val deviceId: Bytes,
    val appVersion: String,
    val authMethod: Int,
    val videoCodecs: List<Int>,
    val audioCodecs: List<Int>,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
)

/** Work for the network layer: send in order, then close. */
public class Actions {
    public val send: MutableList<Pair<ConnId, ControlMessage>> = ArrayList()
    public val close: MutableList<ConnId> = ArrayList()

    public val isEmpty: Boolean get() = send.isEmpty() && close.isEmpty()

    public fun merge(other: Actions) {
        send.addAll(other.send)
        close.addAll(other.close)
    }
}

/**
 * Single-sender session state machine (PROTOCOL.md §4, §7.1, §11): HELLO, takeover approval, ATTACH, the RESUME
 * window and session end. Port of `receiver/src/session/SessionManager`.
 *
 * Each call returns the sends and closes to perform; time is passed in. Not thread-safe: the engine holds its lock.
 */
public class SessionManager(
    private val pairing: PairingManager,
    public var receiverInfo: ReceiverInfo,
    public val policy: Policy = Policy(),
) {
    public data class Policy(val approvalTimeoutMs: Long = 20_000L, val resumeWindowMs: Long = 10_000L)

    public enum class MediaSlot { NONE, VIDEO, AUDIO }

    public class Session internal constructor(
        public val sessionId: Bytes,
        public val sender: SenderInfo,
        public var attachKey: Bytes,
        public var resumeToken: Bytes,
        public var control: ConnId?,
    ) {
        public var video: ConnId? = null
        public var audio: ConnId? = null

        /** When CONTROL dropped: the start of the RESUME window. */
        public var controlLostAtMs: Long? = null
    }

    public class Pending internal constructor(
        public val conn: ConnId,
        public val hello: ControlMessage.Hello,
        public val deadlineMs: Long,
    )

    /** The active session, connected or inside the RESUME window. */
    public var session: Session? = null
        private set

    /** The second sender waiting for approval on the screen. */
    public var pending: Pending? = null
        private set

    public fun mediaSlotOf(conn: ConnId): MediaSlot {
        val s = session ?: return MediaSlot.NONE
        return when (conn) {
            s.video -> MediaSlot.VIDEO
            s.audio -> MediaSlot.AUDIO
            else -> MediaSlot.NONE
        }
    }

    public fun isControl(conn: ConnId): Boolean = session?.control == conn

    public fun onHello(
        conn: ConnId,
        hello: ControlMessage.Hello,
        serverNonce: Bytes,
        certFingerprint: Bytes,
        nowMs: Long,
    ): Actions {
        val a = Actions()
        fun refuse(reason: Int, retryAfterMs: Long? = null): Actions {
            a.send += conn to reject(reason, retryAfterMs)
            a.close += conn
            return a
        }

        if (hello.version != 1) return refuse(RejectReason.VERSION_UNSUPPORTED)
        // Someone is already waiting: refuse before the credential is spent.
        if (pending != null) return refuse(RejectReason.BUSY_DENIED)

        val verdict = pairing.verifyHello(hello, serverNonce, certFingerprint, nowMs)
        when (verdict.outcome) {
            PairingManager.Outcome.ACCEPTED -> Unit
            PairingManager.Outcome.RATE_LIMITED -> return refuse(RejectReason.RATE_LIMITED, verdict.retryAfterMs)
            PairingManager.Outcome.LOCKED_OUT -> return refuse(RejectReason.LOCKED_OUT, verdict.retryAfterMs)
            PairingManager.Outcome.AUTH_FAILED,
            PairingManager.Outcome.UNSUPPORTED_METHOD,
            -> return refuse(RejectReason.AUTH_FAILED)
        }

        val byPairKey = verdict.trusted != null
        val current = session ?: return startSession(conn, hello, byPairKey)
        // A paired device takes over its own session without approval (the app restarted, for example).
        if (byPairKey && current.sender.deviceId == hello.deviceId) {
            a.merge(closeSession(ByeReason.TAKEOVER))
            a.merge(startSession(conn, hello, byPairKey))
            return a
        }
        pending = Pending(conn, hello, nowMs + policy.approvalTimeoutMs)
        a.send += conn to ControlMessage.PendingApproval(policy.approvalTimeoutMs)
        return a
    }

    private fun startSession(conn: ConnId, hello: ControlMessage.Hello, authenticatedByPairKey: Boolean): Actions {
        val s = Session(
            sessionId = Auth.randomBytes(ControlCodec.ID_SIZE),
            sender = senderInfoFrom(hello),
            attachKey = Auth.randomBytes(ControlCodec.KEY_SIZE),
            resumeToken = Auth.randomBytes(ControlCodec.KEY_SIZE),
            control = conn,
        )
        var welcome = makeWelcome(s)
        if (hello.requestPairing == true && !authenticatedByPairKey) {
            val t = pairing.createPairing(hello)
            welcome = welcome.copy(pairKey = t.pairKey, keyId = t.keyId)
        }
        session = s
        return Actions().also { it.send += conn to welcome }
    }

    private fun closeSession(byeReason: Int?): Actions {
        val a = Actions()
        val s = session ?: return a
        session = null
        s.control?.let { control ->
            if (byeReason != null) a.send += control to ControlMessage.Bye(byeReason)
            a.close += control
        }
        s.video?.let { a.close += it }
        s.audio?.let { a.close += it }
        return a
    }

    private fun makeWelcome(s: Session): ControlMessage.Welcome = ControlMessage.Welcome(
        version = 1,
        receiverName = receiverInfo.name,
        sessionId = s.sessionId,
        displayWidth = receiverInfo.displayWidth,
        displayHeight = receiverInfo.displayHeight,
        displayRefresh = receiverInfo.displayRefresh,
        videoDecoders = receiverInfo.videoDecoders,
        audioDecoders = receiverInfo.audioDecoders,
        attachKey = s.attachKey,
        resumeToken = s.resumeToken,
        audioEnabled = receiverInfo.audioEnabled,
    )

    /** Outcome of the takeover dialog on the screen. */
    public fun resolvePending(accept: Boolean): Actions {
        val a = Actions()
        val p = pending ?: return a
        pending = null
        if (!accept) {
            a.send += p.conn to reject(RejectReason.BUSY_DENIED)
            a.close += p.conn
            return a
        }
        a.merge(closeSession(ByeReason.TAKEOVER))
        a.merge(startSession(p.conn, p.hello, p.hello.authMethod == AuthMethod.PAIRED))
        return a
    }

    public fun onAttach(conn: ConnId, attach: ControlMessage.Attach, serverNonce: Bytes): Actions {
        val a = Actions()
        fun result(status: Int): Actions {
            a.send += conn to ControlMessage.AttachResult(status)
            if (status != 0) a.close += conn
            return a
        }

        val s = session
        // Without CONTROL (RESUME window) the old attach_key is void: RESUME has to come first.
        if (s == null || s.sessionId != attach.sessionId || s.control == null) return result(RejectReason.SESSION_UNKNOWN)
        val expected = Auth.attachProof(s.attachKey, attach.sessionId, attach.streamKind, serverNonce)
        if (!Auth.constantTimeEquals(expected, attach.proof)) return result(RejectReason.AUTH_FAILED)

        when {
            attach.streamKind == StreamKind.VIDEO -> {
                s.video?.takeIf { it != conn }?.let { a.close += it }
                s.video = conn
            }
            attach.streamKind == StreamKind.AUDIO && receiverInfo.audioEnabled -> {
                s.audio?.takeIf { it != conn }?.let { a.close += it }
                s.audio = conn
            }
            else -> return result(RejectReason.MALFORMED)
        }
        return result(0)
    }

    public fun onResume(conn: ConnId, resume: ControlMessage.Resume, serverNonce: Bytes): Actions {
        val a = Actions()
        val s = session
        if (s == null || s.sessionId != resume.sessionId ||
            !Auth.constantTimeEquals(Auth.resumeProof(s.resumeToken, resume.sessionId, serverNonce), resume.proof)
        ) {
            a.send += conn to reject(RejectReason.SESSION_UNKNOWN)
            a.close += conn
            return a
        }
        // The sender may notice the drop before we do: an old CONTROL can still be open.
        s.control?.takeIf { it != conn }?.let { a.close += it }
        // Media comes back with the new attach_key.
        s.video?.let { a.close += it }
        s.audio?.let { a.close += it }
        s.video = null
        s.audio = null
        s.control = conn
        s.controlLostAtMs = null
        s.attachKey = Auth.randomBytes(ControlCodec.KEY_SIZE)
        s.resumeToken = Auth.randomBytes(ControlCodec.KEY_SIZE)
        a.send += conn to makeWelcome(s)
        return a
    }

    /** BYE from the session's CONTROL connection or from a pending sender. */
    public fun onBye(conn: ConnId): Actions {
        if (isControl(conn)) return closeSession(null)
        if (pending?.conn == conn) pending = null
        return Actions().also { it.close += conn }
    }

    /** A connection ended: the peer, a timeout or an error. */
    public fun onConnectionClosed(conn: ConnId, nowMs: Long): Actions {
        val a = Actions()
        if (pending?.conn == conn) {
            pending = null
            return a
        }
        val s = session ?: return a
        when (conn) {
            s.control -> {
                s.control = null
                s.controlLostAtMs = nowMs
                s.video?.let { a.close += it }
                s.audio?.let { a.close += it }
                s.video = null
                s.audio = null
            }
            s.video -> s.video = null
            s.audio -> s.audio = null
        }
        return a
    }

    /** Ends the session from the receiver side: the user stopped it or the app is closing. */
    public fun endSession(byeReason: Int): Actions {
        val a = closeSession(byeReason)
        pending?.let { p ->
            a.send += p.conn to reject(RejectReason.BUSY_DENIED)
            a.close += p.conn
            pending = null
        }
        return a
    }

    /** Handles the approval and RESUME timeouts. */
    public fun tick(nowMs: Long): Actions {
        val a = Actions()
        pending?.takeIf { nowMs >= it.deadlineMs }?.let { p ->
            a.send += p.conn to reject(RejectReason.BUSY_TIMEOUT)
            a.close += p.conn
            pending = null
        }
        val lostAt = session?.controlLostAtMs
        if (lostAt != null && nowMs - lostAt >= policy.resumeWindowMs) a.merge(closeSession(null))
        return a
    }

    private fun reject(reason: Int, retryAfterMs: Long? = null): ControlMessage.Reject =
        ControlMessage.Reject(reason, retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(0xFFFF_FFFFL))

    public companion object {
        public fun senderInfoFrom(hello: ControlMessage.Hello): SenderInfo = SenderInfo(
            deviceName = hello.deviceName,
            deviceId = hello.deviceId,
            appVersion = hello.appVersion,
            authMethod = hello.authMethod,
            videoCodecs = hello.videoCodecs,
            audioCodecs = hello.audioCodecs.orEmpty(),
            maxWidth = hello.maxWidth,
            maxHeight = hello.maxHeight,
            maxFps = hello.maxFps,
        )
    }
}
