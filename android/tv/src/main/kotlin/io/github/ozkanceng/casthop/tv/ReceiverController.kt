package io.github.ozkanceng.casthop.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.content.edit
import io.github.ozkanceng.casthop.tv.media.DecoderCatalog
import io.github.ozkanceng.casthop.tv.media.MediaCodecAudioBackend
import io.github.ozkanceng.casthop.tv.media.MediaCodecVideoBackend
import io.github.ozkanceng.casthop.tv.net.LocalAddresses
import io.github.ozkanceng.casthop.tv.net.NsdAdvertiser
import io.github.ozkanceng.casthop.tv.net.TlsIdentity
import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.receiver.FileTrustedStore
import io.github.ozkanceng.yansit.receiver.JsseServerTransport
import io.github.ozkanceng.yansit.receiver.PairingInfo
import io.github.ozkanceng.yansit.receiver.ReceiverEngine
import io.github.ozkanceng.yansit.receiver.ReceiverObserver
import io.github.ozkanceng.yansit.receiver.ReceiverPhase
import io.github.ozkanceng.yansit.receiver.ReceiverState
import io.github.ozkanceng.yansit.receiver.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.BindException
import java.security.GeneralSecurityException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Why the receiver cannot run at all. */
internal enum class Failure { PORT_IN_USE, IDENTITY, NO_DECODER }

/** The panel this app draws on. */
internal data class DisplayInfo(val width: Int, val height: Int, val refresh: Int)

/** Everything the screens show. */
internal data class TvState(
    val receiver: ReceiverState = ReceiverState(),
    val running: Boolean = false,
    val failure: Failure? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFailed: Boolean = false,
    /** The picture on screen came turned 90° clockwise (FrameFlags.ROTATED) and is shown turned back. */
    val videoRotated: Boolean = false,
    val hasNetwork: Boolean = true,
)

/**
 * Owns the receiver for as long as the app is on screen. [start] and [stop] run on one background thread in the order
 * they were called, so leaving and returning quickly cannot open the port twice. There is no background service: a
 * screen that is not showing CastHop must not accept a mirror it cannot display.
 */
internal class ReceiverController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lifecycle = Executors.newSingleThreadExecutor { r -> Thread(r, "casthop-lifecycle") }
    private val catalog = DecoderCatalog()
    private val nsd = NsdAdvertiser(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiLock: WifiManager.WifiLock? = appContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "casthop:mirror")
        ?.apply { setReferenceCounted(false) }

    private val video = MediaCodecVideoBackend(
        catalog,
        MonotonicClock::nowUs,
        onVideoSize = { width, height -> _state.update { it.copy(videoWidth = width, videoHeight = height) } },
        onFailure = { failed -> _state.update { it.copy(videoFailed = failed) } },
        onRotated = { rotated -> _state.update { it.copy(videoRotated = rotated) } },
    )
    private val audio = MediaCodecAudioBackend(catalog, MonotonicClock::nowUs)

    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = _state.asStateFlow()

    private val observers = CopyOnWriteArrayList<ReceiverObserver>()

    @Volatile
    private var engine: ReceiverEngine? = null

    @Volatile
    private var lastPairing: PairingInfo? = null
    private var fingerprint: Bytes? = null
    private var port = 0

    private val engineObserver = object : ReceiverObserver {
        override fun onStateChanged(state: ReceiverState) {
            _state.update { it.copy(receiver = state) }
            holdWifi(state.phase != ReceiverPhase.WAITING)
            observers.forEach { it.onStateChanged(state) }
        }

        override fun onPairingChanged(info: PairingInfo) {
            lastPairing = info
            observers.forEach { it.onPairingChanged(info) }
        }

        override fun onSessionFinished(summary: SessionSummary) {
            observers.forEach { it.onSessionFinished(summary) }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
    }

    val receiverName: String
        get() = prefs.getString(KEY_NAME, null) ?: defaultName()

    private val audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)

    fun start(display: DisplayInfo) {
        lifecycle.execute { open(display) }
    }

    fun stop() {
        lifecycle.execute(::close)
    }

    private fun open(display: DisplayInfo) {
        if (engine != null) return
        if (catalog.videoSupport.isEmpty()) {
            fail(Failure.NO_DECODER)
            return
        }
        val identity = try {
            TlsIdentity.loadOrCreate()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "no TLS identity", e)
            fail(Failure.IDENTITY)
            return
        } catch (e: IOException) {
            Log.e(TAG, "no TLS identity", e)
            fail(Failure.IDENTITY)
            return
        }
        val transport = try {
            JsseServerTransport(identity.context, PORT)
        } catch (e: BindException) {
            Log.e(TAG, "port $PORT is taken", e)
            fail(Failure.PORT_IN_USE)
            return
        }
        val digest = Auth.sha256(identity.certificateDer)
        val receiver = ReceiverEngine(
            ReceiverEngine.Options(
                name = receiverName,
                displayWidth = display.width,
                displayHeight = display.height,
                displayRefresh = display.refresh,
                videoDecoders = catalog.videoSupport,
                audioDecoders = catalog.audioSupport,
                audio = audioEnabled,
                hosts = LocalAddresses::list,
            ),
            transport,
            digest,
            video,
            audio,
            FileTrustedStore(File(appContext.filesDir, TRUSTED_FILE)),
            engineObserver,
        )
        video.pipeline = receiver.videoPipeline
        audio.pipeline = receiver.audioPipeline
        receiver.start()
        engine = receiver
        fingerprint = digest
        port = transport.port
        connectivity.registerDefaultNetworkCallback(networkCallback)
        nsd.publish(receiverName, port, digest)
        _state.update { it.copy(running = true, failure = null, hasNetwork = LocalAddresses.list().isNotEmpty()) }
    }

    private fun close() {
        val running = engine ?: return
        engine = null
        connectivity.unregisterNetworkCallback(networkCallback)
        nsd.stop()
        running.shutdown()
        holdWifi(false)
        _state.update { it.copy(running = false, videoWidth = 0, videoHeight = 0, videoFailed = false, videoRotated = false) }
    }

    private fun fail(failure: Failure) {
        _state.update { it.copy(failure = failure) }
    }

    private fun onNetworkChanged() {
        lifecycle.execute {
            engine?.refreshAddresses()
            _state.update { it.copy(hasNetwork = LocalAddresses.list().isNotEmpty()) }
        }
    }

    private fun holdWifi(hold: Boolean) {
        val lock = wifiLock ?: return
        if (hold && !lock.isHeld) lock.acquire()
        if (!hold && lock.isHeld) lock.release()
    }

    private fun defaultName(): String =
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun setSurface(surface: Surface?) = video.setSurface(surface)

    fun approvePending() = onEngine { it.approvePending() }

    fun denyPending() = onEngine { it.denyPending() }

    fun stopSession() = onEngine { it.stopSession() }

    fun forgetSender(keyIdHex: String) = onEngine { it.forgetSender(keyIdHex) }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs.edit { putString(KEY_NAME, trimmed) }
        onEngine {
            it.setReceiverName(trimmed)
            fingerprint?.let { digest -> nsd.publish(trimmed, port, digest) }
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUDIO, enabled) }
        onEngine { it.setAudioEnabled(enabled) }
    }

    private fun onEngine(action: (ReceiverEngine) -> Unit) {
        lifecycle.execute { engine?.let(action) }
    }

    /** Adds [observer] and hands it the current pairing data at once. */
    fun addObserver(observer: ReceiverObserver) {
        if (observer in observers) return
        observers += observer
        lastPairing?.let(observer::onPairingChanged)
    }

    private companion object {
        const val TAG = "CastHopTv"
        const val PORT = 47800
        const val PREFS = "settings"
        const val KEY_NAME = "name"
        const val KEY_AUDIO = "audio"
        const val TRUSTED_FILE = "trusted"
    }
}
