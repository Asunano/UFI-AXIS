package com.ufi_axis_core.contract

/**
 * 运营商原始名 → 中文展示名（2026-11-10）。
 *
 * 背景：ZTE F50 的 goform `network_provider` 给的是 `CMCC` / `CUCC` / `CTCC`，
 * Telephony 侧则可能是中文、英文全称或 `CHN-UNICOM`。此前只有 App 的
 * `CarrierNames` 做了映射，Web 直接显示 `CMCC` —— 同一个 core 的数据在两个端不一致。
 *
 * 现在映射下沉到 core：DataHub / Telephony / SignalCollector 统一输出中文，
 * App / Web / WS 都拿同一份。**只做展示**，不参与运营商判断（判断在
 * `CarrierSimCatalog`，那边仍读原始 SIM 字段）。
 *
 * 与 App 的 `com.ufi_axis.data.model.CarrierNames` 保持同一套规则；两处都保留是因为
 * core 不能依赖 app:data，app 也不能把 core:contract 当成展示层。
 */
object CarrierNames {
    /** @return 中文名；认不出时**原样返回**，不把未知运营商抹成空。 */
    fun displayName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val lower = value.lowercase()
        return when {
            lower.contains("cmcc") || lower.contains("mobile") || value.contains("移动") -> "中国移动"
            lower.contains("cucc") || lower.contains("unicom") || value.contains("联通") -> "中国联通"
            lower.contains("ctcc") || lower.contains("telecom") || value.contains("电信") -> "中国电信"
            lower.contains("broadnet") || lower == "cbn" || value.contains("广电") -> "中国广电"
            else -> value
        }
    }
}
