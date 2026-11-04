package io.github.ozkanceng.yansit

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ozkanceng.yansit.data.AppSettings
import io.github.ozkanceng.yansit.data.BoardStore
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.stream.AndroidEncoderCaps
import io.github.ozkanceng.yansit.stream.BoardDiscovery
import io.github.ozkanceng.yansit.stream.ConnectTarget
import io.github.ozkanceng.yansit.stream.DiscoveredBoard
import io.github.ozkanceng.yansit.stream.MirrorController
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.sender.StreamPlanner
import io.github.ozkanceng.yansit.stream.currentDisplaySize
import io.github.ozkanceng.yansit.ui.AppPermissions
import io.github.ozkanceng.yansit.ui.DiscoverScreen
import io.github.ozkanceng.yansit.ui.GuideScreen
import io.github.ozkanceng.yansit.ui.PinScreen
import io.github.ozkanceng.yansit.ui.HomeActions
import io.github.ozkanceng.yansit.ui.LatencyScreen
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

private enum class Screen { HOME, GUIDE, PERMISSIONS, SCANNER, SETTINGS, DISCOVER, PIN, LATENCY }

@Composable
private fun YansitApp() {
    val context = LocalContext.current
    val state by MirrorController.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    var consentInFlight by rememberSaveable { mutableStateOf(false) }
    val settings = remember { AppSettings(context) }
    val boardStore = remember { BoardStore(context) }
    // Durum her değiştiğinde (ör. yeni eşleştirme kaydedildi) liste yeniden okunur.
    var boardsVersion by remember { mutableIntStateOf(0) }
    val boards = remember(state, boardsVersion) { boardStore.list() }
    var pendingBoard by remember { mutableStateOf<RememberedBoard?>(null) }
    // Kodla bağlanma: yerel ağ izni istenirken bekleyen akış, ağda bulunan tahtalar ve seçilen tahta.
    var pendingCode by remember { mutableStateOf(false) }
    val discovery = remember { BoardDiscovery(context) }
    var discovered by remember { mutableStateOf<List<DiscoveredBoard>>(emptyList()) }
    var selected by remember { mutableStateOf<DiscoveredBoard?>(null) }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }

    fun openCodeFlow() {
        if (discovery.usesSystemPicker) {
            discovery.pick(
                onPicked = { b ->
                    selected = b
                    screen = Screen.PIN
                },
                onCancel = { },
            )
        } else {
            discovered = emptyList()
            screen = Screen.DISCOVER
            discovery.browse { discovered = it }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val board = pendingBoard
        when {
            pendingCode && AppPermissions.networkGranted(context) -> {
                pendingCode = false
                permissionsDenied = false
                screen = Screen.HOME
                openCodeFlow()
            }
            board != null && AppPermissions.networkGranted(context) -> {
                pendingBoard = null
                permissionsDenied = false
                screen = Screen.HOME
                MirrorController.connect(context, ConnectTarget.Remembered(board))
            }
            board == null && !pendingCode && AppPermissions.requiredGranted(context) -> {
                permissionsDenied = false
                screen = Screen.SCANNER
            }
            else -> permissionsDenied = true
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentInFlight = false
        MirrorController.onConsentResult(context, result.resultCode == Activity.RESULT_OK, result.resultCode, result.data)
    }

    // The camera image is not rotated before encoding, so camera mode keeps the screen in landscape.
    val cameraActive = (state as? MirrorState.Mirroring)?.cameraSize != null
    LaunchedEffect(cameraActive) {
        (context as? Activity)?.requestedOrientation =
            if (cameraActive) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    fun startScan() {
        MirrorController.dismiss()
        pendingBoard = null
        screen = if (AppPermissions.requiredGranted(context)) Screen.SCANNER else Screen.PERMISSIONS
    }

    fun startCode() {
        MirrorController.dismiss()
        pendingBoard = null
        if (AppPermissions.networkGranted(context)) {
            openCodeFlow()
        } else {
            pendingCode = true
            screen = Screen.PERMISSIONS
        }
    }

    fun connectRemembered(board: RememberedBoard) {
        MirrorController.dismiss()
        if (AppPermissions.networkGranted(context)) {
            MirrorController.connect(context, ConnectTarget.Remembered(board))
        } else {
            pendingBoard = board
            screen = Screen.PERMISSIONS
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            boards = boards,
            versionName = BuildConfig.VERSION_NAME,
            actions = HomeActions(
                onScan = ::startScan,
                onCode = ::startCode,
                onBoard = ::connectRemembered,
                onForget = { b ->
                    boardStore.remove(b.fingerprint)
                    boardsVersion++
                },
                onStop = MirrorController::stop,
                onPause = MirrorController::pause,
                onResume = MirrorController::resume,
                onDismiss = MirrorController::dismiss,
                onSettings = { screen = Screen.SETTINGS },
                onGuide = { screen = Screen.GUIDE },
            ),
        )
        Screen.GUIDE -> GuideScreen(onScan = ::startScan, onCode = ::startCode, onBack = { screen = Screen.HOME })
        Screen.PERMISSIONS -> PermissionScreen(
            missing = AppPermissions.missing(context).filter {
                (pendingBoard == null && !pendingCode) || it.permission == AppPermissions.ACCESS_LOCAL_NETWORK
            },
            deniedBefore = permissionsDenied,
            onRequest = {
                val wanted = AppPermissions.missing(context).filter {
                    (pendingBoard == null && !pendingCode) || it.permission == AppPermissions.ACCESS_LOCAL_NETWORK
                }
                permissionLauncher.launch(wanted.map { it.permission }.toTypedArray())
            },
            onBack = {
                pendingCode = false
                screen = Screen.HOME
            },
        )
        Screen.DISCOVER -> DiscoverScreen(
            boards = discovered,
            onPick = { b ->
                discovery.stop()
                selected = b
                screen = Screen.PIN
            },
            onBack = {
                discovery.stop()
                screen = Screen.HOME
            },
        )
        Screen.PIN -> selected?.let { b ->
            PinScreen(
                board = b,
                onConnect = { pin ->
                    screen = Screen.HOME
                    MirrorController.connect(context, ConnectTarget.Discovered(b, pin))
                },
                onBack = { screen = Screen.HOME },
            )
        } ?: run { screen = Screen.HOME }
        Screen.LATENCY -> LatencyScreen(onBack = { screen = Screen.SETTINGS })
        Screen.SCANNER -> ScannerScreen(
            onResult = { qr ->
                screen = Screen.HOME
                MirrorController.connect(context, ConnectTarget.Qr(qr))
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
                audioEnabled = settings.audioEnabled,
                hevcAvailable = remember { AndroidEncoderCaps.hevcHardwareEncoders().isNotEmpty() },
                hevcEnabled = settings.hevcEnabled,
                onHevcChange = { settings.hevcEnabled = it },
                onLatency = { screen = Screen.LATENCY },
                onChange = { p, o ->
                    settings.preset = p
                    settings.overrides = o
                },
                onAudioChange = { settings.audioEnabled = it },
                onBack = { screen = Screen.HOME },
            )
        }
    }
}
