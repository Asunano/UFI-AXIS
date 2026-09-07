package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color

// === Legacy color constants (kept for backward compatibility) ===
// New code should use LocalResolvedPalette.current.* instead

// Monochrome accent palette
val AccentLight = Color(0xFF222222)
val AccentDark = Color(0xFF888888)
val AccentContainerLight = Color(0xFFF0F0F0)
val AccentContainerDark = Color(0xFF2C2C2C)

// Signal strength colors (domain-specific, not theme-dependent)
val SignalExcellent = Color(0xFF2E7D32)
val SignalGood = Color(0xFF689F38)
val SignalFair = Color(0xFFFFA000)
val SignalPoor = Color(0xFFE65100)
val SignalDead = Color(0xFFC62828)

// Network type colors (domain-specific)
val Network5G = Color(0xFF7C4DFF)
val Network4G = Color(0xFF1565C0)
val Network3G = Color(0xFFEF6C00)
val Network2G = Color(0xFF616161)

// Battery level colors (domain-specific)
val BatteryHigh = Color(0xFF2E7D32)
val BatteryMedium = Color(0xFFFFA000)
val BatteryLow = Color(0xFFC62828)

// Traffic direction colors (domain-specific)
val TrafficDown = Color(0xFF1565C0)
val TrafficUp = Color(0xFFE65100)

// === 域色（2026-09-01 批 4 从业务字面量收敛而来，刻意保留原值，不随明暗切换）===
/** 频段锁定：LTE 绿（与 SignalGood 区分，用于频段选择器） */
val BandLte = Color(0xFF22C55E)
/** 频段锁定：NR 紫（与 Network5G 区分，用于频段选择器） */
val BandNr = Color(0xFF8B5CF6)
/** 已连接状态指示点绿（首页连接卡片，铺在渐变卡面上故不跟随明暗） */
val StatusOnline = Color(0xFF4ADE80)
/** 图表阈值线：告警（红） */
val ChartAlert = Color(0xFFE53935)
/** 图表阈值线：警告（黄）—— 刻意比 palette.warning 更偏黄，避免与曲线色撞色 */
val ChartWarn = Color(0xFFFBC02D)
/** 中性描边灰：卡片阴影 / 胶囊导航栏描边的同一基色 */
val NeutralOutline = Color(0xFF9CA4AC)
/**
 * 赞赏粉：`UfiButtonVariant.Donate` 的描边与文字色（对齐旧项目 UFITOOLS-Widget 的 DONATE_PINK）。
 *
 * 与信号 / 电量 / 频段一样属于**刻意不跟随皮肤**的域色：赞赏是情感化的固定标识，
 * 跟着 accent 变会在「科技蓝 / 薄荷绿」下退化成一个看不出是赞赏的普通蓝绿描边按钮。
 */
val DonatePink = Color(0xFFFF6B9D)

// Page backgrounds (now derived from palette)
val PageBackgroundLight = Color(0xFFF5F5F7)
val PageBackgroundDark = Color(0xFF111318)

// Card surfaces (now derived from palette)
val CardSurfaceLight = Color(0xFFFFFFFF)
val CardSurfaceDark = Color(0xFF1E2028)

// Subtle dividers (now derived from palette)
val DividerLight = Color(0xFFE5E5EA)
val DividerDark = Color(0xFF2C2E35)

// === 图表域色（随主题变化，供监控图表曲线使用） ===
// 曲线色属于"域色"，不随成功/警告/失败等语义状态变化，但需在浅/暗主题下均清晰可读，
// 且与语义色区分（避免与 success/warning/error 撞色）。
data class ChartColors(
    val cpu: Color,
    val memory: Color,
    val trafficRx: Color,
    val trafficTx: Color,
    val signalRsrp: Color,
    val signalSinr: Color,
    val battery: Color,
    val temperature: Color
)

/** 根据当前主题（明暗）返回一套双主题安全的图表曲线色 */
fun ChartColors(isDark: Boolean): ChartColors = if (isDark) ChartColors(
    cpu = Color(0xFF64B5F6),
    memory = Color(0xFFBA68C8),
    trafficRx = Color(0xFF4FC3F7),
    trafficTx = Color(0xFF81C784),
    signalRsrp = Color(0xFFF0A030),
    signalSinr = Color(0xFFCE93D8),
    battery = Color(0xFF4DD0E1),
    temperature = Color(0xFFE57373)
) else ChartColors(
    cpu = Color(0xFF1976D2),
    memory = Color(0xFF8E24AA),
    trafficRx = Color(0xFF2196F3),
    trafficTx = Color(0xFF4CAF50),
    signalRsrp = Color(0xFFF57C00),
    signalSinr = Color(0xFF9C27B0),
    battery = Color(0xFF00ACC1),
    temperature = Color(0xFFEF5350)
)

/**
 * 语义状态色统一入口（成功/警告/失败）。
 * 语义色随主题变化，因此以函数形式接收 [ResolvedPalette]，避免 ThemePalette 之外的"第二来源"。
 */
object StatusRamp {
    fun good(palette: ResolvedPalette): Color = palette.success
    fun warn(palette: ResolvedPalette): Color = palette.warning
    fun bad(palette: ResolvedPalette): Color = palette.error
}
