package com.ufi_axis.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ufi_axis.data.model.CarrierKeepAliveAccount
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 话费账单「自动登录」在 App 里的共享文案/状态映射。
 *
 * 设置主页只显示全局策略与参与数；账号级状态在「参与账号」子页展示。
 * 两个页面必须用同一套 phase/原因文案，否则同一状态会出现两种说法。
 */
internal fun keepAlivePhaseLabel(phase: String): String = when (phase) {
    "disabled" -> "已暂停"
    "idle" -> "待检查"
    "checking" -> "检查中"
    "waiting_code" -> "等待验证码"
    "verifying" -> "登录中"
    "ready" -> "已登录"
    "failed" -> "失败"
    "phone_required" -> "缺手机号"
    "unsupported" -> "不支持"
    else -> phase
}

internal fun keepAliveCarrierLabel(carrier: String?): String? = when (carrier) {
    "mobile" -> "中国移动"
    "unicom" -> "中国联通"
    "telecom" -> "中国电信"
    "broadnet", "cbn" -> "中国广电"
    else -> null
}

internal fun keepAliveAccountStatus(
    account: CarrierKeepAliveAccount,
    globalEnabled: Boolean,
): String {
    val carrier = keepAliveCarrierLabel(account.carrier)
    val status = when {
        // 不满足「移动号码 / SIM 在当前设备 / 设备读到号码与账号一致」一律显示不支持。
        // 列表行只放短句；完整原因在账号明细页/页面页脚。
        !account.supported -> buildString {
            append("不支持")
            val reason = when {
                account.carrier != "mobile" -> "非移动"
                account.message?.contains("未就绪") == true -> "SIM 未就绪"
                account.message?.contains("只支持中国移动") == true -> "SIM 不是移动"
                account.message?.contains("READ_PHONE_STATE") == true -> "缺少 SIM 权限"
                account.message?.contains("未识别到") == true -> "未识别到 SIM"
                account.message?.contains("读不到") == true -> "读不到 SIM 号码"
                account.message?.contains("手机号") == true -> "缺完整手机号"
                account.message?.contains("不一致") == true -> "SIM 与账号不一致"
                else -> null
            }
            if (reason != null) append("（").append(reason).append("）")
        }
        // 总开关关着时仍允许先选参与位；开关本身是真实的参与状态。
        !globalEnabled ->
            if (account.participating) "已参与 · 总开关未开" else "未参与 · 总开关未开"
        !account.participating -> "未参与 · 不会自动登录"
        else -> buildString {
            append("已参与 · ")
            append(keepAlivePhaseLabel(account.phase))
            if (account.attemptsToday > 0) append(" · 今日自动登录 ${account.attemptsToday} 次")
            // 上游错误常常是一大段；概览行只收短句，完整文案留在账号明细页。
            account.message
                ?.takeIf { account.phase == "failed" && it.length <= 28 }
                ?.let { append(" · $it") }
        }
    }
    return if (carrier == null) status else "$carrier · $status"
}

/**
 * 把底层异常翻译成用户能行动的文案。
 *
 * 最典型的是"新 App + 旧 core"：旧 core 的 SPA 兜底会对未知路径回 200 + HTML，
 * kotlinx.serialization 抛 `JsonDecodingException`；404/501 则交给
 * [MainViewModel] 侧的 `billSettingsErrorText` 提示升级。
 */
internal fun billSettingsErrorMessage(
    viewModel: MainViewModel,
    error: Throwable,
    fallback: String,
): String = when (error) {
    is kotlinx.serialization.SerializationException ->
        "设备 core 版本过旧，账单设置暂不可用；请先升级 core"

    is java.io.IOException -> "无法连接设备：${error.message ?: "网络错误"}"
    else -> viewModel.tools.billSettingsErrorText(error).takeIf { it.isNotBlank() } ?: fallback
}

/** 设置页读取失败时的缓存提示（账单设置/自动登录两个页面共用）。 */
@Composable
internal fun BillStaleSyncNote(
    message: String,
    onRetry: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsRowCard {
        UfiSettingsItem(
            title = "上次同步失败",
            description = "$message（当前为缓存值）",
            descriptionMaxLines = 2,
            icon = Icons.Default.Info,
            iconTint = palette.error,
            trailing = {
                TextButton(onClick = onRetry) {
                    Text("重试", style = UfiTextStyles.note, color = palette.accent)
                }
            },
        )
    }
}
