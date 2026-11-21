package com.localcast.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.localcast.app.R
import com.localcast.app.service.AddressKind
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.ui.theme.AddressSmallTextStyle
import com.localcast.app.ui.theme.AddressTextStyle
import com.localcast.app.ui.theme.OverlineTextStyle
import com.localcast.app.ui.theme.PinTextStyle
import com.localcast.app.ui.theme.brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ---------- Idle ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdleHeroCard(
    state: CastState,
    deviceName: String,
    network: NetworkKind,
    preset: Preset?,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BrandCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(52.dp)
            Column(Modifier.weight(1f)) {
                Overline(stringResource(R.string.ui_device_label))
                Text(deviceName, style = MaterialTheme.typography.headlineSmall)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkChip(network)
            PresetChip(preset, onOpenSettings)
        }
        if (state.phase == CastPhase.ERROR) {
            NoticeBox(
                text = state.error ?: stringResource(R.string.ui_error_unknown),
                tone = Tone.ERROR,
                title = stringResource(R.string.ui_status_error),
            )
        }
        when {
            network.isMissing -> NoticeBox(stringResource(R.string.ui_no_network), Tone.WARN)
            network.isWarning -> Text(
                stringResource(R.string.ui_net_24_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val busy = state.phase == CastPhase.STOPPING
        Button(
            onClick = onStart,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ui_status_stopping), style = MaterialTheme.typography.titleMedium)
            } else {
                val retry = state.phase == CastPhase.ERROR
                Icon(
                    if (retry) rememberVectorPainter(Icons.Filled.Refresh) else painterResource(R.drawable.ic_stat_cast),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (retry) R.string.ui_retry else R.string.ui_start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            stringResource(R.string.ui_idle_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StepsCard() {
    BrandCard {
        SectionTitle(stringResource(R.string.ui_steps_title))
        listOf(R.string.ui_step_1, R.string.ui_step_2, R.string.ui_step_3).forEachIndexed { i, res ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                NumberBadge(i + 1)
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
            }
        }
    }
}

// ---------- Chips ----------

@Composable
fun NetworkChip(kind: NetworkKind) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(
        when (kind) {
            NetworkKind.WIFI_5 -> R.string.ui_net_wifi_5
            NetworkKind.WIFI_6 -> R.string.ui_net_wifi_6
            NetworkKind.WIFI_24 -> R.string.ui_net_wifi_24
            NetworkKind.WIFI -> R.string.ui_net_wifi
            NetworkKind.HOTSPOT -> R.string.ui_net_hotspot
            NetworkKind.ETHERNET -> R.string.ui_net_ethernet
            NetworkKind.OTHER -> R.string.ui_net_other
            NetworkKind.NONE -> R.string.ui_net_none
        },
    )
    val icon = when (kind) {
        NetworkKind.HOTSPOT -> R.drawable.ic_hotspot
        NetworkKind.ETHERNET, NetworkKind.OTHER -> R.drawable.ic_lan
        NetworkKind.NONE -> R.drawable.ic_wifi_off
        else -> R.drawable.ic_wifi
    }
    val (bg, fg) = when {
        kind.isMissing -> brand.badContainer to brand.onBadContainer
        kind.isWarning -> brand.warnContainer to brand.onWarnContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    InfoPill(
        text = label,
        icon = painterResource(icon),
        container = bg,
        content = fg,
        description = stringResource(R.string.ui_net_cd, label),
    )
}

@Composable
private fun presetName(preset: Preset?): String =
    stringResource(preset?.title ?: R.string.ui_preset_custom)

@Composable
fun PresetChip(preset: Preset?, onClick: () -> Unit) {
    val name = presetName(preset)
    val cd = stringResource(R.string.ui_preset_chip_cd, name)
    AssistChip(
        onClick = onClick,
        label = { Text(name, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = {
            Icon(
                painterResource(presetIcon(preset)),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = Modifier.semantics { contentDescription = cd },
    )
}

fun presetIcon(preset: Preset?): Int = when (preset) {
    Preset.SPEED -> R.drawable.ic_speed
    Preset.QUALITY -> R.drawable.ic_hq
    Preset.BALANCE, null -> R.drawable.ic_tune
}

// ---------- Live ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveHeaderCard(
    state: CastState,
    liveSince: Long?,
    deviceName: String,
    network: NetworkKind,
    preset: Preset?,
    onOpenSettings: () -> Unit,
) {
    BrandCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveBadge(live = state.phase == CastPhase.LIVE)
            Spacer(Modifier.weight(1f))
            if (state.phase == CastPhase.LIVE && liveSince != null) LiveTimer(liveSince)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Overline(deviceName)
            val cap = state.capture
            if (cap != null) {
                Text(
                    stringResource(R.string.ui_capture_info, cap.width, cap.height, cap.fps),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkChip(network)
            PresetChip(preset, onOpenSettings)
        }
    }
}

@Composable
fun LiveBadge(live: Boolean) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val bg = if (live) brand.live else scheme.secondaryContainer
    val fg = if (live) brand.onLive else scheme.onSecondaryContainer
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (live) {
            val pulse by rememberInfiniteTransition(label = "live").animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "dot",
            )
            Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(fg))
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
        }
        Text(
            stringResource(if (live) R.string.ui_live_badge else R.string.ui_starting_badge),
            style = OverlineTextStyle,
            color = fg,
        )
    }
}

@Composable
private fun LiveTimer(since: Long) {
    val elapsed by produceState(SystemClock.elapsedRealtime() - since, since) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            value = now - since
            delay(1_000 - (now - since) % 1_000)
        }
    }
    val text = formatDuration(elapsed)
    val cd = stringResource(R.string.ui_live_duration_cd, text)
    Text(
        text,
        // Tabular figures: digits keep their width, so the timer doesn't jitter every second.
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clearAndSetSemantics { contentDescription = cd },
    )
}

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

@Composable
private fun kindLabel(kind: AddressKind): String = stringResource(
    when (kind) {
        AddressKind.WIFI -> R.string.ui_kind_wifi
        AddressKind.HOTSPOT -> R.string.ui_kind_hotspot
        AddressKind.ETHERNET -> R.string.ui_kind_ethernet
        AddressKind.USB -> R.string.ui_kind_usb
        AddressKind.VPN -> R.string.ui_kind_vpn
        AddressKind.OTHER -> R.string.ui_kind_other
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectCard(state: CastState, snackbar: SnackbarHostState, qrMax: Dp = 280.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = state.primaryUrl
    val copiedText = stringResource(R.string.ui_copied)
    val shareTitle = stringResource(R.string.ui_share_title)
    val clipLabel = stringResource(R.string.app_name)

    BrandCard {
        Text(
            stringResource(R.string.ui_connect_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (url == null) {
            if (state.port == null && state.phase == CastPhase.STARTING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(stringResource(R.string.ui_waiting_address), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                NoticeBox(stringResource(R.string.ui_no_network), Tone.WARN)
            }
            return@BrandCard
        }

        state.qrUrl?.let { QrPlate(it, qrMax) }

        Text(
            url.removeSuffix("/"),
            style = AddressTextStyle,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText(clipLabel, url))
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copiedText) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_copy))
            }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_share))
            }
        }

        val others = state.addresses.drop(1)
        val port = state.port
        if (others.isNotEmpty() && port != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline(stringResource(R.string.ui_other_addresses))
                for (a in others) {
                    Text(
                        stringResource(R.string.ui_address_with_kind, "http://${a.ip}:$port", kindLabel(a.kind)),
                        style = AddressSmallTextStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrPlate(qrUrl: String, maxSide: Dp) {
    val qr by rememberQrBitmap(qrUrl)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = min(maxWidth, maxSide)
            Box(
                Modifier
                    .size(side)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.brand.qrBackground)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                qr?.let {
                    Image(
                        bitmap = it,
                        contentDescription = stringResource(R.string.ui_qr_description),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize().aspectRatio(1f),
                    )
                } ?: CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            stringResource(R.string.ui_qr_caption),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PinCard(state: CastState) {
    val context = LocalContext.current
    val pin = state.pin
    if (!state.pinRequired || pin == null) {
        BrandCard { NoticeBox(stringResource(R.string.ui_pin_disabled), Tone.WARN) }
        return
    }
    BrandCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Overline(stringResource(R.string.ui_pin_title), Modifier.weight(1f))
                TextButton(
                    onClick = { CastController.regeneratePin(context) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_pin_new))
                }
            }
            val pinCd = stringResource(R.string.ui_pin_cd, pin.toCharArray().joinToString(" "))
            Text(
                formatPin(pin),
                style = PinTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { contentDescription = pinCd },
            )
        }
        Text(
            stringResource(R.string.ui_pin_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatPin(pin: String): String =
    if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin

// ---------- Tips ----------

/** One link-like line; expands in place. */
@Composable
fun TipsLine() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val batteryOptimized = rememberBatteryOptimized()
    val stateText = stringResource(if (expanded) R.string.ui_tips_collapse else R.string.ui_tips_expand)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = stateText) { expanded = !expanded }
                .semantics { stateDescription = stateText }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_lightbulb), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.ui_tips_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ui_tip_latency), style = MaterialTheme.typography.bodyMedium)
                if (batteryOptimized) {
                    Text(stringResource(R.string.ui_tip_battery), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { openBatterySettings(context) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_battery), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_tip_battery_action))
                    }
                }
            }
        }
    }
}

// ---------- Stop ----------

@Composable
fun StopButton(phase: CastPhase, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val brand = MaterialTheme.brand
    val stopping = phase == CastPhase.STOPPING
    Button(
        onClick = onStop,
        enabled = !stopping,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = brand.live, contentColor = brand.onLive),
    ) {
        if (stopping) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ui_status_stopping), style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ui_stop), style = MaterialTheme.typography.titleMedium)
        }
    }
}
