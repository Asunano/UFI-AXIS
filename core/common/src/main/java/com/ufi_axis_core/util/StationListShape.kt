package com.ufi_axis_core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「已连接客户端列表」字段（`station_list` / `lan_station_list`）一轮取值的结果形态。
 *
 * ## 为什么需要它（计划书 §15 的 P1-29）
 *
 * 设备对这个字段有**两种形态**：真 JSON 数组，或**数组的 JSON 字符串**（双重编码，视固件而定）。
 * 归一化开着时 `ZteGoformProfile` 的 `normalizeStationLists` 会把后者拉平成真数组；
 * 而 `field_normalization_enabled=false`（排障开关）时那一步没了，双重编码原样透出。
 * 对外 API 手册早就要求客户端两种形态都能解 —— core 自己的消费端必须照做。
 *
 * ## 这个类型真正要防的事
 *
 * 把「**拿不到列表**」和「**列表是空的**」分成两件事：
 * 后者是设备事实（确实一台设备都没接入），前者只是本轮没有信息。
 * 二者混同，一次解析失败就会被当成「所有设备都离开了」，误报一片离开事件。
 *
 * 所以本类型**不提供**「失败时给个空数组」的出口：[Missing] / [Malformed] 根本不携带列表，
 * 调用方**无从构造**当前 MAC 集合 —— 误报路径在类型上就不可表达。
 */
sealed interface StationListShape {

    /**
     * 这一轮拿到了列表。
     *
     * [stations] 为空是**设备事实**，调用方可以据此得出「上一轮那些设备都离开了」。
     */
    data class Available(val stations: JsonArray) : StationListShape

    /**
     * 设备没给这个字段（字段缺失 / JSON null / 空白字符串）。
     *
     * 这是正常形态之一（比如这一版固件不返回该字段），调用方**静默跳过**、不要打 WARN ——
     * 否则默认部署下每分钟一条无用日志（WARN 自 2026-09-24 起无视日志总开关始终落地）。
     */
    object Missing : StationListShape

    /**
     * 有值，但既不是数组、也不是能解析成数组的字符串 —— **真的解析失败**。
     *
     * 调用方应当打一行 WARN（不许静默），并按「本轮没有可用信息」处理。
     */
    object Malformed : StationListShape
}

/**
 * 容错解析 `station_list` 这类客户端列表字段。
 *
 * 判定顺序与归一化开着时走的那条路（`ZteGoformProfile` 的 `asJsonArray`）保持一致，
 * 保证「归一化开 / 关」两种部署解出来的数组**逐元素相同**：
 *
 * 1. `null` / [JsonNull] / 空白字符串 → [StationListShape.Missing]；
 * 2. [JsonArray] → [StationListShape.Available]，**原样返回入参**（不复制、不改元素）；
 * 3. [JsonPrimitive] 且内容能解析成 JSON 数组（双重编码）→ [StationListShape.Available]；
 * 4. 其它（对象、数字、解析失败的字符串）→ [StationListShape.Malformed]。
 *
 * 与 `asJsonArray` 的唯一差别：**空白字符串**在这里是 [StationListShape.Missing] 而不是解析失败。
 * 空串既不能证明「没有设备」、也不算结构损坏，按「设备没给」处理才不会每分钟刷一条 WARN。
 *
 * 本函数**不做元素键归一**（`mac` → `mac_addr` 之类）：那是 profile 的 `structuralDecoder`
 * 的职责，在这里抄一份等于把设备知识散进 `core/common`。
 */
fun parseStationList(element: JsonElement?): StationListShape {
    if (element == null || element is JsonNull) return StationListShape.Missing
    if (element is JsonArray) return StationListShape.Available(element)
    val text = (element as? JsonPrimitive)?.content ?: return StationListShape.Malformed
    if (text.isBlank()) return StationListShape.Missing
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }
    return if (parsed is JsonArray) StationListShape.Available(parsed) else StationListShape.Malformed
}
