package com.ufi_axis.data.repository

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*
import okhttp3.ResponseBody

/** 网络 · 信号 · 流量 · SIM卡 · 流量管理 */
class NetworkRepository(private val api: UfiAxisApi) {
    // Signal
    suspend fun getSignalInfo() = api.getSignalInfo()
    suspend fun getSignalHistory(hours: Int = 24) = api.getSignalHistory(hours)
    suspend fun getNetworkStatus() = api.getNetworkStatus()

    // Traffic
    suspend fun getTrafficRealtime() = api.getTrafficRealtime()
    suspend fun getTrafficHistory(hours: Int = 24) = api.getTrafficHistory(hours)
    suspend fun getTrafficSummary() = api.getTrafficSummary()

    // Mobile Data
    suspend fun setMobileData(enabled: Boolean) = api.setMobileData(mapOf("enabled" to enabled))
    suspend fun setAirplaneMode(enabled: Boolean) = api.setAirplaneMode(mapOf("enabled" to enabled))

    // Bands & Modes
    /** 统一锁定 LTE+NR 频段（goform + AT+SFUN 网络栈重启，无需设备重启）
     * @param lteBands LTE 频段号逗号分隔，null=不修改
     * @param nrBands  NR 频段号逗号分隔，null=不修改
     * @param action   "lock"=锁定, "unlock"=解锁全部 */
    suspend fun setBandLockCombined(lteBands: String?, nrBands: String?, action: String = "lock") =
        api.setBandLock(buildMap {
            if (action == "unlock") { put("action", "unlock") }
            else {
                if (!lteBands.isNullOrBlank()) put("lte_bands", lteBands)
                if (!nrBands.isNullOrBlank()) put("nr_bands", nrBands)
                if (lteBands.isNullOrBlank() && nrBands.isNullOrBlank()) put("action", "unlock")
            }
        })
    suspend fun getBandStatus() = api.getBandStatus()
    suspend fun setNetworkMode(mode: String) = api.setNetworkMode(ModeRequest(mode))

    // Connection
    suspend fun setBearerPreference(preference: String) = api.setBearerPreference(mapOf("preference" to preference))
    suspend fun connectNetwork() = api.connectNetwork()
    suspend fun disconnectNetwork() = api.disconnectNetwork()
    suspend fun setConnectionMode(mode: String) = api.setConnectionMode(mapOf("mode" to mode))

    // Cell
    suspend fun getCellInfo() = api.getCellInfo()
    suspend fun cellLock(pci: String, earfcn: String, rat: String) =
        api.cellLock(mapOf("pci" to pci, "earfcn" to earfcn, "rat" to rat))
    suspend fun unlockAllCell() = api.unlockAllCell()

    // Speed Test
    suspend fun speedTest(chunks: Int = 10) = api.speedTest(chunks)

    // SIM info
    suspend fun getSimInfo() = api.getSimInfo()
    suspend fun switchSimSlot(slot: Int) = api.switchSimSlot(mapOf("slot" to slot))

    // Traffic Management
    suspend fun getTrafficLimit() = api.getTrafficLimit()
    suspend fun setDataLimit(body: Map<String, @JvmSuppressWildcards Any>) = api.setDataLimit(body)
    suspend fun calibrateFlow(body: Map<String, @JvmSuppressWildcards Any>) = api.calibrateFlow(body)

    // Telephony Reset
    suspend fun resetTelephony() = api.resetTelephony()
}
