// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
fun UfiAlertDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    visible: Boolean = true,
    icon: Painter? = null,
    confirmText: String = "确定",
    onConfirm: () -> Unit = onDismiss,
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        icon = icon
    ) {
        // 2026-09-19：正文包进 UfiDialogBody（12dp 节奏），并删掉 Text 自己的上下 8dp。
        // 此前这三个标准弹窗是全库唯一不走 body 的一族：标题→正文 8dp、正文→按钮 8dp，
        // 而其余弹窗都是 12dp —— 同一个 App 里两套节奏。
        //
        // DialogButtonRow 刻意放在 body **外面**：它是按钮区、不是内容，放进去会吃一份
        // spacedBy(12dp) 再加上自己的 padding(top=12dp)，叠成 24dp。
        UfiDialogBody {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
        }
        // 关闭时序由 DialogButtonRow 统一接管（它内部经 LocalUfiDialogClose 排队），
        // 这里**不能**再包一层 —— 双层 requestClose 会被 closing 闸门吞掉第二次调用。
        DialogButtonRow(
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismissAction ?: onDismiss
        )
    }
}

@Composable
fun UfiConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    visible: Boolean = true,
    icon: Painter? = null
) {
    val palette = LocalResolvedPalette.current
    val warning = palette.warning
    UfiDialogShell(
        visible = visible, onDismiss = onDismiss, title = title, icon = icon,
        containerColorOverride = if (destructive) palette.warningContainer else null,
        borderOverride = if (destructive) BorderStroke(1.dp, warning.copy(alpha = if (palette.isDark) 0.5f else 0.35f)) else null,
        scrimAlpha = if (destructive) 0.15f else 0.08f,
        titleColorOverride = if (destructive) warning else null
    ) {
        // 同 UfiAlertDialog：正文进 body、按钮行留在 body 外（2026-09-19）
        UfiDialogBody {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
        DialogButtonRow(
            confirmText = confirmText, onConfirm = onConfirm,
            dismissText = dismissText, onDismiss = onDismiss,
            confirmColor = if (destructive) warning else palette.accent,   // 原 palette.error(红) → 橙色
            dividerColor = if (destructive) warning.copy(alpha = if (palette.isDark) 0.25f else 0.18f) else null,
            outlineColor = if (destructive) warning else null,
            outlineTextColor = if (destructive) warning else null
        )
    }
}

@Composable
fun UfiInputDialog(
    title: String,
    initialValue: String = "",
    hint: String = "",
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validator: ((String) -> String?)? = null,
    visible: Boolean = true,
    icon: Painter? = null
) {
    // 2026-09-12 修复：重命名弹窗每次打开需预填当前名称。原 `remember` 无 key，仅在首次
    // 组合时用初始值播种一次，之后永久复用旧文本——若首次打开时 renameTarget 尚未就绪、
    // initialValue 为空串，输入框就永远空白（用户反馈「重命名时不显示原名称」即此）。
    // 改为以 initialValue 为 key 重新播种：initialValue 变化（每次打开不同文件）即重置为正确初值；
    // 校验错误也随对话框重置，避免上一次的报错残留。
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    var error by remember(initialValue) { mutableStateOf<String?>(null) }
    val palette = LocalResolvedPalette.current

    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        icon = icon
    ) {
        // 同 UfiAlertDialog：输入框进 body、按钮行留在 body 外（2026-09-19）
        UfiDialogBody {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                placeholder = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
                singleLine = true,
                isError = error != null,
                supportingText = error?.let {
                    { Text(it, color = palette.error) }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.inputBorderFocused,
                    unfocusedBorderColor = palette.inputBorder,
                    cursorColor = palette.accent,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary
                ),
                shape = UfiCardDefaults.inputShape,
                // 横向内距的唯一来源是弹窗壳（18dp），纵向节奏的唯一来源是 UfiDialogBody（12dp）。
                // 这里两个都不能再加：加横向会缩成 36dp（2026-09-03 修过），加纵向会与 body 叠。
                modifier = Modifier.fillMaxWidth()
            )
        }
        DialogButtonRow(
            confirmText = confirmText,
            onConfirm = {
                val validationError = validator?.invoke(text)
                if (validationError != null) {
                    // 校验没过时**不关**弹窗。DialogButtonRow 会把这次点击交给
                    // LocalUfiDialogClose，shell 发现弹窗仍然可见会把 backdrop 恢复回去。
                    error = validationError
                } else {
                    onConfirm(text)
                }
            },
            dismissText = dismissText,
            onDismiss = onDismiss
        )
    }
}
