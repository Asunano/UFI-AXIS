package com.ufi_axis_core.api.middleware

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AdaptiveSemaphore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * QoS 前端安全阀
 *
 * 单一全局信号量（50 并发，20~100 动态调整），防止极端情况下前端请求风暴。
 * 健康检查和 WebSocket 不限流。
 *
 * **设计哲学（2026-06-21 重构）**：
 * 前端→后端是 localhost 回环，性能损耗可忽略；真正瓶颈是后端→设备通信，
 * 已由 ShellQoS（root shell）和 GoformQoS（HTTP goform）独立控制。
 * 本中间件仅作为最后一道安全阀，不做分组排队。
 */
class QoSMiddleware {
    private val tag = "QoSMiddleware"

    private val globalSemaphore = AdaptiveSemaphore(
        initialPermits = 50, minPermits = 20, maxPermits = 100
    )

    /** 豁免路径 — 管理/诊断接口不应被安全阀阻塞 */
    private val exemptPaths = listOf(
        "/health",
        "/api/qos/status",
        "/api/config"
    )

    fun install(route: Route) {
        route.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            // 健康检查、WebSocket、管理诊断接口不限流
            if (exemptPaths.any { path == it || path.startsWith("/ws/") }) {
                proceed()
                return@intercept
            }

            if (!globalSemaphore.tryAcquire()) {
                val available = globalSemaphore.availablePermits
                val total = globalSemaphore.totalPermits
                AppLogger.w(tag, "QoS safety valve triggered for $path (available=$available/$total)")
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    toJsonElement(mapOf(
                        "error" to "Server busy, try again later",
                        "retry_after_ms" to 1000,
                        "available_permits" to available,
                        "total_permits" to total
                    ))
                )
                finish()
                return@intercept
            }

            try {
                proceed()
            } finally {
                globalSemaphore.release()
            }
        }
    }

    /** 获取 QoS 中间件状态（供诊断 API） */
    fun getStatus(): Map<String, Any> = mapOf(
        "available" to globalSemaphore.availablePermits,
        "total" to globalSemaphore.totalPermits,
        "target" to globalSemaphore.target
    )
}
