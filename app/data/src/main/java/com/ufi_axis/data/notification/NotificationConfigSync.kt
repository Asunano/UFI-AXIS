package com.ufi_axis.data.notification

import android.content.Context
import com.ufi_axis.util.DebugLog
import kotlinx.serialization.Serializable

/**
 * `GET/PUT /api/notifications/config` 的载荷（T40-6）。
 *
 * 字段名与 core 的 `NotificationConfig` 逐个对齐（snake_case），默认值也必须一致 ——
 * 否则「全新设备第一次 GET」两端会看到不同初值。
 *
 * **不含隧道通知**：它的真源是 `PUT /api/tunnel/settings` 的 `notify_on_failure`。
 */
@Serializable
data class NotificationConfigDto(
    val alert_enabled: Boolean = false,
    // 2026-09-07：日常通知 6 项默认全部 false（用户要求「不要默认开启，需手动打开」）。
    // 必须与 core `NotificationConfig`、`NotifyScene.defaultEnabled`、[readLocal] 的 defValue、
    // `NotifyPrefs.MIRRORED_BOOL_KEYS` 以及 DailyNotifyScreen 的 defaultEnabled 逐字一致。
    val connectivity_enabled: Boolean = false,

    val sms_enabled: Boolean = false,
    val verification_enabled: Boolean = false,
    val download_enabled: Boolean = false,
    val traffic_80_enabled: Boolean = false,
    val device_events_enabled: Boolean = false,
    val dnd_enabled: Boolean = false,
    val dnd_start_hour: Int = NotificationCenter.DEFAULT_DND_START_HOUR,
    val dnd_end_hour: Int = NotificationCenter.DEFAULT_DND_END_HOUR,
    val guard_enabled: Boolean = false,
    val guard_interval_minutes: Int = GuardScheduler.DEFAULT_INTERVAL_MINUTES,
    val guard_foreground_keepalive_enabled: Boolean = false
)

/** `PUT /api/notifications/config` 的响应：回显服务端合并+校验后的最终值。 */
@Serializable
data class NotificationConfigUpdateResponse(
    val success: Boolean = false,
    val config: NotificationConfigDto? = null
)

/**
 * 通知配置的本地读写 + 跨进程生效（T40-6）。
 *
 * ## 为什么需要它
 * 这 10 项原先只有「UI 直接写 SharedPreferences」一条路径，远端（web / 另一台设备）
 * 改了配置在 app 侧**什么都不会发生** —— 全仓唯一的 WS `config_changed` handler
 * 只更新 `AlertConfig` 的内存镜像，不碰 prefs、不碰 AIDL、不碰调度。
 *
 * ## 生效链路（顺序有意义）
 * 1. 写共享 prefs（`ufi_axis_prefs`，用户配置唯一真源，只允许主进程写）；
 * 2. `guard_enabled` / `guard_interval_minutes` 走 [GuardScheduler]，因为它同时负责
 *    WorkManager 周期任务的重调度 —— 只改 pref 不重调度等于没改；
 * 3. `guard_foreground_keepalive_enabled` 除了写 pref 还要真的启停前台服务
 *    （`GuardScheduler.setForegroundKeepAlive` 只存偏好，见其 KDoc）；
 * 4. 最后绑一次 `:ufi_notify` 推 AIDL，刷新那个进程的 `mirror_` 视图。
 *
 * 只能在主进程调用（`NotifyPrefs.shared` 的写入约定）。
 */
object NotificationConfigSync {

    private const val TAG = "NotifyConfig"

    /** 从本地缓存读出当前全量字段。 */
    fun readLocal(context: Context): NotificationConfigDto {
        val p = NotifyPrefs.shared(context)
        return NotificationConfigDto(
            // 默认值统一取 false（2026-09-07 起全部如此）：app 侧所有实际闸门用的都是
            // switchOn(KEY_xxx, false)，defValue 与上面 DTO 的字段默认值必须逐字一致 ——
            // 一旦分叉就会出现「UI 显示关但实际还在推送」。
            alert_enabled = p.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false),
            connectivity_enabled = p.getBoolean(NotificationCenter.KEY_CONNECTIVITY_NOTIF, false),

            sms_enabled = p.getBoolean(NotificationCenter.KEY_SMS_NOTIF, false),
            verification_enabled = p.getBoolean(NotificationCenter.KEY_VERIFICATION_NOTIF, false),
            download_enabled = p.getBoolean(NotificationCenter.KEY_DOWNLOAD_NOTIF, false),
            traffic_80_enabled = p.getBoolean(NotificationCenter.KEY_TRAFFIC_80_NOTIF, false),
            device_events_enabled = p.getBoolean(NotificationCenter.KEY_DEVICE_EVENTS_NOTIF, false),
            dnd_enabled = p.getBoolean(NotificationCenter.KEY_DND_ENABLED, false),
            dnd_start_hour = p.getInt(
                NotificationCenter.KEY_DND_START_HOUR, NotificationCenter.DEFAULT_DND_START_HOUR
            ),
            dnd_end_hour = p.getInt(
                NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR
            ),
            guard_enabled = p.getBoolean(NotificationCenter.KEY_GUARD_ENABLED, false),
            guard_interval_minutes = p.getInt(
                NotificationCenter.KEY_GUARD_INTERVAL_MINUTES,
                GuardScheduler.DEFAULT_INTERVAL_MINUTES
            ),
            guard_foreground_keepalive_enabled =
                p.getBoolean(NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE, false)
        )
    }

    /**
     * 把 core 的配置落到本地并使其真正生效。
     *
     * @param guard 用于 `guard_*` 三项的写入 + 重调度；传 null 时这三项只写 pref
     *              （调用方拿不到实例的场合，下一次 `GuardScheduler.refresh()` 会补齐）。
     */
    fun applyRemote(context: Context, remote: NotificationConfigDto, guard: GuardScheduler?) {
        if (NotifyPrefs.isNotifyProcess()) {
            DebugLog.w(TAG, "applyRemote 只允许主进程调用，已忽略")
            return
        }
        val local = readLocal(context)
        if (local == remote) return

        NotifyPrefs.shared(context).edit()
            .putBoolean(NotificationCenter.KEY_ALERT_NOTIF, remote.alert_enabled)
            .putBoolean(NotificationCenter.KEY_CONNECTIVITY_NOTIF, remote.connectivity_enabled)

            .putBoolean(NotificationCenter.KEY_SMS_NOTIF, remote.sms_enabled)
            .putBoolean(NotificationCenter.KEY_VERIFICATION_NOTIF, remote.verification_enabled)
            .putBoolean(NotificationCenter.KEY_DOWNLOAD_NOTIF, remote.download_enabled)
            .putBoolean(NotificationCenter.KEY_TRAFFIC_80_NOTIF, remote.traffic_80_enabled)
            .putBoolean(NotificationCenter.KEY_DEVICE_EVENTS_NOTIF, remote.device_events_enabled)
            .putBoolean(NotificationCenter.KEY_DND_ENABLED, remote.dnd_enabled)
            .putInt(NotificationCenter.KEY_DND_START_HOUR, remote.dnd_start_hour.coerceIn(0, 23))
            .putInt(NotificationCenter.KEY_DND_END_HOUR, remote.dnd_end_hour.coerceIn(0, 23))
            .apply()

        if (guard != null) {
            // 这两个 setter 自带 WorkManager 重调度；只在真正变化时调用，避免无谓地重排周期任务
            if (local.guard_enabled != remote.guard_enabled) guard.setEnabled(remote.guard_enabled)
            if (local.guard_interval_minutes != remote.guard_interval_minutes) {
                guard.setInterval(remote.guard_interval_minutes)
            }
            // setForegroundKeepAlive 只存偏好，真实前台服务的启停统一在下面做（见那段注释）
            if (local.guard_foreground_keepalive_enabled != remote.guard_foreground_keepalive_enabled) {
                guard.setForegroundKeepAlive(remote.guard_foreground_keepalive_enabled)
            }
        } else {
            NotifyPrefs.putSwitch(context, NotificationCenter.KEY_GUARD_ENABLED, remote.guard_enabled)
            NotifyPrefs.putSwitch(
                context, NotificationCenter.KEY_GUARD_INTERVAL_MINUTES, remote.guard_interval_minutes
            )
            NotifyPrefs.putSwitch(
                context, NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE,
                remote.guard_foreground_keepalive_enabled
            )
        }

        // ★ 保活的真实启停必须在 if/else **之外**按最终值分流。
        //
        // 原先只有 `guard != null` 那一支带启停，而 `refreshNotificationConfig` / patch 后的整包
        //   回显（ToolsModule）走的正是 `guard == null` 这条路径 —— 它只 putSwitch 写 pref，
        //   开方向没人拉起服务（pushToNotifyProcess 里 `client.bind` 的 BIND_AUTO_CREATE 只触发
        //   onCreate、不会 startForeground）⇒ pref=true、「后台守护」页重读后显示为开、
        //   却没有常驻通知 = 本仓明令禁止的「假开关」。关方向已由下面 pushToNotifyProcess 的
        //   `setForegroundKeepAlive(false)`（NotifyService 当场撤通知自停）兜住，只缺开方向。
        //
        // 无条件调用是安全的：startKeepAlive 内部先过 NotifyPrefs.keepAliveShouldRun 闸门，
        //   而该闸门唯一判据就是本函数上面刚写完的 KEY_GUARD_FOREGROUND_KEEPALIVE
        //   （KeepAliveGate.shouldRun：alertNotifEnabled 不参与判定），顺序满足「先写 pref 再启」
        //   的要求，因此这里绕不过闸门；两支都不再自带启停，也不会重复调用。
        if (remote.guard_foreground_keepalive_enabled) {
            NotificationConfigClient.startKeepAlive(context)
        } else {
            NotificationConfigClient.stopKeepAlive(context)
        }


        pushToNotifyProcess(context, remote)
    }

    /**
     * 一次性绑定 `:ufi_notify` 推送变更后解绑。
     *
     * 必须显式推：`:ufi_notify` 读开关时优先读自己那份 `mirror_` 副本，主进程改了共享文件
     * 它是看不到的（`SharedPreferences` 无跨进程 reload）。绑定失败属正常（进程可能没起），
     * 此时下次该进程启动会从 Intent 里的 `NotifyPrefs.snapshot` 拿到新值。
     */
    private fun pushToNotifyProcess(context: Context, remote: NotificationConfigDto) {
        val client = NotificationConfigClient()
        client.bind(context.applicationContext) { connected ->
            if (!connected) return@bind
            client.setDndEnabled(remote.dnd_enabled)
            client.setDndWindow(remote.dnd_start_hour, remote.dnd_end_hour)
            client.setGuardEnabled(remote.guard_enabled)
            client.setGuardIntervalMinutes(remote.guard_interval_minutes)
            // 保活键也必须显式推：它是 `:ufi_notify` 前台服务的闸门之一，而那个进程读的是
            // 自己那份 mirror_ 副本。远端把它改成 false 时，这一句就是「常驻通知立刻消失」的动作
            //（stopKeepAlive 只是 stopService，绑定期间不会销毁服务）。
            client.setForegroundKeepAlive(remote.guard_foreground_keepalive_enabled)
            client.reloadConfig()

            client.unbind(context.applicationContext)
        }
    }
}
