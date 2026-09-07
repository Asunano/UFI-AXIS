// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateContentSize
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
            modifier = Modifier.fillMaxWidth().animateContentSize(),
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
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
            .ufiStandardCard(elevation = 2.dp)
            .padding(contentPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
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