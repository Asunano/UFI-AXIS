package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * Themed slider with optional label and value display.
 */
@Composable
fun UfiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    label: String? = null,
    valueLabel: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null || valueLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (valueLabel != null) {
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.accent.copy(alpha = 0.15f),
                disabledThumbColor = palette.accent.copy(alpha = 0.4f),
                disabledActiveTrackColor = palette.accent.copy(alpha = 0.2f),
                disabledInactiveTrackColor = palette.accent.copy(alpha = 0.08f)
            )
        )
    }
}
