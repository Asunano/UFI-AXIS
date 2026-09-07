// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette

@Composable
fun UfiCompactProgressBar(
    progress: Float,
    color: Color = LocalResolvedPalette.current.accent,
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(3.dp),
        color = color,
        trackColor = LocalResolvedPalette.current.divider.copy(alpha = 0.15f),
    )
}
