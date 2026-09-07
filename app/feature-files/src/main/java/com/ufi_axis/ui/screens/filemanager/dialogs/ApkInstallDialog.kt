package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiConfirmDialog

/**
 * APK 安装确认对话框（T8，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiConfirmDialog]，标准（非危险）确认样式——
 * `destructive = false` 使用主色确认按钮；橙色 destructive 语义保留给删除类操作。
 * 仅展示确认文案并上抛确认/取消，不执行安装。
 *
 * @param visible 是否显示
 * @param fileName 待安装的 APK 文件名，用于拼接到确认文案
 * @param onConfirm 点击「安装」回调
 * @param onDismiss 关闭回调
 */
@Composable
fun ApkInstallDialog(
    visible: Boolean,
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    UfiConfirmDialog(
        visible = visible,
        title = "安装 APK",
        text = "确定要安装「$fileName」吗？",
        confirmText = "安装",
        dismissText = "取消",
        destructive = false,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
