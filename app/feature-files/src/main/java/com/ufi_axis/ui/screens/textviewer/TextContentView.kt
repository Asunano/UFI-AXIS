package com.ufi_axis.ui.screens.textviewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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
    val measurer = rememberTextMeasurer()
    val numberStyle = remember(palette.textSecondary) {
        UfiTextStyles.monoNote.copy(color = palette.textSecondary.copy(alpha = GUTTER_ALPHA))
    }

    // 行号**画在输入框自己的坐标系里**（2026-09-14）。
    //
    // 此前是一个独立的 gutter Canvas：高度钉死一屏，靠 `translate(top = -scrollPx)`
    // 平移坐标系来"假装"跟随滚动，而 `scrollPx` 是在 composition 阶段当参数传进去的。
    // `BasicTextField` 放在 `verticalScroll` 里属于嵌套滚动，垂直拖拽会被输入框自己消化，
    // 此时 `vScroll.value` 不变 ⇒ 那个窗口恒为 `[0, viewportPx]` ⇒ 行号永远停在首屏
    // （约 37 行 = 视口高 ÷ 行高，不是什么常量），再往下滚就"像被挡住一样"。
    //
    // 现在改成给输入框挂 `drawBehind`：
    // - 绘制发生在输入框的坐标空间，输入框随滚动容器整体位移，行号**自动**跟着走 ——
    //   不再需要把滚动值传来传去，也就不存在"传进来的值不更新"这种失效模式；
    // - 没有第二个组件、没有固定高度，绕开了 `:64-68` 记录的那次
    //   `Can't represent a width of 0 and height of N in Constraints` 崩溃
    //   （旧崩溃正是"gutter 高度 = 整篇文本高度"引起的）；
    // - 视口裁剪保留：`vScroll.value` 在 **draw lambda 内部**读取，
    //   State 的读取者变成绘制阶段，滚动只触发重绘、不触发重组，4000 行也不会每帧全画。
    //
    // 修饰符顺序有意义：`drawBehind` 在 `padding(start = gutterWidth)` **之前**，
    // 于是 draw 的坐标原点在留白的**外**沿，x=0 正好落在行号列上。
    val gutterDecoration = if (state.showLineNumbers) {
        Modifier
            .drawBehind {
                val result = layout ?: return@drawBehind
                val top = vScroll.value.toFloat()
                val bottom = top + max(viewportPx, 0)
                val lineStarts = state.lineStarts
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
            .padding(start = gutterWidth)
    } else {
        Modifier
    }

    // onSizeChanged 挂在 padding **之后**：viewportPx 必须等于内容区实际可视高度。
    // 挂在前面会把上下 padding 也算进去，裁剪窗口的 bottom 比实际高出一个 padding，
    // 底部一两行会画到可视区外被 clip 切掉。
    Row(
        modifier = modifier
            .padding(Spacing.EditorContentPadding)
            .onSizeChanged { viewportPx = it.height }
    ) {
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
                modifier = Modifier
                    .fillMaxSize()
                    .then(gutterDecoration),
                textStyle = style,
                cursorBrush = SolidColor(palette.accent),
                readOnly = !state.editable,
                onTextLayout = { layout = it }
            )
        }
    }
}


/**
 * 行号绘制说明（实现在 [EditableContent] 的 `drawBehind` 里）。
 *
 * 用逐行 `drawText` 而不是堆 N 个 `Text`：
 * - 逻辑行与视觉行**不是一对一**（软换行时一行会折成好几行），行号必须画在逻辑行的**首个**
 *   视觉行上，这需要 [TextLayoutResult] 的行几何，只有画的时候拿得到；
 * - 4000 行堆 4000 个 composable，即便都在屏外也要过一遍组合。
 *
 * 也不再用独立的 gutter 组件：行号必须与文本共享同一个坐标系，否则"跟随滚动"这件事
 * 就要靠把滚动值传进去，而那正是它此前只显示首屏的原因。
 */

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
