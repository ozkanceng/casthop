package io.github.ozkanceng.yansit.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Çerçeve türleri (PROTOCOL.md §3). */
public enum class FrameType(public val id: Int, public val maxPayload: Int) {
    VIDEO(0x01, 8 * 1024 * 1024),
    AUDIO(0x02, 64 * 1024),
    CONTROL(0x03, 64 * 1024);

    public companion object {
        public fun fromId(id: Int): FrameType? = entries.firstOrNull { it.id == id }
    }
}

/** Çerçeve bayrakları. Tanımsız bitler korunur ama anlam taşımaz. */
public object FrameFlags {
    public const val CONFIG: Int = 0x0001
    public const val KEYFRAME: Int = 0x0002
    public const val DISCONTINUITY: Int = 0x0004
}

/**
 * 24 baytlık çerçeve başlığı.
 * @property ptsUs u64, gönderenin monotonik saatinde mikrosaniye (ham 64 bit).
 * @property seq u32, 0..2^32-1.
 * @property length yük uzunluğu.
 */
public data class FrameHeader(
    val type: FrameType,
    val flags: Int,
    val ptsUs: Long,
    val seq: Long,
    val length: Int,
) {
    init {
        require(flags in 0..0xFFFF) { "flags u16 olmalı" }
        require(seq in 0..0xFFFF_FFFFL) { "seq u32 olmalı" }
        require(length in 0..type.maxPayload) { "yük sınırı aşıldı" }
    }

    public val isConfig: Boolean get() = flags and FrameFlags.CONFIG != 0
    public val isKeyframe: Boolean get() = flags and FrameFlags.KEYFRAME != 0
    public val isDiscontinuity: Boolean get() = flags and FrameFlags.DISCONTINUITY != 0
}

public object Frames {
    public const val MAGIC: Int = 0x5954
    public const val VERSION: Int = 1
    public const val HEADER_SIZE: Int = 24

    /** Başlığı [out] içine [offset]'ten itibaren yazar. */
    public fun encodeHeader(header: FrameHeader, out: ByteArray, offset: Int = 0) {
        require(out.size - offset >= HEADER_SIZE)
        var o = offset
        o = putU16(out, o, MAGIC)
        out[o++] = VERSION.toByte()
        out[o++] = header.type.id.toByte()
        o = putU16(out, o, header.flags)
        o = putU16(out, o, 0)
        o = putU64(out, o, header.ptsUs)
        o = putU32(out, o, header.seq)
        putU32(out, o, header.length.toLong())
    }

    /** Başlık + yükü tek bir dizi olarak kodlar. */
    public fun encode(type: FrameType, flags: Int, ptsUs: Long, seq: Long, payload: ByteArray): ByteArray {
        val header = FrameHeader(type, flags, ptsUs, seq, payload.size)
        val out = ByteArray(HEADER_SIZE + payload.size)
        encodeHeader(header, out)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * [buf]'un [offset]'indeki 24 baytı başlık olarak çözer.
     * @throws ProtocolException geçersiz başlıkta.
     */
    public fun decodeHeader(buf: ByteArray, offset: Int = 0, available: Int = buf.size - offset): FrameHeader {
        if (available < HEADER_SIZE) throw ProtocolException(ProtocolError.TRUNCATED, "başlık")
        var o = offset
        val magic = getU16(buf, o); o += 2
        if (magic != MAGIC) throw ProtocolException(ProtocolError.BAD_MAGIC)
        val version = buf[o++].toInt() and 0xFF
        if (version != VERSION) throw ProtocolException(ProtocolError.UNSUPPORTED_VERSION, "v$version")
        val typeId = buf[o++].toInt() and 0xFF
        val type = FrameType.fromId(typeId) ?: throw ProtocolException(ProtocolError.UNKNOWN_FRAME_TYPE, "$typeId")
        val flags = getU16(buf, o); o += 2
        o += 2 // reserved: yok sayılır
        val pts = getU64(buf, o); o += 8
        val seq = getU32(buf, o); o += 4
        val length = getU32(buf, o)
        if (length > type.maxPayload) throw ProtocolException(ProtocolError.FRAME_TOO_LARGE, "$length")
        return FrameHeader(type, flags, pts, seq, length.toInt())
    }

    internal fun putU16(b: ByteArray, o: Int, v: Int): Int {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte(); return o + 2
    }

    internal fun putU32(b: ByteArray, o: Int, v: Long): Int {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte(); return o + 4
    }

    internal fun putU64(b: ByteArray, o: Int, v: Long): Int {
        for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte()
        return o + 8
    }

    internal fun getU16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    internal fun getU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    internal fun getU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}

/** Bir çerçeve: başlık + yük. */
public class Frame(public val header: FrameHeader, public val payload: ByteArray)

/**
 * Akıştan çerçeve okur. Başlık tamponu yeniden kullanılır; her çağrı yeni bir yük dizisi döndürür.
 * Akış çerçeve sınırında biterse `null`, ortasında biterse [ProtocolException] (TRUNCATED).
 */
public class FrameReader(private val input: InputStream) {
    private val headerBuf = ByteArray(Frames.HEADER_SIZE)

    @Throws(IOException::class)
    public fun read(): Frame? {
        val first = readFully(headerBuf, 0, Frames.HEADER_SIZE, allowCleanEof = true) ?: return null
        check(first == Frames.HEADER_SIZE)
        val header = Frames.decodeHeader(headerBuf)
        val payload = ByteArray(header.length)
        readFully(payload, 0, header.length, allowCleanEof = false)
        return Frame(header, payload)
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int, allowCleanEof: Boolean): Int? {
        var n = 0
        while (n < len) {
            val r = try {
                input.read(buf, off + n, len - n)
            } catch (e: EOFException) {
                -1
            }
            if (r < 0) {
                if (n == 0 && allowCleanEof) return null
                throw ProtocolException(ProtocolError.TRUNCATED, "akış $n/$len baytta bitti")
            }
            n += r
        }
        return n
    }
}

/** Çerçeveleri tek parça halinde (başlık + yük, tek write çağrısı) yazar. İş parçacığı güvenli değildir. */
public class FrameWriter(private val output: OutputStream) {
    private var scratch = ByteArray(64 * 1024)

    @Throws(IOException::class)
    public fun write(type: FrameType, flags: Int, ptsUs: Long, seq: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        val header = FrameHeader(type, flags, ptsUs, seq, length)
        val total = Frames.HEADER_SIZE + length
        if (scratch.size < total) scratch = ByteArray(Integer.highestOneBit(total - 1) shl 1)
        Frames.encodeHeader(header, scratch)
        System.arraycopy(payload, offset, scratch, Frames.HEADER_SIZE, length)
        output.write(scratch, 0, total)
    }

    @Throws(IOException::class)
    public fun flush() { output.flush() }
}
