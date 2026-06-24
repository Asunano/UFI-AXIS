package com.ufi_axis.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

@Composable
fun UfiSettingsItem(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val mod = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
    }
    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun UfiSettingsToggle(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsItem(
        title = title,
        description = description,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.switchThumbOn,
                    checkedTrackColor = palette.switchTrackOn,
                    uncheckedThumbColor = palette.switchThumbOff,
                    uncheckedTrackColor = palette.switchTrackOff
                )
            )
        }
    )
}

@Composable
fun UfiSettingsValue(
    title: String,
    description: String? = null,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsItem(
        title = title,
        description = description,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                if (onClick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.textSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(Spacing.IconSizeSmall)
                    )
                }
            }
        }
    )
}