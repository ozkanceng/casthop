package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import java.net.Inet4Address

/** Bağlanamama nedenini sınıflandırmaya yardımcı ağ bilgisi (RISKS R1). */
object NetworkDiagnostics {
    /**
     * Tabletin IPv4 ağlarından herhangi biri tahtanın adreslerinden birini içeriyor mu?
     * Bilinmiyorsa (ağ bilgisi alınamadı) `null`.
     */
    fun sharesSubnet(context: Context, boardHosts: List<String>): Boolean? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        // Tablet hotspot yayınlıyorsa erişim noktası arayüzü burada görünmez: o durumda sonuç "bilinmiyor" olur.
        val prefixes = networks.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
            .filter { it.address is Inet4Address }
            .mapNotNull { la -> la.address.hostAddress?.let(::ipv4ToInt)?.let { it to la.prefixLength } }
        if (prefixes.isEmpty()) return null
        return boardHosts.any { host ->
            val h = ipv4ToInt(host) ?: return@any false
            prefixes.any { (addr, len) -> len in 1..32 && sameSubnet(addr, h, len) }
        }
    }

    /**
     * Is the tablet a client of a 2.4 GHz Wi-Fi? Crowded 2.4 GHz classrooms are the usual reason the picture cannot
     * keep up; 5 GHz carries it. `null` when unknown: the tablet shares its own hotspot, or Android is older than 12.
     */
    fun onWifi24GHz(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val frequency = cm.allNetworks.firstNotNullOfOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            (caps?.transportInfo as? WifiInfo)?.frequency?.takeIf { it > 0 }
        } ?: return null
        return is24GHz(frequency)
    }

    internal fun is24GHz(frequencyMhz: Int): Boolean = frequencyMhz in 2400..2500

    internal fun sameSubnet(a: Int, b: Int, prefixLength: Int): Boolean {
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (a and mask) == (b and mask)
    }

    internal fun ipv4ToInt(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            v = (v shl 8) or n
        }
        return v
    }
}
