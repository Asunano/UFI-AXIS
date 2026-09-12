package com.ufi_axis.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.UfiLogPaths
import java.io.File

/**
 * 展示最近一次（或全部）闪退日志的弹窗。可复制全文、导出、删除单条或清除全部。
 *
 * 2026-09-11：补上**导出**入口。此前崩溃 dump 只能在这个弹窗里看和复制 ——
 * 而 dump 动辄几百行（堆栈 + 内存缓冲 + logcat），靠剪贴板转给别人基本不可行，
 * 而用户也不知道原文件躺在哪。现在导出到 `log/export/app/crash/<日期>/`
 * （与日志导出同一棵树，路径见 [UfiLogPaths]），toast 报完整相对路径。
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
                UfiButton(
                    text = "清除全部",
                    onClick = onCleared,
                    variant = UfiButtonVariant.Danger,
                    size = UfiButtonSize.Small
                )
                UfiButton(
                    text = "删除此条",
                    onClick = {
                        file.delete()
                        onDismiss()
                    },
                    variant = UfiButtonVariant.Danger,
                    size = UfiButtonSize.Small
                )
                UfiButton(
                    text = "导出",
                    onClick = { onToast(exportCrashDump(file)) },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
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

/**
 * 把一份崩溃 dump 复制到 `log/export/app/crash/<yyyy-MM-dd>/`，沿用原文件名。
 *
 * 沿用原名（`crash_HH-mm-ss.log`）而不是另起时间戳：导出物要能和原始 dump 对上号。
 * 目录建不出来（外部存储不可写）就报失败 —— 导出物刻意不回退应用私有目录，
 * 落进私有目录等于没导出，理由见 [UfiLogPaths]。
 */
private fun exportCrashDump(file: File): ToastMessage {
    val date = UfiLogPaths.today()
    val dir = UfiLogPaths.exportDir(
        source = UfiLogPaths.APP_DIR,
        date = date,
        sub = UfiLogPaths.CRASH_DIR
    ) ?: return ToastMessage(
        "导出失败：无法创建目录 ${UfiLogPaths.exportRelative(UfiLogPaths.APP_DIR, date, UfiLogPaths.CRASH_DIR)}",
        ToastType.ERROR,
        durationMs = EXPORT_TOAST_MS
    )
    return runCatching {
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        ToastMessage(
            "已导出到 ${UfiLogPaths.displayPath(target.absolutePath)}",
            ToastType.SUCCESS,
            durationMs = EXPORT_TOAST_MS
        )
    }.getOrElse {
        ToastMessage("导出失败: ${it.message}", ToastType.ERROR, durationMs = EXPORT_TOAST_MS)
    }
}

/** 导出结果 toast 要报一条完整路径，默认时长读不完。 */
private const val EXPORT_TOAST_MS = 5000L
