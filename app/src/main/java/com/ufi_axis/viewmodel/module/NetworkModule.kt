package com.ufi_axis.viewmodel.module

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ufi_axis.data.repository.NetworkRepository
import com.ufi_axis.data.repository.WifiLanRepository
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NetworkModule(
    private val networkRepo: NetworkRepository,
    private val wifiLanRepo: WifiLanRepository,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    // ── State ──
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _deviceSettingsState = MutableStateFlow(DeviceSettingsState())
    val deviceSettingsState: StateFlow<DeviceSettingsState> = _deviceSettingsState.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestState())
    val speedTestState: StateFlow<SpeedTestState> = _speedTestState.asStateFlow()

    // ── Callbacks ──
    var onDashboardRefresh: (() -> Unit)? = null
    var onDashboardError: ((String?) -> Unit)? = null

    private val gson = Gson()

    // ── Network ──
    fun refreshNetwork() {
        scope.launch {
            _networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)
            try {
                val sig = async { runCatching { networkRepo.getSignalInfo() } }
                val net = async { runCatching { networkRepo.getNetworkStatus() } }
                val sim = async { runCatching { networkRepo.getSimInfo() } }
                val wifi = async { runCatching { wifiLanRepo.getWifiSettings() } }
                val clients = async { runCatching { wifiLanRepo.getWifiClients() } }

                val rSig = sig.await(); val rNet = net.await(); val rSim = sim.await()
                val rWifi = wifi.await(); val rClients = clients.await()

                val failures = listOfNotNull(
                    "信号" to rSig, "网络状态" to rNet, "SIM" to rSim,
                    "WiFi" to rWifi, "客户端" to rClients
                ).filter { it.second.isFailure }.joinToString("; ") { (name, r) ->
                    "$name: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                val wifiSettingsJson = rWifi.getOrNull()
                val netStatus = rNet.getOrNull()
                val wifiParsed = parseWifiSettings(wifiSettingsJson)

                _networkState.value = _networkState.value.copy(
                    signalInfo = rSig.getOrNull(), networkStatus = netStatus,
                    simInfo = rSim.getOrNull(), wifiSettings = wifiSettingsJson,
                    wifiClients = rClients.getOrNull(), wifiEnabled = wifiParsed.isEnabled,
                    mobileDataEnabled = netStatus?.mobile_data ?: false, isLoading = false,
                    errorMessage = failures
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "refreshNetwork failed", e)
                _networkState.value = _networkState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun toggleMobileData(enabled: Boolean) {
        scope.launch {
            _networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)
            try { networkRepo.setMobileData(enabled); delay(1500); refreshNetwork() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "操作失败: ${e.message}", isLoading = false) }
        }
    }

    fun toggleAirplaneMode(enabled: Boolean) {
        scope.launch {
            try {
                val resp = networkRepo.setAirplaneMode(enabled)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "操作失败")
                delay(1000); onDashboardRefresh?.invoke(); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun setNetworkMode(mode: String) {
        scope.launch {
            try {
                val resp = networkRepo.setNetworkMode(mode)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                delay(1000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    /** 统一锁定 LTE+NR 频段（goform + AT+SFUN 网络栈重启，无需设备重启） */
    fun lockBands(lteBands: String?, nrBands: String?) {
        scope.launch {
            try {
                val resp = if (lteBands == null && nrBands == null) {
                    networkRepo.setBandLockCombined(null, null, "unlock")
                } else {
                    networkRepo.setBandLockCombined(lteBands, nrBands, "lock")
                }
                if (!resp.success) {
                    _networkState.value = _networkState.value.copy(errorMessage = "锁频失败")
                } else {
                    delay(800)
                    loadBandStatus()
                }
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "锁频失败: ${e.message}") }
        }
    }

    fun loadBandStatus() {
        scope.launch {
            try {
                _networkState.update { it.copy(bandStatus = networkRepo.getBandStatus(), loadVersion = System.currentTimeMillis()) }
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "频段状态加载失败: ${e.message}") }
            }
        }
    }

    fun loadDeviceSettings() {
        scope.launch {
            _deviceSettingsState.update { it.copy(isLoading = true, loadVersion = System.currentTimeMillis()) }
            try {
                _deviceSettingsState.update { DeviceSettingsState(settings = wifiLanRepo.getDeviceSettings(), loadVersion = System.currentTimeMillis()) }
            }
            catch (e: Exception) { _deviceSettingsState.update { it.copy(errorMessage = "加载设备设置失败: ${e.message}", isLoading = false) } }
        }
    }

    fun setBearerPreference(preference: String) {
        scope.launch {
            try {
                val resp = networkRepo.setBearerPreference(preference)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    fun connectNetwork() {
        scope.launch {
            try {
                val resp = networkRepo.connectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "连接失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                delay(2000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "连接失败: ${e.message}") }
        }
    }

    fun disconnectNetwork() {
        scope.launch {
            try {
                val resp = networkRepo.disconnectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "断开失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                delay(2000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "断开失败: ${e.message}") }
        }
    }

    fun setConnectionMode(mode: String) {
        scope.launch {
            try {
                val resp = networkRepo.setConnectionMode(mode)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    // ── Speed Test ──
    /** 内网测速，[maxDurationSec] 最大测速时长（秒），默认 10 秒 */
    fun runSpeedTest(maxDurationSec: Int = 10) {
        scope.launch(Dispatchers.IO) {
            _speedTestState.value = SpeedTestState(isRunning = true, testType = "internal")
            var responseBody: okhttp3.ResponseBody? = null
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                // 根据时长动态计算 chunks：按 ~10MB/s 基线估算，确保数据量覆盖测试时长
                // chunks=1 → 1MB，coerceIn 保证至少 10MB、最多 1024MB(=1GB)
                val chunks = (maxDurationSec * 10).coerceIn(10, 1024)
                responseBody = networkRepo.speedTest(chunks)
                val buf = ByteArray(8192)
                var totalBytes = 0L
                val deadlineMs = start + maxDurationSec * 1000L
                val stream = responseBody.byteStream()
                while (isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
                    val bytesRead = stream.read(buf)
                    if (bytesRead == -1) break
                    totalBytes += bytesRead
                }
                if (!isActive) throw CancellationException("测速已取消")
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                val seconds = elapsed / 1000.0
                val speedMbps = if (seconds > 0 && totalBytes > 0) (totalBytes * 8.0 / seconds / 1_000_000) else 0.0
                _speedTestState.value = SpeedTestState(result = "内网下载 ${totalBytes / 1024} KB, 耗时 ${"%.1f".format(seconds)}s, 速度 ${"%.2f".format(speedMbps)} Mbps", testType = "internal")
            } catch (e: CancellationException) {
                _speedTestState.value = SpeedTestState(errorMessage = "内网测速已取消", testType = "internal")
            } catch (e: Exception) {
                _speedTestState.value = SpeedTestState(errorMessage = "内网测速失败: ${e.message}", testType = "internal")
            } finally {
                try { responseBody?.close() } catch (_: Exception) {}
            }
        }
    }

    /** 外网测速：从指定 URL 下载大文件，[maxDurationSec] 最大测速时长（秒），默认 15 秒 */
    fun runExternalSpeedTest(url: String = "https://speedtest.tele2.net/100MB.zip", maxDurationSec: Int = 15) {
        scope.launch(Dispatchers.IO) {
            _speedTestState.value = SpeedTestState(isRunning = true, testType = "external")
            var connection: java.net.HttpURLConnection? = null
            var input: java.io.InputStream? = null
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = (maxDurationSec + 5) * 1000
                connection.requestMethod = "GET"
                connection.connect()
                if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
                val buf = ByteArray(8192)
                var totalBytes = 0L
                val deadlineMs = start + maxDurationSec * 1000L
                input = connection.inputStream
                while (isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
                    val bytesRead = input.read(buf)
                    if (bytesRead == -1) break
                    totalBytes += bytesRead
                }
                if (!isActive) throw CancellationException("测速已取消")
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                val seconds = elapsed / 1000.0
                val speedMbps = if (seconds > 0 && totalBytes > 0) (totalBytes * 8.0 / seconds / 1_000_000) else 0.0
                _speedTestState.value = SpeedTestState(result = "外网下载 ${totalBytes / 1024} KB, 耗时 ${"%.1f".format(seconds)}s, 速度 ${"%.2f".format(speedMbps)} Mbps", testType = "external")
            } catch (e: CancellationException) {
                _speedTestState.value = SpeedTestState(errorMessage = "外网测速已取消", testType = "external")
            } catch (e: Exception) {
                _speedTestState.value = SpeedTestState(errorMessage = "外网测速失败: ${e.message}", testType = "external")
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    // ── Cell ──
    fun loadCellInfo() {
        scope.launch {
            try { _networkState.update { it.copy(cellInfo = networkRepo.getCellInfo(), loadVersion = System.currentTimeMillis()) } }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "基站信息查询失败: ${e.message}") } }
        }
    }

    fun cellLock(pci: String, earfcn: String, rat: String) {
        scope.launch {
            try { networkRepo.cellLock(pci, earfcn, rat); delay(500); loadCellInfo() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "锁基站失败: ${e.message}") }
        }
    }

    fun unlockAllCell() {
        scope.launch {
            try { networkRepo.unlockAllCell(); delay(500); loadCellInfo() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "解锁基站失败: ${e.message}") }
        }
    }

    // ── LAN ──
    fun loadLanSettings() {
        scope.launch {
            _networkState.update { it.copy(errorMessage = null, loadVersion = System.currentTimeMillis()) }
            try { _networkState.update { it.copy(lanSettings = wifiLanRepo.getLanSettings(), loadVersion = System.currentTimeMillis()) } }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "LAN设置查询失败: ${e.message}") } }
        }
    }

    fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String, dhcpStart: String, dhcpEnd: String, dhcpLease: String) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)
                if (resp.success) { delay(500); loadLanSettings() }
                else _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败: ${e.message}") }
        }
    }

    // ── Blacklist ──
    fun loadBlacklist() {
        scope.launch {
            try { _networkState.update { it.copy(blacklistInfo = wifiLanRepo.getAccessControl(), loadVersion = System.currentTimeMillis()) } }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "黑名单查询失败: ${e.message}") } }
        }
    }

    fun blockDevice(mac: String, name: String) {
        scope.launch {
            try {
                val current = wifiLanRepo.getAccessControl()
                val json = try { current.asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
                val existingMacs = (json.get("BlackMacList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val existingNames = (json.get("BlackNameList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val aclMode = json.get("AclMode")?.asString ?: "2"
                val newMacs = if (mac in existingMacs) existingMacs else listOf(mac) + existingMacs
                val newNames = if (name in existingNames) existingNames else listOf(name) + existingNames
                wifiLanRepo.setAccessControl(aclMode, newMacs.joinToString(";"), newNames.joinToString(";"))
                loadBlacklist()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "拉黑失败: ${e.message}") }
        }
    }

    fun unblockDevice(mac: String, name: String) {
        scope.launch {
            try {
                val current = wifiLanRepo.getAccessControl()
                val json = try { current.asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
                val existingMacs = (json.get("BlackMacList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val existingNames = (json.get("BlackNameList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val aclMode = json.get("AclMode")?.asString ?: "2"
                val newMacs = existingMacs.filter { it != mac }
                val newNames = existingNames.filter { it != name }
                wifiLanRepo.setAccessControl(aclMode, newMacs.joinToString(";"), newNames.joinToString(";"))
                loadBlacklist()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "解除拉黑失败: ${e.message}") }
        }
    }

    fun setAccessControl(aclMode: String, macList: String = "") {
        scope.launch {
            try {
                val resp = wifiLanRepo.setAccessControl(aclMode, macList)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "访问控制设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "访问控制设置失败: ${e.message}") }
        }
    }

    // ── Device Control ──
    fun rebootDevice() {
        scope.launch {
            try {
                val resp = wifiLanRepo.rebootDevice()
                if (resp.success) onDashboardError?.invoke("设备正在重启...")
                else onDashboardError?.invoke("重启失败")
            } catch (e: Exception) { onDashboardError?.invoke("重启失败: ${e.message}") }
        }
    }

    fun factoryReset() {
        scope.launch {
            try { wifiLanRepo.factoryReset() }
            catch (e: Exception) { onDashboardError?.invoke("恢复出厂失败: ${e.message}") }
        }
    }

    fun shutdownDevice() {
        scope.launch {
            try {
                val resp = wifiLanRepo.shutdownDevice()
                if (resp.success) onDashboardError?.invoke("设备正在关机...")
                else onDashboardError?.invoke("关机失败")
            } catch (e: Exception) { onDashboardError?.invoke("关机失败: ${e.message}") }
        }
    }

    fun setDeviceMode(enabled: Boolean) {
        scope.launch {
            try { wifiLanRepo.setDeviceMode(enabled) }
            catch (e: Exception) { onDashboardError?.invoke("ADB调试设置失败: ${e.message}") }
        }
    }

    fun setUsbMode(mode: Int) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setUsbMode(mode)
                if (!resp.success) onDashboardError?.invoke("USB模式切换失败")
            } catch (e: Exception) { onDashboardError?.invoke("USB模式切换失败: ${e.message}") }
        }
    }

    fun changePassword(oldPwd: String, newPwd: String) {
        scope.launch {
            try { wifiLanRepo.changePassword(oldPwd, newPwd) }
            catch (e: Exception) { onDashboardError?.invoke("修改密码失败: ${e.message}") }
        }
    }

    fun setHostname(mac: String, hostname: String) {
        scope.launch {
            try { wifiLanRepo.setHostname(mac, hostname) }
            catch (e: Exception) { onDashboardError?.invoke("主机名设置失败: ${e.message}") }
        }
    }

    // ── WiFi Config ──
    fun setWifiConfig(config: Map<String, Any>) {
        scope.launch {
            try { wifiLanRepo.setWifiConfig(config) }
            catch (e: Exception) { onDashboardError?.invoke("WiFi设置失败: ${e.message}") }
        }
    }

    fun setWifiAdvConfig(config: Map<String, Any>) {
        scope.launch {
            try { wifiLanRepo.setWifiAdvConfig(config) }
            catch (e: Exception) { onDashboardError?.invoke("WiFi高级设置失败: ${e.message}") }
        }
    }

    fun setWifiPower(level: Int) {
        scope.launch {
            try { wifiLanRepo.setWifiPower(level) }
            catch (e: Exception) { onDashboardError?.invoke("功率设置失败: ${e.message}") }
        }
    }

    fun setWifiGuest(config: Map<String, Any>) {
        scope.launch {
            try { wifiLanRepo.setWifiGuest(config) }
            catch (e: Exception) { onDashboardError?.invoke("访客WiFi设置失败: ${e.message}") }
        }
    }

    fun setApnConfig(config: Map<String, Any>) {
        scope.launch {
            try { wifiLanRepo.setApnConfig(config) }
            catch (e: Exception) { onDashboardError?.invoke("APN设置失败: ${e.message}") }
        }
    }

    fun loadApnConfig(onResult: (JsonElement?) -> Unit) {
        scope.launch {
            try { onResult(wifiLanRepo.getApnConfig()) }
            catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "APN配置加载失败: ${e.message}")
                onResult(null)
            }
        }
    }

    fun deleteApnProfile(index: Int, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                wifiLanRepo.deleteApnProfile(index)
                onResult(true)
            } catch (e: Exception) {
                onDashboardError?.invoke("删除APN失败: ${e.message}")
                onResult(false)
            }
        }
    }

    fun switchApnProfile(index: Int, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                wifiLanRepo.switchApn(index)
                onResult(true)
            } catch (e: Exception) {
                onDashboardError?.invoke("切换APN失败: ${e.message}")
                onResult(false)
            }
        }
    }

    fun setTr069Config(config: Map<String, Any>) {
        scope.launch {
            try { wifiLanRepo.setTr069Config(config) }
            catch (e: Exception) { onDashboardError?.invoke("TR-069设置失败: ${e.message}") }
        }
    }

    // ── Enhanced Device Settings ──
    fun setWifiEnabled(enabled: Boolean) {
        scope.launch {
            try { wifiLanRepo.setWifiEnabled(enabled); delay(1000); refreshNetwork() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "WiFi开关失败: ${e.message}") }
        }
    }

    fun setFotaDisabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setFotaDisabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败: ${e.message}") }
        }
    }

    fun setPerformanceMode(mode: String) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setPerformanceMode(mode)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败: ${e.message}") }
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setLedEnabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败: ${e.message}") }
        }
    }

    fun setRoamingEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setRoamingEnabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败: ${e.message}") }
        }
    }

    fun setUsbTethering(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setUsbTethering(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "USB共享设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "USB共享设置失败: ${e.message}") }
        }
    }

    fun switchWifiChip(chip: String) {
        scope.launch {
            try {
                AppPreferences(appContext).preferredWifiChip = chip
                wifiLanRepo.switchWifiChip(chip); delay(3000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "WiFi频段切换失败: ${e.message}") }
        }
    }

    fun restoreWifiChipPreference() {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val preferred = prefs.preferredWifiChip
                if (preferred.isBlank()) return@launch
                val settings = wifiLanRepo.getWifiSettings()
                val parsed = parseWifiSettings(settings)
                if (parsed.activeChip.isBlank()) return@launch
                if (parsed.activeChip != preferred) { wifiLanRepo.switchWifiChip(preferred); delay(2000); refreshNetwork() }
            } catch (e: Exception) {
                DebugLog.w("Network", "restoreWifiChipPreference failed: ${e.message}")
            }
        }
    }

    fun setWifiNfc(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setWifiNfc(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "NFC设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "NFC设置失败: ${e.message}") }
        }
    }

    fun setWifiSleep(time: String) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setWifiSleep(time)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败: ${e.message}") }
        }
    }

    fun setSambaSetting(enabled: Boolean) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setSambaSetting(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败: ${e.message}") }
        }
    }

    fun setRestartSchedule(enabled: Boolean, time: String) {
        scope.launch {
            try {
                val resp = wifiLanRepo.setRestartSchedule(enabled, time)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败: ${e.message}") }
        }
    }

    /** 重置 Telephony Provider（紧急恢复：SIM 卡无限重置时使用） */
    fun resetTelephony(onResult: suspend (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                val resp = networkRepo.resetTelephony()
                onResult(resp.success, if (resp.success) "Telephony 已重置，请等待 SIM 重新识别" else "重置失败: exit_code=${resp.exit_code}")
            } catch (e: Exception) {
                onResult(false, "重置失败: ${e.message}")
            }
        }
    }

    fun clearError() { _networkState.value = _networkState.value.copy(errorMessage = null) }
    fun setError(message: String?) { _networkState.value = _networkState.value.copy(errorMessage = message) }

    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        when {
            changedType == "network:band-status" || changedType == "network:cell-info" -> refreshNetworkLight()
            changedType.startsWith("wifi:") -> refreshWifiOnly()
            changedType == "sim:*" -> refreshSimOnly()
            changedType == "device:lan" -> loadLanSettings()
            changedType == "device:acl" -> loadBlacklist()
            changedType == "device:settings" -> loadDeviceSettings()
        }
    }

    /** 轻量刷新：仅信号+网络状态+频段状态（网络模式/频段锁定变更时触发） */
    private fun refreshNetworkLight() {
        scope.launch {
            try {
                val sig = async { runCatching { networkRepo.getSignalInfo() } }
                val net = async { runCatching { networkRepo.getNetworkStatus() } }
                val band = async { runCatching { networkRepo.getBandStatus() } }
                val rSig = sig.await(); val rNet = net.await(); val rBand = band.await()

                val failures = listOfNotNull(
                    "信号" to rSig, "网络" to rNet, "频段" to rBand
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                _networkState.update { it.copy(
                    signalInfo = rSig.getOrNull() ?: it.signalInfo,
                    networkStatus = rNet.getOrNull() ?: it.networkStatus,
                    bandStatus = rBand.getOrNull() ?: it.bandStatus,
                    errorMessage = failures ?: it.errorMessage
                )}
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "刷新失败: ${e.message}") }
            }
        }
    }

    /** 精准刷新 WiFi 设置+客户端列表 */
    private fun refreshWifiOnly() {
        scope.launch {
            try {
                val wifi = async { runCatching { wifiLanRepo.getWifiSettings() } }
                val clients = async { runCatching { wifiLanRepo.getWifiClients() } }
                val rWifi = wifi.await(); val rClients = clients.await()

                val failures = listOfNotNull(
                    "WiFi" to rWifi, "客户端" to rClients
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                val wifiSettingsJson = rWifi.getOrNull()
                val wifiParsed = parseWifiSettings(wifiSettingsJson)
                _networkState.update { it.copy(
                    wifiSettings = wifiSettingsJson ?: it.wifiSettings,
                    wifiClients = rClients.getOrNull() ?: it.wifiClients,
                    wifiEnabled = wifiParsed.isEnabled,
                    errorMessage = failures ?: it.errorMessage
                )}
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "WiFi刷新失败: ${e.message}") }
            }
        }
    }

    /** 精准刷新 SIM 信息 */
    private fun refreshSimOnly() {
        scope.launch {
            try {
                _networkState.update { it.copy(simInfo = networkRepo.getSimInfo()) }
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "SIM信息刷新失败: ${e.message}") }
            }
        }
    }
}

// ── 统一解析（ViewModel + UI 共用） ──
// UFIAxis 服务器返回的全部是平铺 JSON，不存在 Goform 字符串嵌套包装。
// ZTE goform 的嵌套字符串 (如 queryAccessPointInfo 内的 ResponseList)
// 已在服务器端解析并展开为平铺字段，客户端无需再做解包。

@Suppress("DEPRECATION")
private val _parseGson = com.google.gson.GsonBuilder().setLenient().create()

/** WiFi 设置解析结果（服务器 GET /api/wifi/settings 平铺字段） */
data class WifiParsed(
    val ssid: String = "",
    val passphrase: String = "",
    val activeChip: String = "chip1",
    val isEnabled: Boolean = false
)

/**
 * 解析 WiFi settings JSON。
 * 服务器已将 ZTE goform 的 ResponseList + 扁查询字段合并为平铺 JSON：
 *   wifi_chip1_ssid1_ssid          → SSID（已从 ResponseList 提取并优先覆盖）
 *   wifi_chip1_ssid1_passphrase    → 密码（服务端已完成 base64 解码）
 *   wifi_chip / wifi_onoff_state / WiFiModuleSwitch / wifi_enable
 *
 * 客户端不再做 base64 解码，完全信任服务端已返回解码后的明文密码。
 */
fun parseWifiSettings(raw: JsonElement?): WifiParsed {
    if (raw == null) return WifiParsed()
    return try {
        val obj = raw.asJsonObject

        val ssid = obj.safeGetString("wifi_chip1_ssid1_ssid")
            ?: obj.safeGetString("wifi_chip1_ssid") ?: ""
        if (ssid.isBlank() && obj.safeGetString("WiFiModuleSwitch") == "0") return WifiParsed(isEnabled = false)

        val passphrase = obj.safeGetString("wifi_chip1_ssid1_passphrase")
            ?: obj.safeGetString("wifi_chip1_passphrase") ?: ""

        val chipRaw = obj.safeGetString("wifi_chip")
        val chip = when {
            chipRaw == "chip2" -> "chip2"
            chipRaw == "chip1" -> "chip1"
            chipRaw == "1" -> "chip2"
            else -> "chip1"
        }

        val enabled = obj.safeGetString("WiFiModuleSwitch") == "1"
            || obj.safeGetString("wifi_onoff_state") == "1"
            || obj.safeGetString("wifi_enable") == "1"

        WifiParsed(ssid, passphrase, chip, enabled)
    } catch (_: Exception) {
        WifiParsed()
    }
}

/** 解析 WiFi 客户端列表 JSON */
data class WifiClientsParsed(
    val stationList: JsonArray?,
    val lanStationList: JsonArray?
)

/**
 * 解析 WiFi 客户端列表。
 * 服务器 GET /api/wifi/clients 返回 ZTE goform 原始响应（平铺 JSON）：
 *   {
 *     "station_list": "[{\"hostname\":\"...\",\"mac_addr\":\"...\",\"ip_addr\":\"...\"},...]",
 *     "lan_station_list": "[{...},...]",
 *     "wifi_access_sta_num": "3",
 *     "sta_ip_status": "...",
 *     "hostNameList": "host1;host2"
 *   }
 * 其中 station_list / lan_station_list 的值是序列化的 JSON 数组字符串。
 */
fun parseWifiClients(raw: JsonElement?): WifiClientsParsed {
    if (raw == null) return WifiClientsParsed(null, null)
    return try {
        val obj = raw.asJsonObject
        fun parseArrayField(key: String): JsonArray? {
            val value = obj.get(key) ?: return null
            return try {
                if (value.isJsonArray) value.asJsonArray
                else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val s = value.asString
                    if (s.startsWith("[")) _parseGson.fromJson(s, JsonArray::class.java) else null
                } else null
            } catch (_: Exception) { null }
        }
        WifiClientsParsed(
            stationList = parseArrayField("station_list"),
            lanStationList = parseArrayField("lan_station_list")
        )
    } catch (_: Exception) {
        WifiClientsParsed(null, null)
    }
}

/** 解析 Band Status JSON */
data class BandStatusParsed(
    val lteBandLock: String = "",
    val nrBandLock: String = ""
)

/**
 * 解析频段锁定状态。
 * 服务器 GET /api/network/band-status 返回平铺 JSON：
 *   {"lte_band_lock": "1,3,5", "nr_band_lock": "41,78"}
 */
/**
 * 从 cellInfo JSON 中解析当前连接频段。
 *
 * cellInfo 来源：GET /api/network/cell-info（goform 平铺 JSON）。
 * goform 查询 network_information 时会将 NR 字段展开为顶层键（Nr_bands/Nr_fcn/Nr_pci 等），
 * LTE 字段通过独立查询（Lte_bands/Lte_fcn）获取。
 *
 * @return 格式化频段字符串，如 "N41"（NR 频段41）、"B3"（LTE 频段3），或 null
 */
fun parseCurrentBand(cellInfo: JsonElement?): String? {
    if (cellInfo == null) return null
    return try {
        val obj = cellInfo.asJsonObject
        val networkType = obj.safeGetString("network_type") ?: ""
        val isNr = networkType.contains("5G", ignoreCase = true) ||
                networkType.contains("NR", ignoreCase = true)

        if (isNr) {
            // 5G/NR → 从 network_information 展开的 Nr_bands 字段
            val nrBand = obj.safeGetString("Nr_bands")
                ?.split(",")?.firstOrNull { it.isNotBlank() }
            if (!nrBand.isNullOrBlank()) "N$nrBand" else null
        } else {
            // 4G/LTE → 从独立查询的 Lte_bands 字段
            val lteBand = obj.safeGetString("Lte_bands")
                ?.split(",")?.firstOrNull { it.isNotBlank() }
            if (!lteBand.isNullOrBlank()) "B$lteBand" else null
        }
    } catch (_: Exception) { null }
}

fun parseBandStatus(raw: JsonElement?): BandStatusParsed {
    if (raw == null) return BandStatusParsed()
    return try {
        val obj = raw.asJsonObject
        BandStatusParsed(
            lteBandLock = obj.safeGetString("lte_band_lock") ?: "",
            nrBandLock = obj.safeGetString("nr_band_lock") ?: ""
        )
    } catch (_: Exception) {
        BandStatusParsed()
    }
}

/**
 * 解析设备设置 JSON。
 * 服务器 GET /api/device/settings 返回平铺 JSON（ZTE goform 字段原样）：
 *   connection_mode, BearerPreference, net_select,
 *   indicator_light_switch, performance_mode, roam_setting_option, ...
 */
fun parseDeviceSettingsField(raw: JsonElement?, vararg keys: String): String? {
    if (raw == null) return null
    return try {
        val obj = raw.asJsonObject
        for (key in keys) {
            val v = obj.safeGetString(key)
            if (v != null) return v
        }
        null
    } catch (_: Exception) { null }
}

// ── 安全 JSON 取值辅助（使用 Java 原生 API，避免 Kotlin 扩展版本兼容问题） ──

/** 安全地从 JsonObject 取字符串字段 */
private fun JsonObject.safeGetString(key: String): String? {
    return try {
        val el = get(key) ?: return null
        if (el.isJsonPrimitive) el.getAsJsonPrimitive().getAsString() else null
    } catch (_: Exception) { null }
}
