package com.ufi_axis_core.controller.carrier.mobile

import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierFlow
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierPackage
import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import com.ufi_axis_core.controller.carrier.maskCarrierPhone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 移动 App 域 raw JSON → [CarrierBill]（2026-10-31，从 probe 的 `_parse_app` 移植）。
 *
 * ## 口径（每条都踩过）
 * - `unit=03` 是 MB、`04` 是 GB；聚合与包明细各用自己的单位；
 * - 剩余量取 `flowRemainNum` / `remainRes` 原值，**不要用总量减已用**（舍入会差 0.01）；
 * - `flowtype=1` 是定向；
 * - `app-flow.homeFlowInfo.usedTotal` 的 `unit=1` 是 MB，除 1024 得 GB；
 * - `app-accout.this_month_bill` 是余额，不是本期账单；本期账单取 `app-bills.totalBill`；
 * - 任一接口失败只进 warnings，不阻断其他字段。
 */
object MobileBillParser {

    fun parse(raw: Map<String, JsonObject>, phone: String): CarrierBill {
        val warnings = mutableListOf<String>()

        val plan = raw["app-plan"]?.rspBody()
        val flowRows = plan?.obj("newPlanRemainQryRes")
            ?.obj("planRemianFlowListRes")
            ?.arr("planRemianFlowRes")
            ?: JsonArray(emptyList())

        var generalUsed = 0.0
        var generalTotal = 0.0
        var generalRemain = 0.0
        var directedUsed = 0.0
        var directedTotal = 0.0
        var directedRemain = 0.0
        val packages = mutableListOf<CarrierPackage>()

        for (row in flowRows.objects()) {
            val factor = unitFactor(row.str("unit"))
            val directed = row.str("flowtype") == "1"
            val used = (row.num("flowUsdNum") ?: 0.0) * factor
            val total = (row.num("flowSumNum") ?: 0.0) * factor
            val remain = (row.num("flowRemainNum") ?: 0.0) * factor
            if (directed) {
                directedUsed += used; directedTotal += total; directedRemain += remain
            } else {
                generalUsed += used; generalTotal += total; generalRemain += remain
            }

            for (sub in row.arr("planRemianFlowInfoRes").objects()) {
                val name = sub.str("planName")?.takeIf { it.isNotBlank() } ?: "流量包"
                val from = normalizeTime(sub.str("validDate"))
                val to = normalizeTime(sub.str("expireDate"))
                for (detail in sub.arr("flowInfoRes").objects()) {
                    val dFactor = unitFactor(detail.str("unit"))
                    packages += CarrierPackage(
                        kind = if (directed) CarrierPackageKind.DIRECTED else CarrierPackageKind.GENERAL,
                        name = name,
                        remainingGb = ((detail.num("remainRes") ?: 0.0) * dFactor).round2(),
                        totalGb = ((detail.num("totalRes") ?: 0.0) * dFactor).round2(),
                        validFrom = from,
                        validTo = to,
                    )
                }
            }
        }
        if (flowRows.isEmpty()) warnings += "未取到流量余量（app-plan 为空或失败）"

        val fee = raw["app-fee"]?.rspBody()
        val balance = fee?.num("curFeeTotal") ?: fee?.num("realBalanceFee")

        val user = raw["app-user"]?.rspBody()?.obj("userInfo")
        val owner = user?.str("userName")
        val tenure = parseTenure(user?.str("userAge"))
        val star = user?.num("starLevel")

        val planInfo = raw["app-planq"]?.rspBody()?.arr("planQryInfos")?.objects()?.firstOrNull()
        val planName = composePlanName(planInfo?.str("curPlanName"), planInfo?.str("brandName"))

        val bill = raw["app-bills"]?.rspBody()?.arr("historyBillInfo")?.objects()?.firstOrNull()
        val account = raw["app-accout"]?.rspBody()?.arr("accoutQryInfoHis")?.objects()?.firstOrNull()

        val monthlyFlow = raw["app-flow"]?.rspBody()?.obj("homeFlowInfo")
        val monthlyUsed = monthlyFlow?.num("usedTotal")?.let {
            if (monthlyFlow.str("unit") == "1") it / 1024.0 else it
        }

        val points = raw["app-points"]?.rspBody()?.num("pointValue")?.toInt()

        for ((key, body) in raw) {
            body.str("__error")?.let { warnings += "$key 接口失败：$it" }
        }
        if (balance == null) warnings += "余额接口未返回可用余额"
        if (bill == null) warnings += "账单接口未返回本期账单"

        return CarrierBill(
            carrier = CarrierKind.MOBILE,
            phoneMasked = maskCarrierPhone(phone),
            planName = planName,
            ownerName = owner,
            tenureMonths = tenure,
            starLevel = star,
            balanceYuan = balance?.round2(),
            lastMonthBillYuan = (account?.num("allLastMonthBill"))?.round2(),
            currentBillYuan = (bill?.num("totalBill") ?: account?.num("allThisMonthOutBill"))?.round2(),
            currentBillPeriod = bill?.str("inBill") ?: account?.str("inBill"),
            currentBillAsOf = bill?.str("billCycleEndDate"),
            points = points,
            flow = CarrierFlow(
                generalUsedGb = generalUsed.round2(),
                generalTotalGb = generalTotal.round2(),
                generalRemainingGb = generalRemain.round2(),
                directedUsedGb = directedUsed.round2(),
                directedTotalGb = directedTotal.round2(),
                directedRemainingGb = directedRemain.round2(),
            ),
            monthlyUsedGb = monthlyUsed?.round2(),
            voice = null,
            sms = null,
            packages = packages,
            warnings = warnings,
        )
    }

    private fun JsonObject.rspBody(): JsonObject =
        this["rspBody"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonArray?.objects(): List<JsonObject> =
        this.orEmpty().mapNotNull { it as? JsonObject }

    private fun unitFactor(unit: String?): Double = when (unit) {
        "03" -> 1.0 / 1024.0 // MB
        "04" -> 1.0          // GB
        else -> 1.0 / 1024.0 // 缺省按 MB（App 域实测全是 03）
    }

    /** `2026-10-31 14:16:19` → `2026-10-31T14:16:19`；UI 用 LocalDateTime 解析。 */
    private fun normalizeTime(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.replace(' ', 'T')
    }

    /** `5年1个月` → 61；`5年` → 60；`1个月` → 1；解析不出来 null。 */
    private fun parseTenure(text: String?): Int? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val years = Regex("""(\d+)\s*年""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val months = Regex("""(\d+)\s*个月""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (years == 0 && months == 0) null else years * 12 + months
    }

    private fun composePlanName(name: String?, brand: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val b = brand?.trim()?.takeIf { it.isNotEmpty() } ?: return n
        return if (n.contains(b)) n else "$n（$b）"
    }

    private fun Double.round2(): Double = kotlin.math.round(this * 100.0) / 100.0
}
