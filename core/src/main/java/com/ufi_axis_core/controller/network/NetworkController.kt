package com.ufi_axis_core.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor

/**
 * 网络控制器
 * 统一管理移动数据/WiFi/飞行模式/锁频/锁网
 */
class NetworkController(
    private val context: Context,
    private val atChannel: ATChannel,
    private val goformClient: GoformClient
) {
    private val tag = "NetworkController"

    /**
     * 开关移动数据
     * 优先使用 goform CONNECT_NETWORK/DISCONNECT_NETWORK（与参考项目一致）
     * 失败后 fallback 到 Shell
     */
    suspend fun setMobileData(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting mobile data: $enabled")
        // 方式1: Goform API（与参考项目 KanoUtils.kt 一致）
        if (goformClient.setMobileData(enabled)) {
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
        val ssidResult = goformClient.setWifiSSID(ssid)
        if (ssidResult && password != null) {
            return goformClient.setWifiPassword(password)
        }
        return ssidResult
    }

    /**
     * 修改 WiFi 密码
     */
    suspend fun setWifiPassword(password: String): Boolean {
        AppLogger.i(tag, "Setting WiFi password")
        return goformClient.setWifiPassword(password)
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
     * 锁频 - 锁定到指定 EARFCN
     * @param rat 网络制式 (LTE/NR)
     * @param earfcn EARFCN 值
     */
    suspend fun lockBand(rat: String, earfcn: Int): Boolean {
        AppLogger.i(tag, "Locking band: $rat EARFCN=$earfcn")
        return atChannel.lockBand(rat, earfcn)
    }

    /**
     * 解锁频段
     */
    suspend fun unlockBand(): Boolean {
        AppLogger.i(tag, "Unlocking band")
        return atChannel.unlockBand()
    }

    /**
     * 锁定网络制式 (仅 LTE / 仅 5G / 自动)
     */
    suspend fun setPreferredNetworkMode(mode: String): Boolean {
        AppLogger.i(tag, "Setting preferred network mode: $mode")
        val atCommand = when (mode.uppercase()) {
            "LTE_ONLY", "4G_ONLY" -> "AT+ZPREFMOD=0,1,0"
            "5G_ONLY", "NR_ONLY" -> "AT+ZPREFMOD=0,0,1"
            "AUTO" -> "AT+ZPREFMOD=1,1,1"
            "4G_5G", "LTE_NR" -> "AT+ZPREFMOD=0,1,1"
            "WCDMA_ONLY", "3G_ONLY" -> "AT+ZPREFMOD=2,0,0"
            "GSM_ONLY", "2G_ONLY" -> "AT+ZPREFMOD=1,0,0"
            else -> return false
        }
        val response = atChannel.sendCommand(atCommand)
        return response?.contains("OK") == true
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

    // ==================== 高铁模式 ====================

    /**
     * 查询高铁模式状态
     * 参考项目 main.js: AT+SP5GCMDS="get nr synch_param",44
     */
    suspend fun getHighRailMode(): Boolean {
        val resp = atChannel.sendCommand("AT+SP5GCMDS=\"get nr synch_param\",44", 5000) ?: return false
        return try {
            val match = Regex("\\+SP5GCMDS: (\\d+)").find(resp)
            match?.groupValues?.get(1) == "1"
        } catch (e: Exception) { false }
    }

    /**
     * 设置高铁模式
     * 参考项目 main.js: AT+SP5GCMDS="set nr param",35,0|1
     */
    suspend fun setHighRailMode(enabled: Boolean): Boolean {
        val state = if (enabled) 1 else 0
        val resp = atChannel.sendCommand("AT+SP5GCMDS=\"set nr param\",35,$state", 5000)
        return resp?.contains("OK") == true
    }
}
