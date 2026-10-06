package com.ufi_axis_core.api.speedtest

import com.ufi_axis_core.core.database.SpeedTestDao
import com.ufi_axis_core.core.database.SpeedTestRecord
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.util.AppLogger

/**
 * 测速调度中心（定时测速，2026-10-06）。
 *
 * 把「跑一轮测速 → 入库 → 广播 → 通知」收成一条链，供三处复用：
 * - 定时任务 `speedtest` 动作（TaskScheduler → ActionExecutorImpl）；
 * - 手动触发 `POST /api/speedtest/run`；
 * - 将来可能的「开机自测」。
 *
 * 通知走 core 既有的 NotificationDispatcher（邮件/Webhook/WS 三渠道由用户配置决定），
 * 场景用 [NotifyEvent.speedtestResult]。**默认只发通知不发告警**：测速结果不是异常，
 * 混进告警列表会污染「需要处理的问题」这个语义。
 */
class SpeedTestCoordinator(
    private val runner: SpeedTestRunner,
    private val dao: SpeedTestDao,
    /** 上报一轮结果（广播 + 通知）；由装配层接 NotificationDispatcher / WebSocketPushService。 */
    private val onResult: suspend (SpeedTestResult, SpeedTestRecord) -> Unit = { _, _ -> },
) {
    private val tag = "SpeedTestCoordinator"

    /**
     * 跑一轮并落库。
     * @param trigger manual / scheduled
     * @return 入库后的记录；并发位被占时返回 null
     */
    /** 历史查询（新的在前）。 */
    suspend fun history(limit: Int): List<SpeedTestRecord> = dao.getRecent(limit)

    suspend fun runAndStore(trigger: String): SpeedTestRecord? {
        val result = runner.run() ?: return null
        val record = SpeedTestRecord(
            timestamp = result.timestamp,
            trigger = trigger,
            latencyMs = result.latencyMs,
            jitterMs = result.jitterMs,
            downloadMbps = result.downloadMbps,
            uploadMbps = result.uploadMbps,
            bytesUsed = result.bytesUsed,
        )
        dao.insert(record)
        AppLogger.i(
            tag,
            "speedtest($trigger): ↓%.1f ↑%.1f Mbps, %d ms, %.1f MB"
                .format(result.downloadMbps, result.uploadMbps, result.latencyMs, result.bytesUsed / 1048576.0)
        )
        onResult(result, record)
        return record
    }
}
