package com.ufi_axis.ui.components.common

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

// ─────────────────────────────────────────────────
// Internal dialog shell — animated card + scrim
// ─────────────────────────────────────────────────

@Composable
internal fun UfiDialogShell(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!visible) return
    val palette = LocalResolvedPalette.current

    // Backdrop blur animation (API 31+)
    val backdropBlur = remember { Animatable(0f) }
    val backdropAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        backdropBlur.animateTo(15f, tween(480))
        backdropAlpha.animateTo(1f, tween(480))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Blurred backdrop scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val radius = backdropBlur.value
                            if (radius > 0.5f) {
                                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                    radius, radius,
                                    android.graphics.Shader.TileMode.CLAMP
                                ).asComposeRenderEffect()
                            }
                        }
                        alpha = backdropAlpha.value
                    }
                    .background(Color.Black.copy(alpha = 0.08f))
            )

            AnimatedVisibility(
                visible = true,
                enter = scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f)
                ) + fadeIn(animationSpec = tween(480)),
                exit = scaleOut(
                    targetScale = 0.88f,
                    animationSpec = tween(350)
                ) + fadeOut(animationSpec = tween(350))
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    shape = UfiCardDefaults.dialogShape,
                    colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                    border = BorderStroke(1.dp, palette.dialogBorder)
                ) {
                    content()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Internal reusable button row inside dialog
// ─────────────────────────────────────────────────

@Composable
internal fun DialogButtonRow(
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmColor: Color = LocalResolvedPalette.current.accent,
    fontWeight: FontWeight = FontWeight.Bold,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    val palette = LocalResolvedPalette.current
    HorizontalDivider(
        color = palette.divider.copy(alpha = 0.08f),
        modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.DialogPaddingH, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText != null && onDismiss != null) {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = palette.textSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
        }
        TextButton(onClick = onConfirm) {
            Text(confirmText, fontWeight = fontWeight, color = confirmColor, fontSize = fontSize)
        }
    }
}