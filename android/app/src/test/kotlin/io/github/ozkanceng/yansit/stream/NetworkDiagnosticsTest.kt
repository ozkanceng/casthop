package io.github.ozkanceng.yansit.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkDiagnosticsTest {
    private fun ip(s: String) = NetworkDiagnostics.ipv4ToInt(s)!!

    @Test
    fun parsesDottedQuad() {
        assertEquals(0xC0A80114.toInt(), ip("192.168.1.20"))
        assertNull(NetworkDiagnostics.ipv4ToInt("192.168.1"))
        assertNull(NetworkDiagnostics.ipv4ToInt("192.168.1.256"))
        assertNull(NetworkDiagnostics.ipv4ToInt("fe80::1"))
    }

    @Test
    fun subnetComparison() {
        assertTrue(NetworkDiagnostics.sameSubnet(ip("192.168.1.10"), ip("192.168.1.20"), 24))
        assertFalse(NetworkDiagnostics.sameSubnet(ip("192.168.1.10"), ip("192.168.2.20"), 24))
        assertTrue(NetworkDiagnostics.sameSubnet(ip("10.10.5.1"), ip("10.10.200.7"), 16))
        assertTrue(NetworkDiagnostics.sameSubnet(ip("172.16.0.1"), ip("172.16.0.1"), 32))
        assertFalse(NetworkDiagnostics.sameSubnet(ip("172.16.0.1"), ip("172.16.0.2"), 32))
    }
}
