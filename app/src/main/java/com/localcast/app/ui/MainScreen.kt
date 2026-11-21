package com.localcast.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.R
import com.localcast.app.service.AddressKind
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.service.CastStateStore
import com.localcast.app.service.ViewerInfo
import kotlinx.coroutines.launch
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(snackbarHostState: SnackbarHostState, onStart: () -> Unit) {
    val context = LocalContext.current
    val state by CastStateStore.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ui_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            val scroll = rememberScrollState()
            val statusCard: @Composable () -> Unit = {
                StatusCard(state, onStart = onStart, onStop = { CastController.stop(context) })
            }
            val rest: @Composable () -> Unit = {
                if (state.isActive) {
                    ConnectionCard(state, snackbarHostState)
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        statusCard()
                        if (state.isActive) ViewersCard(state.viewers)
                        TipsCard()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        rest()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    statusCard()
                    rest()
                    if (state.isActive) ViewersCard(state.viewers)
                    TipsCard()
                }
            }
        }
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

// ---------- Status ----------

@Composable
private fun StatusCard(state: CastState, onStart: () -> Unit, onStop: () -> Unit) {
    val live = state.phase == CastPhase.LIVE
    val error = state.phase == CastPhase.ERROR
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                error -> MaterialTheme.colorScheme.errorContainer
                live -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    CastPhase.STARTING, CastPhase.STOPPING ->
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    CastPhase.ERROR -> Icon(
                        Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error,
                    )
                    CastPhase.LIVE -> Box(
                        Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(Color(0xFF16A34A)),
                    )
                    else -> Box(
                        Modifier.size(14.dp).clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
                Column {
                    Text(
                        stringResource(
                            when (state.phase) {
                                CastPhase.IDLE -> R.string.ui_status_idle
                                CastPhase.STARTING -> R.string.ui_status_starting
                                CastPhase.LIVE -> R.string.ui_status_live
                                CastPhase.STOPPING -> R.string.ui_status_stopping
                                CastPhase.ERROR -> R.string.ui_status_error
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val cap = state.capture
                    if (live && cap != null) {
                        Text(
                            stringResource(R.string.ui_capture_info, cap.width, cap.height, cap.fps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (error) {
                Text(
                    state.error ?: stringResource(R.string.ui_error_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (state.isActive) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.ui_stop), style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(
                    onClick = onStart,
                    enabled = state.phase != CastPhase.STOPPING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_start), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ---------- Connection ----------

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

@Composable
private fun ConnectionCard(state: CastState, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = state.primaryUrl
    val shown = url?.removeSuffix("/")
    val copiedText = stringResource(R.string.ui_copied)
    val shareTitle = stringResource(R.string.ui_share_title)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (url == null) {
                WarningBox(stringResource(R.string.ui_no_network))
                return@Column
            }
            Text(stringResource(R.string.ui_connect_hint), style = MaterialTheme.typography.bodyMedium)
            Text(
                shown ?: "",
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("LocalCast", url))
                        scope.launch { snackbar.showSnackbar(copiedText) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ui_copy)) }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, url)
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.ui_other_addresses),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (a in others) {
                        Text(
                            stringResource(R.string.ui_address_with_kind, "http://${a.ip}:$port", kindLabel(a.kind)),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }

            val qrUrl = state.qrUrl
            if (qrUrl != null) {
                val qr by rememberQrBitmap(qrUrl)
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        qr?.let {
                            Image(
                                bitmap = it,
                                contentDescription = stringResource(R.string.ui_qr_description),
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.fillMaxSize(),
                            )
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

            if (state.pinRequired) {
                val pin = state.pin
                if (pin != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.ui_pin_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatPin(pin),
                                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        TextButton(
                            onClick = { CastController.regeneratePin(context) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_pin_new))
                        }
                    }
                }
            } else {
                InfoBox(stringResource(R.string.ui_pin_disabled))
            }
        }
    }
}

private fun formatPin(pin: String): String =
    if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin

@Composable
private fun WarningBox(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun InfoBox(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

// ---------- Viewers ----------

@Composable
private fun ViewersCard(viewers: List<ViewerInfo>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.ui_viewers_title, viewers.size),
                style = MaterialTheme.typography.titleMedium,
            )
            if (viewers.isEmpty()) {
                Text(
                    stringResource(R.string.ui_viewers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            viewers.forEach { ViewerRow(it) }
        }
    }
}

private fun browserRes(ua: String?): Int {
    val s = ua ?: return R.string.ui_browser_other
    return when {
        "Edg/" in s || "EdgA/" in s || "EdgiOS/" in s -> R.string.ui_browser_edge
        "SamsungBrowser" in s -> R.string.ui_browser_samsung
        "Firefox/" in s || "FxiOS/" in s -> R.string.ui_browser_firefox
        "Chrome/" in s || "CriOS/" in s -> R.string.ui_browser_chrome
        "Safari/" in s -> R.string.ui_browser_safari
        else -> R.string.ui_browser_other
    }
}

private fun mbps(kbps: Double): String = String.format(Locale.US, "%.1f", kbps / 1000.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerRow(v: ViewerInfo) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.ui_viewer_ip_browser, v.remoteAddress, stringResource(browserRes(v.userAgent))),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (v.connected) R.string.ui_viewer_connected else R.string.ui_viewer_connecting),
                style = MaterialTheme.typography.labelLarge,
                color = if (v.connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val s = v.sender
        val vs = v.viewer
        val chips = buildList<@Composable () -> Unit> {
            val codec = s?.codec ?: vs?.codec
            if (codec != null) add { Metric(stringResource(R.string.ui_metric_codec, codec)) }
            s?.encoderImplementation?.let { add { Metric(stringResource(R.string.ui_metric_encoder, it)) } }
            s?.hardwareEncoder?.let { hw ->
                add {
                    Metric(
                        stringResource(if (hw) R.string.ui_encoder_hardware else R.string.ui_encoder_software),
                        highlight = hw,
                    )
                }
            }
            (s?.fps ?: vs?.fps)?.let { add { Metric(stringResource(R.string.ui_metric_fps, it.toInt().toString())) } }
            (s?.bitrateKbps ?: vs?.bitrateKbps)?.let {
                add { Metric(stringResource(R.string.ui_metric_bitrate, mbps(it))) }
            }
            vs?.latencyMs?.let { add { Metric(stringResource(R.string.ui_metric_latency, it.toInt())) } }
            (s?.rttMs ?: vs?.rttMs)?.let { add { Metric(stringResource(R.string.ui_metric_rtt, it.toInt())) } }
            when (s?.qualityLimitation) {
                "bandwidth" -> add { Metric(stringResource(R.string.ui_limit_bandwidth), warn = true) }
                "cpu" -> add { Metric(stringResource(R.string.ui_limit_cpu), warn = true) }
            }
        }
        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { chips.forEach { it() } }
        }
    }
}

@Composable
private fun Metric(text: String, highlight: Boolean = false, warn: Boolean = false) {
    val bg = when {
        warn -> MaterialTheme.colorScheme.errorContainer
        highlight -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val fg = when {
        warn -> MaterialTheme.colorScheme.onErrorContainer
        highlight -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------- Tips ----------

@Composable
private fun TipsCard() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    var batteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) batteryOptimized = isBatteryOptimized(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ui_tips_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (expanded) R.string.ui_tips_collapse else R.string.ui_tips_expand),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Text(stringResource(R.string.ui_tip_latency), style = MaterialTheme.typography.bodyMedium)
                if (batteryOptimized) {
                    Text(stringResource(R.string.ui_tip_battery), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { openBatterySettings(context) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.ui_tip_battery_action)) }
                }
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}
