package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiChoiceSheet

/**
 * 排序方式底部选择面板（T8，纯展示组件）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiChoiceSheet]。[UfiChoiceSheet] 自身无 `visible`
 * 参数（被调用即挂载 ModalBottomSheet），因此此处用 `if (visible)` 守卫。选中某项会触发
 * [onSelect]（带回排序键）随后由 [UfiChoiceSheet] 内部关闭面板。
 *
 * 排序键对齐 `FileManagerModule.setSortBy`：name(默认) | size | date | type。
 *
 * @param visible 是否显示
 * @param currentSort 当前排序键（用于高亮已选项），默认 "name"
 * @param onSelect 选择某排序项时回调，参数为排序键
 * @param onDismiss 关闭回调
 */
@Composable
fun SortSheet(
    visible: Boolean,
    currentSort: String,
    onSelect: (sortKey: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (visible) {
        UfiChoiceSheet(
            title = "排序方式",
            options = listOf(
                "name" to "名称",
                "size" to "大小",
                "date" to "修改时间",
                "type" to "类型"
            ),
            selectedValue = currentSort,
            onDismiss = onDismiss,
            onSelect = onSelect
        )
    }
}
