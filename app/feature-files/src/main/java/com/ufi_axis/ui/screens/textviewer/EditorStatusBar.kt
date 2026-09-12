package com.ufi_axis.ui.screens.textviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 底部状态条。
 *
 * 与旧实现的差别在于**这里的数字是真的**：旧版把总行数写成「第 N 行」、把 `truncated`
 * 的布尔字面量直接展示（`截断=false`）。现在光标行列来自 [TextViewerState.value] 的选区，
 * 文件级信息（大小 / 编码 / 换行符 / 是否本地副本）挪到顶栏，底栏只留与光标和保存有关的三件事。
 *
 * 右侧状态多了一档「未开启编辑」：2026-09-11 删掉只读态之后，唯一的"不能输入"情形只剩
 * [TextViewerState.canEdit] 这道闸门未确认（超大文件），必须说得明白，
 * 否则用户会以为编辑器坏了。
 */
@Composable
fun EditorStatusBar(
    state: TextViewerState,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.usable) {
            Text(
                text = "行 ${state.cursorLine}, 列 ${state.cursorColumn}",
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary
            )
            // 没有选区就不占位：常态下这一格是空的，写「选中 0 字符」只是噪声
            if (state.selectedChars > 0) {
                Text(
                    text = "选中 ${state.selectedChars} 字符",
                    style = UfiTextStyles.noteCompact,
                    color = palette.accent
                )
            }
            Text(
                text = "${state.lineCount} 行",
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary
            )
        }
        Spacer(Modifier.weight(1f))
        val (label, color) = when {
            !state.usable -> "不可编辑" to palette.textSecondary
            state.saving -> "正在保存…" to palette.accent
            state.dirty -> "● 未保存" to palette.warning
            !state.canEdit -> "未开启编辑" to palette.warning
            else -> "已同步" to palette.textSecondary
        }
        Text(text = label, style = UfiTextStyles.noteCompact, color = color)
    }
}
