package com.ufi_axis.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.ufi_axis.ui.components.common.UfiAlertDialog

@Composable
fun UpdateDialog(
    serverVersion: String,
    updateUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    UfiAlertDialog(
        title = "发现新版本",
        text = "发现新版本 v$serverVersion，是否前往下载？",
        confirmText = "前往下载",
        onConfirm = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
            context.startActivity(intent)
            onDismiss()
        },
        dismissText = "稍后",
        onDismissAction = onDismiss,
        onDismiss = onDismiss
    )
}