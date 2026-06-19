package com.ufi_axis.ui.components.common

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class ToastType { SUCCESS, ERROR, INFO, WARNING }

data class ToastMessage(
    val text: String,
    val type: ToastType = ToastType.INFO,
    val durationMs: Long = 3000L
)

@Composable
fun UfiToastHost(
    toastMessage: ToastMessage?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = toastMessage != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        LaunchedEffect(toastMessage) {
            toastMessage?.let { delay(it.durationMs); onDismiss() }
        }

        toastMessage?.let { msg ->
            val containerColor = when (msg.type) {
                ToastType.SUCCESS -> Color(0xFF2E7D32)
                ToastType.ERROR -> Color(0xFFC62828)
                ToastType.WARNING -> Color(0xFFE65100)
                ToastType.INFO -> MaterialTheme.colorScheme.primary
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = containerColor,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = msg.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}