package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiConfirmDialog

/**
 * 删除确认对话框（T7，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiConfirmDialog] 并以 `destructive = true` 渲染
 * 危险语义（整框橙色告警）。仅展示确认文案并上抛确认/取消，不执行删除。
 *
 * @param visible 是否显示
 * @param targetLabel 目标名称（文件夹/文件），用于拼接到确认文案
 * @param onConfirm 点击「删除」回调
 * @param onDismiss 关闭回调
 */
@Composable
fun DeleteConfirmDialog(
    visible: Boolean,
    targetLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    UfiConfirmDialog(
        visible = visible,
        title = "删除确认",
        text = "确定要删除「$targetLabel」吗？此操作不可撤销。",
        confirmText = "删除",
        dismissText = "取消",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
