package com.ufi_axis.ui.animation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import com.ufi_axis.ui.theme.UfiAnimSpecs as ThemeAnimSpecs

/**
 * ⚠ **已迁移（P3a，2026-09-04）：真正的定义现在在
 * [com.ufi_axis.ui.theme.UfiAnimSpecs]（`theme/MotionTokens.kt`）。**
 *
 * **新代码请从 `com.ufi_axis.ui.theme` 引入**，不要再用本对象。
 *
 * 本文件只剩一层**零行为的转发**，存在的唯一理由是：全库约 287 处动画调用点若一次性改 import，
 * diff 会大到无法评审、也无法按"改一处编译一次"的方式回退。因此旧 import
 * （`import com.ufi_axis.ui.animation.UfiAnimSpecs`）**零改动仍可编译**，只会拿到废弃警告；
 * 调用点随后续维护自然迁移，全部迁完后删除本文件。
 *
 * 为什么是转发 `object` 而不是 `typealias`：Kotlin 的 `typealias` 只在**类型位置**生效，
 * `UfiAnimSpecs.clickScale()` 这种"通过对象名访问成员"的表达式位置用不了别名，
 * 旧调用点会直接编译失败——那就不是过渡层了。
 *
 * 迁移对照：符号名、参数、返回类型**逐一保持不变**，只换包名。
 */
@Deprecated(
    message = "已迁至 com.ufi_axis.ui.theme.UfiAnimSpecs（P3a：所有设计令牌收归 ui.theme）。" +
        "新代码请从 ui.theme 引入；本转发层仅为避免一次性改动约 287 处动画调用点。"
)
object UfiAnimSpecs {

    // ===== 按压 / 点击反馈 =====

    /** 转发 [ThemeAnimSpecs.clickScale] */
    fun <T> clickScale(): SpringSpec<T> = ThemeAnimSpecs.clickScale()

    /** 转发 [ThemeAnimSpecs.buttonPress] */
    fun <T> buttonPress(): SpringSpec<T> = ThemeAnimSpecs.buttonPress()

    /** 转发 [ThemeAnimSpecs.controlPop] */
    fun <T> controlPop(): SpringSpec<T> = ThemeAnimSpecs.controlPop()

    /** 转发 [ThemeAnimSpecs.sendPop] */
    fun <T> sendPop(): SpringSpec<T> = ThemeAnimSpecs.sendPop()

    // ===== 入场 / 转场 =====

    /** 转发 [ThemeAnimSpecs.staggerEnter] */
    fun <T> staggerEnter(): SpringSpec<T> = ThemeAnimSpecs.staggerEnter()

    /** 转发 [ThemeAnimSpecs.dialogEnter] */
    fun <T> dialogEnter(): SpringSpec<T> = ThemeAnimSpecs.dialogEnter()

    /** 转发 [ThemeAnimSpecs.toastDrop] */
    fun <T> toastDrop(): SpringSpec<T> = ThemeAnimSpecs.toastDrop()

    /** 转发 [ThemeAnimSpecs.panelEnter] */
    fun <T> panelEnter(): SpringSpec<T> = ThemeAnimSpecs.panelEnter()

    // ===== 选中 / 强调 =====

    /** 转发 [ThemeAnimSpecs.tagPop] */
    fun <T> tagPop(): SpringSpec<T> = ThemeAnimSpecs.tagPop()

    /** 转发 [ThemeAnimSpecs.tooltipPop] */
    fun <T> tooltipPop(): SpringSpec<T> = ThemeAnimSpecs.tooltipPop()

    /** 转发 [ThemeAnimSpecs.tabSlider] */
    fun <T> tabSlider(): SpringSpec<T> = ThemeAnimSpecs.tabSlider()

    /** 转发 [ThemeAnimSpecs.pagePulse] */
    fun <T> pagePulse(): SpringSpec<T> = ThemeAnimSpecs.pagePulse()

    // ===== 连续量 / 颜色过渡 =====

    /** 转发 [ThemeAnimSpecs.switchThumb] */
    fun <T> switchThumb(): SpringSpec<T> = ThemeAnimSpecs.switchThumb()

    /** 转发 [ThemeAnimSpecs.colorSettle] */
    fun <T> colorSettle(): SpringSpec<T> = ThemeAnimSpecs.colorSettle()

    /** 转发 [ThemeAnimSpecs.colorSwap] */
    fun <T> colorSwap(): SpringSpec<T> = ThemeAnimSpecs.colorSwap()

    /** 转发 [ThemeAnimSpecs.sliderTrack] */
    fun <T> sliderTrack(): SpringSpec<T> = ThemeAnimSpecs.sliderTrack()

    /**
     * 转发 [com.ufi_axis.ui.theme.UfiAnimSpecs.CapsuleExpandStiffness]。
     *
     * 仍写成 `const val`（而不是 `get() =`）是必需的：`UfiCapsuleTabBar.EXPAND_SPRING`
     * 是文件级 `private val`，其初始化式要求 stiffness 参数可在编译期取到常量，
     * 且 `CapsuleRegressionGuardTest` 锁的是那一行的源文本形态。
     */
    const val CapsuleExpandStiffness = com.ufi_axis.ui.theme.UfiAnimSpecs.CapsuleExpandStiffness

    // ===== 非 spring =====

    /** 转发 [ThemeAnimSpecs.fadeEnter] */
    val fadeEnter: TweenSpec<Float> get() = ThemeAnimSpecs.fadeEnter

    /** 转发 [ThemeAnimSpecs.toastDropEasing] */
    val toastDropEasing: CubicBezierEasing get() = ThemeAnimSpecs.toastDropEasing

    /** 转发 [ThemeAnimSpecs.emphasizedInEasing] */
    val emphasizedInEasing: CubicBezierEasing get() = ThemeAnimSpecs.emphasizedInEasing

    /** 转发 [ThemeAnimSpecs.emphasizedOutEasing] */
    val emphasizedOutEasing: CubicBezierEasing get() = ThemeAnimSpecs.emphasizedOutEasing
}
