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

/**
 * 新建下载任务。
 *
 * 保存目录**点选不手打**（2026-09-20）：原来这里是一个自由文本框，用户得自己背出
 * `/storage/emulated/0/...` 这种绝对路径，打错一个字符 core 就落到别处或创建失败。
 * 现在复用公共件 [UfiDirectoryPickerDialog] 浏览设备目录树选一个。
 *
 * @param browse 列目录的能力（`DownloadModule.browseDirs`）。公共件在 `:app:ui`，
 *   不能依赖 viewmodel，所以取数一路由外面注入到这里。
 */
@Composable
internal fun NewDownloadDialog(
    config: DownloadConfigItem,
    browse: suspend (String) -> Pair<List<String>, String?>,
    onDismiss: () -> Unit,
    onConfirm: (url: String, fileName: String?, savePath: String?, speedLimit: Long?, connections: Int?) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    // 空串 = 不指定，交给 core 用引擎配置里的默认保存目录（onConfirm 传 null）。
    var savePath by remember { mutableStateOf("") }
    var speedLimitText by remember { mutableStateOf("") }
    var connectionsText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }

    /** 这次任务实际会落到哪 —— 没选过就是配置里的默认目录，界面上要显示的是这个值。 */
    val effectiveSaveDir = savePath.ifBlank { config.saveDir }

    val onStartClick = {
        if (url.isNotBlank()) {
            val speedLimit = speedLimitText.toLongOrNull()?.let { if (it > 0) it * 1024 * 1024 else null }
            val connections = connectionsText.toIntOrNull()?.let { if (it > 0) it else null }
            onConfirm(url, fileName.ifBlank { null }, savePath.ifBlank { null }, speedLimit, connections)
        }
    }

    // 目录选择器与本弹窗**互斥渲染**（而不是叠在它上面再开一层窗口）：
    // UfiDialogShell 会对宿主 window 做 dim / 模糊处理，两层同时在场时遮罩叠加、返回键
    // 落到哪一层都不好控。上面那些 remember 都在这条分支之前声明，所以选目录期间
    // 已经填好的链接 / 文件名 / 限速不会丢，选完回来原样还在。
    if (showDirPicker) {
        UfiDirectoryPickerDialog(
            visible = true,
            title = "选择保存目录",
            root = UFI_DEVICE_STORAGE_ROOT,
            // 从当前生效的目录起手，让人一眼看到"现在会存到哪"，而不是从零开始翻。
            initialSelection = listOf(effectiveSaveDir),
            multiSelect = false,
            confirmText = "使用此目录",
            browse = browse,
            onDismiss = { showDirPicker = false },
            onConfirm = { dirs ->
                dirs.firstOrNull()?.let { savePath = it }
                showDirPicker = false
            }
        )
        return
    }

    UfiCustomDialog(

        visible = true,
        onDismiss = onDismiss,
        title = "新建下载",
        icon = rememberVectorPainter(Icons.Filled.Download),
        showCloseButton = false,
        confirmButton = {
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读会拿到"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(
                text = "开始下载",
                onClick = { close(onStartClick) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = { close(onDismiss) },
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
                // 2026-09-18：原来这里还套了一层 `Column(verticalArrangement = spacedBy(8.dp))`，
                // 于是"高级选项"里的字段间距是 8dp、而它与上面的链接/按钮之间是 UfiDialogBody 的
                // 12dp —— 同一个弹窗里两种节奏，展开后看着像多塞了一道空隙。
                // 直接摊进 UfiDialogBody 的 Column（12dp）即可，间距只有一个来源。
                UfiDialogTextField("文件名 (可选)", fileName, { fileName = it }, placeholder = "自动检测")
                UfiDialogField("保存目录") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 整行可点：目标是"点一下就去选"，让用户找到那颗小按钮才算命中太苛刻。
                        UfiListRowCard(
                            title = effectiveSaveDir.substringAfterLast('/').ifBlank { effectiveSaveDir },
                            // 副行给完整路径：同名末级目录（Download、UFI）在设备上到处都有，
                            // 只显示末级名字根本分不清落到了哪一个。
                            subtitle = effectiveSaveDir,
                            onClick = { showDirPicker = true },
                            leading = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailing = {
                                UfiButton(
                                    text = "浏览",
                                    onClick = { showDirPicker = true },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                        Text(
                            if (savePath.isBlank()) "未指定，使用下载设置里的默认目录" else "仅本次任务使用此目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                        // 选错了要能退回默认：不给这颗键的话，用户只能靠自己翻回默认目录，
                        // 而默认目录是配置项、随时可能被改，手动找回来就成了猜。
                        if (savePath.isNotBlank()) {
                            UfiButton(
                                text = "恢复默认目录",
                                onClick = { savePath = "" },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // 横向这条是并排两个字段的列间距，与纵向节奏无关，保留
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
            val close = LocalUfiDialogClose.current
            UfiButton(
                text = "确定",
                onClick = { if (trimmed.isNotBlank()) close { onConfirm(trimmed) } },
                enabled = trimmed.isNotBlank()
            )
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = { close(onDismiss) }
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField("文件名", name, { name = it }, placeholder = "文件名", singleLine = true)
        }
    }
}
