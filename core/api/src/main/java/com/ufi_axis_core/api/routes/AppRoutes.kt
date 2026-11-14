package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
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
                else call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "App not found")
            }

            // 安装 APK（从设备路径）
            post("/install") {
                // 写门（2026-10-05 P0-2 批次补全 R4-9 覆盖面）：安装与更新互斥
                if (call.rejectIfUpdating()) return@post
                val body = call.receiveJsonObject()
                val path = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (path.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "path is required")
                    return@post
                }
                val result = appManager.installApk(path)
                if (result.success) {
                    call.respond(
                        HttpStatusCode.OK,
                        toJsonElement(mapOf("success" to result.success, "message" to result.message))
                    )
                } else {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.OPERATION_FAILED, result.message)
                }
            }

            // 安装 APK（从 URL 下载）
            post("/install-url") {
                // 写门（2026-10-05 P0-2 批次补全 R4-9 覆盖面）：安装与更新互斥
                if (call.rejectIfUpdating()) return@post
                val body = call.receiveJsonObject()
                val url = body["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (url.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "url is required")
                    return@post
                }
                // P0-2：sha256 必填——没有内容校验的「下载并 root 安装」等于局域网明文投毒通道
                val sha256 = body["sha256"]?.jsonPrimitive?.contentOrNull ?: ""
                if (sha256.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "sha256 is required (64-hex of the APK to install)")
                    return@post
                }
                val result = appManager.installApkFromUrl(url, sha256)
                if (result.success) {
                    call.respond(
                        HttpStatusCode.OK,
                        toJsonElement(mapOf("success" to result.success, "message" to result.message))
                    )
                } else {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.OPERATION_FAILED, result.message)
                }
            }

            // 卸载应用
            post("/uninstall") {
                // 写门（2026-10-05 P0-2 批次补全 R4-9 覆盖面）：卸载与更新互斥（被更新目标被卸会导致半装态）
                if (call.rejectIfUpdating()) return@post
                val body = call.receiveJsonObject()
                // 卸载不可逆（应用数据一并消失）；与「安装」不是一类 —— 安装可以重做。
                // [owner] POST /api/apps/uninstall
                if (!call.requireOwner(body.ownerPassword())) return@post
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName is required")
                    return@post
                }
                val result = appManager.uninstallApp(pkg)
                if (result.success) {
                    call.respond(
                        HttpStatusCode.OK,
                        toJsonElement(mapOf("success" to result.success, "message" to result.message))
                    )
                } else {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.OPERATION_FAILED, result.message)
                }
            }

            // 权限管理（字面路由必须在参数路由 /{action} 之前注册）
            post("/permission") {
                val body = call.receiveJsonObject()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                val perm = body["permission"]?.jsonPrimitive?.contentOrNull ?: ""
                val grant = body["grant"]?.jsonPrimitive?.booleanOrNull ?: true
                if (pkg.isBlank() || perm.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName and permission required")
                    return@post
                }
                val success = if (grant) appManager.grantPermission(pkg, perm)
                              else appManager.revokePermission(pkg, perm)
                call.respond(toJsonElement(mapOf("success" to success, "grant" to grant)))
            }

            // 通过 ADB shell 一次性授予所有运行时权限（uid 2000，解决 uid 10101 pm grant 无效的问题）
            post("/grant-all-permissions") {
                val body = call.receiveJsonObject()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName is required")
                    return@post
                }
                val (success, message) = appManager.grantAllPermissionsViaAdb(pkg)
                if (success) {
                    call.respond(
                        HttpStatusCode.OK,
                        toJsonElement(mapOf("success" to success, "message" to message))
                    )
                } else {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.OPERATION_FAILED, message)
                }
            }

            // 冻结/解冻
            post("/freeze") {
                val body = call.receiveJsonObject()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName is required")
                    return@post
                }
                val success = appManager.freezeApp(pkg)
                call.respond(toJsonElement(mapOf("success" to success, "action" to "freeze", "packageName" to pkg)))
            }

            post("/unfreeze") {
                val body = call.receiveJsonObject()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName is required")
                    return@post
                }
                val success = appManager.unfreezeApp(pkg)
                call.respond(toJsonElement(mapOf("success" to success, "action" to "unfreeze", "packageName" to pkg)))
            }

            // 禁用/启用/清数据/强制停止（参数路由）
            post("/{action}") {
                // 写门（2026-10-30 审查 §2.10）：这四个动作在更新进行中执行都可能打断 OTA
                // （`clear` 清掉正在写数据的包、`force-stop` 杀掉安装协调进程）。
                if (call.rejectIfUpdating()) return@post
                val action = call.parameters["action"] ?: ""
                val body = call.receiveJsonObject()
                val pkg = body["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pkg.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "packageName is required")
                    return@post
                }
                // 2026-10-30（审查 §2.8）：`clear`（清数据，不可恢复）与 `force-stop`（停进程）
                // 共用这一个端点、同一组参数，只靠 URL 末段区分 —— 一次拼错就从"停一下"
                // 变成"删干净"。所以 `clear` 必须显式带 confirm=true，缺了回 400 而不是沉默执行。
                // 其余三个动作（disable/enable/force-stop）都是可逆或可重做的，按原样放行。
                // `clear` 是**不可逆**的（应用数据一旦清掉不可恢复），与 `uninstall` 同级：
                // 只靠调用方自己填的 `confirm=true` 拦不住任何人。
                // [owner] POST /api/apps/{action}（action=clear）
                if (action == "clear" && !call.requireOwner(body.ownerPassword())) return@post
                if (action == "clear" && body["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "清除应用数据不可恢复，需要 confirm=true"
                    )
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
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_ACTION_TYPE, "Unknown action: $action")
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to success, "action" to action, "packageName" to pkg)))
            }
        }
    }
}