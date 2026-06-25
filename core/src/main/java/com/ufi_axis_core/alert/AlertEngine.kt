package com.ufi_axis_core.alert

import com.ufi_axis_core.core.database.AlertDao
import com.ufi_axis_core.core.database.AlertRecord
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.serialization.json.Json
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
 * - connectivity: 网络断连
 *
 * 特性:
 * - 防抖: 同一告警 5 分钟内不重复触发
 * - 分级: info / warning / critical
 * - 推送到手机端 (WebSocket)
 * - SQLite 持久化
 */
class AlertEngine(
    private val alertDao: AlertDao,
    private val webSocketManager: WebSocketManager,
    private val appSettings: AppSettings
) {
    private val tag = "AlertEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 防抖缓存: alertType -> lastTriggerTime（ConcurrentHashMap 保证协程并发安全）
    private val debounceCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val DEBOUNCE_INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟

    // 告警配置
    @Serializable
    data class AlertConfig(
        val enabled: Boolean = true,
        val temperatureWarning: Double = 45.0,
        val temperatureCritical: Double = 55.0,
        val batteryWarning: Int = 20,
        val batteryCritical: Int = 10,
        val trafficWarningMb: Long = 1024,      // 1GB
        val trafficCriticalMb: Long = 2048,     // 2GB
        val signalWarningRsrp: Int = -100,
        val signalCriticalRsrp: Int = -115
    )

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AlertConfig> = _config

    /**
     * 从 SharedPreferences 加载告警配置
     */
    private fun loadConfig(): AlertConfig {
        val json = appSettings.alertConfigJson
        return if (json != null) {
            try {
                Json.decodeFromString<AlertConfig>(json)
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed to parse alert config, using defaults: ${e.message}")
                AlertConfig()
            }
        } else {
            AlertConfig()
        }
    }

    /**
     * 更新告警配置（持久化到 SharedPreferences）
     */
    fun updateConfig(newConfig: AlertConfig) {
        _config.value = newConfig
        appSettings.alertConfigJson = Json.encodeToString(AlertConfig.serializer(), newConfig)
        AppLogger.i(tag, "Alert config updated and persisted")
    }

    /**
     * 检查温度告警
     */
    suspend fun checkTemperature(temperature: Double) {
        val cfg = _config.value
        if (!cfg.enabled) return

        when {
            temperature >= cfg.temperatureCritical -> {
                triggerAlert("temperature", "critical",
                    "设备温度严重过高: ${temperature}°C",
                    temperature.toString(), cfg.temperatureCritical.toString())
            }
            temperature >= cfg.temperatureWarning -> {
                triggerAlert("temperature", "warning",
                    "设备温度偏高: ${temperature}°C",
                    temperature.toString(), cfg.temperatureWarning.toString())
            }
        }
    }

    /**
     * 检查电池告警
     */
    suspend fun checkBattery(level: Int, isCharging: Boolean) {
        val cfg = _config.value
        if (!cfg.enabled || isCharging) return

        when {
            level <= cfg.batteryCritical -> {
                triggerAlert("battery", "critical",
                    "电池电量极低: $level%",
                    level.toString(), cfg.batteryCritical.toString())
            }
            level <= cfg.batteryWarning -> {
                triggerAlert("battery", "warning",
                    "电池电量偏低: $level%",
                    level.toString(), cfg.batteryWarning.toString())
            }
        }
    }

    /**
     * 检查流量告警
     */
    suspend fun checkTraffic(totalMb: Long) {
        val cfg = _config.value
        if (!cfg.enabled) return

        when {
            totalMb >= cfg.trafficCriticalMb -> {
                triggerAlert("traffic", "critical",
                    "流量严重超额: ${totalMb}MB",
                    totalMb.toString(), cfg.trafficCriticalMb.toString())
            }
            totalMb >= cfg.trafficWarningMb -> {
                triggerAlert("traffic", "warning",
                    "流量超额: ${totalMb}MB",
                    totalMb.toString(), cfg.trafficWarningMb.toString())
            }
        }
    }

    /**
     * 检查信号告警
     */
    suspend fun checkSignal(rsrp: Int) {
        val cfg = _config.value
        if (!cfg.enabled) return

        when {
            rsrp <= cfg.signalCriticalRsrp -> {
                triggerAlert("signal", "critical",
                    "信号极差: RSRP=${rsrp}dBm",
                    rsrp.toString(), cfg.signalCriticalRsrp.toString())
            }
            rsrp <= cfg.signalWarningRsrp -> {
                triggerAlert("signal", "warning",
                    "信号较差: RSRP=${rsrp}dBm",
                    rsrp.toString(), cfg.signalWarningRsrp.toString())
            }
        }
    }

    /**
     * 触发告警（含防抖）
     */
    private suspend fun triggerAlert(
        type: String,
        level: String,
        message: String,
        value: String,
        threshold: String
    ) {
        val debounceKey = "${type}_$level"
        val now = System.currentTimeMillis()
        val lastTrigger = debounceCache[debounceKey] ?: 0

        if (now - lastTrigger < DEBOUNCE_INTERVAL_MS) {
            AppLogger.d(tag, "Alert debounced: $debounceKey")
            return
        }
        debounceCache[debounceKey] = now

        AppLogger.i(tag, "Alert triggered: [$level] $message")

        // 存储到数据库
        val record = AlertRecord(
            type = type,
            level = level,
            message = message,
            value = value,
            threshold = threshold
        )
        alertDao.insert(record)

        // WebSocket 推送到客户端
        webSocketManager.broadcast("alert", mapOf(
            "type" to type,
            "level" to level,
            "message" to message,
            "value" to value,
            "threshold" to threshold,
            "timestamp" to now
        ))
    }

    /**
     * 获取最近的告警记录
     */
    suspend fun getRecentAlerts(limit: Int = 20): List<AlertRecord> {
        return alertDao.getRecentAlerts(limit)
    }

    /**
     * 确认告警
     */
    suspend fun acknowledgeAlert(id: Long) {
        alertDao.acknowledge(id)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }
}
