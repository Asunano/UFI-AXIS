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
 * 通栏低栏不设「投影预留空间」常量：栏没有四周投影，窗口为 MATCH_PARENT 宽 +
 * `gravity=BOTTOM` + `y=0`，视觉分界仅由顶边 1px 发丝线与上缘 6dp 高光承担
 * （均绘制在自身边界内，见 `UfiBottomDock`）。
 *
 * 不要重新引入恒为 0 的投影余量常量：它会被误读为「通栏仍存在投影余量」。
 */

/**
 * 手势导航下**底部系统预留区**的保底高度（**单一真源**）。
 *
 * ## 为什么需要一个下限
 * 「底部预留区」本应由 `navigationBars` / `systemGestures` 两路 inset 读出，但真机上
 * 这两路都可能报告错误值：
 * - 相当一部分 OEM ROM 在**手势导航**模式下把 `navigationBars().bottom` 报成远小于
 *   手势条实际占位的值，甚至直接报 0（其判定为「手势模式没有导航栏」）；
 * - 视图尚未 attach、或宿主 Activity 解析不到时，`getRootWindowInsets()` 返回 null，
 *   无法取得任何读数。
 *
 * 这些分支不得 `return 0`：返回 0 会使胶囊贴死屏幕底边或压在手势条上，造成跨设备
 * 定位不一致。本常量将此类场景兜到 24dp（AOSP 手势条区域的标称高度），
 * 保证胶囊始终位于手势热区之上。
 *
 * 见 [readBottomReservedPx]：所有「读不到」的分支都回落到它，**绝不回 0**。
 */
internal val GESTURE_BOTTOM_INSET_FLOOR: Dp = 24.dp

/*
 * 贴底通栏低栏不使用「胶囊安全间距 + 抬高」模型：
 * - 窗口为 MATCH_PARENT 宽 + `gravity=BOTTOM` + **`y = 0`**，栏主动铺满屏幕底部那条带子
 *   （含手势热区），底色画到屏幕真实底边，图标不压小白条依靠栏内一段 Spacer
 *   （见 `UfiBottomDock` 与 [LocalCapsuleBottomReserved]）；
 * - 因此「胶囊底边离热区多远」的问题不存在：热区那一段是栏自身的一部分，
 *   其中不放任何可点内容；
 * - 「整体抬高」与贴底形态矛盾：y ≠ 0 时，栏与屏幕底边之间会露出页面内容，破坏通栏形态。
 *
 * 安全区取值口径不变：仍由 [readBottomReservedPx] 给出
 * （`max(navigationBars, systemGestures)`、手势导航兜底 [GESTURE_BOTTOM_INSET_FLOOR]、
 * 回读宿主 Activity 的全屏 `rootWindowInsets`）。变化的只是消费方：
 * 从「窗口 y 偏移」改为「栏内 Spacer + 页面 inset」。
 *
 * `ThemeManager.capsuleLiftDp`（设置 → 外观 → 胶囊抬高）仍作为持久化设置项存在，
 * 通栏形态下没有几何消费者。
 */

/**
 * 窗口背景 drawable 的圆角半径。
 *
 * ## 当前只服务于**已停用的真模糊分支**
 * 窗口级真模糊已移除（见 [applyWindowBlur]）：运行期窗口背景恒为 `null`
 * （主题 `windowBackground=@null` + `setBackgroundDrawable(null)` + `setFormat(TRANSLUCENT)`，
 * 这是消除「胶囊外圈 1px 细线 / 黑底」的必需组合）。因此本值实际只被
 * [applyWindowBlur] 中 `enabled == true` 的**不可达分支**用于构造 [GradientDrawable]，
 * 保留该值是为了该分支若恢复时仍有正确半径。
 *
 * ## 取值：18dp
 * 取「胶囊半高 + 冗余」会把胶囊描成完整药丸；设计目标是「明显圆润但不满圆」的卡片感，
 * 故取 18dp。
 *
 * **胶囊的真实可见圆角不在这里** —— 它由内容层承担：见 `UfiCapsuleTabBar` 的
 * `CAPSULE_CORNER`（同为 18）。两处必须保持同一个数值，由回归护栏
 * `bottomInsetCorner_mustSyncTo18dp` / `bottomInsetCorner_mustNotBePill` 约束。
 */
private val CAPSULE_WINDOW_CORNER_RADIUS: Dp = 18.dp

/**
 * 窗口背景 drawable 的着色透明度。
 *
 * `Window.setBackgroundBlurRadius` **必须**有非全透明的窗口背景才能确定模糊区域
 * （见 [applyWindowBlur]）。真正的玻璃质感由 Compose 侧 [UfiCapsuleTabBar] 绘制，
 * 这里只需要「一个形状」而非「一层颜色」，因此 alpha 取 0.12f：足以让系统判定窗口背景
 * 非空、为模糊 kernel 提供作用区域，又不会与 Compose 的 tint 叠加导致浑浊。
 */
private const val CAPSULE_WINDOW_TINT_ALPHA: Float = 0.12f

/**
 * 胶囊窗口**必须置位**的 flag 组合（**单一真源**）。
 *
 * ## 为什么合成一个常量
 * 三个 flag 若分多次 `Window.addFlags` 写入（触摸穿透两个 + 越界布局一个），
 * `Window.setFlags` 在 AOSP 里**无条件**调用 `dispatchWindowAttributesChanged`
 * （没有"值没变就不写"的短路），每一次都会走到 `Dialog.onWindowAttributesChanged` →
 * `WindowManager.updateViewLayout` → `ViewRootImpl.setLayoutParams` + `scheduleTraversals`。
 * 合成一个目标值后，flags 与 gravity / y / 宽高一起在
 * [applyCapsuleWindowParams] 里**一次写定**，且写之前先比对，值相等直接返回。
 *
 * - `FLAG_NOT_FOCUSABLE`：不抢焦点（不弹输入法、不吃返回键）；
 * - `FLAG_NOT_TOUCH_MODAL`：窗口矩形之外的触摸继续下发给下方页面（产品约束）；
 * - `FLAG_LAYOUT_NO_LIMITS`：解除"窗口必须待在系统装饰之内"，让 BOTTOM gravity 的参考系
 *   变成屏幕真实底边（配套 `decorFitsSystemWindows = false`，见 [applyWindowBlur]）。
 */
private const val CAPSULE_WINDOW_FLAGS_ON: Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        // ★ ColorOS 16 上，导航条区域在本窗口存在时恒为白色。
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
        // 补一条不透明底色 —— 主题与 Kotlin 侧写入的 `navigationBarColor = TRANSPARENT`
        // 因此均不生效。这也解释了二级页（底栏隐藏但**窗口仍挂载**）白带依旧出现。
        //
        // 不得改为修改全局 `android:dialogTheme`：那会一次性影响 App 中每个 Compose 弹窗的
        // 系统栏行为。这里只给底栏这一个窗口添加，且走 [applyCapsuleWindowParams] 的
        // 同一次 attributes 写入（带相等性守卫），不额外派发。
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
    // 与 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` 配套，必须**清除**这两个 flag。
    //   平台契约：`FLAG_TRANSLUCENT_NAVIGATION` / `FLAG_TRANSLUCENT_STATUS` 一旦置位，
    //   对应的 `navigationBarColor` / `statusBarColor` 被忽略，改由系统绘制半透明渐变底。
    //   `UfiDialogWindowTheme` 的 `windowIsTranslucent = true` 会带上它们
    //   （该项是窗口级真模糊的硬性前提；模糊虽已移除，仍保留该项以防更换 parent 时被改动），
    //   因此这里必须显式清除，否则透明导航栏色仍不生效。
    //   注意：清除它们**不会**使窗口变回不透明 —— 窗口透明由 `windowIsTranslucent` +
    //   `setFormat(TRANSLUCENT)` 决定，与这两个 flag 无关。
    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS

/**
 * 「窗口级真模糊此刻是否**真的**生效」。
 *
 * ## 为什么需要它
 * 窗口模糊是一个**尽力而为**的能力：即便 API ≥ 31 且参数已成功提交到窗口，
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
 * - `false` → 绘制**多层视觉磨砂**（渐变 + 顶部高光 + 描边），保证观感仍为玻璃而非灰色块。
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
 * 本值只反映「模糊半径是否已写入窗口」，使 UI 在「系统未确认但已提交」场景下
 * 使用介于「真模糊 tint」与「纯视觉磨砂」之间的背景，避免直接回退为不透明灰色块，
 * 也避免过于透明而透出下方内容。
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
 * ## 为什么要有它
 * 通栏形态把「安全区」从**窗口 y 偏移**移入**栏内部的一段 Spacer**
 * （背景铺到屏幕真实底边、内容靠 Spacer 让位）。
 * 消费方因此变为 Compose 内容层，但取值口径必须保持不变：
 * [readBottomReservedPx] 取 `max(navigationBars, systemGestures)`、手势导航兜底
 * [GESTURE_BOTTOM_INSET_FLOOR]，并回读**宿主 Activity** 的全屏 `rootWindowInsets`
 * （读 Dialog 自身的 inset 会形成「窗口位置 → 自身 inset → 再改窗口位置」的自激振荡）。
 *
 * 因此这里下发 [CapsuleBlurHost] 中**已算好**的 `reservedPx`，而不是让栏自行读取
 * `WindowInsets`：真源只能有一个。
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
 * 否则图标会被顶得离屏幕底边过远（真机实测 ges=112px、nav=56px，相差 56px）。
 *
 * 由 [CapsuleBlurHost] 与 [LocalCapsuleBottomReserved] 在同一次 provide 里下发，
 * 两个读数共用同一个 inset listener，天然同步。
 */
val LocalCapsuleBottomSystemBar = compositionLocalOf { 0 }

/**
 * 胶囊悬浮栏底部遮挡预留高度的**读取器**（`() -> Dp`）。
 *
 * ## 为什么需要它
 * 监控页底部内容会被胶囊 Tab 栏遮挡（空态文本即被挡住）。硬编码 `88.dp` 作底部预留不足：
 * 胶囊实际视觉 footprint = 内容高度 × 收起缩放(0.86) + 底部间隙(8dp) + 系统手势/导航栏高度，
 * 该值随设备与 ROM 变化。
 *
 * 本值由 [CapsuleInsetHolder]（跨窗口共享单例）动态提供：
 * - [CapsuleBlurHost]（胶囊所在 **Dialog 窗口**）测量后写入；
 * - 主窗口根（`MainNavGraph`）把「读它的方式」`CompositionLocalProvider` 下发给所有主 Tab 页；
 * - 页面在 **layout 阶段** 调用它做底部预留（见 [ufiCapsuleBottomInset]）。
 *
 * 注意：**CompositionLocal 不跨 Window**。胶囊 Dialog 是独立窗口，其组合树与主窗口不联通，
 * 因此不能直接在 [CapsuleBlurHost] 里 provide 给页面 —— 必须经共享单例桥接（见 [CapsuleInsetHolder]）。
 *
 * ## 类型必须为 `() -> Dp` 而非 `Dp`
 * 它是 `staticCompositionLocalOf`，**没有细粒度读追踪**：`provides` 的值一变，
 * provider 以下**整棵树无条件重组**。而它的 provider 在 `MainNavGraph` 中包着整个 `NavHost`。
 * 若在组合期解引用 `CapsuleInsetHolder.bottomInset.value` 并 provide 一个 `Dp`，
 * 返回动画的时间线会受影响：
 *   1. 离开 MAIN → 胶囊 dispose → 写回兜底 88dp（见本文件 `onDispose`）；
 *   2. pop → 胶囊重挂 → 首帧 `naturalSize == 0` 不发布（护栏
 *      `capsuleInset_mustNotPublishBeforeMeasured`）；
 *   3. 第 2~3 帧测量完成 → 发布真值 → **NavHost 整子树重组 + 所有读它的页面重排**。
 * 88dp 兜底与真值差约 50dp ⇒ 既造成卡顿（整树重组落在转场头几帧），也造成位置跳变。
 *
 * 改为下发**读值方式**后：provide 的 lambda 实例被 `remember` 住、永不变
 * ⇒ static local 永不触发整树重组；快照读推迟到消费方的 layout 阶段
 * ⇒ inset 变化只失效那一个节点的测量。
 *
 * 默认返回 `88.dp`：兼容未包裹、预览及胶囊未测量的场景，保持既有兜底行为且不崩溃。
 */
val LocalCapsuleBottomInset = staticCompositionLocalOf<() -> Dp> { { DEFAULT_CAPSULE_BOTTOM_INSET } }

/**
 * 「底栏遮挡预留」在**页壳内部**真正还需要补的那一段（Dp）。**扣减 navigationBars 的唯一落点。**
 *
 * ## 为什么要减
 * [LocalCapsuleBottomInset] 发布的是**底栏自身总高**（`DOCK_CONTENT_MIN_HEIGHT` + 安全区，
 * 见 [CapsuleBlurHost] 里 `capsuleBottomTotalDp`），**含**安全区那条带子；
 * 而 `UfiScreenScaffold` 的内容 `Box` 早就有 `windowInsetsPadding(WindowInsets.navigationBars)`
 * （`UfiScaffold.kt:623`）—— 页面内容区的底边本来就已经在「屏幕底边 − navigationBars」处。
 * 两个量直接相加会**重复计算**：需要的留白是 `底栏总高 − navigationBars`，实际留白却是
 * `底栏总高`，每个主 Tab 页各多留一个 navigationBars（手势导航≈24dp、三键≈48dp）。
 *
 * ## 旧数值在悬浮形态下成立的原因
 * 悬浮胶囊形态下这一项被**窗口 `y` 抬高 30dp**（`CAPSULE_LIFT`，已移除）近似抵消：
 * 胶囊本体悬在安全区**之上**，页面要避开的是「胶囊高 + 8dp 间隙 + 抬升 + 安全区」，
 * 与页壳消费掉的那一段刚好各据一半，观感上看不出多留。通栏形态把安全区收进了栏自己
 * （背景铺到屏幕真实底边 + 栏内 Spacer 让位，见 [LocalCapsuleBottomReserved]），
 * 抬升归 0 ⇒ 抵消项消失，重复计算随即显现。该重复计算由通栏迁移引入。
 *
 * ## 为什么读到的 navigationBars 就是页壳消费掉的那一段
 * 从 Activity 根到 `UfiScaffold.kt:623` 之间**没有任何** `consumeWindowInsets`（全仓检索无匹配）
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
 * [GESTURE_BOTTOM_INSET_FLOOR]（24dp，应对把 navigationBars 报 0 的 OEM ROM）。
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
 * 用法：作为滚动列表**最后一个 item** 的 `Spacer` 修饰符，语义等价于
 * `LazyColumn(contentPadding = PaddingValues(bottom = inset + extra))`：
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
 * navigationBars 高度的留白，底部最后一行内容会被栏压住。该场景需自行补
 * `navigationBarsPadding()`，或换用不减的口径。
 *
 * 配对关系由回归护栏 `capsuleInset_mustDeductShellConsumedNavigationBars` 约束：
 * 任何一端被单独改掉（这里不再减、或页壳那句被删）都会变红。
 *
 * 全部 6 个调用点均位于页壳内容区之内：
 * `DashboardScreen:229`（壳 166..233）/ `NetworkScreen:210`（壳 92..223）/
 * `ToolsScreen:70`（壳 29..73）/ `SettingsScreen:276`（壳 45..279）/
 * `MonitorScreen:549` 与 `:762`（两者在壳 229..265 内调用的两个 Tab 内容函数里）。
 *
 * 关键点：[LocalCapsuleBottomInset] 的 `.current` 在组合期读到的只是那个**稳定的 lambda**
 * （provider 永不变 ⇒ 不触发重组），真正的快照读 `CapsuleInsetHolder.bottomInset.value`
 * 发生在 `Modifier.layout` 的 measure lambda 里 ⇒ **inset 变化只触发本节点重排，不触发重组**。
 * 这是「pop 第 2~3 帧整树重组」修复的消费侧一半，另一半在 `MainNavGraph` 的 provider。
 * `WindowInsets.navigationBars` 同理：组合期只取稳定对象，`getBottom()` 的读数压在 measure 里。
 *
 * @param extra 额外留白（如列表与底栏之间的呼吸间距）。走 `ui.theme` 的 `Spacing` 令牌，
 *              不得在调用点写裸数值。
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
     * 不设 `bottomReservedDp` 之类的跨窗口预留高度字段：底栏窗口本身已铺到屏幕底边，
     * 主窗口再补画一条安全区带子属于重复绘制。窗口底部发白的真实原因是该窗口缺少
     * `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，导致透明 navigationBarColor 不生效、
     * 系统补了一条不透明导航栏底色（见 [CAPSULE_WINDOW_FLAGS_ON]）。
     */
}

/**
 * 胶囊窗口**此刻是否应该接收触摸**的跨窗口开关（单例）。
 *
 * ## 为什么需要它
 * 胶囊挂载后不再卸载：二级页上只把内容 `scale/alpha` 缩放到 0，**窗口本身保留**。
 * 而 `FLAG_NOT_TOUCH_MODAL` 只放行窗口矩形**之外**的触摸，窗口矩形仍为胶囊的自然尺寸、
 * 仍锚定在底部，于是二级页中屏幕底部那条带子上的控件都收不到事件，
 * 表现为「可见但点击无响应，或需偏移点击才响应」。
 * 音乐播放页将进度条与播放键置于最底部，整排落在这条带子内，该问题最明显。
 *
 * 解法不是撤掉窗口（那会重新引入窗口增删导致的闪帧），而是在隐藏期给窗口补
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
 * 不保留 `bottomOffsetPx`（窗口 y 偏移）与 `insetPx`（背景 drawable 四边内缩量）：
 * 通栏形态下窗口 `y` 恒 0、内缩量恒 0，且窗口背景恒为 `null`，二者均无意义。
 *
 * 注意：`bottomReservedPx` 保留。安全区高度的消费方现为「栏内 Spacer + 页面 inset」，
 * 该字段仍是诊断与复用出口，取值口径见 [readBottomReservedPx]。
 *
 * @property cornerRadiusPx    窗口背景 drawable 的圆角半径（px）。
 * @property tintArgb          窗口背景 drawable 的填充色（ARGB），低 alpha，仅用于定型，
 *                             不参与主视觉着色。
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
     * ## 该字段不是可选的打磨项
     * 此前的假设是「手势导航下平台做 dynamic color adaptation，小白条按背后内容自动反色，
     * 不需要额外处理」。真机验证不成立：小白条的明暗只取决于**窗口的**
     * `isAppearanceLightNavigationBars`，平台不会采样背后像素。
     *
     * 悬浮胶囊形态下该问题不可见：胶囊被抬离底边，小白条压在**页面**底色上，页面位于
     * Activity 窗口中，该窗口的 appearance 已由 `MainActivity.enableEdgeToEdge()` 按**系统**
     * 夜间模式确定。贴底通栏之后，小白条压在**本 Dialog 窗口**的栏底色上，而该窗口从未
     * 设置 appearance ⇒ 取默认值「深背景」⇒ 小白条恒为白色，压在白底栏上不可见。
     *
     * 判据取 [dockSurfaceColor] 的亮度（> 0.5 即浅色），与栏实际绘制的颜色同源；
     * 同时覆盖「应用内主题与系统夜间模式不一致」这类 `enableEdgeToEdge()` 无法处理的组合。
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
 * ## 为什么必须一次写入（返回时阴影闪烁的成因）
 * [applyWindowBlur] 会被**重放三次**（立即 / `view.post` / `delay(100ms)`，见
 * [CapsuleBlurHost]），而每个窗口 setter 都会**无条件**派发一次
 * attributes 变更：`addFlags` / `clearFlags`（→ `Window.setFlags`）、`setDimAmount`、
 * `setLayout`、`attributes =`、`setElevation`、`setFormat` —— AOSP 这些方法均没有
 * 「值未变则跳过」，一律走 `dispatchWindowAttributesChanged` →
 * `Dialog.onWindowAttributesChanged` → `WindowManager.updateViewLayout` →
 * `ViewRootImpl.setLayoutParams` + `scheduleTraversals`。
 *
 * 同一次同步调用里的多次派发会被 `scheduleTraversals` 合并成一帧，所以**真正的代价来自
 * 重放**：三次重放落在三个不同的帧上 ⇒ 三次 WindowManager relayout。胶囊 Dialog 恰好是
 * 在 pop 那一刻新挂的（`MainNavGraph` 立即挂载，不得延后），于是第二次重放落在返回动画
 * 第一帧、第三次落在动画中段，每次 relayout 都会重算 decor 布局并重建窗口 Surface 的
 * 投影区 —— 表现为返回时底部阴影闪烁或抖动。
 *
 * 本函数因此：
 * 1. 把 flags / dimAmount / 宽高 / gravity / y / 刘海模式**合并成一份目标值**；
 * 2. 与窗口**当前** attributes 逐字段比对，**全等则直接 return**（零派发、零 relayout）；
 * 3. 只有真的不一致（首次施加，或 Compose 的 `DialogWrapper.show()` 把属性重放回默认值）
 *    才写一次。
 *
 * 于是重放退化为校验：未被改动则不做任何操作，被改动才修正一次。
 *
 * 注意：`format` 不在此处写入。它必须走 `Window.setFormat`（该方法会同时置上平台内部的
 * `mHaveWindowFormat`，是黑底回归修复的必要条件），在 [applyWindowBlur] 中单独带守卫调用。
 *
 * @param naturalSize 栏根 Box 的固有尺寸。**只用 height**：已测得则把窗口高度钉成它，
 *                    未测得（首帧为 0）退化 `WRAP_CONTENT` 由内容撑开。
 *                    width 分支已移除：通栏形态宽度恒 `MATCH_PARENT`
 *                    （见函数体内说明）。
 */
private fun applyCapsuleWindowParams(
    window: Window,
    naturalSize: MutableState<IntSize>,
    interactive: Boolean
) {
    // ★ width 恒为 MATCH_PARENT。
    //
    // 不得按内容宽度设置窗口：`FLAG_NOT_TOUCH_MODAL` 只放行窗口矩形**之外**的触摸，
    // 窗口一旦撑满宽度，整条底部的点击都会被纳入窗口，胶囊左右两侧的页面内容将无法点击；
    // 悬浮形态正因如此要求窗口矩形紧贴胶囊本体。
    // 通栏低栏的前提相反：它本身就要占满那条带子，「栏以上区域仍需穿透」仍由
    // FLAG_NOT_TOUCH_MODAL 保证；二级页整块不接收触摸另由 [CapsuleTouchGate] 的
    // FLAG_NOT_TOUCHABLE 负责。通栏后窗口矩形更宽，该 gate 的作用更关键。
    //
    // naturalSize 的 **height 分支必须保留**：栏总高仍由内容测量决定，fontScale 放大时
    // 栏需要相应增高；仅 width 分支被移除。
    val desiredWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    val desiredHeight: Int = if (naturalSize.value.height > 0) naturalSize.value.height
        else ViewGroup.LayoutParams.WRAP_CONTENT
    // ★ gravity 不含 CENTER_HORIZONTAL：宽度已是 MATCH_PARENT，水平居中无意义
    //   （保留会暗示窗口仍有可居中的余量）。只保留 BOTTOM：它配合 FLAG_LAYOUT_NO_LIMITS
    //   将参考系固定在**屏幕真实底边**。
    val desiredGravity: Int = Gravity.BOTTOM
    // 窗口已越过系统装饰边界，显式允许铺到刘海/挖孔区，避免横屏被平台二次内缩。
    val desiredCutoutMode: Int =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    // ★ y 恒为 0。
    //
    // 悬浮形态的 y = 安全区 + 安全间距 8dp + 视觉抬高 30dp，用于把胶囊整体**抬离**屏幕底边。
    // 通栏低栏贴底，任何 y > 0 都会在栏与屏幕底边之间露出页面内容，破坏通栏形态。
    // 安全区未被丢弃，只是更换了消费方：栏内部用一段只有底色的 Spacer 让位
    // （[LocalCapsuleBottomReserved] → `UfiBottomDock`），页面 inset 则由
    // [CapsuleInsetHolder] 发布「内容高 + 安全区」。
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
 * 判据只看 `tappableElement().bottom == 0`，不再要求 `navigationBars().bottom > 0`
 * （原因见函数体内的说明）。
 *
 * 取值口径与 [readBottomReservedPx] 完全一致（均读取宿主 Activity 的全屏 rootWindowInsets），
 * 保证「预留区高度」与「按哪套间距」出自同一份 inset 快照，不会互相冲突。
 *
 * @return true = 手势导航（或无法判定时的保守默认，此时启用
 *         [GESTURE_BOTTOM_INSET_FLOOR] 保底，避免胶囊贴底）。
 */
private fun isGestureNavigationMode(view: View): Boolean {
    val decorView: View = view.context.findHostActivity()?.window?.decorView ?: return true
    val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(decorView) ?: return true
    val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
    // ★ 判据不得要求 `navBar > 0`。该条件原用于排除「全屏沉浸、底部无内容」的场景，
    //   但部分 ROM（实测 ColorOS 16）在手势导航下把 `navigationBars().bottom` 也报成 0，
    //   于是会被判定为非手势导航，`readBottomReservedPx` 的 24dp 保底随之失效、
    //   预留区算成 0，通栏底栏不再覆盖手势区。
    //   因此只看 `tappable == 0`：真正的全屏沉浸场景多留 24dp 无副作用（底栏只在正常 UI
    //   下显示），而底部未被覆盖是可见缺陷 —— 两者代价不对称。
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
 * 恰恰依赖这个值 —— 窗口一旦被抬到导航栏上方，下一次读数即归零，y 偏移随之回落，
 * 形成「上跳—下落」的抖动死循环（自激振荡）。
 *
 * Activity 的窗口是全屏的（`MainActivity` 已 `enableEdgeToEdge()`），
 * `getRootWindowInsets()` 返回的是**未被 DecorView 消费**的原始窗口 inset，
 * **不受胶囊浮窗位置影响**，是这里唯一稳定的真源。响应式监听见
 * [rememberBottomReservedPx]：监听挂在 Dialog 自己的 decorView 上只当「变了」的信号，
 * 取值一律回到本函数。
 *
 * ## 已知限制
 * 「Activity 窗口 == 全屏」这个前提在**平板 / 折叠屏展开态 / 多窗口分屏 / 自由窗口**下
 * 不再成立：此时 Activity 自己就是个被裁剪的子窗口，读到的 inset 与屏幕底部预留区无关。
 * 当前实现只覆盖**手机形态**，上述形态尚未处理。
 *
 * @return 预留区高度（px）；解析不到宿主 Activity / 视图尚未 attach / inset 读不到时，
 *         回落到 [GESTURE_BOTTOM_INSET_FLOOR]，**不得返回 0**（返回 0 会使胶囊失去手势区保护）。
 */
private fun readBottomReservedPx(view: View, density: Density): Int {
    val decorView: View = view.context.findHostActivity()?.window?.decorView
        ?: return with(density) { GESTURE_BOTTOM_INSET_FLOOR.roundToPx() }
    val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(decorView)
        ?: return with(density) { GESTURE_BOTTOM_INSET_FLOOR.roundToPx() }

    val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
    // ★ 并集包含 tappableElement：三键导航下它就是那条按键带的高度，而个别 ROM 的
    //   navigationBars 会比它小（甚至为 0）。取三者最大值以确保覆盖：
    //   通栏形态下该值决定「栏有多高」，少算会露出页面底色。
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
 * ## 与 [readBottomReservedPx] 的分工
 * - [readBottomReservedPx] = `max(navigationBars, systemGestures, tappableElement)` + 手势保底
 *   ⇒ 「栏总共要为系统底部让出多高」。悬浮形态下它决定胶囊抬高量（不得落进手势热区）。
 * - 本函数 = `navigationBars` ⇒ 「屏幕底部真正有系统像素（小白条 / 三键按键带）的那一段」。
 *
 * 通栏贴底之后，两者的差值不再是「不可进入区域」：栏本就横跨整条手势热区（热区只拦上滑手势，
 * 不影响绘制）。真正需要留空的只有系统栏本体那一段 —— 图标压在小白条上才是问题。
 * 因此该差值（手势导航下约 112−56 = 56px）应挪到**内容区上方**，而不是堆在下方
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
 * 不提供按导航模式分发的「预留区顶边 → 胶囊底边」呼吸间距：通栏形态没有悬在热区上方的
 * 底边，该间距概念不成立；栏内让位由 Spacer 承担（见 [LocalCapsuleBottomReserved]）。
 */

/**
 * **响应式**读取底部系统预留区高度（px），跟随导航模式切换 / 旋转 / 系统栏显隐实时更新。
 *
 * ## 信号源与取值源必须分离（核心设计）
 * - **信号源** = Dialog 自己的 decorView 上的 `setOnApplyWindowInsetsListener`。
 *   它只用来感知「inset 发生变化了」这一**事件**，回调参数里的 `insets` **一律不取值**。
 * - **取值源** = 宿主 Activity 的 `getRootWindowInsets()`（见 [readBottomReservedPx]）。
 *
 * 二者若合一（直接用回调的 `insets`），就会形成闭环：
 * `读到 inset → 改窗口 y → 窗口位置变 → 自身 inset 读数变 → 再改窗口 y …`，
 * 真机表现为胶囊上下抖动。取值换成**全屏** rootWindowInsets 后，读数与胶囊窗口位置
 * 彻底无关，闭环被切断 —— 因此这里**不需要也不应加 debounce**：加了只会使
 * 旋转 / 分屏后的归位延迟一帧，反而制造新的抖动观感。
 *
 * ## 允许下降
 * 若使用 `if (real > cur) cur = real` 做单调守卫，则横屏（预留区变小）不更新，
 * 三键切手势（48dp → 24dp）也不更新，胶囊会一直停在上一次的最大值上。
 * 因此这里**只要值变化就写入**（`!=`），并依靠「取值源与窗口位置解耦」避免来回抖动。
 *
 * ## 其他约束
 * - `remember` 的 key **只有 `view`**：不得把任何 inset 读数放入 key，否则读数一变
 *   state 即被重置，等于把响应式退化为「每次重建」，并丢失中间态；
 * - listener 内 **原样 `return insets`**，不得返回 `WindowInsetsCompat.CONSUMED`：
 *   消费 inset 会使 Dialog 内部的 Compose 布局全部读到 0；
 * - listener 只挂 Dialog 的 decorView，**不修改 Activity 的 decorView**（该槽位已被
 *   `enableEdgeToEdge()` 占用，覆盖会破坏全局 edge-to-edge）；
 * - view attach 时补读一次：组合期 view 可能尚未 attach，rootWindowInsets 读不到；
 * - `onDispose` 里把 listener 与 attach 回调都摘掉，不留悬挂引用。
 */
@Composable
private fun rememberBottomReservedPx(view: View, density: Density): BottomInsetReadings {
    // key 只有 view：不带任何 inset 读数，杜绝「读数一变就硬重置 state」。
    var reservedPx: Int by remember(view) {
        mutableIntStateOf(readBottomReservedPx(view, density))
    }
    // ★ 第二个读数（系统栏本体高度）与 reservedPx **共用同一个 listener**。
    //   不得为它再注册 `setOnApplyWindowInsetsListener(view.rootView, …)`：
    //   同一个 view 上的 listener 槽位只有一个，第二次注册会**覆盖**第一个，
    //   预留区将不再响应导航模式切换 / 旋转（静默失效，无任何报错）。
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
            // ★ 取值一律回到宿主 Activity 的全屏 rootWindowInsets。
            val next = readBottomReservedPx(view, density)
            // ★ 允许下降：只判断值是否变化，不做单调守卫。
            if (next != reservedPx) reservedPx = next
            val nextBar = readBottomSystemBarPx(view)
            if (nextBar != systemBarPx) systemBarPx = nextBar
            // ★ 原样返回，不得返回 CONSUMED。
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

        // 主动请求一次派发：若 inset 在注册之前已稳定，系统不会再次自发回调。
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
 * ## 承载方式：Dialog + 窗口内真模糊
 * 胶囊由 `MainNavGraph` 用 `Dialog(usePlatformDefaultWidth = false, …)` 承载。
 * 这样才拿得到 [Window] 对象 —— `Window.setBackgroundBlurRadius` 是**唯一**「只糊窗口
 * 自身矩形内」的公开 API，也是胶囊要的毛玻璃效果的唯一正解。
 *
 * ## `FLAG_BLUR_BEHIND` 对胶囊**永久禁用**
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
 * 给胶囊窗口使用 blur-behind 会使**整屏页面内容模糊**，即「胶囊背后整页模糊」。
 * 该行为与窗口是否为 wrap-content **无关**：blur-behind 的作用范围由系统 Dimmer 层决定，
 * 恒为整个容器，收窄窗口尺寸无效。
 * 胶囊是**常驻**浮层（不是可关闭的对话框），整屏模糊会使整个 App 不可用 ——
 * 因此该方案对胶囊**永久禁用**，由回归护栏测试约束（见 `CapsuleRegressionGuardTest`）。
 * 全屏对话框的景深由 `UfiDialogShell` 负责，两者不得互相套用。
 *
 * ## 必须为窗口提供**非透明**背景 drawable
 * `setBackgroundBlurRadius` 的实现依赖 `DecorView` 的 `BackgroundBlurDrawable`：
 * 系统用**窗口背景 drawable 的形状**（含圆角、alpha 蒙版）来裁剪模糊区域。
 * 而 Compose 的 `DialogWrapper.show()` 会把窗口背景重置为**全透明**，于是
 * 「模糊区域为空」：API 调用均成功，但屏幕上没有任何模糊（静默失效）。
 *
 * 所以这里主动 `setBackgroundDrawable` 一个圆角矩形：
 * - [GradientDrawable] 提供药丸圆角（[CapsuleWindowMetrics.cornerRadiusPx]）与一层
 *   极低 alpha 的底色（[CAPSULE_WINDOW_TINT_ALPHA]）—— 仅用于定形，不参与着色，
 *   真正的玻璃质感仍由 Compose 侧 [UfiCapsuleTabBar] 绘制；
 * - 窗口背景 drawable 不需要四边内缩：内缩量恒为 0，[InsetDrawable] 包裹已移除，
 *   drawable 直接以窗口大小绘制（见下文 `capsuleBackground`）。
 *
 * ## 触摸穿透约束
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 必须打上：前者让窗口不抢焦点（不弹输入法、
 * 不吃返回键），后者让**窗口矩形之外**的触摸继续下发给下方页面。底栏是常驻浮层，
 * 一旦缺少这两个 flag，会整块吞掉页面点击 —— 产品约束。
 *
 * ★ 窗口已为 `MATCH_PARENT` 宽 + `gravity=BOTTOM` + `y=0`，「窗口矩形」从紧贴胶囊的小窗
 * 变为屏幕底部整条带子。`FLAG_NOT_TOUCH_MODAL` 的语义不变，只是放行范围变为**栏以上**的
 * 整个页面区域（该区域需要穿透）；栏本身不透明且可点，这是预期行为。
 * 二级页需要整条带子穿透，由 [CapsuleTouchGate] 给窗口补 `FLAG_NOT_TOUCHABLE`；
 * 窗口变宽后该 gate 的作用更关键。
 *
 * ## 窗口尺寸**固定**，不跟随展开进度缩放
 * 窗口若随胶囊缩放，唯一动机是「模糊区 = 窗口矩形」：窗口不缩会在缩小的胶囊四周
 * 露出一圈无遮罩的模糊。以下两种做法均经真机验证不可行：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 根节点 `Modifier.layout` 汇报「自然尺寸 × scale」，靠 wrap-content 自动 resize | 窗口确实会缩，但逼着子树每帧重测 → 内部元素错位 + 动画卡顿 |
 * | 宿主每帧 `setLayout(naturalSize × capsuleScaleOf(progress))` | 每帧触发 WindowManager relayout → 动画卡顿；窗口夹小后回写 naturalSize 造成「越缩越小最终消失」的自激塌缩 |
 *
 * 窗口级真模糊已移除（见下文 `runCatching` 末尾），该约束不再存在。
 * 本函数因此只在「初始 + 重放」时把窗口尺寸设一次：naturalSize 已测得则用之，
 * 否则退化 `WRAP_CONTENT` 由内容撑开。收起/展开的视觉缩放全部由 [UfiCapsuleTabBar]
 * 根 Box 的 `graphicsLayer`（纯绘制期变换）承担，动画期间**零窗口 relayout**。
 *
 * ## 底部截断 / 手势条白边
 * 若 `Dialog` 使用 `decorFitsSystemWindows = true`，窗口会被夹在系统栏安全区内。
 * 后果有两个，表现为同一现象：
 * 1. 平台把手势小白条那条带子当作**本窗口**的装饰区，用窗口的 `navigationBarColor`
 *    （继承自 `android:Theme.Material.Light.Dialog` 的不透明底色）填成一条**白边**；
 * 2. `gravity = BOTTOM` 的参考系变为安全区底边，胶囊被整体顶高，叠加下方白边，
 *    视觉上表现为**胶囊被屏幕下边框截断**。
 *
 * 修复需要三项，缺一不可：
 * - 调用方改 `decorFitsSystemWindows = false`（见 `MainNavGraph`）；
 * - 本函数加 `FLAG_LAYOUT_NO_LIMITS` + `navigationBarColor = TRANSPARENT`
 *   + `isNavigationBarContrastEnforced = false` + `WindowCompat.setDecorFitsSystemWindows(false)`，
 *   让窗口能铺到屏幕真实底边且不再被平台补底色；
 * - 底部系统预留区高度改由 [CapsuleBlurHost] 经 [rememberBottomReservedPx] /
 *   [readBottomReservedPx]（宿主 Activity 的全屏 rootWindowInsets）读出来。
 *   ★ 该高度**不再计入窗口 y**（y 恒 0），而是经 [LocalCapsuleBottomReserved] 下发给
 *   栏内容，由栏内一段只有底色的 Spacer 让位；背景铺到屏幕真实底边
 *   （手势小白条浮在栏底色之上）。取值口径不变，仅消费方变化。
 *
 * 资源侧还在 `UfiDialogWindowTheme` 里预置了同样的透明导航栏配置，
 * 避免 Dialog 每次重建时在本函数生效前闪一帧白边。
 *
 * ## 背景为 null 时必须显式 `setFormat(TRANSLUCENT)`
 * 为消除胶囊外圈 1px 细线，运行时 `setBackgroundDrawable` 设为 `null`、
 * 主题 `windowBackground` 设为 `@null`（同时保留 `windowFrame=@null`）。这虽消除了描边，
 * 但也移除了窗口「表面透明」的显式声明：主题的 `windowIsTranslucent=true` 在这类
 * 「无背景 drawable」组合下对部分 ROM 不生效，窗口 Surface 退回不透明黑色，
 * 表现为胶囊底部整块纯黑、盖住下方红色按钮。
 *
 * 处理方式：清除背景后立即 `dialogWindow.setFormat(PixelFormat.TRANSLUCENT)`，将像素格式
 * 固定为半透明。该设置只取决于 Surface 格式，与背景 drawable 是否为 null 无关，因此即使
 * 背景为 null，窗口仍是透出底层页面的透明浮层，且不再绘制 1px 描边；未将
 * `windowBackground` 改回 transparent，也未新增任何背景层（见下文 `runCatching` 内调用点）。
 *
 * ## Popup 兜底
 * 若调用方退回 `Popup`（没有 `Window` 对象），[resolveDialogWindow] 返回 null，
 * 本函数**放弃真模糊**返回 `false`，由 [UfiCapsuleTabBar] 渲染视觉磨砂降级 ——
 * **绝不**退回 blur-behind。
 *
 * ## 幂等契约
 * 本函数会被重放三次（见 [CapsuleBlurHost]），因此**每一处写入都必须带相等性守卫**：
 * LayoutParams 走 [applyCapsuleWindowParams] 的单次整体赋值，其余（导航栏底色 /
 * elevation / padding / 背景 drawable / 像素格式 / decorFits）各自比对当前值。
 * 「未被改动则不写入任何字段」是本函数的不变量：同值重复写同样触发 relayout。
 *
 * @param view             Dialog 子组合里的 `LocalView`。
 * @param enabled          true 尝试施加模糊，false 清除模糊。即使系统最终不渲染模糊，
 *                         调用也会返回成功；调用方需结合 [WindowManager.isCrossWindowBlurEnabled]
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
        // ═══ 约束与定位：LayoutParams 的**唯一整体写入点**，无条件先执行 ═══
        // 触摸穿透（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL）、不变暗（清 FLAG_DIM_BEHIND
        // + dimAmount 归零）、越界布局（FLAG_LAYOUT_NO_LIMITS）、底部锚定（gravity = BOTTOM
        // + y = 0）、窗口尺寸（MATCH_PARENT 宽 / 内容高）、刘海模式全部合并进
        // [applyCapsuleWindowParams] 的一次赋值，且写前逐字段比对、相等即跳过 ——
        // 重放不再制造 relayout（见该函数 KDoc）。
        //
        // 它排在 runCatching **之外**：触摸穿透是产品约束，不得依赖下文「尽力而为、
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
            // 而小白条不会按背后像素自动反色（平台不采样背后像素，详见
            // [CapsuleWindowMetrics.lightNavHandleSurface]）—— 必须显式告知窗口
            // 「本窗口背景为浅色」，平台才会把小白条绘制为深色。
            //
            // 用 WindowInsetsControllerCompat 而不是直接改 decor 的 systemUiVisibility：
            // 后者在 API 30+ 已废弃，且与 enableEdgeToEdge 用的那套 appearance 位不是同一条路径。
            // 这里同样「值相等则跳过」：controller 的 setter 会走 InsetsController.setSystemBarsAppearance，
            // 无条件写会在每次重放里多派发一次。
            if (decor != null) {
                val controller = WindowInsetsControllerCompat(dialogWindow, decor)
                if (controller.isAppearanceLightNavigationBars != metrics.lightNavHandleSurface) {
                    controller.isAppearanceLightNavigationBars = metrics.lightNavHandleSurface
                }
            }

            // 窗口不消费 inset：栏要铺到屏幕真实底边，安全区由栏内 Spacer 让位
            // （窗口 y 偏移恒为 0，见 applyCapsuleWindowParams 的 y 说明）。
            // ★ 平台没有对应 getter，而 Window.setDecorFitsSystemWindows 会走到
            //   ViewRootImpl.setOnContentApplyWindowInsetsListener → requestFitSystemWindows()，
            //   即**每次调用都强制一次 inset 重派发 + traversal**（还会顺带回调
            //   rememberBottomReservedPx 的监听）。因此用宿主侧的一次性闸门限制为只写一次。
            if (!decorFitsApplied.value) {
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                decorFitsApplied.value = true
            }

            // 剥掉 Compose / Material 主题给 Dialog 注入的默认阴影：默认背景的 elevation
            // shadow 会在底部投出阴影团。decorView.elevation 是 Window.setElevation 的
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

            // ★ 覆盖 Compose 注入的全透明窗口底，为模糊提供所需的形状。
            // capsuleBackground 用**同一个 drawable 实例**同时赋给 window 与 decorView，
            // 以应对部分 ROM 在窗口属性重放时只回滚 theme windowBackground、
            // 而忽略 decorView 直接赋值的差异。
            // 关闭模糊（enabled = false）时，窗口背景 drawable 必须换成**全透明**，
            // 否则会残留一层灰色底；此时 Compose 侧以纯色实心底覆盖。
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

            // ★ 背景清为 null 后必须显式将窗口像素格式设为 TRANSLUCENT。
            // 仅靠主题的 windowIsTranslucent=true 在「无背景 drawable」这种组合下，部分 ROM
            // 不会生效，窗口 Surface 会退回不透明黑色，表现为胶囊底部的纯黑矩形
            // 盖住下方红色按钮。setFormat 只决定 Surface 像素格式，与背景是否为
            // null 无关，因此即使背景为 null，窗口也仍是透出底层页面的透明浮层。
            //
            // 不并进 applyCapsuleWindowParams：必须走 Window.setFormat 才会置上平台内部的
            // mHaveWindowFormat（直接写 LayoutParams.format 不会），这是该处理的必要条件。
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
 * 模糊的是**图层自身绘制的内容**。胶囊背景是半透明纯色，被模糊后只会变成均匀的灰色块，
 * 而不会模糊其**背后**的页面。能模糊「窗口背后已合成画面」的只有窗口级 API（API 31+），
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
 * 触摸穿透（产品约束）由 [applyWindowBlur] 手工补上
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 保证；这是换 Dialog 承载必须偿还的代价，
 * 因此它被放在 `runCatching` 的**第一条**，任何后续步骤失败都不影响它已经生效。
 *
 * ## 施加时机：立即 + `post` + `delay(100ms)` 三次重放
 * Compose 的 `DialogWrapper.show()` 会在组合提交之后**重放窗口属性**并把窗口背景重置成
 * 全透明，将已设置好的 gravity / 背景 drawable / 模糊半径一并抹掉。
 * 这与 `UfiDialogShell` 面临的问题相同，处理方式一致：施加一次之后，在
 * `view.post {}` 与 `delay(100L)` 各重放一次，确保最终状态正确。
 *
 * ★ 三次重放必须是**幂等**的：它们落在三个不同的帧上，若每次无条件重写窗口属性，
 * 即产生三次 WindowManager relayout；而胶囊 Dialog 恰好在 pop 时新挂载，
 * 这三次 relayout 正好落在返回动画中，表现为底部阴影闪烁或抖动。因此
 * [applyWindowBlur] 的每一处写入都带相等性守卫（LayoutParams 收成一次整体赋值），
 * 重放仅在属性确实被 DialogWrapper 改回时才写入。
 * 注意：不得改用「延后挂载」规避该问题，那会使胶囊高亮与实际页面错位（见 `MainNavGraph`）。
 *
 * ## 平台限制（重要）
 * 窗口模糊是**尽力而为**的：省电模式、开发者选项「关闭窗口模糊」、以及相当一部分
 * OEM ROM（`ro.surface_flinger.supports_background_blur=false`）会让它**静默失效** ——
 * 所有 API 调用都返回成功，但屏幕上没有任何模糊。
 *
 * 因此本组件不以「提交成功」作为生效判据，而是：
 * 1. 用 [WindowManager.isCrossWindowBlurEnabled] 查询系统能力；
 * 2. 用 `addCrossWindowBlurEnabledListener` 实时跟随（用户中途开省电模式会即时降级）；
 * 3. 把「真模糊是否生效」通过 [LocalUfiCapsuleRealBlurActive] 下发给 [UfiCapsuleTabBar]，
 *    由它在**真模糊轻透 tint** 与**视觉磨砂降级**两套背景之间切换。
 *
 * 这样无论设备是否支持，用户看到的都是干净的磨砂玻璃胶囊，不会出现半透明灰色块或整页模糊。
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
    // 内部自建 ThemeManager 而不新增参数：`CapsuleBlurHost(content: @Composable () -> Unit)`
    // 的公开签名受回归护栏 `publicApi_signatures_mustRemainStable` 约束，不得新增形参。
    //
    // 使用 `applicationContext` 而非 `LocalContext.current`：本组合位于 Dialog 的
    // ContextThemeWrapper 中，且 ThemeManager 会注册 SharedPreferences 监听，
    // 持有 Activity 级 Context 会在 Dialog 反复重建时留下悬挂引用。
    // `observeExternal = true`：设置页（另一个 ThemeManager 实例）写盘后，
    // 本实例必须经 prefListener 收到变更，滑块才能实时联动。
    val appContext = LocalContext.current.applicationContext
    val themeManager = remember { ThemeManager(appContext, observeExternal = true) }
    // 反注册 prefs 监听：Dialog 宿主会反复重建，不解就每重建一次多挂一个监听。
    DisposableEffect(themeManager) { onDispose { themeManager.dispose() } }
    // 不读取 `ThemeManager.capsuleLiftDp`：抬高量是**悬浮**形态的视觉参数，贴底通栏的
    // y 恒 0，抬高与贴底形态矛盾，该链路已移除；`capsuleLiftDp` 设置项仍存在，
    // 但不再有几何消费者。
    val capsuleCorner by themeManager.capsuleCornerDp.collectAsState()

    // 底部系统预留区（系统栏 ∪ 手势热区）高度。
    //
    // ★ 窗口 y 恒为 0，该高度不再计入窗口偏移，而是
    //   ① 经 [LocalCapsuleBottomReserved] 下发给栏内容，由栏内一段只有底色的 Spacer 让位；
    //   ② 计入 [CapsuleInsetHolder] 发布的页面底部 inset（见下方 capsuleBottomTotalDp）。
    //
    // 注意：**取值链路不得改动** —— 它是消除「窗口位置 → 自身 inset → 再改窗口位置」
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
    // （capsuleLift 是窗口 y 偏移的驱动项，已随该机制一起移除。）
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
    // 即返回时阴影抖动中不经过 LayoutParams 的那一路。故用本闸门限制为只写一次。
    //
    // 仅在效应里读写、不参与组合，所以不会引起重组。
    val decorFitsApplied = remember(view) { mutableStateOf(false) }

    // 胶囊此刻是否该吃触摸（宿主目的地 = 是，二级页 = 否）。见 [CapsuleTouchGate]。
    // 读成 State 才能在翻转时让下面两个效应重跑，把 FLAG_NOT_TOUCHABLE 下发到窗口。
    val capsuleInteractive: Boolean = CapsuleTouchGate.interactive.value

    // ── 底栏底部遮挡总高（跨窗口共享单例）──
    //
    // 计算式为「内容高 + 安全区」。原式为未缩放固有高度 `naturalSize.height` + 底部间隙 8dp +
    // 系统底部预留区 reservedPx，三项均为**悬浮**形态的量：固有高度是胶囊本体（不含安全区，
    // 因为安全区在窗口 y 中）、8dp 是「胶囊底边离手势热区」的安全间距、reservedPx 是被窗口 y
    // 抬起的那一段。通栏形态下栏**自身**覆盖了安全区那条带子（背景铺满 + 栏内 Spacer 让位），
    // 页面需要避开的正是「内容区高 + 安全区」这一整块，既无 8dp 间隙也无抬升。
    //
    // 高度常量复用 `UfiBottomDock` 的 [DOCK_CONTENT_MIN_HEIGHT]（58dp），
    // **不得在此另写 58**：两处各写一份数字最终会漂移。
    //
    // 经 [CapsuleInsetHolder] 写入跨窗口单例，主窗口根读取后经 CompositionLocal 下发。
    // 注意：CompositionLocal 不跨 Window，不得在 Dialog 内直接 provide 给页面。
    //
    // 计算式取「常量式」与「实测总高」的较大值：常量式为 58 + 安全区；实测总高在
    // fontScale 1.5× 时更大（`heightIn(min=58)` 会使栏长高，常量式比真实栏矮几 dp，
    // 页面最后一行会被压在栏下）。两者在常规字号下相等 —— `UfiBottomDock` 的根 Column
    // 即「内容区 + 安全区 Spacer」；取 max 同时满足两种口径，并兼容悬浮胶囊分支
    // （胶囊实测高 ≈50dp < 58 + 安全区，常量式胜出，与迁移前的页面留白一致）。
    val capsuleBottomTotalDp: Dp = remember(reservedPx, naturalSize.value.height) {
        val byConstant = DOCK_CONTENT_MIN_HEIGHT + with(density) { reservedPx.toDp() }
        val byMeasured = with(density) { naturalSize.value.height.toDp() }
        maxOf(byConstant, byMeasured)
    }
    // 写入共享单例。
    //
    // 写入共享单例。
    //
    // ★ **测量完成前不发布**。若无条件写入，首帧 naturalSize 为 0 ⇒ 先发布一个只有
    //   「8dp 间隙 + 系统预留区」的**中间值**（比真实高度矮 ~50dp），下一帧测得后再发布真值。
    //   所有读 [LocalCapsuleBottomInset] 的页面（监控/仪表盘的长列表都读它做底部留白）
    //   因此需要**重新布局两次**：88dp 兜底 → 中间值 → 真值。胶囊 Dialog 在 pop 时新挂载，
    //   这两次重排正好落在返回动画中，底部卡片随之上下跳动、阴影随之闪烁；
    //   跳过中间值后只剩「兜底 88dp → 真值」一次。
    //
    // ★ 该闸门**必须保留**（回归护栏 `capsuleInset_mustNotPublishBeforeMeasured` 约束）：
    //   栏尚未测出时不得让页面按半成品值重排。计算式本身已不依赖 naturalSize，
    //   闸门现在只承担「等待栏完成测量」的时序作用。
    //   供数方：`UfiBottomDock` 的根 Column 通过 `onSizeChanged` 回写
    //   [LocalCapsuleNaturalSize]。若只有 `UfiCapsuleTabBar` 写入，开关切到通栏后该闸门
    //   会永不放行、所有页面停在 88dp 兜底值。
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
    // Compose 的 DialogWrapper.show() 会在组合提交后重放窗口属性，并将窗口背景重置为
    // 全透明，使 flags / gravity / 背景 drawable 一并失效；因此施加一次不够，
    // 必须在下一个消息循环再重放一次（第三次重放在下面的 LaunchedEffect 里）。
    //
    // ★ 重放本身必须是**幂等**的。三次重放落在三个不同的帧上；若每次都无条件重写整套
    //   窗口属性（addFlags / clearFlags / setDimAmount / setLayout / attributes= /
    //   setElevation / setFormat 均无条件派发），pop 期间会稳定产生 3 次 WindowManager
    //   relayout，每次都重算 decor 布局并重建窗口 Surface 的投影区，表现为返回时底部阴影闪烁。
    //   LayoutParams 收成 [applyCapsuleWindowParams] 的一次整体赋值 + 相等则跳过，
    //   装饰项（导航栏底色 / elevation / 背景 drawable / 像素格式 / decorFits）逐个带守卫，
    //   因此后两次重放在属性未被改动时是纯读取：零派发、零 relayout。
    //   注意：不得将重放改为延迟挂载来规避（那会使胶囊高亮与实际页面错位）。
    //
    // 注意：key 中**不含**展开进度。窗口尺寸不随收起/展开逐帧缩放（真模糊已移除，
    // 「每帧 setLayout」方案及其卡顿与自激塌缩一并移除）。这里只负责
    // flags / gravity / 底边锚定 / 背景 drawable 的「初始 + 重放」，
    // 动画期间完全不触发 WindowManager relayout。
    //
    // key 里显式带上 capsuleCorner：metrics 已随它重算（见其 remember key），这里再列一次
    // 是为了让「用户拖动滑块 → 窗口属性重新下发」的因果关系在源码中显式可见，
    // 并防止后续重构 metrics 的 key 时静默切断实时联动。
    // （`capsuleLift` 是窗口 y 偏移的驱动项，已随 y→0 一并移除。）
    DisposableEffect(
        view, metrics, crossWindowBlurEnabled, capsuleCorner, capsuleInteractive
    ) {
        // 真模糊已移除，applyWindowBlur 恒返回 false。这里传 false，窗口背景走 null 分支：
        // 胶囊纯透明、无定形 drawable、无系统 1px 描边，同时完成 flags / gravity /
        // 底边锚定的施加与重放。
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
