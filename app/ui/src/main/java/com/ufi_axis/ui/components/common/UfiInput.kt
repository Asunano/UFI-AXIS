// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

/** 输入框族的共享视觉常量。 */
object UfiInputDefaults {
    /**
     * 聚焦时容器底色染主色的透明度。
     *
     * 2026-08-30：替换掉原来的"聚焦整体放大 1.015×"动画 —— 放大会让输入框在页面里
     * 凸起一块、把相邻控件挤动一下，多个字段连续切换焦点时整列都在轻微跳动。
     * 改成只染一层极淡主色：M3 的 `OutlinedTextField` 本身会对 container/border/label
     * 颜色做 150ms 交叉淡入，所以焦点变化是一次柔和的"点亮"，布局尺寸完全不动。
     */
    const val FocusedTintAlpha = 0.05f
}

/**
 * 输入框族的统一配色 —— [UfiTextField] / [UfiPasswordField] / [UfiDigitField] / [UfiSearchBar] 共用。
 *
 * 2026-09-03 从 4 份几乎相同的 `OutlinedTextFieldDefaults.colors(...)` 收敛而来。收敛动机不只是去重：
 * 原先 4 份**都只设了 focused/unfocused，漏了整套 `error*`**，于是错误态的文字/光标/标签/边框全部
 * 落回 M3 兜底（`colorScheme.onSurface` 等）。当前之所以看起来正常，只是因为 `Theme.kt` 恰好把
 * `onSurface` 映射成了 `palette.textPrimary` —— 一旦换配色方案或在未套 MaterialTheme 的组合里预览，
 * 错误态就会串色（典型表现：深色模式下错误态输入的文字仍是黑的）。
 *
 * 现在所有状态都显式取自 palette，不再依赖 M3 兜底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ufiInputColors(): TextFieldColors {
    val palette = LocalResolvedPalette.current
    return OutlinedTextFieldDefaults.colors(
        // ── 边框 ──
        focusedBorderColor = palette.inputBorderFocused,
        unfocusedBorderColor = palette.inputBorder,
        errorBorderColor = palette.error,
        // ── 容器底色：聚焦时轻微染主色（M3 自带 150ms 交叉淡入），其余透明 ──
        focusedContainerColor = palette.accent.copy(alpha = UfiInputDefaults.FocusedTintAlpha),
        unfocusedContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        // ── 光标 ──
        cursorColor = palette.accent,
        errorCursorColor = palette.error,
        // ── 正文文字：错误态**也**用 textPrimary，错误信息由 supportingText 承担，不靠把输入文字染红 ──
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        errorTextColor = palette.textPrimary,
        disabledTextColor = palette.textSecondary.copy(alpha = 0.5f),
        // ── 浮动标签 ──
        focusedLabelColor = palette.accent,
        unfocusedLabelColor = palette.textSecondary,
        errorLabelColor = palette.error,
        disabledLabelColor = palette.textSecondary.copy(alpha = 0.5f),
        // ── placeholder ──
        focusedPlaceholderColor = palette.textSecondary.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = palette.textSecondary.copy(alpha = 0.6f),
        errorPlaceholderColor = palette.textSecondary.copy(alpha = 0.6f),
        // ── 图标 ──
        focusedLeadingIconColor = palette.iconTint,
        unfocusedLeadingIconColor = palette.iconTint.copy(alpha = 0.6f),
        errorLeadingIconColor = palette.iconTint.copy(alpha = 0.6f),
        focusedTrailingIconColor = palette.iconTint,
        unfocusedTrailingIconColor = palette.iconTint.copy(alpha = 0.6f),
        errorTrailingIconColor = palette.error,
        // ── 辅助说明（错误信息）──
        focusedSupportingTextColor = palette.textSecondary,
        unfocusedSupportingTextColor = palette.textSecondary,
        errorSupportingTextColor = palette.error
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    showToggle: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    // v3（2026-08-11）：增加 textAlign 参数支持密码框文字居中（CONFIRM 阶段需要）
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start
) {
    var visible by remember { mutableStateOf(false) }
    val palette = LocalResolvedPalette.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { if (label.isNotBlank()) Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = androidx.compose.ui.text.TextStyle(textAlign = textAlign),
        keyboardOptions = keyboardOptions,
        supportingText = errorMessage?.let { { Text(it, color = palette.error) } },
        colors = ufiInputColors(),
        trailingIcon = if (showToggle) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码"
                    )
                }
            }
        } else null,
        shape = UfiCardDefaults.inputShape
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    /**
     * 2026-08-31 新增：默认仍然撑满宽度（保持所有既有调用方不变）。
     * 传 false 时宽度完全交给 [modifier]，供「行内窄字段」使用
     * （如下载设置里 `Modifier.width(80.dp)` 的做种率、配对页 90dp 的数量输入）。
     */
    fillMaxWidth: Boolean = true,
    /**
     * 2026-09-11 新增：**光标/选区位置**（默认 null = 不关心，与既有调用点行为完全一致）。
     *
     * 为什么需要它：「点一下把 `{{title}}` 插到光标处」这类功能必须知道插入点在哪，
     * 而 `value: String` 里没有这个信息。传了 [onSelectionChange] 之后本组件改用 M3 的
     * `TextFieldValue` 重载，选区**由调用方持有**（受控），插入后调用方把光标挪到插入内容之后 ——
     * 组件内部再存一份 `TextFieldValue` 就会与外部 `value` 打架，表现是"在中间打字光标跳到末尾"。
     *
     * 越界的选区在这里夹到 `0..value.length`：调用方先改文本再改选区时会有一瞬的不一致，
     * 而越界选区会让 `TextFieldValue` 在测量阶段抛。
     */
    selection: TextRange? = null,
    /** 见 [selection]。null = 走原来的 `String` 重载（既有调用点全部落在这一支）。 */
    onSelectionChange: ((TextRange) -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val supporting: (@Composable () -> Unit)? = if (errorMessage != null) {
        { Text(errorMessage, color = palette.error) }
    } else supportingText
    val fieldModifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier
    // 两支各自调一次 OutlinedTextField：M3 的 String 与 TextFieldValue 是两个独立重载，
    // 没有共用入口。既有调用点必须留在 String 那一支 —— 走 TextFieldValue 而外部只回传
    // String 的话，光标位置每次重组都被重置成末尾。
    if (onSelectionChange == null) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { if (label.isNotBlank()) Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = isError,
            enabled = enabled,
            modifier = fieldModifier,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            supportingText = supporting,
            colors = ufiInputColors(),
            shape = UfiCardDefaults.inputShape
        )
        return
    }
    val caret = selection ?: TextRange(value.length)
    val safeCaret = TextRange(
        caret.start.coerceIn(0, value.length),
        caret.end.coerceIn(0, value.length)
    )
    OutlinedTextField(
        value = TextFieldValue(text = value, selection = safeCaret),
        onValueChange = { next ->
            // 文本没变就别回调：选区移动（点一下、拖选）不该被记成一次编辑。
            if (next.text != value) onValueChange(next.text)
            onSelectionChange(next.selection)
        },
        label = { if (label.isNotBlank()) Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        enabled = enabled,
        modifier = fieldModifier,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        leadingIcon = leadingIcon,
        supportingText = supporting,
        colors = ufiInputColors(),
        shape = UfiCardDefaults.inputShape
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiDigitField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLength: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    // 2026-08-31 新增（均带默认值，既有调用方不受影响）：
    // 行内窄字段用 fillMaxWidth = false；无 label 的紧凑输入用 placeholder；单位后缀用 trailingIcon。
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    fillMaxWidth: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(maxLength)) },
        label = { if (label.isNotBlank()) Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = errorMessage?.let { { Text(it, color = palette.error) } },
        colors = ufiInputColors(),
        shape = UfiCardDefaults.inputShape
    )
}

/**
 * 一行并排放多个输入字段的容器（各字段用 `Modifier.weight()` 分配宽度）。
 *
 * 与 [UfiDialogField]（label + 单个字段，纵向）不是同一件事，别混：
 * 这里管的是「同一行里多个字段的横向排布与间距」，如 SMTP 弹窗的「服务器 2f + 端口 1f」。
 */
@Composable
fun UfiFieldRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        content = content
    )
}

/**
 * 搜索输入框：左侧放大镜、右侧「有内容时才出现」的清除按钮。
 *
 * 2026-08-31：批 1 曾按"零调用"删掉，复核后恢复 —— 各页面的搜索框目前都是用
 * [UfiTextField] 自己拼 leadingIcon/trailingIcon，这里是该收敛的目标而不是该删的死代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "搜索...",
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = ufiInputColors(),
        shape = UfiCardDefaults.inputShape,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = palette.iconTint.copy(alpha = 0.6f)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = palette.iconTint.copy(alpha = 0.6f)
                    )
                }
            }
        }
    )
}
