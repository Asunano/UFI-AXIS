package com.ufi_axis_core.api.pairing

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 「新设备批准请求」的**唤醒推送**调度器（2026-10-21）。
 *
 * ## 它解决什么
 * 网页端登记一次待批准请求后，App 侧的发现手段原来只有 `MainActivity` 里 30 秒一次的轮询 ——
 * 机主看到的「弹窗慢」平均 15 秒、最坏 30 秒。core 明明知道请求是什么时候来的，却没有告诉 App。
 * 本类把「刚登记」这件事立刻推给**在线的已配对 App**，让它在 1 秒内弹窗。
 *
 * ## 为什么是「多次」而不是「一次」
 * 不是「多推几次更保险」，而是三个具体窗口，每一个都真实存在：
 * 1. **订阅竞态** —— App 在 `onOpen` 里先发 `{subscribe:[…]}` 再收帧；core 若在订阅报文被处理前
 *    广播，`subscriptions[type]` 为空会**直接丢弃**（`WebSocketManager.broadcast`）。第 2 帧补上。
 * 2. **单帧超时** —— 一次 `send` 有 `SEND_TIMEOUT_MS`，超时只丢这一帧、不摘连接。第 3/4 帧补上。
 * 3. **App 进程刚起来** —— 冷启动那几百毫秒里 WS 还在建链。+1.2s / +2.5s 帧补上。
 *
 * 再长的窗口（比如补到 30 秒）**没有意义**：真断了链要等 OkHttp 重连，那一层由
 * `WsDataTopic.RECONNECTED` 的补拉精确覆盖，盲发只是白耗电。所以默认表是
 * `0 / 400 / 1200 / 2500 ms`（4 帧 / 2.5 秒），要做成可调常量。
 *
 * ## 三条自我约束
 * 1. **每帧前复查**（[isStillPending]）—— 机主一批准/拒绝就立刻停，不做无用唤醒；
 * 2. **同一 sid 只跑一轮** —— 幂等刷新（Web 刷页面重发同一个 sid）不会把 App 刷屏；
 * 3. **全局预算**（[MAX_BURSTS_PER_WINDOW] / [BURST_WINDOW_MS]）—— `request-approval` 是
 *    **免鉴权**端点，谁进局域网都能发；推送会唤醒机主的手机，必须可被限流。
 *
 * ## 依赖全是 lambda
 * 与 `PairingRoutes.onBlockedDevice`、`UpdateManager.broadcaster` 同款：本类留在 `core:api`，
 * 不必知道 `WebSocketManager` 的存在（那是装配层的事），也便于测试注入。
 *
 * @param push 发一帧唤醒（装配层传 `wsManager.broadcastDataChanged(WsDataTopic.PAIRING_APPROVALS)`）
 * @param isStillPending 该 sid 是否仍待批准（装配层传 `approvalStatus(sid) == STATUS_PENDING`）
 * @param hasLiveClient 现在有没有在线客户端；**只用来省掉明显无效的一帧**，
 *   返回 false 时跳过这一帧但**不结束整轮**（App 可能 1 秒后就连上了）
 */
class ApprovalPushNudger(
    private val push: suspend () -> Unit,
    private val isStillPending: (String) -> Boolean,
    private val hasLiveClient: () -> Boolean = { true },
    private val scheduleMs: List<Long> = DEFAULT_SCHEDULE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** sid → 正在跑的那一轮。用 `isActive` 判活，结束即摘除。 */
    private val activeBursts = ConcurrentHashMap<String, Job>()

    /** 全局预算的滑动窗口（只记每轮的**开始时间**）。 */
    private val burstStarts = mutableListOf<Long>()

    /**
     * 有一条**新**（不是幂等重发）待批准请求登记成功，开始一轮唤醒。
     *
     * 调用方在路由层，且只在 `!isExisting` 时调 —— 幂等命中不该再唤醒一次。
     * 本方法**立即返回**（只 `launch`），不阻塞 `request-approval` 的响应。
     */
    fun onNewApproval(sid: String, now: Long = System.currentTimeMillis()) {
        val key = sid.trim()
        if (key.isEmpty()) return
        pruneFinished()
        if (activeBursts[key]?.isActive == true) return
        if (activeBursts.size >= MAX_CONCURRENT_BURSTS) {
            AppLogger.w(TAG, "approval nudge skipped：并发轮数已达上限 $MAX_CONCURRENT_BURSTS（sid=${key.take(8)}）")
            return
        }
        if (!consumeBudget(now)) {
            AppLogger.w(TAG, "approval nudge skipped：全局预算已用完（sid=${key.take(8)}）")
            return
        }
        val job = scope.launch { runBurst(key) }
        activeBursts[key] = job
        job.invokeOnCompletion { activeBursts.remove(key, job) }
    }

    /** 一轮唤醒的实际推进：逐帧等待 → 复查 pending → 有在线客户端才发。 */
    private suspend fun runBurst(sid: String) {
        var elapsed = 0L
        var sent = 0
        for ((index, at) in scheduleMs.withIndex()) {
            val wait = at - elapsed
            if (wait > 0) delay(wait)
            elapsed = at
            if (!scope.isActive) return
            // 先复查再判在线：已批准/已拒绝时连 hasLiveClient 都不必问。
            if (!isStillPending(sid)) {
                AppLogger.i(TAG, "approval nudge 结束：请求已不在 pending（sid=${sid.take(8)}，发出 $sent 帧）")
                return
            }
            if (!hasLiveClient()) {
                AppLogger.d(TAG, "approval nudge 跳过第 ${index + 1} 帧：当前无 WS 客户端")
                continue
            }
            val ok = runCatching { push() }
                .onFailure { AppLogger.w(TAG, "approval nudge 第 ${index + 1} 帧发送失败: ${it.message}") }
                .isSuccess
            if (ok) sent++
        }
        AppLogger.i(TAG, "approval nudge 完成：sid=${sid.take(8)}，发出 $sent/${scheduleMs.size} 帧")
    }

    private fun pruneFinished() {
        activeBursts.entries.removeAll { !it.value.isActive }
    }

    /**
     * 取一次全局预算。
     *
     * 窗口比待批准 TTL（15 分钟）短得多，是为了「别人狂刷时别把机主的手机叫醒」，
     * 而不是为了限制正常用户：正常场景下 1 分钟里能有 6 台新设备请求批准已经是极端值。
     */
    private fun consumeBudget(now: Long): Boolean {
        synchronized(burstStarts) {
            burstStarts.removeAll { now - it >= BURST_WINDOW_MS }
            if (burstStarts.size >= MAX_BURSTS_PER_WINDOW) return false
            burstStarts += now
            return true
        }
    }

    companion object {
        /** 与 `PairingManager` / `PairingRoutes` / `PairedDeviceApprovalRoutes` 共用同一日志标签。 */
        private const val TAG = "Pairing"

        /** 一轮唤醒的帧时间表（毫秒，相对登记时刻）。理由见类注释，真机可调。 */
        val DEFAULT_SCHEDULE_MS: List<Long> = listOf(0L, 400L, 1_200L, 2_500L)

        /** 全局预算：窗口内最多跑这么多轮。 */
        const val MAX_BURSTS_PER_WINDOW = 6

        /** 全局预算窗口。 */
        const val BURST_WINDOW_MS = 60_000L

        /** 同时最多几轮（不同 sid 并发进来时）。 */
        const val MAX_CONCURRENT_BURSTS = 4
    }
}
