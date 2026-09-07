// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 自定义双滑块 RangeSlider，完全自绘轨道 / 刻度 / thumb，避免 Material3 RangeSlider
 * 在不同 ROM / Compose 版本下 thumb 样式异常（竖线、点击消失等）。
 *
 * ## 视觉（2026-08-30 对齐 UFITOOLS-Widget 的 `ThemeSlider`）
 * 换掉了原先的「24dp 白色圆盘 + 3dp 彩色描边 + 中心圆点」——那个 thumb 又大又空，
 * 三层同心圆压在 6dp 细轨道上头重脚轻。现在与另一端产品同一套画法：
 * - 轨道：6dp 圆角条，底色 = accent @15%（不再用 divider —— 轨道要和 thumb 同色系）
 * - 激活段：两端角色色的水平渐变，恒等于两 thumb 之间那一段
 * - Thumb：实心角色色圆 + 内嵌 2dp 白环（小而实，拖动时放大到 1.2 倍）
 * - 刻度：轨道下方一排小圆点；标签只在拖动时淡入，松手淡出，平时不占视觉噪声
 *
 * ## 交互
 * 单一 pointerInput 作用域统一处理「按下命中最近 thumb / 按下轨道跳转 / 拖动」，
 * 避免父级 detectTapGestures 与子级 detectHorizontalDragGestures 互相吞掉事件。
 *
 * 动效：非拖动（初始 / 编程变更）时位置用 spring 平滑过渡；拖动时瞬时跟随（snap）保证跟手。
 *
 * @param values 当前选中的区间（start 为左端点=小值，endInclusive 为右端点=大值）
 * @param valueRange 整个 slider 的数值范围
 * @param steps 把 valueRange 切分的段数；0 表示连续拖动
 * @param startThumbColor 左端 thumb（=values.start，小值）颜色；缺省 accent
 * @param endThumbColor 右端 thumb（=values.endInclusive，大值）颜色；缺省 accent
 * @param tickStep 刻度步长（值域单位）；<=0 不画刻度。实际间隔取它的整数倍，使刻度数≈6
 * @param tickLabelFormatter 刻度标签格式化；null 只画刻度点不写字
 */
@Composable
fun UfiRangeSlider(
    values: ClosedFloatingPointRange<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
    onValuesChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    trackHeight: Dp = 6.dp,
    thumbSize: Dp = 16.dp,
    startThumbColor: Color? = null,
    endThumbColor: Color? = null,
    tickStep: Float = 0f,
    tickLabelFormatter: ((Float) -> String)? = null
) {
    val palette = LocalResolvedPalette.current
    val startColor = startThumbColor ?: palette.accent
    val endColor = endThumbColor ?: palette.accent

    val ticks = rememberSliderTicks(valueRange, tickStep, tickLabelFormatter)
    val metrics = rememberSliderMetrics(ticks, trackHeight, thumbSize)

    val valuesState = rememberUpdatedState(values)
    val onValuesChangeState = rememberUpdatedState(onValuesChange)
    // 当前正在拖动的 thumb：true=左端(start)，false=右端(end)，null=无
    var activeThumb by remember { mutableStateOf<Boolean?>(null) }
    val dragging = activeThumb != null

    val dispStart by animateSliderFraction(fractionOf(values.start, valueRange), dragging, "startFraction")
    val dispEnd by animateSliderFraction(fractionOf(values.endInclusive, valueRange), dragging, "endFraction")
    val thumbScale by animateThumbScale(dragging)
    val labelAlpha by animateTickLabelAlpha(dragging)

    fun applyValue(draggingStart: Boolean, raw: Float) {
        val cur = valuesState.value
        val snapped = snapToStep(raw, valueRange, steps)
        if (draggingStart) {
            val newStart = snapped.coerceIn(valueRange.start, cur.endInclusive)
            if (newStart != cur.start) onValuesChangeState.value(newStart..cur.endInclusive)
        } else {
            val newEnd = snapped.coerceIn(cur.start, valueRange.endInclusive)
            if (newEnd != cur.endInclusive) onValuesChangeState.value(cur.start..newEnd)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.totalHeight)
            .pointerInput(valueRange, steps) {
                val trackStartPx = metrics.padPx
                val trackLenPx = (size.width - metrics.padPx * 2).coerceAtLeast(1f)
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val cur = valuesState.value
                        val startX = trackStartPx + trackLenPx * fractionOf(cur.start, valueRange)
                        val endX = trackStartPx + trackLenPx * fractionOf(cur.endInclusive, valueRange)
                        // 命中最近的 thumb（按下即开始拖动该 thumb）
                        val draggingStart = abs(down.position.x - startX) <= abs(down.position.x - endX)
                        activeThumb = draggingStart
                        down.consume()
                        // 轨道任意点都可直接拉：按下先跳过去
                        applyValue(draggingStart, valueAt(down.position.x, trackStartPx, trackLenPx, valueRange))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if (change.changedToUp()) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                            applyValue(draggingStart, valueAt(change.position.x, trackStartPx, trackLenPx, valueRange))
                            event.changes.forEach { it.consume() }
                        }
                        activeThumb = null
                    }
                }
            }
    ) {
        drawUfiSlider(
            metrics = metrics,
            ticks = ticks,
            valueRange = valueRange,
            labelAlpha = labelAlpha,
            // 着色区恒等于两滑块之间：这就是用户选的那段，没有第二种解读
            activeFrom = minOf(dispStart, dispEnd),
            activeTo = maxOf(dispStart, dispEnd),
            activeBrush = { from, to ->
                Brush.horizontalGradient(0f to startColor, 1f to endColor, startX = from, endX = to)
            },
            thumbs = listOf(dispStart to startColor, dispEnd to endColor),
            thumbScale = thumbScale,
            trackColor = palette.accent.copy(alpha = 0.15f),
            tickColor = palette.textSecondary.copy(alpha = 0.45f),
            labelColor = palette.textSecondary,
            thumbRingColor = palette.onAccent
        )
    }
}

/**
 * 单滑块版本：与 [UfiRangeSlider] 共用同一套画法与刻度逻辑，只有一个 thumb，
 * 激活段从轨道左端画到 thumb。
 *
 * 用于「一个数值 + 一排刻度」的场合（如免打扰起止小时）。
 * 2026-09-04（P4e 滑块收敛）：[UfiSlider] 原先包的是 Material3 `Slider`（thumb 样式随 Compose
 * 版本变、轨道内 tick 色不走 palette），现已改为「标签行 + 本组件」，全站单值滑块只剩这一套画法。
 *
 * @param value 当前值
 * @param valueRange 值域
 * @param steps 把 valueRange 切分的**段数**（不是 M3 `Slider` 那种"中间停靠点数"）；0 表示连续拖动。
 *   吸附步长 = `(max-min)/steps`。[UfiSlider] 对外是 M3 语义，会在转调本组件时 +1 换算。
 * @param thumbColor thumb 与激活段颜色；缺省 accent
 * @param tickStep 刻度步长（值域单位）；<=0 不画刻度
 * @param tickLabelFormatter 刻度标签格式化；null 只画刻度点不写字
 * @param enabled false 时不接手势、轨道与 thumb 一并淡化（透明度沿用原 [UfiSlider] 的
 *   disabled 口径：thumb 40%、轨道底 8%）
 * @param onValueChangeFinished 抬手时回调一次。用于「拖动中只改本地草稿、松手才提交」的调用点
 *   （如监控设置的保留天数），避免拖动过程里每帧都写库。
 * @param minTrackRowHeight 轨道行的最小高度。手势区 = 整个 Canvas，无刻度时轨道行就是全部高度
 *   （默认只有 24dp，低于可点区最小尺寸），[UfiSlider] 因此传 44dp。缺省 0dp = 按内容算。
 */
@Composable
fun UfiValueSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    trackHeight: Dp = 6.dp,
    thumbSize: Dp = 16.dp,
    thumbColor: Color? = null,
    tickStep: Float = 0f,
    tickLabelFormatter: ((Float) -> String)? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    minTrackRowHeight: Dp = 0.dp
) {
    val palette = LocalResolvedPalette.current
    val baseColor = thumbColor ?: palette.accent
    val color = if (enabled) baseColor else baseColor.copy(alpha = 0.4f)

    val ticks = rememberSliderTicks(valueRange, tickStep, tickLabelFormatter)
    val metrics = rememberSliderMetrics(ticks, trackHeight, thumbSize, minTrackRowHeight)

    val valueState = rememberUpdatedState(value)
    val onValueChangeState = rememberUpdatedState(onValueChange)
    val onFinishedState = rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }

    val dispValue by animateSliderFraction(fractionOf(value, valueRange), dragging, "valueFraction")
    val thumbScale by animateThumbScale(dragging)
    val labelAlpha by animateTickLabelAlpha(dragging)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.totalHeight)
            .pointerInput(valueRange, steps, enabled) {
                if (!enabled) return@pointerInput
                val trackStartPx = metrics.padPx
                val trackLenPx = (size.width - metrics.padPx * 2).coerceAtLeast(1f)
                fun emit(x: Float) {
                    val next = snapToStep(valueAt(x, trackStartPx, trackLenPx, valueRange), valueRange, steps)
                    if (next != valueState.value) onValueChangeState.value(next)
                }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        down.consume()
                        emit(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if (change.changedToUp()) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                            emit(change.position.x)
                            event.changes.forEach { it.consume() }
                        }
                        dragging = false
                        onFinishedState.value?.invoke()
                    }
                }
            }
    ) {
        drawUfiSlider(
            metrics = metrics,
            ticks = ticks,
            valueRange = valueRange,
            labelAlpha = labelAlpha,
            activeFrom = 0f,
            activeTo = dispValue,
            activeBrush = { _, _ -> Brush.horizontalGradient(0f to color, 1f to color) },
            thumbs = listOf(dispValue to color),
            thumbScale = thumbScale,
            trackColor = palette.accent.copy(alpha = if (enabled) 0.15f else 0.08f),
            tickColor = palette.textSecondary.copy(alpha = 0.45f),
            labelColor = palette.textSecondary,
            thumbRingColor = palette.onAccent
        )
    }
}

// ══════════════════════════ 内部共用：刻度 / 尺寸 / 绘制 ══════════════════════════

/** 刻度值 + 预排版好的标签（两者索引一一对应）。 */
private class SliderTicks(
    val values: List<Float>,
    val labels: List<TextLayoutResult>?
)

/** 一次算好的像素尺寸：手势换算与绘制必须用同一份，否则按下位置和画出来的 thumb 会错开。 */
private class SliderMetrics(
    val totalHeight: Dp,
    val padPx: Float,
    val trackHeightPx: Float,
    val trackCenterYPx: Float,
    val thumbRadiusPx: Float,
    val tickRadiusPx: Float,
    val ringWidthPx: Float,
    val tickGapPx: Float,
    val labelGapPx: Float
)

@Composable
private fun rememberSliderTicks(
    valueRange: ClosedFloatingPointRange<Float>,
    tickStep: Float,
    tickLabelFormatter: ((Float) -> String)?
): SliderTicks {
    val labelStyle = UfiTextStyles.captionTiny
    val measurer = rememberTextMeasurer()
    val values = remember(valueRange.start, valueRange.endInclusive, tickStep) {
        computeTickValues(valueRange, tickStep)
    }
    val labels = remember(values, tickLabelFormatter, labelStyle) {
        tickLabelFormatter?.let { format ->
            values.map { measurer.measure(AnnotatedString(format(it)), labelStyle) }
        }
    }
    return remember(values, labels) { SliderTicks(values, labels) }
}

@Composable
private fun rememberSliderMetrics(
    ticks: SliderTicks,
    trackHeight: Dp,
    thumbSize: Dp,
    minTrackRowHeight: Dp = 0.dp
): SliderMetrics {
    val density = LocalDensity.current
    return remember(ticks, trackHeight, thumbSize, minTrackRowHeight, density) {
        buildSliderMetrics(density, ticks, trackHeight, thumbSize, minTrackRowHeight)
    }
}

private fun buildSliderMetrics(
    density: Density,
    ticks: SliderTicks,
    trackHeight: Dp,
    thumbSize: Dp,
    minTrackRowHeight: Dp
): SliderMetrics = with(density) {
    // 左右留白必须同时容纳 thumb 半径与最宽刻度标签的一半，否则首尾标签会被裁掉
    val widestLabel = ticks.labels?.maxOfOrNull { it.size.width } ?: 0
    val sidePadding = maxOf(thumbSize * 0.7f, (widestLabel / 2).toDp())
    // minTrackRowHeight：手势区 = 整个 Canvas，无刻度时轨道行就是全部高度。不给下限的话
    // 单值滑块只有 24dp 高，低于可点区最小尺寸；[UfiSlider] 因此传 44dp 撑起触摸区，
    // 轨道仍按 trackRowHeight/2 垂直居中。缺省 0dp = 保持 UfiRangeSlider 原有尺寸不变。
    val trackRowHeight = maxOf(thumbSize * 1.5f, trackHeight + 16.dp, minTrackRowHeight)
    val tickRowHeight = if (ticks.values.isEmpty()) 0.dp else 10.dp
    val labelRowHeight = if (ticks.labels == null) 0.dp else 16.dp
    SliderMetrics(
        totalHeight = trackRowHeight + tickRowHeight + labelRowHeight,
        padPx = sidePadding.toPx(),
        trackHeightPx = trackHeight.toPx(),
        trackCenterYPx = trackRowHeight.toPx() / 2f,
        thumbRadiusPx = thumbSize.toPx() / 2f,
        tickRadiusPx = 2.5.dp.toPx(),
        ringWidthPx = 2.dp.toPx(),
        tickGapPx = 4.dp.toPx(),
        labelGapPx = 2.dp.toPx()
    )
}

/**
 * 画轨道 → 激活段 → 刻度点 → 刻度标签 → thumb（顺序即层级，thumb 必须压在最上面）。
 *
 * @param activeFrom / [activeTo] 激活段的起止 fraction（0..1）
 * @param activeBrush 由激活段的像素起止 x 生成画刷（渐变需要绝对坐标）
 * @param thumbs 每个 thumb 的 (fraction, 颜色)
 * @param thumbRingColor thumb 内圈环的颜色。环画在 thumb 实底**之内**
 *   （半径 `r - ringWidth/2`、线宽 `ringWidth`，即覆盖 `r-ringWidth..r`），
 *   所以它的底色永远是 thumb 自己的颜色（缺省 = `palette.accent`），故传 `palette.onAccent`。
 */
private fun DrawScope.drawUfiSlider(
    metrics: SliderMetrics,
    ticks: SliderTicks,
    valueRange: ClosedFloatingPointRange<Float>,
    labelAlpha: Float,
    activeFrom: Float,
    activeTo: Float,
    activeBrush: (Float, Float) -> Brush,
    thumbs: List<Pair<Float, Color>>,
    thumbScale: Float,
    trackColor: Color,
    tickColor: Color,
    labelColor: Color,
    thumbRingColor: Color
) {
    val trackLeft = metrics.padPx
    val trackLen = (size.width - metrics.padPx * 2).coerceAtLeast(1f)
    val trackTop = metrics.trackCenterYPx - metrics.trackHeightPx / 2f
    val corner = CornerRadius(metrics.trackHeightPx / 2f)
    fun xOf(fraction: Float) = trackLeft + trackLen * fraction

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(trackLeft, trackTop),
        size = Size(trackLen, metrics.trackHeightPx),
        cornerRadius = corner
    )

    val fromX = xOf(activeFrom)
    val toX = xOf(activeTo)
    if (toX > fromX) {
        drawRoundRect(
            brush = activeBrush(fromX, toX),
            topLeft = Offset(fromX, trackTop),
            size = Size(toX - fromX, metrics.trackHeightPx),
            cornerRadius = corner
        )
    }

    val tickY = trackTop + metrics.trackHeightPx + metrics.tickRadiusPx + metrics.tickGapPx
    ticks.values.forEach { v ->
        drawCircle(
            color = tickColor,
            radius = metrics.tickRadiusPx,
            center = Offset(xOf(fractionOf(v, valueRange)), tickY)
        )
    }

    val labels = ticks.labels
    if (labels != null && labelAlpha > 0.01f) {
        val labelTop = tickY + metrics.tickRadiusPx + metrics.labelGapPx
        ticks.values.forEachIndexed { i, v ->
            val layout = labels[i]
            drawText(
                textLayoutResult = layout,
                color = labelColor,
                alpha = labelAlpha,
                topLeft = Offset(xOf(fractionOf(v, valueRange)) - layout.size.width / 2f, labelTop)
            )
        }
    }

    val r = metrics.thumbRadiusPx * thumbScale
    thumbs.forEach { (fraction, color) ->
        val cx = xOf(fraction)
        drawCircle(color = color, radius = r, center = Offset(cx, metrics.trackCenterYPx))
        // 2026-09-03（P1c）：内圈环原为写死 `Color.White`。它整圈都压在 thumb 实底之内
        // （缺省底色 = palette.accent），换配色时若某主题的 accent 偏浅，白环会与底融掉、
        // thumb 变成一个没有层次的圆点；接 onAccent 后自动跟随主题给出的"accent 上的内容色"。
        drawCircle(
            color = thumbRingColor,
            radius = r - metrics.ringWidthPx / 2f,
            center = Offset(cx, metrics.trackCenterYPx),
            style = Stroke(width = metrics.ringWidthPx)
        )
    }
}

@Composable
private fun animateSliderFraction(target: Float, dragging: Boolean, label: String) =
    animateFloatAsState(
        targetValue = target,
        // 拖动时 snap（跟手），非拖动（初始 / 编程变更）时 spring（平滑）
        animationSpec = if (dragging) snap() else UfiMotion.sliderTrack(),
        label = label
    )

@Composable
private fun animateThumbScale(dragging: Boolean) = animateFloatAsState(
    targetValue = if (dragging) 1.2f else 1f,
    animationSpec = UfiMotion.press(),
    label = "thumbScale"
)

@Composable
private fun animateTickLabelAlpha(dragging: Boolean) = animateFloatAsState(
    // 标签只在拖动时出现：常驻一排小字会把轨道压得很脏
    targetValue = if (dragging) 1f else 0f,
    animationSpec = tween(durationMillis = UfiMotion.Duration.Quick),
    label = "tickLabelAlpha"
)

private fun rangeSizeOf(valueRange: ClosedFloatingPointRange<Float>): Float =
    (valueRange.endInclusive - valueRange.start).let { if (it == 0f) 1f else it }

private fun fractionOf(value: Float, valueRange: ClosedFloatingPointRange<Float>): Float =
    ((value - valueRange.start) / rangeSizeOf(valueRange)).coerceIn(0f, 1f)

private fun valueAt(
    x: Float,
    trackStartPx: Float,
    trackLenPx: Float,
    valueRange: ClosedFloatingPointRange<Float>
): Float {
    val f = ((x - trackStartPx) / trackLenPx).coerceIn(0f, 1f)
    return valueRange.start + f * rangeSizeOf(valueRange)
}

/**
 * 刻度值列表：首个恒为 `valueRange.start`、末个恒为 `endInclusive`，中间对齐到 [tickStep] 整数倍。
 *
 * 间隔会按值域动态放大到 [tickStep] 的整数倍，使刻度总数≈6 ——
 * 直接按 tickStep 画的话，像流量那种 0..10240 的值域会密到糊成一条线。
 */
private fun computeTickValues(
    valueRange: ClosedFloatingPointRange<Float>,
    tickStep: Float
): List<Float> {
    if (tickStep <= 0f || valueRange.endInclusive <= valueRange.start) return emptyList()
    val min = valueRange.start
    val max = valueRange.endInclusive
    val idealStep = (max - min) / 5
    val step = ceil(idealStep / tickStep).toInt().coerceAtLeast(1) * tickStep

    val result = mutableListOf(min)
    var v = ceil(min / step) * step
    if (v <= min + 0.001f) v += step
    while (v < max - 0.001f) {
        result.add(v)
        v += step
    }
    if (result.last() < max - 0.001f) result.add(max)
    return result
}

private fun snapToStep(
    raw: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
): Float {
    if (steps <= 0) return raw.coerceIn(valueRange)
    val step = (valueRange.endInclusive - valueRange.start) / steps
    val stepsCount = ((raw - valueRange.start) / step).roundToInt()
    return (valueRange.start + stepsCount * step).coerceIn(valueRange)
}
