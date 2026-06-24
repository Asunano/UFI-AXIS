package com.ufi_axis_core.controller.goform

/**
 * Goform 设备控制客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 重启/关机/恢复出厂
 * - USB 模式/ADB 调试
 * - 指示灯/性能模式
 * - Samba 文件共享
 * - 定时重启/主机名
 * - 密码修改/TR-069/FOTA
 * - DHCP 设置
 * - MAC 黑名单管理
 * - 基站锁定/解锁
 */
class GoformDeviceClient(private val client: GoformClient) {

    // ==================== 系统控制 ====================

    suspend fun rebootDevice(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "REBOOT_DEVICE"
        )))
    }

    suspend fun factoryReset(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "FACTORY_RESET"
        )))
    }

    suspend fun shutdownDevice(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SHUTDOWN_DEVICE"
        )))
    }

    // ==================== USB/ADB ====================

    suspend fun setUsbMode(mode: Int): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_USB_NETWORK_PROTOCAL",
            "usb_network_protocal" to mode.toString()
        )))
    }

    suspend fun setUsbPortSwitch(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "USB_PORT_SETTING",
            "usb_port_switch" to if (enabled) "1" else "0"
        )))
    }

    suspend fun setDebugMode(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "USB_PORT_SETTING",
            "usb_port_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== 设备设置 ====================

    suspend fun setIndicatorLight(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "INDICATOR_LIGHT_SETTING",
            "indicator_light_switch" to if (enabled) "1" else "0"
        )))
    }

    suspend fun setPerformanceMode(mode: Int): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "PERFORMANCE_MODE_SETTING",
            "performance_mode" to mode.toString()
        )))
    }

    suspend fun setSambaSetting(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SAMBA_SETTING",
            "samba_switch" to if (enabled) "1" else "0"
        )))
    }

    suspend fun setRestartSchedule(enabled: Boolean, time: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "RESTART_SCHEDULE_SETTING",
            "restart_time" to time,
            "restart_schedule_switch" to if (enabled) "1" else "0"
        )))
    }

    suspend fun setHostname(mac: String, hostname: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "EDIT_HOSTNAME",
            "mac" to mac, "hostname" to hostname
        )))
    }

    // ==================== 密码/FOTA/TR-069 ====================

    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "CHANGE_PASSWORD",
            "oldPassword" to client.sha256Hex(oldPassword).uppercase(),
            "newPassword" to client.sha256Hex(newPassword).uppercase()
        )))
    }

    suspend fun setTr069Config(
        enable: Boolean? = null, url: String? = null,
        username: String? = null, password: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "setTR069Config")
        enable?.let { params["tr069_enable"] = if (it) "1" else "0" }
        url?.let { params["tr069_url"] = it }
        username?.let { params["tr069_username"] = it }
        password?.let { params["tr069_password"] = it }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setFotaEnabled(enabled: Boolean): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SetUpgAutoSetting",
            "UpgMode" to if (enabled) "1" else "0",
            "UpgIntervalDay" to "114514",
            "UpgRoamPermission" to "0"
        )))
    }

    // ==================== DHCP ====================

    suspend fun setDhcpSetting(
        lanIp: String, lanNetmask: String, dhcpType: String,
        dhcpStart: String, dhcpEnd: String, dhcpLease: String
    ): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "DHCP_SETTING",
            "lanIp" to lanIp, "lanNetmask" to lanNetmask,
            "lanDhcpType" to dhcpType,
            "dhcpStart" to dhcpStart, "dhcpEnd" to dhcpEnd,
            "dhcpLease" to dhcpLease,
            "dhcp_reboot_flag" to "1",
            "mac_ip_reset" to if (dhcpType == "SERVER") "1" else "0"
        )))
    }

    // ==================== MAC 黑名单 ====================

    suspend fun setDeviceAccessControlList(aclMode: String, macList: String = "", nameList: String = ""): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "setDeviceAccessControlList",
            "AclMode" to aclMode,
            "BlackMacList" to if (aclMode == "2") macList else "",
            "BlackNameList" to if (aclMode == "2") nameList else "",
            "WhiteMacList" to if (aclMode == "1") macList else "",
            "WhiteNameList" to if (aclMode == "1") nameList else ""
        )))
    }

    // ==================== 基站锁定 ====================

    suspend fun cellLock(pci: String, earfcn: String, rat: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "CELL_LOCK",
            "pci" to pci, "earfcn" to earfcn, "rat" to rat
        )))
    }

    suspend fun unlockAllCell(): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "UNLOCK_ALL_CELL"
        )))
    }
}
