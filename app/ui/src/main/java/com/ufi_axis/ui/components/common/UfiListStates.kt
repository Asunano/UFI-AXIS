// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 列表页三态：加载（骨架）/ 空 / 错误。2026-09-16 从文件管理器的 `FileStates.kt` 上提。
 *
 * ## 为什么上提到公共层
 * 文件管理器早在 2026-09-04 就把自己手搓的静态灰块换成了公共 [UfiSkeletonList]，但"什么时候
 * 显示骨架、空态长什么样、错误态带不带重试"这套**编排**还留在 feature 模块里。媒体库拆成
 * 三页后又要来一遍，于是这三个形态在这里定一次：
 *  · 加载 = 骨架屏（不是转圈）——转圈只说"在忙"，骨架屏还告诉你"马上会出现一份列表"；
 *  · 空 = 一行说明文字（可选图标 / 动作），**不复用**错误态的红字；
 *  · 错误 = 文案 + 可选「重试」，按钮走 [UfiButton] 而不是裸 M3 Button。
 *
 * ## 用法约束（沿用 FileStates 的两条）
 * 三个组件都无状态，且根布局**接受外部 modifier** —— 调用方通常传 `Modifier.weight(1f)`
 * 让它占满内容区剩余高度。它们通常位于 `Column` 作用域内，所以内部居中一律用
 * `Box(contentAlignment)` / `Column(Arrangement)`，**禁止** `Modifier.align()`。
 *
 * ## 首屏骨架的红线（从文件管理器继承，别改）
 * 只在「正在加载 **且** 当前一条数据都没有」时显示 [UfiListLoadingState]。已有列表时的刷新
 * 不许换骨架 —— 否则每次刷新都把已渲染内容整块替换成灰块，观感就是闪一下。
 */

/**
 * 加载态：首屏骨架屏。
 *
 * 前置槽尺寸/形状要与真实行**同形**：40dp 圆块对应"图标 + 两行文字"的行（文件、会话），
 * 缩略图列表要传 `leadingWidth = 72.dp, leadingHeight = 44.dp, leadingCircle = false`，
 * 不然数据到位那一帧能看到明显的形状跳变。
 *
 * @param rows 骨架行数，默认 6（够铺满常见机型一屏）
 */
@Composable
fun UfiListLoadingState(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    leadingWidth: Dp = 40.dp,
    leadingHeight: Dp = 40.dp,
    leadingCircle: Boolean = true
) {
    UfiSkeletonList(
        rows = rows,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp),
        leadingWidth = leadingWidth,
        leadingHeight = leadingHeight,
        leadingCircle = leadingCircle
    )
}

/**
 * 加载态（网格版）：等高格子骨架。
 *
 * 网格页别用 [UfiListLoadingState] —— 列表骨架是"横条"，数据到位那一帧整片会重排。
 *
 * @param columns 列数，要与真实网格一致
 * @param cellHeight 格高，要与真实格子一致
 */
@Composable
fun UfiGridLoadingState(
    modifier: Modifier = Modifier,
    columns: Int = 3,
    cells: Int = 9,
    cellHeight: Dp = 108.dp
) {
    UfiSkeletonGrid(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        columns = columns,
        cells = cells,
        cellHeight = cellHeight,
        spacing = Spacing.Small
    )
}

/**
 * 空态：居中一行说明。
 *
 * [text] 必须说清"为什么是空的"，而不是一句"暂无数据"：范围筛出来的空与库里真的没有，
 * 对用户是两件事（媒体页就按扫描范围是否为空给了两种文案）。
 *
 * @param icon 可选图标（媒体页用类型图标，让人一眼知道这一页在看什么）
 * @param action 可选动作槽（例如「重新检查」「去选目录」）
 */
@Composable
fun UfiListEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(Spacing.IconSizeLarge)
            )
            Spacer(Modifier.height(Spacing.Medium))
        }
        Text(
            text = text,
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.Medium))
            action()
        }
    }
}

/**
 * 错误态：文案 + 可选「重试」。
 *
 * 用 `warning` 而不是 `error` 色：这里的错误绝大多数是"这一次没拉到"（超时、断连），
 * 重试一下就好，不是不可恢复的故障。
 */
@Composable
fun UfiListErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = UfiTextStyles.note,
            color = palette.warning,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.Medium))
            UfiButton(
                text = "重试",
                onClick = onRetry,
                variant = UfiButtonVariant.Subtle,
                size = UfiButtonSize.Small
            )
        }
    }
}

/**
 * 列表末尾的「已加载 x / y」。
 *
 * 刻意不是转圈：这一行的作用是让人知道"还有更多、正在往下取"，而一个居中的 spinner
 * 会让人以为整块在重新加载。2026-09-16 从媒体库的 `MediaListCommon` 上提 —— 分页列表都用得上。
 */
@Composable
fun UfiListLoadedCounter(loaded: Int, total: Int, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.Medium),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "已加载 $loaded / $total",
            style = UfiTextStyles.note,
            color = palette.textSecondary
        )
    }
}
