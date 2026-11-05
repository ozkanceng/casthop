package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.protocol.FrameReader
import io.github.ozkanceng.yansit.protocol.FrameType
import io.github.ozkanceng.yansit.protocol.FrameWriter
import io.github.ozkanceng.yansit.protocol.Frames
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.StreamKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Drives the engine over real loopback sockets, with TLS left out and the clock in the test's hands. */
@Timeout(20)
class ReceiverEngineTest {
    private class PlainSocket(private val socket: Socket) : ReceiverSocket {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun handshake() = Unit
        override fun abort() = close()
        override fun close() = socket.close()
    }

    private class PlainTransport : ServerTransport {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        override val port: Int get() = server.localPort
        override fun accept(): ReceiverSocket = PlainSocket(server.accept())
        override fun close() = server.close()
    }

    private inner class Peer {
        val socket = Socket(InetAddress.getLoopbackAddress(), transport.port)
        val reader = FrameReader(socket.getInputStream())
        val writer = FrameWriter(socket.getOutputStream())
        var seq = 0L
        val challenge: ControlMessage.Challenge

        init {
            socket.soTimeout = 5000
            challenge = receive() as ControlMessage.Challenge
        }

        fun receive(): ControlMessage? = reader.read()?.let { ControlCodec.decode(it.payload) }

        /** The next message of type [T], skipping periodic reports. */
        inline fun <reified T : ControlMessage> receiveA(): T {
            while (true) {
                val m = receive()
                if (m is T) return m
                assertTrue(m is ControlMessage.ReceiverReport, "unexpected $m")
            }
        }

        fun send(m: ControlMessage) {
            writer.write(FrameType.CONTROL, 0, clock.get(), seq++, ControlCodec.encode(m))
        }

        fun sendMedia(type: FrameType, flags: Int, pts: Long, payload: ByteArray) {
            writer.write(type, flags, pts, seq++, payload)
        }

        fun closed(): Boolean = try {
            while (true) {
                val m = receive() ?: return true
                assertTrue(m is ControlMessage.ReceiverReport || m is ControlMessage.Reject || m is ControlMessage.Bye)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (_: IOException) {
            true
        }

        fun hello(secret: ByteArray = pairingInfo().token(), method: Int = AuthMethod.QR_TOKEN, device: Bytes = id(0xA)) =
            ControlMessage.Hello(
                version = 1,
                deviceName = "Tablet",
                deviceId = device,
                appVersion = "test",
                authMethod = method,
                clientNonce = id(1),
                authProof = Auth.authProof(secret, method, challenge.serverNonce, id(1), fingerprint, device),
                videoCodecs = listOf(Codec.H264),
                maxWidth = 1920,
                maxHeight = 1080,
                maxFps = 60,
            )
    }

    private fun PairingInfo.token(): ByteArray = java.util.Base64.getUrlDecoder().decode(tokenBase64)

    private val clock = AtomicLong(1_000_000_000L)
    private val transport = PlainTransport()
    private val fingerprint = Auth.sha256("fp".toByteArray())
    private val videoBackend = RecordingVideoBackend()
    private val audioBackend = RecordingAudioBackend()
    private val summaries = java.util.concurrent.CopyOnWriteArrayList<SessionSummary>()
    private val pairings = java.util.concurrent.CopyOnWriteArrayList<PairingInfo>()
    private val store = object : TrustedStore {
        var saved = emptyList<TrustedSender>()
        override fun load() = saved
        override fun save(senders: List<TrustedSender>) {
            saved = senders
        }
    }
    private val observer = object : ReceiverObserver {
        override fun onPairingChanged(info: PairingInfo) {
            pairings += info
        }

        override fun onSessionFinished(summary: SessionSummary) {
            summaries += summary
        }
    }
    private val engine = ReceiverEngine(
        ReceiverEngine.Options(
            name = "Test TV",
            videoDecoders = listOf(CodecSupport(Codec.H264, CodecSupport.FLAG_PREFERRED)),
            audioDecoders = listOf(CodecSupport(Codec.OPUS, CodecSupport.FLAG_PREFERRED)),
            approvalTimeoutMs = 3000,
            hosts = { listOf("192.168.1.20") },
        ),
        transport,
        fingerprint,
        videoBackend,
        audioBackend,
        store,
        observer,
        clock::get,
    )
    private val acceptor = thread(isDaemon = true) {
        engine.open()
        try {
            while (true) {
                val socket = transport.accept()
                engine.accept(socket)?.let { c -> thread(isDaemon = true) { c.run() } }
            }
        } catch (_: IOException) {
            // Test over.
        }
    }

    private fun pairingInfo(): PairingInfo {
        while (pairings.isEmpty()) Thread.sleep(1)
        return pairings.last()
    }

    private fun advance(ms: Long) {
        clock.addAndGet(ms * 1000)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            assertTrue(System.nanoTime() < end, "timed out")
            Thread.sleep(2)
        }
    }

    @AfterEach
    fun tearDown() {
        engine.shutdown()
        acceptor.join(1000)
    }

    private fun open(peer: Peer): ControlMessage.Welcome {
        peer.send(peer.hello())
        return peer.receiveA()
    }

    private fun attach(welcome: ControlMessage.Welcome, kind: Int): Peer {
        val media = Peer()
        val proof = Auth.attachProof(welcome.attachKey, welcome.sessionId, kind, media.challenge.serverNonce)
        media.send(ControlMessage.Attach(welcome.sessionId, kind, proof))
        assertEquals(0, media.receiveA<ControlMessage.AttachResult>().status)
        return media
    }

    @Test
    fun challengeComesFirstWithAFreshNonce() {
        val a = Peer()
        val b = Peer()
        assertEquals("Test TV", a.challenge.receiverName)
        assertEquals(1, a.challenge.minVersion)
        assertEquals(1, a.challenge.maxVersion)
        assertTrue(a.challenge.serverNonce != b.challenge.serverNonce)
    }

    @Test
    fun aFirstMessageOtherThanHelloResumeAttachIsMalformed() {
        val p = Peer()
        p.send(ControlMessage.Ping(1, 1))
        assertEquals(RejectReason.MALFORMED, p.receiveA<ControlMessage.Reject>().reason)
        assertTrue(p.closed())
    }

    @Test
    fun anUnknownFirstMessageIsMalformedToo() {
        val p = Peer()
        p.writer.write(FrameType.CONTROL, 0, 0, 0, byteArrayOf(0x7E, 0))
        assertEquals(RejectReason.MALFORMED, p.receiveA<ControlMessage.Reject>().reason)
    }

    @Test
    fun aBrokenFrameClosesWithoutAnAnswer() {
        val p = Peer()
        p.socket.getOutputStream().write(ByteArray(Frames.HEADER_SIZE) { 0x41 })
        assertNull(runCatching { p.receive() }.getOrNull())
    }

    @Test
    fun mediaBeforeAuthenticationIsRefused() {
        val p = Peer()
        p.sendMedia(FrameType.VIDEO, 0, 0, byteArrayOf(1))
        assertEquals(RejectReason.MALFORMED, p.receiveA<ControlMessage.Reject>().reason)
    }

    @Test
    fun firstMessageHasTenSeconds() {
        val p = Peer()
        advance(9_900)
        engine.tick()
        p.socket.soTimeout = 100
        assertTrue(runCatching { p.receive() }.exceptionOrNull() is java.net.SocketTimeoutException)
        p.socket.soTimeout = 5000
        advance(100)
        engine.tick()
        assertTrue(p.closed())
    }

    @Test
    fun controlSilenceEndsItAfterFiveSecondsAndOpensTheResumeWindow() {
        val p = Peer()
        open(p)
        advance(5000)
        engine.tick()
        assertTrue(p.closed())
        waitUntil { engine.currentState.phase == ReceiverPhase.RECONNECTING }
        advance(10_000)
        engine.tick()
        waitUntil { engine.currentState.phase == ReceiverPhase.WAITING }
        waitUntil { summaries.size == 1 }
    }

    @Test
    fun mediaConnectionMayStaySilent() {
        val control = Peer()
        val welcome = open(control)
        val video = attach(welcome, StreamKind.VIDEO)
        advance(4000)
        control.send(ControlMessage.Ping(1, clock.get()))
        control.receiveA<ControlMessage.Pong>()
        advance(4000)
        engine.tick()
        video.socket.soTimeout = 100
        assertTrue(runCatching { video.receive() }.exceptionOrNull() is java.net.SocketTimeoutException)
    }

    @Test
    fun pendingConnectionIsNotTimedOutBySilence() {
        val first = Peer()
        open(first)
        // Keeps the first session alive past the five seconds below.
        val second = Peer()
        second.send(second.hello(Auth.pinSecret(engine.currentState.pin), AuthMethod.PIN, id(0xB)))
        assertEquals(3000L, second.receiveA<ControlMessage.PendingApproval>().timeoutMs)
        waitUntil { engine.currentState.pendingSenderName == "Tablet" }
        advance(2900)
        engine.tick()
        second.socket.soTimeout = 100
        assertTrue(runCatching { second.receive() }.exceptionOrNull() is java.net.SocketTimeoutException)
        second.socket.soTimeout = 5000
        advance(100)
        engine.tick()
        assertEquals(RejectReason.BUSY_TIMEOUT, second.receiveA<ControlMessage.Reject>().reason)
    }

    @Test
    fun approvalMovesTheSessionToTheNewSender() {
        val first = Peer()
        open(first)
        val second = Peer()
        second.send(second.hello(Auth.pinSecret(engine.currentState.pin), AuthMethod.PIN, id(0xB)))
        second.receiveA<ControlMessage.PendingApproval>()
        waitUntil { engine.currentState.pendingSenderName != null }
        engine.approvePending()
        second.receiveA<ControlMessage.Welcome>()
        assertEquals(ByeReason.TAKEOVER, first.receiveA<ControlMessage.Bye>().reason)
        // The approved connection now has the CONTROL silence rule.
        advance(5000)
        engine.tick()
        assertTrue(second.closed())
    }

    @Test
    fun onlySixteenConnectionsWaitForAuthentication() {
        val waiting = List(ReceiverEngine.MAX_UNAUTHENTICATED) { Peer() }
        val extra = Socket(InetAddress.getLoopbackAddress(), transport.port)
        extra.soTimeout = 5000
        assertEquals(-1, runCatching { extra.getInputStream().read() }.getOrDefault(-1))
        waiting.first().let { assertEquals("Test TV", it.challenge.receiverName) }
    }

    @Test
    fun anAuthenticatedConnectionFreesItsPlace() {
        val control = Peer()
        open(control)
        List(ReceiverEngine.MAX_UNAUTHENTICATED) { Peer() }
    }

    @Test
    fun unknownCriticalMessageEndsTheSessionWithMalformed() {
        val p = Peer()
        open(p)
        p.writer.write(FrameType.CONTROL, 0, 0, p.seq++, byteArrayOf(0x7E, 1))
        assertEquals(ByeReason.MALFORMED, p.receiveA<ControlMessage.Bye>().reason)
    }

    @Test
    fun unknownOptionalMessageIsIgnored() {
        val p = Peer()
        open(p)
        p.writer.write(FrameType.CONTROL, 0, 0, p.seq++, byteArrayOf(0x7E, 0))
        p.send(ControlMessage.Ping(5, 77))
        assertEquals(5, p.receiveA<ControlMessage.Pong>().pingId)
    }

    @Test
    fun controlOnAMediaConnectionCloses() {
        val control = Peer()
        val video = attach(open(control), StreamKind.VIDEO)
        video.send(ControlMessage.Ping(1, 1))
        assertTrue(video.closed())
    }

    @Test
    fun pongEchoesAndOffsetReachesTheMediaPipelines() {
        val control = Peer()
        val welcome = open(control)
        assertTrue(welcome.audioEnabled)
        val audio = attach(welcome, StreamKind.AUDIO)
        audio.sendMedia(FrameType.AUDIO, FrameFlags.CONFIG, 0, byteArrayOf(Codec.OPUS.toByte(), 1))
        audio.sendMedia(FrameType.AUDIO, 0, clock.get(), byteArrayOf(2))
        waitUntil { engine.audioPipeline.totalReceived == 1L }
        assertTrue(audioBackend.submitted.isEmpty(), "dropped until the offset is known")

        control.send(ControlMessage.Ping(9, 123, offsetUs = 0))
        val pong = control.receiveA<ControlMessage.Pong>()
        assertEquals(9, pong.pingId)
        assertEquals(123, pong.t0Us)
        assertEquals(clock.get(), pong.t1Us)
        assertEquals(clock.get(), pong.t2Us)

        audio.sendMedia(FrameType.AUDIO, 0, clock.get(), byteArrayOf(3))
        waitUntil { audioBackend.submitted.isNotEmpty() }
        assertEquals(clock.get() + AudioPipeline.MIN_TARGET_LATENCY_US, audioBackend.submitted.single().second)
    }

    @Test
    fun reportsCountTheIntervalAndTheSummaryTheSession() {
        videoBackend.onSubmit = null
        val control = Peer()
        val welcome = open(control)
        val video = attach(welcome, StreamKind.VIDEO)
        control.send(ControlMessage.Ping(1, 1, offsetUs = 0))
        control.receiveA<ControlMessage.Pong>()

        video.sendMedia(FrameType.VIDEO, FrameFlags.CONFIG, 0, byteArrayOf(Codec.H264.toByte(), 9))
        video.sendMedia(FrameType.VIDEO, FrameFlags.KEYFRAME, clock.get(), byteArrayOf(1, 2, 3))
        waitUntil { videoBackend.submitted.size == 1 }
        assertEquals(ReceiverPhase.CONNECTING, engine.currentState.phase)
        advance(30)
        engine.videoPipeline.onRendered(videoBackend.submitted[0].second, clock.get())
        waitUntil { engine.currentState.phase == ReceiverPhase.STREAMING }

        advance(470)
        engine.sendReport()
        val report = control.receiveA<ControlMessage.ReceiverReport>()
        assertEquals(500, report.intervalMs)
        assertEquals(1, report.framesReceived)
        assertEquals(1, report.framesRendered)
        assertEquals(3, report.bytesReceived)
        assertEquals(30_000, report.c2dAvgUs)
        assertEquals(0, report.decodeBacklog)

        control.send(ControlMessage.Bye(ByeReason.USER_STOP))
        waitUntil { summaries.size == 1 }
        val s = summaries.single()
        assertEquals(welcome.sessionId.toHex(), s.sessionIdHex)
        assertEquals(1, s.framesReceived)
        assertEquals(1, s.framesRendered)
        assertNotNull(s.firstFrameMs)
        assertEquals(30.0, s.c2dAvgMs)
    }

    @Test
    fun pauseShowsAndUnpauseReturns() {
        val control = Peer()
        open(control)
        control.send(ControlMessage.Pause)
        waitUntil { engine.currentState.phase == ReceiverPhase.PAUSED }
        control.send(ControlMessage.Unpause)
        waitUntil { engine.currentState.phase == ReceiverPhase.CONNECTING }
    }

    @Test
    fun usedCredentialsChangeThePairingFile() {
        val before = pairingInfo()
        val p = Peer()
        open(p)
        waitUntil { pairingInfo().tokenBase64 != before.tokenBase64 }
    }

    @Test
    fun pairingSurvivesInTheStore() {
        val p = Peer()
        p.send(p.hello().copy(requestPairing = true))
        val w = p.receiveA<ControlMessage.Welcome>()
        assertNotNull(w.pairKey)
        assertEquals(w.keyId, store.saved.single().keyId)
        waitUntil { engine.currentState.trusted.isNotEmpty() }
        engine.forgetSender(w.keyId!!.toHex())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun stoppingTheSessionSendsUserStop() {
        val p = Peer()
        open(p)
        engine.stopSession()
        assertEquals(ByeReason.USER_STOP, p.receiveA<ControlMessage.Bye>().reason)
    }

    @Test
    fun shutdownSaysGoodbye() {
        val p = Peer()
        open(p)
        engine.shutdown()
        assertEquals(ByeReason.RECEIVER_CLOSING, p.receiveA<ControlMessage.Bye>().reason)
    }
}
