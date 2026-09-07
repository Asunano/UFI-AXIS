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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import java.util.function.Consumer

/**
 * 胶囊四周预留给投影的空间（**单一真源**）。
 *
 * 设为 **0.dp**：承载胶囊的 Dialog 窗口是 wrap-content，原本为让 `shadow(10.dp)` 投影完整
 * 落在窗口内而留的 10dp 留白现在直接去掉，窗口边界紧贴胶囊本体。这样模糊区就等于
 * 胶囊本体，不再有一圈「外圈空白」可供光晕溢出（见 [applyWindowBlur]）。
 *
 * 由 `MainNavGraph` 直接 import 引用，避免两处各写一份而漂移。
 */
internal val CAPSULE_SHADOW_ROOM: Dp = 0.dp

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

/**
 * 底部系统预留区**顶边** → 胶囊**底边**的视觉呼吸间距（手势导航，**单一真源**）。
 *
 * ## 语义变更（底部截断 bug 修复 → 跨设备定位修复）
 * 最早的语义是「距**屏幕**底部的留白」，靠 `decorFitsSystemWindows = true` 让 Dialog
 * 窗口落在安全区内、由系统替我们避开导航栏 —— 代价是系统会在手势小白条位置填一层
 * **白色遮罩**，且胶囊被整体顶高、视觉上像被下边框截断。
 *
 * 现在窗口通过 `FLAG_LAYOUT_NO_LIMITS` 延伸到系统装饰区（见 [applyWindowBlur]），
 * 底部预留区高度由 [readBottomReservedPx] 动态读取后加进窗口 `y` 偏移
 * （见 [CapsuleBlurHost]），所以本常量只剩「别贴死手势条」这一个职责，取 8dp 即可。
 *
 * 参照物也随之精确化：不再是「系统导航栏顶边」，而是「系统栏 ∪ 手势热区」这块
 * **预留区**的顶边 —— 手势导航下两者并不相等（见 [readBottomReservedPx]）。
 *
 * 由 `MainNavGraph` 直接 import 引用，避免两处各写一份而漂移。
 */
internal val CAPSULE_GAP_GESTURE: Dp = 8.dp

/**
 * 底部系统预留区顶边 → 胶囊底边的视觉呼吸间距（**三键 / 二键导航**）。
 *
 * 当前刻意与 [CAPSULE_GAP_GESTURE] 取同一个值（8dp），视觉上两种导航模式一致。
 * 单独留一个常量、并用 [capsuleGapFor] 分发，是为了将来若要给按键导航加大间距，
 * **只改这一个数**即可，不必再动取值链路，也不会误伤手势导航。
 */
internal val CAPSULE_GAP_BUTTON: Dp = 8.dp

/*
 * ★ 设计说明（原 `private val CAPSULE_LIFT: Dp = 24.dp` 的文档，常量已移入函数内）★
 *
 * 产品向的**视觉抬高量**：胶囊在「安全间距」之上再整体上浮的距离。
 *
 * ## 本值已从「文件级常量」改为「用户可调设置项」（改动前必读）
 * 抬高量现由 `ThemeManager.capsuleLiftDp` 持久化（设置 → 外观 → 胶囊尺寸），
 * 默认值 [ThemeManager.DEFAULT_CAPSULE_LIFT_DP] = **30**（产品终稿值，原为 24）。
 * 未拖过滑块的用户读不到该键、回落到这个默认值。
 *
 * 实现形态：文件级常量被删除，改为 CapsuleBlurHost **函数内的局部 val**
 * `CAPSULE_LIFT`（由 `capsuleLiftDp.dp` 构造）。之所以刻意保留这个大写名字而不是
 * 直接内联 `capsuleLift.dp`，是为了让 `bottomOffsetPx` 的赋值表达式**一字不变** ——
 * 回归护栏 `capsuleLift_mustBeWired` 同时断言「`val CAPSULE_LIFT: Dp =` 声明存在」
 * 与「该表达式里出现 CAPSULE_LIFT」，两条都必须继续成立。
 *
 * ## 与 CAPSULE_GAP_GESTURE / CAPSULE_GAP_BUTTON 的语义正交（红线 R3）
 * 那两个常量回答的是**安全**问题 ——「胶囊底边至少要离系统预留区顶边多远，才不会被
 * 手势热区吃掉点击」，取值 8dp 是真机验证过的下限，属于**不变量**，不许动。
 * 本量回答的是**审美**问题 ——「产品希望胶囊看起来离屏幕底边再远一点」。
 *
 * 二者相加（见 [CapsuleWindowMetrics.bottomOffsetPx]）而不是把 8dp 直接调大，
 * 是为了让「安全间距」这条已验证的不变量在源码里保持**可识别、可单独回归**：
 * 用户拖动抬高滑块只改追加项，安全下限 8dp 原封不动，
 * 跨设备一致性（预留区由 [readBottomReservedPx] 动态读出）也完全不受影响。
 */

/**
 * 历史别名，**请勿在新代码中使用** —— 语义已由 [CAPSULE_GAP_GESTURE] 承接。
 *
 * 保留符号只是为了不打断既有 import（`MainNavGraph` 仍引用本名）。取值恒等于
 * [CAPSULE_GAP_GESTURE]，不会与之漂移。
 */
@Deprecated(
    message = "语义已拆分为 CAPSULE_GAP_GESTURE / CAPSULE_GAP_BUTTON，请改用 capsuleGapFor()；" +
        "本别名仅为兼容既有 import 而保留。",
    replaceWith = ReplaceWith("CAPSULE_GAP_GESTURE")
)
internal val CAPSULE_BOTTOM_MARGIN: Dp = CAPSULE_GAP_GESTURE

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
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

/**
 * 胶囊窗口**必须清除**的 flag（**单一真源**）。
 *
 * `FLAG_DIM_BEHIND` 会让整页压一层灰 —— 胶囊是常驻浮层，绝不能变暗。
 * 与之配套的 `dimAmount` 也在 [applyCapsuleWindowParams] 的同一次写入里归零，
 * 不再单独调 `Window.setDimAmount`（那是又一次无条件 attributes 派发）。
 */
private const val CAPSULE_WINDOW_FLAGS_OFF: Int = WindowManager.LayoutParams.FLAG_DIM_BEHIND

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
 * 把「胶囊底部遮挡预留」表达成一个**只在 layout 阶段读值**的高度占位。
 *
 * 用法：作为滚动列表**最后一个 item** 的 `Spacer` 修饰符，语义等价于原来的
 * `LazyColumn(contentPadding = PaddingValues(bottom = inset + extra))` ——
 * 都是在内容末尾追加一段可滚动的空白，且不缩小视口（`Modifier.padding` 会缩小视口、
 * 让 item 在硬边界处被裁掉，不是同一个观感）。
 *
 * 关键点：[LocalCapsuleBottomInset] 的 `.current` 在组合期读到的只是那个**稳定的 lambda**
 * （provider 永不变 ⇒ 不触发重组），真正的快照读 `CapsuleInsetHolder.bottomInset.value`
 * 发生在 `Modifier.layout` 的 measure lambda 里 ⇒ **inset 变化只触发本节点重排，不触发重组**。
 * 这是 P1（pop 第 2~3 帧整树重组）修复的消费侧一半，另一半在 `MainNavGraph` 的 provider。
 *
 * @param extra 额外留白（如列表与胶囊之间的呼吸间距）。走 `ui.theme` 的 `Spacing` 令牌，
 *              不要在调用点写裸数值。
 */
@Composable
fun Modifier.ufiCapsuleBottomInset(extra: Dp): Modifier {
    val insetOf: () -> Dp = LocalCapsuleBottomInset.current
    return this.layout { measurable, constraints ->
        // ★ 这一行是整个修复的落点：快照读在 measure 里，不在组合里。
        val height = (insetOf() + extra).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height)
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
 * @property bottomOffsetPx    窗口相对**屏幕真实底边**的偏移（px）
 *                             = [bottomReservedPx] + (`capsuleGapFor(isGestureNav)` −
 *                             [CAPSULE_SHADOW_ROOM]) + [CAPSULE_LIFT]。
 *                             前两项是**安全**语义（保证胶囊底边不落进手势热区，已验证的
 *                             8dp 不变量），末项是**审美**语义（产品向的整体抬高），二者正交、
 *                             各自独立可调，见 [CAPSULE_LIFT]。
 *                             窗口已用 `FLAG_LAYOUT_NO_LIMITS` 延伸到系统装饰区，因此必须由
 *                             我们自己把预留区高度加回去，否则胶囊会压在手势小白条上被截断。
 * @property cornerRadiusPx    窗口背景 drawable 的圆角半径（px）。
 * @property insetPx           窗口背景 drawable 四边内缩量（px），让模糊区 ≈ 胶囊本体。
 * @property tintArgb          窗口背景 drawable 的填充色（ARGB），低 alpha，只定形不抢色。
 * @property bottomReservedPx  屏幕底部**系统占用/预留区**高度（px）：系统导航栏与手势热区的
 *                             **并集**（见 [readBottomReservedPx]），已计入 [bottomOffsetPx]。
 *                             它比「导航栏高度」更宽 —— 手势导航下 `navigationBars` 可能远小于
 *                             真实手势热区。单独保留一份便于诊断与后续布局复用。
 */
private data class CapsuleWindowMetrics(
    val bottomOffsetPx: Int,
    val cornerRadiusPx: Float,
    val insetPx: Int,
    val tintArgb: Int,
    val bottomReservedPx: Int
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
 * @param naturalSize 胶囊根 Box 的固有尺寸；已测得则钉成固定宽高，未测得（首帧为 0）
 *                    退化 `WRAP_CONTENT` 由内容撑开。
 */
private fun applyCapsuleWindowParams(
    window: Window,
    metrics: CapsuleWindowMetrics,
    naturalSize: MutableState<IntSize>
) {
    val desiredWidth: Int = if (naturalSize.value.width > 0) naturalSize.value.width
        else ViewGroup.LayoutParams.WRAP_CONTENT
    val desiredHeight: Int = if (naturalSize.value.height > 0) naturalSize.value.height
        else ViewGroup.LayoutParams.WRAP_CONTENT
    // 锚定底部居中；y = 系统底部预留区 + 安全间距 + 视觉抬高（见 CapsuleWindowMetrics）。
    val desiredGravity: Int = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    // 窗口已越过系统装饰边界，显式允许铺到刘海/挖孔区，避免横屏被平台二次内缩。
    val desiredCutoutMode: Int =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

    val params: WindowManager.LayoutParams = window.attributes
    val desiredFlags: Int =
        (params.flags or CAPSULE_WINDOW_FLAGS_ON) and CAPSULE_WINDOW_FLAGS_OFF.inv()

    // ★ 相等则跳过：同值重复写也会触发 relayout（AOSP 的 setter 全部无条件派发）。
    val settled: Boolean = params.flags == desiredFlags &&
        params.dimAmount == 0f &&
        params.width == desiredWidth &&
        params.height == desiredHeight &&
        params.gravity == desiredGravity &&
        params.y == metrics.bottomOffsetPx &&
        params.layoutInDisplayCutoutMode == desiredCutoutMode
    if (settled) return

    window.attributes = params.apply {
        flags = desiredFlags
        dimAmount = 0f
        width = desiredWidth
        height = desiredHeight
        gravity = desiredGravity
        y = metrics.bottomOffsetPx
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
    val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
    return tappable == 0 && navBar > 0
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
    val raw = maxOf(navBar, gestures)

    // 手势导航才需要保底：三键导航的 navigationBars 是实打实的按键带高度，读数可信，
    // 强行抬到 24dp 反而会让胶囊无谓地飘起来。
    val floor = with(density) {
        if (isGestureNavigationMode(view)) GESTURE_BOTTOM_INSET_FLOOR.roundToPx() else 0
    }
    return maxOf(raw, floor)
}

/**
 * 按导航模式选择「预留区顶边 → 胶囊底边」的呼吸间距。
 *
 * 当前两个分支取值相同（都是 8dp），保留分发结构是为了将来只调
 * [CAPSULE_GAP_BUTTON] 一个数就能单独加大按键导航下的间距。
 */
private fun capsuleGapFor(isGestureNav: Boolean): Dp =
    if (isGestureNav) CAPSULE_GAP_GESTURE else CAPSULE_GAP_BUTTON

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
private fun rememberBottomReservedPx(view: View, density: Density): Int {
    // key 只有 view：不带任何 inset 读数，杜绝「读数一变就硬重置 state」。
    var reservedPx: Int by remember(view) {
        mutableIntStateOf(readBottomReservedPx(view, density))
    }

    DisposableEffect(view, density) {
        // 信号源：Dialog 自己的 decorView。只当「inset 变了」的通知，不从中取值。
        val signalTarget: View = view.rootView

        // density 变化（字体 / 显示大小调整）时 dp→px 的换算结果会变，而系统未必会因此
        // 重新派发 inset；这里无条件校准一次，避免保底值停在旧密度下的像素数。
        val recalibrated = readBottomReservedPx(view, density)
        if (recalibrated != reservedPx) reservedPx = recalibrated

        ViewCompat.setOnApplyWindowInsetsListener(signalTarget) { _, insets ->
            // ★ 取值一律回到宿主 Activity 的全屏 rootWindowInsets（红线 L1）。
            val next = readBottomReservedPx(view, density)
            // ★ 允许下降：只判「变了没有」，不做单调守卫（红线 L3）。
            if (next != reservedPx) reservedPx = next
            // ★ 原样返回，绝不 CONSUMED（红线 L2）。
            insets
        }

        // view 首次 attach 后才拿得到宿主 Activity 的 rootWindowInsets，这里补读一次，
        // 消除「首帧按保底值定位、attach 后不再纠正」的偏差。
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val next = readBottomReservedPx(view, density)
                if (next != reservedPx) reservedPx = next
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

    return reservedPx
}

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
 * - 由于 [CAPSULE_SHADOW_ROOM] 已为 0.dp，[CapsuleWindowMetrics.insetPx] 恒为 0，
 *   不再需要 [InsetDrawable] 内缩包裹，drawable 直接以胶囊大小绘制，
 *   使**模糊区 = 胶囊本体**而非更大的矩形（见下方 `capsuleBackground`）。
 *
 * ## 触摸穿透红线
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 必须打上：前者让窗口不抢焦点（不弹输入法、
 * 不吃返回键），后者让**窗口矩形之外**的触摸继续下发给下方页面。胶囊是常驻浮层，
 * 一旦漏掉这两个 flag 就会整块吞掉页面点击 —— 产品红线。
 *
 * 同时把窗口收成 wrap-content 并锚到 `BOTTOM|CENTER_HORIZONTAL`，
 * 使窗口矩形本身就只覆盖胶囊，把「窗外」区域压到最小。
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
 *   [readBottomReservedPx]（宿主 Activity 的全屏 rootWindowInsets）读出来，
 *   加进 [CapsuleWindowMetrics.bottomOffsetPx]，把胶囊精确抬到手势热区上方
 *   [CAPSULE_GAP_GESTURE] 处。
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
    decorFitsApplied: MutableState<Boolean>
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

    // ── 路径 1：Dialog（有真正的 Window 对象）──
    val dialogWindow: Window? = resolveDialogWindow(view)
    if (dialogWindow != null) {
        // ═══ 红线 + 定位：LayoutParams 的**唯一整体写入点**，无条件先执行 ═══
        // 触摸穿透（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL）、不变暗（清 FLAG_DIM_BEHIND
        // + dimAmount 归零）、越界布局（FLAG_LAYOUT_NO_LIMITS）、底部锚定（gravity + y）、
        // 窗口尺寸、刘海模式全部合并进 [applyCapsuleWindowParams] 的一次赋值，
        // 且写前逐字段比对、相等即跳过 —— 重放不再制造 relayout（见该函数 KDoc）。
        //
        // 它排在 runCatching **之外**：触摸穿透是产品红线，绝不依赖下方「尽力而为、
        // 个别 ROM 可能抛异常」的装饰配置；后者整段失败也不会退化成吞点击的模态窗。
        applyCapsuleWindowParams(dialogWindow, metrics, naturalSize)

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

            // 窗口自己不吃 inset，底部偏移由 metrics.bottomOffsetPx 精确控制。
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
    val capsuleLift by themeManager.capsuleLiftDp.collectAsState()
    val capsuleCorner by themeManager.capsuleCornerDp.collectAsState()

    // 底部系统预留区（系统栏 ∪ 手势热区）高度。窗口已用 FLAG_LAYOUT_NO_LIMITS 延伸到
    // 系统装饰区，这段高度必须由我们自己加回窗口 y 偏移，否则胶囊压在手势条上被截断。
    //
    // 取值链路见 [rememberBottomReservedPx]：
    // - 监听挂 Dialog 自己的 decorView，只当「inset 变了」的信号；
    // - 取值一律走宿主 Activity 的全屏 rootWindowInsets（与胶囊窗口位置解耦，不自激振荡）；
    // - 允许下降（旋转 / 三键切手势时预留区变小也能跟上），读不到时回落保底值而非 0。
    val reservedPx: Int = rememberBottomReservedPx(view, density)

    // dp→px 换算与取色只能在组合里做，提前算好交给非组合的 applyWindowBlur 复用。
    //
    // 导航模式在这里再判一次而不是塞进 state：预留区高度与导航模式同源同变
    // （手势 ≈24dp / 三键 ≈48dp，切换必然带动 reservedPx 变化 → 本 remember 失效重算），
    // 因此以 reservedPx 为 key 已足够跟随；且当前两套间距取值相同，不影响结果。
    // ★ 局部 CAPSULE_LIFT：文件级 `private val CAPSULE_LIFT: Dp = 24.dp` 已删除，
    //   抬高量改由用户设置驱动。刻意沿用大写常量名，使下方 `bottomOffsetPx` 的赋值
    //   表达式**一字不改** —— 红线 R3 与护栏 `capsuleLift_mustBeWired` 双重要求。
    @Suppress("LocalVariableName")
    val CAPSULE_LIFT: Dp = capsuleLift.dp

    // 圆角：用户值优先，0（理论上不可达，MIN=10）时回退到文件级默认常量。
    val capsuleCornerRadius: Dp =
        if (capsuleCorner > 0) capsuleCorner.dp else CAPSULE_WINDOW_CORNER_RADIUS

    // key 里追加 capsuleLift / capsuleCorner：两者任一变化都必须重算 metrics，
    // 否则 metrics 引用不变，下游以它为 key 的 DisposableEffect / LaunchedEffect
    // 不会重跑，窗口 y 偏移与圆角就不会下发（拖动滑块时胶囊纹丝不动）。
    val metrics: CapsuleWindowMetrics = remember(
        density, palette, reservedPx, capsuleLift, capsuleCorner
    ) {
        val gestureNav = isGestureNavigationMode(view)
        with(density) {
            CapsuleWindowMetrics(
                // 定位基线三项（reservedPx + 安全间距 − 投影留白）原样保留，
                // 只在其上追加产品向的视觉抬高量 CAPSULE_LIFT —— 安全不变量不受影响。
                bottomOffsetPx = reservedPx +
                    (capsuleGapFor(gestureNav) - CAPSULE_SHADOW_ROOM).roundToPx() +
                    CAPSULE_LIFT.roundToPx(),
                cornerRadiusPx = capsuleCornerRadius.toPx(),
                insetPx = CAPSULE_SHADOW_ROOM.roundToPx(),
                tintArgb = palette.cardBg.copy(alpha = CAPSULE_WINDOW_TINT_ALPHA).toArgb(),
                bottomReservedPx = reservedPx
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

    // ── 胶囊底部遮挡总高（跨窗口共享单例）──
    // 2026-08-08 19:47 新增（方案 A 修订版）：监控页底部内容被胶囊遮挡（88dp 硬编码不足）。
    // 计算 = 未缩放固有高度（= 展开态 scale 1.0 最保守，天然覆盖收起 0.86 态；且不随动画
    // 每帧变化，避免页面 padding 闪烁）+ 底部间隙(8dp) + 系统底部预留区(reservedPx，宿主已算)。
    // 经 [CapsuleInsetHolder] 写跨窗口单例，主窗口根读取后 CompositionLocal 下发 ——
    // ⚠ CompositionLocal 不跨 Window，绝不能在 Dialog 内直接 provide 给页面。
    val capsuleBottomTotalDp: Dp = remember(naturalSize.value, reservedPx) {
        with(density) { naturalSize.value.height.toDp() } +
            CAPSULE_GAP_BUTTON +
            with(density) { reservedPx.toDp() }
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
    // key 里显式带上 capsuleLift / capsuleCorner：metrics 已随二者重算（见其 remember key），
    // 这里再列一次是为了让「用户拖动滑块 → 窗口属性重新下发」的因果关系在源码中显式可见，
    // 也防止将来有人重构 metrics 的 key 时静默切断实时联动。
    DisposableEffect(view, metrics, crossWindowBlurEnabled, capsuleLift, capsuleCorner) {
        // 真模糊已移除，applyWindowBlur 恒返回 false；这里仍传 true，但 enabled=false 时
        // 窗口背景实际走 null 分支：胶囊纯透明、无定形 drawable、无系统 1px 描边，
        // 顺带完成 flags / gravity / 底边锚定的施加与重放。
        val wantBlur = false
        var disposed = false
        blurSubmitted = applyWindowBlur(view, wantBlur, metrics, naturalSize, decorFitsApplied)
        realBlurActive = blurSubmitted && crossWindowBlurEnabled

        view.post {
            if (!disposed) {
                blurSubmitted =
                    applyWindowBlur(view, wantBlur, metrics, naturalSize, decorFitsApplied)
                realBlurActive = blurSubmitted && crossWindowBlurEnabled
            }
        }

        onDispose {
            disposed = true
            applyWindowBlur(view, false, metrics, naturalSize, decorFitsApplied)
            blurSubmitted = false
            realBlurActive = false
        }
    }

    // ── 第三次重放：延迟 100ms ──
    // 与 UfiDialogShell 同源的经验值：post 之后仍可能被 DialogWrapper 的后续
    // show()/attributes 重放覆盖，延迟一拍再写一次才留得住。
    // LaunchedEffect 在 dispose 时自动取消，不会有"清除后又被重新点亮"的竞态。
    // 同样不以展开进度为 key（理由见上方 DisposableEffect）。
    // 同样带上 capsuleLift / capsuleCorner（理由见上方 DisposableEffect）。
    //
    // ★ 这一拍恰好落在返回动画中段，是「阴影闪烁」最刺眼的一次。现在它只做校验：
    //   属性没被 DialogWrapper 改回去时全部命中相等性守卫，不写、不 relayout。
    LaunchedEffect(view, metrics, crossWindowBlurEnabled, capsuleLift, capsuleCorner) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val wantBlur = false
        delay(100L)
        blurSubmitted = applyWindowBlur(view, wantBlur, metrics, naturalSize, decorFitsApplied)
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
        LocalCapsuleNaturalSize provides naturalSize
    ) {
        content()
    }
}
