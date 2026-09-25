package com.ufi_axis.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * 手绘天气图形（2026-09-22）。
 *
 * ## 为什么自绘，而不是继续用 Material 图标
 * 原来用的是 `Icons.Default.WbSunny` 这一类 Material 矢量图标 —— 它们不是 emoji，是真矢量，
 * 但那套几何过于标准化：单色、等宽、线端方正，8 种天气摆在一起像一排系统设置项而不是天气。
 * 而 `Icon` 只接受单一 `tint`，**多色是做不到的**（太阳橙心黄芒、雨云灰身蓝丝这种）。
 *
 * 所以改成 Canvas 自绘。本文件所在的 `UfiHeaderCaptionProbe.kt` 里已有自绘先例
 * （天气详情弹窗那张小时温度折线图），这条路在本模块是成立的，也不引入任何新依赖。
 *
 * ## 手绘感从哪来
 * 三件事，没有一件是随机数：
 * 1. **一律圆端**（`StrokeCap.Round`）—— 方端是"工程制图"，圆端是"笔画"；
 * 2. **刻意的不齐**：太阳的 8 根芒长度按固定表错开、雾的横线长短不一、雨丝起点各自偏移。
 *    这些偏移全是写死的常量，**不能用 `Math.random()`** —— Canvas 每次重绘都会重新求值，
 *    随机会让图形每帧抖动；
 * 3. **两层色**：每个形体都有一层"底色 + 稍深的下缘/内核"，而不是纯平铺，
 *    这是单色 `Icon` 给不了的。
 *
 * ## 坐标全部按边长比例
 * 画布是正方形，一切尺寸都写成 `s`（边长）的倍数，所以同一套路径在 34dp 的挂件和
 * 未来更大的天气页里都成立，不需要为每个尺寸调参。
 *
 * @param weatherCode WMO weather_code（与 open-meteo 一致）。
 * @param isDay 白天/夜间。只影响晴天（太阳 ↔ 月亮）；其余天气昼夜同形。
 */
@Composable
internal fun WeatherGlyph(
    weatherCode: Int,
    isDay: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val isDark = LocalResolvedPalette.current.isDark
    val c = glyphColors(isDark)
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        when (weatherCode) {
            0, 1 -> if (isDay) drawSun(s, c) else drawMoon(s, c)
            2 -> drawPartlyCloudy(s, c, isDay)
            3 -> drawOvercast(s, c)
            45, 48 -> drawFog(s, c)
            in 51..57 -> drawDrizzle(s, c)
            in 61..67, in 80..82 -> drawRain(s, c)
            in 71..77, 85, 86 -> drawSnow(s, c)
            95, 96, 99 -> drawThunder(s, c)
            else -> drawOvercast(s, c)
        }
    }
}

/**
 * 一套天气色。
 *
 * 深浅两版：浅色配色下要压暗一档才压得住白底，深色下要提亮一档才不糊进背景。
 *
 * 这套色**刻意不进 `ResolvedPalette` 的令牌层**：那里的语义色是 `error / warning / success`
 * 这一类**状态**色，拿 `error`（红）去画雷暴会让顶栏看起来在报故障。天气色是领域色板。
 * 而且往 `ResolvedPalette` 加字段会强制所有配色方案的构造点一起改 —— 那条约束是刻意的护栏，
 * 为一个挂件图形去动全局配色表不划算。以后天气详情页也要用这套色时，再提进令牌层才值得。
 */
private data class GlyphColors(
    /** 太阳光芒 / 外圈。 */
    val sun: Color,
    /** 太阳内核（比光芒深一档，形成"两层色"）。 */
    val sunCore: Color,
    val moon: Color,
    /** 云的受光面。 */
    val cloud: Color,
    /** 云的下缘阴影（云之所以像云，靠的就是这一层）。 */
    val cloudShade: Color,
    val rain: Color,
    val snow: Color,
    val bolt: Color,
    val fog: Color
)

private fun glyphColors(isDark: Boolean): GlyphColors = if (isDark) {
    GlyphColors(
        sun = Color(0xFFFFD166),
        sunCore = Color(0xFFFFA726),
        moon = Color(0xFFCBD5F5),
        cloud = Color(0xFFD5DEE8),
        cloudShade = Color(0xFF9AA8B8),
        rain = Color(0xFF6FB7F0),
        snow = Color(0xFFE3F4FA),
        bolt = Color(0xFFFFC145),
        fog = Color(0xFFAEBAC4)
    )
} else {
    GlyphColors(
        sun = Color(0xFFF9B534),
        sunCore = Color(0xFFF07C1F),
        moon = Color(0xFF5B6BB5),
        cloud = Color(0xFFB9C6D4),
        cloudShade = Color(0xFF7D8DA0),
        rain = Color(0xFF2E86C8),
        snow = Color(0xFF7FB6CC),
        bolt = Color(0xFFE08A00),
        fog = Color(0xFF7D8A93)
    )
}

// ─────────────────────────── 基础形体 ───────────────────────────

/**
 * 太阳。内核 + 8 根光芒。
 *
 * 光芒长度按 [RAY_LEN] 逐根错开 —— 8 根一样长是"齿轮"，长短交错才像随手画的。
 */
private fun DrawScope.drawSun(
    s: Float,
    c: GlyphColors,
    cx: Float = 0.5f,
    cy: Float = 0.5f,
    scale: Float = 1f
) {
    val center = Offset(s * cx, s * cy)
    val coreR = s * 0.185f * scale
    // 外层柔光：一圈低透明度的同色，让内核不至于像一个突兀的实心点
    drawCircle(color = c.sun.copy(alpha = 0.22f), radius = coreR * 1.34f, center = center)
    drawCircle(color = c.sunCore, radius = coreR, center = center)

    val strokeW = s * 0.055f * scale
    RAY_LEN.forEachIndexed { i, lenFactor ->
        val angle = Math.toRadians((i * 45f + RAY_TILT).toDouble())
        val inner = coreR * 1.52f
        val outer = coreR * (1.52f + 0.62f * lenFactor)
        drawLine(
            color = c.sun,
            start = Offset(
                center.x + (inner * cos(angle)).toFloat(),
                center.y + (inner * sin(angle)).toFloat()
            ),
            end = Offset(
                center.x + (outer * cos(angle)).toFloat(),
                center.y + (outer * sin(angle)).toFloat()
            ),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 月亮（弯月）+ 两颗星。
 *
 * 弯月用**路径求差**画：整圆减去一个偏移的圆。不用"画个圆再盖一个背景色的圆"那种做法 ——
 * 标题栏背景不是已知实色（页面背景可能是渐变/图片），盖上去会露出一块突兀的色块。
 */
private fun DrawScope.drawMoon(s: Float, c: GlyphColors) {
    val r = s * 0.30f
    val center = Offset(s * 0.52f, s * 0.50f)
    val full = Path().apply { addOval(Rect(center, r)) }
    val bite = Path().apply {
        addOval(Rect(Offset(center.x + r * 0.62f, center.y - r * 0.30f), r * 0.92f))
    }
    val crescent = Path().apply { op(full, bite, PathOperation.Difference) }
    drawPath(crescent, c.moon)

    // 两颗星：一大一小、位置不对称 —— 对称摆放会立刻失去随手画的感觉
    drawStar(Offset(s * 0.20f, s * 0.26f), s * 0.052f, c.moon.copy(alpha = 0.85f), s)
    drawStar(Offset(s * 0.30f, s * 0.74f), s * 0.036f, c.moon.copy(alpha = 0.6f), s)
}

/** 四角星（两条交叉线），给夜空点缀用。 */
private fun DrawScope.drawStar(center: Offset, r: Float, color: Color, s: Float) {
    val w = s * 0.022f
    drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), w, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), w, StrokeCap.Round)
}

/**
 * 云。
 *
 * 三个交叠的圆 + 一条圆角底边合成一条路径（NonZero 填充规则下交叠部分自然并成一体）。
 * 先画一层下移的深色作阴影，再画受光面 —— 这一层是"看起来像云"而不是"像三个圆"的关键。
 *
 * @param w 云的半宽基准（整朵云宽约 `1.9 * w`）。
 */
private fun DrawScope.drawCloud(
    s: Float,
    c: GlyphColors,
    cx: Float,
    cy: Float,
    w: Float,
    light: Color = c.cloud,
    shade: Color = c.cloudShade
) {
    fun body(dy: Float): Path = Path().apply {
        addOval(Rect(Offset(s * cx - w * 0.46f, s * cy + dy), w * 0.40f))
        addOval(Rect(Offset(s * cx + 0.02f * w, s * cy - w * 0.22f + dy), w * 0.52f))
        addOval(Rect(Offset(s * cx + w * 0.50f, s * cy + w * 0.04f + dy), w * 0.36f))
        addOval(Rect(Offset(s * cx, s * cy + w * 0.20f + dy), w * 0.44f))
    }
    drawPath(body(w * 0.16f), shade)
    drawPath(body(0f), light)
}

// ─────────────────────────── 各天气 ───────────────────────────

/** 少云：太阳从云后露出来。太阳缩小并推到右上，云压在它前面。 */
private fun DrawScope.drawPartlyCloudy(s: Float, c: GlyphColors, isDay: Boolean) {
    if (isDay) {
        drawSun(s, c, cx = 0.66f, cy = 0.33f, scale = 0.62f)
    } else {
        val r = s * 0.17f
        val center = Offset(s * 0.68f, s * 0.31f)
        val full = Path().apply { addOval(Rect(center, r)) }
        val bite = Path().apply {
            addOval(Rect(Offset(center.x + r * 0.62f, center.y - r * 0.30f), r * 0.92f))
        }
        drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, c.moon)
    }
    drawCloud(s, c, cx = 0.44f, cy = 0.60f, w = s * 0.30f)
}

/** 阴：两朵云，后面那朵更深更小，形成层次。 */
private fun DrawScope.drawOvercast(s: Float, c: GlyphColors) {
    drawCloud(
        s, c, cx = 0.60f, cy = 0.38f, w = s * 0.24f,
        light = c.cloudShade.copy(alpha = 0.75f),
        shade = c.cloudShade.copy(alpha = 0.45f)
    )
    drawCloud(s, c, cx = 0.44f, cy = 0.60f, w = s * 0.30f)
}

/**
 * 雾：三条长短不一的横线，不画云。
 *
 * 雾的视觉特征是"层层横向的遮挡"，加上云反而会和"阴"混淆（这两个原来就共用一个图标）。
 */
private fun DrawScope.drawFog(s: Float, c: GlyphColors) {
    val w = s * 0.062f
    FOG_LINES.forEach { (yF, span) ->
        val y = s * yF
        val half = s * span / 2f
        drawLine(
            color = c.fog.copy(alpha = 0.55f + 0.2f * span),
            start = Offset(s * 0.5f - half, y),
            end = Offset(s * 0.5f + half, y),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/** 毛毛雨：云 + 四条**短**雨丝，比大雨细、比大雨密。 */
private fun DrawScope.drawDrizzle(s: Float, c: GlyphColors) {
    drawCloud(s, c, cx = 0.5f, cy = 0.42f, w = s * 0.30f)
    val w = s * 0.042f
    DRIZZLE_X.forEach { xF ->
        val x = s * xF
        drawLine(
            color = c.rain,
            start = Offset(x, s * 0.72f),
            end = Offset(x - s * 0.02f, s * 0.82f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/** 雨：云 + 三条斜雨丝，起点长度各不相同。 */
private fun DrawScope.drawRain(s: Float, c: GlyphColors) {
    drawCloud(s, c, cx = 0.5f, cy = 0.40f, w = s * 0.30f)
    val w = s * 0.055f
    RAIN_DROPS.forEach { (xF, lenF) ->
        val x = s * xF
        drawLine(
            color = c.rain,
            start = Offset(x, s * 0.68f),
            end = Offset(x - s * 0.05f, s * (0.68f + lenF)),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/** 雪：云 + 两朵六角雪花（三条交叉线各转 60°）。 */
private fun DrawScope.drawSnow(s: Float, c: GlyphColors) {
    drawCloud(s, c, cx = 0.5f, cy = 0.40f, w = s * 0.30f)
    drawFlake(Offset(s * 0.34f, s * 0.79f), s * 0.085f, s, c.snow)
    drawFlake(Offset(s * 0.64f, s * 0.84f), s * 0.065f, s, c.snow.copy(alpha = 0.8f))
}

private fun DrawScope.drawFlake(center: Offset, r: Float, s: Float, color: Color) {
    val w = s * 0.032f
    // 60° 一根，三根组成六角
    for (i in 0 until 3) {
        rotate(degrees = i * 60f, pivot = center) {
            drawLine(
                color = color,
                start = Offset(center.x - r, center.y),
                end = Offset(center.x + r, center.y),
                strokeWidth = w,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 雷暴：深色云 + 一道实心闪电。闪电压在云的下缘，让两者有交叠而不是并列。 */
private fun DrawScope.drawThunder(s: Float, c: GlyphColors) {
    drawCloud(
        s, c, cx = 0.5f, cy = 0.38f, w = s * 0.30f,
        light = c.cloudShade,
        shade = c.cloudShade.copy(alpha = 0.6f)
    )
    val bolt = Path().apply {
        moveTo(s * 0.54f, s * 0.56f)
        lineTo(s * 0.38f, s * 0.80f)
        lineTo(s * 0.49f, s * 0.80f)
        lineTo(s * 0.42f, s * 0.98f)
        lineTo(s * 0.64f, s * 0.72f)
        lineTo(s * 0.52f, s * 0.72f)
        lineTo(s * 0.60f, s * 0.56f)
        close()
    }
    drawPath(bolt, c.bolt)
    // 一道细描边把闪电从深色云里"提"出来
    drawPath(bolt, c.bolt.copy(alpha = 0.5f), style = Stroke(width = s * 0.02f))
}

// ─────────────────────────── 写死的不齐参数 ───────────────────────────
//
// 全部是常量，不能换成随机数：Canvas 每次重绘都会重新求值，随机会让图形逐帧抖动。

/** 太阳 8 根光芒的相对长度（1.0 = 基准）。交错的长短就是"随手画"的来源。 */
private val RAY_LEN = floatArrayOf(1.00f, 0.72f, 0.94f, 0.66f, 1.00f, 0.70f, 0.90f, 0.64f)

/** 光芒整体斜一点，避免第一根正好水平（正交会显得很"制图"）。 */
private const val RAY_TILT = -8f

/** 雾的三条横线：`纵向位置 → 相对长度`。 */
private val FOG_LINES = listOf(0.36f to 0.62f, 0.52f to 0.80f, 0.68f to 0.48f)

/** 毛毛雨四条短雨丝的横向位置。 */
private val DRIZZLE_X = floatArrayOf(0.31f, 0.44f, 0.57f, 0.70f)

/** 雨的三条雨丝：`横向位置 → 长度`。 */
private val RAIN_DROPS = listOf(0.33f to 0.22f, 0.50f to 0.28f, 0.67f to 0.19f)

/** 挂件里的默认边长。抽出来是为了让调用方与这里的比例参数有一个共同参照。 */
internal val WEATHER_GLYPH_SIZE: Dp = 34.dp
