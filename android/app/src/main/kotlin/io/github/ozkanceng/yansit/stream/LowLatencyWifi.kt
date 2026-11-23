package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

private const val TAG = "YansitWifi"

/**
 * Keeps both Wi-Fi radio (low-latency / high-perf) and CPU (PARTIAL_WAKE_LOCK) out of power save while mirroring.
 * Power save parks the radio between beacons and throttles background timers/threads during hotspot mirroring,
 * which adds latency spikes and liveness timeouts that no encoder setting can remove.
 */
class LowLatencyWifi(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)

    @Suppress("DEPRECATION")
    private val locks = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF).mapNotNull { mode ->
        wifi?.createWifiLock(mode, "yansit:mirror-$mode")?.apply { setReferenceCounted(false) }
    }

    private val wakeLock: PowerManager.WakeLock? =
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yansit:mirror-cpu")?.apply {
            setReferenceCounted(false)
        }

    fun acquire() {
        try {
            locks.forEach { it.acquire() }
        } catch (e: RuntimeException) {
            // The system limits locks per app; mirroring still works without one.
            Log.w(TAG, "Wi-Fi lock not acquired", e)
        }
        try {
            // Bounded timeout (4 h) satisfies Android Lint WakeLockTimeout while covering long lessons.
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "CPU wake lock not acquired", e)
        }
    }

    fun release() {
        locks.filter { it.isHeld }.forEach { runCatching { it.release() } }
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
    }
}
