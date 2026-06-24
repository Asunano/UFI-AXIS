package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * 指标徽章卡片 — 显示一个带颜色编码的数值指标（如 RSRP/SNR/RSRQ）
 *
 * @param label 指标名称
 * @param value 格式化后的数值字符串
 * @param raw 原始数值，用于计算质量百分比
 * @param minGood 质量好的下限阈值
 * @param maxGood 质量好的上限阈值
 * @param modifier Modifier
 */
@Composable
fun UfiMetricBadge(
    label: String,
    value: String,
    raw: Int,
    minGood: Int,
    maxGood: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val quality = ((raw - minGood).toFloat() / (maxGood - minGood).toFloat()).coerceIn(0f, 1f)
    val chipColor = when {
        quality > 0.6f -> palette.accent
        quality > 0.3f -> palette.warning
        else -> palette.error
    }
    Card(
        modifier = modifier,
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder()
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = chipColor
            )
        }
    }
}