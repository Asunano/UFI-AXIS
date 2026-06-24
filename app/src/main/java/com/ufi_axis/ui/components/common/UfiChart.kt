package com.ufi_axis.ui.components.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.DownsampledPoint
import com.ufi_axis.ui.theme.LocalResolvedPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UfiLineChart(
    data: List<Pair<Long, Float>>,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = LocalResolvedPalette.current.accent,
    maxValue: Float? = null,
    minValue: Float? = null
) {
    if (data.isEmpty()) return

    val palette = LocalResolvedPalette.current
    val values = data.map { it.second }
    val max = maxValue ?: values.max()
    val min = minValue ?: values.min()
    val range = (max - min).coerceAtLeast(0.01f)

    val gridColor = palette.divider.copy(alpha = 0.5f)
    val fillColor = lineColor.copy(alpha = 0.12f)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val width = size.width
            val height = size.height
            val padding = 4f

            // Draw grid lines
            for (i in 0..4) {
                val y = padding + (height - 2 * padding) * i / 4
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            // Build line path and fill path
            val linePath = Path()
            val fillPath = Path()
            data.forEachIndexed { index, (_, value) ->
                val x = padding + (width - 2 * padding) * index / (data.size - 1).coerceAtLeast(1)
                val normalizedY = (value - min) / range
                val y = height - padding - normalizedY * (height - 2 * padding)

                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, height - padding)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            // Close fill path
            val lastX = padding + (width - 2 * padding)
            fillPath.lineTo(lastX, height - padding)
            fillPath.close()

            // Draw fill
            clipRect(left = padding, top = 0f, right = width - padding, bottom = height) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(fillColor, Color.Transparent),
                        startY = 0f,
                        endY = height
                    )
                )
            }

            // Draw line
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw latest point
            if (data.isNotEmpty()) {
                val last = data.last()
                val x = padding + (width - 2 * padding)
                val normalizedY = (last.second - min) / range
                val y = height - padding - normalizedY * (height - 2 * padding)
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }

        // Value labels row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "%.1f%s".format(min, unit),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
            Text(
                text = "%.1f%s".format(values.last(), unit),
                style = MaterialTheme.typography.labelSmall,
                color = lineColor
            )
            Text(
                text = "%.1f%s".format(max, unit),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }

        // Time labels row
        if (data.size >= 2) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fmt.format(Date(data.first().first)),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary.copy(alpha = 0.6f)
                )
                if (data.size > 4) {
                    Text(
                        text = fmt.format(Date(data[data.size / 2].first)),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = fmt.format(Date(data.last().first)),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Enhanced chart for monitor history data with min/max range shading.
 * Y-axis (value) labels on the left, X-axis (time) labels at the bottom.
 */
@Composable
fun UfiMonitorChart(
    points: List<DownsampledPoint>,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = LocalResolvedPalette.current.accent,
    valueFormatter: ((Double) -> String)? = null
) {
    if (points.isEmpty()) return

    val palette = LocalResolvedPalette.current
    val fmt = valueFormatter ?: { v -> "%.1f".format(v) }
    val allValues = points.flatMap { listOf(it.min, it.max, it.avg) }
    val maxVal = allValues.max().toFloat()
    val minVal = allValues.min().toFloat()
    val range = (maxVal - minVal).coerceAtLeast(0.01f)

    val gridColor = palette.divider.copy(alpha = 0.5f)
    val fillColor = lineColor.copy(alpha = 0.12f)
    val rangeColor = lineColor.copy(alpha = 0.08f)
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = palette.textSecondary
    val timeColor = labelColor.copy(alpha = 0.6f)
    val textMeasurer = rememberTextMeasurer()

    var dragRatio by remember { mutableFloatStateOf(-1f) }  // -1=无拖动, 0..1=位置比例
    val timeFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    val selectedIndex = if (dragRatio >= 0f && points.size >= 2) {
        (dragRatio * (points.size - 1) + 0.5f).toInt().coerceIn(0, points.size - 1)
    } else -1

    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))

        // Chart area with floating tooltip overlay
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val yAxisWidth = 44.dp
            val chartHeight = 120.dp
            val boxWidth = maxWidth

            // Y-axis labels
            Column(modifier = Modifier.width(yAxisWidth).height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) {
                    val value = maxVal.toDouble() - (maxVal.toDouble() - minVal.toDouble()) * i / 4
                    Text(text = fmt(value) + unit, style = labelStyle, color = labelColor, maxLines = 1,
                        modifier = Modifier.wrapContentHeight(Alignment.CenterVertically))
                }
            }

            // Canvas + floating tooltip container
            Box(
                modifier = Modifier
                    .padding(start = yAxisWidth + 4.dp)
                    .fillMaxWidth()
                    .height(chartHeight)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(points) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragRatio = ((offset.x) / size.width).coerceIn(0f, 1f)
                                },
                                onDrag = { change, _ ->
                                    dragRatio = ((change.position.x) / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = { dragRatio = -1f },
                                onDragCancel = { dragRatio = -1f }
                            )
                        }
                ) {
                    val width = size.width; val height = size.height; val padding = 4f; val chartH = height - 2 * padding
                    for (i in 0..4) { val y = padding + chartH * i / 4; drawLine(gridColor, Offset(0f, y), Offset(width, y), 1f) }
                    val count = points.size
                    val xStep = (width - 2 * padding) / (count - 1).coerceAtLeast(1)
                    fun xFor(i: Int) = padding + xStep * i
                    fun yFor(v: Float) = height - padding - ((v - minVal) / range) * chartH
                    if (count > 1) {
                        val rangePath = Path()
                        for (i in 0 until count) { val x = xFor(i); val y = yFor(points[i].max.toFloat()); if (i == 0) rangePath.moveTo(x, y) else rangePath.lineTo(x, y) }
                        for (i in count - 1 downTo 0) { rangePath.lineTo(xFor(i), yFor(points[i].min.toFloat())) }
                        rangePath.close(); drawPath(rangePath, rangeColor)
                    }
                    val linePath = Path(); val fillPath = Path()
                    points.forEachIndexed { i, pt ->
                        val x = xFor(i); val y = yFor(pt.avg.toFloat())
                        if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, height - padding); fillPath.lineTo(x, y) }
                        else { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
                    }
                    fillPath.lineTo(xFor(count - 1), height - padding); fillPath.close()
                    clipRect(left = padding, top = 0f, right = width - padding, bottom = height) {
                        drawPath(fillPath, Brush.verticalGradient(listOf(fillColor, Color.Transparent), startY = 0f, endY = height))
                    }
                    drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx()))

                    if (selectedIndex in points.indices) {
                        val selPt = points[selectedIndex]
                        val sx = xFor(selectedIndex); val sy = yFor(selPt.avg.toFloat())
                        drawCircle(lineColor, 6.dp.toPx(), Offset(sx, sy))
                        drawCircle(lineColor.copy(alpha = 0.3f), 12.dp.toPx(), Offset(sx, sy))
                        drawLine(gridColor, Offset(sx, padding), Offset(sx, height - padding), 1f)
                    }
                    drawCircle(lineColor, 4.dp.toPx(), Offset(xFor(points.size - 1), yFor(points.last().avg.toFloat())))
                }

                // Floating tooltip bubble — 自适应宽度 + 渐入渐出
                val showTooltip = selectedIndex in points.indices
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTooltip,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    if (selectedIndex !in points.indices) return@AnimatedVisibility
                    val pt = points[selectedIndex]
                    val labelWidthDp = yAxisWidth + 4.dp
                    val chartAreaWidth = boxWidth - labelWidthDp
                    val tooltipMaxWidth = (chartAreaWidth / 2f).coerceAtMost(160.dp)
                    val marginDp = 8.dp
                    // 气泡居中在手指位置，然后 clamp 在可见区域内
                    val rawCenter = chartAreaWidth * dragRatio.coerceIn(0f, 1f)
                    val rawLeft = rawCenter - tooltipMaxWidth / 2f
                    val clampedLeft = rawLeft.coerceIn(marginDp, (chartAreaWidth - tooltipMaxWidth - marginDp).coerceAtLeast(marginDp))

                    Surface(
                        modifier = Modifier
                            .offset(x = clampedLeft)
                            .widthIn(min = 100.dp, max = tooltipMaxWidth),
                        shape = RoundedCornerShape(8.dp),
                        color = palette.cardBg,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(timeFmt.format(Date(pt.t)), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                            Text("${fmt(pt.avg)}$unit", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = lineColor)
                            Text("↓${fmt(pt.min)}$unit  ↑${fmt(pt.max)}$unit", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                        }
                    }
                }
            }
        }

        // Time labels
        if (points.size >= 2) {
            Row(modifier = Modifier.padding(start = 48.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeFmt.format(Date(points.first().t)), style = labelStyle, color = timeColor)
                if (points.size > 4) Text(timeFmt.format(Date(points[points.size / 2].t)), style = labelStyle, color = timeColor)
                Text(timeFmt.format(Date(points.last().t)), style = labelStyle, color = timeColor)
            }
        }
    }
}
