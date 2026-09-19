// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 列表页工具条外壳：48dp 一行 + 底部 1dp 分隔线 + 左说明 / 右动作。2026-09-16。
 *
 * ## 为什么上提
 * 文件管理器的 `FileToolbar` 早就长成了这个形状（48dp 高、右侧一排
 * [UfiToolbarAction]、底部一条 divider 收口）。媒体库拆成三页后也要同一条工具条，
 * 于是把**外壳**定在这里，各页只往槽里塞自己的动作 —— 而不是把 `FileToolbar`
 * 复制一份再删掉粘贴板与面包屑。
 *
 * ## 槽的边界
 * · [caption]：这一屏"在看什么范围 / 有多少条"（左侧，会被挤压时省略号）；
 * · [leading]：返回上一级这类**导航**动作（文件管理器用，媒体页不用）；
 * · [actions]：视图切换 / 排序 / 搜索 / 更多 —— 一律用 [UfiToolbarAction]，
 *   别在这里塞 `IconButton`，两种按钮的触区与标签样式不一样。
 *
 * 组件完全无状态：所有行为回调上抛。
 */
@Composable
fun UfiListToolbar(
    modifier: Modifier = Modifier,
    caption: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit
) {
    val palette = LocalResolvedPalette.current
    Box(modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            leading?.invoke(this)
            if (caption != null) {
                Text(
                    text = caption,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
            } else {
                // 没有说明文字时，用空占位把动作推到右侧（与文件管理器同一口径）
                Box(modifier = Modifier.weight(1f))
            }
            actions()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider)
        )
    }
}

/**
 * 视图切换动作（列表 ⇄ 网格）。
 *
 * 图标语义是**「点了会变成什么」**而不是「现在是什么」：网格态显示列表图标。
 * 这条口径来自文件管理器，两处必须一致，否则同一个 App 里两个列表页的同一颗按钮
 * 会指向相反的含义。
 *
 * @param grid 当前是否网格态
 */
@Composable
fun UfiViewModeAction(
    grid: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    UfiToolbarAction(
        icon = if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewModule,
        label = "视图",
        onClick = onToggle,
        modifier = modifier
    )
}

/** 排序动作。图标与文案在全站统一（文件管理器、媒体库都用这一颗）。 */
@Composable
fun UfiSortAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    UfiToolbarAction(
        icon = Icons.AutoMirrored.Filled.Sort,
        label = "排序",
        onClick = onClick,
        modifier = modifier
    )
}
