// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ufi_axis.ui.theme.UfiMotion as ThemeMotion

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║  UFI-AXIS Design Center — Unified UI Component Hub     ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Central access point for the UFI-AXIS design system.
 *
 * ## Components
 * （2026-08-30 公共层去重后的清单；新增组件请同步这里，见 docs/UFI-AXIS-UI-Common-Layer.md）
 * - Layout: UfiScreenScaffold, UfiPageBackground, UfiPageBackgroundBox
 * - Cards: UfiSettingsGroup, UfiSettingsRowCard, UfiEntryCard, UfiGridCard, UfiStatusCard,
 *          UfiErrorBanner, UfiOfflineBanner, UfiRealtimeStatusBanner
 * - Settings rows: UfiSettingsItem, UfiSettingsToggle, UfiSettingsValue,
 *                  UfiSettingsChevron, UfiGroupHeader
 *                  （2026-09-02 批 6：UfiSettingsRow 已并入 UfiSettingsItem 并删除）
 * - Navigation: UfiScrollableTabRow, UfiCapsuleTabBar, UfiAnimatedTabContent
 * - Input: UfiTextField, UfiPasswordField, UfiDigitField
 * - Selection: UfiSwitch, UfiSlider, UfiValueSlider, UfiRangeSlider, UfiDropdown,
 *             UfiSingleChipSelector, UfiMultiChipSelector, UfiOptionGrid
 * - Buttons: UfiButton(variant, size, loading, fillWidth), UfiButtonRow, UfiFloatingActionButton
 *            （2026-09-04 P4c：UfiPrimaryButton / UfiSecondaryButton / UfiSmallButton /
 *             UfiDangerButton / UfiOutlinedActionButton 已合并为 UfiButton 并删除；
 *             新增按钮样式请加 variant，不要新建组件）
 * - Dialogs: UfiCustomDialog, UfiScrollableDialog, UfiAlertDialog, UfiConfirmDialog,
 *            UfiInputDialog, UfiChoiceSheet, UfiDateRangePickerDialog, UfiLogDialog
 * - Dialog parts: UfiDialogBody, UfiDialogField, UfiDialogTextField, UfiDialogPasswordField,
 *                 UfiDialogSwitchField, UfiDialogChipSelector, UfiDialogInfoRow,
 *                 UfiDialogSectionTitle, UfiDialogWarning, UfiDialogActions
 * - Feedback: UfiToastHost, UfiLoadingIndicator, UfiLinearLoading, UfiLoadingBox,
 *             UfiShimmerLoading, UfiCompactProgressBar
 * - Display: UfiDivider, UfiEmptyState, UfiSectionHeader, UfiSectionGroupTitle, UfiStatItem,
 *            UfiTrafficTile, UfiInfoRow, UfiLogPanel, UfiCodeEditorCard
 * - Badges: UfiBadge
 * - Charts: UfiMonitorChart, SeriesLegendChips
 *
 * ## Animation Modifiers (from com.ufi_axis.ui.animation)
 * - Modifier.staggeredEntrance(index) — list item staggered fade-in
 * - Modifier.blurEntrance(key) — blur-to-clear page entrance
 * - Modifier.clickScale { onClick } — press-scale feedback
 * - ThemeRevealWrapper — theme change crossfade
 *
 * ## Stateful Dialog Helpers
 * Use [Ufi.rememberDialogState] for managing dialog visibility:
 * ```
 * val dialog = Ufi.rememberDialogState()
 * UfiButton(text = "Delete", variant = UfiButtonVariant.Danger, onClick = { dialog.show() })
 * if (dialog.isVisible) {
 *     UfiConfirmDialog(
 *         title = "Confirm",
 *         text = "Are you sure?",
 *         onConfirm = { dialog.hide(); doDelete() },
 *         onDismiss = { dialog.hide() }
 *     )
 * }
 * ```
 */
object Ufi {

    /**
     * Creates a dialog state holder for managing dialog visibility.
     */
    @Composable
    fun rememberDialogState(): DialogState {
        var isVisible by remember { mutableStateOf(false) }
        return remember {
            DialogState(
                isVisibleGetter = { isVisible },
                showAction = { isVisible = true },
                hideAction = { isVisible = false }
            )
        }
    }

    /**
     * Creates a stateful dialog state with an associated data payload.
     * Useful for dialogs that need to pass data (e.g., item to delete).
     */
    @Composable
    fun <T> rememberDataDialogState(): DataDialogState<T> {
        var isVisible by remember { mutableStateOf(false) }
        var data by remember { mutableStateOf<T?>(null) }
        return remember {
            DataDialogState(
                isVisibleGetter = { isVisible },
                dataGetter = { data },
                showAction = { d -> data = d; isVisible = true },
                hideAction = { data = null; isVisible = false }
            )
        }
    }
}

class DialogState internal constructor(
    private val isVisibleGetter: () -> Boolean,
    private val showAction: () -> Unit,
    private val hideAction: () -> Unit
) {
    val isVisible: Boolean get() = isVisibleGetter()
    fun show() = showAction()
    fun hide() = hideAction()
}

class DataDialogState<T> internal constructor(
    private val isVisibleGetter: () -> Boolean,
    private val dataGetter: () -> T?,
    private val showAction: (T) -> Unit,
    private val hideAction: () -> Unit
) {
    val isVisible: Boolean get() = isVisibleGetter()
    val data: T? get() = dataGetter()
    fun show(data: T) = showAction(data)
    fun hide() = hideAction()
}

/**
 * ⚠ **已迁移（P3a，2026-09-04）：真正的定义现在在
 * [com.ufi_axis.ui.theme.UfiMotion]（`theme/MotionTokens.kt`）。**
 *
 * **新代码请从 `com.ufi_axis.ui.theme` 引入**，不要再用本对象。
 *
 * 搬走的原因（计划书 G2「把散乱的 UI 设置集合到一处」）：动效是**唯一**不在 `ui.theme` 的
 * 令牌类别 —— 时长/缓动在本文件、spring 参数在 `animation/UfiAnimations.kt`，配色/字体/圆角
 * 却都在 `ui.theme`。想调一个动画时长得在三处之间找。现在全部令牌收归 `ui.theme` 一个包，
 * 索引表见 `docs/app-theme-token-index.md`。
 *
 * 本对象只剩一层**零行为的转发**（每个成员逐一转发到 `ui.theme` 的同名成员，值与类型未变），
 * 存在的唯一理由是：全库约 287 处动画调用点若一次性改 import，diff 会大到无法评审。
 * 因此旧 import（`import com.ufi_axis.ui.components.common.UfiMotion`）与
 * 同包内的无 import 直接引用**零改动仍可编译**，只会拿到废弃警告；
 * 调用点随后续维护自然迁移，全部迁完后删除本对象。
 *
 * 为什么是转发 `object` 而不是 `typealias`：Kotlin 的 `typealias` 只在**类型位置**生效，
 * `UfiMotion.Duration.Base` 这种"通过对象名访问成员"的表达式位置用不了别名，
 * 旧调用点会直接编译失败 —— 那就不是过渡层了。
 *
 * 依赖方向未变：数值仍只存在于 [com.ufi_axis.ui.theme.UfiAnimSpecs]（明细层），
 * 由 [com.ufi_axis.ui.theme.UfiMotion]（语义层）转发，本对象是语义层之上的兼容壳。
 */
@Deprecated(
    message = "已迁至 com.ufi_axis.ui.theme.UfiMotion（P3a：所有设计令牌收归 ui.theme）。" +
        "新代码请从 ui.theme 引入；本转发层仅为避免一次性改动约 287 处动画调用点。"
)
object UfiMotion {

    /** 时长梯度转发层。档位说明与"为什么是这个值"见 [com.ufi_axis.ui.theme.UfiMotion.Duration]。 */
    object Duration {
        /** 90ms — 轻量浮层淡出 */
        const val Flick = com.ufi_axis.ui.theme.UfiMotion.Duration.Flick
        /** 120ms — 按压 / chip 选中等微反馈 */
        const val Micro = com.ufi_axis.ui.theme.UfiMotion.Duration.Micro
        /** 160ms — 轻量跟手反馈；轻量浮层淡入 */
        const val Swift = com.ufi_axis.ui.theme.UfiMotion.Duration.Swift
        /** 180ms — 列表项、开关、次级面板展开 */
        const val Quick = com.ufi_axis.ui.theme.UfiMotion.Duration.Quick
        /** 200ms — 默认淡入淡出 */
        const val Base = com.ufi_axis.ui.theme.UfiMotion.Duration.Base
        /** 220ms — 输入栏 / 胶囊导航 / 标签切换 */
        const val Standard = com.ufi_axis.ui.theme.UfiMotion.Duration.Standard
        /** 250ms — 通用轻量入场 / 离场 */
        const val Fluid = com.ufi_axis.ui.theme.UfiMotion.Duration.Fluid
        /** 280ms — 页面内区块切换 */
        const val Gentle = com.ufi_axis.ui.theme.UfiMotion.Duration.Gentle
        /** 300ms — 数据刷新后的内容淡入 */
        const val Smooth = com.ufi_axis.ui.theme.UfiMotion.Duration.Smooth
        /** 320ms — 页面转场默认时长 */
        const val Sweeping = com.ufi_axis.ui.theme.UfiMotion.Duration.Sweeping
        /** 400ms — 首屏 / 强调型入场 */
        const val Deliberate = com.ufi_axis.ui.theme.UfiMotion.Duration.Deliberate
        /** 420ms — 强调型入场，比 [Deliberate] 慢半拍 */
        const val Emphatic = com.ufi_axis.ui.theme.UfiMotion.Duration.Emphatic
        /** 600ms — 圆形揭示 / 骨架屏 / 图表绘入 */
        const val Reveal = com.ufi_axis.ui.theme.UfiMotion.Duration.Reveal
        /** 800ms — "慢而稳"的自绘动画 */
        const val Languid = com.ufi_axis.ui.theme.UfiMotion.Duration.Languid
        /** 1000ms — 无限旋转一圈的周期 */
        const val Orbit = com.ufi_axis.ui.theme.UfiMotion.Duration.Orbit
        /** 1200ms — 无限循环 / 呼吸类环境动效 */
        const val Ambient = com.ufi_axis.ui.theme.UfiMotion.Duration.Ambient
        /** 1500ms — 扩散脉冲一轮的周期，全站最慢档 */
        const val Pulse = com.ufi_axis.ui.theme.UfiMotion.Duration.Pulse
    }

    /**
     * 按压缩放梯度转发层。分层规则（面积越小缩得越多）见
     * [com.ufi_axis.ui.theme.UfiMotion.PressScale]。
     */
    object PressScale {
        /** 0.92 — FAB */
        const val Fab = com.ufi_axis.ui.theme.UfiMotion.PressScale.Fab
        /** 0.94 — 密排网格小格 */
        const val Cell = com.ufi_axis.ui.theme.UfiMotion.PressScale.Cell
        /** 0.96 — 按钮族 */
        const val Button = com.ufi_axis.ui.theme.UfiMotion.PressScale.Button
        /** 0.97 — chip / 选项格 / 可点列表行 */
        const val Chip = com.ufi_axis.ui.theme.UfiMotion.PressScale.Chip
    }

    /** 缓动曲线转发层。见 [com.ufi_axis.ui.theme.UfiMotion.Easing]。 */
    object Easing {
        /** 标准缓动 */
        val Standard: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.Standard
        /** 匀速 */
        val Linear: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.Linear
        /** 加速离场 */
        val Accelerate: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.Accelerate
        /** 强调减速（入场） */
        val EmphasizedIn: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.EmphasizedIn
        /** 强调加速（离场） */
        val EmphasizedOut: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.EmphasizedOut
        /** 轻微过冲 */
        val Overshoot: androidx.compose.animation.core.Easing get() = ThemeMotion.Easing.Overshoot
    }

    /** Standard entrance animation */
    val standard: AnimationSpec<Float> get() = ThemeMotion.standard

    /** Slow, gentle animation for backgrounds and subtle elements */
    val slow: AnimationSpec<Float> get() = ThemeMotion.slow

    // ===== 弹性（spring）语义入口：转发到 ui.theme 的同名成员 =====

    /** 通用点按缩放反馈 */
    fun <T> press(): SpringSpec<T> = ThemeMotion.press()

    /** 按钮按压（无回弹，紧实） */
    fun <T> buttonPress(): SpringSpec<T> = ThemeMotion.buttonPress()

    /** 小控件弹出（勾选框 / FAB） */
    fun <T> controlPop(): SpringSpec<T> = ThemeMotion.controlPop()

    /** 发送按钮弹出 */
    fun <T> sendPop(): SpringSpec<T> = ThemeMotion.sendPop()

    /** 弹窗入场 */
    fun <T> dialog(): SpringSpec<T> = ThemeMotion.dialog()

    /** 列表交错入场 */
    fun <T> stagger(): SpringSpec<T> = ThemeMotion.stagger()

    /** Toast 落下 */
    fun <T> toast(): SpringSpec<T> = ThemeMotion.toast()

    /** 面板入场缩放 */
    fun <T> panelEnter(): SpringSpec<T> = ThemeMotion.panelEnter()

    /** 标签选中弹跳 */
    fun <T> tagPop(): SpringSpec<T> = ThemeMotion.tagPop()

    /** 提示气泡弹出 */
    fun <T> tooltipPop(): SpringSpec<T> = ThemeMotion.tooltipPop()

    /** 指示条滑动 */
    fun <T> tabSlider(): SpringSpec<T> = ThemeMotion.tabSlider()

    /** 翻页脉冲 */
    fun <T> pagePulse(): SpringSpec<T> = ThemeMotion.pagePulse()

    /** 开关拇指位移 */
    fun <T> switchThumb(): SpringSpec<T> = ThemeMotion.switchThumb()

    /** 颜色沉降（无回弹） */
    fun <T> colorSettle(): SpringSpec<T> = ThemeMotion.colorSettle()

    /** 颜色快切 */
    fun <T> colorSwap(): SpringSpec<T> = ThemeMotion.colorSwap()

    /** 滑块轨道跟随 */
    fun <T> sliderTrack(): SpringSpec<T> = ThemeMotion.sliderTrack()

    /**
     * 标题模糊入场时长（350ms，**有意例外**不在 [Duration] 梯度上）。
     * 理由见 [com.ufi_axis.ui.theme.UfiMotion.HEADER_TITLE_BLUR_MS]。
     */
    const val HEADER_TITLE_BLUR_MS = com.ufi_axis.ui.theme.UfiMotion.HEADER_TITLE_BLUR_MS

    /** 列表项交错入场的逐项延迟（35ms）。见 [com.ufi_axis.ui.theme.UfiMotion.STAGGER_DELAY_MS]。 */
    const val STAGGER_DELAY_MS = com.ufi_axis.ui.theme.UfiMotion.STAGGER_DELAY_MS

    /** Content fade duration for data updates */
    const val DATA_FADE_MS = com.ufi_axis.ui.theme.UfiMotion.DATA_FADE_MS
}
