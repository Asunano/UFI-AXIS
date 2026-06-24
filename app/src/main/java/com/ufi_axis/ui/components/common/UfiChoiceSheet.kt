package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = UfiCardDefaults.bottomSheetTopShape,
        containerColor = palette.cardBg
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary,
            modifier = Modifier.padding(
                horizontal = Spacing.DialogPaddingH,
                vertical = Spacing.Medium
            )
        )
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Spacing.DialogPaddingH,
                        vertical = Spacing.Medium
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = value == selectedValue,
                    onClick = { onSelect(value); onDismiss() },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = palette.accent,
                        unselectedColor = palette.textSecondary
                    )
                )
                Spacer(Modifier.width(Spacing.Medium))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary
                )
            }
        }
        Spacer(Modifier.height(Spacing.Large))
    }
}