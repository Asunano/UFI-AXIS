package com.ufi_axis_core.collector.signal

import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.core.database.SignalRecord
import kotlinx.serialization.json.JsonObject
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
 */
class SignalCollector(
    private val signalClient: GoformSignalClient?,
    private val telephonyCollector: TelephonyCollector
) {
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
            com.ufi_axis_core.util.AppLogger.w("SignalCollector", "goform signal query timeout/error, falling back to Telephony")
            null
        }

        // ── 第 1 层: Goform 独立字段 ──
        if (goformSignal != null) {
            collectLayer1GoformFields(goformSignal, result)
        }

        // ── 第 2 层: neighbor_cell_info + PCI 匹配 ──
        if (goformSignal != null && (!result.containsKey("sinr") || !result.containsKey("rsrq"))) {
            extractFromNeighborCells(goformSignal, result)
        }

        // ── 第 3 层: TelephonyCollector 兜底 ──
        supplementFromTelephony(result)

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
    private fun collectLayer1GoformFields(goformSignal: JsonObject?, result: MutableMap<String, Any>) {
        fun goformStr(key: String): String? =
            goformSignal?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun goformInt(key: String): Int? = goformStr(key)?.toIntOrNull()

        // RSRP: nr_rsrp (network_information) > Z5g_rsrp (独立字段) > lte_rsrp
        goformInt("nr_rsrp")?.let { result["rsrp"] = it }
        if (!result.containsKey("rsrp")) goformInt("Z5g_rsrp")?.let { result["rsrp"] = it }
        if (!result.containsKey("rsrp")) goformInt("lte_rsrp")?.let { result["rsrp"] = it }
        // SINR: Nr_snr(5G) > Lte_snr(4G)
        goformInt("Nr_snr")?.let { result["sinr"] = it }
        if (!result.containsKey("sinr")) goformInt("Lte_snr")?.let { result["sinr"] = it }
        // RSRQ: nr_rsrq(5G) > lte_rsrq(4G)
        goformInt("nr_rsrq")?.let { result["rsrq"] = it }
        if (!result.containsKey("rsrq")) goformInt("lte_rsrq")?.let { result["rsrq"] = it }
        // RSSI: Nr_signal_strength (network_information，真实dBm) > nr_rssi > lte_rssi
        //       注意: goform 'rssi' 字段是信号条数 0-5，不可作为 dBm 使用
        goformInt("Nr_signal_strength")?.let { result["rssi"] = it }
        if (!result.containsKey("rssi")) goformInt("nr_rssi")?.let { result["rssi"] = it }
        if (!result.containsKey("rssi")) goformInt("lte_rssi")?.let { result["rssi"] = it }
        // 元数据
        goformStr("network_type")?.let { result["rat"] = GoformClient.mapNetworkType(it) }
        // cell_id: Nr_cell_id (network_information) > cell_id (独立字段)
        goformStr("Nr_cell_id")?.let { result["cell_id"] = it }
        if (!result.containsKey("cell_id")) goformStr("cell_id")?.let { result["cell_id"] = it }
        val provider = goformStr("network_provider")
        result["operator"] = if (!provider.isNullOrBlank()) provider else telephonyCollector.getOperatorName()
    }

    // ── 第 2 层: neighbor_cell_info + PCI 匹配 ──
    private fun extractFromNeighborCells(goformSignal: JsonObject, result: MutableMap<String, Any>) {
        val neighborArray = try {
            goformSignal["neighbor_cell_info"]?.jsonArray
        } catch (_: Exception) { null } ?: return

        fun pciValue(key: String): Int? =
            goformSignal[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val nrPci = pciValue("Nr_pci")
        val ltePci = pciValue("Lte_pci")

        val cells = neighborArray.mapNotNull { cell ->
            try {
                val obj = cell.jsonObject
                val pci = obj["pci"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val rsrp = obj["rsrp"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val rsrq = obj["rsrq"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val sinr = obj["sinr"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (pci != null) NeighborCell(pci, rsrp, rsrq, sinr) else null
            } catch (_: Exception) { null }
        }

        val serving = cells.firstOrNull { it.pci == nrPci }
            ?: cells.firstOrNull { it.pci == ltePci }
            ?: cells.maxByOrNull { it.rsrp ?: -999 }

        serving?.let { cell ->
            if (!result.containsKey("sinr") && cell.sinr != null) result["sinr"] = cell.sinr
            if (!result.containsKey("rsrq") && cell.rsrq != null) result["rsrq"] = cell.rsrq
            if (!result.containsKey("rsrp") && cell.rsrp != null) result["rsrp"] = cell.rsrp
            if (!result.containsKey("rssi") && cell.rsrp != null && cell.rsrp in -140..-30) {
                result["rssi"] = cell.rsrp + 20
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
