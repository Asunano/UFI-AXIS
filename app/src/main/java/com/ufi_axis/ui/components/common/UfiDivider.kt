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
        color = palette.divider.copy(alpha = 0.12f)
    )
}