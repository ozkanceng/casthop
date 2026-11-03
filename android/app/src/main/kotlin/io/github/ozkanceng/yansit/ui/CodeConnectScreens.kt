package io.github.ozkanceng.yansit.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    BackHandler(onBack = onBack)
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val width = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.headlineSmall, modifier = width)
            Row(width, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.discover_searching), style = MaterialTheme.typography.bodyLarge)
            }
            for (b in boards) {
                Button(onClick = { onPick(b) }, modifier = width.heightIn(min = 64.dp)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                stringResource(R.string.discover_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = width,
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** Tahtadaki 6 haneli kodun girilmesi. */
@Composable
fun PinScreen(board: DiscoveredBoard, onConnect: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var pin by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = pin.length == 6
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            val width = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            Text(
                stringResource(R.string.pin_title, board.name),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = width,
            )
            Text(
                stringResource(R.string.pin_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = width,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter(Char::isDigit).take(6) },
                singleLine = true,
                label = { Text(stringResource(R.string.pin_label)) },
                textStyle = TextStyle(fontSize = 40.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onConnect(pin) }),
                modifier = width.focusRequester(focus),
            )
            Button(onClick = { onConnect(pin) }, enabled = valid, modifier = width.heightIn(min = 72.dp)) {
                Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.titleLarge)
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
