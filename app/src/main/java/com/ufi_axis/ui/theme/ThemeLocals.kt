package com.ufi_axis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * CompositionLocal providers for the theme palette system.
 *
 * Components access the resolved palette via:
 * ```
 * val palette = LocalResolvedPalette.current
 * Text("Hello", color = palette.textPrimary)
 * ```
 */

val LocalThemePalette = compositionLocalOf<ThemePalette> { ThemePresets.Default }

val LocalResolvedPalette = compositionLocalOf<ResolvedPalette> {
    ThemePresets.Default.resolve(isDark = false)
}

/**
 * Provides the theme palette through the composition tree.
 * Resolves light/dark based on system theme.
 */
@Composable
fun ProvideThemePalette(
    palette: ThemePalette,
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val resolved = remember(palette, isDark) { palette.resolve(isDark) }
    CompositionLocalProvider(
        LocalThemePalette provides palette,
        LocalResolvedPalette provides resolved,
        content = content
    )
}
