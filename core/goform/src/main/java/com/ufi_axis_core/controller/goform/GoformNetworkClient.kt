package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey



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
class GoformNetworkClient(
    private val client: GoformClient,
    profile: DeviceProfile?,
) {
    private val writer = GoformSettingWriter(client, profile)


    companion object {
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

    /**
     * 设置承载偏好（网络模式）。
     *
     * @param preference contract 的 `NetworkMode` 别名（大小写不敏感）**或**设备侧的
     *   `BearerPreference` 值。别名 → 设备值的映射在 profile 的 WriteSpec 里，
     *   对 Bearer 取值本身幂等，因此已经调过 `NetworkMode.toBearer()` 的调用方不受影响。
     *   映射不出设备支持的值时直接拒绝下发，不再把猜的值丢给设备（计划书 2.6）。
     *
     * 返回 [WriteOutcome]：`Rejected` 表示值域非法、根本没发请求，route 应回
     * 400 `OUT_OF_RANGE` 而不是 500（计划书 9.5）。
     */
    suspend fun setBearerPreference(preference: String): WriteOutcome =
        writer.writeChecked(SettingKey.NETWORK_MODE, preference)

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

    // 频段号值域由 profile 的 validate 兜（1..255 的纯数字列表），非法值不发请求，
    // 返回 Rejected 让 route 回 400 OUT_OF_RANGE（计划书 9.5）。

    suspend fun lockLteBands(bands: String): WriteOutcome =
        writer.writeChecked(SettingKey.BAND_LOCK_LTE, bands)

    suspend fun lockNrBands(bands: String): WriteOutcome =
        writer.writeChecked(SettingKey.BAND_LOCK_NR, bands)


    /** 解锁全部频段 = 设置为全量频段列表 */
    suspend fun unlockAllBands(): Boolean {
        val lteOk = lockLteBands(LTE_ALL_BANDS).ok
        val nrOk = lockNrBands(NR_ALL_BANDS).ok
        return lteOk && nrOk
    }

    // ==================== 流量管理 ====================

    /**
     * 设置流量限额（含自动清零）。
     *
     * 入参是**结构化**的（数值 + 单位），设备侧的 `"470_1024"` 复合串由
     * `WriteSpec.encode` 拼（计划书 2.8）。null = 该项不下发，保持设备当前值。
     *
     * 六个参数走**同一条** `DATA_LIMIT_SETTING`（2026-08-30 真机抓包确认，
     * 自动清零与清零日也在这条里），不要拆成两次写。
     *
     * @param limitUnit `"MB"` / `"GB"` / `"TB"`；省略时按 GB 处理。
     *   注意这与设备侧的 `data_volume_limit_unit` 不是一回事 —— 后者是限额模式
     *   （`data` 按流量 / `time` 按时长），真实单位藏在 size 的乘数里。
     */
    suspend fun setDataLimit(
        enabled: Boolean,
        limitValue: Long? = null, limitUnit: String? = null,
        alertPercent: String? = null, autoClear: Boolean? = null,
        clearDate: String? = null
    ): WriteOutcome = writer.writeChecked(SettingKey.TRAFFIC_LIMIT, buildMap {
        put("enabled", enabled)
        limitValue?.let { put("limit_value", it) }
        limitUnit?.let { put("limit_unit", it) }
        alertPercent?.let { put("alert_percent", it) }
        autoClear?.let { put("auto_clear", it) }
        clearDate?.let { put("clear_date", it) }
    })




    /**
     * 手动校准流量。
     *
     * @param target 校准哪个量：`"data"`（流量）或 `"time"`（时长）
     * @param value 校准成多少（纯数字字符串）
     *
     * 设备侧要 `data` 与 `time` 两个字段同时在场（未校准的填 `"0"`），
     * 这份补零规则在 profile 的 WriteSpec 里，调用方不用管（计划书 2.6）。
     */
    suspend fun calibrateFlow(target: String = "data", value: String = "0"): WriteOutcome =
        writer.writeChecked(SettingKey.FLOW_CALIBRATION, mapOf("target" to target, "value" to value))

    // ==================== 漫游 ====================


    suspend fun setRoaming(enabled: Boolean): Boolean = writer.write(SettingKey.ROAM, enabled)
}

