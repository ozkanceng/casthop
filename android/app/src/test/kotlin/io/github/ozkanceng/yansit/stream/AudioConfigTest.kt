package io.github.ozkanceng.yansit.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioConfigTest {
    @Test
    fun opusHeadLayout() {
        val h = AudioCapture.opusHead(2, 48000)
        assertEquals(19, h.size)
        assertEquals("OpusHead", String(h, 0, 8, Charsets.US_ASCII))
        assertEquals(1, h[8].toInt()) // sürüm
        assertEquals(2, h[9].toInt()) // kanal
        assertEquals(312, (h[10].toInt() and 0xFF) or ((h[11].toInt() and 0xFF) shl 8)) // ön atlama (LE)
        assertEquals(48000, (0 until 4).sumOf { (h[12 + it].toInt() and 0xFF) shl (8 * it) }) // giriş hızı (LE)
        assertEquals(0, h[18].toInt()) // eşleme ailesi 0 — alıcı yalnız bunu kabul eder
    }

    @Test
    fun aacAudioSpecificConfig() {
        // AAC-LC, 48 kHz, stereo → 0x11 0x90 (tools/fake-sender ADTS testiyle aynı)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AudioCapture.aacAsc(48000, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x08), AudioCapture.aacAsc(44100, 1))
    }
}
