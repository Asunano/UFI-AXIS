package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
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
        }
    }
}
