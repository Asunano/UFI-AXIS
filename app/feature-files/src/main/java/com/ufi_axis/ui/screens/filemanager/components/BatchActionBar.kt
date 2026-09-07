package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard

/**
 * 批量操作条（ticket T9 — 多选模式底部操作条）。
 *
 * 一个无状态的横向卡片，仅在多选模式下由 [com.ufi_axis.ui.screens.filemanager.FileManagerRoot]
 * 通过 `state.multiSelectMode` 守卫后挂载到 [androidx.compose.foundation.layout.Column] 末尾。
 *
 * 设计系统约束（红线）：
 *  - 仅展示一个底部操作条，**不**使用 [androidx.compose.material3.AlertDialog]，
 *    也**不**调用 `UfiDialogShell(...)`（`:app:ui` 的 internal 函数，外部模块调用会编译失败）。
 *  - 颜色统一走 [com.ufi_axis.ui.theme.LocalResolvedPalette.current]：背景使用
 *    [com.ufi_axis.ui.theme.ufiStandardCard]（内部即 `palette.cardBg`，等价于设计稿的
 *    `palette.surface`），主文字 `palette.textPrimary`、次要 `palette.textSecondary`，
 *    删除按钮使用 `palette.warning`（橙）。**禁止** `colorScheme.tertiary`。
 *  - 全站统一 10dp 圆角（[com.ufi_axis.ui.theme.ufiStandardCard] 默认值）。
 *  - 按钮写法与 [com.ufi_axis.ui.screens.filemanager.FileToolbar] 保持一致：
 *    `IconButton` + `Icon`，`tint` 取自 palette，全部设置中文 `contentDescription`。
 *
 * 自身是 Row/卡片，由调用方负责在 Column 中定位，**禁止**内部使用 `Modifier.align()`。
 *
 * @param selectedCount 当前已选中的条目数量
 * @param onSelectAll 点击「全选」
 * @param onCopy 点击「复制」
 * @param onCut 点击「剪切」
 * @param onDelete 点击「删除」（warning 橙）
 * @param onCancel 点击「取消」（退出多选模式）
 * @param modifier 修饰符
 */
@Composable
fun BatchActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current

    Box(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 420.dp)
            .ufiStandardCard(elevation = 8.dp)
            .padding(horizontal = Spacing.PagePadding, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：已选数量文案
            Text(
                text = "已选 $selectedCount 项",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )

            // 右侧：操作按钮组（全选 / 复制 / 剪切 / 删除 / 取消）
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = "全选",
                        tint = palette.textSecondary
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制",
                        tint = palette.textSecondary
                    )
                }
                IconButton(onClick = onCut) {
                    Icon(
                        imageVector = Icons.Filled.ContentCut,
                        contentDescription = "剪切",
                        tint = palette.textSecondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = palette.warning
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消",
                        tint = palette.textSecondary
                    )
                }
            }
        }
    }
}
