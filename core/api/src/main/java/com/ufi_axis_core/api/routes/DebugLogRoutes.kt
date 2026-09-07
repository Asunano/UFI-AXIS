package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*

class DebugLogRoutes {
    fun register(route: Route) {
        route.route("/debug-logs") {
            get {
                val level = call.request.queryParameters["level"]?.takeIf { it.isNotBlank() }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                val logs = AppLogger.getBufferedLogs(level, limit)
                call.respond(toJsonElement(mapOf("logs" to logs, "total" to logs.size)))
            }
            delete {
                AppLogger.clearBuffer()
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // ── 落盘日志文件（2026-08-28 新增）──
            // 日志已改到 /sdcard/Download/UFI-AXIS/log/core/<日期>/，文件管理器可直接看；
            // 这里的接口是给 app / web 远程列目录和读尾部用的（手机上看设备的日志）。
            route("/files") {
                get {
                    val files = AppLogger.listLogFiles()
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "files" to files,
                                "total" to files.size,
                                "total_bytes" to files.sumOf { (it["size"] as? Long) ?: 0L },
                                "dir" to AppLogger.logDirPath()
                            )
                        )
                    )
                }
                // 删除全部落盘日志（只删 core 侧；内存缓冲用上面的 DELETE /debug-logs 清）
                delete {
                    val freed = AppLogger.deleteAllLogFiles()
                    call.respond(toJsonElement(mapOf("success" to true, "freed_bytes" to freed)))
                }
                // 读单个文件的尾部。整读不行——单文件可以有几十 MB，会直接 OOM。
                // {name} 是「日期/文件名」，Ktor 的单段通配匹配不到斜杠，所以用 {name...}
                get("/{name...}") {
                    val name = call.parameters.getAll("name")?.joinToString("/").orEmpty()
                    val maxBytes = call.request.queryParameters["max_bytes"]?.toIntOrNull()
                        ?.coerceIn(1024, 2 * 1024 * 1024) ?: (256 * 1024)
                    val text = AppLogger.readLogTail(name, maxBytes)
                    if (text == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            toJsonElement(mapOf("error" to "log file not found: $name"))
                        )
                    } else {
                        call.respondText(text, ContentType.Text.Plain)
                    }
                }
            }
        }
    }
}
