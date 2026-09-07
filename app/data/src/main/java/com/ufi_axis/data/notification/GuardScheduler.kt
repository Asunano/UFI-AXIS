package com.ufi_axis.data.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * 后台守护任务状态。
 *
 * @param enabled 后台轮询总开关
 * @param intervalMinutes 轮询间隔（15/30/60，WorkManager 平台下限 15min）
 * @param lastRunAt 上次运行时间（epoch ms，0=从未运行）
 * @param nextRunAt 预计下次运行时间（epoch ms，估算值）
 * @param lastResult 上次运行结果描述（如「拉取 3 条告警」「失败：...」）
 */
data class GuardState(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 30,
    val lastRunAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val lastResult: String = "尚未运行"
)

/**
 * WorkManager 周期任务调度器（notifications-fix-plan Part 3.4 S2）。
 *
 * - `enqueuePeriodic(intervalMinutes)`：注册 [BackgroundGuardWorker] 周期任务，
 *   带 `NetworkType.CONNECTED` 约束（离线自动跳过，省电）；
 * - `cancel()`：取消唯一周期任务；
 * - `state: StateFlow<GuardState>`：供 UI 展示（enabled/interval/lastRunAt/nextRunAt/lastResult）。
 *
 * 偏好读写遵循 [NotifyPrefs] 的进程边界约定（T14 方案 A）：
 * 开关（`guard_enabled` / `guard_interval_minutes` / `guard_foreground_keepalive_enabled`）
 * 真源在共享文件且只由主进程写，`:ufi_notify` 内只更新镜像；
 * 运行状态（`guard_last_run_at` / `guard_next_run_at` / `guard_last_result`）写各进程自己的状态文件。
 *
 * @param api 仅用于预创建/复用 RetrofitClient 单例（Worker 内部经 [com.ufi_axis.data.api.RetrofitClient]
 *            取同一实例，不直接持有）。
 */
class GuardScheduler(context: Context, @Suppress("unused") api: com.ufi_axis.data.api.UfiAxisApi) {

    private val appContext = context.applicationContext
    private val statePrefs = NotifyPrefs.state(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    init {
        // 进程重启后补一次 enqueue（UPDATE 幂等），保证周期任务仍在调度
        if (_state.value.enabled) {
            enqueuePeriodic(_state.value.intervalMinutes)
        }
    }

    /** 开/关后台轮询。 */
    fun setEnabled(enabled: Boolean) {
        NotifyPrefs.putSwitch(appContext, NotificationCenter.KEY_GUARD_ENABLED, enabled)
        if (enabled) {
            enqueuePeriodic(_state.value.intervalMinutes)
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
        }
        _state.value = readState()
    }

    /** 修改轮询间隔（钳制到 15-60min）。 */
    fun setInterval(minutes: Int) {
        val safe = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        NotifyPrefs.putSwitch(appContext, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, safe)
        if (_state.value.enabled) {
            enqueuePeriodic(safe)
        }
        _state.value = readState()
    }

    /** 取消周期任务（保留开关偏好）。 */
    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
        _state.value = readState()
    }

    /** 从偏好重新读取状态（Worker 运行后由 UI 调用刷新）。 */
    fun refresh() {
        _state.value = readState()
    }

    /**
     * 写「前台服务保活」偏好。**只存偏好，不启停服务**。
     *
     * 这个 key 是前台保活服务的**唯一闸门**（见 [NotifyPrefs.keepAliveShouldRun]），所以
     * 调用方必须自己补上真实动作：开 → 先调本方法再 `NotificationConfigClient.startKeepAlive`
     * （闸门读 prefs，顺序反了会被自己刚要打开的开关挡掉）；
     * 关 → `NotificationConfigClient` 实例的 `setForegroundKeepAlive(false)`（撤常驻通知）
     * + `stopKeepAlive`。
     */
    fun setForegroundKeepAlive(enabled: Boolean) {
        NotifyPrefs.putSwitch(appContext, NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE, enabled)
    }

    fun isForegroundKeepAlive(): Boolean =
        NotifyPrefs.switchOn(appContext, NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE, false)

    private fun enqueuePeriodic(intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<BackgroundGuardWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        val now = System.currentTimeMillis()
        statePrefs.edit()
            .putLong(NotificationCenter.KEY_GUARD_NEXT_RUN_AT, now + intervalMinutes * 60_000L)
            .apply()
    }

    private fun readState(): GuardState = GuardState(
        enabled = NotifyPrefs.switchOn(appContext, NotificationCenter.KEY_GUARD_ENABLED, false),
        intervalMinutes = NotifyPrefs.switchInt(
            appContext, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES
        ),
        lastRunAt = statePrefs.getLong(NotificationCenter.KEY_GUARD_LAST_RUN_AT, 0L),
        nextRunAt = statePrefs.getLong(NotificationCenter.KEY_GUARD_NEXT_RUN_AT, 0L),
        lastResult = statePrefs.getString(NotificationCenter.KEY_GUARD_LAST_RESULT, "尚未运行") ?: "尚未运行"
    )

    companion object {
        /** 唯一周期任务名。 */
        const val WORK_NAME = "ufi_axis_background_guard"
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 60
    }
}
