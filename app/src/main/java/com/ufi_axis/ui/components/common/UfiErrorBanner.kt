package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
fun UfiErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.accent.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.2f))
    ) {
        Row(
            Modifier.padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.error
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("重试", fontWeight = FontWeight.SemiBold, color = palette.accent)
                }
            }
        }
    }
}

@Composable
fun UfiOfflineBanner(
    lastUpdated: String?,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.accent.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.2f))
    ) {
        Row(
            Modifier.padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "后端服务未连接",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.error
                )
                if (lastUpdated != null) {
                    Text(
                        "最后更新: $lastUpdated",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}