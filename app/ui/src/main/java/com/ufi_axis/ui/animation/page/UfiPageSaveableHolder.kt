package com.ufi_axis.ui.animation.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.ufi_axis.ui.components.common.UfiExperimentalApi

/**
 * 为单页内容提供**独立的可保存状态槽位**（T02 · 状态保留）。
 *
 * ## 解决什么问题
 * 页面被滑出视口后会被宿主销毁（`HorizontalPager` 的 `beyondViewportPageCount` 之外即回收）。
 * 若不做处理，页面内 `rememberSaveable` 的状态（`LazyColumn` 滚动位置、展开态、输入框文本…）
 * 会在回到该页时丢失。本组件用 [SaveableStateHolder] 按 [key] 把这些状态**存档**，
 * 页面重新组合时自动**还原**。
 *
 * ## 为什么 holder 建在内部而不是提到宿主层
 * `rememberSaveableStateHolder()` 本身就是 `rememberSaveable` 的，它会把自己的存档表
 * **递归写入外层的 SaveableStateRegistry**。而 `HorizontalPager`（LazyLayout）已经为每个
 * item 建立了以 item key 为槽位的 `SaveableStateProvider`，因此本组件的存档表会随之被
 * 保存 / 还原 —— 页面销毁重建后状态依然在，无需把 holder 手工提升到宿主层。
 *
 * 另一后端 `AnimatedContent` 仅在 `keepPagesAlive == false` 时才会被选用
 * （见 [selectBackend]），即调用方**明确表示不需要保活**，故那条路径上不保证跨页存档。
 *
 * ## key 的约束
 * [key] 直接取自 [UfiPage.key]，必须**稳定且唯一**：
 * - 用列表下标会导致增删页后状态串档；
 * - 用随机值会导致每次重组都换槽位，状态永远丢失。
 *
 * @param key     该页的稳定唯一标识，作为存档槽位名。
 * @param content 页面内容。
 */
@UfiExperimentalApi
@Composable
fun UfiSaveablePage(
    key: String,
    content: @Composable () -> Unit,
) {
    val stateHolder: SaveableStateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(key = key) {
        content()
    }
}
