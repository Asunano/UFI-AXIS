package com.ufi_axis.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveDarkTheme] 的 JVM 单测。
 *
 * 系统栏 appearance 的最终判据是 `isDark`，而 `isDark` 只由“外观模式 + 系统夜间模式”
 * 决定。这三个分支一旦写反，表现是状态栏/导航栏图标与页面底色同色（直接看不见），
 * 但在 Compose 预览或浅色系统上未必能立刻发现，所以用纯函数测试钉住。
 */
class SystemBarAppearanceTest {

    @Test
    fun `auto follows system night mode`() {
        assertTrue(resolveDarkTheme(ThemeMode.AUTO, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.AUTO, systemDark = false))
    }

    @Test
    fun `light forces light even when system is dark`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = false))
    }

    @Test
    fun `dark forces dark even when system is light`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = true))
    }
}
