package com.localcast.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.service.PauseReason
import com.localcast.app.ui.theme.brand
import com.localcast.app.ui.theme.pin
import com.localcast.app.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ---------- Idle ----------

/** Idle screen body: the round Start button, readiness, network and quality chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdleHero(
    state: CastState,
    deviceName: String,
    network: NetworkKind,
    preset: Preset?,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StartButton(state.phase, onStart)
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.ui_ready_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            deviceName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkChip(network)
            PresetChip(preset, onOpenSettings)
        }
        if (state.phase == CastPhase.ERROR || network.isMissing || network.isWarning) Spacer(Modifier.height(16.dp))
        if (state.phase == CastPhase.ERROR) {
            NoticeBox(
                text = state.error ?: stringResource(R.string.ui_error_unknown),
                tone = Tone.ERROR,
                title = stringResource(R.string.ui_status_error),
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            network.isMissing -> NoticeBox(stringResource(R.string.ui_no_network), Tone.WARN)
            network.isWarning -> Text(
                stringResource(R.string.ui_net_24_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 184 dp disc in a thin ring: the single "go" control of the idle screen. */
@Composable
private fun StartButton(phase: CastPhase, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val busy = phase == CastPhase.STOPPING
    val retry = phase == CastPhase.ERROR
    val label = stringResource(
        when {
            busy -> R.string.ui_status_stopping
            retry -> R.string.ui_retry
            else -> R.string.ui_start
        },
    )
    Box(
        Modifier
            .size(184.dp)
            .border(1.5.dp, MaterialTheme.brand.readyOutline, CircleShape)
            .padding(13.dp)
            .clip(CircleShape)
            .background(scheme.primaryContainer)
            .clickable(enabled = !busy, role = Role.Button, onClick = onStart)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp, color = scheme.primary)
            } else {
                Icon(
                    if (retry) rememberVectorPainter(Icons.Filled.Refresh) else painterResource(R.drawable.ic_stat_cast),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
fun NetworkChip(kind: NetworkKind) {
    val brand = MaterialTheme.brand
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
    val tint = when {
        kind.isMissing -> brand.liveDot
        kind.isWarning -> brand.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val cd = stringResource(R.string.ui_net_cd, label)
    InfoChip(label, painterResource(icon), Modifier.clearAndSetSemantics { contentDescription = cd }, tint = tint)
}

@Composable
private fun presetName(preset: Preset?): String =
    stringResource(preset?.title ?: R.string.ui_preset_custom)

@Composable
fun PresetChip(preset: Preset?, onClick: () -> Unit) {
    val name = presetName(preset)
    ChipButton(
        text = stringResource(R.string.ui_preset_chip, name.lowercase()),
        icon = painterResource(R.drawable.ic_tune),
        onClick = onClick,
        onClickLabel = stringResource(R.string.ui_preset_chip_action),
    )
}

fun presetIcon(preset: Preset): Int = when (preset) {
    Preset.SPEED -> R.drawable.ic_speed
    Preset.BALANCE -> R.drawable.ic_tune
    Preset.QUALITY -> R.drawable.ic_hq
}

// ---------- Live: header ----------

/** "On air" / "Paused" / "Starting…" plaque. The lamp colour does not depend on health. */
@Composable
fun AirPlaque(state: CastState, modifier: Modifier = Modifier) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    when {
        state.phase == CastPhase.STOPPING ->
            StatusPlaque(stringResource(R.string.ui_status_stopping), scheme.surfaceContainer, scheme.onSurface, null, modifier)
        state.phase != CastPhase.LIVE ->
            StatusPlaque(stringResource(R.string.ui_starting_badge), scheme.surfaceContainer, scheme.onSurface, null, modifier)
        state.isPaused ->
            StatusPlaque(stringResource(R.string.ui_paused_badge), brand.warnContainer, brand.onWarnContainer, brand.warn, modifier)
        else ->
            StatusPlaque(stringResource(R.string.ui_live_badge), brand.liveContainer, brand.onLiveContainer, brand.liveDot, modifier)
    }
}

/** Plaque, timer and the settings button: the phone's top row while on air. */
@Composable
fun LiveTopRow(
    state: CastState,
    liveSince: Long?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    timerStyle: TextStyle = MaterialTheme.typography.headlineSmall,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AirPlaque(state)
        Spacer(Modifier.weight(1f))
        if (state.phase == CastPhase.LIVE && liveSince != null) {
            LiveTimer(
                liveSince,
                timerStyle,
                if (state.isPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        SettingsButton(onOpenSettings)
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.ui_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LiveTimer(since: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
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
        style = style.tabular(),
        color = color,
        modifier = modifier.clearAndSetSemantics { contentDescription = cd },
    )
}

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/** Tablet: the lamp on a tonal disc with "On air" (or "Paused") inside, the big timer below. */
@Composable
fun AirLamp(state: CastState, liveSince: Long?) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val paused = state.isPaused
    val live = state.phase == CastPhase.LIVE
    val (bg, ring, lamp, fg) = when {
        !live -> listOf(scheme.surfaceContainer, scheme.outlineVariant, scheme.outline, scheme.onSurface)
        paused -> listOf(brand.warnContainer, brand.warnOutline, brand.warn, brand.onWarnContainer)
        else -> listOf(brand.liveContainer, brand.liveOutline, brand.liveDot, brand.onLiveContainer)
    }
    val label = stringResource(
        when {
            state.phase == CastPhase.STOPPING -> R.string.ui_status_stopping
            !live -> R.string.ui_starting_badge
            paused -> R.string.ui_paused_badge
            else -> R.string.ui_live_badge
        },
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.5.dp, ring, CircleShape)
                .semantics(mergeDescendants = true) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Dot(lamp, size = 16.dp) else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
                Text(label, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center)
            }
        }
        if (live && liveSince != null) {
            LiveTimer(
                liveSince,
                MaterialTheme.typography.displaySmall,
                if (paused) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
    }
}

// ---------- Live: connect (QR, address, PIN) ----------

/** QR in dark modules on the off-white plate (both are theme colours; the bitmap is black/white). */
@Composable
fun QrPlate(qrUrl: String, side: Dp, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val brand = MaterialTheme.brand
    val qr by rememberQrBitmap(qrUrl, margin = QR_MARGIN)
    val showLabel = stringResource(R.string.ui_show_viewers)
    Box(
        modifier
            .size(side)
            .clip(MaterialTheme.shapes.medium)
            .background(brand.qrBackground)
            .then(if (brand.cardBorder.alpha > 0f) Modifier.border(1.dp, brand.cardBorder, MaterialTheme.shapes.medium) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = showLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        qr?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.ui_qr_description),
                filterQuality = FilterQuality.None,
                colorFilter = qrColors(brand.qrModules, brand.qrBackground),
                modifier = Modifier.fillMaxSize(),
            )
        } ?: CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
    }
}

/** Two quiet-zone modules inside the plate's own light padding make ~10–16 dp of quiet zone. */
const val QR_MARGIN = 2

/** Maps the black/white bitmap onto [dark]/[light]: out = dark + (light − dark) × in. */
private fun qrColors(dark: Color, light: Color): ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            light.red - dark.red, 0f, 0f, 0f, dark.red * 255f,
            0f, light.green - dark.green, 0f, 0f, dark.green * 255f,
            0f, 0f, light.blue - dark.blue, 0f, dark.blue * 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Address with tap-to-copy. */
@Composable
fun AddressText(url: String, snackbar: SnackbarHostState, style: TextStyle, textAlign: TextAlign = TextAlign.Center, sidePadding: Dp = 8.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.ui_copied)
    val clipLabel = stringResource(R.string.app_name)
    val shown = url.removePrefix("http://").removeSuffix("/")
    Text(
        shown,
        style = style.tabular(),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.ui_copy_address)) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText(clipLabel, url))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
            }
            .padding(horizontal = sidePadding, vertical = 12.dp),
    )
}

@Composable
fun ShareButton(url: String) {
    val context = LocalContext.current
    val title = stringResource(R.string.ui_share_title)
    IconButton(onClick = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        context.startActivity(Intent.createChooser(send, title))
    }) {
        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.ui_share_address), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "PIN 530 955" with a "New PIN" button, or a warning when the PIN is off. */
@Composable
fun PinLine(state: CastState, centered: Boolean, big: TextStyle = MaterialTheme.typography.headlineSmall) {
    val context = LocalContext.current
    val pin = state.pin
    if (!state.pinRequired || pin == null) {
        Text(
            stringResource(R.string.ui_pin_disabled),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.brand.warnText,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        return
    }
    val pinCd = stringResource(R.string.ui_pin_cd, pin.toCharArray().joinToString(" "))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (centered) Spacer(Modifier.size(48.dp))
        Text(
            stringResource(R.string.ui_pin_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp, top = 6.dp),
        )
        Text(
            formatPin(pin),
            style = big.pin(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics { contentDescription = pinCd },
        )
        IconButton(onClick = { CastController.regeneratePin(context) }) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.ui_pin_new),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

fun formatPin(pin: String): String =
    if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin

/** Waiting for the server address, or no network at all. */
@Composable
fun NoAddress(state: CastState) {
    if (state.port == null && state.phase == CastPhase.STARTING) {
        Row(
            Modifier.heightIn(min = 120.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text(stringResource(R.string.ui_waiting_address), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        NoticeBox(stringResource(R.string.ui_no_network), Tone.WARN)
    }
}

/** Phone and portrait tablet ([large] text): QR, address, PIN and "Show to viewers", centred. */
@Composable
fun ConnectBlock(state: CastState, snackbar: SnackbarHostState, qrSide: Dp, onShow: () -> Unit, large: Boolean = false) {
    val url = state.primaryUrl
    val qrUrl = state.qrUrl
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (url == null || qrUrl == null) {
            NoAddress(state)
            return@Column
        }
        QrPlate(qrUrl, qrSide, onShow)
        Spacer(Modifier.height(if (large) 8.dp else 4.dp))
        AddressText(url, snackbar, if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        if (large) PinLine(state, centered = true, big = MaterialTheme.typography.headlineMedium) else PinLine(state, centered = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            ChipButton(stringResource(R.string.ui_show_viewers), painterResource(R.drawable.ic_fullscreen), onShow)
            ShareButton(url)
        }
    }
}

/** Tablet: QR on the left, address / PIN / "Show" on the right. */
@Composable
fun ConnectBlockWide(state: CastState, snackbar: SnackbarHostState, onShow: () -> Unit) {
    val url = state.primaryUrl
    val qrUrl = state.qrUrl
    if (url == null || qrUrl == null) {
        NoAddress(state)
        return
    }
    BoxWithConstraints {
        // Larger tablets get a larger code: it is scanned from across a table.
        val side = (maxWidth * 0.42f).coerceIn(164.dp, 320.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            QrPlate(qrUrl, side, onShow)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ui_address_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AddressText(url, snackbar, MaterialTheme.typography.titleMedium, TextAlign.Start, sidePadding = 0.dp)
                PinLine(state, centered = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChipButton(stringResource(R.string.ui_show_short), painterResource(R.drawable.ic_fullscreen), onShow)
                    ShareButton(url)
                }
            }
        }
    }
}

// ---------- Live: controls ----------

/** Pause (tonal) and Stop (tonal "on air" plaque colour). */
@Composable
fun LiveButtons(state: CastState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(
            text = stringResource(R.string.ui_pause),
            onClick = { CastController.pause(context) },
            container = scheme.surfaceContainerHigh,
            content = scheme.onSurface,
            icon = painterResource(R.drawable.ic_pause),
            enabled = state.phase == CastPhase.LIVE,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            text = stringResource(if (state.phase == CastPhase.STOPPING) R.string.ui_status_stopping else R.string.ui_stop),
            onClick = onStop,
            container = brand.liveContainer,
            content = brand.onLiveContainer,
            icon = painterResource(R.drawable.ic_stop),
            busy = state.phase == CastPhase.STOPPING,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Calm paused state: what viewers see, why it is paused, and the way back. */
@Composable
fun PausedPanel(state: CastState, modifier: Modifier = Modifier) {
    val brand = MaterialTheme.brand
    Column(
        modifier.widthIn(max = 480.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(brand.warnContainer)
                .border(1.5.dp, brand.warnOutline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null, tint = brand.warn, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.ui_paused_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (state.pause == PauseReason.LOCKED) R.string.ui_paused_body_locked else R.string.ui_paused_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Back on air" (the screen's one filled button) and "End broadcast". */
@Composable
fun PausedButtons(state: CastState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = MaterialTheme.brand
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigButton(
            text = stringResource(R.string.ui_resume),
            onClick = { CastController.resume(context) },
            container = brand.live,
            content = brand.onLive,
            icon = painterResource(R.drawable.ic_stat_cast),
            enabled = state.phase == CastPhase.LIVE,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = onStop,
            enabled = state.phase != CastPhase.STOPPING,
            modifier = Modifier.heightIn(min = 56.dp).padding(top = 4.dp).semantics { role = Role.Button },
        ) {
            Text(
                stringResource(R.string.ui_end_broadcast),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
