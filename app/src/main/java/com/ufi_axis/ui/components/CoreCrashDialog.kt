package com.ufi_axis.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设备后端（core）曾经崩溃过」提示弹窗（2026-09-04 新增）。
 *
 * 与 [CrashLogDialog] 的区别是**崩到底的是谁**：那个是手机 App 自己崩溃（本地有崩溃文件，
 * 可以直接读、可以删）；这个是设备上的 core 进程崩溃 —— 它由 keepalive 脚本 / START_STICKY
 * 自动拉起，HTTP 很快恢复，此前 app 完全无感，用户只看到"数据断了一下"。
 *
 * 因此这里只展示 core 给出的摘要与详情文件路径，**不提供删除/清空** ——
 * 那些文件在设备上，删除应该走日志页的「设备日志文件」，不是在一个提醒弹窗里顺手做。
 *
 * @param timestamp 崩溃发生时刻（core 侧毫秒时间戳，同时是去重键）
 * @param summary   一句话摘要：线程 + 异常类名 + message（已脱敏）
 * @param file      详情文件在设备上的绝对路径
 */
@Composable
fun CoreCrashDialog(
    timestamp: Long,
    summary: String,
    file: String,
    onDismiss: () -> Unit,
    onToast: (ToastMessage) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    val timeText = remember(timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    val detail = buildString {
        appendLine("时间：$timeText")
        if (summary.isNotBlank()) appendLine("原因：$summary")
        if (file.isNotBlank()) appendLine("详情：$file")
    }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "设备后端曾崩溃并已自动重启",
        icon = rememberVectorPainter(Icons.Filled.Warning),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Text(
                text = detail.trimEnd(),
                style = UfiTextStyles.monoCaption
            )
            Text(
                text = "服务已由守护脚本自动拉起，功能不受影响。完整堆栈在设备上的上述文件里，" +
                    "也可以在「日志 → 设备日志文件」中查看。",
                style = UfiTextStyles.monoCaption,
                color = palette.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("UFI-AXIS core crash", detail))
                    onToast(ToastMessage("已复制崩溃信息", ToastType.SUCCESS))
                }) { Text("复制") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}
