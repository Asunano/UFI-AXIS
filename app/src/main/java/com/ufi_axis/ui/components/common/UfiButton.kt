package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
fun UfiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "btnScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(Spacing.ButtonHeight).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled && !loading,
        shape = UfiCardDefaults.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.onAccent,
            disabledContainerColor = palette.accent.copy(alpha = 0.4f),
            disabledContentColor = palette.onAccent.copy(alpha = 0.6f)
        ),
        interactionSource = interactionSource
    ) {
        if (loading) {
            UfiLoadingIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2f
            )
            Spacer(Modifier.width(Spacing.Small))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UfiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "btnScale"
    )

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(Spacing.ButtonHeight).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = UfiCardDefaults.buttonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = palette.accent
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.accent),
        interactionSource = interactionSource
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UfiSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "btnScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(Spacing.SmallButtonHeight).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = UfiCardDefaults.buttonShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.onAccent,
            disabledContainerColor = palette.accent.copy(alpha = 0.4f),
            disabledContentColor = palette.onAccent.copy(alpha = 0.6f)
        ),
        interactionSource = interactionSource
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UfiDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "btnScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(Spacing.ButtonHeight).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = UfiCardDefaults.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.error,
            contentColor = Color.White,
            disabledContainerColor = palette.error.copy(alpha = 0.3f)
        ),
        interactionSource = interactionSource
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UfiButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        content = content
    )
}
