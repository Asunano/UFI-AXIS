// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 横向列表入口卡：图标 + 标题 + 副文案 + 可选状态徽标 + 右箭头。
 *
 * 用于"点进去还有下一层"的列表项（隧道列表、通道列表等）。与 [UfiActionCard]（居中大图标网格卡）
 * 的区别是本组件是**一行一项**、可带状态徽标。
 */
@Composable
fun UfiEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeType: UfiBadgeType = UfiBadgeType.SUCCESS,
    highlighted: Boolean = false,
    iconTint: androidx.compose.ui.graphics.Color? = null
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(
            1.dp,
            if (highlighted) palette.accent.copy(alpha = 0.6f) else palette.cardBorder
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint ?: palette.accent,
                modifier = Modifier.size(Spacing.IconSizeLarge)
            )
            Spacer(Modifier.width(Spacing.Large))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = UfiTextStyles.listItemTitle,
                    color = palette.textPrimary
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                }
            }
            if (badgeText != null) {
                UfiBadge(text = badgeText, type = badgeType)
                Spacer(Modifier.width(Spacing.Small))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
        }
    }
}
