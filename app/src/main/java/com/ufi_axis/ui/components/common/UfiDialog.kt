package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ufi_axis.ui.theme.Spacing

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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Spacing.CardCorner),
        title = {
            Text(title, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (dismissText != null && onDismissAction != null) {
                TextButton(onClick = onDismissAction) {
                    Text(dismissText)
                }
            }
        }
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Spacing.CardCorner),
        title = {
            Text(title, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    fontWeight = FontWeight.SemiBold,
                    color = if (destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Spacing.CardCorner),
        title = {
            Text(title, fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                placeholder = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val validationError = validator?.invoke(text)
                if (validationError != null) {
                    error = validationError
                } else {
                    onConfirm(text)
                }
            }) {
                Text(confirmText, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.InnerPadding, vertical = Spacing.Medium)
        )
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.InnerPadding, vertical = Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = value == selectedValue,
                    onClick = { onSelect(value); onDismiss() }
                )
                Spacer(Modifier.width(Spacing.Medium))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(Spacing.Large))
    }
}

@Composable
fun UfiLoadingDialog(
    text: String = "处理中..."
) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(Spacing.CardCorner),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.Medium))
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}