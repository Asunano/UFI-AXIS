package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import com.ufi_axis_core.util.ThermalZones
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
    private val dynamicThreadPool get() = ctx.dynamicThreadPool

    fun register(route: Route) {
        route.route("/qos") {
            get("/status") {
                val poolInfo = dynamicThreadPool.getThreadPoolInfo()

                // ── CPU 温度：走 ThermalZones（2026-09-25 P3-4）──
                // 这里原来内联 `File("/sys/class/thermal/thermal_zone0/temp")` 只读 zone0 ——
                // 正是缺陷 C 要消灭的那个判据（zone0 在很多平台是电池/外壳，不是 CPU），
                // 而 `ThermalZones` 自称全仓唯一的热区读法。
                //
                // ⚠ 对外契约**一点没动**：`cpu_temp` 仍是**毫摄氏度原始整数**、读失败仍是 `0`。
                //   三处消费方按这个口径写死了 —— `DiagnosticsModels.cpuTempCelsius`（判 <=0 回 null）、
                //   `web/.../PerformancePanel.vue`、API 文档那句「别直接当摄氏度显示」。
                //   所以这里既**不**换成摄氏度、也**不**换成可空，读不到时保留 0 并打一条带
                //   detail 的 warn（原来是完全静默）。
                val cpuTemp = withContext(Dispatchers.IO) {
                    val reading = ThermalZones.readMax()
                    val milliC = reading.maxMilliC
                    if (milliC == null) {
                        // detail 里只有结构性事实（路径/热区名/个数），可以被 repeatGate 正常折叠
                        AppLogger.w("QoSRoutes", "QoS 状态里的 cpu_temp 读不到，按 0 下发：${reading.detail}")
                        0
                    } else {
                        milliC.toInt()
                    }
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
