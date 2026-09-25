package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.api.ArchiveChecksumResponse
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogInfoRow
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

/**
 * 文件校验和对话框（2026-09-12）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiCustomDialog]，展示各算法（md5/sha1/sha256/sha512）
 * 的十六进制摘要，每一行右侧带「复制」按钮，点击写入系统剪贴板。
 *
 * @param visible 是否显示
 * @param result [com.ufi_axis.data.api.ArchiveChecksumResponse]（由 [com.ufi_axis.viewmodel.module.FileManagerModule.checksumFile] 写入 state）
 * @param onDismiss 关闭回调
 */
@Composable
fun ChecksumDialog(
    visible: Boolean,
    result: ArchiveChecksumResponse?,
    onDismiss: () -> Unit
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "文件校验和",
        icon = rememberVectorPainter(Icons.Filled.Fingerprint),
        showCloseButton = false
    ) {
        UfiDialogBody {
            if (result != null) {
                UfiDialogInfoRow("路径", result.path ?: "-", multiline = true)
                result.algorithms.forEach { (algo, hash) ->
                    ChecksumRow(algo = algo.uppercase(), hash = hash)
                }
            }
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = onDismiss,
            confirmText = "关闭",
            dismissText = null,
            topSpacing = Spacing.Large
        )
    }
}

@Composable
private fun ChecksumRow(algo: String, hash: String) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = algo,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.widthIn(min = 48.dp, max = 64.dp).padding(end = 8.dp)
        )
        Text(
            text = hash,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.textPrimary,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        IconButton(onClick = {
            runCatching {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(algo, hash))
            }
        }) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制 $algo 校验和",
                tint = palette.textSecondary
            )
        }
    }
}
