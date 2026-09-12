package com.ufi_axis_core.alert

import com.ufi_axis_core.core.database.AlertDao
import com.ufi_axis_core.core.database.AlertRecord
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.contract.Alerts
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.PushChannel
import com.ufi_axis_core.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * 告警引擎
 *
 * 支持告警类型:
 * - temperature: 设备温度过高
 * - battery: 电池电量低
 * - traffic: 流量超额
 * - signal: 信号质量差
 * - connectivity: 网络断连/恢复
 *
 * 特性:
 * - 防抖: 同一告警 5 分钟内不重复触发；connectivity 60s
 * - 状态变化检测（2026-08-11 P1-E 补丁）: connectivity 仅在 online↔offline 切换时触发，
 *   避免周期采集反复触发"网络已恢复"告警堆叠（WiFi 抖动场景下每分钟一条堆积 50 条）
 * - 分级: info / warning / critical
 * - 投递（WS 推送 + 邮件）统一交给 [NotificationDispatcher]（见 [attachNotifier]）
 * - SQLite 持久化
 * - 系统通知推送由手机端 NotificationCenter 统一负责（device 端仅入库 + 广播，避免双进程重复弹通知）
 */
class AlertEngine(
    private val alertDao: AlertDao,
    private val webSocketManager: WebSocketManager,
    private val appSettings: AppSettings
) {
    private val tag = "AlertEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 边沿触发缓存: alertType -> 上次评估的级别（normal/warning/critical）。
    // 仅当级别发生跃迁（normal→warning、warning→normal 等）时才触发告警，
    // 替代旧的电平触发 + 5 分钟防抖（稳态下仍会 288 条/天堆积）。
    private val lastLevelByType = java.util.concurrent.ConcurrentHashMap<String, String>()

    // 环形上限：alert_records 最多保留最近 MAX_ALERT_ROWS 条，超出自动淘汰最旧。
    private val MAX_ALERT_ROWS = 2000

    /**
     * connectivity 状态变化的确认窗口：新状态连续保持这么久才算真变化。
     *
     * 取 60s 的依据：采集节拍 ~3s，一次制式切换/重新搜网/上游中继切换的空档通常在 10s 量级；
     * 而真断网（拔卡、欠费、信号丢失）会持续远超 60s。窗口再大会让真断网的告警来得太迟。
     */
    private val CONNECTIVITY_CONFIRM_MS = 60_000L

    /**
     * 测试观测钩子：记录最近一次 [triggerAlert] 广播的 payload（含 aggregated 标志）。
     * 生产代码不读取；便于单元测试断言 WS 广播语义而无需 spy final 的 WebSocketManager。
     */
    @Volatile var lastBroadcast: Map<String, Any?>? = null

    /**
     * 通知投递钩子（装配层接到 `NotificationDispatcher::emit`）。
     *
     * 2026-08-31 加：在此之前告警邮件的**唯一**生产者是 app —— app 收到 WS 推送 → 弹系统通知 →
     * 再回传给 core 发信。于是 app 没连上（或没装）时，温度/电量/信号/流量/离线告警一封邮件都发
     * 不出来，与"邮件通知是 core 的独立能力"的初衷相反。那条回传端点已于 2026-09-10 删除。
     *
     * 2026-09-08 阶段 1：原来这里是一个 5 参数的**邮件专用**钩子（type/level/message/value/
     * threshold），推送则是另一条路（构造参数里的 `pushService`）—— 同一件事两个出口，
     * 加第三个渠道要再缝一遍。现在只有这一个钩子，推送与邮件都由分发器的渠道列表决定。
     *
     * 总开关与免打扰不在这里判 —— 闸门在分发器，这里判一遍就成了两份真源。
     */
    @Volatile
    private var notifier: Notifier? = null

    /** 装配通知钩子；传 null 解除。装配时机不限（与 `attachConditionEngine` 同风格）。 */
    fun attachNotifier(n: Notifier?) {
        notifier = n
        AppLogger.i(tag, "Alert notifier ${if (n != null) "attached" else "detached"}")
    }

    /**
     * 把一条告警交给分发器。失败只落日志：投递发不出去不能影响告警入库/广播。
     *
     * @param channels 限定渠道（null = 全部）。**聚合更新必须限定成只推送** ——
     *   见 [triggerAlert] 里那条注释。
     */
    private suspend fun emitAlert(
        type: String,
        level: String,
        message: String,
        value: String,
        threshold: String,
        alertId: Long,
        aggregated: Boolean,
        channels: Set<String>?
    ) {
        val emit = notifier ?: return
        try {
            emit(
                NotifyEvent(
                    scene = sceneOf(type),
                    // 推送 type 必须是**具体告警类型**（temperature / connectivity / …），
                    // 不是 sceneOf 归并后的那一格 —— app 的 `:ufi_notify` 靠它重建
                    // AlertRecord、web 靠它筛选与配色。两者粒度不同，见 NotifyEvent.type。
                    type = type,
                    level = NotifyLevel.fromWire(level),
                    // title 走告警文案本身：**邮件主题**要看得出发生了什么，
                    // 放固定词等于收件箱里一排一模一样的标题。
                    title = message,
                    // 推送那一侧仍是固定串「设备告警」——那是线上契约里的既有取值，
                    // `alert` topic 上还有 web 与 app 主进程 UI 在消费同一份 payload，
                    // 它们是否渲染 title 未逐一核实，所以不跟着邮件主题一起变。见 [PUSH_TITLE]。
                    pushTitle = PUSH_TITLE,
                    // body → PushNotification.message，**必须仍是告警文案**：
                    // app 就是拿它当 `AlertRecord.message` 的。详情走 meta，不塞正文。
                    body = message,
                    meta = listOf(
                        "告警类型" to type,
                        "级别" to level,
                        "当前值" to value,
                        "阈值" to threshold
                    ),
                    extra = mapOf(
                        "id" to alertId.toString(),
                        "value" to value,
                        "threshold" to threshold,
                        "aggregated" to aggregated.toString()
                    ),
                    channels = channels
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "Alert notify failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 「告警 type → 通知场景」的映射表。
     *
     * 2026-09-08 从 `ComponentFactory` 搬进来：装配层不该替告警引擎翻译自己的 type ——
     * 新增一个 type 时该改的地方就在这个文件里，而不是在一层之外的 lambda 里。
     *
     * 未登记的 type 归 [NotifyScenes.ALERT]（阈值告警是默认族），所以漏登记的后果是
     * "归到阈值告警场景"而不是"通知发不出去"。
     */
    private fun sceneOf(type: String): String = when (type) {
        "connectivity" -> NotifyScenes.CONNECTIVITY
        // 套餐限额百分比预警自成一个场景（≠ 绝对 MB 阈值告警）
        "traffic_limit" -> NotifyScenes.TRAFFIC_80
        "device_online", "device_offline" -> NotifyScenes.EVENTS
        else -> NotifyScenes.ALERT
    }

    // P1-E 补丁（2026-08-11）：connectivity 上次网络状态记忆（防止周期采集重复触发"网络已恢复"告警）
    // 仅 online↔offline 切换时触发。2026-08-23 去重改造：其余 4 类指标改用统一的边沿触发 evaluate()，
    // 阈值穿越（如温度跨过警告线）只产生 1 条告警，稳态持续超标不再反复入库。
    @Volatile private var lastNetworkConnected: Boolean? = null
    @Volatile private var lastNetworkType: String = ""

    // 2026-09-04 抖动抑制：状态变化必须**连续保持** CONNECTIVITY_CONFIRM_MS 才算真变化。
    // 采集节拍是 ~3s，一次搜网/切网的瞬时空档以前会立刻产出「网络已断开」+「网络已恢复」两条告警
    // （还会各发一封邮件）。这里记住「待确认的新状态」及其首次出现时间。
    @Volatile private var pendingConnected: Boolean? = null
    @Volatile private var pendingConnectedSince: Long = 0L
    @Volatile private var pendingType: String = ""
    @Volatile private var pendingTypeSince: Long = 0L

    // 告警配置
    // ════════════ 多端同步真源约定（P2 核心） ════════════
    // - enabled / perType / 阈值 等全部以 core 端 SharedPreferences 为唯一权威。
    // - 手机端只是镜像：连接 core 即 GET /config 拉取；仅用户「显式切换」时才 PUT（带 configVersion）。
    // - 严禁「连接即写默认 enabled=true」——这是 ab 设备回弹的根因，已清除。
    // - configVersion 单调递增守门：旧版本 PUT 返回 409，调用方须 GET 最新后重试。
    @Serializable
    data class AlertConfig(
        // ── 总开关（唯一真源：false → 引擎不检测/不入库/不广播） ──
        // 2026-09-07：默认 false（用户要求告警不默认开启）。与 app 侧
        // `com.ufi_axis.data.model.AlertConfig.enabled` / UI 兜底 `?: false` 逐字一致。
        val enabled: Boolean = false,
        // ── 分类开关：type -> 是否启用该类型告警（缺键视为**关闭**，见 typeEnabled） ──
        val perType: Map<String, Boolean> = emptyMap(),
        // ── 同 (type,level) 最小聚合间隔秒（窗口内只累加 count，不新插入） ──
        val minIntervalSec: Int = 1800,
        // 2026-09-07 删除两个假开关：
        // - `edgeTriggeredOnly`：只被持久化/同步，引擎从来不读它 —— 无论真假都一律走 evaluate() 边沿触发；
        // - `maxRows`：同样从来不读，环形裁剪写死用 MAX_ALERT_ROWS。
        // 留着只会让人以为改了有效果。真要做成可配，得先让 triggerAlert 读它。
        // ── 多端同步版本号（单调递增；PUT 守门用） ──
        val configVersion: Long = 1L,
        // ── 阈值（保持向后兼容） ──
        val temperatureWarning: Double = 45.0,
        val temperatureCritical: Double = 55.0,
        val batteryWarning: Int = 20,
        val batteryCritical: Int = 10,
        val trafficWarningMb: Long = 1024,      // 1GB
        val trafficCriticalMb: Long = 2048,     // 2GB
        val signalWarningRsrp: Int = -100,
        val signalCriticalRsrp: Int = -115
    )

    /**
     * 告警聚合计数（事件中心分类胶囊 + hero 统计用）。
     * byType/byLevel 为 map，序列化后对应 JSON 对象。
     */
    @Serializable
    data class AlertCounts(
        val total: Int,
        val unread: Int,
        val byType: Map<String, Int>,
        val byLevel: Map<String, Int>
    )

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AlertConfig> = _config

    /**
     * 单个告警类型是否应当检测（2026-08-27 T40-13）。
     *
     * 此前 `perType` 只被持久化 / 版本守门 / 多端同步，引擎从不查它：用户在设置页关掉
     * 「温度告警」后引擎照旧检测、照旧入库、照旧广播，关闭动作只改变了那个 Switch 的显示。
     *
     * 语义：
     * - 总开关 `enabled=false` → 一律不检测（原有语义不变）；
     * - `perType[type] == false` → 该类型不检测；
     * - **键缺省视为关闭**（2026-09-07 由 `!= false` 改为 `== true`）：用户要求告警分类
     *   不默认开启，app 侧 UI 兜底也统一改成了 `?: false`（AlertSettingsScreen /
     *   NotificationsGuardScreen）。引擎判据必须与 UI 同口径，否则会出现
     *   「UI 显示关但引擎照旧检测入库」的假开关。
     */
    private fun typeEnabled(cfg: AlertConfig, type: String): Boolean =
        cfg.enabled && cfg.perType[type] == true

    /**
     * 从 SharedPreferences 加载告警配置
     */
    private fun loadConfig(): AlertConfig {
        val json = appSettings.alertConfigJson ?: return AlertConfig()
        val parsed = try {
            // 必须用 ConfigJson（ignoreUnknownKeys=true），不能用裸 Json：
            // 裸 Json 严格模式下，任何一次字段增删都会让**已存在的整份配置**解析失败，
            // 落到下面的 catch 里静默重置成默认值 —— 用户的告警开关与阈值一起丢。
            ConfigJson.decodeFromString(AlertConfig.serializer(), json)
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to parse alert config, using defaults: ${e.message}")
            return AlertConfig()
        }
        return migrateLegacyConfig(json, parsed)
    }

    /**
     * 一次性迁移：把旧版写下的配置按**旧语义**补齐 `enabled` / `perType`。
     *
     * 旧版本用裸 `Json`（`encodeDefaults=false`）持久化，等于默认值的字段根本不落盘；
     * 而当时 `enabled` 默认 **true**、`typeEnabled` 的判据是 `!= false`（缺键=开）。
     * 2026-09-07 把默认改成 false、判据改成 `== true` 之后，这些旧配置重新解析出来
     * 就变成「引擎关 + 所有分类关」—— 用户没动过任何开关，告警却全哑了。
     *
     * 判据只看 JSON 里有没有 `enabled` 键：现在写盘一律 `encodeDefaults=true`，
     * 所以缺这个键 ⇔ 这份配置是旧版写的。迁移后立即回写，因此只会发生一次。
     */
    private fun migrateLegacyConfig(rawJson: String, parsed: AlertConfig): AlertConfig {
        val keys = try {
            ConfigJson.parseToJsonElement(rawJson).jsonObject.keys
        } catch (e: Exception) {
            return parsed
        }
        if ("enabled" in keys) return parsed
        val migrated = parsed.copy(
            enabled = true,
            // 旧语义下缺键=该类型开启，所以先把全部类型铺成 true，再让存下来的键覆盖 ——
            // 用户在旧版里显式关掉的那几类仍然是 false。
            perType = Alerts.Type.ALL.associateWith { true } + parsed.perType
        )
        appSettings.alertConfigJson = ConfigJson.encodeToString(AlertConfig.serializer(), migrated)
        AppLogger.w(tag, "旧版告警配置缺少 enabled 键，按旧语义迁移为开启（perType 补齐 ${migrated.perType.size} 项）")
        return migrated
    }

    /**
     * 更新告警配置（持久化到 SharedPreferences）
     * 由 UI 显式切换阈值/开关时调用；configVersion 在 PUT 守门成功后由 replaceConfig 自增，
     * 此处直接覆盖时 version 沿用入参（不动），避免与 PUT 路径重复 +1。
     */
    fun updateConfig(newConfig: AlertConfig) {
        _config.value = newConfig
        appSettings.alertConfigJson = ConfigJson.encodeToString(AlertConfig.serializer(), newConfig)
        AppLogger.i(tag, "Alert config updated and persisted")
    }

    /**
     * PUT /config 守门成功后调用：写入新配置并自增 configVersion。
     * 返回写入后的完整配置（含新 version），供路由响应与 WS 广播。
     * 拒绝旧版本并发写：调用方须先 GET 最新 version。
     */
    fun replaceConfig(incoming: AlertConfig): AlertConfig {
        val current = _config.value
        // 版本守门：入参 version 必须 == 当前 version 才接受；否则视为陈旧写，拒绝（返回当前）。
        if (incoming.configVersion != current.configVersion) {
            AppLogger.w(tag, "Stale config PUT rejected: incoming v=${incoming.configVersion} != current v=${current.configVersion}")
            return current
        }
        val bumped = incoming.copy(configVersion = current.configVersion + 1)
        _config.value = bumped
        appSettings.alertConfigJson = ConfigJson.encodeToString(AlertConfig.serializer(), bumped)
        AppLogger.i(tag, "Alert config replaced (v${bumped.configVersion}) and persisted")
        return bumped
    }

    /**
     * 读取当前告警配置（只读快照）。供测试与未来 UI 初始化读取阈值默认值。
     */
    fun getConfig(): AlertConfig = _config.value

    companion object {
        /**
         * 告警**推送**的固定标题（`PushNotification.title`）。
         *
         * 与 [NotifyEvent.title]（= 告警文案）刻意不同：邮件主题要看得出发生了什么，
         * 而推送的 title 是**线上契约里的既有取值**，逐字保持不变。
         * app 的 `NotifyService` 确实不读它（它从 type/level/message/extra 重建 `AlertRecord`），
         * 但 `alert` topic 上还有 web 与 app 主进程 UI 在消费同一份 payload，
         * 是否渲染 title 未逐一核实 —— 契约字段不做"顺手改良"。
         */
        private const val PUSH_TITLE = "设备告警"

        /**
         * 告警配置对外（HTTP 响应 / WS 广播 / 合并基线）的序列化器。
         *
         * **`encodeDefaults = true` 是必需的**：默认 Json 会省略与默认值相等的字段，
         * 于是全新设备的 `GET /api/alerts/config` 会返回 `{}` —— web 只能靠 `?? 45` 之类的
         * 兜底猜阈值，而 C02 的版本守门更会因为拿不到 `configVersion` 让第一次保存必然 409。
         */
        val ConfigJson = com.ufi_axis_core.util.ConfigJson

        /**
         * C02：把「部分字段的请求体」合并到当前配置上，得到完整的 [AlertConfig]。
         *
         * 【为什么必须合并而不是直接 decode】
         * kotlinx 对**缺省字段取 data class 默认值**，所以 `receive<AlertConfig>()` 的语义是
         * "客户端少传一个键 = 显式把它写回默认值"：少传 `perType` 会清空别端的分类开关，
         * 少传 `minIntervalSec` 会被重置成 1800。这直接诱导客户端"必须先 GET 完整对象再整体 PUT"，
         * 任何一次漏字段都会静默覆盖别端配置（ab 设备回弹的同一类根因）。
         *
         * 合并规则：
         * - 只覆盖 [patch] 里**显式出现**的顶层键，其余保持 [current] 原值；
         * - 值为 JSON null 的键视为"未提供"（而不是把非空字段写成 null 导致 500）；
         * - `perType` 这类对象是**整体替换**（顶层键级合并，不做深合并）：
         *   分类开关是一个完整的 map 语义，深合并会让"删除某个 type 的显式设置"变得不可表达。
         *
         * 合并语义是覆盖语义的超集：客户端只要按 [ConfigJson] 的口径发完整对象，结果与旧覆盖语义一致。
         */
        fun mergeConfigPatch(current: AlertConfig, patch: JsonObject): AlertConfig {
            val effective = patch.filterValues { it !is JsonNull }
            if (effective.isEmpty()) return current
            val base = ConfigJson.encodeToJsonElement(AlertConfig.serializer(), current).jsonObject
            return ConfigJson.decodeFromJsonElement(
                AlertConfig.serializer(),
                JsonObject(base + effective)
            )
        }
    }

    /**
     * 广播 config_changed 事件给所有在线客户端（多端同步：A 设备改设置 → B 设备在线即时生效）。
     * 与告警广播分离，避免被 NotificationCenter 的解读逻辑误处理。
     * fire-and-forget（scope.launch），路由层无需等待广播完成。
     */
    fun broadcastConfigChanged() {
        val cfg = _config.value
        scope.launch {
            webSocketManager.broadcast("config_changed", mapOf(
                "type" to "alert",
                // 与 GET /config 同口径：必须带上默认值字段，否则接收端只能拿到"改过的那几个键"
                "config" to ConfigJson.encodeToString(AlertConfig.serializer(), cfg)
            ))
        }
    }

    /**
     * 检查温度告警（边沿触发）
     */
    suspend fun checkTemperature(temperature: Double) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "temperature")) return
        val level = when {
            temperature >= cfg.temperatureCritical -> "critical"
            temperature >= cfg.temperatureWarning -> "warning"
            else -> "normal"
        }
        evaluate("temperature", level, mapOf(
            "warning" to "设备温度偏高: ${temperature}°C",
            "critical" to "设备温度严重过高: ${temperature}°C"
        ), temperature.toString(), cfg.temperatureCritical.toString())
    }

    /**
     * 检查电池告警（边沿触发）
     */
    suspend fun checkBattery(level: Int, isCharging: Boolean) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "battery") || isCharging) return
        val lvl = when {
            level <= cfg.batteryCritical -> "critical"
            level <= cfg.batteryWarning -> "warning"
            else -> "normal"
        }
        evaluate("battery", lvl, mapOf(
            "warning" to "电池电量偏低: $level%",
            "critical" to "电池电量极低: $level%"
        ), level.toString(), cfg.batteryCritical.toString())
    }

    /**
     * 检查流量告警（边沿触发）
     * 流量为月累计值，跨过警告/严重阈值那一刻触发一次；持续超标期间不再重复入库
     * （旧实现电平触发 + 防抖，月流量一旦超 2GB 即永久超标、每天堆叠 288 条）。
     */
    suspend fun checkTraffic(totalMb: Long) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "traffic")) return
        val level = when {
            totalMb >= cfg.trafficCriticalMb -> "critical"
            totalMb >= cfg.trafficWarningMb -> "warning"
            else -> "normal"
        }
        evaluate("traffic", level, mapOf(
            "warning" to "流量超额: ${formatDataSize(totalMb * 1024 * 1024)}",
            "critical" to "流量严重超额: ${formatDataSize(totalMb * 1024 * 1024)}"
        ), totalMb.toString(), cfg.trafficCriticalMb.toString())
    }

    /**
     * 检查「套餐限额百分比」告警（边沿触发）。
     *
     * 2026-08-31：此判定原先写在 app 侧（`NotificationCenter.maybeNotifyTrafficThresholdReached`），
     * 手机不在线就永远不会预警——与「邮件通知是独立功能」的初衷矛盾。现按
     * 「核心业务归 core、app 只做展示」的架构收归此处：
     * - 与 [checkTraffic] 的区别：那个比的是**固定 MB 阈值**（用户在告警设置里填的），
     *   这个比的是**设备侧套餐限额的百分比**（goform `getDataUsage()` 的 limit + alert_percent）；
     * - 限额未配置（limitBytes <= 0）直接返回，不产生噪声；
     * - 走 [evaluate] 边沿触发，天然实现旧 app 端 `KEY_TRAFFIC_80_NOTIFIED` 的「只提醒一次」语义，
     *   且月初限额重置回落 normal 后会自动解除，无需额外的 reset 调用。
     *
     * @param usedBytes   已用流量（月累计 rx+tx，字节）
     * @param limitBytes  套餐限额（字节）
     * @param alertPercent 预警百分比（设备侧配置，默认 80）
     */
    suspend fun checkTrafficLimit(usedBytes: Long, limitBytes: Long, alertPercent: Int) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "traffic_limit")) return
        if (limitBytes <= 0L) return
        val warnPercent = normalizeAlertPercent(alertPercent)
        val percent = trafficUsagePercent(usedBytes, limitBytes)
        val level = when {
            percent >= 100.0 -> "critical"
            percent >= warnPercent -> "warning"
            else -> "normal"
        }
        val shown = formatUsagePercent(percent)
        val detail = "${formatDataSize(usedBytes)} / ${formatDataSize(limitBytes)}"
        evaluate("traffic_limit", level, mapOf(
            "warning" to "流量已用 $shown%（$detail）",
            "critical" to "流量已达套餐限额（$detail）"
        ), "$shown%", "$warnPercent%")
    }

    /**
     * 检查信号告警（边沿触发）
     */
    suspend fun checkSignal(rsrp: Int) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "signal")) return
        val level = when {
            rsrp <= cfg.signalCriticalRsrp -> "critical"
            rsrp <= cfg.signalWarningRsrp -> "warning"
            else -> "normal"
        }
        evaluate("signal", level, mapOf(
            "warning" to "信号较差: RSRP=${rsrp}dBm",
            "critical" to "信号极差: RSRP=${rsrp}dBm"
        ), rsrp.toString(), cfg.signalCriticalRsrp.toString())
    }

    /**
     * 边沿触发统一入口（2026-08-23 去重改造）
     *
     * 将各指标当前级别与 [lastLevelByType] 记忆的级别比较：
     * - 级别未变（稳态）→ 跳过，不入库；
     * - 级别跃迁（如 normal→warning）→ 触发告警；
     * - 回到 normal → 标记该 type 最近一条未确认告警为"已恢复"（resolvedAt），
     *   便于事件中心展示"已恢复"语义，且不产生冗余告警行。
     *
     * @param type    告警类型
     * @param currentLevel 当前级别 normal/warning/critical
     * @param messages 各级别对应的消息模板（key=warning/critical）
     * @param value   触发值
     * @param threshold 阈值（用于详情展示）
     */
    private suspend fun evaluate(
        type: String,
        currentLevel: String,
        messages: Map<String, String>,
        value: String,
        threshold: String
    ) {
        val cfg = _config.value
        if (!typeEnabled(cfg, type)) return
        val prev = lastLevelByType[type] ?: "normal"
        // 2026-08-28：这里原来有一行 "Alert $type level unchanged, skip" 的 DEBUG。
        // evaluate() 由信号(~3s)/流量(15s)/温度(30s) 三条采集循环调用，稳态下 99% 的调用都走这个分支，
        // 单日几万条一字不差的日志，而"没有变化"本身没有任何排障价值 —— 直接删掉。
        if (currentLevel == prev) return
        lastLevelByType[type] = currentLevel
        if (currentLevel == "normal") {
            val updated = alertDao.markResolved(type, System.currentTimeMillis())
            AppLogger.i(tag, "Alert $type recovered (markResolved=$updated)")
            return
        }
        triggerAlert(type, currentLevel, messages[currentLevel] ?: "告警: $type", value, threshold)
    }

    /**
     * 检查设备联网状态（P1-E + 2026-08-11 边沿触发 + 2026-09-04 抖动确认窗口）
     *
     * 由 DataScheduler 的信号采集循环（~3s）与 scanLocalAlerts（60s）调用。
     *
     * 判定依据是**设备外网是否实际可达**（调用方传 `TelephonyCollector.isDeviceOnline()`，
     * 基于 `NET_CAPABILITY_VALIDATED`），不是 modem 注册态 —— 后者在搜网瞬间会短暂变空，
     * 而欠费/PPP 未拨通时又恒为 true，两个方向都报错。
     *
     * 三层防噪：
     * 1. **边沿触发**：状态没变直接返回（2026-08-11，此前每次采集都入库）；
     * 2. **确认窗口**：变化必须连续保持 [CONNECTIVITY_CONFIRM_MS] 才算真变化 ——
     *    网络抖动、蜂窝↔WiFi 中继切换的瞬时空档不再产出「已断开 + 已恢复」两条告警和两封邮件；
     * 3. **冷启动不报**：`prevConnected == null` 只记忆。
     *
     * @param isConnected 设备当前是否真的能上外网
     * @param networkType 网络类型（4G/5G/3G/2G/WiFi），断网时传空串
     */
    suspend fun checkConnectivity(isConnected: Boolean, networkType: String) {
        val cfg = _config.value
        if (!typeEnabled(cfg, "connectivity")) return

        // 状态变化检测：仅在连接状态或网络类型实际变化时触发
        val prevConnected = lastNetworkConnected
        val prevType = lastNetworkType
        val now = System.currentTimeMillis()

        // 首次调用（prevConnected == null）→ 初始化状态不触发告警（避免设备冷启动噪声）
        if (prevConnected == null) {
            lastNetworkConnected = isConnected
            lastNetworkType = networkType
            AppLogger.d(tag, "checkConnectivity initialized: connected=$isConnected type=$networkType")
            return
        }

        val statusChanged = confirmed(
            changed = prevConnected != isConnected,
            sameAsPending = pendingConnected == isConnected,
            since = pendingConnectedSince,
            now = now,
            onStart = { pendingConnected = isConnected; pendingConnectedSince = now },
            onReset = { pendingConnected = null; pendingConnectedSince = 0L }
        )
        val typeChanged = isConnected && prevType.isNotEmpty() && networkType.isNotEmpty() &&
            confirmed(
                changed = prevType != networkType,
                sameAsPending = pendingType == networkType,
                since = pendingTypeSince,
                now = now,
                onStart = { pendingType = networkType; pendingTypeSince = now },
                onReset = { pendingType = ""; pendingTypeSince = 0L }
            )

        if (!statusChanged && !typeChanged) {
            // 状态未变（周期采集重复）或变化尚未坐实 → 跳过。
            // 2026-08-28：这里原来打一条 DEBUG，但本函数由 collectSignal() 每 ~3-4.5s 调用
            // （注释里写的"每分钟一次"与实际不符），稳态下单日 8000+ 条一字不差的日志。
            return
        }

        // 状态真正变化 → 更新记忆 + 触发告警
        lastNetworkConnected = isConnected
        lastNetworkType = networkType
        pendingConnected = null
        pendingConnectedSince = 0L
        pendingType = ""
        pendingTypeSince = 0L

        if (isConnected) {
            triggerAlert(
                type = "connectivity",
                level = "info",
                message = if (networkType.isNotBlank()) "网络已恢复（$networkType）" else "网络已恢复",
                value = networkType,
                threshold = ""
            )
        } else {
            triggerAlert(
                type = "connectivity",
                level = "warning",
                message = "设备已断网（外网不可达）",
                value = "",
                threshold = networkType
            )
        }
    }

    /**
     * 抖动确认闸门：`changed` 的新值必须连续保持 [CONNECTIVITY_CONFIRM_MS] 才返回 true。
     *
     * @param sameAsPending 本次的新值是否与上次记下的「待确认值」一致（不一致说明又跳回去了，重新计时）
     * @param onStart 开始（或重新开始）计时
     * @param onReset 状态回到与已确认值一致 → 清掉待确认记录
     */
    private inline fun confirmed(
        changed: Boolean,
        sameAsPending: Boolean,
        since: Long,
        now: Long,
        onStart: () -> Unit,
        onReset: () -> Unit
    ): Boolean {
        if (!changed) {
            onReset()
            return false
        }
        if (!sameAsPending || since == 0L) {
            onStart()
            return false
        }
        return now - since >= CONNECTIVITY_CONFIRM_MS
    }

    /**
     * 记录设备事件：WiFi 客户端接入 / 离开（2026-08-31 新增）。
     *
     * 走告警表而不是另建一张事件表：事件中心、WS 推送（notification + alert 双频道）、
     * 邮件转投（[attachMailForwarder]）、app 端通知展示这四条链路都已经围绕告警行建好，
     * 再造一套只会多出四份需要同步的逻辑。
     *
     * 上线 / 下线用**两个 type**（不是同 type 不同 level）：[triggerAlert] 的聚合键是
     * (type, level)，同 type 会把「A 上线」和「B 下线」合并成一行 count++，历史就没了。
     *
     * 门控分两层：这里只管 `perType`（告警设置里的分类开关），
     * 「设备事件通知总开关」`NotificationConfig.device_events_enabled` 在采集侧
     * （`DataScheduler` 的供给器 lambda）判 —— 关掉时连 station_list 都不查。
     *
     * @param online true=接入，false=离开
     * @param label  展示名（hostname，缺失时回落 IP，再回落 MAC）
     * @param mac    MAC 地址（作为告警的 value 落库，便于排查）
     */
    suspend fun recordDeviceEvent(online: Boolean, label: String, mac: String) {
        val type = if (online) "device_online" else "device_offline"
        val cfg = _config.value
        if (!typeEnabled(cfg, type)) return
        val message = if (online) "$label 已接入 WiFi" else "$label 已断开 WiFi"
        triggerAlert(type, "info", message, mac, "")
    }

    /**
     * 触发告警（2026-08-23 去重改造：聚合更新 + 环形上限）
     *
     * 1. 先尝试聚合：同 (type,level) 且未确认的告警行若存在，累加 count + 刷新时间戳 + 清除恢复标记，
     *    不再插入新行（边沿触发已保证稳态下几乎不重复，此处为安全网）；
     * 2. 无匹配行则插入新行（count=1, firstSeenAt=timestamp=now）；
     * 3. 插入后执行环形裁剪，保留最近 MAX_ALERT_ROWS 条。
     *
     * WebSocket 广播携带 aggregated 标志，供前端 badge 增量更新。
     */
    private suspend fun triggerAlert(
        type: String,
        level: String,
        message: String,
        value: String,
        threshold: String
    ) {
        val now = System.currentTimeMillis()

        // 1) 聚合：同 (type,level) 未确认行累加计数（返回受影响行数）
        // 2026-08-25：获取现有记录 ID 以便推送给前端做精确去重
        val existing = alertDao.getUnacknowledged(type, level)
        val bumped = alertDao.bumpExisting(type, level, now)
        if (bumped > 0 && existing != null) {
            AppLogger.i(tag, "Alert aggregated: [$level] $message (count++)")
            // **只推送、不发邮件**：聚合意味着"同一件事又发生了一次"，前端要靠它把 badge 数字
            // 往上加，但邮件不能跟着发 —— 稳态持续超标会变成邮件轰炸（这是 2026-08-31
            // 加邮件钩子时就定下的语义，接分发器不能把它丢掉）。
            // 限定渠道而不是"这里不调 notifier"：将来加 Webhook / 短信渠道时，
            // "聚合只走实时渠道"这条语义会自动跟着 channels 走，不需要再各处判一遍。
            emitAlert(
                type = type, level = level, message = message,
                value = value, threshold = threshold,
                alertId = existing.id, aggregated = true,
                channels = setOf(PushChannel.ID)
            )
            return
        }

        // 2) 新行插入
        val record = AlertRecord(
            type = type,
            level = level,
            message = message,
            value = value,
            threshold = threshold,
            count = 1,
            firstSeenAt = now,
            timestamp = now
        )
        val newId = alertDao.insert(record)

        // 3) 环形上限裁剪
        alertDao.trimTo(MAX_ALERT_ROWS)

        AppLogger.i(tag, "Alert triggered & stored: [$level] $message (id=$newId)")

        // 新告警：投给**所有**已注册渠道（推送 + 邮件）。core 自己发，不依赖 app 是否在线。
        emitAlert(
            type = type, level = level, message = message,
            value = value, threshold = threshold,
            alertId = newId, aggregated = false,
            channels = null
        )
    }

    /**
     * 获取最近的告警记录
     */
    suspend fun getRecentAlerts(limit: Int = 20): List<AlertRecord> {
        return alertDao.getRecentAlerts(limit)
    }

    /**
     * 按时间区间获取告警记录（闭区间 [startTime, endTime]）。
     * 供前端事件中心「按日期/范围查看」：/api/alerts/list 传入 start_time/end_time 时调用。
     */
    suspend fun getAlertsBetween(startTime: Long, endTime: Long): List<AlertRecord> {
        return alertDao.getAlertsBetween(startTime, endTime)
    }

    /**
     * 确认告警
     */
    suspend fun acknowledgeAlert(id: Long) {
        alertDao.acknowledge(id)
    }

    /**
     * 删除单条告警记录（事件中心「更多操作 → 删除」）。
     *
     * 物理删除、不可恢复；前端已做二次确认弹窗。返回受影响行数供路由层判断 id 是否存在。
     *
     * @param id 告警主键
     * @return 实际删除的行数（0 = 该 id 不存在）
     */
    suspend fun deleteAlert(id: Long): Int {
        return alertDao.deleteById(id)
    }

    // ===================== P1 列表 API（2026-08-23） =====================

    /**
     * 游标分页 + 多维过滤（事件中心列表）。
     * @param cursorTs / cursorId 上一页末项 (timestamp,id)；首页传 null 走最新。
     */
    suspend fun getPaged(
        level: String? = null,
        type: String? = null,
        unreadOnly: Boolean = false,
        startTime: Long? = null,
        endTime: Long? = null,
        cursorTs: Long? = null,
        cursorId: Long? = null,
        limit: Int = 50
    ): List<AlertRecord> {
        return alertDao.getPaged(
            level, type, if (unreadOnly) 1 else 0,
            startTime, endTime, cursorTs, cursorId, limit
        )
    }

    /**
     * 聚合计数（事件中心分类胶囊 + hero 统计）。
     */
    suspend fun getCounts(
        level: String? = null,
        type: String? = null,
        unreadOnly: Boolean = false,
        startTime: Long? = null,
        endTime: Long? = null
    ): AlertCounts {
        val total = alertDao.getCountFiltered(level, type, if (unreadOnly) 1 else 0, startTime, endTime)
        return AlertCounts(
            total = total,
            unread = alertDao.getUnreadCount(),
            byType = alertDao.getCountByType().associate { it.type to it.cnt },
            byLevel = alertDao.getUnreadCountByLevel().associate { it.level to it.cnt }
        )
    }

    /**
     * 批量确认：传 ids 按 id 列表；否则按 type/level/unreadOnly 过滤。
     * @return 受影响行数（ids 路径返回列表大小）
     */
    suspend fun acknowledgeAll(
        ids: List<Long>? = null,
        type: String? = null,
        level: String? = null,
        unreadOnly: Boolean = true
    ): Int {
        return if (ids != null) {
            alertDao.ackByIds(ids)
            ids.size
        } else {
            alertDao.ackByFilter(type, level, if (unreadOnly) 1 else 0)
        }
    }

    /**
     * 确认已恢复的旧告警（可选最小年龄秒数）。供事件中心「一键清理已恢复」。
     */
    suspend fun acknowledgeResolved(minAgeSec: Long? = null): Int {
        return alertDao.ackResolved(minAgeSec)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }
}