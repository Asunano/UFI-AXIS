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
 * @param animate 是否播放进度补间动画。
 *   为 true 时还会做**首帧绘入**：初值 0、组合后的第一帧才开始扫向 [progress]，
 *   于是进页面能看到环"画出来"而不是凭空画满。
 * @param animationDurationMillis 补间时长，默认 [UfiMotion.Duration.Languid]（800ms，
 *   "慢而稳的自绘动画"那一档）。
 *
 *   2026-09-21 新增这个入参：首页那四个指标环原来传 `animate = false`，因为 800ms 配
 *   10s 轮询的即时读数观感是"环在慢慢爬"，抖动的读数还会来回摇。现在它们改传
 *   [UfiMotion.Duration.Smooth]（300ms）—— 看得出扫过去的运动感，又不拖。
 *   带默认值，不破坏本文件顶部那条 STABLE-UI-API 冻结约定。
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
    animationDurationMillis: Int = UfiMotion.Duration.Languid,
    centerContent: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val target = progress.coerceIn(0f, 1f)

    /*
     * 首帧绘入（2026-09-21）。
     *
     * `animateFloatAsState` 把**第一次**传入的 targetValue 当作初值，所以直接传 progress
     * 的话首屏是一次性画满、只有之后的变化才有补间 —— 进页面看不到"画出来"的过程。
     * 这里第一帧先给 0，`LaunchedEffect` 在组合之后把 entered 翻成 true，动画才从 0 起跑。
     *
     * 数据晚到（先 0 后有值）也走同一条路：环停在 0，值到了再扫过去，不需要额外分支。
     */
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val animatedProgress by animateFloatAsState(
        targetValue = if (entered) target else 0f,
        animationSpec = tween(durationMillis = animationDurationMillis),
        label = "ringProgress"
    )
    val shownProgress = if (animate) animatedProgress else target
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
