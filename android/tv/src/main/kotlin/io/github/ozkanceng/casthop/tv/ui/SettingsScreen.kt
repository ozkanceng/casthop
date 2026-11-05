package io.github.ozkanceng.casthop.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import io.github.ozkanceng.casthop.tv.BuildConfig
import io.github.ozkanceng.casthop.tv.R
import io.github.ozkanceng.casthop.tv.ui.theme.LocalTvTokens
import io.github.ozkanceng.casthop.tv.ui.theme.TvColors
import io.github.ozkanceng.yansit.receiver.TrustedEntry

@Composable
internal fun SettingsScreen(
    name: String,
    audioEnabled: Boolean,
    trusted: List<TrustedEntry>,
    onRename: (String) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onForget: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalTvTokens.current
    var renaming by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = tokens.screenPaddingX, vertical = tokens.screenPaddingY)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(tokens.gap),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = TvColors.OnBackground,
            fontSize = tokens.title,
            fontWeight = FontWeight.Bold,
        )
        SettingRow(stringResource(R.string.settings_name), name) {
            TvButton(
                text = stringResource(R.string.action_rename),
                onClick = { renaming = true },
                modifier = Modifier.focusRequester(firstFocus),
            )
        }
        SettingRow(stringResource(R.string.settings_audio), stringResource(R.string.settings_audio_hint)) {
            TvButton(
                text = stringResource(if (audioEnabled) R.string.settings_on else R.string.settings_off),
                onClick = { onAudioChanged(!audioEnabled) },
                primary = audioEnabled,
            )
        }
        Text(
            text = stringResource(R.string.settings_trusted),
            color = TvColors.OnBackground,
            fontSize = tokens.heading,
            fontWeight = FontWeight.SemiBold,
        )
        if (trusted.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_trusted_empty),
                color = TvColors.OnSurfaceMuted,
                fontSize = tokens.body,
            )
        }
        trusted.forEach { entry ->
            SettingRow(entry.name, null) {
                TvButton(stringResource(R.string.action_forget), { onForget(entry.keyIdHex) })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                color = TvColors.OnSurfaceMuted,
                fontSize = tokens.caption,
            )
            TvButton(stringResource(R.string.action_close), onClose)
        }
    }
    if (renaming) {
        RenameDialog(
            name = name,
            onSave = {
                onRename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun SettingRow(title: String, detail: String?, action: @Composable () -> Unit) {
    val tokens = LocalTvTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TvColors.OnBackground,
                fontSize = tokens.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    color = TvColors.OnSurfaceMuted,
                    fontSize = tokens.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action()
    }
}

@Composable
private fun RenameDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalTvTokens.current
    var value by remember { mutableStateOf(name) }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val save = { if (value.isNotBlank()) onSave(value) }
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { fieldFocus.requestFocus() }
        Column(
            modifier = Modifier
                .background(TvColors.Surface, RoundedCornerShape(tokens.corner))
                .padding(tokens.gap),
            verticalArrangement = Arrangement.spacedBy(tokens.gap),
        ) {
            Text(
                text = stringResource(R.string.settings_name),
                color = TvColors.OnBackground,
                fontSize = tokens.heading,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_name_hint),
                color = TvColors.OnSurfaceMuted,
                fontSize = tokens.caption,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(MAX_NAME_LENGTH) },
                singleLine = true,
                textStyle = TextStyle(fontSize = tokens.body),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveFocus.requestFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TvColors.OnBackground,
                    unfocusedTextColor = TvColors.OnBackground,
                    focusedBorderColor = TvColors.Focus,
                    unfocusedBorderColor = TvColors.OnSurfaceMuted,
                    cursorColor = TvColors.Focus,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    // A text field keeps the arrows for its cursor; a remote still has to be able to leave it.
                    .onPreviewKeyEvent { event ->
                        val leave = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
                        if (leave) saveFocus.requestFocus()
                        leave
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                TvButton(
                    text = stringResource(R.string.action_save),
                    onClick = save,
                    modifier = Modifier.focusRequester(saveFocus),
                    primary = true,
                )
                TvButton(stringResource(R.string.action_cancel), onDismiss)
            }
        }
    }
}

private const val MAX_NAME_LENGTH = 60
