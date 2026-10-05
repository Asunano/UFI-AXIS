// 2026-10-05 P7：进行中操作弹窗（app 端唯一一份）。消费 ApiOperationController.UiState。
// 复用 UfiDialogShell（P0 修复后 scrim 生效）：running 显示"后台进行"，failed 显示"关闭/重试"，
// success 无按钮自动关。按钮区不复用 DialogButtonRow（它把 onConfirm 包进 close 时序，
// 语义不符）——这里自行排布，点击直接调控制器方法，弹窗关闭由状态机驱动而非按钮驱动。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.module.ApiOperationController

@Composable
fun UfiOperationProgressDialog(
    state: ApiOperationController.UiState,
    onBackground: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    if (state.kind == ApiOperationController.Kind.IDLE ||
        state.kind == ApiOperationController.Kind.HIDDEN
    ) return

    val palette = LocalResolvedPalette.current

    UfiDialogShell(
        visible = true,
        onDismiss = onDismiss,
        dismissOnClickOutside = false,      // 进行中不许误触关闭
        dismissOnBackPress = state.kind != ApiOperationController.Kind.RUNNING,
        scrimAlpha = 0.5f,
        title = state.title,
    ) {
        UfiDialogBody {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 圆环：running 用 CircularProgressIndicator（M3 依赖已有），
                // success/failed 换成对勾/叉图标，尺寸统一 52dp
                if (state.kind == ApiOperationController.Kind.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.size(52.dp))
                } else {
                    Icon(
                        imageVector = if (state.kind == ApiOperationController.Kind.SUCCESS)
                            Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (state.kind == ApiOperationController.Kind.SUCCESS)
                            palette.success else palette.error,
                        modifier = Modifier.size(52.dp)
                    )
                }
                Spacer(Modifier.height(Spacing.Large))
                Text(
                    when (state.kind) {
                        ApiOperationController.Kind.RUNNING -> state.runningText
                        ApiOperationController.Kind.SUCCESS -> state.successText
                        else -> state.failReason ?: "${state.title}未完成"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                state.hint?.takeIf { state.kind == ApiOperationController.Kind.RUNNING }?.let {
                    Spacer(Modifier.height(Spacing.Small))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Large),
            horizontalArrangement = Arrangement.End
        ) {
            when (state.kind) {
                ApiOperationController.Kind.RUNNING -> TextButton(onClick = onBackground) { Text("后台进行") }
                ApiOperationController.Kind.FAILED -> {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    onRetry?.let { Button(onClick = it) { Text("重试") } }
                }
                else -> {}   // SUCCESS 无按钮
            }
        }
    }
}
