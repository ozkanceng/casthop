package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SupportingTest {
    private fun hex(s: String): ByteArray = Bytes.fromHex(s).toByteArray()

    // Parameter sets as ffmpeg's libx264 and libx265 wrote them.
    @Test
    fun readsH264Size() {
        val sps = hex("0000016742c01fda014016ec0440000003004000000f03c60ca8")
        assertEquals(VideoSize(1280, 720), VideoConfig.parseSize(Codec.H264, sps))
    }

    @Test
    fun readsCroppedH264Size() {
        val sps = hex("0000016742c020d900b80a3e22230110000003001000000303c0f1832480")
        assertEquals(VideoSize(722, 1282), VideoConfig.parseSize(Codec.H264, sps))
    }

    @Test
    fun readsH265Size() {
        val sps = hex("000001420101016000000300900000030000030078a003c08010e596566924caf0168080000003008000000f04")
        assertEquals(VideoSize(1920, 1080), VideoConfig.parseSize(Codec.H265, sps))
    }

    @Test
    fun configWithoutSpsHasNoSize() {
        assertNull(VideoConfig.parseSize(Codec.H264, hex("0000000168ce3c80")))
        assertNull(VideoConfig.parseSize(Codec.H264, hex("00000167")))
    }

    @Test
    fun readsOpusHead() {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 0x01, -0x80, -0x45, 0, 0, 0, 0, 0)
        assertEquals(AudioFormat(48_000, 2, 312), AudioConfig.parseOpusHead(head))
        assertNull(AudioConfig.parseOpusHead(ByteArray(19)))
    }

    @Test
    fun readsAudioSpecificConfig() {
        // AAC-LC, 48 kHz, stereo; and 44.1 kHz, mono.
        assertEquals(AudioFormat(48_000, 2), AudioConfig.parseAudioSpecificConfig(hex("1190")))
        assertEquals(AudioFormat(44_100, 1), AudioConfig.parseAudioSpecificConfig(hex("1208")))
        assertNull(AudioConfig.parseAudioSpecificConfig(hex("12")))
    }

    @Test
    fun instanceNameIsCutOnACharacterBoundary() {
        val name = "ş".repeat(40) // 80 bytes
        val cut = Discovery.instanceName(name)
        assertEquals("ş".repeat(26) + " (CastHop)", cut)
        assertTrue(cut.toByteArray().size <= 63)
    }

    @Test
    fun emptyInstanceNameFallsBack() {
        assertEquals("CastHop", Discovery.instanceName(""))
    }

    @Test
    fun instanceNameDiffersFromTheDeviceName() {
        assertEquals("Özkan TV (CastHop)", Discovery.instanceName("Özkan TV"))
    }

    @Test
    fun txtNameStaysTheDeviceName() {
        assertEquals("Özkan TV", Discovery.txt("Özkan TV", Auth.sha256("cert".toByteArray()))["name"])
    }

    @Test
    fun txtRecordReadsBackThroughTheSenderParser() {
        val fp = Auth.sha256("cert".toByteArray())
        val txt = Discovery.txt("Sınıf 3", fp).mapValues { it.value.toByteArray() }
        val parsed = assertNotNull(DiscoveryTxt.parse(txt)).let { DiscoveryTxt.parse(txt)!! }
        assertEquals("Sınıf 3", parsed.name)
        assertEquals(Bytes.of(fp.toByteArray().copyOf(16)), parsed.fingerprintPrefix)
    }

    @Test
    fun trustedStoreRoundTrips(@TempDir dir: File) {
        val store = FileTrustedStore(File(dir, "trusted"))
        assertTrue(store.load().isEmpty())
        val list = listOf(TrustedSender(id(1), key(2), id(3), "Ayşe Öğretmen – Tab S9"))
        store.save(list)
        assertEquals(list, store.load())
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun trustedStoreIsPrivate(@TempDir dir: File) {
        val file = File(dir, "trusted")
        FileTrustedStore(file).save(listOf(TrustedSender(id(1), key(2), id(3), "x")))
        val mode = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            mode,
        )
    }

    @Test
    fun trustedStoreSkipsDamagedLines(@TempDir dir: File) {
        val file = File(dir, "trusted")
        val good = TrustedSender(id(1), key(2), id(3), "ok")
        FileTrustedStore(file).save(listOf(good))
        file.appendText("not a record\nzz zz zz zz\n")
        assertEquals(listOf(good), FileTrustedStore(file).load())
    }

    @Test
    fun secretsStayOutOfToString() {
        val state = ReceiverState(qrUri = "yansit://secret", pin = "123456")
        val info = PairingInfo("yansit://secret", "123456", 47800, "aa", "token")
        for (text in listOf(state.toString(), info.toString())) {
            assertFalse(text.contains("secret") || text.contains("123456") || text.contains("token"))
        }
    }

    @Test
    fun pairingFileHasTheContractFields() {
        val json = PairingInfo("yansit://c?v=1", "012345", 47800, "ab", "tt").toJson()
        assertEquals(
            "{\"uri\":\"yansit://c?v=1\",\"pin\":\"012345\",\"port\":47800,\"fingerprint\":\"ab\",\"token\":\"tt\"}",
            json,
        )
    }

    @Test
    fun statsLineHasTheContractFields() {
        val json = SessionSummary("ab", 3, 2, 1, 12.5, null, "dec\"oder", false, 4, 3, 1, null).toJson()
        assertTrue(json.contains("\"frames_received\":3"))
        assertTrue(json.contains("\"frames_rendered\":2"))
        assertTrue(json.contains("\"frames_dropped\":1"))
        assertTrue(json.contains("\"first_frame_ms\":12.5"))
        assertTrue(json.contains("\"c2d_avg_ms\":null"))
        assertTrue(json.contains("\"decoder\":\"dec\\\"oder\""))
    }
}
