package com.ufi_axis_core.controller.network

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.Notifier

import com.ufi_axis_core.util.formatDataSize
import com.ufi_axis_core.util.formatUsagePercent
import com.ufi_axis_core.util.normalizeAlertPercent
import com.ufi_axis_core.util.trafficUsagePercent
import kotlinx.coroutines.CancellationException
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
 * `DataScheduler` 的流量循环按「套餐限额检查间隔」调一次 [onUsage]（默认 5 分钟，
 * 2026-09-08 起是设置项 `AppSettings.monitorTrafficLimitCheckSec`，节流见
 * `DataScheduler.trafficLimitCheckIntervalMs`），阈值直接复用**流量管理里已有的
 * 告警百分比** `alert_percent`，不再引入第二个阈值配置。
 *
 * ## 为什么先发通知、发成功才关
 *
 * 用户视角里"网断了"和"设备坏了"没有区别。所以顺序是
 * **发通知 → 确认通报到位 → 等 1 分钟 → 关网**：
 * - 通报没到位就**不关网**（宁可多跑流量，也不能让人以为故障还查不到原因）；
 * - 1 分钟是留给用户的反应窗口（收到通知还能抢救一下手上的下载）。
 *
 * ### 「通报到位」到底怎么判（这条规则必须写明白，别让它成为看不见的规则）
 *
 * 判定实现是 `DeliveryReport.anyConfirmedSent()`，只看**有送达确认的渠道**
 * （邮件 SMTP 250 应答、Webhook 2xx、本机短信信箱 tag=2；推送是 fire-and-forget 广播，
 * 恒 `Sent`，拿它判等于恒真）。算"到位"的情形：
 * - `Sent` —— 真发出去了；
 * - `Skipped(GATE)` —— 用户**自己**关了通知总开关或设了免打扰；
 * - `Skipped(SCENE_OFF)` / `Skipped(LEVEL_TOO_LOW)`（2026-09-09 补齐）——
 *   用户**自己**在那个渠道里取消了 `traffic80` 的勾选、或把最低级别调高了。
 *
 * 后两档补齐的理由：「在邮件设置里取消勾选 traffic80」与「关掉通知总开关」是**同一类主动
 * 选择**，而补齐之前只认 GATE，于是前者会让自动关网**永久不执行** —— 用户开着"到量自动
 * 关网"，却因为少勾一个邮件场景而从来不生效。那正是 GATE 那一档想避免的"假开关"，
 * 只是换了个入口进来。
 *
 * #### 「用户自选静默照常关网」不是零通知（别把它当漏洞修）
 *
 * 上面三档 `Skipped` 都算"到位"，读起来像"没人被通知却把网关了"。实际不是：
 * **WS 推送渠道仍然会投**。它不受总闸与场景勾选约束（是 fire-and-forget 广播，客户端
 * 自己决定弹不弹），所以 app 在前台/连着 WS 时照样看得到这条预警 ——
 * 被静默的只是邮件 / Webhook / 本机短信这几条**有送达确认**的渠道，
 * 而那正是用户自己关掉的东西。
 * 也正因为推送恒 `Sent`，它不能用来判"通报到位"（见 [notifier]）：一个恒真的判据
 * 等于没有判据。两件事必须分开看 —— **判据里不认它，但它确实投了**。

 *
 * 仍然**不关网**的三种（原语义保留）：
 * - `Skipped(NOT_CONFIGURED)` —— 渠道没配全，用户根本收不到任何通报；
 * - `Skipped(QUOTA_EXCEEDED)` —— 短信渠道当天配额烧完了。这是"**想通知但没能力**"，
 *   与"SMTP 没配"同类，不是用户选的静默；
 * - `Failed` —— 重试完仍然发不出去。
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
    /**
     * 通知口（由 ComponentFactory 接到 `NotificationDispatcher::emit`）。
     *
     * 返回每渠道结果 + 渠道元信息，本类只看 `anyConfirmedSent()`：
     * **有送达确认的渠道通报到位了**（判定口径与"算作到位"的几种例外见类头注释）。
     * 2026-09-08 之前这里是 `(title, body) -> Boolean` 的邮件专用钩子；换成统一签名时
     * 一度用 `anySent()`，那是错的 —— 推送恒返回 `Sent`，判据会恒真，SMTP 没配好也照样断网。
     *
     * 投递**带有界重试**（最多 3 次、退避 2s + 6s，见 `RetryPolicy.DEFAULT`），所以最坏情况这里
     * 要多等约 40s 才拿到结果，关网也就被推迟同样的时间。这是刻意取舍：
     * 宁可晚关 40s，也不要"通知还没发出去就把网关了"—— 用户视角里那就是设备突然坏了。
     */
    private val notifier: Notifier,


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
                    notify(
                        title = "移动数据已恢复",
                        body = buildString {
                            appendLine("流量已清零（或计费周期已切换），移动数据网络已自动重新打开。")
                            appendLine("恢复结果: ${if (ok) "成功" else "失败，请手动检查"}")
                        }.trimEnd()
                    )
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
                // 这一步会一直等到整轮投递（含重试）出结果，最坏约 40s；
                // 后面那 1 分钟的反应窗口照旧，所以关网最多晚 40s 发生。刻意如此，见 [notifier]。
                val reported = notify("流量即将达到限额，1 分钟后将关闭移动数据", body)
                if (!reported) {
                    // 关键约定：通报没到位就不关网。否则用户只会看到"突然断网"。
                    AppLogger.w(tag, "预警通知没能通报到位（SMTP 未配置齐全、场景未勾选，或重试后仍失败），本次不关闭移动数据")
                    return@launch
                }


                delay(DELAY_BEFORE_OFF_MS)
                val ok = runCatching { networkController.setMobileData(false) }.getOrDefault(false)
                if (ok) {
                    writeState(State(cycle, turnedOff = true, at = System.currentTimeMillis()))
                    AppLogger.w(tag, "已达流量告警阈值（$shown%），移动数据已自动关闭")
                } else {
                    // 不写 state：下一轮限额检查会重来一遍（含通知），比"以为关了其实没关"安全
                    AppLogger.e(tag, "自动关闭移动数据失败，将在下一轮重试")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(tag, "自动关网流程异常", e)
            } finally {
                pending = false
            }
        }
    }

    /**
     * 发一条流量预警类通知，返回"通报到位了吗"（口径见类头注释与 [notifier]）。
     *
     * 场景固定 [NotifyScenes.TRAFFIC_80]：与套餐限额百分比预警同一个场景，
     * 用户在邮件通知页勾了才发邮件（勾没勾由渠道判，不在这里）。
     */
    private suspend fun notify(title: String, body: String): Boolean = notifier(
        NotifyEvent(
            scene = NotifyScenes.TRAFFIC_80,
            level = NotifyLevel.WARNING,
            title = title,
            body = body
        )
    ).anyConfirmedSent()



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
