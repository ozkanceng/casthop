package io.github.ozkanceng.casthop.tv.net

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.client.DiscoveryTxt
import io.github.ozkanceng.yansit.receiver.Discovery

/**
 * Advertises `_yansit._tcp` (PROTOCOL.md §8, §11). Discovery is a convenience: failing here leaves the QR working,
 * so errors only reach the log.
 */
internal class NsdAdvertiser(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun publish(name: String, port: Int, fingerprint: Bytes) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = Discovery.instanceName(name)
            serviceType = DiscoveryTxt.SERVICE_TYPE
            setPort(port)
            for ((key, value) in Discovery.txt(name, fingerprint)) setAttribute(key, value)
            val tv = context.getSystemService(UiModeManager::class.java).currentModeType ==
                Configuration.UI_MODE_TYPE_TELEVISION
            setAttribute("kind", if (tv) "tv" else "tablet")
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "advertising as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }
        listener = registration
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        listener?.let(nsd::unregisterService)
        listener = null
    }

    private companion object {
        const val TAG = "CastHopNsd"
    }
}
