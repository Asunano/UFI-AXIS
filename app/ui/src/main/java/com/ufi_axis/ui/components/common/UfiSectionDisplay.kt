// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

private fun String.sanitizeUnknown(): String? =
    if (isBlank() || equals("unknown", ignoreCase = true) || this == "未知") null else this

@Composable
fun UfiSectionGroupTitle(title: String, subtitle: String, error: Boolean = false) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = UfiTextStyles.cardTitle,
            color = if (error) palette.error else palette.accent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            subtitle,
            style = UfiTextStyles.note,
            color = if (error) palette.error.copy(alpha = 0.7f)
                    else palette.textSecondary.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun UfiInfoRow(label: String, value: String?) {
    val palette = LocalResolvedPalette.current
    val displayValue = value?.sanitizeUnknown()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = UfiTextStyles.body,
            color = palette.textSecondary
        )
        Text(
            text = displayValue ?: "\u2014",
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary
        )
    }
}

/**
 * 「标签在上、值在下」的信息格 —— 卡内 2×N 紧凑网格的单元格。
 *
 * 2026-09-10 新增（纯追加，未动本文件既有签名）：这种格子原先在配对管理页手搓成私有
 * `PairInfoCell`，而"设备名 / 标识 / 状态 / 数量"这类成组只读信息在设备信息、隧道详情
 * 里都是同一形态，留在页面里就是下一处会被复制的手搓件。
 *
 * 与相邻两个组件的分工（别互相替代）：
 * - [UfiInfoRow]：标签值**左右**分列、一行一项。格子宽度只有半屏时，左右分列会让
 *   长值（设备标识、IP）立刻被挤进省略号，所以网格里必须上下排。
 * - [UfiStatItem]：**居中** + 值用大字，那是"指标"；本组件左对齐、值用正文字号，是"信息"。
 *
 * 值固定单行 + 省略号：网格靠等高对齐，值换行会把同排另一格顶歪。
 */
@Composable
fun UfiInfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = UfiTextStyles.note,
            color = palette.textSecondary
        )
        Text(
            text = value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 整页 / 首屏首次加载的占位骨架屏。
 *
 * 2026-09-03 换实现：这个组件名字里写着 Shimmer，但在此之前**根本不是骨架屏** ——
 * 内部是 3 张 M3 `Card`，每张卡正中放一个 24dp 的 [UfiLoadingIndicator] 转圈。
 * 也就是说它既没有扫光（shimmer），也不预示任何布局，只是把「一个转圈」变成「三个转圈」，
 * 比单个转圈更吵。现在换成真骨架屏（[UfiSkeletonGroup] → [UfiSkeletonCard]，走
 * `shimmerBrush()` 的渐变扫光）。
 *
 * 为什么整页首屏用骨架屏而不是转圈：骨架屏先把「接下来会出现几张卡、每张卡几行」画出来，
 * 用户在首帧就知道页面长什么样，数据到位后内容原地落位、不会整块跳变；转圈只表达
 * 「在等」，不含任何布局信息，整页空白时空屏焦虑最重。局部刷新 / 按钮内 / 小范围重载
 * 仍然用 [UfiLoadingIndicator]（见 [UfiButton] 的 loading 态、UfiLoadingBox）—— 那些场景页面已有内容，
 * 骨架屏反而会把已渲染的信息挡掉。
 *
 * 名字保留不改：本项目约定「纯改名不值得动公共签名」（见文件头 [F24] STABLE-UI-API），
 * 且改实现后调用方（DashboardScreen）一行都不用动。
 *
 * `cardCount = 3` 是对着仪表盘首屏真实版式给的：① 连接状态卡、② 4-in-1 指标卡、
 * ③ 设备信息列表卡，正好 3 张。
 */
@Composable
fun UfiShimmerLoading() {
    UfiSkeletonGroup(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.InnerPadding),
        cardCount = 3,
        linesPerCard = 3
    )
}