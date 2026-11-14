package com.ufi_axis.data.model

/**
 * 把设备/运营商返回的**原始运营商名**翻成中文展示名（2026-11-07）。
 *
 * 背景：ZTE F50 的 goform 里 `operator` 字段直接给 `CMCC`（移动）、`CUCC`（联通）、
 * `CTCC`（电信）；而 SIM 侧有时是中文、有时是英文全称。界面上一律显示中文，
 * 否则首页顶部会出现「CMCC」这种缩写。
 *
 * 只做展示：不改模型字段，也不参与任何判断（判断在 core 的 `CarrierSimCatalog`）。
 */
object CarrierNames {

    /** @return 中文名；认不出时**原样返回**（不要在 UI 上把未知运营商抹成空）。 */
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
