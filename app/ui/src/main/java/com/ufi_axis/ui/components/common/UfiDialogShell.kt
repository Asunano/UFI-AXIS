// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiInheritUiScale
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * 变暗（`dimAmount`）的峰值 —— **能模糊时**。
     *
     * 2026-09-17：此前 dim 是**彻底关掉**的（`clearFlags(FLAG_DIM_BEHIND)` + 主题
     * `backgroundDimEnabled=false`），理由是「遮罩由 shell 自己画」—— 但 shell 从来没画，
     * 于是弹窗背后只有模糊、没有压暗，浅色页面上弹窗与背景的层级分不开。
     * 现在改为由 [UfiDialogShell] 随模糊一起渐变施加。
     *
     * 0.10 是刻意压得很低的：主力手段是模糊，dim 只负责把"糊过的背景"再压一档以拉开层级；
     * 给大了会盖住模糊本身的质感，也会把 z 序更低的胶囊导航栏一起压黑。
     * 主题里的 `backgroundDimEnabled=false` **保持不变**（那是平台默认 dim 的开关，
     * 与这里按帧写入的 `dimAmount` 是两回事，且胶囊窗口的护栏测试盯着那个主题）。
     */
    const val DimWithBlur = 0.10f

    /**
     * 变暗峰值 —— **拿不到跨窗口模糊时**（2026-09-18）。
     *
     * 省电模式、开发者选项里关掉动画、以及低端机
     * （`ro.surface_flinger.supports_background_blur=false`）都会让 `FLAG_BLUR_BEHIND`
     * 静默失效。此前只判了 SDK 版本，于是在这些机器/状态上"背景什么都不会发生"——
     * 模糊没有、dim 又只有 0.10，弹窗后面完全没有层次，看着像界面错位。
     *
     * 0.30 仍比平台默认（0.6）浅不少：这套弹窗的设计取向是轻压暗。
     */
    const val DimFallback = 0.30f

    /**
     * backdrop 渐变的**量化步数**（0..[BackdropSteps]）。
     *
     * 为什么要量化：每一步都会走 `Window.attributes =` → `WindowManager.updateViewLayout`
     * → `ViewRootImpl.setLayoutParams` + `scheduleTraversals`。逐帧写（60/90/120fps）
     * 等于每帧一次窗口 relayout，纯属浪费；而 blur 半径这种视觉量，12 档已经看不出台阶
     * （110px / 12 ≈ 9px 一档）。同样的取舍在胶囊那侧是"相等则跳过"，见
     * `applyCapsuleWindowParams` 的 KDoc。
     */
    const val BackdropSteps = 12

    /**
     * 卡片离场的目标缩放（1.0 → 0.92），沿用原 `res/anim/ufi_dialog_exit.xml` 的值。
     *
     * 离场之所以改由 Compose 驱动：平台窗口退出动画只在窗口**销毁那一刻**播，而那时
     * 已经不能再写 `blurBehindRadius`（改一个正在 doDie 的窗口属性会崩）。
     * 要做到"弹窗完全消失的那一刻背景刚好最清晰"，卡片就必须在窗口还活着的时候退场，
     * 与背景共用同一条进度、同时到达端点。进场仍由平台窗口动画负责（480ms overshoot）。
     */
    const val CardExitScaleTo = 0.92f
}

// Logcat tag used by the debug-only blur diagnostics in [UfiDialogShell].
private const val TAG = "UfiDialogShell"

/**
 * 「请求关闭当前弹窗」的注入口（2026-09-17）。
 *
 * 存在的理由：backdrop 的"逐渐清晰"要求**先跑完 ramp 再真正 dismiss**，而 shell 只能拦到
 * 自己那三个关闭入口（点外部 / 返回键 / 右上角 ×）。弹窗内容里的按钮（确认 / 取消）是
 * 调用方给的 lambda，一按就把上层状态翻掉、整个弹窗当帧卸载 —— 窗口没了，ramp 无从播。
 * 全库 133 个弹窗调用点里有 85 个是 `if (show) { UfiXxxDialog(...) }` 这种硬挂载，
 * 光靠 `visible` 参数覆盖不到它们；而**把关闭动作延后到 ramp 之后**对两种挂载方式都成立。
 *
 * 用法（内容侧）：
 * ```
 * val close = LocalUfiDialogClose.current
 * UfiButton(text = "保存", onClick = { close { save(); showDialog = false } })
 * ```
 *
 * 公共按钮行 [DialogButtonRow] / [UfiDialogActions] **已自动接入**，用它们的调用点无需改动。
 *
 * 默认实现是**直接执行**（不在 shell 内、或没接入时的退化行为），因此接入是可选的、
 * 也不会因为组件被搬到弹窗外面而崩。
 *
 * 若交给它的动作**并没有真的关闭弹窗**（例如确认里做了校验决定留下），shell 会在下一帧
 * 发现弹窗仍然可见，把 backdrop 恢复回去 —— 不会留下一个"淡出了却还在"的弹窗。
 */
val LocalUfiDialogClose: ProvidableCompositionLocal<(() -> Unit) -> Unit> =
    staticCompositionLocalOf { { action: () -> Unit -> action() } }

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
 * 按当前进度把 backdrop（窗口级模糊 + 变暗）写进弹窗的 [Window]。
 *
 * 用 **FLAG_BLUR_BEHIND + attributes.blurBehindRadius**（API 31+）—— 它模糊的是窗口
 * **背后**已合成的画面，且不依赖窗口自身的背景 drawable，因此能活过 Compose
 * `DialogWrapper.show()` 对窗口属性的重置。曾经试过 `Window.setBackgroundBlurRadius`：
 * 它要靠半透明的窗口背景 drawable 界定模糊区域，而 `DialogWrapper.show()` 会把那张
 * drawable 换成全透明的，模糊就静默消失了。
 *
 * ## 四条不变量
 * 1. **必须 `decorView.post`**：要排在 `DialogWrapper.show()` 重放窗口属性之后，
 *    否则这次写入会被它覆盖掉。
 * 2. **flags 只在首次写入时置上，之后不再翻**（2026-09-18 修"动画收尾闪一下"）：
 *    原来按 `dim > 0` / `radius > 0` 反复 or/and flag，于是渐变的**第一步与最后一步**
 *    各会多一次 flag 变更 —— 而 flag 变更是整窗 relayout，落在动画收尾那一帧上就是
 *    肉眼可见的一闪。半径 0 / dimAmount 0 本身就等于"无效果"，不需要靠摘 flag 实现。
 * 3. **值没变就不写**（同上，与 `applyCapsuleWindowParams` 的 `if (settled) return`
 *    同一条经验）：AOSP 的 `Window.attributes =` 无条件派发 → `updateViewLayout` →
 *    `setLayoutParams` + `scheduleTraversals`，同值重写照样是一次 relayout。量化后的
 *    渐变本来就会连续给出相同档位，不守这一条等于白烧帧。
 * 4. **不在 `onDispose` 里调用**：那时窗口已进入 `doDie()`，改 LayoutParams 会给一个
 *    正在销毁的 ViewRootImpl 排新 traversal（2026-09-05 崩溃的成因，见下方 DisposableEffect）。
 *    模糊的清除由「离场先跑完 ramp 再真正 dismiss」保证，不靠 dispose 兜底。
 *
 * SDK < 31 或系统当下不支持跨窗口模糊（见 [canBlurBehind]）时 [blurCapable] 传 false：
 * 只写 dim、不挂模糊 flag，渐变观感退化为"逐渐压暗"，不报错。
 */
private fun applyDialogBackdrop(
    window: Window?,
    blurRadius: Int,
    dim: Float,
    blurCapable: Boolean
) {
    if (window == null) return
    window.decorView.post {
        val params = window.attributes ?: return@post
        // flags 一次置上、全程不翻（不变量 2）
        val desiredFlags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            if (blurCapable) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0
        // 值没变就整个跳过（不变量 3）
        val settled = params.flags == desiredFlags &&
            params.dimAmount == dim &&
            (!blurCapable || params.blurBehindRadius == blurRadius)
        if (settled) return@post
        window.attributes = params.apply {
            flags = desiredFlags
            dimAmount = dim
            if (blurCapable) {
                blurBehindRadius = blurRadius
            }
        }
    }
}

/**
 * 这台机器 / 这一刻支不支持跨窗口模糊（2026-09-18）。
 *
 * 只判 SDK 版本是不够的：`isCrossWindowBlurEnabled` 在**省电模式**、开发者选项里
 * **关闭动画**、以及不支持后台模糊的低端机（`ro.surface_flinger.supports_background_blur`）
 * 上都会是 false。此前漏了这一条，那些情况下 `blurBehindRadius` 写了也不生效，
 * 而 dim 又按"有模糊"的 0.10 给 —— 结果是弹窗背后毫无变化。
 *
 * false 时调用方必须把 dim 提到 [UfiDialogAnim.DimFallback]。
 */
private fun Window.canBlurBehind(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val manager = context.getSystemService(WindowManager::class.java) ?: return false
    return manager.isCrossWindowBlurEnabled
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
    // Mounted-state pattern：关闭时窗口必须多活一小会儿 —— backdrop 的"逐渐清晰"靠写
    // 窗口属性实现，窗口一销毁就无从可写（也**不能**在 onDispose 里写，见下方
    // DisposableEffect 的崩溃说明）。所以离场是：先跑 ramp，跑完才真正 dismiss / 卸载。
    var mounted by remember { mutableStateOf(visible) }

    /** 正在离场。置位后所有新的关闭请求都被忽略（防连点导致 ramp 被打断重启）。 */
    val closing = remember { mutableStateOf(false) }

    /**
     * ramp 跑完要执行的动作。
     *
     * - shell 自己的关闭入口（点外部 / 返回键 / 右上角 ×）→ 这里放 `onDismiss`，
     *   因为调用方还不知道要关；
     * - 调用方把 `visible` 翻成 false → 这里是 null，它自己已经关了，再回调一次会
     *   变成"关两次"（有些调用方的 onDismiss 里带副作用）。
     */
    val closeAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    /** backdrop 进度 0..1：模糊半径与 dim 都由它换算（见 [applyDialogBackdrop]）。 */
    val backdrop = remember { Animatable(0f) }

    val reduceMotion = LocalUfiReduceMotion.current

    /** 离场协程要读**最新**的 visible（effect 捕获的是启动那一刻的值，会读到旧的）。 */
    val visibleNow = rememberUpdatedState(visible)

    /** 统一的关闭入口：先起离场 ramp，[closeAction] 留给 ramp 结束时调用。 */
    val requestClose: (() -> Unit) -> Unit = remember {
        { action ->
            if (!closing.value) {
                closeAction.value = action
                closing.value = true
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            // 重新打开：清掉上一轮的离场状态（Animatable 由下面的入场 ramp 从 0 拉起）
            closing.value = false
            closeAction.value = null
            mounted = true
        } else if (mounted && !closing.value) {
            closing.value = true
        }
    }
    if (!mounted) return

    val palette = LocalResolvedPalette.current
    val cardContainer = containerColorOverride ?: palette.cardBg
    val cardBorder = borderOverride ?: BorderStroke(2.dp, palette.dialogBorder)
    val titleColor = titleColorOverride ?: palette.textPrimary

    Dialog(
        onDismissRequest = { requestClose(onDismiss) },
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

        // 平台 Window 进出场动画：整个窗口（卡片）统一缩放淡入/淡出。
        // 卡片的进出场**只由它负责**，Compose 侧不再叠一层 alpha/scale（2026-09-18 回退）——
        // 那样会与这条动画抢同一份视觉变换，而这套 xml 的手感（480ms overshoot 进、
        // 240ms 加速出）是既有设计。本轮只借鉴背景模糊的做法，不动弹窗的进出场。
        window?.setWindowAnimations(UiR.style.UfiDialogAnimation)

        /**
         * 这一刻能不能做跨窗口模糊。决定模糊半径写不写、以及 dim 给哪一档。
         *
         * `remember(window)` 就够：弹窗生命周期很短，不值得为"用户中途开省电模式"
         * 去注册 `addCrossWindowBlurEnabledListener`（还得记得注销）。
         */
        val blurCapable = remember(window) { window?.canBlurBehind() == true }
        val maxDim = if (blurCapable) UfiDialogAnim.DimWithBlur else UfiDialogAnim.DimFallback

        // [Debug-only] Diagnostic log: confirms whether the dialog Window was
        // resolved, the view/context class names, and the runtime SDK level.
        // Wrapped in isDebugBuild() (FLAG_DEBUGGABLE) so release builds stay silent.
        LaunchedEffect(Unit) {
            if (isDebugBuild(localView.context)) {
                Log.d(
                    TAG,
                    "windowResolved=${window != null} " +
                        "blurCapable=$blurCapable " +
                        "view=${localView.javaClass.simpleName} " +
                        "ctx=${localView.context.javaClass.simpleName} " +
                        "sdkInt=${Build.VERSION.SDK_INT}"
                )
            }
        }

        // ── backdrop 渐变：出现时逐渐模糊、消失时逐渐清晰（2026-09-17 / 09-18）──
        //
        // 原来是「一次性硬设 blurBehindRadius=110」：弹窗一出现背景就是满模糊，一关就瞬间
        // 清晰。现在由 Animatable 驱动，进场 400ms 糊上来、离场 220ms 化开。
        //
        // 只管背景：卡片的进出场仍由上面那条平台窗口动画负责。
        //
        // 先写一次 (0, 0f) 再起动画：平台可能已按主题给了默认值，不归零会闪一帧满 dim。
        LaunchedEffect(window) {
            applyDialogBackdrop(window, 0, 0f, blurCapable)
            if (reduceMotion) {
                backdrop.snapTo(1f)
            } else {
                backdrop.animateTo(1f, UfiMotion.dialogBackdropIn())
            }
            // 兜底重放：Compose 的 Dialog 内部可能在组合提交后重置窗口属性，把刚写好的
            // 模糊抹掉。渐变过程本身已经写了十几次，这里只在收尾补一次满值 ——
            // applyDialogBackdrop 有相等性守卫，值没被动过的话这次是空操作（不会 relayout）。
            delay(100L)
            if (!closing.value) {
                applyDialogBackdrop(window, UfiDialogAnim.BlurRadius, maxDim, blurCapable)
            }
        }

        // 进度 → 窗口：量化成 0..BackdropSteps 再写，避免逐帧 relayout（见 BackdropSteps）。
        //
        // [backdrop] 本身是**线性**进度（曲线不写在 spec 里），因为同一条进度还要驱动卡片
        // 离场，而两者要"不同曲线、相同端点"。背景用 Standard（FastOutSlowIn，两头软、
        // 中段均匀）—— 之前用 emphasized accelerate，七成变化挤在最后 30%，观感是
        // "先挂着不动然后啪一下全没了"。
        LaunchedEffect(window) {
            snapshotFlow {
                val eased = UfiMotion.Easing.Standard.transform(backdrop.value.coerceIn(0f, 1f))
                (eased * UfiDialogAnim.BackdropSteps).toInt()
            }
                .distinctUntilChanged()
                .collect { step ->
                    val fraction = step / UfiDialogAnim.BackdropSteps.toFloat()
                    applyDialogBackdrop(
                        window = window,
                        // 拿不到模糊时半径写 0（flag 也不会挂），层次全靠 DimFallback
                        blurRadius = if (blurCapable) (UfiDialogAnim.BlurRadius * fraction).toInt() else 0,
                        dim = maxDim * fraction,
                        blurCapable = blurCapable
                    )
                }
        }

        // ── 离场：ramp 到 0 → 执行关闭动作 → 卸载 / 或恢复 ──
        //
        // 顺序不能换：先卸载窗口的话，"逐渐清晰"就没有窗口可写了；而 onDispose 里补写
        // 会给正在销毁的 ViewRootImpl 排 traversal（2026-09-05 崩溃），也不是出路。
        //
        // 三种收尾（2026-09-18）：
        // 1. 动作真的把弹窗关了（硬挂载的调用点最常见）—— 整棵子树当帧移除，本协程随之
        //    取消，后面的代码不会执行，天然正确；
        // 2. 动作把 `visible` 翻成了 false —— 走下面 `!visible` 分支卸载；
        // 3. 动作**没有**关闭弹窗（确认里做了校验决定留下）—— 恢复 backdrop，
        //    否则会留下一个"淡出完了却还在屏幕上"的弹窗。
        LaunchedEffect(closing.value) {
            if (!closing.value) return@LaunchedEffect
            if (reduceMotion) {
                backdrop.snapTo(0f)
            } else {
                backdrop.animateTo(0f, UfiMotion.dialogBackdropOut())
            }
            val action = closeAction.value
            closeAction.value = null
            action?.invoke()
            // 让调用方的状态变更走完一帧，再判断它到底关没关
            withFrameNanos { }
            if (!visibleNow.value) {
                mounted = false
            } else {
                closing.value = false
                if (reduceMotion) {
                    backdrop.snapTo(1f)
                } else {
                    backdrop.animateTo(1f, UfiMotion.dialogBackdropIn())
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
        //
        // 同时把 requestClose 下发给内容子树（见 [LocalUfiDialogCloseRequest]）：内容里的
        // 确认/取消按钮据此走同一条离场时序，而不是当帧把窗口拆掉。
        CompositionLocalProvider(LocalUfiDialogClose provides requestClose) {
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
                ) { requestClose(onDismiss) },
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
                // 卡片：**进场**由平台窗口动画负责（480ms overshoot，见 ufi_dialog_enter.xml）；
                // **离场**由这里驱动（2026-09-18）。
                //
                // 为什么离场不能继续交给平台：那条 xml 只在窗口销毁那一刻播，而那时窗口已经
                // 进入 doDie，再写 blurBehindRadius 会崩 —— 于是"弹窗完全消失时背景刚好最清晰"
                // 就永远对不齐（原来的表现是背景先化开、卡片再单独淡出，两段串着走）。
                // 现在卡片与背景共用同一条进度（240ms），曲线各自施加、端点同时到达：
                // 卡片 alpha/缩放走 Accelerate（等价于原 xml 的 fast_out_linear_in），
                // 背景走 Standard。
                //
                // ⚠️ compositingStrategy 必须是 ModulateAlpha：默认策略在 alpha < 1 时会把
                // 整张卡片画进离屏缓冲，下面那道 ufiCardShadow 会因此换绘制路径而闪一下。
                Box(
                    modifier = Modifier.graphicsLayer {
                        if (!closing.value) return@graphicsLayer
                        // backdrop 是线性进度，离场时 1 → 0；这里换算成 0 → 1 的"退场完成度"
                        val t = 1f - backdrop.value.coerceIn(0f, 1f)
                        val e = UfiMotion.Easing.Accelerate.transform(t)
                        alpha = 1f - e
                        val s = 1f - (1f - UfiDialogAnim.CardExitScaleTo) * e
                        scaleX = s
                        scaleY = s
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
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
                    // 底部内边距 = 左右同值（2026-09-18，原为 Spacing.Medium 8dp）。
                    // 这是弹窗"最后一块内容/按钮 → 下边框"的**唯一**来源：按钮区自己不再
                    // 带 bottom padding，否则会叠成 20dp，看着像"离下边框比离左右远"。
                    .padding(bottom = Spacing.DialogPaddingH)
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
                                        // 关闭按钮 44dp，图标 22dp，按钮自身内边距 11dp。
                                        // 外层 Column 已有 horizontal=DialogPaddingH，
                                        // 所以按钮到右边框视觉距离 = 18+11=29dp，比标题到左边框的 18dp 多一截。
                                        // 加 offset(x=11dp) 把按钮右推，让图标到边框 = 18dp，与标题对称。
                                        // 同理 y=-11dp 上推，让按钮到上边框也是 ~18dp。
                                        Box(modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .offset(x = 11.dp, y = (-7).dp)
                                        ) {
                                            // FIX-8（2026-08-23）：× 关闭按钮 44dp 触控区 / 22dp 图标（Material 标准）。
                                            // 2026-09-15：去掉底色。原来是 pageBg@0.85 的圆片，压在 cardBg 卡面上
                                            // 就是一枚灰按钮，比标题还抢眼；44dp 的触控区不需要靠可见底色来提示。
                                            Surface(
                                                onClick = { requestClose(onDismiss) },
                                                shape = androidx.compose.foundation.shape.CircleShape,
                                                color = Color.Transparent,
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
        } // CompositionLocalProvider（closeRequest）
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
    // 2026-09-18：两个按钮的动作统一经 [LocalUfiDialogClose] 排时序 —— 弹窗离场的
    // backdrop（逐渐清晰）必须在窗口销毁**之前**播完，而按钮一按就把上层状态翻掉、
    // 弹窗当帧卸载。在 shell 之外使用本组件时该 local 是"直接执行"，行为不变。
    val close = LocalUfiDialogClose.current
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
    // 2026-09-19：Row 的 vertical padding 从 12dp 改成只有 top 12dp，不带 bottom ——
    // shell 的 padding(bottom=18dp) 已经是「最后一块内容 → 下边框」的唯一来源，
    // Row 再带 bottom 12dp 就叠成 30dp。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText != null && onDismiss != null) {
            OutlinedButton(
                onClick = { close(onDismiss) },
                border = BorderStroke(1.dp, outlineColor ?: palette.dialogBorder),
                // 2026-09-08：原为 palette.textPrimary。同一个"取消"在两条路径上不同色 ——
                // UfiDialogActions 走 UfiButton(Secondary) 是 accent，这里是 textPrimary，
                // 同一个 App 里两种弹窗的取消按钮观感不一致。统一到 Secondary 的口径。
                colors = ButtonDefaults.outlinedButtonColors(contentColor = outlineTextColor ?: palette.accent),
                shape = UfiCardDefaults.buttonShape,
                enabled = enabled,
                interactionSource = dismissInteraction,
                // 长标签（如「退出并重新配对」）在 weight(1f) 的半行宽度里会折成两行，
                // 而按钮高度是固定的 → 文字被切。给窄内距 + 单行不换行（2026-09-19）。
                contentPadding = DIALOG_BUTTON_CONTENT_PADDING,
                modifier = Modifier
                    .weight(1f)
                    .height(Spacing.DialogButtonHeight)
                    .ufiPressScale(
                        interactionSource = dismissInteraction,
                        pressedScale = UfiMotion.PressScale.Button,
                        spec = UfiMotion.buttonPress()
                    )
            ) {
                Text(
                    dismissText,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Button(
            onClick = { close(onConfirm) },
            colors = ButtonDefaults.buttonColors(
                containerColor = confirmColor,
                contentColor = palette.onAccent
            ),
            shape = UfiCardDefaults.buttonShape,
            enabled = enabled,
            interactionSource = confirmInteraction,
            contentPadding = DIALOG_BUTTON_CONTENT_PADDING,
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
                Text(
                    confirmText,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * 弹窗底部两个按钮的内容内距（2026-09-19）。
 *
 * M3 默认是 24dp 横向，两个按钮各占半行时留给文字的宽度只剩不到一半 ——
 * 「退出并重新配对」这种 7 字标签会折行，而按钮高度固定，折出来的第二行直接被切掉。
 * 收窄到 8dp，配合 `maxLines = 1, softWrap = false`，长标签才排得下。
 */
private val DIALOG_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
