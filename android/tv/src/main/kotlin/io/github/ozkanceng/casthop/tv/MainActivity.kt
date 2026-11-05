package io.github.ozkanceng.casthop.tv

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ozkanceng.casthop.tv.ui.CastHopTvScreen
import io.github.ozkanceng.casthop.tv.ui.theme.CastHopTvTheme
import kotlin.math.roundToInt

/** The one screen. The receiver runs between onStart and onStop. */
class MainActivity : ComponentActivity() {
    private val controller: ReceiverController get() = (application as CastHopTvApp).controller
    private val localNetworkDenied = mutableStateOf(false)

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkDenied.value = !granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            CastHopTvTheme {
                CastHopTvScreen(
                    controller = controller,
                    localNetworkDenied = localNetworkDenied.value,
                    onRequestLocalNetwork = { requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION) },
                )
            }
        }
        if (needsLocalNetworkPermission()) requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
    }

    override fun onStart() {
        super.onStart()
        localNetworkDenied.value = localNetworkDenied.value && needsLocalNetworkPermission()
        val mode = ContextCompat.getDisplayOrDefault(this).mode
        controller.start(DisplayInfo(mode.physicalWidth, mode.physicalHeight, mode.refreshRate.roundToInt()))
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    private fun needsLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= API_LOCAL_NETWORK &&
        checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED

    private companion object {
        const val API_LOCAL_NETWORK = 37
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
