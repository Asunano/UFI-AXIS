// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard


/**
 * 内容尺寸动画的 spec。
 *
 * ★ 2026-11-02（切 Tab 落定前的顿挫）：页面转场进行中（[UiFrameGate.isBusy]）时**不做尺寸动画**。
 *
 * `animateContentSize` 一旦被"数据到达"触发，就会在这段动画里**逐帧重新 measure/layout 整个卡片**。
 * 而入页的首批数据正好在切页后 30–250ms 落地 —— 那是页面转场（≈350–420ms）的最后 100–200ms，
 * 两者叠在一起的观感就是「动画结尾顿一下」。此时页面还在滑入，硬切肉眼看不出来。
 *
 * 读的是普通快照值（不是 Compose state）：本函数的调用点在组合期，`isBusy` 变化本身不值得触发重组；
 * 真正需要它时（数据到达导致内容变化）组合必然重跑一次，取到的就是当下值。
 */
private val ContentSizeSpec: FiniteAnimationSpec<IntSize>
    get() = if (com.ufi_axis_core.util.UiFrameGate.isBusy) {
        tween(0)
    } else {
        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
    }

@Composable
fun UfiSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // 统一使用 ufiStandardCard 阴影链（灰影 #9CA4AC@30%），与 Box(.ufiStandardCard()) 完全一致
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.CardHorizontalMargin, end = Spacing.CardHorizontalMargin)
            .ufiStandardCard()
            .padding(Spacing.CardPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = ContentSizeSpec),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

/**
 * **单行卡**形态的设置容器：一个条目一张卡，页面用 `Arrangement.spacedBy(10.dp)` 把它们隔开。
 *
 * 与 [UfiSettingsGroup] 的区别只是形态，不是层级：
 * - [UfiSettingsGroup]：多个条目共用一张大卡（阴影 4dp、内边距 20dp），条目之间贴在一起；
 * - [UfiSettingsRowCard]：每个条目独立成卡（阴影 2dp、内边距 16dp），靠卡间距分隔 ——
 *   设置页 / 网络页那种「一屏若干个平级入口」的布局用这个，别再各自手搓
 *   `Box(Modifier.ufiCardShadow(...).clip(...).background(...).border(...))`。
 *
 * 纵向内边距只给 8dp：行内的 [UfiSettingsItem] 自带 8dp 上下留白，合起来正好 16dp。
 * 卡里放的不是设置行（自定义 Hero / 表单 / chip 区）时，用 [contentPadding] 覆盖成 `PaddingValues(16.dp)`
 * 或 `PaddingValues(20.dp)`，不要在外面再套一层 `Box(...).padding(...)`。
 */
@Composable
fun UfiSettingsRowCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Spacing.XLarge,
        vertical = Spacing.Medium
    ),
    /**
     * 整卡可点（2026-11-08 新增，默认 null = 不可点，既有调用点不受影响）。
     *
     * 用在"卡片本身就是入口"的场合（如应用商店：点卡片看详情）。
     * 放在 `ufiStandardCard()` 之后，所以点击热区正好是卡片边界（不含外面的 16dp 间隙），
     * 水波纹也按卡片圆角裁剪。卡片内部的按钮仍各自消费自己的点击。
     */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
            .ufiStandardCard(elevation = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = ContentSizeSpec),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

/**
 * 卡片内的分组标题。
 *
 * @param trailing 标题右侧操作槽（2026-09-03 新增，默认 null = 纯标题，向后兼容）。
 *   分组级动作（检查更新 / 清除 / 刷新）放这里，而不是在卡片底部再摆一颗通栏按钮：
 *   通栏按钮会把"这一组的次要操作"做成视觉上的主操作，且卡片越长按钮离标题越远。
 */
@Composable
fun UfiGroupHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    val palette = LocalResolvedPalette.current
    if (trailing == null) {
        Text(
            text = title,
            style = UfiTextStyles.cardTitle,
            color = palette.accent,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 4.dp, bottom = Spacing.GroupSpacing)
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = Spacing.GroupSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = UfiTextStyles.cardTitle,
            color = palette.accent,
            modifier = Modifier.weight(1f, fill = false)
        )
        trailing()
    }
}