package com.ufi_axis_core.api

import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.*

/**
 * 统一请求数据中心（DataHub）
 *
 * ## 架构
 * ```
 * Routes ──▶ DataHub ──┬── 实时缓存 (DataScheduler)
 *                       ├── 按需缓存 (ResponseCache)
 *                       └── 透传查询 (signalQuery/wifiQuery)
 *                                  │
 *                                  ▼
 *                          GoformClient ──▶ ZTE Device
 * ```
 *
 * ## 使用
 * ```kotlin
 * // 高频实时数据 — DataScheduler 自动采集
 * dataHub.latestCpu.value
 * dataHub.latestTraffic.value
 *
 * // 带缓存的 goform 查询
 * dataHub.getNetworkTypeInfo()    // 30s TTL，跨路由共享
 * dataHub.getWifiSettingsMerged() // ResponseCache，30s TTL
 *
 * // 无缓存透传 — signalQuery {} 直接代理到 GoformSignalClient
 * dataHub.signalQuery { getCellInfo() }
 * dataHub.signalQuery { getFullStatus() }
 *
 * // WiFi 透传
 * dataHub.wifiQuery { getConnectedClients() }
 * ```
 *
 * ## 新增 goform 查询指南
 * 1. **只透传**（无缓存）→ 路由内直接用 `dataHub.signalQuery { it.getXxx() }`
 * 2. **需要缓存** → 在 DataHub 添加专用方法，选择 CacheTTL 常量
 * 3. **跨路由共享** → 使用 ResponseCache.getOrPut / getOrPutAny
 */
class DataHub(
    private val scheduler: DataScheduler,
    private val signalClient: GoformSignalClient,
    private val wifiClient: GoformWifiClient,
    private val responseCache: ResponseCache
) {
    companion object {
        private const val TAG = "DataHub"
    }

    // ═══════════════════════════════════════════════════════════
    // 第一部分：实时缓存（DataScheduler 周期采集）
    // ═══════════════════════════════════════════════════════════

    /** 信号信息（RSRP/SINR/RSRQ/RSSI/RAT/运营商），DataScheduler 3s 周期 */
    suspend fun getSignalInfo(): Map<String, Any> = scheduler.getSignalInfo()

    /** 实时网速（bytes/s），DataScheduler 3s 周期 */
    val latestTraffic get() = scheduler.latestTraffic.value

    /** Goform 月流量（rxBytes, txBytes），DataScheduler 60s 周期 */
    val goformTraffic get() = scheduler.goformTraffic.value

    /** CPU 信息，DataScheduler 2s 周期 */
    val latestCpu get() = scheduler.latestCpu.value

    /** 电池信息，DataScheduler 30s 周期 */
    val latestBattery get() = scheduler.latestBattery.value

    // ═══════════════════════════════════════════════════════════
    // 第二部分：按需缓存（带 TTL 的专用方法）
    // ═══════════════════════════════════════════════════════════

    /**
     * [ResponseCache getOrPutAny 30s] 网络类型信息（运营商、网络制式）—【可缓存数据】
     * 供 NetworkRoutes /status 和 DeviceRoutes /info 共用。
     * goform HTTP 查询可能因路由器固件无响应导致长时间阻塞，使用 withTimeout 保证 5s 内返回。
     */
    suspend fun getNetworkTypeInfo(): GoformNetworkInfo {
        return responseCache.getOrPutAny("hub:network-type-info", 30_000L) {
            val raw = try {
                kotlinx.coroutines.withTimeout(5_000L) {
                    signalClient.getSignalInfo()
                }
            } catch (_: Exception) {
                AppLogger.w("DataHub", "goform network-type-info query timeout")
                null
            }
            GoformNetworkInfo(
                networkType = raw?.get("network_type")?.jsonPrimitive?.contentOrNull ?: "",
                networkProvider = raw?.get("network_provider")?.jsonPrimitive?.contentOrNull ?: "",
                pppStatus = raw?.get("ppp_status")?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    /**
     * [ResponseCache 5min] 设备身份信息（msisdn/IMEI/IMSI/ICCID/版本号等 20 字段）
     */
    suspend fun getDeviceIdentity(): JsonObject? {
        val result = responseCache.getOrPut("device:identity", CacheTTL.DEVICE_IDENTITY) {
            signalClient.getDeviceIdentity() ?: JsonObject(emptyMap())
        }
        return when {
            result is JsonObject && result.isNotEmpty() -> result
            else -> null
        }
    }

    /**
     * [ResponseCache 30s] WiFi 设置（合并扁平字段 + module-info）
     * 需要传入 base64 解码器（路由持有 goformClient::base64Decode 引用）
     */
    suspend fun getWifiSettingsMerged(base64Decoder: (String) -> String): JsonObject {
        val result = responseCache.getOrPut("wifi:settings", CacheTTL.WIFI_SETTINGS) {
            val settings = wifiClient.getWifiSettings()
            val moduleInfo = try { wifiClient.getWifiModuleInfo() } catch (e: Exception) {
                AppLogger.w(TAG, "getWifiModuleInfo failed: ${e.message}"); null
            }
            val merged = mutableMapOf<String, JsonElement>()
            settings?.forEach { (k, v) -> merged[k] = v }

            // 扁平字段 base64 密码解码
            merged["wifi_chip1_ssid1_passphrase"]?.let { v ->
                val raw = v.jsonPrimitive?.contentOrNull
                if (!raw.isNullOrBlank()) merged["wifi_chip1_ssid1_passphrase"] = JsonPrimitive(base64Decoder(raw))
            }
            processModuleInfo(moduleInfo, merged, base64Decoder)
            JsonObject(merged)
        }
        @Suppress("UNCHECKED_CAST")
        return result as JsonObject
    }

    // ═══════════════════════════════════════════════════════════
    // 第三部分：通用透传查询（替代 13 个独立 pass-through 方法）
    // ═══════════════════════════════════════════════════════════

    /**
     * 统一 goform 信号查询入口 — 直接代理到 [GoformSignalClient]。
     *
     * 用法：
     * ```kotlin
     * val status = dataHub.signalQuery { getFullStatus() }
     * val pin    = dataHub.signalQuery { getSimPinStatus() }
     * ```
     *
     * 适用于：getFullStatus, getSimPinStatus, getLanSettings,
     * queryDeviceAccessControlList, getDeviceInfo, getDeviceVersion,
     * getCellInfo, getNeighborCellInfo, getDataUsage, getApnConfig,
     * getBandLockStatus, queryDeviceSettings
     */
    suspend fun <T> signalQuery(block: suspend GoformSignalClient.() -> T): T = signalClient.block()

    /**
     * 统一 WiFi 查询入口 — 直接代理到 [GoformWifiClient]。
     *
     * 用法：
     * ```kotlin
     * val clients = dataHub.wifiQuery { getConnectedClients() }
     * ```
     */
    suspend fun <T> wifiQuery(block: suspend GoformWifiClient.() -> T): T = wifiClient.block()

    // ═══════════════════════════════════════════════════════════
    // 第四部分：缓存失效
    // ═══════════════════════════════════════════════════════════

    /** WiFi 变更后调用（路由 handler 内 suspend 上下文） */
    suspend fun invalidateWifi() {
        responseCache.invalidate("wifi:*")
    }

    /** 设备设置变更后调用 */
    suspend fun invalidateDeviceSettings() {
        responseCache.invalidate("device:settings")
    }

    /** 网络变更后调用 */
    suspend fun invalidateNetwork() {
        responseCache.invalidateAny("hub:network-type-info")
        responseCache.invalidate("network:*")
    }

    // ═══════════════════════════════════════════════════════════
    // 内部：module-info 解析
    // ═══════════════════════════════════════════════════════════

    private fun processModuleInfo(
        moduleInfo: JsonObject?,
        merged: MutableMap<String, JsonElement>,
        base64Decoder: (String) -> String
    ) {
        if (moduleInfo == null) {
            AppLogger.w(TAG, "moduleInfo is null, relying on flat fields only")
            return
        }
        moduleInfo["WiFiModuleSwitch"]?.let { merged["WiFiModuleSwitch"] = it }
        val responseList = moduleInfo["ResponseList"]?.jsonArray ?: return
        AppLogger.i(TAG, "ResponseList size=${responseList.size}")

        val allAps = responseList.mapNotNull { try { it.jsonObject } catch (_: Exception) { null } }
        if (allAps.isEmpty()) return

        val activeAp = allAps.firstOrNull { it["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull == "1" }
            ?: allAps.firstOrNull()

        if (activeAp != null) {
            activeAp["SSID"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_chip1_ssid1_ssid"] = JsonPrimitive(it) }
            val apPwd = activeAp["Password"]?.jsonPrimitive?.contentOrNull?.takeIf { !it.isNullOrBlank() }
                ?: allAps.firstNotNullOfOrNull { it["Password"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() } }
            if (!apPwd.isNullOrBlank()) merged["wifi_chip1_ssid1_passphrase"] = JsonPrimitive(base64Decoder(apPwd))
            activeAp["ChipIndex"]?.jsonPrimitive?.contentOrNull?.let { idx ->
                merged["wifi_chip"] = JsonPrimitive(if (idx == "0") "chip1" else "chip2")
            }
            activeAp["AuthMode"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_chip1_ssid1_auth_mode"] = JsonPrimitive(it) }
            activeAp["EncrypType"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_chip1_ssid1_encryp_type"] = JsonPrimitive(it) }
            activeAp["ApMaxStationNumber"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_chip1_ssid1_max_sta_num"] = JsonPrimitive(it) }
            activeAp["ApBroadcastDisabled"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_chip1_ssid1_broadcast_ssid"] = JsonPrimitive(it) }
        }

        allAps.forEach { ap ->
            val chipIdx = ap["ChipIndex"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val prefix = if (chipIdx == "0") "chip1" else "chip2"
            ap["SSID"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_${prefix}_ssid"] = JsonPrimitive(it) }
            ap["Password"]?.jsonPrimitive?.contentOrNull?.let { b64 ->
                if (b64.isNotBlank()) merged["wifi_${prefix}_passphrase"] = JsonPrimitive(base64Decoder(b64))
            }
            ap["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull?.let { merged["wifi_${prefix}_active"] = JsonPrimitive(it == "1") }
        }
    }
}
