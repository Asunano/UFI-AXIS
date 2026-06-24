package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
fun UfiAlertDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    onConfirm: () -> Unit = onDismiss,
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    UfiDialogShell(visible = true, onDismiss = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
                .padding(top = Spacing.DialogPaddingV)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
                .padding(top = Spacing.Medium, bottom = Spacing.Medium)
        )
        DialogButtonRow(
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismissAction ?: onDismiss
        )
    }
}

@Composable
fun UfiConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    UfiDialogShell(visible = true, onDismiss = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
                .padding(top = Spacing.DialogPaddingV)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
                .padding(top = Spacing.Medium, bottom = Spacing.Medium)
        )
        DialogButtonRow(
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismiss,
            confirmColor = if (destructive) palette.error else palette.accent
        )
    }
}

@Composable
fun UfiInputDialog(
    title: String,
    initialValue: String = "",
    hint: String = "",
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validator: ((String) -> String?)? = null
) {
    var text by remember { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    val palette = LocalResolvedPalette.current

    UfiDialogShell(visible = true, onDismiss = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)
                .padding(top = Spacing.DialogPaddingV)
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            placeholder = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
            singleLine = true,
            isError = error != null,
            supportingText = error?.let {
                { Text(it, color = palette.error) }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.inputBorderFocused,
                unfocusedBorderColor = palette.inputBorder,
                cursorColor = palette.accent,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary
            ),
            shape = UfiCardDefaults.inputShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH, vertical = Spacing.Medium)
        )
        DialogButtonRow(
            confirmText = confirmText,
            onConfirm = {
                val validationError = validator?.invoke(text)
                if (validationError != null) {
                    error = validationError
                } else {
                    onConfirm(text)
                }
            },
            dismissText = dismissText,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun UfiLoadingDialog(
    text: String = "处理中..."
) {
    val palette = LocalResolvedPalette.current
    UfiDialogShell(
        visible = true,
        onDismiss = {},
        dismissOnClickOutside = false,
        dismissOnBackPress = false
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Spacing.DialogPaddingH,
                    vertical = Spacing.DialogPaddingV
                ),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            UfiLoadingIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2f
            )
            Spacer(Modifier.width(Spacing.Medium))
            Text(text, color = palette.textSecondary)
        }
    }
}