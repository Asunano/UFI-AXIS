package com.ufi_axis_core.core.scheduler

import com.ufi_axis_core.collector.signal.SignalCollector
import com.ufi_axis_core.collector.system.CpuInfo
import com.ufi_axis_core.collector.system.CpuInfoLite
import com.ufi_axis_core.collector.system.MemoryInfo
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.core.database.AppDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.PushChannel
import com.ufi_axis_core.api.websocket.WebSocketManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** 分批清理单个 DAO 的过期数据，避免长事务导致 WAL 文件膨胀 */
private suspend fun deleteBatched(
    dao: suspend (Long, Int) -> Int,
    cutoff: Long,
    batchSize: Int = 1000,
    tag: String = ""
): Int {
    var total = 0
    var deleted: Int
    do {
        deleted = dao(cutoff, batchSize)
        total += deleted
    } while (deleted >= batchSize)
    if (total > 0) AppLogger.d("DataScheduler", "Batched delete $tag: $total rows")
    return total
}

/**
 * 多频率采集调度器
 *
 * ════════════════════════════════════════
 * 数据分类与采集策略
 * ════════════════════════════════════════
 *
 * 【热数据 — WebSocket 实时推送】采集间隔 3s
 * - 信号质量 (RSRP/SINR/RSRQ/RSSI): 自适应 MEDIUM（~3s）
 * - 实时网速/吞吐量 (goform thrpt + TrafficStats): 自适应 MEDIUM（~3s）
 * - CPU/内存: 自适应 HIGH（~3s）
 *
 * 【冷数据 — REST API 按需获取】前端通过 /api/dashboard/summary 获取
 * - 电池/温度: 5s 采集，仅写入历史 DB
 * - 月流量统计: 15s 采集，仅更新内部缓存
 * - SMS 联系人: 5s 采集，仅更新内部缓存
 * - 设备信息/固件版本: REST API 缓存 TTL 10-30min
 * - 设备设置/LAN/APN: REST API 缓存 TTL 5-10min
 *
 * 智能并发控制:
 * - 启动时检测CPU核心数、内存大小
 * - 每60秒检测设备性能(CPU/内存)
 * - 动态调整线程池大小
 * - 高负载时降低频率，低负载时提高频率
 *
 * 数据清理策略:
 * - 流量记录: 保留 24 小时
 * - 监控历史(CPU信号等): 保留 3 天（受限设备 DB 压力优化）
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
    private val dynamicThreadPool: DynamicThreadPool = DynamicThreadPool(),
    private val wakeLockRenew: (() -> Unit)? = null,
    private val smsReadStateDao: com.ufi_axis_core.core.database.SmsReadStateDao? = null,
    private val settings: com.ufi_axis_core.util.AppSettings? = null,
    private val vcDao: com.ufi_axis_core.core.database.SmsVerificationCodeDao? = null,
    private val smsController: com.ufi_axis_core.controller.sms.SmsController? = null,
    /**
     * 短信拦截判定（2026-09-08）。这里是三条**写**路径中的两条 ——
     * WS 推送（`collectSmsCache` 的通知段）与验证码入库（`scanVerificationCodes`），
     * 命中则不推 / 不入库并写一条拦截记录。
     *
     * 判定走 `SmsRuleStore` 的 `@Volatile` 内存快照，**不查 DB、不读 prefs**：
     * `collectSmsCache` 挂在 5s 轮询上，每轮多一次 Room 查询就是每天多两万次。
     */
    private val ruleStore: com.ufi_axis_core.controller.sms.SmsRuleStore? = null,
    /**
     * 设备字段映射表（计划书 3.2）。选型在 `ComponentFactory` 里做一次后注入。
     *
     * **不接受 null**：`SignalCollector` 的第 1 层就是归一化，关掉等于信号数据全空，
     * 而 WS `signal` 频道与 REST 共用这份输出。所以字段归一化的回退开关不覆盖这里，
     * 只影响 goform 客户端层的透传查询。
     */
    private val deviceProfile: DeviceProfile = ZteGoformProfile,
    /** 条件引擎（自动化规则）：在各采集点并联评估，零额外采集开销。 */
    private var conditionEngine: ConditionEngine? = null
) {
    private val tag = "DataScheduler"
    // 使用 Dispatchers.IO 而非 Default — DataScheduler 所有协程都在做 IO（shell/http/db），
    // 占 Default 线程池会导致 CPU-bound 协程饥饿
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(tag, "DataScheduler coroutine exception (uncaught)", e)
    })
    // isRunning / monitorEnabled 由 service 协程写、由 Netty worker 线程读（/api/service/status 回读），
    // 因此必须 @Volatile，否则回读到的可能是过期值。
    @Volatile private var isRunning = false
    /** 监控总开关（REST /monitor/control 设置）。仅控制冷采集循环是否运行，与前端连接无关。 */
    @Volatile private var monitorEnabled = true
    /**
     * stop() 代号。[stop] 的收尾 `cancelChildren()` 是延迟执行的（要先 flush，最多 5s），
     * 而 [start] 的幂等守卫看的是 `isRunning`（stop 第一行就置 false）——
     * 于是"停止后立刻启动"会让延迟的 cancelChildren 把**新启动**的采集协程全部杀掉，
     * 且 `isRunning` 仍为 true → `/status` 谎报"运行中"，采集实际永久停摆。
     * 用代号比对确保：期间只要有人 start() 抢跑，这次 stop 的收尾就放弃取消。
     */
    @Volatile private var stopGeneration = 0    /** 实时推送开关（方案 A·2026-08-24）：仅当存在前端 WebSocket 连接时为 true。 */
    @Volatile private var realtimePushEnabled = false

    /** 套餐限额供给器（见 [attachTrafficLimitProvider]）。 */
    @Volatile private var trafficLimitProvider: (suspend () -> JsonObject?)? = null
    /** 上次检查套餐限额的时间戳；限额是月度量，15s 的流量循环里按 [trafficLimitCheckIntervalMs] 节流。 */
    @Volatile private var lastTrafficLimitCheckAt = 0L

    /** 「到达限额自动关网」看护（见 [attachTrafficAutoOffGuard]）。 */
    @Volatile private var trafficAutoOffGuard: (suspend (usedBytes: Long, limitBytes: Long, alertPercent: Int) -> Unit)? = null

    /** 设备事件（WiFi 客户端）看护（见 [attachDeviceEventWatcher]）。 */
    @Volatile private var stationListProvider: (suspend () -> JsonObject?)? = null
    @Volatile private var deviceEventSink: (suspend (online: Boolean, label: String, mac: String) -> Unit)? = null
    /** 上一轮已连接客户端：mac -> 展示名。null = 还没有基线（首轮 / 上一轮查询失败）。 */
    @Volatile private var knownStations: Map<String, String>? = null

    /** 采集循环是否正在运行（供 `/api/service/status` 回读，此前外部无法得知）。 */
    val collecting: Boolean get() = isRunning

    /** 采集总开关的当前值（`/api/service/start|stop` 与 `/api/monitor/control` 的共同真源）。 */
    val monitorSwitchOn: Boolean get() = monitorEnabled

    /** 注入条件引擎（由 ComponentFactory 装配，DataScheduler 启动前完成）。 */
    fun attachConditionEngine(engine: ConditionEngine) {
        conditionEngine = engine
        AppLogger.i(tag, "ConditionEngine attached")
    }

    /**
     * 注入「套餐限额」供给器（2026-08-31 流量预警下沉 core）。
     *
     * 为什么用注入而不是在这里直接调 goform：限额读取的唯一入口是 `GoformSignalClient.getDataUsage()`，
     * 而 `DataHub.getTrafficLimit()` 在它外面套了 10s 响应缓存；scheduler 在 `core:api` 之下，
     * 引不到 DataHub，也不该引到 `NotificationConfig`（那是 core:api 的类）。
     * 于是把「取限额」和「用户是否开启流量预警」这两件事一起塞进 lambda，由 ComponentFactory 装配：
     * 开关关闭时 lambda 直接返回 null，连 goform 查询都不会发出（避免无谓的 ensureLogin）。
     *
     * 供给器返回 canonical 键：`limit_bytes`(Long) / `alert_percent`(Int) / `enabled`(Boolean)。
     */
    fun attachTrafficLimitProvider(provider: suspend () -> JsonObject?) {
        trafficLimitProvider = provider
        AppLogger.i(tag, "TrafficLimitProvider attached")
    }

    /**
     * 注入「到达限额自动关网」看护（2026-09-01）。
     *
     * 与 [attachTrafficLimitProvider] 同样的理由用注入：判定要用 `NetworkController` +
     * 发信口 + `AppSettings` 里的用户开关，这些都在 scheduler 的依赖之外。
     * 这里只负责按同一个节流节奏把「已用 / 限额 / 告警百分比」递过去，
     * 具体"发信成功才关、本周期只关一次"的规则在 `TrafficAutoOffGuard`。
     */
    fun attachTrafficAutoOffGuard(guard: suspend (usedBytes: Long, limitBytes: Long, alertPercent: Int) -> Unit) {
        trafficAutoOffGuard = guard
        AppLogger.i(tag, "TrafficAutoOffGuard attached")
    }

    /**
     * 注入通知分发器（2026-09-08 阶段 1）。
     *
     * 只用在一处：发现新短信 / 验证码时推一条实时通知（见 [collectSmsCache]）。
     * 在此之前那里是**手写 payload 直接 `broadcast("notification", …)`**，绕过了统一出口 ——
     * 于是"推送长什么样"在全仓有两份定义，改 `PushNotification` 时很容易漏掉这一份。
     */
    fun attachNotifier(n: Notifier?) {
        notifier = n
        AppLogger.i(tag, "通知分发器${if (n != null) "已接入" else "已解除"}")
    }

    @Volatile
    private var notifier: Notifier? = null

    /**
     * 注入「设备事件」看护（2026-08-31）：WiFi 客户端接入 / 离开。
     *
     * 同 [attachTrafficLimitProvider] 的理由用注入：`GoformWifiClient` 不在 scheduler 的依赖里，
     * 而「设备事件通知开关」是 `core:api` 的 `NotificationConfig` 字段。两件事都塞进 lambda：
     * - [provider] 返回归一化后的 `{"station_list":[{hostname,ip_addr,mac_addr}]}`；
     *   **开关关闭时必须返回 null**，否则每分钟一次 `ensureLogin()` + station_list 查询
     *   会跟设备官方 Web UI 抢 session（默认关就是为了避免这个代价）；
     * - [sink] 收到差异事件，由装配方决定落库 / 发信 / 推送。
     */
    fun attachDeviceEventWatcher(
        provider: suspend () -> JsonObject?,
        sink: suspend (online: Boolean, label: String, mac: String) -> Unit
    ) {
        stationListProvider = provider
        deviceEventSink = sink
        AppLogger.i(tag, "DeviceEventWatcher attached")
    }

    /**
     * 冷数据采集总开关（由 REST /monitor/control 设置）。
     * 仅控制「是否运行采集循环」，与前端是否连接无关——冷数据（月流量/电池/SMS/设备信息）
     * 是设备侧资产，必须无条件采集，否则前端断开后数据永久归零（见 2026-08-24 goform 掉线复盘）。
     */
    fun setMonitorEnabled(enabled: Boolean) {
        monitorEnabled = enabled
        applyColdCollectionState()
    }

    /**
     * 冷数据采集闸门（方案 A·2026-08-24）：
     * 仅由监控总开关决定，与「前端 WebSocket 是否连接」完全解耦。
     * Core 启动、监控开关打开 → 无条件启动采集循环；监控开关关闭 → 停止。
     * 实时 WebSocket 推送的启停由 [applyRealtimePushState] 单独控制，不在此处。
     */
    fun applyColdCollectionState() {
        val shouldRun = monitorEnabled
        AppLogger.i(tag, "applyColdCollectionState: monitorEnabled=$monitorEnabled, shouldRun=$shouldRun")
        if (shouldRun) start() else if (isRunning) stop()
    }

    /**
     * 实时推送闸门（方案 A·2026-08-24）：
     * 仅控制「是否向 WebSocket 广播实时数据（signal/cpu/memory/traffic）」，
     * 与采集循环解耦——采集循环由 [applyColdCollectionState] 控制，始终运行；
     * 前端断开时采集继续（写入 DB/缓存），只是不广播。前端(重)连接时恢复广播。
     * 由 WebSocketManager 的连接回调驱动。
     */
    fun applyRealtimePushState() {
        val pushing = webSocketManager.getConnectionCount() > 0
        val wasPushing = realtimePushEnabled
        AppLogger.i(tag, "applyRealtimePushState: wsConn=${webSocketManager.getConnectionCount()}, realtimePush=$pushing")
        realtimePushEnabled = pushing
        // 2026-09-07：闸门由关→开时立刻唤醒两条实时采集循环。
        // 它们在无前端连接时躺在 idleDelayMs（默认 60s）的等待上，而广播只发生在采集末尾，
        // 不唤醒的话客户端首屏的 cpu/memory/signal 要空等一个完整 idle 周期（最坏 60s，
        // 期望 ~30s）——这正是「仪表盘首连 CPU/内存/网络制式无数据、重启 App 才有」的根因
        // （重启时是 Room 缓存命中，不是真的拿到了新帧）。
        if (pushing && !wasPushing) {
            cpuMemoryWake.trySend(Unit)
            signalTrafficWake.trySend(Unit)
        }
    }

    /**
     * 实时采集循环的唤醒信号（CONFLATED：只保留最新一个，重复 trySend 不堆积）。
     * 两条循环各一个，因为 Channel 的一个元素只会被一个接收者取走。
     */
    private val cpuMemoryWake = Channel<Unit>(Channel.CONFLATED)
    private val signalTrafficWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * 实时采集循环的间隔等待：有前端连接时按自适应间隔硬等；无连接时按 idleDelayMs 等待，
     * 但可被 [applyRealtimePushState] 的唤醒信号提前打断，使前端连上后立刻采一轮并广播。
     */
    private suspend fun realtimeDelay(priority: DataPriority, wake: Channel<Unit>) {
        if (webSocketManager.getConnectionCount() > 0) {
            delay(getAdaptiveDelay(priority))
        } else {
            withTimeoutOrNull(idleDelayMs) { wake.receive() }
        }
    }

    /** 最近一次缓冲区刷写失败的错误信息（置错误态，供监控/诊断感知；成功刷写后清空）。 */
    @Volatile private var lastFlushError: String? = null
    private var performanceMonitorJob: Job? = null
    private var flushJob: Job? = null
    private var wakeLockRenewJob: Job? = null

    // 信号采集器（三层优先级合并逻辑独立管理）
    private val signalCollector = SignalCollector(signalClient, telephonyCollector, deviceProfile)

    // 批量写入缓冲区 — 减少对 Room 的 I/O 次数，降低低端设备卡顿
    private val cpuBuffer = ConcurrentLinkedQueue<CpuHistoryRecord>()
    private val memoryBuffer = ConcurrentLinkedQueue<MemoryHistoryRecord>()
    private val trafficBuffer = ConcurrentLinkedQueue<TrafficRecord>()
    private val signalBuffer = ConcurrentLinkedQueue<SignalRecord>()
    private val batteryBuffer = ConcurrentLinkedQueue<BatteryHistoryRecord>()
    // 各 buffer 独立计数器 — O(1) 大小追踪，替代 ConcurrentLinkedQueue.size() 的 O(n) 遍历
    private val cpuBufferSize = AtomicInteger(0)
    private val memoryBufferSize = AtomicInteger(0)
    private val trafficBufferSize = AtomicInteger(0)
    private val signalBufferSize = AtomicInteger(0)
    private val batteryBufferSize = AtomicInteger(0)

    // 最新数据缓存（供 API 快速读取）
    private val _latestTraffic = MutableStateFlow<TrafficRecord?>(null)
    val latestTraffic: StateFlow<TrafficRecord?> = _latestTraffic

    private val _latestSignal = MutableStateFlow<SignalRecord?>(null)
    val latestSignal: StateFlow<SignalRecord?> = _latestSignal

    /**
     * 最近一次 [SignalCollector.collect] 的**完整输出**，与 [_latestSignal] 同步更新。
     *
     * 为什么不能只留 [SignalRecord]：它是 Room 实体，只有 rsrp/sinr/rsrq/rssi/rat/operator
     * 六列，服务小区统一字段（`band` / `band_label` / `arfcn` / `pci` / `signal_strength`，
     * 计划书 1.10 / 决策 D2）与 `nr_*` / `lte_*` 原字段一列都没有。以前缓存命中时用
     * [signalRecordToMap] 重建响应，于是 `GET /api/network/signal` 的键集合**随缓存新鲜度
     * 变化**：刚采完那一次有 20+ 个键，10 秒内的后续请求只有 6 个键。客户端因此拿不稳
     * 服务小区字段（app 的基站页只能回退去读 `Nr_*`，而 `cell-info` 分组并不返回 NR 字段）。
     */
    private val _latestSignalMap = MutableStateFlow<Map<String, Any>?>(null)

    /**
     * 统一信号获取接口 — 供 REST API 和其他组件调用。
     * 优先返回缓存值（<10秒），否则立即执行一次采集。
     * 所有信号消费者（WebSocket、REST、前端）共享同一数据源，避免重复查询。
     *
     * **键集合与缓存新鲜度无关**：缓存命中时返回 [_latestSignalMap]（上次采集的完整输出），
     * 只把 `network_registered` 实时补一遍（[SignalRecord] 不存它，且它随注册状态变化）。
     */
    suspend fun getSignalInfo(): Map<String, Any> {
        val cached = _latestSignal.value
        val age = System.currentTimeMillis() - (cached?.timestamp ?: 0)
        if (cached != null && age < 10_000) {
            // 完整输出还在就用它；万一为空（理论上不会，两者同步写）退回按记录重建
            val map = (_latestSignalMap.value ?: signalRecordToMap(cached)).toMutableMap()
            map["network_registered"] = telephonyCollector.isNetworkRegistered()
            return map
        }
        val signalInfo = signalCollector.collect()
        val record = signalCollector.buildRecord(signalInfo)
        _latestSignal.value = record
        _latestSignalMap.value = signalInfo
        return signalInfo
    }

    /** 兜底：完整输出缺失时按 Room 记录重建（只有六列，服务小区字段拿不到）。 */
    private fun signalRecordToMap(r: SignalRecord): Map<String, Any> = buildMap {
        if (r.rsrp != 0) put("rsrp", r.rsrp)
        if (r.sinr != 0) put("sinr", r.sinr)
        if (r.rsrq != 0) put("rsrq", r.rsrq)
        if (r.rssi != 0) put("rssi", r.rssi)
        if (r.rat.isNotEmpty()) put("rat", r.rat)
        if (r.operator.isNotEmpty()) put("operator", r.operator)
    }

    private val _latestCpu = MutableStateFlow<CpuInfoLite?>(null)
    val latestCpu: StateFlow<CpuInfoLite?> = _latestCpu

    private val _latestMemory = MutableStateFlow<MemoryInfo?>(null)
    val latestMemory: StateFlow<MemoryInfo?> = _latestMemory

    private val _latestBattery = MutableStateFlow<Map<String, Any>>(emptyMap())
    val latestBattery: StateFlow<Map<String, Any>> = _latestBattery

    // Goform 月流量缓存（减少对 goform API 的查询频率）
    private val _goformTraffic = MutableStateFlow<Pair<Long, Long>?>(null)  // (rxBytes, txBytes)
    val goformTraffic: StateFlow<Pair<Long, Long>?> = _goformTraffic

    // ── 今日流量（本项目自算：goform 只有「当月累计」，没有任何「今日」字段）──
    // 今日 = 当月累计 − 当日基线。基线 {date, rx, tx} 持久化在
    // AppSettings.trafficDailyBaselineJson，所以：
    //   · core / 服务 / 设备重启后仍是同一天的基线 → 今日不归零、能接着累计；
    //   · 日期变了才重建基线 → 每天 0 点自动重置。
    private val _todayTraffic = MutableStateFlow(0L to 0L)   // (rxBytes, txBytes)
    val todayTraffic: StateFlow<Pair<Long, Long>> = _todayTraffic

    // 基线的内存副本：避免每个采集周期都读 SharedPreferences；首次使用时从 prefs 惰性载入。
    // settings 为 null（测试/降级装配）时退化为纯内存基线，重启即失效。
    @Volatile private var baselineLoaded = false
    @Volatile private var baselineDate: String? = null
    @Volatile private var baselineRx = 0L
    @Volatile private var baselineTx = 0L

    // Goform 实时吞吐量缓存（来自 Modem 固件直接上报，bytes/s）
    @Volatile private var goformRxThrpt: Long = 0L
    @Volatile private var goformTxThrpt: Long = 0L

    // 网速差值计算后备：当设备 realtime_thrpt 为 0 时，用 monthly_bytes 差值计算
    @Volatile private var lastMonthlyRxBytes: Long = 0L
    @Volatile private var lastMonthlyTxBytes: Long = 0L
    // 采样间隔一定要用单调时钟（SystemClock.elapsedRealtime），不能用墙上时钟：
    // 这批设备开机后例行做一次 NTP 校时，用户也可能手动改系统时间。墙上时钟一跳，
    // (now - last) 就可能是负数或几万秒 —— 除出来的网速要么负、要么荒谬地大，
    // 而字节增量上的 coerceAtLeast(0) 只兜住了分子，兜不住分母。
    @Volatile private var lastMonthlyElapsedMs: Long = 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        // 抢跑标记：让任何"正在等 flush 的 stop 收尾"放弃它的 cancelChildren（见 stopGeneration）
        stopGeneration++
        AppLogger.i(tag, "Starting data scheduler")

        // 方案 A：采集循环无条件启动（冷数据始终采集）。实时推送开关依据当前连接数初始化，
        // 之后由 WebSocketManager 连接回调驱动 applyRealtimePushState() 更新。
        realtimePushEnabled = webSocketManager.getConnectionCount() > 0

        // SMS 已读状态首次初始化（一次性，将设备上所有现有短信标记为已读）
        schedulerScope.launch { initializeSmsReadStates() }

        // 启动时检测设备性能: CPU核心数、内存大小
        detectDevicePerformance()

        // 设备性能监控: 每30秒检查一次，动态调整线程池
        startPerformanceMonitor()

        // 批量刷写: 每 10 秒将缓冲区数据批量写入数据库，减少 I/O 次数
        flushJob = schedulerScope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flushBuffers()
            }
        }

        // ── WakeLock 独立续期: 仅在前端连接时续期 ──
        // 前端断开 → WakeLock 120s 后自然过期 → CPU 可深度休眠，零功耗。
        // 前端连接 → 每 60s 续期 → CPU 保持唤醒，实时推送数据。
        // 与采集循环解耦，采集 hang 或 QoS permit 阻塞不会延迟续期。
        if (wakeLockRenew != null) {
            wakeLockRenewJob = schedulerScope.launch {
                while (isActive) {
                    if (webSocketManager.getConnectionCount() > 0) {
                        wakeLockRenew.invoke()
                    }
                    delay(60_000L)
                }
            }
        }

        // ── 【实时数据】CPU + 内存: 合并到同一协程，减少并发协程数 ──
        // 前端连接: 自适应 ~10s（实时刷新）; 断开: 60s（最低监控）
        schedulerScope.launch {
            while (isActive) {
                collectCpu()
                collectMemory()
                realtimeDelay(DataPriority.HIGH, cpuMemoryWake)
            }
        }

        // ── 【实时数据】信号 + 实时吞吐量 + 网速（合并为 1 条 goform 查询，原 3 条）──
        // 前端连接: 自适应 ~10s; 断开: 60s
        schedulerScope.launch {
            while (isActive) {
                collectSignal()
                collectTraffic()
                realtimeDelay(DataPriority.MEDIUM, signalTrafficWake)
            }
        }

        // ── 【冷数据】月流量统计: 15s 固定间隔
        schedulerScope.launch {
            while (isActive) {
                collectGoformTraffic()
                delay(15_000L)
            }
        }

        // ── 【热数据】SMS 联系人缓存: 前端在线 5s / 无连接 15s
        // 2026-08-29：从固定 30s 提频。验证码短信的到达通知对延迟最敏感，30s 轮询意味着
        // 最坏情况要等半分钟才收到通知。这条循环不打 goform（走 ContentResolver），
        // 且 collectSmsCache() 内部有 `newMax <= lastPollMaxSmsId` 短路——没有新消息时
        // 不做未读标记/聚合/广播，所以提频的代价只有 Cursor 查询本身。
        // 2026-09-08 更正：验证码提取**不在**那条短路之后了（挂在短路后会让"打开开关"永远
        // 等不到扫描，见 collectSmsCache），所以开关开着时每轮会多做最多 5 次主键 exists()。
        schedulerScope.launch {
            while (isActive) {
                collectSmsCache()
                delay(
                    if (webSocketManager.getConnectionCount() > 0) SMS_ACTIVE_INTERVAL_MS
                    else SMS_IDLE_INTERVAL_MS
                )
            }
        }

        // ── 【冷数据】电池/温度: 30s
        schedulerScope.launch {
            while (isActive) {
                collectBattery()
                delay(30_000L)
            }
        }

        // ── 【设备事件】WiFi 客户端接入/离开: 60s ──
        // 未装配看护（或用户关闭了设备事件通知）时 checkDeviceEvents() 第一行就返回，
        // 不会产生 goform 查询，所以这条循环在默认配置下几乎零成本。
        schedulerScope.launch {
            while (isActive) {
                checkDeviceEvents()
                delay(deviceEventCheckIntervalMs)
            }
        }

        // ── 【告警灵敏度】本地指标告警扫描: 固定 15s，不随前端连接数退化 ──
        // 2026-08-30：此前温度/电池/断连告警完全搭在采集循环上，而采集循环在"无前端连接"时
        // 一律退到 IDLE_DELAY_MS=60s —— 也就是用户最需要告警的场景（人没开着 App）反而最钝，
        // 最坏要等一分钟以上才发现断网或过热。
        // 这条循环只读**本地**数据源：CPU 温度走 sysfs（readMaxCpuTemp）、电池走 BatteryManager、
        // 注册态走 TelephonyManager —— 零 goform 请求、不占 GoformQoS 许可、不唤醒 modem，
        // 所以提到 15s 对设备负载和耗电几乎没有影响（代价 = 每 15s 三次本地读）。
        // 信号(RSRP)/流量告警仍留在原采集循环里：那两个必须打 goform，提频要付设备请求和电量的钱。
        // 重复调用不会造成重复告警：AlertEngine.evaluate() 是边沿触发，级别没跃迁直接 return。
        schedulerScope.launch {
            while (isActive) {
                scanLocalAlerts()
                delay(alertScanIntervalMs)
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

    /**
     * 本地指标告警扫描（见 [start] 中该循环的说明）。
     *
     * 三段各自 try/catch：任一数据源读失败（例如某些设备没有 thermal_zone）不能拖累另外两段。
     * 不写缓冲区、不广播 WS —— 纯粹为了让 [AlertEngine] 早点看到值；
     * 入库/推送由 AlertEngine 在真的发生级别跃迁时自己做。
     */
    private suspend fun scanLocalAlerts() {
        val engine = alertEngine ?: return

        try {
            // readMaxCpuTemp() 返回毫摄氏度（与 THERMAL_*_THRESHOLD 同单位），告警阈值用摄氏度
            val milli = readMaxCpuTemp()
            if (milli > 0) engine.checkTemperature(milli / 1000.0)
        } catch (e: Exception) {
            AppLogger.w(tag, "scanLocalAlerts: 温度检查失败: ${e.message}")
        }

        try {
            val battery = systemCollector.getBatteryInfo()
            val level = (battery["percent"] as? Int)
                ?: (battery["level"] as? Number)?.toInt()
            if (level != null) {
                engine.checkBattery(level, battery["is_charging"] as? Boolean ?: false)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "scanLocalAlerts: 电池检查失败: ${e.message}")
        }

        try {
            // 与 collectSignal() 用同一个判定依据：设备外网是否实际可达（见那里的说明）
            val online = telephonyCollector.isDeviceOnline()
            engine.checkConnectivity(
                online,
                if (online) telephonyCollector.getNetworkType() else ""
            )
        } catch (e: Exception) {
            AppLogger.w(tag, "scanLocalAlerts: 连接状态检查失败: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        val token = ++stopGeneration
        AppLogger.i(tag, "Stopping data scheduler")
        
        // 先关闭监控和唤醒锁
        performanceMonitorJob?.cancel()
        flushJob?.cancel()
        wakeLockRenewJob?.cancel()
        
        // 异步刷写剩余缓冲数据后停止,避免阻塞主线程
        // 2026-08-23 修复:不在 NonCancellable 中调用 cancelChildren,
        // 避免正在进行的 goform 查询被强制中断导致协程卡死
        schedulerScope.launch {
            try {
                // 等待当前采集周期完成(最多 5s)
                withTimeout(5_000L) {
                    flushBuffers()
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Flush buffers timeout on stop: ${e.message}")
            }

            // 2026-08-26：等 flush 的这 5s 内如果有人 start() 抢跑，就不能再取消子协程
            // ——否则会把刚启动的采集循环全部杀掉，而 isRunning 仍为 true（静默停摆）。
            if (token != stopGeneration || isRunning) {
                AppLogger.w(tag, "Stop finalization skipped: scheduler was restarted during flush")
                return@launch
            }
            // 取消所有子协程(包括正在进行的 goform 查询)
            schedulerScope.coroutineContext.cancelChildren()
            AppLogger.i(tag, "Data scheduler stopped")
        }
    }

    /**
     * 启动时检测设备性能: CPU核心数、内存大小
     */
    private fun detectDevicePerformance() {
        schedulerScope.launch {
            try {
                val cpuInfo = systemCollector.getCpuInfo()
                val memoryInfo = systemCollector.getMemoryInfo()
                _latestCpu.value = CpuInfoLite(cpuInfo.usage_percent, cpuInfo.core_count, cpuInfo.temperature)
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

                    // 自适应 ShellQoS 策略：根据温度和 CPU 负载动态调整 root 并发数。
                    // 档位必须落在 ShellQoS 的 rootSemaphore 区间 [2, 6] 内 —— 越界值会被
                    // adjustTo() 静默 clamp，写 1 和写 2 效果一样。
                    // 2026-08-29：空闲档从 3 提到 5（= ShellQoS.DEFAULT_ROOT_PERMITS）。原来的 3 是
                    // maxPermits 还等于 4 时代的遗留（注释里写着"留 1 给 AT/ShellRoutes"），区间放宽到 6
                    // 之后没跟着改，结果空闲时反而把并发压到低于模块自己的默认值 —— 文件管理器 / 应用列表
                    // / 系统页这些走 root shell 的批量读取会白排队。收缩只应该由温度和 CPU 触发。
                    val adaptiveRootPermits = when {
                        maxTemp > thermalCriticalMilliC -> 2    // 85°C+ 收到最低档
                        maxTemp > thermalWarnMilliC -> 3     // 75°C+ 温和收缩
                        cpuUsage > 80f -> 3                          // CPU 高负载
                        cpuUsage > 50f -> 4                          // CPU 中负载
                        else -> 5                                    // 空闲：按模块默认值全速
                    }
                    ShellQoS.adaptiveAdjust(adaptiveRootPermits)

                    // 自适应 cache TTL：高负载时延长缓存减少 shell 调用
                    // 2026-08-23 优化：提升缓存TTL减少重复请求，加快页面加载
                    val adaptiveTtl = when {
                        maxTemp > thermalWarnMilliC -> 5000L   // 5s
                        cpuUsage > 70f -> 4000L                          // 4s
                        else -> 3000L                                     // 默认 3s（提升本地连接响应速度）
                    }
                    ShellQoS.updateCacheTtl(adaptiveTtl)

                    // 自适应 GoformQoS 策略：控制后端→设备 HTTP 通信并发
                    // 2026-08-23 优化：本地连接提升基础并发限制，减少等待延迟
                    val goformQueryPermits = when {
                        maxTemp > thermalCriticalMilliC -> 2  // 高温时保守
                        maxTemp > thermalWarnMilliC || cpuUsage > 80f -> 4  // 高负载
                        cpuUsage > 50f -> 6  // 中等负载
                        else -> 8  // 空闲时全速（本地连接无需保守）
                    }
                    val goformSetPermits = when {
                        maxTemp > thermalCriticalMilliC || cpuUsage > 80f -> 2
                        else -> 4  // 提升写操作并发
                    }
                    GoformQoS.adaptiveAdjust(goformQueryPermits, goformSetPermits)
                    GoformQoS.updateCacheTtl(adaptiveTtl)

                    AppLogger.d(tag, "Performance check: cpu=${cpuUsage}%, freeMem=${freeMemory / 1024 / 1024}MB, poolSize=${dynamicThreadPool.getThreadPoolInfo().maxPoolSize}, rootPermits=${ShellQoS.rootTotalPermits}(target=${ShellQoS.rootTargetPermits}), goformQ=${GoformQoS.queryTotalPermits}(t=${GoformQoS.queryTargetPermits}), goformS=${GoformQoS.setTotalPermits}, cacheTtl=${adaptiveTtl}ms")

                    // 温度熔断
                    when {
                        maxTemp > thermalCriticalMilliC -> {
                            // 85°C: 紧急降温 — 清空 shell 和 goform 缓存，暂停 10s
                            ShellQoS.clearCache()
                            GoformQoS.clearCache()
                            AppLogger.w(tag, "Thermal critical: ${maxTemp / 1000}°C > ${thermalCriticalMilliC / 1000}°C, clearing caches and pausing")
                            delay(thermalPauseMs)
                        }
                        maxTemp > thermalWarnMilliC -> {
                            // 75°C: 警告（频率降低已由 getAdaptiveDelay 处理）
                            AppLogger.w(tag, "Thermal warning: ${maxTemp / 1000}°C > ${thermalWarnMilliC / 1000}°C")
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
            DataPriority.HIGH -> 3_000L     // CPU/内存: 3s base
            DataPriority.MEDIUM -> 3_000L    // 网速/信号: 3s base
            DataPriority.LOW -> 20_000L      // 流量统计: 20s base
        }

        // 分母来自运行时的 QoS 许可总数。今天它被 minPermits 夹住不会为 0，但这是
        // 一个「改错一行就变 Infinity/NaN」的除法：load 一旦 NaN，coerceIn 直接原样放行 NaN，
        // 采集间隔就会算成 0（toLong 把 NaN 变 0）—— 表现是采集循环空转打满 goform。
        // 这里不依赖别处的钳制，自己保证分母 ≥ 1。
        val shellLoad = 1.0 - (ShellQoS.rootAvailablePermits.toDouble() / ShellQoS.rootTotalPermits.coerceAtLeast(1))
        val goformLoad = 1.0 - (GoformQoS.queryAvailablePermits.toDouble() / GoformQoS.queryTotalPermits.coerceAtLeast(1))
        val load = maxOf(shellLoad, goformLoad).coerceIn(-0.5, 1.0)

        return (baseDelay * (1.0 + load).coerceIn(0.5, 1.5)).toLong()
    }

    private suspend fun collectTraffic() {
        try {
            // ── 全部使用 goform 数据（Modem 固件）──
            // 累计流量：月累计（monthly_rx/tx_bytes）
            // 实时网速：goform thrpt（realtime_rx/tx_thrpt, bytes/s）
            var goform = _goformTraffic.value
            if (goform == null) {
                // 首次采集：立即获取 goform 月流量，不等 120s 周期
                collectGoformTraffic()
                goform = _goformTraffic.value
            }
            val rxBytes = goform?.first ?: 0L
            val txBytes = goform?.second ?: 0L
            val now = System.currentTimeMillis()
            // 上报给客户端的 timestamp 仍然是墙上时钟（前端要显示真实时间点），
            // 但下面算速率的间隔只认单调时钟，两者不能混用。
            val nowElapsed = android.os.SystemClock.elapsedRealtime()

            // 实时网速：优先使用设备上报的 realtime_thrpt，为 0 时用 monthly_bytes 差值计算
            var rxSpeed = goformRxThrpt
            var txSpeed = goformTxThrpt
            if (rxSpeed == 0L && txSpeed == 0L && lastMonthlyElapsedMs > 0 && rxBytes > 0) {
                val elapsedSec = (nowElapsed - lastMonthlyElapsedMs) / 1000.0
                // 至少 0.5s 间隔才有意义；非正数（含单调时钟本身异常）一律跳过，绝不拿它当除数。
                if (elapsedSec > 0.5) {
                    val deltaRx = (rxBytes - lastMonthlyRxBytes).coerceAtLeast(0)
                    val deltaTx = (txBytes - lastMonthlyTxBytes).coerceAtLeast(0)
                    rxSpeed = (deltaRx / elapsedSec).toLong()
                    txSpeed = (deltaTx / elapsedSec).toLong()
                }
            }
            // 记录本次 monthly 值，供下次差值计算
            lastMonthlyRxBytes = rxBytes
            lastMonthlyTxBytes = txBytes
            lastMonthlyElapsedMs = nowElapsed

            val record = TrafficRecord(
                rxBytes = rxBytes,
                txBytes = txBytes,
                rxSpeed = rxSpeed,
                txSpeed = txSpeed
            )
            trafficBuffer.offerBounded(record, trafficBufferSize)
            _latestTraffic.value = record

            // WebSocket 实时推送（方案 A：仅 realtimePushEnabled 时广播；采集本身始终运行）
            if (realtimePushEnabled) {
                webSocketManager.broadcast("traffic", mapOf(
                    "rx_speed" to rxSpeed,
                    "tx_speed" to txSpeed,
                    "rx_bytes" to rxBytes,
                    "tx_bytes" to txBytes,
                    "rx_speed_display" to formatSpeed(rxSpeed),
                    "tx_speed_display" to formatSpeed(txSpeed),
                    "realtime_rx_thrpt" to rxSpeed,
                    "realtime_tx_thrpt" to txSpeed,
                    "timestamp" to now
                ))
            }

            // 告警检查: 流量超额
            if (rxBytes > 0 || txBytes > 0) {
                val totalMb = (rxBytes + txBytes) / (1024 * 1024)
                alertEngine?.checkTraffic(totalMb)
                checkTrafficLimitThrottled(rxBytes + txBytes, now)
            }

            // 自动化规则: 当月累计流量（电平类）
            conditionEngine?.evaluateTraffic(rxBytes + txBytes)
        } catch (e: CancellationException) {
            // stop() 会 cancelChildren()，采集正卡在挂起点时抛的就是这个。
            // 被下面的泛型 catch 吞掉的话，停机瞬间每条采集都会刷一条 ERROR，
            // 看起来像采集失败，实际只是正常停机 —— 必须原样抛回去。
            throw e
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect traffic", e)
        }
    }

    /**
     * 套餐限额百分比预警（2026-08-31 从 app 侧下沉）+ 到达阈值自动关网（2026-09-01）。
     *
     * 由 [collectTraffic] 调用，但按 [trafficLimitCheckIntervalMs]（设置项，默认 5min）节流：
     * 流量循环 15s 一轮，而限额是月度量，没必要每轮都去 goform 拉一次 `getDataUsage()`。
     * 供给器为 null（未装配）或返回 null（预警与自动关网都关着 / 查询失败）时静默跳过。
     *
     * 预警与自动关网共用这一次查询、同一个 `alert_percent` 阈值，但**互不依赖**：
     * 用户可以只开自动关网而不开告警通道（所以这里不再因为 alertEngine 缺席就整段返回）。
     */
    private suspend fun checkTrafficLimitThrottled(usedBytes: Long, now: Long) {
        val provider = trafficLimitProvider ?: return
        val engine = alertEngine
        val guard = trafficAutoOffGuard
        if (engine == null && guard == null) return
        if (now - lastTrafficLimitCheckAt < trafficLimitCheckIntervalMs) return
        lastTrafficLimitCheckAt = now
        try {
            val limit = withTimeout(8_000L) { provider() } ?: return
            // enabled=false → 设备侧没开套餐限额，百分比无意义
            val enabled = limit["enabled"]?.jsonPrimitive?.content?.let { it == "true" || it == "1" } ?: true
            if (!enabled) return
            val limitBytes = limit["limit_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
            if (limitBytes <= 0L) return
            val alertPercent = (limit["alert_percent"]?.jsonPrimitive?.longOrNull ?: 80L).toInt()
            engine?.checkTrafficLimit(usedBytes, limitBytes, alertPercent)
            guard?.invoke(usedBytes, limitBytes, alertPercent)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(tag, "checkTrafficLimit timed out (8s)")
        } catch (e: Exception) {
            AppLogger.w(tag, "checkTrafficLimit failed: ${e.message}")
        }
    }

    /**
     * 设备事件差异检测（2026-08-31）：对比上一轮 station_list 的 MAC 集合，报接入 / 离开。
     *
     * 三条约定：
     * - **首轮只建基线**（`prev == null` 直接返回）：否则 core 每次重启都会把已连的几台设备
     *   全当成「刚接入」报一遍；
     * - 供给器返回 null（开关关闭或查询失败）时**丢弃基线**，下一次成功查询重新建基线 ——
     *   宁可漏报一轮，也不要因为中间断了几分钟而把一批设备误判成上下线；
     * - MAC 统一小写去空白后作键，设备在不同接口里大小写不一致。
     */
    private suspend fun checkDeviceEvents() {
        val provider = stationListProvider ?: return
        val sink = deviceEventSink ?: return
        try {
            val json = withTimeout(8_000L) { provider() }
            if (json == null) {
                knownStations = null
                return
            }
            val list = json["station_list"]?.jsonArray ?: return
            val current = LinkedHashMap<String, String>()
            for (element in list) {
                val row = element.jsonObject
                val mac = row["mac_addr"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (mac.isNullOrEmpty()) continue
                val host = row["hostname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val ip = row["ip_addr"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                current[mac] = host ?: ip ?: mac
            }
            val prev = knownStations
            knownStations = current
            if (prev == null) {
                AppLogger.i(tag, "Device event baseline established: ${current.size} clients")
                return
            }
            for ((mac, label) in current) if (!prev.containsKey(mac)) sink(true, label, mac)
            for ((mac, label) in prev) if (!current.containsKey(mac)) sink(false, label, mac)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w(tag, "checkDeviceEvents timed out (8s)")
            knownStations = null
        } catch (e: Exception) {
            AppLogger.w(tag, "checkDeviceEvents failed: ${e.message}")
            knownStations = null
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
                signalBuffer.offerBounded(record, signalBufferSize)
            } else {
                AppLogger.d(tag, "Signal record skipped: all metrics are 0 (rsrp=${record.rsrp}, sinr=${record.sinr}, rssi=${record.rssi})")
            }
            _latestSignal.value = record
            // 完整输出与记录必须同步写，否则 getSignalInfo() 缓存命中时会退回六列版本
            _latestSignalMap.value = signalInfo
            val cleanSignalInfo = signalInfo.filterValues { value ->
                when (value) {
                    is String -> value.isNotEmpty()
                    is JsonElement -> value.toString().trim('"').isNotEmpty()
                    else -> true
                }
            }
            if (realtimePushEnabled) {
                webSocketManager.broadcast("signal", cleanSignalInfo)
            }

            val rsrp = (signalInfo["rsrp"] as? Number)?.toInt() ?: 0
            if (rsrp != 0) {
                alertEngine?.checkSignal(rsrp)
                // 自动化规则: 信号差（电平类）
                conditionEngine?.evaluateSignal(rsrp)
            }

            // 告警检查: 设备是否真的能上外网（P1-E connectivity）
            // 2026-09-04：判定依据从 isNetworkRegistered()（modem 注册态）换成 isDeviceOnline()
            // （NET_CAPABILITY_VALIDATED，外网实际可达）。注册态在切换制式/搜网瞬间会短暂变空，
            // 网络抖动、蜂窝↔WiFi 中继切换都会被误报成「网络已断开」；而欠费/PPP 没拨上时它
            // 又照样是 true，真离线反倒报不出来。networkType 只作展示，不参与在线判定。
            val deviceOnline = telephonyCollector.isDeviceOnline()
            val networkType = if (deviceOnline) telephonyCollector.getNetworkType() else ""
            alertEngine?.checkConnectivity(deviceOnline, networkType)
            // 自动化规则: 网络类型跳变 / 断网（边沿类）
            conditionEngine?.evaluateConnectivity(deviceOnline, networkType)
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect signal", e)
        }
    }

    private suspend fun collectCpu() {
        try {
            val cpuInfo = systemCollector.getCpuInfo()
            _latestCpu.value = CpuInfoLite(cpuInfo.usage_percent, cpuInfo.core_count, cpuInfo.temperature)

            val maxFreq = cpuInfo.cores.maxOfOrNull { it.freq_mhz } ?: 0.0
            val cpuRecord = CpuHistoryRecord(
                usagePercent = cpuInfo.usage_percent,
                coreCount = cpuInfo.core_count,
                maxFreqMhz = maxFreq,
                temperature = cpuInfo.temperature
            )
            cpuBuffer.offerBounded(cpuRecord, cpuBufferSize)

            if (realtimePushEnabled) {
                webSocketManager.broadcast("cpu", mapOf(
                    "usage_percent" to cpuInfo.usage_percent,
                    "core_count" to cpuInfo.core_count,
                    "cores" to cpuInfo.cores.map { mapOf("core" to it.core, "freq_mhz" to it.freq_mhz, "freq_display" to it.freq_display) },
                    "temperature" to cpuInfo.temperature
                ))
            }

            // 告警检查: CPU 温度
            alertEngine?.checkTemperature(cpuInfo.temperature)
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect CPU", e)
        }
    }

    private suspend fun collectBattery() {
        try {
            val batteryInfo = systemCollector.getBatteryInfo()
            _latestBattery.value = batteryInfo

            // percent < 0 = 这台设备这一轮压根没取到电量（见 SystemCollector.getBatteryInfo 的三级兜底）。
            // 既不能入库也不能拿去判告警：入库会在电量曲线上留一段 -1 的深坑，
            // 判告警则会被当成"电量 -1%"，每 30s 触发一次低电量告警。
            val level: Int = (batteryInfo["percent"] as? Number)?.toInt() ?: -1
            if (level < 0) {
                AppLogger.w(tag, "Skip battery record/alert: level unavailable")
                return
            }
            val isCharging = batteryInfo["is_charging"] as? Boolean ?: false

            // 持久化电池历史 — 使用 buffer 批量写入，与其他采集器统一
            val record = BatteryHistoryRecord(
                level = level,
                isCharging = isCharging,
                temperature = (batteryInfo["temperature"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 0.0,
                voltage = (batteryInfo["voltage"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 0.0
            )
            batteryBuffer.offerBounded(record, batteryBufferSize)

            // 冷数据：不通过 WebSocket 推送，前端通过 /api/dashboard/summary REST API 获取

            // 告警检查: 电池电量
            alertEngine?.checkBattery(level, isCharging)
            // 自动化规则: 电量低（电平类）
            conditionEngine?.evaluateBattery(level, isCharging)
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
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
            ), memoryBufferSize)

            if (realtimePushEnabled) {
                webSocketManager.broadcast("memory", mapOf(
                    "total" to memoryInfo.total,
                    "used" to memoryInfo.used,
                    "available" to memoryInfo.available,
                    "free" to memoryInfo.free,
                    "buffers" to memoryInfo.buffers,
                    "cached" to memoryInfo.cached,
                    "usage_percent" to memoryInfo.usage_percent
                ))
            }
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect memory", e)
        }
    }

    private suspend fun collectGoformTraffic() {
        try {
            // 2026-08-23 修复：添加超时保护，避免 goform 查询卡死导致协程阻塞
            val stats = kotlinx.coroutines.withTimeout(8_000L) {
                signalClient?.getTrafficStats()
            }
            if (stats != null && stats.isNotEmpty()) {
                // 月累计（canonical：rx=下载、tx=上传，方向已由 profile 掰正，见 ZteGoformProfile）
                val rx = stats["monthly_rx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                val tx = stats["monthly_tx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                if (rx > 0 || tx > 0) {
                    _goformTraffic.value = Pair(rx, tx)
                    advanceTodayTraffic(rx, tx)
                    // 冷数据：不通过 WebSocket 推送，前端通过 /api/dashboard/summary REST API 获取
                }
                // 实时吞吐量（Modem 固件直接上报，bytes/s）
                val rxThrpt = stats["realtime_rx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                val txThrpt = stats["realtime_tx_thrpt"]?.jsonPrimitive?.longOrNull ?: 0L
                goformRxThrpt = rxThrpt
                goformTxThrpt = txThrpt
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AppLogger.w(tag, "collectGoformTraffic timed out (8s): ${e.message}")
            // 超时不记录错误,避免日志泛滥
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect goform traffic", e)
        }
    }

    /**
     * 用最新的月累计推进「今日流量」。只在拿到有效月累计（rx>0 或 tx>0）后调用。
     *
     * 重建基线的三种情况：
     *  1. 还没有基线（首次运行 / 基线被重置 / prefs 里的 JSON 损坏）；
     *  2. 日期变了 —— 这就是「每天 0 点重置」；
     *  3. 月累计比基线还小 —— 设备跨月归零或用户做了流量校准，不重建会算出负数。
     *
     * 同一天内 core 反复重启不会重建基线（日期没变，基线从 prefs 读回），
     * 所以「8:00 开机 → 关机 → 17:00 再开机」能接着 8:00 的基线累计，不会从 17:00 重新计。
     *
     * 已知取舍：跨零点期间 core 没在运行时，新一天的基线是「重新拿到数据那一刻」的月累计
     * （23:00 关机、次日 08:00 启动 → 今日从 08:00 起算）。离线期间没有采样，无从推算。
     */
    private fun advanceTodayTraffic(monthRx: Long, monthTx: Long) {
        if (!baselineLoaded) {
            baselineLoaded = true
            val stored = settings?.trafficDailyBaselineJson
            if (stored != null) {
                try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(stored).jsonObject
                    baselineDate = obj["date"]?.jsonPrimitive?.contentOrNull
                    baselineRx = obj["rx"]?.jsonPrimitive?.longOrNull ?: 0L
                    baselineTx = obj["tx"]?.jsonPrimitive?.longOrNull ?: 0L
                } catch (e: Exception) {
                    AppLogger.w(tag, "今日流量基线解析失败，按重建处理: ${e.message}")
                    baselineDate = null
                }
            }
        }

        val today = java.time.LocalDate.now().toString()
        if (baselineDate != today || monthRx < baselineRx || monthTx < baselineTx) {
            baselineDate = today
            baselineRx = monthRx
            baselineTx = monthTx
            settings?.trafficDailyBaselineJson = kotlinx.serialization.json.buildJsonObject {
                put("date", JsonPrimitive(today))
                put("rx", JsonPrimitive(monthRx))
                put("tx", JsonPrimitive(monthTx))
            }.toString()
            AppLogger.d(tag, "今日流量基线重建: date=$today rx=$monthRx tx=$monthTx")
        }

        _todayTraffic.value =
            (monthRx - baselineRx).coerceAtLeast(0) to (monthTx - baselineTx).coerceAtLeast(0)
    }

    // ── SMS 已读状态管理 ──

    /**
     * 首次安装初始化：将设备上所有现有短信标记为已读。
     * 仅执行一次（通过 smsInitialized 标志控制）。
     * 同时记录 high_water_mark，后续新消息（id > mark）自动标记为未读。
     */
    private suspend fun initializeSmsReadStates() {
        val dao = smsReadStateDao ?: return
        val prefs = settings ?: return
        if (prefs.smsInitialized) return

        try {
            // 2026-08-22：改走 SmsController（ContentResolver 优先，零 goform 请求）；
            // 首次初始化把现有短信全部标记已读并记录水位线
            val ctl = smsController ?: return
            val all = ctl.getAllUnfiltered(limit = 200)
            if (all.isEmpty()) {
                prefs.smsInitialized = true
                return
            }

            val states = all.map { m ->
                com.ufi_axis_core.core.database.SmsReadState(
                    msg_id = m.id,
                    read = true,
                    phone = m.address
                )
            }

            dao.insertAll(states)

            val maxId = states.maxOf { it.msg_id }
            prefs.smsHighWaterMark = maxId
            prefs.smsInitialized = true

            AppLogger.i(tag, "SMS read state initialized: ${states.size} messages marked as read, highWaterMark=$maxId")
        } catch (e: Exception) {
            AppLogger.e(tag, "SMS read state initialization failed", e)
        }
    }

    // ── SMS 缓存：后台 goform 轮询 + 按联系人聚合 ──

    // 缓存的联系人列表（按号码聚合，含未读数/总数/最新消息）
    @Volatile private var cachedSmsContacts: List<Map<String, Any>> = emptyList()
    // 防漏检测：记录上次快速轮询的最高ID，用于判断两次轮询间是否遗漏消息
    @Volatile private var lastPollMaxSmsId: Long = 0L
    /**
     * 验证码开关（`sms_code_enabled`）的上次观测值，用于在轮询 tick 上识别 false→true 边沿。
     *
     * 2026-09-08 事故：用户在设置页打开"短信验证码解析"后，`/api/sms/verification-codes`
     * 永远返回空列表，直到**下一条新短信**到达才开始工作。原因是配置写入（ConfigRoutes 的
     * `boolField("sms_code_enabled")`）只改 AppSettings，不会回扫任何历史消息，而扫描逻辑
     * 又挂在 `newMax > lastPollMaxSmsId` 这个"有新消息"边沿之下 —— core 启动后的第一轮轮询
     * 就把 `lastPollMaxSmsId` 顶到了当前最高 ID（那时开关还是关的，什么也没提取），之后每一轮
     * 都在短路处 return，扫描代码根本执行不到。
     *
     * 故意**不持久化**：验证码去重的唯一真相是 `vcDao.exists(msg_id)`，这里只是"本进程有没有
     * 观测过开关"。null = 还没观测过，因此进程重启后若开关已是开的也会补跑一次回扫
     * （幂等、有界），顺带覆盖"重装/清库后开关本来就是开的"这种冷缓存场景。
     */
    @Volatile private var lastSmsCodeEnabled: Boolean? = null

    private suspend fun collectSmsCache() {
        try {
            val ctl = smsController ?: return
            // 2026-08-22：改走 SmsController（ContentResolver 优先，零 goform 请求、零会话），
            // 不再直连 goform 解析 JSON；轮询最新 5 条消息（覆盖验证码等场景）。
            // 调用间隔见 SMS_ACTIVE_INTERVAL_MS / SMS_IDLE_INTERVAL_MS。
            //
            // 用 getAllUnfiltered 而不是 getAll：本方法是三条**写**路径中的两条，职责恰恰是
            // 「看到被拦的短信 → 不推、不入库、标已读、写一条拦截记录」。走过滤后的列表的话，
            // 被拦短信对这里根本不存在 —— 拦截照样生效，但「已拦截」列表永远是空的。
            val latest = ctl.getAllUnfiltered(limit = FAST_PER_PAGE)
            if (latest.isEmpty()) return

            // ── SMS 验证码扫描（功能开启时提取验证码）──
            //
            // 2026-09-08：这一段**必须留在下面 `newMax <= lastPollMaxSmsId` 短路之前**。
            // 它原来在短路之后，于是"用户打开开关"这个动作永远等不到扫描：core 启动第一轮
            // 就把内存里的 `lastPollMaxSmsId` 顶到了当前最高 ID（那轮开关还是关的，没提取任何东西），
            // 之后没有新短信进来就一直在短路处 return，`/api/sms/verification-codes` 于是空到
            // 下一条短信到达为止。扫描本身**不需要**这个边沿：`vcDao.exists(msg_id)` 就是幂等去重，
            // 重复扫同一批消息只会多几次主键查询，不会重复入库。
            // 真正需要边沿的只有"来了新短信"这件事本身（未读标记 + 水位线 + WS 通知 + 聚合缓存），
            // 那些仍在短路之后，所以通知不会每轮重发。
            //
            // codeOf：本轮新提取出的 msgId → 验证码，供下面决定推"验证码"还是"新短信"。
            val codeOf = HashMap<Long, String>()
            val codeEnabled = settings?.smsCodeEnabled == true
            var backfillOk = true
            if (codeEnabled && vcDao != null) {
                // 开关 false→true（或本进程首次观测到它是开的）→ 一次性深回扫历史消息。
                // 只有这里会读比 FAST_PER_PAGE 更深的一页：日常轮询仍然只看最新 5 条。
                if (lastSmsCodeEnabled != true) backfillOk = backfillVerificationCodes(ctl, codeOf)
                scanVerificationCodes(ctl, latest, codeOf)
            }
            // 观测值推进：回扫读历史短信失败时**不**推进，下一轮（5/15s 后）再试一次 ——
            // 否则一次 Cursor 异常就把"用户刚打开开关"这个一次性边沿永久吞掉。
            // 开关关着、或降级装配没有 vcDao 时 backfillOk 恒为 true，直接推进，不会每轮重试。
            if (backfillOk) lastSmsCodeEnabled = codeEnabled

            val newMax = latest.maxOf { it.id }
            if (newMax <= lastPollMaxSmsId) return  // 无新消息，跳过后续的未读标记、聚合和广播
            lastPollMaxSmsId = newMax

            // ── SMS 已读状态：检测新消息并写入未读状态 ──
            val highWaterMark = settings?.smsHighWaterMark ?: 0L
            // ID 体系切换（goform id → 系统 _id，如后端升级后首次运行）：系统 _id 可能
            // 小于历史 goform 水位线 → 新消息永远不触发未读标记；检测到回退时重置水位线
            if (newMax < highWaterMark) {
                settings?.smsHighWaterMark = newMax
            }
            for (m in latest) {
                if (m.id > (settings?.smsHighWaterMark ?: 0L)) {
                    // 只有接收的新消息才标记未读，发送的默认已读。
                    //
                    // 2026-09-08：被拦截的短信也直接标记已读 —— `getUnreadCount` 是纯 DB COUNT，
                    // 而 DB 里**没有正文**，关键词规则在 DB 层无从判断。在唯一能看到正文的地方
                    // （这里）把它写成已读，未读数就天然不含被拦短信，`getUnreadCount` 一行不用改，
                    // 联系人列表的 unread 聚合也自动一致。
                    //
                    // **明确不做追溯**：先收到短信、后加规则的那些历史 `sms_read_state` 不回改。
                    // 追溯要拿正文重跑全表判定，成本高，而且语义可疑 ——
                    // 「我刚加了条规则，历史未读数突然变了」比未读数偏大更让人不安。
                    val blocked = ruleStore?.isBlocked(m.address, m.body) == true
                    smsReadStateDao?.insert(com.ufi_axis_core.core.database.SmsReadState(
                        msg_id = m.id,
                        read = (m.direction == "sent" || blocked),
                        phone = m.address
                    ))
                }
            }
            // 2026-09-04：本轮**真正新增**的接收消息，供下面推 WS 通知用。
            // 必须在推进水位之前算，且用的是与未读标记同一条判据 —— 水位是持久化的
            // （`lastPollMaxSmsId` 只在内存里，进程重启会归零，拿它做通知去重会重复轰炸）。
            val pushBaseline = settings?.smsHighWaterMark ?: 0L
            val newReceived = latest.filter { it.direction == "received" && it.id > pushBaseline }
            if (newMax > (settings?.smsHighWaterMark ?: 0L)) {
                settings?.smsHighWaterMark = newMax
            }

            // ── 新短信 / 新验证码 → WS `notification` 频道（2026-09-04）──
            //
            // 为什么必须由 core 推：app 侧此前**只有**两条发现新短信的路径 ——
            // ① 用户打开短信页时的 REST 轮询；② `BackgroundGuardWorker` 的 15/30 分钟兜底。
            // 而 `sms_contacts` 频道 core 从不广播（见 WsChannel.NEVER_BROADCAST），
            // app 里那条 WS 分支是死代码。于是表现就是"只有进短信页才弹通知"。
            // 现在在这个**已经存在的新消息边沿**上推一条 notification：
            // `notification` 是 `:ufi_notify` 守护进程订阅的频道，因此 app 在后台
            // 甚至主进程被回收时也能弹。
            //
            // 2026-09-08 阶段 1：改走通知分发器（[attachNotifier]），不再手写 payload。
            // 原来这里直接 `webSocketManager.broadcast("notification", …)` 拼一份与
            // `PushNotification` 同形但类型是 `Map<String, Any?>` 的 map —— 于是"推送长什么样"
            // 在全仓有两份定义，改契约时很容易漏掉这一份。
            //
            // **落到线上的 JSON 与 topic 都逐字不变**：
            // - JSON：`PushChannel` 的映射是 scene→type、level 小写、title/body→title/message、
            //   extra 原样、timestamp 原样，6 个键与顺序都与下面这段原 map 一致；
            // - topic：`sms` / `verification` 在 `PushChannel.SINGLE_TOPIC_SCENES` 里，
            //   `mirrorToAlertTopic = false` → **只发 `notification`，不镜像到 `alert`**。
            //   镜像会让 web 的告警列表把短信列成告警（可见的功能回归）。
            //
            // 只投 push 渠道：短信的**邮件**由 `SmsForwardController.forwardSms` 另行 emit
            // （它按 `lastForwardedSmsId` 去重，与这里"每轮只推最新一条"不是一回事）。
            //
            // **每轮最多推一条**：`broadcast` 对同一频道有 100ms 序列化缓存，
            // 同轮连发第二条会被替换成第一条的内容（发出去两条一模一样的通知）；
            // 而 app 侧短信/验证码的 notificationId 是固定的，多条本来也会互相覆盖。
            // 所以取本轮最新的那条 —— app 侧的短信通知也是固定 id、只显示最新那条，语义一致。
            //
            // 2026-09-08 拦截接入：命中规则的**不推**，并写一条拦截记录（path=push）。
            // 先过滤再取最新，不能反 —— 否则「最新那条正好被拦」会连带把同轮里没被拦的
            // 那几条一起吞掉（本来该推的通知永久丢失，而且日志里看不出为什么）。
            // 通知侧 app 完全不做过滤：core 不推，app 天然收不到（core 判定、app 渲染）。
            val pushable = ArrayList<com.ufi_axis_core.controller.sms.SmsController.SmsMessage>(newReceived.size)
            val filterStore = ruleStore
            for (m in newReceived) {
                val verdict = filterStore?.evaluate(m.address, m.body)
                if (filterStore != null && verdict is com.ufi_axis_core.controller.sms.SmsFilter.Verdict.Block) {
                    filterStore.recordBlocked(
                        msgId = m.id, sender = m.address, body = m.body,
                        block = verdict,
                        path = com.ufi_axis_core.controller.sms.SmsRuleStore.Path.PUSH
                    )
                } else {
                    pushable.add(m)
                }
            }
            val newest = pushable.maxByOrNull { it.id }
            val emit = notifier
            if (newest != null && emit != null) {
                val code = codeOf[newest.id]
                val isCode = code != null
                emit(
                    NotifyEvent(
                        scene = if (isCode) NotifyScenes.VERIFICATION else NotifyScenes.SMS,
                        level = NotifyLevel.INFO,
                        title = if (isCode) "验证码" else "新短信",
                        body = if (isCode) {
                            "${newest.address}：$code"
                        } else {
                            "${newest.address}：${newest.body.take(SMS_PUSH_MESSAGE_CHARS)}"
                        },
                        extra = buildMap {
                            put("id", newest.id.toString())
                            put("sender", newest.address)
                            put("snippet", newest.body.take(SMS_PUSH_SNIPPET_CHARS))
                            if (code != null) put("code", code)
                        },
                        timestamp = newest.date,
                        channels = setOf(PushChannel.ID)
                    )
                )
                AppLogger.i(tag, "SMS 通知已推送：id=${newest.id} type=${if (isCode) "verification" else "sms"}")
            }

            // 聚合缓存是**展示口径**，所以这里要把被拦的滤掉（上面用的是未过滤列表，
            // 因为写路径必须看得见它们）。当前 getCachedSmsContacts() 还没有调用方，
            // 但口径先对齐，免得将来接上去时才发现被拦号码挂在联系人列表里。
            val visible = latest.filter { filterStore?.isBlocked(it.address, it.body) != true }
            val messages = visible.map { m ->
                mapOf<String, Any>(
                    "id" to m.id, "number" to m.address, "content" to m.body,
                    "date" to m.date, "read" to true, "direction" to m.direction
                )
            }

            // 批量合并本地已读状态
            val mergedMessages = mergeSmsReadState(messages)

            if (mergedMessages.isEmpty()) return

            // 按号码聚合 → 联系人列表
            val contacts = mergedMessages.groupBy { it["number"] as? String ?: "" }
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

            cachedSmsContacts = contacts.take(MAX_SMS_CONTACTS)

            // 冷数据：不通过 WebSocket 推送，前端通过 REST API 按需获取

            AppLogger.d(tag, "SMS cache: ${contacts.size} contacts, ${mergedMessages.size} messages")
        } catch (e: CancellationException) {
            throw e // 停机取消不是采集失败，见 collectTraffic
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to collect SMS cache", e)
        }
    }

    /**
     * 对一批消息做验证码提取并入库，返回本次**新入库**条数。
     *
     * 幂等的唯一依据是 `vcDao.exists(msg_id)`（`sms_verification_codes.msg_id` 就是主键，
     * 插入还是 `OnConflictStrategy.IGNORE`）—— 所以同一批消息被反复扫描只会多几次主键查询，
     * 不会重复入库，也不会重复计数。这正是"扫描可以脱离有新消息边沿、每轮都跑"的前提，
     * 别再把它挪回 `lastPollMaxSmsId` 短路之后（2026-09-08 事故，见 [lastSmsCodeEnabled]）。
     */
    private suspend fun scanVerificationCodes(
        ctl: com.ufi_axis_core.controller.sms.SmsController,
        messages: List<com.ufi_axis_core.controller.sms.SmsController.SmsMessage>,
        codeOf: MutableMap<Long, String>
    ): Int {
        val dao = vcDao ?: return 0
        val filterStore = ruleStore
        var inserted = 0
        for (m in messages) {
            // 只解析收到的消息：自己发出去的短信里的数字不是验证码
            if (m.direction != "received") continue
            try {
                // 去重：已处理过的消息跳过
                if (dao.exists(m.id) > 0) continue
                // 2026-09-08 拦截接入：命中规则的**不入库**，并写一条拦截记录（path=vc）。
                // 判定放在 exists() 之后：已经入库过的记录不该因为「后来加了规则」而被记一笔拦截
                // （那不是拦截，那是历史数据，删不删由用户在验证码列表里决定）。
                //
                // 注意验证码豁免在这里的语义：豁免只让**关键词**规则放行，`scope=sender` 的
                // 号码黑名单照旧拦 —— 用户把某个号码拉黑之后，它发来的验证码也不该出现在通知 Tab。
                val verdict = filterStore?.evaluate(m.address, m.body)
                if (filterStore != null && verdict is com.ufi_axis_core.controller.sms.SmsFilter.Verdict.Block) {
                    filterStore.recordBlocked(
                        msgId = m.id, sender = m.address, body = m.body,
                        block = verdict,
                        path = com.ufi_axis_core.controller.sms.SmsRuleStore.Path.VC
                    )
                    continue
                }
                val vc = ctl.extractCode(m.address, m.body, m.id, m.date) ?: continue
                dao.insert(vc)
                codeOf[m.id] = vc.code
                inserted++
                AppLogger.d(tag, "VC extracted: code=${vc.code} from=${vc.source}")
            } catch (e: CancellationException) {
                // 停机/切换采集模式时的取消不是"这条解析失败"，吞掉会让整个循环继续跑完
                // 一整批已经没人要的结果。与 SmsRuleStore / SmsController 同一口径：先抛。
                throw e
            } catch (e: Exception) {
                // 单条失败只跳过这条，但必须留下上下文：这里原来是 `catch (_: Exception) {}`，
                // 结果"验证码没解析出来"在日志里一点痕迹都没有，只能靠猜。
                AppLogger.w(tag, "VC 提取失败，跳过该条：msgId=${m.id} from=${m.address}: ${e.message}")
            }
        }
        return inserted
    }

    /**
     * 验证码开关刚打开时的一次性回扫：把用户**已有**的短信也过一遍提取器。
     *
     * 为什么需要：打开开关这个动作只写 AppSettings（`ConfigRoutes` 的 `sms_code_enabled`），
     * 不会产生任何新短信，而日常轮询只看最新 [FAST_PER_PAGE] 条 —— 用户的诉求恰恰是
     * "我刚打开这个功能，想看到我最近收到的验证码"，只靠 5 条窗口永远满足不了。
     *
     * 边界（刻意都收紧了，这是跑在 5s 轮询 tick 上的活儿，不是后台任务）：
     * - 一次性：由 [lastSmsCodeEnabled] 的 false→true 边沿触发，不装闹钟、不加 WakeLock。
     * - 有界：最多读 [VC_BACKFILL_LIMIT] 条，一次 Cursor 查询 + 最多同样条数的主键查询。
     * - 只回扫清理窗口内的消息：清理是按 `created_at` 删的，回扫会给老消息盖上"现在"的
     *   created_at，等于把用户那边已经过期消失的验证码复活一遍，而且每次进程重启复活一次。
     * - `vcDao == null` 的降级装配直接判成"无需回扫"（返回 true，不重试）。
     *
     * @return true = 这次回扫算跑过了（含"没什么可回扫"）；false = 读历史短信失败，调用方
     *         不要推进 [lastSmsCodeEnabled]，下一轮再试。
     */
    private suspend fun backfillVerificationCodes(
        ctl: com.ufi_axis_core.controller.sms.SmsController,
        codeOf: MutableMap<Long, String>
    ): Boolean {
        if (vcDao == null) return true
        val history = try {
            ctl.getAllUnfiltered(limit = VC_BACKFILL_LIMIT)
        } catch (e: CancellationException) {
            // 取消不是"读历史短信失败"。吞掉它会走到 return false，而 false 的语义是
            // "不要推进 lastSmsCodeEnabled，下一轮再试" —— 于是「用户刚打开开关」这个
            // 一次性边沿被变成每轮 5s 都重试一次的回扫。
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "验证码回扫失败：读历史短信异常，下一轮重试: ${e.message}")
            return false
        }
        val cleanupHours = settings?.smsCodeCleanupHours ?: 24
        val cutoff = if (cleanupHours > 0) System.currentTimeMillis() - cleanupHours * 3600_000L else 0L
        val scoped = if (cutoff > 0) history.filter { it.date >= cutoff } else history
        val inserted = scanVerificationCodes(ctl, scoped, codeOf)
        AppLogger.i(
            tag,
            "验证码开关刚打开：回扫历史短信 ${scoped.size}/${history.size} 条" +
                "（limit=$VC_BACKFILL_LIMIT, 保留窗口=${cleanupHours}h），新入库 $inserted 条"
        )
        return true
    }

    /** 批量合并本地已读状态到消息列表 */
    private suspend fun mergeSmsReadState(messages: List<Map<String, Any>>): List<Map<String, Any>> {
        if (messages.isEmpty() || smsReadStateDao == null) return messages
        val ids = messages.mapNotNull { it["id"] as? Long }
        val states = try {
            smsReadStateDao.getReadStates(ids).associate { it.msg_id to it.read }
        } catch (_: Exception) {
            emptyMap<Long, Boolean>()
        }
        return messages.map { msg ->
            val id = msg["id"] as? Long ?: return@map msg
            msg + ("read" to (states[id] ?: true))
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
        // 批次列表声明在 try 之外，便于刷新失败时重新入队（而非静默丢弃已 drain 的数据）
        val cpuBatch = mutableListOf<CpuHistoryRecord>()
        val memBatch = mutableListOf<MemoryHistoryRecord>()
        val trafficBatch = mutableListOf<TrafficRecord>()
        val signalBatch = mutableListOf<SignalRecord>()
        val batteryBatch = mutableListOf<BatteryHistoryRecord>()
        try {
            while (true) { cpuBuffer.poll()?.let { cpuBatch.add(it) } ?: break }
            cpuBufferSize.getAndSet(0)  // 原子重置：drain 期间新增的 item 也计入

            while (true) { memoryBuffer.poll()?.let { memBatch.add(it) } ?: break }
            memoryBufferSize.getAndSet(0)

            while (true) { trafficBuffer.poll()?.let { trafficBatch.add(it) } ?: break }
            trafficBufferSize.getAndSet(0)

            while (true) { signalBuffer.poll()?.let { signalBatch.add(it) } ?: break }
            signalBufferSize.getAndSet(0)

            while (true) { batteryBuffer.poll()?.let { batteryBatch.add(it) } ?: break }
            batteryBufferSize.getAndSet(0)

            val totalFlushed = cpuBatch.size + memBatch.size + trafficBatch.size + signalBatch.size + batteryBatch.size
            if (totalFlushed > 0) {
                // 5 个 buffer 合并为 1 个事务，减少 WAL 模式下的事务竞争
                database.flushAllBuffers(cpuBatch, memBatch, trafficBatch, signalBatch, batteryBatch)
                AppLogger.d(tag, "Flushed buffers: cpu=${cpuBatch.size}, mem=${memBatch.size}, traffic=${trafficBatch.size}, signal=${signalBatch.size}, battery=${batteryBatch.size}")
                lastFlushError = null
            }
        } catch (e: Exception) {
            // 关键路径：刷新失败不要静默吞掉已 drain 的数据（会导致持久化状态错乱/丢点），
            // 重新入队以便下次重试，并置错误态供监控/诊断感知。
            AppLogger.e(tag, "Failed to flush buffers, re-enqueuing for retry", e)
            lastFlushError = e.message ?: e.javaClass.simpleName
            cpuBatch.forEach { cpuBuffer.offerBounded(it, cpuBufferSize) }
            memBatch.forEach { memoryBuffer.offerBounded(it, memoryBufferSize) }
            trafficBatch.forEach { trafficBuffer.offerBounded(it, trafficBufferSize) }
            signalBatch.forEach { signalBuffer.offerBounded(it, signalBufferSize) }
            batteryBatch.forEach { batteryBuffer.offerBounded(it, batteryBufferSize) }
        }
    }

    /**
     * 清理过期数据，总量上限约 150MB
     *
     * 使用分批删除（LIMIT 1000）避免长事务阻塞 WAL 文件合并。
     * 清理完成后执行 WAL checkpoint(TRUNCATE) 回收空间。
     */
    private suspend fun cleanOldData() {
        try {
            val now = System.currentTimeMillis()
            val monitorCutoff = now - retentionDays * 24 * 60 * 60 * 1000L

            // 监控数据统一保留 HISTORY_RETENTION_DAYS 天，分批删除避免长事务
            val cpuDeleted = deleteBatched(
                { cutoff, limit -> database.cpuHistoryDao().deleteOlderThanBatched(cutoff, limit) },
                monitorCutoff, tag = "cpu_history"
            )
            val memoryDeleted = deleteBatched(
                { cutoff, limit -> database.memoryHistoryDao().deleteOlderThanBatched(cutoff, limit) },
                monitorCutoff, tag = "memory_history"
            )
            val trafficDeleted = deleteBatched(
                { cutoff, limit -> database.trafficDao().deleteOlderThanBatched(cutoff, limit) },
                monitorCutoff, tag = "traffic_records"
            )
            val signalDeleted = deleteBatched(
                { cutoff, limit -> database.signalDao().deleteOlderThanBatched(cutoff, limit) },
                monitorCutoff, tag = "signal_history"
            )
            val batteryDeleted = deleteBatched(
                { cutoff, limit -> database.batteryHistoryDao().deleteOlderThanBatched(cutoff, limit) },
                monitorCutoff, tag = "battery_history"
            )
            // 告警记录: 保留 30 天（告警量少，无需分批）
            val alertDeleted = database.alertDao().deleteOlderThan(now - 30L * 24 * 60 * 60 * 1000L)
            // 短信记录: 永久保留，不自动清理

            // 验证码缓存: 按用户配置的间隔清理（0 = 永不清理）
            val cleanupHours = settings?.smsCodeCleanupHours ?: 24
            val vcDeleted = if (cleanupHours > 0) {
                vcDao?.deleteOlderThan(now - cleanupHours * 3600_000L) ?: 0
            } else 0

            if (cpuDeleted + memoryDeleted + trafficDeleted + signalDeleted + batteryDeleted + alertDeleted + vcDeleted > 0) {
                AppLogger.i(tag, "Cleaned data: cpu=$cpuDeleted, memory=$memoryDeleted, traffic=$trafficDeleted, signal=$signalDeleted, battery=$batteryDeleted, alert=$alertDeleted, vc=$vcDeleted")

                // WAL checkpoint(TRUNCATE)：合并 WAL 文件回主库，回收磁盘空间
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    } catch (_: Exception) {}
                }
            }
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
     * 使用 AtomicInteger 计数器实现 O(1) 大小检查，
     * 替代 ConcurrentLinkedQueue.size() 的 O(n) 链表遍历。
     */
    private fun <T> ConcurrentLinkedQueue<T>.offerBounded(item: T, counter: AtomicInteger) {
        while (counter.get() >= MAX_BUFFER_SIZE) {
            if (poll() != null) counter.decrementAndGet() else break
        }
        offer(item)
        counter.incrementAndGet()
    }

    /**
     * 读取 CPU 最高温度（毫摄氏度）
     * 从 /sys/class/thermal/ 下所有 thermal_zone 的 temp 文件读取，返回最大值
     * 直接读取 sysfs，无需 root 权限，避免 shell fork 开销
     */
    private suspend fun readMaxCpuTemp(): Int = withContext(Dispatchers.IO) {
        try {
            val thermalDir = File("/sys/class/thermal")
            thermalDir.listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.mapNotNull { zone ->
                    File(zone, "temp").readText().trim().toIntOrNull()
                }
                ?.maxOrNull() ?: 0
        } catch (e: Exception) {
            // 不再静默吞掉：温度读取失败会影响温控熔断判定，至少记录日志以便诊断
            AppLogger.w(tag, "Failed to read CPU temperature, defaulting to 0: ${e.message}")
            0
        }
    }

    // ── 采集调度参数（2026-09-03）──
    // 原来这些是下面 companion 里的编译期常量，用户改不了；现在改成读 AppSettings。
    // 故意用 `get()` 而不是 val：AppSettings 每次 get 都直接读 SharedPreferences，
    // 循环体内取值 ⇒ 用户在设置页改完下一轮就生效，不需要重启采集。
    // settings 为 null 是降级装配（测试/无 context），此时退回原来的默认值。
    private val flushIntervalMs: Long get() = (settings?.monitorFlushIntervalSec ?: 30) * 1000L
    private val alertScanIntervalMs: Long get() = (settings?.monitorAlertScanSec ?: 15) * 1000L
    private val idleDelayMs: Long get() = (settings?.monitorIdleIntervalSec ?: 60) * 1000L
    private val retentionDays: Long get() = (settings?.monitorRetentionDays ?: 7).toLong()
    /** 温度阈值对外是摄氏度，sysfs 读到的是毫摄氏度，这里统一 ×1000 */
    private val thermalWarnMilliC: Int get() = (settings?.monitorThermalWarnC ?: 70) * 1000
    private val thermalCriticalMilliC: Int get() = (settings?.monitorThermalCriticalC ?: 80) * 1000
    private val thermalPauseMs: Long get() = (settings?.monitorThermalPauseSec ?: 20) * 1000L
    // 2026-09-08 补齐：上一次改造漏了这两个，于是「套餐限额预警」与「设备接入/离开提醒」
    // 三类告警的周期完全不可调 —— 用户把告警扫描间隔调到 5 秒，它们还是 5 分钟 / 60 秒。
    private val trafficLimitCheckIntervalMs: Long
        get() = (settings?.monitorTrafficLimitCheckSec ?: 300) * 1000L
    private val deviceEventCheckIntervalMs: Long
        get() = (settings?.monitorDeviceEventCheckSec ?: 60) * 1000L

    private companion object {
        const val PERFORMANCE_CHECK_INTERVAL_MS = 60_000L    // 性能监控: 60s
        const val BATTERY_COLLECTION_INTERVAL_MS = 60_000L
        // 刷写间隔 / 空闲采集间隔 / 告警扫描间隔 / 保留天数 / 温控档位
        // 2026-09-03 起改由 AppSettings 提供（见类体上方的 flushIntervalMs 等），此处不再放常量。
        // 套餐限额检查间隔 / 设备事件比对间隔 2026-09-08 起同样改由 AppSettings 提供
        //（trafficLimitCheckIntervalMs / deviceEventCheckIntervalMs）。
        const val FAST_PER_PAGE = 5                          // SMS快速轮询：每次5条
        // 验证码开关 false→true 时一次性回扫的深度。100 条的取舍：
        // ① 一次 ContentResolver 查询 + 最多 100 次主键 exists()，跑在 5s 轮询 tick 上，
        //    比 initializeSmsReadStates() 的 200 条更保守（那个只在首装跑一次，不在热循环里）；
        // ② 100 条已经远远覆盖验证码的保留窗口（smsCodeCleanupHours 默认 24h），
        //    回扫还会再按该窗口过滤一次，读更深也不会多入库；
        // ③ 上限与 REST /sms/list 的 200 同数量级，不需要新的分页语义。
        const val VC_BACKFILL_LIMIT = 100                    // 验证码回扫：一次性最多回看 100 条
        // SMS 采集间隔：验证码通知的及时性优先级最高，所以这是唯一一条比信号采集还密的循环。
        // 代价只是系统 Cursor 压力 —— collectSmsCache() 走 SmsController（ContentResolver 优先），
        // 零 goform 请求，不占 GoformQoS 许可，也不给设备加压。
        const val SMS_ACTIVE_INTERVAL_MS = 5_000L            // 有前端连接: 5s
        const val SMS_IDLE_INTERVAL_MS = 15_000L             // 无前端连接: 15s（仍要推通知，不退到空闲采集间隔）
        const val MAX_BUFFER_SIZE = 50                     // 单缓冲区最大记录数（降低内存占用）
        const val HISTORY_QUERY_LIMIT = 10_000               // 单次查询最大记录数
        const val MAX_SMS_CONTACTS = 50               // SMS 联系人缓存上限

        /**
         * 短信推送里 `message` / `extra.snippet` 的截断长度。
         *
         * 2026-09-08 收进分发器时从裸字面量提上来，取值与迁移前逐字一致（60 / 120）——
         * 这两个数字是**线上可见**的（app 的通知正文与详情用的就是它们），不能顺手改。
         */
        const val SMS_PUSH_MESSAGE_CHARS = 60
        const val SMS_PUSH_SNIPPET_CHARS = 120
    }

    enum class DataPriority {
        HIGH,
        MEDIUM,
        LOW
    }
}


