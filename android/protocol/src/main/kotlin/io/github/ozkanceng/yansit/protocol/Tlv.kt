package io.github.ozkanceng.yansit.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val MAX_STR_BYTES = 255

/** Etiketler artan sırada yazılmalıdır (kanonik kodlama). */
internal class TlvWriter(msgType: Int, msgFlags: Int = 0) {
    private val out = ByteArrayOutputStream(64)
    private var lastTag = -1

    init {
        out.write(msgType); out.write(msgFlags)
    }

    private fun header(tag: Int, len: Int) {
        check(tag > lastTag) { "TLV etiketleri artan sırada olmalı" }
        lastTag = tag
        out.write(tag); out.write(len ushr 8); out.write(len and 0xFF)
    }

    fun u8(tag: Int, v: Int) { require(v in 0..0xFF); header(tag, 1); out.write(v) }
    fun bool(tag: Int, v: Boolean) = u8(tag, if (v) 1 else 0)
    fun u16(tag: Int, v: Int) { require(v in 0..0xFFFF); header(tag, 2); out.write(v ushr 8); out.write(v and 0xFF) }
    fun u32(tag: Int, v: Long) {
        require(v in 0..0xFFFF_FFFFL); header(tag, 4)
        for (i in 3 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
    fun u64(tag: Int, v: Long) { header(tag, 8); for (i in 7 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun bytes(tag: Int, v: Bytes, expectedSize: Int) {
        require(v.size == expectedSize) { "alan $expectedSize bayt olmalı" }
        header(tag, v.size); out.write(v.unsafeArray())
    }
    fun str(tag: Int, v: String) {
        val b = v.toByteArray(Charsets.UTF_8)
        require(b.size <= MAX_STR_BYTES) { "dizi $MAX_STR_BYTES baytı aşıyor" }
        header(tag, b.size); out.write(b)
    }
    fun list(tag: Int, v: List<Int>) {
        require(v.size <= 0xFFFF); header(tag, v.size)
        for (x in v) { require(x in 0..0xFF); out.write(x) }
    }
    fun pairs(tag: Int, v: List<CodecSupport>) {
        header(tag, v.size * 2)
        for (p in v) { out.write(p.codec); out.write(p.flags) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Kontrol mesajı gövdesini ayrıştırır; her etiketi en fazla bir kez kabul eder. */
internal class TlvReader private constructor(
    val msgType: Int,
    val msgFlags: Int,
    private val fields: Map<Int, ByteArray>,
) {
    fun has(tag: Int): Boolean = tag in fields

    private fun raw(tag: Int, required: Boolean): ByteArray? {
        val v = fields[tag]
        if (v == null && required) throw ProtocolException(ProtocolError.MISSING_TAG, "tag 0x%02x".format(tag))
        return v
    }

    private fun fixed(tag: Int, size: Int, required: Boolean): ByteArray? {
        val v = raw(tag, required) ?: return null
        if (v.size != size) throw ProtocolException(ProtocolError.BAD_FIELD_LENGTH, "tag 0x%02x: ${v.size} != $size".format(tag))
        return v
    }

    fun u8(tag: Int): Int = u8OrNull(tag, true)!!
    fun u8OrNull(tag: Int, required: Boolean = false): Int? = fixed(tag, 1, required)?.let { it[0].toInt() and 0xFF }
    fun bool(tag: Int): Boolean = u8(tag) != 0
    fun boolOrNull(tag: Int): Boolean? = u8OrNull(tag)?.let { it != 0 }
    fun u16(tag: Int): Int = u16OrNull(tag, true)!!
    fun u16OrNull(tag: Int, required: Boolean = false): Int? = fixed(tag, 2, required)?.let { Frames.getU16(it, 0) }
    fun u32(tag: Int): Long = u32OrNull(tag, true)!!
    fun u32OrNull(tag: Int, required: Boolean = false): Long? = fixed(tag, 4, required)?.let { Frames.getU32(it, 0) }
    fun u64(tag: Int): Long = u64OrNull(tag, true)!!
    fun u64OrNull(tag: Int, required: Boolean = false): Long? = fixed(tag, 8, required)?.let { Frames.getU64(it, 0) }
    fun bytes(tag: Int, size: Int): Bytes = bytesOrNull(tag, size, true)!!
    fun bytesOrNull(tag: Int, size: Int, required: Boolean = false): Bytes? = fixed(tag, size, required)?.let { Bytes.wrap(it) }
    fun str(tag: Int): String = strOrNull(tag, true)!!
    fun strOrNull(tag: Int, required: Boolean = false): String? {
        val v = raw(tag, required) ?: return null
        if (v.size > MAX_STR_BYTES) throw ProtocolException(ProtocolError.STRING_TOO_LONG, "tag 0x%02x".format(tag))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(v)).toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolException(ProtocolError.BAD_UTF8, "tag 0x%02x".format(tag))
        }
    }
    fun list(tag: Int): List<Int> = listOrNull(tag, true)!!
    fun listOrNull(tag: Int, required: Boolean = false): List<Int>? =
        raw(tag, required)?.map { it.toInt() and 0xFF }
    fun pairs(tag: Int): List<CodecSupport> {
        val v = raw(tag, true)!!
        if (v.size % 2 != 0) throw ProtocolException(ProtocolError.BAD_FIELD_LENGTH, "tag 0x%02x: tek uzunluk".format(tag))
        return (v.indices step 2).map { CodecSupport(v[it].toInt() and 0xFF, v[it + 1].toInt() and 0xFF) }
    }

    companion object {
        fun parse(payload: ByteArray): TlvReader {
            if (payload.isEmpty()) throw ProtocolException(ProtocolError.EMPTY_CONTROL)
            if (payload.size < 2) throw ProtocolException(ProtocolError.TRUNCATED, "msg_flags eksik")
            val fields = HashMap<Int, ByteArray>()
            var o = 2
            while (o < payload.size) {
                if (payload.size - o < 3) throw ProtocolException(ProtocolError.MALFORMED_TLV, "yarım TLV başlığı")
                val tag = payload[o].toInt() and 0xFF
                val len = Frames.getU16(payload, o + 1)
                o += 3
                if (payload.size - o < len) throw ProtocolException(ProtocolError.MALFORMED_TLV, "tag 0x%02x taşıyor".format(tag))
                if (fields.containsKey(tag)) throw ProtocolException(ProtocolError.DUPLICATE_TAG, "tag 0x%02x".format(tag))
                fields[tag] = payload.copyOfRange(o, o + len)
                o += len
            }
            return TlvReader(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, fields)
        }
    }
}
