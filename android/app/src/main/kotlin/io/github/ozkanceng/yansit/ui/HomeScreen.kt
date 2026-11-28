package io.github.ozkanceng.yansit.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.data.AppSettings
import io.github.ozkanceng.yansit.data.BoardIdentity
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.data.UpdateBannerInfo
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.ConnectionQuality
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.stream.AudioState
import io.github.ozkanceng.yansit.stream.MirrorController
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.stream.NetworkDiagnostics
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

/** Ana ekran eylemleri. */
class HomeActions(
    val onScan: () -> Unit,
    val onCode: () -> Unit,
    val onManualIp: () -> Unit,
    val onBoard: (RememberedBoard) -> Unit,
    val onToggleFavorite: (RememberedBoard) -> Unit,
    val onRename: (RememberedBoard, String?) -> Unit,
    val onForget: (RememberedBoard) -> Unit,
    val onRetry: () -> Unit,
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
fun HomeScreen(
    state: MirrorState,
    boards: List<RememberedBoard>,
    versionName: String,
    actions: HomeActions,
    onlineFingerprints: Set<Bytes> = emptySet(),
    updateInfo: UpdateBannerInfo? = null,
    onUpdateNow: () -> Unit = {},
    onUpdateSkip: () -> Unit = {},
    onUpdateDismiss: () -> Unit = {},
) {
    val idle = state is MirrorState.Idle || state is MirrorState.Ended
    var renaming by remember { mutableStateOf<RememberedBoard?>(null) }
    var showingUpdateSheet by remember { mutableStateOf(false) }

    if (showingUpdateSheet && updateInfo != null) {
        UpdateDetailsSheet(
            info = updateInfo,
            onUpdateNow = {
                showingUpdateSheet = false
                onUpdateNow()
            },
            onSkipVersion = {
                showingUpdateSheet = false
                onUpdateSkip()
            },
            onDismiss = { showingUpdateSheet = false },
        )
    }

    renaming?.let { board ->
        RenameBoardDialog(
            rawName = board.name,
            currentNickname = board.nickname,
            onSave = { newName ->
                actions.onRename(board, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

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
                ConnectView(
                    state = state,
                    boards = boards,
                    onlineFingerprints = onlineFingerprints,
                    versionName = versionName,
                    actions = actions,
                    onRequestRename = { renaming = it },
                    wide = maxWidth >= WideLayoutWidth,
                    updateInfo = updateInfo,
                    onOpenUpdateDetails = { showingUpdateSheet = true },
                    onUpdateNow = onUpdateNow,
                    onUpdateDismiss = onUpdateDismiss,
                )
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
    onlineFingerprints: Set<Bytes>,
    versionName: String,
    actions: HomeActions,
    onRequestRename: (RememberedBoard) -> Unit,
    wide: Boolean,
    updateInfo: UpdateBannerInfo? = null,
    onOpenUpdateDetails: () -> Unit = {},
    onUpdateNow: () -> Unit = {},
    onUpdateDismiss: () -> Unit = {},
) {
    val ended = state as? MirrorState.Ended
    val updateBanner: @Composable () -> Unit = {
        UpdateBanner(
            info = updateInfo,
            onOpenDetails = onOpenUpdateDetails,
            onUpdateNow = onUpdateNow,
            onDismiss = onUpdateDismiss,
        )
    }
    val hero: @Composable () -> Unit = { Hero() }
    val choices: @Composable () -> Unit = {
        if (ended != null) {
            ConnectionErrorCard(
                error = ended.error,
                detail = ended.detail,
                onRetry = actions.onRetry,
                onGuide = actions.onGuide,
                onManualIp = actions.onManualIp,
                onDismiss = actions.onDismiss,
            )
            if (ended.error != null) {
                TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
        ConnectChoices(boards, onlineFingerprints, actions, onRequestRename)
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
                updateBanner()
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
            updateBanner()
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
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MirrorIllustration(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                Text(
                    stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Hatırlanan tahtalar (favori + takma ad + IP/kimlik) + QR / Kod / Manuel IP bağlanma seçenekleri. */
@Composable
private fun ConnectChoices(
    boards: List<RememberedBoard>,
    onlineFingerprints: Set<Bytes>,
    actions: HomeActions,
    onRequestRename: (RememberedBoard) -> Unit,
) {
    val hasOnlineInfo = onlineFingerprints.isNotEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (boards.isEmpty()) {
            BigButton(R.string.action_connect, Icons.Rounded.QrCodeScanner, actions.onScan, Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.boards_title), style = MaterialTheme.typography.titleLarge)
            for (b in boards) {
                val online = b.fingerprint in onlineFingerprints
                BoardRow(
                    board = b,
                    online = online,
                    dimmed = hasOnlineInfo && !online,
                    onConnect = { actions.onBoard(b) },
                    onToggleFavorite = { actions.onToggleFavorite(b) },
                    onRename = { onRequestRename(b) },
                    onForget = { actions.onForget(b) },
                )
            }
            Text(
                stringResource(R.string.boards_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = actions.onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(R.string.action_scan_qr, Icons.Rounded.QrCodeScanner)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = actions.onCode, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                ButtonLabel(R.string.scanner_or_code, Icons.Rounded.Pin)
            }
            OutlinedButton(onClick = actions.onManualIp, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                ButtonLabel(R.string.scanner_or_ip, Icons.Rounded.Lan)
            }
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

/** Hatırlanan tahta kartı: tek dokunuşla bağlantı + IP/kimlik ayrımı + favori, takma ad ve unutma. */
@Composable
private fun BoardRow(
    board: RememberedBoard,
    online: Boolean,
    dimmed: Boolean,
    onConnect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val subtitle = remember(board) {
        BoardIdentity.subtitle(board.hosts, board.fingerprint, board.name, board.nickname)
    }
    val favDesc = stringResource(
        if (board.favorite) R.string.board_favorite_remove else R.string.board_favorite_add,
        board.displayName,
    )
    val renameDesc = stringResource(R.string.board_rename_desc, board.displayName)
    val forgetDesc = stringResource(R.string.board_forget_desc, board.displayName)

    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) 0.78f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = if (online) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f))
        } else {
            CardDefaults.outlinedCardBorder()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onConnect)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(boardKindIcon(board.kind))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            board.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (board.favorite) {
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = stringResource(R.string.board_favorite_tag),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (online) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.tertiary),
                                    )
                                    Text(
                                        stringResource(R.string.board_online_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = favDesc },
                    ) {
                        Icon(
                            if (board.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = null,
                            tint = if (board.favorite) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onRename,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = renameDesc },
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_rename))
                    }
                }
                TextButton(
                    onClick = onForget,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = forgetDesc },
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.board_forget))
                }
            }
        }
    }
}

/** Bağlantı kurulurken ve yansıtma sürerken gösterilen sütun. */
@Composable
private fun SessionView(state: MirrorState, actions: HomeActions) {
    val context = LocalContext.current
    var batteryBannerDismissed by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val width = Modifier.fillMaxWidth()
        when (state) {
            is MirrorState.Connecting,
            is MirrorState.WaitingApproval,
            is MirrorState.AwaitingConsent,
            is MirrorState.Starting,
            -> ConnectionStepperCard(state = state, onCancel = actions.onStop, modifier = width)

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
                OutlinedButton(onClick = actions.onSettings, modifier = width.heightIn(min = 56.dp)) {
                    ButtonLabel(R.string.action_settings, Icons.Rounded.Settings)
                }
                val batteryRestricted = remember(batteryBannerDismissed) {
                    !NetworkDiagnostics.isIgnoringBatteryOptimizations(context)
                }
                if (batteryRestricted && !batteryBannerDismissed) {
                    BatteryOptBanner(
                        onOpenSettings = {
                            runCatching { context.startActivity(NetworkDiagnostics.batteryOptimizationSettingsIntent()) }
                        },
                        onDismiss = { batteryBannerDismissed = true },
                    )
                }
            }

            MirrorState.Idle, is MirrorState.Ended -> Unit
        }
    }
}

@Composable
private fun BatteryOptBanner(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Rounded.BatteryAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    stringResource(R.string.battery_opt_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.battery_opt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.battery_opt_action))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(@StringRes label: Int, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null)
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                if (!state.reconnecting && !state.paused) {
                    LivePillBadge()
                }
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
                    QualityBadgeAndAlerts(state.stats, state.onHotspot)
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

/** Canlı yayın göstergesi: hafif nabızlı yeşil nokta + "Canlı" etiketi. */
@Composable
private fun LivePillBadge() {
    val pulse by rememberInfiniteTransition(label = "live-pill").animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "live-dot-alpha",
    )
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .alpha(pulse)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Text(
                stringResource(R.string.state_live),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** Canlı bağlantı kalitesi göstergesi (ping, kayıp, gecikme, otomatik kalite düşümü + 5 GHz önerisi). */
@Composable
private fun QualityBadgeAndAlerts(stats: MirrorStats, onHotspot: Boolean) {
    val (label, color) = when {
        stats.autoLowered -> R.string.quality_auto_lowered to MaterialTheme.colorScheme.secondary
        stats.quality == ConnectionQuality.POOR -> R.string.quality_poor to MaterialTheme.colorScheme.error
        stats.quality == ConnectionQuality.FAIR -> R.string.quality_fair to MaterialTheme.colorScheme.secondary
        else -> R.string.quality_good to MaterialTheme.colorScheme.tertiary
    }
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = color)

    val showWeakBanner = stats.autoLowered || stats.quality == ConnectionQuality.POOR
    if (showWeakBanner) {
        val context = LocalContext.current
        val on24GHz = remember(showWeakBanner) { NetworkDiagnostics.onWifi24GHz(context) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = if (stats.quality == ConnectionQuality.POOR) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (stats.quality == ConnectionQuality.POOR) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.quality_weak_banner),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when {
                    onHotspot -> Text(
                        stringResource(R.string.quality_hint_hotspot),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    on24GHz == true -> Text(
                        stringResource(R.string.quality_hint_24ghz),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatGrid(stats: MirrorStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                R.string.stat_latency,
                stringResource(R.string.stat_latency_value, stats.latencyMs?.toString() ?: "–"),
                Modifier.weight(1f),
            )
            StatTile(
                R.string.stat_ping,
                stringResource(R.string.stat_latency_value, stats.rttMs?.toString() ?: "–"),
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                R.string.stat_loss,
                stringResource(R.string.stat_loss_value, stats.lossPercent),
                Modifier.weight(1f),
            )
            StatTile(
                R.string.stat_bitrate,
                stringResource(R.string.stat_bitrate_value, stats.bitrateBps / 1_000_000.0),
                Modifier.weight(1f),
            )
        }
        StatTile(
            R.string.stat_resolution,
            stringResource(R.string.stat_resolution_value, stats.width, stats.height),
            Modifier.fillMaxWidth(),
        )
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

private fun audioText(a: AudioState): Int = when (a) {
    AudioState.ON -> R.string.audio_on
    AudioState.OFF_SETTING -> R.string.audio_off_setting
    AudioState.OFF_BOARD -> R.string.audio_off_board
    AudioState.OFF_PERMISSION -> R.string.audio_off_permission
    AudioState.OFF_CAMERA -> R.string.audio_off_camera
    AudioState.UNSUPPORTED -> R.string.audio_unsupported
    AudioState.FAILED -> R.string.audio_failed
}

private val previewActions = HomeActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})
private val previewBoard = RememberedBoard(
    name = "etap",
    hosts = listOf("192.168.43.102"),
    port = 47800,
    fingerprint = Bytes.fromHex("a4f2" + "00".repeat(30)),
    keyId = Bytes.fromHex("00".repeat(16)),
    pairKey = Bytes.fromHex("00".repeat(32)),
    lastUsedMs = 0,
    nickname = "9-B Matematik",
    favorite = true,
    kind = "board",
)

@Preview(showBackground = true, widthDp = 800, heightDp = 700)
@Composable
private fun HomeIdlePreview() {
    YansitTheme { HomeScreen(MirrorState.Idle, listOf(previewBoard), "0.1.0", previewActions) }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 640)
@Composable
private fun HomeMirroringPreview() {
    YansitTheme {
        HomeScreen(
            MirrorState.Mirroring(
                board = "9-B Matematik",
                stats = MirrorStats(42, 59.8, 16_000_000, 0, 1728, 1072, rttMs = 14, lossPercent = 0.0),
            ),
            emptyList(),
            "0.1.0",
            previewActions,
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 700)
@Composable
private fun HomeErrorPreview() {
    YansitTheme { HomeScreen(MirrorState.Ended("etap", MirrorError.CLIENT_ISOLATION), emptyList(), "0.1.0", previewActions) }
}
