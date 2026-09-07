package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.sms.SmsForwardController
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
 */
class SmsForwardRoutes(private val smsForwardController: SmsForwardController) {
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
                    "forward_dev_info" to cfg.forwardDevInfo,
                    "blacklist" to cfg.blacklist,
                    "scenes" to cfg.scenes.toList()
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
            // （enabled→false、smtp_port→465 …），只有 smtp_pass / blacklist 保留原值。
            // 于是任何客户端「只提交自己关心的字段」就会把另一端设好的配置清零甚至直接关停转发
            // （app 设为 A、web 一存变 B 的典型来源）。现在与 DownloadRoutes 的 `body[...] ?: c.xxx` 写法对齐。
            post("/config") {
                val p = call.receiveJsonObject()
                val c = smsForwardController.loadConfig()

                // 黑名单 / 场景集合：未传保留原值
                val blacklist = p["blacklist"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: c.blacklist
                val scenes = p["scenes"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }?.toSet() ?: c.scenes

                // 凭据类：未传或传空串都保留已存储值（GET 只回 *_set 布尔，无法 round-trip）
                val smtpPass = p["smtp_pass"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: c.smtpPass

                val cfg = SmsForwardController.SmsForwardConfig(
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: c.enabled,
                    smtpHost = p["smtp_host"]?.jsonPrimitive?.contentOrNull ?: c.smtpHost,
                    smtpPort = p["smtp_port"]?.jsonPrimitive?.intOrNull ?: c.smtpPort,
                    smtpUser = p["smtp_user"]?.jsonPrimitive?.contentOrNull ?: c.smtpUser,
                    smtpPass = smtpPass,
                    smtpFrom = p["smtp_from"]?.jsonPrimitive?.contentOrNull ?: c.smtpFrom,
                    smtpTo = p["smtp_to"]?.jsonPrimitive?.contentOrNull ?: c.smtpTo,
                    forwardDevInfo = p["forward_dev_info"]?.jsonPrimitive?.booleanOrNull ?: c.forwardDevInfo,
                    blacklist = blacklist,
                    scenes = scenes
                )
                smsForwardController.saveConfig(cfg)
                // 验证写入：立即回读确认
                val verify = smsForwardController.loadConfig()
                com.ufi_axis_core.util.AppLogger.i("SmsForwardRoutes",
                    "Config saved & verified: enabled=${verify.enabled}, smtpHost=${verify.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}, smtpPass_set=${verify.smtpPass.isNotEmpty()}, scenes=${verify.scenes}")
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            post("/test") {
                val cfg = smsForwardController.loadConfig()
                val result = mutableMapOf<String, Any>("success" to false)
                when {
                    !cfg.enabled -> result["error"] = "邮件通知未启用"
                    !smsForwardController.isSendable(cfg) -> result["error"] = "SMTP 配置不完整（服务器/用户名/密码/收件地址）"
                    else -> {
                        try {
                            val ok = smsForwardController.forwardSms("test", "Test message from UFI-AXIS-Core", System.currentTimeMillis())
                            result["success"] = ok
                            if (!ok) result["error"] = "发送失败，请检查日志"
                        } catch (e: Exception) {
                            result["error"] = "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, toJsonElement(result))
            }

            // app 通知转邮件：app 弹出系统通知后回传一份，core 按 scenes 白名单决定是否发信。
            // 场景开关只存在 core（唯一真源），app 不做判断，避免两端各存一份开关后不一致。
            post("/notify") {
                val p = call.receiveJsonObject()
                val scene = p["scene"]?.jsonPrimitive?.contentOrNull ?: ""
                val title = p["title"]?.jsonPrimitive?.contentOrNull ?: ""
                if (scene.isBlank() || title.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "scene and title required")
                    return@post
                }
                val body = p["body"]?.jsonPrimitive?.contentOrNull ?: ""
                val sent = smsForwardController.sendSceneNotification(scene, title, body)
                // 未勾选场景 / 未启用都返回 200 + sent=false：这是正常的"不发"，不是错误
                call.respond(toJsonElement(mapOf("success" to true, "sent" to sent)))
            }
        }
    }
}
