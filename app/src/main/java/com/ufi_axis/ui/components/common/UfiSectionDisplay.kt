package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.util.FormatUtils.sanitizeUnknown

@Composable
fun UfiSectionGroupTitle(title: String, subtitle: String, error: Boolean = false) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (error) palette.error else palette.accent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) palette.error.copy(alpha = 0.7f)
                    else palette.textSecondary.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun UfiInfoRow(label: String, value: String?) {
    val palette = LocalResolvedPalette.current
    val displayValue = value?.sanitizeUnknown()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        Text(
            text = displayValue ?: "\u2014",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary
        )
    }
}

@Composable
fun UfiShimmerLoading() {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(Spacing.InnerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            Card(
                shape = UfiCardDefaults.shape,
                colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                border = UfiCardDefaults.cardBorder()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    UfiLoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }
}