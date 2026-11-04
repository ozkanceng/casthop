package io.github.ozkanceng.yansit.stream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
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
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.ProtocolException
import io.github.ozkanceng.yansit.protocol.QrPayload
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.client.Credentials
import io.github.ozkanceng.yansit.protocol.client.DeviceInfo
import io.github.ozkanceng.yansit.protocol.client.Endpoint
import io.github.ozkanceng.yansit.protocol.client.FingerprintMismatchException
import io.github.ozkanceng.yansit.protocol.client.HelloOutcome
import io.github.ozkanceng.yansit.protocol.client.IncompatibleReceiverException
import io.github.ozkanceng.yansit.protocol.client.ReachResult
import io.github.ozkanceng.yansit.protocol.client.Reachability
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "YansitMirror"

/** Kullanıcıya gösterilen hata türleri (her biri anlaşılır bir mesaja karşılık gelir). */
enum class MirrorError {
    UNREACHABLE,
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

/** Sesin durumu (arayüzde bilgi satırı). */
enum class AudioState { ON, OFF_SETTING, OFF_BOARD, OFF_PERMISSION, OFF_CAMERA, UNSUPPORTED, FAILED }

sealed interface MirrorState {
    data object Idle : MirrorState
    data class Connecting(val board: String) : MirrorState
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
    ) : MirrorState
    /** Bitti; [error] `null` ise kullanıcı kendisi durdurdu. */
    data class Ended(val board: String, val error: MirrorError?) : MirrorState
}

/** Bağlantı hedefi: yeni QR, hatırlanan tahta ya da ağda bulunup PIN'i girilen tahta. */
sealed interface ConnectTarget {
    data class Qr(val payload: QrPayload) : ConnectTarget
    data class Remembered(val board: RememberedBoard) : ConnectTarget
    data class Discovered(val board: DiscoveredBoard, val pin: String) : ConnectTarget
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
    /** Her bağlantı denemesi ve durdurma bir artırır; eski denemenin geç gelen sonucu yok sayılır. */
    private var generation = 0
    private lateinit var appContext: Context

    /** Uzun kopmada eşleştirmeyle yeniden bağlanma denemesi süresi. */
    private const val LONG_RECONNECT_WINDOW_NS = 60_000_000_000L

    /** Upper limit for camera mode; the preset and the receiver can lower it. */
    private const val CAMERA_MAX_WIDTH = 1920
    private const val CAMERA_MAX_HEIGHT = 1080

    /** Yansıtma sürerken tutulan sistem kaynakları. */
    private class Capture(val service: MirrorService, val projection: MediaProjection?) {
        var display: VirtualDisplay? = null
        var camera: CameraCapture? = null
        var encoder: ScreenEncoder? = null
        var plan: StreamPlan? = null
        /** Preset in use when the network or encoder moved away from the chosen one. */
        var preset: Preset? = null
        var displayListener: DisplayManager.DisplayListener? = null

        fun release() {
            displayListener?.let { service.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
            displayListener = null
            display?.release()
            display = null
            camera?.release()
            camera = null
            encoder?.release()
            encoder = null
            try {
                projection?.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "projection.stop", e)
            }
            service.stopForegroundAndSelf()
        }
    }

    // --- Bağlanma ---

    fun connect(context: Context, target: ConnectTarget) {
        val current = _state.value
        if (current !is MirrorState.Idle && current !is MirrorState.Ended) return
        appContext = context.applicationContext
        this.target = target
        board = when (target) {
            is ConnectTarget.Qr -> target.payload.name
            is ConnectTarget.Remembered -> target.board.name
            is ConnectTarget.Discovered -> target.board.name
        }
        _state.value = MirrorState.Connecting(board)
        val attempt = synchronized(lock) { ++generation }
        scope.launch {
            val result = hello(target) { timeoutMs ->
                if (isCurrent(attempt)) _state.value = MirrorState.WaitingApproval(board, timeoutMs)
            }
            when (result) {
                is HelloResult.Failed -> failIfCurrent(attempt, result.error)
                is HelloResult.Ok -> {
                    if (!isCurrent(attempt)) {
                        // Kullanıcı bu sırada vazgeçti.
                        runCatching { result.session.control.send(ControlMessage.Bye(ByeReason.USER_STOP)) }
                        result.session.close()
                        return@launch
                    }
                    board = result.session.welcome.receiverName
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
        data class Failed(val error: MirrorError) : HelloResult
    }

    /** Ulaşılabilirlik + kimlik doğrulama; QR ile ilk bağlantıda kalıcı eşleştirme istenir ve kaydedilir. */
    private fun hello(target: ConnectTarget, onPending: (Long) -> Unit): HelloResult {
        val settings = AppSettings(appContext)
        val (hosts, port, fp) = when (target) {
            is ConnectTarget.Qr -> Triple(target.payload.hosts, target.payload.port, target.payload.certFingerprint)
            is ConnectTarget.Remembered -> Triple(target.board.hosts, target.board.port, target.board.fingerprint)
            // mDNS'te yalnız parmak izi öneki var: ilk bağlantıda öneki denetlenir, tam iz eşleştirmeyle kaydedilir.
            is ConnectTarget.Discovered -> Triple(target.board.hosts, target.board.port, null)
        }
        val fpPrefix = (target as? ConnectTarget.Discovered)?.board?.fingerprintPrefix
        val host = when (val r = Reachability.firstReachable(hosts, port)) {
            is ReachResult.Reachable -> r.host
            is ReachResult.Unreachable -> return HelloResult.Failed(
                when {
                    r.refused -> MirrorError.REFUSED
                    NetworkDiagnostics.sharesSubnet(appContext, hosts) == false -> MirrorError.DIFFERENT_NETWORK
                    else -> MirrorError.UNREACHABLE
                },
            )
        }
        val credentials = when (target) {
            is ConnectTarget.Qr -> Credentials.QrToken(target.payload.token)
            is ConnectTarget.Remembered -> Credentials.Paired(target.board.keyId, target.board.pairKey)
            is ConnectTarget.Discovered -> Credentials.Pin(target.pin)
        }
        val outcome = try {
            SenderClient.hello(
                Endpoint(host, port, fp, fpPrefix),
                credentials,
                DeviceInfo(
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
                ),
                requestPairing = target !is ConnectTarget.Remembered,
                onPending = onPending,
            )
        } catch (e: FingerprintMismatchException) {
            return HelloResult.Failed(MirrorError.WRONG_BOARD)
        } catch (e: IncompatibleReceiverException) {
            return HelloResult.Failed(MirrorError.INCOMPATIBLE)
        } catch (e: ProtocolException) {
            Log.w(TAG, "protokol hatası", e)
            return HelloResult.Failed(MirrorError.INCOMPATIBLE)
        } catch (e: IOException) {
            Log.w(TAG, "bağlantı hatası", e)
            return HelloResult.Failed(MirrorError.UNREACHABLE)
        }
        val boards = BoardStore(appContext)
        return when (outcome) {
            is HelloOutcome.Rejected -> {
                if (target is ConnectTarget.Discovered && outcome.reason == RejectReason.AUTH_FAILED) {
                    HelloResult.Failed(MirrorError.WRONG_PIN)
                } else if (target is ConnectTarget.Remembered && outcome.reason == RejectReason.AUTH_FAILED) {
                    // Tahta eşleştirmeyi unutmuş (silinmiş veya sertifika yenilenmiş): yeniden QR gerekir.
                    boards.remove(target.board.fingerprint)
                    HelloResult.Failed(MirrorError.BOARD_FORGOT_TABLET)
                } else {
                    HelloResult.Failed(rejectError(outcome.reason))
                }
            }
            is HelloOutcome.Accepted -> {
                val w = outcome.session.welcome
                val now = System.currentTimeMillis()
                val keyId = w.keyId
                val pairKey = w.pairKey
                when (target) {
                    is ConnectTarget.Qr -> if (keyId != null && pairKey != null) {
                        boards.save(
                            RememberedBoard(w.receiverName, target.payload.hosts, target.payload.port,
                                outcome.session.fingerprint, keyId, pairKey, now),
                        )
                    }
                    is ConnectTarget.Discovered -> if (keyId != null && pairKey != null) {
                        boards.save(
                            RememberedBoard(w.receiverName, target.board.hosts, target.board.port,
                                outcome.session.fingerprint, keyId, pairKey, now),
                        )
                    }
                    is ConnectTarget.Remembered -> boards.save(target.board.copy(name = w.receiverName, lastUsedMs = now))
                }
                HelloResult.Ok(outcome.session)
            }
        }
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
        val encoder = ScreenEncoder(caps)
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
            _state.value = MirrorState.Mirroring(
                board, null, audio = AudioState.OFF_CAMERA, cameraSize = size, canSwitchCamera = camera.canSwitch,
            )
            Log.i(TAG, "kamera: ${plan.width}x${plan.height}@${plan.fps} (${plan.encoderName})")
        } catch (e: IOException) {
            Log.w(TAG, "video bağlantısı kurulamadı", e)
            s.stop(ByeReason.ERROR)
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
        val s = synchronized(lock) {
            session?.also { capture = Capture(service, projection) }
        }
        if (s == null) {
            projection.stop()
            service.stopForegroundAndSelf()
            return
        }
        scope.launch { startCapture(service, projection, s) }
    }

    private fun currentPlan(context: Context, s: MirrorSession, preset: Preset? = null): StreamPlan? {
        val (srcW, srcH) = currentDisplaySize(context)
        val w = s.welcome
        val settings = AppSettings(context)
        return StreamPlanner.plan(
            srcW, srcH, preset ?: settings.preset, ReceiverDisplay(w.displayWidth, w.displayHeight, w.displayRefresh),
            encodersFor(settings, w), settings.overrides,
        )
    }

    /** H.265 yalnız ayar açık, tahta donanımla çözüyor ve tablette donanım kodlayıcı varsa; önce denenir. */
    private fun encodersFor(settings: AppSettings, w: ControlMessage.Welcome): List<AndroidEncoderCaps> {
        val boardHevcHw = w.videoDecoders.any { it.codec == Codec.H265 && it.hardware }
        val hevc = if (settings.hevcEnabled && boardHevcHw) AndroidEncoderCaps.hevcHardwareEncoders() else emptyList()
        return hevc + AndroidEncoderCaps.h264Encoders()
    }

    private fun startCapture(service: MirrorService, projection: MediaProjection, s: MirrorSession) {
        val plan = currentPlan(service, s)
        if (plan == null) {
            Log.e(TAG, "uygun kodlayıcı yok: ${encodersFor(AppSettings(service), s.welcome).map { it.name }}")
            fail(MirrorError.NO_ENCODER)
            return
        }
        val caps = encodersFor(AppSettings(service), s.welcome).first { it.name == plan.encoderName }
        val encoder = ScreenEncoder(caps)
        try {
            val display = projection.createVirtualDisplay(
                "CastHop", plan.width, plan.height, service.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.inputSurface, null, main,
            )
            synchronized(lock) {
                capture?.display = display
                capture?.encoder = encoder
                capture?.plan = plan
            }
            s.startVideo(plan, encoder)
            watchRotation(service)
            _state.value = MirrorState.Mirroring(board, null, audio = startAudio(service, projection, s))
            Log.i(TAG, "yansıtma: ${plan.width}x${plan.height}@${plan.fps} (${plan.encoderName})")
        } catch (e: IOException) {
            Log.w(TAG, "video bağlantısı kurulamadı", e)
            s.stop(ByeReason.ERROR)
        } catch (e: RuntimeException) {
            // MediaCodec yapılandırma hataları (IllegalStateException, CodecException...).
            Log.e(TAG, "kodlayıcı başlatılamadı", e)
            synchronized(lock) { if (capture?.encoder == null) encoder.release() }
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

    @Synchronized
    private fun onSourceResized() {
        val (c, s) = synchronized(lock) { capture to session }
        if (c == null || s == null) return
        val service = c.service
        val newPlan = currentPlan(service, s, c.preset) ?: return
        val old = c.plan ?: return
        if (newPlan.width == old.width && newPlan.height == old.height) return
        Log.i(TAG, "kaynak boyutu değişti: ${old.width}x${old.height} → ${newPlan.width}x${newPlan.height}")
        val started = System.nanoTime()
        synchronized(lock) {
            c.display?.resize(newPlan.width, newPlan.height, service.resources.displayMetrics.densityDpi)
            c.plan = newPlan
        }
        s.reconfigure(newPlan)
        Log.i(TAG, "yeniden yapılandırma ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Kodlayıcı tekrar tekrar hata verirse bir alt ön ayar (ARCHITECTURE.md §7). */
    private fun lowerPlan(current: StreamPlan): StreamPlan? {
        val (c, s) = synchronized(lock) { capture to session }
        if (c == null || s == null) return null
        if (c.camera != null) return null // the camera output size is fixed for the session
        val active = c.preset ?: AppSettings(c.service).preset
        val next = active.lower ?: active
        val plan = currentPlan(c.service, s, next)?.takeIf { it != current } ?: return null
        synchronized(lock) {
            c.display?.resize(plan.width, plan.height, c.service.resources.displayMetrics.densityDpi)
            c.plan = plan
            c.preset = next
        }
        return plan
    }

    /** Moves one preset down or back up; never above the one the teacher chose. Advanced settings turn this off. */
    @Synchronized
    private fun stepQuality(step: QualityStep): Boolean {
        val (c, s) = synchronized(lock) { capture to session }
        if (c == null || s == null || c.camera != null) return false
        val settings = AppSettings(c.service)
        if (settings.overrides != StreamOverrides()) return false
        val active = c.preset ?: settings.preset
        val next = (if (step == QualityStep.DOWN) active.lower else active.higher) ?: return false
        if (next < settings.preset) return false
        val plan = currentPlan(c.service, s, next)?.takeIf { it != c.plan } ?: return false
        Log.i(TAG, "kalite: $active → $next (${plan.width}x${plan.height}@${plan.fps})")
        synchronized(lock) {
            c.display?.resize(plan.width, plan.height, c.service.resources.displayMetrics.densityDpi)
            c.plan = plan
            c.preset = next
        }
        s.reconfigure(plan)
        return true
    }

    // --- Kullanıcı eylemleri ---

    fun pause() {
        val (c, s) = synchronized(lock) { capture to session }
        val st = _state.value
        if (c == null || s == null || st !is MirrorState.Mirroring || st.paused) return
        // Yeni kare üretimi durur: tahtada son kare ve "Yansıtma duraklatıldı" kalır.
        c.display?.surface = null
        c.camera?.pause()
        s.pause()
        _state.value = st.copy(paused = true)
    }

    fun resume() {
        val (c, s) = synchronized(lock) { capture to session }
        val st = _state.value
        if (c == null || s == null || st !is MirrorState.Mirroring || !st.paused) return
        c.display?.surface = c.encoder?.inputSurface
        c.camera?.resume()
        s.unpause()
        _state.value = st.copy(paused = false)
    }

    /** Kullanıcı durdurdu veya bağlanmaktan vazgeçti (uygulama ya da bildirim). */
    fun stop() {
        val s = synchronized(lock) {
            generation++
            session
        }
        if (s != null) s.stop(ByeReason.USER_STOP) else releaseCapture()
        if (_state.value !is MirrorState.Ended) _state.value = MirrorState.Ended(board, null)
    }

    /** Sistem yansıtmayı bitirdi (durum çubuğu çipi, ekran kilidi — RISKS R2). */
    internal fun onProjectionStopped() {
        val s = synchronized(lock) {
            generation++
            session
        }
        if (s != null) s.stop(ByeReason.PROJECTION_STOPPED) else releaseCapture()
    }

    /** Hata/sonuç mesajı kapatıldı. */
    fun dismiss() {
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
        return when (val t = target) {
            is ConnectTarget.Remembered -> boards.find(t.board.fingerprint)
            is ConnectTarget.Qr -> boards.find(t.payload.certFingerprint)
            is ConnectTarget.Discovered -> boards.list().firstOrNull { b ->
                (0 until t.board.fingerprintPrefix.size).all { b.fingerprint[it] == t.board.fingerprintPrefix[it] }
            }
            null -> null
        }
    }

    private suspend fun reconnectWithPairing(remembered: RememberedBoard) {
        val st = _state.value
        if (st is MirrorState.Mirroring) _state.value = st.copy(reconnecting = true)
        val attempt = synchronized(lock) { generation }
        val deadline = System.nanoTime() + LONG_RECONNECT_WINDOW_NS
        while (System.nanoTime() < deadline) {
            if (synchronized(lock) { attempt != generation || capture == null }) return
            when (val r = hello(ConnectTarget.Remembered(remembered)) { }) {
                is HelloResult.Ok -> {
                    resumeWithNewSession(attempt, r.session)
                    return
                }
                is HelloResult.Failed -> when (r.error) {
                    MirrorError.UNREACHABLE, MirrorError.DIFFERENT_NETWORK, MirrorError.REFUSED -> delay(1000)
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
            // Önceki oturum kodlayıcıyı durdurdu; aynı Surface ve VirtualDisplay ile yeniden başlar.
            s.startVideo(plan, encoder)
            val audio = c.projection?.let { startAudio(c.service, it, s) } ?: AudioState.OFF_CAMERA
            val cur = _state.value
            if (cur is MirrorState.Mirroring) {
                if (cur.paused) {
                    c.display?.surface = encoder.inputSurface
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

    private fun failIfCurrent(attempt: Int, error: MirrorError) {
        if (isCurrent(attempt)) fail(error)
    }

    private fun fail(error: MirrorError) {
        synchronized(lock) { session }?.stop(ByeReason.ERROR)
        releaseCapture()
        _state.value = MirrorState.Ended(board, error)
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
