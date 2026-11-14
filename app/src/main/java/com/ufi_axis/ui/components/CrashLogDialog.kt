package com.ufi_axis.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.components.common.LocalUfiDialogClose
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.buildCrashExportText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 展示最近一次（或全部）闪退日志的弹窗。可复制全文、导出、删除单条或清除全部。
 *
 * 2026-09-11：补上**导出**入口。此前崩溃 dump 只能在这个弹窗里看和复制 ——
 * 而 dump 动辄几百行（堆栈 + 内存缓冲 + logcat），靠剪贴板转给别人基本不可行。
 *
 * 2026-10-30（M1.5）：导出改为 **SAF 选位置**，不再写死 `log/export/app/crash/<日期>/`；
 * 文件头补 App 版本与崩溃写入时间，导出后可直接「分享」—— 与日志页导出同一口径。
 *
 * 四个动作用统一的 [UfiButton]：原来是三个裸 M3 `TextButton` + 手写 8dp Spacer，
 * 加第四个只会把「同一排按钮各自演化」再复制一遍。破坏性动作走 `variant = Danger`，
 * 复制/导出走视觉重量更低的 `Subtle`，层级关系与原来一致。
 * 用 FlowRow 而不是 Row：四个中文标签在窄屏放不下一行时要折行，不能挤成一排省略号。
 *
 * @param onToast 复制/导出成功等反馈上抛给宿主（state 由宿主持有，保证弹窗关闭后 Toast 仍可见）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashLogDialog(
    file: File,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onToast: (ToastMessage) -> Unit
) {
    val context = LocalContext.current
    val content = remember(file) {
        runCatching { file.readText() }.getOrDefault("(无法读取日志文件)")
    }
    val scope = rememberCoroutineScope()

    // ── 导出（2026-10-30 M1.5：与日志页同口径，SAF 选位置）──
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) {
            onToast(ToastMessage("已取消导出", ToastType.INFO))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            exporting = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(pending.second.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开所选位置")
                }
            }
            exporting = false
            outcome.fold(
                onSuccess = {
                    exportedUri = uri
                    onToast(ToastMessage("已导出崩溃日志", ToastType.SUCCESS, durationMs = EXPORT_TOAST_MS))
                },
                onFailure = {
                    onToast(
                        ToastMessage(
                            "导出失败：${it.message ?: "未知原因"}",
                            ToastType.ERROR,
                            durationMs = EXPORT_TOAST_MS
                        )
                    )
                }
            )
        }
    }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "发现闪退日志",
        icon = rememberVectorPainter(Icons.Filled.Warning),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Text(
                text = content,
                style = UfiTextStyles.monoCaption
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                // 「清除全部」「删除此条」会关掉本弹窗，关闭动作交给 shell 排时序：
                // 离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。必须在弹窗自己的
                // content lambda 内部读，在弹窗外面读会拿到"直接执行"的默认实现。
                // 「导出」「复制」不关闭弹窗，因此不包。
                val close = LocalUfiDialogClose.current
                UfiButton(
                    text = "清除全部",
                    onClick = { close(onCleared) },
                    variant = UfiButtonVariant.Danger,
                    size = UfiButtonSize.Small
                )
                UfiButton(
                    text = "删除此条",
                    onClick = {
                        close {
                            file.delete()
                            onDismiss()
                        }
                    },
                    variant = UfiButtonVariant.Danger,
                    size = UfiButtonSize.Small
                )
                UfiButton(
                    text = "导出",
                    enabled = !exporting,
                    onClick = {
                        val version = runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: "unknown"
                        pendingExport = file.name to buildCrashExportText(
                            fileName = file.name,
                            fileModifiedAt = file.lastModified(),
                            exportedAt = System.currentTimeMillis(),
                            appVersionName = version,
                            content = content
                        )
                        exportLauncher.launch(file.name)
                    },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
                exportedUri?.let { uri ->
                    UfiButton(
                        text = "分享",
                        onClick = {
                            runCatching {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "分享崩溃日志"))
                            }.onFailure {
                                onToast(ToastMessage("没有可用的分享应用", ToastType.ERROR))
                            }
                        },
                        variant = UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Small
                    )
                }
                UfiButton(
                    text = "复制",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("UFI-AXIS crash log", content))
                        onToast(ToastMessage("已复制闪退日志", ToastType.SUCCESS))
                    },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            }
        }
    }
}

/** 导出/分享失败原因与结果提示要留足阅读时间（默认时长读不完一条路径或异常原因）。 */
private const val EXPORT_TOAST_MS = 5000L
