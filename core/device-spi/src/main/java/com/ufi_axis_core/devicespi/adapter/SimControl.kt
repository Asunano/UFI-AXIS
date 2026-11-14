package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.devicespi.WriteOutcome

/**
 * SIM 域的设备适配接口（2026-09-25 批 A1）。
 *
 * 由 [DeviceAdapter.sim] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformSimClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 方法签名照抄 `GoformSimClient.switchSimSlot`，本批是纯接缝迁移，行为与语义不变。
 */
interface SimControl {

    /**
     * 切换 SIM 卡槽。
     *
     * @param slot 对外的卡槽序号（`"1"`/`"2"`/`"3"`）或 `"external"`。
     *   设备侧的运营商预置位（0=移动 1=电信 2=联通 11=外置）只出现在 profile 的
     *   WriteSpec 里；兼容期内旧客户端直接发的设备原值也仍能通过（计划书 2.6）。
     */
    suspend fun switchSimSlot(slot: String): WriteOutcome
}
