package com.ufi_axis.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ufi_axis_core.alert.AlertBus
import com.ufi_axis_core.alert.AlertBusItem
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.model.AlertRecord
import com.ufi_axis.data.model.SmsContact
import com.ufi_axis.data.model.VerificationCode
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 通知中心 —— 全 App 系统通知的唯一出口（notifications-fix-plan Part 3.5）。
 *
 * 职责：
 * - 统一管理 Channel（device_alerts_v2 / device_connectivity / device_sms /
 *   device_downloads / device_events），全部在 [ensureChannels] 一次创建；
 * - 统一开关/状态位持久化（`ufi_axis_prefs`），key 常量集中在本类 companion；
 * - 差异检测去重：`last_notified_alert_id`（告警）/ `last_sms_total`（短信）/
 *   `last_download_status_{taskId}`（下载）/ `traffic_80_notified`（流量 80%）；
 * - 防骚扰：5min 限频（`last_notified_{type}_at`）+ 免打扰时段（起止小时可配，默认 23:00-07:00，
 *   仅 critical 告警突破）+ 多告警合并摘要（BigTextStyle）；
 * - 性能：所有通知入口第一行开关短路 return（轮询高频调用零开销）。
 *
 * 禁止在各 feature/viewmodel 模块散写 NotificationCompat —— 一律经本类出口。
 *
 * @param context 任意 Context（内部统一取 applicationContext，避免泄漏）。
 */
class NotificationCenter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 本进程可安全写入的**状态**文件（游标 / 签名集 / 限频 / 去重）。
     *
     * 主进程 = `ufi_axis_prefs`；`:ufi_notify` = `ufi_axis_notify_prefs`。
     * 见 [NotifyPrefs] 的进程边界约定（T14 方案 A）。
     */
    private val statePrefs by lazy { NotifyPrefs.state(appContext) }

    /** 读用户开关（`:ufi_notify` 内自动走镜像，避免读到过期值）。 */
    private fun switchOn(key: String, default: Boolean): Boolean =
        NotifyPrefs.switchOn(appContext, key, default)

    // ═══════════════════════ Key / Channel 常量（统一收敛处） ═══════════════════════

    companion object {
        /**
         * 邮件转投用的常驻 IO scope。
         *
         * 放 companion 而非实例字段：NotificationCenter 在轮询链路里被反复 new，
         * 每个实例各带一个 scope 会随实例数量线性堆积协程上下文。
         * SupervisorJob 保证单次发信抛异常不会连带取消后续所有转投。
         */
        private val mailScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** 统一偏好文件（与 AppPreferences / AlertSettingsScreen 共用）。 */
        const val PREFS_NAME = "ufi_axis_prefs"

        /** 系统通知推送总开关（告警族上层总闸：阈值告警 / 离线上线 / 流量 80% / 隧道失败）。 */
        const val KEY_ALERT_NOTIF = "alert_notification_enabled"

        /**
         * 设备离线/上线通知开关（默认开）。
         *
         * 2026-08-29 拆出：此前 CONNECTIVITY 场景与 ALERT 共用 [KEY_ALERT_NOTIF]，
         * 想「只关离线提醒、保留温度告警提醒」做不到。默认取 true 是为了保持行为不变 ——
         * 拆分前只要总开关开着就有离线通知，默认 false 会让升级用户的离线提醒突然消失。
         * 仍受 [KEY_ALERT_NOTIF] 总闸约束（见 `notifyDeviceConnectivity`）。
         */
        const val KEY_CONNECTIVITY_NOTIF = "connectivity_notification_enabled"

        /** 新短信通知开关（默认开，P1 日常价值场景）。 */
        const val KEY_SMS_NOTIF = "sms_notification_enabled"

        /** 验证码提取通知开关（默认开，附属于短信通知）。 */
        const val KEY_VERIFICATION_NOTIF = "verification_notification_enabled"

        /** 下载完成/失败通知开关（默认开，P1 日常价值场景）。 */
        const val KEY_DOWNLOAD_NOTIF = "download_notification_enabled"

        /** 流量 80% 限额预警开关（默认开，复用告警 channel，仅 DEFAULT 不响铃）。 */
        const val KEY_TRAFFIC_80_NOTIF = "traffic_80_notification_enabled"

        /** 设备事件（WiFi 客户端/热点等 P2 场景）开关（默认关，用户显式开启）。 */
        const val KEY_DEVICE_EVENTS_NOTIF = "device_events_notification_enabled"

        /** 免打扰时段开关（时段可自定义，见 [KEY_DND_START_HOUR]；仅 critical 突破）。 */
        const val KEY_DND_ENABLED = "dnd_enabled"

        /**
         * 免打扰起始 / 结束小时（0..23，含）。默认 23 → 7。
         *
         * 允许跨天：`start > end` 表示跨零点（23→7 = 当晚 23:00 到次日 07:00）；
         * 两者相等视为**不静默**（零长度窗口），而不是静默 24 小时 ——
         * 用户把两个滑块拖到同一格时，"什么都不静默"远比"整天静默"接近本意。
         */
        const val KEY_DND_START_HOUR = "dnd_start_hour"
        const val KEY_DND_END_HOUR = "dnd_end_hour"

        /** 后台守护：轮询总开关 / 间隔 / 前台服务保活（仅存偏好，不实际启动服务）。 */
        const val KEY_GUARD_ENABLED = "guard_enabled"
        const val KEY_GUARD_INTERVAL_MINUTES = "guard_interval_minutes"
        const val KEY_GUARD_FOREGROUND_KEEPALIVE = "guard_foreground_keepalive_enabled"
        const val KEY_GUARD_LAST_RUN_AT = "guard_last_run_at"
        const val KEY_GUARD_NEXT_RUN_AT = "guard_next_run_at"
        const val KEY_GUARD_LAST_RESULT = "guard_last_result"

        /** 告警去重游标：已通知的最大告警 id。 */
        const val KEY_LAST_NOTIFIED_ALERT_ID = "last_notified_alert_id"

        /** 告警去重游标：已通知的最大告警 timestamp。 */
        const val KEY_LAST_NOTIFIED_ALERT_TS = "last_notified_alert_ts"

        /** 告警去重游标：系统通知（状态栏）。 */
        const val KEY_LAST_NOTIFIED_SYSTEM_ALERT_ID = "last_notified_system_alert_id"
        const val KEY_LAST_NOTIFIED_SYSTEM_ALERT_TS = "last_notified_system_alert_ts"

        /** 告警去重游标：应用内 Toast (Banner)。 */
        const val KEY_LAST_TOASTED_ALERT_ID = "last_toasted_alert_id"
        const val KEY_LAST_TOASTED_ALERT_TS = "last_toasted_alert_ts"

        /** 告警近期已通知签名集（"sig@expireTs" 逗号分隔，TTL 兜底去重，修复 G 爆发重复）。 */
        const val KEY_RECENT_NOTIFIED_SIGS = "recent_notified_sigs"

        /** 设备离线/上线时间戳状态位（ms）。 */
        const val KEY_LAST_DEVICE_OFFLINE_AT = "last_device_offline_at"
        const val KEY_LAST_DEVICE_ONLINE_AT = "last_device_online_at"

        /**
         * 「离线通知已发出」状态位。
         *
         * 用它而不是靠场景限频来去重：限频是按场景记时间戳的，离线报完 5 分钟内恢复，
         * 上线通知会被同一个窗口吞掉 —— 用户只看到「离线」、永远等不到「已恢复」。
         * 有了这个状态位就能做到：离线只报一次、且只有报过离线才报恢复（成对）。
         */
        const val KEY_CONNECTIVITY_OFFLINE_NOTIFIED = "connectivity_offline_notified"

        /** 短信未读总数基线（用于差异检测）。 */
        const val KEY_LAST_SMS_TOTAL = "last_sms_total"

        /** 验证码已通知游标（消息 id）。 */
        const val KEY_LAST_NOTIFIED_VC_ID = "last_notified_vc_id"

        // ── Channel ──
        const val CHANNEL_ALERTS = "device_alerts_v2"
        const val CHANNEL_CONNECTIVITY = "device_connectivity"
        const val CHANNEL_SMS = "device_sms"
        const val CHANNEL_DOWNLOADS = "device_downloads"
        const val CHANNEL_EVENTS = "device_events"

        // ── 常量 ──
        private const val RATE_LIMIT_MS = 5 * 60 * 1000L          // 5min 限频

        /** 免打扰时段默认值（用户未设置过时的起止小时）。 */
        const val DEFAULT_DND_START_HOUR = 23
        const val DEFAULT_DND_END_HOUR = 7

        private const val ID_ALERTS = 1000
        private const val ID_CONNECTIVITY_OFFLINE = 2001
        private const val ID_CONNECTIVITY_ONLINE = 2002
        private const val ID_TRAFFIC_80 = 3001
        private const val ID_DEVICE_EVENTS = 3002
        private const val ID_SMS = 4001
        private const val ID_VERIFICATION_CODE = 4002
        private const val ID_DOWNLOAD = 5001

        /** Intent extra key：通知点击跳转到短信对话界面的手机号。 */
        const val EXTRA_SMS_PHONE = "extra_sms_phone"

        /** P3（应用内通知）：Intent extra key：告警系统通知点击跳转到事件中心的标记。 */
        const val EXTRA_ALERT_DEEPLINK = "extra_alert_deeplink"

        /** 跨进程新告警通知广播 Action（用于 UI 进程同步显示 banner）。 */
        const val ACTION_NEW_ALERTS = "com.ufi_axis.action.NEW_ALERTS"

        /**
         * 系统通知发射进程名后缀（与 Manifest 的 `android:process=":ufi_notify"` 一致）。
         *
         * 系统状态栏通知的去重游标**只在该进程读写** —— `SharedPreferences` 是 `MODE_PRIVATE`，
         * 各进程各持一份内存缓存且不跨进程 reload，多进程同时推进游标会导致重复响铃或漏通知。
         */
        const val PROCESS_NOTIFY = ":ufi_notify"

        /** [NotifyDispatchReceiver] 的告警载荷 extra（JSON 数组）。 */
        const val EXTRA_ALERTS_JSON = "extra_alerts_json"

        /** 单次跨进程转交的最大告警条数（防 Intent 超过 Binder 事务上限）。 */
        const val DISPATCH_MAX_ALERTS = 50
    }

    // ═══════════════════════ 开关读写（供 UI 使用） ═══════════════════════

    fun isSmsNotifEnabled(): Boolean = switchOn(KEY_SMS_NOTIF, false)

    fun setSmsNotifEnabled(enabled: Boolean) {
        NotifyPrefs.putSwitch(appContext, KEY_SMS_NOTIF, enabled)
    }

    fun isDndEnabled(): Boolean = switchOn(KEY_DND_ENABLED, false)

    fun setDndEnabled(enabled: Boolean) {
        // 主进程写共享真源；`:ufi_notify`（AIDL 通道）只刷新自己的镜像，见 NotifyPrefs
        NotifyPrefs.putSwitch(appContext, KEY_DND_ENABLED, enabled)
    }

    /** 当前免打扰窗口（起始小时 to 结束小时，0..23）。 */
    fun dndWindow(): Pair<Int, Int> = Pair(
        NotifyPrefs.switchInt(appContext, KEY_DND_START_HOUR, DEFAULT_DND_START_HOUR),
        NotifyPrefs.switchInt(appContext, KEY_DND_END_HOUR, DEFAULT_DND_END_HOUR)
    )

    /** 写免打扰窗口（越界值钳到 0..23，避免写进去一个永远判不出来的时段）。 */
    fun setDndWindow(startHour: Int, endHour: Int) {
        NotifyPrefs.putSwitch(appContext, KEY_DND_START_HOUR, startHour.coerceIn(0, 23))
        NotifyPrefs.putSwitch(appContext, KEY_DND_END_HOUR, endHour.coerceIn(0, 23))
    }

    /**
     * 免打扰时段判定（仅 critical 可突破）。
     *
     * 时段由 [KEY_DND_START_HOUR] / [KEY_DND_END_HOUR] 决定，默认 23:00-07:00：
     * - `start < end`：同一天内的窗口，命中条件 `start <= h < end`；
     * - `start > end`：跨零点窗口，命中条件 `h >= start || h < end`；
     * - `start == end`：零长度窗口 → 恒不静默（见 [KEY_DND_START_HOUR] 说明）。
     */
    fun isDndActive(): Boolean {
        if (!switchOn(KEY_DND_ENABLED, false)) return false
        val (start, end) = dndWindow()
        if (start == end) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    /** 5min 限频查询（只读，不置位）。 */
    fun isRateLimited(type: String): Boolean = isRateLimited(type, RATE_LIMIT_MS)

    /** 限频查询（窗口可配；只读，不置位）。 */
    private fun isRateLimited(type: String, windowMs: Long): Boolean {
        if (windowMs <= 0L) return false
        val last = statePrefs.getLong("last_notified_${type}_at", 0L)
        return System.currentTimeMillis() - last < windowMs
    }

    /** 限频置位（必须在真正发出通知后调用，否则 [isRateLimited] 永远返回 false）。 */
    private fun markRateLimited(type: String) {
        statePrefs.edit().putLong("last_notified_${type}_at", System.currentTimeMillis()).apply()
    }

    // ═══════════════════════ 告警（N1-N4 / N7） ═══════════════════════

    /**
     * 差异检测新告警并发通知。
     *
     * 2026-08-25 单进程收敛：
     * - **系统状态栏通知只由 `:ufi_notify` 进程发射**（游标单写者，彻底消除跨进程重复/漏发）；
     *   主进程（UI 轮询 / WS 推送 / WorkManager）拿到告警后经 [NotifyDispatchReceiver] 转交；
     * - **应用内 banner 只由主进程发射**（[AlertBus] 是进程内总线，跨进程发无意义）。
     */
    fun maybeNotifyNewAlerts(alerts: List<AlertRecord>) {
        if (!switchOn(KEY_ALERT_NOTIF, false)) return
        if (alerts.isEmpty()) return

        if (isNotifyProcess()) {
            // 通知进程：系统通知的唯一发射者
            handleSystemNotification(alerts)
        } else {
            // 主进程：只发应用内 banner，系统通知转交通知进程
            handleInAppToast(alerts)
            NotifyDispatchReceiver.dispatch(appContext, alerts)
        }
    }

    /**
     * 由 [NotifyDispatchReceiver] 在 `:ufi_notify` 进程内调用 —— 系统通知的唯一入口。
     *
     * 这里**故意不再校验总开关**：开关由用户在主进程写入，而 `MODE_PRIVATE` 的
     * SharedPreferences 不跨进程 reload，本进程可能读到过期值而把通知全部吞掉。
     * 转交前主进程的 [maybeNotifyNewAlerts] 已经校验过开关，那份视图才是权威的。
     */
    internal fun consumeDispatchedAlerts(alerts: List<AlertRecord>) {
        if (alerts.isEmpty()) return
        handleSystemNotification(alerts)
    }

    private fun handleSystemNotification(alerts: List<AlertRecord>) {
        val lastId = statePrefs.getLong(KEY_LAST_NOTIFIED_SYSTEM_ALERT_ID, 0L)
        val lastTs = if (statePrefs.contains(KEY_LAST_NOTIFIED_SYSTEM_ALERT_TS)) {
            statePrefs.getLong(KEY_LAST_NOTIFIED_SYSTEM_ALERT_TS, 0L)
        } else {
            // 兼容迁移：读取旧版单游标
            statePrefs.getLong(KEY_LAST_NOTIFIED_ALERT_TS, System.currentTimeMillis())
        }

        val newAlerts = alerts.filter { it.timestamp > lastTs || (it.timestamp == lastTs && it.id > lastId) }
        com.ufi_axis.util.DebugLog.d("NotificationCenter", "SystemNotif: checking ${alerts.size} alerts, ${newAlerts.size} are new (lastTs=$lastTs, lastId=$lastId)")
        if (newAlerts.isEmpty()) return

        // 推进游标
        var maxTs = lastTs; var maxId = lastId
        for (a in newAlerts) {
            if (a.timestamp > maxTs || (a.timestamp == maxTs && a.id > maxId)) {
                maxTs = a.timestamp; maxId = a.id
            }
        }
        statePrefs.edit()
            .putLong(KEY_LAST_NOTIFIED_SYSTEM_ALERT_TS, maxTs)
            .putLong(KEY_LAST_NOTIFIED_SYSTEM_ALERT_ID, maxId)
            // 同步写回旧 Key 保证兼容性
            .putLong(KEY_LAST_NOTIFIED_ALERT_TS, maxTs)
            .putLong(KEY_LAST_NOTIFIED_ALERT_ID, maxId)
            .apply()

        val visible = if (isDndActive()) newAlerts.filter { it.level == "critical" } else newAlerts
        if (visible.isEmpty()) return

        val distinct = visible.filter { alert ->
            val sig = alertSignature(alert)
            if (isRecentlyNotified(sig)) false
            else { markNotifiedSignature(sig); true }
        }
        if (distinct.isEmpty()) return

        // 流量限额预警与设备事件各走各的安静通道，不跟温度/信号这类硬件告警
        // 一起用 PRIORITY_MAX + CATEGORY_ALARM 响铃。
        val (quiet, others) = distinct.partition {
            it.type == "traffic_limit" || it.type == "device_online" || it.type == "device_offline"
        }
        if (quiet.isNotEmpty()) {
            // 场景解析走 channel（CHANNEL_ALERTS → ALERT），所以 traffic80 的分场景开关要在这里判
            quiet.lastOrNull { it.type == "traffic_limit" }?.let {
                if (switchOn(KEY_TRAFFIC_80_NOTIF, false)) notifyTrafficLimit(it)
            }
            // 设备事件的开关由 notify() 按 DEVICE_EVENTS 场景自行判（CHANNEL_EVENTS → DEVICE_EVENTS）
            quiet.filter { it.type != "traffic_limit" }.takeIf { it.isNotEmpty() }?.let { notifyDeviceEvents(it) }
        }
        if (others.isEmpty()) return

        com.ufi_axis.util.DebugLog.i("NotificationCenter", "SystemNotif: sending notification for ${others.size} distinct alerts")
        sendSystemNotification(others)
    }

    private fun handleInAppToast(alerts: List<AlertRecord>) {
        val lastId = statePrefs.getLong(KEY_LAST_TOASTED_ALERT_ID, 0L)
        val lastTs = statePrefs.getLong(KEY_LAST_TOASTED_ALERT_TS, System.currentTimeMillis() - 60000)

        val newAlerts = alerts.filter { it.timestamp > lastTs || (it.timestamp == lastTs && it.id > lastId) }
        com.ufi_axis.util.DebugLog.d("NotificationCenter", "InAppToast: checking ${alerts.size} alerts, ${newAlerts.size} are new (lastTs=$lastTs, lastId=$lastId)")
        if (newAlerts.isEmpty()) return

        var maxTs = lastTs; var maxId = lastId
        for (a in newAlerts) {
            if (a.timestamp > maxTs || (a.timestamp == maxTs && a.id > maxId)) {
                maxTs = a.timestamp; maxId = a.id
            }
        }
        statePrefs.edit()
            .putLong(KEY_LAST_TOASTED_ALERT_TS, maxTs)
            .putLong(KEY_LAST_TOASTED_ALERT_ID, maxId)
            .apply()

        com.ufi_axis.util.DebugLog.i("NotificationCenter", "InAppToast: emitting ${newAlerts.size} alerts to bus")
        // 发送到应用内总线
        AlertBus.emitAll(newAlerts.map { a ->
            AlertBusItem(id = a.id, type = a.type, level = a.level, message = a.message)
        })
    }

    private fun sendSystemNotification(distinct: List<AlertRecord>) {
        val title = if (distinct.size == 1) alertTypeLabel(distinct.first().type) else "${distinct.size} 条新告警"
        val bigText = if (distinct.size == 1) {
            distinct.first().message
        } else {
            distinct.joinToString("\n") { "${alertTypeLabel(it.type)}：${it.message}" }
        }
        showNotification(
            channelId = CHANNEL_ALERTS,
            notificationId = ID_ALERTS,
            title = title,
            text = if (distinct.size == 1) bigText else "${distinct.size} 条新告警，点击查看",
            priority = NotificationCompat.PRIORITY_MAX,
            bigText = bigText,
            category = NotificationCompat.CATEGORY_ALARM,
            onTap = alertDeepLinkIntent()
        )
    }

    private fun isMainProcess(): Boolean {
        val processName = android.app.Application.getProcessName()
        return processName.isEmpty() || !processName.contains(":")
    }

    /** 是否运行在系统通知发射进程（`:ufi_notify`）。 */
    private fun isNotifyProcess(): Boolean =
        android.app.Application.getProcessName().endsWith(PROCESS_NOTIFY)

    /** 近期已通知签名 TTL（ms）：同签名在此窗口内不重复弹，兜底爆发重复（G 修复）。 */
    private val RECENT_NOTIFIED_TTL_MS = 10 * 60 * 1000L

    /** 验证码新鲜度窗口（ms）：早于此的验证码只推游标不弹通知，见 [maybeNotifyVerificationCodes]。 */
    private val VC_FRESH_WINDOW_MS = 30 * 60 * 1000L

    /** 告警签名：type|level|message（同内容重拉取可识别为重复）。 */
    private fun alertSignature(a: AlertRecord): String = "${a.type}|${a.level}|${a.message}"

    /** 是否在近期已通知签名集内（TTL 内）。 */
    private fun isRecentlyNotified(signature: String): Boolean {
        val raw = statePrefs.getString(KEY_RECENT_NOTIFIED_SIGS, null) ?: return false
        val now = System.currentTimeMillis()
        return raw.split(',').any { part ->
            val idx = part.indexOf('@')
            if (idx <= 0) false
            else part.substring(0, idx) == signature && (part.substring(idx + 1).toLongOrNull() ?: 0L) > now
        }
    }

    /** 把签名写入近期已通知集（自动过期清理 + 上限裁剪，防无限增长）。 */
    private fun markNotifiedSignature(signature: String) {
        val now = System.currentTimeMillis()
        val expire = now + RECENT_NOTIFIED_TTL_MS
        val parts = (statePrefs.getString(KEY_RECENT_NOTIFIED_SIGS, null) ?: "")
            .split(',').filter { it.isNotEmpty() }.mapNotNull { part ->
                val idx = part.indexOf('@')
                if (idx <= 0) null
                else {
                    val exp = part.substring(idx + 1).toLongOrNull() ?: 0L
                    if (exp <= now) null else part
                }
            }.toMutableList()
        while (parts.size >= 64) parts.removeAt(0)   // 最多保留 64 条近期签名
        parts.add("$signature@$expire")
        statePrefs.edit().putString(KEY_RECENT_NOTIFIED_SIGS, parts.joinToString(",")).apply()
    }

    // ═══════════════════════ 设备离线/上线（N5 / N6） ═══════════════════════

    /**
     * 设备连接状态通知（由 DashboardModule 连接状态机调用）。
     *
     * 防抖/防重复/防误发的三层保证（限频窗口对本场景已关闭，见 [NotifyScene.CONNECTIVITY]）：
     * 1. **调用方边沿**：DashboardModule 有 60s 离线确认窗口 + `wsEverConnected`，
     *    短暂抖动和冷启动未连接过都不会走到这里；
     * 2. **状态位成对**：[KEY_CONNECTIVITY_OFFLINE_NOTIFIED] 保证「离线只报一次」，
     *    且**只有报过离线才报恢复** —— 避免用户只收到「已恢复」这种无头通知；
     * 3. **发送结果驱动状态位**：只有 [notify] 真的发出去（返回 true）才置位，
     *    否则（开关关闭 / 免打扰 / 通知权限被撤）不置位，权限恢复后不会出现
     *    「没报离线却报了恢复」。
     *
     * 注意不能靠场景限频去重：限频按场景记时间戳，离线报完 5 分钟内恢复的话，
     * 上线通知会被同一个窗口吞掉。
     *
     * 状态位是持久化的，所以 `offline = false` 分支的**清理动作不受任何开关约束** ——
     * 否则「报了离线 → 用户关掉总开关 / 进程被杀」之后状态位会永久卡在 true，
     * 之后真的离线也再也报不出来。上线通知本身仍受开关约束。
     *
     * @param offline true=离线（device_connectivity HIGH）；false=上线（DEFAULT 静默）
     * @param durationMs 离线时长（ms），仅上线通知使用；<=0 时回退为
     *                   `last_device_offline_at` 与当前时间差值。
     */
    fun notifyDeviceConnectivity(offline: Boolean, durationMs: Long = 0L) {
        // 上层总闸：系统通知推送关掉时告警族一条都不发（分场景开关由 notify(CONNECTIVITY) 判断）
        val masterOn = switchOn(KEY_ALERT_NOTIF, false)
        val alreadyNotified = statePrefs.getBoolean(KEY_CONNECTIVITY_OFFLINE_NOTIFIED, false)
        val offlineAt = statePrefs.getLong(KEY_LAST_DEVICE_OFFLINE_AT, 0L)
        val now = System.currentTimeMillis()
        if (offline) {
            if (alreadyNotified) return        // 已报过离线 → 不重复推
            // 先落离线时刻：总闸关 / 免打扰时段都不发通知，但恢复后仍要算得出离线时长
            if (offlineAt == 0L) statePrefs.edit().putLong(KEY_LAST_DEVICE_OFFLINE_AT, now).apply()
            // 不置位 → 总闸打开或出了免打扰时段后仍会补报
            if (!masterOn || isDndActive()) return
            val sent = notify(
                scene = NotifyScene.CONNECTIVITY,
                payload = NotifyPayload(
                    title = "设备离线",
                    message = "随身 WiFi 设备已断开连接",
                    notificationId = ID_CONNECTIVITY_OFFLINE,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    category = NotificationCompat.CATEGORY_STATUS
                )
            )
            if (sent) statePrefs.edit().putBoolean(KEY_CONNECTIVITY_OFFLINE_NOTIFIED, true).apply()
        } else {
            // 没有待清理的离线状态：每次 WS 连上都会调到这里，直接返回避免无谓写盘
            if (!alreadyNotified && offlineAt == 0L) return
            // 清状态优先于一切开关判断，让下一次离线能重新计一轮
            statePrefs.edit()
                .putBoolean(KEY_CONNECTIVITY_OFFLINE_NOTIFIED, false)
                .putLong(KEY_LAST_DEVICE_OFFLINE_AT, 0L)
                .putLong(KEY_LAST_DEVICE_ONLINE_AT, now)
                .apply()
            // 没报过离线就不报恢复：总闸关期间掉线、免打扰吞掉离线的情况都在这里挡住
            if (!masterOn || !alreadyNotified) return
            val duration = if (durationMs > 0L) durationMs else now - offlineAt
            val minutes = (duration / 60_000L).coerceAtLeast(1L)
            notify(
                scene = NotifyScene.CONNECTIVITY,
                payload = NotifyPayload(
                    title = "设备已重新连接",
                    message = "离线 $minutes 分钟",
                    notificationId = ID_CONNECTIVITY_ONLINE,
                    priority = NotificationCompat.PRIORITY_DEFAULT,
                    category = NotificationCompat.CATEGORY_STATUS,
                    silent = true
                )
            )
        }
    }

    /**
     * 设备事件通知（WiFi 客户端接入/离开）。
     *
     * 判定在 core（`AlertEngine.recordDeviceEvent`，type=`device_online`/`device_offline`），
     * 这里只展示。channel 是 device_events（IMPORTANCE_LOW），场景开关
     * [KEY_DEVICE_EVENTS_NOTIF] 由 `notify()` 按 DEVICE_EVENTS 场景判。
     */
    private fun notifyDeviceEvents(events: List<AlertRecord>) {
        if (isDndActive()) return
        val bigText = events.joinToString("\n") { it.message }
        showNotification(
            channelId = CHANNEL_EVENTS,
            notificationId = ID_DEVICE_EVENTS,
            title = if (events.size == 1) alertTypeLabel(events.first().type) else "${events.size} 条设备事件",
            text = if (events.size == 1) events.first().message else "${events.size} 条设备事件，点击查看",
            priority = NotificationCompat.PRIORITY_LOW,
            bigText = bigText,
            category = NotificationCompat.CATEGORY_STATUS,
            silent = true,
            onTap = alertDeepLinkIntent()
        )
    }


    /**
     * 流量限额预警通知（channel 复用 device_alerts_v2，DEFAULT 静默不响铃）。
     *
     * 2026-08-31：判定逻辑已从 app 删除，唯一真源是 core 的
     * `AlertEngine.checkTrafficLimit`（type=`traffic_limit`，边沿触发天然只提醒一次）。
     * 手机不在线时 core 照样发邮件；这里只负责把 core 推来的那条告警显示成安静通知，
     * 而不是跟其他告警一起走 PRIORITY_MAX/CATEGORY_ALARM 的响铃通道。
     */
    private fun notifyTrafficLimit(alert: AlertRecord) {
        if (isDndActive()) return
        showNotification(
            channelId = CHANNEL_ALERTS,
            notificationId = ID_TRAFFIC_80,
            title = "流量预警",
            text = alert.message,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
            silent = true,
            onTap = alertDeepLinkIntent()
        )
    }

    // ═══════════════════════ 新短信 / 验证码（N9 / N10） ═══════════════════════

    /**
     * 新短信差异检测：按联系人未读总数增量判断（[KEY_LAST_SMS_TOTAL] 基线）。
     * 由 ToolsModule 在 WS sms_contacts 推送与轮询兜底路径调用。
     */
    fun maybeNotifyNewSms(contacts: List<SmsContact>) {
        if (!switchOn(KEY_SMS_NOTIF, false)) return
        val unreadTotal = contacts.sumOf { it.unread }
        val lastTotal = statePrefs.getInt(KEY_LAST_SMS_TOTAL, -1)
        if (lastTotal < 0) {
            // 首次加载：只记录基线，不发通知（避免历史未读轰炸）
            statePrefs.edit().putInt(KEY_LAST_SMS_TOTAL, unreadTotal).apply()
            return
        }
        if (unreadTotal <= lastTotal) return
        statePrefs.edit().putInt(KEY_LAST_SMS_TOTAL, unreadTotal).apply()
        val newestUnread = contacts.filter { it.unread > 0 }.maxByOrNull { it.latestTimestamp } ?: return
        notifyNewSms(newestUnread.phoneNumber, newestUnread.latestMsg)
    }

    /** 新短信通知（device_sms，DEFAULT；点击跳转到该联系人对话界面）。 */
    fun notifyNewSms(sender: String, snippet: String) {
        if (!switchOn(KEY_SMS_NOTIF, false)) return
        notify(
            scene = NotifyScene.SMS,
            payload = NotifyPayload(
                title = "新短信",
                message = "$sender：${snippet.take(60)}",
                notificationId = ID_SMS,
                onTap = smsDeepLinkIntent(sender),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                silent = true,
                category = NotificationCompat.CATEGORY_MESSAGE
            )
        )
    }

    /** 验证码提取通知（device_sms，DEFAULT；点击跳转到该联系人对话界面）。 */
    fun notifyVerificationCode(sender: String, code: String) {
        if (!switchOn(KEY_SMS_NOTIF, false)) return   // 验证码依附短信开关
        notify(
            scene = NotifyScene.VERIFICATION_CODE,
            payload = NotifyPayload(
                title = "验证码",
                message = "$sender：$code",
                notificationId = ID_VERIFICATION_CODE,
                onTap = smsDeepLinkIntent(sender),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                silent = true,
                category = NotificationCompat.CATEGORY_MESSAGE
            )
        )
    }

    /**
     * 验证码差异检测（按消息 id 游标 + 新鲜度窗口去重）。
     *
     * 必须**整批**传入（而不是逐条调用），因为两道闸都需要全局视角：
     * - **首次基线**：游标从未写过时只把水位推到当前最大 msgId、不发任何通知。
     *   逐条调用做不到这件事 —— 游标默认 0，第一条历史码就 `msgId > 0` 成立，
     *   于是把缓存里的旧码全弹一遍（2026-08-30 实测：进短信页弹出 4 小时前的验证码）。
     * - **新鲜度窗口**：超过 [VC_FRESH_WINDOW_MS] 的验证码只推游标、不发通知。
     *   验证码本身几分钟内就失效，迟到的通知没有价值，只会重复用户早就处理过的消息。
     *   窗口取 30 分钟是为了兼容 [BackgroundGuardWorker] 的 15/30 分钟周期
     *   （WorkManager 平台下限 15 分钟）；用户把间隔调到 60 分钟时会漏掉一部分，
     *   那种配置本身就放弃了及时性。
     */
    fun maybeNotifyVerificationCodes(codes: List<VerificationCode>) {
        if (codes.isEmpty()) return
        if (!switchOn(KEY_VERIFICATION_NOTIF, false)) return
        if (!switchOn(KEY_SMS_NOTIF, false)) return

        val maxId = codes.maxOf { it.msgId }
        if (!statePrefs.contains(KEY_LAST_NOTIFIED_VC_ID)) {
            statePrefs.edit().putLong(KEY_LAST_NOTIFIED_VC_ID, maxId).apply()
            return
        }
        val lastId = statePrefs.getLong(KEY_LAST_NOTIFIED_VC_ID, 0L)
        if (maxId <= lastId) return
        // 游标一次推到位：过期而没发通知的那些 id 也算处理过，否则下次调用会再评估一遍
        statePrefs.edit().putLong(KEY_LAST_NOTIFIED_VC_ID, maxId).apply()

        val now = System.currentTimeMillis()
        codes.asSequence()
            .filter { it.msgId > lastId && now - it.timestamp <= VC_FRESH_WINDOW_MS }
            .sortedBy { it.msgId }
            .forEach { notifyVerificationCode(it.source, it.code) }
    }

    // ═══════════════════════ 下载完成/失败（N11 / N12） ═══════════════════════

    /**
     * 下载任务状态差异检测：status 首次变为 completed/error 时通知一次
     * （[last_download_status_{taskId}] 状态位）。由 DownloadModule.loadDownloads 对每个任务调用。
     */
    fun checkDownloadTaskStatus(taskId: String, taskName: String, status: String, sizeMb: Long) {
        if (!switchOn(KEY_DOWNLOAD_NOTIF, false)) return
        if (status != "completed" && status != "error") return
        val key = "last_download_status_$taskId"
        val last = statePrefs.getString(key, null)
        if (last == status) return
        statePrefs.edit().putString(key, status).apply()
        notifyDownloadResult(taskName, status, sizeMb)
    }

    /** 下载完成/失败通知（device_downloads，DEFAULT）。 */
    fun notifyDownloadResult(task: String, status: String, sizeMb: Long) {
        if (isDndActive()) return
        val isError = status == "error"
        val title = if (isError) "下载失败" else "下载完成"
        val text = if (isError) "$task 下载失败，点击重试" else "$task 已下载完成（${formatMb(sizeMb)}）"
        showNotification(
            channelId = CHANNEL_DOWNLOADS,
            notificationId = ID_DOWNLOAD,
            title = title,
            text = text,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_PROGRESS,
            silent = true
        )
    }

    // ═══════════════════════ 私有实现 ═══════════════════════

    /**
     * 统一通知发送入口。所有场景共用：点击打开 App 主界面、锁屏可见、自动取消。
     * 权限缺失（SecurityException）静默失败——下次开启开关会重新请求权限。
     */
    /**
     * 旧版统一发送样板 —— 2026-08-10 迁移为 [notify] 的统一入口包装。
     * 原各场景方法保留场景专用去重（last_notified_alert_id / 状态位），发送链路统一走 [notify]。
     */
    private fun showNotification(
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        priority: Int,
        bigText: String? = null,
        category: String = NotificationCompat.CATEGORY_ALARM,
        silent: Boolean = false,
        onTap: PendingIntent? = null
    ) {
        val scene = channelToScene(channelId)
        notify(
            scene = scene,
            payload = NotifyPayload(
                title = title,
                message = text,
                bigText = bigText,
                notificationId = notificationId,
                priority = priority,
                silent = silent,
                // category 是通知类别（CATEGORY_ALARM 等），不是分组 key；
                // 此前被当作 groupKey 传入导致 setCategory 从未生效、单条通知却被折叠成组。
                category = category,
                onTap = onTap
            )
        )
    }

    /** channel id → 场景（showNotification 迁移用；未注册 channel 回退 DEVICE_EVENTS） */
    private fun channelToScene(channelId: String): NotifyScene = when (channelId) {
        CHANNEL_ALERTS -> NotifyScene.ALERT
        CHANNEL_CONNECTIVITY -> NotifyScene.CONNECTIVITY
        CHANNEL_SMS -> NotifyScene.SMS
        CHANNEL_DOWNLOADS -> NotifyScene.DOWNLOAD
        else -> NotifyScene.DEVICE_EVENTS
    }

    /** 幂等创建全部 Channel（Android 8+；已存在的 channel 重复创建无副作用）。 */
    // ═══════════════ 通知模块化统一入口（2026-08-10 半重构） ═══════════════

    /**
     * 通知模块化一行调用入口：新增通知场景只需 `nc.notify(scene, payload)`，
     * 内部自动处理：开关短路 → 去重 → 限频 → 免打扰 → channel → 构建发送。
     *
     * @param scene  场景（[NotifyScene] 注册表，声明 channel/默认开关/重要性/免打扰突破/限频）
     * @param payload 统一载荷（标题/正文/通知 id/去重 key/点击跳转/静默/分组）
     * @return 是否真的发出去了。被开关/去重/限频/权限拦下都返回 false ——
     *         调用方要据此决定是否推进自己的状态位（例如离线通知没发出去就不该记"已通知"，
     *         否则权限恢复后会出现"没报离线却报了恢复"）。
     */
    fun notify(scene: NotifyScene, payload: NotifyPayload): Boolean {
        // ① 开关短路（零开销：轮询高频调用直接 return；key=null 表示本场景没有本地开关）
        val enabledKey = sceneEnabledKey(scene)
        if (enabledKey != null && !switchOn(enabledKey, scene.defaultEnabled)) return false

        // ② 去重（同 key + value 已通知过 → 跳过）
        val dedupKey = payload.deDupKey
        if (dedupKey != null) {
            val dedupValue = payload.deDupValue
            if (dedupValue != null && statePrefs.getString(dedupKey, null) == dedupValue) return false
        }

        // ③ 限频（同场景 rateLimitMinutes 内只发 1 条；0=不限）
        val rateWindowMs = scene.rateLimitMinutes * 60_000L
        if (isRateLimited(scene.sceneId, rateWindowMs)) return false

        // ④ 免打扰（23:00-07:00：dndBreakthrough 场景可突破，其余强制静默）
        val silent = payload.silent || (isDndActive() && !scene.dndBreakthrough)

        // ⑤ 构建 + 发送
        ensureChannels()
        // 系统级总闸：Android 13+ 用户没授 POST_NOTIFICATIONS、或在系统设置里关了本应用通知。
        // 2026-09-04 补一行 WARN：这里原来静默 return，用户报"所有类型通知都不推"时
        // 日志里一点线索都没有 —— 而这恰恰是最常见的原因（权限从未申请过）。
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            DebugLog.w("NotificationCenter", "系统通知被禁用（未授权 POST_NOTIFICATIONS 或系统设置里已关闭），场景 ${scene.sceneId} 丢弃")
            return false
        }
        val pi = payload.onTap ?: defaultLaunchIntent()
        val builder = NotificationCompat.Builder(appContext, scene.channelId)
            .setSmallIcon(payload.smallIconRes)
            .setContentTitle(payload.title)
            .setContentText(payload.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.bigText ?: payload.message))
            .setContentIntent(pi)
            .setAutoCancel(payload.autoCancel)
            .setPriority(payload.priority ?: importanceToPriority(scene.importance))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(silent)
        payload.category?.let { builder.setCategory(it) }
        payload.groupKey?.let { builder.setGroup(it) }
        try {
            NotificationManagerCompat.from(appContext).notify(payload.notificationId, builder.build())
        } catch (_: SecurityException) {
            // 权限被拒：静默失败，且不写去重/限频状态（否则权限恢复后这条永远不再发）
            return false
        }
        // 发送成功后才记录去重 key 与限频时间戳
        if (dedupKey != null) {
            payload.deDupValue?.let { statePrefs.edit().putString(dedupKey, it).apply() }
        }
        if (rateWindowMs > 0L) markRateLimited(scene.sceneId)
        forwardToMail(scene, payload)
        return true
    }

    /**
     * 把刚发出的系统通知转投邮件（邮件通知功能，2026-08-29）。
     *
     * 挂在 [notify] 成功路径末尾：本地通知的开关/去重/限频/免打扰全部先生效，
     * 邮件只是同一条通知的第二个投递通道，不会绕过任何拦截规则。
     *
     * **是否真的发**由 core 决定：SMTP 凭据只存在 core（`/api/sms-forward/config`），
     * 场景白名单（`scenes`）也只在 core 保存一份，app 侧不再复制一套开关 ——
     * 否则两端各存一份必然出现"app 说开、core 说关"的不一致。
     * 这里只负责把 (scene, title, body) 报给 core，由 core 判定 sendable + 场景命中。
     *
     * 全程 fire-and-forget：邮件失败绝不能影响本地通知已经成功的事实，
     * 因此异常只落 DebugLog，不向上抛、不改 [notify] 的返回值。
     */
    private fun forwardToMail(scene: NotifyScene, payload: NotifyPayload) {
        // 短信族由 core 的短信路径独占邮件投递（见 NotifyScene.mailForward），app 不重复发
        if (!scene.mailForward) return
        mailScope.launch {
            try {
                val api = RetrofitClient.getApiService(AppPreferences(appContext))
                api.forwardNotificationMail(
                    mapOf(
                        "scene" to scene.sceneId,
                        "title" to payload.title,
                        "body" to (payload.bigText ?: payload.message)
                    )
                )
            } catch (e: Exception) {
                DebugLog.w("NotificationCenter", "邮件通知转投失败 scene=${scene.sceneId}", e)
            }
        }
    }

    /**
     * 场景 → 开关 key 映射（用户可在设置页逐场景关闭）。
     *
     * 全部场景都有开关：[NotifyScene.TUNNEL] 的**分场景**开关是 core 的
     * `tunnel_notify_on_failure`（闸门在 `TunnelModule.maybeNotifyFailures`），
     * 这里返回的是它的**上层总闸**「系统通知推送」，与 ALERT / CONNECTIVITY 一致 ——
     * 总闸关掉就不该有任何告警类通知漏出来。
     */
    private fun sceneEnabledKey(scene: NotifyScene): String? = when (scene) {
        NotifyScene.ALERT -> KEY_ALERT_NOTIF
        NotifyScene.CONNECTIVITY -> KEY_CONNECTIVITY_NOTIF
        NotifyScene.SMS -> KEY_SMS_NOTIF
        NotifyScene.VERIFICATION_CODE -> KEY_VERIFICATION_NOTIF
        NotifyScene.DOWNLOAD -> KEY_DOWNLOAD_NOTIF
        NotifyScene.TRAFFIC_80 -> KEY_TRAFFIC_80_NOTIF
        NotifyScene.DEVICE_EVENTS -> KEY_DEVICE_EVENTS_NOTIF
        NotifyScene.TUNNEL -> KEY_ALERT_NOTIF
    }

    private fun importanceToPriority(importance: Int): Int = when (importance) {
        NotificationManager.IMPORTANCE_MAX -> NotificationCompat.PRIORITY_MAX
        NotificationManager.IMPORTANCE_HIGH -> NotificationCompat.PRIORITY_HIGH
        NotificationManager.IMPORTANCE_LOW -> NotificationCompat.PRIORITY_LOW
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    /** 默认点击行为：打开 App 主界面 */
    private fun defaultLaunchIntent(): PendingIntent {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * P3（应用内通知）：告警通知点击行为——打开 App 并携带标记跳转到事件中心（Routes.DETAIL_EVENTS）。
     * requestCode 用固定值区分于默认启动 intent，避免互相覆盖。
     */
    private fun alertDeepLinkIntent(): PendingIntent {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ALERT_DEEPLINK, "detail/events")
        }
        return PendingIntent.getActivity(
            appContext, ID_ALERTS, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 短信/验证码通知的点击行为：打开 App 并携带手机号跳转到短信对话界面。
     * requestCode 使用 notificationId 区分不同通知的 PendingIntent。
     */
    private fun smsDeepLinkIntent(phone: String): PendingIntent {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_SMS_PHONE, phone)
        }
        return PendingIntent.getActivity(
            appContext, phone.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "设备告警", NotificationManager.IMPORTANCE_MAX).apply {
                description = "随身 WiFi 设备告警通知（最高优先级）"
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                enableLights(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTIVITY, "设备连接状态", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "设备离线 / 上线通知"
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SMS, "新短信", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "新短信 / 验证码提取通知"
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "下载任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "下载完成 / 失败通知"
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "设备事件", NotificationManager.IMPORTANCE_LOW).apply {
                description = "WiFi 客户端 / 热点等低频事件（默认关闭）"
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            }
        )
    }

    /** 告警类型 → 中文标题。 */
    private fun alertTypeLabel(type: String): String = when (type) {
        "temperature" -> "温度告警"
        "battery" -> "电量告警"
        "traffic" -> "流量告警"
        "traffic_limit" -> "流量预警"
        "device_online" -> "设备接入"
        "device_offline" -> "设备断开"
        "signal" -> "信号告警"
        "connectivity" -> "连接告警"
        else -> "设备告警"
    }

    /** MB → 可读单位。 */
    private fun formatMb(mb: Long): String = when {
        mb >= 1024L * 1024L -> "%.1f TB".format(mb / (1024.0 * 1024.0))
        mb >= 1024L -> "%.1f GB".format(mb / 1024.0)
        else -> "$mb MB"
    }


    // ═══════════════════════ 测试通知（v20h 用户反馈"开了通知没一条"） ═══════════════════════
    //
    // 背景：告警通知已收敛为手机端 NotificationCenter 单一发射器（device 端仅入库 + 广播），
    // 但用户历史上反馈"开了开关却永远见不到通知"。测试通知用于验证通道 / 权限链路是否畅通。
    //
    // 本方法绕过所有开关 / 限频 / 免打扰过滤，直接走 ensureChannels + NotificationManagerCompat.notify
    // 发一条测试通知；调用方（AlertSettingsScreen）可用其确认通道畅通、按钮反馈给用户。
    //
    // @return TestResult.Success 测试通知发送成功（系统 NotificationManager 已接收）
    //         TestResult.PermissionDenied 未授予 POST_NOTIFICATIONS（Android 13+）
    //         TestResult.Failed(other) 其他异常
    //
    // v20h 关键修复：测试通知使用与核心告警不同的 ID 区间（990_000..990_999），避免与正常的
    // NotificationCenter.notify 内部 ID（ID_ALERTS=1000 固定槽位）冲突；同 channel
    // 复用以避免新建 channel 改变重要性等级（device_alerts_v2 为 IMPORTANCE_MAX）。

    /**
     * 测试通知发送结果（业务层 UI 用作 Toast 文案分发）。
     */
    sealed class TestResult {
        object Success : TestResult()
        data class PermissionDenied(val permission: String) : TestResult()
        data class Failed(val error: String) : TestResult()
    }

    private var lastTestNotificationId: Int = 0

    /** 最近一次发送的测试通知 id（用于观察/避免重发）。 */
    fun lastTestNotificationId(): Int = lastTestNotificationId

    fun sendTestNotification(): TestResult {
        // 幂等 channel 重建（用户从 settings 进来时大部分情况下 channel 已存在）
        ensureChannels()
        // 系统级「应用通知」总开关：部分国产 ROM 即使已授予运行时权限也会在此被关，
        // 此时发通知会被系统静默丢弃，用户看到「测试成功却无通知」的假阳性。
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            com.ufi_axis.util.DebugLog.w("NotificationCenter", "sendTestNotification SKIPPED: 系统「应用通知」已关闭")
            return TestResult.PermissionDenied(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // 运行时权限检查：Android 13+ 必须显式 granted，否则 notify 静默丢弃
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                com.ufi_axis.util.DebugLog.w("NotificationCenter", "sendTestNotification SKIPPED: POST_NOTIFICATIONS not granted")
                return TestResult.PermissionDenied(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 通知 id 取 990_000 起的高位（与 ID_ALERTS=1000 / ID_CONNECTIVITY_OFFLINE=2001 /
        // ID_TRAFFIC_80=3001 / ID_SMS=4001 等内部 id 隔离，避免后续被覆盖）。
        val notificationId = 990_000 + ((System.currentTimeMillis() / 1000L).toInt() and 0x3FF)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)  // 系统级 chat 图标（无 res 依赖，避免 mipmap 缺失）
            .setContentTitle("通知测试")
            .setContentText("UFI-AXIS 通知系统正常工作 · 通道畅通")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "这是一条测试通知，用于验证通知开关 / 通道 / 权限链路是否正常工作。\n" +
                "如能看到此通知，请放心开启告警；如看不到，请检查系统「应用通知」- UFI-AXIS - 是否被禁用。"
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
            lastTestNotificationId = notificationId
            com.ufi_axis.util.DebugLog.i("NotificationCenter", "sendTestNotification OK: id=$notificationId channel=$CHANNEL_ALERTS")
            TestResult.Success
        } catch (e: SecurityException) {
            com.ufi_axis.util.DebugLog.w("NotificationCenter", "sendTestNotification SecurityException: ${e.message}")
            TestResult.Failed(e.message ?: "SecurityException")
        } catch (e: Exception) {
            com.ufi_axis.util.DebugLog.w("NotificationCenter", "sendTestNotification Exception: ${e.message}")
            TestResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
