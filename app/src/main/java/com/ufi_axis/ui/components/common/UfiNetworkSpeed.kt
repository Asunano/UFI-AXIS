package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.theme.LocalResolvedPalette

@Composable
fun UfiNetworkSpeedDisplay(
    downloadSpeed: Long,
    uploadSpeed: Long,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "下载",
                tint = palette.accent
            )
            Text(
                text = formatSpeed(downloadSpeed),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "下载",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "上传",
                tint = palette.accentSecondary
            )
            Text(
                text = formatSpeed(uploadSpeed),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "上传",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    }
}
