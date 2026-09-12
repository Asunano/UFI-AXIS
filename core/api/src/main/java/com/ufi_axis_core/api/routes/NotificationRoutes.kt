package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.notify.GateVerdict
import com.ufi_axis_core.util.AppSettings
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 客户端通知配置（设备级，app 与 web 共享同一份）。
 *
 * 【为什么放 core】这些开关原先是 app `ufi_axis_prefs` 里 10 个散装 per-field key，
 * core 侧完全没有端点，web 完全看不到 —— 同一台设备从 app 关掉「短信通知」，
 * 从 web 进去看不到也改不了。现在 core 是唯一真源，客户端只做缓存 + 进程内镜像。
 *
 * 【与 `AlertConfig` 的边界】本模型是**客户端要不要投递通知**（外加邮件渠道的两个闸）；
 * `AlertConfig.enabled` / `perType` 是**服务端要不要产生告警**。两件事，不要互相当开关用。
 * 2026-09-08 起两者不再有"投递总闸"这种交集：`AlertConfig.notifyEnabled` 已删除，
 * 邮件是否放行改由 [NotificationConfig.master_enabled] + [NotificationConfig.mail_respect_dnd]
 * 决定（判定只有 [notifyAllowed] 一处）。**本模型里没有 `mail_enabled`** ——
 * "邮件通道开不开"的真源是 `SmsForwardController.isSendable()`，理由见 [mail_respect_dnd]。
 *
 * 【隧道通知为什么有一个字段】[NotificationConfig.tunnel_enabled] 是**本机要不要弹**，
 * 与 `AppSettings.tunnelNotifyOnFailure`（**设备端要不要推**）串联，不是同一概念的第二份真源。
 */
@Serializable
data class NotificationConfig(
    /**
     * **L1 全局通知总闸**（2026-09-08 新增，默认关）：管**所有渠道** —— 客户端状态栏与设备端邮件。
     *
     * app 侧真源镜像是 `notification_master_enabled`，判定只有一处
     * （`NotificationCenter.notify`）；邮件侧判定在 core 装配层（见 [notifyAllowed]）。
     *
     * 与 [alert_enabled] 的区别：后者从此只是**告警分类**（阈值告警），不再兼任总闸。
     * 拆分前一个键同时管"全局 + 告警分类 + 隧道分类 + 后台守护 + 连接通报"，
     * 而短信/验证码/下载三条路径根本不读它 —— 关掉后短信照弹、守护反被静默停掉。
     */
    val master_enabled: Boolean = false,
    /**
     * 告警通知分类开关（阈值告警：温度 / 电量 / 流量 / 信号）。
     *
     * 默认 **false**：app 侧闸门是 `switchOn(KEY_ALERT_NOTIF, false)`
     * （`NotifyScene.ALERT.defaultEnabled` 同为 false），需要用户先授通知权限再显式打开。
     */

    val alert_enabled: Boolean = false,
    /**
     * 设备离线/上线通知（2026-08-29 从 `alert_enabled` 拆出）。
     *
     * 默认 **false**（2026-09-07 由 true 改为 false）：用户要求告警与日常通知一律不默认
     * 开启、需手动打开。本字段是**分场景**开关，仍受 [master_enabled] 总闸约束
     * （客户端只做渲染，闸门见 `NotificationCenter.notify` 的 `sceneEnabledKey`）。
     *
     * 下面几项同理。这一组默认值必须与 app 侧 `NotificationConfigDto`、
     * `NotificationConfigSync.readLocal`、`NotifyScene.defaultEnabled`、
     * `NotifyPrefs.MIRRORED_BOOL_KEYS` 逐字一致 —— 分叉就会出现「UI 显示关但还在推送」。
     */
    val connectivity_enabled: Boolean = false,

    /** 新短信通知 */
    val sms_enabled: Boolean = false,
    /** 验证码通知（2026-09-08 起**独立**于 `sms_enabled`，各自成闸） */
    val verification_enabled: Boolean = false,
    /** 下载完成通知 */
    val download_enabled: Boolean = false,
    /** 流量用量到 80% 的一次性提醒（≠ `AlertConfig.perType["traffic"]` 的绝对 MB 阈值告警） */
    val traffic_80_enabled: Boolean = false,
    /** 设备事件通知（默认关，噪声较大） */
    val device_events_enabled: Boolean = false,
    /**
     * 隧道失败通知的**客户端分类开关**（2026-09-08 新增，默认关）。
     *
     * 与 `AppSettings.tunnelNotifyOnFailure` 不是同一个概念，不是第二份真源：
     * 后者决定**设备端要不要推**（`TunnelManager.notifyGiveUp`），本字段决定
     * **本机要不要弹状态栏**（app 侧 `tunnel_notification_enabled`）。两者串联。
     * 拆出的原因：此前 app 侧隧道借用 `alert_enabled`，"只关隧道提醒、留阈值告警"做不到。
     */
    val tunnel_enabled: Boolean = false,
    /**
     * 邮件是否也遵守免打扰时段（默认 **false**）。
     *
     * 状态栏通知恒定遵守 [dnd_enabled]；邮件默认不受影响 —— 邮件是"事后可查"的渠道，
     * 半夜静音的诉求通常只针对会响铃震动的状态栏。想让邮件也安静的用户在邮件通知页打开本项。
     *
     * 注意这里**没有** `mail_enabled`：邮件渠道开不开的真源是
     * `SmsForwardController.isSendable()`（`enabled` + SMTP 四项必填齐全），
     * 邮件通知页的「启用邮件通知」写的就是它。在此再放一个字段就是第二份真源。
     */
    val mail_respect_dnd: Boolean = false,
    /** 免打扰时段 */
    val dnd_enabled: Boolean = false,
    /**
     * 免打扰起始小时（0..23，含）。默认 23。
     *
     * 与 [dnd_end_hour] 构成一个**允许跨天**的静默窗口：`start > end` 时表示跨零点
     * （23→7 = 当晚 23:00 到次日 07:00）。两者相等视为不静默 —— 零长度窗口比
     * "静默 24 小时"更符合用户误操作时的预期。
     */
    val dnd_start_hour: Int = 23,
    /** 免打扰结束小时（0..23，含）。默认 7。语义见 [dnd_start_hour]。 */
    val dnd_end_hour: Int = 7,
    /** 后台看护轮询总开关 */
    val guard_enabled: Boolean = false,
    /** 后台看护轮询间隔（分钟，15..60） */
    val guard_interval_minutes: Int = 30,
    /**
     * 通知历史保留条数（2026-09-08 新增，默认 500，允许 [HISTORY_ROWS_MIN]..[HISTORY_ROWS_MAX]）。
     *
     * **一个字段管两张历史表**：设备端的邮件投递记录（`mail_send_records`）与客户端的
     * 状态栏通知历史（app `notify_history`）。两者是同一件事的两个渠道，给两个旋钮
     * 只会让人猜"我改的是哪一个"。
     *
     * 判定必须真的读它 —— 这是本仓的硬教训：`AlertConfig.maxRows` 曾是可配置项但引擎
     * 从不读，最后被判定为假开关删掉。core 侧在 `SmsForwardController` 每次裁剪时取当前值，
     * app 侧通过 `NotifyPrefs` 镜像取（状态栏通知由 `:ufi_notify` 写，读不到主进程真源）。
     */
    val history_max_rows: Int = 500,
    /**
     * 通知历史保留天数（2026-09-08 新增，默认 30，**0 = 不按时间清理**）。
     *
     * 与 [history_max_rows] 是「先到者生效」的两道上限，同样一个字段管两张表。
     * 两道都**必须可见**：此前 app 侧藏着一条写死的 7 天规则，用户把条数调到 5000
     * 也只留得下 7 天，而界面上没有任何地方提到时间 —— 那是本仓最忌讳的"看不见的规则"。
     *
     * 允许 0（不限时间）而不是给个很大的天数：用户真正的诉求是"别删"，
     * 让他去猜"填 3650 算不算永久"是把实现细节推给用户。
     */
    val history_max_age_days: Int = 30,
    /** 前台服务保活（仅偏好；真实 FGS 启停由客户端另行处理） */
    val guard_foreground_keepalive_enabled: Boolean = false,
    /**
     * **CRITICAL 兜底**（2026-09-10 新增，默认 **true**）。
     *
     * 打开时，级别为 `critical` 的事件（断网 / 套餐用尽 / 自动关网这一档）会穿透
     * **免打扰时段 / 该渠道未勾选此场景 / 低于该渠道的最低级别**三道闸，照常投递；
     * 但**永远穿不透** [master_enabled]（用户说"一条都别发"）、渠道没配全（发不出去）、
     * 每日配额用尽（穿透就是无上限烧钱）。判定与逐条理由只有一处：
     * `NotificationDispatcher.deliverTo`。
     *
     * 为什么默认 true：这三道闸都是用来挡**噪声**的，而 CRITICAL 不是噪声 ——
     * "半夜断网了却因为免打扰而一声不响"正是用户最不能接受的那种静默失败。
     * 想完全按自己的设置走的用户可以关掉它，穿透时 core 侧会留一行 WARN。
     */
    val critical_override_enabled: Boolean = true
)

/**
 * 通知配置路由
 *
 * GET /api/notifications/config — 读取全量
 * PUT /api/notifications/config — 字段级合并更新
 */
class NotificationRoutes(
    private val settings: AppSettings
) {
    fun register(route: Route) {
        route.route("/notifications") {

            /** 未写入过或 JSON 损坏时回落默认值（不报错），全新设备第一次进页面就有可用配置。 */
            get("/config") {
                call.respond(Json.parseToJsonElement(ConfigJson.encodeToString(
                    NotificationConfig.serializer(),
                    load()
                )))
            }

            /**
             * 字段级合并；body 是上述字段的任意子集。
             *
             * 与 `PUT /api/monitor/preferences` 同语义，同样**不做** `configVersion` 版本守门：
             * 这些是端上投递偏好，最后写入者生效即可。
             */
            put("/config") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    merge(load(), patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "通知配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                validate(merged)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@put
                }
                settings.notificationConfigJson =
                    ConfigJson.encodeToString(NotificationConfig.serializer(), merged)
                call.respond(toJsonElement(mapOf("success" to true, "config" to merged)))
            }
        }
    }

    private fun load(): NotificationConfig = read(settings)

    companion object {
        /**
         * 读取当前通知配置（未写入过或 JSON 损坏时回落默认值）。
         *
         * 暴露成静态是给 core 侧的非路由代码用的：`ComponentFactory` 装配「流量预警」
         * 与「设备事件」两条 core 自主链路时需要查这里的开关，但它拿不到路由实例，
         * 也不该再造第二份解析逻辑（默认值一旦分叉就会出现「设置里关了但还在发」）。
         */
        fun read(settings: AppSettings): NotificationConfig {
            val raw = settings.notificationConfigJson ?: return NotificationConfig()
            return try {
                ConfigJson.decodeFromString(NotificationConfig.serializer(), raw)
            } catch (e: Exception) {
                NotificationConfig()
            }
        }


        /**
         * 通知渠道闸门（纯函数，JVM 可测）：**三态判决**。
         *
         * **一个谓词服务所有"设备主动发出去"的渠道**（邮件 / Webhook / 本机短信）。
         * 2026-09-09 从 `mailAllowed` 改名而来：判据本身与邮件无关，名字带 mail 只会让
         * 下一个渠道以为自己需要再写一份 —— 而两份闸门判定就是两套语义。
         *
         * **2026-09-10 从 Boolean 换成三态**：`false` 分不出"总开关关着"与"在免打扰时段"，
         * 而 CRITICAL 兜底（[NotificationConfig.critical_override_enabled]）只允许穿透后者。
         * 分成两档之后，分发器**不必也不能**重算免打扰 —— 静默窗口与每渠道的 `respectDnd`
         * 都在这一处。判定顺序也是语义：总开关在前，它关着的时候连"是不是半夜"都不必问。
         *
         * **不含"渠道是否启用"**：那件事的真源在各渠道自己那边
         * （邮件 `SmsForwardController.isSendable()`、Webhook `WebhookDelivery.isConfigured()`）。
         * 本函数只回答"全局总闸与免打扰放不放行"。
         *
         * 装配层（`ComponentFactory`）把它包成 `(String) -> GateVerdict`（入参是渠道 id）注入
         * `NotificationDispatcher`，所以 `:core:controller` / `:core:common` 都不需要
         * 反向依赖本模块。
         *
         * @param hour 判定时刻的小时（0..23）。由调用方传入而不是内部取系统时间 —— 纯函数才可测。
         * @param respectDnd 免打扰要不要管这个渠道。**每渠道各有自己的真源**：邮件是
         *   [NotificationConfig.mail_respect_dnd]（默认 false），Webhook 是
         *   `WebhookConfig.respectDnd`（默认 true，它跟状态栏一样会响铃）。
         *   传 false 时静默时段对该渠道不成立。
         */
        fun notifyVerdict(c: NotificationConfig, hour: Int, respectDnd: Boolean): GateVerdict {
            if (!c.master_enabled) return GateVerdict.BLOCKED_BY_MASTER
            if (!respectDnd || !c.dnd_enabled) return GateVerdict.ALLOW
            return if (inDndWindow(c.dnd_start_hour, c.dnd_end_hour, hour)) {
                GateVerdict.BLOCKED_BY_DND
            } else {
                GateVerdict.ALLOW
            }
        }

        /**
         * 闸门此刻放不放行（[notifyVerdict] 的布尔视图）。
         *
         * 给只需要"通不通"的**只读**调用方用：各渠道 `/test` 端点响应里的
         * `auto_notify_enabled`。**不新写一份判定** —— 它就是 `notifyVerdict(...) == ALLOW`，
         * 所以"界面上显示的状态"与"实际拦不拦"不会分叉。
         */
        fun notifyAllowed(c: NotificationConfig, hour: Int, respectDnd: Boolean): Boolean =
            notifyVerdict(c, hour, respectDnd) == GateVerdict.ALLOW

        /**
         * 静默窗口判定：**允许跨天**，右端开区间。
         *
         * `start < end` → `[start, end)`；`start > end` → 跨零点（23→7 = 当晚 23:00 到次日 07:00）；
         * `start == end` → 零长度窗口 = **不静默**（用户把两个滑块拖到同一格时，
         * "什么都不静默"远比"整天静默"接近本意，与 `NotificationCenter` 的免打扰口径一致）。
         */
        fun inDndWindow(startHour: Int, endHour: Int, hour: Int): Boolean = when {
            startHour == endHour -> false
            startHour < endHour -> hour >= startHour && hour < endHour
            else -> hour >= startHour || hour < endHour
        }

        /** 看护轮询间隔允许区间：与 app `GuardScheduler` 的钳制区间一致 */
        private const val GUARD_INTERVAL_MIN = 15
        private const val GUARD_INTERVAL_MAX = 60

        /**
         * 通知历史保留条数的允许区间。
         *
         * 下限 100：再少就失去"回头查"的意义（一次告警风暴就能把窗口冲干净）。
         * 上限 5000：两张表都在设备本地，`trimTo` 是每次写入后的全表子查询，
         * 条数上去之后裁剪本身会变成写路径上的负担。
         */
        const val HISTORY_ROWS_MIN = 100
        const val HISTORY_ROWS_MAX = 5000

        /**
         * 保留天数的允许区间。**0 是合法值**，表示不按时间清理。
         *
         * 上限 365：再长的历史对"回头查为什么没收到"没有意义，而条数上限本来就会先生效。
         */
        const val HISTORY_AGE_DAYS_MIN = 0
        const val HISTORY_AGE_DAYS_MAX = 365

        /**
         * `encodeDefaults = true` 是必需的：默认 Json 会省略等于默认值的字段，
         * 全新设备 GET 会返回 `{}`，两端只能各自兜默认值 —— 又回到「各说各话」。
         */
        private val ConfigJson = com.ufi_axis_core.util.ConfigJson

        /** 字段级合并：只有 patch 里显式出现且非 null 的键会覆盖，其余保持服务端现值。 */
        internal fun merge(current: NotificationConfig, patch: JsonObject): NotificationConfig {
            val effective = patch.filterValues { it !is JsonNull }
            if (effective.isEmpty()) return current
            val base = ConfigJson.encodeToJsonElement(NotificationConfig.serializer(), current).jsonObject
            return ConfigJson.decodeFromJsonElement(
                NotificationConfig.serializer(),
                JsonObject(base + effective)
            )
        }

        /** 返回第一条约束违规说明；全部合法返回 null。 */
        internal fun validate(c: NotificationConfig): String? = when {
            c.guard_interval_minutes !in GUARD_INTERVAL_MIN..GUARD_INTERVAL_MAX ->
                "后台看护间隔（guard_interval_minutes）必须在 " +
                    "$GUARD_INTERVAL_MIN..$GUARD_INTERVAL_MAX 分钟之间，收到 ${c.guard_interval_minutes}"

            c.dnd_start_hour !in 0..23 ->
                "免打扰开始小时（dnd_start_hour）必须在 0..23 之间，收到 ${c.dnd_start_hour}"
            c.dnd_end_hour !in 0..23 ->
                "免打扰结束小时（dnd_end_hour）必须在 0..23 之间，收到 ${c.dnd_end_hour}"
            c.history_max_rows !in HISTORY_ROWS_MIN..HISTORY_ROWS_MAX ->
                "通知历史保留条数（history_max_rows）必须在 $HISTORY_ROWS_MIN..$HISTORY_ROWS_MAX 之间，收到 ${c.history_max_rows}"
            c.history_max_age_days !in HISTORY_AGE_DAYS_MIN..HISTORY_AGE_DAYS_MAX ->
                "通知历史保留天数（history_max_age_days）必须在 $HISTORY_AGE_DAYS_MIN..$HISTORY_AGE_DAYS_MAX 之间" +
                    "（0 = 不按时间清理），收到 ${c.history_max_age_days}"
            else -> null
        }
    }
}
