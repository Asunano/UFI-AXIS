package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogChipSelector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.components.common.UfiDialogTextField

@Composable
fun SearchDialog(
    visible: Boolean,
    currentDepth: Int,
    onSearch: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf(currentDepth.coerceIn(1, 3)) }
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "搜索文件",
        icon = rememberVectorPainter(Icons.Filled.Search),
        showCloseButton = false
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "关键词",
                value = query,
                onValueChange = { query = it },
                placeholder = "输入文件名（留空搜全部）"
            )
            UfiDialogChipSelector(
                label = "搜索范围",
                options = listOf(
                    "1" to "当前目录",
                    "2" to "2 层",
                    "3" to "3 层"
                ),
                selectedValue = depth.toString(),
                onSelect = { depth = it.toIntOrNull() ?: 3 }
            )
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = { onSearch(query.trim(), depth) },
            confirmText = "搜索",
            dismissText = "取消"
        )
    }
}
