package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameHeader
import io.github.ozkanceng.yansit.protocol.FrameType
import io.github.ozkanceng.yansit.protocol.FrameWriter
import io.github.ozkanceng.yansit.protocol.Frames
import io.github.ozkanceng.yansit.protocol.ProtocolException
import io.github.ozkanceng.yansit.protocol.RejectReason
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One TLS connection: framing, CHALLENGE, first-message and silence timeouts. Port of
 * `receiver/src/net/Connection`. The upper layer picks the role from the first message.
 *
 * Two threads: [run] reads on the caller's thread and a writer drains a bounded queue, so [sendControl] never blocks
 * the engine on a stalled peer. The engine calls [checkDeadline] from its tick; the reader uses no socket timeout,
 * which would lose its place inside a frame.
 */
public class Connection(
    public val id: ConnId,
    private val socket: ReceiverSocket,
    private val receiverName: String,
    private val handlers: Handlers,
    private val clockUs: () -> Long,
) {
    /** PENDING: a CONTROL connection waiting for approval on the screen, so silence is not a timeout. */
    public enum class Role { UNKNOWN, PENDING, CONTROL, MEDIA }

    public interface Handlers {
        /** A decoded CONTROL message and when it arrived (µs). */
        public fun onControl(connection: Connection, message: ControlMessage, recvUs: Long)

        /** A VIDEO or AUDIO frame. Only called in the MEDIA role. */
        public fun onMedia(connection: Connection, header: FrameHeader, payload: ByteArray, recvUs: Long)

        /** The connection is over. Called once. */
        public fun onClosed(connection: Connection)
    }

    public val serverNonce: Bytes = Auth.randomBytes(ControlCodec.ID_SIZE)

    @Volatile
    public var role: Role = Role.UNKNOWN
        private set

    /** Absolute time (µs) after which the connection is dropped; 0 when there is none. */
    @Volatile
    private var deadlineUs = clockUs() + FIRST_MESSAGE_TIMEOUT_US

    @Volatile
    private var closing = false

    @Volatile
    private var writer: Thread? = null

    private val finished = AtomicBoolean(false)
    private val outgoing = ArrayBlockingQueue<Any>(MAX_QUEUED_MESSAGES)

    /** True once the connection stopped counting as waiting to authenticate. */
    public val isClosing: Boolean get() = closing

    public fun setRole(role: Role) {
        this.role = role
        deadlineUs = if (role == Role.CONTROL) clockUs() + CONTROL_SILENCE_TIMEOUT_US else 0
    }

    /** TLS, then CHALLENGE, then the read loop until the connection ends. Blocks the calling thread. */
    public fun run() {
        try {
            socket.handshake()
            deadlineUs = clockUs() + FIRST_MESSAGE_TIMEOUT_US
            writer = thread(isDaemon = true, name = "yansit-conn-$id-write") { writeLoop() }
            sendControl(ControlMessage.Challenge(1, 1, serverNonce, receiverName.ifEmpty { null }))
            readLoop()
        } catch (_: IOException) {
            // The peer went away or TLS failed: same end as an orderly close.
        } catch (_: ProtocolException) {
            // Frame boundaries are lost, so nothing can be answered (PROTOCOL.md §9.1).
            abort()
        }
        finish()
    }

    /** Queues a message. A peer that stopped reading long enough to fill the queue is dropped. */
    public fun sendControl(message: ControlMessage) {
        if (finished.get() || closing) return
        if (!outgoing.offer(message)) abort()
    }

    /** Sends what is queued, then closes. The engine's tick aborts after [CLOSE_GRACE_US]. */
    public fun closeGracefully() {
        if (closing || finished.get()) return
        closing = true
        deadlineUs = clockUs() + CLOSE_GRACE_US
        if (writer == null || !outgoing.offer(CLOSE)) abort()
    }

    /** Closes at once. Runs off the caller's thread: closing TLS can wait for the peer. */
    public fun abort() {
        closing = true
        deadlineUs = 0
        thread(isDaemon = true, name = "yansit-conn-$id-abort") { socket.abort() }
    }

    /** Drops the connection when its deadline has passed. */
    public fun checkDeadline(nowUs: Long) {
        val deadline = deadlineUs
        if (deadline != 0L && nowUs >= deadline) abort()
    }

    private fun readLoop() {
        val input = BufferedInputStream(socket.input, READ_BUFFER_SIZE)
        val headerBuf = ByteArray(Frames.HEADER_SIZE)
        while (!closing) {
            if (!readFrameStart(input, headerBuf)) return
            val header = Frames.decodeHeader(headerBuf)
            // Refused before the payload is allocated: an unauthenticated peer must not cost 8 MiB per header.
            if (header.type != FrameType.CONTROL && role != Role.MEDIA) {
                protocolViolation()
                return
            }
            val payload = ByteArray(header.length)
            readFully(input, payload, 0)
            handleFrame(header, payload)
        }
    }

    private fun handleFrame(header: FrameHeader, payload: ByteArray) {
        val recvUs = clockUs()
        if (role == Role.CONTROL) deadlineUs = recvUs + CONTROL_SILENCE_TIMEOUT_US
        if (header.type != FrameType.CONTROL) {
            handlers.onMedia(this, header, payload, recvUs)
            return
        }
        if (role == Role.MEDIA) {
            protocolViolation()
            return
        }
        val message = try {
            ControlCodec.decode(payload)
        } catch (_: ProtocolException) {
            protocolViolation()
            return
        }
        handlers.onControl(this, message, recvUs)
    }

    private fun protocolViolation() {
        when (role) {
            Role.CONTROL, Role.PENDING -> sendControl(ControlMessage.Bye(ByeReason.MALFORMED))
            Role.UNKNOWN -> sendControl(ControlMessage.Reject(RejectReason.MALFORMED))
            Role.MEDIA -> Unit
        }
        closeGracefully()
    }

    private fun writeLoop() {
        var seq = 0L
        try {
            val out = FrameWriter(BufferedOutputStream(socket.output, WRITE_BUFFER_SIZE))
            while (true) {
                val item = outgoing.take()
                if (item === CLOSE) break
                out.write(FrameType.CONTROL, 0, clockUs(), seq, ControlCodec.encode(item as ControlMessage))
                out.flush()
                seq = (seq + 1) and 0xFFFF_FFFFL
            }
        } catch (_: IOException) {
            // Nothing to do: the reader sees the same failure.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        socket.close()
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        closing = true
        deadlineUs = 0
        handlers.onClosed(this)
        val w = writer
        if (w == null) {
            socket.abort()
            return
        }
        // Lets a queued REJECT or BYE leave; a writer stuck on a dead peer is cut off.
        outgoing.offer(CLOSE)
        w.join(CLOSE_GRACE_US / 1000)
        if (w.isAlive) socket.abort()
    }

    public companion object {
        public const val FIRST_MESSAGE_TIMEOUT_US: Long = 10_000_000L
        public const val CONTROL_SILENCE_TIMEOUT_US: Long = 5_000_000L
        public const val CLOSE_GRACE_US: Long = 2_000_000L
        private const val MAX_QUEUED_MESSAGES = 256
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val WRITE_BUFFER_SIZE = 16 * 1024
        private val CLOSE = Any()

        /** False on a clean end of stream at a frame boundary. */
        private fun readFrameStart(input: InputStream, buf: ByteArray): Boolean {
            val first = input.read(buf, 0, buf.size)
            if (first < 0) return false
            readFully(input, buf, first)
            return true
        }

        private fun readFully(input: InputStream, buf: ByteArray, start: Int) {
            var n = start
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) throw IOException("stream ended inside a frame")
                n += r
            }
        }
    }
}
