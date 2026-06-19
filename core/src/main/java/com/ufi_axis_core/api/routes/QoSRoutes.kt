package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * QoS 诊断路由
 * GET /api/qos/status — 返回完整的 QoS 系统状态
 */
class QoSRoutes(
    private val qosMiddleware: QoSMiddleware,
    private val dynamicThreadPool: DynamicThreadPool
) {
    fun register(route: Route) {
        route.route("/qos") {
            get("/status") {
                val poolInfo = dynamicThreadPool.getThreadPoolInfo()

                // 读取 CPU 温度（非 root，轻量级）
                val cpuTemp = try {
                    val result = ShellExecutor.execute("cat /sys/class/thermal/thermal_zone0/temp")
                    result.stdout.trim().toIntOrNull() ?: 0
                } catch (_: Exception) { 0 }

                call.respond(toJsonElement(mapOf(
                    "enabled" to true,
                    "shell_root" to mapOf(
                        "available" to ShellQoS.rootAvailablePermits,
                        "total" to ShellQoS.rootTotalPermits,
                        "target" to ShellQoS.rootTargetPermits
                    ),
                    "shell_normal" to mapOf(
                        "available" to ShellQoS.normalAvailablePermits,
                        "total" to ShellQoS.normalTotalPermits
                    ),
                    "cache" to mapOf(
                        "entries" to ShellQoS.cacheSize,
                        "ttl_ms" to ShellQoS.currentCacheTtlMs
                    ),
                    "batch" to mapOf(
                        "total_batches" to ShellQoS.batchStats["total_batches"],
                        "commands_saved" to ShellQoS.batchStats["commands_saved"]
                    ),
                    "endpoints" to qosMiddleware.getStatus(),
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
