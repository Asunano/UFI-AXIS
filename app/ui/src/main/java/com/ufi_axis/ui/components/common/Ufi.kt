// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║  UFI-AXIS Design Center — Unified UI Component Hub     ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Central access point for the UFI-AXIS design system.
 *
 * ## Components
 * （2026-08-30 公共层去重后的清单；新增组件请同步这里）
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
 *            UfiTrafficTile, UfiInfoRow, UfiInfoCell, UfiLogPanel, UfiCodeEditorCard
 * - Badges: UfiBadge
 * - Charts: UfiMonitorChart, SeriesLegendChips
 *
 * ## Animation Modifiers (from com.ufi_axis.ui.animation)
 * - Modifier.blurEntrance(key) — fade + translateY 页面入场（只在首次 / key 变化时播）
 * - Modifier.ufiPressScale(...) / rememberUfiPressScale / rememberUfiPressed — 全站唯一按压反馈实现
 * - ThemeRevealWrapper — theme change crossfade
 *
 * 本清单只列**当前存在**的入口：新增按压 / 入场效果请加到既有 Modifier 的参数上，
 * 不要再造第二个同用途的 Modifier（这就是 ufiPressScale 收敛成"全站唯一按压反馈实现"的理由）。
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
