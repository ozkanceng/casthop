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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import io.github.ozkanceng.yansit.sender.MirrorStats
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

/** Ana ekran: tek büyük eylem düğmesi ve duruma göre bilgi kartı. */
@Composable
fun HomeScreen(
    state: MirrorState,
    versionName: String,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
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
            val contentWidth = Modifier.widthIn(max = 520.dp).fillMaxWidth()
            when (state) {
                MirrorState.Idle -> {
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = contentWidth,
                    )
                    BigButton(R.string.action_connect, onConnect, contentWidth)
                }
                is MirrorState.Connecting -> Progress(stringResource(R.string.state_connecting, state.board), contentWidth)
                is MirrorState.WaitingApproval -> Progress(stringResource(R.string.state_waiting_approval, state.board), contentWidth)
                is MirrorState.AwaitingConsent -> Progress(stringResource(R.string.state_awaiting_consent), contentWidth)
                is MirrorState.Starting -> Progress(stringResource(R.string.state_starting, state.board), contentWidth)
                is MirrorState.Mirroring -> {
                    MirroringCard(state.board, state.stats, contentWidth)
                    BigButton(R.string.action_stop_mirroring, onStop, contentWidth, destructive = true)
                }
                is MirrorState.Ended -> {
                    EndedCard(state.error, contentWidth)
                    BigButton(R.string.action_connect, onConnect, contentWidth)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
                }
            }
            if (state is MirrorState.Connecting || state is MirrorState.WaitingApproval || state is MirrorState.Starting) {
                OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            if (state is MirrorState.Idle || state is MirrorState.Ended) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 56.dp)) {
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
private fun MirroringCard(board: String, stats: MirrorStats?, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.state_mirroring, board), style = MaterialTheme.typography.titleLarge)
            if (stats != null) {
                Text(
                    stringResource(
                        R.string.mirroring_stats,
                        stats.latencyMs?.toString() ?: "–",
                        stats.fps.toInt(),
                        stats.bitrateBps / 1_000_000.0,
                        stats.width,
                        stats.height,
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
        }
    }
}

/** Hata türü → (başlık, açıklama). Açıklama öğretmenin ne yapacağını söyler. */
private fun errorText(error: MirrorError?): Pair<Int, Int?> = when (error) {
    null -> R.string.ended_by_user to null
    MirrorError.UNREACHABLE -> R.string.err_unreachable to R.string.err_unreachable_body
    MirrorError.REFUSED -> R.string.err_refused to R.string.err_refused_body
    MirrorError.WRONG_BOARD -> R.string.err_wrong_board to R.string.err_wrong_board_body
    MirrorError.AUTH_FAILED -> R.string.err_auth to R.string.err_auth_body
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

@Preview(showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun HomeMirroringPreview() {
    YansitTheme {
        HomeScreen(
            MirrorState.Mirroring("7-A Sınıfı", MirrorStats(42, 59.8, 16_000_000, 0, 1728, 1072)),
            "0.1.0", {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun HomeErrorPreview() {
    YansitTheme { HomeScreen(MirrorState.Ended("7-A", MirrorError.UNREACHABLE), "0.1.0", {}, {}, {}, {}) }
}
