package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.NetworkErrorClassifier
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
    val consecutiveFailures: Int = 0,
    /**
     * 最近一次失败是否为**传输层**失败（连不上 / 超时 / DNS），而不是 core 回了个错误码。
     *
     * 用来给提示文案归因：传输层失败 = 摸不到设备；非传输层 = 摸到了但它不高兴
     * （多半是 core 正在启动或升级）。
     */
    val lastFailureWasTransport: Boolean = false
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

    /** [notifyTransportFailure] 的去抖时刻（受 [failLock] 保护）。 */
    private var lastTransportProbeAt = 0L

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
            _healthState.value = HealthState(
                status = HealthStatus.HEALTHY,
                lastCheckedAt = System.currentTimeMillis(),
                errorMessage = null,
                consecutiveFailures = 0,
                lastFailureWasTransport = false
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("HealthModule", "health probe failed: ${e.message}")
            val now = System.currentTimeMillis()
            val transport = NetworkErrorClassifier.isTransportFailure(e)
            if (!countTowardDownFlip) {
                // 静默失败（回前台首探）：只记日志，不动 streak、不动状态
                return false
            }
            val (streak, streakStart) = synchronized(failLock) {
                if (failStreak == 0) failStreakStartedAt = now
                failStreak++
                failStreak to failStreakStartedAt
            }
            val confirmed = streak >= CONFIRM_FAILURE_COUNT &&
                (now - streakStart) >= CONFIRM_FAILURE_SPAN_MS &&
                now >= suppressDownFlipUntil
            val prev = _healthState.value.status
            _healthState.value = HealthState(
                // 未确证时**保持原状态不动**。
                // 2026-09-21 修正：旧实现在未确证分支里把非 UNREACHABLE 一律写成 HEALTHY，
                // 于是冷启动时（status=UNKNOWN）第一次探活失败反而会被记成「健康」——
                // 一个从未成功过的连接被标成正常，这是错的。
                status = if (confirmed) HealthStatus.UNREACHABLE else prev,
                lastCheckedAt = now,
                errorMessage = NetworkErrorClassifier.describe(e) ?: e.message ?: "无法连接到后端服务",
                consecutiveFailures = streak,
                lastFailureWasTransport = transport
            )
            false
        }
    }

    /**
     * 「某个业务请求在传输层失败了」的通知入口（由 `RetrofitClient.onTransportFailure` 驱动）。
     *
     * 立刻补一次探活，让状态翻转不必等周期循环的 30s。带去抖：一个页面并发发 6 个请求
     * 会连着抛 6 个 IOException，不去抖就会打出 6 次 `/health`（而且每次都超时，
     * 反而把确证阈值瞬间凑满 —— 那就退化成「单次网络抖动即定罪」了）。
     *
     * 后台期间（[paused]）不探：Doze/冻结下的请求只会制造假 UNREACHABLE。
     */
    fun notifyTransportFailure() {
        if (paused) return
        val now = System.currentTimeMillis()
        synchronized(failLock) {
            if (now - lastTransportProbeAt < TRANSPORT_PROBE_DEBOUNCE_MS) return
            lastTransportProbeAt = now
        }
        scope.launch { runCatching { checkHealthNow() } }
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

        /**
         * [notifyTransportFailure] 的最小间隔。
         *
         * 取 1.5s 是算过的：配合 [CONFIRM_FAILURE_COUNT] = 2 与 [CONFIRM_FAILURE_SPAN_MS] = 3s，
         * core 真的离线时需要 ≥3 次探活（0s / 1.5s / 3s）才确证 —— 约 3 秒出提示，
         * 既不会被一次网络抖动误判，也不用等周期循环那 30s。
         */
        private const val TRANSPORT_PROBE_DEBOUNCE_MS = 1_500L
    }
}
