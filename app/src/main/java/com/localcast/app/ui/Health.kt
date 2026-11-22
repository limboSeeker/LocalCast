package com.localcast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.service.BroadcastHealth
import com.localcast.app.service.CastState
import com.localcast.app.service.HealthIssue
import com.localcast.app.service.HealthLevel
import com.localcast.app.service.HealthPolicy
import com.localcast.app.ui.theme.brand
import com.localcast.app.ui.theme.tabular

private enum class Severity { IDLE, OK, WARN, CRITICAL }

/** The one line the card leads with: icon + title (+ explanation). Never colour alone. */
private class HealthMessage(val severity: Severity, val title: String, val body: String?)

/** Health as reported by the service, or a stand-in from the viewer list until the first report. */
private fun CastState.healthOrFallback(): BroadcastHealth = health ?: BroadcastHealth(
    level = HealthLevel.OK,
    issue = HealthIssue.NONE,
    viewers = viewers.size,
    liteViewers = 0,
    turnedAway = 0,
    totalKbps = viewers.filter { it.connected }.sumOf { it.sender?.bitrateKbps ?: 0.0 }.toInt(),
    linkKbps = 0,
    worstLatencyMs = viewers.mapNotNull { it.viewer?.latencyMs }.maxOrNull()?.toInt(),
    thermalLevel = capture?.thermalLevel ?: 0,
    hardwareEncoders = 0,
)

/** Priority: overheating > device limit > network > hot > one slow viewer > all good. */
@Composable
private fun healthMessage(state: CastState, h: BroadcastHealth): HealthMessage {
    return when {
        h.thermalLevel >= 3 -> HealthMessage(
            Severity.CRITICAL,
            stringResource(R.string.ui_health_critical_title),
            stringResource(R.string.ui_health_critical_body),
        )
        h.issue == HealthIssue.AT_CAPACITY -> HealthMessage(
            Severity.WARN,
            stringResource(R.string.ui_health_capacity_title),
            pluralStringResource(R.plurals.ui_health_capacity_body, h.turnedAway, h.turnedAway),
        )
        h.issue == HealthIssue.NETWORK -> {
            val lowest = state.viewers.filter { it.connected }.mapNotNull { it.sender?.height }.minOrNull()
            HealthMessage(
                Severity.WARN,
                stringResource(R.string.ui_health_network_title),
                lowest?.let { stringResource(R.string.ui_health_network_body, stringResource(R.string.ui_resolution_p, it)) },
            )
        }
        h.thermalLevel == 2 -> HealthMessage(
            Severity.WARN,
            stringResource(R.string.ui_health_hot_title),
            stringResource(R.string.ui_health_hot_body),
        )
        // Warm (level 1) only lowers 60 -> 30 fps and is common on a tablet in use: shown in the
        // heat metric, not as a warning (same as HealthPolicy).
        h.issue == HealthIssue.LATENCY -> {
            val worst = state.viewers.filter { it.connected }.maxByOrNull { it.viewer?.latencyMs ?: 0.0 }
            HealthMessage(
                Severity.WARN,
                if (worst != null) stringResource(R.string.ui_health_viewer_title, viewerName(worst))
                else stringResource(R.string.ui_health_latency_title),
                h.worstLatencyMs?.let { stringResource(R.string.ui_health_viewer_body, it) },
            )
        }
        state.viewers.isEmpty() -> HealthMessage(Severity.IDLE, stringResource(R.string.ui_viewers_empty), null)
        else -> HealthMessage(Severity.OK, stringResource(R.string.ui_health_ok), null)
    }
}

@Composable
private fun severityIcon(s: Severity): Pair<Painter, Color> {
    val brand = MaterialTheme.brand
    return when (s) {
        Severity.IDLE -> painterResource(R.drawable.ic_group) to MaterialTheme.colorScheme.onSurfaceVariant
        Severity.OK -> rememberVectorPainter(Icons.Filled.CheckCircle) to brand.good
        Severity.WARN -> rememberVectorPainter(Icons.Filled.Warning) to brand.warn
        Severity.CRITICAL -> painterResource(R.drawable.ic_error) to brand.liveDot
    }
}

/** Container / title / body colours of the status line for each level. */
@Composable
private fun severityColors(s: Severity, neutral: Color): Triple<Color, Color, Color> {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    return when (s) {
        Severity.IDLE, Severity.OK -> Triple(neutral, scheme.onSurface, scheme.onSurfaceVariant)
        Severity.WARN -> Triple(brand.warnContainer, brand.onWarnContainer, brand.onWarnContainerVariant)
        Severity.CRITICAL -> Triple(brand.liveContainer, brand.onLiveContainer, brand.onLiveContainer)
    }
}

@Composable
private fun StatusLine(msg: HealthMessage, neutral: Color, chevron: Boolean, modifier: Modifier = Modifier) {
    val (icon, tint) = severityIcon(msg.severity)
    val (bg, fg, sub) = severityColors(msg.severity, neutral)
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).align(Alignment.Top).padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(msg.title, style = MaterialTheme.typography.labelLarge, color = fg)
            if (msg.body != null) Text(msg.body, style = MaterialTheme.typography.bodyMedium, color = sub)
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class Metric(val value: String, val label: String, val extra: String?, val warn: Boolean)

@Composable
private fun heatWord(level: Int): String = stringResource(
    when (level) {
        0 -> R.string.ui_heat_normal
        1 -> R.string.ui_heat_warm
        2 -> R.string.ui_heat_hot
        else -> R.string.ui_heat_critical
    },
)

private fun BroadcastHealth.linkBusy(): Boolean =
    viewers > 1 && linkKbps > 0 && totalKbps >= HealthPolicy.NETWORK_BUSY_SHARE * linkKbps

@Composable
private fun metrics(h: BroadcastHealth): List<Metric> {
    val dash = stringResource(R.string.ui_no_value)
    return listOf(
        Metric(
            h.viewers.toString(),
            pluralStringResource(R.plurals.ui_metric_viewers, h.viewers),
            if (h.liteViewers > 0) pluralStringResource(R.plurals.ui_viewers_lite, h.liteViewers, h.liteViewers) else null,
            warn = false,
        ),
        Metric(
            if (h.linkKbps > 0) stringResource(R.string.ui_ratio, mbps(h.totalKbps), mbps(h.linkKbps)) else mbps(h.totalKbps),
            stringResource(R.string.ui_metric_mbps),
            null,
            warn = h.linkBusy(),
        ),
        Metric(
            h.worstLatencyMs?.let { stringResource(R.string.ui_ms_value, it) } ?: dash,
            stringResource(R.string.ui_metric_latency),
            null,
            warn = (h.worstLatencyMs ?: 0) > HealthPolicy.HIGH_LATENCY_MS,
        ),
        Metric(heatWord(h.thermalLevel), stringResource(R.string.ui_metric_heat), null, warn = h.thermalLevel >= 1),
    )
}

@Composable
private fun MetricCell(m: Metric, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(
            m.value,
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = if (m.warn) MaterialTheme.brand.warnText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(m.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.extra != null) {
            Text(m.extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Four metrics in a row; 2×2 when the space (or large text) does not fit four. */
@Composable
private fun MetricsGrid(items: List<Metric>, forceGrid: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val oneRow = !forceGrid && maxWidth / 4 >= 76.dp * fontScale
        if (oneRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { MetricCell(it, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { MetricCell(it, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * «Broadcast status» card: status line, four metrics and up to two worst viewers.
 * Tapping it opens the details sheet.
 */
@Composable
fun HealthCard(
    state: CastState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    grid: Boolean = false,
    maxViewers: Int = 2,
) {
    val h = state.healthOrFallback()
    val msg = healthMessage(state, h)
    val captureFps = state.capture?.fps
    val cd = stringResource(R.string.ui_health_cd, msg.title)
    val neutral = MaterialTheme.colorScheme.surfaceContainerLow
    Panel(
        modifier.semantics { contentDescription = cd },
        onClick = onOpen,
        onClickLabel = stringResource(R.string.ui_health_open),
    ) {
        StatusLine(msg, neutral, chevron = true)
        if (state.viewers.isNotEmpty()) {
            MetricsGrid(metrics(h), forceGrid = grid)
            sortWorstFirst(state.viewers, captureFps).take(maxViewers).forEach { v ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ViewerRow(v, captureFps)
            }
        }
    }
}

// ---------- Details sheet ----------

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier = Modifier, valueWarn: Boolean = false, extra: @Composable (() -> Unit)? = null) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .heightIn(min = 88.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.tabular(),
            color = if (valueWarn) MaterialTheme.brand.warnText else MaterialTheme.colorScheme.onSurface,
        )
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSheet(state: CastState, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        HealthDetails(state)
    }
}

/** Contents of the details sheet (also reusable in a wide pane). */
@Composable
fun HealthDetails(state: CastState) {
    val h = state.healthOrFallback()
    val msg = healthMessage(state, h)
    val captureFps = state.capture?.fps
    val dash = stringResource(R.string.ui_no_value)
    val connected = state.viewers.filter { it.connected }
    val hw = connected.count { it.sender?.hardwareEncoder == true }
    val sw = connected.count { it.sender?.hardwareEncoder == false }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(
            stringResource(R.string.ui_health_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).semantics { heading() },
        )
        StatusLine(
            msg,
            MaterialTheme.colorScheme.surfaceContainer,
            chevron = false,
            modifier = Modifier.padding(horizontal = 24.dp).clip(MaterialTheme.shapes.medium),
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    stringResource(R.string.ui_tile_viewers),
                    h.viewers.toString(),
                    if (h.liteViewers > 0) pluralStringResource(R.plurals.ui_viewers_lite, h.liteViewers, h.liteViewers) else null,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                val share = if (h.linkKbps > 0) (h.totalKbps.toFloat() / h.linkKbps).coerceIn(0f, 1f) else null
                Tile(
                    stringResource(R.string.ui_tile_bandwidth),
                    if (h.linkKbps > 0) stringResource(R.string.ui_ratio_spaced, mbps(h.totalKbps), mbps(h.linkKbps)) else mbps(h.totalKbps),
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                    valueWarn = h.linkBusy(),
                ) {
                    if (share != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { share },
                            color = if (h.linkBusy()) MaterialTheme.brand.warn else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val worst = connected.filter { it.viewer?.latencyMs != null }.maxByOrNull { it.viewer?.latencyMs ?: 0.0 }
                Tile(
                    stringResource(R.string.ui_tile_latency),
                    h.worstLatencyMs?.let { stringResource(R.string.ui_ms_value, it) } ?: dash,
                    worst?.let { viewerName(it) },
                    Modifier.weight(1f).fillMaxHeight(),
                    valueWarn = (h.worstLatencyMs ?: 0) > HealthPolicy.HIGH_LATENCY_MS,
                )
                Tile(
                    stringResource(R.string.ui_tile_heat),
                    heatWord(h.thermalLevel),
                    if (h.thermalLevel == 0 || captureFps == null) stringResource(R.string.ui_heat_no_limits)
                    else stringResource(R.string.ui_heat_limited, captureFps),
                    Modifier.weight(1f).fillMaxHeight(),
                    valueWarn = h.thermalLevel >= 1,
                )
            }
            if (hw + sw > 0) {
                Text(
                    stringResource(R.string.ui_encoders_summary, hw, sw),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionLabel(stringResource(R.string.ui_viewers_section, state.viewers.size), Modifier.padding(top = 4.dp))
        }
        if (state.viewers.isEmpty()) {
            Text(
                stringResource(R.string.ui_viewers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        sortWorstFirst(state.viewers, captureFps).forEachIndexed { i, v ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ViewerDetailRow(v, captureFps, deviceHasHardware = h.hardwareEncoders > 0 || hw > 0)
        }
        val others = state.addresses.drop(1)
        val port = state.port
        if (others.isNotEmpty() && port != null) {
            SectionLabel(
                stringResource(R.string.ui_other_addresses),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
            others.forEach { a ->
                Text(
                    "${a.ip}:$port",
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }
}
