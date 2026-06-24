package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

/**
 * 统一页面背景 — 滚动容器。
 *
 * - 纯色背景：使用主题 pageBg
 * - 渐变背景：accent→pageBg 微渐变（Dashboard 等主页）
 * - 水平内边距默认 0（因为 UfiSettingsGroup 卡片自带 CardHorizontalMargin）。
 *   对使用自定义 fillMaxWidth 卡片的页面（如 Dashboard），传入 `contentHPadding`
 */
@Composable
fun UfiPageBackground(
    modifier: Modifier = Modifier,
    useGradient: Boolean = false,
    gradientTopColor: Color? = null,
    gradientBottomColor: Color? = null,
    contentHPadding: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val palette = LocalResolvedPalette.current

    val bgModifier = if (useGradient) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(
                    gradientTopColor ?: palette.accent.copy(alpha = 0.05f),
                    gradientBottomColor ?: palette.pageBg
                )
            )
        )
    } else {
        Modifier.background(palette.pageBg)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(bgModifier)
            .padding(horizontal = contentHPadding)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin),
    ) {
        content()
    }
}

/**
 * 统一页面背景 — 非滚动 Box 容器（LazyColumn 等）。
 */
@Composable
fun UfiPageBackgroundBox(
    modifier: Modifier = Modifier,
    useGradient: Boolean = false,
    gradientTopColor: Color? = null,
    gradientBottomColor: Color? = null,
    contentHPadding: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val palette = LocalResolvedPalette.current

    val bg = if (useGradient) {
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    gradientTopColor ?: palette.accent.copy(alpha = 0.05f),
                    gradientBottomColor ?: palette.pageBg
                )
            )
        )
    } else {
        Modifier.fillMaxSize().background(palette.pageBg)
    }

    Box(modifier = modifier.then(bg).padding(horizontal = contentHPadding)) {
        content()
    }
}
