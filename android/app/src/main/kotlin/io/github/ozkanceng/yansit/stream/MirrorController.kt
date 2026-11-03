package io.github.ozkanceng.yansit.stream

import io.github.ozkanceng.yansit.sender.StreamPlanner
import io.github.ozkanceng.yansit.sender.ReceiverDisplay
import io.github.ozkanceng.yansit.sender.MirrorSession
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.sender.SessionEnd
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.ozkanceng.yansit.BuildConfig
import io.github.ozkanceng.yansit.data.AppSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "YansitMirror"

/** Kullanıcıya gösterilen hata türleri (her biri anlaşılır bir mesaja karşılık gelir). */
enum class MirrorError {
    UNREACHABLE,
    REFUSED,
    WRONG_BOARD,
    AUTH_FAILED,
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
    INTERNAL,
}

sealed interface MirrorState {
    data object Idle : MirrorState
    data class Connecting(val board: String) : MirrorState
    data class WaitingApproval(val board: String, val timeoutMs: Long) : MirrorState
    /** Tahta kabul etti; sistemin ekran paylaşım onayı bekleniyor. */
    data class AwaitingConsent(val board: String) : MirrorState
    data class Starting(val board: String) : MirrorState
    data class Mirroring(val board: String, val stats: MirrorStats?) : MirrorState
    /** Bitti; [error] `null` ise kullanıcı kendisi durdurdu. */
    data class Ended(val board: String, val error: MirrorError?) : MirrorState
}

/**
 * Yansıtmanın uygulama genelindeki durum makinesi: QR → bağlan → tahta onayı → sistem onayı → yakalama.
 * Tek örnek; Activity ve [MirrorService] aynı süreçte bunu kullanır.
 */
object MirrorController {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: MirrorSession? = null
    private var capture: Capture? = null
    private var board: String = ""
    /** Her bağlantı denemesi ve durdurma bir artırır; eski denemenin geç gelen sonucu yok sayılır. */
    private var generation = 0

    /** Yansıtma sürerken tutulan sistem kaynakları. */
    private class Capture(
        val service: MirrorService,
        val projection: MediaProjection,
        var display: VirtualDisplay? = null,
        var encoder: ScreenEncoder? = null,
    ) {
        fun release() {
            display?.release()
            display = null
            encoder?.release()
            encoder = null
            try {
                projection.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "projection.stop", e)
            }
            service.stopForegroundAndSelf()
        }
    }

    /** QR okunduktan sonra: tahtaya bağlanır ve kimlik doğrular. */
    fun connect(context: Context, qr: QrPayload) {
        val current = _state.value
        if (current !is MirrorState.Idle && current !is MirrorState.Ended) return
        board = qr.name
        _state.value = MirrorState.Connecting(board)
        val settings = AppSettings(context)
        val attempt = synchronized(lock) { ++generation }
        scope.launch {
            val outcome = try {
                val host = when (val r = Reachability.firstReachable(qr.hosts, qr.port)) {
                    is ReachResult.Reachable -> r.host
                    is ReachResult.Unreachable -> {
                        failIfCurrent(attempt, if (r.refused) MirrorError.REFUSED else MirrorError.UNREACHABLE)
                        return@launch
                    }
                }
                SenderClient.hello(
                    Endpoint(host, qr.port, qr.certFingerprint),
                    Credentials.QrToken(qr.token),
                    DeviceInfo(
                        name = settings.deviceName,
                        deviceId = settings.deviceId,
                        appVersion = BuildConfig.VERSION_NAME,
                        videoCodecs = listOf(Codec.H264),
                        maxWidth = 3840,
                        maxHeight = 2160,
                        maxFps = 60,
                    ),
                ) { timeoutMs ->
                    if (isCurrent(attempt)) _state.value = MirrorState.WaitingApproval(board, timeoutMs)
                }
            } catch (e: FingerprintMismatchException) {
                failIfCurrent(attempt, MirrorError.WRONG_BOARD)
                return@launch
            } catch (e: IncompatibleReceiverException) {
                failIfCurrent(attempt, MirrorError.INCOMPATIBLE)
                return@launch
            } catch (e: ProtocolException) {
                Log.w(TAG, "protokol hatası", e)
                failIfCurrent(attempt, MirrorError.INCOMPATIBLE)
                return@launch
            } catch (e: IOException) {
                Log.w(TAG, "bağlantı hatası", e)
                failIfCurrent(attempt, MirrorError.UNREACHABLE)
                return@launch
            }
            if (!isCurrent(attempt)) {
                // Kullanıcı bu sırada vazgeçti.
                if (outcome is HelloOutcome.Accepted) {
                    runCatching { outcome.session.control.send(ControlMessage.Bye(ByeReason.USER_STOP)) }
                    outcome.session.close()
                }
                return@launch
            }
            when (outcome) {
                is HelloOutcome.Rejected -> failIfCurrent(attempt, rejectError(outcome.reason))
                is HelloOutcome.Accepted -> {
                    board = outcome.session.welcome.receiverName
                    val s = MirrorSession(outcome.session, ::onStats, ::onSessionEnd)
                    synchronized(lock) { session = s }
                    s.start()
                    _state.value = MirrorState.AwaitingConsent(board)
                }
            }
        }
    }

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

    /** [MirrorService] ön plana geçip MediaProjection'ı aldı. */
    internal fun onProjectionReady(service: MirrorService, projection: MediaProjection) {
        val s = synchronized(lock) {
            val s = session
            if (s == null) {
                null
            } else {
                capture = Capture(service, projection)
                s
            }
        }
        if (s == null) {
            projection.stop()
            service.stopForegroundAndSelf()
            return
        }
        scope.launch { startCapture(service, projection, s) }
    }

    private fun startCapture(service: MirrorService, projection: MediaProjection, s: MirrorSession) {
        val (srcW, srcH) = currentDisplaySize(service)
        val w = s.welcome
        val settings = AppSettings(service)
        val encoders = AndroidEncoderCaps.h264Encoders()
        val plan = StreamPlanner.plan(srcW, srcH, settings.preset, ReceiverDisplay(w.displayWidth, w.displayHeight, w.displayRefresh),
            encoders, settings.overrides)
        if (plan == null) {
            Log.e(TAG, "uygun kodlayıcı yok: ${encoders.map { it.name }}")
            fail(MirrorError.NO_ENCODER)
            return
        }
        val caps = encoders.first { it.name == plan.encoderName }
        val encoder = ScreenEncoder(caps)
        try {
            val display = projection.createVirtualDisplay(
                "Yansıt",
                plan.width,
                plan.height,
                service.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.inputSurface,
                null,
                Handler(Looper.getMainLooper()),
            )
            synchronized(lock) {
                capture?.display = display
                capture?.encoder = encoder
            }
            s.startVideo(plan, encoder)
            _state.value = MirrorState.Mirroring(board, null)
            Log.i(TAG, "yansıtma: ${plan.width}x${plan.height}@${plan.fps} (${plan.encoderName}), kaynak ${srcW}x$srcH")
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
        val s = synchronized(lock) { session } ?: return
        s.stop(ByeReason.PROJECTION_STOPPED)
    }

    /** Hata/sonuç mesajı kapatıldı. */
    fun dismiss() {
        if (_state.value is MirrorState.Ended) _state.value = MirrorState.Idle
    }

    private fun onStats(stats: MirrorStats) {
        if (_state.value is MirrorState.Mirroring) _state.value = MirrorState.Mirroring(board, stats)
    }

    private fun onSessionEnd(end: SessionEnd) {
        synchronized(lock) { session = null }
        releaseCapture()
        val error = when (end) {
            is SessionEnd.Local -> if (end.byeReason == ByeReason.PROJECTION_STOPPED) MirrorError.PROJECTION_ENDED
            else if (end.byeReason == ByeReason.ERROR) MirrorError.ENCODER_FAILED else null
            is SessionEnd.Remote -> when (end.byeReason) {
                ByeReason.USER_STOP -> MirrorError.STOPPED_BY_BOARD
                ByeReason.TAKEOVER -> MirrorError.TAKEN_OVER
                ByeReason.RECEIVER_CLOSING -> MirrorError.BOARD_CLOSED
                else -> MirrorError.CONNECTION_LOST
            }
            is SessionEnd.ConnectionLost -> MirrorError.CONNECTION_LOST
            is SessionEnd.EncoderFailed -> MirrorError.ENCODER_FAILED
        }
        Log.i(TAG, "oturum bitti: $end")
        val current = _state.value
        // Kullanıcı durdurduysa "Ended(null)" zaten yazıldı; hatayla üzerine yazma.
        if (!(current is MirrorState.Ended && current.error == null && end is SessionEnd.Local)) {
            _state.value = MirrorState.Ended(board, error)
        }
    }

    private fun releaseCapture() {
        val c = synchronized(lock) { capture.also { capture = null } }
        c?.release()
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
