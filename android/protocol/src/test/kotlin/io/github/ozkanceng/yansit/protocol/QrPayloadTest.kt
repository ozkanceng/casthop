package io.github.ozkanceng.yansit.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QrPayloadTest {
    private val token = Bytes.fromHex("e0e1e2e3e4e5e6e7e8e9eaebecedeeef")
    private val fp = Auth.sha256("x".toByteArray())

    @Test
    fun `round trip with unicode and reserved characters in name`() {
        val p = QrPayload(listOf("192.168.43.1"), 47800, token, fp, "Çağrı'nın tahtası #3 & 100%")
        assertEquals(p, QrPayload.parse(p.toUri()))
    }

    @Test
    fun `unknown parameters are ignored and name is optional`() {
        val uri = QrPayload(listOf("10.0.0.1"), 1, token, fp, "").toUri().replace("&n=", "&x=future&n=")
        assertEquals("", QrPayload.parse(uri).name)
        val noName = uri.substringBefore("&n=")
        assertEquals("", QrPayload.parse(noName).name)
    }

    @Test
    fun `ipv4 validation is strict`() {
        assertTrue(QrPayload.isIPv4("0.0.0.0"))
        assertTrue(QrPayload.isIPv4("255.255.255.255"))
        assertFalse(QrPayload.isIPv4("01.2.3.4"))
        assertFalse(QrPayload.isIPv4("1.2.3"))
        assertFalse(QrPayload.isIPv4("1.2.3.4.5"))
        assertFalse(QrPayload.isIPv4("a.b.c.d"))
        assertFalse(QrPayload.isIPv4("1.2.3.-4"))
    }

    @Test
    fun `bad percent encoding is a bad field`() {
        val uri = QrPayload(listOf("10.0.0.1"), 1, token, fp, "a").toUri() + "%G1"
        assertEquals(QrError.BAD_FIELD, assertThrows<QrException> { QrPayload.parse(uri) }.error)
    }
}
