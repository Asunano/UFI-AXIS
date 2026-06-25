package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 系统资源路由
 * GET /api/system/cpu      - CPU 使用率 + 各核频率
 * GET /api/system/memory   - 内存信息
 * GET /api/system/battery  - 电池信息
 * GET /api/system/storage  - 存储信息
 */
class SystemRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val systemCollector get() = ctx.systemCollector
    private val dataScheduler get() = ctx.dataScheduler
    private val database get() = ctx.database

    fun register(route: Route) {
        route.route("/system") {
            // CPU 信息（StateFlow 仅持有 CpuInfoLite 无 cores，统一走 getCpuInfo()）
            get("/cpu") {
                val cpuInfo = systemCollector.getCpuInfo()
                // 手动构建 JsonObject 绕过 trySerialize，
                // 防止序列化失败时 toJsonElement 降级为 toString() 导致 Gson 解析崩溃
                call.respond(buildJsonObject {
                    put("usage_percent", cpuInfo.usage_percent)
                    put("core_count", cpuInfo.core_count)
                    put("cores", buildJsonArray {
                        cpuInfo.cores.forEach { core ->
                            add(buildJsonObject {
                                put("core", core.core)
                                put("freq_mhz", core.freq_mhz)
                                put("freq_display", core.freq_display)
                            })
                        }
                    })
                    put("temperature", cpuInfo.temperature)
                })
            }

            // CPU 历史（使用轻量查询，仅 SELECT 需要的列）
            get("/cpu/history") {
                val hoursParam = (call.request.queryParameters["hours"] ?: "24").toIntOrNull() ?: 24
                val startTime = System.currentTimeMillis() - hoursParam * 60 * 60 * 1000L
                val records = database.cpuHistoryDao().getLightweightSince(startTime)
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 内存信息
            get("/memory") {
                call.respond(toJsonElement(systemCollector.getMemoryInfo()))
            }

            // 电池信息（优先缓存）
            get("/battery") {
                val cached = dataScheduler.latestBattery.value
                val batteryInfo = if (cached.isNotEmpty()) cached else systemCollector.getBatteryInfo()
                call.respond(toJsonElement(batteryInfo))
            }

            // 存储信息
            get("/storage") {
                call.respond(toJsonElement(systemCollector.getStorageInfo()))
            }

            // 系统运行时间
            get("/uptime") {
                call.respond(toJsonElement(systemCollector.getUptime()))
            }

            // Root 权限检测
            get("/root-check") {
                val hasRoot = ShellExecutor.hasRootAccess()
                call.respond(buildJsonObject { put("hasRoot", hasRoot) })
            }
        }
    }
}
