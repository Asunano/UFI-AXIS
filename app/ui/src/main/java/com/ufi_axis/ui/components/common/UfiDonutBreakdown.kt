// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 圆环构成图的一段。
 *
 * @param value 权重（不需要预先归一化，组件按各段之和折算角度）；负值按 0 处理
 * @param color 该段的颜色。取色请用 [ufiDonutPalette]，不要在业务页里写死色值
 */
data class UfiDonutSegment(
    val value: Double,
    val color: Color
)

/**
 * 多段圆环构成图（donut breakdown）。
 *
 * 与 [UfiRingProgress] 的分工：那个画的是**一个**「已用/总量」比例（单值进度环），
 * 这个画的是**一组分项如何构成总量**（每段一个颜色）。两者都不该在业务页里手绘 drawArc。
 *
 * ## 设计取舍
 * - 段与段之间留 [gapDegrees] 的缝：相邻两段颜色接近时，没有缝就分不出边界。
 * - 占比太小（折算角度不足一条缝）的段**不画**，否则会退化成一串看不出长度的色点，
 *   反而让人以为那几段一样大。它们仍然出现在调用方渲染的图例里，不会凭空消失。
 * - 中心留空并开放 [centerContent] 槽位：环本身只表达"构成"，总量该用文字说。
 *
 * @param segments 各分项。全部为 0（或空列表）时只画轨道，中心内容照常显示。
 * @param centerContent 圆心内容（通常是总量数值 + 一行说明）
 */
@Composable
fun UfiDonutChart(
    segments: List<UfiDonutSegment>,
    modifier: Modifier = Modifier,
    size: Dp = DonutDefaults.Size,
    strokeWidth: Dp = DonutDefaults.StrokeWidth,
    gapDegrees: Float = DonutDefaults.GapDegrees,
    trackColor: Color = LocalResolvedPalette.current.divider.copy(alpha = 0.15f),
    centerContent: (@Composable () -> Unit)? = null
) {
    val total = segments.sumOf { it.value.coerceAtLeast(0.0) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Butt)
            val diameter = this.size.minDimension - strokePx
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )

            if (total <= 0.0) return@Canvas

            var startAngle = -90f
            segments.forEach { segment ->
                val share = (segment.value.coerceAtLeast(0.0) / total).toFloat()
                val full = 360f * share
                // 缝不能吃掉整段：不足一条缝宽的段直接跳过（理由见 KDoc）
                val sweep = full - gapDegrees
                if (sweep > 0f) {
                    drawArc(
                        color = segment.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                }
                startAngle += full
            }
        }

        centerContent?.invoke()
    }
}

/**
 * 图例行：色点 + 名称 + 右侧数值。
 *
 * 与 [UfiDonutChart] 配对使用；色点颜色必须和对应段一致，否则图例就是错的。
 *
 * @param label 分项名称
 * @param value 右侧数值文本（大小、占比、条数由调用方决定怎么写）
 * @param color 与圆环对应段相同的颜色
 * @param trailing 行尾槽位（如「清理」按钮）
 */
@Composable
fun UfiDonutLegendRow(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier.padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(DonutDefaults.LegendDotSize)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(Spacing.Medium))
        Text(
            text = label,
            style = UfiTextStyles.listItemTitle,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            text = value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textSecondary
        )
        trailing?.let {
            Spacer(Modifier.width(Spacing.Medium))
            it()
        }
    }
}

/**
 * 圆环分段配色（按调用顺序取用）。
 *
 * 全部来自 [LocalResolvedPalette]，换配色主题时跟着变 —— 业务页**不要**自己写死一组
 * 十六进制颜色，那是本仓 `color` 字面量基线常年为 0 的原因。
 *
 * 前 6 个是各自独立的语义色；第 7 个起用 accent 降透明度续接（构成图里超过 6 段时，
 * 再造新色只会互相干扰，同色系深浅反而更好读）。
 */
@Composable
fun ufiDonutPalette(): List<Color> {
    val palette = LocalResolvedPalette.current
    return listOf(
        palette.accent,
        palette.accentSecondary,
        palette.success,
        palette.warning,
        palette.error,
        palette.metricNormal,
        palette.accent.copy(alpha = 0.55f),
        palette.accentSecondary.copy(alpha = 0.55f)
    )
}

/** 圆环构成图的几何默认值。取自 [Spacing] 令牌层 —— 组件里不写 dp 字面量。 */
object DonutDefaults {
    val Size: Dp get() = Spacing.DonutSize
    val StrokeWidth: Dp get() = Spacing.DonutStroke
    val LegendDotSize: Dp get() = Spacing.DonutLegendDot

    /** 段间缝隙（角度）。2 度在 168dp 直径上约 3px，够看出边界又不至于吃掉小段。 */
    const val GapDegrees: Float = 2f
}
