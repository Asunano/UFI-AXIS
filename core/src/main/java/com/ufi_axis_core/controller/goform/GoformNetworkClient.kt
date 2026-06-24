package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger

/**
 * Goform 网络控制客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 移动数据开关
 * - 网络制式/承载选择
 * - 连接/断网/连接模式
 * - LTE/NR 频段锁定
 * - 流量限额设置/校准
 * - APN 配置管理
 * - 漫游设置
 */
class GoformNetworkClient(private val client: GoformClient) {

    companion object {
        private const val TAG = "GoformNetworkClient"
        /** ZTE MU300 全 LTE 频段（解锁时使用） */
        const val LTE_ALL_BANDS = "1,3,5,8,34,38,39,40,41"
        /** ZTE MU300 全 NR 频段（解锁时使用） */
        const val NR_ALL_BANDS = "1,5,8,28,41,78"
    }

    // ==================== 移动数据 ====================

    suspend fun setMobileData(enabled: Boolean): Boolean {
        val primaryId = if (enabled) "CONNECT_NETWORK" else "DISCONNECT_NETWORK"
        if (client.isGoformSuccess(client.goformPost(mapOf("isTest" to "false", "notCallback" to "true", "goformId" to primaryId)))) return true
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_DATA_ENABLED",
            "data" to if (enabled) "1" else "0"
        )))
    }

    // ==================== 承载/连接 ====================

    suspend fun setBearerPreference(preference: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_BEARER_PREFERENCE",
            "BearerPreference" to preference
        )))
    }

    suspend fun connectNetwork(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "notCallback" to "true", "goformId" to "CONNECT_NETWORK"
        )))
    }

    suspend fun disconnectNetwork(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "notCallback" to "true", "goformId" to "DISCONNECT_NETWORK"
        )))
    }

    suspend fun setConnectionMode(mode: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_CONNECTION_MODE",
            "ConnectionMode" to mode
        )))
    }

    // ==================== 频段锁定 ====================

    suspend fun lockLteBands(bands: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "LTE_BAND_LOCK",
            "lte_band_lock" to bands
        )))
    }

    suspend fun lockNrBands(bands: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "NR_BAND_LOCK",
            "nr_band_lock" to bands
        )))
    }

    /** 解锁全部频段 = 设置为全量频段列表 */
    suspend fun unlockAllBands(): Boolean {
        val lteOk = lockLteBands(LTE_ALL_BANDS)
        val nrOk = lockNrBands(NR_ALL_BANDS)
        return lteOk && nrOk
    }

    // ==================== 流量管理 ====================

    suspend fun setDataLimit(
        enabled: Boolean,
        limitSize: String? = null, limitUnit: String? = null,
        alertPercent: String? = null, autoClear: Boolean? = null,
        clearDate: String? = null
    ): Boolean {
        val params = mutableMapOf(
            "isTest" to "false", "goformId" to "DATA_LIMIT_SETTING",
            "data_volume_limit_switch" to if (enabled) "1" else "0"
        )
        limitSize?.let { params["data_volume_limit_size"] = it }
        limitUnit?.let { params["data_volume_limit_unit"] = it }
        alertPercent?.let { params["data_volume_alert_percent"] = it }
        autoClear?.let { params["wan_auto_clear_flow_data_switch"] = if (it) "on" else "off" }
        clearDate?.let { params["traffic_clear_date"] = it }
        params["notify_deviceui_enable"] = "0"
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun calibrateFlow(way: String = "data", data: String = "0", time: String = "0"): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "FLOW_CALIBRATION_MANUAL",
            "calibration_way" to way, "time" to time, "data" to data
        )))
    }

    // ==================== APN 配置 ====================

    /**
     * 保存/切换 APN 配置（与参考项目 saveAPNProfile + switchAPNAuto 完全一致）
     *
     * 参考: UFI-TOOLS-REF requests.js saveAPNProfile + switchAPNAuto
     * goformId: APN_PROC_EX
     *
     * @param autoSelect true=切自动模式, false=手动保存配置
     * @param index 配置槽位 (0..19)
     * @param profileName 配置名称
     * @param apn APN 值
     * @param username PPP 用户名
     * @param password PPP 密码
     * @param authType 认证方式: "none"|"pap"|"chap"
     * @param pdpType PDP 类型: "IP"|"IPv6"|"IPv4v6"
     */
    suspend fun setApnConfig(
        autoSelect: Boolean? = null,
        index: Int = 0,
        profileName: String? = null,
        apn: String? = null,
        username: String? = null,
        password: String? = null,
        authType: String? = null,
        pdpType: String? = null
    ): Boolean {
        if (autoSelect == true) {
            // 切自动模式：只需 apn_mode=auto
            return client.isGoformSuccess(client.goformPost(mapOf(
                "isTest" to "false", "goformId" to "APN_PROC_EX",
                "apn_mode" to "auto",
                "index" to index.toString()
            )))
        }

        // autoSelect == false 或 null
        // 如果没有传入 APN 或 profileName，说明只是要切换模式，不是要保存配置
        // 此时必须使用 apn_action="set_default" 而非 "save"，否则会
        // 用空 APN 覆盖 index 槽位，导致 Modem PDP 激活失败 → SIM 无限重置
        val hasProfileData = apn != null || profileName != null
        if (!hasProfileData) {
            AppLogger.w(TAG, "setApnConfig: autoSelect=false without profile data, using set_default to avoid empty APN overwrite")
            return switchApnManual(index)
        }

        // 有完整配置数据时，执行保存（参考 saveAPNProfile）
        val params = mutableMapOf("isTest" to "false", "goformId" to "APN_PROC_EX")
        val pdp = pdpType ?: "IPv4v6"
        params["apn_mode"] = "manual"
        params["apn_action"] = "save"
        params["apn_select"] = "manual"
        params["index"] = index.toString()

        // 基础字段（参考 baseProfile）
        params["wan_dial"] = "*99#"
        params["apn_wan_dial"] = "*99#"
        params["apn_pdp_type"] = pdp
        params["pdp_type"] = pdp
        params["apn_pdp_select"] = "auto"
        params["apn_pdp_addr"] = ""
        params["pdp_select"] = "auto"
        params["pdp_addr"] = ""

        // 配置名
        profileName?.let { params["profile_name"] = it }

        // IPv4 字段（参考 v4Profile）
        apn?.let {
            params["apn_wan_apn"] = it; params["wan_apn"] = it
        }
        val auth = authType ?: "none"
        params["apn_ppp_auth_mode"] = auth; params["ppp_auth_mode"] = auth
        username?.let {
            params["apn_ppp_username"] = it; params["ppp_username"] = it
        }
        password?.let {
            params["apn_ppp_passwd"] = it; params["ppp_passwd"] = it
        }
        params["dns_mode"] = "auto"
        params["prefer_dns_manual"] = ""
        params["standby_dns_manual"] = ""

        // IPv6 字段（参考 v6Profile，IPv4v6 或 IPv6 时都需要）
        if (pdp == "IPv4v6" || pdp == "IPv6") {
            apn?.let {
                params["apn_ipv6_wan_apn"] = it; params["ipv6_wan_apn"] = it
            }
            params["apn_ipv6_ppp_auth_mode"] = auth; params["ipv6_ppp_auth_mode"] = auth
            username?.let {
                params["apn_ipv6_ppp_username"] = it; params["ipv6_ppp_username"] = it
            }
            password?.let {
                params["apn_ipv6_ppp_passwd"] = it; params["ipv6_ppp_passwd"] = it
            }
            params["ipv6_dns_mode"] = "auto"
            params["ipv6_prefer_dns_manual"] = ""
            params["ipv6_standby_dns_manual"] = ""
        }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun deleteApnProfile(index: Int): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "APN_PROC_EX",
            "apn_mode" to "manual", "apn_action" to "delete",
            "index" to index.toString()
        )))
    }

    suspend fun switchApnManual(index: Int = 0): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "APN_PROC_EX",
            "apn_mode" to "manual", "apn_action" to "set_default",
            "set_default_flag" to "1", "apn_pdp_type" to "",
            "index" to index.toString()
        )))
    }

    // ==================== 漫游 ====================

    suspend fun setRoaming(enabled: Boolean): Boolean {
        val roamVal = if (enabled) "on" else "off"
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_CONNECTION_MODE",
            "ConnectionMode" to "auto_dial",
            "roam_setting_option" to roamVal,
            "dial_roam_setting_option" to roamVal
        )))
    }
}
