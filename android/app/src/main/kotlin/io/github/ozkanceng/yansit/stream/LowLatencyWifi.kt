package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

private const val TAG = "YansitWifi"

/**
 * Keeps Wi-Fi out of power save while mirroring. Power save parks the radio between beacons, which adds
 * latency spikes that no encoder setting can remove.
 *
 * Both locks are held: the low latency lock applies while the app counts as foreground with the screen on,
 * and before Android 14 the high performance lock covers the rest. From Android 14 the system turns the
 * second one into a low latency lock too.
 */
class LowLatencyWifi(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private val locks = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF).map { mode ->
        wifi.createWifiLock(mode, "yansit:mirror-$mode").apply { setReferenceCounted(false) }
    }

    fun acquire() {
        try {
            locks.forEach { it.acquire() }
        } catch (e: RuntimeException) {
            // The system limits locks per app; mirroring still works without one.
            Log.w(TAG, "Wi-Fi lock not acquired", e)
        }
    }

    fun release() {
        locks.filter { it.isHeld }.forEach { it.release() }
    }
}
