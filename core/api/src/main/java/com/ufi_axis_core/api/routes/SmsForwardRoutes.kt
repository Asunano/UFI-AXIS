package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.sms.MailChannel
import com.ufi_axis_core.controller.sms.SmsForwardController
import com.ufi_axis_core.core.database.MailSendRecord
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.SkipReason

import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 邮件通知路由（路径仍为 `/api/sms-forward`）。
 *
 * 2026-08-29：curl / 钉钉两条支路连同 `method` 字段一起删除，配置只剩 SMTP；
 * 新增 `POST /notify` 供 app 把自己的通知交给 core 发邮件（SMTP 能力只在 core 侧）。
 * 路径没跟着改名：web 端与 API 手册都按 `/api/sms-forward/…` 引用。
 *
 * 2026-09-08 阶段 1：`/notify` 与 `/test` 改为构造 [NotifyEvent] 走 [notifier]（分发器），
 * 不再直接调控制器 —— 闸门、重试、投递记录都在分发器那一处。
 */
class SmsForwardRoutes(
    private val smsForwardController: SmsForwardController,
    /** 通知分发器入口（装配层接到 `NotificationDispatcher::emit`）。 */
    private val notifier: Notifier
) {
    fun register(route: Route) {
        route.route("/sms-forward") {
            get("/config") {
                val cfg = smsForwardController.loadConfig()
                call.respond(toJsonElement(mapOf(
                    "enabled" to cfg.enabled,
                    "smtp_host" to cfg.smtpHost,
                    "smtp_port" to cfg.smtpPort,
                    "smtp_user" to cfg.smtpUser,
                    "smtp_pass_set" to cfg.smtpPass.isNotEmpty(),
                    "smtp_from" to cfg.smtpFrom,
                    "smtp_to" to cfg.smtpTo,
                    // "能不能发出去"的判据只有控制器那一份（enabled + SMTP 四项齐全）。
                    // 这里必须回：客户端此前只能靠 smtp_host / smtp_to 两项自己另算一套，
                    // 于是"填了服务器和收件人、漏了用户名或密码"时界面显示"已启用"而一封发不出去。
                    // 口径与 `/api/notify/{sms,webhook}/config` 的 `configured` 对齐
                    // （字段名沿用 `/diagnose` 已在用的 `sendable`，不新造第三个名字）。
                    "sendable" to smsForwardController.isSendable(cfg),

                    "forward_dev_info" to cfg.forwardDevInfo,
                    "scenes" to cfg.scenes.toList(),
                    // 规则同构的两个旋钮（2026-09-10）：三条渠道的字段名与语义完全一致。
                    "min_level" to cfg.minLevel.wireName,
                    "daily_limit" to cfg.dailyLimit,
                    // 用量摆在配置里，口径与另两条渠道一致。
                    "sent_today" to smsForwardController.quotaSentToday(),
                    // **不限时回 null** 而不是 0：0 的含义是"已经用尽"，与"不限"恰好相反。
                    "quota_remaining" to mailQuotaRemaining(cfg),
                    // 取值域由 core 给（客户端手抄就多一份会分叉的镜像）。
                    // 回对象数组（`[{"name":..,"label":..}]`），三条渠道同一份形状，见 levelOptions。
                    "levels" to levelOptions(),

                    "daily_limit_min" to SmsForwardController.SmsForwardConfig.MIN_DAILY_LIMIT,
                    "daily_limit_max" to SmsForwardController.SmsForwardConfig.MAX_DAILY_LIMIT
                )))
            }

            // 诊断端点：配置完整性 + 发信计数（app 端统计卡的数据源）
            get("/diagnose") {
                val cfg = smsForwardController.loadConfig()
                val stats = smsForwardController.loadStats()
                val diag = mutableMapOf<String, Any>(
                    "config_enabled" to cfg.enabled,
                    "smtp_host_set" to cfg.smtpHost.isNotBlank(),
                    "smtp_port" to cfg.smtpPort,
                    "smtp_user_set" to cfg.smtpUser.isNotBlank(),
                    "smtp_pass_set" to cfg.smtpPass.isNotEmpty(),
                    "smtp_to_set" to cfg.smtpTo.isNotBlank(),
                    "sendable" to smsForwardController.isSendable(cfg),
                    "scene_count" to cfg.scenes.size,
                    // total 由 success+failed 派生，不单独存，避免三个计数器互相打架
                    "sent_total" to stats.total,
                    "sent_success" to stats.success,
                    "sent_failed" to stats.failed,
                    "last_sent_at" to stats.lastSentAt,
                    "last_error" to stats.lastError
                )
                call.respond(HttpStatusCode.OK, toJsonElement(diag))
            }

            // 字段级合并语义：**只覆盖请求体里出现的字段**，缺失字段一律保留现值。
            //
            // 2026-08-26 修：原实现是全量覆盖 —— 缺失字段落到硬编码默认值
            // （enabled→false、smtp_port→465 …），只有 smtp_pass 保留原值。
            // 于是任何客户端「只提交自己关心的字段」就会把另一端设好的配置清零甚至直接关停转发
            // （app 设为 A、web 一存变 B 的典型来源）。现在与 DownloadRoutes 的 `body[...] ?: c.xxx` 写法对齐。
            post("/config") {
                val p = call.receiveJsonObject()
                val c = smsForwardController.loadConfig()

                // 场景集合：未传保留原值
                val scenes = p["scenes"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }?.toSet() ?: c.scenes

                // 凭据类：未传或传空串都保留已存储值（GET 只回 *_set 布尔，无法 round-trip）
                val smtpPass = p["smtp_pass"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: c.smtpPass

                // 级别名严格解析：认不出就 400（不静默回落）—— 详见下面的注释。
                val rawLevel = p["min_level"]?.jsonPrimitive?.contentOrNull
                val minLevel = if (rawLevel == null) {
                    c.minLevel
                } else {
                    NotifyLevel.entries.firstOrNull { it.wireName == rawLevel.lowercase() } ?: run {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            "min_level 只支持 " +
                                SmsForwardController.SmsForwardConfig.LEVEL_NAMES.joinToString("/") +
                                "，收到 $rawLevel"
                        )
                        return@post
                    }
                }


                val cfg = SmsForwardController.SmsForwardConfig(
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: c.enabled,
                    smtpHost = p["smtp_host"]?.jsonPrimitive?.contentOrNull ?: c.smtpHost,
                    smtpPort = p["smtp_port"]?.jsonPrimitive?.intOrNull ?: c.smtpPort,
                    smtpUser = p["smtp_user"]?.jsonPrimitive?.contentOrNull ?: c.smtpUser,
                    smtpPass = smtpPass,
                    smtpFrom = p["smtp_from"]?.jsonPrimitive?.contentOrNull ?: c.smtpFrom,
                    smtpTo = p["smtp_to"]?.jsonPrimitive?.contentOrNull ?: c.smtpTo,
                    forwardDevInfo = p["forward_dev_info"]?.jsonPrimitive?.booleanOrNull ?: c.forwardDevInfo,
                    scenes = scenes,
                    // 严格解析：认不出的级别名 → 400，不走 NotifyLevel.fromWire 的"回落 INFO"。
                    // 静默回落会让用户以为自己设的门槛生效了（口径同另两条渠道）。
                    minLevel = minLevel,
                    dailyLimit = p["daily_limit"]?.jsonPrimitive?.intOrNull ?: c.dailyLimit
                )
                // 取值域校验：一个越界的上限存进去之后，用户只会看到"邮件莫名其妙不发了"。
                SmsForwardController.SmsForwardConfig.validate(cfg)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@post
                }
                smsForwardController.saveConfig(cfg)
                // 验证写入：立即回读确认
                val verify = smsForwardController.loadConfig()
                com.ufi_axis_core.util.AppLogger.i("SmsForwardRoutes",
                    "Config saved & verified: enabled=${verify.enabled}, smtpHost=${verify.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}, smtpPass_set=${verify.smtpPass.isNotEmpty()}, scenes=${verify.scenes}")
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // 手动测试：**不受总开关、免打扰与场景勾选约束**（manual = true）。
            // 用户刚按下「发送测试」，静默什么都不发比发出去更难排查 —— 但也不能无声跳过闸门，
            // 所以响应里回一个 auto_notify_enabled，让人知道"测试发出去了，自动通知却是关的"。
            //
            // 只投邮件渠道：这个按钮在**邮件通知页**上，测的是 SMTP 通不通。
            // 不限定的话手机上还会多弹一条"测试消息"的系统通知，那不是用户按下按钮时的预期。
            //
            // 场景用 NotifyScenes.TEST 而不是 SMS：投递记录的 scene 列要能把"用户点了几次测试"
            // 与"真收到几条短信"分开，混在一格里排查时无从区分。TEST 不在用户可勾选的场景集里，
            // 靠 manual = true 跳过 accepts 判定。
            post("/test") {

                val cfg = smsForwardController.loadConfig()
                val result = mutableMapOf<String, Any>(
                    "success" to false,
                    "auto_notify_enabled" to smsForwardController.isMailGateOpen()
                )
                when {
                    !cfg.enabled -> result["error"] = "邮件通知未启用"
                    !smsForwardController.isSendable(cfg) -> result["error"] = "SMTP 配置不完整（服务器/用户名/密码/收件地址）"
                    else -> {
                        try {
                            val outcome = notifier(
                                NotifyEvent(
                                    scene = NotifyScenes.TEST,

                                    level = NotifyLevel.INFO,
                                    title = "邮件通知测试",
                                    body = TEST_MAIL_BODY,
                                    meta = listOf("触发方式" to "手动测试"),
                                    manual = true,
                                    channels = setOf(MailChannel.ID)
                                )
                            )[MailChannel.ID]
                            result["success"] = outcome is DeliveryOutcome.Sent
                            // 取**这条渠道**的结果而不是 anySent() 那个布尔：布尔分不出
                            // "SMTP 报错了"与"这次根本没投"，于是 `Skipped(QUOTA_EXCEEDED)`
                            // （测试不放行配额，见 NotificationDispatcher.deliverTo）会回一句
                            // "可在投递记录中查看失败原因" —— 把用户导向一个不存在的 SMTP 故障，
                            // 而真正的原因是"今天的额度用完了"。
                            when (outcome) {
                                is DeliveryOutcome.Failed ->
                                    result["error"] = "邮件发送失败，可在投递记录中查看失败原因"
                                is DeliveryOutcome.Skipped -> result["error"] = skipMessage(outcome, cfg)
                                null -> result["error"] = "设备端未提供邮件渠道"
                                else -> {}
                            }
                        } catch (e: Exception) {
                            result["error"] = "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }

                }
                call.respond(HttpStatusCode.OK, toJsonElement(result))
            }





            // ═════════════ 通知投递历史（2026-09-08） ═════════════
            //
            // `/diagnose` 的三个计数器只回答"成功几封、失败几封、最后一条错误是什么"，
            // 回答不了"哪一封没发出去、什么时候、为什么" —— 失败原因还会被下一次失败覆盖。
            //
            // keyset 游标分页，参数名与 `/api/sms/blocked`、`/api/alerts/list` 一致。
            // DB v12 起结果是三态：`sent` / `failed` / **`skipped`**。加 skipped 是因为
            // 「我开着通知，这条为什么没收到」的答案（免打扰 / 场景未勾 / 级别不够 / 配额用尽）
            // 此前只落在 INFO 日志里，而 release 构建不留 INFO。
            // 与 `/diagnose` 的口径差别也在这儿：那三个计数器仍只统计发起过投递的两态。
            get("/history") {
                val limit = (call.request.queryParameters["limit"] ?: "50").toIntOrNull()?.coerceIn(1, 200) ?: 50
                // 游标必须成对：只给 cursor_ts 会让 SQL 里的 `id < :cId` 变成 `id < NULL`（恒为 NULL），
                // 同一毫秒的边界行被整段跳过。缺一个就当首页。
                val rawTs = call.request.queryParameters["cursor_ts"]?.toLongOrNull()
                val rawId = call.request.queryParameters["cursor_id"]?.toLongOrNull()
                val paired = rawTs != null && rawId != null
                val cursorTs = if (paired) rawTs else null
                val cursorId = if (paired) rawId else null
                // 缺省不过滤；只认三个值，别的按不过滤处理。
                // 对外参数名沿用 `result=success`（客户端已在用），映射到库里的 `outcome` 列。
                val outcome = when (call.request.queryParameters["result"]) {
                    "success" -> MailSendRecord.OUTCOME_SENT
                    "failed" -> MailSendRecord.OUTCOME_FAILED
                    "skipped" -> MailSendRecord.OUTCOME_SKIPPED
                    else -> null
                }
                // 渠道过滤（2026-09-08，DB v11）：不传 = 全部。空串当没传 ——
                // 前端把"全部"渲染成空值再原样提交是常见写法，让它等价于不过滤比返回 0 条友好。
                val channel = call.request.queryParameters["channel"]?.takeIf { it.isNotBlank() }
                val records = smsForwardController.listMailHistory(cursorTs, cursorId, outcome, channel, limit)
                val (total, failed, skipped) = smsForwardController.countMailHistory(channel)
                val last = records.lastOrNull()
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "total" to total,
                    "failed_total" to failed,
                    // 与 failed_total 分开：跳过不是失败，加在一起会让"失败 12 条"里
                    // 其实有 11 条是"免打扰时段没发"。
                    "skipped_total" to skipped,
                    "next_cursor_ts" to last?.sent_at,
                    "next_cursor_id" to last?.id,
                    "has_more" to (records.size >= limit)
                )))
            }

            delete("/history") {
                // 与 GET 同一个 channel 口径：不传 = 全清。带渠道是必须的 ——
                // 清空按钮在各渠道自己的记录页上，不分渠道会把别人的失败记录一起删掉。
                val channel = call.request.queryParameters["channel"]?.takeIf { it.isNotBlank() }
                smsForwardController.clearMailHistory(channel)
                call.respond(toJsonElement(mapOf("success" to true)))
            }
        }
    }

    /** 今天还剩几封；`daily_limit = 0`（不限）时回 null —— 那一档没有"剩余"这个数。 */
    private fun mailQuotaRemaining(c: SmsForwardController.SmsForwardConfig): Int? =
        if (c.dailyLimit == ChannelRules.UNLIMITED) {
            null
        } else {
            (c.dailyLimit - smsForwardController.quotaSentToday()).coerceAtLeast(0)
        }

    /**
     * `Skipped` 的人话解释（口径与另两条渠道共用 [skipMessageOf]）。
     *
     * 测试请求是 `manual`，所以实际只可能撞上**配额**那一道 —— 而那一档必须带上数字，
     * 否则用户会以为是 SMTP 坏了并反复点测试。其余几档由 `SkipReason.label` 统一给话。
     */
    private fun skipMessage(
        outcome: DeliveryOutcome.Skipped,
        cfg: SmsForwardController.SmsForwardConfig
    ): String = skipMessageOf(
        outcome.reason,
        mapOf(
            SkipReason.QUOTA_EXCEEDED to
                "今日发信配额已用尽（${smsForwardController.quotaSentToday()}/${cfg.dailyLimit}），" +
                "明天自动重置",
            SkipReason.NOT_CONFIGURED to "SMTP 配置不完整（服务器/用户名/密码/收件地址），未发送"
        )
    )


    private companion object {
        /**
         * 手动测试信的正文。
         *
         * 内容与 2026-09-08 之前那条 `forwardSms("test", …)` 逐字相同 —— 用户按下测试
         * 收到的邮件不该因为内部改造而变样，而且主题就是这段正文的摘要。
         */
        const val TEST_MAIL_BODY = "Test message from UFI-AXIS-Core"
    }
}

