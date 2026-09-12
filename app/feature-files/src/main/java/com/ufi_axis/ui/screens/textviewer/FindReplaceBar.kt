package com.ufi_axis.ui.screens.textviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 查找 / 替换**浮层**（2026-09-11 从"嵌入布局"改为浮层）。
 *
 * ## 为什么必须是浮层
 * 旧版把它塞进页面的 `Column` 流里，于是「打开查找」会把正文整体往下推一格、
 * 「展开替换行」再推一次，关掉又弹回来 —— 用户正在看的那一行会跑掉。
 * 现在它由调用方用 `Modifier.align(TopCenter)` 覆盖在正文之上，**不参与正文的布局**，
 * 开关与展收都只影响自己。
 *
 * 仍然不用弹窗（`UfiInputDialog`）：查找是"边看边跳"的动作，输入关键字后必须能立刻
 * 看到命中在哪、连续按上下键翻，弹窗会挡住正文，这些都做不到。
 *
 * 替换行只在可编辑时展开 —— 闸门没打开（超大文件未确认）时不该出现点了没用的替换按钮。
 */
@Composable
fun FindReplaceBar(
    state: TextViewerState,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    // 浮层出现时把光标聚焦到搜索框：打开查找即可直接打字，不必先点一下输入框。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val total = state.matches.size
    val counter = when {
        state.query.isEmpty() -> ""
        total == 0 -> "无匹配"
        total >= MAX_MATCHES -> "${state.matchIndex + 1}/$MAX_MATCHES+"
        else -> "${state.matchIndex + 1}/$total"
    }

    Box(
        // 公共浮层阴影：统一灰影（cardShadowColor，#9CA4AC@30%）+ Level 3（10dp），
        // 与弹窗 / Bottom Sheet 同一套，不再用 Surface 的黑色 spot 阴影。
        modifier = modifier
            .ufiCardShadow(elevation = UfiCardDefaults.elevationLevel3Dp, shape = UfiCardDefaults.dialogShape)
            .clip(UfiCardDefaults.dialogShape)
            .background(palette.cardBg, UfiCardDefaults.dialogShape)
            .border(UfiCardDefaults.hairlineBorderWidth, palette.dialogBorder, UfiCardDefaults.dialogShape)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                SearchField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = "查找",
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                // 计数占固定最小宽度并右对齐：数字从 9/12 变成 10/12 时按钮不会左右跳
                Text(
                    text = counter,
                    style = UfiTextStyles.noteCompact,
                    color = if (total == 0 && state.query.isNotEmpty()) palette.warning else palette.textSecondary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = COUNTER_MIN_WIDTH)
                )
                CaseToggle(
                    active = state.caseSensitive,
                    onClick = { state.caseSensitive = !state.caseSensitive }
                )
                MiniIconButton(Icons.Default.KeyboardArrowUp, "上一个", enabled = total > 0, onClick = onPrev)
                MiniIconButton(Icons.Default.KeyboardArrowDown, "下一个", enabled = total > 0, onClick = onNext)
                if (state.editable) {
                    MiniIconButton(
                        Icons.Default.FindReplace,
                        "替换",
                        active = state.replaceExpanded,
                        onClick = { state.replaceExpanded = !state.replaceExpanded }
                    )
                }
                MiniIconButton(Icons.Default.Close, "关闭查找", onClick = onClose)
            }
            if (state.replaceExpanded && state.editable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    SearchField(
                        value = state.replacement,
                        onValueChange = { state.replacement = it },
                        placeholder = "替换为",
                        modifier = Modifier.weight(1f)
                    )
                    UfiButton(
                        text = "替换",
                        size = UfiButtonSize.Small,
                        variant = UfiButtonVariant.Subtle,
                        enabled = total > 0,
                        onClick = onReplaceCurrent
                    )
                    UfiButton(
                        text = "全部",
                        size = UfiButtonSize.Small,
                        enabled = total > 0,
                        onClick = onReplaceAll
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  局部件
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 紧凑查找输入框。
 *
 * 形状与全站输入框同一套：用 [UfiCardDefaults.inputShape]（10dp 圆角）+ `palette.inputBorder`
 * 一道 1dp 描边，背景透明（仅描边、无填充），与 [com.ufi_axis.ui.components.common.UfiTextField]
 * 视觉一致（其 unfocusedContainerColor 也是透明）——而不是另起一套带浅底填充的胶囊。这样浮层里的
 * 输入框和页面其它输入框是同一个语言，也不会在查找栏卡片里凭空多出一块浅色底。聚焦态由 BasicTextField 自带光标（accent）表达；整页只有这一个输入框，不需要
 * 像 UfiTextField 那样再染一层聚焦底色。
 *
 * [focusRequester] 由调用方传入：浮层出现时把光标落到这里（见 [FindReplaceBar]）。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier
            .height(FIELD_HEIGHT)
            .clip(UfiCardDefaults.inputShape)
            .background(Color.Transparent, UfiCardDefaults.inputShape)
            .border(UfiCardDefaults.hairlineBorderWidth, palette.inputBorder, UfiCardDefaults.inputShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = UfiTextStyles.note.copy(color = palette.textPrimary),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            )
        }
    }
}

/** 34dp 的小图标键。工具栏那种 48dp 圆钮在浮层里太占地方。 */
@Composable
private fun MiniIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    val tint = when {
        !enabled -> palette.textSecondary.copy(alpha = 0.35f)
        active -> palette.accent
        else -> palette.textSecondary
    }
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(UfiCardDefaults.chipShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * 「区分大小写」开关。
 *
 * 用文字 `Aa` 而不是图标：`Icons.Default.TextFields` 是一根竖线加个 T，
 * 与"区分大小写"的关联要靠猜；`Aa` 在编辑器语境里是通用写法，也省一个图标依赖。
 */
@Composable
private fun CaseToggle(active: Boolean, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(UfiCardDefaults.chipShape)
            .background(if (active) palette.chipSelectedBg else palette.cardBg, UfiCardDefaults.chipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Aa",
            style = UfiTextStyles.captionEmphasis,
            color = if (active) palette.accent else palette.textSecondary
        )
    }
}

private val FIELD_HEIGHT = 38.dp
private val BUTTON_SIZE = 34.dp

/** 计数区最小宽度：容得下「500+/500+」这类最长文案，避免按钮随数字位数左右跳。 */
private val COUNTER_MIN_WIDTH = 52.dp

