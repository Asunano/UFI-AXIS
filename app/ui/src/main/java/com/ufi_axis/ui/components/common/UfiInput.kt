// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

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
    // 密码字段默认就该走密码键盘：普通文本键盘会带候选词 / 自动纠错 / 首字母大写，
    // 对「只能输入 ASCII 的配对密码 / API Key」是纯干扰，也会让输入法组合串反复触发过滤提示。
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // v3（2026-08-11）：增加 textAlign 参数支持密码框文字居中（CONFIRM 阶段需要）
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start
) {
    var visible by remember { mutableStateOf(false) }
    val palette = LocalResolvedPalette.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // 空 label 必须是真 null，否则 placeholder 在未聚焦时被 M3 隐藏（详见 UfiTextField）
        label = if (label.isNotBlank()) ({ Text(label) }) else null,
        // placeholder 永远单行（理由见 UfiTextField 里同一处注释）
        placeholder = placeholder?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = androidx.compose.ui.text.TextStyle(textAlign = textAlign),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
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
            // ⚠️ label 为空时必须传**真 null**，不能传"什么都不画的 lambda"（2026-09-18 修）：
            // M3 判定"这个字段有浮动 label"看的是 `label != null`，而不是 label 里有没有内容。
            // 传非空 lambda 时 `showExpandedLabel = true`，于是
            // `placeholderOpacity(UnfocusedEmpty) = 0f` —— 表现就是"框空着看不到提示，
            // 一聚焦提示才冒出来"。项目里标签一律由 UfiDialogField 画在框外（label = ""），
            // 所以这条影响所有弹窗输入框。
            label = if (label.isNotBlank()) ({ Text(label) }) else null,
            // placeholder 永远单行：它是提示而不是正文，换行会把输入框顶高、
            // 在窄容器（如搜索行里 weight(1f) 的输入框）里还会把整行布局撑乱（2026-09-19）。
            placeholder = placeholder?.let {
                { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
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
        // 同上：空 label 必须是真 null，否则 placeholder 在未聚焦时被 M3 隐藏
        label = if (label.isNotBlank()) ({ Text(label) }) else null,
        // placeholder 永远单行（理由见 UfiTextField 里同一处注释）
        placeholder = placeholder?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
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
        // 空 label 必须是真 null，否则 placeholder 在未聚焦时被 M3 隐藏（详见 UfiTextField）
        label = if (label.isNotBlank()) ({ Text(label) }) else null,
        // placeholder 永远单行（理由见 UfiTextField 里同一处注释）
        placeholder = placeholder?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
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
 * 紧凑输入框族的共享视觉常量（2026-10-14）。
 *
 * 与 [UfiDropdown] 的触发器**逐项对齐**：同底色、同描边、同圆角、同内距、同最小高度 ——
 * 两者并排出现在一行里（"输入框 + 单位下拉框"）时才会被读成一套控件。
 */
private val UfiCompactFieldMinHeight = 32.dp

/** 紧凑输入框的描边透明度，与 [UfiDropdown] 触发器的边框同值。 */
private const val UfiCompactFieldBorderAlpha = 0.6f

/** 不可用时的整体透明度，与 [UfiDropdown] / [UfiSwitch] 同值。 */
private const val UfiCompactFieldDisabledAlpha = 0.4f

/**
 * 紧凑输入框（2026-10-14）：与 [UfiDropdown] 的触发器同高同款的自绘输入框。
 *
 * ## 为什么另起一个，而不是把 [UfiTextField] 改小
 * [UfiTextField] / [UfiDigitField] 是 M3 `OutlinedTextField`：最小高度 56dp、带浮动 label、
 * 聚焦时 label 上浮。那是"表单页里一个大输入框"的形态，全库一百多个调用点都在用 ——
 * 把它改小等于把整个 App 的表单观感换掉，不该混在一次细节修改里。
 *
 * 这里解决的是另一种场景：**设置行里"输入框 + 单位下拉框"并排**。下拉触发器只有 32dp，
 * 旁边杵一个 56dp 的 M3 输入框就是两代设计（用户："当前输入框太大了"）。
 *
 * ## 跟随字体大小
 * 高度用 `heightIn(min = …)` 而不是 `height(…)`：字体调大时输入框跟着长高、不会把字裁掉。
 * 下拉触发器 2026-10-14 起同样改成最小高度，两者会一起长（这正是用户要的"跟随字体大小"）。
 */
@Composable
fun UfiCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = Int.MAX_VALUE,
    enabled: Boolean = true
) {
    UfiCompactField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        modifier = modifier,
        placeholder = placeholder,
        keyboardType = KeyboardType.Text,
        enabled = enabled
    )
}

/** 见 [UfiCompactTextField]：数字键盘，且只收数字。 */
@Composable
fun UfiCompactDigitField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = Int.MAX_VALUE,
    enabled: Boolean = true
) {
    UfiCompactField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(maxLength)) },
        modifier = modifier,
        placeholder = placeholder,
        keyboardType = KeyboardType.Number,
        enabled = enabled
    )
}

/**
 * 见 [UfiCompactTextField]：密码形态（圆点遮蔽 + 右侧眼睛切换）。
 *
 * 与 [UfiPasswordField]（M3 56dp）的区别只有高度：弹窗里与下拉框并排时，
 * 56dp 的输入框比 32dp 的下拉高出一大截（用户："输入框上下太宽，改扁一点"）。
 */
@Composable
fun UfiCompactPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    UfiCompactField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        keyboardType = KeyboardType.Password,
        enabled = enabled,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            IconButton(
                onClick = { visible = !visible },
                modifier = Modifier.size(Spacing.IconSizeSmall + 8.dp)
            ) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                    tint = LocalResolvedPalette.current.textSecondary,
                    modifier = Modifier.size(Spacing.IconSizeSmall)
                )
            }
        }
    )
}

@Composable
private fun UfiCompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.shape
    Row(
        modifier = modifier
            .heightIn(min = UfiCompactFieldMinHeight)
            .alpha(if (enabled) 1f else UfiCompactFieldDisabledAlpha)
            .clip(shape)
            .background(palette.surfaceMuted)
            .border(1.dp, palette.textSecondary.copy(alpha = UfiCompactFieldBorderAlpha), shape)
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // 占位符画在输入框**下层**（BasicTextField 自身透明）：有内容时被文字盖住，
            // 也不会像 M3 那样在聚焦时把 label 顶上去 —— 紧凑形态里没有"浮动"这件事。
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = UfiTextStyles.bodyLead,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = UfiTextStyles.bodyLead.copy(color = palette.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        trailing?.invoke()
    }
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
