package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameHeader
import io.github.ozkanceng.yansit.protocol.FrameType
import io.github.ozkanceng.yansit.protocol.QrPayload
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the screen shows. */
public enum class ReceiverPhase { WAITING, CONNECTING, STREAMING, PAUSED, RECONNECTING }

/** A paired sender as the settings list sees it: no key material. */
public data class TrustedEntry(val keyIdHex: String, val name: String)

/** Live numbers for the overlay; [latencyMs] is -1 while unknown. */
public data class LiveStats(
    val latencyMs: Int,
    val fps: Double,
    val bitrateKbps: Int,
    val dropped: Long,
    val decoder: String,
    val hardware: Boolean,
)

/** A snapshot for the UI. [toString] leaves out the QR content and PIN, so a stray log cannot leak them. */
public data class ReceiverState(
    val phase: ReceiverPhase = ReceiverPhase.WAITING,
    val receiverName: String = "",
    val qrUri: String = "",
    val pin: String = "",
    val hosts: List<String> = emptyList(),
    val port: Int = 0,
    val senderName: String = "",
    val pendingSenderName: String? = null,
    val stats: LiveStats? = null,
    val trusted: List<TrustedEntry> = emptyList(),
    val audioEnabled: Boolean = false,
) {
    override fun toString(): String = "ReceiverState(phase=$phase)"
}

/** The §11 pairing file content. [toString] leaves the secrets out. */
public data class PairingInfo(
    val uri: String,
    val pin: String,
    val port: Int,
    val fingerprintHex: String,
    val tokenBase64: String,
) {
    override fun toString(): String = "PairingInfo(port=$port)"

    public fun toJson(): String = "{" + listOf(
        "\"uri\":${jsonString(uri)}",
        "\"pin\":${jsonString(pin)}",
        "\"port\":$port",
        "\"fingerprint\":${jsonString(fingerprintHex)}",
        "\"token\":${jsonString(tokenBase64)}",
    ).joinToString(",") + "}"
}

/** One line of the §11 stats file, written when a session ends. */
public data class SessionSummary(
    val sessionIdHex: String,
    val framesReceived: Long,
    val framesRendered: Long,
    val framesDropped: Long,
    val firstFrameMs: Double?,
    val c2dAvgMs: Double?,
    val decoder: String,
    val hardware: Boolean,
    val audioReceived: Long,
    val audioPlayed: Long,
    val audioLate: Long,
    val audioLatencyMs: Double?,
) {
    public fun toJson(): String = "{" + listOf(
        "\"session_id\":${jsonString(sessionIdHex)}",
        "\"frames_received\":$framesReceived",
        "\"frames_rendered\":$framesRendered",
        "\"frames_dropped\":$framesDropped",
        "\"first_frame_ms\":${firstFrameMs ?: "null"}",
        "\"c2d_avg_ms\":${c2dAvgMs ?: "null"}",
        "\"decoder\":${jsonString(decoder)}",
        "\"hardware\":$hardware",
        "\"audio_received\":$audioReceived",
        "\"audio_played\":$audioPlayed",
        "\"audio_late\":$audioLate",
        "\"audio_latency_ms\":${audioLatencyMs ?: "null"}",
    ).joinToString(",") + "}"
}

private fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** Callbacks run on the engine's own thread, one at a time. */
public interface ReceiverObserver {
    public fun onStateChanged(state: ReceiverState) {}

    /** The QR token, PIN or addresses changed. The values are secrets: keep them out of logs. */
    public fun onPairingChanged(info: PairingInfo) {}

    public fun onSessionFinished(summary: SessionSummary) {}
}

/**
 * A running receiver: accepts connections, drives [SessionManager], routes media and sends RECEIVER_REPORT. Port of
 * `receiver/src/app/ReceiverService`, with threads instead of a Qt event loop: every session state change runs under
 * one lock. Socket readers leave the lock before media reaches a pipeline, so a slow decoder cannot hold up CONTROL.
 */
public class ReceiverEngine(
    private val options: Options,
    private val transport: ServerTransport,
    private val fingerprint: Bytes,
    videoBackend: VideoBackend,
    audioBackend: AudioBackend,
    private val store: TrustedStore,
    private val observer: ReceiverObserver,
    private val clockUs: () -> Long = MonotonicClock::nowUs,
) {
    public data class Options(
        val name: String,
        val displayWidth: Int = 1920,
        val displayHeight: Int = 1080,
        val displayRefresh: Int = 60,
        val videoDecoders: List<CodecSupport>,
        val audioDecoders: List<CodecSupport> = emptyList(),
        val audio: Boolean = true,
        val approvalTimeoutMs: Long = 20_000L,
        /** Local IPv4 addresses for the QR, best first. */
        val hosts: () -> List<String>,
    )

    private val lock = Any()
    private val video = VideoPipeline(videoBackend, clockUs, ::onKeyframeNeeded, ::onFirstFrame)

    /** Where a [VideoBackend] reports decoded frames. */
    public val videoPipeline: VideoPipeline get() = video
    private val audio = AudioPipeline(audioBackend)

    /** Where an [AudioBackend] reports played packets. */
    public val audioPipeline: AudioPipeline get() = audio

    private val pairing = PairingManager(nowMs())
    private var receiverName = options.name.take(MAX_NAME_CHARS)
    private var audioOn = options.audio
    private val sessions = SessionManager(
        pairing,
        receiverInfo(),
        SessionManager.Policy(approvalTimeoutMs = options.approvalTimeoutMs),
    )
    private val connections = HashMap<ConnId, Connection>()
    private var nextId = 0L

    @Volatile
    private var running = false
    private var worker: ScheduledExecutorService? = null

    private var sessionId: Bytes? = null
    private var paused = false
    private var hosts = emptyList<String>()
    private var qrUri = ""
    private var sessionStartUs = 0L
    private var firstFrameUs: Long? = null
    private var lastReportUs = 0L
    private var sessionC2dSumUs = 0L
    private var sessionC2dCount = 0L
    private var sessionAudioLatencySumUs = 0L
    private var sessionAudioLatencyCount = 0L
    private var liveStats: LiveStats? = null

    private val handlers = object : Connection.Handlers {
        override fun onControl(connection: Connection, message: ControlMessage, recvUs: Long) {
            synchronized(lock) {
                if (connection.role == Connection.Role.UNKNOWN) {
                    onFirstMessage(connection, message)
                } else {
                    onSessionMessage(connection, message, recvUs)
                }
            }
        }

        override fun onMedia(connection: Connection, header: FrameHeader, payload: ByteArray, recvUs: Long) {
            when (synchronized(lock) { sessions.mediaSlotOf(connection.id) }) {
                SessionManager.MediaSlot.VIDEO -> if (header.type == FrameType.VIDEO) video.pushFrame(header, payload, recvUs)
                SessionManager.MediaSlot.AUDIO -> if (header.type == FrameType.AUDIO) audio.pushFrame(header, payload, recvUs)
                SessionManager.MediaSlot.NONE -> Unit
            }
        }

        override fun onClosed(connection: Connection) {
            synchronized(lock) {
                connections.remove(connection.id)
                apply(sessions.onConnectionClosed(connection.id, nowMs()))
            }
        }
    }

    public val currentState: ReceiverState get() = synchronized(lock) { snapshot() }

    /** Starts listening. */
    public fun start() {
        worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "yansit-engine").also { it.isDaemon = true } }
        open()
        worker?.scheduleWithFixedDelay(::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        worker?.scheduleWithFixedDelay(::sendReport, REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        thread(isDaemon = true, name = "yansit-accept") { acceptLoop() }
    }

    internal fun open() {
        running = true
        synchronized(lock) {
            pairing.setTrustedSenders(store.load())
            pairing.onCredentialsChanged = ::updateCredentials
            pairing.onTrustedChanged = {
                store.save(pairing.trustedSenders)
                publish()
            }
            lastReportUs = clockUs()
            updateCredentials()
        }
    }

    /** Sends BYE to whoever is connected, closes the port and waits briefly for the BYE to leave. */
    public fun shutdown() {
        synchronized(lock) {
            if (!running) return
            running = false
            apply(sessions.endSession(ByeReason.RECEIVER_CLOSING))
            connections.values.toList().forEach { it.closeGracefully() }
        }
        try {
            transport.close()
        } catch (_: IOException) {
            // Closing anyway.
        }
        val end = clockUs() + SHUTDOWN_WAIT_US
        while (synchronized(lock) { connections.isNotEmpty() } && clockUs() < end) Thread.sleep(SHUTDOWN_POLL_MS)
        synchronized(lock) { connections.values.toList().forEach { it.abort() } }
        worker?.shutdown()
        worker?.awaitTermination(SHUTDOWN_WAIT_US / 1000, TimeUnit.MILLISECONDS)
        video.reset()
        audio.reset()
    }

    public fun approvePending() {
        synchronized(lock) {
            val id = sessions.pending?.conn ?: return
            val a = sessions.resolvePending(true)
            if (sessions.isControl(id)) connections[id]?.setRole(Connection.Role.CONTROL)
            apply(a)
        }
    }

    public fun denyPending() {
        synchronized(lock) { apply(sessions.resolvePending(false)) }
    }

    /** Ends the session because the person at the screen asked to. */
    public fun stopSession() {
        synchronized(lock) { apply(sessions.endSession(ByeReason.USER_STOP)) }
    }

    public fun forgetSender(keyIdHex: String) {
        synchronized(lock) { pairing.removeTrusted(Bytes.fromHex(keyIdHex)) }
    }

    public fun setReceiverName(name: String) {
        synchronized(lock) {
            val trimmed = name.trim().take(MAX_NAME_CHARS)
            if (trimmed.isEmpty() || trimmed == receiverName) return
            receiverName = trimmed
            sessions.receiverInfo = receiverInfo()
            updateCredentials()
        }
    }

    /** Takes effect from the next WELCOME. */
    public fun setAudioEnabled(enabled: Boolean) {
        synchronized(lock) {
            audioOn = enabled
            sessions.receiverInfo = receiverInfo()
            publish()
        }
    }

    /** The network changed: the QR needs the new addresses. */
    public fun refreshAddresses() {
        synchronized(lock) { updateCredentials() }
    }

    private fun receiverInfo() = ReceiverInfo(
        name = receiverName,
        displayWidth = options.displayWidth,
        displayHeight = options.displayHeight,
        displayRefresh = options.displayRefresh,
        videoDecoders = options.videoDecoders,
        audioDecoders = options.audioDecoders,
        audioEnabled = audioOn && options.audioDecoders.isNotEmpty(),
    )

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                transport.accept()
            } catch (_: IOException) {
                return
            }
            accept(socket)?.let { connection ->
                thread(isDaemon = true, name = "yansit-conn-${connection.id}") { connection.run() }
            }
        }
    }

    /** §11: at most [MAX_UNAUTHENTICATED] connections before authentication. */
    internal fun accept(socket: ReceiverSocket): Connection? = synchronized(lock) {
        val waiting = connections.values.count { it.role == Connection.Role.UNKNOWN && !it.isClosing }
        if (!running || waiting >= MAX_UNAUTHENTICATED) {
            thread(isDaemon = true, name = "yansit-refuse") { socket.abort() }
            return null
        }
        val connection = Connection(++nextId, socket, receiverName, handlers, clockUs)
        connections[connection.id] = connection
        connection
    }

    private fun onFirstMessage(c: Connection, message: ControlMessage) {
        val id = c.id
        when (message) {
            is ControlMessage.Hello -> {
                val a = sessions.onHello(id, message, c.serverNonce, fingerprint, nowMs())
                if (sessions.isControl(id)) {
                    c.setRole(Connection.Role.CONTROL)
                } else if (sessions.pending?.conn == id) {
                    c.setRole(Connection.Role.PENDING)
                }
                apply(a)
            }
            is ControlMessage.Resume -> {
                val a = sessions.onResume(id, message, c.serverNonce)
                if (sessions.isControl(id)) c.setRole(Connection.Role.CONTROL)
                apply(a)
            }
            is ControlMessage.Attach -> {
                val a = sessions.onAttach(id, message, c.serverNonce)
                if (sessions.mediaSlotOf(id) != SessionManager.MediaSlot.NONE) c.setRole(Connection.Role.MEDIA)
                apply(a)
            }
            else -> {
                c.sendControl(ControlMessage.Reject(RejectReason.MALFORMED))
                c.closeGracefully()
            }
        }
    }

    private fun onSessionMessage(c: Connection, message: ControlMessage, recvUs: Long) {
        if (message is ControlMessage.Bye) {
            apply(sessions.onBye(c.id))
            return
        }
        // A pending connection only expects BYE.
        if (!sessions.isControl(c.id)) return
        when (message) {
            is ControlMessage.Ping -> {
                c.sendControl(ControlMessage.Pong(message.pingId, message.t0Us, recvUs, clockUs()))
                message.offsetUs?.let {
                    video.setClockOffset(it)
                    audio.setClockOffset(it)
                }
            }
            is ControlMessage.Pause -> {
                paused = true
                refreshState()
            }
            is ControlMessage.Unpause -> {
                paused = false
                refreshState()
            }
            else -> Unit // STREAM_CONFIG and SENDER_STATS only inform; the CONFIG frame is authoritative.
        }
    }

    private fun onKeyframeNeeded(reason: Int) {
        synchronized(lock) { sendToControl(ControlMessage.KeyframeRequest(reason)) }
    }

    private fun onFirstFrame() {
        synchronized(lock) {
            if (sessionId == null || firstFrameUs != null) return
            firstFrameUs = clockUs()
            refreshState()
        }
    }

    private fun apply(actions: Actions) {
        for ((id, message) in actions.send) connections[id]?.sendControl(message)
        for (id in actions.close) connections[id]?.closeGracefully()
        refreshState()
    }

    private fun sendToControl(message: ControlMessage) {
        val control = sessions.session?.control ?: return
        connections[control]?.sendControl(message)
    }

    private fun refreshState() {
        val current = sessions.session?.sessionId
        if (current != sessionId) {
            if (sessionId != null) finishSessionStats()
            sessionId = current
            video.reset()
            audio.reset()
            firstFrameUs = null
            paused = false
            sessionC2dSumUs = 0
            sessionC2dCount = 0
            sessionAudioLatencySumUs = 0
            sessionAudioLatencyCount = 0
            liveStats = null
            lastReportUs = clockUs()
            sessionStartUs = lastReportUs
        }
        publish()
    }

    private fun phase(): ReceiverPhase {
        val s = sessions.session
        return when {
            s == null -> ReceiverPhase.WAITING
            s.control == null -> ReceiverPhase.RECONNECTING
            paused -> ReceiverPhase.PAUSED
            firstFrameUs != null -> ReceiverPhase.STREAMING
            else -> ReceiverPhase.CONNECTING
        }
    }

    private fun snapshot() = ReceiverState(
        phase = phase(),
        receiverName = receiverName,
        qrUri = qrUri,
        pin = pairing.pin,
        hosts = hosts,
        port = transport.port,
        senderName = sessions.session?.sender?.deviceName.orEmpty(),
        pendingSenderName = sessions.pending?.hello?.deviceName,
        stats = liveStats,
        trusted = pairing.trustedSenders.map { TrustedEntry(it.keyId.toHex(), it.deviceName) },
        audioEnabled = audioOn,
    )

    private fun publish() {
        val state = snapshot()
        post { observer.onStateChanged(state) }
    }

    private fun post(block: () -> Unit) {
        val w = worker
        if (w == null) {
            block()
            return
        }
        try {
            w.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shutting down: nobody is watching any more.
        }
    }

    internal fun tick() {
        synchronized(lock) {
            pairing.tick(nowMs())
            val before = sessions.session
            val a = sessions.tick(nowMs())
            // A RESUME window can run out with nothing left to close, and the session still ended.
            if (!a.isEmpty || sessions.session !== before) apply(a)
            val now = clockUs()
            connections.values.toList().forEach { it.checkDeadline(now) }
        }
    }

    internal fun sendReport() {
        synchronized(lock) {
            val now = clockUs()
            val intervalUs = maxOf(now - lastReportUs, 1)
            lastReportUs = now
            val v = video.takeStats()
            val a = audio.takeStats()
            // Lip sync: audio follows the moment the picture reaches the screen, one frame included.
            if (v.c2dCount > 0) {
                val frameUs = 1_000_000L / maxOf(options.displayRefresh, 1)
                audio.setTargetLatencyUs(v.c2dSumUs / v.c2dCount + frameUs)
            }
            if (sessions.session?.control == null) return
            val c2dAvgUs = if (v.c2dCount > 0) (v.c2dSumUs / v.c2dCount).coerceAtMost(U32_MAX) else 0
            sendToControl(
                ControlMessage.ReceiverReport(
                    intervalMs = (intervalUs / 1000).coerceAtMost(U16_MAX).toInt(),
                    framesReceived = v.framesReceived,
                    framesRendered = v.framesRendered,
                    framesDropped = v.framesDropped,
                    bytesReceived = v.bytesReceived.coerceAtMost(U32_MAX),
                    c2rAvgUs = if (v.c2rCount > 0) v.c2rSumUs / v.c2rCount else 0,
                    c2rMinUs = v.c2rMinUs,
                    c2rMaxUs = v.c2rMaxUs,
                    c2dAvgUs = c2dAvgUs,
                    decodeBacklog = v.backlog,
                    audioLate = a.late.coerceAtMost(U16_MAX).toInt(),
                ),
            )
            sessionAudioLatencySumUs += a.latencySumUs
            sessionAudioLatencyCount += a.latencyCount
            sessionC2dSumUs += v.c2dSumUs
            sessionC2dCount += v.c2dCount
            liveStats = LiveStats(
                latencyMs = if (v.c2dCount > 0) (c2dAvgUs / 1000).toInt() else -1,
                fps = v.framesRendered * 1e6 / intervalUs,
                bitrateKbps = (v.bytesReceived * 8e3 / intervalUs).toInt(),
                dropped = video.totalDropped,
                decoder = video.decoderName,
                hardware = video.hardware,
            )
            publish()
        }
    }

    private fun finishSessionStats() {
        val id = sessionId ?: return
        val start = sessionStartUs
        val summary = SessionSummary(
            sessionIdHex = id.toHex(),
            framesReceived = video.totalReceived,
            framesRendered = video.totalRendered,
            framesDropped = video.totalDropped,
            firstFrameMs = firstFrameUs?.let { (it - start) / 1000.0 },
            c2dAvgMs = if (sessionC2dCount > 0) sessionC2dSumUs.toDouble() / sessionC2dCount / 1000.0 else null,
            decoder = video.decoderName,
            hardware = video.hardware,
            audioReceived = audio.totalReceived,
            audioPlayed = audio.totalPlayed,
            audioLate = audio.totalLate,
            audioLatencyMs = if (sessionAudioLatencyCount > 0) {
                sessionAudioLatencySumUs.toDouble() / sessionAudioLatencyCount / 1000.0
            } else {
                null
            },
        )
        post { observer.onSessionFinished(summary) }
    }

    private fun updateCredentials() {
        hosts = options.hosts().take(QrPayload.MAX_HOSTS)
        val port = transport.port
        qrUri = if (hosts.isEmpty()) {
            ""
        } else {
            QrPayload(hosts, port, pairing.qrToken, fingerprint, receiverName).toUri()
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(pairing.qrToken.toByteArray())
        val info = PairingInfo(qrUri, pairing.pin, port, fingerprint.toHex(), token)
        post { observer.onPairingChanged(info) }
        publish()
    }

    private fun nowMs(): Long = clockUs() / 1000

    public companion object {
        /** §11: connections held before authentication. */
        public const val MAX_UNAUTHENTICATED: Int = 16

        /** Keeps the name within the protocol's 255-byte `str` even when every character takes four bytes. */
        public const val MAX_NAME_CHARS: Int = 60
        private const val TICK_INTERVAL_MS = 250L
        private const val REPORT_INTERVAL_MS = 500L
        private const val SHUTDOWN_WAIT_US = 1_000_000L
        private const val SHUTDOWN_POLL_MS = 20L
        private const val U16_MAX = 0xFFFFL
        private const val U32_MAX = 0xFFFF_FFFFL
    }
}
