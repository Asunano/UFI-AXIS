package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.state.ClipboardEntry

/**
 * 常驻粘贴横幅（ticket T10）。
 *
 * 无状态横向卡片：当 [com.ufi_axis.ui.screens.filemanager.FileManagerRoot] 检测到
 * `state.clipboard != null` 时挂载到 [androidx.compose.foundation.layout.Column] 末尾。
 * 提供「粘贴到这里」（调用方执行 pasteFromClipboard）与「取消」（clearClipboard）。
 *
 * 设计系统约束（红线）：仅展示横幅卡片，不使用 AlertDialog / 不调用 UfiDialogShell；
 * 颜色走 LocalResolvedPalette（textPrimary / textSecondary / accent / warning），禁 tertiary；
 * 卡片用 ufiStandardCard()（10dp 圆角）；内部禁止 Modifier.align()。
 *
 * @param clipboard 当前剪贴板条目（非空时由调用方守卫后传入）
 * @param onPaste 点击「粘贴到这里」
 * @param onCancel 点击「取消」
 * @param modifier 修饰符
 */
@Composable
fun PasteBanner(
    clipboard: ClipboardEntry,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val count = clipboard.sourcePaths.size
    val verb = if (clipboard.isCut) "已剪切" else "已复制"
    val label = if (count == 1) {
        val name = clipboard.sourcePath.substringAfterLast("/")
        "$verb：$name"
    } else {
        "$verb $count 项"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp)
            .ufiStandardCard()
            .padding(horizontal = Spacing.PagePadding, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = null,
                    tint = palette.accent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = palette.textPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textSecondary)
                ) {
                    Text(text = "取消")
                }
                TextButton(
                    onClick = onPaste,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)
                ) {
                    Text(text = "粘贴到这里")
                }
            }
        }
    }
}
