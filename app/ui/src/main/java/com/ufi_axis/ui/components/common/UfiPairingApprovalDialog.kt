package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiWeight

/**
 * 「新设备请求配对」的批准弹窗（2026-10-15）。
 *
 * ## 为什么是**纯 UI** 组件（只吃字符串与两个回调）
 * 它挂在 App 根部（`MainActivity`）对所有页面生效，而 `:app:ui` 不依赖 `:app:viewmodel` ——
 * 与 [UfiAlertToastBridge] 同一条边界：桥接层在根部把状态翻译成参数，UI 组件只认参数。
 * 这样它也天然可预览、可被画廊收录。
 *
 * ## 为什么配对码要大字
 * 机主要有两条路都能走：把码**念**给请求方，或者在网页上**输入**它。念要看清、
 * 输入要对得上，所以字号放大 + 字距拉开 + 居中 —— 与邮件通知里把码渲染成大号块同一个理由。
 *
 * ## 关闭 = 拒绝
 * 待批准是"必须给出结论"的状态：若关掉只是暂时收起，下一次重组立刻又弹回来（死循环）；
 * 而"不响应"在服务端是 15 分钟过期，让用户以为"关掉=稍后再说"更糟。
 * 所以 `onDismiss` 与「拒绝」同义，按钮文案也直接写「拒绝」。
 *
 * @param code 6 位配对码（**只有已配对设备能拿到**；请求方永远看不到）
 * @param deviceName 已掩码的设备名（前 2 字 + `***`）
 * @param source 已掩码的来源（`192.168.***.***` / `隧道`）
 * @param requestedAtLabel 请求时间的展示串（调用方负责格式化）
 */
@Composable
fun UfiPairingApprovalDialog(
    code: String,
    deviceName: String,
    source: String,
    requestedAtLabel: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiScrollableDialog(
        visible = true,
        onDismiss = onReject,
        title = "新的设备请求配对",
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                onDismiss = onReject,
                onConfirm = onApprove,
                confirmText = "允许",
                dismissText = "拒绝"
            )
        }
    ) {
        UfiDialogBody {
            // 主操作只有一句话：点「允许」就够了。
            // 2026-10-17 用户反馈："到底是要输入验证码还是只需要点允许？说明太模糊" ——
            // 原来那句"让它在网页上输入；码正确即等同允许"把两条路平铺出来，反而让人不知道该做什么。
            Text(
                "点「允许」即可让它完成配对。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
            Text(
                text = code,
                style = MaterialTheme.typography.headlineMedium.copy(
                    letterSpacing = 6.sp,
                    fontWeight = UfiWeight.Strong
                ),
                color = palette.accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                // 配对码是**备用**路径，写清"什么时候才用"，不要和主操作并列
                "上面这串配对码是备用的：只有你不在手机旁边时才用得上 —— " +
                    "从邮件 / Webhook 收到它、在网页上输入，效果与点「允许」相同。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            Text(
                "设备：${deviceName.ifBlank { "未命名设备" }}\n" +
                    "来源：${source.ifBlank { "—" }}\n" +
                    "请求时间：$requestedAtLabel",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}
