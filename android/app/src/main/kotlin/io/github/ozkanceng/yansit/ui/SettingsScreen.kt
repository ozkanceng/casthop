package io.github.ozkanceng.yansit.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.sender.Preset
import io.github.ozkanceng.yansit.sender.StreamOverrides

/** Ayarlar ekranında gösterilecek, cihazın gerçekten desteklediği seçenekler. */
data class SettingsOptions(
    val longSides: List<Int>,
    val fps: List<Int>,
    val maxBitratesBps: List<Long>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preset: Preset,
    overrides: StreamOverrides,
    options: SettingsOptions,
    audioEnabled: Boolean,
    hevcAvailable: Boolean,
    hevcEnabled: Boolean,
    onChange: (Preset, StreamOverrides) -> Unit,
    onAudioChange: (Boolean) -> Unit,
    onHevcChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var audio by remember { mutableStateOf(audioEnabled) }
    var hevc by remember { mutableStateOf(hevcEnabled) }
    BackHandler(onBack = onBack)
    var current by remember { mutableStateOf(preset) }
    var adv by remember { mutableStateOf(overrides) }
    fun update(p: Preset = current, o: StreamOverrides = adv) {
        current = p
        adv = o
        onChange(p, o)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            Text(stringResource(R.string.settings_quality), style = MaterialTheme.typography.titleMedium, modifier = width)
            Column(width.selectableGroup()) {
                PresetRow(Preset.BEST, R.string.preset_best, R.string.preset_best_desc, current) { update(p = it) }
                PresetRow(Preset.BALANCED, R.string.preset_balanced, R.string.preset_balanced_desc, current) { update(p = it) }
                PresetRow(Preset.WEAK_NETWORK, R.string.preset_weak, R.string.preset_weak_desc, current) { update(p = it) }
            }
            HorizontalDivider(width)
            Row(
                width
                    .heightIn(min = 64.dp)
                    .toggleable(value = audio, role = Role.Switch, onValueChange = {
                        audio = it
                        onAudioChange(it)
                    }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_audio), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_audio_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = audio, onCheckedChange = null)
            }
            HorizontalDivider(width)
            Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleMedium, modifier = width)
            Text(
                stringResource(R.string.settings_advanced_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = width,
            )
            ChoiceRow(R.string.settings_resolution, options.longSides, adv.longSide, { stringResourceFor(R.string.option_long_side, it) }) {
                update(o = adv.copy(longSide = it))
            }
            ChoiceRow(R.string.settings_fps, options.fps, adv.fps, { stringResourceFor(R.string.option_fps, it) }) {
                update(o = adv.copy(fps = it))
            }
            // Desteklenmeyen seçenek gösterilmez: tablette donanım H.265 kodlayıcı yoksa anahtar da yok.
            if (hevcAvailable) {
                Row(
                    width
                        .heightIn(min = 64.dp)
                        .toggleable(value = hevc, role = Role.Switch, onValueChange = {
                            hevc = it
                            onHevcChange(it)
                        }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_hevc), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_hevc_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = hevc, onCheckedChange = null)
                }
            }
            ChoiceRow(
                R.string.settings_bitrate,
                options.maxBitratesBps,
                adv.maxBitrateBps,
                { stringResourceFor(R.string.option_mbps, (it / 1_000_000).toInt()) },
            ) { update(o = adv.copy(maxBitrateBps = it)) }
        }
    }
}

@Composable
private fun PresetRow(value: Preset, @StringRes title: Int, @StringRes desc: Int, selected: Preset, onSelect: (Preset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Otomatik" + desteklenen değerler. Desteklenmeyen değerler hiç gösterilmez. */
@Composable
private fun <T : Any> ChoiceRow(
    @StringRes label: Int,
    values: List<T>,
    selected: T?,
    format: @Composable (T) -> String,
    onSelect: (T?) -> Unit,
) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.option_auto)) })
            for (v in values) {
                FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(format(v)) })
            }
        }
    }
}

@Composable
private fun stringResourceFor(@StringRes id: Int, arg: Int): String = stringResource(id, arg)
