package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard
import androidx.compose.ui.graphics.StrokeCap

/**
 * 传输任务横幅（ticket T11）。
 *
 * 无状态横向卡片：当 [com.ufi_axis.ui.screens.filemanager.FileManagerRoot] 检测到
 * 上传或下载进行中（或下载已暂停）时挂载到 Column 末尾。
 *  - 上传行：文件名 + 进度条（上传通常很快，不提供取消）。
 *  - 下载行：文件名 + 进度条 + 「取消」(warning 橙)；若状态为 paused 额外显示「恢复」(accent)。
 *
 * 设计系统约束（红线）：仅展示卡片，不使用 AlertDialog / 不调用 UfiDialogShell；
 * 颜色走 LocalResolvedPalette（textPrimary / textSecondary / accent / warning / divider），禁 tertiary；
 * 卡片用 ufiStandardCard()（10dp 圆角）；内部禁止 Modifier.align()。
 */
@Composable
fun TransferBar(
    isUploading: Boolean,
    uploadProgress: Float,
    uploadFileName: String,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadFileName: String,
    downloadStatus: String,
    onCancelDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val showUpload = isUploading || (uploadProgress >= 0f && uploadProgress < 1f)
    val showDownload = isDownloading || downloadStatus == "paused"
    if (!showUpload && !showDownload) return

    Box(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 420.dp)
            .ufiStandardCard(elevation = 8.dp)
            .padding(horizontal = Spacing.PagePadding, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showUpload) {
                TransferRow(
                    icon = Icons.Filled.Upload,
                    label = if (uploadFileName.isNotEmpty()) "上传中：$uploadFileName" else "上传中",
                    progress = if (uploadProgress >= 0f) uploadProgress else null,
                    showCancel = false,
                    onCancel = {},
                    onResume = null
                )
            }
            if (showDownload) {
                val paused = downloadStatus == "paused"
                TransferRow(
                    icon = Icons.Filled.Download,
                    label = if (downloadFileName.isNotEmpty()) {
                        if (paused) "已暂停：$downloadFileName" else "下载中：$downloadFileName"
                    } else "下载中",
                    progress = if (downloadProgress >= 0f) downloadProgress else null,
                    showCancel = true,
                    onCancel = onCancelDownload,
                    onResume = if (paused) onResumeDownload else null
                )
            }
        }
    }
}

@Composable
private fun TransferRow(
    icon: ImageVector,
    label: String,
    progress: Float?,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onResume: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = palette.accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onResume != null) {
                    IconButton(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "恢复",
                            tint = palette.accent
                        )
                    }
                }
                if (showCancel) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "取消",
                            tint = palette.warning
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = palette.accent,
                trackColor = palette.divider,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        } else {
            LinearProgressIndicator(
                color = palette.accent,
                trackColor = palette.divider,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }
    }
}
