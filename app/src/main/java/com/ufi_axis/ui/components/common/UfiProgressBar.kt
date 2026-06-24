package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette

@Composable
fun UfiProgressBar(
    progress: Float,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = LocalResolvedPalette.current.accent,
    trackColor: Color = LocalResolvedPalette.current.divider.copy(alpha = 0.15f)
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = color)
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = color,
            trackColor = trackColor,
        )
    }
}

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
