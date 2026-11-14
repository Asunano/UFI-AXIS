package com.ufi_axis_core.controller.carrier

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 话费账单后台刷新（2026-11-09）。
 *
 * 目标：用户打开账单页时先看到缓存，而不是等营业厅/物联卡接口。
 *
 * ## 调度
 * - 启动后先等 [initialDelayMs]（默认 60s），避开 core 启动/采集高峰；
 * - 之后按设备策略 [CarrierBillSettings.refreshIntervalMinutes]（默认 20 分钟）跑一轮；
 *   [CarrierBillSettings.refreshEnabled] 关闭时只做 60s 空转，不发请求；
 * - 每轮只刷 [CarrierBillService.refreshTargets]（当前有会话的账号），
 *   账号之间隔 [perAccountDelayMs]（默认 2s），避免同一出口短时间连打上游。
 *
 * ## 与自动登录的关系
 * 它跟 [CarrierAutoLoginManager] 共用同一个后台总闸：后台服务停止时一起停，
 * 避免用户关了"后台服务"之后还在偷偷打运营商接口。
 *
 * ## 失败处理
 * 单个账号失败只记日志，不影响其它账号；下一轮再试。会话失效由
 * [CarrierBillService.billSnapshotOf] 清 Cookie 并回退缓存，下一轮自动跳过该账号。
 */
/**
 * 刷新器只需要这两件事；抽成接口是为了让 [CarrierBillRefresher] 能在纯 JVM 单测里
 * 验证"只刷有会话的账号、强制刷新、单个失败不中断其它账号"。
 */
interface CarrierBillRefreshSource {
    fun refreshTargets(): List<String>
    /** 不声明默认值：另一个被实现的接口 `CarrierService` 已声明，Kotlin 不允许两处默认。 */
    suspend fun billSnapshotOf(accountId: String, force: Boolean): CarrierBillResult
}

class CarrierBillRefresher(
    private val service: CarrierBillRefreshSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 仅作为 [settings] 缺省时的兼容回退；生产路径读设备策略。 */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val perAccountDelayMs: Long = DEFAULT_PER_ACCOUNT_DELAY_MS,
    /** 设备策略提供者；与 [CarrierAutoLoginManager] 共享同一个 store。 */
    private val settings: () -> CarrierBillSettings = {
        CarrierBillSettings(
            refreshIntervalMinutes = (intervalMs / 60_000L).toInt().coerceAtLeast(1),
        )
    },
) {
    private var job: Job? = null

    /**
     * 是否由后台服务拉起。`restart()` 只能在原本就运行时重启，
     * 否则保存一次设置就会在"停止服务"状态下把刷新循环偷偷拉起来。
     */
    @Volatile
    private var shouldRun = false

    fun start() {
        shouldRun = true
        if (job?.isActive == true) return
        job = scope.launch {
            delay(initialDelayMs)
            while (isActive) {
                val current = settings()
                if (current.refreshEnabled) {
                    refreshAll()
                    delay(current.refreshIntervalMinutes * 60_000L)
                } else {
                    // 关闭状态不排长 delay：用户随时可能从设置页打开，60s 内要能感知。
                    delay(60_000L)
                }
            }
        }
        AppLogger.i(TAG, "账单后台刷新已启动（按设备策略：${settings().refreshIntervalMinutes} 分钟）")
    }

    /** 设备策略变化后重启循环，让新的开关/间隔立即生效；服务已停则不复活。 */
    fun restart() {
        val wasRunning = shouldRun
        stop()
        if (wasRunning) start()
    }

    fun stop() {
        shouldRun = false
        job?.cancel()
        job = null
    }

    /** 手动跑一轮（测试/调试用；生产只由 [start] 的循环调用）。 */
    suspend fun refreshAll() {
        val ids = runCatching { service.refreshTargets() }.getOrElse {
            AppLogger.w(TAG, "读取刷新目标失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        if (ids.isEmpty()) return

        var fresh = 0
        var loginRequired = 0
        var failed = 0
        for (id in ids) {
            val result = runCatching { service.billSnapshotOf(id, force = true) }
            result.onSuccess { r ->
                when {
                    r.loginRequired -> loginRequired++
                    r.stale -> failed++
                    else -> fresh++
                }
                if (r.loginRequired || r.stale) {
                    AppLogger.w(
                        TAG,
                        "刷新 $id：loginRequired=${r.loginRequired} stale=${r.stale} err=${r.error}",
                    )
                }
            }.onFailure {
                failed++
                AppLogger.w(TAG, "刷新 $id 失败：${it.javaClass.simpleName}: ${it.message}")
            }
            if (perAccountDelayMs > 0) delay(perAccountDelayMs)
        }
        AppLogger.i(
            TAG,
            "账单后台刷新完成：新鲜 $fresh / 需登录 $loginRequired / 失败 $failed（共 ${ids.size}）",
        )
    }

    companion object {
        private const val TAG = "CarrierBillRefresher"
        // 20 分钟是 probe 实测的联通 keepalive 间隔；移动的 App 域会话也比 30 分钟短。
        // 账单页本身走磁盘缓存，不会因为刷新变频繁而阻塞；这里只影响后台与上游的节奏。
        const val DEFAULT_INTERVAL_MS = 20 * 60 * 1000L
        const val DEFAULT_INITIAL_DELAY_MS = 60 * 1000L
        const val DEFAULT_PER_ACCOUNT_DELAY_MS = 2 * 1000L
    }
}
