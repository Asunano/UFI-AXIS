package com.ufi_axis_core.core.scheduler

import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.CpuInfo
import com.ufi_axis_core.collector.system.MemoryInfo
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.core.database.AppDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.ufi_axis_core.core.database.CpuHistoryRecord
import com.ufi_axis_core.core.database.SignalRecord
import com.ufi_axis_core.core.database.TrafficRecord
import com.ufi_axis_core.core.database.MemoryHistoryRecord
import com.ufi_axis_core.core.database.BatteryHistoryRecord
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import com.ufi_axis_core.api.websocket.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 多频率采集调度器
 *
 * 不同数据类型按不同频率采集:
 * - CPU/内存: 2s (高优先级)
 * - 信号/网速: 3s (中优先级)
 * - 流量统计: 5s (低优先级)
 * - 电池/温度: 30s
 *
 * 智能并发控制:
 * - 启动时检测CPU核心数、内存大小
 * - 每30秒检测设备性能(CPU/内存)
 * - 动态调整线程池大小
 * - 高负载时降低频率，低负载时提高频率
 *
 * 数据清理策略:
 * - 流量记录: 保留 24 小时
 * - 信号历史: 保留 7 天
 * - 告警记录: 保留 30 天
 * - 短信记录: 永久保留（不自动清理）
 * - 总量上限: ~150MB
 */
class DataScheduler(
    private val systemCollector: SystemCollector,
    private val telephonyCollector: TelephonyCollector,
    private val atChannel: ATChannel,
    private val database: AppDatabase,
    private val webSocketManager: WebSocketManager,
    private val goformClient: GoformClient? = null,
    private val alertEngine: com.ufi_axis_core.alert.AlertEngine? = null,
    private val dynamicThreadPool: DynamicThreadPool = DynamicThreadPool(),
    private val qosMiddleware: QoSMiddleware? = null
) {
    private val tag = "DataScheduler"
    // 使用 Dispatchers.Default，实际采集频率由 getAdaptiveDelay 根据 DynamicThreadPool 的性能评分动态调整
    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false
    private var performanceMonitorJob: Job? = null
    private var flushJob: Job? = null

    // 批量写入缓冲区 — 减少对 Room 的 I/O 次数，降低低端设备卡顿
    private val cpuBuffer = ConcurrentLinkedQueue<CpuHistoryRecord>()
    private val memoryBuffer = ConcurrentLinkedQueue<MemoryHistoryRecord>()
    private val trafficBuffer = ConcurrentLinkedQueue<TrafficRecord>()
    private val signalBuffer = ConcurrentLinkedQueue<SignalRecord>()

    // 最新数据缓存（供 API 快速读取）
    private val _latestTraffic = MutableStateFlow<TrafficRecord?>(null)
    val latestTraffic: StateFlow<TrafficRecord?> = _latestTraffic

    private val _latestSignal = MutableStateFlow<SignalRecord?>(null)
    val latestSignal: StateFlow<SignalRecord?> = _latestSignal

    /**
     * 统一信号获取接口 — 供 REST API 和其他组件调用。
     * 优先返回缓存值（<10秒），否则立即执行一次采集。
     * 所有信号消费者（WebSocket、REST、前端）共享同一数据源，避免重复查询。
     */
    suspend fun getSignalInfo(): Map<String, Any> {
        val cached = _latestSignal.value
        val age = System.currentTimeMillis() - (cached?.timestamp ?: 0)
        if (cached != null && age < 10_000) {
            return signalRecordToMap(cached)
        }
        // 缓存过期或不存在，执行一次采集
        val signalInfo = collectSignalUnified()
        val record = buildSignalRecord(signalInfo)
        _latestSignal.value = record
        return signalInfo
    }

    private fun signalRecordToMap(r: SignalRecord): Map<String, Any> = buildMap {
        if (r.rsrp != 0) put("rsrp", r.rsrp)
        if (r.sinr != 0) put("sinr", r.sinr)
        if (r.rsrq != 0) put("rsrq", r.rsrq)
        if (r.rssi != 0) put("rssi", r.rssi)
        if (r.rat.isNotEmpty()) put("rat", r.rat)
        if (r.operator.isNotEmpty()) put("operator", r.operator)
    }

    private val _latestCpu = MutableStateFlow<CpuInfo?>(null)
    val latestCpu: StateFlow<CpuInfo?> = _latestCpu

    private val _latestMemory = MutableStateFlow<MemoryInfo?>(null)
    val latestMemory: StateFlow<MemoryInfo?> = _latestMemory

    private val _latestBattery = MutableStateFlow<Map<String, Any>>(emptyMap())
    val latestBattery: StateFlow<Map<String, Any>> = _latestBattery

    // Goform 月流量缓存（减少对 goform API 的查询频率）
    private val _goformTraffic = MutableStateFlow<Pair<Long, Long>?>(null)  // (rxBytes, txBytes)
    val goformTraffic: StateFlow<Pair<Long, Long>?> = _goformTraffic

    fun start() {
        if (isRunning) return
        isRunning = true
        AppLogger.i(tag, "Starting data scheduler")

        // 启动时检测设备性能: CPU核心数、内存大小
        detectDevicePerformance()

        // 设备性能监控: 每30秒检查一次，动态调整线程池
        startPerformanceMonitor()

        // 批量刷写: 每 10 秒将缓冲区数据批量写入数据库，减少 I/O 次数
        flushJob = schedulerScope.launch {
            while (isActive) {
                delay(BATCH_FLUSH_INTERVAL_MS)
                flushBuffers()
            }
        }

        // CPU: 高优先级 2s
        schedulerScope.launch {
            while (isActive) {
                collectCpu()
                delay(getAdaptiveDelay(DataPriority.HIGH))
            }
        }

        // 内存: 高优先级 2s
        schedulerScope.launch {
            while (isActive) {
                collectMemory()
                delay(getAdaptiveDelay(DataPriority.HIGH))
            }
        }

        // 信号质量: 中优先级 3s
        schedulerScope.launch {
            while (isActive) {
                collectSignal()
                delay(getAdaptiveDelay(DataPriority.MEDIUM))
            }
        }

        // 实时网速: 中优先级 3s
        schedulerScope.launch {
            while (isActive) {
                collectTraffic()
                delay(getAdaptiveDelay(DataPriority.MEDIUM))
            }
        }

        // 流量统计: 60s 固定间隔（goform 查询频率受限，间隔太长会被拒）
        schedulerScope.launch {
            while (isActive) {
                collectGoformTraffic()
                delay(60_000L)
            }
        }

        // 电池/温度: 30s 固定间隔
        schedulerScope.launch {
            while (isActive) {
                collectBattery()
                delay(BATTERY_COLLECTION_INTERVAL_MS)
            }
        }

        // 数据清理: 每小时
        schedulerScope.launch {
            while (isActive) {
                delay(60 * 60 * 1000)
                cleanOldData()
            }
        }
    }

    fun stop() {
        isRunning = false
        performanceMonitorJob?.cancel()
        flushJob?.cancel()
        // 停止前刷写剩余缓冲数据，避免数据丢失
        schedulerScope.launch { flushBuffers() }
        schedulerScope.coroutineContext.cancelChildren()
        AppLogger.i(tag, "Data scheduler stopped")
    }

    /**
     * 启动时检测设备性能: CPU核心数、内存大小
     */
    private fun detectDevicePerformance() {
        schedulerScope.launch {
            try {
                val cpuInfo = systemCollector.getCpuInfo()
                val memoryInfo = systemCollector.getMemoryInfo()
                _latestCpu.value = cpuInfo
                _latestMemory.value = memoryInfo
                AppLogger.i(tag, "Device performance: CPU cores=${cpuInfo.cores.size}, usage=${cpuInfo.usage_percent}%, memory total=${memoryInfo.total / 1024 / 1024}MB, used=${memoryInfo.used / 1024 / 1024}MB")
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to detect device performance", e)
            }
        }
    }

    private fun startPerformanceMonitor() {
        performanceMonitorJob = schedulerScope.launch {
            while (isActive) {
                delay(PERFORMANCE_CHECK_INTERVAL_MS)
                try {
                    val cpuInfo = systemCollector.getCpuInfo()
                    val memoryInfo = systemCollector.getMemoryInfo()
                    val cpuUsage = cpuInfo.usage_percent.toFloat()
                    val freeMemory = memoryInfo.total - memoryInfo.used

                    dynamicThreadPool.adjustByPerformance(cpuUsage, freeMemory)

                    // CPU 温度采集（供自适应策略和熔断共用）
                    val maxTemp = readMaxCpuTemp()

                    // 自适应 ShellQoS 策略：根据温度和 CPU 负载动态调整 root 并发数
                    val adaptiveRootPermits = when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD -> 1   // 85°C+ 极限收缩
                        maxTemp > THERMAL_WARNING_THRESHOLD -> 2    // 75°C+ 温和收缩
                        cpuUsage > 80f -> 2                          // CPU 高负载
                        cpuUsage > 50f -> 3                          // CPU 中负载，保持默认
                        else -> 4                                    // 低负载，稍微放宽
                    }
                    ShellQoS.adaptiveAdjust(adaptiveRootPermits)

                    // 自适应 cache TTL：高负载时延长缓存减少 shell 调用
                    val adaptiveTtl = when {
                        maxTemp > THERMAL_WARNING_THRESHOLD -> 5000L   // 5s
                        cpuUsage > 70f -> 4000L                          // 4s
                        else -> 2000L                                     // 默认 2s
                    }
                    ShellQoS.updateCacheTtl(adaptiveTtl)

                    // QoSMiddleware 感知 ShellQoS 负载，同步调整请求级并发
                    qosMiddleware?.adjustByLoad(
                        ShellQoS.rootAvailablePermits,
                        ShellQoS.rootTotalPermits
                    )

                    AppLogger.d(tag, "Performance check: cpu=${cpuUsage}%, freeMem=${freeMemory / 1024 / 1024}MB, poolSize=${dynamicThreadPool.getThreadPoolInfo().maxPoolSize}, rootPermits=${ShellQoS.rootTotalPermits}(target=${ShellQoS.rootTargetPermits}), cacheTtl=${adaptiveTtl}ms")

                    // 温度熔断
                    when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD -> {
                            // 85°C: 紧急降温 — 清空可用 shell 许可，暂停 10s
                            ShellQoS.clearCache()
                            AppLogger.w(tag, "Thermal critical: ${maxTemp / 1000}°C > ${THERMAL_CRITICAL_THRESHOLD / 1000}°C, clearing shell cache and pausing")
                            delay(THERMAL_PAUSE_MS)
                        }
                        maxTemp > THERMAL_WARNING_THRESHOLD -> {
                            // 75°C: 警告（频率降低已由 getAdaptiveDelay 处理）
                            AppLogger.w(tag, "Thermal warning: ${maxTemp / 1000}°C > ${THERMAL_WARNING_THRESHOLD / 1000}°C")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(tag, "Performance check failed", e)
                }
            }
        }
    }

    private fun getAdaptiveDelay(priority: DataPriority): Long {
        val baseDelay = when (priority) {
            DataPriority.HIGH -> 2_000L    // CPU/内存: 2s base
            DataPriority.MEDIUM -> 3_000L   // 网速/信号: 3s base
            DataPriority.LOW -> 5_000L      // 流量统计: 5s base
        }

        val poolSize = dynamicThreadPool.getThreadPoolInfo().maxPoolSize
        val poolFactor = when {
            poolSize <= 1 -> 2.0           // 高负载时降低频率
            poolSize <= 2 -> 1.5           // 中负载时适度降低
            poolSize >= 4 -> 0.75          // 低负载时提高频率
            else -> 1.0                    // 正常频率
        }

        // Shell 负载因子：shell 满载时延迟翻 3 倍
        val rootTotal = ShellQoS.rootTotalPermits
        val rootAvail = ShellQoS.rootAvailablePermits
        val shellLoad = if (rootTotal > 0) 1.0 - (rootAvail.toDouble() / rootTotal) else 0.0
        val shellFactor = 1.0 + shellLoad * 2.0

        return (baseDelay * poolFactor * shellFactor).toLong()
    }

    private suspend fun collectTraffic() {
        try {
            val trafficInfo = systemCollector.getTrafficStats()
            val record = TrafficRecord(
                rxBytes = trafficInfo["rx_bytes"] as? Long ?: 0,
                txBytes = trafficInfo["tx_bytes"] as? Long ?: 0,
                rxSpeed = trafficInfo["rx_speed"] as? Long ?: 0,
                txSpeed = trafficInfo["tx_speed"] as? Long ?: 0
            )
            trafficBuffer.offerBounded(record)
            _latestTraffic.value = record

            // WebSocket 推送（含 display 字段，与 REST /api/traffic/realtime 对齐）
            webSocketManager.broadcast("traffic", mapOf(
                "rx_speed" to record.rxSpeed,
                "tx_speed" to record.txSpeed,
                "rx_bytes" to record.rxBytes,
                "tx_bytes" to record.txBytes,
                "rx_speed_display" to formatSpeed(record.rxSpeed),
                "tx_speed_display" to formatSpeed(record.txSpeed),
                "timestamp" to System.currentTimeMillis()
            ))

            // 告警检查: 流量超额（将字节转换为 MB）
            val totalMb = (record.rxBytes + record.txBytes) / (1024 * 1024)
            alertEngine?.checkTraffic(totalMb)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect traffic", e)
        }
    }

    private suspend fun collectSignal() {
        try {
            val signalInfo = collectSignalUnified()
            val record = buildSignalRecord(signalInfo)

            // 仅在有有效信号值时才写入历史数据库
            if (record.rsrp != 0 || record.sinr != 0 || record.rssi != 0) {
                signalBuffer.offerBounded(record)
            } else {
                AppLogger.d(tag, "Signal record skipped: all metrics are 0 (rsrp=${record.rsrp}, sinr=${record.sinr}, rssi=${record.rssi})")
            }
            _latestSignal.value = record

            // WebSocket 推送（过滤空字符串值）
            val cleanSignalInfo = signalInfo.filterValues { value ->
                when (value) {
                    is String -> value.isNotEmpty()
                    is JsonElement -> value.toString().trim('"').isNotEmpty()
                    else -> true
                }
            }
            webSocketManager.broadcast("signal", cleanSignalInfo)

            // 告警检查: 信号质量
            val rsrp = signalInfo.intValue("rsrp")
            if (rsrp != 0) {
                alertEngine?.checkSignal(rsrp)
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect signal", e)
        }
    }

    /**
     * 统一信号采集 — 四层优先级:
     * 1. Goform 独立字段（Z5g_rsrp、Nr_snr、nr_rsrq 等）
     * 2. Goform neighbor_cell_info + PCI 匹配（提取服务小区的 SINR/RSRQ）
     * 3. AT+CESQ（标准字段补充，扩展字段在此设备不可靠仅做最后手段）
     * 4. TelephonyCollector 兜底
     */
    private suspend fun collectSignalUnified(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val goformSignal = goformClient?.getSignalInfo()
        // Goform 辅助函数
        fun goformStr(key: String): String? =
            goformSignal?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun goformInt(key: String): Int? = goformStr(key)?.toIntOrNull()

        // ── 第 1 层: Goform 独立字段 ──
        // RSRP: 5G > 4G
        goformInt("Z5g_rsrp")?.let { result["rsrp"] = it }
        if (!result.containsKey("rsrp")) goformInt("lte_rsrp")?.let { result["rsrp"] = it }
        // SINR: Nr_snr(5G) > Lte_snr(4G)
        goformInt("Nr_snr")?.let { result["sinr"] = it }
        if (!result.containsKey("sinr")) goformInt("Lte_snr")?.let { result["sinr"] = it }
        // RSRQ: nr_rsrq(5G) > lte_rsrq(4G)
        goformInt("nr_rsrq")?.let { result["rsrq"] = it }
        if (!result.containsKey("rsrq")) goformInt("lte_rsrq")?.let { result["rsrq"] = it }
        // RSSI: nr_rssi(5G) > lte_rssi(4G)（注意: goform 'rssi' 字段是信号条数 0-5）
        goformInt("nr_rssi")?.let { result["rssi"] = it }
        if (!result.containsKey("rssi")) goformInt("lte_rssi")?.let { result["rssi"] = it }
        // 元数据
        goformStr("network_type")?.let { result["rat"] = GoformClient.mapNetworkType(it) }
        goformStr("cell_id")?.let { result["cell_id"] = it }
        val provider = goformStr("network_provider")
        result["operator"] = if (!provider.isNullOrBlank()) provider else telephonyCollector.getOperatorName()

        // ── 第 2 层: neighbor_cell_info + PCI 匹配 ──
        if (goformSignal != null && (!result.containsKey("sinr") || !result.containsKey("rsrq"))) {
            extractFromNeighborCells(goformSignal, result)
        }

        // ── 第 3 层: AT+CESQ 补充（标准字段可靠，扩展字段此设备不可靠） ──
        supplementFromAT(result)

        // ── 第 4 层: TelephonyCollector 兜底 ──
        supplementFromTelephony(result)
        return result
    }

    /**
     * 第 2 层: 从 Goform neighbor_cell_info 提取服务小区信号。
     * neighbor_cell_info 返回 [{band, earfcn, pci, rsrp, rsrq, sinr}, ...]，
     * 用 Nr_pci/Lte_pci 匹配服务小区 PCI 提取 SINR/RSRQ。
     */
    private fun extractFromNeighborCells(goformSignal: JsonObject, result: MutableMap<String, Any>) {
        val neighborArray = try {
            goformSignal["neighbor_cell_info"]?.jsonArray
        } catch (_: Exception) { null } ?: return

        // 获取服务小区 PCI
        fun pciValue(key: String): Int? =
            goformSignal[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val nrPci = pciValue("Nr_pci")
        val ltePci = pciValue("Lte_pci")

        // 解析邻区列表
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

        // PCI 匹配策略: Nr_pci → Lte_pci → 最强信号小区
        val serving = cells.firstOrNull { it.pci == nrPci }
            ?: cells.firstOrNull { it.pci == ltePci }
            ?: cells.maxByOrNull { it.rsrp ?: -999 }

        serving?.let { cell ->
            if (!result.containsKey("sinr") && cell.sinr != null) result["sinr"] = cell.sinr
            if (!result.containsKey("rsrq") && cell.rsrq != null) result["rsrq"] = cell.rsrq
            if (!result.containsKey("rsrp") && cell.rsrp != null) result["rsrp"] = cell.rsrp
            // neighbor_cell_info 无 RSSI 字段，从 RSRP 估算
            if (!result.containsKey("rssi") && cell.rsrp != null && cell.rsrp in -140..-30) {
                result["rssi"] = cell.rsrp + 20
            }
        }
    }

    private data class NeighborCell(
        val pci: Int, val rsrp: Int?, val rsrq: Int?, val sinr: Int?
    )

    /**
     * 第 3 层: AT+CESQ 补充缺失信号字段。
     * 标准 LTE 字段（SS 公式）可靠；扩展字段 7-9 在此 ZTE 设备编码不匹配标准公式，
     * 仅在标准字段全部缺失时才使用。
     */
    private suspend fun supplementFromAT(result: MutableMap<String, Any>) {
        val needed = listOf("rsrp", "rsrq", "sinr", "rssi").any { !result.containsKey(it) }
        if (!needed) return

        val atSignal = try { atChannel.getSignalQuality() } catch (_: Exception) { return }

        for (key in listOf("rsrp", "rsrq", "sinr")) {
            if (result.containsKey(key)) continue
            (atSignal[key] as? Number)?.let { result[key] = it.toInt() }
        }
        if (!result.containsKey("rssi")) {
            (atSignal["rssi"] as? Number)?.toInt()?.let { rssi ->
                if (rssi in -120..-30) {
                    val rsrp = (result["rsrp"] as? Int)
                    if (rsrp == null || rssi >= rsrp) result["rssi"] = rssi
                }
            }
        }
    }

    /**
     * 第 4 层兜底: 用 TelephonyCollector 补充 Goform、neighbor_cell_info 和 AT 均缺失的字段。
     * RSSI 最终回退: 从 RSRP 估算 (RSSI ≈ RSRP + 20, 假设 20MHz/100RB)。
     */
    private suspend fun supplementFromTelephony(result: MutableMap<String, Any>) {
        val telephonySignal by lazy { telephonyCollector.getSignalInfo() }

        // RSSI: Goform 的 lte_rssi/nr_rssi 缺失时，尝试 Telephony，再从 RSRP 估算
        if (!result.containsKey("rssi")) {
            val telephonyRssi = telephonySignal["rssi"]
            val existingRsrp = result["rsrp"]?.let {
                when (it) {
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            }
            // 物理约束: RSSI(宽带功率) 必须 >= RSRP(单子载波功率)
            // Telephony 返回 -113(asu=0) 但 RSRP=-65 时不可能，拒绝并走估算路径
            if (telephonyRssi != null && telephonyRssi is Number && telephonyRssi.toInt() in -120..-30) {
                val rssiVal = telephonyRssi.toInt()
                if (existingRsrp == null || rssiVal >= existingRsrp) {
                    result["rssi"] = telephonyRssi
                }
            }
            // 最终回退: 从 RSRP 估算 RSSI (≈ RSRP + 20, 假设 20MHz/100RB)
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
        // 运营商: PLMN 纯数字码或 Unknown → 可读运营商名
        val operator = result["operator"]?.toString()
        if (operator.isNullOrBlank() || operator == "Unknown" || operator.all { it.isDigit() }) {
            result["operator"] = telephonyCollector.getOperatorName()
        }
    }

    private fun buildSignalRecord(signalInfo: Map<String, Any>): SignalRecord = SignalRecord(
        rsrp = signalInfo.intValue("rsrp"),
        sinr = signalInfo.intValue("sinr"),
        rsrq = signalInfo.intValue("rsrq"),
        rssi = signalInfo.intValue("rssi"),
        rat = signalInfo.stringValue("rat", ""),
        operator = signalInfo.stringValue("operator", "")
    )

    private suspend fun collectCpu() {
        try {
            val cpuInfo = systemCollector.getCpuInfo()
            _latestCpu.value = cpuInfo

            val maxFreq = cpuInfo.cores.maxOfOrNull { it.freq_mhz } ?: 0.0
            val cpuRecord = CpuHistoryRecord(
                usagePercent = cpuInfo.usage_percent,
                coreCount = cpuInfo.core_count,
                maxFreqMhz = maxFreq,
                temperature = cpuInfo.temperature
            )
            cpuBuffer.offerBounded(cpuRecord)

            webSocketManager.broadcast("cpu", mapOf(
                "usage_percent" to cpuInfo.usage_percent,
                "core_count" to cpuInfo.core_count,
                "cores" to cpuInfo.cores.map { mapOf("core" to it.core, "freq_mhz" to it.freq_mhz, "freq_display" to it.freq_display) },
                "temperature" to cpuInfo.temperature
            ))

            // 告警检查: CPU 温度
            alertEngine?.checkTemperature(cpuInfo.temperature)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect CPU", e)
        }
    }

    private suspend fun collectBattery() {
        try {
            val batteryInfo = systemCollector.getBatteryInfo()
            _latestBattery.value = batteryInfo

            // 持久化电池历史
            database.batteryHistoryDao().insert(BatteryHistoryRecord(
                level = (batteryInfo["percent"] as? Int) ?: (batteryInfo["level"] as? Int) ?: 0,
                isCharging = batteryInfo["is_charging"] as? Boolean ?: false,
                temperature = (batteryInfo["temperature"] as? Number)?.toDouble() ?: 0.0,
                voltage = (batteryInfo["voltage"] as? Number)?.toDouble() ?: 0.0
            ))

            webSocketManager.broadcast("battery", batteryInfo)

            // 告警检查: 电池电量
            val level = (batteryInfo["percent"] as? Int)
                ?: (batteryInfo["level"] as? Number)?.toInt() ?: 100
            val isCharging = batteryInfo["is_charging"] as? Boolean ?: false
            alertEngine?.checkBattery(level, isCharging)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect battery", e)
        }
    }

    private suspend fun collectMemory() {
        try {
            val memoryInfo = systemCollector.getMemoryInfo()
            _latestMemory.value = memoryInfo

            // 缓冲内存历史记录，批量写入减少 I/O
            memoryBuffer.offerBounded(MemoryHistoryRecord(
                total = memoryInfo.total,
                used = memoryInfo.used,
                available = memoryInfo.available,
                usagePercent = memoryInfo.usage_percent
            ))

            webSocketManager.broadcast("memory", mapOf(
                "total" to memoryInfo.total,
                "used" to memoryInfo.used,
                "available" to memoryInfo.available,
                "free" to memoryInfo.free,
                "buffers" to memoryInfo.buffers,
                "cached" to memoryInfo.cached,
                "usage_percent" to memoryInfo.usage_percent
            ))
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect memory", e)
        }
    }

    private suspend fun collectGoformTraffic() {
        try {
            val stats = goformClient?.getTrafficStats()
            if (stats != null && stats.isNotEmpty()) {
                val rx = stats["monthly_rx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                val tx = stats["monthly_tx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                if (rx > 0 || tx > 0) {
                    _goformTraffic.value = Pair(rx, tx)
                    webSocketManager.broadcast("traffic_stats", mapOf(
                        "monthly_rx_bytes" to rx, "monthly_tx_bytes" to tx
                    ))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect goform traffic", e)
        }
    }

    /**
     * 批量刷写缓冲区数据到数据库
     * 将累积的记录一次性写入，大幅减少 I/O 次数
     */
    private suspend fun flushBuffers() {
        try {
            val cpuBatch = mutableListOf<CpuHistoryRecord>()
            while (true) { cpuBuffer.poll()?.let { cpuBatch.add(it) } ?: break }
            if (cpuBatch.isNotEmpty()) database.cpuHistoryDao().insertAll(cpuBatch)

            val memBatch = mutableListOf<MemoryHistoryRecord>()
            while (true) { memoryBuffer.poll()?.let { memBatch.add(it) } ?: break }
            if (memBatch.isNotEmpty()) database.memoryHistoryDao().insertAll(memBatch)

            val trafficBatch = mutableListOf<TrafficRecord>()
            while (true) { trafficBuffer.poll()?.let { trafficBatch.add(it) } ?: break }
            if (trafficBatch.isNotEmpty()) database.trafficDao().insertAll(trafficBatch)

            val signalBatch = mutableListOf<SignalRecord>()
            while (true) { signalBuffer.poll()?.let { signalBatch.add(it) } ?: break }
            if (signalBatch.isNotEmpty()) database.signalDao().insertAll(signalBatch)

            val totalFlushed = cpuBatch.size + memBatch.size + trafficBatch.size + signalBatch.size
            if (totalFlushed > 0) {
                AppLogger.d(tag, "Flushed buffers: cpu=${cpuBatch.size}, mem=${memBatch.size}, traffic=${trafficBatch.size}, signal=${signalBatch.size}")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to flush buffers", e)
        }
    }

    /**
     * 清理过期数据，总量上限约 150MB
     */
    private suspend fun cleanOldData() {
        try {
            val now = System.currentTimeMillis()
            val monitorCutoff = now - HISTORY_RETENTION_DAYS * 24 * 60 * 60 * 1000L

            // 监控数据统一保留 HISTORY_RETENTION_DAYS 天
            val trafficDeleted = database.trafficDao().deleteOlderThan(monitorCutoff)
            val signalDeleted = database.signalDao().deleteOlderThan(monitorCutoff)
            val cpuDeleted = database.cpuHistoryDao().deleteOlderThan(monitorCutoff)
            val memoryDeleted = database.memoryHistoryDao().deleteOlderThan(monitorCutoff)
            val batteryDeleted = database.batteryHistoryDao().deleteOlderThan(monitorCutoff)
            // 告警记录: 保留 30 天
            val alertDeleted = database.alertDao().deleteOlderThan(now - 30L * 24 * 60 * 60 * 1000L)
            // 短信记录: 永久保留，不自动清理

            AppLogger.i(tag, "Cleaned data: cpu=$cpuDeleted, memory=$memoryDeleted, traffic=$trafficDeleted, signal=$signalDeleted, battery=$batteryDeleted, alert=$alertDeleted")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to clean data", e)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1_048_576 -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
            bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    /**
     * 有界入队：超过 MAX_BUFFER_SIZE 时丢弃最旧记录，防止 DB 写入卡住时内存无限增长
     */
    private fun <T> ConcurrentLinkedQueue<T>.offerBounded(item: T) {
        while (size >= MAX_BUFFER_SIZE) poll()
        offer(item)
    }

    /**
     * 读取 CPU 最高温度（毫摄氏度）
     * 从 /sys/class/thermal/ 下所有 thermal_zone 的 temp 文件读取，返回最大值
     */
    private suspend fun readMaxCpuTemp(): Int {
        return try {
            val result = ShellExecutor.execute("cat /sys/class/thermal/thermal_zone*/temp")
            result.stdout.lines()
                .mapNotNull { it.trim().toIntOrNull() }
                .maxOrNull() ?: 0
        } catch (_: Exception) { 0 }
    }

    private companion object {
        const val PERFORMANCE_CHECK_INTERVAL_MS = 30_000L
        const val BATTERY_COLLECTION_INTERVAL_MS = 30_000L
        const val BATCH_FLUSH_INTERVAL_MS = 10_000L        // 缓冲区刷写间隔（10秒）
        const val MAX_BUFFER_SIZE = 100                     // 单缓冲区最大记录数（防 DB 写入卡住时内存溢出）
        const val HISTORY_RETENTION_DAYS = 7L               // 监控数据保留天数
        const val HISTORY_QUERY_LIMIT = 50_000               // 单次查询最大记录数
        const val THERMAL_WARNING_THRESHOLD = 75_000    // 75°C (milli-degrees)
        const val THERMAL_CRITICAL_THRESHOLD = 85_000  // 85°C (milli-degrees)
        const val THERMAL_PAUSE_MS = 10_000L           // 温度熔断暂停时间
    }

    enum class DataPriority {
        HIGH,
        MEDIUM,
        LOW
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.intValue(key: String): Int {
    val v = this[key] ?: return 0
    return when (v) {
        is Number -> v.toInt()
        is JsonPrimitive -> v.content.toIntOrNull() ?: 0
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }
}

private fun Map<String, Any>.stringValue(key: String, default: String = ""): String {
    val v = this[key] ?: return default
    return when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
}
