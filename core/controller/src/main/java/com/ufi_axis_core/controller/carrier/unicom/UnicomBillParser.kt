package com.ufi_axis_core.controller.carrier.unicom

import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierFlow
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierPackage
import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import com.ufi_axis_core.controller.carrier.CarrierSms
import com.ufi_axis_core.controller.carrier.CarrierVoice
import com.ufi_axis_core.controller.carrier.maskCarrierPhone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * 中国联通 mxx 余量/余额 → [CarrierBill]（2026-10-31，从 probe 的 `_parse_mxx` 移植）。
 *
 * ## 口径
 * - `unshared` / `shareData` / `resources` 三处都可能有 `details[]`（共享池/副卡池）；
 * - `elemType`：1=语音、2=短信、3=流量；
 * - 流量 `flowType`：1=通用、2=定向、3=区域；单位 MB，除 1024 得 GB；
 * - 包总量缺失时用「已用 + 剩余」补齐；
 * - 合计 = 通用 + 定向 + 区域（区域有范围限制，UI 单独标）；
 * - 联通没有稳定的上月/本期账单，相关字段留 null。
 */
object UnicomBillParser {

    fun parse(packagePayload: JsonObject?, balancePayload: JsonObject?, phone: String): CarrierBill {
        val warnings = mutableListOf<String>()
        val pkg = packagePayload ?: JsonObject(emptyMap())
        if (packagePayload == null) warnings += "联通余量接口未返回数据"

        val data = mutableListOf<JsonObject>()
        val voices = mutableListOf<JsonObject>()
        val sms = mutableListOf<JsonObject>()
        for (source in listOf("unshared", "shareData", "resources")) {
            for (item in details(pkg[source])) {
                when (item.str("elemType")) {
                    "1" -> voices += item
                    "2" -> sms += item
                    "3" -> data += item
                    else -> Unit
                }
            }
        }

        var general = Triple(0.0, 0.0, 0.0)
        var directed = Triple(0.0, 0.0, 0.0)
        var regional = Triple(0.0, 0.0, 0.0)
        val packages = mutableListOf<CarrierPackage>()

        for (item in data) {
            val (used, total, remain) = dataAmounts(item)
            when (item.str("flowType")) {
                "2" -> directed = add(directed, used, total, remain)
                "3" -> regional = add(regional, used, total, remain)
                else -> general = add(general, used, total, remain)
            }
            packages += CarrierPackage(
                kind = when (item.str("flowType")) {
                    "2" -> CarrierPackageKind.DIRECTED
                    "3" -> CarrierPackageKind.REGIONAL
                    else -> CarrierPackageKind.GENERAL
                },
                name = item.str("feePolicyName") ?: item.str("addUpItemName") ?: "流量包",
                remainingGb = remain,
                totalGb = total,
                validFrom = null,
                validTo = normalizeTime(item.str("endDate1") ?: item.str("endDate")),
            )
        }
        if (data.isEmpty()) warnings += "联通余量明细为空（resources/details 都没取到）"

        val voice = when {
            voices.isNotEmpty() -> {
                var used = 0.0; var total = 0.0; var remain = 0.0
                for (item in voices) {
                    used += item.num("use") ?: 0.0
                    remain += item.num("remain") ?: 0.0
                    total += item.num("total") ?: 0.0
                }
                if (total <= 0.0) total = used + remain
                CarrierVoice(used.round1(), total.round1(), remain.round1())
            }
            pkg.num("canUseValueAll") != null -> {
                val used = pkg.num("voiceHeadUsed") ?: 0.0
                val remain = pkg.num("canUseValueAll") ?: 0.0
                val total = maxOf(pkg.num("voiceSumresource") ?: 0.0, used + remain)
                CarrierVoice(used.round1(), total.round1(), remain.round1())
            }
            else -> null
        }

        val smsInfo = when {
            sms.isNotEmpty() -> {
                var used = 0.0; var total = 0.0; var remain = 0.0
                for (item in sms) {
                    used += item.num("use") ?: 0.0
                    remain += item.num("remain") ?: 0.0
                    total += item.num("total") ?: 0.0
                }
                if (total <= 0.0) total = used + remain
                CarrierSms(used.round1(), total.round1(), remain.round1())
            }
            pkg.num("canUseSmsAll") != null -> {
                val used = pkg.num("smsHeadUsed") ?: 0.0
                val remain = pkg.num("canUseSmsAll") ?: 0.0
                val total = maxOf(pkg.num("smsSumresource") ?: 0.0, used + remain)
                CarrierSms(used.round1(), total.round1(), remain.round1())
            }
            else -> null
        }

        val balance = balanceOf(balancePayload)
        // 用 AppLogger 而不是 android.util.Log：后者在 JVM 单测里是未实现的桩
        // （Method w in android.util.Log not mocked），会让解析器的单测直接红。
        // 只记「解析成功与否」与字段数：`exact` 是余额金额，WARN 恒落盘且可经
        // /api/debug-logs 取走，财务数据不该长期留在日志里。
        com.ufi_axis_core.util.AppLogger.d(
            "UnicomBill",
            "balance keys=${balancePayload?.keys?.size ?: 0}; parsed=${balance != null}"
        )
        if (balance == null) warnings += "联通余额接口未返回可用字段"

        return CarrierBill(
            carrier = CarrierKind.UNICOM,
            phoneMasked = maskCarrierPhone(phone),
            planName = pkg.str("packageName")?.takeIf { it.isNotBlank() },
            ownerName = null,
            tenureMonths = null,
            starLevel = null,
            balanceYuan = balance?.round2(),
            lastMonthBillYuan = null,
            currentBillYuan = null,
            currentBillPeriod = null,
            currentBillAsOf = null,
            points = null,
            flow = CarrierFlow(
                generalUsedGb = general.first.round2(),
                generalTotalGb = general.second.round2(),
                generalRemainingGb = general.third.round2(),
                directedUsedGb = directed.first.round2(),
                directedTotalGb = directed.second.round2(),
                directedRemainingGb = directed.third.round2(),
                regionalUsedGb = regional.first.round2(),
                regionalTotalGb = regional.second.round2(),
                regionalRemainingGb = regional.third.round2(),
            ),
            monthlyUsedGb = null,
            voice = voice,
            sms = smsInfo,
            packages = packages,
            warnings = warnings,
        )
    }

    private fun details(node: JsonElement?): List<JsonObject> {
        val groups = when (node) {
            null -> emptyList()
            is JsonArray -> node.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(node)
            else -> emptyList()
        }
        return groups.flatMap { group ->
            (group["details"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        }
    }

    private fun dataAmounts(item: JsonObject): Triple<Double, Double, Double> {
        val usedMb = item.num("use") ?: 0.0
        val remainMb = item.num("remain") ?: 0.0
        val totalMb = item.num("total") ?: (usedMb + remainMb)
        return Triple((usedMb / 1024).round2(), (totalMb / 1024).round2(), (remainMb / 1024).round2())
    }

    private fun add(base: Triple<Double, Double, Double>, used: Double, total: Double, remain: Double) =
        Triple(base.first + used, base.second + total, base.third + remain)

    /**
     * 联通余额字段在不同省份不固定：`curntbalancecust` 是常见字段，
     * 但有的网关只给 `canusefeecustNew` / `realfeecust`。按 probe 的兜底策略：
     * 先精确字段，再递归找键名带 balance/fee 的数字。
     */
    private fun balanceOf(payload: JsonObject?): Double? {
        if (payload == null) return null
        payload.num("curntbalancecust")?.let { return it }
        val found = mutableListOf<Pair<String, Double>>()
        fun walk(node: JsonObject, prefix: String, depth: Int) {
            if (depth > 2) return
            for ((key, value) in node) {
                val low = key.lowercase()
                when (value) {
                    is JsonObject -> walk(value, "$prefix$key.", depth + 1)
                    is JsonPrimitive -> {
                        val n = parseLooseNumber(value.contentOrNull) ?: continue
                        if ("balance" in low || "fee" in low) found += ("$prefix$key" to n)
                    }
                    else -> Unit
                }
            }
        }
        walk(payload, "", 0)
        return found.firstOrNull {
            val k = it.first.lowercase()
            "available" in k || "curbalance" in k || "balance" in k
        }?.second ?: found.firstOrNull()?.second
    }

    /** 兼容 `7.12`、`7.12元`、`1,234.56`，解析不出来返回 null。 */
    private fun parseLooseNumber(raw: String?): Double? {
        val text = raw?.trim()?.replace(",", "") ?: return null
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { return it }
        return Regex("""-?\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? =
        parseLooseNumber((this[key] as? JsonPrimitive)?.contentOrNull)

    /** `2027-02-28 23:59:59` → ISO；`2027年02月28日到期` → 当日 23:59。 */
    private fun normalizeTime(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?""").find(raw)?.let {
            val g = it.groupValues
            val sec = g[6].ifBlank { "00" }
            return "${g[1]}-${g[2]}-${g[3]}T${g[4]}:${g[5]}:$sec"
        }
        Regex("""^(\d{4})年(\d{2})月(\d{2})日""").find(raw)?.let {
            val g = it.groupValues
            return "${g[1]}-${g[2]}-${g[3]}T23:59:00"
        }
        return null
    }

    private fun Double.round1(): Double = kotlin.math.round(this * 10.0) / 10.0
    private fun Double.round2(): Double = kotlin.math.round(this * 100.0) / 100.0
}
