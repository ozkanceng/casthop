package io.github.ozkanceng.yansit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.data.BoardIdentity
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.stream.DiscoveredBoard
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.stream.NetworkDiagnostics
import kotlinx.coroutines.delay

internal fun boardKindIcon(kind: String?): ImageVector = when (kind) {
    "pc" -> Icons.Rounded.Computer
    "tablet" -> Icons.Rounded.Tablet
    "board" -> Icons.Rounded.CoPresent
    else -> Icons.Rounded.Tv
}

private fun MirrorState.isConnectingFlow(): Boolean =
    this is MirrorState.Connecting ||
        this is MirrorState.WaitingApproval ||
        this is MirrorState.AwaitingConsent ||
        this is MirrorState.Starting

/**
 * Ağdaki tahtalar keşif ekranı.
 * - Aynı adlı "etap" tahtalarını IP son hanesi + 4 haneli kimlik (`#XXXX`) + özel takma adla ayırt eder.
 * - Favori ve son bağlanılan tahtalar üstte çıkar.
 * - İlk 5 saniye shimmer iskelet gösterir; tahta bulunamazsa (hotspot/okul ağı mDNS engeli) otomatik olarak
 *   IP ile bağlan ve QR yedek kartını öne çıkarır.
 */
@Composable
fun DiscoverScreen(
    boards: List<DiscoveredBoard>,
    remembered: List<RememberedBoard>,
    discoveryFailed: Boolean,
    onPick: (DiscoveredBoard) -> Unit,
    onRefresh: () -> Unit,
    onManualIp: () -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val onHotspot = remember { NetworkDiagnostics.isHostingHotspot(context) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(searchGeneration, boards.isEmpty()) {
        if (boards.isNotEmpty()) {
            timedOut = false
        } else {
            timedOut = false
            delay(5_000)
            if (boards.isEmpty()) timedOut = true
        }
    }

    val sorted = remember(boards, remembered) { BoardIdentity.sortDiscovered(boards, remembered) }

    SubScreen(stringResource(R.string.discover_title), onBack) {
        if (onHotspot) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(
                        stringResource(R.string.discover_hotspot_badge),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.discover_searching),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    searchGeneration++
                    onRefresh()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_refresh))
            }
        }

        if (sorted.isEmpty()) {
            if (!timedOut && !discoveryFailed) {
                BoardSkeletonList(count = 3)
            }
            AnimatedVisibility(
                visible = timedOut || discoveryFailed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.discover_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.discover_empty_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onManualIp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Rounded.Lan, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_manual_ip), style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = onScanQr,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.guide_qr_action), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in sorted) {
                    val saved = remembered.firstOrNull { BoardIdentity.matchesPrefix(it.fingerprint, b.fingerprintPrefix) }
                    val displayTitle = saved?.displayName ?: b.name
                    val subtitle = BoardIdentity.subtitle(b.hosts, b.fingerprintPrefix, b.name, saved?.nickname)
                    NavigationCard(
                        title = displayTitle,
                        subtitle = subtitle,
                        icon = boardKindIcon(b.kind ?: saved?.kind),
                        badge = if (saved?.favorite == true) {
                            {
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = stringResource(R.string.board_favorite_tag),
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { onPick(b) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Her durumda mDNS yedeği olarak manuel IP ile bağlanma erişilebilir.
        NavigationCard(
            title = stringResource(R.string.discover_manual_cta),
            subtitle = stringResource(R.string.manual_ip_hint),
            icon = Icons.Rounded.Lan,
            onClick = onManualIp,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.discover_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Yeniden tasarlanan "Kod ile bağlan" ekranı:
 * - Seçili tahtanın kimlik kartı (ad + IP son hanesi + `#XXXX`).
 * - 6 ayrı OTP hücresi (3+3), canlı vurgu animasyonu, haptic + hata titreşimi, yapıştırma & autofill.
 * - 6. hanede otomatik bağlanma; ekran içi adım adım durum göstergesi ve tek dokunuşla "Tekrar dene".
 * - Tahtada kodun yerini gösteren minyatür illüstrasyon.
 */
@Composable
fun PinScreen(
    board: DiscoveredBoard,
    remembered: RememberedBoard?,
    state: MirrorState,
    onConnect: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onManualIp: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    var pin by rememberSaveable(board.fingerprintPrefix.toHex()) { mutableStateOf("") }
    var errorSignal by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val displayTitle = remembered?.displayName ?: board.name
    val subtitle = BoardIdentity.subtitle(board.hosts, board.fingerprintPrefix, board.name, remembered?.nickname)
    val ended = state as? MirrorState.Ended
    val connecting = state.isConnectingFlow()
    val pinError = ended?.error == MirrorError.WRONG_PIN || ended?.error == MirrorError.LOCKED_OUT

    LaunchedEffect(Unit) {
        if (!connecting) runCatching { focus.requestFocus() }
    }

    LaunchedEffect(ended) {
        if (ended?.error != null) {
            errorSignal++
            if (ended.error == MirrorError.WRONG_PIN) {
                delay(380)
                pin = ""
                runCatching { focus.requestFocus() }
            }
        }
    }

    val visualState = when {
        connecting -> OtpVisualState.SUBMITTING
        state is MirrorState.Mirroring -> OtpVisualState.SUCCESS
        pinError -> OtpVisualState.ERROR
        else -> OtpVisualState.IDLE
    }

    SubScreen(stringResource(R.string.pin_title, displayTitle), onBack, maxContentWidth = 520.dp) {
        // Seçili tahta kimlik özeti
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(boardKindIcon(board.kind ?: remembered?.kind))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AnimatedContent(targetState = connecting, label = "pin_flow_state") { inProgress ->
            if (inProgress) {
                ConnectionStepperCard(state = state, onCancel = onCancel)
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.pin_hint),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OtpCodeInput(
                        code = pin,
                        onCodeChange = { pin = it },
                        onComplete = { code -> onConnect(code) },
                        visualState = visualState,
                        enabled = true,
                        errorSignal = errorSignal,
                        focusRequester = focus,
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.otp_auto_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (pin.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    pin = ""
                                    runCatching { focus.requestFocus() }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                    }

                    if (ended?.error != null) {
                        ConnectionErrorCard(
                            error = ended.error,
                            onRetry = {
                                if (ended.error == MirrorError.WRONG_PIN) {
                                    pin = ""
                                    runCatching { focus.requestFocus() }
                                } else if (pin.length == 6) {
                                    onConnect(pin)
                                } else {
                                    onRetry()
                                }
                            },
                            onGuide = onGuide,
                            onManualIp = onManualIp,
                        )
                    }

                    Button(
                        onClick = { onConnect(pin) },
                        enabled = pin.length == 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    ) {
                        Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.titleMedium)
                    }

                    BoardCodeIllustrationCard()
                }
            }
        }
    }
}

/**
 * Manuel IP + 6 haneli OTP kod ile bağlanma ekranı (hotspot ve mDNS engelli okul ağları için kesin yedek).
 */
@Composable
fun ManualIpScreen(
    recentIps: List<String>,
    state: MirrorState,
    onConnect: (host: String, port: Int, pin: String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(recentIps.firstOrNull().orEmpty()) }
    var portText by rememberSaveable { mutableStateOf("47800") }
    var showPort by rememberSaveable { mutableStateOf(false) }
    var pin by rememberSaveable { mutableStateOf("") }
    var errorSignal by remember { mutableIntStateOf(0) }
    val otpFocus = remember { FocusRequester() }

    val cleanHost = host.trim()
    val hostValid = NetworkDiagnostics.isValidIpv4(cleanHost)
    val validPort = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val portValid = validPort != null
    val canEnterCode = hostValid && portValid
    val ended = state as? MirrorState.Ended
    val connecting = state.isConnectingFlow()
    val pinError = ended?.error == MirrorError.WRONG_PIN || ended?.error == MirrorError.LOCKED_OUT

    LaunchedEffect(ended) {
        if (ended?.error != null) {
            errorSignal++
            if (ended.error == MirrorError.WRONG_PIN) {
                delay(380)
                pin = ""
                runCatching { otpFocus.requestFocus() }
            }
        }
    }

    SubScreen(stringResource(R.string.manual_ip_title), onBack, maxContentWidth = 520.dp) {
        AnimatedContent(targetState = connecting, label = "manual_ip_state") { inProgress ->
            if (inProgress) {
                ConnectionStepperCard(state = state, onCancel = onCancel)
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.manual_ip_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = host,
                        onValueChange = { v ->
                            host = v.filter { it.isDigit() || it == '.' }.take(15)
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.manual_ip_label)) },
                        placeholder = { Text(stringResource(R.string.manual_ip_placeholder)) },
                        isError = host.isNotEmpty() && !hostValid,
                        supportingText = if (host.isNotEmpty() && !hostValid) {
                            { Text(stringResource(R.string.manual_ip_invalid)) }
                        } else {
                            null
                        },
                        trailingIcon = if (host.isNotEmpty()) {
                            {
                                IconButton(onClick = { host = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.action_clear))
                                }
                            }
                        } else {
                            null
                        },
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { if (canEnterCode) runCatching { otpFocus.requestFocus() } },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (recentIps.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.manual_ip_recent),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (recent in recentIps) {
                                    AssistChip(
                                        onClick = {
                                            host = recent
                                            runCatching { otpFocus.requestFocus() }
                                        },
                                        label = {
                                            Text(recent, fontFamily = FontFamily.Monospace)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { showPort = !showPort },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.manual_port_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showPort) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    AnimatedVisibility(visible = showPort) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { v -> portText = v.filter(Char::isDigit).take(5) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.manual_port_label)) },
                            isError = !portValid,
                            supportingText = if (!portValid) {
                                { Text(stringResource(R.string.manual_port_invalid)) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        stringResource(R.string.manual_code_section),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    OtpCodeInput(
                        code = pin,
                        onCodeChange = { pin = it },
                        onComplete = { code ->
                            if (hostValid && validPort != null) onConnect(cleanHost, validPort, code)
                        },
                        visualState = when {
                            pinError -> OtpVisualState.ERROR
                            state is MirrorState.Mirroring -> OtpVisualState.SUCCESS
                            else -> OtpVisualState.IDLE
                        },
                        enabled = canEnterCode,
                        errorSignal = errorSignal,
                        focusRequester = otpFocus,
                    )

                    if (ended?.error != null) {
                        ConnectionErrorCard(
                            error = ended.error,
                            onRetry = {
                                if (ended.error == MirrorError.WRONG_PIN) {
                                    pin = ""
                                    runCatching { otpFocus.requestFocus() }
                                } else if (hostValid && validPort != null && pin.length == 6) {
                                    onConnect(cleanHost, validPort, pin)
                                } else {
                                    onRetry()
                                }
                            },
                            onGuide = onGuide,
                        )
                    }

                    Button(
                        onClick = { if (hostValid && validPort != null) onConnect(cleanHost, validPort, pin) },
                        enabled = canEnterCode && pin.length == 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    ) {
                        Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.titleMedium)
                    }

                    BoardCodeIllustrationCard()
                }
            }
        }
    }
}
