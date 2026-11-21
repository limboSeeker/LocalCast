package com.localcast.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val LocalCastShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Brand theme. Dynamic colour is intentionally off: the blue/coral pair is the product's identity
 * and the live/health colours are tuned against these exact surfaces.
 */
@Composable
fun LocalCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = LocalCastTypography,
            shapes = LocalCastShapes,
            content = content,
        )
    }
}

/** `MaterialTheme.brand.live` etc. */
val MaterialTheme.brand: BrandColors
    @Composable @ReadOnlyComposable get() = LocalBrandColors.current
