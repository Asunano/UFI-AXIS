package com.ufi_axis.ui.navigation

import android.os.SystemClock
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import com.ufi_axis.util.DebugLog

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
internal const val UFI_NAV_DEBOUNCE_MS = 350L

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
 * 最近一次**导航**（push 或 pop）发生的时刻（[SystemClock.uptimeMillis] 口径）与目的地。
 *
 * 只服务诊断：`UfiNavFrameGate` 用它算「导航发起 → 转场第一帧」的启动延迟。
 * 用户报的"转场卡"有两种完全不同的形态 —— 动画中掉帧（`最差帧` 变大）与动画**开始得晚**
 * （第一帧之前新页首帧组合太贵，帧统计看不到）。没有这个数就只能靠猜。
 *
 * ## 2026-11-03 二次修正：改由**目的地变化回调**写入，不再只在 push 时打点
 *
 * 第一版只在 `ufiNavigateOnce` 里打点，于是 **pop 完全没有启动延迟** —— 而用户反馈
 * 恰恰是"退出"更卡。现在由 `MainNavGraph` 注册的
 * `NavController.addOnDestinationChangedListener` 统一打点：`navigate()` / `popBackStack()`
 * 的返回值里就会回调，早于任何组合，正是"导航发起"这一刻。push / pop 两个方向都能量。
 *
 * ⚠ **必须同时记目的地**：进入画面的那一页也会跑一次同样的帧统计；只看时间戳会和
 * 上一次导航相减 —— 得到 676 / 833 / 1190 / 1711 / 2319ms 这种噪声（真机日志已出现过）。
 * 只有「本次转场的目的地 == 刚被打点的目的地」时，两者才真的是同一次导航。
 * 时间窗（[UFI_NAV_STARTUP_MATCH_MS]）再兜一层。
 */
private var ufiNavLastNavAtMs = 0L
private var ufiNavLastNavRoute: String = ""

/** 启动延迟的有效匹配窗口：超过它就不是"这次导航引起的转场"。 */
private const val UFI_NAV_STARTUP_MATCH_MS = 1_000L

/**
 * 由 `MainNavGraph` 的目的地变化监听调用：记下"导航发起"时刻。
 *
 * ## 顺带：回到 Tab 页时**清空去抖窗口**（2026-11-03）
 *
 * [ufiNavigateOnce] 的时间窗是"距上一次**被放行的 push**"算的，而 pop 从不更新它。
 * 于是存在一个会把人坑死的组合：点进某页 → 立刻返回 → 立刻再点同一个入口。
 * 如果这一整套动作落在 350ms 内，第三次点击会被判成"连击"而**静默丢弃** ——
 * 用户看到的是"退出之后点什么都没反应"。回到 Tab 页意味着上一次 push 的转场
 * 已经彻底结束（Tab 页根本不在那次 push 的目的地上），此刻清空窗口是安全的，
 * 也是唯一不会误伤的判据。
 */
internal fun ufiNavNoteDestinationChanged(route: String, nowMs: Long = SystemClock.uptimeMillis()) {
    ufiNavLastNavAtMs = nowMs
    ufiNavLastNavRoute = route
    if (ufiRouteIdentityOf(route) in UFI_TAB_ROUTE_IDS) {
        ufiNavLastAcceptedAtMs = 0L
    }
}

/**
 * 给定本次转场的目的地，返回「刚才那次导航的时刻」；无法确认属于同一次导航时返回 0。
 *
 * @param route 本次转场进入的目的地路由（`NavDestination.route`，模板形态）。
 */
internal fun ufiNavStartedAtFor(route: String, nowMs: Long = SystemClock.uptimeMillis()): Long {
    if (ufiNavLastNavAtMs <= 0L) return 0L
    if (ufiRouteIdentityOf(ufiNavLastNavRoute) != ufiRouteIdentityOf(route)) return 0L
    return if (nowMs - ufiNavLastNavAtMs <= UFI_NAV_STARTUP_MATCH_MS) ufiNavLastNavAtMs else 0L
}

/**
 * 五个 Tab 目的地（含宿主 [Routes.MAIN]）的路由标识。
 *
 * 用于 [ufiNavNoteDestinationChanged] 判断"这次导航落到了 Tab 页"。
 */
private val UFI_TAB_ROUTE_IDS: Set<String> = setOf(
    Routes.MAIN,
    Routes.DASHBOARD,
    Routes.NETWORK,
    Routes.MONITOR,
    Routes.TOOLS,
    Routes.SETTINGS,
).map { ufiRouteIdentityOf(it) }.toSet()

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
 * ## 2026-11-03：从"媒体页专用"扩成**所有用户触发的 push 的唯一入口**
 *
 * 之前这道闸只有 `feature-media` 在用，其余 90 多个入口（设置行、工具宫格、网络卡片、
 * 文件列表…）仍然直接 `navController.navigate(...)`。于是有一个真实可复现的竞态：
 * **两根手指同时点两个不同入口**（或者一帧内先后触发两个 onClick）时，两次
 * `navigate()` 都会执行，返回栈里叠两页 —— 界面上看是"同时进入了两个页面"，返回时
 * 要按两次才回到原处。目的地去重挡不住它（两个入口的目标**不同**），只有这条
 * 进程级时间窗能挡。
 *
 * 因此现在的约定是：
 * - **用户触发的 push 一律走本函数**（`ufiNavigateOnce`）；
 * - 程序触发的跳转（通知深链接、启动引导、`MainNavGraph` 的待处理深链）可以继续用
 *   原生 `navigate()` —— 它们不是"人手连击"场景，也不该被用户上一次点击的 350ms
 *   尾巴吃掉。这类调用点在源码里均有注释说明。
 *
 * 配套的回归护栏见 `NavPushGuardTest`：任何 feature 模块里再出现裸 `navigate()` 都会失败。
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
fun NavHostController.ufiNavigateOnce(
    route: String,
    navOptions: NavOptions? = null,
    navigatorExtras: Navigator.Extras? = null,
) {
    if (!ufiNavAdmit(route)) return
    navigate(route, navOptions, navigatorExtras)
}

/**
 * [ufiNavigateOnce] 的 `NavOptionsBuilder` 重载。
 *
 * 存在的唯一理由：让 `navigate(route) { launchSingleTop = true }` 这类调用点可以**原样**
 * 替换成 `ufiNavigateOnce(route) { launchSingleTop = true }`，不必先手工 `navOptions { }`。
 * 选项的构造推迟到放行之后 —— 被挡掉的点击不产生任何对象。
 */
fun NavHostController.ufiNavigateOnce(
    route: String,
    builder: NavOptionsBuilder.() -> Unit,
) {
    if (!ufiNavAdmit(route)) return
    navigate(route, builder)
}

/** 判定这次导航是否放行，并在放行时推进时间窗。 */
private fun NavHostController.ufiNavAdmit(route: String): Boolean {
    val current = currentDestination?.route
    val now = SystemClock.uptimeMillis()
    val admitted = ufiNavAdmission(
        currentRoute = current,
        route = route,
        lastAcceptedAtMs = ufiNavLastAcceptedAtMs,
        nowMs = now,
    )
    if (!admitted) {
        // 只记"哪个目的地、为什么"，不含参数值（路由里可能带文件路径 / 手机号）。
        DebugLog.d("NavGuard") {
            val reason = if (current != null &&
                ufiRouteIdentityOf(current) == ufiRouteIdentityOf(route)
            ) "duplicate" else "debounce"
            "drop route=${ufiRouteIdentityOf(route)} reason=$reason"
        }
        return false
    }
    ufiNavLastAcceptedAtMs = now
    // 导航时刻由 `ufiNavNoteDestinationChanged`（目的地变化回调）统一打点 —— 那里
    // push / pop 都会经过，是「导航发起」的唯一时间源。这里不再重复写。
    return true
}

/**
 * 放行判据的**纯函数**形态（供 JVM 单测直接覆盖，不必起 NavHost）。
 *
 * @param currentRoute   `NavHostController.currentDestination?.route`（目的地模板，可能带 `{arg}`）。
 * @param route          本次要去的具体路由（可能带 `?k=v`）。
 * @param lastAcceptedAtMs 上一次放行时刻；进程内首次为 0。
 * @param nowMs          本次判定的时刻。
 */
internal fun ufiNavAdmission(
    currentRoute: String?,
    route: String,
    lastAcceptedAtMs: Long,
    nowMs: Long,
): Boolean {
    // 第一道：目的地去重。current == null 时放行，理由见 ufiNavigateOnce 的 KDoc。
    if (currentRoute != null && ufiRouteIdentityOf(currentRoute) == ufiRouteIdentityOf(route)) {
        return false
    }
    // 第二道：时间窗去抖。初值 0 不会误挡首次导航 —— uptimeMillis 从开机起算，
    // App 进程起来时它早已远大于 350。
    return nowMs - lastAcceptedAtMs >= UFI_NAV_DEBOUNCE_MS
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
internal fun ufiRouteIdentityOf(route: String): String = route.substringBefore('?')
