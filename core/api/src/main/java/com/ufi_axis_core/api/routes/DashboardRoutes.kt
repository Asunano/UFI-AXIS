package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.GoformNetworkInfo
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.DeviceFields

import com.ufi_axis_core.core.cache.JsonResponseCache
import com.ufi_axis_core.util.AppLogger
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/**
 * Dashboard 聚合路由
 * GET /api/dashboard/summary — 一次性返回仪表盘所需全部数据
 *
 * 减少 Dashboard 刷新时的 HTTP 请求: 7 → 1，显著降低 512MB 设备上的连接开销。
 * 所有数据复用已有 Collector / DataScheduler / DataHub 缓存，不会增加后端负载。
 *
 * 2026-08-22：数据段并行化（coroutineScope + async）。旧实现 7 段串行，
 * goform 查询（网络类型 5s 超时兜底 / 流量限额 / 设备身份）逐个排队，
 * 叠加 shell（uname）/AT/DB 后仪表盘明显慢于只读实时缓存的其他页面；
 * 并行 + 单次共享 hubInfo + 流量限额 10s 缓存后，总耗时 ≈ 最慢一段。
 */
class DashboardRoutes(
    private val ctx: RouteContext
) {
    /**
     * 响应级 JSON 缓存 — 3s 内前端重复轮询直接返回缓存的 JsonElement，
     * 省去 7 个数据源聚合 + Map→JsonElement 递归转换的临时对象分配。
     */
    private val responseCache = JsonResponseCache()

    private companion object {
        const val SUMMARY_CACHE_TTL_MS = 3_000L
    }

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
                // 响应级缓存：3s 内重复轮询直接返回，省去数据聚合 + JSON 序列化
                responseCache.get("summary")?.let { cached ->
                    call.respond(cached)
                    return@get
                }

                // 轻量本地快照（StatFs/SystemClock，微秒级）先取一次，供 summary 与 device_info 复用
                val storageMap = runCatching { systemCollector.getStorageInfo() }.getOrDefault(emptyMap())
                val uptimeMap = runCatching { systemCollector.getUptime() }.getOrDefault(emptyMap())
                val storage = toJsonElement(storageMap)
                val uptime = toJsonElement(uptimeMap)

                // 重 IO 段并行化（2026-08-22 修复仪表盘明显慢于其他页面）：
                // 旧实现 7 段串行，总耗时 ≈ 各段之和——goform 查询超时可达 5s、
                // shell/AT/DB 各几十至几百 ms，串行叠加后仪表盘响应远慢于只读
                // DataScheduler 实时缓存的网络页。并行后总耗时 ≈ 最慢一段。
                // hubInfo 单次获取共享给 device_info 与 network_status（旧实现查两次）。
                // 2026-08-23 修复：添加整体超时保护，避免单个数据源卡死整个请求
                coroutineScope {
                    val batteryAsync = async {
                        runCatching {
                            kotlinx.coroutines.withTimeout(5_000L) {
                                val cached = dataScheduler.latestBattery.value
                                toJsonElement(if (cached.isNotEmpty()) cached else systemCollector.getBatteryInfo())
                            }
                        }.getOrNull()
                    }
                    val hubInfoAsync = async {
                        runCatching { 
                            kotlinx.coroutines.withTimeout(5_000L) { dataHub?.getNetworkTypeInfo() } 
                        }.getOrNull()
                    }
                    val deviceInfoAsync = async {
                        // 等 hubInfo 放在 withTimeout **外面**：它自己已经有 5s 上限，
                        // 以前嵌在里面会把 deviceInfo 的预算吃掉（hubInfo 慢 4s → 只剩 1s
                        // 去做 model/sim/platform/kernel/identity 五个查询），一超时
                        // device_info 整段变 null、SIM 卡与本机号码全空 —— 这就是
                        // 「概览有概率不加载」的主因，且 runCatching 让它全程静默。
                        val hubInfo = hubInfoAsync.await()
                        runCatching {
                            kotlinx.coroutines.withTimeout(8_000L) {
                                buildDeviceInfo(hubInfo, storageMap, uptimeMap)
                            }
                        }.getOrNull()
                    }

                    val trafficSummaryAsync = async {
                        runCatching { 
                            kotlinx.coroutines.withTimeout(5_000L) { buildTrafficSummary() } 
                        }.getOrNull()
                    }
                    val trafficLimitAsync = async {
                        runCatching { 
                            kotlinx.coroutines.withTimeout(5_000L) { buildTrafficLimit() } 
                        }.getOrNull()
                    }
                    val networkStatusAsync = async {
                        runCatching { 
                            kotlinx.coroutines.withTimeout(5_000L) { buildNetworkStatus(hubInfoAsync.await()) } 
                        }.getOrNull()
                    }

                    val response = toJsonElement(mapOf(
                        "device_info" to deviceInfoAsync.await(),
                        "battery" to batteryAsync.await(),
                        "storage" to storage,
                        "uptime" to uptime,
                        "traffic_summary" to trafficSummaryAsync.await(),
                        "traffic_limit" to trafficLimitAsync.await(),
                        "network_status" to networkStatusAsync.await()
                    ))
                    responseCache.put("summary", response, SUMMARY_CACHE_TTL_MS)
                    call.respond(response)
                }
            }
        }
    }

    // ──────────── 设备信息（同 DeviceRoutes /info）────────────
    private suspend fun buildDeviceInfo(
        hubInfo: GoformNetworkInfo?,
        storageMap: Map<String, Any>,
        uptimeMap: Map<String, Any>
    ): JsonElement {
        val deviceInfo = systemController?.getDeviceModel() ?: emptyMap<String, Any>()
        val simInfo = telephonyCollector?.getSimInfo() ?: emptyMap<String, Any>()
        val atInfo = atChannel?.getPlatformInfo() ?: mapOf("connected" to false)
        val kernelVersion = systemController?.getKernelVersion() ?: ""
        val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
        val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
        val identity = try { dataHub?.getDeviceIdentity() } catch (e: Exception) { AppLogger.w("DashboardRoutes", "Failed to get device identity: ${e.message}"); null }
        return toJsonElement(mapOf(
            "device" to deviceInfo,
            "sim" to simInfo,
            "storage" to storageMap,
            "uptime" to uptimeMap,
            "at_channel" to atInfo,
            "kernel" to kernelVersion,
            "network" to mapOf(
                "operator" to (goformProvider ?: telephonyCollector?.getOperatorName() ?: ""),
                "type" to (goformType ?: telephonyCollector?.getNetworkType() ?: ""),
                "connected" to (telephonyCollector?.isNetworkAvailable() ?: false)
            ),
            "identity" to identity
        ))
    }

    // ──────────── 流量汇总（与 TrafficRoutes /summary 共用 TrafficSummaryMapper）────────────
    // 两边曾各写一份：这里**漏了 today_rx_display / today_tx_display**，而 Web 仪表盘和
    // app 的 MonitorOverview 都只读 /api/dashboard/summary，所以「今日已用」一直是 `--`。
    // 现在字段与 /api/traffic/summary 完全一致。
    private suspend fun buildTrafficSummary(): JsonElement {
        val totalRecords = database.trafficDao().getCount()
        val goform = dataScheduler.goformTraffic.value
        var monthRx = 0L; var monthTx = 0L

        if (goform != null && (goform.first > 0 || goform.second > 0)) {
            monthRx = goform.first; monthTx = goform.second
        }

        val (todayRx, todayTx) = dataScheduler.todayTraffic.value
        return TrafficSummaryMapper.build(monthRx, monthTx, todayRx, todayTx, totalRecords)

    }


    // ──────────── 流量限额（与 DeviceRoutes /traffic-limit 共用 TrafficLimitMapper）────────────
    // 两边曾各写一份解析，判定逻辑漂移过（这里的 auto_clear 恒为 false、enabled 不回退 flux_*），
    // 导致仪表盘和设备页显示不一致。现在统一由 mapper 产出，字段与 /traffic-limit 完全一致。
    private suspend fun buildTrafficLimit(): JsonElement? {
        val dh = dataHub ?: return null
        // 10s TTL 缓存（DataHub.getTrafficLimit）：仪表盘高频轮询不再每次透传 goform
        val raw = dh.getTrafficLimit() ?: return null
        return TrafficLimitMapper.build(raw)
    }


    // ──────────── 网络状态（同 NetworkRoutes /status）────────────
    private suspend fun buildNetworkStatus(hubInfo: GoformNetworkInfo?): JsonElement {
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
            // 输出键沿用 canonical 名（`DeviceFields.Connection`），值已在 core 侧统一：
            // `network_type` 是可读文案，不是设备数字码。
            DeviceFields.Connection.PPP_STATUS to (hubInfo?.pppStatus ?: ""),
            "operator" to (goformProvider ?: telephonyCollector?.getOperatorName() ?: ""),
            DeviceFields.Connection.NETWORK_TYPE to (goformType ?: telephonyCollector?.getNetworkType() ?: "")
        ))
    }
}
