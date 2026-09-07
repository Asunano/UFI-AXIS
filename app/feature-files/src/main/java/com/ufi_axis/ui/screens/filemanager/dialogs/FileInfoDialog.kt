package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiDialogBody
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.components.common.UfiDialogInfoRow
import com.ufi_axis.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件信息对话框（T7，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiScrollableDialog]，以
 * [com.ufi_axis.ui.components.common.UfiDialogBody] + 多行
 * [com.ufi_axis.ui.components.common.UfiDialogInfoRow] 展示文件元数据。
 *
 * 大小与修改时间的格式化复用 [FileRowCard][com.ufi_axis.ui.screens.filemanager.components.FileRowCard]
 * 中的私有实现（[formatSize] / [formatDate]），此处原样拷贝以保证一致。
 *
 * @param visible 是否显示
 * @param file 文件数据（[com.ufi_axis.data.api.FileItem]）
 * @param onDismiss 关闭回调
 */
@Composable
fun FileInfoDialog(
    visible: Boolean,
    file: FileItem,
    onDismiss: () -> Unit
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "文件信息",
        icon = rememberVectorPainter(Icons.Filled.Info),
        showCloseButton = false
    ) {
        UfiDialogBody {
            UfiDialogInfoRow("名称", file.name)
            UfiDialogInfoRow("路径", file.path, multiline = true)
            UfiDialogInfoRow("类型", if (file.isDirectory) "目录" else "文件")
            UfiDialogInfoRow("大小", FormatUtils.formatSize(file.size))
            UfiDialogInfoRow("修改时间", formatDate(file.lastModified))
            UfiDialogInfoRow("权限", file.permissions)
            UfiDialogInfoRow("符号链接", if (file.isSymlink) "是" else "否")
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = onDismiss,
            confirmText = "关闭",
            dismissText = null
        )
    }
}

/**
 * 2026-08-31：私有 formatSize 已删除 —— 收敛到 [com.ufi_axis.util.FormatUtils.formatSize]。
 */

/**
 * 将毫秒时间戳格式化为 yyyy-MM-dd 的日期字符串。
 *
 * 与 [com.ufi_axis.ui.screens.filemanager.components.FileRowCard] 中的实现保持一致。
 *
 * @param millis 最后修改时间（毫秒）
 * @return 格式化后的日期；无效时间返回 "-"
 */
private fun formatDate(millis: Long): String {
    if (millis <= 0L) return "-"
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(Date(millis))
}
