package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.devicespi.WriteOutcome


/**
 * Goform 设备控制客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 重启/关机/恢复出厂
 * - ADB 调试开关（USB 调试端口）
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
 *
 * @param commandProfile 写命令表来源，**非空、无默认值**；由装配层传选中插件的 profile。
 *   理由（含「为什么不能在 writer 里兜底」）见 [GoformSettingWriter] 的类 KDoc。
 *
 * ## 为什么不收可空 `profile`
 *
 * 本类**目前没有读侧归一化路径**（不持有 [GoformFieldMapper]），可空 profile 的语义
 * 「排障开关关掉了字段归一化」在这里无从生效，留着只会让人误以为归一化在本类里起作用。
 * 将来长出读侧字段时，按 [GoformSignalClient] 的形状把可空 `profile` 加回来。
 */

class GoformDeviceClient(
    private val client: GoformTransport,
    commandProfile: DeviceProfile,
) {

    private val writer = GoformSettingWriter(client, commandProfile)



    // ==================== 系统控制 ====================

    // 三个动作类命令在 profile 里都是 retry = NEVER，所以 writer 走的仍然是
    // 不重试的 goformPost —— 与改造前逐字一致（重发一次 = 再重启一次 / 第二次擦除）。

    suspend fun rebootDevice(): Boolean = writer.write(SettingKey.REBOOT, emptyMap())

    suspend fun factoryReset(): Boolean = writer.write(SettingKey.FACTORY_RESET, emptyMap())

    suspend fun shutdownDevice(): Boolean = writer.write(SettingKey.SHUTDOWN, emptyMap())

    // ==================== USB/ADB ====================

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

    /**
     * 修改设备后台管理口令。
     *
     * **哈希留在这里**：设备要的是 SHA256 大写十六进制，而这套算法与登录握手共用
     * [GoformTransport.sha256Hex] —— 在 profile 里抄第二份实现就有了两个真源，哪天登录侧
     * 换算法这里不报错、只会静默登不上。顺带明文口令不进 device-schema，少一处泄露面。
     * profile 的 validate 会挡住"忘了哈希直接传明文"（必须是 64 位大写十六进制）。
     *
     * retry 是 `NEVER`：改完口令旧会话必然失效，重登会拿着旧口令再登一次（必然失败），
     * 还会多发一次改密请求。
     *
     * **副作用不在本方法里**：改成功后的 `updateGoformPassword()`（内部会 `resetLogin()`）
     * 由调用点 `DeviceRoutes` 的 `POST /api/device/password` 做，它同时还要写
     * `settings.goformPassword` 与清缓存 —— 那三件事是一组，搬一件进来只会让真源变两份。
     * 这里逐字保持现状，不新增副作用。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean =
        writer.write(SettingKey.BACKEND_PASSWORD, mapOf(
            "old_hash" to client.sha256Hex(oldPassword).uppercase(),
            "new_hash" to client.sha256Hex(newPassword).uppercase(),
        ))

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
