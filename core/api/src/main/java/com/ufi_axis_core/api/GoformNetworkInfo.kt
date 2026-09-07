package com.ufi_axis_core.api

/**
 * 网络类型信息 — DataHub 统一返回格式
 *
 * 供 NetworkRoutes /status 和 DeviceRoutes /info 共用，
 * 通过 DataHub.getNetworkTypeInfo() 获取（内含 10s TTL 缓存）。
 */
data class GoformNetworkInfo(
    val networkType: String,
    val networkProvider: String,
    val pppStatus: String
) {
    val isPppConnected: Boolean get() =
        pppStatus.contains("connected", ignoreCase = true) &&
        !pppStatus.contains("disconnected", ignoreCase = true)
}
