// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import kotlin.math.roundToInt

/**
 * 「展开 / 收起」动画容器 —— 折叠区块的**唯一**公共实现（2026-09-11 新增）。
 *
 * 用在任何"点一行标题、下面一段内容展开"的地方：内容按 [Spacing] 令牌的纵向节奏排布，
 * 高度按 [UfiMotion.Duration.Quick]（180ms，"次级面板展开"那一档）补间，同时做透明度过渡。
 *
 * ## 为什么不直接用 `AnimatedVisibility`（这是本组件存在的唯一理由）
 *
 * 折叠区块几乎总是躺在一个 `Arrangement.spacedBy(...)` 的列里（页面级的
 * [UfiPageBackground]、弹窗级的 [UfiDialogBody] 都是）。`AnimatedVisibility` 在**收起态
 * 仍然发出一个 0 高的子节点**，而 `spacedBy` 是"每两个子项之间加一道间距"、不看子项高度 ——
 * 于是收起之后那一处凭空多出一道卡间距，看起来像是"少了一张卡、位置还留着"。
 * 本仓在 `TrafficManagementScreen` 与 Webhook 页各踩过一次，此前的绕法是退回裸 `if`
 * （间距对了，但展开变成瞬跳）。
 *
 * 本组件同时解决两件事：
 * - 收起动画**播完之后彻底不发节点**（`rendered` 落回 false），所以外层不会算那道幽灵间距；
 * - 播放期间仍然占位并逐帧改高度，所以展开 / 收起都是一段连续运动，不是瞬跳。
 *
 * ## 实现要点
 *
 * 高度用自定义 [Layout] 而不是 `Modifier.height(dp)`：后者要把每帧的 Float 转成 Dp 状态，
 * 每帧都触发一次重组；这里把动画值只在**测量**与**绘制**两个阶段读取
 * （measure 块里读 → 只重新布局；`graphicsLayer` 块里读 → 只重绘），重组只发生两次
 * （开始渲染 / 停止渲染）。同一条纪律见 [UfiSingleChipSelector] 的滑块 `drawBehind`。
 *
 * 内容从顶边往下"露出"（`place(0, 0)` + 裁剪到容器高度），即标题下方的常规手风琴观感。
 *
 * @param expanded true = 展开。首帧按传入值直接就位（不播入场），之后每次变化都补间。
 * @param spacing 内容各子项之间的纵向间距，默认 [Spacing.CardBottomMargin] ——
 *   即页面级卡间距，于是"把几张卡包进来"时观感与不包时完全一致。
 *   放在弹窗或卡内时按所在容器的节奏传（如 [Spacing.Medium]）。
 */
@Composable
fun UfiExpandSection(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.CardBottomMargin,
    content: @Composable ColumnScope.() -> Unit
) {
    // 首帧直接就位：展开态的页面（如全手填预设）一进来就播一段"长出来"属于噪音动画。
    val fraction = remember { Animatable(if (expanded) 1f else 0f) }
    // 是否还要发子节点。收起动画播完才置 false —— 这一位就是"不留幽灵间距"的开关。
    var rendered by remember { mutableStateOf(expanded) }

    LaunchedEffect(expanded) {
        if (expanded) rendered = true
        fraction.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(UfiMotion.Duration.Quick, easing = UfiMotion.Easing.Standard)
        )
        // 只有"收起播完"才停止渲染；连点时上一次 LaunchedEffect 被取消，不会误关。
        if (!expanded) rendered = false
    }

    if (!rendered) return

    Layout(
        modifier = modifier.graphicsLayer {
            // 逐帧只重绘：动画值在本 lambda 里读，属于绘制阶段的订阅。
            alpha = fraction.value
            // 裁到容器自己的高度，否则收起过程中内容会溢出到下一张卡上面。
            clip = true
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
        }
    ) { measurables, constraints ->
        // minHeight 归零：外层若给了最小高度，内容会被强行撑开而看不出高度变化。
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val height = (placeable.height * fraction.value).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, height) {
            // 顶部对齐：内容不动，容器高度从 0 长到满，观感是"从标题下方铺开"。
            placeable.place(0, 0)
        }
    }
}
