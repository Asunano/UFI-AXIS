package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ConfigLimits
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.lib_api.BuildConfig
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 配置管理路由
 *
 * 提供后端配置项的读取和修改接口，供前端 UI 对接。
 * 修改认证或端口配置后需重启服务才能生效。
 *
 * GET  /api/config          - 获取全部配置
 * PUT  /api/config          - 更新配置
 * POST /api/config/reset    - 恢复默认配置
 */
class ConfigRoutes(
    private val settings: AppSettings
) {
    fun register(route: Route) {
        route.route("/config") {

            // 版本信息（P0-1：与 UpdateManager.currentVersionName() 读同一来源 BuildConfig.VERSION_NAME，
            // 保证 /version 与更新比对基准一致，根治版本恒 0.1）
            get("/version") {
                call.respond(toJsonElement(mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "min_client_version" to "1.0",
                    "update_url" to settings.updateUrl
                )))
            }

            // 获取全部配置（脱敏显示 goform_password）
            get {
                val masked = settings.toMap().toMutableMap()
                masked["goform_password"] = maskSecret(settings.goformPassword)
                call.respond(toJsonElement(masked))
            }

            // 更新配置
            // C03：越界/空串/脱敏回写不再"静默丢弃 + success:true"，一律进 rejected_fields 并带原因与允许区间。
            // 兼容性：updated_fields / needs_restart / hint 语义不变，旧客户端不改代码也能跑。
            put {
                val body = call.receiveJsonObject()
                val updated = mutableListOf<String>()
                val rejected = mutableListOf<Map<String, Any?>>()

                fun reject(field: String, reason: String, range: IntRange? = null) {
                    rejected.add(buildMap {
                        put("field", field)
                        put("reason", reason)
                        if (range != null) {
                            put("min", range.first)
                            put("max", range.last)
                        }
                    })
                }

                /** 整型字段：区间外报 OUT_OF_RANGE，键存在但不是整数报 WRONG_TYPE。 */
                fun intField(field: String, range: IntRange, apply: (Int) -> Unit) {
                    val raw = body[field] ?: return
                    if (raw is JsonNull) return
                    // 用 as? 而不是 .jsonPrimitive：对象/数组值会让后者抛异常 → StatusPages 500
                    val v = (raw as? JsonPrimitive)?.intOrNull
                    if (v == null) {
                        reject(field, ErrorCode.WRONG_TYPE, range)
                        return
                    }
                    if (v in range) {
                        apply(v)
                        updated.add(field)
                    } else {
                        reject(field, ErrorCode.OUT_OF_RANGE, range)
                    }
                }

                /** 密钥类字符串：空串与脱敏值都拒绝（脱敏回写会覆盖真实密钥）。 */
                fun secretField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    when {
                        v.isBlank() -> reject(field, ErrorCode.BLANK_VALUE)
                        isMaskedValue(v) -> reject(field, ErrorCode.MASKED_VALUE)
                        else -> {
                            apply(v)
                            updated.add(field)
                        }
                    }
                }

                /** 非空字符串字段。 */
                fun textField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    if (v.isBlank()) {
                        reject(field, ErrorCode.BLANK_VALUE)
                    } else {
                        apply(v)
                        updated.add(field)
                    }
                }

                fun boolField(field: String, apply: (Boolean) -> Unit) {
                    val raw = body[field] ?: return
                    if (raw is JsonNull) return
                    val v = (raw as? JsonPrimitive)?.booleanOrNull
                    if (v == null) {
                        reject(field, ErrorCode.WRONG_TYPE)
                        return
                    }
                    apply(v)
                    updated.add(field)
                }


                secretField("goform_password") { settings.goformPassword = it }

                intField("port", ConfigLimits.PORT) { settings.port = it }
                intField("goform_port", ConfigLimits.GOFORM_PORT) { settings.goformPort = it }

                textField("goform_ip") { settings.goformIp = it }
                textField("update_url") { settings.updateUrl = it }

                boolField("auto_start_on_boot") { settings.autoStartOnBoot = it }
                // 日志三层开关：总闸 → 两侧子开关 → 详细级别
                boolField("log_enabled") {
                    settings.logEnabled = it
                    com.ufi_axis_core.util.AppLogger.setLogEnabled(it)
                }
                boolField("core_log_enabled") {
                    settings.coreLogEnabled = it
                    com.ufi_axis_core.util.AppLogger.setCoreLogEnabled(it)
                }
                // app 侧开关 core 只负责保管真源（app 靠 GET /api/config 回读生效）
                boolField("app_log_enabled") { settings.appLogEnabled = it }
                boolField("debug_mode") {
                    settings.debugMode = it
                    com.ufi_axis_core.util.AppLogger.setDebugMode(it)
                }


                // 设备原始 dump 端点开关（计划书 9.3，默认关；关闭时 /api/device/goform 回 403）
                boolField("goform_dump_enabled") { settings.goformDumpEnabled = it }
                // 裸 goform 命令通道开关（默认关；关闭时 /api/device/goform/query|set 回 403）
                // set 会绕过 profile 的 WriteSpec 值域校验，返回值也不脱敏，只在排障时临时打开
                boolField("goform_command_enabled") { settings.goformCommandEnabled = it }

                // QoS 参数
                boolField("qos_enabled") { settings.qosEnabled = it }
                intField("qos_shell_max_concurrent", ConfigLimits.QOS_SHELL_MAX_CONCURRENT) {
                    settings.qosShellMaxConcurrent = it
                    ShellQoS.updateRootPermits(it)
                }
                intField("qos_cache_ttl_ms", ConfigLimits.QOS_CACHE_TTL_MS) {
                    settings.qosCacheTtlMs = it
                    ShellQoS.updateCacheTtl(it.toLong())
                    GoformQoS.updateCacheTtl(it.toLong())
                }
                intField("qos_goform_query_max", ConfigLimits.QOS_GOFORM_QUERY_MAX) {
                    settings.qosGoformQueryMax = it
                    GoformQoS.adaptiveAdjust(it, settings.qosGoformSetMax)
                }
                intField("qos_goform_set_max", ConfigLimits.QOS_GOFORM_SET_MAX) {
                    settings.qosGoformSetMax = it
                    GoformQoS.adaptiveAdjust(settings.qosGoformQueryMax, it)
                }

                // SMS 验证码解析
                boolField("sms_code_enabled") { settings.smsCodeEnabled = it }
                intField("sms_code_cleanup_hours", ConfigLimits.SMS_CODE_CLEANUP_HOURS) {
                    settings.smsCodeCleanupHours = it
                }

                // update_mirror_base 允许设为空串 = 直连（因此不能用 textField）；
                // contentOrNull 对 JsonNull/数字/布尔返回 null，只有显式传字符串才会进入此分支。
                body["update_mirror_base"]?.jsonPrimitive?.contentOrNull?.let {
                    settings.updateMirrorBase = it
                    updated.add("update_mirror_base")
                }

                val needsRestart = updated.any { it in listOf("port", "goform_ip", "goform_port", "goform_password") }

                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "updated_fields" to updated,
                    "rejected_fields" to rejected,
                    "needs_restart" to needsRestart,
                    "hint" to if (needsRestart) "修改了认证或端口配置，需重启服务生效" else ""
                )))
            }


            // 恢复默认
            post("/reset") {
                settings.resetAll()
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "message" to "已恢复默认配置，需重启服务生效"
                )))
            }
        }
    }

    private fun maskSecret(secret: String): String {
        return if (secret.length <= 4) "****"
        else secret.take(2) + "*".repeat(secret.length - 4) + secret.takeLast(2)
    }

    /**
     * 检测值是否为脱敏占位符（含 3+ 连续星号），
     * 防止前端将 GET 返回的脱敏值回写覆盖真实密钥
     */
    private fun isMaskedValue(value: String): Boolean =
        value.contains("***")
}
