package com.ufi_axis.ui.theme

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * 5 preset theme palettes + dynamic/custom builders.
 * Modeled after UFITOOLSWidget's ThemeColors preset system.
 */
object ThemePresets {

    /** ID 0: Default monochrome (matches current UFI-AXIS look) */
    val Default = ThemePalette(
        id = "default",
        name = "默认",
        accentLight = Color(0xFF222222),
        accentDark = Color(0xFF555555),
        accentSecondaryLight = Color(0xFFE5E5E5),
        accentSecondaryDark = Color(0xFF555555),
        pageBgLight = Color(0xFFF8F8F8),
        pageBgDark = Color(0xFF1A1A1A),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF2A2A2A),
        textPrimaryLight = Color(0xFF111111),
        textPrimaryDark = Color(0xFFEEEEEE),
        textSecondaryLight = Color(0xFF444444),
        textSecondaryDark = Color(0xFFBBBBBB),
        dividerLight = Color(0xFFE5E5E5),
        dividerDark = Color(0xFF333333),
        btnBgLight = Color(0xFF222222),
        btnBgDark = Color(0xFF5A5A5A),
        iconTintLight = Color(0xFF111111),
        iconTintDark = Color(0xFFEEEEEE),
        dataHighlightLight = Color(0xFF111111),
        dataHighlightDark = Color(0xFFFFFFFF)
    )

    /** ID 1: Tech Blue */
    val TechBlue = ThemePalette(
        id = "tech_blue",
        name = "科技蓝",
        accentLight = Color(0xFF1677FF),
        accentDark = Color(0xFF0E5ACD),
        accentSecondaryLight = Color(0xFF69B1FF),
        accentSecondaryDark = Color(0xFF69B1FF),
        pageBgLight = Color(0xFFF5F7FA),
        pageBgDark = Color(0xFF1D2939),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF263548),
        textPrimaryLight = Color(0xFF1D2129),
        textPrimaryDark = Color(0xFFE8EDF2),
        textSecondaryLight = Color(0xFF86909C),
        textSecondaryDark = Color(0xFF86909C),
        dividerLight = Color(0xFFE5E6EB),
        dividerDark = Color(0xFF2A3A4E),
        btnBgLight = Color(0xFF1677FF),
        btnBgDark = Color(0xFF0E5ACD),
        iconTintLight = Color(0xFF1677FF),
        iconTintDark = Color(0xFF0E5ACD),
        dataHighlightLight = Color(0xFF1677FF),
        dataHighlightDark = Color(0xFF69B1FF)
    )

    /** ID 2: Mint Green */
    val MintGreen = ThemePalette(
        id = "mint_green",
        name = "薄荷绿",
        accentLight = Color(0xFF34C799),
        accentDark = Color(0xFF34C799),
        accentSecondaryLight = Color(0xFF90E4C3),
        accentSecondaryDark = Color(0xFF1B6B4E),
        pageBgLight = Color(0xFFF7FCFA),
        pageBgDark = Color(0xFF1A2822),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF24332D),
        textPrimaryLight = Color(0xFF2C3631),
        textPrimaryDark = Color(0xFFD8E8DF),
        textSecondaryLight = Color(0xFF7A9487),
        textSecondaryDark = Color(0xFF7A9487),
        dividerLight = Color(0xFFE2EBE6),
        dividerDark = Color(0xFF2A3D33),
        btnBgLight = Color(0xFF34C799),
        btnBgDark = Color(0xFF228B55),
        iconTintLight = Color(0xFF34C799),
        iconTintDark = Color(0xFF34C799),
        dataHighlightLight = Color(0xFF34C799),
        dataHighlightDark = Color(0xFF90E4C3)
    )

    /** ID 3: Dream Purple */
    val DreamPurple = ThemePalette(
        id = "dream_purple",
        name = "梦幻紫",
        accentLight = Color(0xFF7B61FF),
        accentDark = Color(0xFFB1A1FF),
        accentSecondaryLight = Color(0xFFB1A1FF),
        accentSecondaryDark = Color(0xFF5B46CC),
        pageBgLight = Color(0xFFF7F5FF),
        pageBgDark = Color(0xFF1A1630),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF272044),
        textPrimaryLight = Color(0xFF3A3152),
        textPrimaryDark = Color(0xFFEAE6FF),
        textSecondaryLight = Color(0xFF8A84B8),
        textSecondaryDark = Color(0xFF8A84B8),
        dividerLight = Color(0xFFEAE6FC),
        dividerDark = Color(0xFF2A2540),
        btnBgLight = Color(0xFF7B61FF),
        btnBgDark = Color(0xFF5B46CC),
        iconTintLight = Color(0xFF7B61FF),
        iconTintDark = Color(0xFFB1A1FF),
        dataHighlightLight = Color(0xFF7B61FF),
        dataHighlightDark = Color(0xFFB1A1FF)
    )

    /** ID 4: Vibrant Orange */
    val VibrantOrange = ThemePalette(
        id = "vibrant_orange",
        name = "活力橙",
        accentLight = Color(0xFFFF7D34),
        accentDark = Color(0xFFFF7D34),
        accentSecondaryLight = Color(0xFFFFB989),
        accentSecondaryDark = Color(0xFFB86020),
        pageBgLight = Color(0xFFFFF8F3),
        pageBgDark = Color(0xFF241A15),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF2F221A),
        textPrimaryLight = Color(0xFF3D2B20),
        textPrimaryDark = Color(0xFFE8D8CC),
        textSecondaryLight = Color(0xFF997B69),
        textSecondaryDark = Color(0xFF997B69),
        dividerLight = Color(0xFFFFEDE0),
        dividerDark = Color(0xFF3A2A20),
        btnBgLight = Color(0xFFFF7D34),
        btnBgDark = Color(0xFFCC5500),
        iconTintLight = Color(0xFFFF7D34),
        iconTintDark = Color(0xFFFF7D34),
        dataHighlightLight = Color(0xFFFF7D34),
        dataHighlightDark = Color(0xFFFFB989)
    )

    /** All preset palettes for iteration */
    val allPresets: List<ThemePalette> = listOf(Default, TechBlue, MintGreen, DreamPurple, VibrantOrange)

    /** Find a preset by its ID, falls back to Default */
    fun findById(id: String): ThemePalette =
        allPresets.find { it.id == id } ?: Default
}

/**
 * Build a custom palette from an arbitrary accent color.
 * Derives all other colors algorithmically.
 */
fun buildCustomPalette(accentColor: Color, name: String = "自定义"): ThemePalette {
    val hsl = FloatArray(3)
    android.graphics.Color.colorToHSV(accentColor.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1]

    // Light variant secondary: lighter, less saturated
    val secondaryLightHsl = floatArrayOf(h, (s * 0.6f).coerceAtMost(1f), 0.7f)
    val secondaryLight = Color.hsv(secondaryLightHsl[0], secondaryLightHsl[1], secondaryLightHsl[2])

    // Dark variant secondary: lighter for contrast on dark backgrounds
    val secondaryDarkHsl = floatArrayOf(h, (s * 0.5f).coerceAtMost(1f), 0.8f)
    val secondaryDark = Color.hsv(secondaryDarkHsl[0], secondaryDarkHsl[1], secondaryDarkHsl[2])

    return ThemePalette(
        id = "custom",
        name = name,
        accentLight = accentColor,
        accentDark = secondaryDark,
        accentSecondaryLight = secondaryLight,
        accentSecondaryDark = secondaryDark,
        pageBgLight = Color(0xFFF8F8F8),
        pageBgDark = Color(0xFF1A1A1A),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF2A2A2A),
        textPrimaryLight = Color(0xFF111111),
        textPrimaryDark = Color(0xFFEEEEEE),
        textSecondaryLight = Color(0xFF444444),
        textSecondaryDark = Color(0xFFBBBBBB),
        dividerLight = Color(0xFFE5E5E5),
        dividerDark = Color(0xFF333333),
        btnBgLight = accentColor,
        btnBgDark = secondaryDark,
        iconTintLight = accentColor,
        iconTintDark = secondaryDark,
        dataHighlightLight = accentColor,
        dataHighlightDark = secondaryDark
    )
}

/**
 * Build a dynamic palette from Material You (API 31+).
 * Falls back to Default on older APIs.
 */
@Composable
fun buildDynamicPalette(): ThemePalette {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return ThemePresets.Default
    }

    val context = LocalContext.current
    val lightScheme = dynamicLightColorScheme(context)
    val darkScheme = dynamicDarkColorScheme(context)

    return ThemePalette(
        id = "dynamic",
        name = "动态取色",
        accentLight = lightScheme.primary,
        accentDark = darkScheme.primary,
        accentSecondaryLight = lightScheme.secondary,
        accentSecondaryDark = darkScheme.secondary,
        pageBgLight = lightScheme.background,
        pageBgDark = darkScheme.background,
        cardBgLight = lightScheme.surface,
        cardBgDark = darkScheme.surface,
        textPrimaryLight = lightScheme.onBackground,
        textPrimaryDark = darkScheme.onBackground,
        textSecondaryLight = lightScheme.onSurfaceVariant,
        textSecondaryDark = darkScheme.onSurfaceVariant,
        dividerLight = lightScheme.outlineVariant,
        dividerDark = darkScheme.outlineVariant,
        btnBgLight = lightScheme.primary,
        btnBgDark = darkScheme.primary,
        iconTintLight = lightScheme.primary,
        iconTintDark = darkScheme.primary,
        dataHighlightLight = lightScheme.primary,
        dataHighlightDark = darkScheme.primary
    )
}
