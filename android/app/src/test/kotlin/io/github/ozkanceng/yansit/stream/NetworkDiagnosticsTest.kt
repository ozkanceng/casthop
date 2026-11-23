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

    @Test
    fun tellsTheCrowdedBandFromTheOthers() {
        assertTrue(NetworkDiagnostics.is24GHz(2422)) // channel 3, both classrooms seen so far
        assertTrue(NetworkDiagnostics.is24GHz(2484))
        assertFalse(NetworkDiagnostics.is24GHz(5180))
        assertFalse(NetworkDiagnostics.is24GHz(5955)) // 6 GHz
    }

    @Test
    fun detectsHotspotInterfacesAndValidatesIpv4() {
        assertTrue(NetworkDiagnostics.isValidIpv4("192.168.43.102"))
        assertFalse(NetworkDiagnostics.isValidIpv4("192.168.43.300"))
        assertFalse(NetworkDiagnostics.isValidIpv4("etap.local"))

        assertTrue(NetworkDiagnostics.isHotspotInterfaceName("ap0"))
        assertTrue(NetworkDiagnostics.isHotspotInterfaceName("swlan0"))
        assertTrue(NetworkDiagnostics.isHotspotInterfaceName("wlan1"))
        assertTrue(NetworkDiagnostics.isHotspotInterfaceName("rndis0"))
        assertFalse(NetworkDiagnostics.isHotspotInterfaceName("wlan0"))
        assertFalse(NetworkDiagnostics.isHotspotInterfaceName("rmnet_data0"))

        val hotspotIfaces = listOf(
            NetworkDiagnostics.LocalInterface("rmnet_data0", ip("100.64.1.2"), 24),
            NetworkDiagnostics.LocalInterface("swlan0", ip("192.168.43.1"), 24),
        )
        assertTrue(NetworkDiagnostics.hasHotspotInterface(hotspotIfaces))
        assertFalse(
            NetworkDiagnostics.hasHotspotInterface(
                listOf(NetworkDiagnostics.LocalInterface("wlan0", ip("192.168.1.50"), 24)),
            ),
        )
    }
}
