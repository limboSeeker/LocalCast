package com.localcast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.ui.theme.OverlineTextStyle
import com.localcast.app.ui.theme.brand

/** Surface card used across the app: white (light) / raised slate (dark), hairline border. */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.brand.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, MaterialTheme.brand.cardBorder),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** App mark on its brand-blue tile (top bar, idle hero). */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(MaterialTheme.brand.markStart, MaterialTheme.brand.markEnd))),
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

/** Small upper-case label above a value ("PIN", "THIS DEVICE"). */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = OverlineTextStyle, color = color, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

enum class Tone { INFO, WARN, ERROR }

/** Notice with an icon, so the meaning never rests on colour alone. */
@Composable
fun NoticeBox(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val brand = MaterialTheme.brand
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (tone) {
        Tone.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, painterResource(R.drawable.ic_lightbulb))
        Tone.WARN -> Triple(brand.warnContainer, brand.onWarnContainer, rememberVectorPainter(Icons.Filled.Warning))
        Tone.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, painterResource(R.drawable.ic_error))
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
    }
}

/** Non-interactive pill: icon + text on a tinted background. */
@Composable
fun InfoPill(
    text: String,
    icon: Painter?,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Filled circle with a number: step markers. */
@Composable
fun NumberBadge(n: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
