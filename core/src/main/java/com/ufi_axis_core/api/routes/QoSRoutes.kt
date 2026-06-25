package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import java.io.File
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QoS 诊断路由
 * GET /api/qos/status — 返回完整的 QoS 系统状态
 */
class QoSRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val qosMiddleware get() = ctx.qosMiddleware
    private val dynamicThreadPool get() = ctx.dynamicThreadPool

    fun register(route: Route) {
        route.route("/qos") {
            get("/status") {
                val poolInfo = dynamicThreadPool.getThreadPoolInfo()

                // 读取 CPU 温度（非 root，轻量级）
                val cpuTemp = withContext(Dispatchers.IO) {
                    try {
                        File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toIntOrNull() ?: 0
                    } catch (_: Exception) { 0 }
                }

                call.respond(toJsonElement(mapOf(
                    "enabled" to true,
                    "shell_qos" to mapOf(
                        "root" to mapOf(
                            "available" to ShellQoS.rootAvailablePermits,
                            "total" to ShellQoS.rootTotalPermits,
                            "target" to ShellQoS.rootTargetPermits
                        ),
                        "normal" to mapOf(
                            "available" to ShellQoS.normalAvailablePermits,
                            "total" to ShellQoS.normalTotalPermits
                        ),
                        "cache" to mapOf(
                            "entries" to ShellQoS.cacheSize,
                            "ttl_ms" to ShellQoS.currentCacheTtlMs
                        ),
                        "batch" to ShellQoS.batchStats
                    ),
                    "goform_qos" to GoformQoS.getStatus(),
                    "frontend_safety" to qosMiddleware.getStatus(),
                    "cpu_temp" to cpuTemp,
                    "dynamic_pool" to mapOf(
                        "core" to poolInfo.corePoolSize,
                        "current" to poolInfo.currentPoolSize,
                        "max" to poolInfo.maxPoolSize
                    )
                )))
            }
        }
    }
}
