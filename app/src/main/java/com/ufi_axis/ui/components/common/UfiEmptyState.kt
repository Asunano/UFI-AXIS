package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

@Composable
fun UfiEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = palette.textSecondary.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(Spacing.Medium))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
    }
}