package com.localcast.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.BuildConfig
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.Priority
import com.localcast.app.settings.ResolutionMode
import com.localcast.app.settings.StreamSettings
import com.localcast.app.ui.theme.AddressSmallTextStyle
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
    val preset = Preset.of(settings)
    // A custom setup opens with "Advanced" unfolded: that is where it was made.
    var advanced by rememberSaveable { mutableStateOf(preset == null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.ui_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )

            SectionTitle(stringResource(R.string.ui_set_quality))
            PresetPicker(preset) { p -> change(p::applyTo) }
            Text(
                if (preset != null) stringResource(preset.hint)
                else stringResource(R.string.ui_preset_custom_hint, summary(settings)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AdvancedHeader(advanced) { advanced = !advanced }
            AnimatedVisibility(advanced) {
                AdvancedSettings(settings, ::change)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = settings.requirePin,
                        role = Role.Switch,
                        onValueChange = { v -> change { it.copy(requirePin = v) } },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelBlock(
                    stringResource(R.string.ui_set_require_pin),
                    stringResource(R.string.ui_set_require_pin_hint),
                    Modifier.weight(1f),
                )
                // The whole row toggles; the switch only mirrors the state.
                Switch(checked = settings.requirePin, onCheckedChange = null)
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelBlock(
                    stringResource(R.string.ui_set_port),
                    stringResource(R.string.ui_set_port_hint),
                    Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ui_set_port_value, settings.port),
                    style = AddressSmallTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BatteryRow()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LanguageRow()

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            AboutBlock()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Three cards side by side; stacked rows when large text would break the words. */
@Composable
private fun PresetPicker(current: Preset?, onPick: (Preset) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val cardText = (maxWidth - 16.dp) / 3 - 24.dp
        if (cardText >= 84.dp * fontScale) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Preset.entries.forEach { p ->
                    PresetCard(
                        p,
                        selected = p == current,
                        onClick = { onPick(p) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    PresetCard(p, selected = p == current, onClick = { onPick(p) }, stacked = true)
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    p: Preset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        if (stacked) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    painterResource(presetIcon(p)),
                    contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(p.spec),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            return@Surface
        }
        Box(Modifier.heightIn(min = 96.dp).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painterResource(presetIcon(p)),
                    contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(stringResource(p.title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(p.spec),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
    val stateText = stringResource(if (expanded) R.string.ui_set_advanced_cd_collapse else R.string.ui_set_advanced_cd_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stateText, onClick = onToggle)
            .semantics { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ui_set_advanced),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
}

@Composable
private fun AdvancedSettings(settings: StreamSettings, change: ((StreamSettings) -> StreamSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
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

        SettingGroup(stringResource(R.string.ui_set_fps), hint = stringResource(R.string.ui_set_fps_hint)) {
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
        val bitrateLabel = bitrateLabel(choices[idx])
        SettingGroup(
            stringResource(R.string.ui_set_bitrate),
            trailing = bitrateLabel,
            hint = stringResource(R.string.ui_set_bitrate_hint),
        ) {
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

        SettingGroup(
            stringResource(R.string.ui_set_priority),
            hint = stringResource(
                if (settings.priority == Priority.SMOOTH) R.string.ui_set_priority_smooth_hint
                else R.string.ui_set_priority_sharp_hint,
            ),
        ) {
            Segmented(
                options = listOf(
                    Priority.SMOOTH to stringResource(R.string.ui_set_priority_smooth),
                    Priority.SHARP to stringResource(R.string.ui_set_priority_sharp),
                ),
                selected = settings.priority,
                onSelect = { v -> change { it.copy(priority = v) } },
            )
        }
    }
}

@Composable
private fun bitrateLabel(mbps: Int): String =
    if (mbps == 0) stringResource(R.string.ui_set_bitrate_auto) else stringResource(R.string.ui_set_bitrate_value, mbps)

/** "1080p · 60 fps · 8 Mbps · sharpness" for the "Custom" hint. */
@Composable
private fun summary(s: StreamSettings): String {
    val res = stringResource(
        when (s.resolution) {
            ResolutionMode.P720 -> R.string.ui_set_res_720
            ResolutionMode.P1080 -> R.string.ui_set_res_1080
            ResolutionMode.NATIVE -> R.string.ui_set_res_native
        },
    )
    val fps = when (s.fps) {
        FpsMode.AUTO -> stringResource(R.string.ui_set_summary_fps_auto)
        FpsMode.FPS_30 -> stringResource(R.string.ui_set_summary_fps, 30)
        FpsMode.FPS_60 -> stringResource(R.string.ui_set_summary_fps, 60)
    }
    val bitrate = if (s.bitrateMbps == 0) {
        stringResource(R.string.ui_set_summary_bitrate_auto)
    } else {
        stringResource(R.string.ui_set_bitrate_value, s.bitrateMbps)
    }
    val priority = stringResource(
        if (s.priority == Priority.SMOOTH) R.string.ui_set_priority_smooth else R.string.ui_set_priority_sharp,
    ).lowercase()
    return stringResource(R.string.ui_set_summary, res, fps, bitrate, priority)
}

@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val optimized = rememberBatteryOptimized()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabelBlock(
            stringResource(R.string.ui_set_battery),
            stringResource(if (optimized) R.string.ui_set_battery_on else R.string.ui_set_battery_off),
            Modifier.weight(1f),
        )
        if (optimized) {
            OutlinedButton(onClick = { openBatterySettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_set_battery_action))
            }
        } else {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Per-app language is picked in system settings (Android 13+); older versions follow the system. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { openAppLanguageSettings(context) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabelBlock(
            stringResource(R.string.ui_set_language),
            stringResource(R.string.ui_set_language_current),
            Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun openAppLanguageSettings(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    try {
        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app))
    } catch (_: Exception) {
        // Some OEM builds lack the per-app language screen: fall back to the app's info page.
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun AboutBlock() {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BrandMark(44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.ui_set_about),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.ui_about_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.ui_about_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabelBlock(title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingGroup(
    title: String,
    trailing: String? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        content()
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            ) { Text(label, maxLines = 1) }
        }
    }
}
