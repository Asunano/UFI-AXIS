package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * Themed checkbox with label and optional description.
 */
@Composable
fun UfiCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.accent,
                uncheckedColor = palette.textSecondary,
                checkmarkColor = palette.onAccent,
                disabledCheckedColor = palette.accent.copy(alpha = 0.4f),
                disabledUncheckedColor = palette.textSecondary.copy(alpha = 0.4f)
            )
        )
        if (label != null || description != null) {
            Spacer(Modifier.width(8.dp))
            Column {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * Themed checkbox with animated scale on toggle.
 */
@Composable
fun UfiAnimatedCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.85f,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.5f),
        label = "checkboxScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.textSecondary,
                    checkmarkColor = palette.onAccent
                )
            )
        }
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
