package io.github.ozkanceng.casthop.tv.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ozkanceng.casthop.tv.R
import io.github.ozkanceng.casthop.tv.ReceiverController
import io.github.ozkanceng.casthop.tv.TvState
import io.github.ozkanceng.casthop.tv.ui.theme.LocalTvTokens
import io.github.ozkanceng.casthop.tv.ui.theme.TvColors
import io.github.ozkanceng.yansit.receiver.LiveStats
import io.github.ozkanceng.yansit.receiver.ReceiverPhase
import kotlinx.coroutines.delay

/**
 * The video surface always exists, at the bottom, so a decoder never waits for one. Everything else is drawn over it,
 * and every state but streaming is opaque.
 */
@Composable
internal fun CastHopTvScreen(
    controller: ReceiverController,
    localNetworkDenied: Boolean,
    onRequestLocalNetwork: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val phase = state.receiver.phase
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSurface(state, controller)
        when {
            phase == ReceiverPhase.WAITING && showSettings -> SettingsScreen(
                name = state.receiver.receiverName.ifEmpty { controller.receiverName },
                audioEnabled = state.receiver.audioEnabled,
                trusted = state.receiver.trusted,
                onRename = controller::rename,
                onAudioChanged = controller::setAudioEnabled,
                onForget = controller::forgetSender,
                onClose = { showSettings = false },
            )
            phase == ReceiverPhase.WAITING -> WaitingScreen(
                state = state,
                localNetworkDenied = localNetworkDenied,
                onRequestLocalNetwork = onRequestLocalNetwork,
                onOpenSettings = { showSettings = true },
            )
            phase == ReceiverPhase.STREAMING && !state.videoFailed -> StreamingOverlay(state, controller::stopSession)
            else -> StatusScreen(state, controller::stopSession)
        }
    }
    state.receiver.pendingSenderName?.let { pending ->
        ApprovalDialog(
            sender = pending,
            current = state.receiver.senderName,
            onApprove = controller::approvePending,
            onDeny = controller::denyPending,
        )
    }
}

/** Fits the stream inside the screen and keeps its proportions. */
@Composable
private fun VideoSurface(state: TvState, controller: ReceiverController) {
    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
        state.videoWidth.toFloat() / state.videoHeight
    } else {
        DEFAULT_ASPECT
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = controller.setSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = controller.setSurface(null)
                    },
                )
            }
        },
        modifier = Modifier.aspectRatio(ratio),
    )
}

/**
 * Nothing on screen but the picture until someone presses a key or taps. A key that does nothing is confusing, so
 * Back, OK and a tap all open the same controls.
 */
@Composable
private fun StreamingOverlay(state: TvState, onStop: () -> Unit) {
    val tokens = LocalTvTokens.current
    var controls by remember { mutableStateOf(false) }
    val catcherFocus = remember { FocusRequester() }
    val stopFocus = remember { FocusRequester() }
    BackHandler(enabled = !controls) { controls = true }
    BackHandler(enabled = controls) { controls = false }
    LaunchedEffect(controls) {
        if (controls) {
            stopFocus.requestFocus()
            delay(CONTROLS_VISIBLE_MS)
            controls = false
        } else {
            catcherFocus.requestFocus()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(catcherFocus)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controls = true },
            ),
    )
    if (controls) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = tokens.screenPaddingX, vertical = tokens.screenPaddingY),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Column(
                modifier = Modifier
                    .background(TvColors.SurfaceScrim, RoundedCornerShape(tokens.corner))
                    .padding(tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gapSmall),
            ) {
                Text(
                    text = state.receiver.senderName,
                    color = TvColors.OnBackground,
                    fontSize = tokens.heading,
                    fontWeight = FontWeight.SemiBold,
                )
                state.receiver.stats?.let { StatsLines(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    TvButton(
                        text = stringResource(R.string.action_stop_mirroring),
                        onClick = onStop,
                        modifier = Modifier.focusRequester(stopFocus),
                        primary = true,
                    )
                    TvButton(stringResource(R.string.action_resume_watching), { controls = false })
                }
            }
        }
    }
}

@Composable
private fun StatsLines(stats: LiveStats) {
    val tokens = LocalTvTokens.current
    val lines = listOf(
        if (stats.latencyMs >= 0) {
            stringResource(R.string.stats_latency, stats.latencyMs)
        } else {
            stringResource(R.string.stats_latency_unknown)
        },
        stringResource(R.string.stats_rate, stats.fps, stats.bitrateKbps),
        stringResource(R.string.stats_dropped, stats.dropped),
        stringResource(R.string.stats_decoder, stats.decoder),
    )
    lines.forEach { Text(text = it, color = TvColors.OnSurfaceMuted, fontSize = tokens.caption) }
}

/** Connecting, paused, reconnecting or an undecodable stream: one sentence and a way out. */
@Composable
private fun StatusScreen(state: TvState, onStop: () -> Unit) {
    val tokens = LocalTvTokens.current
    val sender = state.receiver.senderName
    val stopFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { stopFocus.requestFocus() }
    val title = when {
        state.videoFailed -> stringResource(R.string.state_video_failed)
        state.receiver.phase == ReceiverPhase.PAUSED -> stringResource(R.string.state_paused)
        state.receiver.phase == ReceiverPhase.RECONNECTING -> stringResource(R.string.state_reconnecting)
        else -> stringResource(R.string.state_connecting, sender)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = tokens.screenPaddingX, vertical = tokens.screenPaddingY),
        verticalArrangement = Arrangement.spacedBy(tokens.gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = TvColors.OnBackground,
            fontSize = tokens.title,
            lineHeight = tokens.title,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (state.receiver.phase == ReceiverPhase.PAUSED) {
            Text(
                text = stringResource(R.string.state_paused_detail, sender),
                color = TvColors.OnSurfaceMuted,
                fontSize = tokens.body,
                textAlign = TextAlign.Center,
            )
        }
        TvButton(
            text = stringResource(R.string.action_stop_mirroring),
            onClick = onStop,
            modifier = Modifier.focusRequester(stopFocus),
        )
    }
}

/** A second device wants the screen. Focus starts on Deny, so a stray OK press cannot end the running mirror. */
@Composable
private fun ApprovalDialog(sender: String, current: String, onApprove: () -> Unit, onDeny: () -> Unit) {
    val tokens = LocalTvTokens.current
    val denyFocus = remember { FocusRequester() }
    LaunchedEffect(sender) { denyFocus.requestFocus() }
    Dialog(onDismissRequest = onDeny, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            modifier = Modifier
                .widthIn(max = DIALOG_MAX_WIDTH.dp)
                .background(TvColors.Surface, RoundedCornerShape(tokens.corner))
                .padding(tokens.gap),
            verticalArrangement = Arrangement.spacedBy(tokens.gap),
        ) {
            Text(
                text = stringResource(R.string.approval_title),
                color = TvColors.OnBackground,
                fontSize = tokens.heading,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.approval_body, sender, current),
                color = TvColors.OnBackground,
                fontSize = tokens.body,
                lineHeight = tokens.body * DIALOG_LINE_HEIGHT,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                TvButton(
                    text = stringResource(R.string.action_deny),
                    onClick = onDeny,
                    modifier = Modifier.focusRequester(denyFocus),
                )
                TvButton(stringResource(R.string.action_approve), onApprove, primary = true)
            }
        }
    }
}

private const val DEFAULT_ASPECT = 16f / 9f
private const val CONTROLS_VISIBLE_MS = 10_000L
private const val DIALOG_MAX_WIDTH = 640
private const val DIALOG_LINE_HEIGHT = 1.25f
