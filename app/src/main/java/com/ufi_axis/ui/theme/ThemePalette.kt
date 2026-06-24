package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Complete theme palette with light/dark variants for each color slot.
 * Mirrors UFITOOLSWidget's ThemeColors.Palette design.
 */
data class ThemePalette(
    val id: String,
    val name: String,
    // Accent (primary brand color)
    val accentLight: Color,
    val accentDark: Color,
    // Accent secondary (lighter variant for badges, chips)
    val accentSecondaryLight: Color,
    val accentSecondaryDark: Color,
    // Page background
    val pageBgLight: Color,
    val pageBgDark: Color,
    // Card surface
    val cardBgLight: Color,
    val cardBgDark: Color,
    // Primary text
    val textPrimaryLight: Color,
    val textPrimaryDark: Color,
    // Secondary text
    val textSecondaryLight: Color,
    val textSecondaryDark: Color,
    // Divider
    val dividerLight: Color,
    val dividerDark: Color,
    // Button background
    val btnBgLight: Color,
    val btnBgDark: Color,
    // Icon tint
    val iconTintLight: Color,
    val iconTintDark: Color,
    // Data highlight (for emphasized data values)
    val dataHighlightLight: Color,
    val dataHighlightDark: Color
) {
    /**
     * Resolve to a flat set of colors based on current dark/light mode.
     */
    fun resolve(isDark: Boolean): ResolvedPalette = ResolvedPalette(
        accent = if (isDark) accentDark else accentLight,
        accentSecondary = if (isDark) accentSecondaryDark else accentSecondaryLight,
        pageBg = if (isDark) pageBgDark else pageBgLight,
        cardBg = if (isDark) cardBgDark else cardBgLight,
        textPrimary = if (isDark) textPrimaryDark else textPrimaryLight,
        textSecondary = if (isDark) textSecondaryDark else textSecondaryLight,
        divider = if (isDark) dividerDark else dividerLight,
        btnBg = if (isDark) btnBgDark else btnBgLight,
        iconTint = if (isDark) iconTintDark else iconTintLight,
        dataHighlight = if (isDark) dataHighlightDark else dataHighlightLight,
        isDark = isDark
    )
}

/**
 * Resolved (flat) palette for the current dark/light mode.
 * Components access colors directly from this class.
 */
data class ResolvedPalette(
    val accent: Color,
    val accentSecondary: Color,
    val pageBg: Color,
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val btnBg: Color,
    val iconTint: Color,
    val dataHighlight: Color,
    val isDark: Boolean
) {
    /** Card border color: subtle alpha-based border */
    val cardBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    /** Dialog border color: slightly more visible */
    val dialogBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)

    /** Toast border color */
    val toastBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    /** Switch track unchecked color */
    val switchTrackOff: Color
        get() = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFE0E0E0)

    /** Switch thumb unchecked color */
    val switchThumbOff: Color
        get() = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFFFAFAFA)

    /** Switch track checked color (accent at 30% alpha) */
    val switchTrackOn: Color
        get() = accent.copy(alpha = 0.3f)

    /** Switch thumb checked color */
    val switchThumbOn: Color
        get() = accent

    /** Text on accent (for buttons with accent background) */
    val onAccent: Color
        get() = Color.White

    /** Error color (consistent across themes) */
    val error: Color
        get() = if (isDark) Color(0xFFFF6B6B) else Color(0xFFE53935)

    /** Error container (subtle red background) */
    val errorContainer: Color
        get() = if (isDark) Color(0xFF3D1515) else Color(0xFFFFEBEE)

    /** Warning color */
    val warning: Color
        get() = if (isDark) Color(0xFFFFB74D) else Color(0xFFFF9800)

    /** Success color */
    val success: Color
        get() = if (isDark) Color(0xFF66BB6A) else Color(0xFF43A047)

    /** Input field border (unfocused) */
    val inputBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)

    /** Input field border (focused) */
    val inputBorderFocused: Color
        get() = accent

    /** Chip selected background */
    val chipSelectedBg: Color
        get() = accent.copy(alpha = 0.12f)

    /** Chip unselected border */
    val chipUnselectedBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)

    /** Bottom nav indicator */
    val navIndicator: Color
        get() = accent.copy(alpha = 0.15f)

    /** Scrim/overlay for dialogs */
    val scrim: Color
        get() = Color.Black.copy(alpha = if (isDark) 0.5f else 0.35f)
}
