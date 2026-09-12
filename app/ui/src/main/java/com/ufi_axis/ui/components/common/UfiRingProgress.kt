// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 环形进度指示器（donut chart 风格）。
 *
 * 用于流量用量、存储空间等「已用/总量」场景的可视化展示。
 * 中心显示百分比文字，外圈为进度弧，内圈为轨道。
 *
 * @param progress 进度值 0f..1f
 * @param size 组件整体尺寸
 * @param strokeWidth 环的线宽
 * @param label 百分比下方的小字标签（如 "已用 2.3 GB"）
 * @param color 进度弧颜色
 * @param trackColor 轨道颜色
 * @param animate 是否播放进度补间动画。2026-08-31 新增：首页指标环是"跟随轮询刷新的即时读数"，
 *   每次刷新都跑一遍 800ms 补间反而抖，故它传 false。
 * @param centerContent 中心内容槽位。2026-08-31 新增：默认仍是「百分比 + [label]」，
 *   传入时完全替换中心内容（首页指标环要显示 "2.3/8.0 GB" 这类原文而非百分比）。
 */
@Composable
fun UfiRingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    strokeWidth: Dp = 10.dp,
    label: String? = null,
    color: Color = LocalResolvedPalette.current.accent,
    trackColor: Color = LocalResolvedPalette.current.divider.copy(alpha = 0.15f),
    animate: Boolean = true,
    centerContent: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        // 2026-09-04（P2b）：原为裸 `tween(durationMillis = 800)`。800 本来就等于
        // Duration.Languid（"慢而稳的自绘动画"正是这一档的定义），纯换 token，零观感变化。
        animationSpec = tween(durationMillis = UfiMotion.Duration.Languid),
        label = "ringProgress"
    )
    val shownProgress = if (animate) animatedProgress else progress.coerceIn(0f, 1f)
    val percentText = "${(shownProgress * 100).toInt()}%"

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val diameter = size.toPx() - strokeWidth.toPx()
            val topLeft = Offset(strokeWidth.toPx() / 2, strokeWidth.toPx() / 2)
            val arcSize = Size(diameter, diameter)

            // 轨道
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )

            // 进度弧
            if (shownProgress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * shownProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
        }

        // 中心内容：默认「百分比 + label」，调用方可用 centerContent 整体替换
        if (centerContent != null) {
            centerContent()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(
                    text = percentText,
                    style = UfiTextStyles.heroValue,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center
                )
                if (label != null) {
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text(
                        text = label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
