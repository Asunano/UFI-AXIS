package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.devicespi.WriteOutcome

/**
 * network 域（移动数据 / 制式 / 拨号 / 频段 / 流量 / 漫游）的设备适配接口（2026-09-25 批 A2b）。
 *
 * 由 [DeviceAdapter.network] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformNetworkClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 覆盖面 = `GoformNetworkClient` 的全部写方法（11 个）。方法签名、参数名、返回类型与 KDoc
 * 逐字照抄那边，本批是纯接缝迁移，行为与语义不变 —— 包括「哪些回 [Boolean]、哪些回
 * [WriteOutcome]」这个既有的不齐整（回 [WriteOutcome] 的**五个**是已经接了值域校验三态的：
 * 其余六个仍是两态 [Boolean]）。在这里统一它就不是"纯接缝迁移"了，route 的响应形状会跟着变。
 *
 * ## 两处与 `GoformNetworkClient` 不同的地方
 *
 * 1. **频段锁定收 [BandSelection] 而不是掩码串**：那边还有一对
 *    `lteAllBands()` / `nrAllBands()` 返回「下发给设备的频段全集掩码」，是 goform 的实现细节，
 *    不上本接口（理由见 [BandSelection]）。
 * 2. **[lockLteBands] / [lockNrBands] 仍是两个方法**，没有合并成一个 `lockBands(lte, nr)`：
 *    现有调用点分别取两个 [WriteOutcome]、分别判 `ok`、再分别看是不是
 *    [WriteOutcome.Rejected]。合并会把错误处理粒度从「两个 RAT 各自一份结论」压成一份，
 *    那是行为变更。
 *
 * ## 已知的契约泄漏（本批**刻意不修**）
 *
 * [setBearerPreference] / [setConnectionMode] / [calibrateFlow] 三处的取值域里带着设备侧原值，
 * 各自的 KDoc 写明了。改成中立枚举会动对外契约（route 收什么、客户端发什么），
 * 不在纯接缝批次里做。
 */
interface NetworkControl {

    // ==================== 移动数据 ====================

    /** 开关移动数据。设备侧「开 / 关是两条不同命令」「主命令不成功要发兜底命令」都在实现侧。 */
    suspend fun setMobileData(enabled: Boolean): Boolean

    // ==================== 承载/连接 ====================

    /**
     * 设置承载偏好（网络模式）。
     *
     * @param preference 值域是**两者的并集**：contract 的 `NetworkMode` 别名（大小写不敏感）
     *   **或**设备侧的 `BearerPreference` 原值（如 `Only_5G`）。
     *
     * ⚠ **已知的契约泄漏**：后半截（设备侧原值）是 goform 的词汇，却留在了协议无关的签名上。
     * 它是兼容期的产物 —— 旧客户端直接发设备原值，映射对 Bearer 取值本身幂等，所以两种都收。
     * 接第二种协议时**必须先在这里立中立别名**（把设备原值那一路收进各自的实现侧），
     * 别把本方法当成"协议无关"的。
     *
     * 返回 [WriteOutcome]：`Rejected` 表示值域非法、根本没发请求，route 应回
     * 400 `OUT_OF_RANGE` 而不是 500。
     */
    suspend fun setBearerPreference(preference: String): WriteOutcome

    /** 手动拨号。与 [setMobileData] 是**两个独立入口**（这一项没有兜底命令），不要合并。 */
    suspend fun connectNetwork(): Boolean

    /** 挂断。与 [connectNetwork] 同一项设置，由实现侧按取值选命令。 */
    suspend fun disconnectNetwork(): Boolean

    /**
     * 连接模式（自动 / 手动拨号）。
     *
     * @param mode 当前值域是**设备侧原值** `"auto_dial"` / `"manual_dial"`。
     *
     * ⚠ **已知的契约泄漏**：这是 goform 的词汇。不改成枚举是因为它会动对外契约 ——
     * `POST` 到 `/api/network/connection-mode` 的入参归一化目前就落在 route 里
     * （两端历史上各发一套别名，route 统一映射成这两个设备原值）。
     * 接第二种协议时必须先在这里立中立别名，再把设备原值的映射搬进各自的实现侧。
     */
    suspend fun setConnectionMode(mode: String): Boolean

    // ==================== 频段锁定 ====================

    /**
     * 锁定 LTE 频段。
     *
     * @param bands 要锁哪些频段的**意图**（见 [BandSelection]）：[BandSelection.All] =
     *   解除该 RAT 的限制，[BandSelection.Only] = 明确的频段列表（空串同样是解除限制）。
     *
     * 值域校验在实现侧（非法频段号不下发，回 [WriteOutcome.Rejected] 让 route 回
     * 400 `OUT_OF_RANGE`）。
     */
    suspend fun lockLteBands(bands: BandSelection): WriteOutcome

    /** 锁定 NR 频段。语义同 [lockLteBands]。 */
    suspend fun lockNrBands(bands: BandSelection): WriteOutcome

    /**
     * 解锁全部频段 = 两个 RAT 都按 [BandSelection.All] 下发。
     *
     * ⚠ 本方法**当前没有上层调用点**：用户点「解锁全部频段」走的是
     * `NetworkController.lockBands(unlockAll = true)`（它还要重启网络协议栈）。
     * 照搬过来是为了让 `GoformNetworkClient` 的写方法在本接口上一个不缺；
     * 两态 [Boolean]（`lte && nr`）的返回也逐字保留。
     */
    suspend fun unlockAllBands(): Boolean

    // ==================== 流量管理 ====================

    /**
     * 设置流量限额（含自动清零）。
     *
     * 入参是**结构化**的（数值 + 单位），设备侧的复合串由实现侧拼。
     * null = 该项不下发，保持设备当前值。六个参数是**一次**写入，不要拆成两次。
     *
     * @param limitUnit `"MB"` / `"GB"` / `"TB"`；省略时按 GB 处理。
     */
    suspend fun setDataLimit(
        enabled: Boolean,
        limitValue: Long? = null, limitUnit: String? = null,
        alertPercent: String? = null, autoClear: Boolean? = null,
        clearDate: String? = null
    ): WriteOutcome

    /**
     * 手动校准流量。
     *
     * @param target 校准哪个量：`"data"`（流量）或 `"time"`（时长）。
     *   ⚠ **已知的契约泄漏**：这两个取值是设备侧词汇，且是对外入参（route 原样透传），
     *   与 [setConnectionMode] 同一类问题，接第二种协议时先在这里立中立别名。
     * @param value 校准成多少（纯数字字符串）
     *
     * 「设备侧要 data 与 time 两个字段同时在场、未校准的填 0」这条补零规则在实现侧。
     */
    suspend fun calibrateFlow(target: String = "data", value: String = "0"): WriteOutcome

    // ==================== 漫游 ====================

    suspend fun setRoaming(enabled: Boolean): Boolean
}
