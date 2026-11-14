package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.devicespi.WriteOutcome

/**
 * device 域（设备本体控制）的设备适配接口（2026-09-25 批 A2a）。
 *
 * 由 [DeviceAdapter.device] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformDeviceClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 覆盖面与 `GoformDeviceClient` 逐一对应（13 个方法，全是写操作）：
 * 重启 / 关机 / 恢复出厂、ADB 调试开关、指示灯 / 性能模式、Samba 文件共享、定时重启、
 * 密码修改 / FOTA、LAN-DHCP 设置、基站锁定与解锁。
 *
 * 方法签名（参数名、参数类型、返回类型）与 KDoc 照抄 `GoformDeviceClient`，
 * 本批是纯接缝迁移，行为与语义不变 —— 包括「哪些方法回 [Boolean]、哪些回
 * [WriteOutcome]」这个既有的不齐整：回 [WriteOutcome] 的三个（[setRestartSchedule] /
 * [setDhcpSetting] / [cellLock]）是已经接了值域校验三态的，其余仍是两态 [Boolean]。
 * 在这里统一它就不是"纯接缝迁移"了（route 的响应形状会跟着变）。
 */
interface DeviceControl {

    // ==================== 系统控制 ====================

    suspend fun rebootDevice(): Boolean

    suspend fun factoryReset(): Boolean

    suspend fun shutdownDevice(): Boolean

    // ==================== USB/ADB ====================

    /**
     * 用户主动的调试开关（`/api/device/debug`）。
     *
     * 2026-08-22：原先还有一个 `setUsbPortSwitch`（USB_PORT_SETTING 自动切换）已删除 ——
     * 切换会重启 adbd，打断用户正在进行的 adb 操作。
     */
    suspend fun setDebugMode(enabled: Boolean): Boolean

    // ==================== 设备设置 ====================

    suspend fun setIndicatorLight(enabled: Boolean): Boolean

    /** @param mode 0 = 均衡，1 = 高性能 */
    suspend fun setPerformanceMode(mode: Int): Boolean

    suspend fun setSambaSetting(enabled: Boolean): Boolean

    suspend fun setRestartSchedule(enabled: Boolean, time: String): WriteOutcome

    // ==================== 密码/FOTA ====================

    /**
     * 修改设备后台管理口令。
     *
     * **哈希留在实现侧**：goform 设备要的是 SHA256 大写十六进制，而这套算法与登录握手共用
     * `GoformTransport.sha256Hex` —— 在 profile 里抄第二份实现就有了两个真源，哪天登录侧
     * 换算法那里不报错、只会静默登不上。顺带明文口令不进 device-schema，少一处泄露面。
     * profile 的 validate 会挡住"忘了哈希直接传明文"（必须是 64 位大写十六进制）。
     * 所以本接口收的是**明文**，哈希是实现细节。
     *
     * retry 是 `NEVER`：改完口令旧会话必然失效，重登会拿着旧口令再登一次（必然失败），
     * 还会多发一次改密请求。
     *
     * **副作用不在本方法里**：改成功后的 `updateGoformPassword()`（内部会 `resetLogin()`）
     * 由调用点 `DeviceRoutes` 的 `POST /api/device/password` 做，它同时还要写
     * `settings.goformPassword` 与清缓存 —— 那三件事是一组，搬一件进来只会让真源变两份。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean

    /**
     * @param enabled true = 允许 FOTA 自动升级（正向语义）。
     *   对外 `POST /api/device/fota` 的旧字段是反的，那次翻转只在 route 里做。
     */
    suspend fun setFotaEnabled(enabled: Boolean): Boolean

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
    ): WriteOutcome

    // ==================== 基站锁定 ====================

    /** @param networkType 制式名（`"LTE"` / `"NR"`）；数字 RAT 码只存在于 profile 里。 */
    suspend fun cellLock(pci: String, earfcn: String, networkType: String): WriteOutcome

    suspend fun unlockAllCell(): Boolean
}
