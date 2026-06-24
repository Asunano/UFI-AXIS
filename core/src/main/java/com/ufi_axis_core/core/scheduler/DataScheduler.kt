package com.ufi_axis_core.core.scheduler

import com.ufi_axis_core.collector.signal.SignalCollector
import com.ufi_axis_core.collector.system.CpuInfo
import com.ufi_axis_core.collector.system.MemoryInfo
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.core.database.AppDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import com.ufi_axis_core.core.database.CpuHistoryRecord
import com.ufi_axis_core.core.database.SignalRecord
import com.ufi_axis_core.core.database.TrafficRecord
import com.ufi_axis_core.core.database.MemoryHistoryRecord
import com.ufi_axis_core.core.database.BatteryHistoryRecord
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import com.ufi_axis_core.api.websocket.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 多频率采集调度器
 *
 * ════════════════════════════════════════
 * 数据分类与采集策略
 * ════════════════════════════════════════
 *
 * 【实时数据】— 需要高频采集，不缓存或短缓存
 * - 信号质量 (RSRP/SINR/RSRQ/RSSI): 自适应 MEDIUM（~10s）
 * - 实时网速/吞吐量 (goform thrpt + TrafficStats): 自适应 MEDIUM（~10s）
 * - CPU/内存: 自适应 MEDIUM（~10s）
 * - WiFi 客户端列表: TTL 15s（实时性要求高）
 *
 * 【可缓存数据】— 变化缓慢，低频采集 + 长缓存
 * - 月流量统计: 120s 固定间隔
 * - 电池/温度: 120s 固定间隔
 * - SMS 联系人缓存: 60s 固定间隔
 * - 设备信息/固件版本: TTL 10-30min
 * - 运营商/SIM 卡信息: TTL 15min
 * - 设备设置/LAN/APN: TTL 5-10min
 *
 * 智能并发控制:
 * - 启动时检测CPU核心数、内存大小
 * - 每60秒检测设备性能(CPU/内存)
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
    private val database: AppDatabase,
    private val webSocketManager: WebSocketManager,
    private val signalClient: GoformSignalClient? = null,
    private val smsClient: GoformSmsClient? = null,
    private val alertEngine: com.ufi_axis_core.alert.AlertEngine? = null,
    private val dynamicThreadPool: DynamicThreadPool = DynamicThreadPool()
) {
    private val tag = "DataScheduler"
    // 使用 Dispatchers.IO 而非 Default — DataScheduler 所有协程都在做 IO（shell/http/db），
    // 占 Default 线程池会导致 CPU-bound 协程饥饿
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(tag, "DataScheduler coroutine exception (uncaught)", e)
    })
    private var isRunning = false
    private var performanceMonitorJob: Job? = null
    private var flushJob: Job? = null

    // 信号采集器（三层优先级合并逻辑独立管理）
    private val signalCollector = SignalCollector(signalClient, telephonyCollector)

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
            // 缓存命中：SignalRecord 不存储 network_registered，从 TelephonyCollector 实时补充
            val map = signalRecordToMap(cached).toMutableMap()
            map["network_registered"] = telephonyCollector.isNetworkRegistered()
            return map
        }
        val signalInfo = signalCollector.collect()
        val record = signalCollector.buildRecord(signalInfo)
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

    // Goform 实时吞吐量缓存（来自 Modem 固件直接上报，比 Android TrafficStats 更准确）
    @Volatile private var goformRxThrpt: Long = 0L
    @Volatile private var goformTxThrpt: Long = 0L

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

        // ── 【实时数据】CPU + 内存: 合并到同一协程，减少并发协程数（中优先级 10s 自适应）
        schedulerScope.launch {
            while (isActive) {
                collectCpu()
                collectMemory()
                delay(getAdaptiveDelay(DataPriority.MEDIUM))
            }
        }

        // ── 【实时数据】信号 + 实时吞吐量 + 网速（合并为 1 条 goform 查询，原 3 条）
        schedulerScope.launch {
            while (isActive) {
                collectSignal()
                collectTraffic()
                delay(getAdaptiveDelay(DataPriority.MEDIUM))
            }
        }

        // ── 【可缓存数据】月流量统计: 120s 固定间隔
        schedulerScope.launch {
            while (isActive) {
                collectGoformTraffic()
                delay(120_000L)
            }
        }

        // ── 【可缓存数据】SMS 联系人缓存: 60s
        schedulerScope.launch {
            while (isActive) {
                collectSmsCache()
                delay(60_000L)
            }
        }

        // ── 【可缓存数据】电池/温度: 120s
        schedulerScope.launch {
            while (isActive) {
                collectBattery()
                delay(120_000L)
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
        // 停止前同步刷写剩余缓冲数据（runBlocking 确保 flush 完成后再取消子协程，
        // 避免 launch + cancelChildren 竞态导致 flush 被中断、数据丢失）
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { flushBuffers() }
        } catch (_: Exception) {}
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
                    // maxPermits=4 → 极限不超过 4，防止 Unisoc 内核 sprd_ipc_probe 竞态
                    val adaptiveRootPermits = when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD -> 1   // 85°C+ 极限收缩
                        maxTemp > THERMAL_WARNING_THRESHOLD -> 2    // 75°C+ 温和收缩
                        cpuUsage > 80f -> 2                          // CPU 高负载
                        cpuUsage > 50f -> 3                          // CPU 中负载，保持默认
                        else -> 3                                    // 低负载，不放大（maxPermits=4 留 1 给 AT/ShellRoutes）
                    }
                    ShellQoS.adaptiveAdjust(adaptiveRootPermits)

                    // 自适应 cache TTL：高负载时延长缓存减少 shell 调用
                    val adaptiveTtl = when {
                        maxTemp > THERMAL_WARNING_THRESHOLD -> 5000L   // 5s
                        cpuUsage > 70f -> 4000L                          // 4s
                        else -> 2000L                                     // 默认 2s
                    }
                    ShellQoS.updateCacheTtl(adaptiveTtl)

                    // 自适应 GoformQoS 策略：控制后端→设备 HTTP 通信并发
                    val goformQueryPermits = when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD -> 1
                        maxTemp > THERMAL_WARNING_THRESHOLD || cpuUsage > 80f -> 2
                        cpuUsage > 50f -> 3
                        else -> 5
                    }
                    val goformSetPermits = when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD || cpuUsage > 80f -> 1
                        else -> 2
                    }
                    GoformQoS.adaptiveAdjust(goformQueryPermits, goformSetPermits)
                    GoformQoS.updateCacheTtl(adaptiveTtl)

                    AppLogger.d(tag, "Performance check: cpu=${cpuUsage}%, freeMem=${freeMemory / 1024 / 1024}MB, poolSize=${dynamicThreadPool.getThreadPoolInfo().maxPoolSize}, rootPermits=${ShellQoS.rootTotalPermits}(target=${ShellQoS.rootTargetPermits}), goformQ=${GoformQoS.queryTotalPermits}(t=${GoformQoS.queryTargetPermits}), goformS=${GoformQoS.setTotalPermits}, cacheTtl=${adaptiveTtl}ms")

                    // 温度熔断
                    when {
                        maxTemp > THERMAL_CRITICAL_THRESHOLD -> {
                            // 85°C: 紧急降温 — 清空 shell 和 goform 缓存，暂停 10s
                            ShellQoS.clearCache()
                            GoformQoS.clearCache()
                            AppLogger.w(tag, "Thermal critical: ${maxTemp / 1000}°C > ${THERMAL_CRITICAL_THRESHOLD / 1000}°C, clearing caches and pausing")
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
            DataPriority.HIGH -> 6_000L    // CPU/内存: 6s base (was 3s) — 加倍放慢
            DataPriority.MEDIUM -> 10_000L   // 网速/信号: 10s base (was 5s) — 加倍放慢
            DataPriority.LOW -> 20_000L      // 流量统计: 20s base (was 10s) — 加倍放慢
        }

        val poolSize = dynamicThreadPool.getThreadPoolInfo().maxPoolSize
        // 取最大因子，而非乘法乘积，防止极端情况下延迟暴增到 75 秒
        val poolFactor = when {
            poolSize <= 1 -> 2.0
            poolSize <= 2 -> 1.5
            poolSize >= 4 -> 0.75
            else -> 1.0
        }

        val rootTotal = ShellQoS.rootTotalPermits
        val rootAvail = ShellQoS.rootAvailablePermits
        val shellLoad = if (rootTotal > 0) 1.0 - (rootAvail.toDouble() / rootTotal) else 0.0
        val shellFactor = 1.0 + shellLoad * 2.0

        val goformTotal = GoformQoS.queryTotalPermits
        val goformAvail = GoformQoS.queryAvailablePermits
        val goformLoad = if (goformTotal > 0) 1.0 - (goformAvail.toDouble() / goformTotal) else 0.0
        val goformFactor = 1.0 + goformLoad * 1.5

        // 取三个因子的最大值（而非乘积），保证最大延迟不超过 baseDelay * 2.0
        val combinedFactor = maxOf(poolFactor, shellFactor, goformFactor).coerceIn(0.5, 2.0)

        return (baseDelay * combinedFactor).toLong()
    }

    // 上一次 TrafficStats 累计值，用于计算实时速率差值
    @Volatile private var prevRxBytes: Long = 0L
    @Volatile private var prevTxBytes: Long = 0L
    @Volatile private var prevTrafficTime: Long = 0L

    private suspend fun collectTraffic() {
        try {
            val trafficInfo = systemCollector.getTrafficStats()
            val rxBytes = trafficInfo["rx_bytes"] as? Long ?: 0L
            val txBytes = trafficInfo["tx_bytes"] as? Long ?: 0L
            val now = System.currentTimeMillis()

            // ── 实时速率：优先 goform thrpt（Modem 固件），回退到 TrafficStats 差值 ──
            var rxSpeed = goformRxThrpt
            var txSpeed = goformTxThrpt

            if (rxSpeed <= 0 && txSpeed <= 0) {
                // goform 数据不可用时，用 TrafficStats 差值计算瞬时速率
                val elapsed = (now - prevTrafficTime).coerceAtLeast(100L) / 1000.0  // 秒
                if (prevTrafficTime > 0 && elapsed > 0) {
                    rxSpeed = ((rxBytes - prevRxBytes).coerceAtLeast(0L) / elapsed).toLong()
                    txSpeed = ((txBytes - prevTxBytes).coerceAtLeast(0L) / elapsed).toLong()
                }
            }

            prevRxBytes = rxBytes; prevTxBytes = txBytes; prevTrafficTime = now

            val record = TrafficRecord(
                rxBytes = rxBytes,
                txBytes = txBytes,
                rxSpeed = rxSpeed,
                txSpeed = txSpeed
            )
            trafficBuffer.offerBounded(record)
            _latestTraffic.value = record

            // WebSocket 推送（含 goform thrpt 实时数据和 display 字段）
            webSocketManager.broadcast("traffic", mapOf(
                "rx_speed" to record.rxSpeed,
                "tx_speed" to record.txSpeed,
                "rx_bytes" to record.rxBytes,
                "tx_bytes" to record.txBytes,
                "realtime_rx_thrpt" to goformRxThrpt,
                "realtime_tx_thrpt" to goformTxThrpt,
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
            // ── 单次合并查询（信号 16 字段 + 实时吞吐量 2 字段）─
            // 替代原 getSignalInfo() 2 次 + getTrafficThrpt() 1 次
            val goformData = try {
                kotlinx.coroutines.withTimeout(5_000L) {
                    signalClient?.getSignalInfo()
                }
            } catch (_: Exception) {
                null
            }

            // ── 提取实时吞吐量（来自合并查询结果）─
            if (goformData != null) {
                val rx = goformData["realtime_rx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                val tx = goformData["realtime_tx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                goformRxThrpt = rx
                goformTxThrpt = tx
            }

            // ── 信号采集（传入已预取的 goform 数据，避免重复查询）─
            val signalInfo = signalCollector.collect(goformData)
            val record = signalCollector.buildRecord(signalInfo)

            // ── 以下保持与原有逻辑一致 ──
            if (record.rsrp != 0 || record.sinr != 0 || record.rssi != 0) {
                signalBuffer.offerBounded(record)
            } else {
                AppLogger.d(tag, "Signal record skipped: all metrics are 0 (rsrp=${record.rsrp}, sinr=${record.sinr}, rssi=${record.rssi})")
            }
            _latestSignal.value = record

            val cleanSignalInfo = signalInfo.filterValues { value ->
                when (value) {
                    is String -> value.isNotEmpty()
                    is JsonElement -> value.toString().trim('"').isNotEmpty()
                    else -> true
                }
            }
            webSocketManager.broadcast("signal", cleanSignalInfo)

            val rsrp = (signalInfo["rsrp"] as? Number)?.toInt() ?: 0
            if (rsrp != 0) {
                alertEngine?.checkSignal(rsrp)
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect signal", e)
        }
    }

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
            val stats = signalClient?.getTrafficStats()
            if (stats != null && stats.isNotEmpty()) {
                // 月累计流量
                val rx = stats["monthly_rx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                val tx = stats["monthly_tx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                if (rx > 0 || tx > 0) {
                    _goformTraffic.value = Pair(rx, tx)
                    webSocketManager.broadcast("traffic_stats", mapOf(
                        "monthly_rx_bytes" to rx, "monthly_tx_bytes" to tx
                    ))
                }
                // 实时吞吐量（Modem 固件直接上报，bytes/s）
                val rxThrpt = stats["realtime_rx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                val txThrpt = stats["realtime_tx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                goformRxThrpt = rxThrpt
                goformTxThrpt = txThrpt
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect goform traffic", e)
        }
    }

    // ── SMS 缓存：后台 goform 轮询 + 按联系人聚合 ──

    // 缓存的联系人列表（按号码聚合，含未读数/总数/最新消息）
    @Volatile private var cachedSmsContacts: List<Map<String, Any>> = emptyList()
    // 防漏检测：记录上次快速轮询的最高ID，用于判断两次轮询间是否遗漏消息
    @Volatile private var lastPollMaxSmsId: Long = 0L

    private suspend fun collectSmsCache() {
        try {
            val sms = smsClient ?: return
            // 轮询最新 5 条消息（覆盖验证码等场景），30s 间隔足够
            val smsData = sms.getSmsList(page = 0, perPage = FAST_PER_PAGE)
            if (smsData == null) return
            val arr = smsData["messages"]?.jsonArray ?: return

            val messages = arr.mapNotNull { el ->
                try {
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = decodeSmsB64(
                        obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0"
                    )
                    val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                    val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val direction = when (tag) { "2", "3" -> "sent"; else -> "received" }
                    val read = tag != "1"
                    val date = parseSmsDate(dateStr)
                    mapOf<String, Any>(
                        "id" to id, "number" to number, "content" to content,
                        "date" to date, "read" to read, "direction" to direction
                    )
                } catch (_: Exception) { null }
            }

            if (messages.isEmpty()) return

            // 更新最高ID
            val newMax = messages.maxOfOrNull { (it["id"] as? Long) ?: 0L } ?: 0L
            if (newMax > lastPollMaxSmsId) lastPollMaxSmsId = newMax

            // 按号码聚合 → 联系人列表
            val contacts = messages.groupBy { it["number"] as? String ?: "" }
                .mapValues { (_, msgs) ->
                    val latest = msgs.maxByOrNull { (it["date"] as? Long) ?: 0L }
                    mapOf<String, Any>(
                        "phoneNumber" to (latest?.get("number") ?: "unknown"),
                        "total" to msgs.size,
                        "unread" to msgs.count { (it["read"] as? Boolean) != true && (it["direction"] as? String) == "received" },
                        "latestMsg" to (latest?.get("content") as? String ?: ""),
                        "latestTimestamp" to ((latest?.get("date") as? Long) ?: 0L),
                        "latestDirection" to (latest?.get("direction") as? String ?: "received")
                    )
                }
                .values
                .sortedByDescending { it["latestTimestamp"] as Long }

            cachedSmsContacts = contacts

            webSocketManager.broadcast("sms_contacts", mapOf(
                "contacts" to contacts,
                "count" to contacts.size,
                "timestamp" to System.currentTimeMillis()
            ))

            AppLogger.d(tag, "SMS cache: ${contacts.size} contacts, ${messages.size} messages")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect SMS cache", e)
        }
    }

    internal fun getCachedSmsContacts(): List<Map<String, Any>> = cachedSmsContacts

    private fun decodeSmsB64(contentB64: String, encodeType: String): String {
        return try {
            val decoded = android.util.Base64.decode(contentB64, android.util.Base64.DEFAULT)
            when (encodeType) { "2" -> String(decoded, Charsets.UTF_16BE); else -> String(decoded, Charsets.UTF_8) }
        } catch (_: Exception) { contentB64 }
    }

    @Suppress("SameParameterValue")
    private fun parseSmsDate(dateStr: String): Long {
        val parts = dateStr.split(",")
        if (parts.size < 6) return 0L
        val year = 2000 + (parts[0].toIntOrNull() ?: return 0L)
        val month = parts[1].toIntOrNull() ?: return 0L
        val day = parts[2].toIntOrNull() ?: return 0L
        val hour = parts[3].toIntOrNull() ?: return 0L
        val minute = parts[4].toIntOrNull() ?: return 0L
        val second = parts[5].toIntOrNull() ?: return 0L
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+8"))
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
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
     * 有界入队：超过 MAX_BUFFER_SIZE 时丢弃最旧记录。
     * 用 AtomicInteger counter 代替 size() O(n) 遍历。
     */
    private val bufferCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private fun <T> ConcurrentLinkedQueue<T>.offerBounded(item: T) {
        if (bufferCounter.incrementAndGet() > MAX_BUFFER_SIZE) {
            if (poll() != null) bufferCounter.decrementAndGet() else bufferCounter.set(size.coerceAtMost(MAX_BUFFER_SIZE))
        }
        offer(item)
    }

    /**
     * 读取 CPU 最高温度（毫摄氏度）
     * 从 /sys/class/thermal/ 下所有 thermal_zone 的 temp 文件读取，返回最大值
     * 使用缓存 Shell 调用减少 sysfs 压力
     */
    private suspend fun readMaxCpuTemp(): Int {
        return try {
            val result = ShellExecutor.executeAsRoot("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null")
            result.stdout.lines()
                .mapNotNull { it.trim().toIntOrNull() }
                .maxOrNull() ?: 0
        } catch (_: Exception) { 0 }
    }

    private companion object {
        const val PERFORMANCE_CHECK_INTERVAL_MS = 60_000L   // 性能监控: 60s（原30s，加倍放慢）
        const val BATTERY_COLLECTION_INTERVAL_MS = 30_000L
        const val BATCH_FLUSH_INTERVAL_MS = 20_000L        // 缓冲区刷写间隔（20秒，原10秒，加倍放慢）
        const val FAST_PER_PAGE = 5                          // SMS快速轮询：每次5条
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


