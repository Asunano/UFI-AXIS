package com.ufi_axis.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.feature.settings.R
import com.ufi_axis.ui.theme.LocalResolvedPalette
import kotlin.math.PI
import kotlin.math.sin

/**
 * 标题栏天气图标（2026-11-11 起用 Tabler outline；2026-11-15 改成分部件动效）。
 *
 * ## 为什么分部件
 * 上一版除太阳外都是**整张图**一起位移/旋转/淡出。多部件图标（云+雨、云+雪、日+云）
 * 整体一动，云也跟着晃，而且雨丝、雪花本身完全没在动 —— 读起来"像图标在抖"，
 * 而不是"天在下雨"。现在按层拆分：**该动的那一层动，别的不动**，与太阳的
 * 「核心静止 + 光芒旋转」同一套语言。
 *
 * 拆出来的资源：
 * - `ic_weather_cloud_only`：云（雨/雪/雾/雷暴/晴间多云的公共底）
 * - `ic_weather_partly_sun`：晴间多云里的太阳（圆盘 + 光芒）
 * - `ic_weather_bolt`：闪电
 * - 雨丝 / 雪花 / 冰雹 / 雾带 / z 直接画（数量随天气码变化，见 [rainDropCount] 等）
 *
 * 视觉稿与逐帧评审见 `dev-docs/mockups/weather-motion/index.html`（开发文档，不入库）。
 *
 * @param weatherCode WMO weather_code（与 open-meteo 一致）。
 * @param isDay 白天/夜间；只影响晴天与"多云"的太阳/月亮选择。
 */

/** 标题栏挂件的固定尺寸；天气设置页的摘要"预览"也用这个基准。 */
internal val WEATHER_GLYPH_SIZE = 34.dp

/** 图标的坐标空间：所有 path 都写在 24×24 里，绘制时统一缩放到控件尺寸。 */
private const val GLYPH_UNITS = 24f

@Composable
internal fun WeatherGlyph(
    weatherCode: Int,
    isDay: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val dark = LocalResolvedPalette.current.isDark
    val tint = weatherTint(weatherCode, isDay, dark)
    when (weatherKindOf(weatherCode, isDay)) {
        WeatherKind.SUN -> SunGlyph(size, tint, modifier)
        WeatherKind.MOON -> MoonGlyph(size, tint, modifier)
        WeatherKind.PARTLY -> PartlyGlyph(size, tint, modifier)
        WeatherKind.CLOUD -> CloudGlyph(size, tint, modifier)
        WeatherKind.FOG -> FogGlyph(size, tint, modifier)
        WeatherKind.RAIN -> RainGlyph(
            drops = rainDropCount(weatherCode),
            heavy = isHeavyPrecip(weatherCode),
            size = size,
            tint = tint,
            modifier = modifier
        )
        WeatherKind.SNOW -> SnowGlyph(
            flakes = snowFlakeCount(weatherCode),
            fine = weatherCode == 77,
            heavy = weatherCode == 75,
            size = size,
            tint = tint,
            modifier = modifier
        )
        WeatherKind.STORM -> StormGlyph(
            hail = weatherCode == 96 || weatherCode == 99,
            size = size,
            tint = tint,
            modifier = modifier
        )
    }
}

/** 天气挂件实际会画的那几种动画。 */
internal enum class WeatherKind { SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

/**
 * WMO code → 动画种类（[WeatherGlyph] 的唯一分派依据，抽出纯函数便于单测）。
 *
 * 覆盖 core `WeatherRoutes.describe()` 里的全部 27 个码；未列出的码统一落到
 * [WeatherKind.CLOUD]（云呼吸）—— 宁可"看起来像阴天"，也不要画错成下雨。
 */
internal fun weatherKindOf(code: Int, isDay: Boolean): WeatherKind = when {
    // 0 晴 / 1 晴间多云（mainly clear，仍按晴天画）
    code == 0 || code == 1 -> if (isDay) WeatherKind.SUN else WeatherKind.MOON
    // 2 多云：白天太阳 + 云，夜里只有云
    code == 2 -> if (isDay) WeatherKind.PARTLY else WeatherKind.CLOUD
    code == 3 -> WeatherKind.CLOUD
    code == 45 || code == 48 -> WeatherKind.FOG
    code in 51..57 || code in 61..67 || code in 80..82 -> WeatherKind.RAIN
    code in 71..77 || code == 85 || code == 86 -> WeatherKind.SNOW
    code == 95 || code == 96 || code == 99 -> WeatherKind.STORM
    else -> WeatherKind.CLOUD
}

// ══════════════ 雨 / 雪 / 冰雹的"量" ══════════════
//
// 抽成纯函数是为了让"哪个天气码画几根线"可测：这段映射错了不会崩，
// 只会表现为"大雨看起来和小雨一样"，很难在 UI 上回归，所以单独锁住。

/**
 * 雨的竖线条数：小 2 / 中 3 / 大 5。
 *
 * 51/56/57 小毛毛雨、61 小雨、80 小阵雨 → 2
 * 53 毛毛雨、63 中雨、66/67 冻雨、81 阵雨 → 3
 * 55 大毛毛雨、65 大雨、82 强阵雨 → 5
 */
internal fun rainDropCount(code: Int): Int = when (code) {
    55, 65, 82 -> 5
    53, 63, 66, 67, 81 -> 3
    51, 56, 57, 61, 80 -> 2
    else -> 0
}

/**
 * 雪的雪花片数：小 2 / 中 3 / 大 5。
 *
 * 71 小雪、85 小阵雪 → 2
 * 73 中雪、86 阵雪、77 雪粒 → 3
 * 75 大雪 → 5
 */
internal fun snowFlakeCount(code: Int): Int = when (code) {
    75 -> 5
    73, 77, 86 -> 3
    71, 85 -> 2
    else -> 0
}

/** 大雨 / 大雪：下落更快，密度与速度一起体现"大"。 */
internal fun isHeavyPrecip(code: Int): Boolean =
    code == 55 || code == 65 || code == 82 || code == 75

// ══════════════ 各天气的图标 ══════════════

/**
 * 晴天：核心不转，光芒以 16s 一圈慢慢旋转；光芒本身四长四短。
 *
 * 没有让整张图一起转：太阳的圆核跟着转是看不出来的，反而会像图标在晃。
 */
@Composable
private fun SunGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "weather-sun")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-sun-rotation"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-sun-breathe"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_sun_core),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Icon(
            painter = painterResource(R.drawable.ic_weather_sun_rays),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = breathe
                    scaleY = breathe
                }
        )
    }
}

/**
 * 夜间晴：月亮**完全静止**，右上角三颗 `z` 依次飞出并逐渐变小（2026-11-15 用户定稿）。
 *
 * 三颗共用同一条轨迹（从紧贴月亮的右上角出发，向右上 45° 飞 2.7×4.6 单位），
 * 彼此错峰 0.9s —— 屏上是一条"越远越小"的尾巴。
 * 缩放绕**字形自身**的中心（不是画布原点）：否则缩小会把 z 往左上拽，
 * 与向右上的位移抵消，读起来就成了"往左下飞"（HTML 稿里踩过这个坑）。
 */
@Composable
private fun MoonGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "weather-moon")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Z_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-moon-z"
    )
    // 月亮背后那层极淡的光晕，3.4s 呼吸（不改变月亮本身的位置）
    val halo by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-moon-halo"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_moon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(Modifier.fillMaxSize()) {
            scaledUnits { drawMoonZ(progress, tint, halo) }
        }
    }
}

/** 在 24×24 坐标系里画三颗 z（错峰见 [Z_STAGGER]）。 */
private fun DrawScope.drawMoonZ(progress: Float, tint: Color, halo: Float) {
    // 光晕：先画在月亮右上，极淡
    drawCircle(
        color = tint.copy(alpha = halo),
        radius = 3.2f,
        center = Offset(17.8f, 6.6f)
    )
    repeat(3) { index ->
        // 错峰：第 index 颗比上一颗晚 0.9s
        val phase = ((progress - index * Z_STAGGER + 1f) % 1f)
        val eased = phase
        val alpha = when {
            eased < 0.12f -> eased / 0.12f
            eased > 0.65f -> ((1f - eased) / 0.35f) * 0.78f
            else -> 0.95f - (eased - 0.12f) * 0.30f
        }.coerceIn(0f, 1f)
        if (alpha <= 0.01f) return@repeat
        val scale = 1f - 0.66f * eased
        val dx = 2.7f * eased
        val dy = -4.6f * eased
        val baseX = 17.2f
        val baseY = 6.4f
        val zSize = 3f
        // 绕字形中心缩放，而不是绕画布原点
        val cx = baseX + zSize / 2f
        val cy = baseY + zSize / 2f
        fun p(x: Float, y: Float): Offset =
            Offset(cx + (x - cx) * scale + dx, cy + (y - cy) * scale + dy)

        val path = Path().apply {
            moveTo(p(baseX, baseY).x, p(baseX, baseY).y)
            val topRight = p(baseX + zSize, baseY)
            lineTo(topRight.x, topRight.y)
            val bottomLeft = p(baseX, baseY + zSize)
            lineTo(bottomLeft.x, bottomLeft.y)
            val bottomRight = p(baseX + zSize, baseY + zSize)
            lineTo(bottomRight.x, bottomRight.y)
        }
        drawPath(
            path = path,
            color = tint.copy(alpha = alpha),
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * 多云（白天）：太阳光芒 22s **慢**转（被云挡掉大半，只要一点动静），
 * 云 5.2s 横向漂 ±0.9 单位。两层独立 —— 整图一起漂会让太阳跟着晃。
 */
@Composable
private fun PartlyGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "weather-partly")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-partly-rays"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-partly-drift"
    )
    /*
     * 太阳在**左上方、压在云下面**（2026-11-15 用户指出原图太阳居中不对）。
     *
     * `ic_weather_partly` 原本把太阳画在正中央，云只盖住它的右下 —— 读起来像"云前粘了个太阳"。
     * 真实天气图标的惯例是「太阳退到左后上方，云挡掉它右下一角」：
     * 所以这里把太阳整体**缩放 0.78 + 左移 3.6 单位 + 上移 2.9 单位**，
     * 缩放是为了让光芒缩回 24 单位框内（不缩的话左移会把最外侧的光束推出边界被裁掉）。
     *
     * 绘制顺序即层级：先太阳、后云 ⇒ 云自然盖住太阳的右下角。
     */
    val unit = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / GLYPH_UNITS }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_partly_sun),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = PARTLY_SUN_SCALE
                    scaleY = PARTLY_SUN_SCALE
                    translationX = -3.6f * unit
                    translationY = -2.9f * unit
                }
        )
        Icon(
            painter = painterResource(R.drawable.ic_weather_cloud_only),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = (drift - 0.5f) * 1.8f * density }
        )
    }
}

/** 阴：只做"呼吸"（1.2% 缩放 + 0.35 单位下沉），阴天不该有方向性移动。 */
@Composable
private fun CloudGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "weather-cloud")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-cloud-breathe"
    )
    Icon(
        painter = painterResource(R.drawable.ic_weather_cloud_only),
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = 1f + 0.012f * breathe
                scaleX = scale
                scaleY = scale
                translationY = 0.35f * breathe * density
            }
    )
}

/** 雾：云不动，下方雾带 5.6s 左右扫 ±2.6 单位、透明度 0.55→1。 */
@Composable
private fun FogGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "weather-fog")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-fog-drift"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_cloud_only),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(Modifier.fillMaxSize()) {
            scaledUnits {
                val dx = (drift - 0.5f) * 5.2f
                drawLine(
                    color = tint.copy(alpha = 0.55f + 0.45f * drift),
                    start = Offset(5f + dx, 20f),
                    end = Offset(19f + dx, 20f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 雨：云完全不动，只有下方竖线错峰下落。
 *
 * 条数由 [rainDropCount] 决定（小 2 / 中 3 / 大 5）；大雨下落更快（0.85s vs 1.15s），
 * 落差固定 4.8 单位（−1.4 → +3.4）—— 24 单位的画布里约等于屏高的 1/5，
 * 能明确读出"在掉"，而不是"在抖"。
 */
@Composable
private fun RainGlyph(
    drops: Int,
    heavy: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val count = drops.coerceAtLeast(2)
    val transition = rememberInfiniteTransition(label = "weather-rain")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (heavy) 850 else 1150,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-rain-fall"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_cloud_only),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(Modifier.fillMaxSize()) {
            scaledUnits {
                repeat(count) { index ->
                    val phase = ((progress + index.toFloat() / count) % 1f)
                    val alpha = fallingAlpha(phase)
                    if (alpha <= 0.01f) return@repeat
                    val x = dropX(index, count)
                    val y = DROP_BASE_Y + (-1.4f + 4.8f * phase)
                    drawLine(
                        color = tint.copy(alpha = alpha),
                        start = Offset(x, y),
                        end = Offset(x, y + 2f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

/**
 * 雪：与雨同一套"竖直下落"（用户要求"参考雨"），只多 ±0.35 单位的轻摆与更慢的节奏。
 *
 * 片数由 [snowFlakeCount] 决定（小 2 / 中 3 / 大 5）；[fine] = 雪粒，半径取 0.7 而不是 0.9。
 */
@Composable
private fun SnowGlyph(
    flakes: Int,
    fine: Boolean,
    heavy: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val count = flakes.coerceAtLeast(2)
    val transition = rememberInfiniteTransition(label = "weather-snow")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (heavy) 1900 else 2600,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-snow-fall"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_cloud_only),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(Modifier.fillMaxSize()) {
            scaledUnits {
                repeat(count) { index ->
                    val phase = ((progress + index.toFloat() / count) % 1f)
                    val alpha = fallingAlpha(phase)
                    if (alpha <= 0.01f) return@repeat
                    val sway = 0.35f * sin(phase * 2f * PI.toFloat())
                    val x = dropX(index, count) + sway
                    val y = FLAKE_BASE_Y + (-1.6f + 5.0f * phase)
                    drawCircle(
                        color = tint.copy(alpha = alpha),
                        radius = if (fine) 0.7f else 0.9f,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

/**
 * 雷暴：闪电按 3.2s 节拍闪两下（**阶跃**，不是渐变），云同步压暗到 72%。
 * [hail] 时右侧多两颗下落的冰雹（96 / 99 与 95 的唯一区别）。
 */
@Composable
private fun StormGlyph(
    hail: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "weather-storm")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "weather-storm-flash"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_weather_cloud_only),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = stormCloudAlpha(progress) }
        )
        Icon(
            painter = painterResource(R.drawable.ic_weather_bolt),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = stormBoltAlpha(progress) }
        )
        if (hail) {
            Canvas(Modifier.fillMaxSize()) {
                scaledUnits {
                    repeat(2) { index ->
                        val phase = ((progress * 3f + index * 0.5f) % 1f)
                        val x = 17f + index * 1.6f
                        val y = 16f + 3.2f * phase
                        drawCircle(
                            color = tint.copy(alpha = (1f - phase).coerceIn(0f, 1f)),
                            radius = 0.8f,
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}

// ══════════════ 绘制辅助 ══════════════

/** 在 24×24 坐标系里绘制：把画布缩放到控件尺寸。 */
private inline fun DrawScope.scaledUnits(block: DrawScope.() -> Unit) {
    val unit = size.minDimension / GLYPH_UNITS
    scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) { block() }
}

/** 下落物体的透明度曲线：淡入 16%、淡出 80%（与 HTML 稿同一口径）。 */
private fun fallingAlpha(phase: Float): Float = when {
    phase < 0.16f -> phase / 0.16f
    phase > 0.80f -> (1f - phase) / 0.20f
    else -> 1f
}.coerceIn(0f, 1f)

/** 第 index 条雨丝 / 雪花在云下方的横坐标：均匀铺在 9.2…16.4 之间。 */
private fun dropX(index: Int, count: Int): Float {
    if (count <= 1) return 12.8f
    val start = 9.2f
    val end = 16.4f
    return start + (end - start) * index / (count - 1)
}

/** 闪电的亮度：0.62 / 0.66 / 0.70 / 0.74 四处阶跃，全是硬切（不用渐变）。 */
private fun stormBoltAlpha(progress: Float): Float = when {
    progress < 0.62f -> 0.25f
    progress < 0.66f -> 1f
    progress < 0.70f -> 0.35f
    progress < 0.74f -> 1f
    progress < 0.80f -> 0.30f
    else -> 0.25f
}

/** 云在闪电点亮时同步压暗。 */
private fun stormCloudAlpha(progress: Float): Float = when {
    progress < 0.62f -> 1f
    progress < 0.66f -> 0.72f
    progress < 0.70f -> 0.92f
    progress < 0.74f -> 0.86f
    progress < 0.80f -> 0.95f
    else -> 1f
}

/** 晴间多云里太阳的缩放：缩放后左移才不会把最外侧光芒推出 24 单位框。 */
private const val PARTLY_SUN_SCALE = 0.78f

/** 三颗 z 的周期与错峰。 */
private const val Z_PERIOD_MS = 2700
private const val Z_STAGGER = 0.9f / 2.7f

/** 雨丝 / 雪花的起落基准（24 单位坐标系）。 */
private const val DROP_BASE_Y = 14f
private const val FLAKE_BASE_Y = 15.5f

/**
 * 天气 tint：一处天气色只服务这个挂件，不进 ResolvedPalette。
 * 这些值沿用上一版 Canvas 的天气色板，深色背景下提亮、浅色背景下压暗。
 */
private fun weatherTint(weatherCode: Int, isDay: Boolean, dark: Boolean): Color = when {
    weatherCode == 0 || weatherCode == 1 -> if (isDay) {
        if (dark) Color(0xFFFFD166) else Color(0xFFF9B534)
    } else {
        if (dark) Color(0xFFCBD5F5) else Color(0xFF5B6BB5)
    }

    weatherCode == 2 || weatherCode == 3 ->
        if (dark) Color(0xFFD5DEE8) else Color(0xFF7D8DA0)

    weatherCode == 45 || weatherCode == 48 ->
        if (dark) Color(0xFFAEBAC4) else Color(0xFF7D8A93)

    weatherCode in 51..57 || weatherCode in 61..67 || weatherCode in 80..82 ->
        if (dark) Color(0xFF6FB7F0) else Color(0xFF2E86C8)

    weatherCode in 71..77 || weatherCode == 85 || weatherCode == 86 ->
        if (dark) Color(0xFFE3F4FA) else Color(0xFF5A9FB5)

    weatherCode == 95 || weatherCode == 96 || weatherCode == 99 ->
        if (dark) Color(0xFFFFC145) else Color(0xFFE08A00)

    else -> if (dark) Color(0xFFD5DEE8) else Color(0xFF7D8DA0)
}
