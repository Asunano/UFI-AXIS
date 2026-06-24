package com.ufi_axis.data.api

import com.google.gson.JsonElement
import com.ufi_axis.data.model.*
import okhttp3.ResponseBody
import retrofit2.http.*

interface UfiAxisApi {

    // ========== Health ==========
    @GET("health")
    suspend fun getHealth(): HealthResponse

    // ========== Device ==========
    @GET("api/device/info")
    suspend fun getDeviceInfo(): DeviceInfoResponse

    @GET("api/device/identity")
    suspend fun getDeviceIdentity(): Map<String, String>

    @GET("api/device/version")
    suspend fun getDeviceVersion(): DeviceVersionResponse

    @GET("api/device/model")
    suspend fun getDeviceModel(): DeviceModel

    @GET("api/device/magisk")
    suspend fun getMagiskStatus(): MagiskStatus

    // ========== System ==========
    @GET("api/system/cpu")
    suspend fun getCpuInfo(): CpuInfo

    @GET("api/system/cpu/history")
    suspend fun getCpuHistory(@Query("hours") hours: Int = 24): CpuHistoryResponse

    @GET("api/system/memory")
    suspend fun getMemoryInfo(): MemoryInfo

    @GET("api/system/battery")
    suspend fun getBatteryInfo(): BatteryInfo

    @GET("api/system/storage")
    suspend fun getStorageInfo(): StorageInfo

    @GET("api/system/uptime")
    suspend fun getUptime(): UptimeInfo

    @GET("api/system/root-check")
    suspend fun checkRootAccess(): RootCheckResponse

    // ========== Traffic ==========
    @GET("api/traffic/realtime")
    suspend fun getTrafficRealtime(): TrafficRealtime

    @GET("api/traffic/history")
    suspend fun getTrafficHistory(@Query("hours") hours: Int = 24): TrafficHistoryResponse

    @GET("api/traffic/summary")
    suspend fun getTrafficSummary(): TrafficSummary

    // ========== Network ==========
    @GET("api/network/signal")
    suspend fun getSignalInfo(): SignalInfo

    @GET("api/network/signal/history")
    suspend fun getSignalHistory(@Query("hours") hours: Int = 24): SignalHistoryResponse

    @GET("api/network/status")
    suspend fun getNetworkStatus(): NetworkStatusResponse

    @POST("api/network/data")
    suspend fun setMobileData(@Body body: Map<String, Boolean>): EnabledResponse

    @POST("api/network/airplane")
    suspend fun setAirplaneMode(@Body body: Map<String, Boolean>): AirplaneResponse

    @POST("api/network/band")
    suspend fun setBandLock(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // 查询当前频段锁定状态
    @GET("api/network/band-status")
    suspend fun getBandStatus(): JsonElement

    @POST("api/network/mode")
    suspend fun setNetworkMode(@Body body: ModeRequest): ModeResponse

    // ========== SIM / SMS ==========
    @GET("api/sim/info")
    suspend fun getSimInfo(): SimInfoResponse

    @POST("api/sms/send")
    suspend fun sendSms(@Body body: SmsSendRequest): SmsSendResponse

    @GET("api/sms/list")
    suspend fun getSmsList(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("phone") phone: String? = null
    ): SmsListResponse

    @GET("api/sms/contacts")
    suspend fun getSmsContacts(): SmsContactListResponse

    // ========== Shell ==========
    @POST("api/shell/exec")
    suspend fun shellExec(@Body body: ShellExecRequest): ShellExecResponse

    // ========== AT ==========
    @POST("api/at/command")
    suspend fun sendAtCommand(@Body body: AtCommandRequest): AtCommandResponse

    @GET("api/at/status")
    suspend fun getAtStatus(): AtStatusResponse

    @GET("api/at/platform")
    suspend fun getPlatformInfo(): PlatformInfo

    // ========== Alerts ==========
    @GET("api/alerts/config")
    suspend fun getAlertConfig(): AlertConfig

    @PUT("api/alerts/config")
    suspend fun updateAlertConfig(@Body body: AlertConfig): AlertConfigUpdateResponse

    @GET("api/alerts/list")
    suspend fun getAlertList(@Query("limit") limit: Int = 20): AlertListResponse

    @POST("api/alerts/ack")
    suspend fun ackAlert(@Body body: AckRequest): SuccessResponse

    // ========== WiFi ==========
    @POST("api/wifi/ssid")
    suspend fun setWifiSsid(@Body body: WifiSsidRequest): SuccessResponse

    @POST("api/wifi/password")
    suspend fun setWifiPassword(@Body body: WifiPasswordRequest): SuccessResponse

    @POST("api/wifi/config")
    suspend fun setWifiConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/wifi/adv-config")
    suspend fun setWifiAdvConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/wifi/power")
    suspend fun setWifiPower(@Body body: Map<String, Int>): SuccessResponse

    @POST("api/wifi/guest")
    suspend fun setWifiGuest(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @GET("api/wifi/settings")
    suspend fun getWifiSettings(): WifiSettingsResponse

    @GET("api/wifi/module-info")
    suspend fun getWifiModuleInfo(): JsonElement

    @GET("api/wifi/clients")
    suspend fun getWifiClients(): WifiClientsResponse

    // ========== Device Control ==========
    @POST("api/device/reboot")
    suspend fun rebootDevice(): DeviceControlResponse

    @POST("api/device/factory-reset")
    suspend fun factoryReset(): DeviceControlResponse

    @POST("api/device/debug")
    suspend fun setDeviceMode(@Body body: Map<String, Boolean>): DeviceDebugResponse

    @POST("api/device/usb-mode")
    suspend fun setUsbMode(@Body body: Map<String, Int>): UsbModeResponse

    @POST("api/device/password")
    suspend fun changePassword(@Body body: Map<String, String>): SuccessResponse

    @POST("api/device/apn")
    suspend fun setApnConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/tr069")
    suspend fun setTr069Config(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // 查询设备设置状态（LED、性能模式、漫游、Bearer 等）
    @GET("api/device/settings")
    suspend fun getDeviceSettings(): JsonElement

    @DELETE("api/device/apn/{index}")
    suspend fun deleteApnProfile(@Path("index") index: Int): SuccessResponse

    @POST("api/device/apn/switch")
    suspend fun switchApn(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/data-limit")
    suspend fun setDataLimit(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/flow-calibration")
    suspend fun calibrateFlow(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @GET("api/device/traffic-limit")
    suspend fun getTrafficLimit(): JsonElement

    @GET("api/device/apn")
    suspend fun getApnConfig(): JsonElement

    @GET("api/device/sim-pin")
    suspend fun getSimPinStatus(): JsonElement

    @GET("api/device/lan-settings")
    suspend fun getLanSettings(): JsonElement

    @GET("api/device/access-control")
    suspend fun getAccessControl(): JsonElement

    @POST("api/device/access-control")
    suspend fun setAccessControl(@Body body: Map<String, String>): SuccessResponse

    // ========== Network Control (new) ==========
    @POST("api/network/bearer")
    suspend fun setBearerPreference(@Body body: Map<String, String>): BearerPreferenceResponse

    @POST("api/network/connect")
    suspend fun connectNetwork(): DeviceControlResponse

    @POST("api/network/disconnect")
    suspend fun disconnectNetwork(): DeviceControlResponse

    @POST("api/network/connection-mode")
    suspend fun setConnectionMode(@Body body: Map<String, String>): ConnectionModeResponse

    @GET("api/network/cell-info")
    suspend fun getCellInfo(): JsonElement

    // ========== SMS / SIM Actions ==========
    @POST("api/sms/delete")
    suspend fun deleteSms(@Body body: Map<String, String>): SmsActionResponse

    @POST("api/sms/read")
    suspend fun markSmsRead(@Body body: Map<String, String>): SmsActionResponse

    @POST("api/sim/switch")
    suspend fun switchSimSlot(@Body body: Map<String, Int>): SimSwitchResponse

    // ========== Config ==========
    @GET("api/config/version")
    suspend fun getServerVersion(): ServerVersionInfo

    @GET("api/config")
    suspend fun getConfig(): AppConfig

    @PUT("api/config")
    suspend fun updateConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): ConfigUpdateResponse

    @POST("api/config/reset")
    suspend fun resetConfig(): ConfigResetResponse

    // ========== App Management ==========
    @GET("api/apps")
    suspend fun getAppList(@Query("filter") filter: String = "user"): AppListResponse

    @GET("api/apps/{packageName}")
    suspend fun getAppDetail(@Path("packageName") packageName: String): AppDetailResponse

    @POST("api/apps/install")
    suspend fun installApp(@Body body: AppInstallRequest): AppInstallResponse

    @POST("api/apps/install-url")
    suspend fun installAppFromUrl(@Body body: AppInstallUrlRequest): AppInstallResponse

    @POST("api/apps/uninstall")
    suspend fun uninstallApp(@Body body: AppActionRequest): AppInstallResponse

    @POST("api/apps/disable")
    suspend fun disableApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/enable")
    suspend fun enableApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/clear")
    suspend fun clearAppData(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/force-stop")
    suspend fun forceStopApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/permission")
    suspend fun managePermission(@Body body: AppPermissionRequest): AppPermissionResponse

    @POST("api/apps/freeze")
    suspend fun freezeApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/unfreeze")
    suspend fun unfreezeApp(@Body body: AppActionRequest): AppActionResponse

    // ========== ADB ==========
    @GET("api/adb/status")
    suspend fun getAdbStatus(): AdbStatus

    @POST("api/adb/start")
    suspend fun startAdb(@Body body: Map<String, Int>): SuccessResponse

    @POST("api/adb/stop")
    suspend fun stopAdb(): SuccessResponse

    // ADB auto-start on boot
    @GET("api/adb/auto-start")
    suspend fun getAdbAutoStart(): JsonElement

    @POST("api/adb/auto-start")
    suspend fun setAdbAutoStart(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== SMS Forward ==========
    @GET("api/sms-forward/config")
    suspend fun getSmsForwardConfig(): SmsForwardConfig

    @POST("api/sms-forward/config")
    suspend fun saveSmsForwardConfig(@Body body: SmsForwardConfig): SmsForwardSaveResponse

    @POST("api/sms-forward/test")
    suspend fun testSmsForward(): com.google.gson.JsonElement

    // ========== Scheduled Tasks ==========
    @GET("api/tasks")
    suspend fun getTaskList(): TaskListResponse

    @GET("api/tasks/{id}")
    suspend fun getTask(@Path("id") id: String): ScheduledTask

    @POST("api/tasks")
    suspend fun createTask(@Body body: ScheduledTask): TaskCreateResponse

    @PUT("api/tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: ScheduledTask): SuccessResponse

    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): SuccessResponse

    @POST("api/tasks/clear")
    suspend fun clearTasks(): SuccessResponse

    // ========== Enhanced Device ==========
    @GET("api/device/thermal")
    suspend fun getThermalZones(): JsonElement

    @GET("api/device/connections")
    suspend fun getConnectionCounts(): JsonElement

    @GET("api/device/data-usage")
    suspend fun getDataUsage(): JsonElement

    @GET("api/device/selinux")
    suspend fun getSelinuxStatus(): JsonElement

    @POST("api/device/fota")
    suspend fun setFotaDisabled(@Body body: Map<String, Boolean>): SuccessResponse

    @POST("api/device/performance")
    suspend fun setPerformanceMode(@Body body: Map<String, String>): SuccessResponse

    @POST("api/device/led")
    suspend fun setLedEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    @POST("api/device/roaming")
    suspend fun setRoamingEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    @POST("api/device/usb-tether")
    suspend fun setUsbTethering(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== WiFi Enable ==========
    @POST("api/wifi/enable")
    suspend fun setWifiEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== WiFi Chip Switch ==========
    @POST("api/wifi/chip")
    suspend fun switchWifiChip(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== WiFi NFC ==========
    @POST("api/wifi/nfc")
    suspend fun setWifiNfc(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== WiFi Sleep ==========
    @POST("api/wifi/sleep")
    suspend fun setWifiSleep(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== SAMBA ==========
    @POST("api/device/samba")
    suspend fun setSambaSetting(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== Cell Lock ==========
    @POST("api/device/cell-lock")
    suspend fun cellLock(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/cell-unlock")
    suspend fun unlockAllCell(): SuccessResponse

    // ========== Shutdown ==========
    @POST("api/device/shutdown")
    suspend fun shutdownDevice(): DeviceControlResponse

    // ========== Restart Schedule ==========
    @POST("api/device/restart-schedule")
    suspend fun setRestartSchedule(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== Hostname ==========
    @POST("api/device/hostname")
    suspend fun setHostname(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== Telephony Reset ==========
    @POST("api/device/telephony-reset")
    suspend fun resetTelephony(): ShellExecResponse

    // ========== DHCP ==========
    @POST("api/device/dhcp")
    suspend fun setDhcpSetting(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== Speed Test ==========
    @Streaming
    @GET("api/speedtest")
    suspend fun speedTest(@Query("ckSize") ckSize: Int = 10): ResponseBody

    // ========== Debug Logs ==========
    @GET("api/debug-logs")
    suspend fun getDebugLogs(@Query("level") level: String? = null, @Query("limit") limit: Int = 200): JsonElement

    @HTTP(method = "DELETE", path = "api/debug-logs", hasBody = false)
    suspend fun clearDebugLogs(): JsonElement

    // ========== File Management ==========
    @GET("api/files/list")
    suspend fun listFiles(@Query("path") path: String, @Query("force") force: Boolean = false): FileListResponse

    @GET("api/files/info")
    suspend fun getFileInfo(@Query("path") path: String): FileInfoResponse

    @POST("api/files/read")
    suspend fun readFile(@Body body: Map<String, String>): FileReadResponse

    @POST("api/files/write")
    suspend fun writeFile(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/files/delete")
    suspend fun deleteFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/rename")
    suspend fun renameFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/move")
    suspend fun moveFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/copy")
    suspend fun copyFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/mkdir")
    suspend fun createDirectory(@Body body: Map<String, String>): SuccessResponse

    @GET("api/files/search")
    suspend fun searchFiles(@Query("path") path: String, @Query("query") query: String, @Query("depth") depth: Int = 3): JsonElement

    @GET("api/files/disk-usage")
    suspend fun getDiskUsage(): JsonElement

    @POST("api/files/chmod")
    suspend fun chmodFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/touch")
    suspend fun touchFile(@Body body: Map<String, String>): SuccessResponse

    // ========== Advanced Tools ==========
    @GET("api/advanced/ttyd/status")
    suspend fun getTtydStatus(): JsonElement

    @POST("api/advanced/ttyd/start")
    suspend fun startTtyd(): JsonElement

    @POST("api/advanced/ttyd/stop")
    suspend fun stopTtyd(): JsonElement

    @GET("api/advanced/iperf3/status")
    suspend fun getIperf3Status(): JsonElement

    @POST("api/advanced/iperf3/start")
    suspend fun startIperf3(): JsonElement

    @POST("api/advanced/iperf3/stop")
    suspend fun stopIperf3(): JsonElement

    @GET("api/advanced/fota/status")
    suspend fun getFotaStatus(): JsonElement

    @POST("api/advanced/fota/disable")
    suspend fun disableFotaAdvanced(): JsonElement

    @GET("api/advanced/cpu-cores")
    suspend fun getCpuCores(): JsonElement

    @POST("api/advanced/cpu-cores")
    suspend fun setCpuCores(@Body body: Map<String, @JvmSuppressWildcards Any>): JsonElement

    @POST("api/advanced/net-accelerate")
    suspend fun netAccelerate(): JsonElement

    @POST("api/advanced/disable-phantom-killer")
    suspend fun disablePhantomKiller(): JsonElement

    @GET("api/advanced/bandwidth-limit")
    suspend fun getBandwidthLimit(): JsonElement

    @POST("api/advanced/bandwidth-limit")
    suspend fun setBandwidthLimit(@Body body: Map<String, String>): JsonElement

    @HTTP(method = "DELETE", path = "api/advanced/bandwidth-limit", hasBody = false)
    suspend fun removeBandwidthLimit(): JsonElement

    @GET("api/advanced/cellular-usage")
    suspend fun getCellularUsage(@Query("start") start: Long? = null, @Query("end") end: Long? = null): JsonElement

    // ========== Monitor ==========
    @GET("api/monitor/history")
    suspend fun getMonitorHistory(
        @Query("type") type: String,
        @Query("hours") hours: Int,
        @Query("points") points: Int = 360
    ): MonitorHistoryResponse

    @GET("api/monitor/storage")
    suspend fun getMonitorStorage(): MonitorStorageResponse

    @POST("api/monitor/clean")
    suspend fun cleanHistory(@Body body: CleanHistoryRequest): CleanHistoryResponse
}

data class MagiskStatus(
    val installed: Boolean,
    val version: String
)

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = "",
    val isSymlink: Boolean = false
)

data class FileListResponse(
    val files: List<FileItem>,
    val path: String,
    val parent: String? = null,
    val error: String? = null,
    val message: String? = null
)

data class FileInfoResponse(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String,
    val owner: String = "",
    val group: String = ""
)

data class FileReadResponse(
    val content: String,
    val encoding: String = "utf-8",
    val size: Int
)

data class RootCheckResponse(
    val hasRoot: Boolean
)
