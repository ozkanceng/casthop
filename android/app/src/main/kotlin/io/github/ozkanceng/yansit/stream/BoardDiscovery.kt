package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import java.net.Inet4Address
import java.util.concurrent.Executor

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
 * `_yansit._tcp` arama (PROTOCOL.md §8). Android 17+ (API 37): sistemin hizmet seçicisi gösterilir; bu yol yerel ağ
 * izni gerektirmez ve seçilen tahta çözümlenebilir. Daha eski sürümlerde sürekli arama + uygulamanın kendi listesi.
 */
class BoardDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd: NsdManager = app.getSystemService(NsdManager::class.java)
    private val main: Executor = ContextCompat.getMainExecutor(app)
    private var listener: NsdManager.DiscoveryListener? = null
    private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
    private val found = LinkedHashMap<String, DiscoveredBoard>()

    /** Sistem seçicisi kullanılabilir mi (API 37+)? */
    val usesSystemPicker: Boolean get() = Build.VERSION.SDK_INT >= API_PICKER

    /** API 37+: sistem seçicisini açar; seçilen tahta çözümlenince [onPicked], vazgeçilirse [onCancel]. */
    @RequiresApi(API_PICKER)
    fun pick(onPicked: (DiscoveredBoard) -> Unit, onCancel: () -> Unit) {
        stop()
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
                if (!picked) onCancel()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "seçici açılamadı: $errorCode")
                onCancel()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        nsd.discoverServices(request, main, l)
    }

    /** API < 37: sürekli arama; liste her değiştiğinde [onUpdate]. */
    fun browse(onUpdate: (List<DiscoveredBoard>) -> Unit) {
        stop()
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
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        nsd.discoverServices(DiscoveryTxt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
        found.clear()
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
        return DiscoveredBoard(txt.name ?: info.serviceName, hosts.take(4), info.port, txt.fingerprintPrefix, txt.kind)
    }

    private companion object {
        const val API_PICKER = 37
    }
}
