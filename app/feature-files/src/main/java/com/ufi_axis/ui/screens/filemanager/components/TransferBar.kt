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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
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
 *  - 上传行：文件名 + 批次计数 + 进度条 + 「取消」。
 *    （2026-09-19：原先写的是"上传通常很快，不提供取消" —— 那是 200MB 上限时代的判断，
 *    接入分片后单文件上限 2GB，一个视频可能要传几分钟，没有取消就只能等或杀进程。）
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
    modifier: Modifier = Modifier,
    /**
     * 取消当前上传批次。
     *
     * 2026-09-19 开放：文件头那句「上传通常很快，不提供取消」是 200MB 上限时代的判断，
     * 接入分片后上限是 2GB，一个视频可能要传几分钟 —— 没有取消就只能等或杀进程。
     */
    onCancelUpload: () -> Unit = {},
    /**
     * 本批文件总数 / 已传完数（多选上传，2026-09-19）。
     *
     * 默认 0 表示「没有批次信息」—— 那时按原样只显示文件名，不出 (n/N)。
     * 单文件上传时总数是 1，同样不显示 (1/1)：那是一句废话。
     */
    uploadTotalCount: Int = 0,
    uploadDoneCount: Int = 0,
    /**
     * ── 远端推送行（core 暂存 → 外部存储源，2026-09-21）──
     *
     * 为什么必须单独一行：上传到远端源是**两段**链路，上面那条「上传中」只描述
     * 手机 → core 这一段。它走完之后文件还在设备上，真正落到 FTP/WebDAV 要看这一段。
     * 只显示第一段等于告诉用户"传完了"，而那时远端目录里什么都没有。
     *
     * @param pushFileName 当前正在推送的文件名（无在途作业时为空）
     * @param pushSourceLabel 目标存储源的显示名
     * @param pushProgress 0f..1f；-1f = 未知
     * @param pushQueuedCount 除当前这个之外还在排队的数量（串行推送，会排队）
     * @param pushFailedCount 失败且还没被清除的作业数 —— **即便没有在途作业也要显示**，
     *   否则一次失败就彻底沉默了
     * @param onCancelPush 取消当前在途推送
     * @param onOpenPushPanel 打开任务面板（查看全部 / 重试 / 清除）。null = 不显示入口
     */
    pushFileName: String = "",
    pushSourceLabel: String = "",
    pushProgress: Float = -1f,
    pushQueuedCount: Int = 0,
    pushFailedCount: Int = 0,
    onCancelPush: () -> Unit = {},
    onOpenPushPanel: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val showUpload = isUploading || (uploadProgress >= 0f && uploadProgress < 1f)
    val showDownload = isDownloading || downloadStatus == "paused"
    val showPush = pushFileName.isNotEmpty()
    val showPushFailure = !showPush && pushFailedCount > 0
    if (!showUpload && !showDownload && !showPush && !showPushFailure) return

    Box(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 420.dp)
            .ufiStandardCard(elevation = 8.dp)
            .padding(horizontal = Spacing.PagePadding, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showUpload) {
                // 批次进度只在多于一个文件时出现。进度条本身仍然是**当前这一个**文件的推进：
                // 换成批次百分比的话，10 个小文件会让条子一格一格跳，反而看不出在动。
                val counter = if (uploadTotalCount > 1) " (${uploadDoneCount + 1}/$uploadTotalCount)" else ""
                TransferRow(
                    icon = Icons.Filled.Upload,
                    label = if (uploadFileName.isNotEmpty()) "上传中：$uploadFileName$counter" else "上传中$counter",
                    progress = if (uploadProgress >= 0f) uploadProgress else null,
                    showCancel = true,
                    onCancel = onCancelUpload,
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
            if (showPush) {
                val queued = if (pushQueuedCount > 0) "（+$pushQueuedCount 排队）" else ""
                val target = if (pushSourceLabel.isNotEmpty()) "推送到 $pushSourceLabel" else "推送到外部存储"
                TransferRow(
                    icon = Icons.Filled.CloudUpload,
                    label = "$target：$pushFileName$queued",
                    progress = if (pushProgress >= 0f) pushProgress else null,
                    showCancel = true,
                    onCancel = onCancelPush,
                    onResume = null,
                    onDetails = onOpenPushPanel
                )
            }
            if (showPushFailure) {
                // 没有在途作业但有失败记录：这一行是唯一的失败出口，不能省。
                // 进度条给 0f（而不是不确定态转圈）—— 它已经不在动了。
                TransferRow(
                    icon = Icons.Filled.CloudUpload,
                    label = "$pushFailedCount 个文件推送失败",
                    progress = 0f,
                    showCancel = false,
                    onCancel = {},
                    onResume = null,
                    onDetails = onOpenPushPanel
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
    modifier: Modifier = Modifier,
    /** 「查看详情」入口（推送行用它打开任务面板）。null = 不显示。 */
    onDetails: (() -> Unit)? = null
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
                if (onDetails != null) {
                    IconButton(onClick = onDetails) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = "查看推送任务",
                            tint = palette.textSecondary
                        )
                    }
                }
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
