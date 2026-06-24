package com.ufi_axis.data.repository

import com.google.gson.JsonElement
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*

/** WiFi · LAN/DHCP · 设备控制 · 设备设置 */
class WifiLanRepository(private val api: UfiAxisApi) {
    // WiFi
    suspend fun setWifiSsid(ssid: String, password: String? = null) = api.setWifiSsid(WifiSsidRequest(ssid, password))
    suspend fun setWifiPassword(password: String, encryption: String = "WPA2-PSK") =
        api.setWifiPassword(WifiPasswordRequest(password, encryption))
    suspend fun setWifiConfig(config: Map<String, @JvmSuppressWildcards Any>) = api.setWifiConfig(config)
    suspend fun setWifiAdvConfig(config: Map<String, @JvmSuppressWildcards Any>) = api.setWifiAdvConfig(config)
    suspend fun setWifiPower(level: Int) = api.setWifiPower(mapOf("level" to level))
    suspend fun setWifiGuest(config: Map<String, @JvmSuppressWildcards Any>) = api.setWifiGuest(config)
    suspend fun setWifiEnabled(enabled: Boolean) = api.setWifiEnabled(mapOf("enabled" to enabled))
    suspend fun setWifiNfc(enabled: Boolean) = api.setWifiNfc(mapOf("enabled" to enabled))
    suspend fun setWifiSleep(time: String) = api.setWifiSleep(mapOf("time" to time))
    suspend fun switchWifiChip(chip: String) = api.switchWifiChip(mapOf("chip" to chip))
    suspend fun getWifiSettings(): JsonElement = api.getWifiSettings()
    suspend fun getWifiClients(): JsonElement = api.getWifiClients()

    // LAN & DHCP
    suspend fun getLanSettings(): JsonElement = api.getLanSettings()
    suspend fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String, dhcpStart: String, dhcpEnd: String, dhcpLease: String) =
        api.setDhcpSetting(mapOf("lan_ip" to lanIp, "lan_netmask" to lanNetmask, "dhcp_type" to dhcpType, "dhcp_start" to dhcpStart, "dhcp_end" to dhcpEnd, "dhcp_lease" to dhcpLease))

    // Access Control
    suspend fun getAccessControl(): JsonElement = api.getAccessControl()
    suspend fun setAccessControl(aclMode: String, macList: String = "", nameList: String = "") =
        api.setAccessControl(mapOf("acl_mode" to aclMode, "mac_list" to macList, "name_list" to nameList))

    // Device Control
    suspend fun rebootDevice() = api.rebootDevice()
    suspend fun factoryReset() = api.factoryReset()
    suspend fun shutdownDevice() = api.shutdownDevice()
    suspend fun setDeviceMode(enabled: Boolean) = api.setDeviceMode(mapOf("enabled" to enabled))
    suspend fun setUsbMode(mode: Int) = api.setUsbMode(mapOf("mode" to mode))
    suspend fun changePassword(oldPwd: String, newPwd: String) =
        api.changePassword(mapOf("old_password" to oldPwd, "new_password" to newPwd))
    suspend fun setHostname(mac: String, hostname: String) =
        api.setHostname(mapOf("mac" to mac, "hostname" to hostname))

    // Device Settings
    suspend fun getDeviceSettings(): JsonElement = api.getDeviceSettings()
    suspend fun getApnConfig(): JsonElement = api.getApnConfig()
    suspend fun setApnConfig(config: Map<String, @JvmSuppressWildcards Any>) = api.setApnConfig(config)
    suspend fun deleteApnProfile(index: Int) = api.deleteApnProfile(index)
    suspend fun switchApn(index: Int) = api.switchApn(mapOf("index" to index))
    suspend fun setTr069Config(config: Map<String, @JvmSuppressWildcards Any>) = api.setTr069Config(config)
    suspend fun setFotaDisabled(enabled: Boolean) = api.setFotaDisabled(mapOf("enabled" to enabled))
    suspend fun setPerformanceMode(mode: String) = api.setPerformanceMode(mapOf("mode" to mode))
    suspend fun setLedEnabled(enabled: Boolean) = api.setLedEnabled(mapOf("enabled" to enabled))
    suspend fun setRoamingEnabled(enabled: Boolean) = api.setRoamingEnabled(mapOf("enabled" to enabled))
    suspend fun setUsbTethering(enabled: Boolean) = api.setUsbTethering(mapOf("enabled" to enabled))
    suspend fun setSambaSetting(enabled: Boolean) = api.setSambaSetting(mapOf("enabled" to enabled))
    suspend fun setRestartSchedule(enabled: Boolean, time: String) =
        api.setRestartSchedule(mapOf("enabled" to enabled, "time" to time))
}
