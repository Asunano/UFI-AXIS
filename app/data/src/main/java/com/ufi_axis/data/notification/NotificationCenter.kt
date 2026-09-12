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
import com.ufi_axis.data.model.AlertRecord
import com.ufi_axis.util.DebugLog
import java.util.Calendar

/**
 * 通知中心 —— 全 App 系统通知的唯一出口（notifications-fix-plan Part 3.5）。
 *
 * 职责：
 * - 统一管理 Channel（device_alerts_v2 / device_connectivity / device_sms /
 *   device_downloads / device_events），全部在 [ensureChannels] 一次创建；
 * - 统一开关/状态位持久化（`ufi_axis_prefs`），key 常量集中在本类 companion；
 * - 差异检测去重：`last_notified_alert_id`（告警游标）+ `recent_notified_sigs`（签名 TTL）；
 *   短信 / 验证码 / 下载 / 隧道 / 流量 / 连通性的**事件判定全部在 core**，app 只渲染推送；
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
        /** 统一偏好文件（与 AppPreferences / AlertSettingsScreen 共用）。 */
        const val PREFS_NAME = "ufi_axis_prefs"

        // ═══════════════════ 通知闸门层级（2026-09-08 定契约）═══════════════════
        //
        // 每一层只回答一个问题，键与写入方一一对应；判定实现只允许有一处。
        //
        //   L0 系统权限   areNotificationsEnabled()          只读，不落库，仅参与显示态
        //   L1 全局总闸   KEY_NOTIFY_MASTER                  管**所有渠道**（状态栏 + 邮件）
        //   L2 渠道       状态栏 = L1 且 L0 放行（不另设键）
        //                 邮件   = core 的 mailEnabled（邮件通知页写，app 侧不存）
        //   L3 分类       KEY_ALERT_NOTIF / KEY_CONNECTIVITY_NOTIF / KEY_SMS_NOTIF /
        //                 KEY_VERIFICATION_NOTIF / KEY_DOWNLOAD_NOTIF /
        //                 KEY_TRAFFIC_80_NOTIF / KEY_DEVICE_EVENTS_NOTIF / KEY_TUNNEL_NOTIF
        //
        // 独立轴（**不**挂在通知树上）：
        //   免打扰   KEY_DND_ENABLED + 时段  —— 状态栏恒定遵守；邮件仅在 core 的
        //                                      mailRespectDnd 打开时遵守
        //   后台守护 KEY_GUARD_ENABLED       —— 巡检调度，与通知无关
        //   保活     KEY_GUARD_FOREGROUND_KEEPALIVE
        //
        // 唯一出口：状态栏通知全部经 [notify]（[showNotification] 也是它的包装），
        // L1 与 L3 的判定都只写在那一处；测试通知（[sendTestNotification]）刻意豁免 L1/L3。
        //
        // 病史：在此之前 KEY_ALERT_NOTIF 一个键同时兼任「全局总闸 + 告警分类 + 隧道分类 +
        // 后台守护总闸 + 连接参数通报判据」，而短信 / 验证码 / 下载三条路径根本不读它 ——
        // 于是"系统通知推送"关掉后短信照弹、后台守护却被静默停掉，界面文案也随之说谎。

        /**
         * **L1 全局通知总闸**（默认关）：管所有渠道 —— 状态栏与（经 core 镜像的）邮件。
         *
         * 2026-09-08 新增。升级迁移见 [migrateGateKeys]：老用户 [KEY_ALERT_NOTIF] = true
         * 表示"通知本来是开的"，迁移后 master 置 true 且告警分类保持 true，观感不变。
         */
        const val KEY_NOTIFY_MASTER = "notification_master_enabled"

        /** [migrateGateKeys] 的一次性标记，防止用户之后手动关掉 master 又被迁移改回来。 */
        private const val KEY_GATE_MIGRATED = "notify_gate_migrated_v2"

        /** **L3 告警分类**开关（阈值告警：温度 / 电量 / 流量 / 信号）。 */
        const val KEY_ALERT_NOTIF = "alert_notification_enabled"

        /**
         * **L3 隧道分类**开关（默认关，2026-09-08 拆出）。
         *
         * 此前 `sceneEnabledKey(TUNNEL)` 直接映射到 [KEY_ALERT_NOTIF] —— 隧道在 app 侧
         * 没有自己的键，想「只关隧道失败提醒、保留阈值告警」做不到。
         * core 侧另有 `tunnel_notify_on_failure` 决定要不要推，两者是串联关系。
         */
        const val KEY_TUNNEL_NOTIF = "tunnel_notification_enabled"

        /**
         * 邮件是否也遵守免打扰时段（默认关，2026-09-08）。
         *
         * **判定不在 app**：邮件由 core 发，闸门是 `NotificationRoutes.mailAllowed`。
         * 本键只是给「邮件通知」页显示与下发用的镜像 —— 与其余分类键一样走
         * `NotificationConfigSync` 的 readLocal / applyRemote，不参与任何本机投递判据。
         * 因此它既不在 [CATEGORY_KEYS] 里（它不是分类），也不必镜像给 `:ufi_notify`。
         */
        const val KEY_MAIL_RESPECT_DND = "mail_respect_dnd"

        /**
         * 「严重事件兜底」开关（**默认开**，2026-09-10）。
         *
         * 真源是 core 的 `NotificationConfig.critical_override_enabled`。它是那份配置里
         * **唯一默认为 true** 的字段，所以本地镜像的默认值必须处处写 [DEFAULT_CRITICAL_OVERRIDE] ——
         * 少写一处就是「UI 显示开着、实际按关处理」的假开关。
         *
         * 它能穿透什么：级别为 critical 的事件可越过免打扰时段、渠道未勾选的触发场景、
         * 渠道的最低级别门槛。它**穿不过**总闸 [KEY_NOTIFY_MASTER]（关了一条都不发）、
         * 渠道配置不完整、当日配额已用尽 —— 那三种是"发不出去"，不是"不想发"。
         *
         * app 侧唯一的本机判据在 [notify] 的免打扰分支（状态栏告警在静默时段能不能响铃）。
         * 因此它必须镜像给 `:ufi_notify`（见 `NotifyPrefs.MIRRORED_BOOL_KEYS`）——
         * 那个进程是状态栏通知的唯一发射者，读不到主进程刚写的真源。
         */
        const val KEY_CRITICAL_OVERRIDE = "critical_override_enabled"

        /**
         * [KEY_CRITICAL_OVERRIDE] 的默认值。
         *
         * 必须与 core `NotificationConfig.critical_override_enabled`、
         * `NotificationConfigDto`、`NotificationConfigSync.readLocal` 与
         * `NotifyPrefs.MIRRORED_BOOL_KEYS` 逐字一致。
         */
        const val DEFAULT_CRITICAL_OVERRIDE = true

        /**
         * **L3 分类键全集**（2026-09-08）。顺序即设置页展示顺序。
         *
         * 用途：UI 判断"总闸开着但一个分类都没开"（那种状态下一条通知都不会来，
         * 必须在文案里说出来，否则又是一个"看着开了却收不到"）。
         *
         * 新增场景时必须同时补 [sceneEnabledKey] 与本集合 —— 由 `NotifyGateGuardTest` 守。
         */
        val CATEGORY_KEYS: List<String> = listOf(
            KEY_ALERT_NOTIF,
            KEY_CONNECTIVITY_NOTIF,
            KEY_SMS_NOTIF,
            KEY_VERIFICATION_NOTIF,
            KEY_DOWNLOAD_NOTIF,
            KEY_TRAFFIC_80_NOTIF,
            KEY_DEVICE_EVENTS_NOTIF,
            KEY_TUNNEL_NOTIF
        )

        /**
         * 一次性升级迁移：把旧的"一键兼任总闸"语义拆到新键上。
         *
         * 老版本里 [KEY_ALERT_NOTIF] 同时是全局总闸与告警分类，且隧道借用它。迁移口径：
         * - `master` ← 老 [KEY_ALERT_NOTIF]（老用户开着的话升级后照旧能收到通知，不会"升级即静音"）
         * - 隧道分类 ← 若从未显式写过，取老 [KEY_ALERT_NOTIF]（保持原口径）
         * - 告警分类 ← 原值不动
         *
         * 只在**主进程**执行（[PREFS_NAME] 是共享真源，只允许主进程写），且靠
         * [KEY_GATE_MIGRATED] 标记保证只跑一次 —— 否则用户之后手动关掉 master，
         * 下次冷启动又会被"迁移"改回来。
         */
        fun migrateGateKeys(context: Context) {
            if (NotifyPrefs.isNotifyProcess()) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_GATE_MIGRATED, false)) return
            val legacyOn = prefs.getBoolean(KEY_ALERT_NOTIF, false)
            prefs.edit()
                .putBoolean(KEY_NOTIFY_MASTER, legacyOn)
                .putBoolean(KEY_TUNNEL_NOTIF, prefs.getBoolean(KEY_TUNNEL_NOTIF, legacyOn))
                .putBoolean(KEY_GATE_MIGRATED, true)
                .apply()
        }

        /**
         * 设备离线/上线通知开关（默认**关**，与 [NotifyScene.CONNECTIVITY].defaultEnabled、
         * core 的 `NotificationConfig.connectivity_enabled` 及 DailyNotifyScreen 逐字一致）。
         *
         * 2026-08-29 拆出：此前 CONNECTIVITY 场景与 ALERT 共用 [KEY_ALERT_NOTIF]，
         * 想「只关离线提醒、保留温度告警提醒」做不到。
         * 真正起作用的闸门是 [notifyConnectivity] → [notify] 的 `sceneEnabledKey(CONNECTIVITY)`。
         * 仍受 [KEY_ALERT_NOTIF] 总闸约束（判定与总闸都在 core，app 只渲染推送）。
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
        // guard_last_run_at / guard_next_run_at / guard_last_result 已于 2026-09-08 删除：
        // 它们只服务于「后台守护 → 后台任务状态」那张只读诊断卡，而三项都不可信
        //（next_run_at 是本地估算值而非 WorkManager 排期，见 GuardScheduler.GuardState 的 KDoc）。

        /**
         * 通知历史保留条数（2026-09-08 新增设置项，默认 [DEFAULT_HISTORY_MAX_ROWS]）。
         *
         * 真源在 core 的 `NotificationConfig.history_max_rows`，**一个值同时管两张表**：
         * 本机状态栏通知历史（`notify_history`）与设备端邮件投递记录（`mail_send_records`）。
         * 必须镜像（见 `NotifyPrefs.MIRRORED_INT_KEYS`）—— 状态栏通知历史由 `:ufi_notify` 写，
         * 那个进程读不到主进程刚写的真源。
         */
        const val KEY_HISTORY_MAX_ROWS = "notify_history_max_rows"

        /**
         * 通知历史保留天数（2026-09-08 新增设置项，默认 [DEFAULT_HISTORY_MAX_AGE_DAYS]，**0 = 不限**）。
         *
         * 与 [KEY_HISTORY_MAX_ROWS] 是「先到者生效」的两道上限。
         * 之前这条规则是写死的 7 天常量，界面上看不见 —— 用户把条数调大也留不住，
         * 现在两道都在设置里。真源同样在 core（`history_max_age_days`），同样必须镜像。
         */
        const val KEY_HISTORY_MAX_AGE_DAYS = "notify_history_max_age_days"

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

        /**
         * 通知历史保留条数的默认值与允许区间。
         *
         * 与 core `NotificationRoutes.HISTORY_ROWS_MIN/MAX` 及 `NotificationConfig.history_max_rows`
         * 的默认值逐字一致 —— 分叉会出现「设置页显示 500、实际按别的值裁剪」。
         */
        const val DEFAULT_HISTORY_MAX_ROWS = 500
        const val MIN_HISTORY_MAX_ROWS = 100
        const val MAX_HISTORY_MAX_ROWS = 5000

        /** 保留天数的默认值与允许区间（**0 = 不按时间清理**，是合法值）。 */
        const val DEFAULT_HISTORY_MAX_AGE_DAYS = 30
        const val MIN_HISTORY_MAX_AGE_DAYS = 0
        const val MAX_HISTORY_MAX_AGE_DAYS = 365

        private const val ID_ALERTS = 1000
        private const val ID_CONNECTIVITY_OFFLINE = 2001
        private const val ID_CONNECTIVITY_ONLINE = 2002
        private const val ID_TRAFFIC_80 = 3001
        private const val ID_DEVICE_EVENTS = 3002
        private const val ID_SMS = 4001
        private const val ID_VERIFICATION_CODE = 4002
        private const val ID_DOWNLOAD = 5001

        /** 隧道异常通知 id 基址（按「引擎类型|实例名」散列偏移，保证不同实例互不覆盖）。 */
        private const val ID_TUNNEL_BASE = 9600

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

    /**
     * 「严重事件兜底」是否开启（本进程口径：主进程读真源、`:ufi_notify` 读镜像）。
     *
     * 全 app 只有这一处读它 —— [notify] 的免打扰分支与设置页显示都走这里，
     * 各自 `getBoolean` 一遍就会在默认值上分叉（这个字段默认 true，最容易漏）。
     */
    fun isCriticalOverrideEnabled(): Boolean =
        switchOn(KEY_CRITICAL_OVERRIDE, DEFAULT_CRITICAL_OVERRIDE)

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

        // 连通性 / 流量限额预警 / 设备事件各走各自的 channel 与场景开关，不跟温度、信号
        // 这类硬件告警一起用 PRIORITY_MAX + CATEGORY_ALARM 响铃。
        val (routed, others) = distinct.partition {
            it.type == "connectivity" ||
                it.type == "traffic_limit" || it.type == "device_online" || it.type == "device_offline"
        }
        if (routed.isNotEmpty()) {
            // 连通性：断开与恢复各一条（id 不同，见 notifyConnectivity），所以逐条渲染
            routed.filter { it.type == "connectivity" }.forEach { notifyConnectivity(it) }
            // 只渲染最新那条流量预警（旧的已被它取代，没必要叠一串）
            routed.lastOrNull { it.type == "traffic_limit" }?.let { notifyTrafficLimit(it) }
            // 设备事件的开关由 notify() 按 DEVICE_EVENTS 场景自行判（CHANNEL_EVENTS → DEVICE_EVENTS）
            routed.filter { it.type == "device_online" || it.type == "device_offline" }
                .takeIf { it.isNotEmpty() }?.let { notifyDeviceEvents(it) }
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

    // ═══════════════════════ 设备事件 / 流量预警（N5-N8） ═══════════════════════

    /**
     * 连通性告警通知（NotifyScene.CONNECTIVITY → device_connectivity）。
     *
     * 2026-09-07：此前 core 推来的 `connectivity` 告警和温度/信号一起落到
     * [sendSystemNotification]（CHANNEL_ALERTS + ID_ALERTS），于是 CHANNEL_CONNECTIVITY /
     * ID_CONNECTIVITY_* / [NotifyScene.CONNECTIVITY] 全都没人用，而用户开关
     * [KEY_CONNECTIVITY_NOTIF] 什么都管不着 —— 把「设备离线/上线」关掉照样收得到，是个假开关。
     * 现在走 [notify]，开关由 `sceneEnabledKey(CONNECTIVITY)` 判。
     *
     * 判定全在 core 的 `AlertEngine.checkConnectivity`（边沿触发 + 60s 确认窗口 + 恢复消警），
     * 这里只渲染：不做边沿检测、不记「只报一次」标志位。
     *
     * 方向取自告警自身的级别（core：恢复 = info，断网 = warning），两个方向用不同的通知 id ——
     * 共用一个 id 时「网络已恢复」会把还挂在状态栏的「设备已断网」直接替换掉，
     * 用户回头看不出刚才断过。
     */
    private fun notifyConnectivity(alert: AlertRecord) {
        val online = alert.level == "info"
        notify(
            scene = NotifyScene.CONNECTIVITY,
            payload = NotifyPayload(
                title = alertTypeLabel(alert.type),
                message = alert.message,
                notificationId = if (online) ID_CONNECTIVITY_ONLINE else ID_CONNECTIVITY_OFFLINE,
                // 恢复是"事后补一条"，静默；断网要看得见，用 channel 的 IMPORTANCE_HIGH 派生优先级
                silent = online,
                category = NotificationCompat.CATEGORY_STATUS,
                onTap = alertDeepLinkIntent()
            )
        )
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
     * 流量限额预警通知（NotifyScene.TRAFFIC_80 → channel 复用 device_alerts_v2，静默不响铃）。
     *
     * 2026-08-31：判定逻辑已从 app 删除，唯一真源是 core 的
     * `AlertEngine.checkTrafficLimit`（type=`traffic_limit`，边沿触发天然只提醒一次）。
     * 手机不在线时 core 照样发邮件；这里只负责把 core 推来的那条告警显示成安静通知，
     * 而不是跟其他告警一起走 PRIORITY_MAX/CATEGORY_ALARM 的响铃通道。
     *
     * 2026-09-07：改走 [NotifyScene.TRAFFIC_80]。原来走 `showNotification(CHANNEL_ALERTS, …)`，
     * 而 `channelToScene(CHANNEL_ALERTS)` 会映射成 [NotifyScene.ALERT] —— 于是闸门读的是总闸
     * [KEY_ALERT_NOTIF]，用户的「流量限额预警」开关 [KEY_TRAFFIC_80_NOTIF] 在通知侧什么都管不着，
     * TRAFFIC_80 这个场景也没人用。channel 与重要性两种写法完全一致（场景声明的就是
     * CHANNEL_ALERTS + IMPORTANCE_DEFAULT），只是闸门终于对上了。
     */
    private fun notifyTrafficLimit(alert: AlertRecord) {
        if (isDndActive()) return
        notify(
            scene = NotifyScene.TRAFFIC_80,
            payload = NotifyPayload(
                title = "流量预警",
                message = alert.message,
                notificationId = ID_TRAFFIC_80,
                priority = NotificationCompat.PRIORITY_DEFAULT,
                silent = true,
                category = NotificationCompat.CATEGORY_STATUS,
                onTap = alertDeepLinkIntent()
            )
        )
    }

    // ═══════════════════════ 新短信 / 验证码（N9 / N10） ═══════════════════════

    /** 新短信通知（device_sms，DEFAULT；点击跳转到该联系人对话界面）。 */
    fun notifyNewSms(sender: String, snippet: String) {
        // 开关判定不在这里：[notify] 里 sceneEnabledKey(SMS) 就是 KEY_SMS_NOTIF，
        // 再查一遍属于"每个入口各自复查"，与总闸同一类坑（2026-09-08 删）。
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
        // 2026-09-08：原来这里查的是 KEY_SMS_NOTIF（"验证码依附短信开关"），
        // 于是「关短信通知、留验证码」做不到，而设置页明明有独立的验证码开关。
        // 现在闸门只有一处：[notify] 里 sceneEnabledKey(VERIFICATION_CODE) = KEY_VERIFICATION_NOTIF。
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

    // ═══════════════════════ 下载完成/失败（N11 / N12） ═══════════════════════

    /**
     * 下载完成/失败通知（device_downloads，DEFAULT）—— 渲染 core 推来的 `download` 通知。
     *
     * 判定（终态跃迁、只提醒一次）唯一真源是 core 的 `DownloadManager.notifyTaskResult`，
     * 标题/正文直接用推送里那份（core 那份带文件名、来源、大小、错误原因）。
     *
     * @param isError 推送 `extra.status == "error"`，只影响 category 与折叠态首行。
     */
    fun notifyDownloadResult(title: String, message: String, isError: Boolean) {
        if (isDndActive()) return
        showNotification(
            channelId = CHANNEL_DOWNLOADS,
            notificationId = ID_DOWNLOAD,
            title = title,
            text = message.lineSequence().firstOrNull() ?: title,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            bigText = message,
            category = if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_PROGRESS,
            silent = true
        )
    }

    // ═══════════════════════ 内网穿透隧道异常（N13） ═══════════════════════

    /**
     * 隧道异常通知（NotifyScene.TUNNEL → device_events）—— 渲染 core 推来的 `tunnel` 通知。
     *
     * 判定（看护重连到达上限后放弃）与分场景开关 `tunnel_notify_on_failure` 都在 core 的
     * `TunnelManager.notifyGiveUp`，推送到达即代表「该提醒」；app 侧只剩 [notify] 里的
     * 上层总闸与免打扰。通知 id 由「引擎类型 + 实例名」一起派生：只用名字的话，
     * 同名的 FRP 通道与 CF 隧道会算出同一个 id，后发的那条把前一条覆盖掉。
     */
    fun notifyTunnelFailure(kind: String, name: String, title: String, message: String) {
        notify(
            scene = NotifyScene.TUNNEL,
            payload = NotifyPayload(
                title = title,
                message = message.lineSequence().firstOrNull() ?: title,
                bigText = message,
                notificationId = ID_TUNNEL_BASE + ("$kind|$name".hashCode() and 0x7FF),
                groupKey = "tunnel_failure"
            )
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
        // 每条通知的结果都要落历史（送达 / 被谁拦下），因为下面 5 个分支原本全是静默
        // `return false` —— 用户报"收不到通知"时除了猜没有别的线索（release 只留 WARN/ERROR）。
        // 写在本函数内部而不是各调用点：唯一出口就是这里，记录才不依赖调用方自觉。
        fun blocked(reason: String): Boolean {
            NotifyHistoryStore.record(
                context = appContext,
                sceneId = scene.sceneId,
                title = payload.title,
                message = payload.message,
                delivered = false,
                blockedBy = reason
            )
            return false
        }

        // ⓪ 全局总闸（L1，2026-09-08）：所有状态栏通知的唯一总出口都在本函数，
        //    所以这一句就够了，不需要各投递路径各自复查（那正是短信/下载当年漏检的成因）。
        //    测试通知不经本函数（[sendTestNotification] 直接调 NotificationManagerCompat），
        //    因此排查链路时它照样能发 —— 这是刻意的豁免。
        if (!switchOn(KEY_NOTIFY_MASTER, false)) return blocked(NotifyHistoryStore.REASON_MASTER)

        // ① 分类开关短路（L3，零开销：轮询高频调用直接 return；key=null 表示本场景没有本地开关）
        val enabledKey = sceneEnabledKey(scene)
        if (enabledKey != null && !switchOn(enabledKey, scene.defaultEnabled)) {
            return blocked(NotifyHistoryStore.REASON_CATEGORY)
        }

        // ② 去重（同 key + value 已通知过 → 跳过）
        val dedupKey = payload.deDupKey
        if (dedupKey != null) {
            val dedupValue = payload.deDupValue
            if (dedupValue != null && statePrefs.getString(dedupKey, null) == dedupValue) {
                return blocked(NotifyHistoryStore.REASON_DEDUP)
            }
        }

        // ③ 限频（同场景 rateLimitMinutes 内只发 1 条；0=不限）
        val rateWindowMs = scene.rateLimitMinutes * 60_000L
        if (isRateLimited(scene.sceneId, rateWindowMs)) {
            return blocked(NotifyHistoryStore.REASON_RATE_LIMIT)
        }

        // ④ 免打扰（23:00-07:00：dndBreakthrough 场景可突破，其余强制静默）
        //    注意它**不算拦截**：通知照样送达，只是不响铃震动，所以不记 blocked。
        //
        //    2026-09-10：突破权还要再过一道「严重事件兜底」开关（默认开）。
        //    这是 app 侧唯一受该开关影响的本机判据 —— 用户把兜底关掉却仍在静默时段被
        //    阈值告警吵醒，那个开关就是假的。口径与 core 一致：兜底只放宽"要不要响"，
        //    从不放宽总闸（总闸在上面第 ① 步已经拦过）。
        val silent = payload.silent ||
            (isDndActive() && !(scene.dndBreakthrough && isCriticalOverrideEnabled()))

        // ⑤ 构建 + 发送
        ensureChannels()
        // 系统级总闸：Android 13+ 用户没授 POST_NOTIFICATIONS、或在系统设置里关了本应用通知。
        // 2026-09-04 补一行 WARN：这里原来静默 return，用户报"所有类型通知都不推"时
        // 日志里一点线索都没有 —— 而这恰恰是最常见的原因（权限从未申请过）。
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            DebugLog.w("NotificationCenter", "系统通知被禁用（未授权 POST_NOTIFICATIONS 或系统设置里已关闭），场景 ${scene.sceneId} 丢弃")
            return blocked(NotifyHistoryStore.REASON_PERMISSION)
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
            return blocked(NotifyHistoryStore.REASON_PERMISSION)
        }
        // 发送成功后才记录去重 key 与限频时间戳
        if (dedupKey != null) {
            payload.deDupValue?.let { statePrefs.edit().putString(dedupKey, it).apply() }
        }
        if (rateWindowMs > 0L) markRateLimited(scene.sceneId)
        NotifyHistoryStore.record(
            context = appContext,
            sceneId = scene.sceneId,
            title = payload.title,
            message = payload.message,
            delivered = true
        )
        return true
    }

    /**
     * 场景 → 开关 key 映射（用户可在设置页逐场景关闭）。
     *
     * 全部场景都有开关：[NotifyScene.TUNNEL] 的**分场景**开关是 core 的
     * `tunnel_notify_on_failure`（闸门在 core 的 `TunnelManager.notifyGiveUp`，推送前就已判过），
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
        NotifyScene.TUNNEL -> KEY_TUNNEL_NOTIF
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
            .setContentText("UFI-AXIS 通知渠道正常 · 此条已成功送达")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "这是一条测试通知，用于确认通知开关、通知渠道与系统权限是否都已放行。\n" +
                "若未收到，请在系统设置的「应用通知」中检查 UFI-AXIS 是否被禁用。"
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
