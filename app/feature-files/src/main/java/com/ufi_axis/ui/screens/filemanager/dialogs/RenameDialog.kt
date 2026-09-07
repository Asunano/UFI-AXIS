package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiInputDialog

/**
 * 重命名对话框（T7，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiInputDialog]，预填当前名称 [initialName]，
 * 采集新名称并上抛。校验规则：非空、不含 "/"、且与原名不同。
 *
 * @param visible 是否显示
 * @param initialName 当前名称（作为输入框初值）
 * @param onConfirm 点击「确定」且校验通过时回调，参数为新名称
 * @param onDismiss 关闭回调
 */
@Composable
fun RenameDialog(
    visible: Boolean,
    initialName: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit
) {
    UfiInputDialog(
        visible = visible,
        title = "重命名",
        initialValue = initialName,
        hint = "新名称",
        confirmText = "确定",
        validator = { n ->
            when {
                n.isBlank() -> "名称不能为空"
                n.contains('/') -> "名称不能包含 /"
                n == initialName -> "名称未改变"
                else -> null
            }
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
