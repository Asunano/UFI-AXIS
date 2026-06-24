package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.*

/**
 * Goform WiFi 管理客户端
 *
 * 从 GoformClient 拆分，负责：
 * - WiFi 状态/热点/客户端查询
 * - WiFi 基础设置（SSID/加密/密码/最大连接数）
 * - WiFi 高级设置（信道/带宽/模式/国家码）
 * - WiFi 功率/访客
 * - WiFi 芯片切换/NFC/休眠
 */
class GoformWifiClient(private val client: GoformClient) {
    private val tag = "GoformWifi"

    // ==================== WiFi 查询 ====================

    suspend fun getWifiModuleInfo(): JsonObject? {
        return client.query(listOf("queryWiFiModuleSwitch", "queryAccessPointInfo"))
    }

    /**
     * 获取当前 WiFi 热点配置（AuthMode/EncrypType/Password/SSID 等），
     * 用于修改单项参数时保留其他参数不被重置
     */
    internal suspend fun getCurrentWifiConfig(): Map<String, String> {
        val info = getWifiModuleInfo()
        val config = mutableMapOf<String, String>()
        if (info != null) {
            val list = info["ResponseList"]?.jsonArray
            if (list != null && list.isNotEmpty()) {
                val allAps = list.mapNotNull { try { it.jsonObject } catch (_: Exception) { null } }
                val ap = allAps.firstOrNull {
                    it["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull == "1"
                } ?: allAps.firstOrNull()
                if (ap != null) {
                    ap["AuthMode"]?.jsonPrimitive?.contentOrNull?.let { config["AuthMode"] = it }
                    ap["EncrypType"]?.jsonPrimitive?.contentOrNull?.let { config["EncrypType"] = it }
                    ap["SSID"]?.jsonPrimitive?.contentOrNull?.let { config["SSID"] = it }
                    ap["Password"]?.jsonPrimitive?.contentOrNull?.let { config["Password"] = it }
                    ap["ChipIndex"]?.jsonPrimitive?.contentOrNull?.let { config["ChipIndex"] = it }
                    ap["ApMaxStationNumber"]?.jsonPrimitive?.contentOrNull?.let { config["ApMaxStationNumber"] = it }
                    ap["ApBroadcastDisabled"]?.jsonPrimitive?.contentOrNull?.let { config["ApBroadcastDisabled"] = it }
                }
            }
        }
        return config
    }

    suspend fun getWifiSettings(): JsonObject? {
        return client.query(listOf(
            "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
            "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid"
        ))
    }

    suspend fun getConnectedClients(): JsonObject? {
        return client.query(listOf("wifi_access_sta_num", "station_list", "sta_ip_status", "lan_station_list", "hostNameList"))
    }

    // ==================== WiFi 设置 ====================

    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "setAccessPointInfo")
        val current = if (authMode == null || ssid == null) getCurrentWifiConfig() else emptyMap()

        val effectiveSsid = ssid?.trim() ?: current["SSID"]?.trim()
        effectiveSsid?.let { params["SSID"] = it }

        val effectiveAuth = authMode ?: current["AuthMode"]
        if (effectiveAuth != null) {
            params["AuthMode"] = effectiveAuth
            if (effectiveAuth == "OPEN") {
                params["EncrypType"] = "NONE"
            } else {
                params["EncrypType"] = encrypType ?: current["EncrypType"] ?: "CCMP"
            }
        } else {
            params["AuthMode"] = "WPA2PSK"
            params["EncrypType"] = encrypType ?: "CCMP"
        }

        val effectiveEncryp = params["EncrypType"]
        if (effectiveAuth != "OPEN" && effectiveEncryp != "NONE") {
            val effectivePwd = passphrase ?: current["Password"]?.let { client.base64Decode(it) }
            effectivePwd?.let { params["Password"] = client.base64Encode(it) }
        }

        maxStaNum?.let { params["ApMaxStationNumber"] = it.toString() }
        params["ApBroadcastDisabled"] = (broadcastDisabled ?: current["ApBroadcastDisabled"]?.toIntOrNull() ?: 0).toString()
        params["ApIsolate"] = "0"
        params["AccessPointIndex"] = "0"
        params["ChipIndex"] = chipIndex ?: current["ChipIndex"] ?: "0"
        AppLogger.i(tag, "setWifiConfig: SSID=${params["SSID"]} Auth=${params["AuthMode"]} Enc=${params["EncrypType"]}")
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiAdvConfig(
        channel: String? = null, bandwidth: Int? = null,
        mode: String? = null, countryCode: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "SET_WIFI_ADV_CONFIG")
        channel?.let { params["wifiChannel"] = it }
        bandwidth?.let { params["wifiBandwidth"] = it.toString() }
        mode?.let { params["wifiMode"] = it }
        countryCode?.let { params["wifiCountryCode"] = it }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiPower(level: Int): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_WIFI_POWER",
            "wifiPowerLevel" to level.toString()
        )))
    }

    suspend fun setWifiGuest(
        enabled: Boolean? = null, ssid: String? = null, authMode: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "SET_WIFI_GUEST")
        enabled?.let { params["wifiGuestEnable"] = if (it) "1" else "0" }
        ssid?.let { params["wifiGuestSSID"] = it }
        authMode?.let { params["wifiGuestAuthMode"] = it }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiSSID(ssid: String): Boolean {
        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = current["EncrypType"] ?: "CCMP"
        val chipIndex = current["ChipIndex"] ?: "0"
        val params = mutableMapOf(
            "isTest" to "false", "goformId" to "setAccessPointInfo",
            "SSID" to ssid.trim(), "AuthMode" to authMode, "EncrypType" to encrypType,
            "AccessPointIndex" to "0", "ChipIndex" to chipIndex,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0"
        )
        if (authMode != "OPEN" && encrypType != "NONE") {
            current["Password"]?.let { params["Password"] = it }
        }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiEnabled(enabled: Boolean): Boolean {
        return if (enabled) {
            client.isGoformSuccess(client.goformPost(mapOf(
                "isTest" to "false", "goformId" to "switchWiFiChip",
                "ChipEnum" to "chip1", "GuestEnable" to "0"
            )))
        } else {
            client.isGoformSuccess(client.goformPost(mapOf(
                "isTest" to "false", "goformId" to "switchWiFiModule",
                "SwitchOption" to "0"
            )))
        }
    }

    suspend fun setWifiPassword(password: String): Boolean {
        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = if (authMode == "OPEN") "NONE" else (current["EncrypType"] ?: "CCMP")
        val chipIndex = current["ChipIndex"] ?: "0"
        val params = mutableMapOf(
            "isTest" to "false", "goformId" to "setAccessPointInfo",
            "Password" to client.base64Encode(password),
            "AuthMode" to authMode, "EncrypType" to encrypType,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0", "AccessPointIndex" to "0",
            "ChipIndex" to chipIndex
        )
        current["SSID"]?.let { params["SSID"] = it }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun switchWifiChip(chip: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "switchWiFiChip",
            "ChipEnum" to chip, "GuestEnable" to "0"
        )))
    }

    suspend fun setWifiNfc(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "WIFI_NFC_SET",
            "web_wifi_nfc_switch" to if (enabled) "1" else "0"
        )))
    }

    suspend fun setWifiSleep(time: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_WIFI_SLEEP_INFO",
            "sleep_sysIdleTimeToSleep" to time
        )))
    }
}
