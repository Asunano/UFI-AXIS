package com.ufi_axis.ui.animation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import com.ufi_axis.ui.theme.LocalThemePalette

/**
 * Wraps content with a crossfade animation that triggers when the theme changes.
 * Simplified version of the UFITOOLSWidget circle-reveal theme transition.
 *
 * Usage (typically in the app's root composable):
 * ```
 * ThemeRevealWrapper {
 *     // Your themed content
 * }
 * ```
 */
@Composable
fun ThemeRevealWrapper(
    content: @Composable () -> Unit
) {
    val palette = LocalThemePalette.current
    Crossfade(
        targetState = palette.id,
        animationSpec = tween(400),
        label = "themeReveal"
    ) {
        content()
    }
}
