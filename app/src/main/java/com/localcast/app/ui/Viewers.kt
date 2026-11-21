package com.localcast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.service.ViewerInfo
import com.localcast.app.ui.theme.AddressSmallTextStyle
import com.localcast.app.ui.theme.brand

/** Per-viewer stream health, shown as icon + word + colour (never colour alone). */
enum class Health { GOOD, FAIR, POOR, LOST, CONNECTING }

/**
 * Judges delivered frame rate against the capture rate and the measured latency.
 * The expected rate is capped by what the encoder actually sends: a static screen is legitimately
 * sent at a few fps and must not read as a bad connection.
 */
fun viewerHealth(v: ViewerInfo, captureFps: Int?): Health {
    val hasStats = v.sender != null || v.viewer != null
    if (!v.connected) return if (hasStats) Health.LOST else Health.CONNECTING
    val received = v.viewer?.fps ?: v.sender?.fps
    val expected = listOfNotNull(
        captureFps?.takeIf { it > 0 }?.toDouble(),
        v.sender?.fps?.takeIf { it > 0 },
    ).minOrNull()
    val ratio = if (received != null && expected != null) received / expected else null
    val latency = v.viewer?.latencyMs
    val limited = v.sender?.qualityLimitation == "bandwidth" || v.sender?.qualityLimitation == "cpu"
    return when {
        (ratio != null && ratio < 0.5) || (latency != null && latency > 300) -> Health.POOR
        (ratio != null && ratio < 0.8) || (latency != null && latency > 150) || limited -> Health.FAIR
        else -> Health.GOOD
    }
}

@Composable
fun ViewersCard(viewers: List<ViewerInfo>, captureFps: Int?, modifier: Modifier = Modifier) {
    BrandCard(modifier) {
        val countCd = pluralStringResource(R.plurals.ui_viewers_count_cd, viewers.size, viewers.size)
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = countCd },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_group), null, tint = MaterialTheme.colorScheme.primary)
            SectionTitle(stringResource(R.string.ui_viewers_title), Modifier.weight(1f))
            Text(
                viewers.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (viewers.isEmpty()) {
            Text(
                stringResource(R.string.ui_viewers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        viewers.forEach { ViewerRow(it, captureFps) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerRow(v: ViewerInfo, captureFps: Int?) {
    val health = viewerHealth(v, captureFps)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(browserRes(v.userAgent)), style = MaterialTheme.typography.titleSmall)
                Text(v.remoteAddress, style = AddressSmallTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HealthPill(health)
        }
        val s = v.sender
        val vs = v.viewer
        val chips = buildList<@Composable () -> Unit> {
            (s?.codec ?: vs?.codec)?.let { add { Metric(stringResource(R.string.ui_metric_codec, it)) } }
            s?.hardwareEncoder?.let { hw ->
                add {
                    Metric(
                        stringResource(if (hw) R.string.ui_encoder_hardware else R.string.ui_encoder_software),
                        highlight = hw,
                    )
                }
            }
            (vs?.fps ?: s?.fps)?.let { add { Metric(stringResource(R.string.ui_metric_fps, it.toInt().toString())) } }
            (vs?.bitrateKbps ?: s?.bitrateKbps)?.let { add { Metric(stringResource(R.string.ui_metric_bitrate, it / 1000.0)) } }
            vs?.latencyMs?.let { add { Metric(stringResource(R.string.ui_metric_latency, it.toInt())) } }
            (vs?.rttMs ?: s?.rttMs)?.let { add { Metric(stringResource(R.string.ui_metric_rtt, it.toInt())) } }
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
private fun HealthPill(h: Health) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(
        when (h) {
            Health.GOOD -> R.string.ui_health_good
            Health.FAIR -> R.string.ui_health_fair
            Health.POOR -> R.string.ui_health_poor
            Health.LOST -> R.string.ui_health_lost
            Health.CONNECTING -> R.string.ui_health_connecting
        },
    )
    data class Look(val bg: Color, val fg: Color, val icon: Painter?)
    val look = when (h) {
        Health.GOOD -> Look(brand.goodContainer, brand.onGoodContainer, rememberVectorPainter(Icons.Filled.CheckCircle))
        Health.FAIR -> Look(brand.warnContainer, brand.onWarnContainer, rememberVectorPainter(Icons.Filled.Warning))
        Health.POOR, Health.LOST -> Look(brand.badContainer, brand.onBadContainer, painterResource(R.drawable.ic_error))
        Health.CONNECTING -> Look(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, null)
    }
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(look.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (look.icon != null) {
            Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(16.dp))
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = look.fg)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = look.fg)
    }
}

@Composable
private fun Metric(text: String, highlight: Boolean = false, warn: Boolean = false) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        warn -> brand.warnContainer
        highlight -> scheme.primaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val fg = when {
        warn -> brand.onWarnContainer
        highlight -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
