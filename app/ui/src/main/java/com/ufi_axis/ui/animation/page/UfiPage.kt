package com.ufi_axis.ui.animation.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.ufi_axis.ui.components.common.UfiExperimentalApi

/**
 * 一页的描述（T01 · 数据模型）。
 *
 * @property key     稳定且唯一的标识。用于页面 diff 与 `rememberSaveableStateHolder` 的状态保存槽位，
 *                   **不可随重组变化**（不要用列表下标或随机值）。
 * @property content 该页的内容 Composable。仅在该页需要渲染时被调用。
 */
@UfiExperimentalApi
@Immutable
data class UfiPage(
    val key: String,
    val content: @Composable () -> Unit,
)
