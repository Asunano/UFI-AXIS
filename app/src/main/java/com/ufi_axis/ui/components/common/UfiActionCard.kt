package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * 大尺寸操作入口卡片 — 圆形图标 + 标题 + 描述
 * 适用于工具首页的 NAS 文件入口等场景
 */
@Composable
fun UfiActionCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier,
        shape = UfiCardDefaults.widgetShape,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder(),
        onClick = onClick
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = palette.accent.copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = palette.accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                description, style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}