package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.viewmodel.state.DownloadConfigItem

@Composable
internal fun NewDownloadDialog(
    config: DownloadConfigItem,
    onDismiss: () -> Unit,
    onConfirm: (url: String, fileName: String?, savePath: String?, speedLimit: Long?, connections: Int?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var savePath by remember { mutableStateOf("") }
    var speedLimitText by remember { mutableStateOf("") }
    var connectionsText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    val onStartClick = {
        if (url.isNotBlank()) {
            val speedLimit = speedLimitText.toLongOrNull()?.let { if (it > 0) it * 1024 * 1024 else null }
            val connections = connectionsText.toIntOrNull()?.let { if (it > 0) it else null }
            onConfirm(url, fileName.ifBlank { null }, savePath.ifBlank { null }, speedLimit, connections)
        }
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "新建下载",
        icon = rememberVectorPainter(Icons.Filled.Download),
        showCloseButton = false,
        confirmButton = {
            UfiButton(
                text = "开始下载",
                onClick = onStartClick,
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogField("下载链接") {
                UfiTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "",
                    placeholder = "https:// / magnet: / ftp:// ...",
                    singleLine = true
                )
            }

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(
                    if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showAdvanced) "收起选项" else "高级选项", style = MaterialTheme.typography.labelMedium)
            }

            // 2026-09-04：原为 `AnimatedVisibility(visible = showAdvanced)`。
            // 改成朴素 if：下载模块本轮统一去掉所有动效（用户明确要求）。
            // AnimatedVisibility 在这里还有个副作用 —— 它自带 expand/shrink 会让弹窗高度做补间，
            // 展开过程中对话框整体尺寸每帧都在变，输入框跟着抖；直接 if 是一帧到位。
            if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UfiDialogTextField("文件名 (可选)", fileName, { fileName = it }, placeholder = "自动检测")
                    UfiDialogTextField("保存路径 (可选)", savePath, { savePath = it }, placeholder = config.saveDir)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            UfiDialogField("限速 (MB/s)") {
                                UfiTextField(
                                    value = speedLimitText, onValueChange = { speedLimitText = it },
                                    label = "", placeholder = "不限", singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            UfiDialogField("连接数") {
                                UfiTextField(
                                    value = connectionsText, onValueChange = { connectionsText = it },
                                    label = "", placeholder = "默认", singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
internal fun RenameDownloadDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    val trimmed = name.trim()

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "重命名",
        icon = rememberVectorPainter(Icons.Filled.Edit),
        showCloseButton = false,
        confirmButton = {
            UfiButton(
                text = "确定",
                onClick = { if (trimmed.isNotBlank()) onConfirm(trimmed) },
                enabled = trimmed.isNotBlank()
            )
        },
        dismissButton = {
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = onDismiss
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField("文件名", name, { name = it }, placeholder = "文件名", singleLine = true)
        }
    }
}
