package com.ufi_axis.ui.animation.page

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.components.common.UfiExperimentalApi

/**
 * 可扩展的 Tab / 页面切换容器（T02 · 对外唯一公共入口）。
 *
 * ## 用法
 * ```
 * var index by rememberSaveable { mutableIntStateOf(0) }
 * UfiPageSwitcher(
 *     pages = listOf(
 *         UfiPage("home") { HomeScreen() },
 *         UfiPage("stats") { StatsScreen() },
 *     ),
 *     selectedIndex = index,
 *     onSelectedIndexChange = { index = it },
 *     transition = UfiPageTransitions.Cube3D,
 * )
 * ```
 *
 * ## 状态是「受控」的
 * 本组件**不持有**选中项。[selectedIndex] 由调用方提供，手势滑动切页时通过
 * [onSelectedIndexChange] 回吐 —— 这样 Tab 栏（`UfiTabRow`）与页面区天然共享同一份状态，
 * 点击 Tab 和滑动手势双向联动，不会出现两套下标打架。
 *
 * ## 扩展一种动画
 * 实现 [UfiPageTransition] 并 `UfiPageTransitions.register(...)` 即可，本组件零改动。
 *
 * ## 无障碍 / 性能降级
 * [respectReducedMotion] 为 `true`（默认）时，命中以下任一条件即自动降级：
 * - `LocalUfiReduceMotion` 为 `true`（系统「降低动效」，由 T05 在 `:app` 层探测注入）；
 * - [UfiPageSwitcherDefaults.isLowEndDevice] 判定为低端机。
 *
 * 降级目标为 [UfiPageTransition.reducedMotionFallback]，未指定则统一降到
 * [UfiPageTransitions.Fade]。**降级只在本函数发生一次**，宿主层拿到的已是最终策略。
 *
 * @param pages                 页面列表。每项的 `key` 必须稳定唯一（详见 [UfiPage]）。
 *                              传空列表时渲染为空容器，不会崩溃。
 * @param selectedIndex         当前选中页下标。越界值会被安全夹取到合法区间。
 * @param onSelectedIndexChange 用户手势切页后的回调。程序化切页（外部改 [selectedIndex]）
 *                              不会触发此回调。
 * @param onSelectionProgressChange 连续页位置实时回调（currentPage + currentPageOffsetFraction，
 *                              如 2.5 = 第 2、3 页之间），供底部导航实时追踪选中态。
 *                              **每帧回调**，仅在有回调时才有开销；传 `null` 表示不需要。
 * @param modifier              作用于容器根节点。
 * @param transition            转场策略，默认 [UfiPageSwitcherDefaults.DefaultTransition]。
 * @param swipeEnabled          是否允许横向拖拽切页，默认 `true`。
 * @param animationSpec         覆盖策略自带的 [UfiPageTransition.spec]；`null` 表示不覆盖。
 * @param respectReducedMotion  是否尊重「降低动效」与低端机判定，默认 `true`。
 * @param keepPagesAlive        是否让相邻页保持存活以保留页面内状态，默认 `true`。
 */
@UfiExperimentalApi
@Composable
fun UfiPageSwitcher(
    pages: List<UfiPage>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onSelectionProgressChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    transition: UfiPageTransition = UfiPageSwitcherDefaults.DefaultTransition,
    swipeEnabled: Boolean = UfiPageSwitcherDefaults.DefaultSwipeEnabled,
    animationSpec: AnimationSpec<Float>? = null,
    respectReducedMotion: Boolean = UfiPageSwitcherDefaults.DefaultRespectReducedMotion,
    keepPagesAlive: Boolean = UfiPageSwitcherDefaults.DefaultKeepPagesAlive,
) {
    val reduceMotion: Boolean = LocalUfiReduceMotion.current
    val context = LocalContext.current

    // 低端机判定要走系统服务，结果在进程生命周期内恒定，按 context 缓存一次即可。
    val isLowEndDevice: Boolean = remember(context) {
        UfiPageSwitcherDefaults.isLowEndDevice(context)
    }

    // 降低动效：系统「降低动效」开关命中，或判定为低端机，且调用方要求尊重它。
    val reducedMotion: Boolean = respectReducedMotion && (reduceMotion || isLowEndDevice)

    val effectiveTransition: UfiPageTransition =
        if (reducedMotion) {
            transition.reducedMotionFallback ?: UfiPageTransitions.Fade
        } else {
            transition
        }

    // ── F4：弱机（低内存 / 低 RAM）强制关闭过渡模糊 ──────────────────────────────
    // 设备若不足以流畅承载 GPU 离屏模糊（见 UfiPageSwitcherDefaults.isBlurSupported），
    // 把 LocalUfiBlurEnabled 下传为 false，使宿主两个后端彻底跳过 applyTransitionBlur，
    // 从根上规避低端真机卡顿。非弱机路径不受影响：blurSupported 为 true 时有效值与上游
    // 「设置 → 外观」开关完全一致。
    val blurSupported: Boolean = remember(context) {
        UfiPageSwitcherDefaults.isBlurSupported(context)
    }
    val effectiveBlurEnabled: Boolean = LocalUfiBlurEnabled.current && blurSupported
    CompositionLocalProvider(LocalUfiBlurEnabled provides effectiveBlurEnabled) {
        UfiPageSwitcherHost(
            pages = pages,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            onSelectionProgressChange = onSelectionProgressChange,
            modifier = modifier,
            transition = effectiveTransition,
            swipeEnabled = swipeEnabled,
            animationSpec = animationSpec,
            keepPagesAlive = keepPagesAlive,
            reduceMotion = reducedMotion,
        )
    }
}

