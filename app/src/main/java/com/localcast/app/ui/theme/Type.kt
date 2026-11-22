package com.localcast.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// System font (Roboto, or the OEM / user font), two weights only: Regular 400 and Medium 500.
// Bold light-on-dark reads harsher; 500 is dense enough.
private val Sans = FontFamily.Default
private val Regular = FontWeight.Normal
private val Medium = FontWeight.Medium

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

// Scale 44 / 28 / 22 / 18 / 16 / 14 / 12 sp. Nothing below 12 sp; body text not below 14 sp.
internal val LocalCastTypography = Typography(
    displayLarge = style(44, 52, Medium, -0.02),
    displayMedium = style(44, 52, Medium, -0.02),
    displaySmall = style(44, 52, Medium, -0.02),
    headlineLarge = style(28, 36, Medium, -0.01),
    headlineMedium = style(28, 36, Medium, -0.01),
    headlineSmall = style(28, 36, Medium, -0.01),
    titleLarge = style(22, 28, Medium, -0.01),
    titleMedium = style(18, 24, Medium),
    titleSmall = style(16, 24, Medium),
    bodyLarge = style(16, 24, Regular),
    bodyMedium = style(14, 20, Regular),
    bodySmall = style(12, 16, Regular, 0.01),
    labelLarge = style(16, 20, Medium),
    labelMedium = style(14, 20, Medium),
    labelSmall = style(12, 16, Medium, 0.01),
)

/** Tabular figures: the timer, IP, PIN and metrics keep their width as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** PIN digits: tabular and slightly opened up; the gap between triads is a real space. */
fun TextStyle.pin(): TextStyle = copy(fontFeatureSettings = "tnum", letterSpacing = 0.04.em)
