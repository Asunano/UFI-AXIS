package com.ufi_axis.ui.animation.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「当前页是否为选中页」的组合局部量（T02 · 宿主注入）。
 *
 * 宿主（[UfiPageSwitcherHost]）在渲染每一页时都会通过 `CompositionLocalProvider` 注入
 * **该页专属的容器**：只有 `pageIndex == selectedIndex` 的那一页的容器里是 `true`，
 * 其余（预加载 / 正在离场 / 保活中的）页面是 `false`。
 *
 * ## ★★ 为什么类型是 `State<Boolean>` 而不是裸 `Boolean`（2026-09-05 主因修复）★★
 * 本值是 `staticCompositionLocalOf` —— 它**没有细粒度读追踪**，provide 的值一变，
 * provider 以下**整棵子树无条件重组**（连 skippable 的子 composable 也不许跳过，
 * 这正是 static 变体的定义）。而 `beyondViewportPageCount = 1` 时在场三页里有**两页**
 * 的 active 值会在同一刻翻转 ⇒ **两棵完整页子树被强制重组**。
 * 真机现象：横滑切主界面落定那一帧「顿一下」（同一笔开销在点击路径上表现为「不跟手」）。
 *
 * 修法与 `MainNavGraph` 的 `LocalCapsuleBottomInset`（`staticCompositionLocalOf<() -> Dp>`）
 * 完全同源：**provide「读值的方式」，不 provide 值**。宿主为每页 `remember` 一个身份稳定的
 * 容器（实例跨 `selectedIndex` 变化永不改变）⇒ static local 的值从不变化 ⇒ 永不触发强制重组；
 * 对 `.value` 的读取是**细粒度快照读**，只有真正读它的 composable 会失效。
 *
 * ## 典型用途
 * 页面内容据此**暂停后台页的昂贵行为**，避免离屏页仍在跑动画 / 轮询 / 播放。
 * ⚠ 但请**不要**直接读本值（理由见下），一律走 [isUfiPageForeground]。
 *
 * 默认是一个恒为 `false` 的常量容器 —— 即「未被宿主管理时按非活跃处理」，这是更安全的
 * 保守默认值：页面被直接单独使用（未套 [UfiPageSwitcher]）时不会误启动后台任务。
 *
 * ## ⚠ 页面**不要**直接消费本值做轮询门控
 * 默认值 `false` 的保守性有个副作用：一个既能当 Tab 页、又能被 NavHost 当 detail 页
 * 单独打开的页面（如 `MonitorScreen`），在 detail 场景下拿到的是默认 `false`，
 * 会被误判成「非活跃」而永不加载数据。请改用 [isUfiPageForeground]，它会先用
 * [LocalUfiPageHosted] 区分「是否处于 Switcher 宿主之下」再做判断。
 *
 * 注：本符号属于实验性 API（等同 `@UfiExperimentalApi`）。因 `UfiExperimentalApi` 的
 * `@Target` 未包含 `AnnotationTarget.PROPERTY`，此处无法以注解标注，改以文档声明。
 * 与同模块的 `LocalUfiReduceMotion`（T01）保持一致的处理方式。
 */
val LocalUfiPageActive: ProvidableCompositionLocal<State<Boolean>> =
    staticCompositionLocalOf { UfiPageInactive }

/**
 * [LocalUfiPageActive] 的默认容器：恒为 `false` 的**不可变** [State]。
 *
 * 刻意不是 `mutableStateOf(false)`：默认值不该有任何写入口，也不需要参与快照系统
 * （读它注册不到任何失效，正好符合「这个值永远不会变」的事实）。
 */
private object UfiPageInactive : State<Boolean> {
    override val value: Boolean get() = false
}

/**
 * 「当前内容是否处于 [UfiPageSwitcher] 宿主管理之下」的组合局部量。
 *
 * 宿主（`UfiPageSwitcherHost` 的**两个后端**）在渲染每一页时都会 provide `true`；
 * 任何**没有**被 Switcher 包裹的内容（如 NavHost 直接打开的 detail 页、独立 Preview、
 * 单元测试）都会拿到默认值 `false`。
 *
 * 它存在的唯一理由，是让 [LocalUfiPageActive] 的 `false` 变得**可区分**：
 * | hosted | active | 真实含义 |
 * |---|---|---|
 * | `true`  | `true`  | 在 Switcher 内，且是当前选中页 → 前台 |
 * | `true`  | `false` | 在 Switcher 内，但是后台/预加载页 → 应暂停轮询 |
 * | `false` | `false` | 根本不在 Switcher 内（独立使用）→ 应视为前台 |
 *
 * 第 2 行与第 3 行的 `active` 同为 `false`，只看 [LocalUfiPageActive] 无法分辨 ——
 * 这正是必须引入本值的原因。业务侧一般不直接读它，读 [isUfiPageForeground] 即可。
 *
 * 注：本符号属于实验性 API（等同 `@UfiExperimentalApi`），标注方式同 [LocalUfiPageActive]。
 */
val LocalUfiPageHosted: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * 「当前页面是否应按**前台**对待」—— 轮询 / 实时刷新 / 动画门控的**推荐入口**。
 *
 * ## 语义
 * ```
 * 前台 = !被宿主管理 || 是当前选中页
 * ```
 * - **未被 [UfiPageSwitcher] 管理**（`hosted == false`）→ 恒为 `true`。
 *   即「页面被单独使用时，保持它原有的行为」，不会因为引入门控而少加载数据；
 * - **被宿主管理**（`hosted == true`）→ 退化为 [LocalUfiPageActive]，
 *   只有当前选中页为 `true`，后台页 / 预加载页 / 正在离场的页均为 `false`。
 *
 * ## 为什么不能直接用 [LocalUfiPageActive]
 * `LocalUfiPageActive` 的默认值是「恒为 `false` 的容器」。像 `MonitorScreen` 这类**双身份**页面
 * （既是底部 Tab 之一，又能被 NavHost 当 detail 页单独打开），在 detail 场景下并不处于
 * Switcher 内，只能拿到默认容器。若页面写成：
 * ```
 * if (!LocalUfiPageActive.current.value) return  // ✗ detail 场景下数据永不加载
 * ```
 * 就会把「不在 Switcher 内」误判为「后台页」，造成**数据永不加载**的严重回归。
 * 本函数通过 [LocalUfiPageHosted] 把这两种 `false` 区分开，从根上杜绝该误用。
 *
 * ## 用法
 * ```
 * val foreground = isUfiPageForeground()
 * LaunchedEffect(foreground) {
 *     if (foreground) startPolling() else stopPolling()
 * }
 * ```
 *
 * ## ★ 返回值必须是 `Boolean`（不是 `() -> Boolean`）—— 副作用 key 的正确性红线
 * 调用方普遍把返回值当 `LaunchedEffect` / `DisposableEffect` 的 **key**
 * （`DashboardScreen` 的 `startAutoRefresh` 启停、`NetworkScreen` 的首次加载、
 * `MonitorScreen` 的首屏拉取）。所以这里必须**在组合期把 `.value` 读出来**：
 * - 这次读取把「调用它的那个 composable」订阅进了宿主那份容器 ⇒ 前后台一翻转，
 *   该 composable 立即失效重组 ⇒ 新的 `Boolean` 成为新的 key ⇒ 副作用照常重启；
 * - 若改成返回 lambda，key 就变成了一个恒定的函数对象，`startAutoRefresh` 将**永远不会**
 *   随前后台切换启停 —— 那比「顿一下」严重得多。
 *
 * 与「不 provide 裸 Boolean」并不矛盾：**容器身份稳定**消掉的是 static local 的
 * 强制整树重组，**在此处读 `.value`** 保留的是细粒度失效与正确的 key 语义。
 *
 * @return 该内容此刻是否应执行前台级别的昂贵行为（轮询 / 实时刷新 / 播放）。
 */
@Composable
fun isUfiPageForeground(): Boolean {
    val hosted: Boolean = LocalUfiPageHosted.current
    // 短路：非宿主管理时连容器都不读 —— 少一次无意义的快照订阅。
    // 语义仍是 `!hosted || active`（见上表）。
    return !hosted || LocalUfiPageActive.current.value
}
