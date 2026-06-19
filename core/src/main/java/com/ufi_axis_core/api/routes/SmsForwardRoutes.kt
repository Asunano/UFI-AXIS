package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.controller.sms.SmsForwardController
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class SmsForwardRoutes(private val smsForwardController: SmsForwardController) {
    fun register(route: Route) {
        route.route("/sms-forward") {
            get("/config") {
                val cfg = smsForwardController.loadConfig()
                call.respond(toJsonElement(mapOf(
                    "enabled" to cfg.enabled,
                    "method" to cfg.method,
                    "smtp_host" to cfg.smtpHost,
                    "smtp_port" to cfg.smtpPort,
                    "smtp_user" to cfg.smtpUser,
                    "smtp_pass_set" to cfg.smtpPass.isNotEmpty(),
                    "smtp_from" to cfg.smtpFrom,
                    "smtp_to" to cfg.smtpTo,
                    "curl_url" to cfg.curlUrl,
                    "curl_template" to cfg.curlTemplate,
                    "dingtalk_token" to cfg.dingtalkToken,
                    "dingtalk_secret_set" to cfg.dingtalkSecret.isNotEmpty(),
                    "forward_dev_info" to cfg.forwardDevInfo,
                    "blacklist" to cfg.blacklist
                )))
            }

            // 诊断端点：检查配置和短信读取能力
            get("/diagnose") {
                val cfg = smsForwardController.loadConfig()
                val diag = mutableMapOf<String, Any>(
                    "config_enabled" to cfg.enabled,
                    "config_method" to cfg.method,
                    "smtp_host_set" to cfg.smtpHost.isNotBlank(),
                    "smtp_port" to cfg.smtpPort,
                    "smtp_user_set" to cfg.smtpUser.isNotBlank(),
                    "smtp_pass_set" to cfg.smtpPass.isNotEmpty(),
                    "smtp_to_set" to cfg.smtpTo.isNotBlank()
                )
                call.respond(HttpStatusCode.OK, toJsonElement(diag))
            }

            post("/config") {
                val p = call.receive<JsonObject>()
                val existingCfg = smsForwardController.loadConfig()

                // 解析黑名单 JSON 数组
                val blacklist = p["blacklist"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: existingCfg.blacklist  // 前端未传时保留已有黑名单

                // 若前端未传 smtp_pass（空字符串或缺失），保留已存储的密码
                val smtpPass = p["smtp_pass"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: existingCfg.smtpPass

                // 若前端未传 dingtalk_secret，保留已存储的密钥
                val dingtalkSecret = p["dingtalk_secret"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?: existingCfg.dingtalkSecret

                val cfg = SmsForwardController.SmsForwardConfig(
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    method = p["method"]?.jsonPrimitive?.contentOrNull ?: "disabled",
                    smtpHost = p["smtp_host"]?.jsonPrimitive?.contentOrNull ?: "",
                    smtpPort = p["smtp_port"]?.jsonPrimitive?.intOrNull ?: 465,
                    smtpUser = p["smtp_user"]?.jsonPrimitive?.contentOrNull ?: "",
                    smtpPass = smtpPass,
                    smtpFrom = p["smtp_from"]?.jsonPrimitive?.contentOrNull ?: "",
                    smtpTo = p["smtp_to"]?.jsonPrimitive?.contentOrNull ?: "",
                    curlUrl = p["curl_url"]?.jsonPrimitive?.contentOrNull ?: "",
                    curlTemplate = p["curl_template"]?.jsonPrimitive?.contentOrNull
                        ?: "from={{sms-from}}&content={{sms-body}}&time={{sms-time}}",
                    dingtalkToken = p["dingtalk_token"]?.jsonPrimitive?.contentOrNull ?: "",
                    dingtalkSecret = dingtalkSecret,
                    forwardDevInfo = p["forward_dev_info"]?.jsonPrimitive?.booleanOrNull ?: false,
                    blacklist = blacklist
                )
                smsForwardController.saveConfig(cfg)
                // 验证写入：立即回读确认
                val verify = smsForwardController.loadConfig()
                com.ufi_axis_core.util.AppLogger.i("SmsForwardRoutes",
                    "Config saved & verified: enabled=${verify.enabled}, method=${verify.method}, smtpHost=${verify.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}, smtpPass_set=${verify.smtpPass.isNotEmpty()}")
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            post("/test") {
                val cfg = smsForwardController.loadConfig()
                val result = mutableMapOf<String, Any>("success" to false)
                when {
                    !cfg.enabled -> result["error"] = "转发未启用"
                    cfg.method == "disabled" -> result["error"] = "未选择转发方式"
                    else -> {
                        try {
                            val ok = smsForwardController.forwardSms("test", "Test message from UFI-AXIS-Core", System.currentTimeMillis())
                            result["success"] = ok
                            if (!ok) result["error"] = "发送失败，请检查 logcat 日志"
                        } catch (e: Exception) {
                            result["error"] = "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, toJsonElement(result))
            }
        }
    }
}
