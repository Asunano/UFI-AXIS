package com.ufi_axis.ui.animation

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/**
 * Animated text with two-phase blur transition.
 * Phase 1 (260ms): blur 0→10 (text becomes blurry)
 * At peak blur: swap to new text content
 * Phase 2 (320ms): blur 10→0 (new text becomes clear)
 *
 * On API 31+, uses RenderEffect blur. On lower APIs, falls back to alpha + scale crossfade.
 */
@Composable
fun AnimatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE
) {
    var displayText by remember { mutableStateOf(text) }
    val blurRadius = remember { Animatable(0f) }
    val fadeAlpha = remember { Animatable(1f) }
    val fadeScale = remember { Animatable(1f) }
    val isApi31Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(text) {
        if (text != displayText) {
            if (isApi31Plus) {
                // Phase 1: Blur out old text (0 → 10, 260ms)
                blurRadius.snapTo(0f)
                blurRadius.animateTo(10f, tween(260))

                // At peak blur: swap text
                displayText = text

                // Phase 2: Unblur new text (10 → 0, 320ms)
                blurRadius.animateTo(0f, tween(320))
            } else {
                // Fallback: alpha + scale crossfade
                fadeAlpha.animateTo(0.3f, tween(260))
                fadeScale.animateTo(0.97f, tween(260))
                displayText = text
                fadeAlpha.animateTo(1f, tween(320))
                fadeScale.animateTo(1f, tween(320))
            }
        }
    }

    Text(
        text = displayText,
        modifier = modifier.graphicsLayer {
            val radius = blurRadius.value
            if (radius > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                    radius, radius,
                    android.graphics.Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
            if (!isApi31Plus) {
                alpha = fadeAlpha.value
                scaleX = fadeScale.value
                scaleY = fadeScale.value
            }
        },
        style = style,
        color = color,
        maxLines = maxLines
    )
}
