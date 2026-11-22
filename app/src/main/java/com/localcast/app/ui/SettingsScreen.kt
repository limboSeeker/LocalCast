package com.localcast.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.BuildConfig
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.service.Diagnostics
import com.localcast.app.settings.FpsMode
import com.localcast.app.settings.Priority
import com.localcast.app.settings.ResolutionMode
import com.localcast.app.settings.StreamSettings
import com.localcast.app.ui.theme.brand
import com.localcast.app.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsPage { MAIN, ADVANCED }

/** Full-screen settings: quality, access, general; "Advanced" is a second page. */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val repo = LocalCastApp.instance.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = StreamSettings())
    val scope = rememberCoroutineScope()
    fun change(t: (StreamSettings) -> StreamSettings) {
        scope.launch { repo.update(t) }
    }
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val back = { if (page == SettingsPage.ADVANCED) page = SettingsPage.MAIN else onClose() }
    BackHandler(onBack = back)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
                }
                Text(
                    stringResource(if (page == SettingsPage.MAIN) R.string.ui_settings else R.string.ui_set_advanced),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    when (page) {
                        SettingsPage.MAIN -> MainPage(settings, ::change) { page = SettingsPage.ADVANCED }
                        SettingsPage.ADVANCED -> AdvancedPage(settings, ::change)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    SectionLabel(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun MainPage(settings: StreamSettings, change: ((StreamSettings) -> StreamSettings) -> Unit, onAdvanced: () -> Unit) {
    val preset = Preset.of(settings)
    Section(stringResource(R.string.ui_set_quality))
    PresetPicker(preset) { p -> change(p::applyTo) }
    Text(
        if (preset != null) stringResource(preset.hint) else stringResource(R.string.ui_preset_custom_hint, summary(settings)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )

    Section(stringResource(R.string.ui_set_access))
    SwitchRow(
        stringResource(R.string.ui_set_require_pin),
        stringResource(R.string.ui_set_require_pin_hint),
        settings.requirePin,
    ) { v -> change { it.copy(requirePin = v) } }
    SwitchRow(
        stringResource(R.string.ui_set_pause_lock),
        stringResource(R.string.ui_set_pause_lock_hint),
        settings.pauseOnLock,
    ) { v -> change { it.copy(pauseOnLock = v) } }

    Section(stringResource(R.string.ui_set_general))
    ThemeRow()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LanguageRow()
    NavRow(stringResource(R.string.ui_set_advanced), null, onAdvanced)

    Section(stringResource(R.string.ui_set_reliability))
    BatteryRow()
    if (isXiaomiFamily()) XiaomiHint()
    DiagnosticsRow()

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    AboutBlock()
}

// ---------- Quality ----------

/** Three tiles side by side; stacked rows when large text would break the words. */
@Composable
private fun PresetPicker(current: Preset?, onPick: (Preset) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val tile = (maxWidth - 16.dp) / 3
        if (tile >= 92.dp * fontScale) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Preset.entries.forEach { p ->
                    PresetTile(p, p == current, { onPick(p) }, Modifier.weight(1f).fillMaxHeight(), stacked = false)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p -> PresetTile(p, p == current, { onPick(p) }, Modifier.fillMaxWidth(), stacked = true) }
            }
        }
    }
}

@Composable
private fun PresetTile(p: Preset, selected: Boolean, onClick: () -> Unit, modifier: Modifier, stacked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainer,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.brand.readyOutline) else null,
    ) {
        val icon = @Composable {
            Icon(
                painterResource(presetIcon(p)),
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        if (stacked) {
            Row(
                Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                icon()
                Text(stringResource(p.title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        } else {
            Column(
                Modifier.heightIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                icon()
                Text(stringResource(p.title), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------- Rows ----------

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabelBlock(title, hint, Modifier.weight(1f))
        // The whole row toggles; the switch only mirrors the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable
private fun NavRow(title: String, hint: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabelBlock(title, hint, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabelBlock(title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun themeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.ui_theme_system
        ThemeMode.LIGHT -> R.string.ui_theme_light
        ThemeMode.DARK -> R.string.ui_theme_dark
    },
)

@Composable
private fun ThemeRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    ValueRow(stringResource(R.string.ui_set_theme), themeName(UiPrefs.theme)) { open = true }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ui_set_theme), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = UiPrefs.theme == mode, role = Role.RadioButton) {
                                UiPrefs.chooseTheme(mode)
                                open = false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = UiPrefs.theme == mode, onClick = null)
                        Text(themeName(mode), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_cancel)) }
        },
    )
}

/** Per-app language is picked in system settings (Android 13+); older versions follow the system. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    ValueRow(stringResource(R.string.ui_set_language), stringResource(R.string.ui_set_language_current)) {
        openAppLanguageSettings(context)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun openAppLanguageSettings(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    try {
        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app))
    } catch (_: Exception) {
        // Some OEM builds lack the per-app language screen: fall back to the app's info page.
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
    } catch (_: Exception) {
    }
}

@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val optimized = rememberBatteryOptimized()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
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
                Text(stringResource(R.string.ui_set_battery_action), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.brand.good)
        }
    }
}

private fun isXiaomiFamily(): Boolean {
    val m = Build.MANUFACTURER.lowercase()
    val b = Build.BRAND.lowercase()
    return listOf("xiaomi", "redmi", "poco").any { it == m || it == b }
}

/** HyperOS / MIUI kill background apps unless Autostart and "No restrictions" are allowed. */
@Composable
private fun XiaomiHint() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        NoticeBox(
            text = stringResource(R.string.ui_xiaomi_hint),
            tone = Tone.WARN,
            title = stringResource(R.string.ui_xiaomi_title),
        )
        TextButton(onClick = { openAppDetails(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ui_xiaomi_action), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DiagnosticsRow() {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_500)
            copied = false
        }
    }
    val label = stringResource(R.string.app_name)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText(label, Diagnostics.text(context)))
                copied = true
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabelBlock(
            stringResource(R.string.ui_set_diagnostics),
            stringResource(if (copied) R.string.ui_set_diagnostics_done else R.string.ui_set_diagnostics_hint),
            Modifier.weight(1f),
        )
        Icon(
            if (copied) rememberVectorPainter(Icons.Filled.CheckCircle) else painterResource(R.drawable.ic_copy),
            contentDescription = null,
            tint = if (copied) MaterialTheme.brand.good else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun AboutBlock() {
    Row(
        Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark(40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.ui_about_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.ui_about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Advanced ----------

@Composable
private fun AdvancedPage(settings: StreamSettings, change: ((StreamSettings) -> StreamSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
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

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabelBlock(stringResource(R.string.ui_set_port), stringResource(R.string.ui_set_port_hint), Modifier.weight(1f))
            Text(
                settings.port.toString(),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
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
        FpsMode.FPS_30 -> stringResource(R.string.ui_fps_value, 30)
        FpsMode.FPS_60 -> stringResource(R.string.ui_fps_value, 60)
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
                Text(trailing, style = MaterialTheme.typography.titleSmall.tabular(), color = MaterialTheme.colorScheme.primary)
            }
        }
        content()
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
