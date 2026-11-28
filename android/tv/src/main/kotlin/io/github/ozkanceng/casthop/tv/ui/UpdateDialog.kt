package io.github.ozkanceng.casthop.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ozkanceng.casthop.tv.R
import io.github.ozkanceng.casthop.tv.TvUpdateChecker
import io.github.ozkanceng.casthop.tv.TvUpdateInfo
import io.github.ozkanceng.casthop.tv.ui.theme.LocalTvTokens
import io.github.ozkanceng.casthop.tv.ui.theme.TvColors

/** Android TV için D-Pad odak dostu, QR indirme kodlu şık güncelleme penceresi. */
@Composable
internal fun TvUpdateDialog(
    info: TvUpdateInfo,
    onOpenBrowser: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalTvTokens.current
    val context = LocalContext.current
    val hasBrowser = remember(info.url) { TvUpdateChecker.canOpenBrowser(context, info.url) }
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { primaryFocus.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = tokens.screenPaddingX, vertical = tokens.screenPaddingY)
                .widthIn(max = 760.dp)
                .background(TvColors.Surface, RoundedCornerShape(tokens.corner))
                .border(2.dp, TvColors.Primary.copy(alpha = 0.65f), RoundedCornerShape(tokens.corner))
                .padding(tokens.gap),
            verticalArrangement = Arrangement.spacedBy(tokens.gapSmall),
        ) {
            Text(
                text = stringResource(R.string.update_dialog_title),
                color = TvColors.OnBackground,
                fontSize = tokens.heading,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "v${info.currentVersion}  →  v${info.latestVersion}",
                color = TvColors.Primary,
                fontSize = tokens.body,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QrCode(
                    content = info.url,
                    description = stringResource(R.string.update_qr_desc),
                    modifier = Modifier
                        .size(168.dp)
                        .background(TvColors.QrLight, RoundedCornerShape(tokens.corner)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(tokens.gapSmall),
                ) {
                    if (info.notes.isNotBlank()) {
                        Text(
                            text = info.notes,
                            color = TvColors.OnBackground,
                            fontSize = tokens.body,
                            lineHeight = tokens.body * 1.25f,
                        )
                    }
                    Text(
                        text = stringResource(R.string.update_scan_hint),
                        color = TvColors.OnSurfaceMuted,
                        fontSize = tokens.caption,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = tokens.gapSmall),
                horizontalArrangement = Arrangement.spacedBy(tokens.gapSmall),
            ) {
                if (hasBrowser) {
                    TvButton(
                        text = stringResource(R.string.update_open_browser),
                        onClick = onOpenBrowser,
                        primary = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                    )
                }
                TvButton(
                    text = stringResource(R.string.update_later),
                    onClick = onDismiss,
                    primary = !hasBrowser,
                    modifier = if (!hasBrowser) Modifier.focusRequester(primaryFocus) else Modifier,
                )
                TvButton(
                    text = stringResource(R.string.update_skip_version),
                    onClick = onSkip,
                )
            }
        }
    }
}
