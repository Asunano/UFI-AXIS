package com.ufi_axis_core.core.scheduler

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 条件引擎（自动化规则核心）
 *
 * 把「条件」连到「动作」——这正是此前缺失的桥。复用 [ActionExecutor] 执行动作，
 * 由 [com.ufi_axis_core.core.scheduler.DataScheduler] 在各采集点并联调用 evaluateXxx，
 * 复用既有 ~3~15s 采样节奏，零额外采集开销。
 *
 * 两类语义：
 *  - 电平类（traffic / signal / battery）：满足条件期间只触发一次（armed 武装位），
 *    条件清除后自动 re-arm，下次可再触发（月流量跨账期重置即天然 re-arm）。
 *  - 边沿类（network_type_changed / disconnect）：仅在状态跳变瞬间触发一次，
 *    持续同状态不重复执行。
 *  - 所有规则带 cooldownMs（默认 60s）兜底，防断网/信号抖动场景刷屏。
 *
 * 持久化：SharedPreferences "automation_rules"（与 TaskScheduler 同模式），零数据库依赖。
 */
class ConditionEngine(
    private val context: Context,
    private val actionExecutor: ActionExecutor
) {
    private val tag = "ConditionEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val rules = ConcurrentHashMap<String, AutomationRule>()
    private val armed = ConcurrentHashMap<String, Boolean>()      // 电平类防刷武装位
    private val lastFiredAt = ConcurrentHashMap<String, Long>()   // 冷却时间戳
    @Volatile private var lastNetworkConnected: Boolean? = null    // 边沿检测：上一轮联网态（null=冷启动）
    @Volatile private var lastNetworkType: String = ""             // 边沿检测：上一轮网络类型

    // 抖动确认（2026-09-04）：待确认的新状态与其首次出现时间
    @Volatile private var pendingConnected: Boolean? = null
    @Volatile private var pendingType: String = ""
    @Volatile private var pendingSince: Long = 0L

    /** 联网状态变化的确认窗口，与 `AlertEngine.CONNECTIVITY_CONFIRM_MS` 取同一量级。 */
    private val CONFIRM_MS = 60_000L
    private val prefs = context.getSharedPreferences("automation_rules", Context.MODE_PRIVATE)

    init { loadRules() }

    // ──────────── 查询 ────────────

    fun list(): List<AutomationRule> = rules.values.sortedBy { it.createdAt }
    fun get(id: String): AutomationRule? = rules[id]
    fun getLogs(ruleId: String): List<ExecutionLog> = rules[ruleId]?.logs ?: emptyList()

    // ──────────── 增删改 ────────────

    fun add(rule: AutomationRule): Boolean {
        if (!ActionExecutor.VALID_ACTION_TYPES.contains(rule.actionType)) return false
        rules[rule.id] = rule
        saveRules()
        AppLogger.i(tag, "Rule added: '${rule.name}' (${rule.id}) trigger=${rule.triggerType} action=${rule.actionType}")
        return true
    }

    fun remove(id: String): Boolean {
        rules.remove(id) ?: return false
        armed.remove(id)
        lastFiredAt.remove(id)
        saveRules()
        AppLogger.i(tag, "Rule removed: $id")
        return true
    }

    fun update(rule: AutomationRule): Boolean {
        if (!rules.containsKey(rule.id)) return false
        if (!ActionExecutor.VALID_ACTION_TYPES.contains(rule.actionType)) return false
        rules[rule.id] = rule
        armed.remove(rule.id) // 参数变更后重置武装位，避免残留
        saveRules()
        return true
    }

    fun clear() {
        rules.clear()
        armed.clear()
        lastFiredAt.clear()
        saveRules()
        AppLogger.i(tag, "All rules cleared")
    }

    // ──────────── 评估入口（由 DataScheduler 并联调用）────────────

    /** 当月累计流量（rx+tx 字节）。电平类。 */
    fun evaluateTraffic(totalBytes: Long) {
        rules.values
            .filter { it.enabled && it.triggerType == "traffic_total_reached" }
            .forEach { rule ->
                val threshold = rule.triggerParams["thresholdBytes"]?.content?.toLongOrNull() ?: return@forEach
                val met = totalBytes >= threshold
                val a = armed[rule.id] ?: false
                if (met && !a && canFire(rule)) {
                    fire(rule, "当月流量 ${fmt(totalBytes)} ≥ ${fmt(threshold)}，停止网络数据")
                    armed[rule.id] = true
                }
                if (!met) armed[rule.id] = false // 账期重置→re-arm
            }
    }

    /** 信号 RSRP（dBm，负值；越小越差）。电平类。 */
    fun evaluateSignal(rsrp: Int) {
        rules.values
            .filter { it.enabled && it.triggerType == "signal_below" }
            .forEach { rule ->
                val threshold = rule.triggerParams["rsrp"]?.content?.toIntOrNull() ?: return@forEach
                val met = rsrp <= threshold
                val a = armed[rule.id] ?: false
                if (met && !a && canFire(rule)) {
                    fire(rule, "信号 RSRP=${rsrp} ≤ ${threshold}，执行动作")
                    armed[rule.id] = true
                }
                if (!met) armed[rule.id] = false
            }
    }

    /** 电池电量。电平类（未充电且 ≤ 阈值）。 */
    fun evaluateBattery(level: Int, isCharging: Boolean) {
        rules.values
            .filter { it.enabled && it.triggerType == "battery_below" }
            .forEach { rule ->
                val threshold = rule.triggerParams["levelPercent"]?.content?.toIntOrNull() ?: return@forEach
                val met = !isCharging && level <= threshold
                val a = armed[rule.id] ?: false
                if (met && !a && canFire(rule)) {
                    fire(rule, "电量 ${level}% ≤ ${threshold}% 且未充电，执行动作")
                    armed[rule.id] = true
                }
                if (!met) armed[rule.id] = false
            }
    }

    /**
     * 设备联网状态（边沿类）。
     * 同时驱动两类条件：
     *  - network_type_changed：网络类型跳变且当前 == targetType（如 5G→4G）时触发（旗舰用例：锁回 5G）
     *  - disconnect：由联网跳变为断网时触发
     * 冷启动仅记忆初始状态，不触发。
     *
     * 2026-09-04：入参改为「设备外网是否实际可达」（`TelephonyCollector.isDeviceOnline()`），
     * 并加 [CONFIRM_MS] 确认窗口 —— 此前用 modem 注册态且无窗口，搜网/切网的瞬时空档就会
     * 触发 `disconnect` 规则（可能是"重启网络"这类有副作用的动作）。规则自带的 cooldown
     * 只能限频，挡不住误触发。
     */
    fun evaluateConnectivity(isConnected: Boolean, networkType: String) {
        val prevConnected = lastNetworkConnected
        val prevType = lastNetworkType
        val now = System.currentTimeMillis()

        if (prevConnected == null) {
            // 冷启动，仅初始化记忆，不触发
            lastNetworkConnected = isConnected
            lastNetworkType = networkType
            return
        }

        // 确认窗口：新状态没连续保持够时间就只记「待确认」，不更新已确认态、不触发规则
        if (prevConnected != isConnected || (prevType != networkType && networkType.isNotEmpty())) {
            if (pendingConnected != isConnected || pendingType != networkType) {
                pendingConnected = isConnected
                pendingType = networkType
                pendingSince = now
                return
            }
            if (now - pendingSince < CONFIRM_MS) return
        } else {
            // 回到已确认态 → 清掉待确认记录（抖动被吸收）
            pendingConnected = null
            pendingSince = 0L
            pendingType = ""
            return
        }

        lastNetworkConnected = isConnected
        lastNetworkType = networkType
        pendingConnected = null
        pendingSince = 0L
        pendingType = ""

        // 网络类型跳变（边沿）
        if (prevType.isNotEmpty() && networkType.isNotEmpty() && prevType != networkType) {
            rules.values
                .filter { it.enabled && it.triggerType == "network_type_changed" }
                .forEach { rule ->
                    val target = rule.triggerParams["targetType"]?.content ?: return@forEach
                    if (networkType == target && canFire(rule)) {
                        fire(rule, "网络类型跳变：$prevType → $networkType，自动执行动作")
                    }
                }
        }

        // 断网（边沿）
        if (prevConnected && !isConnected) {
            rules.values
                .filter { it.enabled && it.triggerType == "disconnect" }
                .forEach { rule ->
                    if (canFire(rule)) fire(rule, "蜂窝网络已断开，执行动作")
                }
        }
    }

    // ──────────── 内部 ────────────

    private fun canFire(rule: AutomationRule): Boolean {
        val now = System.currentTimeMillis()
        val last = lastFiredAt[rule.id] ?: 0L
        return (now - last) >= rule.cooldownMs
    }

    private fun fire(rule: AutomationRule, reason: String) {
        lastFiredAt[rule.id] = System.currentTimeMillis()
        AppLogger.i(tag, "Firing rule '${rule.name}' (${rule.id}): $reason")
        scope.launch {
            try {
                val result = actionExecutor.execute(rule.actionType, rule.params)
                appendLog(rule.id, ExecutionLog(taskId = rule.id, success = result.success, output = result.message))
                AppLogger.i(tag, "Rule '${rule.name}' ${if (result.success) "succeeded" else "failed"}: ${result.message}")
            } catch (e: Exception) {
                appendLog(rule.id, ExecutionLog(taskId = rule.id, success = false, output = (e.message ?: "Unknown error").take(500)))
                AppLogger.e(tag, "Rule '${rule.name}' exception: ${e.message}")
            }
        }
    }

    private fun appendLog(ruleId: String, log: ExecutionLog) {
        val rule = rules[ruleId] ?: return
        val updated = rule.copy(logs = (rule.logs + log).takeLast(50))
        rules[ruleId] = updated
        saveRules()
    }

    private fun fmt(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"
    }

    private fun saveRules() {
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AutomationRule.serializer()),
            rules.values.toList()
        )
        prefs.edit().putString("rules_json", json).apply()
    }

    private fun loadRules() {
        val json = prefs.getString("rules_json", null) ?: return
        try {
            val list = kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AutomationRule.serializer()),
                json
            )
            list.forEach { rules[it.id] = it }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to load rules: ${e.message}")
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        AppLogger.i(tag, "ConditionEngine stopped")
    }
}
