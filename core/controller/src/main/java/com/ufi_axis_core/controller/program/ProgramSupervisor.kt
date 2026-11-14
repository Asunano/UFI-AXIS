package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 退避与熔断的**纯函数**部分。
 *
 * 单独抽取以便在不启动 Android、不访问文件的情况下进行单元测试：这些参数决定崩溃程序
 * 是否会引发重启风暴，须由测试覆盖。
 */
object ProgramBackoff {

    /**
     * 第 [attempt] 次重试前要等多久（毫秒）。`attempt` 从 0 开始。
     *
     * 退避表用尽后取最后一个值（不做指数增长）：`900s` 表示持续重试且频率可控。
     * 与隧道「连续 3 次即放弃」的策略相反：隧道连接失败多为配置错误，
     * 应用退出更可能是被系统回收，重启属于正常操作。
     */
    fun delayMs(attempt: Int, backoffS: List<Int>): Long {
        if (backoffS.isEmpty()) return 60_000L
        val idx = attempt.coerceIn(0, backoffS.size - 1)
        return backoffS[idx].coerceAtLeast(1) * 1000L
    }

    /**
     * 该次退出是否计为失败。
     *
     * 判据为存活时长，而非退出码：运行两小时后退出的程序即使属于崩溃，
     * 也不计入连续失败（其已正常工作过）。只有启动后短时间内退出才视为崩溃循环，
     * 需要累加计数。
     */
    fun isFailure(uptimeMs: Long, healthyUptimeMs: Long = 60_000L): Boolean =
        uptimeMs < healthyUptimeMs

    /** 是否熔断 */
    fun shouldOpen(failures: Int, giveUpAfter: Int): Boolean = failures >= giveUpAfter
}

/**
 * 看护：使期望运行的程序最终处于运行状态。
 *
 * ## 与 `TunnelManager` 的三处差异
 *
 * | 维度 | TunnelManager | 本实现 | 原因 |
 * |---|---|---|---|
 * | 放弃策略 | 连续失败 3 次即停止 | 退避至 900s 持续重试，8 次后熔断 | 隧道连接失败多为配置错误；应用退出多为被系统回收，重启是常态 |
 * | 失败计数 | 每轮 +1 | 按存活时长判定（长时间运行后清零） | 否则每月崩溃一次的程序会被误判为崩溃循环 |
 * | 重启前置 | 复查 desired 快照 | 同样复查，并检查退避是否到期 | 用户手动启动会重置退避，应立即拉起 |
 *
 * ## 看护以 `desired` 为判据
 *
 * 用户停止后，程序不应被任何机制拉起。`desired` 是唯一的意图信号，进程存活状态只反映事实；
 * 两者混用会导致用户停止后程序被看护重新拉起。
 */
class ProgramSupervisor(
    private val store: ProgramStore,
    private val runtime: ProgramRuntime,
    private val catalog: ProgramCatalog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 通知回调（标题, 正文）；装配层接到通知分发器 */
    private val notify: (String, String) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis
) {

    @Volatile
    private var guardJob: Job? = null

    private val guardLock = Any()

    /** 看护间隔。短于隧道：应用退出后需尽快恢复，不等待 30s。 */
    private val intervalMs = 10_000L

    /**
     * Core 启动时按 app.json 的 `autostart` 恢复期望运行状态。
     *
     * 与停止操作的关系：停止只清除 `desired`，不修改 `autostart`，下次 Core 启动仍会
     * 在此拉起。若需重启后不再拉起，应关闭设置中的开机自启。
     */
    fun restoreAutostart() {
        catalog.idsForDisplay().forEach { id ->
            if (!store.isInstalled(id)) return@forEach
            val meta = store.readMeta(id) ?: return@forEach
            if (!meta.autostart) return@forEach
            val st = store.readState(id)
            if (st.desired) return@forEach
            store.writeState(
                id,
                st.copy(
                    desired = true,
                    consecutiveFailures = 0,
                    circuitOpen = false,
                    nextRetryAt = 0
                )
            )
            AppLogger.i(TAG, "开机自启：$id 已恢复期望运行")
        }
    }

    fun startGuard() {
        synchronized(guardLock) {
            if (guardJob?.isActive == true) return
            guardJob = scope.launch(Dispatchers.IO) {
                // 首轮延迟：core 刚启动时网络与磁盘繁忙，且 desired 尚未重建完成
                delay(10_000)
                while (isActive) {
                    runCatching { guardPass() }
                        .onFailure { AppLogger.w(TAG, "看护轮次异常: ${it.message}") }
                    delay(intervalMs)
                }
            }
            AppLogger.i(TAG, "看护已启动")
        }
    }

    fun stopGuard() {
        val job = synchronized(guardLock) {
            val j = guardJob
            guardJob = null
            j
        }
        job?.cancel()
        AppLogger.i(TAG, "看护已停止")
    }

    /**
     * 设置期望运行状态。这是用户意图的唯一入口：启动与停止按钮均经由本方法，
     * 不直接操作进程。
     */
    fun setDesired(id: String, desired: Boolean) {
        val st = store.readState(id)
        store.writeState(
            id,
            st.copy(
                desired = desired,
                // 用户手动操作会重置失败计数与熔断状态（重试语义）
                consecutiveFailures = if (desired) 0 else st.consecutiveFailures,
                circuitOpen = if (desired) false else st.circuitOpen,
                nextRetryAt = if (desired) 0 else st.nextRetryAt
            )
        )
        AppLogger.i(TAG, "期望运行[$id] = $desired")
    }

    /**
     * 进程退出回调（由 [ProgramRuntime] 调用）。
     *
     * 归因与计数在此完成：Runtime 只报告事实，决策由 Supervisor 负责。
     */
    fun onExit(id: String, code: Int, reason: String) {
        val st = store.readState(id)
        if (!st.desired) {
            AppLogger.i(TAG, "程序退出但 desired=false，不重试: $id（$reason）")
            return
        }

        // 程序正常退出（code 0）表示明确的停止意图：清除 desired，不进入崩溃重启流程。
        // 若不检查 code，程序从自身界面退出后会被看护重新拉起。
        if (code == 0) {
            store.writeState(
                id,
                st.copy(
                    desired = false,
                    consecutiveFailures = 0,
                    circuitOpen = false,
                    nextRetryAt = 0,
                    lastExitCode = 0,
                    lastExitReason = reason,
                    lastStopAt = now()
                )
            )
            AppLogger.i(TAG, "程序正常退出，清除 desired: $id（$reason）")
            return
        }

        val manifest = catalog.get(id)
        val uptime = (now() - st.lastStartAt).coerceAtLeast(0L)
        val healthyUptimeMs = (manifest?.restart?.crashLoopWindowS ?: 60) * 1000L
        val failure = ProgramBackoff.isFailure(uptime, healthyUptimeMs)
        val failures = if (failure) st.consecutiveFailures + 1 else 0

        val giveUp = failure && ProgramBackoff.shouldOpen(failures, manifest?.restart?.giveUpAfter ?: 8)
        val backoff = ProgramBackoff.delayMs(
            (failures - 1).coerceAtLeast(0),
            manifest?.restart?.backoffS ?: listOf(5, 15, 60, 300, 900)
        )

        store.writeState(
            id,
            st.copy(
                consecutiveFailures = failures,
                circuitOpen = giveUp,
                nextRetryAt = if (giveUp) 0 else now() + backoff,
                lastExitCode = code,
                lastExitReason = reason,
                lastStopAt = now()
            )
        )

        if (giveUp) {
            val text = "连续失败 $failures 次后停止自动重启：$id（$reason）"
            AppLogger.e(TAG, text)
            notify("第三方程序已停止重试", text)
        } else {
            AppLogger.w(TAG, "将在 ${backoff / 1000}s 后重启 $id（第 $failures 次失败）")
        }
    }

    /**
     * 启动前失败（端口被占、内存不足、exec 失败）。
     *
     * 这类失败没有进程退出事件，不能使用 [onExit] 的 uptime 判据：lastStartAt 可能为 0
     * 或为上一次成功启动的时间，据此计算的 uptime 会很长，导致永不累计失败、无限重试。
     */
    fun onStartFailure(id: String, reason: String) {
        val st = store.readState(id)
        if (!st.desired) return
        val manifest = catalog.get(id)
        val failures = st.consecutiveFailures + 1
        val giveUp = ProgramBackoff.shouldOpen(failures, manifest?.restart?.giveUpAfter ?: 8)
        val backoff = ProgramBackoff.delayMs(
            (failures - 1).coerceAtLeast(0),
            manifest?.restart?.backoffS ?: listOf(5, 15, 60, 300, 900)
        )
        store.writeState(
            id,
            st.copy(
                consecutiveFailures = failures,
                circuitOpen = giveUp,
                nextRetryAt = if (giveUp) 0 else now() + backoff,
                lastExitCode = -1,
                lastExitReason = reason,
                lastStopAt = now()
            )
        )
        if (giveUp) {
            val text = "连续失败 $failures 次后停止自动重启：$id（$reason）"
            AppLogger.e(TAG, text)
            notify("第三方程序已停止重试", text)
        } else {
            AppLogger.w(TAG, "启动失败，将在 ${backoff / 1000}s 后重试 $id（第 $failures 次失败）：$reason")
        }
    }

    /**
     * 一轮巡检：对满足「desired、有清单、未熔断、退避到期」条件的程序执行拉起。
     *
     * 检查顺序是刻意的：先确认进程是否仍存活，再判断各类不应启动的条件；
     * 反之会出现刚启动即被判为未运行、重复启动的竞态。
     */
    private suspend fun guardPass() {
        // 预算淘汰先于拉起：已超预算时不再启动新实例。
        // 超限时停止占用最大的程序，依据见 ProgramMemoryBudget。
        runCatching { runtime.enforceMemoryBudget() }
            .onFailure { AppLogger.w(TAG, "内存预算淘汰异常: ${it.message}") }

        if (catalog.all().isEmpty()) {
            skip("-", "清单为空（assets 未加载或全部校验失败）")
            return
        }

        for (manifest in catalog.all()) {
            val id = manifest.id
            if (!store.isInstalled(id)) {
                // 未安装不属于异常，不得调用 [skip]（它会写 log/programs/<id>/lifecycle.log）。
                // 否则每个未安装程序都会被每轮巡检重建日志目录：彻底卸载刚删除的目录
                // 会在下一次巡检被重建，残留检查无法通过。
                AppLogger.d(TAG, "看护跳过未安装：$id")
                continue
            }
            if (!runtime.isStorageReady(id)) {
                skip(id, "外部存储不可用（已拔出或未挂载）")
                continue
            }
            if (!runtime.isContentReady(id)) {
                skip(id, "内容目录所在存储不可用（已拔出或未挂载）")
                continue
            }
            val st = store.readState(id)
            if (!st.desired) continue
            // 已在运行则跳过：不能以「是否有任意实例在运行」作为判据（多实例下会互相遮挡），
            // 但必须拦住「本实例在运行」，否则每轮巡检都会重复 spawn。
            if (runtime.isRunning(id)) continue
            if (st.circuitOpen) {
                skip(id, "已熔断（连续失败 ${st.consecutiveFailures} 次）")
                continue
            }
            if (st.nextRetryAt > 0 && now() < st.nextRetryAt) {
                skip(id, "退避中，还有 ${(st.nextRetryAt - now()) / 1000}s")
                continue
            }

            AppLogger.i(TAG, "看护拉起 $id（第 ${st.consecutiveFailures + 1} 次尝试）")
            val result = runtime.start(manifest)
            if (result.isSuccess) {
                store.writeState(id, store.readState(id).copy(lastStartAt = now()))
            } else {
                // 启动失败（端口、内存或 exec 失败）：按失败计数，不使用 onExit 的 uptime 判据。
                onStartFailure(id, result.exceptionOrNull()?.message ?: "启动失败")
            }
            // 不在此处 return：一轮需处理全部 desired 程序。单个程序启动失败
            // （端口被占用、内存不足）不应阻塞其余程序。
        }
    }

    /** 同一原因 60s 内只记录一次，避免每轮巡检（10s）重复写入日志 */
    private val lastSkip = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun skip(id: String, reason: String) {
        val now = now()
        val key = "$id|$reason"
        val prev = lastSkip[key] ?: 0L
        if (now - prev < 60_000) return
        lastSkip[key] = now
        runtime.lifecycle(id, "看护跳过：$reason")
    }

    private companion object {
        const val TAG = "ProgramSupervisor"
    }
}
