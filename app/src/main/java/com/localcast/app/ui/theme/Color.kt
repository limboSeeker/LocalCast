package com.localcast.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand core. "Signal blue" is the action colour, "on air" coral marks a live broadcast.
val SignalBlue = Color(0xFF1E5EFF)
val OnAirCoral = Color(0xFFFF4F5E)
private val MarkStart = Color(0xFF3470FF)
private val MarkEnd = Color(0xFF1748DB)

internal val LightColors = lightColorScheme(
    primary = SignalBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174D),
    inversePrimary = Color(0xFFB4C5FF),
    secondary = Color(0xFF4C5A7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3F7),
    onSecondaryContainer = Color(0xFF0A1730),
    tertiary = Color(0xFFB3213A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDADC),
    onTertiaryContainer = Color(0xFF40000D),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF161A23),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF161A23),
    surfaceVariant = Color(0xFFDFE3EE),
    onSurfaceVariant = Color(0xFF444B5C),
    surfaceTint = SignalBlue,
    inverseSurface = Color(0xFF2B303B),
    inverseOnSurface = Color(0xFFEEF0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF737A8C),
    outlineVariant = Color(0xFFC3C8D6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6F7FB),
    surfaceDim = Color(0xFFD6D9E2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFEAEDF4),
    surfaceContainerHigh = Color(0xFFE4E7EF),
    surfaceContainerHighest = Color(0xFFDEE2EA),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF7FA0FF),
    onPrimary = Color(0xFF001A52),
    primaryContainer = Color(0xFF1A44C2),
    onPrimaryContainer = Color(0xFFDCE4FF),
    inversePrimary = SignalBlue,
    secondary = Color(0xFFBEC6E0),
    onSecondary = Color(0xFF283149),
    secondaryContainer = Color(0xFF3E4760),
    onSecondaryContainer = Color(0xFFDAE2FD),
    tertiary = Color(0xFFFFB2B6),
    onTertiary = Color(0xFF670019),
    tertiaryContainer = Color(0xFF8C1029),
    onTertiaryContainer = Color(0xFFFFDADC),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE3E6EE),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = Color(0xFF3F4555),
    onSurfaceVariant = Color(0xFFBFC5D3),
    surfaceTint = Color(0xFF7FA0FF),
    inverseSurface = Color(0xFFE3E6EE),
    inverseOnSurface = Color(0xFF2B303B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A91A2),
    outlineVariant = Color(0xFF3F4555),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B47),
    surfaceDim = Color(0xFF0E1117),
    surfaceContainerLowest = Color(0xFF0A0C11),
    surfaceContainerLow = Color(0xFF161A21),
    surfaceContainer = Color(0xFF1A1E26),
    surfaceContainerHigh = Color(0xFF242933),
    surfaceContainerHighest = Color(0xFF2F3440),
)

/** Brand colours Material3 has no slot for: live badge, stream health, the QR plate. */
@Immutable
data class BrandColors(
    /** Solid "on air" fill (badge, stop button). */
    val live: Color,
    val onLive: Color,
    /** Accent dot / pulse; always the pure brand coral. */
    val liveDot: Color,
    val good: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val onBadContainer: Color,
    /** QR codes stay dark-on-white in both themes: some scanners can't read inverted codes. */
    val qrBackground: Color,
    val card: Color,
    val cardBorder: Color,
    /** Gradient of the brand tile behind the app mark (same as the launcher icon). */
    val markStart: Color,
    val markEnd: Color,
)

// Light: coral deepened to #DC2F45 so white text keeps 4.5:1.
internal val LightBrand = BrandColors(
    live = Color(0xFFDC2F45),
    onLive = Color(0xFFFFFFFF),
    liveDot = OnAirCoral,
    good = Color(0xFF15803D),
    goodContainer = Color(0xFFD8F5E1),
    onGoodContainer = Color(0xFF0B3D1E),
    warn = Color(0xFFB45309),
    warnContainer = Color(0xFFFFE8C7),
    onWarnContainer = Color(0xFF4A2300),
    bad = Color(0xFFC81E3A),
    badContainer = Color(0xFFFFDADC),
    onBadContainer = Color(0xFF40000D),
    qrBackground = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E5EE),
    markStart = MarkStart,
    markEnd = MarkEnd,
)

// Dark: brand coral as-is with dark text (6:1).
internal val DarkBrand = BrandColors(
    live = OnAirCoral,
    onLive = Color(0xFF2B0008),
    liveDot = OnAirCoral,
    good = Color(0xFF5EDB8A),
    goodContainer = Color(0xFF12391F),
    onGoodContainer = Color(0xFFC4F2D2),
    warn = Color(0xFFFFB547),
    warnContainer = Color(0xFF4A3000),
    onWarnContainer = Color(0xFFFFDDB0),
    bad = Color(0xFFFF8A94),
    badContainer = Color(0xFF5C1120),
    onBadContainer = Color(0xFFFFDADC),
    qrBackground = Color(0xFFFFFFFF),
    card = Color(0xFF171B23),
    cardBorder = Color(0xFF252A35),
    markStart = MarkStart,
    markEnd = MarkEnd,
)

val LocalBrandColors = staticCompositionLocalOf { LightBrand }
