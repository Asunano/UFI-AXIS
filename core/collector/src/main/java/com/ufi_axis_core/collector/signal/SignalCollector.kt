package com.ufi_axis_core.collector.signal

import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.core.database.SignalRecord
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.ServingCell
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 统一信号采集器 — 三层优先级合并。
 *
 * 1. Goform 独立字段（nr_rsrp、Nr_snr、nr_rsrq、lte_rsrp 等）
 * 2. Goform neighbor_cell_info + PCI 匹配（提取服务小区的 SINR/RSRQ）
 * 3. TelephonyCollector 兜底（Android CellInfo API）
 *
 * 不再使用 AT 指令查询信号（AT+CSQ/AT+CESQ），全部由 goform 接口覆盖。
 * 所有信号消费者（DataScheduler、REST API、WebSocket）共享同一数据源。
 *
 * ## 与 DeviceProfile 的分工（计划书 1.9 / D5）
 *
 * **只有第 1 层的字段映射搬进了 [profile]**：设备侧字段名、别名优先级链、值编码、
 * `network_information` 子对象的摊平，全部由 `:core:device-schema` 负责。
 *
 * 第 2、3 层留在本类，因为它们不是"字段映射"而是**多数据源编排**：第 2 层要拿
 * 服务小区 PCI 去邻区数组里匹配，第 3 层依赖 Android `TelephonyManager`（还带冷却，
 * 防 `com.android.phone` 的 Cursor 溢出）。把它们搬进纯 JVM 的 device-schema 是不可能的。
 *
 * 输出的 key 一律是 `DeviceFields.Signal` 的 canonical 名（core 自有小写归一名，
 * **不是**设备原名），WS `signal` 频道与 REST 共用同一份，一个都不能改。
 */
class SignalCollector(
    private val signalClient: GoformSignalClient?,
    private val telephonyCollector: TelephonyCollector,
    private val profile: DeviceProfile = ZteGoformProfile
) {
    private var lastTelephonyQueryAt = 0L
    private val telephonyCooldownMs = 15_000L // 2026-08-24: 限制 Telephony API 频率，防止系统 Cursor 泄漏

    /**
     * 执行三层优先级信号采集，返回统一的信号 Map。
     * goform HTTP 查询可能因路由器固件无响应导致长时间阻塞，
     * 使用 withTimeout 保证 5s 内返回（超时则跳过 goform 层直接走 Telephony 兜底）。
     *
     * @param preFetchedGoform 可选预取 goform 数据。由 DataScheduler 传入已合并的
     *                         getSignalInfo() 结果，避免重复 HTTP 查询。
     */
    suspend fun collect(preFetchedGoform: JsonObject? = null): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        // goform 查询带超时保护：5s 内无响应则跳过
        // 如果外部已预取数据则直接使用，否则自己查询
        val goformSignal = preFetchedGoform ?: try {
            kotlinx.coroutines.withTimeout(5_000L) {
                signalClient?.getSignalInfo()
            }
        } catch (_: Exception) {
            // 2026-08-24: 仅在 debug 日志记录超时，避免 warn 级日志在掉线时刷屏
            com.ufi_axis_core.util.AppLogger.d("SignalCollector", "goform signal query timeout/error, will fallback to Telephony if cooldown allow")
            null
        }

        // ── 第 1 层: Goform 独立字段 ──
        if (goformSignal != null) {
            collectLayer1GoformFields(goformSignal, result)
        }

        // ── 第 2 层: neighbor_cell_info + PCI 匹配 ──
        if (goformSignal != null &&
            (!result.containsKey(DeviceFields.Signal.SINR) || !result.containsKey(DeviceFields.Signal.RSRQ))
        ) {
            extractFromNeighborCells(goformSignal, result)
        }

        // ── 第 3 层: TelephonyCollector 兜底 ──
        // 2026-08-24: 即使 goform 失败，也不要每 3s 都调一次 Telephony API。
        // 系统 com.android.phone 进程在高频 allCellInfo 下容易 Cursor 溢出（见 logcat）。
        val now = System.currentTimeMillis()
        if (goformSignal == null || result.isEmpty()) {
            if (now - lastTelephonyQueryAt >= telephonyCooldownMs) {
                supplementFromTelephony(result)
                lastTelephonyQueryAt = now
            } else {
                com.ufi_axis_core.util.AppLogger.d("SignalCollector", "Telephony fallback skipped (cooldown)")
            }
        } else {
            // 如果 goform 有部分数据（如只有运营商），仍可以尝试补充，但同样应用冷却
            if (now - lastTelephonyQueryAt >= telephonyCooldownMs) {
                supplementFromTelephony(result)
                lastTelephonyQueryAt = now
            }
        }

        return result
    }

    /**
     * 根据采集结果构建 SignalRecord。
     */
    fun buildRecord(signalInfo: Map<String, Any>): SignalRecord = SignalRecord(
        rsrp = signalInfo.intValue("rsrp"),
        sinr = signalInfo.intValue("sinr"),
        rsrq = signalInfo.intValue("rsrq"),
        rssi = signalInfo.intValue("rssi"),
        rat = signalInfo.stringValue("rat", ""),
        operator = signalInfo.stringValue("operator", "")
    )

    // ── 第 1 层: Goform 独立字段 ──
    //
    // 字段名与别名优先级链已全部搬进 DeviceProfile（计划书 1.9）。本方法只负责三件事：
    //   ① 调 FieldNormalizer 拿到 canonical 字段；
    //   ② 把 JSON 值转回 Int / String 存进 result（下游 SignalRecord 与 WS 都按这两种类型消费）；
    //   ③ 派生服务小区统一字段（计划书 1.10）。
    //
    // 用 LegacyAliases.DROP：信号 map 从来只有 core 自有的小写归一名，
    // 绝不能让设备原名（Lte_snr / Nr_bands…）漏进来 —— WS `signal` 频道也吃这份数据。
    private fun collectLayer1GoformFields(goformSignal: JsonObject?, result: MutableMap<String, Any>) {
        if (goformSignal == null) return

        // network_information 子对象（含双重编码形态）的摊平由 profile 的结构解码器负责
        val canon = FieldNormalizer.normalize(
            goformSignal,
            profile,
            FieldGroup.SIGNAL,
            FieldNormalizer.LegacyAliases.DROP,
        )

        for ((key, value) in canon) {
            val prim = value as? JsonPrimitive ?: continue
            val text = prim.content
            if (text.isEmpty()) continue
            // 数值型 spec 用的是 Decoders.NUMERIC（输出 JSON 数字），字符串型是 JSON 字符串。
            // 按 isString 区分而不是尝试 toIntOrNull()，否则 cell_id 这种纯数字字符串会被转成
            // Int 而溢出（NCI 超 Int 范围）。
            result[key] = if (prim.isString) text else (text.toIntOrNull() ?: text)
        }

        // 服务小区统一字段：客户端不再需要按制式 if
        for ((key, value) in ServingCell.derive(canon)) {
            val prim = value as? JsonPrimitive ?: continue
            if (prim.content.isEmpty()) continue
            result[key] = if (prim.isString) prim.content else (prim.content.toIntOrNull() ?: prim.content)
        }

        // operator 的兜底跨数据源（goform 没填就问 Telephony），所以留在这一层而不是 profile
        if (result[DeviceFields.Signal.OPERATOR]?.toString().isNullOrBlank()) {
            result[DeviceFields.Signal.OPERATOR] = telephonyCollector.getOperatorName()
        }
    }

    // ── 第 2 层: neighbor_cell_info + PCI 匹配 ──
    //
    // 留在本类而不是搬进 profile：这一层不是字段映射，而是"拿服务小区 PCI 去邻区数组里
    // 匹配"的编排逻辑。服务小区 PCI 取自第 1 层归一化后的 `nr_pci` / `lte_pci`
    // （设备原名 Nr_pci / Lte_pci 已经收进 profile，这里不再出现设备侧字面量）。
    private fun extractFromNeighborCells(goformSignal: JsonObject, result: MutableMap<String, Any>) {
        val neighborArray = try {
            goformSignal[DeviceFields.CellInfo.NEIGHBOR_CELL_INFO]?.jsonArray
        } catch (_: Exception) { null } ?: return

        fun pciFromResult(key: String): Int? = when (val v = result[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
        val nrPci = pciFromResult(DeviceFields.Signal.NR_PCI)
        val ltePci = pciFromResult(DeviceFields.Signal.LTE_PCI)

        val cells = neighborArray.mapNotNull { cell ->
            try {
                val obj = cell.jsonObject
                val pci = obj[DeviceFields.CellInfo.ITEM_PCI]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val rsrp = obj[DeviceFields.CellInfo.ITEM_RSRP]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val rsrq = obj[DeviceFields.CellInfo.ITEM_RSRQ]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val sinr = obj[DeviceFields.CellInfo.ITEM_SINR]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (pci != null) NeighborCell(pci, rsrp, rsrq, sinr) else null
            } catch (_: Exception) { null }
        }

        val serving = cells.firstOrNull { it.pci == nrPci }
            ?: cells.firstOrNull { it.pci == ltePci }
            ?: cells.maxByOrNull { it.rsrp ?: -999 }

        serving?.let { cell ->
            if (!result.containsKey(DeviceFields.Signal.SINR) && cell.sinr != null) {
                result[DeviceFields.Signal.SINR] = cell.sinr
            }
            if (!result.containsKey(DeviceFields.Signal.RSRQ) && cell.rsrq != null) {
                result[DeviceFields.Signal.RSRQ] = cell.rsrq
            }
            if (!result.containsKey(DeviceFields.Signal.RSRP) && cell.rsrp != null) {
                result[DeviceFields.Signal.RSRP] = cell.rsrp
            }
            if (!result.containsKey(DeviceFields.Signal.RSSI) && cell.rsrp != null && cell.rsrp in -140..-30) {
                result[DeviceFields.Signal.RSSI] = cell.rsrp + 20
            }
        }
    }

    private data class NeighborCell(
        val pci: Int, val rsrp: Int?, val rsrq: Int?, val sinr: Int?
    )

    // ── 第 3 层: TelephonyCollector 兜底 ──
    private suspend fun supplementFromTelephony(result: MutableMap<String, Any>) {
        val telephonySignal by lazy { telephonyCollector.getSignalInfo() }

        if (!result.containsKey("rssi")) {
            val telephonyRssi = telephonySignal["rssi"]
            val existingRsrp = result["rsrp"]?.let {
                when (it) {
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            }
            if (telephonyRssi != null && telephonyRssi is Number && telephonyRssi.toInt() in -120..-30) {
                val rssiVal = telephonyRssi.toInt()
                if (existingRsrp == null || rssiVal >= existingRsrp) {
                    result["rssi"] = telephonyRssi
                }
            }
            if (!result.containsKey("rssi") && existingRsrp != null && existingRsrp in -140..-30) {
                result["rssi"] = existingRsrp + 20
            }
        }
        if (!result.containsKey("rsrp")) {
            telephonySignal["rsrp"]?.let { result["rsrp"] = it }
        }
        if (!result.containsKey("sinr")) {
            telephonySignal["sinr"]?.let { result["sinr"] = it }
        }
        if (!result.containsKey("rsrq")) {
            telephonySignal["rsrq"]?.let { result["rsrq"] = it }
        }
        if (!result.containsKey("rat")) {
            result["rat"] = telephonyCollector.getNetworkType()
        }
        val operator = result["operator"]?.toString()
        if (operator.isNullOrBlank() || operator == "Unknown" || operator.all { it.isDigit() }) {
            result["operator"] = telephonyCollector.getOperatorName()
        }
        // network_registered 必须在此层补充：前 2 层（goform）不包含 Android 网络注册状态
        if (!result.containsKey("network_registered")) {
            result["network_registered"] = telephonyCollector.isNetworkRegistered()
        }
    }
}

// 顶级辅助函数（与原始 DataScheduler 行为完全一致）

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.intValue(key: String): Int {
    val v = this[key] ?: return 0
    return when (v) {
        is Number -> v.toInt()
        is kotlinx.serialization.json.JsonPrimitive -> v.content.toIntOrNull() ?: 0
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }
}

private fun Map<String, Any>.stringValue(key: String, default: String = ""): String {
    val v = this[key] ?: return default
    return when (v) {
        is kotlinx.serialization.json.JsonPrimitive -> v.content
        else -> v.toString()
    }
}
