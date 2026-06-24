package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * 网格小卡片入口 — 图标 + 标题 + 简短描述
 * 适用于工具首页的管理与自动化等网格布局
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
    Card(
        modifier = modifier,
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder(),
        onClick = onClick
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = palette.iconTint)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                description, style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp
            )
        }
    }
}