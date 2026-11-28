package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.net.SocketFactory

/** Bağlanamama nedenini sınıflandırmaya, hotspot ve pil kısıtlamasını algılamaya yardımcı ağ/sistem bilgisi. */
object NetworkDiagnostics {
    internal data class LocalInterface(val name: String, val address: Int, val prefixLength: Int)

    /**
     * Tabletin IPv4 ağlarından (Wi-Fi, Ethernet ve aktif mobil erişim noktası arayüzleri dahil) herhangi biri
     * tahtanın adreslerinden birini içeriyor mu? Bilinmiyorsa `null`.
     */
    fun sharesSubnet(context: Context, boardHosts: List<String>): Boolean? {
        val prefixes = localPrefixes(context)
        if (prefixes.isEmpty()) return null
        return boardHosts.any { host ->
            val h = ipv4ToInt(host) ?: return@any false
            prefixes.any { (addr, len) -> len in 1..32 && sameSubnet(addr, h, len) }
        }
    }

    /**
     * Aday tahta IP'lerini önceliklendirir: tabletin şu an bağlı olduğu yerel alt ağla eşleşen IPv4'ler en öne alınır,
     * geri kalanlar mevcut sırasıyla korunur.
     */
    fun prioritizeHosts(context: Context, hosts: List<String>): List<String> =
        prioritizeHosts(hosts, localPrefixes(context))

    internal fun prioritizeHosts(hosts: List<String>, prefixes: List<Pair<Int, Int>>): List<String> {
        val clean = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.size <= 1 || prefixes.isEmpty()) return clean
        val matched = ArrayList<String>(clean.size)
        val rest = ArrayList<String>(clean.size)
        for (host in clean) {
            val ip = ipv4ToInt(host)
            val onSameSubnet = ip != null && prefixes.any { (addr, len) -> len in 1..32 && sameSubnet(addr, ip, len) }
            if (onSameSubnet) matched += host else rest += host
        }
        return matched + rest
    }

    /**
     * Hedef yerel IP için en uygun Wi-Fi veya Ethernet [Network] nesnesini seçer.
     * - Doğrulanmamış (internetsiz/MEB filtreli) okul Wi-Fi'ı + açık mobil veri senaryosunda TCP paketlerinin
     *   mobil veriye kaçmasını kesin olarak engeller.
     * - Cihaz kendi hotspot'unu (soft-AP) paylaşıyorsa ve hedef IP bir istemci ağına uymuyorsa `null` döner
     *   (sistem varsayılan soketi soft-AP arayüzüne yönlendirir).
     */
    fun selectLocalNetwork(context: Context, targetHost: String): Network? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val targetIp = ipv4ToInt(targetHost.trim())
        val lanNetworks = cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifi && !isEth) return@mapNotNull null
            Triple(n, isWifi, cm.getLinkProperties(n))
        }
        if (lanNetworks.isEmpty()) return null

        // 1) Alt ağı hedef IP ile birebir örtüşen Wi-Fi / Ethernet ağı
        if (targetIp != null) {
            for ((network, _, props) in lanNetworks) {
                val addrs = props?.linkAddresses.orEmpty()
                val matches = addrs.any { la ->
                    val addr = la.address as? Inet4Address ?: return@any false
                    val ip = addr.hostAddress?.let(::ipv4ToInt) ?: return@any false
                    la.prefixLength in 1..32 && sameSubnet(ip, targetIp, la.prefixLength)
                }
                if (matches) return network
            }
        }

        // 2) Kendi hotspot'umuz açıksa hedef IP soft-AP alt ağında olabilir → varsayılan soket kullan.
        if (isHostingHotspot(context)) return null

        // 3) Hotspot yok, aktif Wi-Fi/Ethernet var → mobil veriyi atlamak için yerel ağı bağla (Wi-Fi öncelikli).
        return lanNetworks.firstOrNull { it.second }?.first ?: lanNetworks.first().first
    }

    fun socketFactoryFor(context: Context, targetHost: String): SocketFactory? =
        selectLocalNetwork(context, targetHost)?.socketFactory

    /**
     * Telefon/tablet şu anda kendi Wi-Fi hotspot (mobil erişim noktası) arayüzünü paylaşıyor mu?
     * `ConnectivityManager.allNetworks` soft-AP arayüzlerini göstermez; bu nedenle `NetworkInterface` taranır.
     */
    fun isHostingHotspot(context: Context): Boolean {
        val ifaces = readLocalInterfaces()
        return hasHotspotInterface(ifaces)
    }

    /** Uygulama sistem pil optimizasyonundan muaf mı? */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Pil optimizasyonu ayar ekranı intent'i (Play Store politikasına uyumlu, özel izin gerektirmez). */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Geçerli IPv4 dizesi mi? (ör. "192.168.43.102"). */
    fun isValidIpv4(text: String): Boolean = ipv4ToInt(text.trim()) != null

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

    private fun localPrefixes(context: Context): List<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val networks = cm.allNetworks.filter { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
            networks.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
                .filter { it.address is Inet4Address }
                .mapNotNull { la -> la.address.hostAddress?.let(::ipv4ToInt)?.let { it to la.prefixLength } }
                .forEach { out.add(it) }
        }
        // Hotspot soft-AP arayüzleri (ap0, swlan0, wlan1, rndis0) ConnectivityManager'da görünmez; doğrudan ekle.
        for (iface in readLocalInterfaces()) {
            if (isLanOrHotspotInterface(iface.name) && isPrivateIpv4(iface.address)) {
                out.add(iface.address to iface.prefixLength)
            }
        }
        return out.toList()
    }

    private fun readLocalInterfaces(): List<LocalInterface> = runCatching {
        val result = mutableListOf<LocalInterface>()
        val enumIfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (enumIfaces.hasMoreElements()) {
            val nif = enumIfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                val ip = addr.hostAddress?.let(::ipv4ToInt) ?: continue
                val prefix = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                result.add(LocalInterface(nif.name.lowercase(), ip, prefix))
            }
        }
        result
    }.getOrDefault(emptyList())

    internal fun hasHotspotInterface(interfaces: List<LocalInterface>): Boolean = interfaces.any { iface ->
        isPrivateIpv4(iface.address) && isHotspotInterfaceName(iface.name)
    }

    internal fun isHotspotInterfaceName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ap") ||
            n.startsWith("swlan") ||
            n.startsWith("softap") ||
            n.startsWith("rndis") ||
            n.startsWith("tether") ||
            n == "wlan1" ||
            n == "wlan2"
    }

    private fun isLanOrHotspotInterface(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("dummy")
        ) {
            return false
        }
        return n.startsWith("wlan") || n.startsWith("eth") || isHotspotInterfaceName(n)
    }

    internal fun isPrivateIpv4(ip: Int): Boolean {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
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
