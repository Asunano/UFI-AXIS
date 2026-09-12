package com.ufi_axis_core.notify

/**
 * 某个渠道这次为什么不投。
 *
 * 用枚举而不是字符串：`TrafficAutoOffGuard` 要**按原因**决定关不关网
 * （见 [DeliveryReport.anyConfirmedSent]），而"用字符串比较跳过原因"是随手改一句日志
 * 文案就会静默失效的判定。
 *
 * @param label 落进投递记录 `error` 列的中文说明（DB v12 起 `Skipped` 也留档）。
 *   放在枚举上而不是让 app / web 各自维护一张映射表：那两张表加一档新原因时**不会报错**，
 *   只会在界面上显示成空白或原样的枚举名。日志仍然打枚举名（本仓纪律：文案给人看、日志给排查看）。
 */
enum class SkipReason(val label: String) {
    /**
     * **通知总开关关闭**（`NotificationConfig.master_enabled` = false）。
     *
     * **只作用于 [NotifyChannel.respectsMasterGate] = true 的渠道**：推送不受总闸约束，
     * 理由见 [NotificationDispatcher.emit]。
     *
     * 与 [QUIET_HOURS] 分成两档是必须的（2026-09-10）：CRITICAL 兜底
     * （`NotificationDispatcher.criticalOverride`）能穿透免打扰、**永远不能**穿透总开关，
     * 一个含糊的"总开关关闭或处于免打扰时段"让投递记录看不出这次到底属于哪一种，
     * 也让兜底判定没法只针对后者。
     */
    MASTER_OFF("通知总开关已关闭"),

    /**
     * 处于**免打扰时段**（`dnd_enabled` + 静默窗口，且该渠道的 `respectDnd` 为真）。
     *
     * 与 [MASTER_OFF] 的区别见那一档的说明：CRITICAL 事件在兜底开启时可以穿透本档。
     */
    QUIET_HOURS("处于免打扰时段"),

    /** 渠道配置没填全（邮件 = SMTP 四项必填缺一）。 */
    NOT_CONFIGURED("渠道配置不完整"),

    /** 用户没在这个渠道里勾选这个场景（邮件读 `SmsForwardConfig.scenes`）。 */
    SCENE_OFF("该渠道未勾选此触发场景"),

    /**
     * 事件级别低于该渠道配的最低级别（[ChannelRules.minLevel]）。
     *
     * 为什么不复用 [SCENE_OFF]：两者在 [DeliveryReport.anyConfirmedSent] 里**结论相同**
     * （都是用户主动选的"别用这个渠道通知我"），但分发器会把原因打进日志 ——
     * 场景明明勾着却记一行"场景未勾选"，是会把排查带向错误方向的假线索。
     */
    LEVEL_TOO_LOW("事件级别低于该渠道的最低级别"),

    /**
     * 该渠道的**每日条数配额**已用尽（[ChannelRules.dailyLimit]）。
     *
     * 与上面几档的区别是**性质不同**：那几档是"用户不想收"，这一档是
     * **"想通知但没有能力了"**，和 SMTP 没配全同类。所以：
     * - 分发器为它打 **WARN**（不是 INFO）—— 这是真的漏了一条通知；
     * - [DeliveryReport.anyConfirmedSent] **不认它**（配额烧完不等于用户收到了通报）；
     * - CRITICAL 兜底**也穿不透它**（穿透就是无上限烧钱）。
     */
    QUOTA_EXCEEDED("当日发送配额已用尽")
}

/**
 * 闸门对**某个渠道**的三态判决。
 *
 * 为什么不是 Boolean（2026-09-10）：`false` 分不出"用户把总开关关了"与"现在是半夜"，
 * 而 CRITICAL 兜底只允许穿透后者。用布尔的话分发器只能在自己这边重算一次免打扰 ——
 * 那就是第二份闸门判定，而免打扰的真源（每渠道各自的 `respectDnd` + 静默窗口）在装配层。
 *
 * 判定实现仍然全仓只有一份：`NotificationRoutes.notifyVerdict`。装配层把它包成
 * `(渠道 id) -> GateVerdict` 注入分发器，**分发器不重算任何一半**。
 */
enum class GateVerdict {
    /** 放行。 */
    ALLOW,

    /** 通知总开关关闭 → [SkipReason.MASTER_OFF]。兜底也穿不透。 */
    BLOCKED_BY_MASTER,

    /** 处于免打扰时段 → [SkipReason.QUIET_HOURS]。CRITICAL 兜底可以穿透。 */
    BLOCKED_BY_DND
}

/**
 * 单个渠道对一条事件的投递结果。
 *
 * [Skipped] 与 [Failed] 的区别是刻意的：**只有 [Failed] 算投递失败**。
 * 「配置没填全」「场景没勾选」不是投递结果，混进失败数里用户会以为自己有一堆发信失败
 * （沿用 `SmsForwardController.MailStats` 的既有口径）。
 */
sealed interface DeliveryOutcome {
    /** 投出去了。 */
    data object Sent : DeliveryOutcome

    /** 该渠道这次不投。原因见 [SkipReason]；分发器会为每条 [Skipped] 打一行 INFO。 */
    data class Skipped(val reason: SkipReason) : DeliveryOutcome

    /**
     * 投递失败。
     *
     * @param retryable 值不值得再试一次。判据由渠道给出 —— 只有邮件懂
     *   `AuthenticationFailedException` 是永久错、`SocketTimeoutException` 是瞬时错
     *   （见 `MailDelivery.isRetryable`）。分发器只按这个布尔决定循不循环。
     */
    data class Failed(val error: String, val retryable: Boolean) : DeliveryOutcome
}

/**
 * 一次 [NotifyChannel.deliver] 的完整返回：投递结论 + **这一次**的诊断补充。
 *
 * ## 为什么诊断要跟着返回值走，而不是渠道自己留一份快照
 *
 * `DeliveryOutcome` 只有 `Sent` / `Skipped` / `Failed(error)` 三态，回不出"HTTP 几多、
 * 目标说了什么""这次算不算进配额、还剩几条" —— 而那恰恰是各渠道 `/test` 端点唯一有用的
 * 信息。此前的做法是渠道留一个 `@Volatile var lastAttempt`，`/test` 路由投递完再去回读，
 * 那有两个必然出错的地方：
 * 1. **`Skipped` 时根本没投**（[NotificationDispatcher.deliverTo] 直接 return），回读拿到的是
 *    **上一次**的快照 —— 于是 `/test` 出现 `success=false` 配 `verdict=SENT`、
 *    或者"未送达 · HTTP 200 · 一小时前"这种自相矛盾的响应；
 * 2. **并发覆盖**：同一渠道供 5 个触发源 + `/test` 共用，两次投递重叠时后一次会把前一次的
 *    快照冲掉，`/test` 回的是别人那一次的结果。
 *
 * 两者是同一个根因（把本次结果从渠道的可变字段里回读），所以一起收口成返回值。
 *
 * ## 为什么 [diagnostics] 是 `Any?`
 *
 * 状态码、配额数字这些字段的类型与含义只有渠道和它自己的 `/test` 端点懂
 * （`WebhookChannel.Attempt` / `LocalSmsChannel.Attempt`）。压成 `Map<String, String>`
 * 会让消费方逐个字段做字符串解析，而把那两个类型搬进本模块又等于让分发器认识
 * HTTP 状态码与 goform 结论。本模块只负责把它**原样**随结果带回去，不解释它。
 *
 * `null` = 本渠道没有额外可回的东西（推送 / 邮件那两档）。
 */
data class DeliveryAttempt(
    val outcome: DeliveryOutcome,
    val diagnostics: Any? = null
)

/**
 * 一次 [NotificationDispatcher.emit] 的结果：**每渠道结果 + 判定所需的渠道元信息**。
 *
 * ## 为什么不是裸 `Map<String, DeliveryOutcome>`
 *
 * `TrafficAutoOffGuard` 要判的是"**有送达确认的**渠道投出去了吗"
 * （见 [anyConfirmedSent]），而"这个渠道有没有送达确认"是 [NotifyChannel] 的属性，
 * 裸 map 里查不到。三条路可选，选了第三条：
 * 1. 把可确认位塞进 [DeliveryOutcome] —— 那是把渠道元信息焊到"这一次的结果"上，
 *    `Sent` 会从一个单例变成带参数的数据类，每个消费方都得跟着解构；
 * 2. 给分发器单开一个 `emitAndConfirm(event): Boolean` —— 调用方拿到的就不是
 *    [Notifier] 而是第二种钩子签名，正是这次重构要消灭的异构钩子；
 * 3. **本类**：仍然 `is` 一个 `Map<String, DeliveryOutcome>`（委托实现，既有 `.anySent()`
 *    与 `results["mail"]` 全部照旧），只是额外带上渠道元信息。产出方是分发器 ——
 *    它本来就是全仓唯一认识渠道注册表的地方。
 */
class DeliveryReport(
    private val outcomes: Map<String, DeliveryOutcome>,
    /** 本次结果里哪些渠道**有送达确认**（[NotifyChannel.hasDeliveryConfirmation]）。 */
    private val confirmableChannels: Set<String>,
    /**
     * 渠道 id → 本次那一轮**真的投过**时留下的诊断补充（[DeliveryAttempt.diagnostics]）。
     *
     * 只有走到 [NotifyChannel.deliver] 的渠道才会有条目：`Skipped` 那几档一次都没投，
     * 键根本不存在 —— `/test` 路由因此不可能读到上一次投递的残留（那正是本字段要根治的 bug，
     * 理由见 [DeliveryAttempt]）。
     */
    private val diagnostics: Map<String, Any?> = emptyMap()
) : Map<String, DeliveryOutcome> by outcomes {

    /**
     * 某个渠道**本次**的诊断补充；没投过（或该渠道不提供）返回 null。
     *
     * 调用方按自己那个渠道的类型 `as?` 一下即可（`/test` 端点是唯一的消费方）。
     *
     * ## 不变量：非 null ⇔ 本轮走到过 [NotifyChannel.deliver]（且它返回了）
     *
     * `/test` 端点把它当成"本轮到底有没有发起过投递"的判据，客户端再据此决定要不要渲染
     * 「HTTP 状态码」「计入今日配额」那几行 —— 在本机短信这条按条计费的渠道上，
     * 判错一次用户就会多按一次测试、多花一条话费。所以 [NotificationDispatcher] 里
     * 与它相关的两处都得守住：
     * - `Skipped` 那六档一次都没投 → 给 null；
     * - 整轮预算到点时**不打断在飞的那一次**（判在尝试之间），所以带回的是上一次已完成
     *   尝试的诊断，而不是"投过了却没有诊断"。
     */
    fun diagnosticsOf(channelId: String): Any? = diagnostics[channelId]



    /**
     * 「可以放心执行那个会被用户当成故障的动作了吗」——目前唯一的消费方是
     * `TrafficAutoOffGuard`（关移动数据前必须确认通报到位）。
     *
     * 判定只看**有送达确认的渠道**（邮件有 SMTP 250 应答、Webhook 有 2xx、本机短信有信箱
     * tag 回读；推送是 fire-and-forget 广播，恒 `Sent` 说明不了任何事），把这几种情况算作
     * "通报到位"：
     * - `Sent` —— 真的投出去了；
     * - `Skipped(`[SkipReason.MASTER_OFF]`)` / `Skipped(`[SkipReason.QUIET_HOURS]`)`
     *   —— 用户**主动**关掉了通知总开关、或设了免打扰时段；
     * - `Skipped(`[SkipReason.SCENE_OFF]`)` / `Skipped(`[SkipReason.LEVEL_TOO_LOW]`)`
     *   —— 用户**主动**在这个渠道里取消了这个场景的勾选、或把最低级别调高了。
     *
     * 后两档为什么和总闸同类（2026-09-09 补齐）：「在邮件设置里取消勾选 traffic80」与
     * 「关掉通知总开关」是**同一类主动选择**。而在补齐之前，前者会让自动关网
     * **永久不执行** —— 用户开着"到量自动关网"，却因为没勾一个邮件场景而从来不生效，
     * 正是总闸那一档想避免的隐形耦合（"开了但从来不生效"的假开关），只是换了个入口。

     *
     * 仍然返回 false 的三种：
     * - `Skipped(`[SkipReason.NOT_CONFIGURED]`)` —— 渠道没配全，用户根本收不到通报；
     * - `Skipped(`[SkipReason.QUOTA_EXCEEDED]`)` —— **想通知但没能力了**（与"SMTP 没配"同类，
     *   不是用户选的静默）；
     * - `Failed` —— 发不出去。
     *
     * 原语义保留：**通知没发出去就不关网**，否则用户只看到"突然断网"。
     */
    fun anyConfirmedSent(): Boolean = confirmableChannels.any { id ->
        when (val outcome = outcomes[id]) {
            is DeliveryOutcome.Sent -> true
            is DeliveryOutcome.Skipped -> outcome.reason in USER_SILENCED_REASONS
            else -> false
        }
    }

    private companion object {
        /**
         * 「用户自己选的静默」——算作通报到位的跳过原因。
         *
         * 集中成一个集合而不是散在 `when` 里：这几档必须**同进同出**，
         * 而"漏改一处"的表现是自动关网在某个入口下静默地永不执行。
         *
         * [SkipReason.MASTER_OFF] 与 [SkipReason.QUIET_HOURS] 都在里面：它们是 2026-09-10
         * 从一档 `GATE` 拆出来的两半，对本判定的结论与拆分前**完全一致**（两者都是用户
         * 主动选的静默）。拆分只为了让投递记录与 CRITICAL 兜底能区分这两种情况。
         */
        val USER_SILENCED_REASONS = setOf(
            SkipReason.MASTER_OFF,
            SkipReason.QUIET_HOURS,
            SkipReason.SCENE_OFF,
            SkipReason.LEVEL_TOO_LOW
        )
    }
}


/**
 * 至少一个渠道真的投出去了。
 *
 * 给"只关心有没有发出去、不需要区分是哪条渠道"的调用方用：
 * `POST /api/sms-forward/test` 与 `/notify`（都限定了 `channels = {mail}`，所以这个布尔
 * 就是邮件的结论），以及 `SmsForwardController.forwardSms`（2026-09-10 起是
 * `exclude = {push}`，所以它的语义是"邮件 / Webhook / 本机短信里至少一条发出去了"，
 * 落点只有一行日志）。
 *
 * **不要拿它当"通报到位"用** —— 推送恒返回 `Sent`（没有送达确认），
 * 混着推送一起判会让这个布尔恒真。那种判定用 [DeliveryReport.anyConfirmedSent]。
 */
fun Map<String, DeliveryOutcome>.anySent(): Boolean = values.any { it is DeliveryOutcome.Sent }

/**
 * 触发源与分发器之间**唯一**的钩子签名。
 *
 * 12 个触发源全部用它，装配层一行 `attachNotifier(dispatcher::emit)`。
 * 之所以返回结果而不是 `Unit`：`TrafficAutoOffGuard` 必须知道"通报到位了吗"
 * （见 [DeliveryReport.anyConfirmedSent]）。给它单开一个返回 Boolean 的签名就又变回
 * 异构钩子了 —— 一个签名 + 不关心结果的调用方直接忽略返回值，比两个签名好维护。
 */
typealias Notifier = suspend (NotifyEvent) -> DeliveryReport

/**
 * 一个投递渠道。
 *
 * 渠道**不认识触发源**：它只拿到 [NotifyEvent]，不知道事件来自告警还是下载。
 * 也**不自己查总开关与免打扰** —— 那道闸门在分发器（见 [NotificationDispatcher.emit]），
 * 渠道只用 [respectsMasterGate] 声明"闸门管不管我"。
 */
interface NotifyChannel {

    /** 渠道 id。落进投递记录的 `channel` 列，也是 [NotifyEvent.channels] 限定集合里的字符串。 */
    val id: String

    /**
     * 本渠道是否留投递记录。
     *
     * mail = true；push = false —— WS 广播是一对多的，没有"逐端投递结果"这种东西，
     * 而客户端那侧本来就有自己的 `notify_history`。
     *
     * 由分发器读：只有为 true 时它才会在整轮重试结束后调 [recordHistory]，
     * 所以实现方不必在自己那边再判一遍。
     */
    val recordsHistory: Boolean

    /**
     * 全局总闸（`master_enabled` + 免打扰）管不管本渠道。
     *
     * - `MailChannel` = **true**：邮件是设备主动发出去的终态投递，用户关了总闸就是不想收。
     * - `PushChannel` = **false**：推送只表示"这件事发生了"，**弹不弹是客户端的决定**。
     *   客户端自己有一整套闸门（`NotificationConfig` 总闸 + 分类开关 + 免打扰，判定在
     *   `NotificationCenter.notify`）。在 core 侧再判一遍等于把客户端开关复制一份到设备侧，
     *   还会顺手掐掉 web 的实时告警 —— web 根本不弹状态栏，"免打扰"对它不成立。
     *
     * **没有默认值是刻意的**：新渠道必须显式回答"我受不受总闸约束"。给个默认
     * （无论哪一边）都会让下一个渠道悄悄继承一个没人想过的语义。
     */
    val respectsMasterGate: Boolean

    /**
     * 本渠道的 [deliver] 返回 [DeliveryOutcome.Sent] 是否代表**真的送到了**。
     *
     * - `MailChannel` = **true**：SMTP `Transport.send` 拿到 250 应答才返回。
     * - `PushChannel` = **false**：WS 广播是 fire-and-forget，交给自己的 scope 就返回了，
     *   没有逐端结果（连了 0 个客户端算成功还是失败？）。
     *
     * 存在的理由：`TrafficAutoOffGuard` 要在"通报到位"之后才关移动数据。不区分可确认性的话，
     * 恒 `Sent` 的推送会让那个判定永远为真 —— SMTP 没配好也照样断网，用户只看到设备坏了。
     * 判定见 [DeliveryReport.anyConfirmedSent]。
     */
    val hasDeliveryConfirmation: Boolean

    /**
     * 本渠道的**路由规则与独立配额计数器**。`null` = 不设门槛（push 那一档）。
     *
     * 三条"设备主动发出去"的渠道（邮件 / Webhook / 本机短信）各返回一份自己的实现，
     * 形状完全一样、计数器互不共享 —— 理由与分工全写在 [ChannelRules] 上。
     * 分发器只**读**它（见 [NotificationDispatcher.emit] 的判定顺序），消耗留在渠道里。
     *
     * **有默认值是刻意的**，与 [respectsMasterGate] 那条"必须显式回答"恰好相反：
     * 推送根本没有"级别门槛"与"每日配额"这两个概念（弹不弹是客户端的决定，广播也不花钱），
     * 逼它写一个 `minLevel() = INFO` + `dailyLimit() = UNLIMITED` 的空实现只会多一处噪声 ——
     * 而那处噪声里的每一行都在假装回答一个对它不成立的问题。
     * 反过来 [respectsMasterGate] 对**任何**渠道都成立（要么受管要么不受管），
     * 给默认值就会让下一个渠道悄悄继承一个没人想过的语义。
     */
    val rules: ChannelRules? get() = null

    /** 配置是否齐全（邮件 = `SmsForwardController.isSendable()`）。不齐全 → `Skipped`。 */
    fun isConfigured(): Boolean


    /** 本渠道投不投这个场景（邮件读 `SmsForwardConfig.scenes`；push 恒 true）。 */
    fun accepts(scene: String): Boolean

    /**
     * 投一次。**不重试** —— 重试循环在分发器，由 [DeliveryOutcome.Failed.retryable] 驱动。
     *
     * 抛异常也算失败（分发器会兜住并转成 `Failed`），但推荐自己转成 `Failed` 并给出
     * `retryable`：异常分类的判据只有渠道自己懂。
     *
     * 返回 [DeliveryAttempt] 而不是裸 [DeliveryOutcome]：诊断字段（HTTP 状态码 / 配额结算）
     * 必须跟着**这一次**的结果一起交出来，不能让 `/test` 端点事后去回读渠道的可变快照
     * （两种必然出错的时序见 [DeliveryAttempt]）。没有额外可回的渠道给
     * `DeliveryAttempt(outcome)` 即可。
     */
    suspend fun deliver(event: NotifyEvent): DeliveryAttempt


    /**
     * 整轮（含全部重试与退避）的外壳。分发器把重试循环整段塞进来。
     *
     * 存在的唯一理由：SMTP 需要一把 WakeLock **覆盖整段投递**。每次尝试各申请一次
     * 等于在退避那几秒放开锁，而退避恰恰是最容易被 CPU 深睡吞掉的部分。
     * 默认直接执行 —— push 这类渠道不需要任何外壳，不该被迫写一个空壳。
     */
    suspend fun <T> withRound(block: suspend () -> T): T = block()


    /**
     * 整轮（含全部重试）结束后写一条投递记录。**只有 [recordsHistory] 为 true 时分发器才会调。**
     *
     * 为什么由分发器驱动而不是渠道自己在 [deliver] 里写：`attempts` 只有分发器知道
     * （重试循环在它手里），而"重试过没有"恰恰是排查时第一个要看的。一封邮件重试三次
     * 只该留**一条**记录，写在 deliver 里必然变成三条。
     *
     * **三态都会调**（DB v12 起）：[DeliveryOutcome.Skipped] 也留档，否则"我开着通知，
     * 这条为什么没收到"只能去翻 INFO 日志，而 release 构建不留 INFO。
     * 跳过时 `attempts = 0`（一次都没投），且分发器只在 [isConfigured] 为真时才调 ——
     * 实现方不必自己判噪声闸（见 `NotificationDispatcher.recordIfNeeded`）。
     *
     * 默认空实现是给 [recordsHistory] = false 的渠道用的 —— 它们永远不会被调到。
     */
    suspend fun recordHistory(event: NotifyEvent, outcome: DeliveryOutcome, attempts: Int) {}
}
