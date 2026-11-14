package com.ufi_axis_core.controller.carrier

/**
 * SIM 运营商识别表（2026-11-07，从 `CarrierSimDetector` 里抽出的**纯逻辑**）。
 *
 * 抽出来的理由：识别规则全是字符串匹配，但 `CarrierSimDetector` 依赖
 * `TelephonyManager`（测试机没有真 SIM），把它留在里层就永远测不到。
 *
 * ## 两条线索，先号段后名字
 *
 * | 运营商 | MNC（460xx） | 名字关键字 |
 * |---|---|---|
 * | 中国移动 | 00 / 02 / 04 / 07 / 08 | 移动、CMCC、China Mobile |
 * | 中国联通 | 01 / 06 / 09 | 联通、CUCC、China Unicom |
 * | 中国电信 | 03 / 05 / 11 / 12 | 电信、CTCC、China Telecom |
 * | 中国广电 | 15 | 广电、CBN、BroadNet |
 *
 * 号段优先：某些 ROM 把 `simOperatorName` 填成 "CMCC"、有的直接填中文，还有的空串；
 * 号段是运营商自己写进 SIM 的，比名字稳。名字只作退路。
 */
object CarrierSimCatalog {

    private data class Rule(
        val kind: CarrierKind,
        val mncs: List<String>,
        val nameKeywords: List<String>,
    )

    private val RULES = listOf(
        Rule(
            kind = CarrierKind.MOBILE,
            mncs = listOf("46000", "46002", "46004", "46007", "46008"),
            nameKeywords = listOf("移动", "cmcc", "china mobile"),
        ),
        Rule(
            kind = CarrierKind.UNICOM,
            mncs = listOf("46001", "46006", "46009"),
            nameKeywords = listOf("联通", "cucc", "china unicom"),
        ),
        Rule(
            kind = CarrierKind.TELECOM,
            mncs = listOf("46003", "46005", "46011", "46012"),
            nameKeywords = listOf("电信", "ctcc", "china telecom"),
        ),
        Rule(
            kind = CarrierKind.BROADNET,
            mncs = listOf("46015"),
            nameKeywords = listOf("广电", "cbn", "broadnet", "broadnet"),
        ),
    )

    /** @return null = 不认识（虚拟运营商 / 境外卡 / 空值）。 */
    fun of(numeric: String, operatorName: String): CarrierKind? {
        val n = numeric.trim()
        if (n.isNotEmpty()) {
            RULES.firstOrNull { rule -> rule.mncs.any { n.startsWith(it) } }?.let { return it.kind }
        }
        val name = operatorName.trim().lowercase()
        if (name.isEmpty()) return null
        return RULES.firstOrNull { rule -> rule.nameKeywords.any { name.contains(it) } }?.kind
    }

    /** 给用户看的名字（日志、错误文案）。 */
    fun displayName(kind: CarrierKind?): String = when (kind) {
        CarrierKind.MOBILE -> "中国移动"
        CarrierKind.UNICOM -> "中国联通"
        CarrierKind.TELECOM -> "中国电信"
        CarrierKind.BROADNET -> "中国广电"
        CarrierKind.IOT_CARD -> "云上卡"
        null -> "未知运营商"
    }
}
