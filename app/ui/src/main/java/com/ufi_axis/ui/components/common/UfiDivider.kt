// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.theme.LocalResolvedPalette

@Composable
fun UfiDivider(modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    HorizontalDivider(
        modifier = modifier,
        color = palette.divider.copy(alpha = 0.08f)
    )
}