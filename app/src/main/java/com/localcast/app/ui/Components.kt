package com.localcast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.ui.theme.brand

/** Panel on the background: tonal in dark, white with a hairline in light. Radius 16, no shadow. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val border = MaterialTheme.brand.cardBorder
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .then(if (border.alpha > 0f) Modifier.border(1.dp, border, shape) else Modifier),
        content = content,
    )
}

/** Small round status mark (10 dp): health of a viewer, the tally lamp in a plaque. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Plaque with a lamp: "On air", "Paused", "Starting…". Full radius, tonal. */
@Composable
fun StatusPlaque(
    text: String,
    container: Color,
    content: Color,
    lamp: Color?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lamp != null) {
            // Static on purpose: this screen is itself being cast, and an endless animation would
            // keep the encoder and Wi-Fi busy at full frame rate while nothing else changes.
            Dot(lamp)
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Section heading inside a screen or sheet ("Access", "Viewers · 3"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** 56 dp pill button. At most one filled colour button per screen; the rest are tonal. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.6f),
            disabledContentColor = content.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        elevation = null,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = content)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tonal chip-sized action ("Show to viewers"): 40 dp visual, 48 dp touch target. */
@Composable
fun ChipButton(
    text: String,
    icon: Painter?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Non-interactive chip: icon + text on the tonal container (network, quality). */
@Composable
fun InfoChip(text: String, icon: Painter?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

enum class Tone { WARN, ERROR }

/** Notice with an icon, so the meaning never rests on colour alone. */
@Composable
fun NoticeBox(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val brand = MaterialTheme.brand
    val bg: Color
    val fg: Color
    val sub: Color
    val iconTint: Color
    val icon: Painter
    when (tone) {
        Tone.WARN -> {
            bg = brand.warnContainer; fg = brand.onWarnContainer; sub = brand.onWarnContainerVariant
            iconTint = brand.warn; icon = rememberVectorPainter(Icons.Filled.Warning)
        }
        Tone.ERROR -> {
            bg = brand.liveContainer; fg = brand.onLiveContainer; sub = brand.onLiveContainer
            iconTint = brand.liveDot; icon = painterResource(R.drawable.ic_error)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (title != null) sub else fg)
        }
    }
}

/** App mark on its brand tile (About). */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Surface(
        modifier.size(size),
        shape = RoundedCornerShape(size * 0.3f),
        color = Color.Transparent,
        border = BorderStroke(0.dp, Color.Transparent),
    ) {
        Box(
            Modifier.background(Brush.linearGradient(listOf(MaterialTheme.brand.markStart, MaterialTheme.brand.markEnd))),
            contentAlignment = Alignment.Center,
        ) {
            // The adaptive-icon foreground keeps its 66/108 safe zone: scale it up so the mark fills the tile.
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(size * 1.55f),
            )
        }
    }
}
