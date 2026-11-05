package io.github.ozkanceng.casthop.tv.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** Local IPv4 addresses for the QR (PROTOCOL.md §8, §11): Ethernet first, then Wi-Fi, then anything else. */
internal object LocalAddresses {
    fun list(): List<String> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
        return interfaces
            .filter(::usable)
            .sortedBy { rank(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }

    private fun usable(nic: NetworkInterface): Boolean = try {
        nic.isUp && !nic.isLoopback
    } catch (_: SocketException) {
        false
    }

    private fun rank(name: String): Int = when {
        name.startsWith("eth") -> 0
        name.startsWith("wlan") -> 1
        else -> 2
    }
}
