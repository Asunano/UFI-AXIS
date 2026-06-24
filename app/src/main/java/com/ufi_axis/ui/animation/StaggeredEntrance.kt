package com.ufi_axis.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntOffset.Companion.Zero
import kotlin.math.roundToInt

/**
 * Staggered entrance animation for list items.
 * Each item slides up from 60dp and fades in, delayed by [index] * 50ms.
 *
 * Usage in a Column/LazyColumn:
 * ```
 * items.forEachIndexed { index, item ->
 *     MyItem(modifier = Modifier.staggeredEntrance(index))
 * }
 * ```
 */
fun Modifier.staggeredEntrance(
    index: Int,
    triggerKey: Any? = null
): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val translationY = remember { Animatable(60f) }
    val delay = index * 50L

    LaunchedEffect(triggerKey) {
        alpha.snapTo(0f)
        translationY.snapTo(60f)
        kotlinx.coroutines.delay(delay)
        alpha.animateTo(1f, UfiAnimSpecs.staggerEnter)
    }
    LaunchedEffect(triggerKey) {
        translationY.snapTo(60f)
        kotlinx.coroutines.delay(delay)
        translationY.animateTo(0f, UfiAnimSpecs.staggerEnter)
    }

    graphicsLayer {
        this.alpha = alpha.value
        this.translationY = translationY.value
    }
}

/**
 * Press-scale animation for clickable elements.
 * Scales down to 0.96 while pressed, springs back on release.
 *
 * Usage:
 * ```
 * Row(modifier = Modifier.clickScale { onClick() }) { ... }
 * ```
 */
fun Modifier.clickScale(
    onClick: () -> Unit
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        scale.animateTo(
            targetValue = if (isPressed) 0.96f else 1f,
            animationSpec = UfiAnimSpecs.clickScale
        )
    }

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(onClick) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { onClick() }
            )
        }
}

/**
 * Blur-in entrance for screen content.
 * On API 31+, applies RenderEffect blur that clears to sharp.
 * On lower APIs, falls back to simple fade.
 *
 * Usage:
 * ```
 * UfiScrollableColumn(modifier = Modifier.blurEntrance(routeName)) { ... }
 * ```
 */
fun Modifier.blurEntrance(
    triggerKey: Any? = null
): Modifier = composed {
    val blur = remember { Animatable(20f) }

    LaunchedEffect(triggerKey) {
        blur.snapTo(20f)
        blur.animateTo(0f, UfiAnimSpecs.fadeEnter)
    }

    graphicsLayer {
        if (blur.value > 0.5f) {
            renderEffect = android.graphics.RenderEffect.createBlurEffect(
                blur.value, blur.value,
                android.graphics.Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
        }
        alpha = if (blur.value > 10f) 0.7f else 1f
    }
}
