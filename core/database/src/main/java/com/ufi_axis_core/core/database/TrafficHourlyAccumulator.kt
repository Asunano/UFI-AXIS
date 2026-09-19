package com.ufi_axis_core.core.database

import java.time.Instant
import java.time.ZoneId

/**
 * 每小时流量用量的**纯决策逻辑**（无 IO、无时钟、无 Android 依赖，可直接单测）。
 *
 * goform 只给「当月累计」，所以每小时用量只能靠相邻两次采样求差。求差这件事本身有四种
 * 会算出错误数据的情形，全部在这里显式挡掉 —— 挡掉的方式是**丢弃这一次增量**，
 * 而不是猜一个值写进去：流量历史宁可少一段，也不能凭空多一段。
 */
object TrafficHourlyAccumulator {

    /**
     * 判定「core 离线过久」的阈值。
     *
     * 采集循环是秒级的，两次采样间隔正常远小于这个值。一旦超过，说明中间 core 没在跑
     * （服务被杀 / 设备关机 / 休眠），这段时间里产生的流量**无法归属到具体小时** ——
     * 如果照常求差，重启后第一次采样会把离线期间的全部用量（可能是几天的量）
     * 一股脑记到当前这一个小时上，柱状图会长出一根假的天柱。
     */
    const val OFFLINE_GAP_MS = 10 * 60 * 1000L

    /**
     * 「累计值没变」这一种跳过原因。
     *
     * 单独给个常量而不是散落的字面量：调用方需要区分它 —— 这是唯一一种**每个采集周期都会命中**
     * 的跳过（设备没跑流量时），所以日志和基准落盘都要对它特殊处理，字符串写错就静默失效。
     */
    const val NO_CHANGE_REASON = "no-change"


    /** 上一次采样：时刻 + 当时的当月累计。 */
    data class Sample(val atMs: Long, val rx: Long, val tx: Long)

    /** 决策结果。两种情况都要更新 [next]（下一次求差的基准），区别只在于是否落一条增量。 */
    sealed interface Decision {
        /** 基准要更新到 [next]，但**不**写增量。[reason] 只用于日志。 */
        data class SkipDelta(val next: Sample, val reason: String) : Decision

        /** 把 ([rxDelta], [txDelta]) 累加进 [hourStart] 这个小时桶，并把基准更新到 [next]。 */
        data class Record(
            val hourStart: Long,
            val rxDelta: Long,
            val txDelta: Long,
            val next: Sample
        ) : Decision
    }

    /**
     * 给定「上一次采样」与「这一次读到的当月累计」，决定要不要记一条增量、记到哪个小时桶。
     *
     * 丢弃增量的四种情形：
     * 1. [prev] 为 null —— 首次运行 / prefs 被清 / JSON 损坏，没有可比对的前值；
     * 2. 时间倒流（`nowMs < prev.atMs`）—— 设备时间被改，差值毫无意义；
     * 3. 离线过久（间隔 > [OFFLINE_GAP_MS]）—— 见该常量注释；
     * 4. 累计值变小 —— 设备跨月归零、用户做了流量校准、或 Modem 重启计数器归零。
     *
     * 增量归属到**当前时刻所在的小时**（本地时区）。跨小时那一次采样的增量会整块落进新
     * 小时，误差上限就是一个采样间隔的流量，秒级采样下可以忽略；真要精确切分得知道流量
     * 在这个间隔内的时间分布，而 goform 不提供。
     */
    fun decide(
        prev: Sample?,
        nowMs: Long,
        monthRx: Long,
        monthTx: Long,
        zone: ZoneId
    ): Decision {
        val next = Sample(nowMs, monthRx, monthTx)
        if (prev == null) return Decision.SkipDelta(next, "no-previous-sample")
        if (nowMs < prev.atMs) return Decision.SkipDelta(next, "clock-went-backwards")
        if (nowMs - prev.atMs > OFFLINE_GAP_MS) return Decision.SkipDelta(next, "offline-gap")
        if (monthRx < prev.rx || monthTx < prev.tx) return Decision.SkipDelta(next, "counter-reset")

        val rxDelta = monthRx - prev.rx
        val txDelta = monthTx - prev.tx
        if (rxDelta == 0L && txDelta == 0L) return Decision.SkipDelta(next, NO_CHANGE_REASON)

        return Decision.Record(hourStartOf(nowMs, zone), rxDelta, txDelta, next)
    }

    /**
     * 某时刻所属小时的起点（**本地时区**整点的 epoch ms）。
     *
     * 用本地时区而不是 `epochMs / 3600_000`：后者是 UTC 整点，在 UTC+8 会让每天的第一个
     * 小时桶落到前一天去，日/周/月/年的边界全部错位。
     */
    fun hourStartOf(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
}
