package com.ufi_axis.data.repository

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*

/** 设备 & 系统信息 */
class DeviceRepository(private val api: UfiAxisApi) {
    suspend fun checkHealth() = api.getHealth()
    suspend fun getDeviceInfo() = api.getDeviceInfo()
    suspend fun getDeviceIdentity() = api.getDeviceIdentity()
    suspend fun getDeviceVersion() = api.getDeviceVersion()
    suspend fun getDeviceModel() = api.getDeviceModel()
    suspend fun getMagiskStatus() = api.getMagiskStatus()
    suspend fun getCpuInfo() = api.getCpuInfo()
    suspend fun getCpuHistory(hours: Int = 24) = api.getCpuHistory(hours)
    suspend fun getMemoryInfo() = api.getMemoryInfo()
    suspend fun getBatteryInfo() = api.getBatteryInfo()
    suspend fun getStorageInfo() = api.getStorageInfo()
    suspend fun getUptime() = api.getUptime()
    suspend fun getServerVersion() = api.getServerVersion()
    suspend fun getConfig() = api.getConfig()
    suspend fun updateConfig(config: Map<String, @JvmSuppressWildcards Any>) = api.updateConfig(config)
    suspend fun resetConfig() = api.resetConfig()
}
