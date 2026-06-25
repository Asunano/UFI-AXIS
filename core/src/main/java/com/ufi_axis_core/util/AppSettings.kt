package com.ufi_axis_core.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置管理器
 *
 * 持久化存储后端服务的关键配置项，支持运行时修改。
 * 修改认证或端口配置后需重启服务才能生效。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ufi_axis_settings"

        // Keys
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_SECRET = "auth_secret"
        private const val KEY_PORT = "server_port"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_GOFORM_IP = "goform_ip"
        private const val KEY_GOFORM_PORT = "goform_port"
        private const val KEY_GOFORM_PASSWORD = "goform_password"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_QOS_ENABLED = "qos_enabled"
        private const val KEY_QOS_SHELL_MAX = "qos_shell_max_concurrent"
        private const val KEY_QOS_CACHE_TTL = "qos_cache_ttl_ms"
        private const val KEY_QOS_GOFORM_QUERY_MAX = "qos_goform_query_max"
        private const val KEY_QOS_GOFORM_SET_MAX = "qos_goform_set_max"
        private const val KEY_ADB_AUTO_START = "adb_auto_start_on_boot"
        private const val KEY_ALERT_CONFIG = "alert_config"

        // Defaults
        const val DEFAULT_TOKEN = "ufi-axis-default-token"
        const val DEFAULT_SECRET = "ufi-axis-default-secret"
        const val DEFAULT_PORT = 8088
        const val DEFAULT_AUTO_START = true
        const val DEFAULT_GOFORM_IP = "192.168.0.1"
        const val DEFAULT_GOFORM_PORT = 8080
        const val DEFAULT_GOFORM_PASSWORD = "admin"
        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_QOS_ENABLED = true
        const val DEFAULT_QOS_SHELL_MAX = 3
        const val DEFAULT_QOS_CACHE_TTL = 2000
        const val DEFAULT_QOS_GOFORM_QUERY_MAX = 4
        const val DEFAULT_QOS_GOFORM_SET_MAX = 2

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- Auth ---

    var token: String
        get() = prefs.getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var secret: String
        get() = prefs.getString(KEY_SECRET, DEFAULT_SECRET) ?: DEFAULT_SECRET
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    // --- Server ---

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    // --- Service ---

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    // --- Goform ---

    var goformIp: String
        get() = prefs.getString(KEY_GOFORM_IP, DEFAULT_GOFORM_IP) ?: DEFAULT_GOFORM_IP
        set(value) = prefs.edit().putString(KEY_GOFORM_IP, value).apply()

    var goformPort: Int
        get() = prefs.getInt(KEY_GOFORM_PORT, DEFAULT_GOFORM_PORT)
        set(value) = prefs.edit().putInt(KEY_GOFORM_PORT, value.coerceIn(1, 65535)).apply()

    var goformPassword: String
        get() = prefs.getString(KEY_GOFORM_PASSWORD, DEFAULT_GOFORM_PASSWORD) ?: DEFAULT_GOFORM_PASSWORD
        set(value) = prefs.edit().putString(KEY_GOFORM_PASSWORD, value).apply()

    // --- Debug ---

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    // --- QoS ---

    var qosEnabled: Boolean
        get() = prefs.getBoolean(KEY_QOS_ENABLED, DEFAULT_QOS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_QOS_ENABLED, value).apply()

    var qosShellMaxConcurrent: Int
        get() = prefs.getInt(KEY_QOS_SHELL_MAX, DEFAULT_QOS_SHELL_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_SHELL_MAX, value.coerceIn(1, 10)).apply()

    var qosCacheTtlMs: Int
        get() = prefs.getInt(KEY_QOS_CACHE_TTL, DEFAULT_QOS_CACHE_TTL)
        set(value) = prefs.edit().putInt(KEY_QOS_CACHE_TTL, value.coerceIn(500, 30000)).apply()

    var qosGoformQueryMax: Int
        get() = prefs.getInt(KEY_QOS_GOFORM_QUERY_MAX, DEFAULT_QOS_GOFORM_QUERY_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_GOFORM_QUERY_MAX, value.coerceIn(1, 8)).apply()

    var qosGoformSetMax: Int
        get() = prefs.getInt(KEY_QOS_GOFORM_SET_MAX, DEFAULT_QOS_GOFORM_SET_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_GOFORM_SET_MAX, value.coerceIn(1, 4)).apply()

    // --- ADB Auto-Start ---

    var adbAutoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_ADB_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_AUTO_START, value).apply()

    // --- Alert Config ---

    var alertConfigJson: String?
        get() = prefs.getString(KEY_ALERT_CONFIG, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_ALERT_CONFIG, value)
            else editor.remove(KEY_ALERT_CONFIG)
            editor.apply()
        }

    // --- Helpers ---

    /** 将全部配置导出为 Map，供 API 返回 */
    fun toMap(): Map<String, Any> = mapOf(
        "token" to token,
        "secret" to secret,
        "port" to port,
        "auto_start_on_boot" to autoStartOnBoot,
        "goform_ip" to goformIp,
        "goform_port" to goformPort,
        "goform_password" to goformPassword,
        "debug_mode" to debugMode,
        "qos_enabled" to qosEnabled,
        "qos_shell_max_concurrent" to qosShellMaxConcurrent,
        "qos_cache_ttl_ms" to qosCacheTtlMs,
        "qos_goform_query_max" to qosGoformQueryMax,
        "qos_goform_set_max" to qosGoformSetMax,
        "adb_auto_start_on_boot" to adbAutoStartOnBoot
    )

    /** 恢复全部默认值 */
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}
