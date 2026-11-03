package io.github.ozkanceng.yansit.protocol

import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscoveryTxtTest {
    @Test
    fun parsesNameAndFingerprintPrefix() {
        val fp = Bytes.fromHex((0 until 16).joinToString("") { "%02x".format(it) })
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fp.toByteArray())
        val txt = mapOf("v" to "1".toByteArray(), "name" to "7-A Sınıfı".toByteArray(), "fp" to b64.toByteArray())
        val r = DiscoveryTxt.parse(txt)!!
        assertEquals("7-A Sınıfı", r.name)
        assertEquals(fp, r.fingerprintPrefix)
    }

    @Test
    fun rejectsOtherVersionsAndBadPrefix() {
        assertNull(DiscoveryTxt.parse(mapOf("v" to "2".toByteArray(), "fp" to "AAAAAAAAAAAAAAAAAAAAAA".toByteArray())))
        assertNull(DiscoveryTxt.parse(mapOf("v" to "1".toByteArray(), "fp" to "kısa".toByteArray())))
        assertNull(DiscoveryTxt.parse(mapOf("v" to "1".toByteArray())))
    }
}
