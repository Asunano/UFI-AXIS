package com.ufi_axis.ui.screens.textviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 编辑器操作栏 —— 「图标在上、文字在下」的方块格，右对齐；**左侧同行显示文件完整路径**。
 *
 * ## 为什么从 M3 顶栏搬到这里
 * 顶栏右侧的 `IconButton` 只有图标，保存 / 撤销 / 重做 / 查找这四个**语义不同却都很像通用符号**
 * 的动作放在一起要靠记忆分辨。搬出来后：
 * - 每个动作自带文字标签，看一眼就知道点下去会发生什么；
 * - 形态与文件管理器工具栏（[com.ufi_axis.ui.screens.filemanager.FileToolbar]）**完全同一套**
 *   [UfiToolbarAction]（`vertical = true`），两个页面之间来回走不会觉得是两个应用。
 *
 * 顶栏里只留「返回」与右上角的「更多」——低频动作本就该收进菜单。
 *
 * ## 路径为什么也在这一行
 * 完整路径与四个动作**共占一条**操作栏：路径在左（folder 图标 + 单行省略），动作在右。
 * 单行省略足够当"面包屑"用——路径再长也不把操作栏顶高、不抢动作的位置；
 * 之前它独占一条地址条、一长就折成多行，反而在小屏上把整行撑高。
 *
 * ## 为什么禁用态要自己传 tint
 * [UfiToolbarAction] 的竖排分支按设计**不吃** `enabled`（文件管理器那边没有禁用场景），
 * 所以这里显式把禁用色传进去，否则"撤销不可用"看起来仍然可点。
 */
@Composable
fun EditorToolbar(
    path: String,
    canSave: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    findActive: Boolean,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val dimmed = palette.textSecondary.copy(alpha = 0.35f)

    Box(modifier = modifier.fillMaxWidth().height(ToolbarHeight)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.PagePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            // 左侧：文件完整路径（与保存 / 撤销 / 重做 / 查找同一行）。
            // 单行省略：路径只占左侧一段，过长用省略号收口（面包屑语义），
            // 不把操作栏撑高、也不抢右侧动作的位置。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = path,
                    style = UfiTextStyles.note,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            UfiToolbarAction(
                icon = Icons.Default.Save,
                label = "保存",
                onClick = onSave,
                enabled = canSave,
                tint = if (canSave) palette.accent else dimmed
            )
            UfiToolbarAction(
                icon = Icons.AutoMirrored.Filled.Undo,
                label = "撤销",
                onClick = onUndo,
                enabled = canUndo,
                tint = if (canUndo) palette.textSecondary else dimmed
            )
            UfiToolbarAction(
                icon = Icons.AutoMirrored.Filled.Redo,
                label = "重做",
                onClick = onRedo,
                enabled = canRedo,
                tint = if (canRedo) palette.textSecondary else dimmed
            )
            UfiToolbarAction(
                icon = Icons.Default.Search,
                label = "查找",
                onClick = onFind,
                tint = if (findActive) palette.accent else palette.textSecondary
            )
        }

        // 细底部分隔线收口（与 FileToolbar 一致）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider)
        )
    }
}

private val ToolbarHeight = 48.dp
