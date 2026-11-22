package com.localcast.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// «Ефір» v3. Depth comes from tone, not colour: saturated colour only on small marks (the tally
// lamp, health dots, icons); large areas are tonal containers. Contrast figures: efir_v3_tokens.md.

private val MarkStart = Color(0xFF3470FF)
private val MarkEnd = Color(0xFF1748DB)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCFAA),
    onPrimary = Color(0xFF0F3A26),
    primaryContainer = Color(0xFF223B2F),
    onPrimaryContainer = Color(0xFFBDE6CE),
    inversePrimary = Color(0xFF2B7450),
    secondary = Color(0xFFA4A9B1),
    onSecondary = Color(0xFF1E2024),
    secondaryContainer = Color(0xFF292C31),
    onSecondaryContainer = Color(0xFFDEE0E3),
    tertiary = Color(0xFFE2B058),
    onTertiary = Color(0xFF2B2110),
    tertiaryContainer = Color(0xFF3F3423),
    onTertiaryContainer = Color(0xFFF3DAA6),
    background = Color(0xFF1E2024),
    onBackground = Color(0xFFDEE0E3),
    surface = Color(0xFF1E2024),
    onSurface = Color(0xFFDEE0E3),
    surfaceVariant = Color(0xFF383C43),
    onSurfaceVariant = Color(0xFFA4A9B1),
    surfaceTint = Color(0xFF1E2024),
    inverseSurface = Color(0xFFDEE0E3),
    inverseOnSurface = Color(0xFF2A2D31),
    error = Color(0xFFF2938B),
    onError = Color(0xFF2E0B09),
    errorContainer = Color(0xFF4A272A),
    onErrorContainer = Color(0xFFFFC6C0),
    outline = Color(0xFF757C86),
    outlineVariant = Color(0xFF383C43),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383C43),
    surfaceDim = Color(0xFF1E2024),
    surfaceContainerLowest = Color(0xFF1A1C1F),
    surfaceContainerLow = Color(0xFF23262A),
    surfaceContainer = Color(0xFF292C31),
    surfaceContainerHigh = Color(0xFF30343A),
    surfaceContainerHighest = Color(0xFF383C43),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF2B7450),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7EDE0),
    onPrimaryContainer = Color(0xFF0E3A25),
    inversePrimary = Color(0xFF8CCFAA),
    secondary = Color(0xFF585F69),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECEEF1),
    onSecondaryContainer = Color(0xFF262A30),
    tertiary = Color(0xFFA8720C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E8C9),
    onTertiaryContainer = Color(0xFF4A3100),
    background = Color(0xFFF6F7F8),
    onBackground = Color(0xFF262A30),
    surface = Color(0xFFF6F7F8),
    onSurface = Color(0xFF262A30),
    surfaceVariant = Color(0xFFD9DDE2),
    onSurfaceVariant = Color(0xFF585F69),
    surfaceTint = Color(0xFFF6F7F8),
    inverseSurface = Color(0xFF2F3338),
    inverseOnSurface = Color(0xFFEEF0F2),
    error = Color(0xFFB3362F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE1DD),
    onErrorContainer = Color(0xFF6B1913),
    outline = Color(0xFF7A818B),
    outlineVariant = Color(0xFFDADEE3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9DDE2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE3E6EA),
    surfaceContainerHighest = Color(0xFFD9DDE2),
)

/** Colours Material3 has no slot for: the tally lamp, on-air plaques, health, the QR plate. */
@Immutable
data class BrandColors(
    /** Ring around "Start" and the selected quality tile. */
    val readyOutline: Color,
    /** The tally lamp: a small dot only, never a large area. */
    val liveDot: Color,
    val liveText: Color,
    /** "On air" plaque, Stop button, the lamp disc on tablets. */
    val liveContainer: Color,
    val onLiveContainer: Color,
    val liveOutline: Color,
    /** The one filled colour button: "Back on air". */
    val live: Color,
    val onLive: Color,
    val warn: Color,
    val warnText: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val onWarnContainerVariant: Color,
    val warnOutline: Color,
    val good: Color,
    /** QR codes stay dark-on-light in both themes: some scanners can't read inverted codes. */
    val qrBackground: Color,
    val qrModules: Color,
    /** Hairline around cards: light theme only (dark cards stand out by tone). */
    val cardBorder: Color,
    /** "Show to viewers" is always light, whatever the theme. */
    val showBackground: Color,
    /** Gradient of the brand tile behind the app mark (same as the launcher icon). */
    val markStart: Color,
    val markEnd: Color,
)

internal val DarkBrand = BrandColors(
    readyOutline = Color(0xFF4F7F67),
    liveDot = Color(0xFFEF746B),
    liveText = Color(0xFFF2938B),
    liveContainer = Color(0xFF4A272A),
    onLiveContainer = Color(0xFFFFC6C0),
    liveOutline = Color(0xFFA65650),
    live = Color(0xFFDC8A80),
    onLive = Color(0xFF2E0B09),
    warn = Color(0xFFE2B058),
    warnText = Color(0xFFE5BC6E),
    warnContainer = Color(0xFF3F3423),
    onWarnContainer = Color(0xFFF3DAA6),
    onWarnContainerVariant = Color(0xFFD6C29A),
    warnOutline = Color(0xFF8A7038),
    good = Color(0xFF7FC79F),
    qrBackground = Color(0xFFE6E4DF),
    qrModules = Color(0xFF1E2024),
    cardBorder = Color.Transparent,
    showBackground = Color(0xFFF4F2EE),
    markStart = MarkStart,
    markEnd = MarkEnd,
)

internal val LightBrand = BrandColors(
    readyOutline = Color(0xFF5A9677),
    liveDot = Color(0xFFD2463D),
    liveText = Color(0xFFB3362F),
    liveContainer = Color(0xFFFBE1DD),
    onLiveContainer = Color(0xFF6B1913),
    liveOutline = Color(0xFFCC6B62),
    live = Color(0xFFBE3D35),
    onLive = Color(0xFFFFFFFF),
    warn = Color(0xFFA8720C),
    warnText = Color(0xFF835600),
    warnContainer = Color(0xFFF7E8C9),
    onWarnContainer = Color(0xFF4A3100),
    onWarnContainerVariant = Color(0xFF6B5420),
    warnOutline = Color(0xFFA8823A),
    good = Color(0xFF2E8B5A),
    qrBackground = Color(0xFFFFFFFF),
    qrModules = Color(0xFF1E2024),
    cardBorder = Color(0xFFDADEE3),
    showBackground = Color(0xFFF4F2EE),
    markStart = MarkStart,
    markEnd = MarkEnd,
)

val LocalBrandColors = staticCompositionLocalOf { LightBrand }
