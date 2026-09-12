package com.ufi_axis_core.controller.notify

import android.content.Context
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import java.time.LocalDate

/**
 * 本机短信回发渠道（[LocalSmsChannel]）的配置。
 *
 * ## 为什么独立存一份 prefs 而不进 `NotificationConfig`
 *
 * 同 [WebhookConfig]：`NotificationConfig`（`NotificationRoutes.kt:92-94`）明确写了
 * 「渠道启用位不放这里」—— 那份模型是**客户端要不要投递通知**的开关组。
 * 渠道配置归渠道自己（邮件 `sms_forward`、Webhook `notify_webhook`、本渠道 `notify_local_sms`）。
 *
 * ## 这里的每一个字段都是「刹车」
 *
 * 这条渠道**会花钱**（真的从本机 SIM 发出短信、按条计费），所以配置项不是功能开关，
 * 而是三道限幅：[minLevel]（只投够重要的）、[dailyLimit]（一天最多几条）、[scenes]
 * （哪些场景才配得上一条短信）。默认值一律取**最保守**的那一端。
 */
data class LocalSmsConfig(
    val enabled: Boolean = false,
    /**
     * 目标手机号。允许数字 / `+` / `-` / 空格（用户习惯写 `138-0013-8000`），
     * 投递前由 [LocalSmsDelivery.normalizeNumber] 去掉分隔符再交给固件。
     *
     * 空号码 = **未配置齐全**（[LocalSmsDelivery.isConfigured]），不是"发给自己"。
     */
    val targetNumber: String = "",
    /**
     * 最低投递级别。**默认 [NotifyLevel.CRITICAL]** —— 第一道刹车。
     *
     * 级别不够的事件拿 `Skipped(LEVEL_TOO_LOW)`，**不发**。默认只投 CRITICAL 是因为
     * INFO 档里有"新短信 / 验证码"这类每天可能十几条的事件，按条计费的渠道接上去就是账单事故。
     */
    val minLevel: NotifyLevel = NotifyLevel.CRITICAL,
    /**
     * 每日条数上限（本地日期跨天重置，见 [LocalSmsConfigStore.sentToday]）——第二道刹车。
     *
     * 用"今天还剩几条"而不是"24 小时滑动窗口"：用户脑子里的模型就是前者，
     * 滑动窗口会出现"明明一整天没发却说超额"的困惑（昨天 23:50 发的那条还在窗口里）。
     */
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    /** 勾选的场景 id（与邮件 / Webhook 同一套词表 [NotifyScenes]）——第三道刹车。 */
    val scenes: Set<String> = emptySet(),
    /**
     * 免打扰时段要不要连本渠道一起静默。默认 **true**。
     *
     * 与 [LocalSmsChannel.respectsMasterGate]（渠道级常量，恒 true）是两件事：
     * 那个是"总闸管不管我"，这个是用户配置。短信会在半夜把人叫醒，比 Webhook 更该默认跟随免打扰。
     */
    val respectDnd: Boolean = true
) {
    companion object {
        /**
         * 默认每日 5 条。
         *
         * 取值理由：这条渠道只投 CRITICAL（断网 / 套餐用尽 / 自动关网），一天真出 5 次
         * 已经说明设备有持续性故障 —— 再多发只是重复同一件事，而话费是线性烧的。
         */
        const val DEFAULT_DAILY_LIMIT = 5

        /** 每日上限的允许区间。下限 1 = "一天只允许一条"（合法诉求）；上限 50 = 再多就不是通知而是事故。 */
        const val MIN_DAILY_LIMIT = 1
        const val MAX_DAILY_LIMIT = 50

        /** 级别的线上口径小写名（`info` / `warning` / `critical`），供 REST 回给客户端渲染下拉。 */
        val LEVEL_NAMES: List<String> = NotifyLevel.entries.map { it.wireName }

        /**
         * 返回第一条约束违规说明；全部合法返回 null。口径与 [WebhookConfig.validate] 一致。
         *
         * 号码为空**是合法的**（= 还没配完，[LocalSmsDelivery.isConfigured] 会判它不可投），
         * 但填了就必须是能拨出去的形状 —— 把"存下去之后才发现永远发不出去"提前到 PUT 时报错。
         */
        fun validate(c: LocalSmsConfig): String? = when {
            c.targetNumber.isNotBlank() && !LocalSmsDelivery.isValidNumber(c.targetNumber) ->
                "目标号码（target_number）只能包含数字、+、-、空格，去掉分隔符后长度需在 " +
                    "${LocalSmsDelivery.MIN_NUMBER_CHARS}..${LocalSmsDelivery.MAX_NUMBER_CHARS} 之间"
            c.dailyLimit !in MIN_DAILY_LIMIT..MAX_DAILY_LIMIT ->
                "每日条数上限（daily_limit）需在 $MIN_DAILY_LIMIT..$MAX_DAILY_LIMIT 之间，收到 ${c.dailyLimit}"
            // 场景 id 打错字的话界面上勾了却永远不触发，日志里也看不出异常 —— 提前拒掉。
            (c.scenes - NotifyScenes.ALL).isNotEmpty() ->
                "触发场景（scenes）包含无法识别的项：" +
                    (c.scenes - NotifyScenes.ALL).sorted().joinToString(",")
            else -> null
        }
    }
}

/**
 * [LocalSmsConfig] 的持久化 **+ 每日配额计数器**（独立 prefs `notify_local_sms`）。
 *
 * ## 为什么配额也放这里
 *
 * 配额是"这个渠道今天还能不能发"，与渠道配置同生命周期、同一个文件读写。
 * 单开一个 store 只会多一处要在装配层接线的东西，而两者从来不会被分别使用。
 *
 * ## 为什么要锁
 *
 * 分发器串行投递、同一渠道也不会并发进 `deliver`，但 `GET /api/notify/sms/config`
 * 会在任意时刻读 [sentToday]，而 `POST /test` 与自动通知走的是不同协程。
 * "读到一半正好跨天"这种时序不值得靠"应该不会发生"来保证 —— 一把进程内锁就够。
 */
class LocalSmsConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 配额读写的互斥。只护 `quota_day` + `quota_count` 这一对，配置字段不需要。 */
    private val quotaLock = Any()

    /**
     * 配置读写的互斥。**刻意与 [quotaLock] 分开**（口径同 `WebhookConfigStore.configLock`）：
     * 两者护的是不相干的键组，共用一把会让 `GET /config` 去等一次配额写盘完成
     * （投递路径每发一条就写一次）。
     *
     * 为什么配置也要锁：[save] 是"六个键一起写"，[load] 是"六个键一起读"，而
     * `PUT /api/notify/sms/config` 与投递路径在不同协程上。不加锁时 `load()` 可以撞在
     * 一次 `save()` 的中间，读出**新号码 + 旧每日上限**这种从未被用户存过的组合 ——
     * 在一条按条计费的渠道上，那意味着按一个已经被改掉的上限去发短信。
     */
    private val configLock = Any()

    fun load(): LocalSmsConfig = synchronized(configLock) {
        LocalSmsConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            targetNumber = prefs.getString(KEY_TARGET_NUMBER, "") ?: "",
            // 认不出的级别名回落 CRITICAL（**不是** NotifyLevel.fromWire 的 INFO）：
            // 一份读坏的配置在这条渠道上退化成"最省钱"的那一档，而不是"最能烧钱"的那一档。
            minLevel = levelOf(prefs.getString(KEY_MIN_LEVEL, null)),
            dailyLimit = prefs.getInt(KEY_DAILY_LIMIT, LocalSmsConfig.DEFAULT_DAILY_LIMIT),
            scenes = prefs.getStringSet(KEY_SCENES, emptySet()) ?: emptySet(),
            respectDnd = prefs.getBoolean(KEY_RESPECT_DND, true)
        )
    }

    fun save(config: LocalSmsConfig) = synchronized(configLock) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_TARGET_NUMBER, config.targetNumber.trim())
            putString(KEY_MIN_LEVEL, config.minLevel.wireName)
            putInt(KEY_DAILY_LIMIT, config.dailyLimit)
            putStringSet(KEY_SCENES, config.scenes)
            putBoolean(KEY_RESPECT_DND, config.respectDnd)
        }.apply()
    }


    // ══════════ 每日配额 ══════════

    /**
     * 今天已发出几条。**按本地日期判跨天** —— 存的是 `yyyy-MM-dd` 字符串而不是时间戳，
     * 于是"今天"与用户日历上的今天永远一致（时间戳 + 86400s 的算法会跟着时区/夏令时漂）。
     */
    fun sentToday(today: String = currentDay()): Int = synchronized(quotaLock) {
        if (prefs.getString(KEY_QUOTA_DAY, null) != today) 0 else prefs.getInt(KEY_QUOTA_COUNT, 0)
    }

    /** 今天还剩几条（钳到 0，防止用户把上限从 20 调到 5 之后出现负数）。 */
    fun quotaRemaining(limit: Int, today: String = currentDay()): Int =
        (limit - sentToday(today)).coerceAtLeast(0)

    /** 配额还有余量吗。只读，**不消耗** —— 消耗要等设备真的受理（见 [consume]）。 */
    fun hasQuota(limit: Int, today: String = currentDay()): Boolean = sentToday(today) < limit

    /**
     * 计一条，返回计入后的今日已用数。
     *
     * 调用点只有一处：一次投递结束后、结论**排除不了"已经发出去"**时
     * （见 [LocalSmsChannel.deliver] 与 `LocalSmsDelivery.countsTowardQuota`）。
     * 不在发送前预扣是因为"设备明确拒收"那一档是可重试的 —— 预扣会让一次重试烧掉两条配额，
     * 而那两条里一条钱都没花。
     */
    fun consume(today: String = currentDay()): Int = synchronized(quotaLock) {
        val sameDay = prefs.getString(KEY_QUOTA_DAY, null) == today
        val next = (if (sameDay) prefs.getInt(KEY_QUOTA_COUNT, 0) else 0) + 1
        prefs.edit().putString(KEY_QUOTA_DAY, today).putInt(KEY_QUOTA_COUNT, next).apply()
        next
    }

    private companion object {
        /** 独立 prefs 文件名（与邮件的 `sms_forward`、Webhook 的 `notify_webhook` 平级）。 */
        const val PREFS_NAME = "notify_local_sms"

        const val KEY_ENABLED = "enabled"
        const val KEY_TARGET_NUMBER = "target_number"
        const val KEY_MIN_LEVEL = "min_level"
        const val KEY_DAILY_LIMIT = "daily_limit"
        const val KEY_SCENES = "scenes"
        const val KEY_RESPECT_DND = "respect_dnd"

        /** 配额的"哪一天"（`yyyy-MM-dd`，本地日期）与"那天发了几条"。 */
        const val KEY_QUOTA_DAY = "quota_day"
        const val KEY_QUOTA_COUNT = "quota_count"

        fun currentDay(): String = LocalDate.now().toString()

        fun levelOf(name: String?): NotifyLevel =
            NotifyLevel.entries.firstOrNull { it.wireName == name?.lowercase() } ?: NotifyLevel.CRITICAL
    }
}
