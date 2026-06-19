package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.Spacing

@Composable
fun UfiSingleChipSelector(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    wrapContent: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selectedValue,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = if (wrapContent) Modifier else Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun UfiMultiChipSelector(
    options: List<Pair<String, String>>,
    selectedValues: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value in selectedValues,
                onClick = { onToggle(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
fun UfiIntChipSelector(
    values: List<Int>,
    selectedValues: Set<Int>,
    onToggle: (Int) -> Unit,
    prefix: String = "",
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        values.forEach { value ->
            val label = "$prefix$value"
            FilterChip(
                selected = value in selectedValues,
                onClick = { onToggle(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}