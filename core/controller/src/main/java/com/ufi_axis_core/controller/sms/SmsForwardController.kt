package com.ufi_axis_core.controller.sms

import android.content.Context
import android.os.BatteryManager
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.core.database.MailSendRecord
import com.ufi_axis_core.core.database.MailSendRecordDao
import com.ufi_axis_core.controller.notify.NotifyTime
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.PushChannel
import com.ufi_axis_core.notify.RetryPolicy
import com.ufi_axis_core.notify.anySent
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.internet.InternetAddress

import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * 邮件通知（原「短信转发」）。
 *
 * 2026-08-29 收敛为**只走 SMTP 邮件**：原来还有 curl Webhook 与钉钉机器人两条支路，
 * 但三条支路共用一个 `method` 单选，意味着永远只有一条在生效，配置面板却要维护三套字段；
 * 而钉钉/Webhook 的实际使用者是零。删掉之后 `enabled` + SMTP 五项就是全部配置。
 * （类名与 `/api/sms-forward/…` 路径保留：web 端与 API 手册都按这个名字引用，
 * 为了改个名去动跨端契约不值得。）
 *
 * ## 2026-09-08 阶段 1：本类退回「SMTP 实现 + 短信业务」两件事
 *
 * 通用通知的分发（闸门 / 重试 / 多渠道）搬到了 `NotificationDispatcher`，本类对外只留：
 * - [forwardSms]：**设备短信**转邮件。它带验证码提取、拦截规则判定、`msg_id` 去重 ——
 *   那是短信业务不是通用通知，所以这条链路不整体经过分发器；但**真正的投递**仍交给分发器
 *   （[attachNotifier]），这样全仓只有一个重试循环。
 * - [deliverMailOnce] / [recordMailOutcome] / [withSmtpWakeLock]：给 `MailChannel` 用的
 *   内部入口。它们**不查闸门** —— 分发器已经查过了，渠道再查一遍就是第二份判定。
 */

class SmsForwardController(
    private val context: Context,
    private val systemCollector: SystemCollector? = null
) {
    private val tag = "SmsForwardController"
    private val prefs = context.getSharedPreferences("sms_forward", Context.MODE_PRIVATE)

    /**
     * 拦截判定（2026-09-08 起取代旧的 `blacklist` 配置字段）。
     *
     * 用 attach 注入而不是构造参数：本控制器在 `buildControllerGraph` 里构造，那一层拿不到
     * `AppDatabase`（规则与拦截记录两张表都在 Room 里）。装配顺序见 `ComponentFactory`。
     * 没 attach 时判定退化成「谁都不拦」—— 降级装配下邮件照发，不会因为拦截模块缺失而静默丢邮件。
     */
    @Volatile
    private var ruleStore: SmsRuleStore? = null

    fun attachRuleStore(store: SmsRuleStore) {
        ruleStore = store
        AppLogger.i(tag, "SmsRuleStore attached")
    }

    /**
     * 邮件投递记录 DAO（2026-09-08）。注入理由同 [ruleStore]：本控制器构造时拿不到 `AppDatabase`。
     *
     * 没 attach 时只是不留历史，发信照走 —— 记录是自查手段，不该成为发信的前置条件。
     */
    @Volatile
    private var mailHistoryDao: MailSendRecordDao? = null

    /**
     * 保留上限的取值口（真源是 `NotificationConfig.history_max_rows` / `history_max_age_days`）。
     *
     * 用 lambda 每次裁剪时现取，而不是 attach 时读一次存下来 —— 存下来等于"改了设置要重启
     * core 才生效"，那正是本仓禁止的假开关。装配层负责把它接到 `NotificationRoutes.read`。
     * 未 attach 时回落 [DEFAULT_MAIL_HISTORY_ROWS] / [DEFAULT_MAIL_HISTORY_AGE_DAYS]。
     */
    @Volatile
    private var mailHistoryMaxRows: (() -> Int)? = null

    /** 保留天数取值口；**0 = 不按时间清理**（语义见 `NotificationConfig.history_max_age_days`）。 */
    @Volatile
    private var mailHistoryMaxAgeDays: (() -> Int)? = null

    fun attachMailHistory(dao: MailSendRecordDao, maxRows: () -> Int, maxAgeDays: () -> Int) {
        mailHistoryDao = dao
        mailHistoryMaxRows = maxRows
        mailHistoryMaxAgeDays = maxAgeDays
        AppLogger.i(tag, "邮件投递记录已接入（当前上限 ${maxRows()} 条 / ${maxAgeDays()} 天，0 天=不限）")
    }

    /**
     * 邮件渠道闸门（总开关 + 免打扰）的**只读取值口**：`null` = 未装配 = 放行。
     *
     * **这不是强制点。** 全仓唯一的闸门强制点是 `NotificationDispatcher.emit`
     * （按渠道判定：邮件受约束 → `Skipped(GATE)`，推送不受约束照投）。本字段存在的唯一用途
     * 是 [isMailGateOpen] —— 给 `POST /api/sms-forward/test` 的响应回一个 `auto_notify_enabled`。
     *
     * 判定**谓词**只有一份：`NotificationRoutes.notifyAllowed`（`master_enabled &&
     * 遵守免打扰时再过静默窗口`），由装配层包成 lambda 注入 —— 本模块因此不必反向依赖
     * `:core:api`。这里读的和分发器拦的是同一个 lambda，所以"显示的状态"与"实际拦不拦"
     * 不会分叉。
     *
     * **不含"邮件通道有没有启用"**：那件事的真源是 [isSendable]。
     */
    @Volatile
    private var mailGate: (() -> Boolean)? = null

    /** 装配邮件闸门取值口；传 null 解除（= 按放行显示）。 */
    fun attachMailGate(gate: (() -> Boolean)?) {
        mailGate = gate
        AppLogger.i(tag, "邮件闸门取值口${if (gate != null) "已接入" else "已解除（按放行显示）"}")
    }

    /**
     * 闸门此刻是否放行自动通知。**只读查询，不拦任何投递。**
     *
     * 只给 `POST /api/sms-forward/test` 的响应用：测试信走 manual 口径不受闸门约束，
     * 但必须让用户知道"这封测试发出去了，自动通知却是关的"，否则他会以为功能已经通了。
     */
    fun isMailGateOpen(): Boolean = MailDelivery.allowed(mailGate, manual = false)


    /**
     * 通知分发器入口（装配层接到 `NotificationDispatcher::emit`）。
     *
     * [forwardSms] 判完短信业务之后把事件交给它，而不是自己走一遍投递 ——
     * 重试循环、投递记录、`channel` 列这些必须只有一处。没 attach 时 [forwardSms] 只能
     * 记一条 WARN 并返回 false：静默丢邮件比报错更难查。
     */
    @Volatile
    private var notifier: Notifier? = null

    fun attachNotifier(n: Notifier?) {
        notifier = n
        AppLogger.i(tag, "通知分发器${if (n != null) "已接入" else "已解除"}")
    }


    /** 写历史 / 裁剪用的 fire-and-forget scope（与 `SmsRuleStore.scope` 同样的用法）。 */
    private val historyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 停机收尾：收掉 [historyScope]。由 `BackendService.stopAllComponents()` 调。
     *
     * 不 cancel 的话「停止服务」之后排队中的历史写入与裁剪还会继续往 Room 里打 ——
     * 与 `pushService.shutdown()` 要解决的是同一类问题：自带 scope 的组件必须自己有取消入口。
     */
    fun shutdown() {
        historyScope.cancel()
    }



    init {
        migrateScenesV2()
        migrateScenesV3()
    }

    /**
     * 场景语义升级（2026-08-31）：短信正文与验证码变成 [SmsForwardConfig.scenes] 里的两个开关。
     *
     * 升级前 `forwardSms` 不看 scenes，设备短信一律转发；直接改成"按 scenes 放行"会让老用户
     * （scenes 里当然没有 sms / verification）突然收不到短信邮件。所以一次性把这两个 id 补进去，
     * 保持升级前后行为一致；补完落标记，用户之后取消勾选不会被再补回来。
     */
    private fun migrateScenesV2() {
        if (prefs.getBoolean(KEY_SCENES_V2_MIGRATED, false)) return
        val scenes = (prefs.getStringSet("scenes", emptySet()) ?: emptySet()).toMutableSet()
        scenes.add(NotifyScenes.SMS)
        scenes.add(NotifyScenes.VERIFICATION)
        prefs.edit()
            .putStringSet("scenes", scenes)
            .putBoolean(KEY_SCENES_V2_MIGRATED, true)
            .apply()
    }

    /**
     * 场景升级 v3（2026-09-08）：电池事件从"伪装成短信"变成自己的 [SCENE_BATTERY] 场景。
     *
     * 升级前电池通知借 `forwardSms("SYSTEM", …)` 进来，而 `SYSTEM` 被显式排除在场景判定之外
     * —— 也就是说它**绕过了所有场景开关**，只要 SMTP 配好就一直发。现在它有了自己的场景，
     * 不补这一次的话老用户（scenes 里当然没有 battery）会突然收不到低电量邮件。
     * 理由与 [migrateScenesV2] 完全一致：迁移不该改变用户观察到的行为。
     */
    private fun migrateScenesV3() {
        if (prefs.getBoolean(KEY_SCENES_V3_MIGRATED, false)) return
        val scenes = (prefs.getStringSet("scenes", emptySet()) ?: emptySet()).toMutableSet()
        scenes.add(NotifyScenes.BATTERY)
        prefs.edit()
            .putStringSet("scenes", scenes)
            .putBoolean(KEY_SCENES_V3_MIGRATED, true)
            .apply()
    }


    data class SmsForwardConfig(
        val enabled: Boolean = false,
        val smtpHost: String = "",
        val smtpPort: Int = 465,
        val smtpUser: String = "",
        val smtpPass: String = "",
        val smtpFrom: String = "",
        val smtpTo: String = "",
        val forwardDevInfo: Boolean = false,
        /**
         * 允许转发邮件的场景 id（对应 app 侧 `NotifyScene.sceneId`：
         * sms / verification / alert / connectivity / download / traffic80 / events / tunnel，
         * 外加设备侧独有的 battery）。
         *
         * **这是邮件渠道场景勾选的唯一真源**，由 `MailChannel.accepts` 读。
         * 设备短信也在这里管：正文走 `sms`、抓到验证码走 `verification`（2026-08-31 起，
         * 见 [migrateScenesV2]）；电池事件走 `battery`（2026-09-08 起，见 [migrateScenesV3]，
         * 此前它伪装成 `SYSTEM` 短信绕过全部场景开关）。
         *
         * `manual`（`POST /api/sms-forward/test`）不受本集合约束 —— 判定在分发器。
         */
        val scenes: Set<String> = emptySet(),
        /**
         * 最低投递级别。**默认 [NotifyLevel.INFO]** = 什么都放行。
         *
         * 2026-09-10「第二步：规则同构」补的旋钮（三条渠道形状一样，见 `ChannelRules`）。
         * 默认取 INFO 而不是抄本机短信那份 CRITICAL：这次改造**不能改变存量用户观察到的行为**，
         * 邮件此前没有级别门槛 —— 默认给个 CRITICAL 等于让所有人升级后突然收不到短信转发邮件。
         */
        val minLevel: NotifyLevel = NotifyLevel.INFO,
        /**
         * 每日上限；**默认 [ChannelRules.UNLIMITED]** = 不限（理由同 [minLevel]）。
         *
         * 量词统一叫「条」而不是「封」：三条渠道的这个旋钮在界面上是同一句话
         * （规则同构的目的就是让用户不用逐页确认形状），为邮件单独换一个量词
         * 会让 app / web / core 报错三处出现三种叫法。
         *
         * 计数器是**邮件渠道独占**的（`sms_forward` prefs 里的 `quota_day` / `quota_count`），
         * 与 Webhook、本机短信那两份物理隔离 —— 共享一个计数器意味着邮件发多了会吃掉
         * 短信的额度，而那正是"某天突然收不到关键短信"这类最难排查的故障。
         */
        val dailyLimit: Int = ChannelRules.UNLIMITED
    ) {
        companion object {
            /**
             * 每日上限的允许区间。**0 = 不限**（[ChannelRules.UNLIMITED]，也是默认值）。
             *
             * 上限 500：这道闸防的不是话费而是**被服务商封**。免费 SMTP（QQ / Gmail / 163）
             * 的日发信量普遍限制在几百封，撞上之后账号会被临时锁定 ——
             * 那时用户看到的是"邮件全都发不出去"，比漏掉几条通知严重得多。
             *
             * 与本机短信那份（1..50，下限 1）的差别在这个 0：短信"一天只允许一条"是合理诉求、
             * "不限"是账单事故；邮件恰好相反。
             */
            const val MIN_DAILY_LIMIT = ChannelRules.UNLIMITED
            const val MAX_DAILY_LIMIT = 500

            /** 级别的线上口径小写名，供 REST 回给客户端渲染下拉（口径同另两条渠道）。 */
            val LEVEL_NAMES: List<String> = NotifyLevel.entries.map { it.wireName }

            /**
             * 返回第一条约束违规说明；全部合法返回 null。口径与 `WebhookConfig.validate` 一致。
             *
             * SMTP 四项为空**是合法的**（= 还没配完，[isSendable] 会判它不可投），
             * 所以这里只校验有取值域的那一项 —— 把"存下去之后才发现永远发不出去"提前到写入时报错。
             */
            fun validate(c: SmsForwardConfig): String? = when {
                c.dailyLimit !in MIN_DAILY_LIMIT..MAX_DAILY_LIMIT ->
                    "每日上限（daily_limit）需在 $MIN_DAILY_LIMIT..$MAX_DAILY_LIMIT 之间" +
                        "（$MIN_DAILY_LIMIT = 不限），收到 ${c.dailyLimit}"
                else -> null
            }
        }
    }


    fun loadConfig(): SmsForwardConfig = SmsForwardConfig(
        enabled = prefs.getBoolean("enabled", false),
        smtpHost = prefs.getString("smtp_host", "") ?: "",
        smtpPort = prefs.getInt("smtp_port", 465),
        smtpUser = prefs.getString("smtp_user", "") ?: "",
        smtpPass = prefs.getString("smtp_pass", "") ?: "",
        smtpFrom = prefs.getString("smtp_from", "") ?: "",
        smtpTo = prefs.getString("smtp_to", "") ?: "",
        forwardDevInfo = prefs.getBoolean("forward_dev_info", false),
        scenes = prefs.getStringSet("scenes", emptySet()) ?: emptySet(),
        // 认不出的级别名回落 INFO（与本字段默认值一致）：一份读坏的配置不该让邮件静默消失。
        // 本机短信那侧刻意回落 CRITICAL —— 那条渠道读坏配置时应该退化成"最省钱"的一档。
        minLevel = levelOf(prefs.getString(KEY_MIN_LEVEL, null)),
        dailyLimit = prefs.getInt(KEY_DAILY_LIMIT, ChannelRules.UNLIMITED)
    )

    fun saveConfig(config: SmsForwardConfig) {
        prefs.edit().apply {
            putBoolean("enabled", config.enabled)
            putString("smtp_host", config.smtpHost)
            putInt("smtp_port", config.smtpPort)
            putString("smtp_user", config.smtpUser)
            putString("smtp_pass", config.smtpPass)
            putString("smtp_from", config.smtpFrom)
            putString("smtp_to", config.smtpTo)
            putBoolean("forward_dev_info", config.forwardDevInfo)
            putStringSet("scenes", config.scenes)
            putString(KEY_MIN_LEVEL, config.minLevel.wireName)
            putInt(KEY_DAILY_LIMIT, config.dailyLimit)
        }.commit()  // 同步写入，确保后续读取能拿到最新值
    }

    // ══════════ 邮件渠道的每日配额（口径与 LocalSmsConfigStore 逐条一致） ══════════
    //
    // 为什么放在本类：邮件的配置就在这份 `sms_forward` prefs 里，配额与配置同生命周期、
    // 同一个文件读写。单开一个 store 只会多一处要在装配层接线的东西。
    // **这一对键是邮件独占的** —— 三条渠道各一份、物理隔离（理由见 `ChannelRules`）。

    /** 配额读写的互斥。只护 `quota_day` + `quota_count` 这一对，配置字段不需要。 */
    private val quotaLock = Any()

    /**
     * 今天已发出几封。**按本地日期判跨天** —— 存 `yyyy-MM-dd` 字符串而不是时间戳，
     * 于是"今天"与用户日历上的今天永远一致（时间戳 + 86400s 的算法会跟着时区/夏令时漂）。
     */
    fun quotaSentToday(today: String = currentDay()): Int = synchronized(quotaLock) {
        if (prefs.getString(KEY_QUOTA_DAY, null) != today) 0 else prefs.getInt(KEY_QUOTA_COUNT, 0)
    }

    /**
     * 计一封，返回计入后的今日已用数。
     *
     * 调用点只有一处：[MailChannel.deliver] 拿到 `Sent` 之后。只在 `Sent` 时计是因为
     * SMTP 的 250 应答是**确定结论**（服务器收下了），不像本机短信那样存在
     * "可能已经发出去了"的中间态 —— 那侧的口径因此是"排除不了已经发出去"。
     */
    fun consumeQuota(today: String = currentDay()): Int = synchronized(quotaLock) {
        val sameDay = prefs.getString(KEY_QUOTA_DAY, null) == today
        val next = (if (sameDay) prefs.getInt(KEY_QUOTA_COUNT, 0) else 0) + 1
        prefs.edit().putString(KEY_QUOTA_DAY, today).putInt(KEY_QUOTA_COUNT, next).apply()
        next
    }

    /**
     * 旧 `blacklist` → `sms_rule` 表的一次性搬迁：读一次、删键、返回待导入的条目。
     *
     * 由 `SmsRuleStore.startStartupMaintenance` 在启动时调用一次。配置里已经没有这个字段了，
     * 所以这里**直读 prefs 原始键** —— 存量安装的旧条目只剩这一条转换路径。
     * 删键 + 落标记是为了幂等：只删键的话，读到空集合与"还没搬过"无法区分；有标记之后
     * 即使旧键被别的写入重新带回来，也不会把同一批条目再导一遍让规则列表越滚越长。
     *
     * @return 需要转成规则的 pattern 列表；已搬过或本来就空时返回空列表。
     */
    fun takeLegacyBlacklistForMigration(): List<String> {
        if (prefs.getBoolean(KEY_BLACKLIST_MIGRATED, false)) return emptyList()
        val legacy = (prefs.getStringSet("blacklist", emptySet()) ?: emptySet()).toList()
        prefs.edit()
            .remove("blacklist")
            .putBoolean(KEY_BLACKLIST_MIGRATED, true)
            .commit()
        if (legacy.isNotEmpty()) {
            AppLogger.i(tag, "旧邮件黑名单 ${legacy.size} 条待转为拦截规则，prefs 键已删除")
        }
        return legacy
    }

    /**
     * 发信计数（app 端「总发送/成功/失败」统计卡的数据源）。
     *
     * 只统计**真正发起过 SMTP 投递**的次数：黑名单拦截、场景未勾选、配置不全
     * 都不是"发送失败"，计进去会让失败数虚高、用户误以为 SMTP 有问题。
     */
    data class MailStats(
        val success: Int = 0,
        val failed: Int = 0,
        val lastSentAt: Long = 0L,
        val lastError: String = ""
    ) {
        val total: Int get() = success + failed
    }

    /**
     * 已投递过的最新短信 id（-1 = 还没有基线）。
     *
     * 2026-08-31 从 `BackendService` 的内存字段挪到这里持久化：内存版在服务重建/进程被杀后
     * 归零，只能靠"短信新鲜度窗口"防止把历史短信重发一遍，而窗口同时也会把**发现得晚**的
     * 新短信一起判成历史 → 邮件永久丢失。id 落盘之后判重不再依赖时钟，窗口只留一个很宽的
     * 上限兜住"换 id 体系/首次运行"。
     */
    var lastForwardedSmsId: Long
        get() = prefs.getLong(KEY_LAST_FORWARDED_SMS_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_FORWARDED_SMS_ID, value).apply() }

    fun loadStats(): MailStats = MailStats(
        success = prefs.getInt(KEY_STAT_SUCCESS, 0),
        failed = prefs.getInt(KEY_STAT_FAILED, 0),
        lastSentAt = prefs.getLong(KEY_STAT_LAST_AT, 0L),
        lastError = prefs.getString(KEY_STAT_LAST_ERROR, "") ?: ""
    )

    private fun recordSuccess() {
        prefs.edit()
            .putInt(KEY_STAT_SUCCESS, prefs.getInt(KEY_STAT_SUCCESS, 0) + 1)
            .putLong(KEY_STAT_LAST_AT, System.currentTimeMillis())
            .putString(KEY_STAT_LAST_ERROR, "")
            .apply()
    }

    private fun recordFailure(reason: String) {
        prefs.edit()
            .putInt(KEY_STAT_FAILED, prefs.getInt(KEY_STAT_FAILED, 0) + 1)
            .putString(KEY_STAT_LAST_ERROR, reason.take(200))
            .apply()
    }

    /** 配置是否可用于发信（缺任一必填项都发不出去，调用方据此提前退出/提示）。 */
    fun isSendable(cfg: SmsForwardConfig = loadConfig()): Boolean =

        cfg.enabled && cfg.smtpHost.isNotBlank() && cfg.smtpUser.isNotBlank() &&
            cfg.smtpPass.isNotEmpty() && cfg.smtpTo.isNotBlank()

    /**
     * 设备短信转邮件。
     *
     * 这条链路**不整体经过分发器**：验证码提取、拦截规则判定、`msg_id` 去重都是短信业务，
     * 不是通用通知。但判完之后**投递交给分发器**（[attachNotifier]）——
     * 闸门、重试、投递记录必须只有一处，本函数里不再判闸门。
     *
     * @param manual 手动触发：原样透传给 [NotifyEvent.manual]（分发器据此跳过闸门与场景勾选）。
     * @return true = **除推送之外**至少有一条渠道真的投出去了（邮件 / Webhook / 本机短信）。
     *   目前唯一的消费方是 `BackendService.forwardLatestSmsIfNew` 的一行日志，
     *   不参与任何判定 —— 需要"通报到位"那种语义的用 `DeliveryReport.anyConfirmedSent`。
     */
    suspend fun forwardSms(from: String, body: String, timestamp: Long, manual: Boolean = false): Boolean {
        val cfg = loadConfig()

        if (!isSendable(cfg)) return false
        val code = MailTemplate.extractCode(body)

        // 短信正文 / 验证码是两个独立开关（场景 id 与 app 的 NotifyScene 对齐）：
        // 抓到验证码走 verification，否则走 sms。场景勾选由分发器统一判（MailChannel.accepts），
        // 这里只负责把 scene 定出来 —— 判两遍就是第二份真源。
        val scene = if (code != null) NotifyScenes.VERIFICATION else NotifyScenes.SMS

        // 拦截规则（号码黑名单 + 关键词）。判定与另外五个接入点共用同一个 SmsFilter 快照，
        // 所以「界面上说被拦了」与「邮件真的没发」永远一致。
        //
        // 旧实现是 `blacklist.any { from.contains(it) || body.contains(it) }` ——
        // 一个条目同时匹配发件人和正文，`10086` 既屏蔽运营商也屏蔽任何提到 10086 的短信。
        val store = ruleStore
        val verdict = store?.evaluate(from, body)
        if (verdict is SmsFilter.Verdict.Block) {
            // 正文只打 50 字（沿用既有做法，避免把整条短信写进日志）
            AppLogger.i(tag, "SMS mail blocked by rule ${verdict.ruleId}: from=$from body=${body.take(50)}")
            // 邮件链路拿不到设备侧短信 id（入参只有 from/body/timestamp），msg_id 落 0，
            // 由 store 按 sender + 60s 窗口与推送/入库那两条路径的记录合并。
            store.recordBlocked(msgId = 0L, sender = from, body = body, block = verdict, path = SmsRuleStore.Path.MAIL)
            return false
        }

        val emit = notifier
        if (emit == null) {
            AppLogger.w(tag, "分发器未接入，短信邮件无法投递（from=$from）")
            return false
        }
        return emit(
            NotifyEvent(
                scene = scene,
                level = NotifyLevel.INFO,
                title = if (code != null) "验证码 $code" else "来自 $from 的短信",
                body = body,
                meta = listOf(
                    "发件人" to from,
                    "接收时间" to formatTime(timestamp)
                ),
                highlight = code,
                extra = buildMap {
                    put("sender", from)
                    if (code != null) put("code", code)
                },
                timestamp = timestamp,
                manual = manual,
                // 除**推送**之外的所有渠道都投：邮件、Webhook、本机短信……用户在哪条渠道里
                // 勾了「短信正文 / 验证码」就该在那条渠道收到，这是 2026-09-10 修掉的假开关
                // （此前这里写死 `channels = {mail}`，Webhook 与本机短信勾了永远不触发）。
                //
                // 单独排除推送：设备短信的**推送**由 `DataScheduler` 在"发现新短信"的边沿另行
                // emit（它按"每轮只推最新一条"去重，与邮件按 lastForwardedSmsId 去重不是一回事），
                // 两边同时投同一条短信会推两遍。
                //
                // 用 exclude 而不是把渠道 id 列全（`{mail, webhook, local_sms}`）：那份清单
                // 每加一条新渠道都要回来补，漏补就又是一个"勾了不触发"的假开关。
                exclude = setOf(PushChannel.ID)

            )
        ).anySent()
    }

    /**
     * `MailChannel` 专用：把一条 [NotifyEvent] 渲染成邮件并**投一次**。
     *
     * 不查闸门（分发器查过了）、不重试（循环在分发器）、不写记录（[recordMailOutcome] 另写一次）。
     * 失败原样抛异常 —— 分类交给 [MailDelivery.isRetryable]，那判据只有邮件懂。
     *
     * 发 `multipart/alternative`：先挂纯文本再挂 HTML —— 顺序不能反，
     * 邮件客户端取的是**最后一个**它能渲染的分段，反了就永远显示纯文本。
     */
    internal suspend fun deliverMailOnce(subject: String, event: NotifyEvent) {
        val cfg = loadConfig()
        val mail = MailTemplate.Mail(
            scene = event.scene,
            title = event.title,
            meta = event.meta,
            body = event.body,
            highlight = event.highlight,
            deviceInfo = if (cfg.forwardDevInfo) buildDeviceInfo() else ""
        )
        withContext(Dispatchers.IO) {
            // 会话与报文每次尝试重建一次：重试之间不共享 MimeMessage 会多花一点序列化开销，
            // 但换来的是"报文构建失败"与"投递失败"在同一个 try 里被同样分类，
            // 而 buildMessage 内部对 debugOut 的安装是幂等的（session 用完即弃）。
            Transport.send(buildMessage(cfg, subject, mail))
        }
    }

    /**
     * `MailChannel` 专用：整轮（含重试）结束后写一次统计 + 一条投递记录。
     *
     * **一封邮件只写一条**：重试三次写三条失败记录，用户看到的是"三封发不出去"，与事实不符。
     * 由分发器在整轮结束时驱动 —— `attempts` 只有它知道。
     *
     * [MailStats] 只统计**发起过投递**的两态（既有口径）：跳过既不算成功也不算失败。
     * 把"没勾场景"计进失败数，会让那两个计数器答不了它们唯一要答的问题 ——「SMTP 通不通」。
     * 但**投递记录仍然写**（DB v12 起三态），因为"为什么没收到"只能靠它回答。
     */
    internal fun recordMailOutcome(scene: String, subject: String, outcome: String, detail: String) {
        when (outcome) {
            MailSendRecord.OUTCOME_SENT -> recordSuccess()
            MailSendRecord.OUTCOME_FAILED -> recordFailure(detail)
            else -> Unit
        }
        recordChannelHistory(MailChannel.ID, scene, subject, loadConfig().smtpTo, outcome, detail)
    }



    /**
     * 构建设备状态信息（参考项目 KanoUtils.buildStatusSmsMsg）
     */
    private suspend fun buildDeviceInfo(): String {
        return try {
            val sb = StringBuilder()
            // 电池信息
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) sb.appendLine("Battery: ${level}%")

            // CPU / 内存（如果有 SystemCollector）
            systemCollector?.let { sc ->
                try {
                    val mem = sc.getMemoryInfo()
                    val usedMb = mem.used / 1048576
                    val totalMb = mem.total / 1048576
                    sb.appendLine("Memory: ${"%.0f".format(mem.usage_percent)}% used (${usedMb}MB / ${totalMb}MB)")
                } catch (_: Exception) {}
                try {
                    val cpu = sc.getCpuInfo()
                    sb.appendLine("CPU: ${"%.1f".format(cpu.usage_percent)}%")
                } catch (_: Exception) {}
                try {
                    val uptime = sc.getUptime()
                    sb.appendLine("Uptime: ${uptime["uptime_display"] ?: "N/A"}")
                } catch (_: Exception) {}
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            AppLogger.w(tag, "buildDeviceInfo failed: ${e.message}")
            ""
        }
    }

    /**
     * 整轮投递（含全部重试与退避）的持锁外壳。由 `MailChannel.withRound` 调，分发器负责把
     * 重试循环整段塞进 [block]。
     *
     * 为什么邮件要自己持锁：本方法的调用方五花八门（短信 observer、电池事件、告警引擎、
     * app 回传的 HTTP 请求），指望上层都记得持锁不现实；而 core 唯一那把保活 WakeLock
     * 只在前端连接时续期，前端断开后 SMTP 握手随时可能被 CPU 深睡打断。
     *
     * 锁**只在整轮开头申请一次**，覆盖全部重试与退避（预算见 [SMTP_WAKELOCK_TIMEOUT_MS]）：
     * 每次重试重新申请等于退避期间放开锁，而退避正是最容易被深睡吞掉的那几秒。
     * 锁带超时兜底，finally 释放；取不到锁也照常发（只是可能被打断）。
     */
    internal suspend fun <T> withSmtpWakeLock(block: suspend () -> T): T {
        val wakeLock = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "UfiAxisCore::MailSmtp").apply {
                setReferenceCounted(false)
                acquire(SMTP_WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "SMTP WakeLock 获取失败（投递可能被深睡打断）: ${e.message}")
            null
        }
        try {
            return block()
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
            } catch (_: Exception) {}
        }
    }




    /**
     * 投递记录：**全仓唯一的 insert + 环形裁剪实现**，多渠道共用（`mail_send_records`
     * 自 DB v11 起带 `channel` 列）。由分发器在整轮结束时经渠道的 `recordHistory` 驱动，
     * 一次投递**只写一条** —— 重试三次写三条，用户看到的是"三封发不出去"，与事实不符。
     *
     * 为什么写在这个类里：DAO 与两道保留上限的取值口都在这儿（[attachMailHistory]），
     * 而 `WebhookChannel` 不该为了留档去认识邮件控制器 —— 它拿到的是
     * `DeliveryHistoryRecorder` 那个函数接口，装配层负责接到本方法上。
     *
     * **三态口径（DB v12）**：`sent` / `failed` / `skipped` 都写。`skipped` 是 v12 新增的 ——
     * 只在**渠道已配置**时才会被送进来（判定在 `NotificationDispatcher.recordIfNeeded`），
     * 所以不会出现"SMTP 没填、每条事件记一行"把环形缓冲冲满的情况。
     * 与 [MailStats] 的区别也在这儿：那两个计数器仍是二态，只统计发起过投递的结果。
     *
     * 异步写、失败只记 WARN：历史是自查手段，写不进去不该影响"通知已经发出去了"这件事。
     *
     * @param channel 渠道 id（`MailChannel.ID` / `WebhookChannel.ID` / `LocalSmsChannel.ID`）。
     * @param target 投到哪儿了。邮件是收件地址；Webhook 只给 scheme+host（URL 里带凭据），
     *   本机短信给脱敏号码。
     * @param outcome [MailSendRecord.OUTCOME_SENT] / `OUTCOME_FAILED` / `OUTCOME_SKIPPED` 之一。
     *   `success` 列由本方法从它算出来 —— 调用方拿不到把两者写成矛盾值的机会
     *   （见 [MailSendRecord.success]）。
     * @param detail 失败原因 / 跳过原因；成功时空串。
     */
    fun recordChannelHistory(
        channel: String,
        scene: String,
        subject: String,
        target: String,
        outcome: String,
        detail: String
    ) {
        val dao = mailHistoryDao ?: return
        // 停服之后 historyScope 已经 cancel，此时 launch **既不执行也不报错** ——
        // 那一瞬间的投递记录会静默消失，而 release 只保留 WARN/ERROR，事后完全查不到
        // "为什么最后那条通知没有留档"。所以先问一句，丢了就明确留痕。
        if (!historyScope.isActive) {
            AppLogger.w(
                tag,
                "delivery history dropped after shutdown: channel=$channel scene=$scene outcome=$outcome"
            )
            return
        }
        historyScope.launch {

            try {
                dao.insert(
                    MailSendRecord(
                        channel = channel,
                        scene = scene,
                        subject = subject.take(SUBJECT_CHARS),
                        recipient = target,
                        outcome = outcome,
                        success = outcome == MailSendRecord.OUTCOME_SENT,
                        error = detail.take(ERROR_CHARS)
                    )
                )

                // 环形裁剪：照 SmsRuleStore.recordBlocked 的做法，insert 之后立刻裁。
                // 两道上限都是设置项、每次现取，并钳制到允许区间 ——
                // 远端传进来的值已经被 NotificationRoutes.validate 挡过一轮，这里是第二道保险：
                // 一个 0 或负数的条数会让那条 DELETE 清空整张表。
                dao.trimTo(
                    (mailHistoryMaxRows?.invoke() ?: DEFAULT_MAIL_HISTORY_ROWS)
                        .coerceIn(MIN_MAIL_HISTORY_ROWS, MAX_MAIL_HISTORY_ROWS)
                )
                // 天数 0 = 不按时间清理（合法值，不是"立刻全删"）。
                val days = (mailHistoryMaxAgeDays?.invoke() ?: DEFAULT_MAIL_HISTORY_AGE_DAYS)
                    .coerceIn(MIN_MAIL_HISTORY_AGE_DAYS, MAX_MAIL_HISTORY_AGE_DAYS)
                if (days > 0) {
                    dao.deleteOlderThan(System.currentTimeMillis() - days * DAY_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "投递记录写入失败（投递本身不受影响）：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 投递历史读取：keyset 游标分页（形态与 `/api/alerts/list` 一致）。
     *
     * @param outcome 只看某一态（`null` = 全部）。取值是 [MailSendRecord.OUTCOME_SENT] /
     *   `OUTCOME_FAILED` / `OUTCOME_SKIPPED`。
     * @param channel 只看某个渠道（`null` = 全部）。列表页必须能按渠道分开看，
     *   否则邮件 / Webhook / 本机短信混在一起没法排查。
     */
    suspend fun listMailHistory(
        cursorTs: Long?,
        cursorId: Long?,
        outcome: String?,
        channel: String?,
        limit: Int
    ): List<MailSendRecord> =
        mailHistoryDao?.getPaged(cursorTs, cursorId, outcome, channel, limit) ?: emptyList()


    /**
     * 历史条数（total / failed / skipped 三个计数，给列表页的过滤器打标）。
     *
     * 与 [listMailHistory] 同一个 [channel] 口径：只看 mail 时上面的总数也必须只算 mail，
     * 否则"筛选后 3 条 / 总计 50"这种自相矛盾的数字会让人以为分页坏了。
     *
     * failed 与 skipped **分开两个计数**：跳过不是失败（见 [MailSendRecord.OUTCOME_SKIPPED]），
     * 加在一起会让"失败 12 条"里其实有 11 条是"免打扰时段没发"。
     */
    suspend fun countMailHistory(channel: String?): Triple<Int, Int, Int> {
        val dao = mailHistoryDao ?: return Triple(0, 0, 0)
        return Triple(dao.countAll(channel), dao.countFailed(channel), dao.countSkipped(channel))
    }


    /**
     * 清空投递记录。
     *
     * @param channel 只清一个渠道（`null` = 全清）。带渠道口径是必须的：清空按钮在**各自**的
     *   投递记录页上，不分渠道的话在 Webhook 页按一次会把邮件那侧的失败记录一起删掉。
     */
    suspend fun clearMailHistory(channel: String? = null) {
        val dao = mailHistoryDao ?: return
        if (channel == null) dao.deleteAll() else dao.deleteByChannel(channel)
    }


    /**
     * 构建 SMTP 会话与报文。**整轮投递只调一次**，重试只重发这份报文。
     *
     * 两个超时（连接 / 读各 [SMTP_TIMEOUT_MS]）是 WakeLock 预算与重试耗时的计算依据，
     * 改这里要同步 [MailDelivery.ATTEMPT_BUDGET_MS]。
     */
    private fun buildMessage(cfg: SmsForwardConfig, subject: String, mail: MailTemplate.Mail): MimeMessage {
        ensureMailcap()
        val props = Properties().apply {
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.debug", "true")
            if (cfg.smtpPort == SMTP_SSL_PORT) {
                // SSL (port 465)
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", SMTP_SSL_PORT.toString())
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.ssl.protocols", "TLSv1.2")
            } else {
                // STARTTLS (port 587 etc.)
                put("mail.smtp.starttls.enable", "true")
            }
            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.timeout", SMTP_TIMEOUT_MS.toString())
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(cfg.smtpUser, cfg.smtpPass)
        })

        // 将 JavaMail 协议级调试日志重定向到 AppLogger，可在 app 内查看完整 SMTP 对话。
        // 只在这里装一次：这个 PrintStream 没人关闭，每次重试重装就是每次投递多漏一个。
        session.setDebugOut(java.io.PrintStream(object : java.io.OutputStream() {
            private val buffer = StringBuilder()
            override fun write(b: Int) {
                if (b == '\n'.code) {
                    val line = buffer.toString().trim()
                    if (line.isNotEmpty()) AppLogger.d("$tag/SMTP", line)
                    buffer.clear()
                } else {
                    buffer.append(b.toChar())
                }
            }
        }, true))

        AppLogger.i(tag, "SMTP config: host=${cfg.smtpHost}, port=${cfg.smtpPort}, user=${cfg.smtpUser}, " +
                "passLen=${cfg.smtpPass.length}, from=${cfg.smtpFrom.ifBlank { cfg.smtpUser }}, to=${cfg.smtpTo}")

        return MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.smtpFrom.ifBlank { cfg.smtpUser }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.smtpTo))
            setSubject(subject, "UTF-8")
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply {
                    setText(MailTemplate.text(mail), "UTF-8")
                })
                addBodyPart(MimeBodyPart().apply {
                    setContent(MailTemplate.html(mail), "text/html; charset=UTF-8")
                })
            })
        }
    }


    /**
     * 注册 JavaMail 的 DataContentHandler（Android 上必须手动做，一次性）。
     *
     * `MimeMessage.setContent(Multipart)` / `text/html` 分段在写流时会通过
     * `javax.activation.CommandMap` 反查 handler。桌面 JDK 靠 jar 里的
     * `META-INF/mailcap` 自动注册，但 APK 打包会把各依赖的 META-INF 合并/丢弃，
     * 查不到 handler 就抛 `UnsupportedDataTypeException`，被 JavaMail 包成
     * **`MessagingException: IOException while sending message`** —— 外层报文完全看不出根因。
     *
     * 这也是 2026-08-30 换 HTML 模板后发信开始失败的原因：之前用 `setText()`（纯 text/plain）
     * 走的是不需要查表的快路径，改成 multipart + text/html 后才踩到。
     */
    private fun ensureMailcap() {
        if (mailcapRegistered) return
        synchronized(SmsForwardController::class.java) {
            if (mailcapRegistered) return
            val mc = (CommandMap.getDefaultCommandMap() as? MailcapCommandMap) ?: MailcapCommandMap()
            mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
            mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
            mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
            mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
            CommandMap.setDefaultCommandMap(mc)
            mailcapRegistered = true
        }
    }

    /** 邮件正文里的时间。格式与 Webhook 模板的 `{{time}}` 共用一份（见 [NotifyTime]）。 */
    private fun formatTime(ts: Long): String = NotifyTime.format(ts)

    private companion object {
        const val KEY_STAT_SUCCESS = "stat_success"
        const val KEY_STAT_FAILED = "stat_failed"
        const val KEY_STAT_LAST_AT = "stat_last_at"
        const val KEY_STAT_LAST_ERROR = "stat_last_error"
        const val KEY_LAST_FORWARDED_SMS_ID = "last_forwarded_sms_id"
        const val KEY_SCENES_V2_MIGRATED = "scenes_migrated_v2"
        /** 旧 `blacklist` 已搬迁到 `sms_rule` 表的标记（见 [takeLegacyBlacklistForMigration]）。 */
        const val KEY_BLACKLIST_MIGRATED = "blacklist_migrated_to_rules"

        /** 电池场景已补进 `scenes` 的标记（见 [migrateScenesV3]）。 */
        const val KEY_SCENES_V3_MIGRATED = "scenes_migrated_v3"

        /** 规则同构的两个旋钮（2026-09-10「第二步」）。线上字段名同为 snake_case。 */
        const val KEY_MIN_LEVEL = "min_level"
        const val KEY_DAILY_LIMIT = "daily_limit"

        /**
         * 配额的"哪一天"（`yyyy-MM-dd`，本地日期）与"那天发了几封"。
         * **邮件渠道独占这一对键**（键名与另两条渠道相同，但落在各自的 prefs 文件里）。
         */
        const val KEY_QUOTA_DAY = "quota_day"
        const val KEY_QUOTA_COUNT = "quota_count"

        fun currentDay(): String = java.time.LocalDate.now().toString()

        /** 认不出的级别名回落 INFO（理由见 [SmsForwardConfig.minLevel]）。 */
        fun levelOf(name: String?): NotifyLevel =
            NotifyLevel.entries.firstOrNull { it.wireName == name?.lowercase() } ?: NotifyLevel.INFO




        /** 单次 SMTP 尝试的连接 / 读超时（两项同值）。改这里要同步 [MailDelivery.ATTEMPT_BUDGET_MS]。 */
        const val SMTP_TIMEOUT_MS = 10_000

        /** 隐式 SSL 端口。465 走 SSLSocketFactory，其余端口走 STARTTLS，判据只有这一处。 */
        const val SMTP_SSL_PORT = 465

        /**
         * 单封邮件**整轮投递**的持锁上限。
         *
         * 算式：`maxAttempts(3) × ATTEMPT_BUDGET_MS(20s：connect 10s + read 10s)`
         * `+ totalBackoffMs(2s + 6s)` = 68s，再加 [SMTP_WAKELOCK_MARGIN_MS] 余量 = 90s。
         * 用算式而不是写死数字：改重试次数或退避时，锁不会悄悄变得不够长。
         *
         * 重试参数取自 [RetryPolicy.DEFAULT] —— 那是分发器实际用的那一份，
         * 邮件侧不再另存一套次数/退避（阶段 0 曾有，阶段 1 重试上移后就成了第二份真源）。
         *
         * 锁只在整轮开头申请一次（见 [withSmtpWakeLock]）—— 中途放开等于把退避那几秒
         * 送进深睡；超时自动释放兜住"忘了 release"的泄漏。
         */
        val SMTP_WAKELOCK_TIMEOUT_MS =
            RetryPolicy.DEFAULT.maxAttempts * MailDelivery.ATTEMPT_BUDGET_MS +
                RetryPolicy.DEFAULT.totalBackoffMs + SMTP_WAKELOCK_MARGIN_MS


        /** 持锁余量：SMTP 之外还有 DNS 解析、TLS 握手、报文序列化，超时值管不到这些。 */
        const val SMTP_WAKELOCK_MARGIN_MS = 22_000L


        /**
         * 邮件历史保留条数的默认值与允许区间。
         *
         * 真源是 `NotificationConfig.history_max_rows`（设置项，一个字段同时管设备端邮件历史
         * 与客户端状态栏通知历史）。这三个常量与 `NotificationRoutes` 的
         * `HISTORY_ROWS_MIN/MAX` 及字段默认值必须逐字一致 —— 这里不能引 `:core:api`
         * （模块方向相反），所以只能靠这条注释与那侧的单测钉住。
         */
        const val DEFAULT_MAIL_HISTORY_ROWS = 500
        const val MIN_MAIL_HISTORY_ROWS = 100
        const val MAX_MAIL_HISTORY_ROWS = 5000

        /**
         * 保留天数的默认值与允许区间（真源 `NotificationConfig.history_max_age_days`）。
         * **0 = 不按时间清理**，是合法值。同样必须与 `NotificationRoutes` 那侧逐字一致。
         */
        const val DEFAULT_MAIL_HISTORY_AGE_DAYS = 30
        const val MIN_MAIL_HISTORY_AGE_DAYS = 0
        const val MAX_MAIL_HISTORY_AGE_DAYS = 365

        /** 天 → 毫秒。用 Long 相乘，365 天的毫秒数早就溢出 Int 了。 */
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** 历史里主题 / 失败原因的留存长度（主题本身就可能含验证码短信摘要，够看即可）。 */
        const val SUBJECT_CHARS = 120
        const val ERROR_CHARS = 200




        /** CommandMap 是进程级全局的，注册一次即可（见 [ensureMailcap]）。 */
        @Volatile
        var mailcapRegistered = false
    }
}
