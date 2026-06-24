package com.ufi_axis_core.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/**
 * 网络控制器
 * 统一管理移动数据/WiFi/飞行模式/锁频/锁网
 */
class NetworkController(
    private val context: Context,
    private val atChannel: ATChannel,
    private val networkClient: GoformNetworkClient,
    private val wifiClient: GoformWifiClient
) {
    private val tag = "NetworkController"
    // 网络栈重启互斥锁 — 防止并发 AT+SFUN 调用
    private val stackRestartMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 开关移动数据
     * 优先使用 goform CONNECT_NETWORK/DISCONNECT_NETWORK（与参考项目一致）
     * 失败后 fallback 到 Shell
     */
    suspend fun setMobileData(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting mobile data: $enabled")
        // 方式1: Goform API（与参考项目 KanoUtils.kt 一致）
        if (networkClient.setMobileData(enabled)) {
            AppLogger.i(tag, "Mobile data set via goform: $enabled")
            return true
        }
        // 方式2: settings put global (fallback)
        val settingsCmd = "settings put global mobile_data ${if (enabled) 1 else 0}"
        val settingsResult = ShellExecutor.executeAsRoot(settingsCmd)
        if (settingsResult.isSuccess) {
            ShellExecutor.executeAsRoot("am broadcast -a android.intent.action.ANY_DATA_STATE --ez state $enabled")
            AppLogger.i(tag, "Mobile data set via settings: $enabled")
            return true
        }
        // 方式3: svc 命令
        val command = if (enabled) "svc data enable" else "svc data disable"
        return ShellExecutor.executeAsRoot(command).isSuccess
    }

    /**
     * 修改 WiFi 热点 SSID
     */
    suspend fun setWifiSSID(ssid: String, password: String? = null): Boolean {
        AppLogger.i(tag, "Setting WiFi SSID: $ssid")
        val ssidResult = wifiClient.setWifiSSID(ssid)
        if (ssidResult && password != null) {
            return wifiClient.setWifiPassword(password)
        }
        return ssidResult
    }

    /**
     * 修改 WiFi 密码
     */
    suspend fun setWifiPassword(password: String): Boolean {
        AppLogger.i(tag, "Setting WiFi password")
        return wifiClient.setWifiPassword(password)
    }

    /**
     * 开关飞行模式
     */
    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting airplane mode: $enabled")
        val value = if (enabled) 1 else 0
        val result = ShellExecutor.executeAsRoot(
            "settings put global airplane_mode_on $value && " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
        return result.isSuccess
    }

    /**
     * 统一频段锁定（goform + AT+SFUN 网络栈重启，无需设备重启）
     *
     * 流程（严格参考 UFI-TOOLS-REF submitBandForm）：
     * 1. 始终同时发送 LTE_BAND_LOCK 和 NR_BAND_LOCK goform 请求
     *    - 已选频段 → 以逗号拼接
     *    - 未选频段 → 空字符串（清除该 RAT 的频段限制）
     *    参考：lte_band_lock: lte_bands.join(','), nr_band_lock: nr_bands.join(',')
     * 2. 不调用 SET_BEARER_PREFERENCE — 制式切换由设备自动决定
     * 3. AT+SFUN=5 → 等待 500ms → AT+SFUN=4 重启网络协议栈，立即生效
     *
     * @param lteBands LTE 频段号，逗号分隔，如 "1,3,5"；null 表示不限制（发空串）
     * @param nrBands  NR 频段号，逗号分隔，如 "41,78"；null 表示不限制（发空串）
     * @param unlockAll 解锁全部频段 → 发送全频段列表
     * @return BandLockResult
     */
    data class BandLockResult(
        val success: Boolean,
        val mode: String,
        val stackRestarted: Boolean
    )

    suspend fun lockBands(
        lteBands: String?,
        nrBands: String?,
        unlockAll: Boolean = false
    ): BandLockResult {
        // unlockAll：同时发全频段 = 解除所有限制
        val lteValue = when {
            unlockAll -> GoformNetworkClient.LTE_ALL_BANDS
            lteBands != null -> lteBands
            else -> ""   // 未选 LTE → 清空该 RAT 限制（参考项目：lte_bands.join(',') 空数组→空串）
        }
        val nrValue = when {
            unlockAll -> GoformNetworkClient.NR_ALL_BANDS
            nrBands != null -> nrBands
            else -> ""   // 未选 NR → 清空该 RAT 限制
        }

        if (!unlockAll && lteValue.isEmpty() && nrValue.isEmpty())
            return BandLockResult(false, "no_bands", false)

        // 始终发送两个 goform 请求（参考项目 Promise.all）
        AppLogger.i(tag, "Band lock goform: LTE=\"$lteValue\" NR=\"$nrValue\"")
        val lteOk = networkClient.lockLteBands(lteValue)
        val nrOk = networkClient.lockNrBands(nrValue)
        AppLogger.i(tag, "Band lock result: LTE=$lteOk NR=$nrOk")

        // 不调用 SET_BEARER_PREFERENCE（参考项目 submitBandForm 不设置制式）
        val modeDesc = when {
            unlockAll -> "auto"
            lteValue.isNotBlank() && nrValue.isNotBlank() -> "lte_nr"
            lteValue.isNotBlank() && nrValue.isEmpty()  -> "lte_only"
            lteValue.isEmpty()  && nrValue.isNotBlank() -> "nr_only"
            else -> "auto"
        }

        val goformSuccess = lteOk || nrOk
        val result = BandLockResult(success = goformSuccess, mode = modeDesc, stackRestarted = false)

        // goform 写入成功后重启网络协议栈让频段立即生效
        if (result.success) {
            val restarted = restartNetworkStack()
            AppLogger.i(tag, "Band lock stack restart: $restarted")
            return result.copy(stackRestarted = restarted)
        }
        return result
    }

    /**
     * 重启网络协议栈（AT+SFUN=5→延时→AT+SFUN=4）
     *
     * 参考 UFI-TOOLS-REF: networkStackSwitch()
     * AT+SFUN=5 关闭网络栈 → 等待芯片重新加载配置 → AT+SFUN=4 重启
     * 这使得 goform 写入的频段限制无需设备重启即可生效。
     *
     * 安全措施：
     * - 互斥锁防止并发 AT+SFUN 调用
     * - 重启后额外 2s 等待 modem 完全稳定
     */
    suspend fun restartNetworkStack(): Boolean = stackRestartMutex.withLock {
        AppLogger.i(tag, "Restarting network stack (AT+SFUN=5/4)...")
        try {
            val off = atChannel.sendCommand("AT+SFUN=5", 5000)
            if (off?.contains("OK") != true) {
                AppLogger.w(tag, "AT+SFUN=5 failed: $off, stack restart skipped")
                return false
            }
            AppLogger.i(tag, "Network stack off, waiting 500ms...")
            delay(500)
            val on = atChannel.sendCommand("AT+SFUN=4", 5000)
            if (on?.contains("OK") != true) {
                AppLogger.w(tag, "AT+SFUN=4 failed: $on")
                return false
            }
            AppLogger.i(tag, "Network stack restarted, waiting 2s for modem stabilization...")
            delay(2000)
            AppLogger.i(tag, "Network stack restart complete")
            return true
        } catch (e: Exception) {
            AppLogger.e(tag, "Network stack restart exception: ${e.message}")
            return false
        }
    }

    /**
     * 获取当前网络状态
     */
    fun getNetworkStatus(): Map<String, Any> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val result = mutableMapOf<String, Any>()

        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

        result["is_connected"] = capabilities != null
        result["has_internet"] = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        result["has_cellular"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        result["has_wifi"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return result
    }

}
