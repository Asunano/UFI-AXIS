package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiInputDialog

/**
 * 新建文件对话框（T7，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiInputDialog]，采集文件名并上抛。
 * 仅传文件名（不含路径），调用方负责拼接当前路径调用 `FileManagerModule.createFile(name)`。
 * 名称校验同新建文件夹（非空、不含 "/")。
 *
 * @param visible 是否显示
 * @param onConfirm 点击「创建」且校验通过时回调，参数为用户输入的文件名
 * @param onDismiss 关闭回调
 */
@Composable
fun NewFileDialog(
    visible: Boolean,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    UfiInputDialog(
        visible = visible,
        title = "新建文件",
        hint = "文件名称",
        confirmText = "创建",
        validator = { n ->
            when {
                n.isBlank() -> "名称不能为空"
                n.contains('/') -> "名称不能包含 /"
                else -> null
            }
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
