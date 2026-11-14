// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import kotlinx.coroutines.launch

/**
 * 带搜索的弹窗：**输入词 → 点「搜索」→ 从结果里选一条**。
 *
 * ```
 * ┌─────────────────────────────┐
 * │  标题                     ×  │  ← UfiDialogShell 的标题行
 * │  [ 搜索内容        ] [搜索]  │  ← 同一行：输入框 weight(1f) + 按钮按内容宽
 * │  结果 1 / 结果 2 / …（或空态）│  ← 展开动画出现，条目之间 UfiDivider
 * └─────────────────────────────┘
 * ```
 *
 * 为什么**不做输入即搜**（debounce）：这类搜索背后往往是一次网络往返（甚至经 core 再转发
 * 到上游），每次按键都打一发既慢又浪费；而"选一次"这个动作本身很少发生，一个明确的
 * 「搜索」按钮更省。需要即时过滤的场景请用 [UfiListToolbar] 那一族的本地筛选，不要用本组件。
 *
 * 状态（query / results / searching / searched）**全部由本组件持有**，调用方只给数据源与行渲染。
 * 弹窗 `visible = false` 时 [UfiDialogShell] 会卸载整棵内容子树，所以下次打开是干净的空状态 ——
 * 调用方不需要（也不应该）自己去清 query。
 *
 * 间距：内容走 [UfiDialogBody] 的 12dp 纵向节奏，横向内距由 shell 统一提供，本组件不加任何
 * Spacer / padding（加了就会和 shell 叠出"输入框比标题窄一圈"那类问题）。
 *
 * @param visible 是否显示。请**直接把状态传进来**（而不是用 `if (show) { ... }` 硬挂载），
 *                否则 shell 的离场时序（backdrop 逐渐清晰 → 再卸载窗口）没有机会播完。
 * @param onDismiss 关闭回调。标题行的 ×、点击弹窗外、以及 [resultItem] 里的 `dismiss()` 都会走它。
 * @param title 标题（由 [UfiCustomDialog] / shell 渲染）。
 * @param placeholder 输入框的 placeholder，如"中文或英文均可"。
 * @param onSearch 搜索触发：返回结果列表。挂在 `scope.launch` 里，可以 suspend。
 * @param minQueryLength 最短搜索字符数，不够时搜索按钮禁用。
 * @param resultItem 每一条结果的渲染。`dismiss` 是"关掉这个弹窗"，已接入 [LocalUfiDialogClose]
 *                   的离场时序 —— 选中后请调用它，不要在外面另翻一份 `show = false`。
 */
@Composable
fun <T> UfiSearchDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    placeholder: String = "",
    onSearch: suspend (query: String) -> List<T>,
    minQueryLength: Int = 1,
    resultItem: @Composable (item: T, dismiss: () -> Unit) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<T>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title
    ) {
        // 关闭动作交给 shell 排时序：离场 backdrop（逐渐清晰）要播完才卸载窗口，见 LocalUfiDialogClose。
        // 「搜索」不关弹窗（结果就在弹窗里出），所以只有 dismiss 需要包。
        val close = LocalUfiDialogClose.current
        val dismiss: () -> Unit = { close(onDismiss) }

        UfiDialogBody {
            // 搜索行：输入框与按钮**等高**。
            //
            // 高度对齐的做法：Row 用 `height(IntrinsicSize.Min)`（= 取子项的最小固有高度，
            // 这里就是输入框那 56dp），按钮再 `fillMaxHeight()` 撑到同高。
            // 之前只写 CenterVertically 是"垂直居中"而不是"等高"，所以按钮比输入框矮一截。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UfiTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "",
                    modifier = Modifier.weight(1f),
                    placeholder = placeholder.ifEmpty { null },
                    enabled = !searching,
                    singleLine = true,
                    fillMaxWidth = false
                )
                UfiButton(
                    text = if (searching) "搜索中…" else "搜索",
                    size = UfiButtonSize.Small,
                    enabled = query.trim().length >= minQueryLength && !searching,
                    modifier = Modifier.fillMaxHeight(),
                    onClick = {
                        scope.launch {
                            searching = true
                            try {
                                results = onSearch(query.trim())
                                searched = true
                            } finally {
                                // finally：数据源抛异常时也要把按钮从"搜索中…"放回来，
                                // 不然弹窗会卡成一个点不动的死按钮。
                                searching = false
                            }
                        }
                    }
                )
            }

            // 结果区：整块一起展开/收起。用 AnimatedVisibility 而不是裸 if —— 结果是异步来的，
            // 裸 if 会让列表"啪"一下顶开输入框下面的内容。
            AnimatedVisibility(
                visible = results.isNotEmpty() || (searched && results.isEmpty()),
                enter = expandVertically(
                    animationSpec = tween(UfiMotion.Duration.Quick, easing = UfiMotion.Easing.Standard)
                ) + fadeIn(tween(UfiMotion.Duration.Base)),
                exit = shrinkVertically(
                    animationSpec = tween(UfiMotion.Duration.Quick, easing = UfiMotion.Easing.Standard)
                ) + fadeOut(tween(UfiMotion.Duration.Micro))
            ) {
                if (results.isEmpty()) {
                    // 空态：搜过了但一条都没有。次要色小字，与 UfiDialogNote 同一档口径。
                    Text(
                        text = "没有找到匹配的结果，换个写法再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                } else {
                    // 不用 LazyColumn：搜索结果通常不到 20 条，而 Lazy 在弹窗（高度受 heightIn
                    // 约束、外层可能还有滚动）里反而要额外处理嵌套滚动与 Infinity 约束。
                    Column(Modifier.fillMaxWidth()) {
                        results.forEach { item ->
                            resultItem(item, dismiss)
                            UfiDivider()
                        }
                    }
                }
            }
        }
    }
}
