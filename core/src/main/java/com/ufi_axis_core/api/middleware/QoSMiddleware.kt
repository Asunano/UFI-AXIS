package com.ufi_axis_core.api.middleware

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import com.ufi_axis_core.util.AdaptiveSemaphore

/**
 * QoS 请求级并发控制中间件
 *
 * 将 API 端点分为三组，各组独立限制并发：
 * - SHELL_HEAVY：涉及 shell 执行的端点（advanced、apps、shell 等），动态 1~4 并发
 * - IO_TRANSFER：文件传输端点（upload、download、stream），动态 4~8 并发
 * - DEFAULT：其他端点，动态 4~12 并发
 *
 * 超限请求立即返回 429 Too Many Requests。
 * 使用 AdaptiveSemaphore 支持运行时动态调整许可数，由 DataScheduler 性能监控驱动。
 */
class QoSMiddleware {
    private val tag = "QoSMiddleware"

    private val shellHeavySemaphore = AdaptiveSemaphore(
        initialPermits = SHELL_HEAVY_PERMITS, minPermits = 1, maxPermits = 4
    )
    private val ioTransferSemaphore = AdaptiveSemaphore(
        initialPermits = IO_TRANSFER_PERMITS, minPermits = 4, maxPermits = 8
    )
    private val defaultSemaphore = AdaptiveSemaphore(
        initialPermits = DEFAULT_PERMITS, minPermits = 4, maxPermits = 12
    )

    companion object {
        private const val SHELL_HEAVY_PERMITS = 2
        private const val IO_TRANSFER_PERMITS = 6
        private const val DEFAULT_PERMITS = 8

        /** IO 传输端点路径（upload/download/stream） */
        private val IO_TRANSFER_PATHS = listOf(
            "/api/files/upload",
            "/api/files/download",
            "/api/files/stream"
        )

        /** shell-heavy 端点路径前缀（不含 IO 传输） */
        private val SHELL_HEAVY_PREFIXES = listOf(
            "/api/advanced/",
            "/api/files/",
            "/api/apps/",
            "/api/shell/",
            "/api/device/info",
            "/api/system/cpu",
            "/api/device/connections",
            "/api/device/data-usage"
        )
    }

    fun install(route: Route) {
        route.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            // 健康检查和 WebSocket 不限流
            if (path == "/health" || path.startsWith("/ws/")) {
                proceed()
                return@intercept
            }

            // 分组判定：IO 传输优先匹配，其次 shell-heavy，其余 default
            val (semaphore, groupName) = when {
                IO_TRANSFER_PATHS.any { path.startsWith(it) } -> ioTransferSemaphore to "io-transfer"
                SHELL_HEAVY_PREFIXES.any { path.startsWith(it) } -> shellHeavySemaphore to "shell-heavy"
                else -> defaultSemaphore to "default"
            }

            if (!semaphore.tryAcquire()) {
                AppLogger.w(tag, "QoS limit reached for $groupName group: $path")
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    toJsonElement(mapOf(
                        "error" to "Server busy, try again later",
                        "group" to groupName,
                        "retry_after_ms" to 1000
                    ))
                )
                finish()
                return@intercept
            }

            try {
                proceed()
            } finally {
                semaphore.release()
            }
        }
    }

    /**
     * 根据 ShellQoS 负载动态调整 shell-heavy 组并发数
     * 由 DataScheduler 性能监控循环调用
     */
    fun adjustByLoad(shellAvailable: Int, shellTotal: Int) {
        val saturation = if (shellTotal > 0) 1.0 - shellAvailable.toDouble() / shellTotal else 0.0
        val heavyPermits = when {
            saturation > 0.8 -> 1  // shell 几乎满载，收紧入口
            saturation > 0.5 -> 2  // 中等负载
            else -> 3              // 空闲，放宽
        }
        shellHeavySemaphore.adjustTo(heavyPermits)

        // IO 传输组：高负载时稍收紧，低负载时放宽
        val ioPermits = when {
            saturation > 0.8 -> 4
            saturation > 0.5 -> 5
            else -> 6
        }
        ioTransferSemaphore.adjustTo(ioPermits)

        // default 组反向联动：shell 满载时放宽 default 组（让非 shell 请求通过）
        val defaultPermits = when {
            saturation > 0.8 -> 10
            saturation > 0.5 -> 8
            else -> 6
        }
        defaultSemaphore.adjustTo(defaultPermits)
    }

    /** 获取 QoS 中间件状态（供诊断 API） */
    fun getStatus(): Map<String, Any> = mapOf(
        "shell_heavy_available" to shellHeavySemaphore.availablePermits,
        "shell_heavy_total" to shellHeavySemaphore.totalPermits,
        "shell_heavy_target" to shellHeavySemaphore.target,
        "io_transfer_available" to ioTransferSemaphore.availablePermits,
        "io_transfer_total" to ioTransferSemaphore.totalPermits,
        "io_transfer_target" to ioTransferSemaphore.target,
        "default_available" to defaultSemaphore.availablePermits,
        "default_total" to defaultSemaphore.totalPermits,
        "default_target" to defaultSemaphore.target
    )
}
