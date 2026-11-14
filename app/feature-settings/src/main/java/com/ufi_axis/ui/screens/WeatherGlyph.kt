package com.ufi_axis.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.ufi_axis.feature.settings.R
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 标题栏天气图标（2026-11-11）。
 *
 * ## 为什么换成 Tabler SVG
 * 上一版是 Canvas 手绘的多色图形。它比 Material 单色图标有天气感，但挂件尺寸只有
 * 34dp，细节一多就显得像 emoji：云的下缘、雨丝、太阳芒在缩放后很难保持干净。
 *
 * 现在使用 Tabler Icons 的 outline 天气图标（MIT）：
 * - 24×24、2px 圆端 stroke，34dp/44dp 下线条仍然清楚；
 * - 单一 tint 由 WMO code 决定（太阳琥珀、月亮靛蓝、雨蓝、雪青、雷暴橙）；
 * - Android 不直接渲染 SVG，`res/drawable/ic_weather_*.xml` 是由 Tabler SVG
 *   的 pathData 生成的 VectorDrawable，不引入 SVG runtime 依赖。
 *
 * 原始 SVG 与来源说明放在 `docs/mockups/tabler-weather/`。
 *
 * @param weatherCode WMO weather_code（与 open-meteo 一致）。
 * @param isDay 白天/夜间；只影响晴天和“晴间多云”的太阳/月亮选择。
 */
/** 标题栏挂件的固定尺寸；天气设置页的摘要“预览”也用这个基准。 */
internal val WEATHER_GLYPH_SIZE = 34.dp

@Composable
internal fun WeatherGlyph(
    weatherCode: Int,
    isDay: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val dark = LocalResolvedPalette.current.isDark
    val tint = weatherTint(weatherCode, isDay, dark)
    if ((weatherCode == 0 || weatherCode == 1) && isDay) {
        SunGlyph(size = size, tint = tint, modifier = modifier)
    } else {
        AnimatedWeatherIcon(
            painter = painterResource(weatherRes(weatherCode, isDay)),
            weatherCode = weatherCode,
            isDay = isDay,
            size = size,
            tint = tint,
            modifier = modifier
        )
    }
}

/**
 * 晴天：核心不转，光芒以 26s 一圈慢慢旋转；光芒本身四长四短。
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
 * 其余天气的专属微动效：
 * - 月亮：缓慢上下浮动 + 呼吸透明度
 * - 云/晴间多云：水平漂移
 * - 雾：更慢、更宽的水平漂移
 * - 雨：轻微下沉回弹
 * - 雪：缓慢摆动
 * - 雷暴：短促亮度脉冲
 */
@Composable
private fun AnimatedWeatherIcon(
    painter: Painter,
    weatherCode: Int,
    isDay: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val transition = rememberInfiniteTransition(label = "weather-motion")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (weatherCode) {
                    45, 48 -> 4400
                    2, 3 -> 3600
                    else -> 2800
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-drift"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-pulse"
    )
    val sway by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "weather-sway"
    )

    val translationX = when {
        weatherCode == 45 || weatherCode == 48 -> (drift - 0.5f) * 2.4f * density
        weatherCode == 2 || weatherCode == 3 -> (drift - 0.5f) * 1.6f * density
        else -> 0f
    }
    val translationY = when {
        // 月亮：缓慢上下浮动
        (weatherCode == 0 || weatherCode == 1) && !isDay ->
            (drift - 0.5f) * 1.4f * density
        weatherCode in 51..57 || weatherCode in 61..67 || weatherCode in 80..82 ->
            (1f - drift) * 1.2f * density
        else -> 0f
    }
    val rotation = when (weatherCode) {
        in 71..77, 85, 86 -> sway * 4f
        else -> 0f
    }
    val alpha = when {
        weatherCode == 95 || weatherCode == 96 || weatherCode == 99 ->
            0.62f + pulse * 0.38f
        weatherCode == 45 || weatherCode == 48 ->
            0.72f + pulse * 0.28f
        // 月亮：呼吸透明度
        (weatherCode == 0 || weatherCode == 1) && !isDay ->
            0.84f + pulse * 0.16f
        else -> 1f
    }

    Icon(
        painter = painter,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                this.translationX = translationX
                this.translationY = translationY
                this.rotationZ = rotation
                this.alpha = alpha
            }
    )
}

/** WMO code → Tabler 天气图标资源。 */
private fun weatherRes(weatherCode: Int, isDay: Boolean): Int = when {
    weatherCode == 0 || weatherCode == 1 ->
        if (isDay) R.drawable.ic_weather_sun else R.drawable.ic_weather_moon

    // 2 = 晴间多云。夜间用云，避免把太阳画到月亮旁边。
    weatherCode == 2 ->
        if (isDay) R.drawable.ic_weather_partly else R.drawable.ic_weather_cloud

    weatherCode == 3 -> R.drawable.ic_weather_cloud
    weatherCode == 45 || weatherCode == 48 -> R.drawable.ic_weather_cloud_fog
    weatherCode in 51..57 -> R.drawable.ic_weather_cloud_rain
    weatherCode in 61..67 || weatherCode in 80..82 -> R.drawable.ic_weather_cloud_rain
    weatherCode in 71..77 || weatherCode == 85 || weatherCode == 86 ->
        R.drawable.ic_weather_cloud_snow
    weatherCode == 95 || weatherCode == 96 || weatherCode == 99 ->
        R.drawable.ic_weather_cloud_storm
    else -> R.drawable.ic_weather_cloud
}

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
