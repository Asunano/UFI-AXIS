// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 状态卡：左侧标题 + 可选副文案，右侧状态徽标（[UfiBadge]）。
 *
 * 用于"某个服务/隧道现在是什么状态"这类一行式展示，取色与圆角统一走
 * [LocalResolvedPalette] + [UfiCardDefaults]，不要在业务页里自绘 Surface + pill。
 *
 * 2026-10-13：自带 [Spacing.CardHorizontalMargin]（16dp）横向外边距，与 [UfiSettingsRowCard] /
 * [UfiSettingsGroup] 对齐；**调用方不要再补水平 padding**，那会叠成 32dp 的双重内距。
 * [horizontalMargin] 只服务于"已经身处有横向内距的容器里"的调用点（例如短信页那个
 * `contentPadding = 16dp` 的 LazyColumn），那里显式传 `0.dp`。
 */
@Composable
fun UfiStatusCard(
    title: String,
    statusText: String,
    modifier: Modifier = Modifier,
    statusType: UfiBadgeType = UfiBadgeType.DEFAULT,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    horizontalMargin: Dp = Spacing.CardHorizontalMargin
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalMargin),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.TextStackGap)
            ) {
                Text(
                    text = title,
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
            UfiBadge(text = statusText, type = statusType)
            trailing?.let {
                Spacer(Modifier.width(Spacing.Medium))
                it()
            }
        }
    }
}
