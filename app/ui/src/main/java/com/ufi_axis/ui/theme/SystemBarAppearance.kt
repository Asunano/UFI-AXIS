package com.ufi_axis.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.core.view.WindowCompat

/**
 * 把应用内解析出的明暗写到两条系统栏的图标 appearance 上。
 *
 * `enableEdgeToEdge(...)` 只在 Activity `onCreate` 时按**系统** `Configuration.uiMode`
 * 初始化一次；本应用的外观是独立设置（`ThemeMode.LIGHT/DARK/AUTO` + 多套皮肤），
 * 两者可以完全相反。系统深色 + 应用内浅色时，系统仍按“深背景”渲染，
 * 状态栏图标与手势小白条都是白的，压在浅色页面上直接看不见。
 *
 * 两个 setter 都先比较再写：`SideEffect` 每次重组都会调用本函数，
 * 避免对未变化的系统栏重复触发重绘。
 */
fun Activity.applySystemBarAppearance(darkTheme: Boolean) {
    val lightIcons = !darkTheme
    WindowCompat.getInsetsController(window, window.decorView).apply {
        if (isAppearanceLightStatusBars != lightIcons) {
            isAppearanceLightStatusBars = lightIcons
        }
        if (isAppearanceLightNavigationBars != lightIcons) {
            isAppearanceLightNavigationBars = lightIcons
        }
    }
}

/**
 * 外观模式 + 系统夜间模式 → 最终明暗。
 *
 * 纯函数，不碰 Android API，便于 JVM 单测覆盖“自动 / 强制浅 / 强制深”三种分支。
 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.AUTO -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * 冷启动首帧用：在 `setContent` 之前解析**已持久化**的 `ThemeMode`。
 *
 * `enableEdgeToEdge(SystemBarStyle.dark(...))` 的初始 appearance 是“深底浅图标”，
 * 浅色主题下会先闪一帧白图标。这里提前按用户设置同步一次，
 * 之后由 Compose 里的 `SideEffect` 跟随运行期变化。
 *
 * 只构造一个 `observeExternal = false` 的临时实例来复用 [ThemeManager] 的播种逻辑；
 * 它不注册 SharedPreferences 监听，用完立即 [ThemeManager.dispose]。
 */
fun resolveBootDarkTheme(context: Context): Boolean {
    val manager = ThemeManager(context.applicationContext, observeExternal = false)
    return try {
        resolveDarkTheme(manager.themeMode.value, isSystemDark(context))
    } finally {
        manager.dispose()
    }
}

/** 系统当前是否处于夜间模式；只用于冷启动首帧，运行期以 Compose 的 `isSystemInDarkTheme()` 为准。 */
private fun isSystemDark(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
