package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RemotePushJobInfo
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiBadge
import com.ufi_axis.ui.components.common.UfiBadgeType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiEmptyState
import com.ufi_axis.ui.components.common.UfiInfoRow
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.FileManagerModule

/**
 * 推送任务页（core 暂存 → 外部存储源，2026-09-21）。
 *
 * ## 为什么是页面而不是弹窗
 * 一条作业要显示文件名、目标源、大小、状态、进度、失败原因，还要有取消 / 重试两个按钮；
 * 再叠上"失败详情"就只能嵌两层弹窗。而这一屏是用户**排查问题**时看的（"我传的文件到哪了"），
 * 需要能滚、能读长文本、能复制。
 *
 * ## 为什么按源过滤（[sourceId]）
 * 入口在每个外部存储源自己的工具栏右上角：人是在"CF R2 里面"想知道"我传给 CF R2 的
 * 那几个文件到了没有"。把所有源的作业混在一个全局列表里，等于把挑拣的工作又推回给用户。
 *
 * ## 错误详情为什么必须能复制
 * 远端失败的真正线索在原始异常里（FTP 的 reply、S3 的 error code、SSL 握手原因），
 * 那种字符串既长又不适合朗读。core 会把完整异常链 + 推送上下文一起回传（`error_detail`），
 * 这里提供一键复制。
 *
 * @param sourceId 只看这个源的作业；null = 全部
 */
@Composable
fun RemotePushScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    sourceId: String? = null
) {
    val state by viewModel.files.state.collectAsState()
    val palette = LocalResolvedPalette.current
    val clipboard = LocalClipboardManager.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var detailJob by remember { mutableStateOf<RemotePushJobInfo?>(null) }

    // 进页面就拉一次，并在还有在途作业时开始轮询（轮询自己会在全部结束后停）。
    LaunchedEffect(Unit) {
        viewModel.files.loadRemotePushJobs()
        viewModel.files.startRemotePushPolling()
    }

    val jobs = state.remotePushJobs.filter { sourceId == null || it.source_id == sourceId }
    val sourceLabel = jobs.firstOrNull()?.source_label
        ?: state.allRemoteSources.firstOrNull { it.id == sourceId }?.label
    val hasFinished = jobs.any { it.state !in ACTIVE_STATES }

    UfiScreenScaffold(
        title = if (sourceId != null && !sourceLabel.isNullOrBlank()) "推送任务 · $sourceLabel" else "推送任务",
        navController = navController,
        showBack = true,
        actions = {
            if (hasFinished) {
                IconButton(onClick = {
                    viewModel.files.clearFinishedRemotePush()
                    toastMessage = ToastMessage("已清除完成记录", ToastType.SUCCESS)
                }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清除记录", tint = palette.textPrimary)
                }
            }
        }
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            if (jobs.isEmpty()) {
                UfiEmptyState(
                    icon = Icons.Filled.CloudUpload,
                    message = "没有推送任务",
                    hint = "上传到外部存储源时，文件会先传到设备，再由设备推送到远端。这一页显示推送那一段的状态。",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    jobs.forEach { job ->
                        // 一条作业一张单行卡（公共容器 UfiSettingsRowCard 自带边距与阴影），
                        // 不再手搓 Box + ufiStandardCard —— 那样卡与卡的间距、阴影深浅
                        // 都会和设置页/网络页里的同类列表对不上。
                        UfiSettingsRowCard {
                            PushJobContent(
                                job = job,
                                onCancel = { viewModel.files.cancelRemotePush(job.id) },
                                onRetry = { viewModel.files.retryRemotePush(job.id) },
                                onDetail = { detailJob = job }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Medium))
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    // 失败详情：等宽字体 + 可滚动 + 一键复制。
    detailJob?.let { job ->
        val detail = job.error_detail?.takeIf { it.isNotBlank() }
            ?: job.error?.takeIf { it.isNotBlank() }
            ?: "设备端没有回传更详细的原因（可能是旧版 core）。"
        UfiCustomDialog(
            visible = true,
            onDismiss = { detailJob = null },
            title = "失败详情",
            showCloseButton = true
        ) {
            UfiDialogBody {
                Text(
                    text = detail,
                    // 等宽字体：异常链里有路径、类名和数字，等宽才对得齐、也更容易看出在哪一层断的
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = palette.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UfiButton(
                    text = "复制",
                    onClick = {
                        clipboard.setText(AnnotatedString(detail))
                        toastMessage = ToastMessage("已复制失败详情", ToastType.SUCCESS)
                    },
                    variant = UfiButtonVariant.Secondary,
                    modifier = Modifier.weight(1f)
                )
                UfiButton(
                    text = "关闭",
                    onClick = { detailJob = null },
                    variant = UfiButtonVariant.Primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PushJobContent(
    job: RemotePushJobInfo,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDetail: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val active = job.state in ACTIVE_STATES
    val errorText = job.error

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = job.file_name.ifEmpty { "(未知文件)" },
            style = UfiTextStyles.panelTitleStrong,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (active) {
            UfiLoadingIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2f)
            Spacer(Modifier.size(6.dp))
        }
        UfiBadge(text = stateLabel(job), type = badgeType(job.state))
    }

    Spacer(Modifier.height(4.dp))
    UfiDivider()

    UfiInfoRow(label = "目标", value = job.source_label.ifEmpty { job.source_id })
    UfiInfoRow(
        label = "大小",
        value = if (active && job.total_bytes > 0) {
            "${FormatUtils.formatSize(job.sent_bytes)} / ${FormatUtils.formatSize(job.total_bytes)}"
        } else if (job.total_bytes > 0) {
            FormatUtils.formatSize(job.total_bytes)
        } else {
            "未知"
        }
    )

    if (!errorText.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodySmall,
            color = palette.warning,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active) {
            UfiButton(
                text = "取消",
                onClick = onCancel,
                variant = UfiButtonVariant.Danger,
                modifier = Modifier.weight(1f)
            )
        }
        if (job.retryable) {
            UfiButton(
                text = "重试",
                onClick = onRetry,
                variant = UfiButtonVariant.Primary,
                modifier = Modifier.weight(1f)
            )
        }
        if (!errorText.isNullOrBlank() || !job.error_detail.isNullOrBlank()) {
            UfiButton(
                text = "失败详情",
                onClick = onDetail,
                variant = UfiButtonVariant.Secondary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 状态文案。推送中带百分比 —— 只写"推送中"看不出它到底有没有在动。 */
private fun stateLabel(job: RemotePushJobInfo): String = when (job.state) {
    FileManagerModule.PUSH_STATE_QUEUED -> "排队中"
    FileManagerModule.PUSH_STATE_PUSHING ->
        if (job.progress >= 0f) "推送中 ${(job.progress * 100).toInt()}%" else "推送中"
    FileManagerModule.PUSH_STATE_SUCCESS -> "已完成"
    FileManagerModule.PUSH_STATE_FAILED -> "失败"
    FileManagerModule.PUSH_STATE_CANCELLED -> "已取消"
    else -> job.state
}

private fun badgeType(state: String): UfiBadgeType = when (state) {
    FileManagerModule.PUSH_STATE_QUEUED, FileManagerModule.PUSH_STATE_PUSHING -> UfiBadgeType.INFO
    FileManagerModule.PUSH_STATE_SUCCESS -> UfiBadgeType.SUCCESS
    FileManagerModule.PUSH_STATE_FAILED -> UfiBadgeType.ERROR
    else -> UfiBadgeType.DEFAULT
}

/** 在途状态集合。与 core 的 `RemotePushManager.PushJob.active` 同一个判据。 */
private val ACTIVE_STATES = setOf(
    FileManagerModule.PUSH_STATE_QUEUED,
    FileManagerModule.PUSH_STATE_PUSHING
)
