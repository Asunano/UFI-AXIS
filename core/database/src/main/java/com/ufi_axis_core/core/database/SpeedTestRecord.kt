package com.ufi_axis_core.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 测速结果历史（定时测速，2026-10-06）。
 *
 * 数据源是 core 侧的 [com.ufi_axis_core.api.speedtest.SpeedTestRunner]：
 * 它模拟客户端测速协议（HEAD 延迟 / GET 下行 / POST 上行，对准 speedtestone.losn.cc
 * 经 core 自己的 relay 白名单通道），让「设备自测」不依赖任何客户端在线。
 *
 * 写入口：定时任务（`speedtest` 动作，TaskScheduler）与手动触发
 * （POST /api/speedtest/run）。UI 消费：web SpeedTestModal 的历史 tab / 新历史视图。
 *
 * retention：沿用 DataScheduler.cleanOldData 的 retentionDays 模式（30 天默认），
 * 不像 traffic_hourly 那样永久保留 —— 测速是高频采样型数据，价值随时间衰减快。
 */
@Entity(tableName = "speedtest_history")
data class SpeedTestRecord(
    /** epoch 毫秒。主键直接用时间戳：同毫秒并发触发几乎没有意义（重新测一次即可）。 */
    @PrimaryKey val timestamp: Long,
    /** 触发来源：manual / scheduled */
    val trigger: String,
    /** 延迟（ms），无样本时 -1 */
    val latencyMs: Int,
    /** 抖动（ms），无样本时 -1 */
    val jitterMs: Int,
    /** 下行 Mbps（Mbps = Mbit/s） */
    val downloadMbps: Double,
    /** 上行 Mbps；上行未完成时 -1 */
    val uploadMbps: Double,
    /** 消耗流量（字节，下行+上行合计），供「这月测速花了多少流量」回看 */
    val bytesUsed: Long,
)
