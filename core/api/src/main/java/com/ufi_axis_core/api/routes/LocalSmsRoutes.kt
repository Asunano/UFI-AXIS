package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.notify.LocalSmsChannel
import com.ufi_axis_core.controller.notify.LocalSmsConfig
import com.ufi_axis_core.controller.notify.LocalSmsConfigStore
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.SkipReason

import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 本机短信回发渠道的配置与测试端点（阶段 3）。
 *
 * ```
 * GET  /api/notify/sms/config   读全量（含 configured / sent_today / quota_remaining 三个只读位）
 * PUT  /api/notify/sms/config   字段级合并（口径同 PUT /api/notify/webhook/config）
 * POST /api/notify/sms/test     真发一条短信，响应说明是否计入配额与剩余配额
 * ```
 *
 * ## 为什么路径是 `/api/notify/sms` 而渠道 id 是 `local_sms`
 *
 * 路径按"用户看到的东西"命名（这一页叫「本机短信」），渠道 id 要与**场景** id `sms`
 * 区分开（见 [LocalSmsChannel.ID] 的注释）。两者不必同名 —— 邮件那条同样是
 * `/api/sms-forward` 对 `mail`。
 *
 * ## 这一组端点和另两条渠道最大的不同：`POST /test` **真的花钱**
 *
 * 所以它的响应必须回三样东西，缺一个用户就会反复点：
 * 成功/失败原因、**这次算不算进配额**、**还剩几条**。
 */
class LocalSmsRoutes(
    private val store: LocalSmsConfigStore,
    /**
     * 只为读 [LocalSmsChannel.isConfigured]（"能不能投"的判据只有渠道那一份）。
     *
     * **不再从渠道回读投递快照** —— 固件结论与配额结算现在随 `DeliveryReport` 一起回来
     * （见 `DeliveryAttempt`）。
     */
    private val channel: LocalSmsChannel,

    /** 通知分发器入口（装配层接到 `NotificationDispatcher::emit`）。 */
    private val notifier: Notifier,
    /**
     * 闸门**只读取值口**（装配层注入的那同一个 lambda，已按 local_sms 渠道绑好 `respectDnd`）。
     *
     * 不拦任何投递（强制点只有 `NotificationDispatcher.emit`），唯一用途是 `/test` 响应里的
     * `auto_notify_enabled` —— 让用户知道"这条测试短信发出去了，自动通知却是关的"。
     */
    private val gate: () -> Boolean
) {
    fun register(route: Route) {
        route.route("/notify/sms") {

            get("/config") {
                call.respond(toJsonElement(configPayload(store.load())))
            }

            /** 字段级合并：body 是配置字段的任意子集，未出现的键保留服务端现值。 */
            put("/config") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    merge(store.load(), patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "本机短信配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                LocalSmsConfig.validate(merged)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@put
                }
                store.save(merged)
                // 立即回读：与另两条渠道同做法，把"存进去没有"当场验掉。
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "config" to configPayload(store.load())
                )))
            }

            /**
             * 手动测试：**真的从本机 SIM 发出一条短信，并且计入今日配额。**
             *
             * 不受总开关、免打扰、场景勾选与级别门槛约束（`manual = true`），但仍要求配置齐全
             * ——号码没填的"测试"发不出去是事实。级别仍然给 CRITICAL：这条测试短信要走的就是
             * "最关键那一档"的完整路径，用一个更低的级别去测反而验不到真实链路。
             *
             * 配额**照扣**是刻意的，也是 `manual` 唯一不放行的一道：不扣的话"发送测试"
             * 就是一个可以无限点的烧钱按钮。响应里把这件事写清楚
             * （`counted_toward_quota` / `quota_remaining`）。
             */
            post("/test") {
                val cfg = store.load()
                val result = mutableMapOf<String, Any?>(
                    "success" to false,
                    "auto_notify_enabled" to gate(),
                    // 无论走到哪个分支都先回一份现值：用户按下按钮后第一眼看的就是"还剩几条"。
                    "counted_toward_quota" to false,
                    "sent_today" to store.sentToday(),
                    "quota_remaining" to store.quotaRemaining(cfg.dailyLimit),
                    "daily_limit" to cfg.dailyLimit
                )
                if (!cfg.enabled) {
                    result["error"] = "本机短信通知未启用"
                } else if (!channel.isConfigured()) {
                    result["error"] = LocalSmsConfig.validate(cfg) ?: "目标号码未填写"
                } else {
                    try {
                        val report = notifier(
                            NotifyEvent(
                                scene = NotifyScenes.TEST,
                                level = NotifyLevel.CRITICAL,
                                title = "短信通道测试",
                                body = TEST_BODY,
                                manual = true,
                                channels = setOf(LocalSmsChannel.ID)
                            )
                        )
                        val outcome = report[LocalSmsChannel.ID]
                        result["success"] = outcome is DeliveryOutcome.Sent
                        when (outcome) {
                            is DeliveryOutcome.Failed -> result["error"] = outcome.error
                            is DeliveryOutcome.Skipped -> result["error"] = skipMessage(outcome, cfg)
                            null -> result["error"] = "设备端未提供本机短信渠道"
                            else -> {}
                        }
                        // 固件结论、配额结算都只有渠道那侧知道（DeliveryOutcome 里没有这些）。
                        //
                        // ## 契约：`attempted_at` 出现 ⇔ 本轮走到过 `deliver()`
                        //
                        // 这六个字段（`verdict` / `detail` / `counted_toward_quota` /
                        // `sent_today` / `quota_remaining` / `attempted_at`）**整组同进同出**，
                        // 来源是同一个 `LocalSmsChannel.Attempt` 对象，不可能只出现一部分。
                        // 于是客户端可以拿 `attempted_at` 是否存在当作"本轮到底有没有真的发过一条"的判据 ——
                        // 这条渠道花钱，那个判据错一次用户就会多按一次测试、多花一条话费。
                        //
                        // core 侧保证这条**双向**成立，逐条核实过：
                        // 1. `Skipped`（测试请求是 manual，实际只会撞上配额那一道）→
                        //    `NotificationDispatcher.deliverTo` 直接 return，诊断给 null，
                        //    下面几个字段保持上面预填的**现值**（那才是这次的真实状态）；
                        // 2. `Sent` / `Failed`（含渠道内部抛异常被兜成 Failed）→ 诊断一定非 null；
                        // 3. 整轮预算用尽 → 判在**尝试之间**、不打断在飞的那一次，所以带回的是
                        //    上一次已完成尝试的诊断。这一条尤其重要：拦腰砍掉一次 goform 发送会造出
                        //    "短信可能已经发出去、配额却没扣、响应里也看不出投过"的三重错位
                        //    （理由写在 `CHANNEL_ROUND_BUDGET_MS` 上）；
                        // 4. 渠道未注册 → `report[ID]` 为 null，两者一起缺。
                        // 不变量由 `NotificationDispatcherTest` 的「诊断与 deliver 同进同出」钉住。
                        (report.diagnosticsOf(LocalSmsChannel.ID) as? LocalSmsChannel.Attempt)?.let { attempt ->

                            result["verdict"] = attempt.verdict
                            result["detail"] = attempt.detail
                            result["counted_toward_quota"] = attempt.countedTowardQuota
                            result["sent_today"] = attempt.sentToday
                            result["quota_remaining"] = attempt.quotaRemaining
                            result["attempted_at"] = attempt.at
                        }
                    } catch (e: Exception) {
                        result["error"] = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                call.respond(HttpStatusCode.OK, toJsonElement(result))
            }
        }
    }

    /**
     * `Skipped` 的人话解释。
     *
     * 测试请求是 `manual`，所以实际只可能撞上**配额**那一道（花钱的闸不为测试放行）；
     * 其余几档由 [skipMessageOf] 按 `SkipReason.label` 统一给话 —— 三条渠道对
     * "总开关关了""在免打扰时段"本来就该是同一句，此前那两张逐字重复的 `when` 表
     * 只是等着有一天分叉。
     *
     * 这里覆写的两档都带**这条渠道独有的数字/词**：配额要说清"5/5，明天重置"
     * （不然用户只会反复点测试），级别要说清当前门槛是哪一档。
     */
    private fun skipMessage(outcome: DeliveryOutcome.Skipped, cfg: LocalSmsConfig): String =
        skipMessageOf(
            outcome.reason,
            mapOf(
                SkipReason.QUOTA_EXCEEDED to
                    "今日配额已用尽（${store.sentToday()}/${cfg.dailyLimit}），明天自动重置",
                SkipReason.LEVEL_TOO_LOW to
                    "当前最低级别为 ${cfg.minLevel.wireName}，本次通知未达到该级别，未发送",
                SkipReason.NOT_CONFIGURED to "本机短信配置未填完，未发送"
            )
        )


    /** GET 与 PUT 的响应共用一份形状 —— 两处各拼一次必然会分叉。 */
    private fun configPayload(c: LocalSmsConfig): Map<String, Any?> = mapOf(
        "enabled" to c.enabled,
        "target_number" to c.targetNumber,
        "min_level" to c.minLevel.wireName,
        "daily_limit" to c.dailyLimit,
        "scenes" to c.scenes.toList().sorted(),
        "respect_dnd" to c.respectDnd,
        // 客户端不必自己复现"能不能投"的判据（开关 + 号码合法）——这里读的就是渠道那一份。
        "configured" to channel.isConfigured(),
        // 花钱的渠道必须把用量摆在配置里：UI 上那行"今日 2/5"读的就是这两个字段。
        "sent_today" to store.sentToday(),
        "quota_remaining" to store.quotaRemaining(c.dailyLimit),
        // 级别可选值由 core 给（app 手抄三个字符串就多一份会分叉的镜像）。
        // 回对象数组（`[{"name":..,"label":..}]`），三条渠道同一份形状，见 levelOptions。
        "levels" to levelOptions(),

        "daily_limit_min" to LocalSmsConfig.MIN_DAILY_LIMIT,
        "daily_limit_max" to LocalSmsConfig.MAX_DAILY_LIMIT
    )

    /** 字段级合并：只有 patch 里显式出现且非 null 的键会覆盖。 */
    private fun merge(current: LocalSmsConfig, patch: JsonObject): LocalSmsConfig {
        fun key(name: String): JsonElement? = patch[name]?.takeIf { it !is JsonNull }
        return current.copy(
            enabled = key("enabled")?.jsonPrimitive?.boolean ?: current.enabled,
            targetNumber = key("target_number")?.jsonPrimitive?.content?.trim() ?: current.targetNumber,
            // 严格解析：认不出的级别名**抛出**（→ 400），不走 NotifyLevel.fromWire 的
            // "回落 INFO"。在这条渠道上回落 INFO 等于把最省钱的档位悄悄换成最能烧钱的那档。
            minLevel = key("min_level")?.jsonPrimitive?.content?.let(::levelOrThrow) ?: current.minLevel,
            dailyLimit = key("daily_limit")?.jsonPrimitive?.int ?: current.dailyLimit,
            scenes = key("scenes")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                ?: current.scenes,
            respectDnd = key("respect_dnd")?.jsonPrimitive?.boolean ?: current.respectDnd
        )
    }

    private fun levelOrThrow(name: String): NotifyLevel =
        NotifyLevel.entries.firstOrNull { it.wireName == name.lowercase() }
            ?: throw IllegalArgumentException(
                "min_level 只支持 ${LocalSmsConfig.LEVEL_NAMES.joinToString("/")}，收到 $name"
            )

    private companion object {
        /**
         * 测试短信的正文。写清楚"这是测试" —— 用户按下按钮之后收到的是一条**真短信**，
         * 别让他以为设备真出事了。
         */
        const val TEST_BODY = "短信通道测试，收到本条短信说明设备可在断网时通过短信发送通知"
    }
}
