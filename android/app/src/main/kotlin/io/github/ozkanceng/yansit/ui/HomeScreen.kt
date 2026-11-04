package io.github.ozkanceng.yansit.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.ozkanceng.yansit.stream.MirrorController
import io.github.ozkanceng.yansit.data.AppSettings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.stream.AudioState
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

/** Ana ekran eylemleri. */
class HomeActions(
    val onScan: () -> Unit,
    val onCode: () -> Unit,
    val onBoard: (RememberedBoard) -> Unit,
    val onForget: (RememberedBoard) -> Unit,
    val onStop: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onDismiss: () -> Unit,
    val onSettings: () -> Unit,
    val onGuide: () -> Unit,
)

/**
 * Ana ekran. Boştayken bağlanma seçenekleri ve kısa rehber; bağlantı sürerken tek bir durum sütunu gösterilir.
 * Geniş ekranda (yatay tablet) bağlanma görünümü iki sütuna ayrılır.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: MirrorState, boards: List<RememberedBoard>, versionName: String, actions: HomeActions) {
    val idle = state is MirrorState.Idle || state is MirrorState.Ended
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.primary) },
                actions = {
                    if (idle) {
                        IconButton(onClick = actions.onGuide) {
                            Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.action_guide))
                        }
                        IconButton(onClick = actions.onSettings) {
                            Icon(Icons.Rounded.Settings, stringResource(R.string.action_settings))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (idle) {
                ConnectView(state, boards, versionName, actions, wide = maxWidth >= WideLayoutWidth)
            } else {
                SessionView(state, actions)
            }
        }
    }
}

/** Bağlantı yokken gösterilen görünüm: hata kartı, bağlanma seçenekleri, kısa adımlar ve rehber bağlantısı. */
@Composable
private fun ConnectView(
    state: MirrorState,
    boards: List<RememberedBoard>,
    versionName: String,
    actions: HomeActions,
    wide: Boolean,
) {
    val ended = state as? MirrorState.Ended
    val hero: @Composable () -> Unit = { Hero() }
    val choices: @Composable () -> Unit = {
        if (ended != null) {
            EndedCard(ended.error, actions.onGuide, Modifier.fillMaxWidth())
            TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_ok))
            }
        }
        ConnectChoices(boards, actions)
    }
    val quickSteps: @Composable () -> Unit = {
        if (boards.isEmpty() && ended?.error == null) {
            SectionCard(stringResource(R.string.guide_quick_title), Modifier.fillMaxWidth()) {
                StepList(GuideSteps.qr)
            }
        }
    }
    val guideLink: @Composable () -> Unit = {
        NavigationCard(
            title = stringResource(R.string.action_guide),
            subtitle = stringResource(R.string.guide_link_desc),
            icon = Icons.AutoMirrored.Rounded.HelpOutline,
            onClick = actions.onGuide,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val version: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.home_version, versionName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (wide) {
        Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                hero()
                guideLink()
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                choices()
                quickSteps()
                version()
            }
        }
    } else {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hata varken önce çözüm görünsün.
            if (ended?.error == null) hero()
            choices()
            quickSteps()
            guideLink()
            version()
        }
    }
}

@Composable
private fun Hero() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MirrorIllustration(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Hatırlanan tahtalar (tek dokunuş) + QR ile yeni tahta. Tahta yoksa QR birincil eylemdir. */
@Composable
private fun ConnectChoices(boards: List<RememberedBoard>, actions: HomeActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (boards.isEmpty()) {
            BigButton(R.string.action_connect, Icons.Rounded.QrCodeScanner, actions.onScan, Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.boards_title), style = MaterialTheme.typography.titleLarge)
            for (b in boards) BoardRow(b, actions)
            Text(
                stringResource(R.string.boards_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                ButtonLabel(R.string.action_scan_qr, Icons.Rounded.QrCodeScanner)
            }
        }
        OutlinedButton(onClick = actions.onCode, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            ButtonLabel(R.string.action_code, Icons.Rounded.Pin)
        }
        CameraModeRow()
    }
}

/** One switch: the next connection sends the camera instead of the screen. */
@Composable
private fun CameraModeRow() {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    var on by remember { mutableStateOf(settings.cameraMode) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .toggleable(value = on, role = Role.Switch) {
                on = it
                settings.cameraMode = it
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.camera_mode), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.camera_mode_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun BoardRow(board: RememberedBoard, actions: HomeActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavigationCard(
            title = board.name,
            icon = Icons.Rounded.Tv,
            onClick = { actions.onBoard(board) },
            modifier = Modifier.weight(1f),
        )
        val forgetDesc = stringResource(R.string.board_forget_desc, board.name)
        TextButton(
            onClick = { actions.onForget(board) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = forgetDesc },
        ) { Text(stringResource(R.string.board_forget)) }
    }
}

/** Bağlantı kurulurken ve yansıtma sürerken gösterilen sütun. */
@Composable
private fun SessionView(state: MirrorState, actions: HomeActions) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val width = Modifier.fillMaxWidth()
        when (state) {
            is MirrorState.Connecting -> Progress(stringResource(R.string.state_connecting, state.board))
            is MirrorState.WaitingApproval -> Progress(stringResource(R.string.state_waiting_approval, state.board))
            is MirrorState.AwaitingConsent ->
                Progress(stringResource(R.string.state_awaiting_consent), stringResource(R.string.err_consent_body))
            is MirrorState.Starting -> Progress(stringResource(R.string.state_starting, state.board))
            is MirrorState.Mirroring -> {
                MirroringCard(state, width)
                if (state.paused) {
                    BigButton(R.string.action_resume, Icons.Rounded.PlayArrow, actions.onResume, width)
                } else {
                    FilledTonalButton(onClick = actions.onPause, modifier = width.heightIn(min = 64.dp)) {
                        ButtonLabel(R.string.action_pause, Icons.Rounded.Pause)
                    }
                }
                BigButton(R.string.action_stop_mirroring, Icons.Rounded.Stop, actions.onStop, width, destructive = true)
            }
            MirrorState.Idle, is MirrorState.Ended -> Unit
        }
        if (state is MirrorState.Connecting || state is MirrorState.WaitingApproval || state is MirrorState.Starting) {
            OutlinedButton(onClick = actions.onStop, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun ButtonLabel(@StringRes label: Int, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null)
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BigButton(
    @StringRes label: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Progress(text: String, hint: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MirroringCard(state: MirrorState.Mirroring, modifier: Modifier) {
    Card(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Tv)
                Text(
                    stringResource(R.string.state_mirroring, state.board),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                state.reconnecting -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.state_reconnecting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                state.paused -> Text(
                    stringResource(R.string.state_paused),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                state.stats != null -> {
                    QualityLabel(state.stats)
                    StatGrid(state.stats)
                }
            }
            state.cameraSize?.let { size ->
                CameraPreview(size, MirrorController::setCameraPreview, Modifier.fillMaxWidth())
                if (state.canSwitchCamera) {
                    OutlinedButton(
                        onClick = MirrorController::switchCamera,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) { Text(stringResource(R.string.action_switch_camera)) }
                }
            }
            if (!state.reconnecting) {
                Text(
                    stringResource(audioText(state.audio)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.audio == AudioState.ON) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                stringResource(R.string.mirroring_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatGrid(stats: MirrorStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(R.string.stat_latency, stringResource(R.string.stat_latency_value, stats.latencyMs?.toString() ?: "–"), Modifier.weight(1f))
            StatTile(R.string.stat_fps, stats.fps.toInt().toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(R.string.stat_bitrate, stringResource(R.string.stat_bitrate_value, stats.bitrateBps / 1_000_000.0), Modifier.weight(1f))
            StatTile(R.string.stat_resolution, stringResource(R.string.stat_resolution_value, stats.width, stats.height), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(@StringRes label: Int, value: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun EndedCard(error: MirrorError?, onGuide: () -> Unit, modifier: Modifier) {
    val (title, body) = errorText(error)
    Card(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = if (error == null) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            // Okul ağı istemci yalıtımı yapıyorsa en güvenilir yol tabletin hotspot'u (RISKS R1).
            if (error == MirrorError.UNREACHABLE || error == MirrorError.DIFFERENT_NETWORK) {
                Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.titleMedium)
                StepList(GuideSteps.hotspot)
                TextButton(onClick = onGuide, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_guide), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** Bağlantı kalitesi: yakalama→ekran gecikmesine göre (hedef ≤ 80 ms, kabul ≤ 120 ms). */
@Composable
private fun QualityLabel(stats: MirrorStats) {
    val latency = stats.latencyMs ?: return
    val (label, color) = when {
        stats.autoLowered -> R.string.quality_auto_lowered to MaterialTheme.colorScheme.secondary
        latency <= 120 -> R.string.quality_good to MaterialTheme.colorScheme.tertiary
        latency <= 250 -> R.string.quality_fair to MaterialTheme.colorScheme.secondary
        else -> R.string.quality_poor to MaterialTheme.colorScheme.error
    }
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = color)
}

private fun audioText(a: AudioState): Int = when (a) {
    AudioState.ON -> R.string.audio_on
    AudioState.OFF_SETTING -> R.string.audio_off_setting
    AudioState.OFF_BOARD -> R.string.audio_off_board
    AudioState.OFF_PERMISSION -> R.string.audio_off_permission
    AudioState.OFF_CAMERA -> R.string.audio_off_camera
    AudioState.UNSUPPORTED -> R.string.audio_unsupported
    AudioState.FAILED -> R.string.audio_failed
}

/** Hata türü → (başlık, açıklama). Açıklama öğretmenin ne yapacağını söyler. */
private fun errorText(error: MirrorError?): Pair<Int, Int?> = when (error) {
    null -> R.string.ended_by_user to null
    MirrorError.UNREACHABLE -> R.string.err_unreachable to R.string.err_unreachable_body
    MirrorError.DIFFERENT_NETWORK -> R.string.err_different_network to R.string.err_different_network_body
    MirrorError.REFUSED -> R.string.err_refused to R.string.err_refused_body
    MirrorError.WRONG_BOARD -> R.string.err_wrong_board to R.string.err_wrong_board_body
    MirrorError.AUTH_FAILED -> R.string.err_auth to R.string.err_auth_body
    MirrorError.WRONG_PIN -> R.string.err_wrong_pin to R.string.err_wrong_pin_body
    MirrorError.BOARD_FORGOT_TABLET -> R.string.err_board_forgot to R.string.err_board_forgot_body
    MirrorError.BUSY_DENIED -> R.string.err_busy_denied to R.string.err_busy_denied_body
    MirrorError.BUSY_TIMEOUT -> R.string.err_busy_timeout to R.string.err_busy_timeout_body
    MirrorError.RATE_LIMITED -> R.string.err_rate_limited to R.string.err_rate_limited_body
    MirrorError.LOCKED_OUT -> R.string.err_locked_out to R.string.err_locked_out_body
    MirrorError.INCOMPATIBLE -> R.string.err_incompatible to R.string.err_incompatible_body
    MirrorError.NO_ENCODER -> R.string.err_no_encoder to R.string.err_no_encoder_body
    MirrorError.CONSENT_DENIED -> R.string.err_consent to R.string.err_consent_body
    MirrorError.CONNECTION_LOST -> R.string.err_connection_lost to R.string.err_connection_lost_body
    MirrorError.ENCODER_FAILED -> R.string.err_encoder to R.string.err_encoder_body
    MirrorError.STOPPED_BY_BOARD -> R.string.err_stopped_by_board to null
    MirrorError.BOARD_CLOSED -> R.string.err_board_closed to null
    MirrorError.TAKEN_OVER -> R.string.err_taken_over to R.string.err_taken_over_body
    MirrorError.CAMERA_FAILED -> R.string.err_camera to R.string.err_camera_body
    MirrorError.PROJECTION_ENDED -> R.string.err_projection_ended to R.string.err_projection_ended_body
    MirrorError.INTERNAL -> R.string.err_internal to null
}

private val previewActions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
private val previewBoard = RememberedBoard(
    "7-A Sınıfı", listOf("192.168.1.20"), 47800, Bytes.fromHex("00".repeat(32)), Bytes.fromHex("00".repeat(16)),
    Bytes.fromHex("00".repeat(32)), 0,
)

@Preview(showBackground = true, widthDp = 800, heightDp = 700)
@Composable
private fun HomeIdlePreview() {
    YansitTheme { HomeScreen(MirrorState.Idle, listOf(previewBoard), "0.1.0", previewActions) }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun HomeMirroringPreview() {
    YansitTheme {
        HomeScreen(
            MirrorState.Mirroring("7-A Sınıfı", MirrorStats(42, 59.8, 16_000_000, 0, 1728, 1072)),
            emptyList(), "0.1.0", previewActions,
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 700)
@Composable
private fun HomeErrorPreview() {
    YansitTheme { HomeScreen(MirrorState.Ended("7-A", MirrorError.DIFFERENT_NETWORK), emptyList(), "0.1.0", previewActions) }
}
