package com.ufi_axis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.DownsampledPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LineChart(
    data: List<Pair<Long, Float>>,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    maxValue: Float? = null,
    minValue: Float? = null
) {
    if (data.isEmpty()) return

    val values = data.map { it.second }
    val max = maxValue ?: values.max()
    val min = minValue ?: values.min()
    val range = (max - min).coerceAtLeast(0.01f)

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%.1f%s".format(values.last(), unit),
                style = MaterialTheme.typography.labelSmall,
                color = lineColor
            )
            Text(
                text = "%.1f%s".format(max, unit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (data.size > 4) {
                    Text(
                        text = fmt.format(Date(data[data.size / 2].first)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = fmt.format(Date(data.last().first)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
fun MonitorChart(
    points: List<DownsampledPoint>,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    valueFormatter: ((Double) -> String)? = null
) {
    if (points.isEmpty()) return

    val fmt = valueFormatter ?: { v -> "%.1f".format(v) }
    val allValues = points.flatMap { listOf(it.min, it.max, it.avg) }
    val maxVal = allValues.max().toFloat()
    val minVal = allValues.min().toFloat()
    val range = (maxVal - minVal).coerceAtLeast(0.01f)

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val fillColor = lineColor.copy(alpha = 0.12f)
    val rangeColor = lineColor.copy(alpha = 0.08f)
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = labelColor.copy(alpha = 0.6f)
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Chart area: Y-axis labels on the left + canvas on the right
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val yAxisWidth = 44.dp
            val chartHeight = 120.dp

            // Y-axis labels (vertically aligned with grid lines)
            Column(
                modifier = Modifier
                    .width(yAxisWidth)
                    .height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    val value = maxVal.toDouble() - (maxVal.toDouble() - minVal.toDouble()) * i / 4
                    Text(
                        text = fmt(value) + unit,
                        style = labelStyle,
                        color = labelColor,
                        maxLines = 1,
                        modifier = Modifier.wrapContentHeight(Alignment.CenterVertically)
                    )
                }
            }

            // Canvas (chart drawing area)
            Canvas(
                modifier = Modifier
                    .padding(start = yAxisWidth + 4.dp)
                    .fillMaxWidth()
                    .height(chartHeight)
            ) {
                val width = size.width
                val height = size.height
                val padding = 4f
                val chartH = height - 2 * padding

                // Grid lines
                for (i in 0..4) {
                    val y = padding + chartH * i / 4
                    drawLine(gridColor, Offset(0f, y), Offset(width, y), 1f)
                }

                val count = points.size
                val xStep = (width - 2 * padding) / (count - 1).coerceAtLeast(1)

                fun xFor(i: Int) = padding + xStep * i
                fun yFor(v: Float) = height - padding - ((v - minVal) / range) * chartH

                // Min-max range fill
                if (count > 1) {
                    val rangePath = Path()
                    for (i in 0 until count) {
                        val x = xFor(i); val y = yFor(points[i].max.toFloat())
                        if (i == 0) rangePath.moveTo(x, y) else rangePath.lineTo(x, y)
                    }
                    for (i in count - 1 downTo 0) {
                        rangePath.lineTo(xFor(i), yFor(points[i].min.toFloat()))
                    }
                    rangePath.close()
                    drawPath(rangePath, rangeColor)
                }

                // Avg line + gradient fill
                val linePath = Path()
                val fillPath = Path()
                points.forEachIndexed { i, pt ->
                    val x = xFor(i)
                    val y = yFor(pt.avg.toFloat())
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, height - padding)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xFor(count - 1), height - padding)
                fillPath.close()

                clipRect(left = padding, top = 0f, right = width - padding, bottom = height) {
                    drawPath(fillPath, Brush.verticalGradient(
                        colors = listOf(fillColor, Color.Transparent),
                        startY = 0f, endY = height
                    ))
                }
                drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx()))

                // Latest point marker
                val lastPt = points.last()
                drawCircle(lineColor, 4.dp.toPx(), Offset(xFor(count - 1), yFor(lastPt.avg.toFloat())))
            }
        }

        // Current value (latest avg)
        val latestAvg = points.last().avg
        Text(
            text = "${fmt(latestAvg)}$unit",
            style = labelStyle,
            color = lineColor,
            modifier = Modifier.padding(top = 2.dp)
        )

        // Time labels (X-axis, below chart area, offset to align with canvas)
        if (points.size >= 2) {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            Row(
                modifier = Modifier
                    .padding(start = 48.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = timeFmt.format(Date(points.first().t)),
                    style = labelStyle,
                    color = timeColor
                )
                if (points.size > 4) {
                    Text(
                        text = timeFmt.format(Date(points[points.size / 2].t)),
                        style = labelStyle,
                        color = timeColor
                    )
                }
                Text(
                    text = timeFmt.format(Date(points.last().t)),
                    style = labelStyle,
                    color = timeColor
                )
            }
        }
    }
}
