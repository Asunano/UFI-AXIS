package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    showToggle: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var visible by remember { mutableStateOf(false) }
    val palette = LocalResolvedPalette.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = keyboardOptions,
        supportingText = errorMessage?.let { { Text(it, color = palette.error) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.inputBorderFocused,
            unfocusedBorderColor = palette.inputBorder,
            cursorColor = palette.accent,
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
            focusedLabelColor = palette.accent,
            unfocusedLabelColor = palette.textSecondary,
            focusedTrailingIconColor = palette.iconTint,
            unfocusedTrailingIconColor = palette.iconTint.copy(alpha = 0.6f)
        ),
        trailingIcon = if (showToggle) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码"
                    )
                }
            }
        } else null,
        shape = UfiCardDefaults.inputShape
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        leadingIcon = leadingIcon,
        supportingText = if (errorMessage != null) {
            { Text(errorMessage, color = palette.error) }
        } else supportingText,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.inputBorderFocused,
            unfocusedBorderColor = palette.inputBorder,
            cursorColor = palette.accent,
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
            focusedLabelColor = palette.accent,
            unfocusedLabelColor = palette.textSecondary,
            focusedLeadingIconColor = palette.iconTint,
            unfocusedLeadingIconColor = palette.iconTint.copy(alpha = 0.6f),
            focusedTrailingIconColor = palette.iconTint,
            unfocusedTrailingIconColor = palette.iconTint.copy(alpha = 0.6f)
        ),
        shape = UfiCardDefaults.inputShape
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiDigitField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLength: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = errorMessage?.let { { Text(it, color = palette.error) } },
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
}

@Composable
fun UfiFieldRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiInputWithAction(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    actionEnabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
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
        Button(
            onClick = onAction,
            enabled = actionEnabled && value.isNotBlank(),
            shape = UfiCardDefaults.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.onAccent
            )
        ) {
            Text(actionText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "搜索...",
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.inputBorderFocused,
            unfocusedBorderColor = palette.inputBorder,
            cursorColor = palette.accent,
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
            focusedLeadingIconColor = palette.iconTint,
            unfocusedLeadingIconColor = palette.iconTint.copy(alpha = 0.6f),
            focusedTrailingIconColor = palette.iconTint,
            unfocusedTrailingIconColor = palette.iconTint.copy(alpha = 0.6f)
        ),
        shape = UfiCardDefaults.inputShape,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = palette.iconTint.copy(alpha = 0.6f)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = palette.iconTint.copy(alpha = 0.6f)
                    )
                }
            }
        }
    )
}
