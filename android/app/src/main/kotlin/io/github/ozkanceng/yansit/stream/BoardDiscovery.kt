package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "YansitNsd"

/** mDNS ile bulunan tahta. Kimlik doğrulama yine PIN ile yapılır; parmak izinin yalnız öneki bilinir. */
data class DiscoveredBoard(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprintPrefix: Bytes,
    val kind: String? = null,
)

/**
 * `_yansit._tcp` arama (PROTOCOL.md §8).
 * Arama süresince `MulticastLock` tutar ki Wi-Fi sürücüsü mDNS paketlerini güç tasarrufunda düşürmesin.
 * Android 17+ (API 37): sistem seçicisi veya yerel ağ izni varsa liste; daha eski sürümlerde sürekli liste.
 */
class BoardDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd: NsdManager = app.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val main: Executor = ContextCompat.getMainExecutor(app)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
    private val found = LinkedHashMap<String, DiscoveredBoard>()

    /** Sistem seçicisi kullanılabilir mi (API 37+)? */
    val usesSystemPicker: Boolean get() = Build.VERSION.SDK_INT >= API_PICKER

    /** API 37+: sistem seçicisini açar; seçilen tahta çözümlenince [onPicked], vazgeçilirse [onCancel]. */
    @RequiresApi(API_PICKER)
    fun pick(onPicked: (DiscoveredBoard) -> Unit, onCancel: () -> Unit) {
        stop()
        acquireMulticast()
        val request = DiscoveryRequest.Builder(DiscoveryTxt.SERVICE_TYPE)
            .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
            .setDisplayNameAttribute("name")
            .build()
        var picked = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                picked = true
                resolve(info) { board ->
                    stop()
                    if (board != null) onPicked(board) else onCancel()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                if (!picked) {
                    releaseMulticast()
                    onCancel()
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "seçici açılamadı: $errorCode")
                releaseMulticast()
                onCancel()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        nsd.discoverServices(request, main, l)
    }

    /** Sürekli mDNS taraması; liste her değiştiğinde [onUpdate], hata olursa [onFailed]. */
    fun browse(onUpdate: (List<DiscoveredBoard>) -> Unit, onFailed: (Int) -> Unit = {}) {
        stop()
        acquireMulticast()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                resolve(info) { board ->
                    if (board != null) {
                        found[info.serviceName] = board
                        onUpdate(found.values.toList())
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) onUpdate(found.values.toList())
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "arama başlatılamadı: $errorCode")
                releaseMulticast()
                main.execute { onFailed(errorCode) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        try {
            nsd.discoverServices(DiscoveryTxt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices", e)
            releaseMulticast()
            main.execute { onFailed(-1) }
        }
    }

    /**
     * Belirli sertifika parmak izine sahip tahtanın güncel IP'sini mDNS üzerinden kısa sürede arar.
     * Hotspot veya Wi-Fi kopması sonrası tahtanın IP adresi değiştiğinde kesintisiz yeniden bağlanma için kullanılır.
     */
    fun findByFingerprint(fingerprint: Bytes, timeoutMs: Long = 2500L): DiscoveredBoard? {
        val hit = AtomicReference<DiscoveredBoard?>(null)
        val latch = CountDownLatch(1)
        val temp = BoardDiscovery(app)
        temp.browse(
            onUpdate = { list ->
                val match = list.firstOrNull { b ->
                    val prefix = b.fingerprintPrefix
                    prefix.size <= fingerprint.size && (0 until prefix.size).all { fingerprint[it] == prefix[it] }
                }
                if (match != null && hit.compareAndSet(null, match)) {
                    latch.countDown()
                }
            },
            onFailed = { latch.countDown() },
        )
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            temp.stop()
        }
        return hit.get()
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
        found.clear()
        releaseMulticast()
    }

    private fun acquireMulticast() {
        try {
            val lock = multicastLock ?: wifi?.createMulticastLock("yansit:mdns")?.apply {
                setReferenceCounted(false)
            }?.also { multicastLock = it }
            if (lock != null && !lock.isHeld) lock.acquire()
        } catch (e: RuntimeException) {
            Log.w(TAG, "MulticastLock alınamadı", e)
        }
    }

    private fun releaseMulticast() {
        multicastLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    private fun resolve(info: NsdServiceInfo, done: (DiscoveredBoard?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(info, done)
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "çözümlenemedi: ${serviceInfo.serviceName} ($errorCode)")
                        main.execute { done(null) }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val hosts = listOfNotNull(serviceInfo.host)
                        main.execute { done(toBoard(serviceInfo, hosts)) }
                    }
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithCallback(info: NsdServiceInfo, done: (DiscoveredBoard?) -> Unit) {
        var delivered = false
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (!delivered) {
                    delivered = true
                    done(null)
                }
            }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val board = toBoard(serviceInfo, serviceInfo.hostAddresses) ?: return
                if (!delivered) {
                    delivered = true
                    done(board)
                }
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks.add(cb)
        nsd.registerServiceInfoCallback(info, main, cb)
    }

    private fun toBoard(info: NsdServiceInfo, addresses: List<java.net.InetAddress>): DiscoveredBoard? {
        val txt = DiscoveryTxt.parse(info.attributes) ?: return null
        val hosts = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        if (hosts.isEmpty() || info.port !in 1..65535) return null
        val displayName = txt.name ?: info.serviceName
        return DiscoveredBoard(displayName, hosts.take(4), info.port, txt.fingerprintPrefix, txt.kind)
    }

    private companion object {
        const val API_PICKER = 37
    }
}
