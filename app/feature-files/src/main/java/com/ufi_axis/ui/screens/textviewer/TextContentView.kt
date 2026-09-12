package com.ufi_axis.ui.screens.textviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlin.math.max

/**
 * 正文区 —— 只剩一条渲染路径：**可编辑**。
 *
 * ## 2026-09-11：删掉只读态
 * 旧实现是两个互不相干的 composable（`ReadOnlyContent` 逐行 `LazyColumn` /
 * `EditableContent` 单个 `BasicTextField`）。两态能力长期不对等（只读态有行号、
 * 有命中高亮、能跳转行；编辑态都没有），而"看大文件"这个只读态唯一不可替代的价值，
 * 现在由「整份下载到手机缓存后再编辑」覆盖，于是只读态整体删除。
 *
 * 保留**单个** [BasicTextField] 不是偷懒：跨行选择、输入法组合、光标都由它统一管理，
 * 拆成多个输入框会立刻丢掉这三件事。代价是整篇文本算一个 TextLayout，
 * 所以有 [TextViewerState.EDITABLE_MAX_BYTES] 这道闸门（超限要用户显式确认）。
 */
@Composable
fun TextContentView(
    state: TextViewerState,
    modifier: Modifier = Modifier
) {
    EditableContent(state = state, modifier = modifier)
}

// ─────────────────────────────────────────────────────────────────────────────
//  编辑态
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EditableContent(
    state: TextViewerState,
    modifier: Modifier
) {
    val palette = LocalResolvedPalette.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportPx by remember { mutableIntStateOf(0) }
    val gutterWidth = gutterWidthFor(state.lineCount)
    val style = UfiTextStyles.monoCodeBlock.copy(color = palette.textPrimary)

    // 仅文本区域滚动；行号列作「粘性」侧栏固定不动，高度恒等于可视区高度。
    // 旧实现把整行（含 gutter Canvas）放进 verticalScroll 的 Row，且 gutter 高度 =
    // 整篇文本高度（可达数十万 px）；在 AnimatedContent（页面切换）等受限约束下，
    // 该固定高度会成为非法 Constraints（minHeight > maxHeight）触发
    // `Can't represent a width of 0 and height of N in Constraints` 崩溃。
    Row(
        modifier = modifier
            .onSizeChanged { viewportPx = it.height }
            .padding(Spacing.EditorContentPadding)
    ) {
        if (state.showLineNumbers) {
            EditorGutter(
                layout = layout,
                lineStarts = state.lineStarts,
                width = gutterWidth,
                scrollPx = vScroll.value,
                viewportPx = viewportPx
            )
        }
        // 自动换行开着时输入框填满剩余宽度（由它自己折行）；关掉时套一层横向滚动，
        // 无限宽约束下 BasicTextField 按最长行自然宽度布局，长行不折。
        val fieldContainer = if (state.softWrap) {
            Modifier.verticalScroll(vScroll)
        } else {
            Modifier.verticalScroll(vScroll).horizontalScroll(hScroll)
        }
        Box(modifier = fieldContainer.weight(1f)) {
            BasicTextField(
                value = state.value,
                onValueChange = { state.onInput(it, System.currentTimeMillis()) },
                modifier = Modifier.fillMaxSize(),
                textStyle = style,
                cursorBrush = SolidColor(palette.accent),
                readOnly = !state.editable,
                onTextLayout = { layout = it }
            )
        }
    }
}

/**
 * 行号列（Canvas 逐行画）。
 *
 * 用 Canvas 逐行画而不是堆 N 个 `Text`：
 * - 逻辑行与视觉行**不是一对一**（软换行时一行会折成好几行），行号必须画在逻辑行的**首个**
 *   视觉行上，这需要 [TextLayoutResult] 的行几何，只有画的时候拿得到；
 * - 4000 行堆 4000 个 composable，即便都在屏外也要过一遍组合。
 *
 * 只画视口内的行：`scrollPx` 与 `viewportPx` 都是 State，滚动时本来就要重画，
 * 顺手把画的量从"整篇"压到"一屏"。
 */
@Composable
private fun EditorGutter(
    layout: TextLayoutResult?,
    lineStarts: IntArray,
    width: Dp,
    scrollPx: Int,
    viewportPx: Int
) {
    val palette = LocalResolvedPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = remember(palette.textSecondary) {
        UfiTextStyles.monoNote.copy(color = palette.textSecondary.copy(alpha = GUTTER_ALPHA))
    }
    // 关键修复：Canvas 高度恒等于可视区高度（viewportPx），不再等于整篇文本高度。
    // 绘制时按 scrollPx 上移坐标系，使绝对行号 Y 落入可视窗口 [0, viewportPx]，
    // 配合下方 top/bottom 裁剪只画视口内的行。
    val heightDp = if (viewportPx > 0) with(density) { viewportPx.toDp() } else 1.dp

    Canvas(
        modifier = Modifier
            .width(width)
            .height(heightDp)
            .padding(end = Spacing.EditorGutterGap)
    ) {
        val result = layout ?: return@Canvas
        translate(top = -scrollPx.toFloat()) {
            val top = scrollPx.toFloat()
            val bottom = top + max(viewportPx, 0)
            for (index in lineStarts.indices) {
                val visualLine = result.getLineForOffset(lineStarts[index])
                val y = result.getLineTop(visualLine)
                if (y + LINE_PROBE_SLACK < top) continue
                if (y - LINE_PROBE_SLACK > bottom) break
                drawText(
                    textMeasurer = measurer,
                    text = (index + 1).toString(),
                    topLeft = Offset(0f, y),
                    style = numberStyle
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  规格
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 行号列宽度 = 位数 × 字宽，再与下限取大。
 *
 * 旧实现固定 48sp：三位行号时右侧空一大片，五位以上又被挤掉。
 */
private fun gutterWidthFor(lineCount: Int): Dp {
    val digits = lineCount.toString().length
    val byDigits = Spacing.EditorGutterDigitWidth * digits + Spacing.EditorGutterGap
    return if (byDigits > Spacing.EditorGutterMinWidth) byDigits else Spacing.EditorGutterMinWidth
}

/** 视口裁剪的容差：半行高度，避免边界行忽隐忽现。 */
private const val LINE_PROBE_SLACK = 24f

private const val GUTTER_ALPHA = 0.55f
