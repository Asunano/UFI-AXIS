package com.ufi_axis.ui.components.common

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * Animated number display — smoothly transitions between numeric values.
 * Uses a cross-fade + scale animation for value changes.
 */
@Composable
fun UfiAnimatedNumber(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.SemiBold,
    prefix: String = "",
    suffix: String = ""
) {
    val palette = LocalResolvedPalette.current
    val resolvedColor = if (color == Color.Unspecified) palette.textPrimary else color

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix.isNotEmpty()) {
            Text(
                text = prefix,
                style = style,
                fontWeight = fontWeight,
                color = resolvedColor
            )
        }

        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(300))
            },
            label = "animatedNumber"
        ) { targetValue ->
            Text(
                text = targetValue,
                style = style,
                fontWeight = fontWeight,
                color = resolvedColor
            )
        }

        if (suffix.isNotEmpty()) {
            Text(
                text = suffix,
                style = style,
                fontWeight = fontWeight,
                color = resolvedColor
            )
        }
    }
}

/**
 * Animated float counter — interpolates between old and new float values.
 */
@Composable
fun UfiAnimatedCounter(
    value: Float,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.SemiBold,
    decimals: Int = 1,
    suffix: String = ""
) {
    val palette = LocalResolvedPalette.current
    val resolvedColor = if (color == Color.Unspecified) palette.textPrimary else color

    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "counter"
    )

    val displayText = "%.${decimals}f".format(animatedValue) + suffix

    Text(
        text = displayText,
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = resolvedColor
    )
}

/**
 * Animated integer counter — interpolates between old and new int values.
 */
@Composable
fun UfiAnimatedIntCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.SemiBold,
    suffix: String = ""
) {
    val palette = LocalResolvedPalette.current
    val resolvedColor = if (color == Color.Unspecified) palette.textPrimary else color

    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "intCounter"
    )

    Text(
        text = "$animatedValue$suffix",
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = resolvedColor
    )
}
