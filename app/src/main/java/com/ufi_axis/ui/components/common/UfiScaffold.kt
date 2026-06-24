package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * Custom header bar matching UFITOOLSWidget style:
 * Centered title (28sp bold) + optional subtitle, back button on the left, actions on the right.
 */
@Composable
fun UfiHeader(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val palette = LocalResolvedPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Spacing.HeaderHeight)
            .padding(horizontal = Spacing.HeaderPaddingH)
    ) {
        // Back button (left)
        if (showBack && onBack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clip(UfiCardDefaults.legacyShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Centered title + subtitle
        val titleBlur = remember { Animatable(8f) }
        LaunchedEffect(title) {
            titleBlur.snapTo(8f)
            titleBlur.animateTo(0f, tween(350))
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = Spacing.HeaderTitleSize,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                letterSpacing = 0.56.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val r = titleBlur.value
                    if (r > 0.5f) {
                        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                            r, r, android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                    alpha = if (r > 4f) 0.6f else 1f
                }
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = Spacing.HeaderSubtitleSize,
                    color = palette.textPrimary.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Actions (right)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = actions
        )
    }
}

/**
 * Screen scaffold with custom UfiHeader (no TopAppBar).
 * Public API unchanged for backward compatibility with all screens.
 */
@Composable
fun UfiScreenScaffold(
    title: String,
    navController: NavHostController? = null,
    showBack: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val palette = LocalResolvedPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Custom header
        UfiHeader(
            title = title,
            showBack = showBack,
            onBack = if (showBack && navController != null) {
                { navController.popBackStack() }
            } else null,
            actions = actions
        )

        // Content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets(0))
        ) {
            content(PaddingValues(0.dp))
        }
    }
}

@Composable
@Deprecated(
    message = "Use UfiPageBackground (import com.ufi_axis.ui.components.common.UfiPageBackground) instead",
    replaceWith = ReplaceWith(
        "UfiPageBackground(modifier = modifier, contentHPadding = 0.dp, content = content)",
        "com.ufi_axis.ui.components.common.UfiPageBackground"
    ),
    level = DeprecationLevel.WARNING
)
fun UfiScrollableColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.CardBottomMargin),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

@Composable
fun UfiLoadingBox(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    content: @Composable () -> Unit
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            UfiLoadingIndicator()
        }
    } else {
        content()
    }
}

@Composable
fun UfiLinearLoading(
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = LocalResolvedPalette.current.accent
        )
    }
}
