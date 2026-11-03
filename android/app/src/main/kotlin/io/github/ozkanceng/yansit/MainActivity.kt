package io.github.ozkanceng.yansit

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ozkanceng.yansit.data.AppSettings
import io.github.ozkanceng.yansit.stream.AndroidEncoderCaps
import io.github.ozkanceng.yansit.stream.MirrorController
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.sender.StreamPlanner
import io.github.ozkanceng.yansit.stream.currentDisplaySize
import io.github.ozkanceng.yansit.ui.AppPermissions
import io.github.ozkanceng.yansit.ui.HomeScreen
import io.github.ozkanceng.yansit.ui.PermissionScreen
import io.github.ozkanceng.yansit.ui.ScannerScreen
import io.github.ozkanceng.yansit.ui.SettingsOptions
import io.github.ozkanceng.yansit.ui.SettingsScreen
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YansitTheme { YansitApp() }
        }
    }
}

private enum class Screen { HOME, PERMISSIONS, SCANNER, SETTINGS }

@Composable
private fun YansitApp() {
    val context = LocalContext.current
    val state by MirrorController.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    var consentInFlight by rememberSaveable { mutableStateOf(false) }
    val settings = remember { AppSettings(context) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (AppPermissions.requiredGranted(context)) {
            permissionsDenied = false
            screen = Screen.SCANNER
        } else {
            permissionsDenied = true
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentInFlight = false
        MirrorController.onConsentResult(context, result.resultCode == Activity.RESULT_OK, result.resultCode, result.data)
    }

    // Tahta kabul edince sistemin ekran paylaşımı onayı istenir (her oturumda zorunlu).
    LaunchedEffect(state) {
        if (state is MirrorState.AwaitingConsent && !consentInFlight) {
            consentInFlight = true
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Yalnız "tüm ekran": öğretmenin tek uygulama seçmesi gerekmez.
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            consentLauncher.launch(intent)
        }
    }

    fun startConnect() {
        MirrorController.dismiss()
        screen = if (AppPermissions.requiredGranted(context)) Screen.SCANNER else Screen.PERMISSIONS
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            versionName = BuildConfig.VERSION_NAME,
            onConnect = ::startConnect,
            onStop = MirrorController::stop,
            onDismiss = MirrorController::dismiss,
            onSettings = { screen = Screen.SETTINGS },
        )
        Screen.PERMISSIONS -> PermissionScreen(
            missing = AppPermissions.missing(context),
            deniedBefore = permissionsDenied,
            onRequest = { permissionLauncher.launch(AppPermissions.missing(context).map { it.permission }.toTypedArray()) },
            onBack = { screen = Screen.HOME },
        )
        Screen.SCANNER -> ScannerScreen(
            onResult = { qr ->
                screen = Screen.HOME
                MirrorController.connect(context, qr)
            },
            onBack = { screen = Screen.HOME },
        )
        Screen.SETTINGS -> {
            val options = remember {
                val encoders = AndroidEncoderCaps.h264Encoders()
                val (w, h) = currentDisplaySize(context)
                val maxBps = encoders.maxOfOrNull { it.bitrateRange.last } ?: 0L
                SettingsOptions(
                    longSides = StreamPlanner.supportedLongSides(encoders, w, h),
                    fps = StreamPlanner.supportedFps(encoders, w, h),
                    maxBitratesBps = listOf(4L, 8L, 12L, 16L, 24L).map { it * 1_000_000 }.filter { it <= maxBps },
                )
            }
            SettingsScreen(
                preset = settings.preset,
                overrides = settings.overrides,
                options = options,
                onChange = { p, o ->
                    settings.preset = p
                    settings.overrides = o
                },
                onBack = { screen = Screen.HOME },
            )
        }
    }
}
