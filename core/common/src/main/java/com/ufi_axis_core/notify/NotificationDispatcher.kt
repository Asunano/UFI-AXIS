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
 * @param maxAttempts 单个渠道的最大尝试次数（首次投递 + 重试）。超出该次数的尝试不会提升成功率，
 *   只会增加功耗。
 * @param backoffMs 第 1、2 次失败后的等待时长。前短后长：瞬时抖动可在 2s 内恢复，
 *   链路真正中断时延长等待没有收益。
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
 * 通知分发器：闸门判定、重试与投递记录的唯一汇聚点。
 *
 * ## 职责
 *
 * 触发源只描述事件（[NotifyEvent]），渠道选择、闸门判定、重试与投递记录统一由本类完成。
 * 渠道通过 [register] 显式注册，新增渠道不需要修改任何触发源。
 *
 * ## 闸门只有一个强制点，且按渠道判定
 *
 * 判定谓词全仓只有一份：`NotificationRoutes.notifyVerdict`（`master_enabled` 与
 * 「遵守免打扰时的静默窗口」两半各自映射成 [GateVerdict] 的一档），由装配层包成 [gate]
 * lambda 注入 —— 本模块因此不反向依赖 `:core:api`，也不在此重算任何一半。
 *
 * 强制点只有 [emit] 一处（`SmsForwardController.isMailGateOpen()` 是只读查询，
 * 仅用于 `/api/sms-forward/test` 的响应，不拦截任何投递）。
 *
 * 闸门判定位于渠道循环内部，不短路整条事件：受总闸约束的渠道得到
 * `Skipped(`[SkipReason.MASTER_OFF]`)` 或 `Skipped(`[SkipReason.QUIET_HOURS]`)`，其余渠道照常投递。
 * 该行为是必需的：`master_enabled` 默认 false，若在遍历前统一判闸，用户未显式打开总开关时
 * 连推送都无法发出 —— 告警、新短信、验证码、下载与隧道事件均不再经 WebSocket 下发，
 * app 的 `:ufi_notify` 与 web 的实时告警会全部停止。某渠道是否受闸门约束由渠道自身声明
 * （[NotifyChannel.respectsMasterGate]）。
 *
 * ## CRITICAL 兜底
 *
 * `event.level == CRITICAL` 且 [criticalOverride] 开启时，该事件可穿透免打扰、场景未勾选与
 * 级别不足三道闸，但不可穿透总开关、渠道未配全与配额用尽三道。逐条理由见 [deliverTo]。
 *
 * ## 注册表
 *
 * 注册表是 [register] 累加的 [CopyOnWriteArrayList]，不使用 SPI 或反射：渠道数量为个位数、
 * 装配点唯一（`ComponentFactory`），显式列表比自动发现更易读、更易调试。
 */
class NotificationDispatcher(
    /**
     * 总闸与免打扰的三态判决。由装配层注入，实现为 `NotificationRoutes.notifyVerdict`。
     *
     * 入参是渠道 id：总闸（`master_enabled`）对所有渠道相同，但「免打扰是否作用于该渠道」
     * 属于每渠道各自的用户配置（邮件 `mail_respect_dnd` 默认 false，Webhook `respectDnd`
     * 默认 true）。若谓词不接受渠道参数，免打扰就只能在各渠道内分别判定，
     * 从而产生第二份闸门判定。
     *
     * 返回三态而非 Boolean：Boolean 无法区分「总开关关闭」与「处于免打扰时段」，
     * 而 CRITICAL 兜底只允许穿透后者。装配层把已算好的两半分别映射过来，本类不重算免打扰 ——
     * 静默窗口与每渠道的 `respectDnd` 均不在本模块内。
     *
     * null = 未装配 = 放行：降级装配下通知照常发送，装配层缺失不得导致通知被静默丢弃。
     *
     * 它只作用于 [NotifyChannel.respectsMasterGate] = true 的渠道，不是整条事件的开关。
     */
    private val gate: ((String) -> GateVerdict)? = null,
    /**
     * CRITICAL 兜底总开关的取值口（真源是 `NotificationConfig.critical_override_enabled`，
     * 默认 true）。
     *
     * null = 未装配 = 视为开启，理由与 [gate] 的 null 语义一致：装配层缺失不得导致
     * 关键通知（断网、套餐用尽、自动关网）被静默。
     *
     * 以 lambda 形式每次现读，而不是构造时取一次：缓存取值会使配置变更需要重启 core 才生效。
     */
    private val criticalOverride: (() -> Boolean)? = null,
    private val retry: RetryPolicy = RetryPolicy.DEFAULT,
    /**
     * 单个渠道一整轮的预算（默认 [CHANNEL_ROUND_BUDGET_MS]）。
     *
     * 该参数存在仅为可测试性：验证「预算到点后不再重试，且仍带回上一次的诊断」这条不变量时，
     * 单测无法真实等待 45 秒。生产装配一律使用默认值（`ComponentFactory` 不传该参数）。
     */
    private val roundBudgetMs: Long = CHANNEL_ROUND_BUDGET_MS
) {


    private val tag = "NotifyDispatcher"

    private val channels = CopyOnWriteArrayList<NotifyChannel>()

    /**
     * 每渠道一把投递互斥锁（渠道 id → [Mutex]）。配额的 check-then-act 依赖它保证原子性。
     *
     * ## 必要性
     *
     * [blockingReason] 中的 `rules.hasQuota()` 与渠道内部的 `store.consume()` 是两次独立加锁，
     * 中间隔着一次真实发信。同一个 notifier 供多个触发源与 `/test` 端点共用，配额只剩 1 条时
     * 两条 CRITICAL 事件重叠，双方都会读到「还有余量」并各发一条，实际发出 2 条。
     * 对按条计费的本机短信渠道，这是多计一条费用，且用户配置的每日上限不再生效。
     *
     * ## 使用锁而非「原子预扣 + 失败回退」
     *
     * 预扣要求分发器判断「本次是否算作已发送」，而该判据只有渠道掌握 ——
     * 本机短信的口径是「无法排除已经发出」（`LocalSmsDelivery.countsTowardQuota`：
     * `REJECTED` 不计、`NO_RESPONSE` 计），邮件与 Webhook 的口径是「仅有确定结论才计」。
     * 把该判断搬进分发器，等于在此复制三套结论映射；本类也不应认识 `GoformSmsClient`
     * （既有分工：判定同构在分发器，记账各自在渠道，见 [ChannelRules]）。
     * 加锁不改变这条分工，只是使「读配额 → 投递 → 扣配额」这一段不被其他轮次插入。
     *
     * ## 按渠道分锁而非一把全局锁
     *
     * 配额本身是每渠道独立的（三条渠道各一对 `quota_day` / `quota_count`）。全局锁会使
     * 「邮件正在做 SMTP 握手」阻塞「本机短信发送断网通知」，而这两条链路互为补充。
     * 锁的持有时间由 [CHANNEL_ROUND_BUDGET_MS] 兜住上限，不会无界等待。
     */
    private val channelLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 已经停机了吗。停机后 [emit] 直接返回空结果，不再进入任何渠道。
     *
     * `@Volatile` 足够：单写（[shutdown]）多读，只需保证写入对其他线程立即可见。
     */
    @Volatile
    private var stopped = false

    /** 在途轮次计数。[shutdown] 依赖它在停机时等待在途投递收尾，理由见 [shutdown]。 */
    private val activeRounds = AtomicInteger(0)


    /** 注册一个渠道。同 id 重复注册会替换旧渠道，避免重复注册导致同一事件向该渠道投递两次。 */
    fun register(channel: NotifyChannel) {
        channels.removeAll { it.id == channel.id }
        channels.add(channel)
        AppLogger.i(tag, "渠道已注册: ${channel.id}（留投递记录=${channel.recordsHistory}，受总闸约束=${channel.respectsMasterGate}）")
    }

    /**
     * 投递一条事件，返回每个渠道的结果（渠道 id → 结果）及渠道元信息。
     *
     * 返回值参与调用方决策：`TrafficAutoOffGuard` 依据 [DeliveryReport.anyConfirmedSent]
     * 决定是否关闭移动数据，语义必须保持「通知未发出则不关网」，否则用户只会感知为突然断网。
     *
     * 语义：
     * - 闸门按渠道判定，不短路整条事件：受总闸约束的渠道返回 `Skipped(MASTER_OFF)` /
     *   `Skipped(QUIET_HOURS)`，其余渠道照常投递（理由见类注释）。`Skipped` 同样写入投递记录，
     *   但仅当渠道已配置时才写入 —— 噪声闸的理由见 [recordIfNeeded]；
     * - 渠道筛选有两道：[NotifyEvent.channels] 白名单与 [NotifyEvent.exclude] 黑名单。
     *   两道都未通过的渠道不出现在结果中（见 [exclusionsOf]）；
     * - 渠道之间串行，不并发：SMTP 需要持有 WakeLock，并发只会争抢 CPU 唤醒时间，
     *   而通知不属于延迟敏感路径。单渠道一轮有显式预算（[CHANNEL_ROUND_BUDGET_MS]），
     *   因此串行不会让一个无响应的目标把后续渠道拖到数分钟之后；
     * - 任一渠道抛异常不影响其他渠道（逐个捕获，异常转为 `Failed`）；
     * - [CancellationException] 原样重抛（约定：取消不是投递失败）；
     * - [shutdown] 之后一律返回空结果：组件均已停止，不应再接受投递。
     */
    suspend fun emit(event: NotifyEvent): DeliveryReport {
        // 停机后提前返回：此后 owner 会关闭渠道自身资源（`WebhookChannel.close()` 的
        // CIO 连接池、`SmsForwardController.historyScope`），此时进入的投递必然以异常收场。
        if (stopped) return DeliveryReport(emptyMap(), emptySet())
        val results = LinkedHashMap<String, DeliveryOutcome>(channels.size)
        val confirmable = LinkedHashSet<String>()
        // 诊断信息：只有实际进入 deliver() 的渠道才会有条目（理由见 DeliveryAttempt）。
        val diagnostics = LinkedHashMap<String, Any?>(channels.size)
        // 黑名单在循环外解析一次：同时提供白名单与黑名单时需要记录 WARN，
        // 放在循环内会按渠道数量重复记录同一行。
        val excluded = exclusionsOf(event)
        // 兜底开关同样在循环外读取一次：它是全局设置，按渠道重复读取只会增加 JSON 解析次数。
        val override = event.level == NotifyLevel.CRITICAL && criticalOverride?.invoke() != false
        activeRounds.incrementAndGet()
        try {
            for (channel in channels) {
                // 白名单最先判定：不在集合内的渠道不应调用 isConfigured()
                // （告警聚合更新仅限 push，此时检查邮件配置没有意义，还会多一次 prefs 读取）。
                val wanted = event.channels?.contains(channel.id) != false
                if (!wanted) continue
                // 黑名单：不产生 Skipped，也不记录日志。被排除的渠道并非「未发出」，
                // 而是「不属于该投递路径的职责」（新短信与电池的推送另有触发源）——
                // 记为 Skipped 会误导排查，并影响 anyConfirmedSent 的判据。
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
     * 本次需要排除的渠道 id。
     *
     * [NotifyEvent.channels]（白名单，「只投这些」）与 [NotifyEvent.exclude]（黑名单，
     * 「除这些之外都投」）不应同时给值：同时给值说明触发源对投递目标的语义存在歧义。
     * 处理方式是「以白名单为准并记录一条 WARN」，两点均为刻意设计：
     * - 以白名单为准 —— 其语义更窄；多发一条通知（尤其是计费的短信渠道）的代价高于少发；
     * - 记录 WARN 而非静默 —— 静默采纳任一语义会掩盖该歧义，而 release 构建只保留
     *   WARN/ERROR，落在 INFO 中事后无法查证。
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
     * 闸门对该渠道的判决。
     *
     * 三条短路，缺一不可：
     * - `event.manual` —— 手动触发（`POST /api/…/test`）跳过闸门：用户刚触发「发送测试」时，
     *   静默不发送比发送更难以排查；端点响应会另外返回 `auto_notify_enabled` 告知闸门状态，
     *   不做无声跳过；
     * - `!channel.respectsMasterGate` —— 推送不受该闸门约束（是否弹出由客户端决定）；
     * - `gate == null` —— 未装配按放行处理。
     *
     * 渠道 id 需传入：免打扰是否作用于该渠道属于每渠道各自的用户配置（见 [gate]）。
     */
    private fun verdictFor(channel: NotifyChannel, event: NotifyEvent): GateVerdict {
        if (event.manual || !channel.respectsMasterGate) return GateVerdict.ALLOW
        return gate?.invoke(channel.id) ?: GateVerdict.ALLOW
    }

    /**
     * 构造 [DeliveryOutcome.Skipped] 并记录日志。
     *
     * 日志与投递记录都需要：投递记录只在渠道已配置时写入，而「SMTP 某项未填，因此该告警
     * 未发出」恰恰是最需要留痕的情形，只存在于这行日志中。日志记录枚举名，投递记录写入
     * [SkipReason.label] 的中文文案，同样是刻意分工（约定：文案面向用户，日志面向排查）。
     *
     * [SkipReason.QUOTA_EXCEEDED] 记录 WARN：其余各档均为「用户不想接收」，
     * 而配额用尽是「需要通知但已无能力」—— 这是真实丢失了一条通知，混在 INFO 中
     * （release 只保留 WARN/ERROR）事后无法查证。
     */
    private fun skipped(channel: NotifyChannel, event: NotifyEvent, reason: SkipReason): DeliveryOutcome {
        val line = "渠道 ${channel.id} 跳过投递（scene=${event.scene}，原因=$reason）"
        if (reason == SkipReason.QUOTA_EXCEEDED) AppLogger.w(tag, line) else AppLogger.i(tag, line)
        return DeliveryOutcome.Skipped(reason)
    }


    /**
     * 单个渠道的整轮投递：判定短路 → 有界重试 → 写一条投递记录。
     *
     * ## 判定顺序（全仓唯一的强制点）
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
     * 闸门排在最前，且闸门判定本身仍在 [emit]（[gate] 是入参）：该谓词属于跨渠道的全局状态，
     * 放在此处会使本函数同时依赖注册表与全局配置。级别与配额两道读取 [NotifyChannel.rules]
     * （`null` 表示该渠道没有这两道闸，对应 push）。
     *
     * ## 两条 `manual` 口径
     *
     * - 级别与场景对 `manual` 放行：用户刚触发「发送测试」，因未勾选场景或级别不足而静默
     *   不发送，比发送更难以排查（沿用既有 `manual` 语义）。配置检查不跳过 ——
     *   SMTP 未填完时「测试」发不出去属于事实；
     * - 配额对 `manual` 不放行：配额意味着「已无能力」（计费渠道已达上限），
     *   测试按钮不应越过它，否则每次测试都会多计一条费用。
     *
     * ## CRITICAL 兜底（`event.level == CRITICAL` 且 [criticalOverride] 开启）
     *
     * 可穿透以下三道：
     * 1. [SkipReason.QUIET_HOURS] —— 关键事件的定义即包含夜间唤醒用户；
     * 2. [SkipReason.SCENE_OFF] —— 用户未勾选该场景，但断网、套餐用尽一类事件仍需送达；
     * 3. [SkipReason.LEVEL_TOO_LOW] —— 级别门槛用于抑制噪声，而 CRITICAL 不属于噪声。
     *
     * 永不穿透以下三道：
     * 1. [SkipReason.MASTER_OFF] —— 该状态表示用户已选择全部停发，兜底不得违反；
     * 2. [SkipReason.NOT_CONFIGURED] —— 不是不想发送，而是无法发送；
     * 3. [SkipReason.QUOTA_EXCEEDED] —— 需要通知但已无能力，穿透即无上限计费。
     *
     * 穿透时必须留痕：记录一行 WARN（release 只保留 WARN/ERROR），且该次投递在成功或失败时
     * 照常写入投递记录。不新增第四种 [DeliveryOutcome]：「经兜底放行的成功」与普通成功在结果上
     * 没有区别，增加一态会使每个消费方都要追加分支。
     *
     * ## 全仓唯一的重试循环
     *
     * 重试循环只在此处：两个循环并存会使一次失败放大为 3 × 3 次尝试，并重复写入投递记录。
     * 邮件侧只保留异常分类（`MailDelivery.isRetryable`）与失败文案 —— 这些判据只有邮件掌握
     * （授权码错误与握手超时），分发器不应也无从得知。
     *
     * ## 每渠道独立的整轮预算
     *
     * 每一轮有一个截止时刻（[roundBudgetMs]）。判据位于尝试之间：退避结束后是否仍有时间
     * 再开始一次尝试。到点即带上一次尝试的真实结果收工，不合成「预算用尽」的假失败。
     * 取值推导与不打断在飞尝试的理由写在 [CHANNEL_ROUND_BUDGET_MS] 上。
     *
     * ## 配额的 check-then-act 在一把锁里
     *
     * 本函数整体由 [channelLocks] 中该渠道的锁保护：`hasQuota()` 与渠道内部的 `consume()`
     * 之间隔着一次真实发信，不加锁时两条并发的 CRITICAL 事件会在配额只剩 1 条时双双放行
     * （理由与备选方案见 [channelLocks]）。
     *
     * @param override 该事件是否享受 CRITICAL 兜底（由 [emit] 计算，全渠道共用一份）。
     */
    private suspend fun deliverTo(
        channel: NotifyChannel,
        event: NotifyEvent,
        gate: GateVerdict,
        override: Boolean
    ): DeliveryAttempt = channelLocks.computeIfAbsent(channel.id) { Mutex() }.withLock {
        // 先按「不启用兜底」判定一次：该结果既是非 CRITICAL 事件的最终结论，
        // 也是判断兜底是否真正生效的依据 —— 仅在确实发生穿透时记录 WARN，
        // 否则每条 CRITICAL 事件都会产生一行噪声日志。
        val strict = blockingReason(channel, event, gate, override = false)
        val penetrable = strict != null && strict in CRITICAL_OVERRIDABLE
        // 仅在确实可穿透时重新判定（额外的 prefs 读取只发生在该档）：穿透第一道之后，
        // 后续各道仍需按顺序判定，不能直接放行 —— 免打扰可穿透不代表 SMTP 未配全也能发送。
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
            // attempts = 0 表示未发生任何投递。历史页据此区分「跳过」与「重试 3 次后失败」。
            recordIfNeeded(channel, event, outcome, attempts = 0)
            // 诊断返回 null：该轮未发生任何投递，状态码与配额结算均不属于本次；
            // 回读渠道上一次的快照会返回过期数据，DeliveryAttempt 的诊断字段即用于避免该问题。
            return@withLock DeliveryAttempt(outcome)
        }


        var attempts = 0
        // 整轮预算的截止时刻。判据位于尝试之间，不打断正在执行的尝试（理由见
        // CHANNEL_ROUND_BUDGET_MS 的「不使用 withTimeout 打断在飞尝试」一节）。
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
                    // 渠道未自行转换为 Failed 而抛出异常：无法判定是否为瞬时错误，按不可重试处理 ——
                    // 宁可少投递一次，也不将一个永久错误放大为三次投递与三份日志。
                    DeliveryAttempt(
                        DeliveryOutcome.Failed(
                            "${e.javaClass.simpleName}: ${e.message}", retryable = false
                        )
                    )
                }
                val failure = last.outcome as? DeliveryOutcome.Failed
                if (failure == null || !failure.retryable || attempts >= retry.maxAttempts) break
                val backoff = retry.backoffMs.getOrElse(attempts - 1) { retry.backoffMs.lastOrNull() ?: 0L }
                // 预算判据：退避结束后是否仍有时间开始下一次尝试。到点即保留上一次尝试的真实结果
                // 收工，不合成「预算用尽」的假失败 —— 用户需要看到 HTTP 500、超时等真实原因。
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
     * 按 [deliverTo] 的判定表逐道判定，返回第一道拦下本次投递的原因；全部通过返回 null。
     *
     * 实现为提前返回的 `if` 链而非 `when`：每一道的入参不同（分别读取 [NotifyEvent]、
     * 渠道配置与配额计数器），且顺序本身即语义 —— `when` 的分支可在不报错的情况下被重排，
     * 而提前返回链一旦重排即可读出错位（例如「配额判定完成后仍在检查配置是否完整」）。
     *
     * @param override 为 true 时把三道可穿透的闸视为未拦截 —— 穿透表见 [CRITICAL_OVERRIDABLE]。
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
     * 写入投递记录，带两道过滤。
     *
     * 1. `!recordsHistory` —— 推送没有逐端投递结果（客户端侧另有 `notify_history`）；
     * 2. `Skipped` 且渠道未配全时不写入（跳过也留档之后引入的噪声闸）：对于 SMTP 未填完的
     *    用户，每条事件都会记一行「渠道配置不完整」，默认 500 条的环形缓冲在几十条之后
     *    即被占满，把真正的失败记录挤出缓冲，而后者正是该表必须保留的内容。判据使用
     *    `isConfigured()` 而不是 `reason != NOT_CONFIGURED`：闸门两档排在配置检查之前，
     *    未配全的渠道在免打扰时段得到的是 `Skipped(QUIET_HOURS)`，只看原因同样会被写满。
     *
     * 写入异常只记 WARN：投递记录用于自查，写入失败不应影响「通知已经发出」这一事实。
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
     * 停机收尾：先置位停机标志，再等待在途轮次收尾，最后清空注册表。
     *
     * ## 必须等待在途轮次
     *
     * `BackendService.stopAllComponents` 紧随本函数调用 `webhookChannel.close()`
     * （关闭 CIO 连接池）与 `smsForwardController.shutdown()`（取消历史写入 scope）。
     * 若只执行 `channels.clear()` 便返回，一条正在进行的 Webhook 投递会在其连接池被关闭后
     * 以异常收场，并按「不可重试」丢弃 —— 表现为停服瞬间触发的那条通知始终未发出，
     * 投递记录中只有一行连接异常。
     *
     * ## 两步
     *
     * 1. [stopped] 置位，此后 [emit] 一律空操作（新轮次无法进入）；
     * 2. 等待 [activeRounds] 归零，最多等待 [SHUTDOWN_DRAIN_MS]。超时则记录一条 WARN 后放行 ——
     *    停服不能无限阻塞，而单轮预算为 [CHANNEL_ROUND_BUDGET_MS]，等待满该值会使
     *    「停止服务」停滞半分钟以上；该取舍必须留痕，不能静默。
     *
     * 采用轮询而非信号量：本路径只在停机时执行一次，[SHUTDOWN_POLL_MS] 的粒度足够，
     * 而引入需要在每次 emit 中维护的 `CompletableDeferred` 只会多一处需要对齐的状态。
     *
     * 本函数不取消任何渠道 —— 渠道自身的 scope（`WebSocketPushService.scope`、
     * `SmsForwardController.historyScope`）与连接池由各自 owner 在停服路径上关闭。
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
         * 单个渠道一整轮的预算：到点之后不再开始新的尝试。
         *
         * ## 必要性
         *
         * 渠道之间串行，注册顺序为 push → mail → webhook → local_sms（`ComponentFactory`）。
         * Webhook 的 `timeout_ms` 由用户配置，上限 `WebhookConfig.MAX_TIMEOUT_MS` = 60s ——
         * 一个不响应的目标（域名可解析、端口不回包）会把该轮拖到 3 × 60s + 8s ≈ 3 分钟，
         * 而排在其后的正是断网时唯一仍可用的链路（本机短信走信令网）。结果是「数据断开」
         * 这条 CRITICAL 通知要等三分钟才会轮到短信渠道，用户在此期间只能看到设备失联。
         *
         * ## 45s 的推导
         *
         * 默认配置下单渠道最坏耗时 = 3 × `WebhookConfig.DEFAULT_TIMEOUT_MS`(10s) +
         * `RetryPolicy.DEFAULT_BACKOFF_MS`(2s + 6s) = 38s。预算取 45s：
         * - 大于 38s → 默认配置下不会截断正常重试（截断正常重试即丢失通知）；
         * - 余量 7s 用于 SMTP 渠道的建连与握手抖动（`withRound` 还需申请 WakeLock）；
         * - 将「用户把 `timeout_ms` 调到 60s」的配置从约 188s 压缩为一次尝试即收工。
         *
         * 预算按渠道各一份，而不是整条 emit 共用一份：共用一份时，前面的渠道耗尽预算后，
         * 后续渠道将没有任何投递机会，与预算要保护的目标相反。
         *
         * ## 不使用 `withTimeout` 打断在飞尝试
         *
         * 用 `withTimeoutOrNull(45s)` 包住整段重试会产生两个后果，均比多等一段时间严重：
         * 1. 可能已产生费用但未记账：在 `LocalSmsChannel.deliver` 中途取消时，
         *    goform 的 SEND_SMS 可能已写入固件，而 `store.consume()` 永远不会执行 ——
         *    短信已发出、配额未扣减，用户配置的每日上限从此偏移一条；
         * 2. 诊断字段整组丢失：被取消的 `deliver` 不返回任何内容，于是「该次投递是否发起过」
         *    无法体现在响应中（`attempted_at` 缺失会被客户端视为「未投递」，
         *    契约见 `WebhookRoutes` / `LocalSmsRoutes` 的 `/test`）。
         *
         * 因此判据改为尝试之间的截止时间。代价是最坏耗时 = 截止时刻 + 一次尝试的时长，
         * 而单次尝试由渠道自身超时兜住（webhook = `timeout_ms` ≤ 60s；
         * mail = `SMTP_TIMEOUT_MS` 连接与读取各 10s；local_sms = goform 的 10s 请求超时 +
         * 3 × 1.2s 回读）。「× 3 次重试」带来的放大已不存在。
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
         * CRITICAL 兜底可穿透的三道闸。该集合是唯一来源，理由逐条写在 [deliverTo] 上。
         *
         * 集中为集合而不是散落在判定逻辑中：这三档与「永不穿透」的另外三档必须同时变更，
         * 漏改一处的表现分别是「关键通知在夜间被静默」（少放一档）与
         * 「总开关关闭时仍在发送、配额被透支」（多放一档）—— 两者都破坏对用户的既有承诺。
         */
        val CRITICAL_OVERRIDABLE = setOf(
            SkipReason.QUIET_HOURS,
            SkipReason.SCENE_OFF,
            SkipReason.LEVEL_TOO_LOW
        )
    }
}

