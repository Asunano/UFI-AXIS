package com.ufi_axis.data.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog

/**
 * 后台守护 Worker（notifications-fix-plan Part 3.4 S3）。
 *
 * 由 [GuardScheduler] 注册为 WorkManager 周期任务（15/30/60min）：
 * 1. 开关短路：后台轮询关闭 **或** 全局通知总闸关闭 → 直接成功返回（零网络开销）；
 * 2. `GET /api/alerts/list` → [NotificationCenter.maybeNotifyNewAlerts]（与前台轮询/WS 共用去重）。
 *
 * 短信 / 验证码**不在这里拉**：到达判定在 core（持久化水位 + ContentObserver 事件驱动），
 * core 直接推 `notification`（type=sms|verification），`:ufi_notify` 收到即渲染。
 * 让 Worker 再拉一遍等于把同一个事实判两次，而且 15 分钟的平台下限做不到及时。
 *
 * 网络约束 `NetworkType.CONNECTED` 由 [GuardScheduler] 设置，离线时 WorkManager 自动跳过。
 * WorkManager 通过默认 WorkerFactory 反射实例化（无需 Manifest 声明）。
 */
class BackgroundGuardWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val prefs = appContext.getSharedPreferences(NotificationCenter.PREFS_NAME, Context.MODE_PRIVATE)

        // 后台守护由它自己的开关决定。
        // 注意这里**不是** KEY_ALERT_NOTIF：原来第一句是
        // `if (!prefs.getBoolean(KEY_ALERT_NOTIF, false)) return success()`，于是"系统通知推送"
        // 成了守护之上的隐藏总闸：用户开着后台守护、关掉通知推送，巡检整个不跑，界面上却没有提示。
        // 分类要不要弹是投递层的事，由 NotificationCenter.notify 的分类闸负责。
        if (!prefs.getBoolean(NotificationCenter.KEY_GUARD_ENABLED, false)) {
            return Result.success()
        }

        // 全局通知总闸（2026-09-08）：守护的唯一产物就是通知，总闸关着时拉回来的告警会在
        // `NotificationCenter.notify` 的 REASON_MASTER 处被整条丢掉 —— 那就别联网了。
        //
        // 排期侧已由 `GuardScheduler.syncSchedule` 把总闸算进 enqueue 条件，这里是第二道：
        // cancel 生效前可能还有一次已排好的执行落地，且 WorkManager 的取消不是即时的。
        if (!prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)) {
            return Result.success()
        }

        val api = RetrofitClient.getApiService(AppPreferences(appContext))
        val center = NotificationCenter(appContext)

        return try {
            val resp = api.getAlertList(50)
            center.maybeNotifyNewAlerts(resp.alerts)
            Result.success()
        } catch (e: Exception) {
            DebugLog.w(TAG, "后台拉取告警失败: ${e.message}")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "BackgroundGuard"
    }
}
