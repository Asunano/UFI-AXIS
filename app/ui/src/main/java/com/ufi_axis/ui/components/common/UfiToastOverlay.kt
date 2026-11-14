// [F24] STABLE-UI-API：公共组件签名已冻结。UfiToastOverlay 为平台层单例，
// 平台层 Toast：卡片视觉对齐 class-schedule 的 AppToastCard（2026-11-10），
// 彻底脱离 Compose 视图树 —— 因此不会随页面滚动 / NavHost 转场而跟随移动或错位。
package com.ufi_axis.ui.components.common

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import kotlin.math.roundToInt
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 平台层 Toast 配色（已解析为 argb Int，从 Compose 侧 LocalResolvedPalette 转换传入）。
 */
data class UfiToastColors(
    val cardBg: Int,
    val textPrimary: Int,
    /** 次级文字色：INFO 的线性图标用它，和 class-schedule 的 ToastIcon 一致。 */
    val textSecondary: Int,
    val accent: Int,
    val success: Int,
    val error: Int,
    val warning: Int,
    /** 兼容旧调用方保留；当前 class-schedule 样式不再使用 warningContainer。 */
    val warningContainer: Int,
    /** 兼容旧调用方保留；Info 描边改用 class-schedule 的黑 16% / 白 31%。 */
    val toastBorder: Int,
    val isDark: Boolean
)

/**
 * Toast 平台层单例 —— 水滴下落 + 顶部卡片。
 *
 * 与 Compose Popup 不同，本实现把卡片直接挂到 [Activity.window.decorView]，
 * 完全脱离 Compose 视图树，因此：
 *  - 不会随页面滚动 / AnimatedContent 转场而跟随移动或位置异常；
 *  - 不拦截点击（独立 FrameLayout，仅卡片区域消费事件，且默认不抢焦点）；
 *  - 调用方（UfiToastHost 外壳）无需关心 Activity / 生命周期，传入 Activity 即可。
 *
 * 视觉完全对齐 class-schedule 的 `AppToastCard`：
 *  14dp 圆角、1dp 描边、22dp 线性图标（无圆形底色）、
 *  非 Info 用 10% 语气色底 + 55% 语气色描边，Info 用中性卡底 + 黑 16% / 白 31% 描边；
 *  主文 15sp medium、副文 13sp 0.55 alpha；最多 6 行，整卡点击收起。
 */
object UfiToastOverlay {

    @Volatile private var activeToast: View? = null
    @Volatile private var pendingRemoveRunnable: Runnable? = null

    /**
     * 当前 toast 的 `onDismiss`（与 [activeToast] **同源写入、同源清空**）。
     *
     * ## 为什么必须存下来
     * `UfiToastHost` 是 `LaunchedEffect(toastMessage)`，调用方的契约是「`onDismiss` 里把
     * state 置 null，下次才能再弹」。而 [ToastMessage] 是 data class ⇒ **同一条** toast
     * 再次触发时 key 相等、effect 不会重启。于是任何一条「不回调 onDismiss 就结束」的
     * 收尾路径都会让调用方的 state 永久停在非 null，那条 toast **再也不显示**。
     *
     * 只有 [exitAndRemove] 能拿到 `show` 传进来的那个 lambda（闭包捕获），而
     * [releaseFromDyingHost] / [dismissActive] 拿不到 —— 所以把它和 [activeToast] 一起
     * 记在单例上，由 [consumeDismissCallback] 保证「恰好回调一次」。
     */
    @Volatile private var activeToastOnDismiss: (() -> Unit)? = null

    /**
     * 宿主销毁看门狗：挂在 toast 自己身上的 attach 监听，detach 时走 [releaseFromDyingHost]。
     *
     * ## 为什么需要（loading toast 泄漏 Activity）
     * `isLoading = true` 的 toast **不注册** `postDelayed` 自动移除（见 [show] 末尾），
     * 清引用只发生在退场动画结束 / [dismissActive] / [popDialogHost]（仅弹窗窗口）三条路径。
     * 若它挂在 `Activity.window.decorView` 上而 Activity 被销毁（旋转 / finish），
     * 上面三条一条都不走 ⇒ 单例长期持有该 View 及其 Activity context。
     *
     * 监听器由 [clearHostDetachWatcher] 在**所有正常移除路径**里先行注销，
     * 否则 `removeView` 触发的 detach 会再触发一次收尾（onDismiss 被回调两次）。
     */
    @Volatile private var hostDetachWatcher: View.OnAttachStateChangeListener? = null

    /** 懒初始化：本对象可能在 JVM 单测里被源码级断言以外的方式触碰，避免类初始化就依赖 Looper。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 当前 toast **实际挂载在哪个弹窗 Window** 上（挂在 Activity.decorView 时为 null）。
     *
     * ## 为什么要单独记一份，而不是每次去比 `activeToast.parent === window.decorView`
     * [popDialogHost] 是在宿主窗口**正在销毁**时被调用的（Compose 的 Dialog 组合被
     * dispose ⇒ `Dialog.dismiss()` ⇒ decor 正在 detach）。那一刻**任何**对将死窗口的
     * 触碰都是风险：`Window.getDecorView()` 在 `PhoneWindow` 上带 `installDecor()` 的
     * 副作用，而 detach 流程中途重建 decor 是未定义行为。
     *
     * 记住挂载时的 Window 引用后，[popDialogHost] 只需做一次**引用相等比较**，
     * 全程不调用窗口 / 视图上的任何方法 —— 这是「dispose 期间不得改动 decor 子 View」
     * 这条不变量的第一道保障（见 [releaseFromDyingHost]）。
     */
    @Volatile private var activeToastHost: Window? = null

    /**
     * 当前打开的弹窗 Window 栈（后进后出 = 栈顶是最上层弹窗）。
     *
     * ## 为什么需要它（2026-09-04）
     * Toast 挂在 `Activity.window.decorView`，而 `UfiDialogShell` 用的是
     * [androidx.compose.ui.window.Dialog] —— **独立 Window，z 序天然在 Activity 窗口之上**。
     * 于是"弹窗里操作后弹 toast"（输入校验失败、保存成功…）必然被弹窗盖住，
     * 而这恰好是 toast 最该出现的场合。这不是组合树层级问题：`UfiToastHost` 只有一个
     * `LaunchedEffect`、不产出任何 Compose 节点，挂在哪一层都不影响绘制。
     *
     * 解法：弹窗出现时把自己的 window 压栈，toast 优先挂到**栈顶弹窗**的 decorView ——
     * 这样它就渲染在弹窗窗口内部（自然在弹窗内容之上，且不会被 `FLAG_BLUR_BEHIND` 模糊，
     * 因为那个 flag 只模糊本窗口**背后**的内容）。没有弹窗时行为与之前完全一致。
     */
    private val dialogHostWindows = ArrayList<Window>(2)

    /**
     * 弹窗出现时登记自己的 window（由 `UfiDialogShell` 的 `DisposableEffect` 调用）。
     * 重复登记同一个 window 只会把它提到栈顶，不会重复入栈。
     */
    fun pushDialogHost(window: Window) {
        synchronized(dialogHostWindows) {
            dialogHostWindows.remove(window)
            dialogHostWindows.add(window)
        }
    }

    /**
     * 弹窗离场时注销。
     *
     * ## ★★ 红线：本函数**绝不允许**改动宿主 decor 的子 View 集合（2026-09-05 崩溃修复）★★
     *
     * ### 崩掉的那一版是什么样
     * ```
     * if (hosted) dismissActive()   // → (toast.parent as ViewGroup).removeView(toast)
     * ```
     *
     * ### 为什么必崩（真机栈已还原，见下）
     * 本函数的唯一调用点是 `UfiDialogShell` 的 `DisposableEffect(window).onDispose`，
     * 它跑在**弹窗窗口正在销毁**的调用栈里面：
     *
     * ```
     * Choreographer → AndroidUiDispatcher$dispatchCallback.doFrame
     *   → Recomposer.runRecomposeAndApplyChanges
     *   → CompositionImpl.applyChanges → applyChangesInLocked
     *   → RememberEventDispatcher.dispatchRememberObservers
     *   → DisposableEffectImpl.onForgotten
     *   → AndroidDialog_androidKt$Dialog$1$1$…$onDispose$1.dispose()   // Compose 的 Dialog
     *   → Dialog.dismiss() → dismissDialog() → WindowManagerImpl.removeViewImmediate()
     *   → ViewRootImpl.die/doDie → ViewRootImpl.dispatchDetachedFromWindow()
     *   → DecorView(ViewGroup).dispatchDetachedFromWindow()            // ← 开始遍历子 View
     *       final int count = mChildrenCount;      // 快照：2
     *       final View[] children = mChildren;     // 快照：**同一个数组对象**
     *       for (int i = 0; i < count; i++) children[i].dispatchDetachedFromWindow();
     *         ├─ i=0：contentRoot → … → Compose 的 DialogLayout.onDetachedFromWindow()
     *         │        → disposeComposition() → 本弹窗子组合的所有 onDispose
     *         │        → UfiDialogShell 的 onDispose → popDialogHost(window)
     *         │        → dismissActive() → decorView.removeView(toast)
     *         │           ⇒ ViewGroup.removeViewInternal：arraycopy 前移 +
     *         │             children[--mChildrenCount] = null  ⇒ **数组末尾出现 null 空洞**
     *         └─ i=1：children[1] == null ⇒ NullPointerException
     *                 'void android.view.View.dispatchDetachedFromWindow()' on a null object
     * ```
     *
     * 关键点：`count` / `children` 是**循环开始前的快照**，而 `removeView` 改的是**同一个
     * 数组**。toast 被 [show] 直接 `addView` 到 `window.decorView`，因此它是 contentRoot 的
     * **后继兄弟**（index ≥ 1）——正好落在「已被 count 计入、但还没轮到」的区间里。
     *
     * 崩溃栈里看不到任何 App 帧，是因为空洞是在 i=0 那一支**返回之后**才被 i=1 读到的，
     * 这正是「有人在窗口销毁流程中改了 decor 的子 View」的典型指纹。
     *
     * ### 现在怎么做
     * 宿主窗口马上就要连同它的整棵 decor 树一起消失，**toast 根本不需要被摘下来** ——
     * 它会随窗口一起被销毁。这里真正必须做的只有「别留下悬挂状态」：撤掉延时移除回调、
     * 停掉属性动画、清空单例里的引用（见 [releaseFromDyingHost]）。
     * 全程不碰视图树、不碰将死的 [Window]，因此不可能再制造空洞。
     */
    fun popDialogHost(window: Window) {
        // ★ 只做引用相等比较：不调用 window.decorView（PhoneWindow 的 getter 带
        //   installDecor() 副作用），不读 toast.parent —— 将死窗口一律零触碰。
        val hosted = synchronized(dialogHostWindows) {
            dialogHostWindows.remove(window)
            activeToastHost === window
        }
        if (hosted) releaseFromDyingHost()
    }

    /**
     * 宿主窗口正在销毁时的**只清引用**收尾 —— 不做任何视图树改动。
     *
     * 与 [dismissActive] 的分工：
     * - [dismissActive]：窗口活着，toast 要被"收掉"，必须 `removeView` 才会从屏幕上消失；
     * - 本函数：窗口已在 detach 流程中，`removeView` 会破坏平台正在遍历的 children 数组
     *   （机制见 [popDialogHost]），而 toast 反正会随窗口一起消失，摘它毫无收益。
     *
     * 因此这里只做三件不触碰视图层级的事：
     * 1. 撤掉 `postDelayed` 的自动移除回调（`removeCallbacks` 只动 View 自己的消息队列）；
     * 2. 取消属性动画（否则动画结束回调会在窗口消失后回调到一棵孤儿树上）；
     * 3. 清空 [activeToast] / [activeToastHost] / [pendingRemoveRunnable]，
     *    避免单例继续持有一个已随窗口死亡的 View（内存泄漏 + 下次 [dismissActive] 空操作）。
     *
     * 之后**必须**把 [activeToastOnDismiss] 回调一次：这条路径上 [exitAndRemove] 的结束回调
     * 不会执行（动画已 cancel、Runnable 已撤），没人替调用方把 state 置 null，
     * 而 `UfiToastHost` 的 `LaunchedEffect(toastMessage)` 会因此永久停在非 null ⇒
     * 同一条 toast 之后再触发时 key 相等、effect 不重启，那条 toast 再也不显示。
     * 回调**只改 Compose state、不触碰视图树**，因此不违反「将死窗口零触碰」；
     * 且经 [mainHandler] 抛回主线程执行，既不在 detach 遍历栈里写 state，也不假设调用线程。
     */
    private fun releaseFromDyingHost() {
        val toast = activeToast
        if (toast != null) {
            pendingRemoveRunnable?.let { toast.removeCallbacks(it) }
            toast.animate().cancel()
        }
        // 只摘监听器（改的是 View 自己的 listener 列表，不是 decor 的 children 数组）
        clearHostDetachWatcher(toast)
        pendingRemoveRunnable = null
        activeToast = null
        activeToastHost = null
        // 幂等：consume 后引用已空，重入（popDialogHost 之后 View 自己再 detach）不会二次回调
        postDismiss(consumeDismissCallback())
    }

    /**
     * 取出并清空 [activeToastOnDismiss] —— 「onDismiss 恰好回调一次」的唯一闸门。
     *
     * 所有收尾路径（[exitAndRemove] / [dismissActive] / [releaseFromDyingHost]）都必须
     * 经它取值，重入时拿到 null ⇒ 天然幂等。
     */
    private fun consumeDismissCallback(): (() -> Unit)? {
        val callback = activeToastOnDismiss
        activeToastOnDismiss = null
        return callback
    }

    /**
     * 把 `onDismiss` 抛回主线程执行。
     *
     * 回调只会改调用方的 Compose state（契约见 [UfiToastHost]），绝不触碰视图树；
     * post 一次的意义是：① 不在「窗口 detach 遍历 / 组合 dispose」的栈里写 snapshot state；
     * ② [dismissActive] 是 public API，无法假设调用线程一定是主线程。
     */
    private fun postDismiss(callback: (() -> Unit)?) {
        if (callback == null) return
        mainHandler.post(callback)
    }

    /**
     * 注销宿主销毁看门狗。
     *
     * **正常移除路径必须先调它再 `removeView`**：否则 detach 会回调
     * [releaseFromDyingHost]，与该路径自己的收尾叠加成两次 onDismiss。
     * 传 null 只清单例引用（View 已不可触碰时用）。
     */
    private fun clearHostDetachWatcher(toast: View?) {
        val watcher = hostDetachWatcher ?: return
        hostDetachWatcher = null
        toast?.removeOnAttachStateChangeListener(watcher)
    }

    /** 宿主容器 + 它所属的弹窗窗口（挂到 Activity 时窗口为 null）。 */
    private class ToastHost(val container: ViewGroup, val window: Window?)

    /**
     * 解析 toast 的挂载宿主：优先栈顶弹窗的 decorView，没有弹窗时回落到 Activity 的 decorView。
     *
     * 容器与「它属于哪个 Window」必须在**同一次决策**里一起返回：两者若各算一次，
     * 中间插入的 `pushDialogHost` / `popDialogHost` 会让 [activeToastHost] 与实际挂载容器
     * 不一致，[popDialogHost] 的相等判据就失真了（要么漏清理、要么误清理）。
     */
    private fun resolveHost(context: Context?): ToastHost? {
        // 2026-11-10：只认「还活着」的弹窗。弹窗关闭后 Window 仍可能在栈里停留到
        // Compose dispose 完成；此时把 toast add 到它的 decor，动画照跑但窗口不可见，
        // 表现就是"toast 永远不出现"（安装/卸载确认后点确定最容易复现）。
        synchronized(dialogHostWindows) {
            val iterator = dialogHostWindows.listIterator(dialogHostWindows.size)
            while (iterator.hasPrevious()) {
                val window = iterator.previous()
                val decor = window.peekDecorView()
                if (decor == null || !decor.isAttachedToWindow || !decor.isShown ||
                    decor.windowVisibility != View.VISIBLE || !decor.hasWindowFocus()
                ) {
                    iterator.remove()
                    continue
                }
                (decor as? ViewGroup)?.let { return ToastHost(it, window) }
            }
        }
        val activity = findActivity(context) ?: return null
        val decor = activity.window.decorView as? ViewGroup ?: return null
        return ToastHost(decor, null)
    }

    // ── 常量（与 class-schedule AppToastCard 对齐）──
    /** class-schedule 同款：状态栏下 28dp；弹窗窗口自己已让开系统栏，用 0 inset。 */
    private const val TOP_GAP_DP = 28
    private const val MAX_WIDTH_RATIO = 0.86f
    /** class-schedule 同款下落 600ms。 */
    private const val DROP_DURATION = 600L
    /** 退场 250ms：刻意不在 UfiMotion.Duration 的档位上（Standard 220 与 Gentle 280 之间），本文件是它的唯一来源。 */
    private const val EXIT_DURATION = 250L
    private const val RIPPLE_PUSH_DP = 5f
    private const val RIPPLE_REBOUND_DP = 6f
    private const val EXIT_LIFT_DP = 8f

    /** 沿 ContextWrapper 链向上查找真正的 Activity（兼容 ContextThemeWrapper / Compose Context 包裹）。 */
    private fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx != null) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * 显示 Toast。
     * @param context    当前 Context（内部沿 ContextWrapper 链查找 Activity，兼容 Screen 内被 ContextThemeWrapper 包裹的情况）
     * @param type       语义类型（决定图标符号 + 主色）
     * @param title      主标题（必填）
     * @param subtitle   副标题（可选）
     * @param isLoading  加载中（持久，需外部替换/关闭）
     * @param durationMs 停留时长（ms）；isLoading=true 时忽略
     * @param colors     已解析配色
     * @param onDismiss  退出动画结束时回调（用于清空调用方 state）
     */
    fun show(
        context: Context,
        type: ToastType,
        title: String,
        subtitle: String? = null,
        isLoading: Boolean = false,
        durationMs: Long = 3000L,
        colors: UfiToastColors,
        onDismiss: (() -> Unit)? = null
    ) {
        // 沿 ContextWrapper 链向上找到真正的 Activity（Screen 内 LocalContext 可能是 ContextThemeWrapper，
        // 直接 as? Activity 会失败 → 静默不显示，这是 FIX-14 后续「根本不显示」的根因）。
        // activity 仍要用于取屏宽/资源等（见下方 MAX_WIDTH_RATIO 计算），
        // 但**挂载容器**改由 resolveHost 决定：有弹窗时挂到弹窗 window，否则挂 Activity。
        val activity = findActivity(context) ?: return
        val host = resolveHost(context) ?: return
        val decorView = host.container
        // 替换上一条 toast：它的 onDismiss 在这里被消费掉（post 到主线程，落在本次 show 之后），
        // 这样"每条显示出来的 toast 恰好回调一次 onDismiss"在所有路径上成立 ——
        // 漏掉它就等于把调用方的 state 永久钉在非 null（机制见 activeToastOnDismiss 的 KDoc）。
        dismissActive()

        val density = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * MAX_WIDTH_RATIO).toInt()

        // ── class-schedule 同款配色：非 Info 用 10% 语气色底 + 55% 语气色描边 ──
        val typeColor = when (type) {
            ToastType.SUCCESS -> colors.success
            ToastType.ERROR -> colors.error
            ToastType.WARNING -> colors.warning
            ToastType.INFO -> colors.textSecondary
        }
        val tinted = type != ToastType.INFO
        val bgColor = if (tinted) blend(colors.cardBg, typeColor, 0.10f) else colors.cardBg
        val borderColor = if (tinted) withAlpha(typeColor, 0.55f) else neutralBorder(colors.isDark)
        val titleColor = if (tinted) typeColor else colors.textPrimary

        val toastView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                cornerRadius = 14f * density
                setStroke(dp(1), borderColor)
            }
            elevation = dp(10).toFloat()

            // 线性图标（不是旧版的圆形色块 + 白符号），与 class-schedule 的 ToastIcon 一致。
            addView(ToastIconView(activity, type, typeColor).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }

                addView(TextView(activity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(titleColor)
                    includeFontPadding = false
                    maxLines = 6
                })

                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(activity).apply {
                        text = subtitle
                        textSize = 13f
                        setTextColor(colors.textPrimary)
                        alpha = 0.55f
                        includeFontPadding = false
                        maxLines = 6
                        setLineSpacing(0f, 1.3f)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                    })
                }
            })
        }

        // 测量并限定宽度
        toastView.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val toastWidth = minOf(toastView.measuredWidth, maxWidth)
        val toastHeight = toastView.measuredHeight

        // 添加到 DecorView（顶层 FrameLayout，Gravity.TOP|CENTER_HORIZONTAL 固定屏幕顶部居中，
        // 彻底脱离 Compose 视图树，不随页面滚动 / 转场移动）
        // class-schedule：状态栏下 28dp；若挂在弹窗窗口里，窗口已让开系统栏，inset=0。
        val topInset = if (host.window != null) 0 else statusBarHeightPx(activity)
        val topMargin = topInset + dp(TOP_GAP_DP)
        val lp = android.widget.FrameLayout.LayoutParams(
            toastWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.topMargin = topMargin
        }
        decorView.addView(toastView, lp)
        // 读屏用户：Toast 是挂在 decorView 上的独立浮层，不会自动获得焦点，
        // 不显式播报的话「密码错误 / 配对失败」对 TalkBack 完全静默。
        @Suppress("DEPRECATION")
        toastView.announceForAccessibility(
            if (subtitle.isNullOrBlank()) title else "$title，$subtitle"
        )

        activeToast = toastView
        // ★ 与容器同源记下宿主窗口：popDialogHost 只靠这个引用判断"是不是挂在将死的窗口上"，
        //   从而无需在销毁流程里去读 window.decorView / toast.parent（见 popDialogHost 红线）。
        activeToastHost = host.window
        // 与 activeToast 同源存回调：releaseFromDyingHost / dismissActive 拿不到 show 的闭包，
        // 不存下来那两条路径就会「不回调 onDismiss 就结束」（后果见 activeToastOnDismiss 的 KDoc）。
        activeToastOnDismiss = onDismiss
        // 宿主销毁看门狗：loading toast 没有 postDelayed 自动移除，挂在 Activity.decorView 上时
        // Activity 被销毁（旋转 / finish）不会走任何现有清理路径 ⇒ 单例长期持有 View + Activity。
        // detach 时只清引用（releaseFromDyingHost 不碰视图树），正常移除路径已先注销本监听器。
        val detachWatcher = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // 幂等守卫：只有"当前活跃 toast"才触发收尾，避免与 popDialogHost 重复清理
                if (activeToast === v) releaseFromDyingHost()
            }
        }
        hostDetachWatcher = detachWatcher
        toastView.addOnAttachStateChangeListener(detachWatcher)
        toastView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 初始隐藏在屏幕上方；layout 已带 topMargin，所以最终 translationY=0。
        toastView.translationY = -toastHeight - dp(40).toFloat()
        toastView.alpha = 0f

        // 下落入场（class-schedule 同款 600ms + Overshoot 1.25）
        toastView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(DROP_DURATION)
            .setInterpolator(OvershootInterpolator(1.25f))
            .withEndAction { startRipplePulse(toastView) }
            .start()

        // 加载中持久；其余按 duration 自动消失
        if (!isLoading) {
            pendingRemoveRunnable = Runnable {
                exitAndRemove(toastView, decorView, onDismiss)
            }
            toastView.postDelayed(pendingRemoveRunnable, durationMs)
        }

        // 入场动画期间也允许点掉；放在 postDelayed 之后，点击时能稳妥取消自动移除。
        toastView.setOnClickListener {
            pendingRemoveRunnable?.let { runnable -> toastView.removeCallbacks(runnable) }
            pendingRemoveRunnable = null
            exitAndRemove(toastView, decorView, onDismiss)
        }
    }

    /**
     * 立即移除当前活跃 Toast（被下一次 [show] 自动调用，或外部主动关闭）。
     *
     * ⚠ 本函数会**改动宿主容器的子 View 集合**（`removeView`），因此**只能在宿主窗口存活时
     * 调用**。窗口正在销毁的路径必须走 [releaseFromDyingHost]，原因见 [popDialogHost] 的红线：
     * 在 `ViewGroup.dispatchDetachedFromWindow` 遍历期间 `removeView` 会在平台已快照的
     * children 数组里留下 null 空洞，下一次循环迭代必然 NPE。
     *
     * ## 为什么这里也要回调 onDismiss
     * 本函数**绕过** [exitAndRemove]（直接 cancel 动画 + `removeView`），因此那条路径的
     * `withEndAction` 不会执行，没人替调用方把 state 置 null。它有两类调用者：
     * - [show] 的「替换上一条 toast」：由 show 决定要不要回调（见那里的注释）；
     * - 外部主动关闭（例如收掉一条 loading toast）：不回调就是永久卡住 state，
     *   同一条 toast 再也弹不出来（机制见 [activeToastOnDismiss]）。
     * 统一用 [consumeDismissCallback] 取值 ⇒ 至多一次，绝不会与其他路径叠加。
     */
    fun dismissActive() {
        pendingRemoveRunnable?.let { runnable ->
            activeToast?.removeCallbacks(runnable)
        }
        pendingRemoveRunnable = null
        activeToast?.let { toast ->
            toast.animate().cancel()
            // 先注销看门狗再摘 View：否则 removeView 触发的 detach 会再走一次收尾
            clearHostDetachWatcher(toast)
            (toast.parent as? ViewGroup)?.removeView(toast)
            activeToast = null
            activeToastHost = null
        }
        postDismiss(consumeDismissCallback())
    }

    /** 涟漪脉冲：水滴落地后单次水波（下降冲击 → 回弹 → 稳定） */
    private fun startRipplePulse(toast: View) {
        if (toast.parent == null) return
        val density = toast.resources.displayMetrics.density
        val pushPx = RIPPLE_PUSH_DP * density
        val reboundPx = RIPPLE_REBOUND_DP * density

        toast.animate()
            .translationYBy(pushPx)
            .setDuration(UfiMotion.Duration.Quick.toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (toast.parent == null) return@withEndAction
                toast.animate()
                    .translationYBy(-(pushPx + reboundPx))
                    .setDuration(EXIT_DURATION)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .withEndAction {
                        if (toast.parent == null) return@withEndAction
                        toast.animate()
                            .translationYBy(reboundPx * 0.4f)
                            .setDuration(UfiMotion.Duration.Quick.toLong())
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun exitAndRemove(toast: View, parent: ViewGroup, onDismiss: (() -> Unit)?) {
        if (toast.parent == null) {
            // 已经不在树上：先把单例里的回调槽消费掉（避免看门狗 / releaseFromDyingHost 再回调一次），
            // 再用闭包里那份直接回调 —— 这条分支不在动画结束回调里，本身就在主线程。
            if (activeToast === toast) consumeDismissCallback()
            onDismiss?.invoke()
            return
        }
        toast.animate().cancel()
        toast.animate()
            .alpha(0f)
            .translationYBy(-EXIT_LIFT_DP * toast.resources.displayMetrics.density)
            .setDuration(EXIT_DURATION)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                // 先注销看门狗：removeView 会触发 detach，否则 releaseFromDyingHost 会再收尾一次
                clearHostDetachWatcher(toast)
                if (toast.parent != null) parent.removeView(toast)
                if (activeToast == toast) {
                    activeToast = null
                    activeToastHost = null
                }
                // 单例那份由本路径消费掉；实际回调用闭包里的同一个 lambda（保持既有行为）
                consumeDismissCallback()
                onDismiss?.invoke()
            }
            .start()
    }

    // ── class-schedule 同款样式工具 ──

    /** 将 [overlay] 以 [ratio] 混到 [base] 上（alpha 强制不透明，等价 Compose 的 lerp）。 */
    private fun blend(base: Int, overlay: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun channel(b: Int, o: Int): Int = (b * (1f - r) + o * r).roundToInt().coerceIn(0, 255)
        return Color.argb(
            255,
            channel(Color.red(base), Color.red(overlay)),
            channel(Color.green(base), Color.green(overlay)),
            channel(Color.blue(base), Color.blue(overlay)),
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    /** 中性提示描边：class-schedule 深色白 31% / 浅色黑 16%。 */
    private fun neutralBorder(isDark: Boolean): Int =
        if (isDark) Color.argb(0x4F, 255, 255, 255) else Color.argb(0x29, 0, 0, 0)

    private fun statusBarHeightPx(activity: Activity): Int {
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            activity.resources.getDimensionPixelSize(resId)
        } else {
            (24f * activity.resources.displayMetrics.density).roundToInt()
        }
    }

    /**
     * class-schedule `ToastIcon` 的原生复刻：22dp 线性图标。
     * success = 对勾；info = 圆圈 + i；warning/error = 圆圈 + 感叹号。
     */
    private class ToastIconView(
        context: Context,
        private val type: ToastType,
        private val tint: Int,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = tint
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val stroke = 1.8f * density
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f - stroke / 2f
            paint.color = tint

            when (type) {
                ToastType.SUCCESS -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke * 1.4f
                    val path = Path().apply {
                        moveTo(w * 0.18f, h * 0.52f)
                        lineTo(w * 0.42f, h * 0.76f)
                        lineTo(w * 0.84f, h * 0.26f)
                    }
                    canvas.drawPath(path, paint)
                }

                ToastType.INFO -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, h * 0.32f, stroke * 0.9f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke * 1.4f
                    canvas.drawLine(cx, h * 0.46f, cx, h * 0.72f, paint)
                }

                // 2026-11-14：WARNING 与 ERROR 原先共用「圆 + 叹号」，只剩颜色区分 ——
                // 色弱用户与黑白截图里完全分不出来。现在四种形态各不相同：
                // 对勾 / 圆 i / 三角 ! / 圆 ×。
                ToastType.WARNING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    val triangle = Path().apply {
                        moveTo(cx, h * 0.15f)
                        lineTo(w * 0.92f, h * 0.84f)
                        lineTo(w * 0.08f, h * 0.84f)
                        close()
                    }
                    canvas.drawPath(triangle, paint)
                    paint.strokeWidth = stroke * 1.4f
                    canvas.drawLine(cx, h * 0.45f, cx, h * 0.63f, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, h * 0.74f, stroke * 0.9f, paint)
                }

                ToastType.ERROR -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.strokeWidth = stroke * 1.4f
                    canvas.drawLine(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.66f, paint)
                    canvas.drawLine(w * 0.66f, h * 0.34f, w * 0.34f, h * 0.66f, paint)
                }
            }
        }
    }

}
