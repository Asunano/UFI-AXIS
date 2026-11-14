package com.ufi_axis_core.deviceschema

import com.ufi_axis_core.contract.DeviceFields
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务小区统一字段的派生（计划书 1.10）。
 *
 * ## 为什么需要它
 *
 * 设备把频段/频点/带宽/PCI 按制式分成两组（`nr_*` 与 `lte_*`），客户端此前必须自己判制式再选，
 * 例如 web 的 `formatBand()` 写 `rat === '5G' ? nr_band : lte_band`。本对象把这段 if 收进 core。
 *
 * ## 为什么不放 DeviceProfile
 *
 * 它的输入是**已归一化的 canonical 字段**，与具体设备无关 —— 任何 profile 归一化出
 * `nr_band` / `lte_band` 之后都能用同一套派生规则。放 profile 里会让每个新设备都抄一遍。
 *
 * ## 规则（三条都是契约）
 *
 * 1. **NR 优先、LTE 兜底。** 与 `rsrp` / `sinr` 的合并顺序一致。
 * 2. **判定依据是"字段是否存在"，不看 `rat` 字符串。** `rat` 是 44 项映射表的输出
 *    （含 `"NSA"` / `"未知(xx)"` 等），拿它做分支会在固件返回新值时静默走错分支。
 *    NSA 双连接下两侧都有值，此时取 NR。
 * 3. **缺失即省略 key。** `band_width` 尤其常缺 —— `Nr_band_widths` / `Lte_bands_widths`
 *    在多数固件上是空串，UI 按"没这个键 → 显示 —"处理。
 *
 * `nr_*` / `lte_*` 原字段全部保留，需要看双连接的场景仍然读那两组。
 */
object ServingCell {

    /**
     * @param signal 已经过 [FieldNormalizer] 归一化的 SIGNAL 分组结果
     * @return 只包含派生字段的对象（不改动入参，调用方自行合并）
     */
    fun derive(signal: Map<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()

        /** @return 值 + 是否来自 NR；两侧都没有则 null */
        fun pick(nrKey: String, lteKey: String): Pair<JsonElement, Boolean>? {
            signal[nrKey]?.let { return it to true }
            signal[lteKey]?.let { return it to false }
            return null
        }

        pick(DeviceFields.Signal.NR_BAND, DeviceFields.Signal.LTE_BAND)?.let { (value, isNr) ->
            out[DeviceFields.Signal.BAND] = value
            val num = (value as? JsonPrimitive)?.content?.trim()
            if (!num.isNullOrEmpty()) {
                // 前缀由 core 拼：前端此前一边拼 N/B（formatBand）、一边又反向剥前缀
                // （parseBands），两套规则漂移过。
                //
                // 2026-10-11 用户裁决：`n` → **`N`**（3GPP 官方写法就是大写）。
                // 在**源头**改，而不是让 app / web 各自在显示层 toUpperCase ——
                // 后者要两端各改一处、出两版包，而这里改一次两端自动都对。
                //
                // 安全性：客户端唯一的判据是 app `SignalInfo.isNr`，它用
                // `startsWith("n", ignoreCase = true)` —— **大小写不敏感**，N78 仍判为 5G。
                // web 端没有拿 band_label 当判据的地方（parseBands 处理的是
                // lte_band_lock 那类纯数字逗号串，与本字段无关）。
                out[DeviceFields.Signal.BAND_LABEL] = JsonPrimitive(if (isNr) "N$num" else "B$num")
            }
        }
        pick(DeviceFields.Signal.NR_ARFCN, DeviceFields.Signal.LTE_ARFCN)
            ?.let { out[DeviceFields.Signal.ARFCN] = it.first }
        pick(DeviceFields.Signal.NR_BAND_WIDTH, DeviceFields.Signal.LTE_BAND_WIDTH)
            ?.let { out[DeviceFields.Signal.BAND_WIDTH] = it.first }
        pick(DeviceFields.Signal.NR_SIGNAL_STRENGTH, DeviceFields.Signal.LTE_SIGNAL_STRENGTH)
            ?.let { out[DeviceFields.Signal.SIGNAL_STRENGTH] = it.first }
        pick(DeviceFields.Signal.NR_PCI, DeviceFields.Signal.LTE_PCI)
            ?.let { out[DeviceFields.Signal.PCI] = it.first }

        return JsonObject(out)
    }
}
