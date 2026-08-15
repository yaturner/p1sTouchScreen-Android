package com.das.p1stouch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val P1SColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = BackgroundDark,
    secondary = AccentGreen,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    outline = BorderDark,
)

/**
 * This app is dark-only by design (matches the Python original's fixed dark
 * kiosk theme), so it ignores [isSystemInDarkTheme] rather than branching on it.
 */
@Composable
fun P1STheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = P1SColorScheme,
        typography = P1STypography,
        content = content,
    )
}
