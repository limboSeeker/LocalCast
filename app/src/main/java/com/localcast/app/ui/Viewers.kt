package com.localcast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.service.HealthPolicy
import com.localcast.app.service.ViewerInfo
import com.localcast.app.ui.theme.brand
import com.localcast.app.ui.theme.tabular
import kotlin.math.roundToInt

/** Per-viewer stream health. Always shown with a word next to the coloured dot. */
enum class Health(val severity: Int) { GOOD(0), CONNECTING(1), FAIR(2), POOR(3), LOST(4) }

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
        (ratio != null && ratio < 0.8) || (latency != null && latency > HealthPolicy.HIGH_LATENCY_MS) || limited -> Health.FAIR
        else -> Health.GOOD
    }
}

/** Worst first: problems, then the highest latency. */
fun sortWorstFirst(viewers: List<ViewerInfo>, captureFps: Int?): List<ViewerInfo> =
    viewers.sortedWith(
        compareByDescending<ViewerInfo> { viewerHealth(it, captureFps).severity }
            .thenByDescending { it.viewer?.latencyMs ?: 0.0 },
    )

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

private val ANDROID_MODEL = Regex("""Android [^;)]*; ([^;)]+?)(?: Build/[^;)]*)?\)""")

/** "iPad", "Mac", "Pixel 9", "Smart TV"… from the user agent; null when it says nothing useful. */
internal fun deviceFromUserAgent(ua: String?): String? {
    val s = ua ?: return null
    return when {
        "iPad" in s -> "iPad"
        "iPhone" in s -> "iPhone"
        listOf("SMART-TV", "SmartTV", "Tizen", "Web0S", "WebOS", "BRAVIA", "AFT").any { it in s } -> "Smart TV"
        "CrOS" in s -> "Chromebook"
        "Android" in s -> ANDROID_MODEL.find(s)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length > 1 && it != "K" && !it.startsWith("wv") } ?: "Android"
        "Macintosh" in s -> "Mac"
        "Windows" in s -> "Windows"
        "Linux" in s -> "Linux"
        else -> null
    }
}

/** "MacBook · Chrome"-style name: device and browser. */
@Composable
fun viewerName(v: ViewerInfo): String {
    val browser = stringResource(browserRes(v.userAgent))
    val device = deviceFromUserAgent(v.userAgent)
    return if (device != null) stringResource(R.string.ui_viewer_name, device, browser) else browser
}

@Composable
private fun healthColor(h: Health): Color {
    val brand = MaterialTheme.brand
    return when (h) {
        Health.GOOD -> brand.good
        Health.FAIR -> brand.warn
        Health.POOR, Health.LOST -> brand.liveDot
        Health.CONNECTING -> MaterialTheme.colorScheme.outline
    }
}

@Composable
private fun healthWord(h: Health): String = stringResource(
    when (h) {
        Health.GOOD -> R.string.ui_viewer_ok
        Health.FAIR, Health.POOR -> R.string.ui_viewer_unstable
        Health.LOST -> R.string.ui_viewer_lost
        Health.CONNECTING -> R.string.ui_viewer_connecting
    },
)

private fun ViewerInfo.fps(): Int? = (viewer?.fps ?: sender?.fps)?.roundToInt()

/** One-line row (56 dp): dot, name, and on the right the frame rate or what's wrong. */
@Composable
fun ViewerRow(v: ViewerInfo, captureFps: Int?, modifier: Modifier = Modifier) {
    val health = viewerHealth(v, captureFps)
    val name = viewerName(v)
    val fps = v.fps()
    val fpsText = fps?.let { stringResource(R.string.ui_fps_value, it) }
    val right = when (health) {
        Health.GOOD -> fpsText
        Health.FAIR, Health.POOR -> listOfNotNull(healthWord(health), fpsText).joinToString(" · ")
        Health.LOST, Health.CONNECTING -> healthWord(health)
    }
    val cd = listOfNotNull(name, healthWord(health), fpsText.takeIf { health != Health.LOST }).joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics { contentDescription = cd }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(healthColor(health))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (right != null) {
            Text(
                right,
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = if (health == Health.GOOD || health == Health.CONNECTING) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.brand.warnText,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** Two-line row for the details: codec · encoder · resolution · bitrate; fps and latency on the right. */
@Composable
fun ViewerDetailRow(v: ViewerInfo, captureFps: Int?, deviceHasHardware: Boolean, modifier: Modifier = Modifier) {
    val health = viewerHealth(v, captureFps)
    val s = v.sender
    val vs = v.viewer
    val encoder = s?.hardwareEncoder?.let { hw ->
        stringResource(
            when {
                hw -> R.string.ui_encoder_hw
                deviceHasHardware -> R.string.ui_encoder_lite
                else -> R.string.ui_encoder_sw
            },
        )
    }
    val height = s?.height ?: vs?.height
    val bitrate = (s?.bitrateKbps ?: vs?.bitrateKbps)?.let { stringResource(R.string.ui_mbps_value, mbps(it.roundToInt())) }
    val line2 = listOfNotNull(
        (s?.codec ?: vs?.codec)?.let { if (it.equals("H264", true)) "H.264" else it },
        encoder,
        height?.let { stringResource(R.string.ui_resolution_p, it) },
        bitrate,
        if (health == Health.LOST || health == Health.CONNECTING) healthWord(health) else null,
    ).joinToString(" · ").ifEmpty { v.remoteAddress }
    val fps = v.fps()
    val latency = vs?.latencyMs?.roundToInt()
    val warn = MaterialTheme.brand.warnText
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val name = viewerName(v)
    val cd = listOfNotNull(
        name,
        healthWord(health),
        line2,
        fps?.let { stringResource(R.string.ui_fps_value, it) },
        latency?.let { stringResource(R.string.ui_ms_value, it) },
    ).joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clearAndSetSemantics { contentDescription = cd }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Dot(healthColor(health))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line2, style = MaterialTheme.typography.bodyMedium.tabular(), color = normal)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (fps != null) {
                Text(
                    stringResource(R.string.ui_fps_value, fps),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = if (health == Health.FAIR || health == Health.POOR) warn else normal,
                )
            }
            if (latency != null) {
                Text(
                    stringResource(R.string.ui_ms_value, latency),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = if (latency > HealthPolicy.HIGH_LATENCY_MS) warn else normal,
                )
            }
        }
    }
}

/** List of one-line rows separated by hairlines, inside a panel. */
@Composable
fun ViewerList(viewers: List<ViewerInfo>, captureFps: Int?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        if (viewers.isEmpty()) {
            Text(
                stringResource(R.string.ui_viewers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        viewers.forEachIndexed { i, v ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ViewerRow(v, captureFps)
        }
    }
}

/** kbps → Mbps for display: one decimal below 10, whole numbers above. */
fun mbps(kbps: Int): String {
    val m = kbps / 1000.0
    return if (m < 10 && m != m.roundToInt().toDouble()) String.format(java.util.Locale.getDefault(), "%.1f", m) else m.roundToInt().toString()
}
