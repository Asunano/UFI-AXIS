package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 「主人密码」输入弹窗（破坏性操作保护）。
 *
 * ## 什么时候会出现
 *
 * 只在 core 的 `OwnerGate` 开关**打开**时：不可逆操作（恢复出厂 / 恢复备份 / root shell /
 * 自定义 shell 任务 / 删全部日志 / 移除配对设备 / 解封黑名单）会先回 `401 MISSING_PASSWORD`，
 * `OwnerPasswordGate` 把它桥到这里。默认配置下这个弹窗永远不会出现。
 *
 * ## 为什么是**纯 UI** 组件（只吃字符串与两个回调）
 *
 * 它挂在 App 根部（`MainActivity`）对所有页面生效，而 `:app:ui` 不依赖 `:app:data`
 * 的 `OwnerPasswordGate` —— 与 [UfiPairingApprovalDialog] 同一条边界：
 * 桥接层在根部把状态翻译成参数，UI 组件只认参数。
 *
 * ## 关闭 = 取消
 *
 * 关掉弹窗不能"稍后再说"：拦截器正阻塞在 OkHttp 的网络线程上等一个答复，
 * 用户不答就只能等到超时。所以 `onDismiss` 与「取消」同义，调用方拿回原始 401。
 *
 * @param hint 一句话说明为什么突然要密码（来自 core 的 `error` 或本地默认文案）
 * @param onConfirm 用户点了确认：参数是输入的密码 + 是否勾选"5 分钟内不再询问"
 * @param onCancel 用户取消 / 关闭弹窗
 */
@Composable
fun UfiOwnerPasswordDialog(
    hint: String,
    onConfirm: (password: String, remember: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    var password by remember { mutableStateOf("") }
    // 默认**不**记住：保护默认每次都问，少数"连着做几件事"的场景由用户自己勾。
    var remember by remember { mutableStateOf(false) }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onCancel,
        title = "需要配对密码",
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                onDismiss = onCancel,
                onConfirm = { onConfirm(password, remember) },
                confirmText = "确认",
                dismissText = "取消",
                enabled = password.isNotBlank()
            )
        }
    ) {
        // 纵向节奏只来自 UfiDialogBody 的 spacedBy(Spacing.Large)：
        // 这里再叠 Spacer 会把「标题 → 说明 → 输入框」撑成三段大空白（2026-11-14 修）。
        UfiDialogBody {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            UfiPasswordField(
                value = password,
                onValueChange = { password = it },
                label = "配对密码",
                modifier = Modifier
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text(
                    text = "5 分钟内不再询问",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }
        }
    }
}
