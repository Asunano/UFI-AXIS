package com.ufi_axis_core.controller.goform

/**
 * Goform SIM 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - SIM 卡槽切换
 */
class GoformSimClient(private val client: GoformClient) {

    /**
     * 切换 SIM 卡槽 (双卡设备)
     * @param slot 0=移动, 1=电信, 2=联通, 11=外置
     */
    suspend fun switchSimSlot(slot: String): Boolean {
        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_SIM_SLOT",
            "sim_slot" to slot
        )))
    }
}
