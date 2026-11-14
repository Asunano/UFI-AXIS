package com.ufi_axis_core.controller.carrier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ufi_axis_core.contract.CarrierNames

/**
 * 判断「当前设备里的 SIM 是不是中国移动」。
 *
 * 只认 SIM 运营商，不认当前数据网络：随身 WiFi 可能插的是移动卡，但出口被
 * 隧道/热点改写；自动登录要绑的是**设备内那张卡**。
 */
class CarrierSimDetector(context: Context) {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val subscriptions = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        as? SubscriptionManager

    fun detect(): CarrierSimCapability {
        val tm = telephony ?: return unsupported("设备没有 TelephonyManager")
        val state = runCatching { tm.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        if (state != TelephonyManager.SIM_STATE_READY) {
            return unsupported("SIM 未就绪（state=$state）")
        }
        val canReadPhone = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!canReadPhone) return unsupported("缺少 READ_PHONE_STATE 权限")

        val numeric = runCatching { tm.simOperator }.getOrNull().orEmpty()
        val name = runCatching { tm.simOperatorName }.getOrNull().orEmpty()
        // 识别仍用原始名（CarrierSimCatalog 认 CMCC / 中国移动 两种写法）；
        // 但对外展示名统一翻中文 —— 否则 Web 的「SIM：CMCC（46013）」与全站「中国移动」不一致。
        val displayName = CarrierNames.displayName(name)
        // line1Number 有的机型带 +86（13 位数字）；统一归一化成 11 位，
        // 否则严格匹配会把它当成"读不到 SIM 号码"。
        val phone = runCatching { tm.line1Number }
            .getOrNull()
            ?.filter(Char::isDigit)
            ?.let { digits ->
                when {
                    digits.length == 11 -> digits
                    digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
                    else -> null
                }
            }
        val simCount = runCatching { subscriptions?.activeSubscriptionInfoList?.size ?: 0 }.getOrDefault(0)

        // 识别规则在 [CarrierSimCatalog]（纯函数，可单测）：号段优先、名字兜底。
        val carrier = CarrierSimCatalog.of(numeric, name)
        if (carrier == null) {
            return CarrierSimCapability(
                supported = false, carrier = null, simOperator = numeric, operatorName = displayName,
                phone = phone, simCount = simCount,
                reason = "未识别到国内运营商 SIM（${displayName.ifBlank { numeric }}）"
            )
        }
        if (carrier != CarrierKind.MOBILE) {
            return CarrierSimCapability(
                supported = false, carrier = carrier, simOperator = numeric, operatorName = displayName,
                phone = phone, simCount = simCount,
                reason = "自动登录只支持中国移动（当前是${CarrierSimCatalog.displayName(carrier)}）"
            )
        }
        return CarrierSimCapability(
            supported = true, carrier = carrier, simOperator = numeric, operatorName = displayName,
            phone = phone, simCount = simCount, reason = null
        )
    }

    private fun unsupported(reason: String) = CarrierSimCapability(
        supported = false, carrier = null, simOperator = "", operatorName = "",
        phone = null, simCount = 0, reason = reason
    )
}
