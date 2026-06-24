package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette

@Composable
fun UfiStatItem(
    value: String,
    label: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val resolvedColor = if (valueColor == Color.Unspecified) palette.textPrimary else valueColor
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = resolvedColor
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}