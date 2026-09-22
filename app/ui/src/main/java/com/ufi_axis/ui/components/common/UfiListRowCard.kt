// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard

/**
 * 通用「一行一项」列表卡：前置槽（缩略图 / 图标容器）+ 标题 + 副信息 + 后置槽。
 *
 * ## 与既有两个组件的分工
 * - [UfiEntryCard]：前置固定是一个 `ImageVector` 图标、后置固定是右箭头 —— 「点进去还有下一层」；
 * - [UfiSettingsRowCard]：容器型，里面塞 [UfiSettingsItem] 那种"标题 + 开关/值"；
 * - 本组件：前置**是任意内容**（媒体缩略图是 72×44 的图，不是图标），后置也任意（时长、勾选、菜单）。
 *
 * 加它的理由是媒体中心三个分栏原本各自手搓 `Row + padding + Divider`：字号、行距、选中态
 * 全是页面里现搓的字面量，跟文件管理器的行卡（`FileRowCard`）视觉不一致。现在视觉口径
 * 收在这里一份：`ufiStandardCard()` 的卡面与阴影、[UfiTextStyles.bodyLeadStrong] 标题、
 * [UfiTextStyles.note] 副信息、选中态 accent 8% 底 + 50% 描边（与 `FileRowCard` 同值）。
 *
 * @param title 主文案，单行省略
 * @param subtitle 副文案（null / 空串 = 不占位）
 * @param selected 选中/正在播放态：叠 accent 8% 底色 + 描边
 * @param onClick null = 纯展示行，不给点击反馈
 * @param leading 前置槽（缩略图容器自己决定尺寸；本组件不钉尺寸）
 * @param trailing 后置槽（时长、勾选框、更多按钮…）
 * @param onLongClick 长按回调（2026-09-20 追加）。
 *
 *   ## 为什么是参数，而不是让调用方在 [modifier] 里自己包一层 `combinedClickable`
 *   1. **手势必须落在圆角裁剪之后**：卡面的 `clip(shape)` 是本组件内部 `ufiStandardCard()`
 *      做的，而 [modifier] 处在整条链的最前面。调用方在那里挂手势，按压水波纹就画在
 *      裁剪之外 —— 同一个列表里"能长按的行"是方角波纹、"只能单击的行"是圆角波纹。
 *   2. **两个手势检测器会互相吃事件**：调用方包了 `combinedClickable` 还照常传 [onClick]
 *      的话，同一个节点上叠两层可点击修饰符，内层先消费掉按下事件、长按就永远不触发。
 *      要避开只能约定"用长按时不许传 onClick"，那是个**隐式**契约，比多一个参数更难守。
 *
 *   为 null 时行为与从前逐字相同（走 [onClick] 那条 `clickable`），
 *   所以 [F24] 的向后兼容要求成立：参数带默认值、追加在最末、现有调用点一行都不用改。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UfiListRowCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiStandardCard(elevation = 2.dp)
            .then(
                if (selected) {
                    Modifier
                        .background(palette.accent.copy(alpha = 0.08f))
                        .border(1.dp, palette.accent.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
            .then(
                // 三档互斥：有长按 → combinedClickable（onClick 缺省时给个空实现，
                // 否则纯长按的行连"按下"反馈都没有）；只有单击 → 原来的 clickable；
                // 都没有 → 纯展示行，一个手势修饰符都不挂。
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick
                    )
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.Medium))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = UfiTextStyles.bodyLeadStrong,
                    color = if (selected) palette.accent else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.Small))
                trailing()
            }
        }
    }
}
