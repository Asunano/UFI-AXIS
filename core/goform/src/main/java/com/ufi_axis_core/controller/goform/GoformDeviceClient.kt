package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey


/**
 * Goform 设备控制客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 重启/关机/恢复出厂
 * - USB 模式/ADB 调试
 * - 指示灯/性能模式
 * - Samba 文件共享
 * - 定时重启
 * - 密码修改/FOTA
 * - DHCP 设置
 * - 基站锁定/解锁
 *
 * 已迁进 [DeviceProfile.writeSpec] 的写操作（指示灯、性能模式、Samba、USB 调试、
 * 定时重启）在这里只剩一行转发 —— 命令名、参数键与布尔编码都在 profile 里，
 * 换设备只改 profile（计划书阶段 2）。
 */

class GoformDeviceClient(
    private val client: GoformClient,
    profile: DeviceProfile?,
) {
    private val writer = GoformSettingWriter(client, profile)


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

    // 2026-08-22：setUsbPortSwitch 已删除——USB_PORT_SETTING 自动切换功能废弃
    // （切换会重启 adbd，打断用户正在进行的 adb 操作）。setDebugMode 保留为
    // 用户主动的调试开关（/api/device/debug）。

    suspend fun setDebugMode(enabled: Boolean): Boolean = writer.write(SettingKey.USB_PORT, enabled)

    // ==================== 设备设置 ====================

    suspend fun setIndicatorLight(enabled: Boolean): Boolean = writer.write(SettingKey.LED, enabled)

    /** @param mode 0 = 均衡，1 = 高性能 */
    suspend fun setPerformanceMode(mode: Int): Boolean =
        writer.write(SettingKey.PERFORMANCE_MODE, mode)

    suspend fun setSambaSetting(enabled: Boolean): Boolean = writer.write(SettingKey.SAMBA, enabled)

    suspend fun setRestartSchedule(enabled: Boolean, time: String): WriteOutcome =
        writer.writeChecked(SettingKey.RESTART_SCHEDULE, mapOf("enabled" to enabled, "time" to time))


    // ==================== 密码/FOTA ====================

    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "CHANGE_PASSWORD",
            "oldPassword" to client.sha256Hex(oldPassword).uppercase(),
            "newPassword" to client.sha256Hex(newPassword).uppercase()
        )))
    }

    /**
     * @param enabled true = 允许 FOTA 自动升级（正向语义）。
     *   对外 `POST /api/device/fota` 的旧字段是反的，那次翻转只在 route 里做。
     */
    suspend fun setFotaEnabled(enabled: Boolean): Boolean =
        writer.write(SettingKey.FOTA_AUTO_UPDATE, enabled)

    // ==================== DHCP ====================

    /**
     * LAN / DHCP 设置。
     *
     * @param dhcpType `"SERVER"`（开启 DHCP 服务器）/ `"DISABLE"`。设备侧那两个跟着它走的
     *   隐含参数（`dhcp_reboot_flag` / `mac_ip_reset`）由 profile 的 `WriteSpec` 补齐。
     */
    suspend fun setDhcpSetting(
        lanIp: String, lanNetmask: String, dhcpType: String,
        dhcpStart: String, dhcpEnd: String, dhcpLease: String
    ): WriteOutcome = writer.writeChecked(SettingKey.LAN_DHCP, mapOf(
        "lan_ip" to lanIp, "lan_netmask" to lanNetmask, "dhcp_type" to dhcpType,
        "dhcp_start" to dhcpStart, "dhcp_end" to dhcpEnd, "dhcp_lease" to dhcpLease,
    ))


    // ==================== 基站锁定 ====================

    /** @param networkType 制式名（`"LTE"` / `"NR"`）；数字 RAT 码只存在于 profile 里。 */
    suspend fun cellLock(pci: String, earfcn: String, networkType: String): WriteOutcome =
        writer.writeChecked(SettingKey.CELL_LOCK, mapOf(
            "pci" to pci, "earfcn" to earfcn, "network_type" to networkType,
        ))

    suspend fun unlockAllCell(): Boolean = writer.write(SettingKey.CELL_UNLOCK, emptyMap())
}
