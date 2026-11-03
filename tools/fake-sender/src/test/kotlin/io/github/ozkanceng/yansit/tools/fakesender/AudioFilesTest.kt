package io.github.ozkanceng.yansit.tools.fakesender

import io.github.ozkanceng.yansit.protocol.Codec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class AudioFilesTest {
    private fun oggPage(vararg packets: ByteArray): ByteArray {
        val segs = ArrayList<Int>()
        for (p in packets) {
            var n = p.size
            while (n >= 255) { segs.add(255); n -= 255 }
            segs.add(n)
        }
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(ByteArray(22)) // sürüm, tür, granül, seri, sıra, CRC (ayrıştırıcı denetlemez)
        out.write(segs.size)
        segs.forEach { out.write(it) }
        packets.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val opusHead = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    @Test
    fun oggOpusPacketsAndDurations() {
        val big = ByteArray(300) { 0x78 } // TOC 0x78: CELT 20 ms, tek kare; 255 + 45 bayt iki segment
        val data = oggPage(opusHead) + oggPage("OpusTags".toByteArray()) + oggPage(byteArrayOf(0x78, 1, 2), big)
        val s = AudioFiles.parse(data)
        assertEquals(Codec.OPUS, s.codec)
        assertEquals(2, s.channels)
        assertArrayEquals(opusHead, s.config)
        assertEquals(2, s.packets.size)
        assertEquals(300, s.packets[1].first.size)
        assertEquals(20_000L, s.packets[0].second)
    }

    @Test
    fun opusTocDurations() {
        assertEquals(20_000L, AudioFiles.opusPacketDurationUs(byteArrayOf(0xFC.toByte()))) // CELT 20 ms
        assertEquals(10_000L, AudioFiles.opusPacketDurationUs(byteArrayOf(0x00))) // SILK 10 ms
        assertEquals(40_000L, AudioFiles.opusPacketDurationUs(byteArrayOf(0xF9.toByte()))) // CELT 20 ms × 2
        assertEquals(60_000L, AudioFiles.opusPacketDurationUs(byteArrayOf(0xFB.toByte(), 3))) // kod 3, 3 kare
    }

    @Test
    fun adtsToRawAacAndAsc() {
        // AAC-LC, 48 kHz (indeks 3), stereo, koruma yok; yük 3 bayt → çerçeve 10 bayt.
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), 0x01, 0x5F, 0xFC.toByte())
        val frame = header + byteArrayOf(1, 2, 3)
        val s = AudioFiles.parse(frame + frame)
        assertEquals(Codec.AAC_LC, s.codec)
        assertEquals(48000, s.sampleRate)
        assertEquals(2, s.channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), s.config) // objType 2, sf 3, ch 2
        assertEquals(2, s.packets.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.packets[0].first)
        assertEquals(21_333L, s.packets[0].second)
    }
}
