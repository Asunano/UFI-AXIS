package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
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

            // 版本信息
            get("/version") {
                val packageInfo = try {
                    call.application.environment?.config?.propertyOrNull("ktor.application.version")?.getString()
                } catch (_: Exception) { null }

                call.respond(toJsonElement(mapOf(
                    "version" to (packageInfo ?: "0.1"),
                    "min_client_version" to "1.0",
                    "update_url" to "https://github.com/Asunano/UFI-AXIS/releases"
                )))
            }

            // 获取全部配置（脱敏显示 secret、goform_password 和 token）
            get {
                val masked = settings.toMap().toMutableMap()
                masked["secret"] = maskSecret(settings.secret)
                masked["goform_password"] = maskSecret(settings.goformPassword)
                masked["token"] = maskSecret(settings.token)
                call.respond(toJsonElement(masked))
            }

            // 更新配置
            put {
                val body = call.receive<JsonObject>()
                val updated = mutableListOf<String>()

                body["token"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank() && !isMaskedValue(it)) {
                        settings.token = it
                        updated.add("token")
                    }
                }

                body["secret"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank() && !isMaskedValue(it)) {
                        settings.secret = it
                        updated.add("secret")
                    }
                }

                body["port"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 1024..65535) {
                        settings.port = it
                        updated.add("port")
                    }
                }

                body["auto_start_on_boot"]?.jsonPrimitive?.booleanOrNull?.let {
                    settings.autoStartOnBoot = it
                    updated.add("auto_start_on_boot")
                }

                body["goform_ip"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank()) {
                        settings.goformIp = it
                        updated.add("goform_ip")
                    }
                }

                body["goform_port"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 1..65535) {
                        settings.goformPort = it
                        updated.add("goform_port")
                    }
                }

                body["goform_password"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank() && !isMaskedValue(it)) {
                        settings.goformPassword = it
                        updated.add("goform_password")
                    }
                }

                body["debug_mode"]?.jsonPrimitive?.booleanOrNull?.let {
                    settings.debugMode = it
                    com.ufi_axis_core.util.AppLogger.setDebugMode(it)
                    updated.add("debug_mode")
                }

                // QoS 参数
                body["qos_enabled"]?.jsonPrimitive?.booleanOrNull?.let {
                    settings.qosEnabled = it
                    updated.add("qos_enabled")
                }

                body["qos_shell_max_concurrent"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 1..10) {
                        settings.qosShellMaxConcurrent = it
                        ShellQoS.updateRootPermits(it)
                        updated.add("qos_shell_max_concurrent")
                    }
                }

                body["qos_cache_ttl_ms"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 500..30000) {
                        settings.qosCacheTtlMs = it
                        ShellQoS.updateCacheTtl(it.toLong())
                        GoformQoS.updateCacheTtl(it.toLong())
                        updated.add("qos_cache_ttl_ms")
                    }
                }

                body["qos_goform_query_max"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 1..8) {
                        settings.qosGoformQueryMax = it
                        GoformQoS.adaptiveAdjust(it, settings.qosGoformSetMax)
                        updated.add("qos_goform_query_max")
                    }
                }

                body["qos_goform_set_max"]?.jsonPrimitive?.intOrNull?.let {
                    if (it in 1..4) {
                        settings.qosGoformSetMax = it
                        GoformQoS.adaptiveAdjust(settings.qosGoformQueryMax, it)
                        updated.add("qos_goform_set_max")
                    }
                }

                val needsRestart = updated.any { it in listOf("token", "secret", "port", "goform_ip", "goform_port", "goform_password") }

                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "updated_fields" to updated,
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
