package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

@Composable
fun UfiCustomDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    showCloseButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissOnClickOutside,
        dismissOnBackPress = dismissOnBackPress
    ) {
        if (title != null) {
            val palette = LocalResolvedPalette.current
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                modifier = Modifier
                    .padding(horizontal = Spacing.DialogPaddingH)
                    .padding(top = Spacing.DialogPaddingV)
            )
        }
        content()
        if (showCloseButton) {
            val palette = LocalResolvedPalette.current
            Spacer(Modifier.height(Spacing.Medium))
            Row(Modifier.fillMaxWidth().padding(end = Spacing.DialogPaddingH), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭", color = palette.textSecondary) }
            }
        }
    }
}

@Composable
fun UfiScrollableDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    showCloseButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissOnClickOutside,
        dismissOnBackPress = dismissOnBackPress
    ) {
        if (title != null) {
            val palette = LocalResolvedPalette.current
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                modifier = Modifier
                    .padding(horizontal = Spacing.DialogPaddingH)
                    .padding(top = Spacing.DialogPaddingV)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.Medium)
        ) {
            content()
        }
        if (showCloseButton) {
            val palette = LocalResolvedPalette.current
            Spacer(Modifier.height(Spacing.Medium))
            Row(Modifier.fillMaxWidth().padding(end = Spacing.DialogPaddingH), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭", color = palette.textSecondary) }
            }
        }
    }
}