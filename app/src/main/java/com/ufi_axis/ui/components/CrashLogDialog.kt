package com.ufi_axis.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import com.ufi_axis.ui.theme.UfiTextStyles
import java.io.File

/**
 * 展示最近一次（或全部）闪退日志的弹窗。可复制全文、删除单条或清除全部。
 *
 * @param onToast 复制成功等反馈上抛给宿主（state 由宿主持有，保证弹窗关闭后 Toast 仍可见）。
 */
@Composable
fun CrashLogDialog(
    file: File,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onToast: (ToastMessage) -> Unit
) {
    val context = LocalContext.current
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // P2-4：破坏性操作（清除/删除）用错误色区分，与成功操作（复制）形成层级
                TextButton(
                    onClick = onCleared,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.error)
                ) { Text("清除全部") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        file.delete()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.error)
                ) { Text("删除此条") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("UFI-AXIS crash log", content))
                    onToast(ToastMessage("已复制闪退日志", ToastType.SUCCESS))
                }) { Text("复制") }
            }
        }
    }
}
