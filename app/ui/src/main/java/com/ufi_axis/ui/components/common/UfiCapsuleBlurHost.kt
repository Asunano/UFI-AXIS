package com.ufi_axis.ui.components.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import java.util.function.Consumer

/*
 * ★ 已删除（2026-09-24，贴底通栏低栏迁移阶段 2.1）：`CAPSULE_SHADOW_ROOM`（恒 0.dp）★
 *
 * ## 它原来是什么
 * 「胶囊四周预留给投影的空间」的单一真源。悬浮形态下承载胶囊的 Dialog 窗口是
 * wrap-content，`shadow(10.dp)` 画到窗口矩形之外的部分会被裁掉，所以窗口四周要留一圈
 * 余量；后来投影收窄，它被压到 0.dp，只剩两个职责：
 * - 当 `bottomOffsetPx` 表达式里一个恒 0 的减项，让护栏 `capsuleLift_mustBeWired`
 *   能在源码文本里找到这个 token；
 * - 给 `MainNavGraph` 的 `Modifier.padding(CAPSULE_SHADOW_ROOM)` 提供同一个数。
 *
 * ## 通栏形态下为什么不再需要
 * 通栏低栏**没有四周投影**：窗口是 MATCH_PARENT 宽 + `gravity=BOTTOM` + `y=0`，
 * 栏的视觉分界只由「顶边 1px 发丝线 + 上缘 6dp 高光」承担（都画在自己边界之内，
 * 见 `UfiBottomDock`）。既没有溢出窗口的阴影要养，`bottomOffsetPx` 整条表达式也已退休，
 * 两个消费者同时消失 —— 留着一个恒 0 的 Dp 常量只会误导后人以为「通栏还有投影余量」。
 *
 * 删除前已全仓 grep：消费者只有本文件的 `bottomOffsetPx` / `insetPx` 与
 * `MainNavGraph` 的那一处 padding，两者在同一批改动里一并拆除。
 *
 * 详见 `docs/bottom-dock-migration-plan.md` §2.1（窗口层参数对照表）与 §2.5。
 */

/**
 * 手势导航下**底部系统预留区**的保底高度（**单一真源**）。
 *
 * ## 为什么需要一个下限
 * 「底部预留区」本应由 `navigationBars` / `systemGestures` 两路 inset 读出来，但真机上
 * 这两路都可能骗人：
 * - 相当一部分国产 ROM 在**手势导航**模式下把 `navigationBars().bottom` 报成一个远小于
 *   手势条实际占位的值，甚至直接报 0（它们认为「手势模式没有导航栏」）；
 * - 视图尚未 attach、或宿主 Activity 解析不到时，`getRootWindowInsets()` 返回 null，
 *   一点读数都拿不到。
 *
 * 旧实现在这些分支上一律 `return 0`，胶囊于是**贴死屏幕底边 / 压在手势条上**——正是
 * 「跨设备定位不一致」的根因之一（R1）。本常量把这类场景兜到 24dp（AOSP 手势条区域的
 * 标称高度），保证胶囊**永远**在手势热区之上。
 *
 * 见 [readBottomReservedPx]：所有「读不到」的分支都回落到它，**绝不回 0**。
 */
internal val GESTURE_BOTTOM_INSET_FLOOR: Dp = 24.dp

/*
 * ★ 已删除（2026-09-24，贴底通栏低栏迁移阶段 2.1）★
 * `CAPSULE_GAP_GESTURE` / `CAPSULE_GAP_BUTTON` / `capsuleGapFor()` / `CAPSULE_BOTTOM_MARGIN`
 * （历史别名）/ 局部常量 `CAPSULE_LIFT`。
 *
 * ## 这一组原来回答两个正交的问题
 * - **安全**（`CAPSULE_GAP_*`，8dp，经 `capsuleGapFor(isGestureNav)` 按导航模式分发）：
 *   「胶囊**底边**至少要离系统预留区顶边多远，才不会被手势热区吃掉点击」。
 *   悬浮胶囊是一块浮在页面之上、四边都有邻接内容的浮层，底边落进手势热区就会
 *   「点不动 / 一点就回桌面」，8dp 是真机验证过的下限（旧注释称之为红线 R3）。
 * - **审美**（`CAPSULE_LIFT`，由 `ThemeManager.capsuleLiftDp` 驱动，默认 30dp）：
 *   「产品希望胶囊看起来离屏幕底边再远一点」。它是**追加项**而非替换项，
 *   刻意与安全间距相加、而不是把 8dp 调大，好让那条已验证的不变量单独可回归。
 *
 * 三者（外加恒 0 的减项 `CAPSULE_SHADOW_ROOM`）合成窗口 `y` 偏移 `bottomOffsetPx`，
 * 由 `applyCapsuleWindowParams` 写进 `LayoutParams.y`，把整块胶囊**抬离**屏幕底边。
 *
 * ## 通栏形态下为什么整组都不再需要
 * 贴底通栏低栏的窗口是 MATCH_PARENT 宽 + `gravity=BOTTOM` + **`y = 0`**：栏**主动铺满**
 * 屏幕底部那条带子（含手势热区），底色一路画到屏幕真实底边，图标不压小白条靠的是
 * **栏内部的一段 Spacer**（见 `UfiBottomDock` 与 [LocalCapsuleBottomReserved]）。
 * 于是：
 * - 「胶囊底边离热区多远」这个问题**本身消失**了 —— 栏不再有一条悬在热区上方的底边，
 *   热区那一段是栏自己的一部分，里面刻意不放任何可点内容；
 * - 「整体抬高」与贴底形态直接矛盾：只要 y ≠ 0，栏与屏幕底边之间就会露出一条页面内容，
 *   通栏就不通了。
 *
 * ⚠ 安全区的**取值口径**一个字都没有变：仍是 [readBottomReservedPx]
 * （`max(navigationBars, systemGestures)`、手势导航兜底 [GESTURE_BOTTOM_INSET_FLOOR]、
 * 一律回读宿主 Activity 的全屏 `rootWindowInsets`）。变的只是**消费方**：
 * 从「窗口 y 偏移」挪到「栏内 Spacer + 页面 inset」。
 *
 * ⚠ `ThemeManager.capsuleLiftDp`（设置 → 外观 → 胶囊抬高）这个持久化设置项本身仍在，
 *   通栏形态下已无几何消费者。它的清理属于阶段 3（旧实现下线）的范围，本批不动。
 *
 * 删除前已全仓 grep：`capsuleGapFor` 只在本文件的 `bottomOffsetPx` 里被调用；
 * `CAPSULE_GAP_BUTTON` 另有一处消费者是 `capsuleBottomTotalDp`（页面 inset 的 8dp 间隙），
 * 已在同一批的 2.3 里改写为「58dp + 安全区」；`CAPSULE_BOTTOM_MARGIN` 只剩
 * `MainNavGraph` 的一行 import（无实际引用），已一并摘掉。
 *
 * 详见 `docs/bottom-dock-migration-plan.md` §2.1 / §3 / §7-2.1。
 */

/**
 * 窗口背景 drawable 的圆角半径。
 *
 * ## ⚠ 当前只服务于**已 dead 的真模糊分支**
 * 窗口级真模糊已被彻底移除（见 [applyWindowBlur]）：运行期窗口背景恒为 `null`
 * （主题 `windowBackground=@null` + `setBackgroundDrawable(null)` + `setFormat(TRANSLUCENT)`，
 * 这是消除「胶囊外圈 1px 细线 / 黑底」的红线组合）。因此本值实际只被
 * [applyWindowBlur] 里那条 `enabled == true` 的**不可达分支**用于构造 [GradientDrawable]，
 * 留着是为了该分支若将来复活时仍有一份正确的半径。
 *
 * ## 取值：18dp（产品拍板的折中值）
 * 旧值等于「胶囊半高 + 冗余」，会把胶囊描成一颗完整药丸；新视觉要的是「明显圆润但
 * 不满圆」的卡片感，故取 18dp。
 *
 * **胶囊的真实可见圆角不在这里** —— 它由内容层承担：见 `UfiCapsuleTabBar` 的
 * `CAPSULE_CORNER`（同为 18）。两处必须保持同一个数值，由回归护栏
 * `bottomInsetCorner_mustSyncTo18dp` / `bottomInsetCorner_mustNotBePill` 锁死。
 */
private val CAPSULE_WINDOW_CORNER_RADIUS: Dp = 18.dp

/**
 * 窗口背景 drawable 的着色透明度。
 *
 * `Window.setBackgroundBlurRadius` **必须**有一个非全透明的窗口背景才能确定模糊区域
 * （见 [applyWindowBlur] 的「F2 命门」）。但真正的玻璃质感由 Compose 侧
 * [UfiCapsuleTabBar] 绘制，这里只需要「一个形状」而不是「一层颜色」——
 * 所以 alpha 只给到 0.12f：足够让系统认定窗口背景非空、让模糊 kernel 有落脚点，
 * 又不会与 Compose 的 tint 叠加变浑。窗口底色只是给系统模糊一个「形状落脚点」，
 * 不是主视觉层，故压低透明度避免与胶囊内部 frosted 叠加变浑。
 */
private const val CAPSULE_WINDOW_TINT_ALPHA: Float = 0.12f

/**
 * 胶囊窗口**必须置位**的 flag 组合（**单一真源**）。
 *
 * ## 为什么合成一个常量
 * 这三个 flag 原来分两次 `Window.addFlags` 打上（触摸穿透两个 + 越界布局一个）。
 * `Window.setFlags` 在 AOSP 里**无条件**调用 `dispatchWindowAttributesChanged`
 * （没有"值没变就不写"的短路），每一次都会走到 `Dialog.onWindowAttributesChanged` →
 * `WindowManager.updateViewLayout` → `ViewRootImpl.setLayoutParams` + `scheduleTraversals`。
 * 合成一个目标值后，flags 与 gravity / y / 宽高一起在
 * [applyCapsuleWindowParams] 里**一次写定**，且写之前先比对，值相等直接返回。
 *
 * - `FLAG_NOT_FOCUSABLE`：不抢焦点（不弹输入法、不吃返回键）；
 * - `FLAG_NOT_TOUCH_MODAL`：窗口矩形之外的触摸继续下发给下方页面（产品红线）；
 * - `FLAG_LAYOUT_NO_LIMITS`：解除"窗口必须待在系统装饰之内"，让 BOTTOM gravity 的参考系
 *   变成屏幕真实底边（配套 `decorFitsSystemWindows = false`，见 [applyWindowBlur]）。
 */
private const val CAPSULE_WINDOW_FLAGS_ON: Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        // ★ 2026-09-24（ColorOS 16：小白条那条带子恒为白，且**只要本窗口存在就白**）
        //
        // `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` 是 `Window.setNavigationBarColor` 生效的
        // **前置条件**（平台契约：不带这个 flag 时 navigationBarColor 是个 no-op，
        // 系统改用自己那套默认的不透明导航栏底色）。
        //
        // Activity 窗口有这个 flag（`Theme.Material` 的 `windowDrawsSystemBarBackgrounds=true`，
        // 且 `enableEdgeToEdge()` 也会补），所以主窗口一直是沉浸的 —— 这正是
        // 「启动检测页（底栏窗口还没创建）小白条正常」的原因。
        // 而 **Dialog 主题没有这一项**（`Theme.Material.*.Dialog` 默认 false），
        // 于是底栏窗口一旦挂载、盖住屏幕底部，系统就按「这个窗口不自己画系统栏背景」
        // 补一条不透明底色 —— 我们在主题和 Kotlin 侧写的那两处 `navigationBarColor = TRANSPARENT`
        // 全程是空转。这也解释了为什么二级页（底栏隐藏但**窗口仍挂载**）白带照旧。
        //
        // ⚠ 别把它挪去改全局 `android:dialogTheme`：那会一次性影响 App 里每一个 Compose 弹窗的
        //   系统栏行为。这里只给底栏这一个窗口加，且走 [applyCapsuleWindowParams] 的
        //   同一次 attributes 写入（带相等性守卫），不额外派发。
        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

/**
 * 胶囊窗口**必须清除**的 flag（**单一真源**）。
 *
 * `FLAG_DIM_BEHIND` 会让整页压一层灰 —— 胶囊是常驻浮层，绝不能变暗。
 * 与之配套的 `dimAmount` 也在 [applyCapsuleWindowParams] 的同一次写入里归零，
 * 不再单独调 `Window.setDimAmount`（那是又一次无条件 attributes 派发）。
 */
// FLAG_TRANSLUCENT_* 在 API 30 起标记 deprecated，但**清除**它们仍然是让
// navigationBarColor 生效的必要条件（Android 12~14 的真机上照旧），minSdk 是 31。
@Suppress("DEPRECATION")
private const val CAPSULE_WINDOW_FLAGS_OFF: Int = WindowManager.LayoutParams.FLAG_DIM_BEHIND or
    // ★ 2026-09-24：与 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` 配套，必须**清掉**这两个。
    //   平台契约：`FLAG_TRANSLUCENT_NAVIGATION` / `FLAG_TRANSLUCENT_STATUS` 一旦置位，
    //   对应的 `navigationBarColor` / `statusBarColor` 直接被忽略、改由系统画半透明渐变底。
    //   `UfiDialogWindowTheme` 的 `windowIsTranslucent = true` 会带上它们
    //   （那一项是当年为窗口级真模糊留的硬性前提，模糊虽已移除但该项保留着防止换 parent 时被改掉），
    //   所以这里必须显式清除，否则透明导航栏色仍然是空转。
    //   ⚠ 清它们**不会**让窗口变回不透明：窗口透明由 `windowIsTranslucent` + `setFormat(TRANSLUCENT)`
    //     决定，与这两个 flag 无关。
    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS

/**
 * 「窗口级真模糊此刻是否**真的**生效」。
 *
 * ## 为什么需要它
 * 窗口模糊是一个**尽力而为**的能力：即便 API ≥ 31 且我们成功把参数提交给了窗口，
 * 系统也可能整体关闭 cross-window blur（省电模式、开发者选项「关闭窗口模糊」、
 * 或 OEM 在 `ro.surface_flinger.supports_background_blur` 上直接给了 false）。
 * 这些情况下所有 API 调用全部「成功」，但屏幕上一点模糊都不会有。
 *
 * 另一类失败是**承载方式**导致的：只有拿得到 [Window] 的宿主（Dialog）才能调用
 * `Window.setBackgroundBlurRadius`；若调用方退回 `Popup`（无 `Window` 对象），
 * 真模糊无从施加，本值恒为 false（见 [CapsuleBlurHost]）。
 *
 * [CapsuleBlurHost] 把上述「真相」算出来并通过本 CompositionLocal 下发，
 * [UfiCapsuleTabBar] 据此二选一：
 * - `true`  → 画**轻透 tint**，把系统模糊透上来（真·毛玻璃）；
 * - `false` → 画**多层视觉磨砂**（渐变 + 顶部高光 + 描边），保证观感仍是玻璃而非灰泥。
 *
 * 默认 `false`：任何没有被 [CapsuleBlurHost] 包裹的场景都按「无真模糊」渲染，永远不会
 * 出现「以为有模糊结果一片半透明看不清」的最差情况。
 */
val LocalUfiCapsuleRealBlurActive = compositionLocalOf { false }

/**
 * 「窗口内背景模糊」参数是否已**提交到窗口**。
 *
 * 与 [LocalUfiCapsuleRealBlurActive] 区分：后者表示「系统确认支持且提交成功」，
 * 用于 UI 切换为极轻 tint；但部分 ROM 的 [WindowManager.isCrossWindowBlurEnabled]
 * 查询返回 false，实际上 `Window.setBackgroundBlurRadius` 仍能渲染出模糊。
 *
 * 本值只反映「我们有没有把模糊半径写进窗口」，让 UI 在「系统未确认但已提交」
 * 场景下用一条介于「真模糊 tint」与「纯视觉磨砂」之间的背景，避免直接 fallback
 * 成不透明灰泥，也避免太透明而透出下方内容。
 */
val LocalUfiCapsuleBlurSubmitted = compositionLocalOf { false }

/**
 * 系统对 cross-window blur 的**能力开关**（由 [WindowManager.isCrossWindowBlurEnabled] 查询）。
 *
 * 与 [LocalUfiCapsuleRealBlurActive] 不同：后者是「能力 + 提交」的合取，
 * 本值只反映系统当前是否**宣称**支持模糊，用于真机诊断与中间态 UI。
 */
val LocalUfiCapsuleBlurCapability = compositionLocalOf { false }

/**
 * 胶囊展开进度（0 = 完全收起，1 = 完全展开）。
 *
 * [CapsuleBlurHost] 创建并通过本 CompositionLocal 向下传递一个可写的
 * [MutableFloatState]，[UfiCapsuleTabBar] 把自己的展开进度写回这里，供宿主观测。
 *
 * ## ★ 它**不再驱动窗口尺寸**（改动前必读）
 * 历史上有过两版「让窗口跟着进度缩」的实现，都是为了让窗口级真模糊的模糊区
 * （= 窗口矩形）与缩小后的胶囊对齐：
 * 1. 根节点 `Modifier.layout` 汇报 `自然尺寸 × scale` —— 逼着子树每帧重测，错位 + 卡顿；
 * 2. 宿主每帧 `setLayout(naturalSize × capsuleScaleOf(progress))` —— 每帧触发
 *    WindowManager relayout，动画卡顿，且窗口夹小后引发胶囊自激塌缩。
 *
 * 现在**窗口级真模糊已被彻底移除**，上述两版方案随之全部删除：胶囊的收起/展开缩放
 * 只由 [UfiCapsuleTabBar] 根 Box 的 `graphicsLayer` 表达（纯绘制期变换、零 measure、
 * 零窗口 relayout），玻璃观感由其 frosted 渐变背景层承担。
 *
 * 本值因此退化为**只写不读**的观测出口：[UfiCapsuleTabBar] 仍会把进度写回，
 * 但宿主不再消费它。保留是为了不破坏公开契约，也便于真机诊断。
 *
 * 默认 1f：任何未被 [CapsuleBlurHost] 包裹的场景都按展开态处理。
 */
val LocalCapsuleExpandProgress = compositionLocalOf { mutableFloatStateOf(1f) }

/**
 * 胶囊根 Box 的**自然（未缩放）测量尺寸**（px）。
 *
 * 由 [UfiCapsuleTabBar] 在其自定义 `Layout` 的 measure lambda 内、基于
 * `maxIntrinsicWidth(Constraints.Infinity)` 推导的**固有尺寸**写入（与窗口约束无关，
 * 避免「越缩越小最终消失」的自激塌缩）。
 *
 * [applyWindowBlur] 只在「初始 + 重放」时读一次，用它给窗口设一个**固定**尺寸
 * （尚未测得时退化 `WRAP_CONTENT`）。窗口尺寸**不再逐帧跟随展开进度**：
 * 那套方案连同它服务的窗口级真模糊一起被移除了（见 [LocalCapsuleExpandProgress]）。
 */
val LocalCapsuleNaturalSize = compositionLocalOf { mutableStateOf(IntSize.Zero) }

/**
 * 底部系统预留区高度（px），由 [CapsuleBlurHost] 下发给栏内容消费。
 *
 * ## 为什么要有它（2026-09-24，贴底通栏低栏迁移阶段 1）
 * 通栏形态把「安全区」从**窗口 y 偏移**挪进了**栏内部的一段 Spacer**
 *（背景铺到屏幕真实底边、内容靠 Spacer 让位，见 `docs/bottom-dock-migration-plan.md` §3）。
 * 消费方从此变成 Compose 内容层，但取值口径**一个字都不能变** ——
 * [readBottomReservedPx] 是 `max(navigationBars, systemGestures)`、手势导航兜底
 * [GESTURE_BOTTOM_INSET_FLOOR]、且一律回读**宿主 Activity** 的全屏 `rootWindowInsets`
 *（读 Dialog 自己的会形成「窗口位置 → 自身 inset → 再改窗口位置」的自激振荡，已踩过）。
 *
 * 所以这里下发的是 [CapsuleBlurHost] 里**已经算好**的那份 `reservedPx`，
 * 而不是让栏自己去读一遍 `WindowInsets`：真源只能有一个。
 *
 * 默认 0：未被 [CapsuleBlurHost] 包裹的场景（@Preview / 单测）没有真实屏幕底边可贴，
 * 留 0 比留一个凭空的兜底值更容易看出「忘了套宿主」。
 */
val LocalCapsuleBottomReserved = compositionLocalOf { 0 }

/**
 * 底部**系统栏本体**高度（px，只有 `navigationBars`）—— [LocalCapsuleBottomReserved] 的搭档。
 *
 * 前者是「栏总共要为系统底部让出多高」（含手势热区），后者是「屏幕底部真正有系统像素
 * （小白条 / 三键按键带）的那一段」。通栏形态下栏本来就横跨整条手势热区（热区只拦上滑手势，
 * 不影响绘制），真正需要留空的只有系统栏本体 —— 两者的差值应该挪到**内容区上方**，
 * 否则图标会被顶得离屏幕底边过远（2026-09-24 真机：ges=112px 而 nav=56px，差出 56px）。
 *
 * 由 [CapsuleBlurHost] 与 [LocalCapsuleBottomReserved] 在同一次 provide 里下发，
 * 两个读数共用同一个 inset listener，天然同步。
 */
val LocalCapsuleBottomSystemBar = compositionLocalOf { 0 }

/**
 * 胶囊悬浮栏底部遮挡预留高度的**读取器**（`() -> Dp`）。
 *
 * ## 为什么需要它（2026-08-08 19:47 新增，方案 A 修订版）
 * 监控页底部内容曾被胶囊 Tab 栏遮挡（真机截图：「暂无异常事件」空态被挡）——旧代码用硬编码
 * `88.dp` 做底部预留，但胶囊实际视觉 footprint = 内容高度×收起缩放(0.86) + 底部间隙(8dp) +
 * 系统手势/导航栏高度，随设备和 ROM 变化，88dp 在部分机型不足。
 *
 * 本值由 [CapsuleInsetHolder]（跨窗口共享单例）动态提供：
 * - [CapsuleBlurHost]（胶囊所在 **Dialog 窗口**）测量后写入；
 * - 主窗口根（`MainNavGraph`）把「读它的方式」`CompositionLocalProvider` 下发给所有主 Tab 页；
 * - 页面在 **layout 阶段** 调用它做底部预留（见 [ufiCapsuleBottomInset]）。
 *
 * ⚠ **CompositionLocal 不跨 Window**：胶囊 Dialog 是独立窗口，其组合树与主窗口不联通，
 * 因此不能直接在 [CapsuleBlurHost] 里 provide 给页面 —— 必须经共享单例桥接（见 [CapsuleInsetHolder]）。
 *
 * ## ★★ 为什么类型是 `() -> Dp` 而不是 `Dp`（2026-09-05 P1，别改回去）★★
 * 它是 `staticCompositionLocalOf` —— **没有细粒度读追踪**，`provides` 的值一变，
 * provider 以下**整棵树无条件重组**。而它的 provider 在 `MainNavGraph` 里包着整个 `NavHost`。
 * 旧写法在组合期解引用 `CapsuleInsetHolder.bottomInset.value` 再 provide 一个 `Dp`，
 * 于是返回动画的时间线正好被砸中：
 *   1. 离开 MAIN → 胶囊 dispose → 写回兜底 88dp（见本文件 `onDispose`）；
 *   2. pop → 胶囊重挂 → 首帧 `naturalSize == 0` 不发布（护栏
 *      `capsuleInset_mustNotPublishBeforeMeasured`）；
 *   3. 第 2~3 帧测量完成 → 发布真值 → **NavHost 整子树重组 + 所有读它的页面重排**。
 * 88dp 兜底与真值差约 50dp ⇒ 既是"卡顿"（整树重组落在转场头几帧）也是"位置跳"。
 *
 * 改成下发**读值的方式**后：provide 的 lambda 实例被 `remember` 住、永不变
 * ⇒ static local 永不触发整树重组；快照读推迟到消费方的 layout 阶段
 * ⇒ inset 变化只失效那一个节点的测量。
 *
 * 默认返回 `88.dp`：兼容未包裹场景 / 预览 / 胶囊未测量时（保持旧硬编码行为，不崩溃）。
 */
val LocalCapsuleBottomInset = staticCompositionLocalOf<() -> Dp> { { DEFAULT_CAPSULE_BOTTOM_INSET } }

/**
 * 「底栏遮挡预留」在**页壳内部**真正还需要补的那一段（Dp）。**扣减 navigationBars 的唯一落点。**
 *
 * ## 为什么要减（2026-09-24，贴底通栏低栏迁移 §5.21 / §7-2.9 ①）
 * [LocalCapsuleBottomInset] 发布的是**底栏自身总高**（`DOCK_CONTENT_MIN_HEIGHT` + 安全区，
 * 见 [CapsuleBlurHost] 里 `capsuleBottomTotalDp`），**含**安全区那条带子；
 * 而 `UfiScreenScaffold` 的内容 `Box` 早就有 `windowInsetsPadding(WindowInsets.navigationBars)`
 * （`UfiScaffold.kt:623`）—— 页面内容区的底边本来就已经在「屏幕底边 − navigationBars」处。
 * 两个量直接相加就是**双算**：需要的留白是 `底栏总高 − navigationBars`，实际留白却是
 * `底栏总高`，每个主 Tab 页各多留一个 navigationBars（手势导航≈24dp、三键≈48dp）。
 *
 * ## 旧数字当年为什么是对的
 * 悬浮胶囊形态下这一项被**窗口 `y` 抬高 30dp**（`CAPSULE_LIFT`，已随 §2.1 退休）近似抵掉了：
 * 胶囊本体悬在安全区**之上**，页面要避开的是「胶囊高 + 8dp 间隙 + 抬升 + 安全区」，
 * 与页壳消费掉的那一段刚好各据一半，观感上看不出多留。通栏形态把安全区收进了栏自己
 * （背景铺到屏幕真实底边 + 栏内 Spacer 让位，见 [LocalCapsuleBottomReserved]），
 * 抬升归 0 ⇒ 抵消项消失，双算立刻显形。这是本次迁移**引入**的回归，不是历史遗留。
 *
 * ## 为什么读到的 navigationBars 就是页壳消费掉的那一段（已实查，2026-09-24）
 * 从 Activity 根到 `UfiScaffold.kt:623` 那句之间**没有任何** `consumeWindowInsets`（全仓 grep 为 0）
 * 也没有第二处消费 navigationBars 的 `windowInsetsPadding`：
 * - `MainNavGraph` 的 `Scaffold` 是 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`（`MainNavGraph.kt:373`），
 *   四边恒 0、不消费；
 * - 页壳根 `Column` 只有 `statusBarsPadding()`（`UfiScaffold.kt:577`），消费的是 statusBars（顶部），
 *   与 navigationBars 是两路不同的 inset。
 *
 * 另外：`windowInsetsPadding` 的「消费」只在 modifier 链内经 ModifierLocal 传播，**不会**改变
 * 组合期 `WindowInsets.navigationBars` 的读数 —— 所以这里读到的恒是完整值，与页壳收掉的那一段同量。
 *
 * ## 为什么只减 navigationBars、不减整条安全区
 * [readBottomReservedPx] 的口径是 `max(navigationBars, systemGestures)` 且带手势兜底
 * [GESTURE_BOTTOM_INSET_FLOOR]（24dp，应对把 navigationBars 报 0 的国产 ROM）。
 * 页壳只 padding 了 navigationBars 这一路，所以只能减这一路：
 * 兜底/手势那部分高出来的差额仍留在结果里，页面该避的照样避。
 *
 * @param total 底栏自身总高（[LocalCapsuleBottomInset] 的读数）。
 * @param navigationBars 组合期取到的稳定 inset 对象，读数压在本函数（layout 阶段）里发生。
 * @param extra 呼吸间距（调用点走 `Spacing` 令牌）。它**不参与**扣减：页壳消费的是安全区，
 *              与「内容离栏多远」是两回事，减到 0 以下只会把呼吸吃掉。
 */
private fun Density.capsuleBottomClearance(total: Dp, navigationBars: WindowInsets, extra: Dp): Dp =
    (total - navigationBars.getBottom(this).toDp()).coerceAtLeast(0.dp) + extra

/**
 * 把「底栏底部遮挡预留」表达成一个**只在 layout 阶段读值**的高度占位。
 *
 * 用法：作为滚动列表**最后一个 item** 的 `Spacer` 修饰符，语义等价于原来的
 * `LazyColumn(contentPadding = PaddingValues(bottom = inset + extra))` ——
 * 都是在内容末尾追加一段可滚动的空白，且不缩小视口（`Modifier.padding` 会缩小视口、
 * 让 item 在硬边界处被裁掉，不是同一个观感）。
 *
 * ## ★★ 配对约定：本修饰符假定调用点在 `UfiScreenScaffold` 的内容区之内 ★★
 * 那里已经 `windowInsetsPadding(WindowInsets.navigationBars)`（`UfiScaffold.kt:623`），
 * 所以本修饰符**显式减掉**页壳已经消费的那一段（见 [capsuleBottomClearance]）：
 * ```
 * 最终高度 = (底栏总高 − navigationBars.bottom).coerceAtLeast(0.dp) + extra
 * ```
 * 在**页壳之外**使用（自持 `Scaffold`、或直接挂在满屏 Box 上的页面）会**少留**一个
 * navigationBars 的白 —— 底部最后一行内容被栏压住。那种场景请自己补
 * `navigationBarsPadding()`，或换用不减的口径。
 *
 * 配对关系由护栏 `capsuleInset_mustDeductShellConsumedNavigationBars` 钉住：
 * 任何一端被单独改掉（这里不再减、或页壳那句被删）都会变红。
 *
 * 已实查（2026-09-24）：全部 6 个调用点都在页壳内容区之内 ——
 * `DashboardScreen:229`（壳 166..233）/ `NetworkScreen:210`（壳 92..223）/
 * `ToolsScreen:70`（壳 29..73）/ `SettingsScreen:276`（壳 45..279）/
 * `MonitorScreen:549` 与 `:762`（两者在壳 229..265 内调用的两个 Tab 内容函数里）。
 *
 * 关键点：[LocalCapsuleBottomInset] 的 `.current` 在组合期读到的只是那个**稳定的 lambda**
 * （provider 永不变 ⇒ 不触发重组），真正的快照读 `CapsuleInsetHolder.bottomInset.value`
 * 发生在 `Modifier.layout` 的 measure lambda 里 ⇒ **inset 变化只触发本节点重排，不触发重组**。
 * 这是 P1（pop 第 2~3 帧整树重组）修复的消费侧一半，另一半在 `MainNavGraph` 的 provider。
 * `WindowInsets.navigationBars` 同理：组合期只取稳定对象，`getBottom()` 的读数压在 measure 里。
 *
 * @param extra 额外留白（如列表与底栏之间的呼吸间距）。走 `ui.theme` 的 `Spacing` 令牌，
 *              不要在调用点写裸数值。
 */
@Composable
fun Modifier.ufiCapsuleBottomInset(extra: Dp): Modifier {
    val insetOf: () -> Dp = LocalCapsuleBottomInset.current
    val navigationBars = WindowInsets.navigationBars
    return this.layout { measurable, constraints ->
        // ★ 这一行是整个修复的落点：快照读在 measure 里，不在组合里。
        val height = capsuleBottomClearance(insetOf(), navigationBars, extra)
            .roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height)
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * 把「底栏底部遮挡预留」表达成一个**向上的位移**，给贴底的**浮动**控件用。
 *
 * 与 [ufiCapsuleBottomInset] 同源（共用 [capsuleBottomClearance]，因此**没有第二个数字**），
 * 区别只在表达方式：占位改不了「`align(Alignment.BottomCenter)` 贴在容器底边」的浮层，
 * 那种控件要的是把自己抬离容器底边 `clearance` 那么多。
 *
 * 配对约定与 [ufiCapsuleBottomInset] 完全一致：调用点必须在 `UfiScreenScaffold` 内容区之内
 * （容器底边 = 屏幕底边 − navigationBars），壳外使用会抬不够、被栏压住。
 *
 * 首个消费者：监控「总览」Tab 的浮动分页条（`MonitorScreen.kt`，原先写死 `offset(y = -40.dp)`）。
 */
@Composable
fun Modifier.ufiCapsuleBottomLift(extra: Dp): Modifier {
    val insetOf: () -> Dp = LocalCapsuleBottomInset.current
    val navigationBars = WindowInsets.navigationBars
    // offset 的 lambda 形态同样是 layout 阶段求值 —— 与上面一致，inset 变化只重排不重组。
    return this.offset {
        IntOffset(0, -capsuleBottomClearance(insetOf(), navigationBars, extra).roundToPx())
    }
}

/**
 * 胶囊底部遮挡预留高度的**默认兜底值**（= 旧硬编码 88dp，保持行为兼容）。
 * 胶囊宿主测量完成前、以及任何未包裹场景都回落它。
 */
internal val DEFAULT_CAPSULE_BOTTOM_INSET: Dp = 88.dp

/**
 * 胶囊底部遮挡 inset 的**跨窗口共享状态**（单例）。
 *
 * ## 为什么需要单例（方案 A 修订版核心）
 * 胶囊活在独立 `Dialog` 窗口（`MainNavGraph` 用 `Dialog { CapsuleBlurHost { ... } }` 承载），
 * 而页面内容（`MonitorScreen` 等）在主 Activity 窗口 —— **CompositionLocal 不跨 Window**，
 * 宿主 provide 的值页面读不到。因此用本单例作为两个窗口间的桥：
 * - **写**：[CapsuleBlurHost]（Dialog 内）测量 `naturalSize` + `reservedPx` 后写入 [bottomInset]；
 * - **读**：主窗口根（`MainNavGraph`）`collectAsState` 读取 → `CompositionLocalProvider` 下发，
 *   页面 `LocalCapsuleBottomInset.current` 消费。
 *
 * 胶囊隐藏（`showCapsule=false` → Dialog 卸载）时宿主 `onDispose` 写回默认值。
 */
object CapsuleInsetHolder {
    /** 当前胶囊底部遮挡总高（Dp）。默认 [DEFAULT_CAPSULE_BOTTOM_INSET]。 */
    val bottomInset: MutableState<Dp> = mutableStateOf(DEFAULT_CAPSULE_BOTTOM_INSET)

    /*
     * ★ 已删除（2026-09-24）：`bottomReservedDp` —— 把预留区高度跨窗口发给主窗口，
     *   好让主窗口补画一条底栏窗口「够不到」的安全区带子。
     *
     *   当时的假设（floating Dialog 在 Android 15/16 被 DecorView 吃掉系统 inset）
     *   被诊断版在真机上证伪：底栏窗口本来就铺到了屏幕底边。真因是那个窗口缺
     *   `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，导致透明 navigationBarColor 空转、
     *   系统补了一条不透明导航栏底色（见 [CAPSULE_WINDOW_FLAGS_ON]）。
     *   补画那条带子因此是纯重复绘制，连同本字段一起移除。
     */
}

/**
 * 胶囊窗口**此刻是否应该接收触摸**的跨窗口开关（单例）。
 *
 * ## 为什么需要它（2026-09-16：二级页底部控件点不动）
 * 胶囊自 2026-09-15 起「挂载后不再卸载」——二级页上只把内容 `scale/alpha` 动到 0，
 * **窗口本身留着**。而 `FLAG_NOT_TOUCH_MODAL` 只放行窗口矩形**之外**的触摸：窗口矩形
 * 依旧是胶囊的自然尺寸、依旧锚在底部（y = 系统预留区 + 间距 + 抬高），于是二级页里
 * 屏幕底部那条带子上的一切控件都收不到事件 —— 表现就是「看得见、点不动、点偏了才响应」。
 * 音乐播放页把进度条与播放键放在最底部，整排全落在这条带子里，问题最刺眼。
 *
 * 解法不是把窗口撤掉（那会带回窗口增删的闪帧，已被回退过），而是在隐藏期给窗口补
 * `FLAG_NOT_TOUCHABLE`：窗口还在、像素不画、**触摸整块下发**给下面的页面。
 * 与 [CapsuleInsetHolder] 同理走单例 —— CompositionLocal 不跨 Window。
 *
 * - **写**：`MainNavGraph` 按「当前是不是宿主目的地」写入（detail 页 = false）；
 * - **读**：[CapsuleBlurHost]（Dialog 内）作为窗口属性的一部分下发。
 */
object CapsuleTouchGate {
    /** true = 胶囊可点（停在宿主目的地）；false = 窗口不吃触摸（二级页）。 */
    val interactive: MutableState<Boolean> = mutableStateOf(true)
}

/**
 * 查询当前设备/系统是否**真的**支持并开启了 cross-window blur。
 *
 * API < 31 直接 false。任何异常（部分 ROM 反射阉割过 WindowManager）都按 false 处理，
 * 让 UI 走视觉磨砂降级，绝不因为一个装饰性效果崩溃。
 */
private fun queryCrossWindowBlurEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return false
    return runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
}

/**
 * 施加窗口模糊所需、且**只能在 `@Composable` 里算出来**的度量值。
 *
 * [applyWindowBlur] 是普通函数（要在 `DisposableEffect` / `view.post` 等非组合上下文里
 * 反复调用），拿不到 `LocalDensity` / `LocalResolvedPalette`；因此把 dp→px 换算与取色
 * 全部前置到 [CapsuleBlurHost] 里做一次，用本值传进去。
 *
 * ## ★ 已删除的两个字段（2026-09-24，贴底通栏低栏迁移阶段 2.1）
 * - `bottomOffsetPx`：窗口相对屏幕真实底边的 `y` 偏移
 *   （= `bottomReservedPx` + (`capsuleGapFor(isGestureNav)` − `CAPSULE_SHADOW_ROOM`) + `CAPSULE_LIFT`）。
 *   悬浮形态靠它把胶囊**抬离**底边；通栏形态 `y` 恒 0（栏主动铺满底部那条带子），
 *   整条表达式与它依赖的三个常量一起退休，见文件顶部的两段删除说明。
 * - `insetPx`：窗口背景 drawable 四边内缩量。它恒等于 `CAPSULE_SHADOW_ROOM`（恒 0），
 *   而窗口背景又必须恒为 `null`（红线 R1），所以本就是一个双重意义上的死值。
 *
 * ⚠ [bottomReservedPx] **保留**：安全区高度现在的消费方是「栏内 Spacer + 页面 inset」，
 *   这一份仍是诊断与复用的出口，取值口径（[readBottomReservedPx]）一字未改。
 *
 * @property cornerRadiusPx    窗口背景 drawable 的圆角半径（px）。
 * @property tintArgb          窗口背景 drawable 的填充色（ARGB），低 alpha，只定形不抢色。
 * @property bottomReservedPx  屏幕底部**系统占用/预留区**高度（px）：系统导航栏与手势热区的
 *                             **并集**（见 [readBottomReservedPx]）。它比「导航栏高度」更宽 ——
 *                             手势导航下 `navigationBars` 可能远小于真实手势热区。
 *                             通栏形态下由 [LocalCapsuleBottomReserved] 下发给栏内容消费。
 */
private data class CapsuleWindowMetrics(
    val cornerRadiusPx: Float,
    val tintArgb: Int,
    val bottomReservedPx: Int,
    /**
     * 栏底色是否**浅色** ⇒ 手势小白条必须画成**深色**才看得见。
     *
     * ## 这条不是可选的"打磨"（§5.3 的原假设是错的）
     * 计划 §5.3 当时写「手势导航下平台做 dynamic color adaptation，小白条按背后内容自动反色，
     * 不需要额外处理」。真机证伪：小白条的明暗只看**窗口的** `isAppearanceLightNavigationBars`，
     * 平台不会去采样背后像素。
     *
     * 悬浮胶囊时代看不出来：胶囊被抬离底边，小白条压的是**页面**底色，而页面在 Activity 窗口里，
     * 那个窗口的 appearance 由 `MainActivity.enableEdgeToEdge()` 按**系统**夜间模式定过一次。
     * 贴底通栏之后小白条压的是**本 Dialog 窗口**里的栏底色，而这个窗口从来没设过 appearance
     * ⇒ 取默认值「深背景」⇒ 小白条恒为白 ⇒ 压在白底栏上直接看不见。
     *
     * 判据取 [dockSurfaceColor] 的亮度（> 0.5 即浅色），与栏实际画的那块颜色同源；
     * 顺带也覆盖了「应用内主题与系统夜间模式不一致」这种 `enableEdgeToEdge()` 管不到的组合。
     */
    val lightNavHandleSurface: Boolean
)

/**
 * 从任意 [Context] 沿 [ContextWrapper] 链上溯，找出宿主 [Activity]。
 *
 * Compose 的 `Dialog()` 用 `ContextThemeWrapper(activityContext, …)` 构造，
 * 所以对话框子组合里的 `LocalView.context` **不是** Activity 本身，必须解包。
 */
private fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 把胶囊窗口的 [WindowManager.LayoutParams] **一次写定**（本文件唯一的整体赋值点）。
 *
 * ## 为什么必须收成一次写入（返回时阴影闪烁 / 抽搐的根因）
 * [applyWindowBlur] 会被**重放三次**（立即 / `view.post` / `delay(100ms)`，见
 * [CapsuleBlurHost]），而它旧实现里的每一个窗口 setter 都会**无条件**派发一次
 * attributes 变更：`addFlags` / `clearFlags`（→ `Window.setFlags`）、`setDimAmount`、
 * `setLayout`、`attributes =`、`setElevation`、`setFormat` —— AOSP 这些方法里都没有
 * "值没变就短路"，一律走 `dispatchWindowAttributesChanged` →
 * `Dialog.onWindowAttributesChanged` → `WindowManager.updateViewLayout` →
 * `ViewRootImpl.setLayoutParams` + `scheduleTraversals`。
 *
 * 同一次同步调用里的多次派发会被 `scheduleTraversals` 合并成一帧，所以**真正的代价来自
 * 重放**：三次重放落在三个不同的帧上 ⇒ 三次 WindowManager relayout。胶囊 Dialog 恰好是
 * 在 pop 那一刻新挂的（`MainNavGraph` 立即挂载，不得延后），于是第二次重放落在返回动画
 * 第一帧、第三次落在动画中段，每次 relayout 都会重算 decor 布局并重建窗口 Surface 的
 * 投影区 —— 用户看到的就是「返回时底部阴影闪一下 / 抽一下」。
 *
 * 本函数因此：
 * 1. 把 flags / dimAmount / 宽高 / gravity / y / 刘海模式**合并成一份目标值**；
 * 2. 与窗口**当前** attributes 逐字段比对，**全等则直接 return**（零派发、零 relayout）；
 * 3. 只有真的不一致（首次施加，或 Compose 的 `DialogWrapper.show()` 把属性重放回默认值）
 *    才写一次。
 *
 * 于是重放退化为"校验"：没被改动就什么都不做，被改动了才修一次。
 *
 * ⚠ `format` 不在这里写：它必须走 `Window.setFormat`（那会同时置上平台内部的
 * `mHaveWindowFormat`，是"黑底回归修复"的命门），在 [applyWindowBlur] 里单独带守卫调用。
 *
 * @param naturalSize 栏根 Box 的固有尺寸。**只用 height**：已测得则把窗口高度钉成它，
 *                    未测得（首帧为 0）退化 `WRAP_CONTENT` 由内容撑开。
 *                    width 分支已于 2026-09-24 退休 —— 通栏形态宽度恒 `MATCH_PARENT`
 *                    （见函数体内说明与迁移计划 §5.7）。
 */
private fun applyCapsuleWindowParams(
    window: Window,
    naturalSize: MutableState<IntSize>,
    interactive: Boolean
) {
    // ★ 2026-09-24（贴底通栏低栏，迁移计划 §2.1 对照表）：width 恒 MATCH_PARENT。
    //
    // 原来是 `if (naturalSize.value.width > 0) it else WRAP_CONTENT` —— 悬浮胶囊必须让
    // 窗口矩形**紧贴胶囊本体**：`FLAG_NOT_TOUCH_MODAL` 只放行窗口矩形**之外**的触摸，
    // 窗口一撑满宽度就会把整条底部的点击圈进来，胶囊左右两侧的页面内容点不动。
    // 通栏低栏的前提正好相反 —— 它**就是**要占满那条带子，而「栏以上区域仍需穿透」
    // 依旧由 FLAG_NOT_TOUCH_MODAL 保证（栏本身是不透明可点的，这是预期）；
    // 二级页整块不吃触摸另由 [CapsuleTouchGate] 的 FLAG_NOT_TOUCHABLE 负责，通栏后
    // 窗口矩形更宽，那条 gate 比改造前更重要（§5.4）。
    //
    // ⚠ naturalSize 的 **height 分支保留**：栏总高仍由内容测量决定（fontScale 放大时
    //   栏要能长高，见 §5.7 / §5.13），只有 width 分支退休。
    val desiredWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    val desiredHeight: Int = if (naturalSize.value.height > 0) naturalSize.value.height
        else ViewGroup.LayoutParams.WRAP_CONTENT
    // ★ 2026-09-24：gravity 去掉 CENTER_HORIZONTAL —— 宽度已是 MATCH_PARENT，
    //   水平居中无意义（留着只会让人以为窗口还有可居中的余量）。只保留 BOTTOM：
    //   它配合 FLAG_LAYOUT_NO_LIMITS 把参考系钉在**屏幕真实底边**。
    val desiredGravity: Int = Gravity.BOTTOM
    // 窗口已越过系统装饰边界，显式允许铺到刘海/挖孔区，避免横屏被平台二次内缩。
    val desiredCutoutMode: Int =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    // ★ 2026-09-24：y 恒 0（迁移计划 §2.1 对照表）。
    //
    // 原值是 `metrics.bottomOffsetPx` = 安全区 + 安全间距 8dp + 视觉抬高 30dp，
    // 用来把悬浮胶囊整体**抬离**屏幕底边。通栏低栏贴底，任何 y > 0 都会在栏与屏幕底边
    // 之间露出一条页面内容 —— 通栏就不通了。安全区不是被丢掉，而是换了消费方：
    // 栏内部用一段只有底色的 Spacer 让位（[LocalCapsuleBottomReserved] → `UfiBottomDock`），
    // 页面 inset 则由 [CapsuleInsetHolder] 发布「内容高 + 安全区」。见 §3。
    val desiredY: Int = 0

    val params: WindowManager.LayoutParams = window.attributes
    // 隐藏期（二级页）补 FLAG_NOT_TOUCHABLE：窗口留着不闪帧，但整块不吃触摸 ——
    // FLAG_NOT_TOUCH_MODAL 只放行窗口矩形**之外**，光靠它二级页底部依旧点不动。
    // 见 [CapsuleTouchGate]。
    val desiredFlags: Int =
        ((params.flags or CAPSULE_WINDOW_FLAGS_ON) and CAPSULE_WINDOW_FLAGS_OFF.inv())
            .let { base ->
                if (interactive) {
                    base and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            }

    // ★ 相等则跳过：同值重复写也会触发 relayout（AOSP 的 setter 全部无条件派发）。
    val settled: Boolean = params.flags == desiredFlags &&
        params.dimAmount == 0f &&
        params.width == desiredWidth &&
        params.height == desiredHeight &&
        params.gravity == desiredGravity &&
        params.y == desiredY &&
        params.layoutInDisplayCutoutMode == desiredCutoutMode
    if (settled) return

    window.attributes = params.apply {
        flags = desiredFlags
        dimAmount = 0f
        width = desiredWidth
        height = desiredHeight
        gravity = desiredGravity
        y = desiredY
        layoutInDisplayCutoutMode = desiredCutoutMode
    }
}

/**
 * 判别当前是否为**手势导航**模式。
 *
 * 判据用 `tappableElement`：它表示「窗口内**仍可被点击**的区域被系统吃掉了多少」。
 * - 三键 / 二键导航：底部那条实体按键带是不可点击的，`tappableElement().bottom > 0`；
 * - 手势导航：小白条区域仍然接受应用的点击（只是同时被系统拿去识别手势），
 *   平台按约定把 `tappableElement().bottom` 报成 **0**。
 *
 * 再与 `navigationBars().bottom > 0` 取合取，排除「全屏沉浸、底部什么都没有」的场景。
 *
 * 取值口径与 [readBottomReservedPx] 完全一致（都走宿主 Activity 的全屏 rootWindowInsets），
 * 保证「预留区高度」与「按哪套间距」出自同一份 inset 快照，不会互相打架。
 *
 * @return true = 手势导航（或无法判定时的保守默认，此时会启用
 *         [GESTURE_BOTTOM_INSET_FLOOR] 保底，宁可抬高也不贴死）。
 */
private fun isGestureNavigationMode(view: View): Boolean {
    val decorView: View = view.context.findHostActivity()?.window?.decorView ?: return true
    val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(decorView) ?: return true
    val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
    // ★ 2026-09-24（ColorOS 16 手势区露黑）：判据去掉了原来的 `&& navBar > 0`。
    //   原意是排除「全屏沉浸、底部什么都没有」的场景，但部分 ROM（实测 ColorOS 16）
    //   在手势导航下把 `navigationBars().bottom` 也报成 0 —— 于是这里判成"不是手势导航"，
    //   `readBottomReservedPx` 的 24dp 保底随之失效、预留区算成 0，通栏底栏就不再覆盖手势区。
    //   现在只看 `tappable == 0`：真·全屏沉浸场景多留 24dp 无害（底栏本来就只在正常 UI 下显示），
    //   而漏底是肉眼可见的缺陷 —— 两者的代价不对称。
    return tappable == 0
}

/**
 * 读取屏幕底部「系统占用 / 预留区」高度（px）：**系统栏与手势热区的并集**，胶囊绝不能进入。
 *
 * ## 为什么不是「导航栏高度」
 * 手势导航下 `navigationBars().bottom` 只描述那条小白条的绘制带，而系统真正会拦截
 * 上滑手势的热区（`systemGestures().bottom`）通常更高；只避开前者，胶囊底部就会落进
 * 手势热区里 —— 点不动、或一点就触发返回桌面。所以这里取 **max(navBar, systemGestures)**。
 *
 * ## 为什么取值必须走宿主 Activity 而不是胶囊自己的窗口
 * 平台计算 inset 时是**按窗口 frame 裁剪**的（`InsetsState.calculateInsets(frame, …)`）：
 * 一个 wrap-content 的小浮窗只要和导航栏没有交集，读到的就是 0。而胶囊窗口的位置又
 * 恰恰依赖这个值 —— 窗口一旦被抬到导航栏上方，下一次读数就归零，y 偏移随之塌回去，
 * 形成「上跳—下落」的抖动死循环（自激振荡）。
 *
 * Activity 的窗口是全屏的（`MainActivity` 已 `enableEdgeToEdge()`），
 * `getRootWindowInsets()` 返回的是**未被 DecorView 消费**的原始窗口 inset，
 * **不受胶囊浮窗位置影响**，是这里唯一稳定的真源。响应式监听见
 * [rememberBottomReservedPx]：监听挂在 Dialog 自己的 decorView 上只当「变了」的信号，
 * 取值一律回到本函数。
 *
 * ## ⚠ 已知限制（本轮不覆盖）
 * 「Activity 窗口 == 全屏」这个前提在**平板 / 折叠屏展开态 / 多窗口分屏 / 自由窗口**下
 * 不再成立：此时 Activity 自己就是个被裁剪的子窗口，读到的 inset 与屏幕底部预留区无关。
 * 本轮修复只覆盖**手机形态**，上述形态待后续单独处理。
 *
 * @return 预留区高度（px）；解析不到宿主 Activity / 视图尚未 attach / inset 读不到时，
 *         回落到 [GESTURE_BOTTOM_INSET_FLOOR]，**绝不回 0**（旧实现回 0 正是根因 R1）。
 */
private fun readBottomReservedPx(view: View, density: Density): Int {
    val decorView: View = view.context.findHostActivity()?.window?.decorView
        ?: return with(density) { GESTURE_BOTTOM_INSET_FLOOR.roundToPx() }
    val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(decorView)
        ?: return with(density) { GESTURE_BOTTOM_INSET_FLOOR.roundToPx() }

    val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
    // ★ 2026-09-24：并集里加上 tappableElement。三键导航下它就是那条按键带的高度，
    //   而个别 ROM 的 navigationBars 会比它小（甚至为 0）。取三者最大值，宁多不少 ——
    //   通栏形态下这个值决定「栏有多高」，少算一点就是屏幕底部露出一条页面底色。
    val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
    val raw = maxOf(navBar, gestures, tappable)

    // 手势导航才需要保底：三键导航的 navigationBars 是实打实的按键带高度，读数可信，
    // 强行抬到 24dp 反而会让胶囊无谓地飘起来。
    val floor = with(density) {
        if (isGestureNavigationMode(view)) GESTURE_BOTTOM_INSET_FLOOR.roundToPx() else 0
    }
    return maxOf(raw, floor)
}

/**
 * 读取**系统栏本体**占用的底部高度（px）—— 只有 `navigationBars`，不含手势热区。
 *
 * ## 与 [readBottomReservedPx] 的分工（2026-09-24，通栏形态下才有意义）
 * - [readBottomReservedPx] = `max(navigationBars, systemGestures, tappableElement)` + 手势保底
 *   ⇒ 「栏总共要为系统底部让出多高」。悬浮胶囊时代它决定胶囊抬多高（绝不能落进手势热区）。
 * - 本函数 = `navigationBars` ⇒ 「屏幕底部真正有系统像素（小白条 / 三键按键带）的那一段」。
 *
 * 通栏贴底之后两者的差值不再是"不能进入的区域"：栏本来就横跨整条手势热区（热区只拦上滑手势，
 * 不影响绘制）。真正需要留空的只有系统栏本体那一段 —— 图标压在小白条上才是问题。
 * 于是那个差值（手势导航下约 112−56 = 56px）应该挪到**内容区上方**，而不是堆在下方
 * 把图标顶得离屏幕底边过远。见 [UfiBottomDock] 里 topPad / bottomPad 的拆分。
 *
 * 取值源与 [readBottomReservedPx] 完全一致（宿主 Activity 的全屏 rootWindowInsets），
 * 读不到时回 0 —— 这里回 0 是安全的：它只决定「内容区下方留多少」，
 * 少留会让图标更靠下、不会像预留区那样把整条栏算错高度。
 */
private fun readBottomSystemBarPx(view: View): Int {
    val decorView: View = view.context.findHostActivity()?.window?.decorView ?: return 0
    val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(decorView) ?: return 0
    return insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
}

/*
 * ★ 已删除（2026-09-24，贴底通栏低栏迁移阶段 2.1）：`capsuleGapFor(isGestureNav)` ★
 *
 * 它按导航模式分发「预留区顶边 → 胶囊底边」的呼吸间距（两个分支都是 8dp，分发结构只为
 * 将来单独加大按键导航的间距）。唯一调用点是 `bottomOffsetPx` 的赋值表达式，
 * 随该表达式一起退休 —— 通栏形态没有「悬在热区上方的底边」，这个间距概念本身不成立。
 * 详见文件顶部那段常量删除说明与 `docs/bottom-dock-migration-plan.md` §2.1。
 */

/**
 * **响应式**读取底部系统预留区高度（px），跟随导航模式切换 / 旋转 / 系统栏显隐实时更新。
 *
 * ## 信号源与取值源必须分离（核心设计，改动前必读）
 * - **信号源** = Dialog 自己的 decorView 上的 `setOnApplyWindowInsetsListener`。
 *   它只用来感知「inset 发生变化了」这一**事件**，回调参数里的 `insets` **一律不取值**。
 * - **取值源** = 宿主 Activity 的 `getRootWindowInsets()`（见 [readBottomReservedPx]）。
 *
 * 二者若合一（直接用回调的 `insets`），就会形成闭环：
 * `读到 inset → 改窗口 y → 窗口位置变 → 自身 inset 读数变 → 再改窗口 y …`，
 * 真机表现为胶囊上下抖动。取值换成**全屏** rootWindowInsets 后，读数与胶囊窗口位置
 * 彻底无关，闭环被物理切断 —— 因此这里**不需要也不应该加 debounce**：加了只会让
 * 旋转 / 分屏后归位延迟一拍，反而制造新的抖动观感。
 *
 * ## 允许下降（纠正旧实现的「只增不减」锁死）
 * 旧实现用 `if (real > cur) cur = real` 做单调守卫，于是：横屏（预留区变小）→ 不更新，
 * 三键切手势（48dp → 24dp）→ 不更新，胶囊永远悬在上一次的最大值上。这里改成
 * **只要值变了就写**（`!=`），并靠「取值源与窗口位置解耦」来保证不会来回抖。
 *
 * ## 其它约束
 * - `remember` 的 key **只有 `view`**：绝不把任何 inset 读数放进 key，否则读数一变
 *   state 就被硬重置，等于把响应式退化成「每次重建」，还会丢掉中间态；
 * - listener 里 **原样 `return insets`**，绝不返回 `WindowInsetsCompat.CONSUMED` ——
 *   吃掉 inset 会让 Dialog 内部的 Compose 布局全部读到 0；
 * - listener 只挂 Dialog 的 decorView，**不碰 Activity 的 decorView**（那个槽位已被
 *   `enableEdgeToEdge()` 占用，覆盖它会破坏全局 edge-to-edge）；
 * - view attach 时补读一次：组合期 view 可能尚未 attach，rootWindowInsets 读不到；
 * - `onDispose` 里把 listener 与 attach 回调都摘掉，不留悬挂引用。
 */
@Composable
private fun rememberBottomReservedPx(view: View, density: Density): BottomInsetReadings {
    // key 只有 view：不带任何 inset 读数，杜绝「读数一变就硬重置 state」。
    var reservedPx: Int by remember(view) {
        mutableIntStateOf(readBottomReservedPx(view, density))
    }
    // ★ 2026-09-24：第二个读数（系统栏本体高度），与 reservedPx **共用同一个 listener**。
    //   绝不能为它再挂一个 `setOnApplyWindowInsetsListener(view.rootView, …)` ——
    //   同一个 view 上的 listener 槽位只有一个，第二次注册会**顶掉**第一个，
    //   预留区就此不再响应导航模式切换 / 旋转（静默失效，没有任何报错）。
    var systemBarPx: Int by remember(view) {
        mutableIntStateOf(readBottomSystemBarPx(view))
    }

    DisposableEffect(view, density) {
        // 信号源：Dialog 自己的 decorView。只当「inset 变了」的通知，不从中取值。
        val signalTarget: View = view.rootView

        // density 变化（字体 / 显示大小调整）时 dp→px 的换算结果会变，而系统未必会因此
        // 重新派发 inset；这里无条件校准一次，避免保底值停在旧密度下的像素数。
        val recalibrated = readBottomReservedPx(view, density)
        if (recalibrated != reservedPx) reservedPx = recalibrated
        val recalibratedBar = readBottomSystemBarPx(view)
        if (recalibratedBar != systemBarPx) systemBarPx = recalibratedBar

        ViewCompat.setOnApplyWindowInsetsListener(signalTarget) { _, insets ->
            // ★ 取值一律回到宿主 Activity 的全屏 rootWindowInsets（红线 L1）。
            val next = readBottomReservedPx(view, density)
            // ★ 允许下降：只判「变了没有」，不做单调守卫（红线 L3）。
            if (next != reservedPx) reservedPx = next
            val nextBar = readBottomSystemBarPx(view)
            if (nextBar != systemBarPx) systemBarPx = nextBar
            // ★ 原样返回，绝不 CONSUMED（红线 L2）。
            insets
        }

        // view 首次 attach 后才拿得到宿主 Activity 的 rootWindowInsets，这里补读一次，
        // 消除「首帧按保底值定位、attach 后不再纠正」的偏差。
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val next = readBottomReservedPx(view, density)
                if (next != reservedPx) reservedPx = next
                val nextBar = readBottomSystemBarPx(view)
                if (nextBar != systemBarPx) systemBarPx = nextBar
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(attachListener)

        // 主动请求一次派发：若 inset 在我们注册之前就已稳定，系统不会再自发回调。
        ViewCompat.requestApplyInsets(signalTarget)

        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(signalTarget, null)
            view.removeOnAttachStateChangeListener(attachListener)
        }
    }

    return BottomInsetReadings(reservedPx = reservedPx, systemBarPx = systemBarPx)
}

/**
 * [rememberBottomReservedPx] 的两个读数。
 *
 * @property reservedPx 栏要为系统底部让出的总高（`max(navigationBars, systemGestures,
 *   tappableElement)` + 手势保底）。决定**栏总高**与页面底部留白。
 * @property systemBarPx 其中真正有系统像素的那一段（只有 `navigationBars`）。
 *   决定**内容区下方**至少要留多少 —— 差值挪到内容区上方，见 [UfiBottomDock]。
 */
private data class BottomInsetReadings(val reservedPx: Int, val systemBarPx: Int)

/**
 * 从子组合的 `LocalView` 出发解析出承载它的对话框 [Window]。
 *
 * 对话框的 `Window` 在 view 树里的挂载位置**随 Compose 版本 / ROM 变化**：可能是
 * 内容 ComposeView 自身、它的 `context`、它的直接 `parent`，也可能要再往上翻几层容器。
 * 只探一个点位必然在某些机型上漏掉，因此四个点位全试 + 父链上溯
 * （与 `UfiDialogShell` 中已在真机验证过的解析范式保持一致）。
 *
 * @return 解析到的对话框窗口；若宿主不是 Dialog（例如 `Popup`）则为 `null`。
 */
private fun resolveDialogWindow(view: View): Window? {
    (view as? DialogWindowProvider)?.window?.let { return it }
    (view.context as? DialogWindowProvider)?.window?.let { return it }
    (view.parent as? DialogWindowProvider)?.window?.let { return it }
    var parent: ViewParent? = view.parent
    while (parent != null) {
        (parent as? DialogWindowProvider)?.window?.let { return it }
        parent = (parent as? View)?.parent
    }
    return null
}

/**
 * 给承载胶囊的**对话框窗口**施加 / 清除「**窗口内**背景模糊」。
 *
 * ## 现在的承载方式：Dialog + 窗口内真模糊
 * 胶囊由 `MainNavGraph` 用 `Dialog(usePlatformDefaultWidth = false, …)` 承载。
 * 这样才拿得到 [Window] 对象 —— `Window.setBackgroundBlurRadius` 是**唯一**「只糊窗口
 * 自身矩形内」的公开 API，也是胶囊要的毛玻璃效果的唯一正解。
 *
 * ## ⚠ `FLAG_BLUR_BEHIND` 对胶囊**永久禁用**（真机 bug 复盘，改动前必读）
 * Android 12 提供的是**两种语义完全不同**的窗口模糊：
 *
 * | API | 模糊范围 | 适用场景 |
 * |---|---|---|
 * | `FLAG_BLUR_BEHIND` + `LayoutParams.setBlurBehindRadius` | **窗口背后的整个屏幕** | 对话框景深 |
 * | `Window.setBackgroundBlurRadius` | **仅窗口自身矩形内** | 毛玻璃浮层 |
 *
 * 官方文档原文：*"Note the difference with `WindowManager.LayoutParams#setBlurBehindRadius`,
 * which blurs **the whole screen** behind the window. Background blur blurs the screen behind
 * only **within the bounds of the window**."*
 *
 * 旧实现给胶囊窗口打的是 blur-behind —— 于是**整屏页面内容都被糊掉**，正是真机截图里
 * 「胶囊背后一整页全是模糊」的根因。它与窗口是不是 wrap-content **毫无关系**：
 * blur-behind 的作用范围由系统 Dimmer 层决定，恒为整个容器，收窄窗口尺寸一点用都没有。
 * 胶囊是**常驻**浮层（不是会关掉的对话框），整屏糊掉等于整个 App 不能用 ——
 * 因此这条路对胶囊**永久封死**，有回归护栏测试盯着（见 `CapsuleRegressionGuardTest`）。
 * 全屏对话框的景深另说，那是 `UfiDialogShell` 的事，两者不要互相抄。
 *
 * ## ★ F2 命门：必须自己给窗口一个**非透明**背景 drawable
 * `setBackgroundBlurRadius` 的实现依赖 `DecorView` 的 `BackgroundBlurDrawable`：
 * 系统用**窗口背景 drawable 的形状**（含圆角、alpha 蒙版）来裁剪模糊区域。
 * 而 Compose 的 `DialogWrapper.show()` 会把窗口背景重置成**全透明**，于是
 * 「模糊区域 = 空」，所有 API 调用都成功、屏幕上却一点模糊都没有（静默失效）。
 *
 * 所以这里主动 `setBackgroundDrawable` 一个圆角矩形：
 * - [GradientDrawable] 提供药丸圆角（[CapsuleWindowMetrics.cornerRadiusPx]）与一层
 *   极低 alpha 的底色（[CAPSULE_WINDOW_TINT_ALPHA]）——「定形」而不「抢色」，
 *   真正的玻璃质感仍由 Compose 侧 [UfiCapsuleTabBar] 绘制；
 * - 窗口背景 drawable 不再需要任何四边内缩：`CapsuleWindowMetrics.insetPx`（恒等于已删除的
 *   `CAPSULE_SHADOW_ROOM`，恒 0）与 [InsetDrawable] 内缩包裹都已退休，drawable 直接以
 *   窗口大小绘制（见下方 `capsuleBackground`）。
 *
 * ## 触摸穿透红线
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 必须打上：前者让窗口不抢焦点（不弹输入法、
 * 不吃返回键），后者让**窗口矩形之外**的触摸继续下发给下方页面。底栏是常驻浮层，
 * 一旦漏掉这两个 flag 就会整块吞掉页面点击 —— 产品红线。
 *
 * ★ 2026-09-24（贴底通栏低栏）：窗口已改成 `MATCH_PARENT` 宽 + `gravity=BOTTOM` + `y=0`，
 * 「窗口矩形」从「紧贴胶囊的小窗」变成「屏幕底部整条带子」。`FLAG_NOT_TOUCH_MODAL` 的语义
 * **一字未变**，只是它现在放行的是**栏以上**的整个页面区域（那才是需要穿透的地方）；
 * 栏本身不透明可点，这是预期。二级页要让整条带子都穿透，靠的是 [CapsuleTouchGate]
 * 给窗口补 `FLAG_NOT_TOUCHABLE` —— 窗口变宽之后这条 gate 比改造前更重要（§5.4）。
 *
 * ## ★ 窗口尺寸**固定**，不再跟随展开进度缩放（去模糊重构，改动前必读）
 * 历史上窗口必须跟着胶囊缩，唯一理由是「模糊区 = 窗口矩形」：窗口不缩就会在缩小的胶囊
 * 四周露出一圈裸模糊。为此先后试过两版，都被真机否掉：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 根节点 `Modifier.layout` 汇报「自然尺寸 × scale」，靠 wrap-content 自动 resize | 窗口确实会缩，但逼着子树每帧重测 → 内部元素错位 + 动画卡顿 |
 * | 宿主每帧 `setLayout(naturalSize × capsuleScaleOf(progress))` | 每帧触发 WindowManager relayout → 动画卡顿；窗口夹小后回写 naturalSize 造成「越缩越小最终消失」的自激塌缩 |
 *
 * 既然窗口级真模糊本身已被移除（见下方 `runCatching` 末尾），这条约束就不复存在。
 * 本函数因此只在「初始 + 重放」时把窗口尺寸设一次：naturalSize 已测得则用之，
 * 否则退化 `WRAP_CONTENT` 由内容撑开。收起/展开的视觉缩放全部由 [UfiCapsuleTabBar]
 * 根 Box 的 `graphicsLayer`（纯绘制期变换）承担，动画期间**零窗口 relayout**。
 *
 * ## ★ 底部截断 / 手势条白边（真机 bug 复盘）
 * 旧实现让 `Dialog` 用 `decorFitsSystemWindows = true`，窗口被夹在系统栏安全区里。
 * 后果有两个，用户看到的是同一件事：
 * 1. 平台把手势小白条那条带子当作**本窗口**的装饰区，用窗口的 `navigationBarColor`
 *    （从 `android:Theme.Material.Light.Dialog` 继承来的不透明底色）填成一条**白边**；
 * 2. `gravity = BOTTOM` 的参考系变成安全区底边，胶囊被整体顶高，加上那条白边压在下方，
 *    视觉上就像**胶囊被屏幕下边框截断**。
 *
 * 修复三件套（缺一不可）：
 * - 调用方改 `decorFitsSystemWindows = false`（见 `MainNavGraph`）；
 * - 本函数加 `FLAG_LAYOUT_NO_LIMITS` + `navigationBarColor = TRANSPARENT`
 *   + `isNavigationBarContrastEnforced = false` + `WindowCompat.setDecorFitsSystemWindows(false)`，
 *   让窗口能铺到屏幕真实底边且不再被平台补底色；
 * - 底部系统预留区高度改由 [CapsuleBlurHost] 经 [rememberBottomReservedPx] /
 *   [readBottomReservedPx]（宿主 Activity 的全屏 rootWindowInsets）读出来。
 *   ★ 2026-09-24（贴底通栏低栏）：这份高度**不再加进窗口 y**（y 已恒 0），
 *   而是经 [LocalCapsuleBottomReserved] 下发给栏内容，由栏内一段只有底色的 Spacer 让位；
 *   背景则一路铺到屏幕真实底边（手势小白条浮在栏底色之上）。取值口径一字未改，
 *   变的只是消费方，见 `docs/bottom-dock-migration-plan.md` §3。
 *
 * 资源侧还在 `UfiDialogWindowTheme` 里预置了同样的透明导航栏配置，
 * 避免 Dialog 每次重建时在本函数生效前闪一帧白边。
 *
 * ## ★ 黑底回归修复：背景为 null 时必须显式 `setFormat(TRANSLUCENT)`
 * 上一版为消除胶囊外圈 1px 细线，把运行时 `setBackgroundDrawable` 改成 `null`、
 * 主题 `windowBackground` 改成 `@null`（同时保留 `windowFrame=@null`）。这虽去掉了边，
 * 却也抹掉了窗口「表面透明」的显式声明：主题的 `windowIsTranslucent=true` 在这类
 * 「无背景 drawable」组合下部分 ROM 不生效，窗口 Surface 退回不透明黑色——用户截图里
 * 胶囊底部整块纯黑、盖住下方红色按钮即此。
 *
 * 修复手段：清掉背景后立刻 `dialogWindow.setFormat(PixelFormat.TRANSLUCENT)`，把像素格式
 * 钉死成半透明。它只认 Surface 的格式、与背景 drawable 是否为 null 无关，因此即便背景
 * 为 null，窗口仍是透出底层页面的透明浮层，且不再描 1px 边——没有把 `windowBackground`
 * 改回 transparent、也没有新增任何背景层（见下方 `runCatching` 内的调用点）。
 *
 * ## Popup 兜底
 * 若调用方退回 `Popup`（没有 `Window` 对象），[resolveDialogWindow] 返回 null，
 * 本函数**放弃真模糊**返回 `false`，由 [UfiCapsuleTabBar] 渲染视觉磨砂降级 ——
 * **绝不**退回 blur-behind。
 *
 * ## ★ 幂等契约（2026-09-05，返回时阴影闪烁）
 * 本函数会被重放三次（见 [CapsuleBlurHost]），因此**每一处写入都必须带相等性守卫**：
 * LayoutParams 走 [applyCapsuleWindowParams] 的单次整体赋值，其余（导航栏底色 /
 * elevation / padding / 背景 drawable / 像素格式 / decorFits）各自比对当前值。
 * 「没人改过就一个字节都不写」是这里的不变量 —— 同值重复写照样触发 relayout。
 *
 * @param view             Dialog 子组合里的 `LocalView`。
 * @param enabled          true 尝试施加模糊，false 清除模糊。即使系统最终不渲染模糊，
 *                         调用也会"成功"返回；调用方需结合 [WindowManager.isCrossWindowBlurEnabled]
 *                         判断是否真的生效。
 * @param metrics          预先在组合里算好的窗口度量（见 [CapsuleWindowMetrics]）。
 * @param naturalSize      胶囊根 Box 的固有尺寸，转交 [applyCapsuleWindowParams] 钉窗口宽高。
 * @param decorFitsApplied `decorFitsSystemWindows` 的一次性闸门（宿主持有，与窗口同寿命）：
 *                         该 setter 没有 getter 可比对，而每次调用都会强制一次 inset 重派发
 *                         + traversal，所以只在闸门未合时写一次。
 * @return 模糊是否**成功提交到了窗口**（不代表系统一定会渲染出模糊，见
 *         [LocalUfiCapsuleRealBlurActive] 的说明）。清除操作恒返回 false。
 */
@Suppress("DEPRECATION") // Window.navigationBarColor / isNavigationBarContrastEnforced：见函数体内说明
private fun applyWindowBlur(
    view: View,
    enabled: Boolean,
    metrics: CapsuleWindowMetrics,
    naturalSize: MutableState<IntSize>,
    decorFitsApplied: MutableState<Boolean>,
    interactive: Boolean
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

    // ── 路径 1：Dialog（有真正的 Window 对象）──
    val dialogWindow: Window? = resolveDialogWindow(view)
    if (dialogWindow != null) {
        // ═══ 红线 + 定位：LayoutParams 的**唯一整体写入点**，无条件先执行 ═══
        // 触摸穿透（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL）、不变暗（清 FLAG_DIM_BEHIND
        // + dimAmount 归零）、越界布局（FLAG_LAYOUT_NO_LIMITS）、底部锚定（gravity = BOTTOM
        // + y = 0）、窗口尺寸（MATCH_PARENT 宽 / 内容高）、刘海模式全部合并进
        // [applyCapsuleWindowParams] 的一次赋值，且写前逐字段比对、相等即跳过 ——
        // 重放不再制造 relayout（见该函数 KDoc）。
        //
        // 它排在 runCatching **之外**：触摸穿透是产品红线，绝不依赖下方「尽力而为、
        // 个别 ROM 可能抛异常」的装饰配置；后者整段失败也不会退化成吞点击的模态窗。
        applyCapsuleWindowParams(dialogWindow, naturalSize, interactive)

        // ── 装饰（导航栏底色 / 背景 / 阴影）：尽力而为，异常则降级 ──
        return runCatching {
            val decor: View? = dialogWindow.decorView

            // 导航栏底色透明 + 关闭对比度强制：否则平台会给半透明导航栏补一层浅色 scrim，
            // 那正是真机看到的「小白条位置的白色遮罩」。两个 setter 都有 getter，
            // 因此直接用「值相等则跳过」守卫 —— 它们内部会 updateColorViews，重复写等于
            // 每次重放都让 decor 重算一遍色块。
            // （两个 setter 在 API 35 起被标记 deprecated —— 目标 SDK ≥ 35 时系统强制
            //   edge-to-edge、调用变成 no-op；但 minSdk 是 31，Android 12~14 的真机仍然
            //   需要它们，所以保留并在函数级 @Suppress("DEPRECATION")。）
            if (dialogWindow.navigationBarColor != Color.TRANSPARENT) {
                dialogWindow.navigationBarColor = Color.TRANSPARENT
            }
            if (dialogWindow.isNavigationBarContrastEnforced) {
                dialogWindow.isNavigationBarContrastEnforced = false
            }

            // 手势小白条的明暗。栏贴底之后小白条压在**本窗口**画的栏底色上，
            // 而小白条不会自己按背后像素反色（§5.3 的原假设已被真机证伪，详见
            // [CapsuleWindowMetrics.lightNavHandleSurface]）—— 必须显式告诉窗口
            // 「我这块背景是浅的」，平台才会把小白条画成深色。
            //
            // 用 WindowInsetsControllerCompat 而不是直接改 decor 的 systemUiVisibility：
            // 后者在 API 30+ 已废弃，且与 enableEdgeToEdge 用的那套 appearance 位不是同一条路径。
            // 这里同样"值相等则跳过"：controller 的 setter 会走 InsetsController.setSystemBarsAppearance，
            // 无条件写会在每次重放里多派发一次。
            if (decor != null) {
                val controller = WindowInsetsControllerCompat(dialogWindow, decor)
                if (controller.isAppearanceLightNavigationBars != metrics.lightNavHandleSurface) {
                    controller.isAppearanceLightNavigationBars = metrics.lightNavHandleSurface
                }
            }

            // 窗口自己不吃 inset：栏要铺到屏幕真实底边，安全区由栏内 Spacer 让位
            // （2026-09-24 前是「窗口 y 偏移精确控制」，见 applyCapsuleWindowParams 的 y 说明）。
            // ★ 平台没有对应 getter，而 Window.setDecorFitsSystemWindows 会走到
            //   ViewRootImpl.setOnContentApplyWindowInsetsListener → requestFitSystemWindows()，
            //   即**每次调用都强制一次 inset 重派发 + traversal**（还会顺带回调
            //   rememberBottomReservedPx 的监听）。因此用宿主侧的一次性闸门锁成只写一次。
            if (!decorFitsApplied.value) {
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                decorFitsApplied.value = true
            }

            // 剥掉 Compose / Material 主题给 Dialog 注入的默认阴影：默认背景的 elevation
            // shadow 会投出底部"黑色阴影团"。decorView.elevation 是 Window.setElevation 的
            // 可观测出口（PhoneWindow 内部就是转发给它），据此做相等性守卫 ——
            // setElevation 会重设 surfaceInsets 并派发 attributes，重复写就是一次 relayout。
            if (decor != null && decor.elevation != 0f) {
                dialogWindow.setElevation(0f)
            }
            if (decor != null && (decor.paddingLeft != 0 || decor.paddingTop != 0 ||
                    decor.paddingRight != 0 || decor.paddingBottom != 0)
            ) {
                decor.setPadding(0, 0, 0, 0)
            }

            // ★ F2 命门：覆盖 Compose 注入的全透明窗口底，给模糊一个"落脚的形状"。
            // capsuleBackground 用**同一个 drawable 实例**同时赋给 window 与 decorView，
            // 双保险对抗部分 ROM 在窗口属性重放时只回滚 theme windowBackground、
            // 而忽略 decorView 直接赋值的差异（修复方向 #2）。
            // 用户关闭模糊（enabled = false）时，窗口背景 drawable 必须换成**全透明**，
            // 否则会残留一层灰（设计文档 T03）；此时 Compose 侧走纯色实心底覆盖之。
            //
            // 两个分支都带相等性守卫：drawable 换新会让 decor 重新 invalidate + 重算 outline，
            // 而三次重放里通常只有第一次真的需要改（后两次是「查有没有被 Compose 改回去」）。
            if (enabled) {
                val existing = decor?.background as? GradientDrawable
                val matched = existing != null &&
                    existing.cornerRadius == metrics.cornerRadiusPx &&
                    existing.color?.defaultColor == metrics.tintArgb
                if (!matched) {
                    val capsuleBackground = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = metrics.cornerRadiusPx
                        setColor(metrics.tintArgb)
                    }
                    dialogWindow.setBackgroundDrawable(capsuleBackground)
                    decor?.background = capsuleBackground
                }
            } else if (decor == null || decor.background != null) {
                // 把窗口背景 drawable 清成 null（而非 ColorDrawable / transparent drawable），
                // 避免系统对透明背景 drawable 的形状描出胶囊外圈 1px 细线。
                dialogWindow.setBackgroundDrawable(null)
                decor?.background = null
            }

            // ★ 关键修复：背景清 null 后必须显式把窗口像素格式钉成 TRANSLUCENT。
            // 仅靠主题的 windowIsTranslucent=true 在「无背景 drawable」这种组合下，部分 ROM
            // 不会生效，窗口 Surface 会退回到不透明黑色——用户截图里胶囊底部的纯黑矩形、
            // 盖住下方红色按钮正是这个。setFormat 只决定 Surface 像素格式，与背景是否为
            // null 无关，因此即使背景为 null，窗口也仍是透出底层页面的透明浮层。
            //
            // 不并进 applyCapsuleWindowParams：必须走 Window.setFormat 才会置上平台内部的
            // mHaveWindowFormat（直接写 LayoutParams.format 不会），那是本修复的命门。
            // 守卫读 attributes.format —— setFormat 正是写它，所以是精确的幂等判据。
            if (dialogWindow.attributes.format != PixelFormat.TRANSLUCENT) {
                dialogWindow.setFormat(PixelFormat.TRANSLUCENT)
            }

            // ★ 窗口级真模糊已被**彻底移除**：本函数不再向窗口提交任何模糊半径。
            // 历史方案（窗口内背景模糊 + 每帧按展开进度缩放窗口矩形）在真机上带来
            // 动画卡顿与胶囊自激塌缩，收益远小于代价；胶囊的玻璃观感改由 Compose 侧
            // [UfiCapsuleTabBar] 的 frosted 渐变背景层（渐变底 + 顶部高光 + 渐变描边）承担。
            // 因此这里恒返回 false：**未施加任何真模糊**，下游一律走 frosted 分支。
            enabled && false
        }.getOrDefault(false)
    }

    // ── 路径 2：Popup（无 Window 对象）──
    // 公开 API 无法只糊窗口内；**绝不**退回整屏模糊。
    // 直接放弃真模糊，由 UfiCapsuleTabBar 渲染视觉磨砂。
    return false
}

/**
 * 为悬浮胶囊探测 / 施加**窗口内背景模糊**的宿主，并向下游广播「真模糊是否生效」。
 *
 * ## 为什么模糊必须放在独立窗口里
 * Compose 内容层没有 CSS `backdrop-filter` 的等价物：`Modifier.graphicsLayer { renderEffect }`
 * 模糊的是**图层自身绘制的内容**。胶囊背景是半透明纯色，被模糊后只会变成一坨灰泥，
 * 而不会模糊它**背后**的页面。能模糊「窗口背后已合成画面」的只有窗口级 API（API 31+），
 * 因此胶囊必须**处在自己的窗口**里。
 *
 * ## 承载方式：Dialog（不是 Popup）
 * 调用方（`MainNavGraph`）用
 * `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, …))` 承载胶囊：
 * 只有 Dialog 才有 [Window] 对象，才够得着**只糊窗口内**的
 * `Window.setBackgroundBlurRadius`；Popup 由 `WindowManager.addView` 直接挂载，
 * 没有 `Window` / `DecorView`，公开 API 无从调用（同名 `LayoutParams` setter 是 `@hide`）。
 *
 * `usePlatformDefaultWidth = false` 是关键：它**解除**平台默认宽度约束，窗口尺寸由内容
 * 决定；胶囊内容是 wrap-content，于是拿到一个紧贴胶囊的小窗（模糊只限胶囊、窗外点击穿透）。
 * 若误写成 `true`，窗口会被撑成平台默认全宽 —— 全宽模糊带 + 吞掉整条底部点击，正是要避免的事故。
 *
 * 触摸穿透（产品红线）由 [applyWindowBlur] 手工补上
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 保证；这是换 Dialog 承载必须偿还的代价，
 * 因此它被放在 `runCatching` 的**第一条**，任何后续步骤失败都不影响它已经生效。
 *
 * ## 施加时机：立即 + `post` + `delay(100ms)` 三次重放
 * Compose 的 `DialogWrapper.show()` 会在组合提交之后**重放窗口属性**并把窗口背景重置成
 * 全透明，把我们刚设好的 gravity / 背景 drawable / 模糊半径一起抹掉。
 * 这与 `UfiDialogShell` 踩过的坑是同一个，解法也一样：施加一次之后，在
 * `view.post {}` 与 `delay(100L)` 各重放一次，确保最终态是我们的。
 *
 * ★ 三次重放必须是**幂等**的（2026-09-05）：它们落在三个不同的帧上，若每次都无条件重写
 * 窗口属性，就是三次 WindowManager relayout —— 而胶囊 Dialog 恰好在 pop 那一刻新挂，
 * 这三次正好砸在返回动画里，表现为「底部阴影闪烁 / 抽搐」。因此
 * [applyWindowBlur] 的每一处写入都带相等性守卫（LayoutParams 更是收成一次整体赋值），
 * 重放只在「真被 DialogWrapper 改回去了」时才落笔。
 * ⚠ 不许改用「延后挂载」来回避：那会让胶囊高亮与实际页面错位（已完整回退，见 `MainNavGraph`）。
 *
 * ## 平台限制（重要）
 * 窗口模糊是**尽力而为**的：省电模式、开发者选项「关闭窗口模糊」、以及相当一部分
 * OEM ROM（`ro.surface_flinger.supports_background_blur=false`）会让它**静默失效** ——
 * 所有 API 调用都返回成功，但屏幕上没有任何模糊。
 *
 * 因此本组件不「提交完就当成功」，而是：
 * 1. 用 [WindowManager.isCrossWindowBlurEnabled] 查询系统能力；
 * 2. 用 `addCrossWindowBlurEnabledListener` 实时跟随（用户中途开省电模式会即时降级）；
 * 3. 把「真模糊是否生效」通过 [LocalUfiCapsuleRealBlurActive] 下发给 [UfiCapsuleTabBar]，
 *    由它在**真模糊轻透 tint** 与**视觉磨砂降级**两套背景之间切换。
 *
 * 这样无论设备支不支持，用户看到的都是「干净的磨砂玻璃胶囊」，不会再出现「一块半透明灰泥」，
 * 更不会出现「整页被糊」。
 *
 * @param content 胶囊内容（由调用方放在 `Dialog` 内部）。
 */
@Composable
fun CapsuleBlurHost(content: @Composable () -> Unit) {
    val view: View = LocalView.current
    val density = LocalDensity.current
    val palette = LocalResolvedPalette.current

    // ── 用户可调的胶囊视觉参数（设置 → 外观 → 胶囊尺寸）──
    //
    // ★ 为什么在这里**内部自建** ThemeManager 而不是加一个参数：
    //   `CapsuleBlurHost(content: @Composable () -> Unit)` 的公开签名被回归护栏
    //   `publicApi_signatures_mustRemainStable` 锁死（红线 R7），不得新增形参。
    //
    // 用 `applicationContext` 而非 `LocalContext.current`：本组合活在 Dialog 的
    // ContextThemeWrapper 里，且 ThemeManager 会注册 SharedPreferences 监听 ——
    // 持有 Activity 级 Context 会在 Dialog 反复重建时留下悬挂引用。
    // `observeExternal = true`：设置页（另一个 ThemeManager 实例）写盘后，
    // 本实例必须经 prefListener 收到变更，滑块才能实时联动。
    val appContext = LocalContext.current.applicationContext
    val themeManager = remember { ThemeManager(appContext, observeExternal = true) }
    // 反注册 prefs 监听：Dialog 宿主会反复重建，不解就每重建一次多挂一个监听。
    DisposableEffect(themeManager) { onDispose { themeManager.dispose() } }
    // ★ 已删除（2026-09-24，迁移阶段 2.1）：`val capsuleLift by themeManager.capsuleLiftDp…`
    //   与由它构造的局部常量 `CAPSULE_LIFT`（大写名字是为了让 `bottomOffsetPx` 的表达式
    //   与护栏 `capsuleLift_mustBeWired` 对得上）。抬高量是**悬浮**形态的审美参数；
    //   贴底通栏的 y 恒 0，抬高与「贴底」直接矛盾，整条链路退休。
    //   `ThemeManager.capsuleLiftDp` 这个设置项本身仍在（清理属于阶段 3），只是不再有消费者。
    val capsuleCorner by themeManager.capsuleCornerDp.collectAsState()

    // 底部系统预留区（系统栏 ∪ 手势热区）高度。
    //
    // ★ 消费方已变（2026-09-24）：窗口 y 恒 0，这段高度不再加进窗口偏移，而是
    //   ① 经 [LocalCapsuleBottomReserved] 下发给栏内容，由栏内一段只有底色的 Spacer 让位；
    //   ② 计入 [CapsuleInsetHolder] 发布的页面底部 inset（见下方 capsuleBottomTotalDp）。
    //
    // ⚠ **取值链路一个字都不要动** —— 它是踩过「窗口位置 → 自身 inset → 再改窗口位置」
    //   自激振荡的单一真源，见 [rememberBottomReservedPx]：
    // - 监听挂 Dialog 自己的 decorView，只当「inset 变了」的信号；
    // - 取值一律走宿主 Activity 的全屏 rootWindowInsets（与本窗口位置解耦，不自激振荡）；
    // - 允许下降（旋转 / 三键切手势时预留区变小也能跟上），读不到时回落保底值而非 0。
    val bottomInsets: BottomInsetReadings = rememberBottomReservedPx(view, density)
    val reservedPx: Int = bottomInsets.reservedPx

    // dp→px 换算与取色只能在组合里做，提前算好交给非组合的 applyWindowBlur 复用。

    // 圆角：用户值优先，0（理论上不可达，MIN=10）时回退到文件级默认常量。
    val capsuleCornerRadius: Dp =
        if (capsuleCorner > 0) capsuleCorner.dp else CAPSULE_WINDOW_CORNER_RADIUS

    // key 里带 capsuleCorner：它一变就必须重算 metrics，否则 metrics 引用不变，
    // 下游以它为 key 的 DisposableEffect / LaunchedEffect 不会重跑，圆角就不会下发。
    // （原来还带 capsuleLift —— 那是窗口 y 偏移的驱动项，已随 CAPSULE_LIFT 一起退休。）
    val metrics: CapsuleWindowMetrics = remember(
        density, palette, reservedPx, capsuleCorner
    ) {
        with(density) {
            CapsuleWindowMetrics(
                cornerRadiusPx = capsuleCornerRadius.toPx(),
                tintArgb = palette.cardBg.copy(alpha = CAPSULE_WINDOW_TINT_ALPHA).toArgb(),
                bottomReservedPx = reservedPx,
                // 判据必须来自**栏实际画的那块颜色**（[dockSurfaceColor]），不是 palette.isDark ——
                // 两者在多套皮肤下不总是一致（有偏亮的深色皮肤、也有偏暗的浅色皮肤），
                // 而小白条压的是前者。0.5 是亮度中线。
                lightNavHandleSurface = dockSurfaceColor(palette).luminance() > 0.5f
            )
        }
    }

    // 系统能力：设备/ROM 此刻是否真的会渲染 cross-window blur。
    var crossWindowBlurEnabled: Boolean by remember(view) {
        mutableStateOf(queryCrossWindowBlurEnabled(view.context))
    }

    // 真模糊是否已经生效（能力 OK + 用户开关 ON + 参数成功提交）。
    var realBlurActive: Boolean by remember(view) { mutableStateOf(false) }

    // 真模糊参数是否已成功提交到窗口（不保证系统一定渲染出来，见 LocalUfiCapsuleBlurSubmitted）。
    var blurSubmitted: Boolean by remember(view) { mutableStateOf(false) }

    // 胶囊展开进度（0=收起,1=展开），由子组合 [UfiCapsuleTabBar] 通过 snapshotFlow 每帧写回。
    // 真模糊移除后宿主**不再消费**本值（窗口尺寸不再逐帧缩放）：胶囊的收起/展开缩放
    // 完全由 [UfiCapsuleTabBar] 根 Box 的 graphicsLayer 表达。这里仍保留并下发，
    // 一是为诊断/观测留一个稳定出口，二是保住 [LocalCapsuleExpandProgress] 的契约不变。
    val expandProgress = remember { mutableFloatStateOf(1f) }

    // 胶囊根 Box 的「自然（未缩放）测量尺寸」，由 [UfiCapsuleTabBar] 在其自定义 Layout 的
    // 固有测量阶段写入。[applyWindowBlur] 在「初始 + 重放」时用它给窗口设一次固定尺寸
    // （未测得时退化 WRAP_CONTENT），不再逐帧使用。初始为 0，首帧测量后即被填充。
    val naturalSize = remember { mutableStateOf(IntSize.Zero) }

    // `decorFitsSystemWindows` 的**一次性闸门**（key = view ⇒ 与窗口同生命周期）。
    //
    // 平台没有 `isDecorFitsSystemWindows` 这样的 getter，而 `Window.setDecorFitsSystemWindows`
    // 会走到 `ViewRootImpl.setOnContentApplyWindowInsetsListener` → `requestFitSystemWindows()`：
    // **每次调用都强制一次 inset 重派发 + traversal**，还会顺带触发
    // [rememberBottomReservedPx] 的监听。三次重放就是三次多余的 traversal，
    // 正是「返回时阴影抽搐」里不经过 LayoutParams 的那一路。故用本闸门锁成只写一次。
    //
    // 仅在效应里读写、不参与组合，所以不会引起重组。
    val decorFitsApplied = remember(view) { mutableStateOf(false) }

    // 胶囊此刻是否该吃触摸（宿主目的地 = 是，二级页 = 否）。见 [CapsuleTouchGate]。
    // 读成 State 才能在翻转时让下面两个效应重跑，把 FLAG_NOT_TOUCHABLE 下发到窗口。
    val capsuleInteractive: Boolean = CapsuleTouchGate.interactive.value

    // ── 底栏底部遮挡总高（跨窗口共享单例）──
    // 2026-08-08 19:47 新增（方案 A 修订版）：监控页底部内容被胶囊遮挡（88dp 硬编码不足）。
    //
    // ★ 2026-09-24（贴底通栏低栏，迁移计划 §2.3 / §7-2.3）：计算式改为「内容高 + 安全区」。
    //
    // 原式 = 未缩放固有高度 `naturalSize.height` + 底部间隙 8dp + 系统底部预留区 reservedPx。
    // 三项都是**悬浮**形态的量：固有高度是胶囊本体（不含安全区，因为安全区在窗口 y 里）、
    // 8dp 是「胶囊底边离手势热区」的安全间距、reservedPx 是被窗口 y 抬起来的那一段。
    // 通栏形态下栏**自己**就覆盖了安全区那条带子（背景铺满 + 栏内 Spacer 让位），
    // 页面要避开的正是「内容区高 + 安全区」这一整块，既没有 8dp 间隙也没有抬升。
    //
    // 高度常量复用 `UfiBottomDock` 的 [DOCK_CONTENT_MIN_HEIGHT]（58dp，§1 参数表的定稿值）
    // —— **刻意不在这里另写一个 58**：两处各写一份数字迟早漂移（这正是 CAPSULE_SHADOW_ROOM
    // 当年被收成「单一真源」的同一个理由）。
    //
    // 经 [CapsuleInsetHolder] 写跨窗口单例，主窗口根读取后 CompositionLocal 下发 ——
    // ⚠ CompositionLocal 不跨 Window，绝不能在 Dialog 内直接 provide 给页面。
    //
    // ⚠ §5.13 与 §7-2.3 的口径冲突已裁决（2026-09-24）：**取两者的较大值**。
    //   §7-2.3 写的是常量式「58 + 安全区」，§5.13 要求发布**实测总高**
    //   （fontScale 1.5× 时 `heightIn(min=58)` 会让栏长高，常量式会比真实栏矮几 dp，
    //   页面最后一行被压在栏下面）。两者并不真的矛盾：`UfiBottomDock` 的根 Column
    //   就是「内容区 + 安全区 Spacer」，常规字号下实测值**恰好等于**常量式，
    //   放大字号时实测值更大。取 max 同时满足两条，且天然兼容旧的悬浮胶囊分支
    //   （胶囊实测高 ≈50dp < 58 + 安全区，常量式胜出 = 与迁移前的页面留白一致）。
    val capsuleBottomTotalDp: Dp = remember(reservedPx, naturalSize.value.height) {
        val byConstant = DOCK_CONTENT_MIN_HEIGHT + with(density) { reservedPx.toDp() }
        val byMeasured = with(density) { naturalSize.value.height.toDp() }
        maxOf(byConstant, byMeasured)
    }
    // 写入共享单例。
    //
    // ★ 2026-09-04（返回时底部闪烁）：**测量完成前不发布**。
    //   原实现无条件写入，而首帧 naturalSize 还是 0 ⇒ 先发布一个只有
    //   「8dp 间隙 + 系统预留区」的**假值**（比真实高度矮 ~50dp），下一帧测得后再发布真值。
    //   所有读 [LocalCapsuleBottomInset] 的页面（监控/仪表盘的长列表都读它做底部留白）
    //   因此要**重新布局两次**：88dp 兜底 → 假值 → 真值。胶囊 Dialog 是在 pop 那一刻
    //   新挂的，这两次重排正好落在返回动画里，底部卡片跟着上下跳、阴影随之闪 ——
    //   跳过假值后只剩「兜底 88dp → 真值」一次。
    //
    // ★ 这道「测量完成前不发布」的闸门**保留**（护栏 `capsuleInset_mustNotPublishBeforeMeasured`
    //   守的就是它，语义不变：栏还没测出来就别让页面按一个半成品值重排）。
    //   注意计算式本身已不依赖 naturalSize，闸门现在只起「等栏真的立起来」的时序作用。
    //   ★ 供数方已补齐（2026-09-24）：`UfiBottomDock` 的根 Column 挂了 `onSizeChanged`
    //     回写 [LocalCapsuleNaturalSize]。此前只有 `UfiCapsuleTabBar` 写，开关切到通栏后
    //     这道闸门会永不放行、所有页面停在 88dp 兜底值。
    LaunchedEffect(capsuleBottomTotalDp, naturalSize.value.height) {
        if (naturalSize.value.height > 0) {
            CapsuleInsetHolder.bottomInset.value = capsuleBottomTotalDp
        }
    }
    // 胶囊 Dialog 卸载（隐藏 / 离开主 Tab）时写回默认值，避免残留旧值。
    DisposableEffect(Unit) {
        onDispose { CapsuleInsetHolder.bottomInset.value = DEFAULT_CAPSULE_BOTTOM_INSET }
    }

    // ── 跟随系统 blur 开关：省电模式 / 开发者选项 会在运行期动态切换 ──
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            crossWindowBlurEnabled = false
            return@DisposableEffect onDispose { }
        }
        val windowManager = view.context
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@DisposableEffect onDispose { }

        val listener = Consumer<Boolean> { enabled ->
            crossWindowBlurEnabled = enabled == true
        }
        val registered = runCatching {
            windowManager.addCrossWindowBlurEnabledListener(listener)
        }.isSuccess
        // 注册成功时系统会立刻回调一次当前值；失败则退回一次性查询。
        if (!registered) {
            crossWindowBlurEnabled = queryCrossWindowBlurEnabled(view.context)
        }

        onDispose {
            if (registered) {
                runCatching { windowManager.removeCrossWindowBlurEnabledListener(listener) }
            }
        }
    }

    // ── 施加 / 清除窗口属性（立即 + post 两次）──
    // Compose 的 DialogWrapper.show() 会在组合提交后重放窗口属性、并把窗口背景重置成
    // 全透明，把 flags / gravity / 背景 drawable 一起抹掉；因此施加一次不够，
    // 必须在下一个消息循环再重放一次（第三次重放在下面的 LaunchedEffect 里）。
    //
    // ★ 2026-09-05（返回时阴影闪烁 / 抽搐）：重放本身现在是**幂等**的。
    //   三次重放落在三个不同的帧上，旧实现每次都无条件重写整套窗口属性（addFlags /
    //   clearFlags / setDimAmount / setLayout / attributes= / setElevation / setFormat 全都
    //   无条件派发），于是 pop 期间稳定产生 3 次 WindowManager relayout，每次都重算 decor
    //   布局并重建窗口 Surface 的投影区 —— 那就是「返回时底部阴影闪一下」。
    //   现在 LayoutParams 收成 [applyCapsuleWindowParams] 的一次整体赋值 + 相等则跳过，
    //   装饰项（导航栏底色 / elevation / 背景 drawable / 像素格式 / decorFits）逐个带守卫，
    //   所以后两次重放在「没人改过」时是纯读取：零派发、零 relayout。
    //   ⚠ 不得把重放改成延迟挂载来规避（那会让胶囊高亮与实际页面错位，已完整回退）。
    //
    // ⚠ key 里**不带**展开进度：窗口尺寸不再随收起/展开逐帧缩放（真模糊已移除，
    // 那套「每帧 setLayout」的方案连同它的卡顿与自激塌缩一并删掉了）。这里只负责
    // flags / gravity / 底边锚定 / 背景 drawable 的「初始 + 重放」，
    // 动画期间完全不触发 WindowManager relayout。
    //
    // key 里显式带上 capsuleCorner：metrics 已随它重算（见其 remember key），这里再列一次
    // 是为了让「用户拖动滑块 → 窗口属性重新下发」的因果关系在源码中显式可见，
    // 也防止将来有人重构 metrics 的 key 时静默切断实时联动。
    // （原来还列了 capsuleLift —— 那是窗口 y 偏移的驱动项，2026-09-24 随 y→0 一并退休。）
    DisposableEffect(
        view, metrics, crossWindowBlurEnabled, capsuleCorner, capsuleInteractive
    ) {
        // 真模糊已移除，applyWindowBlur 恒返回 false；这里仍传 true，但 enabled=false 时
        // 窗口背景实际走 null 分支：胶囊纯透明、无定形 drawable、无系统 1px 描边，
        // 顺带完成 flags / gravity / 底边锚定的施加与重放。
        val wantBlur = false
        var disposed = false
        blurSubmitted = applyWindowBlur(
            view, wantBlur, metrics, naturalSize, decorFitsApplied, capsuleInteractive
        )
        realBlurActive = blurSubmitted && crossWindowBlurEnabled

        view.post {
            if (!disposed) {
                blurSubmitted = applyWindowBlur(
                    view, wantBlur, metrics, naturalSize, decorFitsApplied, capsuleInteractive
                )
                realBlurActive = blurSubmitted && crossWindowBlurEnabled
            }
        }

        onDispose {
            disposed = true
            applyWindowBlur(
                view, false, metrics, naturalSize, decorFitsApplied, capsuleInteractive
            )
            blurSubmitted = false
            realBlurActive = false
        }
    }

    // ── 第三次重放：延迟 100ms ──
    // 与 UfiDialogShell 同源的经验值：post 之后仍可能被 DialogWrapper 的后续
    // show()/attributes 重放覆盖，延迟一拍再写一次才留得住。
    // LaunchedEffect 在 dispose 时自动取消，不会有"清除后又被重新点亮"的竞态。
    // 同样不以展开进度为 key（理由见上方 DisposableEffect）。
    // 同样带上 capsuleCorner（理由见上方 DisposableEffect）。
    //
    // ★ 这一拍恰好落在返回动画中段，是「阴影闪烁」最刺眼的一次。现在它只做校验：
    //   属性没被 DialogWrapper 改回去时全部命中相等性守卫，不写、不 relayout。
    LaunchedEffect(
        view, metrics, crossWindowBlurEnabled, capsuleCorner, capsuleInteractive
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val wantBlur = false
        delay(100L)
        blurSubmitted = applyWindowBlur(
            view, wantBlur, metrics, naturalSize, decorFitsApplied, capsuleInteractive
        )
        realBlurActive = blurSubmitted && crossWindowBlurEnabled
    }

    // ★ 已删除：「每帧 setLayout 让窗口跟随展开进度缩放」的 LaunchedEffect。
    // 它服务的唯一目的是让**窗口内背景模糊**的模糊区（= 窗口矩形）跟着胶囊一起缩；
    // 真模糊移除后它彻底失去意义，而代价却很实在：每帧触发 WindowManager relayout
    // 导致动画卡顿，且窗口被夹小后曾引发胶囊「越缩越小最终消失」的自激塌缩。
    // 现在窗口尺寸只在 [applyWindowBlur] 的「初始 + 重放」时各设一次
    // （naturalSize 已测得则用之，否则 WRAP_CONTENT），不再逐帧变化；
    // 胶囊的收起/展开缩放全部由根 Box 的 graphicsLayer（纯绘制期变换）表达。

    CompositionLocalProvider(
        LocalUfiCapsuleRealBlurActive provides realBlurActive,
        LocalUfiCapsuleBlurSubmitted provides blurSubmitted,
        LocalUfiCapsuleBlurCapability provides crossWindowBlurEnabled,
        LocalCapsuleExpandProgress provides expandProgress,
        LocalCapsuleNaturalSize provides naturalSize,
        // 下发**已经算好**的那份安全区高度，让通栏低栏自己不必再读一遍 WindowInsets。
        // 用 compositionLocalOf（非 static）：它每次导航栏模式切换才变一次，
        // 但变时只需要让真正读它的那一层（栏内容）重组，不该带走整棵子树。
        LocalCapsuleBottomReserved provides reservedPx,
        // 搭档读数：系统栏本体高度。两者共用同一个 inset listener，天然同步。
        LocalCapsuleBottomSystemBar provides bottomInsets.systemBarPx
    ) {
        content()
    }
}
