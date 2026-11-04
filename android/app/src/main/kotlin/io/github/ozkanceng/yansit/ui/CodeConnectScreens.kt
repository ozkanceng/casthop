package io.github.ozkanceng.yansit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.stream.DiscoveredBoard

/** Ağda bulunan tahtalar (Android 16 ve öncesi; Android 17+ sistem seçicisi kullanılır). */
@Composable
fun DiscoverScreen(boards: List<DiscoveredBoard>, onPick: (DiscoveredBoard) -> Unit, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.discover_title), onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.discover_searching), style = MaterialTheme.typography.bodyLarge)
        }
        for (b in boards) {
            NavigationCard(title = b.name, icon = Icons.Rounded.Tv, onClick = { onPick(b) }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            stringResource(R.string.discover_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tahtadaki 6 haneli kodun girilmesi. */
@Composable
fun PinScreen(board: DiscoveredBoard, onConnect: (String) -> Unit, onBack: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = pin.length == 6
    SubScreen(stringResource(R.string.pin_title, board.name), onBack, maxContentWidth = 480.dp) {
        Text(
            stringResource(R.string.pin_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter(Char::isDigit).take(6) },
            singleLine = true,
            label = { Text(stringResource(R.string.pin_label)) },
            textStyle = TextStyle(
                fontSize = 40.sp,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (valid) onConnect(pin) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Button(onClick = { onConnect(pin) }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.titleLarge)
        }
    }
}
