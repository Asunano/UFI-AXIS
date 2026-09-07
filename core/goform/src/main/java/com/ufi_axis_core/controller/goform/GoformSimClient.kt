package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey


/**
 * Goform SIM 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - SIM 卡槽切换
 */
class GoformSimClient(
    private val client: GoformClient,
    profile: DeviceProfile?,
) {
    private val writer = GoformSettingWriter(client, profile)

    /**
     * 切换 SIM 卡槽。
     *
     * @param slot 对外的卡槽序号（`"1"`/`"2"`/`"3"`）或 `"external"`。
     *   设备侧的运营商预置位（0=移动 1=电信 2=联通 11=外置）只出现在 profile 的
     *   WriteSpec 里；兼容期内旧客户端直接发的设备原值也仍能通过（计划书 2.6）。
     */
    suspend fun switchSimSlot(slot: String): WriteOutcome =
        writer.writeChecked(SettingKey.SIM_SLOT, slot)
}
