package com.das.p1stouch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Persisted as [com.das.p1stouch.data.PrinterConfig.themeMode]'s raw
 * "dark"/"light"/"system" string; this is the typed form the UI works with. */
enum class ThemeMode(val configValue: String) {
    DARK("dark"), LIGHT("light"), SYSTEM("system");

    companion object {
        fun fromConfigValue(value: String): ThemeMode = entries.firstOrNull { it.configValue == value } ?: DARK
    }
}

private val P1SDarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = OnAccentGreen,
    secondary = AccentGreen,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    error = DangerRed,
    outline = BorderDark,
)

private val P1SLightColorScheme = lightColorScheme(
    primary = AccentGreen,
    onPrimary = OnAccentGreen,
    secondary = AccentGreen,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    error = DangerRed,
    outline = BorderLight,
)

/**
 * The Python original is dark-only (a fixed kiosk display); this app adds a
 * Dark/Light/Match Phone choice (Settings screen), since a phone/tablet --
 * unlike a dedicated Pi touchscreen -- has its own system theme the user
 * already picked for a reason. Applies live via recomposition, no restart
 * needed (theme is a pure UI-layer concern, unlike the backend-affecting
 * settings that do require one).
 */
@Composable
fun P1STheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) P1SDarkColorScheme else P1SLightColorScheme,
        typography = P1STypography,
        content = content,
    )
}
