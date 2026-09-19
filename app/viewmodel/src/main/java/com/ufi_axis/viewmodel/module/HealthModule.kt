package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后端（core）健康检查。
 *
 * 两套用途：
 * 1. 周期访问 core 的 `/health` 端点，维护 [healthState]（当前是否可达）；
 * 2. [checkHealthNow] 供「数据加载出错后」即时复查后端是否还活着 —— 若探活也失败，基本可判定
 *    后端服务挂了 / 地址不可达，UI 据此弹「后端掉线」提示而不是笼统的「加载失败」。
 *
 * `/health` 免鉴权、零负载（HttpServer.kt 直接回 status=timestamp），可作为廉价的"后端是否在线"探针。
 *
 * ## 2026-09 增强：前后台闸门 + 确证后再翻转
 *
 * 旧实现的问题：30s 循环在进程被冻结/Doze 的后台仍会发请求，超时被记成 UNREACHABLE；
 * 用户切回前台时若恰好看见这份旧结果，会立刻弹「后端掉线」——典型的切后台误报。
 *
 * 现在：
 * - [onAppBackgrounded] 暂停周期探活；
 * - [onAppForegrounded] 先静默探一次（结果**不**因单次失败直接对外当掉线用），
 *   成功后再恢复周期循环；
 * - 状态翻转要过 [CONFIRM_FAILURE_COUNT] 次失败且跨度 ≥ [CONFIRM_FAILURE_SPAN_MS]
 *   （与 `AuthRejectionPolicy` 同思路：单次超时不定罪）；
 * - 任一次成功清零连击。
 */
enum class HealthStatus { UNKNOWN, HEALTHY, UNREACHABLE }

data class HealthState(
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val lastCheckedAt: Long = 0L,
    val errorMessage: String? = null,
    /** 当前连续失败次数（诊断用；成功后归 0）。 */
    val consecutiveFailures: Int = 0
)

class HealthModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope
) {
    private val _healthState = MutableStateFlow(HealthState())
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    @Volatile
    private var paused = false

    private var periodicJob: Job? = null

    /** 确证「掉线」用的连击：失败计数 + 首次失败时刻。 */
    private val failLock = Any()
    private var failStreak = 0
    private var failStreakStartedAt = 0L

    /** 回前台后短时间内的探活失败只记 streak，不立刻把状态打成 UNREACHABLE（见 [onAppForegrounded]）。 */
    @Volatile
    private var suppressDownFlipUntil = 0L

    /**
     * 单次健康检查：成功回 true（status=HEALTHY），失败回 false。
     *
     * @param countTowardDownFlip 是否把结果计入「确证掉线」的连击。
     *   回前台静默复查传 false：避免刚 resume 的一次超时就把 UI 打成掉线。
     */
    suspend fun checkHealthNow(countTowardDownFlip: Boolean = true): Boolean {
        return try {
            api.getHealth()
            if (countTowardDownFlip) clearFailStreak()
            val prev = _healthState.value
            _healthState.value = HealthState(
                status = HealthStatus.HEALTHY,
                lastCheckedAt = System.currentTimeMillis(),
                errorMessage = null,
                consecutiveFailures = 0
            )
            // 静默路径也要在成功时清掉掉线态（回前台探通了，别留着旧 UNREACHABLE）
            if (!countTowardDownFlip && prev.status == HealthStatus.UNREACHABLE) {
                // 上面已写成 HEALTHY
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("HealthModule", "health probe failed: ${e.message}")
            val now = System.currentTimeMillis()
            val (streak, streakStart) = if (countTowardDownFlip) {
                synchronized(failLock) {
                    if (failStreak == 0) failStreakStartedAt = now
                    failStreak++
                    failStreak to failStreakStartedAt
                }
            } else {
                // 静默失败：只记日志，不动 streak，也不把状态打成 UNREACHABLE
                return false
            }
            val confirmed = streak >= CONFIRM_FAILURE_COUNT &&
                (now - streakStart) >= CONFIRM_FAILURE_SPAN_MS &&
                now >= suppressDownFlipUntil
            _healthState.value = HealthState(
                status = if (confirmed) HealthStatus.UNREACHABLE else {
                    // 未确证时：若原本 HEALTHY，保持 HEALTHY（避免一次超时就把状态打脏）
                    if (_healthState.value.status == HealthStatus.UNREACHABLE) {
                        HealthStatus.UNREACHABLE
                    } else {
                        HealthStatus.HEALTHY
                    }
                },
                lastCheckedAt = now,
                errorMessage = e.message ?: "无法连接到后端服务",
                consecutiveFailures = streak
            )
            false
        }
    }

    /** 后台：停周期探活（Doze/冻结下的请求只会制造假 UNREACHABLE）。 */
    fun onAppBackgrounded() {
        paused = true
        periodicJob?.cancel()
        periodicJob = null
        DebugLog.d("HealthModule", "periodic health check paused (app background)")
    }

    /**
     * 回前台：静默探一次（不计入确证连击），成功后再恢复周期。
     *
     * 300ms 窗口：resume 后极短时间内的业务错误复查若紧跟在静默失败之后，也不因
     * 「刚回前台的网络还没起来」立刻定罪（见 [suppressDownFlipUntil]）。
     */
    fun onAppForegrounded(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        paused = false
        suppressDownFlipUntil = System.currentTimeMillis() + RESUME_GRACE_MS
        scope.launch {
            runCatching { checkHealthNow(countTowardDownFlip = false) }
            if (!paused) startPeriodicCheck(intervalMs)
        }
    }

    /** 周期健康检查：每 [intervalMs] 探活一次，直到暂停或 [scope] 取消。 */
    fun startPeriodicCheck(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        // 已有任务则不重复起（resume 与 init 可能叠）
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive && !paused) {
                checkHealthNow()
                delay(intervalMs)
            }
        }
    }

    private fun clearFailStreak() = synchronized(failLock) {
        failStreak = 0
        failStreakStartedAt = 0L
    }

    companion object {
        /** 周期探活默认间隔。 */
        const val DEFAULT_INTERVAL_MS = 30_000L

        /** 连续失败多少次才允许把状态打成 UNREACHABLE。 */
        private const val CONFIRM_FAILURE_COUNT = 2

        /** 连击须跨过的最短时间（把同一瞬间的并行超时算作一波）。 */
        private const val CONFIRM_FAILURE_SPAN_MS = 3_000L

        /** 回前台后的宽限期：此窗口内业务路径的探活失败也先不定罪。 */
        private const val RESUME_GRACE_MS = 2_000L
    }
}
