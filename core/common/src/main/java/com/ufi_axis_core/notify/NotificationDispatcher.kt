package com.ufi_axis_core.notify

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger


/**
 * 有界重试策略。
 *
 * @param maxAttempts 单个渠道最多尝试几次（首次 + 重试）。再多只是在一条已经不通的链路上多耗电。
 * @param backoffMs 第 1、2 次失败后的等待。前短后长：瞬时抖动 2s 就恢复，真中断多等一会儿也没用。
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val backoffMs: List<Long> = DEFAULT_BACKOFF_MS
) {
    companion object {
        /** 默认尝试次数（首次 + 2 次重试）。 */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** 默认退避序列，与 [DEFAULT_MAX_ATTEMPTS] 配套（3 次尝试 = 2 段退避）。 */
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(2_000L, 6_000L)

        val DEFAULT = RetryPolicy()
    }

    /** 整轮最坏退避总时长。给渠道算 WakeLock 预算用（见 `SmsForwardController.SMTP_WAKELOCK_TIMEOUT_MS`）。 */
    val totalBackoffMs: Long get() = backoffMs.take(maxAttempts - 1).sum()
}

/**
 * 通知分发器：**闸门、重试、投递记录三件事收敛在这里的唯一一处。**
 *
 * ## 为什么需要它
 *
 * 在此之前 12 个触发源各自持有一个**签名不同的单钩子**（`AlertEngine` 5 参数、
 * `DownloadManager` 2 参数、`TrafficAutoOffGuard` 返回 Boolean、短信/电池直接调
 * `forwardSms`），真正的"分发"是装配层 90 行手写 lambda。加第二个渠道等于改 6 个触发源
 * 加缝 6 条 lambda，是渠道数 × 触发源数 的组合爆炸。
 *
 * ## 闸门只有一个强制点，而且**按渠道**判
 *
 * 判定**谓词**全仓只有一份：`NotificationRoutes.notifyVerdict`（`master_enabled` 与
 * 「遵守免打扰时的静默窗口」两半各自映射成 [GateVerdict] 的一档），由装配层包成 [gate]
 * lambda 注入 —— 本模块因此不必反向依赖 `:core:api`，**也不在这里重算任何一半**。
 *
 * **强制点只有 [emit] 这一处**（`SmsForwardController.isMailGateOpen()` 是只读查询，
 * 只给 `/api/sms-forward/test` 的响应用，不拦任何投递）。
 *
 * 闸门判定在**渠道循环内部**，不短路整条事件：受总闸约束的渠道拿
 * `Skipped(`[SkipReason.MASTER_OFF]`)` 或 `Skipped(`[SkipReason.QUIET_HOURS]`)`，
 * 其余渠道照投。为什么必须这样：`master_enabled`
 * 默认 false，一旦在遍历前统一判闸，用户没显式打开总开关时连**推送**都发不出去 ——
 * 告警 / 新短信 / 验证码 / 下载 / 隧道一条 WS 都不发，app 的 `:ufi_notify` 与 web 的
 * 实时告警全停。哪个渠道受闸门约束由渠道自己声明（[NotifyChannel.respectsMasterGate]）。
 *
 * ## CRITICAL 兜底
 *
 * `event.level == CRITICAL` 且 [criticalOverride] 开启时，这条事件穿透**免打扰 / 场景未勾 /
 * 级别不够**三道，永远穿不透**总开关 / 没配全 / 配额用尽**三道。逐条理由见 [deliverTo]。
 *
 * ## 注册表
 *
 * 就是 [register] 累加的一个 [CopyOnWriteArrayList]，不搞 SPI / 反射：渠道数量是个位数、
 * 装配点唯一（`ComponentFactory`），一个显式列表比任何自动发现都好读、好调试。
 */
class NotificationDispatcher(
    /**
     * 总闸 + 免打扰的**三态**判决。装配层注入，实现仍是 `NotificationRoutes.notifyVerdict`。
     *
     * **入参是渠道 id**：总闸（`master_enabled`）对所有渠道同一份，但"免打扰要不要管我"
     * 是**每渠道自己的用户配置**（邮件 `mail_respect_dnd` 默认 false，Webhook
     * `respectDnd` 默认 true）。给一个不带参数的谓词，就只能在渠道里各判一次免打扰 ——
     * 那正是这次重构要消灭的第二份闸门判定。
     *
     * **为什么返回三态而不是 Boolean**（2026-09-10）：`false` 分不出"总开关关着"与
     * "在免打扰时段"，而 CRITICAL 兜底只允许穿透后者。装配层把已经算好的两半分别映射过来，
     * 本类**不重算免打扰** —— 静默窗口与每渠道的 `respectDnd` 都不在这个模块里。
     *
     * **null = 未装配 = 放行**：降级装配下通知照发，不能因为装配层漏接就静默丢通知。
     *
     * 它只作用于 [NotifyChannel.respectsMasterGate] = true 的渠道，不是整条事件的开关。
     */
    private val gate: ((String) -> GateVerdict)? = null,
    /**
     * CRITICAL 兜底总开关的取值口（真源是 `NotificationConfig.critical_override_enabled`，
     * **默认 true**）。
     *
     * **null = 未装配 = 视为开启**，理由与 [gate] 的 null 语义一致：不能因为装配层漏接
     * 就让关键通知（断网 / 套餐用尽 / 自动关网）被静默。
     *
     * 用 lambda 每次现读而不是构造时取一次：存下来等于"改了设置要重启 core 才生效"。
     */
    private val criticalOverride: (() -> Boolean)? = null,
    private val retry: RetryPolicy = RetryPolicy.DEFAULT,
    /**
     * 单个渠道一整轮的预算（默认 [CHANNEL_ROUND_BUDGET_MS]）。
     *
     * 做成参数只为**可测**：验"预算到点就不再重试、且仍然带回上一次的诊断"这条不变量时，
     * 单测不可能真等 45 秒。生产装配一律用默认值（`ComponentFactory` 不传它）。
     */
    private val roundBudgetMs: Long = CHANNEL_ROUND_BUDGET_MS
) {


    private val tag = "NotifyDispatcher"

    private val channels = CopyOnWriteArrayList<NotifyChannel>()

    /**
     * 每渠道一把投递互斥锁（渠道 id → [Mutex]）。**配额的 check-then-act 靠它变成原子的。**
     *
     * ## 为什么必须有锁
     *
     * [blockingReason] 里的 `rules.hasQuota()` 与渠道内部的 `store.consume()` 是**两次独立
     * 加锁**，中间隔着一次真实发信。同一个 notifier 供 5 个触发源 + 2 个 `/test` 端点共用，
     * 配额只剩 1 条时两条 CRITICAL 事件重叠，两边都会读到"还有余量"→ 两边都发 →
     * 实际发出 2 条。在本机短信这条按条计费的渠道上，这就是**多花的一条钱**，
     * 而用户设的"每日上限"这道刹车根本没起作用。
     *
     * ## 为什么用锁而不是"原子预扣 + 失败回退"
     *
     * 预扣要求分发器知道"这一次到底算不算发出去了"，而那个判据只有渠道懂 ——
     * 本机短信的口径是"排除不了已经发出去"（`LocalSmsDelivery.countsTowardQuota`：
     * `REJECTED` 不计、`NO_RESPONSE` 计），邮件与 Webhook 的口径是"只有确定结论才计"。
     * 把它搬进分发器就等于把三套结论映射复制到这里，而本类连 `GoformSmsClient` 都不该认识
     * （既有分工：**判定同构在分发器，记账各自在渠道**，见 [ChannelRules]）。
     * 加一把锁不改变这条分工，只是让"读配额 → 投递 → 扣配额"这一段不被别的轮次插进来。
     *
     * ## 为什么按渠道分而不是一把全局锁
     *
     * 配额本来就是**每渠道独立**的（三条渠道各一对 `quota_day` / `quota_count`），
     * 一把全局锁会让"邮件正在做 SMTP 握手"挡住"本机短信要发一条断网通知" ——
     * 而那两条恰恰是需要各走各的链路互补关系。锁的持有时间由
     * [CHANNEL_ROUND_BUDGET_MS] 兜住上限，不会无界等待。
     */
    private val channelLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 已经停机了吗。停机后 [emit] 直接返回空结果，不再进入任何渠道。
     *
     * `@Volatile` 足够：单写（[shutdown]）多读，只需要"写了之后别人立刻看得见"。
     */
    @Volatile
    private var stopped = false

    /** 在途轮次计数（[shutdown] 靠它等在飞的投递收尾，理由见那个函数）。 */
    private val activeRounds = AtomicInteger(0)


    /** 注册一个渠道。同 id 重复注册会替换旧的（装配跑第二遍时不至于投两份）。 */
    fun register(channel: NotifyChannel) {
        channels.removeAll { it.id == channel.id }
        channels.add(channel)
        AppLogger.i(tag, "渠道已注册: ${channel.id}（留投递记录=${channel.recordsHistory}，受总闸约束=${channel.respectsMasterGate}）")
    }

    /**
     * 投一条事件，返回**每个渠道**的结果（渠道 id → 结果）加渠道元信息。
     *
     * 返回值不是装饰：`TrafficAutoOffGuard` 要靠 [DeliveryReport.anyConfirmedSent] 决定
     * 要不要关移动数据 —— 语义必须保持"通知没发出去就不关网"，否则用户看到的只是"突然断网"。
     *
     * 语义：
     * - 闸门**按渠道**判，不短路整条事件：受总闸约束的渠道 `Skipped(MASTER_OFF)` /
     *   `Skipped(QUIET_HOURS)`，其余照投（理由见类头注释）。`Skipped` **也写投递记录**
     *   （DB v12 起），但只在渠道已配置时写 —— 噪声闸的理由见 [recordIfNeeded]；
     * - 渠道筛选是"[NotifyEvent.channels] 白名单 → [NotifyEvent.exclude] 黑名单"两道，
     *   两道都过不了的渠道**不出现在结果里**（见 [exclusionsOf]）；
     * - 渠道之间**顺序串行**，不并发：SMTP 要持 WakeLock，两个渠道并发只会抢 CPU 唤醒时间，
     *   而通知不是延迟敏感路径。单渠道那一轮有显式预算（[CHANNEL_ROUND_BUDGET_MS]），
     *   所以串行不会让一个不响应的目标把后面的渠道拖到几分钟之后；
     * - 任一渠道抛异常不影响其它渠道（逐个兜住，异常转 `Failed`）；
     * - [CancellationException] 原样重抛（本仓纪律：取消不是投递失败）；
     * - [shutdown] 之后一律返回**空结果**：组件都停了，不能再往里投。
     */
    suspend fun emit(event: NotifyEvent): DeliveryReport {
        // 停机后提前返回：下面 owner 就要去关渠道自己的资源（`WebhookChannel.close()` 的
        // CIO 连接池、`SmsForwardController.historyScope`），这时放进来的投递必然以异常收场。
        if (stopped) return DeliveryReport(emptyMap(), emptySet())
        val results = LinkedHashMap<String, DeliveryOutcome>(channels.size)
        val confirmable = LinkedHashSet<String>()
        // 诊断补充：只有真的走到 deliver() 的渠道才会有条目（理由见 DeliveryAttempt）。
        val diagnostics = LinkedHashMap<String, Any?>(channels.size)
        // 黑名单在循环外解析一次：同时给了白名单与黑名单时要打 WARN，
        // 放进循环就会按渠道数量重复打同一行。
        val excluded = exclusionsOf(event)
        // 兜底开关也在循环外读一次：它是全局设置，按渠道数量重复读只是多几次 JSON 解析。
        val override = event.level == NotifyLevel.CRITICAL && criticalOverride?.invoke() != false
        activeRounds.incrementAndGet()
        try {
            for (channel in channels) {
                // 白名单在最前面判：不在集合里的渠道连 isConfigured() 都不该被问到
                // （告警聚合更新限定 push，此时问邮件"配置全不全"毫无意义还会读一次 prefs）。
                val wanted = event.channels?.contains(channel.id) != false
                if (!wanted) continue
                // 黑名单：**不产生 Skipped、不打日志**。被排除的渠道不是"没发出去"，
                // 而是"这条投递路径不负责它"（新短信与电池的推送另有触发源）——
                // 记成 Skipped 会让排查时以为通知漏了，还会污染 anyConfirmedSent 的判据。
                if (channel.id in excluded) continue
                if (channel.hasDeliveryConfirmation) confirmable.add(channel.id)
                val attempt = deliverTo(channel, event, verdictFor(channel, event), override)
                results[channel.id] = attempt.outcome
                attempt.diagnostics?.let { diagnostics[channel.id] = it }
            }
        } finally {
            activeRounds.decrementAndGet()
        }
        return DeliveryReport(results, confirmable, diagnostics)
    }


    /**
     * 本次要排除的渠道 id。
     *
     * [NotifyEvent.channels]（白名单，"只投这些"）与 [NotifyEvent.exclude]（黑名单，
     * "除了这些都投"）**不应同时给值**：那说明触发源对自己想投给谁没想清楚。
     * 这里的处理是**以白名单为准 + 打一条 WARN**，两条都是刻意的：
     * - 以白名单为准 —— 它的语义更窄，多投一条通知（尤其是花钱的短信渠道）比少投更难收场；
     * - 打 WARN 而不是静默 —— 静默采纳一个等于把这个歧义埋起来，
     *   而 release 构建只留 WARN/ERROR，落在 INFO 里等于事后查不到。
     */
    private fun exclusionsOf(event: NotifyEvent): Set<String> {
        val exclude = event.exclude
        if (exclude.isNullOrEmpty()) return emptySet()
        if (event.channels != null) {
            AppLogger.w(
                tag,
                "事件同时给了 channels 与 exclude（scene=${event.scene}，" +
                    "channels=${event.channels}，exclude=$exclude），以 channels 为准并忽略 exclude"
            )
            return emptySet()
        }
        return exclude
    }


    /**
     * 闸门对**这个渠道**的判决。
     *
     * 三条短路，缺一不可：
     * - `event.manual` —— 手动触发（`POST /api/…/test`）跳过闸门：用户刚按下
     *   "发送测试"，静默什么都不发比发出去更难排查；端点响应会另外回一个
     *   `auto_notify_enabled` 告知闸门状态，不做无声跳过；
     * - `!channel.respectsMasterGate` —— 推送不受约束（弹不弹是客户端的决定）；
     * - `gate == null` —— 未装配算放行。
     *
     * 渠道 id 传下去是因为"免打扰管不管我"是每渠道各自的用户配置（见 [gate]）。
     */
    private fun verdictFor(channel: NotifyChannel, event: NotifyEvent): GateVerdict {
        if (event.manual || !channel.respectsMasterGate) return GateVerdict.ALLOW
        return gate?.invoke(channel.id) ?: GateVerdict.ALLOW
    }

    /**
     * 造一条 [DeliveryOutcome.Skipped] 并打日志。
     *
     * 日志与留档是**两件事，都要**：留档（DB v12 起）只发生在渠道已配置时，而
     * "SMTP 一项没填，所以这条告警没发出去"恰恰是最需要留痕的那一种 ——
     * 它只在这行日志里。反过来，日志打**枚举名**、留档写 [SkipReason.label] 的中文，
     * 也是刻意的分工（本仓纪律：文案给人看，日志给排查看）。
     *
     * [SkipReason.QUOTA_EXCEEDED] 打 **WARN**：其余几档都是"用户不想收"，而配额用尽是
     * **想通知却没能力了** —— 那是真的漏了一条通知，混在 INFO 里（release 只保留
     * WARN/ERROR）等于事后完全查不到。
     */
    private fun skipped(channel: NotifyChannel, event: NotifyEvent, reason: SkipReason): DeliveryOutcome {
        val line = "渠道 ${channel.id} 跳过投递（scene=${event.scene}，原因=$reason）"
        if (reason == SkipReason.QUOTA_EXCEEDED) AppLogger.w(tag, line) else AppLogger.i(tag, line)
        return DeliveryOutcome.Skipped(reason)
    }


    /**
     * 单个渠道的整轮投递：判定短路 → 有界重试 → 写一条投递记录。
     *
     * ## 判定顺序（**全仓唯一的强制点**）
     *
     * ```
     * gate == BLOCKED_BY_MASTER            → Skipped(MASTER_OFF)
     * gate == BLOCKED_BY_DND               → Skipped(QUIET_HOURS)      ← CRITICAL 兜底可穿透
     * !isConfigured()                      → Skipped(NOT_CONFIGURED)
     * !manual && rules 级别不够             → Skipped(LEVEL_TOO_LOW)    ← CRITICAL 兜底可穿透
     * !manual && !accepts(scene)           → Skipped(SCENE_OFF)        ← CRITICAL 兜底可穿透
     * rules 配额已用尽                      → Skipped(QUOTA_EXCEEDED)
     *                                      → deliver()，失败按 retryable 重试
     * ```
     *
     * 闸门排在最前面，且**闸门判定本身仍在 [emit]**（[gate] 是入参）—— 那道谓词属于
     * "跨渠道的全局状态"，放进这里会让本函数同时认识注册表与全局配置。
     * 级别与配额两道读的是 [NotifyChannel.rules]（`null` = 这个渠道没有这两道闸，push 那一档）。
     *
     * ## 两条 `manual` 口径
     *
     * - **级别与场景 `manual` 放行**：用户刚按下"发送测试"，因为没勾场景 / 级别不够而
     *   静默什么都不发，比发出去更难排查（沿用既有 `manual` 语义）。配置检查照旧不跳过 ——
     *   SMTP 没填完的"测试"发不出去是事实；
     * - **配额 `manual` 也不放行**：配额是"没有能力了"（花钱的渠道已经烧到上限），
     *   测试按钮不该越过它 —— 越过就等于按一次测试多花一条钱。
     *
     * ## CRITICAL 兜底（`event.level == CRITICAL` 且 [criticalOverride] 开启）
     *
     * **穿透**以下三道：
     * 1. [SkipReason.QUIET_HOURS] —— 半夜也要叫醒用户，这就是"关键"的定义；
     * 2. [SkipReason.SCENE_OFF] —— 用户没勾这个场景，但断网 / 套餐用尽这类事他一定想知道；
     * 3. [SkipReason.LEVEL_TOO_LOW] —— 门槛是用来挡噪声的，而 CRITICAL 不是噪声。
     *
     * **永远不穿透**以下三道：
     * 1. [SkipReason.MASTER_OFF] —— 那是用户说"一条都别发"，兜底也不能违反；
     * 2. [SkipReason.NOT_CONFIGURED] —— 不是不想发，是发不出去；
     * 3. [SkipReason.QUOTA_EXCEEDED] —— 想通知但没能力了，穿透它就是无上限烧钱。
     *
     * 穿透时**必须留痕**：打一行 WARN（release 只保留 WARN/ERROR），并且这一次投递在
     * 成功 / 失败时照常写投递记录。**不新增第四种 [DeliveryOutcome]** ——
     * "被兜底放行的成功"和普通成功在结果上没有区别，多一态会让每个消费方都得跟着解构。
     *
     * ## 全仓唯一的重试循环
     *
     * 阶段 0 曾把它放在 `MailDelivery.send`，阶段 1 上移到这里 —— 两个循环并存的话，
     * 一次失败会被放大成 3 × 3 次尝试，投递记录也会各写一份。
     * 留在邮件侧的只有**异常分类**（`MailDelivery.isRetryable`）与失败文案：
     * 那些判据只有邮件懂（授权码错 vs 握手超时），分发器不该也不能知道。
     *
     * ## 全渠道共用的一把「整轮预算」
     *
     * 每一轮有一个截止时刻（[roundBudgetMs]）。判据在**尝试之间**：退避完还有没有时间再开
     * 一次？到点就带着**刚才那次的真实结果**收工（不合成"预算用尽"的假失败）。
     * 取值推导与"为什么不拦腰砍"写在 [CHANNEL_ROUND_BUDGET_MS] 上。
     *

     * ## 配额的 check-then-act 在一把锁里
     *
     * 本函数整体由 [channelLocks] 里该渠道那把锁保护：`hasQuota()` 与渠道内部的
     * `consume()` 之间隔着一次真实发信，不加锁时两条并发的 CRITICAL 会在"只剩 1 条"时
     * 双双放行（理由与备选方案见 [channelLocks]）。
     *
     * @param override 这条事件是否享受 CRITICAL 兜底（由 [emit] 算好，全渠道共用一份）。
     */
    private suspend fun deliverTo(
        channel: NotifyChannel,
        event: NotifyEvent,
        gate: GateVerdict,
        override: Boolean
    ): DeliveryAttempt = channelLocks.computeIfAbsent(channel.id) { Mutex() }.withLock {
        // 先按"没有兜底"算一遍：这既是非 CRITICAL 事件的最终结论，也是判断
        // "兜底到底有没有起作用"的依据 —— 只有真的穿透了才打那行 WARN，
        // 否则每条 CRITICAL 都会留一行噪声。
        val strict = blockingReason(channel, event, gate, override = false)
        val penetrable = strict != null && strict in CRITICAL_OVERRIDABLE
        // 只有真要穿透时才重算一遍（多几次 prefs 读只发生在这一档）：穿透第一道之后
        // 后面几道仍然要按顺序判，不能直接放行 —— 免打扰穿过去了，SMTP 没配全照样发不出去。
        val skipReason = if (override && penetrable) {
            blockingReason(channel, event, gate, override = true)
        } else {
            strict
        }
        if (override && penetrable && skipReason == null) {
            AppLogger.w(
                tag,
                "CRITICAL 兜底放行：渠道 ${channel.id}（scene=${event.scene}，" +
                    "本应跳过=$strict，因事件级别为 CRITICAL 且兜底开启而照常投递）"
            )
        }
        if (skipReason != null) {
            val outcome = skipped(channel, event, skipReason)
            // attempts = 0：一次都没投。历史页据此把"跳过"与"重试 3 次后失败"分开显示。
            recordIfNeeded(channel, event, outcome, attempts = 0)
            // 诊断给 null：这一轮**一次都没投**，任何"状态码 / 配额结算"都不属于本次
            // （回读渠道上一次的快照正是 DeliveryAttempt 要根治的那个 bug）。
            return@withLock DeliveryAttempt(outcome)
        }


        var attempts = 0
        // 整轮预算的截止时刻。判在**尝试之间**，绝不打断在飞的那一次（理由见
        // CHANNEL_ROUND_BUDGET_MS 的「为什么不用 withTimeout 拦腰砍」一节）。
        val deadline = System.currentTimeMillis() + roundBudgetMs
        // withRound 包住整段重试：SMTP 的 WakeLock 必须覆盖退避（见 NotifyChannel.withRound）。
        val attempt = channel.withRound {
            var last: DeliveryAttempt
            while (true) {
                attempts++
                last = try {
                    channel.deliver(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 渠道没自己转成 Failed 就抛了：拿不准是否瞬时错，按不可重试处理 ——
                    // 宁可少发一次，也不要把一个永久错误放大成三次投递 + 三份日志。
                    DeliveryAttempt(
                        DeliveryOutcome.Failed(
                            "${e.javaClass.simpleName}: ${e.message}", retryable = false
                        )
                    )
                }
                val failure = last.outcome as? DeliveryOutcome.Failed
                if (failure == null || !failure.retryable || attempts >= retry.maxAttempts) break
                val backoff = retry.backoffMs.getOrElse(attempts - 1) { retry.backoffMs.lastOrNull() ?: 0L }
                // 预算判据："退避完还有没有时间再开一次"。到点就**保留刚才那次的真实结果**收工，
                // 不合成一个"预算用尽"的假失败 —— 用户要看的是 HTTP 500 / 超时这种真原因。
                if (System.currentTimeMillis() + backoff >= deadline) {
                    AppLogger.w(
                        tag,
                        "渠道 ${channel.id} 整轮预算用尽，不再重试（scene=${event.scene}，" +
                            "预算=${roundBudgetMs}ms，已尝试 $attempts 次），后续渠道照常投递"
                    )
                    break
                }
                delay(backoff)
            }
            last
        }

        recordIfNeeded(channel, event, attempt.outcome, attempts)
        attempt
    }


    /**

     * 按 [deliverTo] 那张表逐道判，返回**第一道**拦下这次投递的原因；全过则 null。
     *
     * 写成早退的 `if` 链而不是 `when`：每一道的入参不同（有的读 [NotifyEvent]、有的读
     * 渠道配置、有的读配额计数器），而**顺序本身就是语义** —— `when` 的分支可以被随手
     * 重排而不报错，早退链重排时读起来就是错的（"配额都判完了还问配置全不全"）。
     *
     * @param override 为 true 时把三道可穿透的闸当作"没拦" —— 穿透表见 [CRITICAL_OVERRIDABLE]。
     */
    private fun blockingReason(
        channel: NotifyChannel,
        event: NotifyEvent,
        gate: GateVerdict,
        override: Boolean
    ): SkipReason? {
        if (gate == GateVerdict.BLOCKED_BY_MASTER) return SkipReason.MASTER_OFF
        if (gate == GateVerdict.BLOCKED_BY_DND && !override) return SkipReason.QUIET_HOURS
        if (!channel.isConfigured()) return SkipReason.NOT_CONFIGURED
        val rules = channel.rules
        val userChoiceApplies = !event.manual && !override
        // 级别在场景之前：两者的结论相同（都算"用户主动静默"），但记错原因会把排查
        // 带向错误方向 —— 场景明明勾着却记一行"场景未勾选"。
        if (userChoiceApplies && rules != null && event.level.ordinal < rules.minLevel().ordinal) {
            return SkipReason.LEVEL_TOO_LOW
        }
        if (userChoiceApplies && !channel.accepts(event.scene)) return SkipReason.SCENE_OFF
        // 配额既不被 manual 放行、也不被兜底穿透：这一档是"没有能力了"，不是"不想收"。
        if (rules != null && !rules.hasQuota()) return SkipReason.QUOTA_EXCEEDED
        return null
    }

    /**
     * 写投递记录，带两道过滤。
     *
     * 1. `!recordsHistory` —— 推送没有"逐端投递结果"这种东西（客户端那侧有自己的
     *    `notify_history`）；
     * 2. **`Skipped` 且渠道没配全时不写**（DB v12 加"跳过也留档"时补的噪声闸）：
     *    SMTP 一项没填的用户，每来一条事件都会记一行"渠道配置不完整" ——
     *    默认 500 条的环形缓冲几十条就被冲满，把真正的失败记录挤出去，
     *    而那正是这张表唯一必须留住的东西。判据用 `isConfigured()` 而不是
     *    `reason != NOT_CONFIGURED`：闸门那两档排在配置检查之前，没配全的渠道在免打扰时段
     *    拿到的是 `Skipped(QUIET_HOURS)`，只看原因照样会被刷满。
     *
     * 异常只记 WARN：留档是自查手段，写不进去不该影响"通知已经发出去了"这件事。
     */
    private suspend fun recordIfNeeded(
        channel: NotifyChannel,
        event: NotifyEvent,
        outcome: DeliveryOutcome,
        attempts: Int
    ) {
        if (!channel.recordsHistory) return
        if (outcome is DeliveryOutcome.Skipped && !channel.isConfigured()) return
        try {
            channel.recordHistory(event, outcome, attempts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "渠道 ${channel.id} 投递记录写入失败（投递本身不受影响）：${e.message}")
        }
    }


    /**
     * 停机收尾：**先关门，再等在途轮次收尾**，最后清空注册表。
     *
     * ## 为什么必须等
     *
     * `BackendService.stopAllComponents` 紧跟在本函数之后调 `webhookChannel.close()`
     * （关 CIO 连接池）与 `smsForwardController.shutdown()`（cancel 历史写入 scope）。
     * 原实现只 `channels.clear()` 就返回，于是一条**正在飞**的 Webhook 投递会在自己用的
     * 连接池被关掉之后以异常收场，并按"不可重试"直接丢弃 —— 表现是"停服的那一刻恰好触发的
     * 那条通知永远没发出去，投递记录里也只有一行连接异常"。
     *
     * ## 两步
     *
     * 1. [stopped] 置位 → 之后 [emit] 一律空操作（新的轮次进不来）；
     * 2. 等 [activeRounds] 归零，最多等 [SHUTDOWN_DRAIN_MS]。等不动就打一条 **WARN** 放行 ——
     *    停服不能挂着不动，而单轮预算是 [CHANNEL_ROUND_BUDGET_MS]，真等满那个数会让
     *    "点了停止服务"僵住半分钟以上；那种取舍必须留痕而不是静默。
     *
     * 轮询而不是挂一个信号量：这里只在停机路径上跑一次，[SHUTDOWN_POLL_MS] 的粒度足够，
     * 而为它引入一个需要在每次 emit 里维护的 `CompletableDeferred` 反倒多一处状态要对齐。
     *
     * 仍然**不去 cancel 渠道** —— 渠道自己的 scope（`WebSocketPushService.scope`、
     * `SmsForwardController.historyScope`）与连接池由各自的 owner 在停服路径上收。
     */
    suspend fun shutdown() {
        stopped = true
        val deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MS
        while (activeRounds.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(SHUTDOWN_POLL_MS)
        }
        val leftover = activeRounds.get()
        if (leftover > 0) {
            AppLogger.w(
                tag,
                "分发器停止时仍有 $leftover 轮投递在途（等待 ${SHUTDOWN_DRAIN_MS}ms 后放行）：" +
                    "渠道资源随后被 owner 关闭，这几轮会以取消/连接异常收场"
            )
        }
        channels.clear()
        AppLogger.i(tag, "分发器已停止（注册表已清空）")
    }


    private companion object {
        /**
         * **单个渠道**一整轮的预算：到点之后**不再开始新的一次尝试**。
         *
         * ## 为什么必须有
         *
         * 渠道之间是串行的，而注册顺序是 push → mail → webhook → **local_sms**
         * （`ComponentFactory`）。Webhook 的 `timeout_ms` 由用户配，上限
         * `WebhookConfig.MAX_TIMEOUT_MS` = 60s —— 一个不响应的目标（域名解析得通、
         * 端口不回包）会把这一轮拖到 3 × 60s + 8s ≈ 3 分钟，而排在它后面的正是
         * **断网时唯一还能通的那条链路**（本机短信走信令网）。于是"数据断了"这条
         * CRITICAL 通知要等三分钟才轮到发短信，用户在这三分钟里看到的只是"设备不见了"。
         *
         * ## 45s 的推导
         *
         * 默认配置下单渠道最坏 = 3 × `WebhookConfig.DEFAULT_TIMEOUT_MS`(10s) +
         * `RetryPolicy.DEFAULT_BACKOFF_MS`(2s + 6s) = **38s**。预算取 45s：
         * - 比 38s 大 → **默认配置下一次重试都不会被砍掉**（砍掉正常重试就是丢通知）；
         * - 留的 7s 余量给 SMTP 那条的建连/握手抖动（`withRound` 还要申请 WakeLock）；
         * - 把"用户把 timeout_ms 调到 60s"那种配置从 ~188s 压到"一次尝试就收工"。
         *
         * 预算是**每渠道各一份**而不是整条 emit 一份：整条一份的话，前面的渠道烧完预算，
         * 后面的渠道会一次投递机会都拿不到 —— 那与本预算要保护的东西恰好相反。
         *
         * ## 为什么不用 `withTimeout` 拦腰砍在飞的那一次
         *
         * 曾经是 `withTimeoutOrNull(45s)` 包住整段重试。那样有两个后果，都比"多等一会儿"严重：
         * 1. **可能真花了钱却没记账**：砍在 `LocalSmsChannel.deliver` 中间时，
         *    goform 那条 SEND_SMS 可能已经落到固件里，而 `store.consume()` 永远走不到 ——
         *    短信发出去了、配额没扣，用户设的每日上限从此偏一条；
         * 2. **诊断字段整组丢失**：被取消的 `deliver` 不返回任何东西，于是"这一轮到底有没有
         *    发起过投递"在响应里看不出来（`attempted_at` 缺失被客户端当成"没投过"，
         *    契约见 `WebhookRoutes` / `LocalSmsRoutes` 的 `/test`）。
         *
         * 所以判据改成**尝试之间**的截止时间。代价是最坏耗时 = 截止时刻 + 一次尝试的时长，
         * 而"一次尝试"由渠道自己的超时兜住（webhook = `timeout_ms` ≤ 60s；
         * mail = `SMTP_TIMEOUT_MS` 连接/读各 10s；local_sms = goform 的 10s 请求超时 +
         * 3 × 1.2s 回读）。要消灭的"× 3 次重试"那部分已经没了。
         */
        const val CHANNEL_ROUND_BUDGET_MS = 45_000L


        /**
         * 停机时等在途轮次收尾的上限。
         *
         * 不取 [CHANNEL_ROUND_BUDGET_MS]：那会让"点了停止服务"最坏僵住 45s。
         * 5s 是"一次 HTTP 投递或一次 SMTP 应答绝大多数能收尾"的量级；
         * 等不到就打 WARN 放行（见 [shutdown]）。
         */
        const val SHUTDOWN_DRAIN_MS = 5_000L

        /** 停机等待的轮询粒度。只在停机路径上跑，50ms 足够细也不忙等。 */
        const val SHUTDOWN_POLL_MS = 50L

        /**


         * CRITICAL 兜底**能穿透**的三道闸。整张表就这一处，理由逐条写在 [deliverTo] 上。
         *
         * 集中成集合而不是散在判定里：这三档与"永远不穿透"的另外三档必须**同进同出**，
         * 而漏改一处的表现分别是"关键通知在半夜被静默"（少放一档）和
         * "总开关关着还在发 / 配额烧穿"（多放一档）—— 两种都是用户看得见的承诺被打破。
         */
        val CRITICAL_OVERRIDABLE = setOf(
            SkipReason.QUIET_HOURS,
            SkipReason.SCENE_OFF,
            SkipReason.LEVEL_TOO_LOW
        )
    }
}

