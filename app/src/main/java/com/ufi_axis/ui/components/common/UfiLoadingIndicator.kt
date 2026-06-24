package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import kotlin.math.PI
import kotlin.math.sin

/**
 * A Canvas-based breathing arc loading indicator.
 *
 * Draws a full-circle track at 15% alpha of the accent color, with a rotating
 * arc whose sweep angle oscillates between 270 and 330 degrees to produce a
 * "breathing" effect. Completes one full rotation per second.
 */
@Composable
fun UfiLoadingIndicator(
    modifier: Modifier = Modifier.size(40.dp),
    strokeWidth: Float = 3f
) {
    val palette = LocalResolvedPalette.current
    val accentColor = palette.accent
    val trackColor = accentColor.copy(alpha = 0.15f)

    // Rotation animation: 0 -> 360 degrees, 1000ms per revolution, linear, infinite
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Breathing sweep: 300 +/- 30 degrees (oscillates between 270 and 330)
    val sweepAngle = 300f + 30f * sin((rotation * PI / 180.0)).toFloat()

    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.dp.toPx()
        val diameter = size.minDimension - strokeWidthPx
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)

        // Track: full circle ring
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )

        // Arc: rotating with breathing sweep
        drawArc(
            color = accentColor,
            startAngle = rotation,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )
    }
}
