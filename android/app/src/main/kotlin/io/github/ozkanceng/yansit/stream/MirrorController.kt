package io.github.ozkanceng.yansit.stream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.util.Size
import android.view.Display
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.BuildConfig
import io.github.ozkanceng.yansit.data.AppSettings
import io.github.ozkanceng.yansit.data.BoardStore
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.ProtocolException
import io.github.ozkanceng.yansit.protocol.QrPayload
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.client.ConnectFirstResult
import io.github.ozkanceng.yansit.protocol.client.Credentials
import io.github.ozkanceng.yansit.protocol.client.DeviceInfo
import io.github.ozkanceng.yansit.protocol.client.Endpoint
import io.github.ozkanceng.yansit.protocol.client.FingerprintMismatchException
import io.github.ozkanceng.yansit.protocol.client.HelloOutcome
import io.github.ozkanceng.yansit.protocol.client.IncompatibleReceiverException
import io.github.ozkanceng.yansit.protocol.client.PinnedTls
import io.github.ozkanceng.yansit.protocol.client.SenderClient
import io.github.ozkanceng.yansit.protocol.client.Session
import io.github.ozkanceng.yansit.sender.LinkState
import io.github.ozkanceng.yansit.sender.MirrorSession
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.sender.Preset
import io.github.ozkanceng.yansit.sender.QualityStep
import io.github.ozkanceng.yansit.sender.ReceiverDisplay
import io.github.ozkanceng.yansit.sender.SessionEnd
import io.github.ozkanceng.yansit.sender.StreamOverrides
import io.github.ozkanceng.yansit.sender.StreamPlan
import io.github.ozkanceng.yansit.sender.StreamPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "YansitMirror"

/** Kullanıcıya gösterilen hata türleri (her biri anlaşılır bir mesaja karşılık gelir). */
enum class MirrorError {
    UNREACHABLE,
    CLIENT_ISOLATION,
    DIFFERENT_NETWORK,
    REFUSED,
    WRONG_BOARD,
    AUTH_FAILED,
    WRONG_PIN,
    BOARD_FORGOT_TABLET,
    BUSY_DENIED,
    BUSY_TIMEOUT,
    RATE_LIMITED,
    LOCKED_OUT,
    INCOMPATIBLE,
    NO_ENCODER,
    CONSENT_DENIED,
    CONNECTION_LOST,
    ENCODER_FAILED,
    STOPPED_BY_BOARD,
    BOARD_CLOSED,
    TAKEN_OVER,
    PROJECTION_ENDED,
    CAMERA_FAILED,
    INTERNAL,
}

/** Bağlantı sırasındaki kullanıcı dostu aşama. */
enum class ConnectStep { SEARCHING, SECURING, WAITING_APPROVAL, STARTING }

/** Sesin durumu (arayüzde bilgi satırı). */
enum class AudioState { ON, OFF_SETTING, OFF_BOARD, OFF_PERMISSION, OFF_CAMERA, UNSUPPORTED, FAILED }

sealed interface MirrorState {
    data object Idle : MirrorState
    data class Connecting(val board: String, val step: ConnectStep = ConnectStep.SEARCHING) : MirrorState
    data class WaitingApproval(val board: String, val timeoutMs: Long) : MirrorState
    /** Tahta kabul etti; sistemin ekran paylaşım onayı bekleniyor. */
    data class AwaitingConsent(val board: String) : MirrorState
    data class Starting(val board: String) : MirrorState
    data class Mirroring(
        val board: String,
        val stats: MirrorStats?,
        val paused: Boolean = false,
        /** Ağ koptu, oturum sürdürülmeye çalışılıyor (görüntü tahtada donuk kalır). */
        val reconnecting: Boolean = false,
        val audio: AudioState = AudioState.OFF_SETTING,
        /** Camera mode: the size of the picture being sent; `null` when the screen is mirrored. */
        val cameraSize: Size? = null,
        val canSwitchCamera: Boolean = false,
        /** Telefon hotspot veya 2,4 GHz Wi-Fi üzerinden mi yayın yapılıyor? */
        val onHotspot: Boolean = false,
    ) : MirrorState
    /** Bitti; [error] `null` ise kullanıcı kendisi durdurdu. [detail] hassas bilgi içermeyen kısa teşhis özetidir. */
    data class Ended(val board: String, val error: MirrorError?, val detail: String? = null) : MirrorState
}

/** Bağlantı hedefi: yeni QR, hatırlanan tahta, mDNS + PIN ya da manuel IP + PIN. */
sealed interface ConnectTarget {
    data class Qr(val payload: QrPayload) : ConnectTarget
    data class Remembered(val board: RememberedBoard) : ConnectTarget
    data class Discovered(val board: DiscoveredBoard, val pin: String) : ConnectTarget
    data class Manual(val host: String, val port: Int, val pin: String) : ConnectTarget
}

/**
 * Yansıtmanın uygulama genelindeki durum makinesi: hedef → bağlan → tahta onayı → sistem onayı → yakalama.
 * Tek örnek; Activity ve [MirrorService] aynı süreçte bunu kullanır.
 */
object MirrorController {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var session: MirrorSession? = null
    private var capture: Capture? = null
    private var board: String = ""
    private var target: ConnectTarget? = null
    /** Certificate fingerprint of the board in the current session; keys the remembered preset. */
    private var boardFingerprint: Bytes? = null
    /** Her bağlantı denemesi ve durdurma bir artırır; eski denemenin geç gelen sonucu yok sayılır. */
    private var generation = 0
    /** Aktif bağlantı denemesinin soketlerini, mDNS taramasını ve korutinini anında kesmek için tutamaç. */
    private var activeAttempt: AttemptCanceller? = null
    /** Bağlantı aşamasında Wi-Fi radyosunu uyku modundan çıkaran geçici kilit (`MirrorService` devralınca bırakılır). */
    private var connectWifiLock: LowLatencyWifi? = null
    private lateinit var appContext: Context

    private class AttemptCanceller {
        val cancelled = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val cleanups = CopyOnWriteArrayList<() -> Unit>()
        @Volatile var job: Job? = null

        fun registerSocket(socket: Socket) {
            sockets += socket
            if (cancelled.get()) runCatching { socket.close() }
        }

        fun registerCancel(cleanup: () -> Unit) {
            cleanups += cleanup
            if (cancelled.get()) runCatching { cleanup() }
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            job?.cancel()
            for (s in sockets) runCatching { s.close() }
            for (c in cleanups) runCatching { c() }
            sockets.clear()
            cleanups.clear()
        }
    }

    /** Uzun kopmada eşleştirmeyle yeniden bağlanma denemesi süresi. */
    private const val LONG_RECONNECT_WINDOW_NS = 60_000_000_000L
    private const val TRANSIENT_RETRY_DELAY_MS = 350L
    private const val MDNS_REDISCOVERY_TIMEOUT_MS = 3500L

    /** Upper limit for camera mode; the preset and the receiver can lower it. */
    private const val CAMERA_MAX_WIDTH = 1920
    private const val CAMERA_MAX_HEIGHT = 1080

    /** Yansıtma sürerken tutulan sistem kaynakları. */
    private class Capture(val service: MirrorService, val projection: MediaProjection?) {
        var display: VirtualDisplay? = null
        var camera: CameraCapture? = null
        var encoder: CaptureSource? = null
        var plan: StreamPlan? = null
        /** Preset in use when the network or encoder moved away from the chosen one. */
        var preset: Preset? = null
        var displayListener: DisplayManager.DisplayListener? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null

        fun release() {
            displayListener?.let { service.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
            displayListener = null
            networkCallback?.let { cb ->
                runCatching { service.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            networkCallback = null
            camera?.release()
            camera = null
            // The source detaches the display before it lets go of its surface.
            encoder?.release()
            encoder = null
            display?.release()
            display = null
            try {
                projection?.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "projection.stop", e)
            }
            service.stopForegroundAndSelf()
        }
    }

    // --- Bağlanma ---

    /** Son bağlantı denemesini güncel kayıt bilgileriyle tek dokunuşla yeniden dener (varsa). */
    fun retryLast(context: Context): Boolean {
        val last = target ?: return false
        val current = _state.value
        if (current !is MirrorState.Idle && current !is MirrorState.Ended) return false
        val boards = BoardStore(context.applicationContext)
        val refreshed = when (last) {
            is ConnectTarget.Remembered ->
                boards.find(last.board.fingerprint)?.let { ConnectTarget.Remembered(it) } ?: last
            // Harcanmış tek-kullanımlık QR token'ını tekrar göndermek yerine eşleştirme kaydedildiyse kayıtlı kaydı kullan.
            is ConnectTarget.Qr ->
                boards.find(last.payload.certFingerprint)?.let { ConnectTarget.Remembered(it) } ?: last
            else -> last
        }
        connect(context, refreshed)
        return true
    }

    fun connect(context: Context, target: ConnectTarget) {
        val current = _state.value
        if (current !is MirrorState.Idle && current !is MirrorState.Ended) return
        appContext = context.applicationContext
        this.target = target
        board = when (target) {
            is ConnectTarget.Qr -> target.payload.name
            is ConnectTarget.Remembered -> target.board.displayName
            is ConnectTarget.Discovered ->
                BoardStore(appContext).findByPrefix(target.board.fingerprintPrefix)?.displayName ?: target.board.name
            is ConnectTarget.Manual -> target.host
        }
        _state.value = MirrorState.Connecting(board, ConnectStep.SEARCHING)
        val canceller = AttemptCanceller()
        val (attempt, prevCanceller) = synchronized(lock) {
            val prev = activeAttempt
            activeAttempt = canceller
            (++generation) to prev
        }
        prevCanceller?.cancel()
        acquireConnectWifi()
        canceller.job = scope.launch {
            val result = hello(
                target = target,
                canceller = canceller,
                onStep = { step ->
                    if (isCurrent(attempt)) _state.value = MirrorState.Connecting(board, step)
                },
                onPending = { timeoutMs ->
                    if (isCurrent(attempt)) _state.value = MirrorState.WaitingApproval(board, timeoutMs)
                },
            )
            when (result) {
                is HelloResult.Failed -> failIfCurrent(attempt, result.error, result.detail)
                is HelloResult.Ok -> {
                    if (!isCurrent(attempt) || canceller.cancelled.get()) {
                        // Kullanıcı bu sırada vazgeçti.
                        runCatching { result.session.control.send(ControlMessage.Bye(ByeReason.USER_STOP)) }
                        result.session.close()
                        return@launch
                    }
                    boardFingerprint = result.session.fingerprint
                    board = BoardStore(appContext).find(result.session.fingerprint)?.displayName
                        ?: result.session.welcome.receiverName
                    val s = newMirrorSession(result.session)
                    synchronized(lock) { session = s }
                    s.start()
                    if (AppSettings(appContext).cameraMode) {
                        startCameraService()
                    } else {
                        _state.value = MirrorState.AwaitingConsent(board)
                    }
                }
            }
        }
    }

    private sealed interface HelloResult {
        data class Ok(val session: Session) : HelloResult
        data class Failed(val error: MirrorError, val detail: String? = null) : HelloResult
    }

    /**
     * Doğrudan Pinned-TLS yarışı + Wi-Fi ağ sabitleme + DHCP/IP çakışmasında mDNS yeniden keşfi + ARP uyanma tekrarı +
     * QR → kayıtlı anahtar yedeği.
     */
    private fun hello(
        target: ConnectTarget,
        canceller: AttemptCanceller = AttemptCanceller(),
        onStep: (ConnectStep) -> Unit = {},
        onPending: (Long) -> Unit,
    ): HelloResult {
        val settings = AppSettings(appContext)
        val boards = BoardStore(appContext)
        val initialRawHosts: List<String>
        var port: Int
        val fp: Bytes?
        when (target) {
            is ConnectTarget.Qr -> {
                initialRawHosts = target.payload.hosts
                port = target.payload.port
                fp = target.payload.certFingerprint
            }
            is ConnectTarget.Remembered -> {
                val live = BoardDiscovery.cachedByFingerprint(target.board.fingerprint)
                initialRawHosts = if (live != null && live.hosts.isNotEmpty()) {
                    port = live.port
                    BoardStore.mergeHosts(null, live.hosts, target.board.hosts)
                } else {
                    port = target.board.port
                    target.board.hosts
                }
                fp = target.board.fingerprint
            }
            // mDNS'te yalnız parmak izi öneki var: ilk bağlantıda öneki denetlenir, tam iz eşleştirmeyle kaydedilir.
            is ConnectTarget.Discovered -> {
                initialRawHosts = target.board.hosts
                port = target.board.port
                fp = null
            }
            is ConnectTarget.Manual -> {
                initialRawHosts = listOf(target.host)
                port = target.port
                fp = null
            }
        }
        var hosts = NetworkDiagnostics.prioritizeHosts(appContext, initialRawHosts)
        val fpPrefix = (target as? ConnectTarget.Discovered)?.board?.fingerprintPrefix
        val deviceInfo = buildDeviceInfo(settings)

        fun raceTls(candidateHosts: List<String>, candidatePort: Int): ConnectFirstResult = PinnedTls.connectFirst(
            hosts = candidateHosts,
            port = candidatePort,
            expectedFingerprint = fp,
            expectedPrefix = fpPrefix,
            socketFactoryProvider = { h -> NetworkDiagnostics.socketFactoryFor(appContext, h) },
            cancelSignal = canceller.cancelled,
            onRegisterSocket = canceller::registerSocket,
        )

        onStep(ConnectStep.SEARCHING)
        Log.i(TAG, "bağlanılıyor: adaylar=$hosts port=$port tür=${target::class.simpleName}")
        var connectResult = raceTls(hosts, port)
        var mdnsStatus = ""

        // Hatırlanan tahtanın IP'si DHCP ile değiştiyse (timeout, REFUSED veya başka tahtanın IP'si → FingerprintMismatch),
        // mDNS üzerinden parmak izine göre güncel adresi bul.
        if (connectResult is ConnectFirstResult.Failed &&
            connectResult.incompatible == null &&
            target is ConnectTarget.Remembered &&
            !canceller.cancelled.get()
        ) {
            Log.i(TAG, "ilk deneme başarısız (refused=${connectResult.refused}, mismatch=${connectResult.fingerprintMismatch != null}), mDNS aranıyor")
            val rediscovered = BoardDiscovery(appContext).findByFingerprint(
                fingerprint = target.board.fingerprint,
                timeoutMs = MDNS_REDISCOVERY_TIMEOUT_MS,
                cancelSignal = canceller.cancelled,
                onRegisterCancel = canceller::registerCancel,
            )
            if (rediscovered != null && rediscovered.hosts.isNotEmpty() && !canceller.cancelled.get()) {
                val freshHosts = NetworkDiagnostics.prioritizeHosts(appContext, rediscovered.hosts)
                hosts = BoardStore.mergeHosts(null, freshHosts, hosts)
                port = rediscovered.port
                boards.updateHosts(target.board.fingerprint, freshHosts, port)
                mdnsStatus = "mDNS:${freshHosts.first()}"
                Log.i(TAG, "mDNS taze adres buldu: $freshHosts:$port")
                connectResult = raceTls(freshHosts, port)
            } else {
                mdnsStatus = "mDNS:yok"
            }
        }

        // Ders arası soğuk ARP / Wi-Fi güç tasarrufu uyanması için geçici ağ hatasında 1 kez otomatik tekrar.
        if (connectResult is ConnectFirstResult.Failed &&
            connectResult.incompatible == null &&
            connectResult.fingerprintMismatch == null &&
            !connectResult.refused &&
            !canceller.cancelled.get()
        ) {
            if (sleepCancellable(TRANSIENT_RETRY_DELAY_MS, canceller)) {
                Log.i(TAG, "soğuk ARP/Wi-Fi uyanma tekrarı: $hosts:$port")
                connectResult = raceTls(hosts, port)
            }
        }

        if (canceller.cancelled.get()) return HelloResult.Failed(MirrorError.UNREACHABLE)

        val connected = when (val r = connectResult) {
            is ConnectFirstResult.Connected -> r
            is ConnectFirstResult.Failed -> {
                val primaryHost = hosts.firstOrNull().orEmpty()
                val detail = buildString {
                    if (primaryHost.isNotEmpty()) append("$primaryHost:$port")
                    val reason = when {
                        r.fingerprintMismatch != null -> "parmak izi uyuşmadı"
                        r.incompatible != null -> "sürüm/TLS uyumsuz"
                        r.refused -> "port kapalı"
                        else -> "yanıt yok"
                    }
                    if (isNotEmpty()) append(" · ")
                    append(reason)
                    if (mdnsStatus.isNotEmpty()) append(" · $mdnsStatus")
                }
                val err = when {
                    r.fingerprintMismatch != null -> MirrorError.WRONG_BOARD
                    r.incompatible != null -> MirrorError.INCOMPATIBLE
                    r.refused -> MirrorError.REFUSED
                    NetworkDiagnostics.sharesSubnet(appContext, hosts) == false -> MirrorError.DIFFERENT_NETWORK
                    NetworkDiagnostics.sharesSubnet(appContext, hosts) == true -> MirrorError.CLIENT_ISOLATION
                    else -> MirrorError.UNREACHABLE
                }
                Log.w(TAG, "TLS bağlantısı başarısız ($err): $detail", r.lastError)
                return HelloResult.Failed(err, detail)
            }
        }

        val host = connected.host
        val socketFactory = NetworkDiagnostics.socketFactoryFor(appContext, host)
        // QR bağlantısında TLS sertifika parmak izi doğrulandıysa, tahta zaten kayıtlıysa taze IP'yi hemen koru.
        if (target is ConnectTarget.Qr && boards.find(target.payload.certFingerprint) != null) {
            boards.updateHosts(target.payload.certFingerprint, target.payload.hosts, port, preferredHost = host)
        }

        onStep(ConnectStep.SECURING)
        val credentials = when (target) {
            is ConnectTarget.Qr -> Credentials.QrToken(target.payload.token)
            is ConnectTarget.Remembered -> Credentials.Paired(target.board.keyId, target.board.pairKey)
            is ConnectTarget.Discovered -> Credentials.Pin(target.pin)
            is ConnectTarget.Manual -> Credentials.Pin(target.pin)
        }
        val endpoint = Endpoint(host, port, fp, fpPrefix, socketFactory)
        var outcome = try {
            SenderClient.hello(
                socket = connected.socket,
                endpoint = endpoint,
                credentials = credentials,
                device = deviceInfo,
                requestPairing = target !is ConnectTarget.Remembered,
                onPending = onPending,
            )
        } catch (e: FingerprintMismatchException) {
            return HelloResult.Failed(MirrorError.WRONG_BOARD, "$host:$port · parmak izi uyuşmadı")
        } catch (e: IncompatibleReceiverException) {
            return HelloResult.Failed(MirrorError.INCOMPATIBLE, "$host:$port · sürüm uyumsuz")
        } catch (e: ProtocolException) {
            Log.w(TAG, "protokol hatası", e)
            return HelloResult.Failed(MirrorError.INCOMPATIBLE, "$host:$port · protokol hatası")
        } catch (e: IOException) {
            Log.w(TAG, "HELLO bağlantı hatası", e)
            return HelloResult.Failed(MirrorError.UNREACHABLE, "$host:$port · el sıkışma koptu")
        }

        // QR token'ı 10 dk sınırında döndüyse veya harcandıysa, TLS parmak izi zaten doğrulanmış kayıtlı tahta için
        // mevcut `pairKey` ile sessizce 1 kez tekrar dene.
        var usedPairedFallback = false
        if (outcome is HelloOutcome.Rejected &&
            outcome.reason == RejectReason.AUTH_FAILED &&
            target is ConnectTarget.Qr &&
            !canceller.cancelled.get()
        ) {
            val saved = boards.find(target.payload.certFingerprint)
            if (saved != null) {
                Log.i(TAG, "QR token geçersiz; doğrulanmış parmak izi için kayıtlı anahtar ile tekrar deneniyor")
                try {
                    val fallbackOutcome = SenderClient.hello(
                        endpoint = Endpoint(host, port, saved.fingerprint, null, socketFactory),
                        credentials = Credentials.Paired(saved.keyId, saved.pairKey),
                        device = deviceInfo,
                        requestPairing = false,
                        onPending = onPending,
                    )
                    if (fallbackOutcome is HelloOutcome.Accepted) {
                        outcome = fallbackOutcome
                        usedPairedFallback = true
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "kayıtlı anahtar yedeği başarısız", e)
                } catch (e: ProtocolException) {
                    Log.w(TAG, "kayıtlı anahtar yedeği protokol hatası", e)
                }
            }
        }

        return when (val finalOutcome = outcome) {
            is HelloOutcome.Rejected -> {
                val isPinFlow = target is ConnectTarget.Discovered || target is ConnectTarget.Manual
                if (isPinFlow && finalOutcome.reason == RejectReason.AUTH_FAILED) {
                    HelloResult.Failed(MirrorError.WRONG_PIN)
                } else if (target is ConnectTarget.Remembered && finalOutcome.reason == RejectReason.AUTH_FAILED) {
                    // Tahta eşleştirmeyi unutmuş (silinmiş veya sertifika yenilenmiş): yeniden QR/kod gerekir.
                    boards.remove(target.board.fingerprint)
                    HelloResult.Failed(MirrorError.BOARD_FORGOT_TABLET, "$host:$port · eşleştirme reddedildi")
                } else {
                    HelloResult.Failed(rejectError(finalOutcome.reason), "$host:$port · red (${finalOutcome.reason})")
                }
            }
            is HelloOutcome.Accepted -> {
                val w = finalOutcome.session.welcome
                val now = System.currentTimeMillis()
                val keyId = w.keyId
                val pairKey = w.pairKey
                when (target) {
                    is ConnectTarget.Qr -> if (usedPairedFallback) {
                        boards.find(finalOutcome.session.fingerprint)?.let { existing ->
                            boards.save(
                                existing.copy(name = w.receiverName, hosts = target.payload.hosts, port = port, lastUsedMs = now),
                                preferredHost = host,
                            )
                        }
                    } else if (keyId != null && pairKey != null) {
                        boards.save(
                            RememberedBoard(
                                w.receiverName, target.payload.hosts, target.payload.port,
                                finalOutcome.session.fingerprint, keyId, pairKey, now,
                            ),
                            preferredHost = host,
                        )
                    }
                    is ConnectTarget.Discovered -> if (keyId != null && pairKey != null) {
                        boards.save(
                            RememberedBoard(
                                w.receiverName, target.board.hosts, target.board.port,
                                finalOutcome.session.fingerprint, keyId, pairKey, now,
                                kind = target.board.kind,
                            ),
                            preferredHost = host,
                        )
                    }
                    is ConnectTarget.Manual -> if (keyId != null && pairKey != null) {
                        boards.save(
                            RememberedBoard(
                                w.receiverName, listOf(host), port,
                                finalOutcome.session.fingerprint, keyId, pairKey, now,
                            ),
                            preferredHost = host,
                        )
                    }
                    is ConnectTarget.Remembered -> boards.save(
                        target.board.copy(name = w.receiverName, hosts = hosts, port = port, lastUsedMs = now),
                        preferredHost = host,
                    )
                }
                HelloResult.Ok(finalOutcome.session)
            }
        }
    }

    private fun buildDeviceInfo(settings: AppSettings): DeviceInfo = DeviceInfo(
        name = settings.deviceName,
        deviceId = settings.deviceId,
        appVersion = BuildConfig.VERSION_NAME,
        videoCodecs = if (settings.hevcEnabled && AndroidEncoderCaps.hevcHardwareEncoders().isNotEmpty()) {
            listOf(Codec.H265, Codec.H264)
        } else {
            listOf(Codec.H264)
        },
        maxWidth = 3840,
        maxHeight = 2160,
        maxFps = 60,
    )

    private fun sleepCancellable(ms: Long, canceller: AttemptCanceller): Boolean {
        val step = 50L
        var elapsed = 0L
        while (elapsed < ms) {
            if (canceller.cancelled.get()) return false
            try {
                Thread.sleep(minOf(step, ms - elapsed))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            elapsed += step
        }
        return !canceller.cancelled.get()
    }

    private fun acquireConnectWifi() {
        val lockObj = synchronized(lock) {
            connectWifiLock ?: LowLatencyWifi(appContext).also { connectWifiLock = it }
        }
        lockObj.acquire()
    }

    private fun releaseConnectWifi() {
        synchronized(lock) { connectWifiLock }?.release()
    }

    private fun newMirrorSession(s: Session) = MirrorSession(
        s,
        onStats = ::onStats,
        onEnd = ::onSessionEnd,
        onLink = ::onLink,
        fallbackPlan = ::lowerPlan,
        onAudioIssue = ::onAudioIssue,
        qualityStep = ::stepQuality,
    )

    /** Sistem ekran paylaşımı onay ekranının sonucu. */
    fun onConsentResult(context: Context, granted: Boolean, resultCode: Int, data: Intent?) {
        if (_state.value !is MirrorState.AwaitingConsent) return
        if (!granted || data == null) {
            synchronized(lock) { session }?.stop(ByeReason.USER_STOP)
            _state.value = MirrorState.Ended(board, MirrorError.CONSENT_DENIED)
            return
        }
        _state.value = MirrorState.Starting(board)
        ContextCompat.startForegroundService(context, MirrorService.startIntent(context, resultCode, data, board))
    }

    // --- Yakalama ---

    /** Camera mode needs no screen capture consent, only the camera permission. */
    private fun startCameraService() {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            fail(MirrorError.CAMERA_FAILED)
            return
        }
        _state.value = MirrorState.Starting(board)
        ContextCompat.startForegroundService(appContext, MirrorService.startCameraIntent(appContext, board))
    }

    /** [MirrorService] is in the foreground with the camera type. */
    internal fun onCameraServiceReady(service: MirrorService) {
        releaseConnectWifi()
        val s = synchronized(lock) {
            session?.also { capture = Capture(service, null) }
        }
        if (s == null) {
            service.stopForegroundAndSelf()
            return
        }
        scope.launch { startCameraCapture(service, s) }
    }

    private fun startCameraCapture(service: MirrorService, s: MirrorSession) {
        val settings = AppSettings(service)
        val w = s.welcome
        val base = StreamPlanner.plan(
            CAMERA_MAX_WIDTH, CAMERA_MAX_HEIGHT, settings.preset,
            ReceiverDisplay(w.displayWidth, w.displayHeight, w.displayRefresh), encodersFor(settings, w), settings.overrides,
        )
        if (base == null) {
            fail(MirrorError.NO_ENCODER)
            return
        }
        val caps = encodersFor(settings, w).first { it.name == base.encoderName }
        val size = CameraCapture.pickSize(service, base.width, base.height) {
            it.width % caps.widthAlignment == 0 && it.height % caps.heightAlignment == 0 &&
                caps.isSupported(it.width, it.height, base.fps)
        }
        if (size == null) {
            fail(MirrorError.CAMERA_FAILED)
            return
        }
        val plan = base.copy(width = size.width, height = size.height)
        val encoder = CameraEncoder(caps)
        try {
            synchronized(lock) {
                capture?.encoder = encoder
                capture?.plan = plan
            }
            s.startVideo(plan, encoder)
            val camera = CameraCapture(service, encoder.inputSurface, size, plan.fps) { e ->
                Log.e(TAG, "kamera durdu", e)
                scope.launch { fail(MirrorError.CAMERA_FAILED) }
            }
            synchronized(lock) { capture?.camera = camera }
            camera.start()
            watchNetwork(service)
            _state.value = MirrorState.Mirroring(
                board, null, audio = AudioState.OFF_CAMERA, cameraSize = size, canSwitchCamera = camera.canSwitch,
                onHotspot = NetworkDiagnostics.isHostingHotspot(service),
            )
            Log.i(TAG, "kamera: ${plan.width}x${plan.height}@${plan.fps} (${plan.encoderName})")
        } catch (e: IOException) {
            Log.w(TAG, "video bağlantısı kurulamadı", e)
            fail(MirrorError.CONNECTION_LOST, "video kanalı kurulamadı")
        } catch (e: RuntimeException) {
            Log.e(TAG, "kodlayıcı başlatılamadı", e)
            fail(MirrorError.ENCODER_FAILED)
        }
    }

    fun setCameraPreview(surface: Surface?) {
        synchronized(lock) { capture?.camera }?.setPreview(surface)
    }

    fun switchCamera() {
        synchronized(lock) { capture?.camera }?.switchCamera()
    }

    /** [MirrorService] ön plana geçip MediaProjection'ı aldı. */
    internal fun onProjectionReady(service: MirrorService, projection: MediaProjection) {
        releaseConnectWifi()
        val existing = synchronized(lock) {
            session?.also { capture = Capture(service, projection) }
        }
        if (existing != null) {
            scope.launch { startCapture(service, projection, existing) }
            return
        }
        // Ekran paylaşım izni beklenirken kontrol bağlantısı koptuysa, alınan izinle kayıtlı anahtardan kesintisiz devam et.
        val remembered = rememberedForCurrent()
        if (remembered == null) {
            projection.stop()
            service.stopForegroundAndSelf()
            return
        }
        val attempt = synchronized(lock) {
            capture = Capture(service, projection)
            generation
        }
        scope.launch {
            Log.i(TAG, "izin sırasında oturum koptu; kayıtlı anahtarla yeniden bağlanılıyor")
            when (val r = hello(ConnectTarget.Remembered(remembered)) { }) {
                is HelloResult.Ok -> {
                    val s = newMirrorSession(r.session)
                    val ok = synchronized(lock) {
                        if (attempt != generation || capture == null) false else {
                            session = s
                            true
                        }
                    }
                    if (!ok) {
                        r.session.close()
                        return@launch
                    }
                    s.start()
                    startCapture(service, projection, s)
                }
                is HelloResult.Failed -> fail(r.error, r.detail)
            }
        }
    }

    /**
     * The encoder frame for the session: the screen turned to the board's orientation, so turning the tablet never
     * changes it ([ScreenSource] moves the picture inside it).
     */
    private fun currentPlan(context: Context, s: MirrorSession, preset: Preset? = null): StreamPlan? {
        val (screenW, screenH) = currentDisplaySize(context)
        val w = s.welcome
        val (srcW, srcH) = FrameFit.planningSource(screenW, screenH, w.displayWidth, w.displayHeight)
        val settings = AppSettings(context)
        return StreamPlanner.plan(
            srcW, srcH, preset ?: settings.preset, ReceiverDisplay(w.displayWidth, w.displayHeight, w.displayRefresh),
            encodersFor(settings, w), settings.overrides,
        )
    }

    private fun receiverDisplay(s: MirrorSession): ReceiverDisplay =
        s.welcome.let { ReceiverDisplay(it.displayWidth, it.displayHeight, it.displayRefresh) }

    /** H.265 yalnız ayar açık, tahta donanımla çözüyor ve tablette donanım kodlayıcı varsa; önce denenir. */
    private fun encodersFor(settings: AppSettings, w: ControlMessage.Welcome): List<AndroidEncoderCaps> {
        val boardHevcHw = w.videoDecoders.any { it.codec == Codec.H265 && it.hardware }
        val hevc = if (settings.hevcEnabled && boardHevcHw) AndroidEncoderCaps.hevcHardwareEncoders() else emptyList()
        return hevc + AndroidEncoderCaps.h264Encoders()
    }

    /**
     * Başlangıç ön ayarı: bu tahtada hatırlanan son kararlı ön ayar; telefon hotspot'unda veya 2,4 GHz Wi-Fi'da
     * ilk saniye donmasını önlemek için en fazla [Preset.WEAK_NETWORK] (720p/30) ile yumuşak başlar,
     * ağ rahat taşırsa QualityGovernor kademeli olarak seçilen ön ayara yükseltir.
     */
    private fun startPreset(context: Context, onHotspot: Boolean): Preset {
        val settings = AppSettings(context)
        if (settings.overrides != StreamOverrides()) return settings.preset
        val remembered = boardFingerprint?.let { BoardStore(context).preset(it) }
        var start = Preset.start(settings.preset, remembered)
        val constrainedLink = onHotspot || NetworkDiagnostics.onWifi24GHz(context) == true
        if (constrainedLink && start < Preset.WEAK_NETWORK) {
            start = Preset.WEAK_NETWORK
        }
        return start
    }

    private fun startCapture(service: MirrorService, projection: MediaProjection, s: MirrorSession) {
        val settings = AppSettings(service)
        val onHotspot = NetworkDiagnostics.isHostingHotspot(service)
        val start = startPreset(service, onHotspot)
        val allEncoders = encodersFor(settings, s.welcome)
        val plan = currentPlan(service, s, start)
        if (plan == null) {
            Log.e(TAG, "uygun kodlayıcı yok: ${allEncoders.map { it.name }}")
            fail(MirrorError.NO_ENCODER)
            return
        }
        val caps = allEncoders.first { it.name == plan.encoderName }
        val dpi = service.resources.displayMetrics.densityDpi
        val (screenW, screenH) = currentDisplaySize(service)
        var display: VirtualDisplay? = null
        var encoder: ScreenSource? = null
        try {
            // Created without a surface: the source sizes it and attaches its own once the encoder runs.
            val vd = projection.createVirtualDisplay(
                "CastHop", plan.width, plan.height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main,
            ) ?: error("createVirtualDisplay")
            display = vd
            val ceilingFps = currentPlan(service, s, settings.preset)?.fps ?: plan.fps
            encoder = ScreenSource(
                caps = caps,
                display = vd,
                densityDpi = dpi,
                sourceWidth = screenW,
                sourceHeight = screenH,
                maxFps = maxOf(plan.fps, ceilingFps),
                canRotate = s.welcome.rotatedFrames,
                encoderLookup = { name -> encodersFor(settings, s.welcome).firstOrNull { it.name == name } },
            )
            synchronized(lock) {
                capture?.display = display
                capture?.encoder = encoder
                capture?.plan = plan
                capture?.preset = start
            }
            s.startVideo(plan, encoder)
            watchRotation(service)
            watchNetwork(service)
            _state.value = MirrorState.Mirroring(
                board = board,
                stats = null,
                audio = startAudio(service, projection, s),
                onHotspot = onHotspot,
            )
            Log.i(TAG, "yansıtma: ${plan.width}x${plan.height}@${plan.fps} (${plan.encoderName}), $start, hotspot=$onHotspot")
        } catch (e: IOException) {
            Log.w(TAG, "video bağlantısı kurulamadı", e)
            fail(MirrorError.CONNECTION_LOST, "video kanalı kurulamadı")
        } catch (e: RuntimeException) {
            // MediaCodec yapılandırma hataları (IllegalStateException, CodecException...).
            Log.e(TAG, "kodlayıcı başlatılamadı", e)
            synchronized(lock) {
                if (capture?.encoder == null) {
                    encoder?.release()
                    display?.release()
                }
            }
            fail(MirrorError.ENCODER_FAILED)
        }
    }

    /** Ses (isteğe bağlı): ayar, tahta desteği, izin ve kodlayıcı varsa. Başarısızlık görüntüyü etkilemez. */
    private fun startAudio(context: Context, projection: MediaProjection, s: MirrorSession): AudioState {
        if (!AppSettings(context).audioEnabled) return AudioState.OFF_SETTING
        if (!s.welcome.audioEnabled) return AudioState.OFF_BOARD
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return AudioState.OFF_PERMISSION
        }
        val capture = AudioCapture.create(projection, s.welcome.audioDecoders) ?: return AudioState.UNSUPPORTED
        return try {
            s.startAudio(capture)
            AudioState.ON
        } catch (e: IOException) {
            Log.w(TAG, "ses bağlantısı kurulamadı", e)
            AudioState.FAILED
        } catch (e: RuntimeException) {
            // AudioRecord/MediaCodec yapılandırma hataları.
            Log.w(TAG, "ses başlatılamadı", e)
            AudioState.FAILED
        } catch (e: SecurityException) {
            AudioState.OFF_PERMISSION
        }
    }

    private fun onAudioIssue(e: Throwable) {
        Log.w(TAG, "ses durdu", e)
        val st = _state.value
        if (st is MirrorState.Mirroring) _state.value = st.copy(audio = AudioState.FAILED)
    }

    /**
     * Döndürme: Android 14+ [onCapturedContentResize] ile de bildirir; tüm sürümlerde ekran dinleyicisi.
     * Her iki yol da [onSourceResized]'a gelir; boyut aynıysa bir şey yapılmaz.
     */
    private fun watchRotation(context: Context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) scope.launch { onSourceResized() }
            }
        }
        dm.registerDisplayListener(listener, main)
        synchronized(lock) { capture?.displayListener = listener }
    }

    /** MediaProjection.Callback.onCapturedContentResize (API 34+). */
    internal fun onCapturedContentResize() {
        scope.launch { onSourceResized() }
    }

    /** Rotation: the encoder and the stream stay as they are; only the picture moves inside the frame. */
    @Synchronized
    private fun onSourceResized() {
        val c = synchronized(lock) { capture } ?: return
        val source = c.encoder as? ScreenSource ?: return
        val (w, h) = currentDisplaySize(c.service)
        try {
            source.setSourceSize(w, h)
        } catch (e: RuntimeException) {
            Log.w(TAG, "döndürme", e)
        }
    }

    /** Wi-Fi / hotspot ağ değişikliklerini dinleyip oturumu gecikmesiz toparlar. */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        var primed = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!primed) {
                    primed = true
                    return
                }
                Log.i(TAG, "ağ kullanılabilir oldu; canlılık sınanıyor")
                synchronized(lock) { session }?.onNetworkChanged()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "ağ arayüzü kayboldu; canlılık sınanıyor")
                synchronized(lock) { session }?.onNetworkChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (!primed) return
                synchronized(lock) { session }?.onNetworkChanged()
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(req, cb)
            synchronized(lock) { capture?.networkCallback = cb }
        }.onFailure { e -> Log.w(TAG, "registerNetworkCallback", e) }
    }

    /** Kodlayıcı tekrar tekrar hata verirse önce alt ön ayar, hâlâ başarısızsa yazılım H.264 kodlayıcıya düşer. */
    private fun lowerPlan(current: StreamPlan): StreamPlan? {
        val (c, s) = synchronized(lock) { capture to session }
        if (c == null || s == null) return null
        if (c.camera != null) return null // the camera output size is fixed for the session
        val settings = AppSettings(c.service)
        val active = c.preset ?: settings.preset
        val next = active.lower
        if (next != null) {
            val plan = currentPlan(c.service, s, next)?.takeIf { it != current }
            if (plan != null) {
                synchronized(lock) {
                    c.plan = plan
                    c.preset = next
                }
                return plan
            }
        }
        // Donanım kodlayıcı en alt ön ayarda bile hata veriyorsa yazılım H.264 kodlayıcıya güvenli geri dönüş.
        if (current.hardware) {
            val swEncoders = AndroidEncoderCaps.h264Encoders().filterNot { it.hardware }
            if (swEncoders.isNotEmpty()) {
                val (screenW, screenH) = currentDisplaySize(c.service)
                val w = s.welcome
                val (srcW, srcH) = FrameFit.planningSource(screenW, screenH, w.displayWidth, w.displayHeight)
                val swPlan = StreamPlanner.plan(
                    srcW, srcH, Preset.WEAK_NETWORK, ReceiverDisplay(w.displayWidth, w.displayHeight, w.displayRefresh),
                    swEncoders, settings.overrides,
                )
                if (swPlan != null) {
                    Log.w(TAG, "donanım kodlayıcı başarısız, yazılım kodlayıcıya geçiliyor: ${swPlan.encoderName}")
                    synchronized(lock) {
                        c.plan = swPlan
                        c.preset = Preset.WEAK_NETWORK
                    }
                    return swPlan
                }
            }
        }
        return null
    }

    /**
     * Ağ koşullarına göre kalite basamağı değiştirir (`1080p/60 ↔ 720p/30 ↔ 540p/24`); seçilen ön ayarı aşmaz.
     * `ScreenSource` taze, kalıcı-olmayan kodlayıcı yüzeyi oluşturup GL ile tam hedef boyutta çizdiği için
     * çözünürlük değişimi donanım kodlayıcılarda güvenle çalışır ve dar hotspot'ta IDR boyutunu yarı yarıya düşürür.
     */
    @Synchronized
    private fun stepQuality(step: QualityStep): Boolean {
        val (c, s) = synchronized(lock) { capture to session }
        if (c == null || s == null || c.camera != null) return false
        val settings = AppSettings(c.service)
        if (settings.overrides != StreamOverrides()) return false
        val active = c.preset ?: settings.preset
        val next = (if (step == QualityStep.DOWN) active.lower else active.higher) ?: return false
        if (next < settings.preset) return false
        val base = c.plan ?: return false
        val plan = currentPlan(c.service, s, next)?.takeIf { it != base } ?: return false
        Log.i(TAG, "kalite: $active → $next (${plan.width}x${plan.height}@${plan.fps}, ${plan.minBps}–${plan.maxBps} bps)")
        synchronized(lock) {
            c.plan = plan
            c.preset = next
        }
        if (plan.width == base.width && plan.height == base.height && plan.encoderName == base.encoderName) {
            s.retune(plan)
        } else {
            s.reconfigure(plan)
        }
        boardFingerprint?.let { BoardStore(c.service).savePreset(it, next.takeIf { p -> p != settings.preset }) }
        return true
    }

    // --- Kullanıcı eylemleri ---

    fun pause() {
        val (c, s) = synchronized(lock) { capture to session }
        val st = _state.value
        if (c == null || s == null || st !is MirrorState.Mirroring || st.paused) return
        // Yeni kare üretimi durur: tahtada son kare ve "Yansıtma duraklatıldı" kalır.
        c.encoder?.setFramesEnabled(false)
        c.camera?.pause()
        s.pause()
        _state.value = st.copy(paused = true)
    }

    fun resume() {
        val (c, s) = synchronized(lock) { capture to session }
        val st = _state.value
        if (c == null || s == null || st !is MirrorState.Mirroring || !st.paused) return
        c.encoder?.setFramesEnabled(true)
        c.camera?.resume()
        s.unpause()
        _state.value = st.copy(paused = false)
    }

    /** Kullanıcı durdurdu veya bağlanmaktan vazgeçti (uygulama ya da bildirim). */
    fun stop() {
        val (s, prevCanceller) = synchronized(lock) {
            generation++
            val prev = activeAttempt
            activeAttempt = null
            session to prev
        }
        prevCanceller?.cancel()
        releaseConnectWifi()
        if (s != null) s.stop(ByeReason.USER_STOP) else releaseCapture()
        if (_state.value !is MirrorState.Ended) _state.value = MirrorState.Ended(board, null)
    }

    /** Sistem yansıtmayı bitirdi (durum çubuğu çipi, ekran kilidi — RISKS R2). */
    internal fun onProjectionStopped() {
        val (s, prevCanceller) = synchronized(lock) {
            generation++
            val prev = activeAttempt
            activeAttempt = null
            session to prev
        }
        prevCanceller?.cancel()
        releaseConnectWifi()
        if (s != null) s.stop(ByeReason.PROJECTION_STOPPED) else releaseCapture()
    }

    /** Hata/sonuç mesajı kapatıldı. */
    fun dismiss() {
        releaseConnectWifi()
        if (_state.value is MirrorState.Ended) _state.value = MirrorState.Idle
    }

    // --- Oturum olayları ---

    private fun onStats(stats: MirrorStats) {
        val st = _state.value
        if (st is MirrorState.Mirroring) _state.value = st.copy(stats = stats)
    }

    private fun onLink(link: LinkState) {
        val st = _state.value
        if (st is MirrorState.Mirroring) _state.value = st.copy(reconnecting = link == LinkState.RECONNECTING)
    }

    private fun onSessionEnd(end: SessionEnd) {
        val ended = synchronized(lock) { session.also { session = null } }
        Log.i(TAG, "oturum bitti: $end")
        // Uzun kopma: RESUME penceresi geçti ama yakalama sürüyor ve tahta hatırlanıyorsa eşleştirmeyle
        // yeni oturum açılır; öğretmenin bir şey yapması gerekmez.
        if (end is SessionEnd.ConnectionLost && ended != null && synchronized(lock) { capture } != null) {
            val remembered = rememberedForCurrent()
            if (remembered != null) {
                scope.launch { reconnectWithPairing(remembered) }
                return
            }
        }
        releaseConnectWifi()
        releaseCapture()
        val error = when (end) {
            is SessionEnd.Local -> when (end.byeReason) {
                ByeReason.PROJECTION_STOPPED -> MirrorError.PROJECTION_ENDED
                ByeReason.ERROR -> MirrorError.ENCODER_FAILED
                else -> null
            }
            is SessionEnd.Remote -> when (end.byeReason) {
                ByeReason.USER_STOP -> MirrorError.STOPPED_BY_BOARD
                ByeReason.TAKEOVER -> MirrorError.TAKEN_OVER
                ByeReason.RECEIVER_CLOSING -> MirrorError.BOARD_CLOSED
                else -> MirrorError.CONNECTION_LOST
            }
            is SessionEnd.ConnectionLost -> MirrorError.CONNECTION_LOST
            is SessionEnd.EncoderFailed -> MirrorError.ENCODER_FAILED
        }
        val current = _state.value
        // Kullanıcı durdurduysa "Ended(null)" zaten yazıldı; hatayla üzerine yazma.
        if (!(current is MirrorState.Ended && current.error == null && end is SessionEnd.Local)) {
            _state.value = MirrorState.Ended(board, error)
        }
    }

    private fun rememberedForCurrent(): RememberedBoard? {
        val boards = BoardStore(appContext)
        boardFingerprint?.let { fp -> boards.find(fp)?.let { return it } }
        return when (val t = target) {
            is ConnectTarget.Remembered -> boards.find(t.board.fingerprint)
            is ConnectTarget.Qr -> boards.find(t.payload.certFingerprint)
            is ConnectTarget.Discovered -> boards.findByPrefix(t.board.fingerprintPrefix)
            is ConnectTarget.Manual -> null
            null -> null
        }
    }

    private suspend fun reconnectWithPairing(remembered: RememberedBoard) {
        val st = _state.value
        if (st is MirrorState.Mirroring) _state.value = st.copy(reconnecting = true)
        val attempt = synchronized(lock) { generation }
        val deadline = System.nanoTime() + LONG_RECONNECT_WINDOW_NS
        var backoffMs = 1000L
        var currentBoard = remembered
        while (System.nanoTime() < deadline) {
            if (synchronized(lock) { attempt != generation || capture == null }) return
            when (val r = hello(ConnectTarget.Remembered(currentBoard)) { }) {
                is HelloResult.Ok -> {
                    resumeWithNewSession(attempt, r.session)
                    return
                }
                is HelloResult.Failed -> when (r.error) {
                    MirrorError.UNREACHABLE, MirrorError.CLIENT_ISOLATION, MirrorError.DIFFERENT_NETWORK, MirrorError.REFUSED, MirrorError.WRONG_BOARD -> {
                        // Güncel kayıt mDNS ile yenilenmiş olabilir; yeniden oku.
                        BoardStore(appContext).find(currentBoard.fingerprint)?.let { currentBoard = it }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 3 / 2).coerceAtMost(3500L)
                    }
                    else -> {
                        releaseCapture()
                        _state.value = MirrorState.Ended(board, r.error)
                        return
                    }
                }
            }
        }
        releaseCapture()
        _state.value = MirrorState.Ended(board, MirrorError.CONNECTION_LOST)
    }

    private fun resumeWithNewSession(attempt: Int, newSession: Session) {
        val s = newMirrorSession(newSession)
        val c = synchronized(lock) {
            if (attempt != generation || capture == null) null else capture.also { session = s }
        }
        val plan = c?.plan
        val encoder = c?.encoder
        if (c == null || plan == null || encoder == null) {
            newSession.close()
            return
        }
        s.start()
        try {
            // Önceki oturum kodlayıcıyı durdurdu; aynı VirtualDisplay ile yeniden başlar.
            s.startVideo(plan, encoder)
            val audio = c.projection?.let { startAudio(c.service, it, s) } ?: AudioState.OFF_CAMERA
            val cur = _state.value
            if (cur is MirrorState.Mirroring) {
                if (cur.paused) {
                    encoder.setFramesEnabled(true)
                    c.camera?.resume()
                }
                _state.value = cur.copy(reconnecting = false, paused = false, audio = audio)
            }
            Log.i(TAG, "eşleştirmeyle yeniden bağlanıldı")
        } catch (e: IOException) {
            Log.w(TAG, "yeniden bağlanma: video", e)
            s.stop(ByeReason.ERROR)
        }
    }

    // --- Yardımcılar ---

    private fun releaseCapture() {
        val c = synchronized(lock) { capture.also { capture = null } }
        if (c != null) main.post { c.release() }
    }

    private fun isCurrent(attempt: Int): Boolean {
        val state = _state.value
        return synchronized(lock) { attempt == generation } &&
            (state is MirrorState.Connecting || state is MirrorState.WaitingApproval)
    }

    private fun failIfCurrent(attempt: Int, error: MirrorError, detail: String? = null) {
        if (isCurrent(attempt)) fail(error, detail)
    }

    private fun fail(error: MirrorError, detail: String? = null) {
        releaseConnectWifi()
        synchronized(lock) { session }?.stop(ByeReason.ERROR)
        releaseCapture()
        _state.value = MirrorState.Ended(board, error, detail)
    }

    private fun rejectError(reason: Int): MirrorError = when (reason) {
        RejectReason.AUTH_FAILED, RejectReason.SESSION_UNKNOWN -> MirrorError.AUTH_FAILED
        RejectReason.BUSY_DENIED -> MirrorError.BUSY_DENIED
        RejectReason.BUSY_TIMEOUT -> MirrorError.BUSY_TIMEOUT
        RejectReason.RATE_LIMITED -> MirrorError.RATE_LIMITED
        RejectReason.LOCKED_OUT -> MirrorError.LOCKED_OUT
        RejectReason.VERSION_UNSUPPORTED, RejectReason.MALFORMED -> MirrorError.INCOMPATIBLE
        else -> MirrorError.INTERNAL
    }
}
