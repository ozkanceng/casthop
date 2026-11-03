package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.FrameReader
import io.github.ozkanceng.yansit.protocol.FrameType
import io.github.ozkanceng.yansit.protocol.FrameWriter
import io.github.ozkanceng.yansit.protocol.ProtocolError
import io.github.ozkanceng.yansit.protocol.ProtocolException
import io.github.ozkanceng.yansit.protocol.StreamKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException

/** CLOCK_MONOTONIC mikrosaniye (Android ve Linux JVM'de System.nanoTime). */
public object MonotonicClock {
    public fun nowUs(): Long = System.nanoTime() / 1000
}

/** CONTROL bağlantısı: gönderim iş parçacığı güvenlidir; alım tek bir okuyucu iş parçacığından yapılmalıdır. */
public class ControlChannel internal constructor(private val tls: PinnedSocket) : AutoCloseable {
    private val reader = FrameReader(BufferedInputStream(tls.socket.inputStream, 64 * 1024))
    private val writer = FrameWriter(BufferedOutputStream(tls.socket.outputStream, 16 * 1024))
    private var seq = 0L

    public val fingerprint: Bytes get() = tls.fingerprint

    /** 0 = sınırsız. */
    public var readTimeoutMs: Int
        get() = tls.socket.soTimeout
        set(value) {
            tls.socket.soTimeout = value
        }

    @Throws(IOException::class)
    @Synchronized
    public fun send(msg: ControlMessage) {
        writer.write(FrameType.CONTROL, 0, MonotonicClock.nowUs(), seq, ControlCodec.encode(msg))
        writer.flush()
        seq = (seq + 1) and 0xFFFFFFFFL
    }

    /** Bir sonraki mesaj; bağlantı düzgün kapandıysa `null`. Tanınmayan kritik olmayan mesajlar atlanır. */
    @Throws(IOException::class, ProtocolException::class)
    public fun receive(): ControlMessage? {
        while (true) {
            val frame = reader.read() ?: return null
            if (frame.header.type != FrameType.CONTROL) {
                throw ProtocolException(ProtocolError.UNKNOWN_FRAME_TYPE, "CONTROL bağlantısında ${frame.header.type}")
            }
            val msg = ControlCodec.decode(frame.payload)
            if (msg !is ControlMessage.Unknown) return msg
        }
    }

    override fun close() {
        tls.close()
    }
}

/** VIDEO/AUDIO bağlantısı (ATTACH sonrası yalnız S→R çerçeveleri). */
public class MediaChannel internal constructor(private val tls: PinnedSocket, public val kind: Int) : AutoCloseable {
    private val writer = FrameWriter(BufferedOutputStream(tls.socket.outputStream, 256 * 1024))
    private var seq = 0L

    /** Bir çerçeve gönderir ve hemen ağa bırakır (gecikme için tamponda bekletilmez). */
    @Throws(IOException::class)
    @Synchronized
    public fun send(flags: Int, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        val type = if (kind == StreamKind.AUDIO) FrameType.AUDIO else FrameType.VIDEO
        writer.write(type, flags, ptsUs, seq, payload, offset, length)
        writer.flush()
        seq = (seq + 1) and 0xFFFFFFFFL
    }

    /**
     * Çekirdek gönderim tamponu. Bit hızına göre küçük tutulur (≈ 100 ms veri) ki tıkanıklıkta gecikme
     * tamponda birikmek yerine gönderici kuyruğunda görülsün ve kare atılabilsin.
     */
    public fun setSendBufferBytes(bytes: Int) {
        try {
            tls.socket.sendBufferSize = bytes
        } catch (_: java.net.SocketException) {
        }
    }

    override fun close() {
        tls.close()
    }
}
