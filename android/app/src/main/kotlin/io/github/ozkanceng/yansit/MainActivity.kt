package io.github.ozkanceng.yansit

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ozkanceng.yansit.data.AppSettings
import io.github.ozkanceng.yansit.data.BoardIdentity
import io.github.ozkanceng.yansit.data.BoardStore
import io.github.ozkanceng.yansit.data.ManualCheckResult
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.data.UpdateBannerInfo
import io.github.ozkanceng.yansit.data.UpdateChecker
import io.github.ozkanceng.yansit.data.UpdateStatusUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.StreamPlanner
import io.github.ozkanceng.yansit.stream.AndroidEncoderCaps
import io.github.ozkanceng.yansit.stream.BoardDiscovery
import io.github.ozkanceng.yansit.stream.ConnectTarget
import io.github.ozkanceng.yansit.stream.DiscoveredBoard
import io.github.ozkanceng.yansit.stream.MirrorController
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.stream.currentDisplaySize
import io.github.ozkanceng.yansit.ui.AppPermissions
import io.github.ozkanceng.yansit.ui.DiscoverScreen
import io.github.ozkanceng.yansit.ui.GuideScreen
import io.github.ozkanceng.yansit.ui.HomeActions
import io.github.ozkanceng.yansit.ui.HomeScreen
import io.github.ozkanceng.yansit.ui.LatencyScreen
import io.github.ozkanceng.yansit.ui.ManualIpScreen
import io.github.ozkanceng.yansit.ui.PermissionScreen
import io.github.ozkanceng.yansit.ui.PinScreen
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

private enum class Screen { HOME, GUIDE, PERMISSIONS, SCANNER, SETTINGS, DISCOVER, PIN, MANUAL_IP, LATENCY }

@Composable
private fun YansitApp() {
    val context = LocalContext.current
    val state by MirrorController.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    var consentInFlight by rememberSaveable { mutableStateOf(false) }
    val settings = remember { AppSettings(context) }
    val boardStore = remember { BoardStore(context) }
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateBannerInfo?>(null) }
    var updateDismissed by rememberSaveable { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<UpdateStatusUi?>(null) }

    // Başlangıçtan 3 sn sonra arka planda günde en fazla 1 kez sürüm denetimi (yansıtmayı geciktirmez).
    LaunchedEffect(Unit) {
        delay(3000)
        if (MirrorController.state.value is MirrorState.Idle) {
            UpdateChecker.checkIfDue(context, settings)?.let { updateInfo = it }
        }
    }
    // Durum her değiştiğinde (ör. yeni eşleştirme kaydedildi) liste yeniden okunur.
    var boardsVersion by remember { mutableIntStateOf(0) }
    val boards = remember(state, boardsVersion) { boardStore.list() }
    var pendingBoard by remember { mutableStateOf<RememberedBoard?>(null) }
    // Yerel ağ izni istenirken bekleyen akışlar.
    var pendingCode by remember { mutableStateOf(false) }
    var pendingManualIp by remember { mutableStateOf(false) }
    val discovery = remember { BoardDiscovery(context) }
    val passiveDiscovery = remember { BoardDiscovery(context) }
    var discovered by remember { mutableStateOf<List<DiscoveredBoard>>(emptyList()) }
    var onlineFingerprints by remember { mutableStateOf<Set<Bytes>>(emptySet()) }
    var discoveryFailed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DiscoveredBoard?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            discovery.stop()
            passiveDiscovery.stop()
        }
    }

    // Ana ekran boştayken hafif pasif mDNS taraması: aynı ağdaki kayıtlı tahtayı "Bu ağda" olarak işaretler
    // ve değişen DHCP IP'sini kullanıcı tıklamadan önce sessizce günceller.
    val passiveActive = screen == Screen.HOME &&
        (state is MirrorState.Idle || state is MirrorState.Ended) &&
        boards.isNotEmpty() &&
        AppPermissions.networkGranted(context)
    DisposableEffect(passiveActive) {
        if (!passiveActive) {
            passiveDiscovery.stop()
            onDispose { }
        } else {
            passiveDiscovery.browse(
                onUpdate = { found ->
                    val currentBoards = boardStore.list()
                    val online = HashSet<Bytes>()
                    var hostsChanged = false
                    for (b in currentBoards) {
                        val hit = found.firstOrNull { BoardIdentity.matchesPrefix(b.fingerprint, it.fingerprintPrefix) }
                        if (hit != null) {
                            online += b.fingerprint
                            val merged = BoardStore.mergeHosts(null, hit.hosts, b.hosts)
                            if (merged != b.hosts || hit.port != b.port) {
                                boardStore.updateHosts(b.fingerprint, hit.hosts, hit.port)
                                hostsChanged = true
                            }
                        }
                    }
                    onlineFingerprints = online
                    if (hostsChanged) boardsVersion++
                },
            )
            onDispose { passiveDiscovery.stop() }
        }
    }

    val orderedBoards = remember(boards, onlineFingerprints) {
        if (onlineFingerprints.isEmpty()) {
            boards
        } else {
            val (online, offline) = boards.partition { it.fingerprint in onlineFingerprints }
            online + offline
        }
    }

    fun refreshDiscovery() {
        discovered = emptyList()
        discoveryFailed = false
        discovery.browse(
            onUpdate = {
                discovered = it
                discoveryFailed = false
            },
            onFailed = { discoveryFailed = true },
        )
    }

    fun openCodeFlow() {
        if (discovery.usesSystemPicker) {
            discovery.pick(
                onPicked = { b ->
                    selected = b
                    MirrorController.dismiss()
                    screen = Screen.PIN
                },
                onCancel = { },
            )
        } else {
            screen = Screen.DISCOVER
            refreshDiscovery()
        }
    }

    fun leaveConnectScreen() {
        if (state is MirrorState.Ended) {
            MirrorController.dismiss()
        } else if (state !is MirrorState.Idle && state !is MirrorState.Mirroring) {
            MirrorController.stop()
        }
        screen = Screen.HOME
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val board = pendingBoard
        when {
            pendingManualIp && AppPermissions.networkGranted(context) -> {
                pendingManualIp = false
                permissionsDenied = false
                MirrorController.dismiss()
                screen = Screen.MANUAL_IP
            }
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
            board == null && !pendingCode && !pendingManualIp && AppPermissions.requiredGranted(context) -> {
                permissionsDenied = false
                screen = Screen.SCANNER
            }
            else -> permissionsDenied = true
        }
    }
    // Sound is off by default, so its permission is asked for when the user turns it on.
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
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
    // Yansıtma başlayınca kod/IP ekranlarından otomatik olarak canlı kontrol ekranına geçilir.
    LaunchedEffect(state) {
        if (state !is MirrorState.AwaitingConsent && consentInFlight) {
            consentInFlight = false
        }
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
        } else if (state is MirrorState.Mirroring &&
            (screen == Screen.PIN || screen == Screen.MANUAL_IP || screen == Screen.DISCOVER)
        ) {
            screen = Screen.HOME
        }
    }

    fun startScan() {
        MirrorController.dismiss()
        pendingBoard = null
        pendingCode = false
        pendingManualIp = false
        screen = if (AppPermissions.requiredGranted(context)) Screen.SCANNER else Screen.PERMISSIONS
    }

    fun startCode() {
        MirrorController.dismiss()
        pendingBoard = null
        pendingManualIp = false
        if (AppPermissions.networkGranted(context)) {
            openCodeFlow()
        } else {
            pendingCode = true
            screen = Screen.PERMISSIONS
        }
    }

    fun startManualIp() {
        MirrorController.dismiss()
        pendingBoard = null
        pendingCode = false
        if (AppPermissions.networkGranted(context)) {
            screen = Screen.MANUAL_IP
        } else {
            pendingManualIp = true
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
            boards = orderedBoards,
            onlineFingerprints = onlineFingerprints,
            versionName = BuildConfig.VERSION_NAME,
            updateInfo = if (updateDismissed) null else updateInfo,
            onUpdateNow = { updateInfo?.let { UpdateChecker.openUpdateDestination(context, it) } },
            onUpdateSkip = {
                updateInfo?.let { settings.skippedUpdateVersion = it.latestVersion }
                updateInfo = null
                updateDismissed = true
            },
            onUpdateDismiss = { updateDismissed = true },
            actions = HomeActions(
                onScan = ::startScan,
                onCode = ::startCode,
                onManualIp = ::startManualIp,
                onBoard = ::connectRemembered,
                onToggleFavorite = { b ->
                    boardStore.setFavorite(b.fingerprint, !b.favorite)
                    boardsVersion++
                },
                onRename = { b, newName ->
                    boardStore.setNickname(b.fingerprint, newName)
                    boardsVersion++
                },
                onForget = { b ->
                    boardStore.remove(b.fingerprint)
                    boardsVersion++
                },
                onRetry = { MirrorController.retryLast(context) },
                onStop = MirrorController::stop,
                onPause = MirrorController::pause,
                onResume = MirrorController::resume,
                onDismiss = MirrorController::dismiss,
                onSettings = { screen = Screen.SETTINGS },
                onGuide = { screen = Screen.GUIDE },
            ),
        )
        Screen.GUIDE -> GuideScreen(onScan = ::startScan, onCode = ::startCode, onBack = { screen = Screen.HOME })
        Screen.PERMISSIONS -> {
            val networkOnly = pendingBoard != null || pendingCode || pendingManualIp
            PermissionScreen(
                missing = AppPermissions.missing(context, settings.audioEnabled).filter {
                    !networkOnly || it.permission == AppPermissions.ACCESS_LOCAL_NETWORK
                },
                deniedBefore = permissionsDenied,
                onRequest = {
                    val wanted = AppPermissions.missing(context, settings.audioEnabled).filter {
                        !networkOnly || it.permission == AppPermissions.ACCESS_LOCAL_NETWORK
                    }
                    permissionLauncher.launch(wanted.map { it.permission }.toTypedArray())
                },
                onBack = {
                    pendingCode = false
                    pendingManualIp = false
                    pendingBoard = null
                    screen = Screen.HOME
                },
            )
        }
        Screen.DISCOVER -> DiscoverScreen(
            boards = discovered,
            remembered = boards,
            discoveryFailed = discoveryFailed,
            onPick = { b ->
                discovery.stop()
                selected = b
                MirrorController.dismiss()
                screen = Screen.PIN
            },
            onRefresh = ::refreshDiscovery,
            onManualIp = {
                discovery.stop()
                startManualIp()
            },
            onScanQr = {
                discovery.stop()
                startScan()
            },
            onBack = {
                discovery.stop()
                screen = Screen.HOME
            },
        )
        Screen.PIN -> selected?.let { b ->
            val rememberedMatch = remember(boards, b) {
                boards.firstOrNull { BoardIdentity.matchesPrefix(it.fingerprint, b.fingerprintPrefix) }
            }
            PinScreen(
                board = b,
                remembered = rememberedMatch,
                state = state,
                onConnect = { pin ->
                    MirrorController.dismiss()
                    MirrorController.connect(context, ConnectTarget.Discovered(b, pin))
                },
                onCancel = MirrorController::stop,
                onRetry = { MirrorController.retryLast(context) },
                onManualIp = {
                    leaveConnectScreen()
                    startManualIp()
                },
                onGuide = {
                    MirrorController.dismiss()
                    screen = Screen.GUIDE
                },
                onBack = ::leaveConnectScreen,
            )
        } ?: run { screen = Screen.HOME }
        Screen.MANUAL_IP -> ManualIpScreen(
            recentIps = remember(boards) { boardStore.recentIps() },
            state = state,
            onConnect = { host, port, pin ->
                MirrorController.dismiss()
                MirrorController.connect(context, ConnectTarget.Manual(host, port, pin))
            },
            onCancel = MirrorController::stop,
            onRetry = { MirrorController.retryLast(context) },
            onGuide = {
                MirrorController.dismiss()
                screen = Screen.GUIDE
            },
            onBack = ::leaveConnectScreen,
        )
        Screen.LATENCY -> LatencyScreen(onBack = { screen = Screen.SETTINGS })
        Screen.SCANNER -> ScannerScreen(
            onResult = { qr ->
                screen = Screen.HOME
                MirrorController.connect(context, ConnectTarget.Qr(qr))
            },
            onCode = ::startCode,
            onManualIp = ::startManualIp,
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
                onAudioChange = { on ->
                    settings.audioEnabled = on
                    if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onBack = { screen = Screen.HOME },
                mirroring = state is MirrorState.Mirroring,
                versionName = BuildConfig.VERSION_NAME,
                autoCheckUpdates = settings.autoCheckUpdates,
                onAutoCheckUpdatesChange = { settings.autoCheckUpdates = it },
                checkingUpdates = checkingUpdates,
                updateStatus = updateStatus,
                availableUpdate = updateInfo,
                onCheckUpdatesNow = {
                    if (!checkingUpdates) {
                        checkingUpdates = true
                        updateStatus = UpdateStatusUi.Checking
                        scope.launch {
                            when (val res = UpdateChecker.checkNow(context, settings)) {
                                is ManualCheckResult.Available -> {
                                    updateInfo = res.info
                                    updateDismissed = false
                                    updateStatus = UpdateStatusUi.Available(res.info.latestVersion)
                                }
                                is ManualCheckResult.UpToDate -> {
                                    updateInfo = null
                                    updateStatus = UpdateStatusUi.UpToDate(res.currentVersion)
                                }
                                ManualCheckResult.Failed -> {
                                    updateStatus = UpdateStatusUi.Failed
                                }
                            }
                            checkingUpdates = false
                        }
                    }
                },
                onOpenUpdate = { updateInfo?.let { UpdateChecker.openUpdateDestination(context, it) } },
            )
        }
    }
}
