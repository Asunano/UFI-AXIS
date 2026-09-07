// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow

/**
 * 网格小卡片入口 — 图标 + 标题 + 简短描述
 * 适用于工具首页的管理与自动化等网格布局
 *
 * 视觉统一为全局标准卡片样式（与 [com.ufi_axis.ui.theme.ufiStandardCard] 一致）：
 * Box + ufiCardShadow + clip + background + border 四件套，替代 M3 原生 Card。
 */
@Composable
fun UfiGridCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(palette.cardBg, cardShape)
            .border(1.dp, palette.cardBorder, cardShape)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = palette.accent)
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis)
            )
            Text(
                description,
                style = UfiTextStyles.caption,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
