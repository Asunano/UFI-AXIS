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
 * 1. 开关短路：告警与短信通知**都**关闭 → 直接成功返回（零网络开销）；
 * 2. `GET /api/alerts/list` → [NotificationCenter.maybeNotifyNewAlerts]（与前台轮询/WS 共用去重）；
 * 3. `GET /api/sms/contacts` → [NotificationCenter.maybeNotifyNewSms]，
 *    `GET /api/sms/verification-codes` → [NotificationCenter.maybeNotifyVerificationCodes]；
 * 4. 更新 GuardState 状态位（last_run_at / last_result / next_run_at 估算）。
 *
 * 第 3 步是 2026-08-30 补的：在此之前短信/验证码通知的唯一触发点是 ToolsModule，
 * 而 ToolsModule 只在短信界面组合时才跑 —— 用户现象就是「必须进短信-通知界面才弹验证码通知」。
 * 注意 WorkManager 周期下限是 15 分钟（平台限制），所以这条路径只是**兜底**；
 * 及时性依赖 core 侧的 ContentObserver 事件驱动转发。
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

        val alertEnabled = prefs.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false)
        // 2026-09-04：短信开关的默认值原来写成 false，与 `NotifyScene.SMS.defaultEnabled = true`
        // 以及 `NotificationCenter.maybeNotifyNewSms` 的读法不一致 —— 从未碰过短信开关的用户，
        // 前台进短信页能收到通知、后台兜底轮询却直接短路，表现为"必须打开短信页才有通知"。
        val smsEnabled = prefs.getBoolean(NotificationCenter.KEY_SMS_NOTIF, false)
        // 开关短路：两族通知都关 → 零开销 return（性能约束）
        if (!alertEnabled && !smsEnabled) {
            return Result.success()
        }
        // 后台守护总开关兜底（正常由 GuardScheduler 控制调度，此处防异常路径）
        if (!prefs.getBoolean(NotificationCenter.KEY_GUARD_ENABLED, false)) {
            return Result.success()
        }

        val api = RetrofitClient.getApiService(AppPreferences(appContext))
        val center = NotificationCenter(appContext)
        val results = mutableListOf<String>()
        // 任一分支失败都不该拖垮其它分支，所以各自 try/catch；只要有一个失败就整体 retry。
        var failed = false

        if (alertEnabled) {
            try {
                val resp = api.getAlertList(50)
                center.maybeNotifyNewAlerts(resp.alerts)
                results += "告警 ${resp.count}"
            } catch (e: Exception) {
                DebugLog.w(TAG, "后台拉取告警失败: ${e.message}")
                results += "告警失败"
                failed = true
            }
        }

        if (smsEnabled) {
            try {
                val resp = api.getSmsContacts()
                center.maybeNotifyNewSms(resp.contacts)
                results += "短信 ${resp.contacts.size}"
            } catch (e: Exception) {
                DebugLog.w(TAG, "后台拉取短信失败: ${e.message}")
                results += "短信失败"
                failed = true
            }
            try {
                val resp = api.getVerificationCodes()
                center.maybeNotifyVerificationCodes(resp.codes)
                results += "验证码 ${resp.count}"
            } catch (e: Exception) {
                DebugLog.w(TAG, "后台拉取验证码失败: ${e.message}")
                results += "验证码失败"
                failed = true
            }
        }

        updateGuardState(appContext, results.joinToString(" / "))
        return if (failed) Result.retry() else Result.success()
    }

    private fun updateGuardState(appContext: Context, result: String) {
        val prefs = appContext.getSharedPreferences(NotificationCenter.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val interval = prefs.getInt(NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, 30)
        prefs.edit()
            .putLong(NotificationCenter.KEY_GUARD_LAST_RUN_AT, now)
            .putLong(NotificationCenter.KEY_GUARD_NEXT_RUN_AT, now + interval * 60_000L)
            .putString(NotificationCenter.KEY_GUARD_LAST_RESULT, result)
            .apply()
    }

    private companion object {
        const val TAG = "BackgroundGuard"
    }
}
