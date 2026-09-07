package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
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
 * 【与 `AlertConfig` 的边界】本模型是**客户端要不要投递通知**；
 * `AlertConfig.enabled` / `perType` 是**服务端要不要产生告警**。两件事，不要互相当开关用。
 * 唯一有交集的是 `alert_enabled` ↔ `AlertConfig.notifyEnabled`（后者是服务端侧的投递总闸），
 * 客户端仍需要自己这一份，因为通知权限、免打扰、前台保活都是端上的事。
 *
 * 【为什么没有隧道通知】隧道失败通知的真源是 `AppSettings.tunnelNotifyOnFailure`
 * （`PUT /api/tunnel/settings` 的 `notify_on_failure`），早已在 core。
 * 再加一个 `tunnel_enabled` 就是给同一概念造第二份真源。
 */
@Serializable
data class NotificationConfig(
    /**
     * 告警通知总开关 —— 同时是**告警族的上层总闸**：阈值告警 / 设备离线上线
     * （`connectivity_enabled`）/ 流量 80% / 隧道失败，关掉后一条都不投递。
     *
     * 默认 **false**：app 侧所有实际闸门用的都是 `switchOn(KEY_ALERT_NOTIF, false)`，
     * 需要用户先授通知权限再显式打开（`NotifyScene.ALERT.defaultEnabled` 同为 false）。
     */

    val alert_enabled: Boolean = false,
    /**
     * 设备离线/上线通知（2026-08-29 从 `alert_enabled` 拆出）。
     *
     * 默认 **false**（2026-09-07 由 true 改为 false）：用户要求告警与日常通知一律不默认
     * 开启、需手动打开。本字段是**分场景**开关，仍受 `alert_enabled` 总闸约束
     * （客户端闸门见 `NotificationCenter.notifyDeviceConnectivity`）。
     *
     * 下面 4 项同理。这一组默认值必须与 app 侧 `NotificationConfigDto`、
     * `NotificationConfigSync.readLocal`、`NotifyScene.defaultEnabled`、
     * `NotifyPrefs.MIRRORED_BOOL_KEYS` 逐字一致 —— 分叉就会出现「UI 显示关但还在推送」。
     */
    val connectivity_enabled: Boolean = false,

    /** 新短信通知 */
    val sms_enabled: Boolean = false,
    /** 验证码通知（依赖 `sms_enabled`，两者都开才投递） */
    val verification_enabled: Boolean = false,
    /** 下载完成通知 */
    val download_enabled: Boolean = false,
    /** 流量用量到 80% 的一次性提醒（≠ `AlertConfig.perType["traffic"]` 的绝对 MB 阈值告警） */
    val traffic_80_enabled: Boolean = false,
    /** 设备事件通知（默认关，噪声较大） */
    val device_events_enabled: Boolean = false,
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
    /** 前台服务保活（仅偏好；真实 FGS 启停由客户端另行处理） */
    val guard_foreground_keepalive_enabled: Boolean = false
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


        /** 看护轮询间隔允许区间：与 app `GuardScheduler` 的钳制区间一致 */
        private const val GUARD_INTERVAL_MIN = 15
        private const val GUARD_INTERVAL_MAX = 60

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
                "guard_interval_minutes 必须在 $GUARD_INTERVAL_MIN..$GUARD_INTERVAL_MAX 分钟之间，收到 ${c.guard_interval_minutes}"
            c.dnd_start_hour !in 0..23 ->
                "dnd_start_hour 必须在 0..23 之间，收到 ${c.dnd_start_hour}"
            c.dnd_end_hour !in 0..23 ->
                "dnd_end_hour 必须在 0..23 之间，收到 ${c.dnd_end_hour}"
            else -> null
        }
    }
}
