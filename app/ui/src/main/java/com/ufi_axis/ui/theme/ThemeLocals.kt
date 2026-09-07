package com.ufi_axis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.abs

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

// ═══════════════════ 全局 UI 缩放：跨 Window 补偿 ═══════════════════

/**
 * 全局 UI 缩放系数（已含基准，1f = 不缩放）。由 [UFIAXISTheme] 下发。
 *
 * 存在的意义是给 Dialog / Popup 做补偿——见 [UfiInheritUiScale]。
 */
val LocalUfiUiScale = compositionLocalOf { 1f }

/**
 * 设备**原始** density（未经缩放）。由 [UFIAXISTheme] 下发，[UfiInheritUiScale] 用它判断
 * 当前所处的组合是否已经套过缩放，从而做到幂等。null = 不在 UFIAXISTheme 树下。
 */
val LocalUfiBaseDensity = compositionLocalOf<Float?> { null }

/**
 * 在 **Dialog / Popup 的内容根部**重新套用全局 UI 缩放。
 *
 * 为什么必须显式补一次：Compose 每创建一个新的 `AbstractComposeView`（Dialog 的 `DialogLayout`、
 * Popup 的 `PopupLayout`）时，`ProvideCommonCompositionLocals` 会在子组合根部重新
 * `LocalDensity provides owner.density`（来自 View 的真实 resources density），把 [UFIAXISTheme]
 * 里覆盖的自定义 density **冲掉**。自定义 CompositionLocal（[LocalResolvedPalette]、
 * [LocalUfiUiScale]）不在那份平台列表里，所以颜色能继承、density 不能——这正是胶囊导航栏和
 * 所有弹窗曾经不跟随缩放的原因。
 *
 * 幂等：通过比较「当前 density」与「原始 density × scale」判断是否已缩放过，因此在主窗口树内
 * 误套一层也不会二次缩小。
 */
@Composable
fun UfiInheritUiScale(content: @Composable () -> Unit) {
    val baseDensity = LocalUfiBaseDensity.current
    val scale = LocalUfiUiScale.current
    val current = LocalDensity.current
    if (baseDensity == null || scale == 1f) {
        content()
        return
    }
    val target = baseDensity * scale
    // 已经是目标 density（说明在主窗口树内，或外层已经补过）→ 不再叠加
    if (abs(current.density - target) < DENSITY_EPSILON) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density = target, fontScale = current.fontScale),
        content = content
    )
}

/** density 浮点比较容差：density 常见值量级在 1~4，1e-4 足够区分「同一档」与「差一档」 */
private const val DENSITY_EPSILON = 0.0001f
