package com.ufi_axis_core.contract

/**
 * 信号量值域（2026-11-09）。
 *
 * 设备固件在异常状态下会返回明显不可能的 SINR（现场见过 30,000,000），
 * Telephony 的 `CellInfo.UNAVAILABLE` 也可能以 `Int.MAX_VALUE` 混进来。
 * 这些值一旦入库，监控图会被一个尖峰拉爆 Y 轴，看起来像"数据坏了"。
 *
 * 这里给出**唯一一份**可接受区间：
 * - 采集层用它丢弃越界值（而不是钳到边界，避免伪造数据）；
 * - `buildRecord` 用它兜底，越界写成 0（既有的"无信号哨兵"，DAO 会过滤）；
 * - 图表 SQL 用同一组区间过滤历史坏行，旧数据不用删除也不会再出现在曲线上。
 *
 * 区间刻意比 3GPP 理论值宽：目标是拦"明显不可能"的量级错误（如 3e7、INT_MAX），
 * 而不是把弱信号/负 SINR 误杀。
 */
object SignalSanity {
    val RSRP = -160..-20
    val RSRQ = -30..0
    val SINR = -50..50
    val RSSI = -120..-20

    /** 需要做值域检查的 signal map key（含 NR/LTE 的分制式 SNR）。 */
    val NUMERIC_KEYS: Set<String> = setOf(
        DeviceFields.Signal.RSRP,
        DeviceFields.Signal.RSRQ,
        DeviceFields.Signal.SINR,
        DeviceFields.Signal.RSSI,
        DeviceFields.Signal.NR_SNR,
        DeviceFields.Signal.LTE_SNR,
    )

    fun isValid(field: String, value: Int): Boolean = when (field) {
        DeviceFields.Signal.RSRP -> value in RSRP
        DeviceFields.Signal.RSRQ -> value in RSRQ
        DeviceFields.Signal.SINR,
        DeviceFields.Signal.NR_SNR,
        DeviceFields.Signal.LTE_SNR -> value in SINR
        DeviceFields.Signal.RSSI -> value in RSSI
        else -> true
    }

    fun takeIfValid(field: String, value: Int?): Int? =
        value?.takeIf { isValid(field, it) }
}
