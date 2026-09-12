// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.ufi_axis.ui.R as UiR
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiInheritUiScale
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import kotlinx.coroutines.delay
// FIX-7（2026-08-23）：shell 默认 × close 图标。
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.ufi_axis.ui.theme.UfiMotion

// ─────────────────────────────────────────────────
// Dialog animation constants (centralized)
// ─────────────────────────────────────────────────

internal object UfiDialogAnim {
    /** System window background blur radius applied via FLAG_BLUR_BEHIND + blurBehindRadius (API 31+). */
    const val BlurRadius = 110

    /** Exit target scale for the card content (AnimatedVisibility scaleOut). */
    const val ExitScaleTarget = 0.92f
    /**
     * Exit duration (ms) for the card content fade/scale-out.
     *
     * 语义：退出要比入场略快但不能显得仓促。
     *
     * 2026-09-04（P2b）：原为裸 `240`，注释写「刻意不在 UfiMotion.Duration 档位上」。
     * 现在梯度里有了 [UfiMotion.Duration.Fluid]（250），语义恰好就是这条
     * （"比进场快半拍但不仓促"，位于 Standard 220 与 Gentle 280 之间），
     * 差 10ms 在吸附容差内，故并入该档 —— 目的是不让"离场时长"这一种手感在全库有两个数。
     * 本对象不再是数值来源，只是把 token 转成 shell 内部的命名。
     */
    const val ExitDuration = UfiMotion.Duration.Fluid
}

// Logcat tag used by the debug-only blur diagnostics in [UfiDialogShell].
private const val TAG = "UfiDialogShell"

/**
 * True only for debuggable builds.
 *
 * The `:app:ui` module is an Android **library**, and AGP 8+ no longer generates
 * `BuildConfig` for libraries by default. To keep the blur diagnostics debug-only
 * WITHOUT enabling `buildFeatures { buildConfig = true }` (which would require
 * editing this module's build file), we detect debuggability at runtime via the
 * application's `FLAG_DEBUGGABLE` flag — the standard, dependency-free equivalent
 * of `BuildConfig.DEBUG`.
 */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * Applies the OS-native cross-window blur to a dialog's [Window] (API 31+).
 *
 * Uses **FLAG_BLUR_BEHIND + attributes.blurBehindRadius** — the path proven to
 * work in UFITOOLS-Widget. This blurs the content *behind* the window and does
 * NOT depend on the window's own background drawable, which is what makes it
 * reliable inside Compose's `DialogWrapper`.
 *
 * `Window.setBackgroundBlurRadius` was tried before but failed here: it relies
 * on a translucent window background drawable to define the blur region, and
 * `DialogWrapper.show()` overwrites that drawable with a transparent one — so
 * the blur silently disappeared. `blurBehindRadius` is independent of the
 * window background and survives that reset.
 *
 * The change is posted on [Window.getDecorView] so it runs *after*
 * `DialogWrapper.show()` re-applies the window attributes, otherwise the
 * blur flags/radius would be wiped. Safe to call repeatedly (idempotent);
 * a null window or pre-API-31 build is ignored.
 */
private fun applyDialogBackgroundBlur(window: Window?) {
    if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    window.decorView.post {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@post
        // Cross-window blur behind this window (independent of the window's own
        // background drawable, so it survives DialogWrapper.show() resetting it).
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        // Keep dimming on Compose's own scrim (the full-screen Box); suppress the
        // platform dim so it doesn't stack on top of it and over-darken.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = UfiDialogAnim.BlurRadius
        }
    }
}

// ─────────────────────────────────────────────────
// Internal dialog shell — animated card
//
// Outside-tap dismissal: the outer full-screen Box (which centers the card)
// would otherwise cover Compose's built-in scrim, so we implement tap-outside
// directly — the outer Box is `clickable` and calls `onDismiss()` when
// `dismissOnClickOutside` is true; the inner card Box has a no-op `clickable`
// (indication=null) that *consumes* taps inside the card so they don't bubble
// up and accidentally dismiss. `dismissOnBackPress` still uses Compose's native
// back-press handling via DialogProperties.
//
// Background blur: uses the system cross-window blur via
// `FLAG_BLUR_BEHIND + attributes.blurBehindRadius` (Android 12 / API 31+),
// obtained through the official `DialogWindowProvider`. This is the OS-native
// cross-window blur — no custom shader, no self-written ValueAnimator ramp.
// It blurs the content *behind* the window and is independent of the window's
// own background drawable, which is what makes it survive Compose's
// `DialogWrapper.show()` resetting the window attributes (the previous
// `setBackgroundBlurRadius` attempt failed exactly there). Below API 31 it
// silently no-ops and the dialog falls back to the Compose scrim only.
// ─────────────────────────────────────────────────

@Composable
internal fun UfiDialogShell(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    title: String? = null,
    icon: Painter? = null,
    containerColorOverride: Color? = null,
    borderOverride: BorderStroke? = null,
    scrimAlpha: Float = 0.5f,
    titleColorOverride: Color? = null,
    // FIX-7（2026-08-23）：title 左侧前置 Composable slot，优先级高于 [icon: Painter?]。
    // 调用方需要展示非 Painter 类标题图标（ImageVector / 色块圆 Icon / 状态点等）时，
    // 通过该 slot 传入自定义 Composable，无需为其新增 drawing resource。
    titleLeading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    // FIX-7（2026-08-23 · 23:55）：默认右上角 × 关闭按钮。
    // 当 showCloseIcon=true 时，shell 会在 title 行右上角自动渲染一个 24dp × IconButton，
    // 点击触发 onDismiss()。调用方显式传 trailingContent 时优先级高于 showCloseIcon
    // （保留完全定制能力，比如「设置」图标/复选框等）。
    showCloseIcon: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // Mounted-state pattern: keep the Dialog mounted while the exit animation
    // plays, otherwise the scale-out + fade-out transition would never run.
    var mounted by remember { mutableStateOf(visible) }
    var shown by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            // 进入交给平台 Window 动画（setWindowAnimations）整体淡入，
            // 不再用 delay(16) 两阶段，避免 scrim 先出现一帧再弹卡片的闪烁。
            mounted = true
            shown = true
        } else {
            // 统一为 B 类观感：关闭时不做卡片级退出动画，整窗（卡片+遮罩）随平台
            // ufi_dialog_exit 一同缩放淡出。关键：不能先 shown=false 让卡片单独消失再揭窗，
            // 否则会出现「卡片瞬间消失、只剩遮罩随后淡出」的闪烁——卡片须保持可见，
            // 随整窗一起在 ufi_dialog_exit 里平滑淡出。
            delay(16L)
            mounted = false
        }
    }
    if (!mounted) return

    val palette = LocalResolvedPalette.current
    val cardContainer = containerColorOverride ?: palette.cardBg
    val cardBorder = borderOverride ?: BorderStroke(2.dp, palette.dialogBorder)
    val titleColor = titleColorOverride ?: palette.textPrimary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress
        )
    ) {
        // System background blur via the official DialogWindowProvider → Window.
        //
        // The dialog's Window can be reached from several positions in the view
        // tree, and the exact position varies across Compose versions / emulators:
        //   • the content ComposeView itself,
        //   • its `context` (the Activity in some versions → not a provider),
        //   • its direct `parent`,
        //   • or higher up after traversing intermediate containers.
        // We try ALL of them and walk the parent chain so we always resolve the
        // real dialog Window. If `window` is null here, the blur is silently
        // skipped — the debug log below will reveal it (see TAG = "UfiDialogShell").
        val localView = LocalView.current
        val window = remember(localView) {
            (localView as? DialogWindowProvider)?.window
                ?: (localView.context as? DialogWindowProvider)?.window
                ?: (localView.parent as? DialogWindowProvider)?.window
                ?: run {
                    var p = localView.parent
                    while (p is ViewParent) {
                        (p as? DialogWindowProvider)?.window?.let { return@run it }
                        p = (p as? View)?.parent
                    }
                    null
                }
        }

        // 平台 Window 进入动画：整个窗口（遮罩+卡片）统一缩放淡入，消除闪烁。
        window?.setWindowAnimations(UiR.style.UfiDialogAnimation)

        // [Debug-only] Diagnostic log: confirms whether the dialog Window was
        // resolved, the view/context class names, and the runtime SDK level.
        // Wrapped in isDebugBuild() (FLAG_DEBUGGABLE) so release builds stay silent.
        LaunchedEffect(Unit) {
            if (isDebugBuild(localView.context)) {
                Log.d(
                    TAG,
                    "windowResolved=${window != null} " +
                        "view=${localView.javaClass.simpleName} " +
                        "ctx=${localView.context.javaClass.simpleName} " +
                        "sdkInt=${Build.VERSION.SDK_INT}"
                )
            }
        }

        // Apply the blur immediately, then re-apply after the next frames.
        // Compose's Dialog internals may reset the window background after the
        // composition commits, which would otherwise wipe our blur region — the
        // delayed re-apply restores it. LaunchedEffect auto-cancels on dispose,
        // so no stale blur survives (DisposableEffect clears it too).
        LaunchedEffect(window) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
                applyDialogBackgroundBlur(window)
                if (isDebugBuild(localView.context)) {
                    Log.d(TAG, "blur applied immediately (radius=${UfiDialogAnim.BlurRadius})")
                }
                delay(100L)
                applyDialogBackgroundBlur(window)
                if (isDebugBuild(localView.context)) {
                    Log.d(TAG, "blur re-applied post-layout (radius=${UfiDialogAnim.BlurRadius})")
                }
            }
        }

        DisposableEffect(window) {
            // 2026-09-04：把本弹窗的 window 登记给 Toast 层。
            // Toast 挂在 Activity.window.decorView，而这里是独立 Window（z 序更高），
            // 不登记的话"弹窗内输入校验失败弹 toast"这类反馈必然被弹窗盖住 ——
            // 而那恰好是最需要 toast 的场合。登记后 toast 会挂到栈顶弹窗的 decorView，
            // 即渲染在弹窗窗口内部（在弹窗内容之上，且不受 FLAG_BLUR_BEHIND 模糊影响）。
            window?.let { UfiToastOverlay.pushDialogHost(it) }
            onDispose {
                // ★★ 不变量（2026-09-05 崩溃修复）：本 onDispose 跑在**窗口正在销毁**的栈里，
                //    因此**不得**改动 decor 的子 View、也不得改写窗口 LayoutParams。★★
                //
                // 精确时序（R8 mapping 已还原真机栈，见 UfiToastOverlay.popDialogHost 的 KDoc）：
                //   Compose 的 Dialog onDispose → Dialog.dismiss()
                //     → WindowManagerImpl.removeViewImmediate → ViewRootImpl.doDie
                //     → DecorView.dispatchDetachedFromWindow()      // 正在遍历 children 快照
                //       → DialogLayout（AbstractComposeView）的 detach 监听
                //       → disposeComposition() → **本 onDispose**
                // 也就是说：这里的每一行都插在平台的窗口销毁流程**中间**执行。
                //
                // 曾经的两处违规：
                // 1. popDialogHost 内部 removeView(toast) —— 在平台已快照的 children 数组里
                //    留下 null 空洞，循环下一轮 NPE（已修，见该函数）；
                // 2. 下面原本还有一段 clearFlags(FLAG_BLUR_BEHIND) + attributes = … ——
                //    它会走 Window.setFlags → Dialog.onWindowAttributesChanged →
                //    WindowManager.updateViewLayout → ViewRootImpl.setLayoutParams +
                //    scheduleTraversals，即**给一个已经进入 doDie() 的 ViewRootImpl 排一次
                //    新的 traversal**。且它是纯粹的死代码：`window` 由 `remember(localView)`
                //    持有，与本弹窗窗口同生命周期，本效应 dispose 时窗口必然正在销毁，
                //    模糊会随窗口一起消失，无需（也无从）清除。故整段删除。
                window?.let { UfiToastOverlay.popDialogHost(it) }
            }
        }

        // 全局 UI 缩放补偿：Dialog 是独立 Window，Compose 会在这棵子组合根部重新
        // `LocalDensity provides owner.density`，把 UFIAXISTheme 覆盖的缩放 density 冲掉。
        // 所有 Ufi*Dialog 都经由本 Shell，故补在这一处即可让全部弹窗跟随全局缩放。
        UfiInheritUiScale {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = dismissOnClickOutside,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    // P2-1：scrim 声明 Button 角色，读屏用户可感知"点击外部关闭"
                    role = Role.Button,
                    onClickLabel = "点击空白处关闭弹窗"
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            val maxDialogH = with(LocalConfiguration.current) {
                (screenHeightDp * 0.82f).dp
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .heightIn(max = maxDialogH)
                    .padding(horizontal = 16.dp)
            ) {
                AnimatedVisibility(
                    visible = shown,
                    enter = EnterTransition.None,
                    // 统一为 B 类观感：关闭时不做卡片级 scaleOut+fadeOut，仅由平台窗口
                    // ufi_dialog_exit（240ms）承担整窗淡出，与 when 硬卸载的弹窗完全一致。
                    exit = ExitTransition.None
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxDialogH)
                            .ufiCardShadow(elevation = UfiCardDefaults.elevationLevel3Dp, shape = UfiCardDefaults.dialogShape)
                            .background(cardContainer, UfiCardDefaults.dialogShape)
                            .border(
                                width = cardBorder.width,
                                color = palette.dialogBorder,
                                shape = UfiCardDefaults.dialogShape
                            )
                            // 消费卡片内部的点击，避免冒泡到外层 Box 触发关闭；
                            // 无涟漪（indication=null），不影响内部按钮/输入框等交互。
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.DialogPaddingH)
                                .padding(bottom = Spacing.Medium)
                        ) {
                            if (title != null) {
                                // 标题行：Box 包裹 —— 左侧 icon+title，右侧 trailingContent（右上角 X 关闭等自定义槽）。
                                // 水平 padding 继承内容列（唯一来源 DialogPaddingH），这里只留垂直呼吸。
                                // 2026-09-04：顶距原为 DialogPaddingV(24dp)，与左右的 DialogPaddingH(18dp) 不等，
                                // 标题看着"往下掉"一截。改为同用 DialogPaddingH —— 上、左、右三边同值，
                                // 标题块四周才是均匀的。
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Spacing.DialogPaddingH)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    ) {
                                        // FIX-7：titleLeading Composable slot 优先于 icon Painter，
                                        // 给调用方完全自定义标题图标的能力（如色块 ImageVector）。
                                        if (titleLeading != null) {
                                            titleLeading()
                                            Spacer(Modifier.width(Spacing.Large))
                                        } else if (icon != null) {
                                            Icon(
                                                painter = icon,
                                                contentDescription = null,
                                                tint = palette.accent,
                                                modifier = Modifier.size(Spacing.IconSizeDialog)
                                            )
                                            Spacer(Modifier.width(Spacing.Large))
                                        }
                                        Text(
                                            title,
                                            // 2026-09-04：原为 UfiTextStyles.screenTitle（titleLarge 22sp + Strong）——
                                            // 那是**页面**标题的字阶，压在弹窗里与正文的层级差不够，标题不像标题。
                                            // 改用 UfiTextStyles.dialogTitle（headlineSmall 24sp，该 token 此前零调用点，
                                            // 本来就是为弹窗留的）+ Strong 字重，字号与字重同时上一档。
                                            style = UfiTextStyles.dialogTitle.copy(fontWeight = UfiWeight.Strong),
                                            color = titleColor
                                        )
                                    }
                                    // 右端槽位优先级：trailingContent（调用方显式传）> showCloseIcon（默认 ×）。
                                    // 任一为有效值才占位，避免双 × 重叠。
                                    val hasTrailing = trailingContent != null
                                    val effectiveCloseIcon = showCloseIcon && !hasTrailing
                                    if (hasTrailing) {
                                        Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailingContent!!() }
                                    } else if (effectiveCloseIcon) {
                                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                            // FIX-8（2026-08-23）：× 关闭按钮尺寸 32dp/18dp → 44dp/22dp（Material 标准触控区）。
                                            // 用 cardBg 加透明圆背景，让按钮更显眼、可点击区域更直观。
                                            Surface(
                                                onClick = onDismiss,
                                                shape = androidx.compose.foundation.shape.CircleShape,
                                                color = palette.pageBg.copy(alpha = 0.85f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Close,
                                                        contentDescription = "关闭",
                                                        tint = palette.textSecondary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // 标题与内容之间的呼吸感。2026-09-04：8dp → Spacing.Large（12dp）——
                            // 标题字阶从 22sp 提到 24sp 后，8dp 显得标题"压"在内容上；间距要跟着字号走，
                            // 否则层级差被间距吃回去。（原注释：避免「自定义时间范围」贴住下方 StepCard）
                            Spacer(Modifier.height(Spacing.Large))
                            // 内容水平内边距：避免弹窗内元素贴近边框（之前缺失导致更新设置等弹窗贴近左右边框）
                            content()
                        }
                    }
                }
            }
        } // Box (scrim)
        } // UfiInheritUiScale
    }
}

// ─────────────────────────────────────────────────
// Internal reusable button row inside dialog
// ─────────────────────────────────────────────────

@Composable
// P2-5：公共版按钮行（去 internal 公开供各模块复用）
fun DialogButtonRow(
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmColor: Color = LocalResolvedPalette.current.accent,
    fontWeight: FontWeight = FontWeight.Bold,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    dividerColor: Color? = null,
    outlineColor: Color? = null,
    outlineTextColor: Color? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    // 2026-09-04：这两个按钮原来是裸 M3 OutlinedButton / Button，**没有按压缩放** ——
    // 只有 M3 默认 ripple。于是"弹窗里的按钮没反馈"成了全站最显眼的一处不一致：
    // 页面里的按钮（UfiButton / EventsSettingsButton…）都会缩，唯独最常用的弹窗确认/取消不缩。
    // 现在按 PressScale 分层规则接入按钮族档位（0.96 + buttonPress），播放走 ufiPressScale
    // （事件驱动 + 最短保持 120ms，短按也看得见，原因见 animation/PressFeedback.kt 文件头）。
    // 两个按钮各自一份 interactionSource：它们是独立可点区，共享会让点一个另一个也缩。
    val dismissInteraction = remember { MutableInteractionSource() }
    val confirmInteraction = remember { MutableInteractionSource() }
    HorizontalDivider(
        color = dividerColor ?: palette.divider.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText != null && onDismiss != null) {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, outlineColor ?: palette.dialogBorder),
                // 2026-09-08：原为 palette.textPrimary。同一个"取消"在两条路径上不同色 ——
                // UfiDialogActions 走 UfiButton(Secondary) 是 accent，这里是 textPrimary，
                // 同一个 App 里两种弹窗的取消按钮观感不一致。统一到 Secondary 的口径。
                colors = ButtonDefaults.outlinedButtonColors(contentColor = outlineTextColor ?: palette.accent),
                shape = UfiCardDefaults.buttonShape,
                enabled = enabled,
                interactionSource = dismissInteraction,
                modifier = Modifier
                    .weight(1f)
                    .height(Spacing.DialogButtonHeight)
                    .ufiPressScale(
                        interactionSource = dismissInteraction,
                        pressedScale = UfiMotion.PressScale.Button,
                        spec = UfiMotion.buttonPress()
                    )
            ) {
                Text(dismissText, fontSize = fontSize, fontWeight = fontWeight)
            }
        }
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = confirmColor,
                contentColor = palette.onAccent
            ),
            shape = UfiCardDefaults.buttonShape,
            enabled = enabled,
            interactionSource = confirmInteraction,
            modifier = Modifier
                .weight(1f)
                .height(Spacing.DialogButtonHeight)
                .ufiPressScale(
                    interactionSource = confirmInteraction,
                    pressedScale = UfiMotion.PressScale.Button,
                    spec = UfiMotion.buttonPress()
                )
        ) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.onAccent
                )
            } else {
                Text(confirmText, fontSize = fontSize, fontWeight = fontWeight)
            }
        }
    }
}
