package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiRingProgress
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 上传到外部存储是**两段**链路，这个枚举就是"现在在第几段"。
 *
 * 之所以要显式区分而不是共用一个"传输中"：两段的取消语义完全不同 ——
 * 第一段取消的是手机到设备的传输（分片会被丢弃），第二段取消的是设备到远端的推送
 * （暂存文件会被删）。文案和按钮都必须跟着变，否则用户点"取消"时并不知道自己取消了什么。
 */
enum class UploadPhase {
    /** 手机 → core（可断点续传，进度来自本地字节计数） */
    UPLOADING,

    /** core 暂存 → 远端存储源（整份流式推送，进度来自轮询作业） */
    PUSHING
}

/**
 * 上传进度弹窗（2026-09-21，两阶段版）。
 *
 * ## 为什么从底部横条升级成弹窗
 * 横条能塞的信息只有"一行文字 + 一条细进度"，而上传是用户**主动发起、在意结果**的操作：
 * 传到哪、第几个 / 共几个、能不能取消，这些在一条横条里既放不下也容易被当背景噪音划过去。
 *
 * ## 为什么两段要在同一个弹窗里连续显示
 * 之前第一段用弹窗、第二段只剩底部横条，于是"进度跑到 100% 然后弹窗消失"看起来就是
 * **上传结束了** —— 而实际上文件才刚到设备、推送还没开始。两段接在一个弹窗里、
 * 用 [AnimatedContent] 做淡入淡出过渡，"还没完"这件事才不需要用户自己去推断。
 *
 * ## 「后台进行」不是"最小化窗口"
 * 点它之后传输**继续跑**，只是表现降级成底部横条 —— 用户想去别的目录挑下一批文件时，
 * 弹窗是障碍。两种形态共用同一份 state，切换只改一个本地开关，不碰任何传输逻辑。
 *
 * @param visible 是否显示
 * @param phase 当前处于哪一段
 * @param fileName 当前文件名（两段各自的当前文件）
 * @param progress 当前进度 0f..1f；负数表示未知
 * @param doneCount 已完成的文件数（仅第一段的批量计数有意义）
 * @param totalCount 本批总数；<=1 时不显示计数
 * @param targetLabel 目标位置的**人话**描述（"CF R2" / "Download"），不要摆 `remote:<id>/…`
 * @param queuedCount 第二段排队中的作业数（串行推送会排队）
 * @param onCancel 取消当前这一段
 * @param onBackground 转为后台（弹窗关闭、底部横条接管）
 */
@Composable
fun UploadProgressDialog(
    visible: Boolean,
    phase: UploadPhase,
    fileName: String,
    progress: Float,
    doneCount: Int,
    totalCount: Int,
    targetLabel: String,
    queuedCount: Int,
    onCancel: () -> Unit,
    onBackground: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = visible,
        // 点外部等于「后台进行」而不是取消：这是个进行中的任务，
        // 误触关掉后如果传输也停了，用户会丢掉已经传上去的部分。
        onDismiss = onBackground,
        title = when (phase) {
            UploadPhase.UPLOADING -> "上传到设备"
            UploadPhase.PUSHING -> "推送到 $targetLabel"
        },
        icon = rememberVectorPainter(
            when (phase) {
                UploadPhase.UPLOADING -> Icons.Filled.FileUpload
                UploadPhase.PUSHING -> Icons.Filled.CloudUpload
            }
        ),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UfiRingProgress(
                    // 进度未知时按 0 画：环是空的但能看出"在这一步"，
                    // 比不画环让弹窗忽然缩一截要稳。
                    progress = if (progress >= 0f) progress else 0f,
                    size = 120.dp,
                    strokeWidth = 8.dp
                )

                // 阶段切换的过渡：两段的文字块整体淡入淡出。
                // 不做位移/缩放 —— 弹窗高度会随文案行数变，再叠位移就会看起来在抖。
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith
                            fadeOut(animationSpec = tween(140))
                    },
                    label = "uploadPhase"
                ) { shown ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = fileName.ifBlank { "准备中…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = when (shown) {
                                UploadPhase.UPLOADING -> buildString {
                                    if (totalCount > 1) append("第 ${doneCount + 1} / $totalCount 个 · ")
                                    append("第 1 步：传到设备")
                                }
                                UploadPhase.PUSHING -> buildString {
                                    append("第 2 步：设备推送到 $targetLabel")
                                    if (queuedCount > 0) append(" · 还有 $queuedCount 个排队")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = when (shown) {
                                UploadPhase.UPLOADING ->
                                    "传完还要由设备推送到远端，这一步结束不代表文件已经在外部存储上"
                                UploadPhase.PUSHING ->
                                    "可以点「后台进行」去做别的事，推送状态在工具栏的「推送任务」里看"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            UfiButton(
                // 文案必须点明取消的是哪一段：两段取消掉的东西不一样
                text = when (phase) {
                    UploadPhase.UPLOADING -> "取消上传"
                    UploadPhase.PUSHING -> "取消推送"
                },
                onClick = onCancel,
                variant = UfiButtonVariant.Danger,
                modifier = Modifier.weight(1f)
            )
            UfiButton(
                text = "后台进行",
                onClick = onBackground,
                variant = UfiButtonVariant.Primary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
