package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
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
 * - Layout: UfiScreenScaffold, UfiPageBackground, UfiPageBackgroundBox, UfiHeader
 * - Cards: UfiSettingsGroup, UfiCollapsibleGroup, UfiInfoCard, UfiErrorBanner, UfiOfflineBanner
 * - Navigation: UfiNavigationItem, UfiTabRow, UfiScrollableTabRow
 * - Input: UfiTextField, UfiPasswordField, UfiDigitField, UfiSearchBar, UfiInputWithAction
 * - Selection: UfiSwitch, UfiCheckbox, UfiAnimatedCheckbox, UfiSlider, UfiDropdown,
 *             UfiSingleChipSelector, UfiMultiChipSelector, UfiIntChipSelector
 * - Buttons: UfiPrimaryButton, UfiSecondaryButton, UfiSmallButton, UfiDangerButton, UfiButtonRow
 * - Dialogs: UfiAlertDialog, UfiConfirmDialog, UfiInputDialog, UfiChoiceSheet, UfiLoadingDialog
 * - Feedback: UfiToastHost, UfiErrorBanner, UfiLoadingIndicator, UfiLinearLoading
 * - Display: UfiDivider, UfiEmptyState, UfiSectionHeader, UfiSectionGroupTitle, UfiStatItem,
 *            UfiCodeBlock, UfiHistoryItem, UfiResultCard, UfiInfoRow
 * - Badges: UfiBadge, UfiTag
 * - Data: UfiAnimatedNumber, UfiAnimatedCounter, UfiAnimatedIntCounter
 * - Loading: UfiSkeletonCard, UfiSkeletonLine, UfiSkeletonCircle, UfiSkeletonGroup, UfiShimmerLoading
 * - FAB: UfiFloatingActionButton, UfiExtendedFab
 *
 * ## Animation Modifiers (from com.ufi_axis.ui.animation)
 * - Modifier.staggeredEntrance(index) — list item staggered fade-in
 * - Modifier.blurEntrance(key) — blur-to-clear page entrance
 * - Modifier.clickScale { onClick } — press-scale feedback
 * - AnimatedText — blur-transition text
 * - ThemeRevealWrapper — theme change crossfade
 *
 * ## Stateful Dialog Helpers
 * Use [Ufi.rememberDialogState] for managing dialog visibility:
 * ```
 * val dialog = Ufi.rememberDialogState()
 * UfiPrimaryButton("Delete", onClick = { dialog.show() })
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
 * Unified motion constants for the UFI-AXIS design system.
 * Wraps and extends [com.ufi_axis.ui.animation.UfiAnimSpecs].
 */
object UfiMotion {
    /** Quick micro-interaction (button press, toggle) */
    val quick: AnimationSpec<Float>
        get() = spring(stiffness = 600f, dampingRatio = 0.6f)

    /** Standard entrance animation */
    val standard: AnimationSpec<Float>
        get() = tween(400, easing = FastOutSlowInEasing)

    /** Emphasized entrance with overshoot */
    val emphasized: AnimationSpec<Float>
        get() = spring(dampingRatio = 0.5f, stiffness = 380f)

    /** Slow, gentle animation for backgrounds and subtle elements */
    val slow: AnimationSpec<Float>
        get() = tween(800, easing = FastOutSlowInEasing)

    /** Page transition duration in milliseconds */
    const val PAGE_TRANSITION_MS = 350

    /** Stagger delay between list items in milliseconds */
    const val STAGGER_DELAY_MS = 50L

    /** Content fade duration for data updates */
    const val DATA_FADE_MS = 300
}
