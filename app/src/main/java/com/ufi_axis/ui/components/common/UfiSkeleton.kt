package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
private fun shimmerBrush(): Brush {
    val palette = LocalResolvedPalette.current
    val baseColor = palette.divider.copy(alpha = 0.15f)
    val highlightColor = palette.divider.copy(alpha = 0.35f)

    val translateAnim by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim)
    )
}

@Composable
fun UfiSkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    widthFraction: Float = 1f,
    cornerRadius: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush())
    )
}

@Composable
fun UfiSkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

@Composable
fun UfiSkeletonCard(
    modifier: Modifier = Modifier,
    lineCount: Int = 3,
    showAvatar: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = UfiCardDefaults.horizontalMargin,
                end = UfiCardDefaults.horizontalMargin,
                bottom = UfiCardDefaults.padding
            ),
        shape = UfiCardDefaults.shape,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardBorder()
    ) {
        Row(
            modifier = Modifier.padding(UfiCardDefaults.padding),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showAvatar) {
                UfiSkeletonCircle(size = 40.dp)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(lineCount) { index ->
                    val fraction = if (index == lineCount - 1) 0.6f else 1f
                    UfiSkeletonLine(widthFraction = fraction)
                }
            }
        }
    }
}

@Composable
fun UfiSkeletonGroup(
    modifier: Modifier = Modifier,
    cardCount: Int = 3,
    linesPerCard: Int = 3
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(cardCount) {
            UfiSkeletonCard(lineCount = linesPerCard)
        }
    }
}
