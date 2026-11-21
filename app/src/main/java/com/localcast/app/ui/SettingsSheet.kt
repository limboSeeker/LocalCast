package com.localcast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.Priority
import com.localcast.app.settings.ResolutionMode
import com.localcast.app.settings.StreamSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val repo = LocalCastApp.instance.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = StreamSettings())
    val scope = rememberCoroutineScope()
    fun change(t: (StreamSettings) -> StreamSettings) {
        scope.launch { repo.update(t) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.ui_settings), style = MaterialTheme.typography.titleLarge)

            SettingGroup(stringResource(R.string.ui_set_resolution)) {
                Segmented(
                    options = listOf(
                        ResolutionMode.P720 to stringResource(R.string.ui_set_res_720),
                        ResolutionMode.P1080 to stringResource(R.string.ui_set_res_1080),
                        ResolutionMode.NATIVE to stringResource(R.string.ui_set_res_native),
                    ),
                    selected = settings.resolution,
                    onSelect = { v -> change { it.copy(resolution = v) } },
                )
            }

            SettingGroup(stringResource(R.string.ui_set_fps)) {
                Segmented(
                    options = listOf(
                        FpsMode.AUTO to stringResource(R.string.ui_set_auto),
                        FpsMode.FPS_30 to stringResource(R.string.ui_set_fps_30),
                        FpsMode.FPS_60 to stringResource(R.string.ui_set_fps_60),
                    ),
                    selected = settings.fps,
                    onSelect = { v -> change { it.copy(fps = v) } },
                )
            }

            val choices = StreamSettings.BITRATE_CHOICES
            val idx = choices.indexOf(settings.bitrateMbps).coerceAtLeast(0)
            val bitrateLabel = if (choices[idx] == 0) {
                stringResource(R.string.ui_set_bitrate_auto)
            } else {
                stringResource(R.string.ui_set_bitrate_value, choices[idx])
            }
            SettingGroup(stringResource(R.string.ui_set_bitrate), trailing = bitrateLabel) {
                Slider(
                    value = idx.toFloat(),
                    onValueChange = { f ->
                        val v = choices[f.roundToInt().coerceIn(0, choices.lastIndex)]
                        if (v != settings.bitrateMbps) change { it.copy(bitrateMbps = v) }
                    },
                    valueRange = 0f..choices.lastIndex.toFloat(),
                    steps = choices.size - 2,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = bitrateLabel },
                )
            }

            SettingGroup(stringResource(R.string.ui_set_priority)) {
                Segmented(
                    options = listOf(
                        Priority.SMOOTH to stringResource(R.string.ui_set_priority_smooth),
                        Priority.SHARP to stringResource(R.string.ui_set_priority_sharp),
                    ),
                    selected = settings.priority,
                    onSelect = { v -> change { it.copy(priority = v) } },
                )
                Text(
                    stringResource(
                        if (settings.priority == Priority.SMOOTH) R.string.ui_set_priority_smooth_hint
                        else R.string.ui_set_priority_sharp_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ui_set_require_pin), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = settings.requirePin,
                    onCheckedChange = { v -> change { it.copy(requirePin = v) } },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ui_set_port), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ui_set_port_value, settings.port),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingGroup(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(label) }
        }
    }
}
