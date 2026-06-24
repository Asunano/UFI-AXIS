package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 标签-值行 — 在一行内显示标签和对应的值文本
 * 适用于 SIM 卡信息等 key-value 展示场景
 */
@Composable
fun UfiLabelValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(modifier = modifier) {
        Text(
            "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}