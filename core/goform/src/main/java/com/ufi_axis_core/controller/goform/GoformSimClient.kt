package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey


/**
 * Goform SIM 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - SIM 卡槽切换
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
class GoformSimClient(
    private val client: GoformTransport,
    commandProfile: DeviceProfile,
) {

    private val writer = GoformSettingWriter(client, commandProfile)


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
