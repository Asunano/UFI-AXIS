package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * DLNA MediaServer 配置端点（2026-10-09，`/api/dlna` 下）。
 *
 * - `GET /api/dlna/status` → { enabled, running, ready, dirs }
 * - `PUT /api/dlna/config` → { enabled?, dirs? }；目录未配置时 enabled=true 拒绝
 *   （web/app 开关据此置灰，这里再拦一次纵深防御）。
 *
 * 依赖注入用回调（[DlnaStatus]/[DlnaConfig] 接口），与 ServiceRoutes 同款 ——
 * `:core:api` 不能反向依赖 `:core`（DlnaService 在那里），装配层注入实现。
 *
 * 共享目录口径：与媒体中心扫描目录同一套用户存储白名单语义（/storage 下、无 `..`）。
 * 目录校验只做形态检查，不要求目录当前存在（SD 卡拔出不该清配置）。
 */
class DlnaRoutes(
    private val status: () -> DlnaStatus,
    private val applyConfig: (DlnaConfig) -> Unit,
) {
    data class DlnaStatus(
        val enabled: Boolean,
        val running: Boolean,
        val ready: Boolean,
        val dirs: List<String>,
    )

    data class DlnaConfig(
        val enabled: Boolean? = null,
        val dirs: List<String>? = null,
    )

    fun register(route: Route) {
        route.route("/dlna") {
            get("/status") {
                val s = status()
                call.respond(toJsonElement(mapOf(
                    "enabled" to s.enabled,
                    "running" to s.running,
                    "ready" to s.ready,
                    "dirs" to s.dirs,
                )))
            }

            put("/config") {
                val body = call.receiveJsonObject()
                var dirs: List<String>? = null
                // 2026-10-09 修复：app 的 AppJson.encodeDefaults=true 会把可空字段写成 "dirs":null，
                // containsKey 为真但 jsonArray 对 JsonNull 抛异常 → 误报「缺少 dirs 数组」。
                // null 与「未提供」同义，都跳过（只在 dirs 是真数组时才走校验）。
                val dirsEl = body["dirs"]
                if (dirsEl != null && dirsEl !is kotlinx.serialization.json.JsonNull) {
                    val arr = runCatching { dirsEl.jsonArray }.getOrNull() ?: run {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 dirs 数组")
                        return@put
                    }
                    val cleaned = arr.mapNotNull { e -> runCatching { e.jsonPrimitive.content }.getOrNull() }
                        .map { it.trim().trimEnd('/') }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val illegal = cleaned.filter {
                        it.contains("..") ||
                            !(it.startsWith("/storage") || it.startsWith("/sdcard") || it.startsWith("/mnt/media_rw"))
                    }
                    if (illegal.isNotEmpty()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "目录不在用户存储范围内: ${illegal.first()}")
                        return@put
                    }
                    dirs = cleaned
                }

                val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull
                // 目录未配置时禁止开启：与前端开关置灰双保险
                if (enabled == true) {
                    val after = dirs ?: status().dirs
                    if (after.isEmpty()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "请先配置共享目录")
                        return@put
                    }
                }

                applyConfig(DlnaConfig(enabled = enabled, dirs = dirs))
                val s = status()
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "enabled" to s.enabled,
                    "running" to s.running,
                    "ready" to s.ready,
                    "dirs" to s.dirs,
                )))
            }
        }
    }
}
