package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.navigation.LocalUfiCapsuleSelectionProgress
import com.ufi_axis.ui.navigation.ufiNavTransitionDurationMs
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 贴底**通栏低栏**（bottom dock）—— 悬浮胶囊的替代形态。
 *
 * 与 [UfiCapsuleTabBar] 的根本差别：
 * - 宽度铺满、上圆角 22dp / 下直角，**贴屏幕真实底边**；
 * - **没有收起态**：标签常显，不存在展开/收回状态机、自动收起计时器、标签淡入淡出；
 * - 背景铺到安全区里（手势小白条浮在栏底色之上），内容用一段只有底色的 [Spacer] 让位 ——
 *   这两层分离就是「手势区沉浸」的全部实现（详见 `docs/bottom-dock-migration-plan.md` §3）。
 *
 * 唯一的动画是选中药丸的横向位移。它**不是**「跟着整数选中下标跑」那么简单：5 个 Tab 页
 * 仍可横滑切换，所以药丸必须逐帧跟住 pager 的连续进度，落定 / 点击时再归位到整格。
 * 这套时序是悬浮胶囊时代四轮真机调试的产物，整段搬了过来 —— 见 `indicatorPos` 的注释。
 *
 * ## 为什么不复用 [UfiCapsuleTabBar]
 * 那个组件的每一处几何都是「随展开进度插值」的：格宽、整体缩放、标签 alpha、药丸高度
 * 全部挂在 `expandProgress` / `labelReveal` 上。通栏形态把这些进度全部钉成常量，
 * 改原地实现等于把它掏空；新旧并存一段（编译期开关切换）才能装包逐项对比。
 *
 * 入参形状与 [UfiCapsuleTabBar] 对齐（含复用 [CapsuleTabItem]），以便调用点二选一直接替换。
 *
 * @param tabs          Tab 列表（4~5 项）。空列表直接不渲染。
 * @param selectedIndex 宿主持有的选中下标；越界会被夹回合法区间（状态恢复期可能临时越界）。
 * @param onTabSelected 点击回调，参数是被点格的下标。
 */
@Composable
fun UfiBottomDock(
    tabs: List<CapsuleTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeIndex: Int = selectedIndex.coerceIn(0, tabs.lastIndex)

    // 安全区高度。**刻意不自己读 WindowInsets**：取值口径（max(navigationBars, systemGestures)、
    // 手势导航兜底 24dp、且一律回读宿主 Activity 的全屏 rootWindowInsets 以免自激振荡）
    // 全部封在 `UfiCapsuleBlurHost` 的 readBottomReservedPx() 里，是已经踩过坑的单一真源。
    // 这里只消费宿主 provide 好的成品值，见 [LocalCapsuleBottomReserved]。
    val reservedPx: Int = LocalCapsuleBottomReserved.current
    val reservedDp: Dp = with(density) { reservedPx.toDp() }

    // ★ 2026-09-24（真机："图标离屏幕下部分太远"）：把预留区拆成上下两段，**栏总高不变**。
    //
    // 原来 `reservedDp` 整段都垫在内容区**下方**。它的口径是
    // `max(navigationBars, systemGestures, tappableElement)` —— 手势导航下 systemGestures
    // 胜出（真机 112px），而真正有系统像素的小白条只有 navigationBars（56px）。
    // 于是内容区被那 56px 的差额整体顶高，图标离屏幕底边过远、视觉重心偏上。
    //
    // 手势热区只拦上滑手势、**不影响绘制**（栏本来就横跨整条热区），所以下方只需要让开
    // 系统栏本体；差额挪到内容区**上方**，栏总高 = topPad + 内容区 + bottomPad 与改之前
    // 逐像素相等 —— 页面底部留白、进出场滑动距离、窗口高度全部不受影响。
    //
    // 三键导航下 navigationBars(48dp) 通常就是 reserved 的胜出者 ⇒ topPad = 0、
    // bottomPad = 全部，与改之前完全一致（那时按键带确实需要整段让开）。
    val systemBarPx: Int = LocalCapsuleBottomSystemBar.current
    // ★ 2026-09-24 第二轮：在系统栏本体之上再多留 [DOCK_ICON_LIFT]，把内容整体往上抬。
    //   总高仍由下面那条恒等式锁住（topPad + 内容 + bottomPad ≡ reservedDp），
    //   抬升是在预留区**内部**重新分配，栏高一个像素不变。
    //   `coerceAtMost(reservedDp)`：横屏 / 无导航栏时 reserved 可能比这个和还小，
    //   夹住才不会让 topPadDp 变成负数（Dp 允许负值，Spacer 会直接抛测量异常）。
    val bottomPadDp: Dp =
        (with(density) { systemBarPx.toDp() } + DOCK_ICON_LIFT).coerceAtMost(reservedDp)
    val topPadDp: Dp = reservedDp - bottomPadDp



    // 把「栏的实测总高」回写给窗口宿主。
    //
    // 两个消费者，缺一不可（这两处原先都只由 UfiCapsuleTabBar 供数，通栏迁移时差点漏掉）：
    // ① 窗口高度：`applyCapsuleWindowParams` 用它代替 WRAP_CONTENT，窗口恰好只有栏那么高 ——
    //    窗口不能占满全屏，否则栏以上区域的触摸全被这个 Dialog 圈走（FLAG_NOT_TOUCH_MODAL
    //    只放行**窗口之外**的触摸，见 §5.4）；
    // ② 页面底部 inset：`CapsuleInsetHolder` 的发布闸门是 `naturalSize.height > 0`，
    //    没人写就永远不放行，所有读 `ufiCapsuleBottomInset` 的页面会一直停在 88dp 兜底值。
    //
    // 实测值而非「58 + reserved」常量：本 Column = 内容区 + 安全区 Spacer，实测总高天然等于
    // 那个常量式，且 fontScale 放大时会自己长高（§5.13 —— 常量式在 1.5× 下会比真实栏矮几 dp）。
    val naturalSize = LocalCapsuleNaturalSize.current

    // ══ 选中药丸的位置驱动（2026-09-24，迁移阶段 2.7：从 UfiCapsuleTabBar 移植）══════════
    //
    // ## 为什么必须移植，而不是继续用 animateFloatAsState(safeIndex)
    // 5 个 Tab 页仍然可以横滑切换（`MainNavGraph` 的 `UfiPageSwitcher(swipeEnabled = true)`
    // 没有被删，被删的只是「在栏本体上横向拖拽切 tab」那套 draggable，见计划 §5.2）。
    // 只跟整数下标的话，药丸要等页面落定、`mainTabIndex` 回吐之后才**跳**过去 ——
    // 悬浮胶囊时代它是逐帧跟着 pager 连续进度走的，这是通栏迁移带来的观感退化。
    //
    // 下面这套状态与三条分支是胶囊 2026-09-03 ~ 09-05 四轮真机调试的结论，
    // 每条注释都标了「它防的是哪个真机现象」。**不要凭"看起来更简洁"改写**。
    //
    // 与胶囊的差异（通栏没有收起态）：`expanded` / `resetToken` / 标签淡入淡出
    // 那几条相关的写入点一律不移植，本栏也没有「滑动中展开胶囊」这回事。

    // 归位目标读的「最新选中下标」。
    // 必须是 rememberUpdatedState 而不是直接捕获 safeIndex：归位发生在 delay / 等待之后，
    // 那一刻要取**此刻**的下标。用 effect 重启那一瞬捕获的旧值会把已经停在新格的药丸往回拽
    //（2026-09-04 第二版真机现象：「切换完成后还会往后抽一下」）。
    // 它的返回对象身份稳定，读它不会让 effect 重启。
    val latestIndex: State<Int> = rememberUpdatedState(safeIndex)

    // pager 的连续页位置（下标空间，可为小数；静止时是整数；宿主未挂载时是 NaN）。
    // 宿主以 lambda 形态下发（`MainNavGraph:881-884`），provides 本身不读 floatValue，
    // 所以滑动时这一行与整棵子树都不重组 —— 真正的读取推迟到协程 / 绘制期。
    val selectionProgressProvider: () -> Float = LocalUfiCapsuleSelectionProgress.current

    // 「正在横滑」判据：连续进度偏离整数页即为真。
    //
    // ★ 刻意只保留 State 容器、**不写成 `var … by remember`**（胶囊 2026-09-05 第四版）：
    //   后者是**组合期读**，而它同时是归位 effect 的输入 —— 横滑一次必然翻转两下
    //  （起手 false→true、落定 true→false），每一下都让整条栏重组一次。
    //   本组件活在独立 Dialog 窗口里，一次重组要重测量、重绘、再提交那个窗口，
    //   而这两下恰好落在「药丸还在跑归位动画」的可见窗口里 ⇒ 肉眼可见的 hitch。
    //   现在所有读取都在协程内（`snapshotFlow { isScrollingState.value }`）。
    val isScrollingState: MutableState<Boolean> = remember { mutableStateOf(false) }

    // 「本次归位是否紧跟一段滑动」。
    // 只有这种归位需要付 [IDLE_SETTLE_CONFIRM_MS] 防抖窗（手势收尾时 isScrolling 会抖）；
    // 点击路径不存在中途态，必须零延迟，否则就是「点了不跟手 / 没有即点即达」。
    // 写入点恰好两处：横滑（下面这条收集器）置真、点 Tab（[DockTab] 的 onClick）清零。
    var motionSettlePending: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val p = selectionProgressProvider()
            if (p.isNaN()) 0f else abs(p - p.roundToInt())
        }.collect { offset ->
            val scrolling = offset > DOCK_SCROLLING_EPSILON
            isScrollingState.value = scrolling
            if (scrolling) motionSettlePending = true
        }
    }

    // 「钉住目标格」的请求：非空表示药丸必须待在该格、**不许**去跟 pager 的扫场进度。
    //
    // ⚠ 它防的不是拖拽（拖拽已删），而是 pager 的**逐页扫场**：跨多页切换时
    // `selectionProgress` 是从起点一路扫过来的，药丸若跟着它，观感就是「先被拽回原处、
    // 再追一遍」。点第 1 格直接跳第 4 格就会触发，所以点击路径必须设它（见 [DockTab] 的 onClick）。
    var settleIndex: Int? by remember { mutableStateOf(null) }

    // ── 药丸归位与页面转场同一条时间轴 ──────────────────────────────────────────
    //
    // 胶囊 2026-09-05 第三版的结论：药丸原来走自己的 spec，页面走
    // `tween(navTransitionMs, 0.4/0/0.2/1)`，**时长不同、曲线不同、到位时刻也不同** ——
    // 这种不同步本身就被感知为「不跟手」。三个输入与 `MainNavGraph` 逐字相同：
    // ThemeManager（第一手设置源）+ `LocalUfiReduceMotion`（系统降低动效）+
    // 同一个归一化函数 [ufiNavTransitionDurationMs]（含「关闭档 0」「未播种 -1」两档）。
    val appContext = LocalContext.current.applicationContext
    val themeManager = remember { ThemeManager(appContext, observeExternal = true) }
    // 反注册 prefs 监听：不解的话本 Dock 销毁后监听仍挂在进程级 SharedPreferences 上。
    DisposableEffect(themeManager) { onDispose { themeManager.dispose() } }
    val rawTransitionDurationMs by themeManager.transitionDurationMs.collectAsState()
    val systemReduceMotion = LocalUfiReduceMotion.current
    val navTransitionMs: Int = ufiNavTransitionDurationMs(rawTransitionDurationMs, systemReduceMotion)
    // rememberUpdatedState：归位 effect 的 key 里没有时长（改个设置不该打断正在跑的归位），
    // 但 effect 内必须读到**此刻**的 spec，不能是首次组合那份。
    val settleSpecState: State<AnimationSpec<Float>> = rememberUpdatedState(
        remember(navTransitionMs) { indicatorSettleSpec(navTransitionMs) }
    )

    // 药丸位置（下标空间，动画中可为小数）。
    //
    // ★ 必须是 [Animatable] 而不是 `animateFloatAsState`：后者只能追一个**整数目标**，
    //   给不出「滑动期逐帧 snapTo(连续进度) 跟手、落定后 animateTo(整格) 归位」这两种模式。
    // ★ 存的是**下标**而不是像素：格宽要等 Row 测完才知道，把像素塞进 Animatable 的话
    //   每次尺寸变化（转屏 / fontScale）都会打断正在跑的滑动。
    val indicatorPos: Animatable<Float, AnimationVector1D> =
        remember { Animatable(safeIndex.toFloat()) }

    // ── 唯一一条位置驱动：三条分支（钉住 / 跟手 / 归位）──────────────────────────
    //
    // 判据走 [DockSettleProbe] 在**协程内**读（理由见 isScrollingState 的注释）：
    // data class 的 equals + distinctUntilChanged ≡「key 是否变化」；
    // collectLatest 的「新值到达即取消上一次 body」≡「key 变了就取消并重启 effect」。
    // ⚠ 不要把 `selectionProgressProvider()` 加进探针：它每帧都变，会把本流变成每帧一次的
    //   发射源；它只在「跟手」那条分支体内用嵌套 snapshotFlow 现读。
    LaunchedEffect(indicatorPos) {
        snapshotFlow {
            DockSettleProbe(
                index = latestIndex.value,
                scrolling = isScrollingState.value,
                settleIndex = settleIndex,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                val settling = probe.settleIndex
                if (settling != null) {
                    // A) 钉住目标格：点击（含跨多页）走这里。
                    // 等 pager **真正**停在目标页之后才交还控制权，否则中途会被 B 分支接手、
                    // 药丸回头去跟那条从起点扫过来的进度（= 被拽回原处再追一遍）。
                    // 等待条件读 `latestIndex.value` / `isScrollingState.value` 的**实时值**：
                    // 本 body 的闭包不随组合更新，只能直接读唯一真相源。
                    indicatorPos.animateTo(settling.toFloat(), settleSpecState.value)
                    snapshotFlow { latestIndex.value == settling && !isScrollingState.value }.first { it }
                    settleIndex = null
                } else if (probe.scrolling) {
                    // B) 跟手：逐帧 snapTo 连续进度。
                    // 这一段**不能再套一层平滑**（弹簧/tween 都不行）—— 手指在拖，任何插值都是延迟。
                    // NaN 直接跳过：那是「宿主没挂载 / 没发布过进度」，snapTo(NaN) 会把药丸画飞。
                    snapshotFlow { selectionProgressProvider() }.collect { p ->
                        if (p.isNaN()) return@collect
                        indicatorPos.snapTo(p.coerceIn(0f, tabs.lastIndex.toFloat()))
                    }
                } else {
                    // C) 归位：不滑不钉时药丸必须停在**整格**，绝不允许卡在两格中间。
                    //
                    // 2026-09-04（「拖动界面切页时药丸抽搐」）：不能一进来就归位。页面拖动的收尾
                    // 阶段 isScrolling 会短暂抖成 false（fling 结束到 settle 开始之间有空帧），
                    // 立刻归位 ⇒ 药丸被从跟手位置猛拽向整格，下一帧 isScrolling 又变 true、
                    // 再 snapTo 回连续进度，来回两下就是肉眼可见的抽搐。
                    // 防抖窗期间只要判据变化（滑动恢复），本次归位自动取消，什么都不会发生。
                    //
                    // 2026-09-04 第二版（「切换完成后还会往后抽一下」）：只加延迟不够 ——
                    // 手势 settle 的新下标要等 `onSelectedIndexChange → mainTabIndex → 重组`
                    // 才到，新页首帧组合一旦超过确认窗（真机很常见，新 Tab 要建整棵子树），
                    // 就会拿着**旧下标**把已经停在新格的药丸往回拽。修法：目标读
                    // [latestIndex] 的此刻值（唯一真相源），并且差距小于 [SETTLE_SNAP_EPSILON]
                    // 时直接 snap，不跑一次没必要的动画。
                    //
                    // ⚠ 归位目标**绝不能**改读 `selectionProgressProvider()`（2026-09-05 已回退）：
                    //   那是第二个真相源，进过二级页后（宿主子树被 NavHost 销毁）它停在旧值上，
                    //   会把药丸永久锁在旧格 ——「栏上显示 a、实际在 b」。见 [settledIndexOf] 的 KDoc。
                    //
                    // 2026-09-05 第二版（「横滑切页时药丸先到目标格、往回抽一下、再回目标」）：
                    // 只延迟仍不够，因为两个判据用的是**不同的时钟** —— 本栏判「不滑了」看连续
                    // 进度是否落进整数带（pager 的 settle 还没结束就成立），而 `mainTabIndex` 的
                    // 回吐被要求在 `isScrollInProgress == false` 之后才发生，间隔常 >
                    // [IDLE_SETTLE_CONFIRM_MS]。所以归位必须加一个前提：**等权威追上**。
                    // 见 [awaitDockSettleAuthority]（内含 NaN / 过期 / 超时三条兜底）。
                    //
                    // 2026-09-05 第三版（即点即达）：防抖窗改为**有条件**（[settleNeedsConfirmWindow]）
                    // —— 手势收尾照付，点击零延迟。
                    awaitDockSettleAuthority(latestIndex, selectionProgressProvider, motionSettlePending)
                    val target = settledIndexOf(latestIndex.value, tabs.lastIndex)
                    val targetF = target.toFloat()
                    if (abs(indicatorPos.value - targetF) <= SETTLE_SNAP_EPSILON) {
                        if (indicatorPos.value != targetF) indicatorPos.snapTo(targetF)
                    } else {
                        indicatorPos.animateTo(targetF, settleSpecState.value)
                    }
                }
            }
    }

    // RTL：`Row` 会被 Compose 自动镜像，但药丸的横向偏移是**手算**的（indicatorPos × 格宽），
    // 手算值不会跟着镜像 —— 不取反的话 RTL 下药丸会停在视觉相反的那一格上（§5.19）。
    // 这里只在组合期算出「要不要取反」这个布尔，取反本体留在绘制期 —— 因为参与取反的
    // `indicatorPos.value` 每帧都变，在组合期读它就等于每帧重组整条栏。
    val mirrorPos: Boolean = layoutDirection == LayoutDirection.Rtl

    // ══ 每个 Tab 一份「选中度」：取色的连续因子（2026-09-24，从 UfiCapsuleTabBar 移植）══════
    //
    // ## 为什么取色不能继续用布尔
    // 原来是 `isSelected = index == safeIndex` ⇒ 颜色只在页面**落定**那一刻翻转。
    // 药丸接上 pager 连续进度之后，横滑到两格之间时药丸早已滑走，旧格的图标与标签却还顶着
    // 「药丸内配色」—— 浅色主题下那是白色（见 [dockPillContentColor]），于是白字白图标压在
    // 几乎同色的栏底色上，约 200~400ms 完全不可读。这是上一批接连续进度时引入的观感缺口。
    //
    // 修法与胶囊同源：每格一份 [Animatable]，0 = 未选中端、1 = 药丸内端，取色在**绘制期**
    // 按它 lerp（见 [DockTab]）。颜色与药丸因此是同一段运动的两个面，而不是两套各自的时序。
    //
    // ★ 因子按**逻辑下标**算（`1 - |progress - index|`），RTL 下**不取反**（§5.19）：
    //   需要镜像的只有药丸那个手算的横向偏移（上面的 mirrorPos）；选中度活在下标空间里，
    //   与书写方向无关。顺手把它也取反的后果是 RTL 下「亮的那一格」与药丸各在一边。
    val selectionFactors: List<Animatable<Float, AnimationVector1D>> = remember(tabs.size) {
        List(tabs.size) { index -> Animatable(if (index == safeIndex) 1f else 0f) }
    }

    // ── 取色驱动：与药丸驱动**同一份探针、同一份 motionSettlePending、同一条 spec** ─────
    //
    // 三处同源都是硬结论，各自对应一个真机现象：
    // ① 同一份 [DockSettleProbe]（同样在协程内读，理由见它的 KDoc 与 isScrollingState 那段）——
    //    判据不同步时两条驱动会在不同帧切分支，观感是「药丸已到位、图标还在抽」这种半修好状态；
    // ② 同一份 `motionSettlePending` + 同一个 [awaitDockSettleAuthority] —— 胶囊 2026-09-05
    //    第二版的结论：只给滑块加「等权威追上」时，着色仍会在 latestIndex 还是旧格时
    //    animateTo 回旧格，于是**只修好一半**，另一半照旧抽。第三版把防抖窗改成有条件之后，
    //    两条驱动继续读同一份 pending，才能保持「手势收尾照付防抖窗、点击零延迟」这同一套时机；
    // ③ 同一条 [settleSpecState]（= 与页面转场同源的 tween）。胶囊那边着色走 200ms 的
    //    `SELECTION_SPEC`、滑块走 380ms，两者到位时刻不同。通栏刻意**不复活**那个 200ms 档
    //    （`DOCK_SELECTION_SPEC` 已在上一批删掉，见文件末尾的墓碑），颜色与药丸同时落定，
    //    也因此没有引入任何新的时长常量。
    LaunchedEffect(selectionFactors) {
        snapshotFlow {
            DockSettleProbe(
                index = latestIndex.value,
                scrolling = isScrollingState.value,
                settleIndex = settleIndex,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                val settling = probe.settleIndex
                if (settling != null) {
                    // A) 钉住目标格：点击（含跨多页）走这里。直接点亮目标格并保持，
                    //    **不跟** pager 的逐页扫场进度 —— 跟了就是「沿途每一格被依次点亮一遍」，
                    //    正是胶囊 2026-09-01 删掉的那条"扫荡 / 拖拉"观感。
                    //    与药丸的 A 分支同时开跑、同一条 spec，所以药丸停在哪格、亮的就是哪格。
                    coroutineScope {
                        selectionFactors.forEachIndexed { index, factor ->
                            launch {
                                factor.animateTo(
                                    if (index == settling) 1f else 0f,
                                    settleSpecState.value
                                )
                            }
                        }
                    }
                } else if (probe.scrolling) {
                    // B) 跟手：逐帧 snapTo。相邻两格此消彼长，非相邻格恒 0（被 coerceIn 夹掉）。
                    //    这一段**不能再套一层平滑**：手指在拖，任何插值都是延迟 —— 那又变成
                    //    「药丸跟手、颜色慢半拍」的另一种不同步。
                    //    NaN 直接跳过（宿主未挂载 / 从未发布过进度），否则整栏会被着成非法色。
                    snapshotFlow { selectionProgressProvider() }.collect { p ->
                        if (p.isNaN()) return@collect
                        selectionFactors.forEachIndexed { index, factor ->
                            factor.snapTo((1f - abs(p - index)).coerceIn(0f, 1f))
                        }
                    }
                } else {
                    // C) 归位：不滑不钉时「点哪个亮哪个」，且必须与药丸走**同一条**
                    //    「确认再归位」规则（见 indicatorPos 的 C 分支）。
                    //    少了这条等待的真机现象有两个，与药丸那边逐字同源：
                    //    - 页面拖动收尾的空帧里 isScrolling 抖成 false ⇒ 亮度被拽回整格、
                    //      下一帧又跟手，图标亮度来回跳一次（2026-09-04）；
                    //    - 归位时 latestIndex 还是旧格 ⇒ 先亮回旧格、回吐抵达后再亮到新格
                    //      （2026-09-04 第二版那条「往后抽一下」在颜色上的版本）。
                    //    目标同样取 latestIndex（唯一真相源），**绝不能**读
                    //    `selectionProgressProvider()`：那是第二个真相源，进过二级页后停在旧值上，
                    //    会把亮着的那一格永久锁在旧位置（见 [settledIndexOf] 的 KDoc）。
                    awaitDockSettleAuthority(latestIndex, selectionProgressProvider, motionSettlePending)
                    val target = settledIndexOf(latestIndex.value, tabs.lastIndex)
                    coroutineScope {
                        selectionFactors.forEachIndexed { index, factor ->
                            val goal = if (index == target) 1f else 0f
                            // 已经停在端点上就不跑动画：点击同页、或反复落定同一格时，
                            // 省掉一条完全没有视觉变化的逐帧重绘流。
                            if (factor.value != goal) {
                                launch { factor.animateTo(goal, settleSpecState.value) }
                            }
                        }
                    }
                }
            }
    }

    // 栏底色。见 [dockSurfaceColor] —— 它是**跨文件的单一真源**（窗口层要用同一个值
    // 去定手势小白条的明暗，见 UfiCapsuleBlurHost 的 `isAppearanceLightNavigationBars`）。
    val dockSurface: Color = dockSurfaceColor(palette)


    // 顶边发丝线：栏与「几乎同色的页面底色」之间唯一的分界（§5.17）。
    //
    // ★ 2026-09-24（用户定稿，第三轮）：上缘那条 6dp 高光带**已删除**。
    //   它先是白色渐变（浅色主题下白底加白，读不出来），改成主题色实色后又变成一条
    //   6dp 的彩色横条 —— 观感上像"底栏自己又长了个顶栏"。朴素形态只留 1 物理像素的发丝线。
    val topHairline: Color = palette.divider.copy(alpha = 0.62f)



    Column(
        modifier = modifier
            .fillMaxWidth()
            // 相等性判断不可省：onSizeChanged 的回调里直接无条件写 MutableState 会
            // 「写 → 重组 → 重新测量 → 再写」空转；只在真的变了时写一次才收敛。
            .onSizeChanged { size -> if (naturalSize.value != size) naturalSize.value = size }
            // ★ clip 必须在 drawBehind **之前**（§5.15）：底色 / 上缘高光 / 发丝线三者都要被
            //   22dp 上圆角裁住。发丝线画在 y=0，被裁后在圆角处自然收窄 —— 这正是想要的收边，
            //   不要为了"线要通到边"把 clip 去掉。
            .clip(DOCK_SHAPE)
            .drawBehind {
                // 1) 底色：铺满整个 Box，**含安全区那一段** —— 沉浸的本质就是这一句。
                //    实色，不是渐变（理由见上方 dockSurface 的注释）。
                drawRect(color = dockSurface)

                // 2) 顶边发丝线。用裸 1f 而不是 1.dp.toPx()：发丝线要的是**恒 1 物理像素**，
                //    换算成 dp 后在 3x 屏上会变成 3px 的粗边，就回到"贴纸边"那种廉价感了。
                //
                //    ★ 已删除（2026-09-24）：夹在底色与发丝线之间的那层「上缘 6dp 高光带」
                //      （`drawRect(brush = Brush.verticalGradient(topSheen → Transparent), 6dp)`，
                //      后改为主题色实色）。理由见上方 topHairline 的注释：6dp 彩条像第二个顶栏。
                drawRect(color = topHairline, size = Size(size.width, 1f))
            }
    ) {
        // 预留区的**上半段**（手势热区超出系统栏本体的那部分）。只有底色、没有内容。
        // 它与下方那段 Spacer 加起来恒等于 reservedDp ⇒ 栏总高与拆分之前逐像素相同。
        // 三键导航下通常为 0（那时 navigationBars 就是 reserved 的胜出者）。
        Spacer(modifier = Modifier.fillMaxWidth().height(topPadDp))

        // 内容区。外层 Box 负责把 tabs 行水平居中，tabs 行自己限宽 —— 背景仍由上面的
        // drawBehind 通栏铺满，两者分离是 §3 的既有结构，§5.12 的限宽约束搭在这个结构上。
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    // ★ 2026-09-25（用户定稿："图标往中间靠一点点，药丸左右大一点，当前太瘦"）：
                    //   tabs 行加横向内边距 [DOCK_ROW_H_PADDING]。这是唯一能同时满足三件事的杠杆：
                    //     ① 药丸变宽 —— 格宽虽然小了，但药丸的内缩从 16dp 降到 8dp，净宽 +12.8dp；
                    //     ② 图标往中间靠 —— 格心内移，图标边距 26.3 → 32.7dp；
                    //     ③ 药丸外边缘仍压在卡片那条线上 —— 因为
                    //        `DOCK_ROW_H_PADDING + PILL_INSET_H ≡ Spacing.CardHorizontalMargin`
                    //        （这条恒等式写在 PILL_INSET_H 的定义里，不是靠注释维持）。
                    //   padding 排在 widthIn **之前**：于是 480dp 限的是「内容带」，外框上限 480+8×2。
                    .padding(horizontal = DOCK_ROW_H_PADDING)
                    // §5.12 超宽屏 / 平板 / 折叠屏展开：800dp 宽时每格 200dp，图标散得极开、观感崩坏。
                    // widthIn 在 fillMaxWidth 之前：先把可用 maxWidth 夹到 480dp，再让 Row 填满它。
                    .widthIn(max = DOCK_TABS_MAX_WIDTH)
                    .fillMaxWidth()
                    // ★ heightIn(min) 而不是 height：系统字体放到 1.5× 时 10sp 标签渲染成 15sp，
                    //   固定高度会把内容压扁 / 裁切（§5.13）。允许长高，栏总高随之变大。
                    .heightIn(min = DOCK_CONTENT_MIN_HEIGHT)
                    .drawBehind {
                        // 选中药丸画在内容区的 drawBehind 里，因此它的垂直范围天然只覆盖**内容区**，
                        // 不会把安全区算进去 —— 否则三键导航下（安全区≈48dp）药丸会明显偏下（§5.1）。
                        //
                        // ★ `indicatorPos.value` 只在**这里**读，也就是**绘制期**读。
                        //   提到组合期（例如先算一个 `val visualPos = …` 再传进来）的话，滑动时
                        //   每帧都会让整条栏重组、重测量、并重新提交那个 Dialog 窗口 ——
                        //   与宿主「以 lambda 形式下发进度，滑动时零重组」（`MainNavGraph:881-884`）
                        //   的用意正好相反。drawBehind 里读只订阅绘制，逐帧变化只触发重绘。
                        val pos = indicatorPos.value
                        drawSelectionPill(
                            visualPos = if (mirrorPos) tabs.lastIndex - pos else pos,
                            tabCount = tabs.size,
                            accent = palette.accent
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    DockTab(
                        tab = tab,
                        // 取色因子以**取值函数**形态下发，不是算好的 Float / Color：
                        // 传值就必须靠重组把每帧的新值送进去，而本栏活在独立 Dialog 窗口里，
                        // 一次重组 = 重测量 + 重绘 + 重新提交那个窗口，正好抵消上一批
                        // 「滑动期整栏零重组」的成果（与宿主以 lambda 下发进度同一个用意）。
                        selectionFactor = { selectionFactors[index].value },
                        // 仍是**落定后的整数选中态**：只喂无障碍语义（§5.20）与字重档位，不参与取色。
                        isSelected = index == safeIndex,
                        palette = palette,
                        // weight(1f)：格宽等分（屏宽 ÷ tabs.size），不再随任何进度插值。
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // 钉住目标格：点第 1 格直接跳第 4 格时 pager 同样会**逐页扫场**，
                            // selectionProgress 从起点一路扫过来；药丸若去跟它就是「被拽回原处
                            // 再追一遍」。钉到目标格直到 pager 真的停在目标页（分支 A），
                            // 观感才是即点即达。
                            settleIndex = index
                            // 点击路径没有「滑动中途态」：显式清掉，让归位跳过防抖确认窗
                            //（即点即达，见 [settleNeedsConfirmWindow]）。
                            // 若此刻页面其实还在滑，上面那条 isScrolling 收集器会立刻把它重新置真，
                            // 于是照旧付防抖窗 —— 朝保守方向出错，不会引入抽搐。
                            motionSettlePending = false
                            onTabSelected(index)
                        }
                    )
                }
            }
        }


        // 预留区的**下半段** = 系统栏本体（小白条 / 三键按键带）那一条。
        // 图标不压小白条靠的就是这一段，而不是把整条栏抬起来。
        // 横屏下可能是 0（导航栏移到侧边），此时图标贴屏幕底边 —— 那是正确行为（§5.9）。
        //
        // ★ 2026-09-24：这里原来垫的是**整段** reservedDp（含手势热区），图标因此被顶高
        //   约 (systemGestures − navigationBars) ≈ 56px，真机反馈"图标离屏幕下部分太远"。
        //   拆分后总高不变、图标下移，见上方 topPadDp / bottomPadDp 的推导。
        Spacer(modifier = Modifier.fillMaxWidth().height(bottomPadDp))
    }
}

/**
 * 通栏低栏里的单个 Tab：图标 [DOCK_ICON_SIZE] + 标签常显，垂直居中。
 *
 * （这里原来写死"图标 24dp"，而 2026-09-24 已经调成 26dp —— 陈旧文案顺手改成引用常量，
 * 免得下次改尺寸又漏一处。）
 *
 * @param selectionFactor 本格「选中度」的**取值函数**（0 = 未选中端、1 = 药丸内端）。
 *   刻意不是 `Float` / 算好的 `Color`：形参若是值，每帧的新值只能靠**重组**送进来，
 *   而本栏活在独立 Dialog 窗口里 —— 一次重组要重测量、重绘、再提交那个窗口，
 *   与上一批「滑动期整栏零重组」的成果正好相抵。传函数则读取推迟到绘制期。
 * @param isSelected **落定后的整数选中态**。只用于两件事，都与颜色无关：
 *   ① 无障碍语义（§5.20 要求 `selected` 是离散值 —— 给连续量的话 TalkBack 会在横滑途中
 *      反复播报选中变化）；② 字重档位（字重变化要重新测量文本，不能每帧变）。
 */
@Composable
private fun DockTab(
    tab: CapsuleTabItem,
    selectionFactor: () -> Float,
    isSelected: Boolean,
    palette: ResolvedPalette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    // 取色的两个**端点**，一个都没动（组合期算一次即可：它们只随主题变）。
    // 选中端仍走 [dockPillContentColor]（§5.14 那条 accent 亮度判据，不许绕过），
    // 未选中端仍是 textSecondary。中间值由绘制期 lerp 产生，所以「落定后」的观感与上一版相同，
    // 变的只是**两格之间那段**：颜色跟着药丸走，不再等落定才翻转。
    val unselectedColor: Color = palette.textSecondary
    val selectedColor: Color = dockPillContentColor(palette)

    // 标签样式在组合期定好（含字重档位），**颜色不进 style** —— 见下面的 ColorProducer。
    // 原来是 material3 `Text(style = capsuleLabel, fontSize = …, fontWeight = …)`：
    // 显式传 style 时它不再合并 LocalTextStyle，把 fontSize / fontWeight 并进来就是它内部
    // 那份 mergedStyle，因此换到 BasicText 后字体度量（行高、字距）与之前逐字相同。
    val labelStyle: TextStyle = remember(isSelected) {
        UfiTextStyles.capsuleLabel.copy(
            fontSize = DOCK_LABEL_SIZE,
            fontWeight = if (isSelected) UfiWeight.Emphasis else UfiWeight.Medium
        )
    }

    Box(
        modifier = modifier
            // ★ 2026-09-24（真机：整屏都变成导航栏）：这里原来是 `fillMaxHeight()` + `heightIn(min=48dp)`。
            //   通栏形态下 `fillMaxHeight` 是致命的 —— tabs 行只有 `heightIn(min = 58dp)`，
            //   **maxHeight 没有上界**：底栏 Dialog 的窗口高是 WRAP_CONTENT / 实测高，
            //   传给内容的 maxHeight 仍然是整屏高度。于是单格撑满整屏 → Row → Box → 根 Column
            //   一路被撑满，根 Column 的 drawBehind 底色随之铺满整个屏幕 = 「整屏都是导航栏」。
            //   悬浮胶囊时代不会暴露：那时窗口是 wrap-content 的小窗，maxHeight 本身就只有胶囊那么高。
            //
            //   改成 `defaultMinSize`：只给格高**下限**，上限交给内容 ⇒ Row 的高度仍是最高的那一格
            //   （= 58dp，fontScale 放大时一起长高），整条栏不会再被撑开。
            //   下限取两个约束的较大者：内容区基准 58dp（§1 参数表）与 48dp 触摸目标下限（§5.20），
            //   写成 maxOf 而不是直接写 58 —— 将来有人把内容区调矮时，触摸目标不会跟着破线。
            .defaultMinSize(minHeight = maxOf(DOCK_CONTENT_MIN_HEIGHT, DOCK_MIN_TOUCH_TARGET))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            // §5.20 无障碍：语义挂在**整格**上，且 `selected` 必须是落定后的整数态。
            // 图标不再是 `Icon`（见下），但它本来的 contentDescription 就是 null ——
            // 标签常显后「图标描述 + 可见文字」会被朗读两遍，所以语义只有整格这一处。
            .semantics {
                role = Role.Tab
                selected = isSelected
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DOCK_ICON_LABEL_GAP)
        ) {
            // 图标：`Icon(tint = …)` 的 tint 只吃组合期算好的 Color，用它就等于把因子读到组合期
            // （滑动时每帧重组整条栏）。换成「painter + drawWithCache」—— 与胶囊 CapsuleTab 里
            // 图标的写法同源：因子在 onDrawWithContent 内读，订阅的是**绘制**，逐帧变化只重绘。
            // 这里不带胶囊那份 alpha 斜坡（ICON_ALPHA_UNSEL）：本栏未选中端是实色 textSecondary，
            // 端点必须保持不变。
            val iconPainter = rememberVectorPainter(tab.icon)
            Spacer(
                modifier = Modifier
                    .size(DOCK_ICON_SIZE)
                    .drawWithCache {
                        onDrawWithContent {
                            val factor = selectionFactor().coerceIn(0f, 1f)
                            with(iconPainter) {
                                draw(
                                    size,
                                    colorFilter = ColorFilter.tint(
                                        lerp(unselectedColor, selectedColor, factor)
                                    )
                                )
                            }
                        }
                    }
            )
            // 标签：走 [BasicText] 的 `color: ColorProducer` 重载。material3 的 `Text` 没有这个
            // 口子（它的 color 是组合期的 Color），而 ColorProducer 的 lambda 由文本节点在
            // **绘制**时才调用 —— 因子的读取因此和图标、药丸落在同一个相位上。
            // 其余参数与原来的 Text 等价（style 已并入 fontSize / fontWeight，见 labelStyle）。
            BasicText(
                text = tab.label,
                style = labelStyle,
                color = ColorProducer {
                    lerp(unselectedColor, selectedColor, selectionFactor().coerceIn(0f, 1f))
                },
                // 绝不允许换行：一旦换行内容区会被顶破 58dp，栏总高与页面 inset 一起跳（§5.11）。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── 选中药丸 ────────────────────────────────────────────────────────────────

/**
 * 在内容区背后画选中药丸：**一块实色 accent 的圆角矩形**，没有别的。
 *
 * ## 已删除的三层装饰（2026-09-24，用户定稿"改朴素点"）
 * - **外发光**：用 6 圈同心圆角矩形按 `(1-t)²` 衰减近似高斯（内圈 5dp 满 alpha、外到 14dp 归零）。
 *   删它是因为在实色底栏上它读不成"光"，只读成药丸边上一圈脏边；顺带省掉每帧 6 次
 *   `drawRoundRect`（药丸逐帧跟手，这 6 次是每帧都画的）。
 * - **160° 线性渐变填充**（accent → 略紫 `#7C4DFF` 混 22%）：用户已定"不要渐变"。
 * - **内顶 1px 白高光**：给药丸"上表面厚度"的那条线，同属"高光"一类。
 *
 * 想找回来请读 git 历史，不要凭记忆重写 —— 那三层的参数各有推导（尤其外发光的圆环近似）。
 *
 * @param visualPos 药丸位置（下标空间，已按 layoutDirection 取过反）。
 * @param tabCount  Tab 数，用来等分格宽。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionPill(
    visualPos: Float,
    tabCount: Int,
    accent: Color
) {
    if (tabCount <= 0) return

    val step = size.width / tabCount
    val insetH = PILL_INSET_H.toPx()
    val insetV = PILL_INSET_V.toPx()
    val left = visualPos * step + insetH
    val top = insetV
    val pillW = step - insetH * 2f
    val pillH = size.height - insetV * 2f
    if (pillW <= 0f || pillH <= 0f) return

    drawRoundRect(
        color = accent,
        topLeft = Offset(left, top),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(PILL_CORNER.toPx())
    )
}


/**
 * 药丸**内部**图标与文字的颜色。
 *
 * ## 这个判据不能省（§5.14）
 * 药丸是实色 accent，默认配白字。但项目有多套配色，某些主题的 accent 偏亮
 * （浅黄绿、浅青）—— 此时白字对比度不足 3:1，直接不可读。所以按 accent 的相对亮度择一：
 * 偏亮就改用 `textPrimary`（深色文字），偏暗才用白色。
 *
 * 刻意写成独立函数 + 本段注释：下次有人加主题时，如果只看到调用点的一个三元表达式，
 * 很容易"顺手"简化成恒白 —— 那是 2026-09-03「上一版主题残影」那类问题的同源形态。
 *
 * 0.55f 是取到「白字刚好还能过 3:1」的那条线；调它请连带真机核对最亮的那套配色。
 */
private fun dockPillContentColor(palette: ResolvedPalette): Color =
    if (palette.accent.luminance() > 0.55f) palette.textPrimary else Color.White

/**
 * 通栏低栏的底色 —— **跨文件的单一真源**。
 *
 * ## 为什么直接就是 `palette.cardBg`（浅色主题下即「白底」）
 * 2026-09-24 定稿。之前是 `lerp(cardBg, …)` 压暗一档再乘 0.92，本意是让栏比卡片稍深、
 * 好跟页面分开。真机上的结果是"脏"：浅色主题下白里发灰，而分界本来就已经由顶边发丝线
 * 承担了。直接用卡片底色，栏与卡片同色、观感干净，也不必再为深浅两个主题各写一套系数
 *（`palette` 本身已经按主题解析过，这就是用 palette token 而不是自己算颜色的意义）。
 *
 * ## ⚠ 它同时是「手势小白条该亮还是该暗」的判据来源
 * 小白条的明暗由**窗口的** `isAppearanceLightNavigationBars` 决定，而不是平台自动按背后
 * 像素反色（迁移计划 §5.3 原先的假设是错的，真机上小白条不会自己适配）。栏贴底之后，
 * 小白条正好压在本颜色上 ⇒ 判据必须用**这个**颜色的亮度，见 `UfiCapsuleBlurHost` 里
 * `CapsuleWindowMetrics.lightNavHandleSurface` 的接线。
 * 所以本函数是 `internal`：两个文件必须读同一个值，不允许各算一份。
 *
 * ## 不透明度
 * 恒不透明（cardBg 本身不透明）。**不要加 alpha**：本栏活在一个背景恒为 null 的 Dialog
 * 窗口里（红线 R1），alpha 会让下方页面像素直接透上来 —— 栏的明暗就变成"跟着当前页内容走"，
 * 滚动时底栏会呼吸，安全区那一段还会透出页面最底部内容，小白条的对比度也跟着不可控。
 */
internal fun dockSurfaceColor(palette: ResolvedPalette): Color = palette.cardBg


// ── 视觉常量（全部来自迁移计划 §1 的参数表）──────────────────────────────────

/**
 * 上方圆角 —— **已归零**（2026-09-24 用户定稿："右上角左上角的圆角过渡可以删除"）。
 *
 * 原为 22dp（§1 参数表的定稿值，18 偏方、26 以上有"抽屉"感）。贴底通栏 + 纯实色底之后，
 * 圆角在栏两端与页面之间切出两个小三角形的页面底色，反而像"没贴住"。
 *
 * ⚠ 保留 `RoundedCornerShape(0.dp)` 而不换成 `RectangleShape`：`clip(DOCK_SHAPE)` 那一句
 * 是护栏 `dockSurface_mustBeContentLayer_andWindowBackgroundStayNull` 的锚点之一
 * （它检查 `clip` 出现在 `drawBehind` **之前** —— 顺序反了圆角就裁不到底色）。
 * 将来若要把圆角改回非 0，改这里一处即可，结构不用动。
 */
private val DOCK_SHAPE = RoundedCornerShape(0.dp)

/**
 * 内容区**最小**高度 58dp = 图标 24 + 间距 3 + 标签 10sp≈14 + 上下 padding 8.5×2。
 * 是下限而不是定值，理由见调用点的 `heightIn` 注释（fontScale）。
 *
 * ## 为什么是 `internal` 而不是 `private`（2026-09-24，迁移阶段 2.3）
 * 页面底部 inset 的计算式（`UfiCapsuleBlurHost` 的 `capsuleBottomTotalDp`）要用同一个
 * 「内容区高」，公式是 `58dp + 安全区`。放宽可见性是为了让那边**复用本常量**，
 * 而不是另写一个 58 —— 两处各写一份数字迟早漂移。
 * 计划 §2.3 / §7-2.3 明确要求复用，见 `docs/bottom-dock-migration-plan.md`。
 */
internal val DOCK_CONTENT_MIN_HEIGHT: Dp = 58.dp

/** tabs 行的限宽（§5.12）。背景仍通栏铺满，只有这一行居中限宽。 */
private val DOCK_TABS_MAX_WIDTH: Dp = 480.dp

/**
 * 图标尺寸。
 *
 * ★ 2026-09-24：24 → 26dp（真机反馈"图标有点小"）。内容区仍是 58dp 下限，
 * 图标 26 + 间距 3 + 标签 10sp≈14 = 43dp，上下各余 7.5dp，不会顶破。
 * 再往上加建议先把内容区基准一起抬高，否则标签与图标会挤到一起。
 */
private val DOCK_ICON_SIZE: Dp = 26.dp

/** 图标与标签的间距。 */
private val DOCK_ICON_LABEL_GAP: Dp = 3.dp

/** 标签字号。 */
private val DOCK_LABEL_SIZE = 10.sp

/** 单格触摸目标下限（Material 无障碍要求）。 */
private val DOCK_MIN_TOUCH_TARGET: Dp = 48.dp

/**
 * 内容整体上抬量（2026-09-24 用户定稿："图标往上移动 8dp"）。
 *
 * 加在 `bottomPadDp` 上、从 `topPadDp` 里扣 —— 两者之和恒等于预留区，所以**栏总高不变**。
 * 上限被 `coerceAtMost(reservedDp)` 夹住：预留区本来就很小（横屏 / 无导航栏）时不抬，
 * 否则 topPad 会变负。
 */
private val DOCK_ICON_LIFT: Dp = 8.dp

/** 药丸相对单元格的纵向内缩（上下各一份）。 */
private val PILL_INSET_V: Dp = 4.dp

/**
 * tabs 行的横向内边距。
 *
 * ★ 2026-09-25（用户定稿："图标往中间靠一点点，药丸左右大一点，当前太瘦"）：`0` → `8.dp`。
 *
 * 它与 [PILL_INSET_H] 是**同一笔 16dp 的两次拆分**：
 * ```
 * 屏边 ──8dp(本常量)── 格0左缘 ──8dp(PILL_INSET_H)── 药丸左缘
 *  └────────────────── 16dp = Spacing.CardHorizontalMargin ──────┘
 * ```
 * 所以药丸外缘仍然压在卡片那条竖线上，但**格宽变小、药丸内缩变小 ⇒ 药丸净宽变大**：
 * 393dp 屏上格宽 78.6 → 75.4dp，药丸 46.6 → **59.4dp**（+27%）。
 * 图标随格心内移：边距 26.3 → **32.7dp**（往中间靠了 6.4dp），这正是用户要的。
 *
 * ⚠ 想再加大药丸只能继续加大本常量（同时 [PILL_INSET_H] 自动变小）。上限是
 * `Spacing.CardHorizontalMargin`（那时 `PILL_INSET_H = 0`、药丸与格同宽、相邻药丸会贴在一起）；
 * 实务上建议不超过 12dp。**不要**单独改 [PILL_INSET_H] —— 那会破坏上面那条恒等式，
 * 药丸外缘就不再与卡片对齐了。
 */
private val DOCK_ROW_H_PADDING: Dp = 8.dp

/**
 * 药丸相对单元格的横向内缩（左右各一份）。
 *
 * ★ 2026-09-25（用户定稿）：先由 `10.dp` 改为 [Spacing.CardHorizontalMargin]（对齐卡片），
 * 同日再按"药丸太瘦"的反馈拆成 `CardHorizontalMargin − DOCK_ROW_H_PADDING`（= 8dp）。
 *
 * ## 对齐关系是**算出来的**，不是注释维持的
 * ```
 * 药丸左缘(屏幕) = DOCK_ROW_H_PADDING + PILL_INSET_H ≡ Spacing.CardHorizontalMargin
 * ```
 * 这条恒等式由本常量的定义式本身保证 —— 改 [DOCK_ROW_H_PADDING] 时它自动跟着变，
 * 两个数怎么分都不会让药丸偏离卡片那条线。护栏
 * `dockPillInset_mustReferenceCardHorizontalMargin_notALiteral` 钉的就是这个定义式。
 *
 * 为什么真源必须是卡片那个 token（`Spacing.CardHorizontalMargin`）而不是字面量 `16.dp`：
 * 要表达的是「药丸外缘与卡片外缘同一条竖线」这个**关系**。写死数字的后果是静默失配 ——
 * 将来有人把卡片外边距调成 20dp，药丸还停在 16dp，编译绿、单测绿，只有真机能看出错开 4dp。
 *
 * ## 药丸宽度
 * 药丸宽 = 格宽 − 8×2。393dp 屏：内容带 377dp ⇒ 格宽 75.4dp ⇒ 药丸 **59.4dp**，
 * 比图标 26dp 宽 33dp、比最宽标签「仪表盘」约 30dp 宽 29dp，两侧各余约 14.7dp。
 * 360dp 窄屏：内容带 344 ⇒ 格宽 68.8 ⇒ 药丸 52.8dp，仍宽于 fontScale 1.5× 下的
 * 「仪表盘」（约 45dp）—— 拆分之前那一档是会顶出药丸的，这次顺带治好了。
 *
 * ## ⚠ 一条给后人的提醒
 * **图标与标签的边距不由本常量决定**，而是 `DOCK_ROW_H_PADDING + (格宽 − 内容宽)/2`。
 * 「药丸对齐卡片」与「图标对齐卡片」是**两件事**，用户 2026-09-25 明确只要前者 ——
 * 等分格 + 内容居中这套图标布局他认为很好，**不要**改成两端对齐 / 包裹内容
 *（别换 `Arrangement.SpaceBetween`、别把 `weight(1f)` 改成 wrap content）。
 * 屏宽 ≥ [DOCK_TABS_MAX_WIDTH] 时内容带被夹住并居中，对齐关系自然失效，那是有意的。
 */
private val PILL_INSET_H: Dp = Spacing.CardHorizontalMargin - DOCK_ROW_H_PADDING

/** 药丸圆角。内容区 58dp 减去上下各 4dp 后药丸高 50dp，15dp ≈ 三成半高，圆润但不满药丸。 */
private val PILL_CORNER: Dp = 15.dp

// ★ 已删除（2026-09-24，"改朴素点"）：药丸的外发光 / 渐变 / 内顶高光那一组常量 ——
//   PILL_GLOW_NEAR(5dp) / PILL_GLOW_FAR(14dp) / PILL_GLOW_ALPHA(0.42) / PILL_GLOW_RINGS(6) /
//   PILL_TOP_SHEEN_ALPHA(0.30) / PILL_GRADIENT_TINT(#7C4DFF) / PILL_GRADIENT_DX、DY(160°)。
//   连同 `pillFillBrush()` 一起退休，药丸现在是一块实色 accent 圆角矩形。
//   删除理由与各参数的推导见 `drawSelectionPill` 的 KDoc 与 git 历史。
//   同批删除的还有栏顶那条 6dp 高光带的 DOCK_TOP_SHEEN_HEIGHT。


/**
 * 「正在横滑」的判定带宽（下标空间）。
 *
 * 连续进度与最近整数页的距离超过本值即视为滑动中。**不要收得更小**：pager 静止时
 * 进度未必是精确整数（浮点累积），太紧会让「滑动中」在静止时误触发、
 * 于是药丸在归位与跟手两条分支之间来回横跳。与胶囊里那条 0.01f 判据同值。
 */
private const val DOCK_SCROLLING_EPSILON: Float = 0.01f

/**
 * 位置驱动的一组判据快照。
 *
 * ## 存在的唯一理由：把这些输入的读取从**组合期**挪进协程
 * 若直接把它们当 `LaunchedEffect` 的 key（组合期读），横滑一次 `scrolling` 必然翻转两下
 *（起手 false→true、落定 true→false），每一下都让整条栏重组；本栏活在独立 Dialog 窗口 +
 * 跨窗口渲染里，一次重组要重测量、重绘、再提交那个窗口，而这两下恰好落在药丸仍在动的
 * 可见窗口里 ⇒ 肉眼可见的 hitch。点击路径全程 `scrolling == false`，所以这两次重组
 * **只存在于横滑路径** —— 正是胶囊时代「点击不顿、横滑顿」的差异来源之一（2026-09-05 第四版）。
 *
 * ## 为什么等价
 * - `data class` 的 `equals` + `distinctUntilChanged()` ≡ 原来的「key 是否变化」；
 * - `collectLatest` 的「新值到达即取消上一次 body」≡ 原来的「key 变了就取消并重启 effect」。
 *
 * ⚠ **不要**把 `selectionProgressProvider()` 加进来：它每帧都变，会把本流变成每帧一次的
 * 发射源。它只在「跟手」分支体内用嵌套 `snapshotFlow` 现读。
 *
 * @param index 权威下标（`latestIndex.value`，即 `mainTabIndex` 那一份唯一真相）。
 */
private data class DockSettleProbe(
    val index: Int,
    val scrolling: Boolean,
    val settleIndex: Int?,
)

/**
 * 归位前的统一等待：先等权威追上（带超时兜底），必要时再走一个防抖确认窗。
 *
 * ## 为什么是本文件的一份副本，而不是直接调用胶囊那个同名函数
 * `UfiCapsuleTabBar.awaitSettleAuthority` 是 `private`（文件级私有），够不到；而它的
 * **可见性被护栏钉死** —— `CapsuleRegressionGuardTest.authorityCatchUp_mustHaveNaNAndTimeoutFallbacks`
 * 用字面量 `"private suspend fun awaitSettleAuthority"` 截取函数体，放宽成 `internal`
 * 会让那条护栏直接红灯。所以这里只复制**编排**，三条判据本体仍复用同一份
 * `internal` 纯函数（[authorityCaughtUp] / [settleNeedsConfirmWindow]）与同一组常量
 * （[AUTHORITY_CATCHUP_TIMEOUT_MS] / [IDLE_SETTLE_CONFIRM_MS]），不另写一套数字。
 * 阶段 3 删掉胶囊本体时，这两份编排应合并成一处。
 *
 * ## 三条兜底路径（缺一条就会把药丸锁死在旧格）
 * 1. 进度是 `NaN`（宿主未挂载 / 从未发布）—— [authorityCaughtUp] 立刻返回 true，零等待；
 * 2. 进度是**过期值**（宿主刚重建、还没发第一帧）—— 新宿主发出第一帧即自动一致，
 *    真的一直不一致时由第 3 条收尾；
 * 3. 超时（[AUTHORITY_CATCHUP_TIMEOUT_MS]）—— 放弃等待，直接归位。
 *
 * 三条路径的终点完全相同：按 `latestIndex` 归位。等待只影响**时机**、不影响**目标**，
 * 所以无论走哪一条，药丸都不可能停在与宿主不符的格子上。
 *
 * ## 防抖窗是**有条件**的（即点即达）
 * 判据见 [settleNeedsConfirmWindow]。这不会让「横滑落定往回抽」复活 —— 那个 bug 的成因是
 * 归位时读到**陈旧的** `latestIndex`，真正的修复是本函数第一段「等权威追上」；
 * 防抖窗只是附带的手势收尾保护，而它在手势路径上照旧全额支付（`followsMotion` 为真）。
 * 被省掉的只有「当帧权威就已一致、且此前不在滑动」的点击路径。
 */
private suspend fun awaitDockSettleAuthority(
    latestIndex: State<Int>,
    selectionProgressProvider: () -> Float,
    followsMotion: Boolean,
) {
    // 当帧先判一次：点击路径这里就已成立，不进流、不等待。
    val caughtUpAtEntry: Boolean = authorityCaughtUp(selectionProgressProvider(), latestIndex.value)
    if (!caughtUpAtEntry) {
        withTimeoutOrNull(AUTHORITY_CATCHUP_TIMEOUT_MS) {
            snapshotFlow { authorityCaughtUp(selectionProgressProvider(), latestIndex.value) }
                .first { it }
        }
    }
    // 手势收尾阶段 isScrolling 会在整数带附近抖动，这段时间里若恢复滑动，
    // 外层 collectLatest 会取消本次归位。点击路径不存在该抖动，直接跳过（零延迟）。
    if (settleNeedsConfirmWindow(followsMotion, caughtUpAtEntry)) {
        delay(IDLE_SETTLE_CONFIRM_MS)
    }
}

// ★ 已删除（2026-09-24，迁移阶段 2.7）：`DOCK_SELECTION_SPEC` ★
//
// 它是 `tween(UfiMotion.Duration.Base = 200ms, Standard)`，配 `animateFloatAsState` 用的。
// 药丸改跟 pager 连续进度之后，两处 `animateTo` 一律走 [indicatorSettleSpec]
//（= `tween(navTransitionMs, Standard)`，默认 380ms）—— 必须与页面转场**同一条时间轴**，
// 否则页面到位、药丸还在跑（或反之），观感就是胶囊时代那条「不跟手」。200 与 380 不同步。
//
// ⚠ 顺带定掉胶囊里留着的一个未决项：它「钉住目标格」那一下用的是 `INDICATOR_SPEC`
//   (`spring(0.8, 500)`)，KDoc 里注明「点击路径该用哪条曲线未拍板」。**通栏一律不用弹簧** ——
//   本栏格宽等分且药丸只比格窄 16dp×2，弹簧的过冲会让药丸越过格边界、压到邻格图标上。
//   两处 animateTo 都走同一条 tween，收尾干净。

