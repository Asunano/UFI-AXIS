package com.ufi_axis.data.repository

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*

class UfiAxisRepository(private val api: UfiAxisApi) {

    // ========== Health ==========
    suspend fun checkHealth() = api.getHealth()

    // ========== Device ==========
    suspend fun getDeviceInfo() = api.getDeviceInfo()
    suspend fun getDeviceModel() = api.getDeviceModel()
    suspend fun getMagiskStatus() = api.getMagiskStatus()

    // ========== System ==========
    suspend fun getCpuInfo() = api.getCpuInfo()
    suspend fun getCpuHistory(hours: Int = 24) = api.getCpuHistory(hours)
    suspend fun getMemoryInfo() = api.getMemoryInfo()
    suspend fun getBatteryInfo() = api.getBatteryInfo()
    suspend fun getStorageInfo() = api.getStorageInfo()
    suspend fun getUptime() = api.getUptime()

    // ========== Traffic ==========
    suspend fun getTrafficRealtime() = api.getTrafficRealtime()
    suspend fun getTrafficHistory(hours: Int = 24) = api.getTrafficHistory(hours)
    suspend fun getTrafficSummary() = api.getTrafficSummary()

    // ========== Network ==========
    suspend fun getSignalInfo() = api.getSignalInfo()
    suspend fun getSignalHistory(hours: Int = 24) = api.getSignalHistory(hours)
    suspend fun getNetworkStatus() = api.getNetworkStatus()
    suspend fun setMobileData(enabled: Boolean) = api.setMobileData(mapOf("enabled" to enabled))
    suspend fun setAirplaneMode(enabled: Boolean) = api.setAirplaneMode(mapOf("enabled" to enabled))
    suspend fun setBandLock(rat: String, bands: String, action: String = "lock") =
        api.setBandLock(mapOf("rat" to rat, "bands" to bands, "action" to action))
    suspend fun getBandStatus() = api.getBandStatus()
    suspend fun setNetworkMode(mode: String) = api.setNetworkMode(ModeRequest(mode))

    // ========== SIM / SMS ==========
    suspend fun getSimInfo() = api.getSimInfo()
    suspend fun sendSms(phone: String, message: String) = api.sendSms(SmsSendRequest(phone, message))
    suspend fun getSmsList(limit: Int = 100) = api.getSmsList(limit)
    suspend fun sendUssd(code: String) = api.sendUssd(UssdRequest(code))

    // ========== AT ==========
    suspend fun sendAtCommand(command: String) = api.sendAtCommand(AtCommandRequest(command))
    suspend fun getAtStatus() = api.getAtStatus()

    // ========== Alerts ==========
    suspend fun getAlertConfig() = api.getAlertConfig()
    suspend fun updateAlertConfig(config: AlertConfig) = api.updateAlertConfig(config)
    suspend fun getAlertList(limit: Int = 20) = api.getAlertList(limit)
    suspend fun ackAlert(id: Long) = api.ackAlert(AckRequest(id))

    // ========== WiFi ==========
    suspend fun setWifiSsid(ssid: String, password: String? = null) =
        api.setWifiSsid(WifiSsidRequest(ssid, password))
    suspend fun setWifiPassword(password: String, encryption: String = "WPA2-PSK") =
        api.setWifiPassword(WifiPasswordRequest(password, encryption))
    suspend fun setWifiConfig(config: Map<String, Any>) =
        api.setWifiConfig(config)
    suspend fun setWifiAdvConfig(config: Map<String, Any>) =
        api.setWifiAdvConfig(config)
    suspend fun setWifiPower(level: Int) =
        api.setWifiPower(mapOf("level" to level))
    suspend fun setWifiGuest(config: Map<String, Any>) =
        api.setWifiGuest(config)
    suspend fun getWifiSettings() = api.getWifiSettings()
    suspend fun getWifiClients() = api.getWifiClients()

    // ========== Device Control ==========
    suspend fun rebootDevice() = api.rebootDevice()
    suspend fun factoryReset() = api.factoryReset()
    suspend fun setDeviceMode(enabled: Boolean) = api.setDeviceMode(mapOf("enabled" to enabled))
    suspend fun setUsbMode(mode: Int) = api.setUsbMode(mapOf("mode" to mode))
    suspend fun changePassword(oldPwd: String, newPwd: String) =
        api.changePassword(mapOf("old_password" to oldPwd, "new_password" to newPwd))
    suspend fun setApnConfig(config: Map<String, Any>) =
        api.setApnConfig(config)
    suspend fun setTr069Config(config: Map<String, Any>) =
        api.setTr069Config(config)
    suspend fun getDeviceSettings() = api.getDeviceSettings()
    suspend fun getLanSettings() = api.getLanSettings()
    suspend fun getAccessControl() = api.getAccessControl()
    suspend fun setAccessControl(aclMode: String, macList: String = "", nameList: String = "") =
        api.setAccessControl(mapOf("acl_mode" to aclMode, "mac_list" to macList, "name_list" to nameList))

    // ========== Network Control (new) ==========
    suspend fun setBearerPreference(preference: String) =
        api.setBearerPreference(mapOf("preference" to preference))
    suspend fun connectNetwork() = api.connectNetwork()
    suspend fun disconnectNetwork() = api.disconnectNetwork()
    suspend fun setConnectionMode(mode: String) =
        api.setConnectionMode(mapOf("mode" to mode))
    suspend fun getCellInfo() = api.getCellInfo()

    // ========== SMS / SIM Actions ==========
    suspend fun deleteSms(id: String) = api.deleteSms(mapOf("id" to id))
    suspend fun markSmsRead(id: String) = api.markSmsRead(mapOf("id" to id))
    suspend fun switchSimSlot(slot: Int) = api.switchSimSlot(mapOf("slot" to slot))

    // ========== Config ==========
    suspend fun getConfig() = api.getConfig()
    suspend fun updateConfig(config: Map<String, Any>) = api.updateConfig(config)
    suspend fun resetConfig() = api.resetConfig()
    suspend fun getServerVersion() = api.getServerVersion()

    // ========== App Management ==========
    suspend fun getAppList(filter: String = "user") = api.getAppList(filter)
    suspend fun getAppDetail(pkg: String) = api.getAppDetail(pkg)
    suspend fun installApp(path: String) = api.installApp(AppInstallRequest(path))
    suspend fun installAppFromUrl(url: String) = api.installAppFromUrl(AppInstallUrlRequest(url))
    suspend fun uninstallApp(pkg: String) = api.uninstallApp(AppActionRequest(pkg))
    suspend fun disableApp(pkg: String) = api.disableApp(AppActionRequest(pkg))
    suspend fun enableApp(pkg: String) = api.enableApp(AppActionRequest(pkg))
    suspend fun clearAppData(pkg: String) = api.clearAppData(AppActionRequest(pkg))
    suspend fun forceStopApp(pkg: String) = api.forceStopApp(AppActionRequest(pkg))
    suspend fun managePermission(pkg: String, perm: String, grant: Boolean) = api.managePermission(AppPermissionRequest(pkg, perm, grant))
    suspend fun freezeApp(pkg: String) = api.freezeApp(AppActionRequest(pkg))
    suspend fun unfreezeApp(pkg: String) = api.unfreezeApp(AppActionRequest(pkg))

    // ========== ADB ==========
    suspend fun getAdbStatus() = api.getAdbStatus()
    suspend fun startAdb(port: Int = 5555) = api.startAdb(mapOf("port" to port))
    suspend fun stopAdb() = api.stopAdb()

    // ========== SMS Forward ==========
    suspend fun getSmsForwardConfig() = api.getSmsForwardConfig()
    suspend fun saveSmsForwardConfig(config: SmsForwardConfig) = api.saveSmsForwardConfig(config)
    suspend fun testSmsForward() = api.testSmsForward()

    // ========== Scheduled Tasks ==========
    suspend fun getTaskList() = api.getTaskList()
    suspend fun getTask(id: String) = api.getTask(id)
    suspend fun createTask(task: ScheduledTask) = api.createTask(task)
    suspend fun updateTask(id: String, task: ScheduledTask) = api.updateTask(id, task)
    suspend fun deleteTask(id: String) = api.deleteTask(id)
    suspend fun clearTasks() = api.clearTasks()

    // ========== Enhanced Device ==========
    suspend fun getThermalZones() = api.getThermalZones()
    suspend fun getConnectionCounts() = api.getConnectionCounts()
    suspend fun getDataUsage() = api.getDataUsage()
    suspend fun getTrafficLimit() = api.getTrafficLimit()
    suspend fun setDataLimit(body: Map<String, Any>) = api.setDataLimit(body)
    suspend fun calibrateFlow(body: Map<String, Any>) = api.calibrateFlow(body)
    suspend fun getSelinuxStatus() = api.getSelinuxStatus()
    suspend fun setFotaDisabled(enabled: Boolean) = api.setFotaDisabled(mapOf("enabled" to enabled))
    suspend fun setPerformanceMode(mode: String) = api.setPerformanceMode(mapOf("mode" to mode))
    suspend fun setLedEnabled(enabled: Boolean) = api.setLedEnabled(mapOf("enabled" to enabled))
    suspend fun setRoamingEnabled(enabled: Boolean) = api.setRoamingEnabled(mapOf("enabled" to enabled))
    suspend fun setUsbTethering(enabled: Boolean) = api.setUsbTethering(mapOf("enabled" to enabled))
    suspend fun setSaMode(enabled: Boolean) = api.setSaMode(mapOf("enabled" to enabled))

    // ========== VoLTE / VoNR ==========
    suspend fun getVolteStatus(slot: Int = 0) = api.getVolteStatus(slot)
    suspend fun setVolteStatus(enabled: Boolean, slot: Int = 0) = api.setVolteStatus(mapOf("enabled" to if (enabled) "1" else "0", "slot" to slot))
    suspend fun getVonrStatus(slot: Int = 0) = api.getVonrStatus(slot)
    suspend fun setVonrStatus(enabled: Boolean, slot: Int = 0) = api.setVonrStatus(mapOf("enabled" to if (enabled) "1" else "0", "slot" to slot))

    // ========== WiFi Enable ==========
    suspend fun setWifiEnabled(enabled: Boolean) = api.setWifiEnabled(mapOf("enabled" to enabled))

    // ========== WiFi Chip Switch ==========
    suspend fun switchWifiChip(chip: String) = api.switchWifiChip(mapOf("chip" to chip))

    // ========== WiFi NFC ==========
    suspend fun setWifiNfc(enabled: Boolean) = api.setWifiNfc(mapOf("enabled" to enabled))

    // ========== WiFi Sleep ==========
    suspend fun setWifiSleep(time: String) = api.setWifiSleep(mapOf("time" to time))

    // ========== SAMBA ==========
    suspend fun setSambaSetting(enabled: Boolean) = api.setSambaSetting(mapOf("enabled" to enabled))

    // ========== Cell Lock ==========
    suspend fun cellLock(pci: String, earfcn: String, rat: String) =
        api.cellLock(mapOf("pci" to pci, "earfcn" to earfcn, "rat" to rat))
    suspend fun unlockAllCell() = api.unlockAllCell()

    // ========== Shutdown ==========
    suspend fun shutdownDevice() = api.shutdownDevice()

    // ========== Restart Schedule ==========
    suspend fun setRestartSchedule(enabled: Boolean, time: String) =
        api.setRestartSchedule(mapOf("enabled" to enabled, "time" to time))

    // ========== Hostname ==========
    suspend fun setHostname(mac: String, hostname: String) =
        api.setHostname(mapOf("mac" to mac, "hostname" to hostname))

    // ========== DHCP ==========
    suspend fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String, dhcpStart: String, dhcpEnd: String, dhcpLease: String) =
        api.setDhcpSetting(mapOf("lan_ip" to lanIp, "lan_netmask" to lanNetmask, "dhcp_type" to dhcpType, "dhcp_start" to dhcpStart, "dhcp_end" to dhcpEnd, "dhcp_lease" to dhcpLease))

    // ========== Speed Test ==========
    suspend fun speedTest(chunks: Int = 10) = api.speedTest(chunks)

    // ========== Debug Logs ==========
    suspend fun getDebugLogs(level: String? = null, limit: Int = 200) = api.getDebugLogs(level, limit)
    suspend fun clearDebugLogs() = api.clearDebugLogs()

    // ========== File Management ==========
    suspend fun listFiles(path: String, force: Boolean = false) = api.listFiles(path, force)
    suspend fun getFileInfo(path: String) = api.getFileInfo(path)
    suspend fun readFile(path: String) = api.readFile(mapOf("path" to path))
    suspend fun writeFile(path: String, content: String) = api.writeFile(mapOf("path" to path, "content" to content))
    suspend fun deleteFile(path: String) = api.deleteFile(mapOf("path" to path))
    suspend fun renameFile(oldPath: String, newPath: String) = api.renameFile(mapOf("old_path" to oldPath, "new_path" to newPath))
    suspend fun moveFile(source: String, destination: String) = api.moveFile(mapOf("source" to source, "destination" to destination))
    suspend fun copyFile(source: String, destination: String) = api.copyFile(mapOf("source" to source, "destination" to destination))
    suspend fun createDirectory(path: String) = api.createDirectory(mapOf("path" to path))
    suspend fun searchFiles(path: String, query: String, depth: Int = 3) = api.searchFiles(path, query, depth)
    suspend fun getDiskUsage() = api.getDiskUsage()
    suspend fun chmodFile(path: String, mode: String) = api.chmodFile(mapOf("path" to path, "mode" to mode))
    suspend fun touchFile(path: String) = api.touchFile(mapOf("path" to path))

    // ========== Advanced Tools ==========
    suspend fun getTtydStatus() = api.getTtydStatus()
    suspend fun startTtyd() = api.startTtyd()
    suspend fun stopTtyd() = api.stopTtyd()
    suspend fun getIperf3Status() = api.getIperf3Status()
    suspend fun startIperf3() = api.startIperf3()
    suspend fun stopIperf3() = api.stopIperf3()
    suspend fun getFotaStatus() = api.getFotaStatus()
    suspend fun disableFotaAdvanced() = api.disableFotaAdvanced()
    suspend fun getCpuCores() = api.getCpuCores()
    suspend fun setCpuCores(enable: Boolean) = api.setCpuCores(mapOf("enable" to enable))
    suspend fun netAccelerate() = api.netAccelerate()
    suspend fun disablePhantomKiller() = api.disablePhantomKiller()
    suspend fun getBandwidthLimit() = api.getBandwidthLimit()
    suspend fun setBandwidthLimit(mbit: String) = api.setBandwidthLimit(mapOf("mbit" to mbit))
    suspend fun removeBandwidthLimit() = api.removeBandwidthLimit()
    suspend fun getCellularUsage(start: Long? = null, end: Long? = null) = api.getCellularUsage(start, end)

    // ========== Monitor ==========
    suspend fun getMonitorHistory(type: String, hours: Int, points: Int = 360) =
        api.getMonitorHistory(type, hours, points)
    suspend fun getMonitorStorage() = api.getMonitorStorage()
    suspend fun cleanHistory(type: String? = null, days: Int? = null) =
        api.cleanHistory(CleanHistoryRequest(type, days))
}
