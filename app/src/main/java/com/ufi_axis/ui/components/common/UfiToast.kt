package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ToastType { SUCCESS, ERROR, INFO, WARNING }

data class ToastMessage(
    val text: String,
    val type: ToastType = ToastType.INFO,
    val durationMs: Long = 3000L
)

@Composable
fun UfiToastHost(
    toastMessage: ToastMessage?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    if (toastMessage == null) return
    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current.density

    // Three-phase water-drop animation
    val dropOffsetY = remember(toastMessage) { Animatable(-200f) }
    val rippleOffsetY = remember(toastMessage) { Animatable(0f) }
    val exitAlpha = remember(toastMessage) { Animatable(1f) }
    val exitOffsetY = remember(toastMessage) { Animatable(0f) }

    LaunchedEffect(toastMessage) {
        // Phase 0: Drop-in (600ms, overshoot)
        dropOffsetY.snapTo(-200f)
        exitAlpha.snapTo(1f)
        exitOffsetY.snapTo(0f)
        dropOffsetY.animateTo(
            targetValue = 0f,
            animationSpec = tween(600, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f))
        )

        // Phase 1: Push down 5dp (180ms, decelerate)
        rippleOffsetY.snapTo(0f)
        rippleOffsetY.animateTo(5f, tween(180, easing = FastOutSlowInEasing))

        // Phase 2: Rebound up to -6dp (250ms, strong decelerate)
        rippleOffsetY.animateTo(-6f, tween(250, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)))

        // Phase 3: Settle down to +2.4dp then back to 0 (180ms each)
        rippleOffsetY.animateTo(2.4f, tween(180, easing = FastOutSlowInEasing))
        rippleOffsetY.animateTo(0f, tween(180, easing = FastOutSlowInEasing))

        // Wait for remaining duration
        delay(toastMessage.durationMs)

        // Exit: fade out + slide up 16dp (250ms, accelerate)
        launch {
            exitAlpha.animateTo(0f, tween(250, easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)))
        }
        exitOffsetY.animateTo(-16f, tween(250, easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)))
        onDismiss()
    }

    val icon = when (toastMessage.type) {
        ToastType.SUCCESS -> Icons.Default.CheckCircle
        ToastType.ERROR -> Icons.Default.Error
        ToastType.WARNING -> Icons.Default.Warning
        ToastType.INFO -> Icons.Default.Info
    }
    val iconColor = when (toastMessage.type) {
        ToastType.SUCCESS -> palette.success
        ToastType.ERROR -> palette.error
        ToastType.WARNING -> palette.warning
        ToastType.INFO -> palette.accent
    }

    // Ripple ring scale based on bounce offset
    val rippleScale = 1f + abs(rippleOffsetY.value) * 0.008f
    val rippleAlpha = (0.12f * (1f - abs(rippleOffsetY.value) / 10f)).coerceAtLeast(0f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Ripple ring behind card
        Box(
            modifier = Modifier
                .padding(top = 60.dp)
                .size(width = 300.dp, height = 56.dp)
                .clip(UfiCardDefaults.toastShape)
                .background(palette.accent.copy(alpha = rippleAlpha))
                .scale(rippleScale)
        )

        // Toast card
        val totalOffsetY = dropOffsetY.value + rippleOffsetY.value + exitOffsetY.value
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(top = 60.dp)
                .offset { IntOffset(0, (totalOffsetY * density).roundToInt()) }
                .graphicsLayer { alpha = exitAlpha.value },
            shape = UfiCardDefaults.toastShape,
            color = palette.cardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.toastBorder),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = Spacing.ToastPaddingH,
                    vertical = Spacing.ToastPaddingV
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(Spacing.IconSizeLarge)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = toastMessage.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary
                )
            }
        }
    }
}
