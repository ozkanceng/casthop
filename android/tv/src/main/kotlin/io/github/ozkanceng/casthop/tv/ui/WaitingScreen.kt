package io.github.ozkanceng.casthop.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.ozkanceng.casthop.tv.Failure
import io.github.ozkanceng.casthop.tv.R
import io.github.ozkanceng.casthop.tv.TvState
import io.github.ozkanceng.casthop.tv.ui.theme.LocalTvTokens
import io.github.ozkanceng.casthop.tv.ui.theme.TvColors

/** Opaque, so the idle video surface underneath never shows. */
@Composable
internal fun WaitingScreen(
    state: TvState,
    localNetworkDenied: Boolean,
    onRequestLocalNetwork: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = LocalTvTokens.current
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { settingsFocus.requestFocus() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = tokens.screenPaddingX, vertical = tokens.screenPaddingY),
    ) {
        val qr: @Composable () -> Unit = { QrPanel(state) }
        val details: @Composable () -> Unit = {
            Details(state, localNetworkDenied, onRequestLocalNetwork, onOpenSettings, settingsFocus)
        }
        if (maxWidth >= maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                qr()
                Column(modifier = Modifier.weight(1f)) { details() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(tokens.gap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                qr()
                Column(modifier = Modifier.fillMaxWidth()) { details() }
            }
        }
    }
}

@Composable
private fun QrPanel(state: TvState) {
    val tokens = LocalTvTokens.current
    val uri = state.receiver.qrUri
    if (uri.isNotEmpty()) {
        QrCode(
            content = uri,
            description = stringResource(R.string.qr_description),
            modifier = Modifier
                .size(tokens.qrSize)
                .background(TvColors.QrLight, RoundedCornerShape(tokens.corner)),
        )
    }
}

@Composable
private fun Details(
    state: TvState,
    localNetworkDenied: Boolean,
    onRequestLocalNetwork: () -> Unit,
    onOpenSettings: () -> Unit,
    settingsFocus: FocusRequester,
) {
    val tokens = LocalTvTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(tokens.gapSmall)) {
        Text(
            text = stringResource(R.string.waiting_title),
            color = TvColors.OnBackground,
            fontSize = tokens.title,
            lineHeight = tokens.title * LINE_HEIGHT,
            fontWeight = FontWeight.Bold,
        )
        when {
            state.failure != null -> Problem(stringResource(failureText(state.failure)))
            localNetworkDenied -> {
                Problem(stringResource(R.string.local_network_denied))
                TvButton(stringResource(R.string.action_allow), onRequestLocalNetwork, primary = true)
            }
            !state.hasNetwork -> Problem(stringResource(R.string.waiting_no_network))
            state.receiver.pin.isEmpty() -> Body(stringResource(R.string.waiting_starting))
            else -> Instructions(state)
        }
        TvButton(
            text = stringResource(R.string.action_settings),
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = tokens.gapSmall).focusRequester(settingsFocus),
        )
    }
}

@Composable
private fun Instructions(state: TvState) {
    val tokens = LocalTvTokens.current
    Body(stringResource(R.string.waiting_step_app))
    Body(stringResource(R.string.waiting_step_scan))
    Text(
        text = stringResource(R.string.waiting_pin_label),
        color = TvColors.OnSurfaceMuted,
        fontSize = tokens.caption,
        modifier = Modifier.padding(top = tokens.gapSmall),
    )
    Text(
        text = state.receiver.pin.chunked(PIN_GROUP).joinToString(" "),
        color = TvColors.Primary,
        fontSize = tokens.pin,
        lineHeight = tokens.pin,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = stringResource(R.string.waiting_same_network),
        color = TvColors.OnSurfaceMuted,
        fontSize = tokens.caption,
    )
    state.receiver.hosts.firstOrNull()?.let { host ->
        Text(
            text = stringResource(R.string.waiting_address, host),
            color = TvColors.OnSurfaceMuted,
            fontSize = tokens.caption,
        )
    }
}

@Composable
private fun Body(text: String) {
    val tokens = LocalTvTokens.current
    Text(text = text, color = TvColors.OnBackground, fontSize = tokens.body, lineHeight = tokens.body * LINE_HEIGHT)
}

@Composable
private fun Problem(text: String) {
    val tokens = LocalTvTokens.current
    Text(text = text, color = TvColors.Error, fontSize = tokens.body, lineHeight = tokens.body * LINE_HEIGHT)
}

private fun failureText(failure: Failure): Int = when (failure) {
    Failure.PORT_IN_USE -> R.string.error_port_in_use
    Failure.IDENTITY -> R.string.error_identity
    Failure.NO_DECODER -> R.string.error_no_decoder
}

private const val LINE_HEIGHT = 1.25f
private const val PIN_GROUP = 3
