package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiDropdown(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalResolvedPalette.current

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = palette.iconTint
                )
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.inputBorderFocused,
                unfocusedBorderColor = palette.inputBorder,
                cursorColor = palette.accent,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.accent,
                unfocusedLabelColor = palette.textSecondary
            ),
            shape = UfiCardDefaults.inputShape
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            options.forEach { (value, displayLabel) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayLabel,
                            fontWeight = if (value == selectedValue) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (value == selectedValue) palette.accent else palette.textPrimary
                        )
                    },
                    onClick = {
                        onValueSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
