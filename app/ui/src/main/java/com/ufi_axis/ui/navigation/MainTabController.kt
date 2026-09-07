package com.ufi_axis.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 底部 Tab 切换控制器（方案 A′ · 跨模块调用入口）。
 *
 * ## 解决什么问题
 * 迁移前，任意页面想跳到「我的」只需 `navController.navigate("settings")`。
 * 迁移后 5 个 Tab 不再是独立的 NavHost 目的地，而是 [Routes.MAIN] 内
 * `UfiPageSwitcher` 的 5 页，导航语义变成「改变宿主的 selectedIndex」。
 *
 * 本 CompositionLocal 把这个能力以最小侵入的方式下发给任意深度的子树
 * （含 `:app:feature-*` 等下游模块），避免层层透传回调。
 *
 * ## 用法
 * ```
 * val mainTabController = LocalUfiMainTabController.current
 * IconButton(onClick = { mainTabController(4) }) { Icon(Icons.Default.Settings, "设置") }
 * ```
 *
 * ## 索引约定
 * 参数为目标 Tab 下标，取值 `0..4`，顺序与底部导航栏一致：
 *
 * | index | Tab |
 * |---|---|
 * | 0 | 仪表盘 dashboard |
 * | 1 | 网络 network |
 * | 2 | 监控 monitor |
 * | 3 | 工具 tools |
 * | 4 | 我的 settings |
 *
 * ## 默认值刻意抛异常
 * 未被 `MainNavGraph` 的 [Routes.MAIN] 提供就使用，属于接线错误，应当在开发期立刻暴露，
 * 而不是静默无响应（那样按钮点了没反应，极难排查）。
 */
val LocalUfiMainTabController = staticCompositionLocalOf<(Int) -> Unit> {
    error("LocalUfiMainTabController 未提供：请在 MainNavGraph 的 Routes.MAIN 处 provide")
}

/**
 * 胶囊实时选中进度（连续页位置，如 2.5 = 第 2、3 页之间）。
 *
 * 2026-08-23 性能优化：由 Float 改为 lambda () -> Float。
 * 配合非 static 容器（compositionLocalOf），实现进度更新时全量零重组。
 */
val LocalUfiCapsuleSelectionProgress = compositionLocalOf<() -> Float> { { Float.NaN } }
