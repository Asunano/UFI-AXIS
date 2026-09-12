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
 * 本状态**只有开关与间隔两项，不含任何运行时诊断字段**（"上次何时跑的""下次何时跑""上次结果"）。
 * 这不是漏了，而是三条硬约束决定的：
 *
 * ① 「下次运行时间」在客户端算不出来。能算的只有 `now + interval`，而不是 WorkManager 的真实排期 ——
 *    Doze、厂商冻结、网络约束未满足都会让它延后，于是界面上显示的是一个**已经过期的"未来时间"**。
 * ② 「上次结果」在这里没有可用的取值域。这个 Worker 只做"拉一遍告警"，结果只能落成
 *    「告警 N」「告警失败」「尚未运行」三种，说明不了任何问题，也指导不了任何操作。
 * ③ 这个类在**两个进程**里各有一份实例（主进程与 `:ufi_notify`），而 Worker 只跑在主进程。
 *    诊断字段读的是各自进程的私有状态，同一个字段在两处必然显示不同值。
 *
 * 想给用户"它到底有没有在跑"这个答案，出路是投递记录 / 日志那条线（跨进程有唯一落点），
 * 不是往本状态里加字段。
 *
 * @param enabled 后台轮询总开关
 * @param intervalMinutes 轮询间隔（15/30/60，WorkManager 平台下限 15min）
 */
data class GuardState(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 30
)

/**
 * WorkManager 周期任务调度器（notifications-fix-plan Part 3.4 S2）。
 *
 * - [syncSchedule]：按「后台轮询开关 AND 全局通知总闸」把 [BackgroundGuardWorker] 排上或取消，
 *   带 `NetworkType.CONNECTED` 约束（离线自动跳过，省电）；
 * - `cancel()`：取消唯一周期任务；
 * - `state: StateFlow<GuardState>`：供 UI 展示（enabled / interval）。
 *
 * 偏好读写遵循 [NotifyPrefs] 的进程边界约定（T14 方案 A）：
 * 开关（`guard_enabled` / `guard_interval_minutes` / `guard_foreground_keepalive_enabled`）
 * 真源在共享文件且只由主进程写，`:ufi_notify` 内只更新镜像。
 *
 * @param api 仅用于预创建/复用 RetrofitClient 单例（Worker 内部经 [com.ufi_axis.data.api.RetrofitClient]
 *            取同一实例，不直接持有）。
 */
class GuardScheduler(context: Context, @Suppress("unused") api: com.ufi_axis.data.api.UfiAxisApi) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    init {
        // 进程重启后重算一次（UPDATE 幂等）：总闸可能在上次进程存活期间被别端关掉，
        // 无条件 enqueue 会把已经该停的任务又排回来。
        syncSchedule(appContext)
    }

    /** 开/关后台轮询。 */
    fun setEnabled(enabled: Boolean) {
        NotifyPrefs.putSwitch(appContext, NotificationCenter.KEY_GUARD_ENABLED, enabled)
        syncSchedule(appContext)
        _state.value = readState()
    }

    /** 修改轮询间隔（钳制到 15-60min）。 */
    fun setInterval(minutes: Int) {
        val safe = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        NotifyPrefs.putSwitch(appContext, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, safe)
        syncSchedule(appContext)
        _state.value = readState()
    }

    /** 取消周期任务（保留开关偏好）。 */
    fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        _state.value = readState()
    }

    /** 从偏好重新读取状态（别端改过开关后由 UI 调用刷新）。 */
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

    private fun readState(): GuardState = GuardState(
        enabled = NotifyPrefs.switchOn(appContext, NotificationCenter.KEY_GUARD_ENABLED, false),
        intervalMinutes = NotifyPrefs.switchInt(
            appContext, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES
        )
    )

    companion object {
        /** 唯一周期任务名。 */
        const val WORK_NAME = "ufi_axis_background_guard"
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 60

        /**
         * 按当前偏好把周期任务排上或取消 —— **排期条件是「后台轮询开关 AND 全局通知总闸」**。
         *
         * ## 为什么总闸要参与（2026-09-08）
         * 守护的唯一产物就是通知。总闸关着时 Worker 照样每 15/30/60 分钟联网拉 50 条告警，
         * 最后在 `NotificationCenter.notify` 的 `REASON_MASTER` 处被整条丢掉 ——
         * 那是纯粹的流量与唤醒浪费，而 UI 上「后台守护」入口此时已置灰、用户根本进不去关它。
         *
         * ## 为什么做成"每次重算"而不是加一个 `paused_by_master` 持久位
         * 只 `cancel()` 不改 `guard_enabled` 会被自动复活：本类的 `init`、
         * `NotifyService.reloadConfig` 都会按 `guard_enabled` 幂等补 enqueue。
         * 把两个条件的 AND 收敛到这唯一一处 enqueue/cancel 入口，任何一端改开关后
         * 只要再调一次本方法，结果就是对的，不需要额外状态、也不会互相覆盖。
         *
         * 做成静态是因为 `NotificationConfigSync.applyRemote` 那条路径拿不到实例
         * （`guard` 参数在 Hero 开关与整包回显两条路径上都是 null）。
         *
         * 幂等：`ExistingPeriodicWorkPolicy.UPDATE` + `cancelUniqueWork` 都可以重复调用。
         */
        fun syncSchedule(context: Context) {
            val app = context.applicationContext
            val workManager = WorkManager.getInstance(app)
            val guardOn = NotifyPrefs.switchOn(app, NotificationCenter.KEY_GUARD_ENABLED, false)
            val masterOn = NotifyPrefs.switchOn(app, NotificationCenter.KEY_NOTIFY_MASTER, false)
            if (!guardOn || !masterOn) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val minutes = NotifyPrefs
                .switchInt(app, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            val request = PeriodicWorkRequestBuilder<BackgroundGuardWorker>(
                minutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
