package com.ufi_axis.util

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE)

    var serverIp: String
        get() = prefs.getString(KEY_SERVER_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_IP, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 8088)
        set(value) = prefs.edit().putInt(KEY_SERVER_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "ufi-axis-default-token") ?: "ufi-axis-default-token"
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var autoRefresh: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REFRESH, value).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
            DebugLog.enabled = value
        }

    var gatewayIp: String
        get() = prefs.getString(KEY_GATEWAY_IP, "192.168.0.1") ?: "192.168.0.1"
        set(value) = prefs.edit().putString(KEY_GATEWAY_IP, value).apply()

    var goformPassword: String
        get() = prefs.getString(KEY_GOFORM_PASSWORD, "admin") ?: "admin"
        set(value) = prefs.edit().putString(KEY_GOFORM_PASSWORD, value).apply()

    var goformPort: Int
        get() = prefs.getInt(KEY_GOFORM_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_GOFORM_PORT, value).apply()

    /** 用户偏好的 WiFi 芯片（chip1=2.4G, chip2=5G），用于设备重启后自动恢复 */
    var preferredWifiChip: String
        get() = prefs.getString(KEY_PREFERRED_WIFI_CHIP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PREFERRED_WIFI_CHIP, value).apply()

    val baseUrl: String
        get() = "http://$serverIp:$serverPort/"

    val isConfigValid: Boolean
        get() = serverIp.isNotBlank()

    companion object {
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_GATEWAY_IP = "gateway_ip"
        private const val KEY_GOFORM_PASSWORD = "goform_password"
        private const val KEY_GOFORM_PORT = "goform_port"
        private const val KEY_PREFERRED_WIFI_CHIP = "preferred_wifi_chip"
    }
}
