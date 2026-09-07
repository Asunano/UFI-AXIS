package com.ufi_axis.ui.animation.page

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.ufi_axis.ui.components.common.UfiExperimentalApi

/**
 * 「是否降低动效」的组合局部量。
 *
 * 宿主（T02 `UfiPageSwitcherHost`）在此值为 `true` 时，会把当前策略降级到
 * [UfiPageTransition.reducedMotionFallback]，若其为 `null` 则统一降到 [UfiPageTransitions.Fade]。
 *
 * 默认 `false`。真实探测（系统「开发者选项 - 动画时长缩放」为 0、无障碍 ReduceMotion、
 * 低端机判定）由 **T05** 在 `:app` 层探测后通过 `CompositionLocalProvider` 注入，
 * 本层只定义契约，保持纯数据 / 可单测。
 *
 * 用法：
 * ```
 * CompositionLocalProvider(LocalUfiReduceMotion provides shouldReduceMotion) { ... }
 * ```
 *
 * 注：本符号属于实验性 API（等同 `@UfiExperimentalApi`）。因 `UfiExperimentalApi` 的
 * `@Target` 未包含 `AnnotationTarget.PROPERTY`，此处无法以注解标注，改以文档声明。
 */
val LocalUfiReduceMotion: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * [UfiPageSwitcher][UfiPage] 家族的默认参数与降级判定（T01 · 默认值层）。
 *
 * 约定：**动画默认值统一走本对象，不在调用点写魔法数字**。
 */
@UfiExperimentalApi
object UfiPageSwitcherDefaults {

    /**
     * 默认转场策略。
     *
     * 取 [UfiPageTransitions.Fade]，其观感复刻现有 `tabEnter`（`fadeIn(220) + scaleIn(0.98f)`），
     * 保证接入后**零视觉回归**。
     */
    val DefaultTransition: UfiPageTransition
        get() = UfiPageTransitions.Fade

    /** 是否默认允许横向滑动手势切页。 */
    val DefaultSwipeEnabled: Boolean
        get() = true

    /** 是否默认让非当前页保持存活（保留 `LazyColumn` 滚动位置等页面内状态）。 */
    val DefaultKeepPagesAlive: Boolean
        get() = true

    /** 是否默认尊重系统的「降低动效」设置。 */
    val DefaultRespectReducedMotion: Boolean
        get() = true

    /**
     * 判定当前设备是否为低端机，用于自动降级到 Fade（3D 类动画在低端机上容易掉帧）。
     *
     * 判定条件：系统标记为 low-RAM 设备（`ActivityManager.isLowRamDevice`）。
     *
     * ⚠ 历史上曾把「CPU 可用核心数 `<= 4`」也作为判据，但这是**极不可靠的信号**：
     * 现代设备的 CPU 核心会按负载热插拔，`Runtime.getRuntime().availableProcessors()`
     * 的返回值随运行时波动，大量正常的中高端机型会被误判为低端机，从而把用户**主动选择**
     * 的旋转 / 3D 等高级转场静默降级成淡入淡出（[UfiPageSwitcher] 的 `effectiveTransition`
     * 在 `isLowEndDevice == true` 时会回落到 `reducedMotionFallback`，而后者默认 `null` → Fade）。
     * 因此只保留官方 `isLowRamDevice` 这一个稳定信号，避免无谓降级。
     *
     * @param context 任意 [Context]，内部只用于取系统服务，不持有引用。
     * @return `true` 表示应当降级动画。
     */
    fun isLowEndDevice(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isLowRamDevice
    }

    /** 支持过渡模糊所需的最低进程内存上限（MB）。低于此档视为「小内存 + 弱 GPU」弱机。 */
    private const val BLUR_SUPPORT_MIN_MEMORY_MB: Int = 96

    /**
     * 判定设备是否**支持**过渡模糊（GPU 离屏模糊渲染）。
     *
     * 返回 `false` 的弱机将被 [UfiPageSwitcher] 强制跳过**全部**页面切换过渡模糊，
     * 避免低端真机 / 模拟器软渲染下满屏 `RenderEffect` 模糊造成卡顿（根因见宿主
     * `applyTransitionBlur` 注释）。
     *
     * 满足任一即视为不支持：
     * 1. `ActivityManager.isLowRamDevice` —— 系统明确标记为低内存设备；
     * 2. `ActivityManager.getMemoryClass()`（进程可用内存上限，单位 MB）低于
     *    [BLUR_SUPPORT_MIN_MEMORY_MB] —— 这类设备通常「小内存 + 弱 GPU」组合，
     *    满屏模糊极易掉帧。
     *
     * 阈值取 `96` MB：Android 中 `getMemoryClass()` 常见档位为 96 / 128 / 192 / 256，
     * 96MB 是低端机与中端机的分水岭（多数 2019 年后主流机型 ≥ 128MB），低于此档最易在
     * 模糊过场掉帧，故将其排除在模糊支持之外。本函数与 [isLowEndDevice] 维度不同
     * （一个管动画降级、一个管模糊开关），但 low-RAM 设备会同时命中二者。
     *
     * @param context 任意 [Context]，仅用于取系统服务，不持有引用。
     * @return `true` 表示设备足以流畅承载过渡模糊。
     */
    fun isBlurSupported(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.isLowRamDevice) return false
        if (activityManager.memoryClass < BLUR_SUPPORT_MIN_MEMORY_MB) return false
        return true
    }
}

