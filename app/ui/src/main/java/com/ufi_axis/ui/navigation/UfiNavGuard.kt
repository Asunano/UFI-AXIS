package com.ufi_axis.ui.navigation

import android.os.SystemClock
import androidx.navigation.NavHostController

/**
 * 两次放行之间的最小间隔。低于它的第二次导航被丢弃。
 *
 * ## 这道闸是给共享元素加的（2026-09-20）
 * 转场还没跑完就发起下一次导航时，屏上会**同时**存在同一个页面的两个实例（旧的在离场、
 * 新的在进场），两边挂着同一个常量 key（[UFI_SHARED_KEY_AUDIO_COVER] 等）。
 * Compose 1.10.4 的 `SharedElement` 对「同 key 多于两个候选」的处理是：
 * 取 `enabledEntries` 里**第一个** `target == true` 的条目当终点；一个都没有就判定为
 * 缺少终点，于是**这一组谁都不动**（源码 `SharedElement.kt` 的 `_allEntries` 注释与
 * `SharedTransitionStateMachine.invalidateTargetBoundsProvider()`）。
 * 更糟的是 `onSharedTransitionFinished()` 只在「候选 ≤ 1 或没有可见内容」时才
 * `resetState()` —— 重叠那一次留下的状态机状态会被带进后面几次转场，
 * 所以症状是"反复进出几次之后就再也不连贯了"，而不是只坏一帧。
 *
 * ## 为什么不回到上一版的生命周期判定
 * 那个要等整段转场（还带 1.2 倍放慢，见 `Navigation.kt` 的 `SHARED_AXIS_SLOWDOWN`）
 * 跑完才放行，表现是"刚打开 / 刚返回要等一下才能点"。时间窗只吃掉人手不可能达到的
 * 连击频率，正常操作节奏不受影响。
 *
 * 350ms：略小于二级页转场的最长时长，宁可漏掉尾巴几十毫秒里的一次重叠，
 * 也不要把"看着已经到位了却点不动"的手感做出来。
 */
private const val UFI_NAV_DEBOUNCE_MS = 350L

/**
 * 上一次放行的时刻（[SystemClock.uptimeMillis] 口径）。
 *
 * 文件级单例而不是按 NavHostController 实例分：进程内只有 `MainNavGraph` 那一个
 * NavHostController，所有调用点面对的都是它。若将来出现第二个 NavHost（分屏 / 独立窗口），
 * 这里必须改成按实例存，否则两个 NavHost 会互相吃掉彼此的导航。
 *
 * 用 `uptimeMillis` 而不是 `currentTimeMillis`：后者会被用户改时间、NTP 校时跳变，
 * 一次回拨就能让这道闸把接下来的点击全挡住。
 *
 * 普通 `var` 而非 `MutableState`：只有本函数读写，不该让任何 Composable 对它建立
 * snapshot 依赖（与 [UfiMiniPlayerBarMetrics] 同一条理由）。
 */
private var ufiNavLastAcceptedAtMs = 0L

/**
 * 只在「目标与当前目的地不是同一个」且「距上次放行已超过 [UFI_NAV_DEBOUNCE_MS]」时导航。
 *
 * 两道闸各挡一件事，都过才 `navigate`：
 * 1. 目的地去重 —— 防连点 push 两层（返回要按两下）；
 * 2. 时间窗去抖 —— 防转场未完成就叠下一次，理由见 [UFI_NAV_DEBOUNCE_MS]。
 *
 * 顺序是先去重再看时间窗：去重那一条是"这次点击本来就没有意义"，
 * 它不该消耗时间窗的配额（否则挡掉一次重复点击会顺带把之后 350ms 内的**有效**导航也挡了）。
 *
 * ## 为什么不是只用生命周期（最早那一版的做法）
 * 最早那一版要求 `currentBackStackEntry` 的生命周期 `isAtLeast(RESUMED)`。但一个 entry 要等
 * **转场动画整段跑完**才回到 RESUMED，而本项目的二级页转场还带 1.2 倍放慢系数
 * （见 `Navigation.kt` 的 `SHARED_AXIS_SLOWDOWN`，最长约 720ms）—— 于是整个转场时长内
 * 所有导航点击都被吞掉，用户感受是"刚打开 / 刚返回要等一下才能点"。
 *
 * 现在的两道闸分别只做一件小事：去重不占时间（`navigate()` 同步更新
 * `currentDestination`，连点的第二次进来时当前目的地已经是目标了），时间窗只有 350ms
 * 且不看转场是否真的结束 —— 合起来覆盖了那一版想挡的全部场景，代价小得多。
 *
 * ## 为什么 `currentDestination == null` 时放行
 * 这个 null 只出现在 NavHost 还没把任何目的地推上来之前（图刚构建、深链尚未解析完）。
 * 那个时刻**不存在**"已经 push 过一次"这回事，所以拦截不是去重、而是把**首次**导航
 * 直接丢掉 —— 用户看到的是"点了没反应"，而且没有任何后续事件会补发。
 * 本函数的职责是**去重**，不是"禁止在未知状态下导航"，所以未知状态一律放行。
 *
 * ## 适用范围
 * 只用在「push 一个新目的地」的点击上（列表行、迷你条整块）。
 * 不要拿它包 `popBackStack`：返回本来就允许在转场中途被打断，套上去会把连续返回吃掉。
 *
 * @param route 目标路由，语义与 [NavHostController.navigate] 完全一致。
 */
fun NavHostController.ufiNavigateOnce(route: String) {
    val current = currentDestination?.route
    // 第一道：目的地去重。current == null 时放行，理由见上（那不是"重复点击"场景）。
    if (current != null && ufiRouteIdentityOf(current) == ufiRouteIdentityOf(route)) {
        return
    }
    // 第二道：时间窗去抖。初值 0 不会误挡首次导航 —— uptimeMillis 从开机起算，
    // App 进程起来时它早已远大于 350。
    val now = SystemClock.uptimeMillis()
    if (now - ufiNavLastAcceptedAtMs < UFI_NAV_DEBOUNCE_MS) {
        return
    }
    ufiNavLastAcceptedAtMs = now
    navigate(route)
}

/**
 * 取一条路由里"是哪个目的地"那部分，用来给 [ufiNavigateOnce] 做比对。
 *
 * ⚠ 两边拿到的串形态不同，**不能直接 `==`**：
 * - `currentDestination?.route` 是登记时那条**模板**，查询参数还带着占位符
 *   （`media/audio?path={path}`）；
 * - 传进来的 `route` 是已经填好值的**具体串**（`media/audio?path=%2Fsdcard%2Fa.mp3`）。
 *
 * 所以只比 `?` 之前那段（= 目的地本身）。副作用是"同一目的地、不同参数"也会被视作同一个，
 * 这正是这里想要的：连点列表里两首不同的歌同样只该进一次播放页（第一次点已经把播放页
 * 推上来了，第二次点的那首在播放页里换歌，而不是再叠一层播放页）。
 */
private fun ufiRouteIdentityOf(route: String): String = route.substringBefore('?')
