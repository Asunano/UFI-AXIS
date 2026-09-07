package com.ufi_axis_core.controller.network

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.formatDataSize
import com.ufi_axis_core.util.formatUsagePercent
import com.ufi_axis_core.util.normalizeAlertPercent
import com.ufi_axis_core.util.trafficUsagePercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 到达流量告警阈值后自动关闭移动数据（core 自制功能，设备本身没有这个能力）。
 *
 * ## 触发链路
 *
 * `DataScheduler` 的流量循环每 5 分钟调一次 [onUsage]（节流见
 * `DataScheduler.TRAFFIC_LIMIT_CHECK_INTERVAL_MS`），阈值直接复用**流量管理里已有的
 * 告警百分比** `alert_percent`，不再引入第二个阈值配置。
 *
 * ## 为什么先发邮件、发成功才关
 *
 * 用户视角里"网断了"和"设备坏了"没有区别。所以顺序是
 * **发邮件 → 发信成功 → 等 1 分钟 → 关网**：
 * - 邮件发失败就**不关网**（宁可多跑流量，也不能让人以为故障还查不到原因）；
 * - 1 分钟是留给用户的反应窗口（收到邮件还能抢救一下手上的下载）。
 *
 * 关网动作放在 [scope] 的独立协程里：等待这 1 分钟不能占着采集循环。
 *
 * ## 只关一次
 *
 * 触发后把「计费周期 + 已关网」写进 `AppSettings.trafficAutoOffStateJson`。用量回落
 * （清零日 / 用户校准）或跨月时这个凭据作废，下一周期才能再触发。凭据必须持久化，
 * 否则 core 重启后会再关一次、再发一封邮件。
 *
 * 恢复策略由用户二选一（[Config.restoreOnReset]）：清零后自动重开，或只关一次等手动开。
 */
class TrafficAutoOffGuard(
    private val settings: AppSettings,
    private val networkController: NetworkController,
    /** 发信口（返回 true = 确实发出去了）。由 ComponentFactory 接到 SmsForwardController。 */
    private val mailSender: suspend (title: String, body: String) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val tag = "TrafficAutoOffGuard"

    /** 已发邮件、正在等 1 分钟的那段时间：不重复触发（进程内即可，重启后 state 会兜住）。 */
    @Volatile private var pending = false

    /** 用户配置。 */
    data class Config(
        /** 达到告警阈值后自动关闭移动数据。 */
        val enabled: Boolean = false,
        /** 流量清零后自动重新打开；false = 只关一次，等用户手动开。 */
        val restoreOnReset: Boolean = false,
    )

    private data class State(val cycle: String, val turnedOff: Boolean, val at: Long)

    /**
     * 每轮限额检查调用一次。
     *
     * @param usedBytes 本月已用（rx+tx）
     * @param limitBytes 套餐限额；<= 0 表示未设限额，直接返回
     * @param alertPercent 流量管理里的告警百分比（1..100，越界回落 80）
     */
    suspend fun onUsage(usedBytes: Long, limitBytes: Long, alertPercent: Int) {
        val cfg = readConfig(settings)
        if (!cfg.enabled) return
        if (limitBytes <= 0L) return

        val warnPercent = normalizeAlertPercent(alertPercent)
        val percent = trafficUsagePercent(usedBytes, limitBytes)
        val cycle = currentCycle()
        val state = readState()

        // 用量回落或跨周期 —— 上一次的"已关网"凭据作废
        if (percent < warnPercent || (state != null && state.cycle != cycle)) {
            if (state?.turnedOff == true) {
                if (cfg.restoreOnReset) {
                    val ok = runCatching { networkController.setMobileData(true) }.getOrDefault(false)
                    AppLogger.i(tag, "流量已清零，自动重新打开移动数据: $ok")
                    mailSender("移动数据已恢复", buildString {
                        appendLine("流量已清零（或计费周期已切换），移动数据网络已自动重新打开。")
                        appendLine("恢复结果: ${if (ok) "成功" else "失败，请手动检查"}")
                    }.trimEnd())
                } else {
                    AppLogger.i(tag, "流量已清零，但恢复策略是「只关一次」，保持关闭")
                }
            }
            if (state != null) writeState(null)
            return
        }

        if (state?.turnedOff == true || pending) return

        pending = true
        val shown = formatUsagePercent(percent)
        scope.launch {
            try {
                val body = buildString {
                    appendLine("本月流量已用 $shown%（阈值 $warnPercent%）。")
                    appendLine("已用: ${formatBytes(usedBytes)} / 限额: ${formatBytes(limitBytes)}")
                    appendLine()
                    appendLine("本邮件发出 1 分钟后将自动关闭移动数据网络 —— 这是你在流量管理里开启的自动保护，不是设备故障。")
                    appendLine(
                        if (cfg.restoreOnReset) "流量清零后会自动重新打开移动数据。"
                        else "关闭后不会自动恢复，需要手动重新打开移动数据。"
                    )
                }.trimEnd()
                val sent = mailSender("流量即将达到限额，1 分钟后将关闭移动数据", body)
                if (!sent) {
                    // 关键约定：邮件没发出去就不关网。否则用户只会看到"突然断网"。
                    AppLogger.w(tag, "预警邮件未发出（场景未勾选 / SMTP 未配置 / 发送失败），本次不关闭移动数据")
                    return@launch
                }
                delay(DELAY_BEFORE_OFF_MS)
                val ok = runCatching { networkController.setMobileData(false) }.getOrDefault(false)
                if (ok) {
                    writeState(State(cycle, turnedOff = true, at = System.currentTimeMillis()))
                    AppLogger.w(tag, "已达流量告警阈值（$shown%），移动数据已自动关闭")
                } else {
                    // 不写 state：下一轮（5 分钟后）会重来一遍（含邮件），比"以为关了其实没关"安全
                    AppLogger.e(tag, "自动关闭移动数据失败，将在下一轮重试")
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "自动关网流程异常", e)
            } finally {
                pending = false
            }
        }
    }

    private fun currentCycle(): String = java.time.LocalDate.now().let {
        "%04d-%02d".format(it.year, it.monthValue)
    }

    private fun readState(): State? {
        val raw = settings.trafficAutoOffStateJson ?: return null
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            State(
                cycle = obj["cycle"]?.jsonPrimitive?.contentOrNull ?: return null,
                turnedOff = obj["turned_off"]?.jsonPrimitive?.booleanOrNull ?: false,
                at = obj["at"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        } catch (e: Exception) {
            AppLogger.w(tag, "自动关网状态解析失败，按未触发处理: ${e.message}")
            null
        }
    }

    private fun writeState(state: State?) {
        settings.trafficAutoOffStateJson = state?.let {
            buildJsonObject {
                put("cycle", it.cycle)
                put("turned_off", it.turnedOff)
                put("at", it.at)
            }.toString()
        }
    }

    /**
     * 2026-09-02：删掉本地实现，统一走 core:common 的 [formatDataSize]。
     * 原来这里和 TrafficSummaryMapper 各有一份只到 GB 的拷贝，导致邮件正文与
     * API/告警文案在 TB 级流量上给出不同数字。
     */
    private fun formatBytes(bytes: Long): String = formatDataSize(bytes)


    companion object {
        /** 发信成功到真正关网之间的缓冲。用户至少有 1 分钟收邮件、抢救手上的传输。 */
        const val DELAY_BEFORE_OFF_MS = 60_000L

        /** 读用户配置（缺省全关：这功能会主动断网，不能默认开）。 */
        fun readConfig(settings: AppSettings): Config {
            val raw = settings.trafficAutoOffConfigJson ?: return Config()
            return try {
                val obj = Json.parseToJsonElement(raw).jsonObject
                Config(
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    restoreOnReset = obj["restore_on_reset"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            } catch (e: Exception) {
                Config()
            }
        }

        /** 写用户配置。运行状态是另一个 key，这里不会碰到它。 */
        fun writeConfig(settings: AppSettings, cfg: Config) {
            settings.trafficAutoOffConfigJson = buildJsonObject {
                put("enabled", cfg.enabled)
                put("restore_on_reset", cfg.restoreOnReset)
            }.toString()
        }

        /** 运行状态（给 API 回读："本周期是否已经因为限额关过网"）。 */
        fun readStateJson(settings: AppSettings): JsonObject? = try {
            settings.trafficAutoOffStateJson?.let { Json.parseToJsonElement(it).jsonObject }
        } catch (e: Exception) {
            null
        }
    }
}
