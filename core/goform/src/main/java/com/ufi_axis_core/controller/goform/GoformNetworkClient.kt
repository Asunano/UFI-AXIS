package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.devicespi.WriteOutcome
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
 *
 * @param commandProfile 命令表来源，**非空**；由装配层传选中插件的 profile
 *
 * ## 为什么本类**不收**可空 `profile`（阶段 2 批 D1 裁决）
 *
 * 本类**目前没有读侧归一化路径** —— 它不持有 [GoformFieldMapper]，所有方法都是写操作。
 * 可空 `profile` 的唯一语义是「排障开关关掉了字段归一化」，而那件事在本类里无从生效；
 * 留一个没人用的可空参数只会让下一个人以为「字段归一化在这三个纯写客户端里生效」（错的）。
 *
 * **将来本类长出读侧字段时**（比如 APN 列表回读要归一化），按 [GoformSignalClient] 的形状
 * 把可空 `profile` 加回来 —— 可空那份管归一化、非空那份管命令表，两份都要。
 *
 * [commandProfile]（**非空**）只喂**命令表** —— 写命令的 goformId、参数键、
 * 以及写命令的**参数值域**（[lteAllBands] / [nrAllBands] 的频段全集就是这一类）。
 * 字段归一化可以关，命令表不能关。所以它**没有默认值**：默认值等于把选型逻辑散进每个
 * 客户端的签名，换设备要改 N 处且漏一处不报错（同一条判据见 `DeviceRuntime.commandProfile`）。
 */
class GoformNetworkClient(
    private val client: GoformTransport,
    private val commandProfile: DeviceProfile,
) {

    // 写路径吃的是**命令表**那一份（非空）：排障开关不该改变「往设备发什么命令」，
    // 这是阶段 2 批 D1 修掉的 P1-31（原来传的是可空 profile，writer 内部 `?: ZteGoformProfile` 兜底）。
    private val writer = GoformSettingWriter(client, commandProfile)


    private companion object {
        const val TAG = "GoformNetwork"
    }


    // ==================== 移动数据 ====================

    /**
     * 开关移动数据。
     *
     * 两件设备事实都在 [SettingKey.MOBILE_DATA] 的 WriteSpec 里：开/关是两条不同的 goformId
     * （`commandOf`），主命令不成功还要发一条 `SET_DATA_ENABLED` 兜底（`fallback`）。
     * 所以这里没有 if —— 兜底的触发条件覆盖「非 Ok 的全部分支」，与改造前「第一条返回
     * 不成功或 null 就发第二条」等价（见 [GoformSettingWriter.writeChecked]）。
     */
    suspend fun setMobileData(enabled: Boolean): Boolean = writer.write(SettingKey.MOBILE_DATA, enabled)

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

    /**
     * 手动拨号。
     *
     * 走 [SettingKey.PPP_DIAL] 而不是 [SettingKey.MOBILE_DATA]：命令名与参数完全相同，
     * 但这一项**没有 `SET_DATA_ENABLED` 兜底** —— 现状这两个入口失败就是失败。
     * 合并进 MOBILE_DATA 等于给它们偷偷加一条从来没发过的命令（会额外改一次数据开关）。
     */
    suspend fun connectNetwork(): Boolean = writer.write(SettingKey.PPP_DIAL, true)

    /** 挂断。与 [connectNetwork] 同一个 key，由 `commandOf` 按取值选 `DISCONNECT_NETWORK`。 */
    suspend fun disconnectNetwork(): Boolean = writer.write(SettingKey.PPP_DIAL, false)

    /** @param mode 设备侧原值 `auto_dial` / `manual_dial`（取值域校验在 profile 的 validate 里）。 */
    suspend fun setConnectionMode(mode: String): Boolean =
        writer.write(SettingKey.CONNECTION_MODE, mode)

    // ==================== 频段锁定 ====================

    // 频段号值域由 profile 的 validate 兜（1..255 的纯数字列表），非法值不发请求，
    // 返回 Rejected 让 route 回 400 OUT_OF_RANGE（计划书 9.5）。

    suspend fun lockLteBands(bands: String): WriteOutcome =
        writer.writeChecked(SettingKey.BAND_LOCK_LTE, bands)

    suspend fun lockNrBands(bands: String): WriteOutcome =
        writer.writeChecked(SettingKey.BAND_LOCK_NR, bands)


    /**
     * 「解锁全部 LTE 频段」时要下发的取值。
     *
     * 值来自 [commandProfile]（命令表那一份，非空）—— 频段全集是 `LTE_BAND_LOCK` 这条
     * **写命令的参数值域**，属于命令表范畴：字段归一化可以关，命令表不能关。
     *
     * ## profile 没给掩码（`null`）时返回空串
     *
     * 空串在设备侧就是「不发限制」（`ZteGoformProfile.validateBandList` 把空串当解锁放过，
     * `encode` 原样发 `lte_band_lock=`）—— 与「空串 = 不发限制」**同一侧**，
     * 也就是「不下发该 RAT 的全集」。刻意**不**抛异常（用户点一下「解锁」不该崩在写路径上），
     * 也刻意**不**把 `null` 当成「有全集」去发一个空串冒充全集 —— 那两件事语义不同，
     * 所以这里必须留一行 WARN（见 [DeviceProfile.lteAllBandsMask] 的 KDoc）。
     *
     * 给 `NetworkController` 用的也是这两个方法：跨模块直读 companion 常量已经删掉
     * （计划书 §15 的 P0-1 / 任务 2.9）。
     */
    fun lteAllBands(): String = allBandsOf(commandProfile.lteAllBandsMask(), "LTE")

    /** 「解锁全部 NR 频段」时要下发的取值。语义同 [lteAllBands]。 */
    fun nrAllBands(): String = allBandsOf(commandProfile.nrAllBandsMask(), "NR")

    private fun allBandsOf(mask: String?, rat: String): String {
        if (mask == null) {
            AppLogger.w(
                TAG,
                "${commandProfile.id} 未提供 $rat 频段全集掩码，本次「解锁全部频段」不下发 $rat 全集" +
                    "（按空串 = 不发限制处理）"
            )
            return ""
        }
        return mask
    }

    /** 解锁全部频段 = 设置为全量频段列表 */
    suspend fun unlockAllBands(): Boolean {
        val lteOk = lockLteBands(lteAllBands()).ok
        val nrOk = lockNrBands(nrAllBands()).ok
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

