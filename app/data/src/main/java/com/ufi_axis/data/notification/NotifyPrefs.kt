package com.ufi_axis.data.notification

import android.content.Context
import android.content.SharedPreferences

/**
 * 通知相关偏好的**进程边界**统一封装（T14 方案 A）。
 *
 * ## 为什么需要它
 * `SharedPreferences` 是 `MODE_PRIVATE`，两个进程各持一份内存 map，且提交时会把
 * **整个 map** 重写到文件后 rename。因此多进程共写同一文件有两类问题：
 * 1. **写-写互相覆盖**：某进程的 map 对其它 key 是过期的，它的一次写入会把另一进程的改动整体回退
 *    （典型表现：`:ufi_notify` 写一次告警游标，把用户刚改的开关值还原了）；
 * 2. **读过期**：主进程刚写的开关，`:ufi_notify` 读不到（无跨进程 reload）。
 *
 * ## 约定（方案 A）
 * - [SHARED_NAME]（`ufi_axis_prefs`）是**用户配置唯一真源，只允许主进程写**；
 * - `:ufi_notify` 把自己的运行状态写到私有文件 [NOTIFY_NAME]，从不写共享文件；
 * - `:ufi_notify` 读开关时**优先读镜像**（`mirror_` 前缀，存在私有文件里），
 *   镜像由主进程在启动服务 / 转交告警 / 走 AIDL 时下发，从而规避读过期。
 */
object NotifyPrefs {

    /** 共享配置文件：用户开关唯一真源，只允许主进程写。 */
    const val SHARED_NAME = "ufi_axis_prefs"

    /** `:ufi_notify` 私有状态文件（含开关镜像）。 */
    const val NOTIFY_NAME = "ufi_axis_notify_prefs"

    /** 镜像 key 前缀（写在 [NOTIFY_NAME] 里，与真源 key 区分）。 */
    private const val MIRROR_PREFIX = "mirror_"

    /**
     * 需要在 `:ufi_notify` 内读取的布尔开关 → 各自的**真实默认值**（必须镜像）。
     *
     * 用 Map 而不是 Set（2026-09-04 改）：[snapshot] 以前对所有布尔键统一按 `false` 兜底，
     * 于是「默认 true 且用户从未改过」的开关会被镜像成 false。这里的每个默认值都必须与
     * `com.ufi_axis.util.AppPreferences` / core `AppSettings.DEFAULT_*` 逐字一致
     * （日志总闸默认 false、两个子开关默认 true）。
     *
     * 日志开关为什么必须镜像：`:ufi_notify` 与主进程各持一份 `MODE_PRIVATE` 缓存且不跨进程
     * reload —— 用户在 app 里关掉「详细日志」，通知进程仍用它启动时那份旧值，
     * 继续往**同一批日志文件**写 DEBUG（实测 `runtime.log` 里成片的 `[NotifyDispatch]` DEBUG 行）。
     * 表现就是「明明关了，日志还在长」。快照随每次转交/启动下发，通知进程在真正干活
     * （也就是真正会写日志）之前就能拿到最新开关，无需为此额外唤醒进程。
     */
    private val MIRRORED_BOOL_KEYS: Map<String, Boolean> = mapOf(
        NotificationCenter.KEY_ALERT_NOTIF to false,
        NotificationCenter.KEY_DND_ENABLED to false,
        NotificationCenter.KEY_GUARD_ENABLED to false,
        NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE to false,
        KEY_LOG_ENABLED to false,
        KEY_APP_LOG_ENABLED to true,
        KEY_CORE_LOG_ENABLED to true,
        KEY_DEBUG_MODE to false,
        // 2026-09-04：`:ufi_notify` 现在也会发短信/验证码通知（core 在"发现新短信"边沿推
        // type=sms|verification 的 notification，只有这个进程订阅该频道）。它读这两个 key
        // 时若不走镜像，就只能看到自己启动那一刻的 prefs —— 用户在主进程关掉短信通知，
        // 这个进程还会继续弹。默认值与 `NotifyScene.SMS/VERIFICATION_CODE.defaultEnabled` 一致（2026-09-07 起同为 false）。
        // 2026-09-07：两者默认改为 false（用户要求日常通知不默认开启）。
        NotificationCenter.KEY_SMS_NOTIF to false,
        NotificationCenter.KEY_VERIFICATION_NOTIF to false
    )

    /** 日志开关 key（与 `com.ufi_axis.util.AppPreferences` / core `AppSettings` 逐字一致）。 */
    const val KEY_LOG_ENABLED = "log_enabled"
    const val KEY_APP_LOG_ENABLED = "app_log_enabled"
    const val KEY_CORE_LOG_ENABLED = "core_log_enabled"
    const val KEY_DEBUG_MODE = "debug_mode"

    /**
     * 需要在 `:ufi_notify` 内读取的整型配置（必须镜像）→ 各自的默认值。
     *
     * 用 Map 而不是 Set：[snapshot] 要为每个 key 取"未设置时的兜底值"，
     * 统一套一个默认值会把免打扰起止小时写成轮询间隔（30），静默时段直接漂到 30 点。
     */
    private val MIRRORED_INT_KEYS: Map<String, Int> = mapOf(
        NotificationCenter.KEY_GUARD_INTERVAL_MINUTES to GuardScheduler.DEFAULT_INTERVAL_MINUTES,
        NotificationCenter.KEY_DND_START_HOUR to NotificationCenter.DEFAULT_DND_START_HOUR,
        NotificationCenter.KEY_DND_END_HOUR to NotificationCenter.DEFAULT_DND_END_HOUR
    )

    fun isNotifyProcess(): Boolean =
        android.app.Application.getProcessName().endsWith(NotificationCenter.PROCESS_NOTIFY)

    /** 共享配置文件句柄（任何进程都可读；**只有主进程可写**）。 */
    fun shared(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SHARED_NAME, Context.MODE_PRIVATE)

    /** 本进程可安全写入的状态文件：主进程 = 共享文件；`:ufi_notify` = 私有文件。 */
    fun state(context: Context): SharedPreferences =
        if (isNotifyProcess()) {
            context.applicationContext.getSharedPreferences(NOTIFY_NAME, Context.MODE_PRIVATE)
        } else {
            shared(context)
        }

    /** 读布尔开关：`:ufi_notify` 优先读镜像，无镜像才回退共享文件。 */
    fun switchOn(context: Context, key: String, default: Boolean): Boolean {
        if (isNotifyProcess()) {
            val mirror = context.applicationContext
                .getSharedPreferences(NOTIFY_NAME, Context.MODE_PRIVATE)
            val mk = MIRROR_PREFIX + key
            if (mirror.contains(mk)) return mirror.getBoolean(mk, default)
        }
        return shared(context).getBoolean(key, default)
    }

    /**
     * 前台保活服务（`:ufi_notify` 的 `NotifyService`）的**唯一闸门判据**。
     *
     * 判据本体是纯函数 [KeepAliveGate.shouldRun]（JVM 可测），这里只负责取值：
     * 保活键读**本进程口径**（主进程 = 真源；`:ufi_notify` = 镜像，见 [switchOn]）。
     *
     * 判据放在这里而不是各调用点：`NotifyService.shouldRun`（`:app`）与
     * [NotificationConfigClient.startKeepAlive]（`:app:data`）分属两个模块，
     * 后者不能反向依赖前者。两份拷贝迟早分叉 —— 2026-09-05 上午那次回归的成因正是
     * 「每个入口各自复查开关」，其中一处漏了保活键。
     *
     * 默认值与 [MIRRORED_BOOL_KEYS]、`NotificationConfigDto`、`NotificationConfigSync.readLocal`
     * 逐字一致（都是 false）。
     */
    fun keepAliveShouldRun(context: Context): Boolean = KeepAliveGate.shouldRun(
        keepAliveEnabled =
            switchOn(context, NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE, false),
        alertNotifEnabled = switchOn(context, NotificationCenter.KEY_ALERT_NOTIF, false)
    )

    /** 读整型配置：规则同 [switchOn]。 */
    fun switchInt(context: Context, key: String, default: Int): Int {
        if (isNotifyProcess()) {
            val mirror = context.applicationContext
                .getSharedPreferences(NOTIFY_NAME, Context.MODE_PRIVATE)
            val mk = MIRROR_PREFIX + key
            if (mirror.contains(mk)) return mirror.getInt(mk, default)
        }
        return shared(context).getInt(key, default)
    }

    /**
     * 写布尔开关。
     *
     * 主进程 → 写共享文件（真源）；`:ufi_notify` → 只写镜像
     * （该进程走到这里只有 AIDL 通道一种情况，其语义正是"刷新本进程视图"）。
     */
    fun putSwitch(context: Context, key: String, value: Boolean) {
        if (isNotifyProcess()) {
            state(context).edit().putBoolean(MIRROR_PREFIX + key, value).apply()
        } else {
            shared(context).edit().putBoolean(key, value).apply()
        }
    }

    /** 写整型配置：规则同 [putSwitch]。 */
    fun putSwitch(context: Context, key: String, value: Int) {
        if (isNotifyProcess()) {
            state(context).edit().putInt(MIRROR_PREFIX + key, value).apply()
        } else {
            shared(context).edit().putInt(key, value).apply()
        }
    }

    /**
     * 主进程生成开关快照，放进发往 `:ufi_notify` 的 Intent extra。
     * 格式：`key=value` 以 `;` 分隔（类型由 key 归属的集合决定，无需编码类型）。
     */
    fun snapshot(context: Context): String {
        val p = shared(context)
        val parts = ArrayList<String>(MIRRORED_BOOL_KEYS.size + MIRRORED_INT_KEYS.size)
        for ((k, default) in MIRRORED_BOOL_KEYS) parts.add("$k=${p.getBoolean(k, default)}")
        for ((k, default) in MIRRORED_INT_KEYS) parts.add("$k=${p.getInt(k, default)}")
        return parts.joinToString(";")
    }

    /**
     * `:ufi_notify` 收到快照 → 落地为镜像，并同步本进程的日志开关。非通知进程调用为 no-op。
     */
    fun applySnapshot(context: Context, snapshot: String?) {
        if (snapshot.isNullOrBlank() || !isNotifyProcess()) return
        val editor = state(context).edit()
        for (part in snapshot.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx)
            val raw = part.substring(idx + 1)
            when {
                MIRRORED_BOOL_KEYS.containsKey(key) -> editor.putBoolean(MIRROR_PREFIX + key, raw == "true")
                MIRRORED_INT_KEYS.containsKey(key) ->
                    raw.toIntOrNull()?.let { editor.putInt(MIRROR_PREFIX + key, it) }
                else -> {}
            }
        }
        editor.apply()
        applyLogSwitches(context)
    }

    /**
     * 把镜像里的日志开关灌进本进程的 [com.ufi_axis.util.DebugLog]。
     *
     * 通知进程的 `Application.onCreate` 只能读到**它自己启动那一刻**的 prefs 快照，
     * 之后主进程的改动它一概看不到（`MODE_PRIVATE` 不跨进程 reload）。所以每次收到快照
     * 都重新灌一次，否则用户关掉的日志在这个进程里会一直开着。
     */
    private fun applyLogSwitches(context: Context) {
        // 默认值必须与 MIRRORED_BOOL_KEYS / AppPreferences 逐字一致：总闸 false、app 子开关 true、详细 false
        com.ufi_axis.util.DebugLog.masterEnabled = switchOn(context, KEY_LOG_ENABLED, false)
        com.ufi_axis.util.DebugLog.appEnabled = switchOn(context, KEY_APP_LOG_ENABLED, true)
        com.ufi_axis.util.DebugLog.enabled = switchOn(context, KEY_DEBUG_MODE, false)
    }
}

/**
 * 「前台服务保活该不该跑」的**纯判据**（无 Context / 无 Android 依赖，JVM 可测）。
 *
 * 判据只有一条：**「后台守护 → 前台服务保活」开关自己说了算**。
 *
 * ## 为什么把「系统通知推送」摘出去（2026-09-05 下午）
 * 上午那一版写成 `alertNotif && keepAlive`，理由是「告警总闸关掉时服务跑着也发不出任何通知」。
 * 这条理由**与代码不符**：
 * - `:ufi_notify` 是短信 / 验证码推送的**唯一订阅方**（core 在"发现新短信"的边沿推
 *   `type=sms|verification`），而 [NotificationCenter.notifyNewSms] /
 *   [NotificationCenter.notifyVerificationCode] 只看 `KEY_SMS_NOTIF`（默认 **true**），
 *   完全不过 `KEY_ALERT_NOTIF`；
 * - 保活的语义是「让守护进程活着」，告警只是它承载的用途之一。
 *
 * 于是 AND 语义造成了**两个方向都错**：
 * - 用户只开保活（告警总闸默认 false）→ 开关显示为开、服务却起不来（本仓禁止的「假开关」），
 *   同时短信 / 验证码实时推送一起失效；
 * - 用户在「通知与守护」页关掉告警总闸 → 明明还开着保活，常驻通知却被另一个开关撤掉。
 *
 * [alertNotifEnabled] 仍保留在签名里（而不是删掉）：它是这次语义变更的**受测对象** ——
 * 单测用它逐一钉住四种组合，下次谁想把总闸重新 AND 进来，红灯会先响。
 */
object KeepAliveGate {

    /**
     * @param keepAliveEnabled `guard_foreground_keepalive_enabled`（「前台服务保活」），唯一判据。
     * @param alertNotifEnabled `alert_notification_enabled`（「系统通知推送」）：
     *        **不参与**判定，只决定服务跑起来之后发不发告警类通知。
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldRun(keepAliveEnabled: Boolean, alertNotifEnabled: Boolean): Boolean = keepAliveEnabled
}
