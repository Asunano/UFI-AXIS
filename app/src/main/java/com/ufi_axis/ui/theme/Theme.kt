package com.ufi_axis.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Derive a Material 3 color scheme from a ResolvedPalette.
 * This ensures M3 components (that use MaterialTheme.colorScheme) still work
 * with the multi-theme system.
 */
private fun buildColorSchemeFromPalette(resolved: ResolvedPalette): ColorScheme {
    return if (resolved.isDark) {
        darkColorScheme(
            primary = resolved.accent,
            onPrimary = Color.White,
            primaryContainer = resolved.accent.copy(alpha = 0.2f),
            onPrimaryContainer = resolved.textPrimary,
            secondary = resolved.accentSecondary,
            onSecondary = Color.White,
            secondaryContainer = resolved.accent.copy(alpha = 0.15f),
            onSecondaryContainer = resolved.textPrimary,
            tertiary = resolved.accentSecondary,
            onTertiary = Color.White,
            tertiaryContainer = resolved.accent.copy(alpha = 0.15f),
            onTertiaryContainer = resolved.textPrimary,
            error = resolved.error,
            onError = Color.White,
            errorContainer = resolved.errorContainer,
            onErrorContainer = resolved.error,
            background = resolved.pageBg,
            onBackground = resolved.textPrimary,
            surface = resolved.cardBg,
            onSurface = resolved.textPrimary,
            surfaceVariant = resolved.cardBg.copy(alpha = 0.7f),
            onSurfaceVariant = resolved.textSecondary,
            outline = resolved.textSecondary,
            outlineVariant = resolved.divider
        )
    } else {
        lightColorScheme(
            primary = resolved.accent,
            onPrimary = Color.White,
            primaryContainer = resolved.accent.copy(alpha = 0.1f),
            onPrimaryContainer = resolved.textPrimary,
            secondary = resolved.accentSecondary,
            onSecondary = Color.White,
            secondaryContainer = resolved.accent.copy(alpha = 0.08f),
            onSecondaryContainer = resolved.textPrimary,
            tertiary = resolved.accentSecondary,
            onTertiary = Color.White,
            tertiaryContainer = resolved.accent.copy(alpha = 0.08f),
            onTertiaryContainer = resolved.textPrimary,
            error = resolved.error,
            onError = Color.White,
            errorContainer = resolved.errorContainer,
            onErrorContainer = resolved.error,
            background = resolved.pageBg,
            onBackground = resolved.textPrimary,
            surface = resolved.cardBg,
            onSurface = resolved.textPrimary,
            surfaceVariant = resolved.pageBg,
            onSurfaceVariant = resolved.textSecondary,
            outline = resolved.textSecondary,
            outlineVariant = resolved.divider
        )
    }
}

@Composable
fun UFIAXISTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePresets.Default,
    content: @Composable () -> Unit
) {
    // Resolve palette for current dark/light mode
    val resolved = palette.resolve(darkTheme)

    // Derive M3 color scheme from resolved palette
    val colorScheme = buildColorSchemeFromPalette(resolved)

    // Provide palette through CompositionLocal
    ProvideThemePalette(palette = palette, isDark = darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
