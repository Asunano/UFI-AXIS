package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiInputDialog

/**
 * 新建文件夹对话框（T7，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiInputDialog]，仅负责采集文件夹名称并上抛，
 * 不持有任何 ViewModel 或业务状态。名称校验在组件内完成（非空、不含 "/")，
 * 校验失败由 [UfiInputDialog] 就地提示，不触发 [onConfirm]。
 *
 * @param visible 是否显示
 * @param onConfirm 点击「创建」且校验通过时回调，参数为用户输入的文件夹名称
 * @param onDismiss 关闭（取消 / 点外部）回调
 */
@Composable
fun NewFolderDialog(
    visible: Boolean,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    UfiInputDialog(
        visible = visible,
        title = "新建文件夹",
        hint = "文件夹名称",
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
