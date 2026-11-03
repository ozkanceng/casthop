package io.github.ozkanceng.yansit.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.data.RememberedBoard
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

/** Ana ekran eylemleri. */
class HomeActions(
    val onScan: () -> Unit,
    val onBoard: (RememberedBoard) -> Unit,
    val onForget: (RememberedBoard) -> Unit,
    val onStop: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onDismiss: () -> Unit,
    val onSettings: () -> Unit,
)

/** Ana ekran: tek büyük eylem düğmesi, hatırlanan tahtalar ve duruma göre bilgi kartı. */
@Composable
fun HomeScreen(state: MirrorState, boards: List<RememberedBoard>, versionName: String, actions: HomeActions) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val contentWidth = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            when (state) {
                MirrorState.Idle -> {
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = contentWidth,
                    )
                    ConnectChoices(boards, actions, contentWidth)
                }
                is MirrorState.Connecting -> Progress(stringResource(R.string.state_connecting, state.board), contentWidth)
                is MirrorState.WaitingApproval -> Progress(stringResource(R.string.state_waiting_approval, state.board), contentWidth)
                is MirrorState.AwaitingConsent -> Progress(stringResource(R.string.state_awaiting_consent), contentWidth)
                is MirrorState.Starting -> Progress(stringResource(R.string.state_starting, state.board), contentWidth)
                is MirrorState.Mirroring -> {
                    MirroringCard(state, contentWidth)
                    if (state.paused) {
                        BigButton(R.string.action_resume, actions.onResume, contentWidth)
                    } else {
                        FilledTonalButton(onClick = actions.onPause, modifier = contentWidth.heightIn(min = 64.dp)) {
                            Text(stringResource(R.string.action_pause), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    BigButton(R.string.action_stop_mirroring, actions.onStop, contentWidth, destructive = true)
                }
                is MirrorState.Ended -> {
                    EndedCard(state.error, contentWidth)
                    ConnectChoices(boards, actions, contentWidth)
                    TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_ok)) }
                }
            }
            if (state is MirrorState.Connecting || state is MirrorState.WaitingApproval || state is MirrorState.Starting) {
                OutlinedButton(onClick = actions.onStop, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            if (state is MirrorState.Idle || state is MirrorState.Ended) {
                OutlinedButton(onClick = actions.onSettings, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_settings))
                }
            }
            Text(
                text = stringResource(R.string.home_version, versionName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Hatırlanan tahtalar (tek dokunuş) + QR ile yeni tahta. Tahta yoksa yalnız büyük QR düğmesi. */
@Composable
private fun ConnectChoices(boards: List<RememberedBoard>, actions: HomeActions, modifier: Modifier) {
    if (boards.isEmpty()) {
        BigButton(R.string.action_connect, actions.onScan, modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.boards_title), style = MaterialTheme.typography.titleMedium)
        for (b in boards) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onBoard(b) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val forgetDesc = stringResource(R.string.board_forget_desc, b.name)
                TextButton(
                    onClick = { actions.onForget(b) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = forgetDesc },
                ) { Text(stringResource(R.string.board_forget)) }
            }
        }
        OutlinedButton(onClick = actions.onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(R.string.action_scan_qr), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.boards_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigButton(@StringRes label: Int, onClick: () -> Unit, modifier: Modifier, destructive: Boolean = false) {
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
        Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Progress(text: String, modifier: Modifier) {
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MirroringCard(state: MirrorState.Mirroring, modifier: Modifier) {
    Card(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.state_mirroring, state.board), style = MaterialTheme.typography.titleLarge)
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
                state.stats != null -> Text(
                    stringResource(
                        R.string.mirroring_stats,
                        state.stats.latencyMs?.toString() ?: "–",
                        state.stats.fps.toInt(),
                        state.stats.bitrateBps / 1_000_000.0,
                        state.stats.width,
                        state.stats.height,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun EndedCard(error: MirrorError?, modifier: Modifier) {
    val (title, body) = errorText(error)
    Card(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = if (error == null) CardDefaults.cardColors() else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            // Okul ağı istemci yalıtımı yapıyorsa en güvenilir yol tabletin hotspot'u (RISKS R1).
            if (error == MirrorError.UNREACHABLE || error == MirrorError.DIFFERENT_NETWORK) {
                Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.hotspot_steps), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Hata türü → (başlık, açıklama). Açıklama öğretmenin ne yapacağını söyler. */
private fun errorText(error: MirrorError?): Pair<Int, Int?> = when (error) {
    null -> R.string.ended_by_user to null
    MirrorError.UNREACHABLE -> R.string.err_unreachable to R.string.err_unreachable_body
    MirrorError.DIFFERENT_NETWORK -> R.string.err_different_network to R.string.err_different_network_body
    MirrorError.REFUSED -> R.string.err_refused to R.string.err_refused_body
    MirrorError.WRONG_BOARD -> R.string.err_wrong_board to R.string.err_wrong_board_body
    MirrorError.AUTH_FAILED -> R.string.err_auth to R.string.err_auth_body
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
    MirrorError.PROJECTION_ENDED -> R.string.err_projection_ended to R.string.err_projection_ended_body
    MirrorError.INTERNAL -> R.string.err_internal to null
}

private val previewActions = HomeActions({}, {}, {}, {}, {}, {}, {}, {})
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
