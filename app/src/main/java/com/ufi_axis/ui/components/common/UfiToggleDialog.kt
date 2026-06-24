package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

data class ToggleItem(
    val title: String,
    val description: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

@Composable
fun UfiToggleDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    description: String? = null,
    items: List<ToggleItem>
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title
    ) {
        val palette = LocalResolvedPalette.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH)
        ) {
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = Spacing.Medium)
                )
            }

            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(Spacing.Medium))
                    HorizontalDivider(color = palette.divider.copy(alpha = 0.08f))
                    Spacer(Modifier.height(Spacing.Medium))
                }

                UfiSettingsToggle(
                    title = item.title,
                    description = item.description,
                    checked = item.checked,
                    onCheckedChange = item.onCheckedChange
                )
            }
        }
    }
}