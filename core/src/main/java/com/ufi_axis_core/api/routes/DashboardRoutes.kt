package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.util.AppLogger
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * Dashboard 聚合路由
 * GET /api/dashboard/summary — 一次性返回仪表盘所需全部数据
 *
 * 减少 Dashboard 刷新时的 HTTP 请求: 7 → 1，显著降低 512MB 设备上的连接开销。
 * 所有数据复用已有 Collector / DataScheduler / DataHub 缓存，不会增加后端负载。
 */
class DashboardRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val systemCollector get() = ctx.systemCollector
    private val dataScheduler get() = ctx.dataScheduler
    private val database get() = ctx.database
    private val networkController get() = ctx.networkController
    private val dataHub get() = ctx.dataHub
    private val telephonyCollector get() = ctx.telephonyCollector
    private val systemController get() = ctx.systemController
    private val atChannel get() = ctx.atChannel

    fun register(route: Route) {
        route.route("/dashboard") {
            get("/summary") {
                // 每个字段独立 try-catch，一个失败不影响其他
                val battery = runCatching {
                    val cached = dataScheduler.latestBattery.value
                    toJsonElement(if (cached.isNotEmpty()) cached else systemCollector.getBatteryInfo())
                }.getOrNull()

                val storage = runCatching { toJsonElement(systemCollector.getStorageInfo()) }.getOrNull()
                val uptime = runCatching { toJsonElement(systemCollector.getUptime()) }.getOrNull()

                // 以下复用各独立端点的逻辑，不增加额外负担
                val deviceInfo = runCatching { buildDeviceInfo() }.getOrNull()
                val trafficSummary = runCatching { buildTrafficSummary() }.getOrNull()
                val trafficLimit = runCatching { buildTrafficLimit() }.getOrNull()
                val networkStatus = runCatching { buildNetworkStatus() }.getOrNull()

                call.respond(toJsonElement(mapOf(
                    "device_info" to deviceInfo,
                    "battery" to battery,
                    "storage" to storage,
                    "uptime" to uptime,
                    "traffic_summary" to trafficSummary,
                    "traffic_limit" to trafficLimit,
                    "network_status" to networkStatus
                )))
            }
        }
    }

    // ──────────── 设备信息（同 DeviceRoutes /info）────────────
    private suspend fun buildDeviceInfo(): JsonElement {
        val deviceInfo = systemController?.getDeviceModel() ?: emptyMap<String, Any>()
        val simInfo = telephonyCollector?.getSimInfo() ?: emptyMap<String, Any>()
        val storageInfo = systemCollector.getStorageInfo()
        val uptime = systemCollector.getUptime()
        val atInfo = atChannel?.getPlatformInfo() ?: mapOf("connected" to false)
        val kernelVersion = systemController?.getKernelVersion() ?: ""
        val hubInfo = try { dataHub?.getNetworkTypeInfo() } catch (e: Exception) { AppLogger.w("DashboardRoutes", "Failed to get network type info: ${e.message}"); null }
        val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
        val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
        val identity = try { dataHub?.getDeviceIdentity() } catch (e: Exception) { AppLogger.w("DashboardRoutes", "Failed to get device identity: ${e.message}"); null }
        return toJsonElement(mapOf(
            "device" to deviceInfo,
            "sim" to simInfo,
            "storage" to storageInfo,
            "uptime" to uptime,
            "at_channel" to atInfo,
            "kernel" to kernelVersion,
            "network" to mapOf(
                "operator" to (goformProvider ?: telephonyCollector?.getOperatorName() ?: ""),
                "type" to (if (goformType != null) GoformClient.mapNetworkType(goformType) else telephonyCollector?.getNetworkType() ?: ""),
                "connected" to (telephonyCollector?.isNetworkAvailable() ?: false)
            ),
            "identity" to identity
        ))
    }

    // ──────────── 流量汇总（同 TrafficRoutes /summary）────────────
    private suspend fun buildTrafficSummary(): JsonElement {
        val latest = dataScheduler.latestTraffic.value
        val totalRecords = database.trafficDao().getCount()
        val goform = dataScheduler.goformTraffic.value
        var monthRx = 0L; var monthTx = 0L
        var todayRx = 0L; var todayTx = 0L

        if (goform != null && (goform.first > 0 || goform.second > 0)) {
            monthRx = goform.first; monthTx = goform.second
        }

        // Goform 不可用时回退到数据库差值
        if (monthRx == 0L && monthTx == 0L) {
            val zone = java.time.ZoneId.systemDefault()
            val todayStart = java.time.LocalDate.now(zone)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val monthStart = java.time.LocalDate.now(zone).withDayOfMonth(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val todayRecords = database.trafficDao().getRecordsSince(todayStart)
            val monthRecords = database.trafficDao().getRecordsSince(monthStart)
            todayRx = if (todayRecords.size >= 2) (todayRecords.last().rxBytes - todayRecords.first().rxBytes).coerceAtLeast(0) else 0L
            todayTx = if (todayRecords.size >= 2) (todayRecords.last().txBytes - todayRecords.first().txBytes).coerceAtLeast(0) else 0L
            monthRx = if (monthRecords.size >= 2) (monthRecords.last().rxBytes - monthRecords.first().rxBytes).coerceAtLeast(0) else 0L
            monthTx = if (monthRecords.size >= 2) (monthRecords.last().txBytes - monthRecords.first().txBytes).coerceAtLeast(0) else 0L
        }

        val totalRx = if (monthRx > 0) monthRx else (latest?.rxBytes ?: 0)
        val totalTx = if (monthTx > 0) monthTx else (latest?.txBytes ?: 0)

        return toJsonElement(mapOf(
            "total_rx_bytes" to totalRx,
            "total_tx_bytes" to totalTx,
            "total_bytes" to (totalRx + totalTx),
            "total_rx_display" to formatBytes(totalRx),
            "total_tx_display" to formatBytes(totalTx),
            "record_count" to totalRecords,
            "today_rx_display" to formatBytes(todayRx),
            "today_tx_display" to formatBytes(todayTx),
            "month_rx_display" to formatBytes(monthRx),
            "month_tx_display" to formatBytes(monthTx)
        ))
    }

    // ──────────── 流量限额（同 DeviceRoutes /traffic-limit）────────────
    private suspend fun buildTrafficLimit(): JsonElement? {
        val dh = dataHub ?: return null
        val raw = dh.signalQuery { getDataUsage() } ?: return null
        val enabled = raw["data_volume_limit_switch"]?.jsonPrimitive?.contentOrNull == "1"
        val limitSize = raw["data_volume_limit_size"]?.jsonPrimitive?.contentOrNull ?: ""
        val limitUnit = raw["data_volume_limit_unit"]?.jsonPrimitive?.contentOrNull ?: "MB"
        val alertPercent = raw["data_volume_alert_percent"]?.jsonPrimitive?.contentOrNull ?: "80"
        val autoClear = raw["wan_auto_clear_flow_data_switch"]?.jsonPrimitive?.contentOrNull == "1"
        val clearDate = raw["traffic_clear_date"]?.jsonPrimitive?.contentOrNull ?: "1"
        val monthlyRx = raw["monthly_rx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val monthlyTx = raw["monthly_tx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val monthlyTime = raw["monthly_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return toJsonElement(mapOf(
            "enabled" to enabled,
            "limit_size" to limitSize,
            "limit_unit" to limitUnit,
            "alert_percent" to alertPercent,
            "auto_clear" to autoClear,
            "clear_date" to clearDate,
            "monthly_rx_bytes" to monthlyRx,
            "monthly_tx_bytes" to monthlyTx,
            "monthly_time" to monthlyTime
        ))
    }

    // ──────────── 网络状态（同 NetworkRoutes /status）────────────
    private suspend fun buildNetworkStatus(): JsonElement {
        val hubInfo = try { dataHub?.getNetworkTypeInfo() } catch (e: Exception) { AppLogger.w("DashboardRoutes", "Failed to get network type info: ${e.message}"); null }
        val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
        val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
        val mobileDataEnabled = if (hubInfo != null) hubInfo.isPppConnected
            else telephonyCollector?.isMobileDataEnabled() ?: false
        return toJsonElement(mapOf(
            "network" to (networkController?.getNetworkStatus() ?: mapOf(
                "is_connected" to false, "has_internet" to false,
                "has_cellular" to false, "has_wifi" to false
            )),
            "mobile_data" to mobileDataEnabled,
            "ppp_status" to (hubInfo?.pppStatus ?: ""),
            "operator" to (goformProvider ?: telephonyCollector?.getOperatorName() ?: ""),
            "network_type" to (if (goformType != null) GoformClient.mapNetworkType(goformType) else telephonyCollector?.getNetworkType() ?: "")
        ))
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}