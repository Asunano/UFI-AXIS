package com.ufi_axis_core.collector.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor

/**
 * Telephony 采集器
 * 通过网络类型/运营商等 Android 系统 API 采集信息
 * 作为 AT 指令的补充数据源
 */
class TelephonyCollector(private val context: Context) {

    private val tag = "TelephonyCollector"
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * 获取信号信息（不依赖 AT 指令的备用方案）
     */
    fun getSignalInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            if (telephonyManager == null) {
                result["error"] = "TelephonyManager not available"
                return result
            }

            result["operator"] = getOperatorName()
            result["rat"] = getNetworkType()
            result["network_registered"] = isNetworkRegistered()

            // Android 12+ CellInfo 需要位置权限
            if (hasCellInfoPermission()) {
                val cellInfo = telephonyManager.allCellInfo
                if (cellInfo?.isNotEmpty() == true) {
                    val primaryCell = cellInfo.firstOrNull { it.isRegistered }
                    if (primaryCell != null) {
                        parseCellInfo(primaryCell, result)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get signal info", e)
        }
        return result
    }

    /**
     * 获取运营商名称
     */
    fun getOperatorName(): String {
        return try {
            telephonyManager?.networkOperatorName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 获取运营商代码
     */
    fun getOperatorCode(): String {
        return try {
            telephonyManager?.networkOperator ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 获取网络类型 (4G/5G/3G/2G)
     */
    fun getNetworkType(): String {
        return try {
            // 优先通过 ConnectivityManager 判断
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        // 通过 TelephonyManager 获取具体类型
                        getDataNetworkType()
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    else -> "Unknown"
                }
            } else {
                getDataNetworkType()
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 获取 SIM 卡状态
     */
    @Suppress("DEPRECATION")
    fun getSimInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            result["sim_state"] = when (telephonyManager?.simState) {
                TelephonyManager.SIM_STATE_READY -> "Ready"
                TelephonyManager.SIM_STATE_ABSENT -> "Absent"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
                else -> "Unknown"
            }
            if (hasPhoneStatePermission()) {
                result["sim_operator"] = telephonyManager?.simOperatorName ?: "Unknown"
            }
            result["phone_type"] = when (telephonyManager?.phoneType) {
                TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                TelephonyManager.PHONE_TYPE_NONE -> "None"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get SIM info", e)
        }
        return result
    }

    /**
     * 检查网络连接状态
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val activeNetwork = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查移动数据是否开启
     * 使用 settings get global mobile_data 更准确（因为 ConnectivityManager 在随身WiFi上不准）
     * 注意: 已改为 suspend 函数，避免 runBlocking 在协程上下文中死锁
     */
    suspend fun isMobileDataEnabled(): Boolean {
        return try {
            val result = ShellExecutor.execute("settings get global mobile_data 2>/dev/null")
            if (result.isSuccess) {
                val v = result.stdout.trim()
                return v == "1"
            }
            // fallback: ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            false
        }
    }

    // --- 内部方法 ---

    private fun isNetworkRegistered(): Boolean {
        return try {
            telephonyManager?.networkOperator?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getDataNetworkType(): String {
        return try {
            if (!hasPhoneStatePermission()) return "Unknown"
            when (telephonyManager?.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA -> "3G+"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun parseCellInfo(cellInfo: CellInfo, result: MutableMap<String, Any>) {
        when (cellInfo) {
            is CellInfoLte -> {
                val signal = cellInfo.cellSignalStrength
                result["rsrp"] = signal.rsrp
                result["rsrq"] = signal.rsrq
                // Android API 在部分设备上返回 Integer.MAX_VALUE 表示 RSSI 不可用
                val rssi = signal.rssi
                if (rssi in -120..-30) {
                    result["rssi"] = rssi
                }
                // LTE CellSignalStrength 不提供 SINR，不填充避免误导
                result["rat"] = "4G"
            }
            is CellInfoNr -> {
                val signal = cellInfo.cellSignalStrength
                if (signal is CellSignalStrengthNr) {
                    result["rsrp"] = signal.csiRsrp
                    result["sinr"] = signal.csiSinr
                    result["rsrq"] = signal.csiRsrq
                    // CellSignalStrengthNr 不提供 RSSI，从 CSI-RSRP 估算
                    // RSSI ≈ RSRP + 10*log10(N)，典型 20MHz 约 100 RB → ~+20dB
                    if (signal.csiRsrp != Int.MAX_VALUE && signal.csiRsrp < 0) {
                        result["rssi"] = signal.csiRsrp + 20
                    }
                    result["rat"] = "5G"
                }
            }
            is CellInfoWcdma -> {
                val signal = cellInfo.cellSignalStrength
                val rssi = signal.dbm
                if (rssi in -120..-30) {
                    result["rssi"] = rssi
                }
                result["rat"] = "3G"
            }
            is CellInfoGsm -> {
                val signal = cellInfo.cellSignalStrength
                val rssi = signal.dbm
                if (rssi in -120..-30) {
                    result["rssi"] = rssi
                }
                result["rat"] = "2G"
            }
        }
    }

    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Android 12+ (API 31) CellInfo 需要位置权限
     */
    private fun hasCellInfoPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

data class SignalInfo(
    val rsrp: Int,
    val sinr: Int,
    val rsrq: Int,
    val rat: String
)

data class NetworkStatus(
    val type: String,
    val operator: String
)

data class TrafficStatsData(
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeed: Long,
    val txSpeed: Long
)
