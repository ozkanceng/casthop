package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.data.BoardIdentity
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * Android 10–13 (API < 34) için seri `NsdManager.resolveService()` kuyruğu.
 * Aynı anda birden fazla çağrı yapıldığında Android `FAILURE_ALREADY_ACTIVE (3)` ile reddeder;
 * bu kuyruk okul ağındaki 10–40 ETAP tahtasını çakışmasız sırayla çözümler ve geçici çakışmada tekrar dener.
 */
internal class SerialResolveQueue<T>(
    private val scheduleDelay: (Long, () -> Unit) -> Unit,
    private val resolveOne: (item: T, onResolved: () -> Unit, onFailed: (errorCode: Int) -> Unit) -> Unit,
    private val maxRetries: Int = 2,
    private val retryDelayMs: Long = 120L,
) {
    private data class Entry<T>(val item: T, val attempts: Int)

    private val queue = ArrayDeque<Entry<T>>()
    private var inFlight = false
    private var stopped = false

    @Synchronized
    fun enqueue(item: T) {
        if (stopped) return
        queue.addLast(Entry(item, 0))
        if (!inFlight) startNextLocked()
    }

    @Synchronized
    fun clear() {
        stopped = true
        queue.clear()
        inFlight = false
    }

    @Synchronized
    fun reset() {
        stopped = false
        queue.clear()
        inFlight = false
    }

    private fun startNextLocked() {
        if (stopped || inFlight) return
        val entry = queue.removeFirstOrNull() ?: return
        inFlight = true
        val settled = AtomicBoolean(false)
        resolveOne(
            entry.item,
            {
                if (settled.compareAndSet(false, true)) finishOne()
            },
            { errorCode ->
                if (!settled.compareAndSet(false, true)) return@resolveOne
                val shouldRetry = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && entry.attempts < maxRetries
                synchronized(this) {
                    inFlight = false
                    if (stopped) return@synchronized
                    if (shouldRetry) {
                        val next = Entry(entry.item, entry.attempts + 1)
                        scheduleDelay(retryDelayMs) {
                            synchronized(this) {
                                if (stopped) return@synchronized
                                queue.addLast(next)
                                if (!inFlight) startNextLocked()
                            }
                        }
                    } else {
                        startNextLocked()
                    }
                }
            },
        )
    }

    private fun finishOne() {
        synchronized(this) {
            inFlight = false
            if (!stopped) startNextLocked()
        }
    }
}

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val callbacks = LinkedHashMap<String, NsdManager.ServiceInfoCallback>()
    private val found = LinkedHashMap<String, DiscoveredBoard>()
    private var browseGeneration = 0

    private val legacyQueue = SerialResolveQueue<Pair<NsdServiceInfo, (DiscoveredBoard?) -> Unit>>(
        scheduleDelay = { delayMs, block -> mainHandler.postDelayed(block, delayMs) },
        resolveOne = { (info, done), onResolved, onFailed ->
            @Suppress("DEPRECATION")
            try {
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "çözümlenemedi: ${serviceInfo.serviceName} ($errorCode)")
                            onFailed(errorCode)
                            if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                                main.execute { done(null) }
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val hosts = listOfNotNull(serviceInfo.host)
                            val board = toBoard(serviceInfo, hosts)
                            onResolved()
                            main.execute { done(board) }
                        }
                    },
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "resolveService hatası", e)
                onFailed(-1)
                main.execute { done(null) }
            }
        },
    )

    /** Sistem seçicisi kullanılabilir mi (API 37+)? */
    val usesSystemPicker: Boolean get() = Build.VERSION.SDK_INT >= API_PICKER

    /** API 37+: sistem seçicisini açar; seçilen tahta çözümlenince [onPicked], vazgeçilirse [onCancel]. */
    @RequiresApi(API_PICKER)
    fun pick(onPicked: (DiscoveredBoard) -> Unit, onCancel: () -> Unit) {
        stop()
        val gen = ++browseGeneration
        legacyQueue.reset()
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
                resolveSingle(info, gen) { board ->
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
        val gen = ++browseGeneration
        legacyQueue.reset()
        acquireMulticast()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                resolveContinuous(info, gen, onUpdate)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.execute {
                    if (gen != browseGeneration) return@execute
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        callbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                    }
                    val removed = found.remove(info.serviceName)
                    if (removed != null) {
                        val snapshot = found.values.toList()
                        updateLiveCache(snapshot)
                        onUpdate(snapshot)
                    }
                }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "arama başlatılamadı: $errorCode")
                releaseMulticast()
                main.execute { if (gen == browseGeneration) onFailed(errorCode) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        try {
            nsd.discoverServices(DiscoveryTxt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices", e)
            releaseMulticast()
            main.execute { if (gen == browseGeneration) onFailed(-1) }
        }
    }

    /**
     * Belirli sertifika parmak izine sahip tahtanın güncel IP'sini mDNS üzerinden arar.
     * Hotspot veya Wi-Fi kopması / DHCP değişimi sonrası kesintisiz yeniden bağlanma için kullanılır.
     */
    fun findByFingerprint(
        fingerprint: Bytes,
        timeoutMs: Long = 3500L,
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
        onRegisterCancel: ((() -> Unit)) -> Unit = {},
    ): DiscoveredBoard? {
        if (cancelSignal.get()) return null
        val hit = AtomicReference<DiscoveredBoard?>(null)
        val latch = CountDownLatch(1)
        val temp = BoardDiscovery(app)
        onRegisterCancel {
            latch.countDown()
            main.execute { temp.stop() }
        }
        temp.browse(
            onUpdate = { list ->
                val match = list.firstOrNull { b -> BoardIdentity.matchesPrefix(fingerprint, b.fingerprintPrefix) }
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
            main.execute { temp.stop() }
        }
        return if (cancelSignal.get()) null else hit.get()
    }

    fun stop() {
        browseGeneration++
        legacyQueue.clear()
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
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

    private fun resolveSingle(info: NsdServiceInfo, gen: Int, done: (DiscoveredBoard?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(info, gen, singleShot = true) { board -> done(board) }
        } else {
            legacyQueue.enqueue(info to { board -> if (gen == browseGeneration) done(board) })
        }
    }

    private fun resolveContinuous(info: NsdServiceInfo, gen: Int, onUpdate: (List<DiscoveredBoard>) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveWithCallback(info, gen, singleShot = false) { board ->
                if (gen != browseGeneration) return@resolveWithCallback
                if (board != null) {
                    found[info.serviceName] = board
                    val snapshot = found.values.toList()
                    updateLiveCache(snapshot)
                    onUpdate(snapshot)
                } else {
                    if (found.remove(info.serviceName) != null) {
                        val snapshot = found.values.toList()
                        updateLiveCache(snapshot)
                        onUpdate(snapshot)
                    }
                }
            }
        } else {
            legacyQueue.enqueue(
                info to { board ->
                    if (gen == browseGeneration && board != null) {
                        found[info.serviceName] = board
                        val snapshot = found.values.toList()
                        updateLiveCache(snapshot)
                        onUpdate(snapshot)
                    }
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithCallback(
        info: NsdServiceInfo,
        gen: Int,
        singleShot: Boolean,
        onBoard: (DiscoveredBoard?) -> Unit,
    ) {
        var delivered = false
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (singleShot && !delivered) {
                    delivered = true
                    onBoard(null)
                }
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (gen != browseGeneration) return
                val board = toBoard(serviceInfo, serviceInfo.hostAddresses) ?: return
                if (singleShot) {
                    if (!delivered) {
                        delivered = true
                        onBoard(board)
                    }
                } else {
                    onBoard(board)
                }
            }

            override fun onServiceLost() {
                if (gen != browseGeneration) return
                if (!singleShot) onBoard(null)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks.put(info.serviceName, cb)?.let { old -> runCatching { nsd.unregisterServiceInfoCallback(old) } }
        try {
            nsd.registerServiceInfoCallback(info, main, cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerServiceInfoCallback hatası", e)
            callbacks.remove(info.serviceName)
            if (singleShot && !delivered) {
                delivered = true
                onBoard(null)
            }
        }
    }

    internal companion object {
        const val API_PICKER = 37
        private const val LIVE_CACHE_TTL_MS = 45_000L

        private data class CachedEntry(val board: DiscoveredBoard, val seenAtMs: Long)

        private val liveCache = ConcurrentHashMap<Bytes, CachedEntry>()

        /** Pasif veya aktif keşifte görülen tahtaları kısa ömürlü önbelleğe yazar. */
        fun updateLiveCache(boards: List<DiscoveredBoard>, nowMs: Long = System.currentTimeMillis()) {
            for (b in boards) {
                liveCache[b.fingerprintPrefix] = CachedEntry(b, nowMs)
            }
            liveCache.entries.removeIf { nowMs - it.value.seenAtMs > LIVE_CACHE_TTL_MS }
        }

        /** Yakın zamanda mDNS'te görülen tahtanın taze bilgisi (yoksa veya bayatsa `null`). */
        fun cachedByFingerprint(fingerprint: Bytes, nowMs: Long = System.currentTimeMillis()): DiscoveredBoard? {
            var best: CachedEntry? = null
            for ((prefix, entry) in liveCache) {
                if (nowMs - entry.seenAtMs > LIVE_CACHE_TTL_MS) continue
                if (BoardIdentity.matchesPrefix(fingerprint, prefix)) {
                    if (best == null || entry.seenAtMs > best.seenAtMs) best = entry
                }
            }
            return best?.board
        }

        /**
         * `NsdServiceInfo` adresleri ile isteğe bağlı mDNS TXT `ip=` ipuçlarını birleştirir.
         * Android 10–13 `serviceInfo.host` IPv6 döndürse veya `.local` adı başka ETAP tahtasıyla çakışsa bile
         * TXT içindeki IPv4'ler sayesinde tahta kaybolmaz.
         */
        fun buildBoard(
            serviceName: String,
            port: Int,
            attributes: Map<String, ByteArray?>,
            addresses: List<java.net.InetAddress>,
        ): DiscoveredBoard? {
            val txt = DiscoveryTxt.parse(attributes) ?: return null
            if (port !in 1..65535) return null
            val resolvedV4 = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            // TXT `ip=` listesi doğrudan tahtanın kendi bildirdiği IPv4'lerdir (.local çakışmasından etkilenmez).
            val hosts = (txt.hosts + resolvedV4).distinct().take(4)
            if (hosts.isEmpty()) return null
            val displayName = txt.name ?: serviceName
            return DiscoveredBoard(displayName, hosts, port, txt.fingerprintPrefix, txt.kind)
        }
    }

    private fun toBoard(info: NsdServiceInfo, addresses: List<java.net.InetAddress>): DiscoveredBoard? =
        buildBoard(info.serviceName, info.port, info.attributes, addresses)
}
