package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.components.ComponentManager
import com.ufi_axis_core.controller.system.TunnelManager
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可选二进制组件路由（2026-09-01：frpc / cloudflared 从 APK 剥离，改为按需下载）。
 *
 * - GET  /api/components                 组件列表（本地状态 + 远端清单）；`?refresh=true` 强制重拉清单
 * - GET  /api/components/status          当前安装任务进度（前端轮询）
 * - POST /api/components/{id}/install    从更新源下载安装（异步，立即返回）
 * - POST /api/components/{id}/uninstall  卸载（有实例在跑时拒绝）
 * - POST /api/components/{id}/upload     本地上传安装（raw body，裸二进制或 tar.gz）
 *
 * "有没有实例在跑"只有 [TunnelManager] 知道，所以卸载前置检查放在这一层，
 * 而不是塞进 [ComponentManager]（那样它就得反向依赖引擎）。
 */
class ComponentRoutes(
    private val componentManager: ComponentManager,
    private val tunnelManager: TunnelManager
) {

    companion object {
        private const val TAG = "ComponentRoutes"
    }

    fun register(route: Route) {
        route.route("/components") {
            get {
                val refresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
                val list = withContext(Dispatchers.IO) { componentManager.listComponents(refresh) }
                call.respond(toJsonElement(mapOf("components" to list) + componentManager.statusToMap()))
            }

            get("/status") {
                call.respond(toJsonElement(componentManager.statusToMap()))
            }

            post("/{id}/install") {
                val id = call.parameters["id"].orEmpty()
                val error = componentManager.install(id)
                if (error != null) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to error)))
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to true) + componentManager.statusToMap()))
            }

            post("/{id}/uninstall") {
                val id = call.parameters["id"].orEmpty()
                if (tunnelManager.hasRunningInstances(id)) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        toJsonElement(mapOf("error" to "$id 还有实例在运行，请先全部停止后再卸载"))
                    )
                    return@post
                }
                withContext(Dispatchers.IO) { componentManager.uninstall(id) }.fold(
                    onSuccess = {
                        tunnelManager.invalidateComponentCaches()
                        AppLogger.i(TAG, "组件已卸载: $id")
                        call.respond(toJsonElement(mapOf("success" to true)))
                    },
                    onFailure = { e ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            toJsonElement(mapOf("error" to (e.message ?: "卸载失败")))
                        )
                    }
                )
            }

            // raw body 而非 multipart：组件动辄几十 MB，multipart 那套先攒 ByteArray 的写法
            // 在 256MB RAM 的低端设备上会直接 OOM，这里必须边收边落盘。
            post("/{id}/upload") {
                val id = call.parameters["id"].orEmpty()
                if (tunnelManager.hasRunningInstances(id)) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        toJsonElement(mapOf("error" to "$id 还有实例在运行，请先全部停止后再覆盖安装"))
                    )
                    return@post
                }
                val stream = call.receiveStream()
                withContext(Dispatchers.IO) { componentManager.installFromUpload(id, stream) }.fold(
                    onSuccess = { version ->
                        tunnelManager.invalidateComponentCaches()
                        AppLogger.i(TAG, "组件本地上传安装成功: $id v$version")
                        call.respond(
                            toJsonElement(mapOf("success" to true, "version" to version))
                        )
                    },
                    onFailure = { e ->
                        AppLogger.w(TAG, "组件本地上传安装失败[$id]: ${e.message}")
                        call.respond(
                            HttpStatusCode.BadRequest,
                            toJsonElement(mapOf("error" to (e.message ?: "上传安装失败")))
                        )
                    }
                )
            }
        }
    }
}
