package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.controller.system.AppManager
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class AppRoutes(
    private val appManager: AppManager
) {
    fun register(route: Route) {
        route.route("/apps") {
            // 应用列表
            get {
                val filter = call.request.queryParameters["filter"] ?: "all"
                val apps = appManager.listApps(filter)
                call.respond(toJsonElement(mapOf(
                    "apps" to apps,
                    "count" to apps.size,
                    "root" to appManager.hasRoot()
                )))
            }

            // 应用详情
            get("/{packageName}") {
                val pkg = call.parameters["packageName"] ?: ""
                val info = appManager.getAppInfo(pkg)
                if (info != null) call.respond(toJsonElement(info))
                else call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf("error" to "App not found")))
            }

            // 安装 APK（从设备路径）
            post("/install") {
                val body = call.receive<JsonObject>()
                val path = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (path.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "path is required")))
                    return@post
                }
                val result = appManager.installApk(path)
                call.respond(
                    if (result.success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to result.success, "message" to result.message))
                )
            }

            // 安装 APK（从 URL 下载）
            post("/install-url") {
                val body = call.receive<JsonObject>()
                val url = body["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "url is required")))
                    return@post
                }
                val result = appManager.installApkFromUrl(url)
                call.respond(
                    if (result.success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to result.success, "message" to result.message))
                )
            }

            // 卸载应用
            post("/uninstall") {
                val body = call.receive<JsonObject>()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "packageName is required")))
                    return@post
                }
                val result = appManager.uninstallApp(pkg)
                call.respond(
                    if (result.success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to result.success, "message" to result.message))
                )
            }

            // 权限管理（字面路由必须在参数路由 /{action} 之前注册）
            post("/permission") {
                val body = call.receive<JsonObject>()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                val perm = body["permission"]?.jsonPrimitive?.contentOrNull ?: ""
                val grant = body["grant"]?.jsonPrimitive?.booleanOrNull ?: true
                if (pkg.isBlank() || perm.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "packageName and permission required")))
                    return@post
                }
                val success = if (grant) appManager.grantPermission(pkg, perm)
                              else appManager.revokePermission(pkg, perm)
                call.respond(toJsonElement(mapOf("success" to success, "grant" to grant)))
            }

            // 冻结/解冻
            post("/freeze") {
                val body = call.receive<JsonObject>()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "packageName is required")))
                    return@post
                }
                val success = appManager.freezeApp(pkg)
                call.respond(toJsonElement(mapOf("success" to success, "action" to "freeze", "packageName" to pkg)))
            }

            post("/unfreeze") {
                val body = call.receive<JsonObject>()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "packageName is required")))
                    return@post
                }
                val success = appManager.unfreezeApp(pkg)
                call.respond(toJsonElement(mapOf("success" to success, "action" to "unfreeze", "packageName" to pkg)))
            }

            // 禁用/启用/清数据/强制停止（参数路由）
            post("/{action}") {
                val action = call.parameters["action"] ?: ""
                val body = call.receive<JsonObject>()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "packageName is required")))
                    return@post
                }
                val success = when (action) {
                    "disable" -> appManager.disableApp(pkg)
                    "enable" -> appManager.enableApp(pkg)
                    "clear" -> appManager.clearAppData(pkg)
                    "force-stop" -> appManager.forceStop(pkg)
                    else -> null
                }
                if (success == null) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Unknown action: $action")))
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to success, "action" to action, "packageName" to pkg)))
            }
        }
    }
}