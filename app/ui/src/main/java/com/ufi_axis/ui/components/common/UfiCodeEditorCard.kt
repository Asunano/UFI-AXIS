// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 等宽文本编辑器卡片：标题行（可带一个行内操作按钮，如"复制"）+ 多行等宽输入框 + 可选底部区。
 *
 * 用于编辑 TOML / YAML / 脚本等纯文本配置，取色与圆角统一走 [LocalResolvedPalette] +
 * [UfiCardDefaults]，业务页不要再自绘 Card + OutlinedTextField 组合。
 */
@Composable
fun UfiCodeEditorCard(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = 160.dp,
    maxHeight: Dp = 300.dp,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    footer: @Composable (ColumnScope.() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.InnerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary
                )
                if (actionText != null && onAction != null) {
                    UfiButton(variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small, text = actionText, onClick = onAction)
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight, max = maxHeight),
                enabled = enabled,
                textStyle = UfiTextStyles.monoNote,
                label = label?.let { { Text(it) } },
                singleLine = false,
                shape = UfiCardDefaults.inputShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.cardBorder,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    focusedLabelColor = palette.accent,
                    unfocusedLabelColor = palette.textSecondary,
                    cursorColor = palette.accent
                )
            )
            footer?.invoke(this)
        }
    }
}
