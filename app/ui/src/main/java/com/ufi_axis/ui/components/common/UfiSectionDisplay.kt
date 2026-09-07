// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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