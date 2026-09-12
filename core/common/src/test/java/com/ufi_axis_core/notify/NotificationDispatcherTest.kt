package com.ufi_axis_core.notify

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger


/**
 * [NotificationDispatcher] 的核心语义：闸门作用域、渠道限定、有界重试、渠道隔离、
 * 以及"通报到位"的判定（[DeliveryReport.anyConfirmedSent]）。
 *
 * 这是阶段 1 新增的核心组件，也是**全仓唯一的重试循环**所在，所以用假渠道逐条钉住。
 *
 * 退避一律传 [NO_BACKOFF]：验的是次数与判据，不是真去等 2s + 6s
 * （生产取值在 `RetryPolicy.DEFAULT`）。用 `runBlocking` 而不是 `runTest`：
 * 零退避下没有需要虚拟时间推进的地方，少一个测试依赖。
 */
class NotificationDispatcherTest {

    private companion object {
        /** 单测用的零退避（真实退避是 `RetryPolicy.DEFAULT_BACKOFF_MS`）。 */
        val NO_BACKOFF = listOf(0L, 0L)

        val FAST_RETRY = RetryPolicy(maxAttempts = RetryPolicy.DEFAULT_MAX_ATTEMPTS, backoffMs = NO_BACKOFF)

        /** 邮件渠道 id。`MailChannel` 在 `:core:controller`，本模块只能用同一个字符串。 */
        const val MAIL = "mail"

        /** 本机短信渠道 id（`LocalSmsChannel.ID`，同样在另一个模块）。 */
        const val LOCAL_SMS = "local_sms"

        /** Webhook 渠道 id（`WebhookChannel.ID`，同样在另一个模块）。 */
        const val WEBHOOK = "webhook"

        /**
         * 诊断补充的哨兵值。
         *
         * 真渠道那侧是 `WebhookChannel.Attempt` / `LocalSmsChannel.Attempt`（在 `:core:controller`，
         * 本模块引不到）。这些用例验的是"有没有把它带回来"，不是它长什么样，所以给个字符串就够。
         */
        const val DIAGNOSTICS = "attempt-diagnostics"

        /** 单次 deliver 的耗时与整轮预算：**预算比一次尝试还短**，用来把预算判定逼出来。 */
        const val SLOW_DELIVER_MS = 60L
        const val TIGHT_BUDGET_MS = 40L


        fun event(
            scene: String = NotifyScenes.ALERT,
            level: NotifyLevel = NotifyLevel.WARNING,
            manual: Boolean = false,
            channels: Set<String>? = null,
            exclude: Set<String>? = null
        ) = NotifyEvent(
            scene = scene,
            level = level,
            title = "t",
            body = "b",
            manual = manual,
            channels = channels,
            exclude = exclude
        )
    }

    /**
     * 可编程假渠道：记录被调了几次、写了几条投递记录。
     *
     * 默认按**邮件**那一档配（受总闸约束、有送达确认），推送那一档用 [pushLike] 造。
     *
     * [outcomes] 按尝试次序取用（取完之后一直用最后一个），所以"第 1 次失败第 2 次成功"
     * 这种序列可以直接表达。
     *
     * [rules] 默认 **null** = 这条渠道没有级别门槛与配额（push 那一档）。要验同构规则的
     * 用例自己传一份 [FakeRules]。
     */
    private class FakeChannel(
        override val id: String,
        override val recordsHistory: Boolean = false,
        override val respectsMasterGate: Boolean = true,
        override val hasDeliveryConfirmation: Boolean = true,
        override val rules: ChannelRules? = null,
        private val configured: Boolean = true,
        private val acceptsScene: Boolean = true,
        private val outcomes: List<DeliveryOutcome> = listOf(DeliveryOutcome.Sent),
        /**
         * `deliver` 随结果带回的诊断补充（真渠道那侧是 `WebhookChannel.Attempt` 这类对象，
         * 本模块认不到它们，所以用一个哨兵值 —— 验的是"有没有带回来"，不是它长什么样）。
         */
        private val diagnostics: Any? = null,
        /** 单次 `deliver` 的耗时。只给"整轮预算"那条用例用（默认 0 = 立刻返回）。 */
        private val deliverDelayMs: Long = 0L,
        /** 非空时 deliver 直接抛这个异常（测"渠道抛异常不影响其它渠道"）。 */
        private val throws: (() -> Throwable)? = null
    ) : NotifyChannel {
        var deliverCalls = 0
        var rounds = 0
        val history = mutableListOf<Pair<DeliveryOutcome, Int>>()

        override fun isConfigured(): Boolean = configured
        override fun accepts(scene: String): Boolean = acceptsScene

        override suspend fun <T> withRound(block: suspend () -> T): T {
            rounds++
            return block()
        }

        override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
            deliverCalls++
            throws?.let { throw it() }
            if (deliverDelayMs > 0) delay(deliverDelayMs)
            return DeliveryAttempt(
                outcomes.getOrElse(deliverCalls - 1) { outcomes.last() },
                diagnostics
            )
        }



        override suspend fun recordHistory(event: NotifyEvent, outcome: DeliveryOutcome, attempts: Int) {
            history.add(outcome to attempts)
        }

        companion object {
            /** 推送那一档：不受总闸约束、没有送达确认、没有 rules。 */
            fun pushLike(id: String = PushChannel.ID) = FakeChannel(
                id = id,
                respectsMasterGate = false,
                hasDeliveryConfirmation = false
            )
        }
    }

    /**
     * 假的同构规则：直接给死取值，不去碰任何存储。
     *
     * 生产实现（三条渠道各一份）读的是自己那份 prefs 与自己那对 `quota_day` / `quota_count`；
     * 本类只负责把"级别门槛 / 每日上限 / 今天已用"三个数喂给分发器，
     * 验的是**判定**，不是取值来源。
     */
    private class FakeRules(
        private val min: NotifyLevel = NotifyLevel.INFO,
        private val limit: Int = ChannelRules.UNLIMITED,
        private val used: Int = 0
    ) : ChannelRules {
        override fun minLevel(): NotifyLevel = min
        override fun dailyLimit(): Int = limit
        override fun sentToday(): Int = used
    }

    // ══════════ 闸门作用域 ══════════

    /**
     * 总开关关闭 → **只掐受约束的渠道**：邮件 `Skipped(MASTER_OFF)`、推送照投。
     *
     * 这条是 F1 的回归线：闸门曾经拦在渠道循环之前，而 `master_enabled` 默认 false，
     * 于是用户没手动打开总开关时一条 WS 推送都发不出去（app 的 `:ufi_notify` 与 web
     * 实时告警全停）。闸门作用域一旦再收窄回"整条事件"，这里就红。
     */
    @Test
    fun `closed gate skips mail but still pushes`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL, recordsHistory = true)
        val dispatcher = NotificationDispatcher(gate = { GateVerdict.BLOCKED_BY_MASTER }, retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertEquals("推送不受总闸约束，必须照投", DeliveryOutcome.Sent, results[PushChannel.ID])
        assertEquals(1, push.deliverCalls)
        assertEquals(DeliveryOutcome.Skipped(SkipReason.MASTER_OFF), results[MAIL])
        assertEquals("闸门拦下时邮件不该投递", 0, mail.deliverCalls)
        // DB v12 起跳过也留档：「我开着通知，这条为什么没收到」的答案原本只在 INFO 日志里，
        // 而 release 不留 INFO。attempts = 0 是"一次都没投"的标记。
        assertEquals(1, mail.history.size)
        assertEquals(DeliveryOutcome.Skipped(SkipReason.MASTER_OFF), mail.history.single().first)
        assertEquals("跳过时尝试次数必须是 0", 0, mail.history.single().second)
    }

    /**
     * 闸门三态各自映射到一档 [SkipReason]，`ALLOW` 与未装配都放行。
     *
     * 这条钉的是"**别把两种静默混成一句话**"（2026-09-10 把 `GATE` 拆成两档的理由）：
     * 投递记录里要看得出到底是"用户关了总开关"还是"现在是半夜"，而 CRITICAL 兜底
     * 只允许穿透后者 —— 混在一档里那个判定根本没法写。
     */
    @Test
    fun `gate verdict maps to its own skip reason`() = runBlocking {
        val cases = mapOf(
            GateVerdict.BLOCKED_BY_MASTER to DeliveryOutcome.Skipped(SkipReason.MASTER_OFF),
            GateVerdict.BLOCKED_BY_DND to DeliveryOutcome.Skipped(SkipReason.QUIET_HOURS),
            GateVerdict.ALLOW to DeliveryOutcome.Sent
        )
        assertEquals("新增了 GateVerdict 却没登记到这张表里", GateVerdict.entries.size, cases.size)

        for ((verdict, expected) in cases) {
            val mail = FakeChannel(MAIL)
            val dispatcher = NotificationDispatcher(gate = { verdict }, retry = FAST_RETRY)
            dispatcher.register(mail)
            assertEquals("gate=$verdict 的结果", expected, dispatcher.emit(event())[MAIL])
        }
    }

    /** 手动触发跳过闸门（用户刚按下"发送测试"，静默什么都不发比发出去更难排查）。 */
    @Test
    fun `manual event passes a closed gate`() = runBlocking {
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(gate = { GateVerdict.BLOCKED_BY_MASTER }, retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event(manual = true))

        assertEquals(DeliveryOutcome.Sent, results[MAIL])
        assertEquals(1, mail.deliverCalls)
    }

    /** 未装配闸门（降级装配）视为放行：不能因为装配层漏接就静默丢通知。 */
    @Test
    fun `missing gate means pass`() = runBlocking {
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(gate = null, retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Sent, dispatcher.emit(event())[MAIL])
    }

    /** manual 只跳过**场景勾选**，不跳过配置检查：SMTP 没填完的"测试"确实发不出去。 */
    @Test
    fun `manual event still requires channel configuration`() = runBlocking {
        val mail = FakeChannel(MAIL, configured = false)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(
            DeliveryOutcome.Skipped(SkipReason.NOT_CONFIGURED),
            dispatcher.emit(event(manual = true))[MAIL]
        )
        assertEquals(0, mail.deliverCalls)
    }

    /** 场景没勾选 → `Skipped(SCENE_OFF)`，并留一条 `attempts = 0` 的跳过记录。 */
    @Test
    fun `unaccepted scene is recorded as skipped`() = runBlocking {
        val mail = FakeChannel(MAIL, recordsHistory = true, acceptsScene = false)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Skipped(SkipReason.SCENE_OFF), dispatcher.emit(event())[MAIL])
        assertEquals(0, mail.deliverCalls)
        assertEquals(DeliveryOutcome.Skipped(SkipReason.SCENE_OFF), mail.history.single().first)
        assertEquals(0, mail.history.single().second)
    }

    // ══════════ 跳过留档的噪声闸 ══════════

    /**
     * 渠道**没配全**时的跳过 → 不留档。
     *
     * 这道闸是必须的：SMTP 一项没填的用户，每来一条事件都会记一行"渠道配置不完整"，
     * 默认 500 条的环形缓冲几十条就被冲满，把真正的失败记录挤出去 ——
     * 而那才是这张表唯一必须留住的东西。原因仍然会打进日志。
     */
    @Test
    fun `skip is not recorded when the channel is unconfigured`() = runBlocking {
        val mail = FakeChannel(MAIL, recordsHistory = true, configured = false)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(
            DeliveryOutcome.Skipped(SkipReason.NOT_CONFIGURED),
            dispatcher.emit(event())[MAIL]
        )
        assertTrue("没配全的渠道不留跳过记录，否则环形缓冲会被刷满", mail.history.isEmpty())
    }

    /**
     * 没配全 **且** 总开关关闭 → 拿到的是 `Skipped(MASTER_OFF)`，同样不留档。
     *
     * 这条钉的是噪声闸的判据必须是 `isConfigured()` 而不是 `reason != NOT_CONFIGURED`：
     * 闸门那两档排在配置检查之前，只看原因的话没配全的渠道在免打扰时段照样会被刷满。
     */
    @Test
    fun `gate skip is not recorded when the channel is unconfigured`() = runBlocking {
        val mail = FakeChannel(MAIL, recordsHistory = true, configured = false)
        val dispatcher = NotificationDispatcher(gate = { GateVerdict.BLOCKED_BY_MASTER }, retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Skipped(SkipReason.MASTER_OFF), dispatcher.emit(event())[MAIL])
        assertTrue(mail.history.isEmpty())
    }

    /**
     * 渠道**自己**在 `deliver()` 里返回的跳过（本机短信的级别不够 / 配额用尽）也留档。
     *
     * 这两档是那条渠道特有的、也是最需要留痕的：「这条告警为什么没发短信」以前只能翻日志。
     * 与上面几档的区别是它走完了 `deliver()`，所以 `attempts` 是 1 而不是 0。
     */
    @Test
    fun `skip returned by deliver is recorded`() = runBlocking {
        val sms = FakeChannel(
            LOCAL_SMS,
            recordsHistory = true,
            outcomes = listOf(DeliveryOutcome.Skipped(SkipReason.LEVEL_TOO_LOW))
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.LEVEL_TOO_LOW), sms.history.single().first)
        assertEquals("deliver 走过一次，尝试次数是 1", 1, sms.history.single().second)
    }

    /** 每一档 [SkipReason] 都要有中文说明（留档时写进 `error` 列，界面直接显示）。 */
    @Test
    fun `every skip reason has a label`() {
        for (reason in SkipReason.entries) {
            assertTrue("SkipReason.$reason 缺中文说明", reason.label.isNotBlank())
        }
    }

    // ══════════ 同构规则（ChannelRules：级别门槛 + 每日配额） ══════════
    //
    // 2026-09-10 阶段 2：这两道闸此前只有本机短信渠道有、而且判定写在它自己的 deliver() 里。
    // 现在三条渠道形状一样、判定只有分发器这一处，所以这一组用例是那次搬迁的验收线。

    /** 级别不够 → `Skipped(LEVEL_TOO_LOW)`，而且 `deliver` **零调用**（花钱的渠道靠这个不烧钱）。 */
    @Test
    fun `level below the channel threshold skips without delivering`() = runBlocking {
        val sms = FakeChannel(
            LOCAL_SMS,
            recordsHistory = true,
            rules = FakeRules(min = NotifyLevel.CRITICAL)
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        // 事件默认 WARNING < CRITICAL
        assertEquals(DeliveryOutcome.Skipped(SkipReason.LEVEL_TOO_LOW), dispatcher.emit(event())[LOCAL_SMS])
        assertEquals("级别不够时一次都不该投", 0, sms.deliverCalls)
    }

    /** 级别刚好够（等于门槛）→ 照投。门槛是"最低"，不是"高于"。 */
    @Test
    fun `level at the channel threshold delivers`() = runBlocking {
        val sms = FakeChannel(LOCAL_SMS, rules = FakeRules(min = NotifyLevel.WARNING))
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        assertEquals(
            DeliveryOutcome.Sent,
            dispatcher.emit(event(level = NotifyLevel.WARNING))[LOCAL_SMS]
        )
    }

    /** 配额用尽 → `Skipped(QUOTA_EXCEEDED)`，`deliver` 零调用（这条是账单防线）。 */
    @Test
    fun `exhausted quota skips without delivering`() = runBlocking {
        val sms = FakeChannel(LOCAL_SMS, rules = FakeRules(limit = 5, used = 5))
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        assertEquals(DeliveryOutcome.Skipped(SkipReason.QUOTA_EXCEEDED), dispatcher.emit(event())[LOCAL_SMS])
        assertEquals("配额用尽时一次都不该投", 0, sms.deliverCalls)
    }

    /**
     * `dailyLimit = UNLIMITED` 时**永不**触发配额，哪怕今天已经发了很多条。
     *
     * 这一档是邮件与 Webhook 的**默认值**：这次改造不能让存量用户突然收不到通知。
     */
    @Test
    fun `unlimited quota never triggers`() = runBlocking {
        val mail = FakeChannel(
            MAIL,
            rules = FakeRules(limit = ChannelRules.UNLIMITED, used = 9_999)
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Sent, dispatcher.emit(event())[MAIL])
        assertEquals(1, mail.deliverCalls)
    }

    /** 没有 rules 的渠道（push 那一档）不受这两道闸约束 —— 它连这两个概念都没有。 */
    @Test
    fun `a channel without rules is not gated by level or quota`() = runBlocking {
        val push = FakeChannel.pushLike()
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)

        assertEquals(
            DeliveryOutcome.Sent,
            dispatcher.emit(event(level = NotifyLevel.INFO))[PushChannel.ID]
        )
    }

    /**
     * `manual` **放行级别与场景，但不放行配额**。
     *
     * 前半：用户刚按下"发送测试"，因为没勾场景 / 级别不够而静默什么都不发，比发出去更难排查。
     * 后半：配额是"没有能力了"（花钱的渠道已经烧到上限），测试按钮越过它就等于
     * **按一次测试多花一条钱**。
     */
    @Test
    fun `manual passes level and scene but never quota`() = runBlocking {
        val relaxed = FakeChannel(
            LOCAL_SMS,
            acceptsScene = false,
            rules = FakeRules(min = NotifyLevel.CRITICAL)
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(relaxed)
        assertEquals(
            "manual 应放行级别与场景",
            DeliveryOutcome.Sent,
            dispatcher.emit(event(level = NotifyLevel.INFO, manual = true))[LOCAL_SMS]
        )
        assertEquals(1, relaxed.deliverCalls)

        val exhausted = FakeChannel(LOCAL_SMS, rules = FakeRules(limit = 3, used = 3))
        val quotaDispatcher = NotificationDispatcher(retry = FAST_RETRY)
        quotaDispatcher.register(exhausted)
        assertEquals(
            "manual 不该越过配额（越过就是按一次测试多花一条钱）",
            DeliveryOutcome.Skipped(SkipReason.QUOTA_EXCEEDED),
            quotaDispatcher.emit(event(manual = true))[LOCAL_SMS]
        )
        assertEquals(0, exhausted.deliverCalls)
    }

    /**
     * 判定**顺序**：同时"没配全 + 级别不够"时报 `NOT_CONFIGURED`。
     *
     * 顺序不能反：`LEVEL_TOO_LOW` 算"用户主动静默"（`anyConfirmedSent` 认它），
     * 而 `NOT_CONFIGURED` 是"根本发不出去"（不认）。反了之后 SMTP 一项没填的用户
     * 会让自动关网照常执行 —— 一边发不出通知一边把网关掉。
     */
    @Test
    fun `configuration is checked before the level threshold`() = runBlocking {
        val mail = FakeChannel(
            MAIL,
            configured = false,
            rules = FakeRules(min = NotifyLevel.CRITICAL)
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.NOT_CONFIGURED), results[MAIL])
        assertFalse("没配全就不算通报到位", results.anyConfirmedSent())
    }

    /** 闸门排在配置检查之前：总开关关着时，没配全的渠道报的是 `MASTER_OFF`。 */
    @Test
    fun `the gate is checked before the configuration`() = runBlocking {
        val mail = FakeChannel(MAIL, configured = false)
        val dispatcher = NotificationDispatcher(gate = { GateVerdict.BLOCKED_BY_MASTER }, retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Skipped(SkipReason.MASTER_OFF), dispatcher.emit(event())[MAIL])
    }

    // ══════════ CRITICAL 兜底 ══════════
    //
    // 用户可见的承诺（`NotificationConfig.critical_override_enabled`，默认开）：
    // CRITICAL 穿透 免打扰 / 场景未勾 / 级别不够；永远穿不透 总开关 / 没配全 / 配额用尽。
    // 下面两条用例把这张表**逐档**钉死 —— 少放一档 = 半夜断网一声不响，
    // 多放一档 = 总开关成了假开关或者配额被烧穿。

    /**
     * 可穿透的三档：CRITICAL 过得去，WARNING 过不去（同一套配置，只差事件级别）。
     *
     * `LEVEL_TOO_LOW` 那一档在**当前三档级别下天然不可能拦住 CRITICAL**
     * （CRITICAL 是最高级，任何门槛都满足），所以它在这里验的是"最高级事件永远够门槛"。
     * 兜底规则仍然把它写进穿透表，是为了将来加入更高级别时行为不变 —— 这条注释就是那道提醒。
     */
    @Test
    fun `critical override penetrates dnd scene and level`() = runBlocking {
        // 每一档给一个"只有这一道闸会拦"的渠道 + 闸门判决
        val cases = listOf(
            Triple(SkipReason.QUIET_HOURS, GateVerdict.BLOCKED_BY_DND, { FakeChannel(MAIL) }),
            Triple(SkipReason.SCENE_OFF, GateVerdict.ALLOW, { FakeChannel(MAIL, acceptsScene = false) }),
            Triple(
                SkipReason.LEVEL_TOO_LOW, GateVerdict.ALLOW,
                { FakeChannel(MAIL, rules = FakeRules(min = NotifyLevel.CRITICAL)) }
            )
        )
        assertEquals("穿透表变了就要跟着改这条用例", 3, cases.size)

        for ((reason, verdict, channelOf) in cases) {
            val blocked = channelOf()
            val strict = NotificationDispatcher(gate = { verdict }, retry = FAST_RETRY)
            strict.register(blocked)
            assertEquals(
                "WARNING 不该穿透 $reason",
                DeliveryOutcome.Skipped(reason),
                strict.emit(event(level = NotifyLevel.WARNING))[MAIL]
            )
            assertEquals(0, blocked.deliverCalls)

            val passed = channelOf()
            val lenient = NotificationDispatcher(gate = { verdict }, retry = FAST_RETRY)
            lenient.register(passed)
            assertEquals(
                "CRITICAL 应当穿透 $reason",
                DeliveryOutcome.Sent,
                lenient.emit(event(level = NotifyLevel.CRITICAL))[MAIL]
            )
            assertEquals(1, passed.deliverCalls)
        }
    }

    /**
     * 穿不透的三档：连 CRITICAL **也过不去**。
     *
     * - `MASTER_OFF` —— 用户说"一条都别发"，兜底也不能违反；
     * - `NOT_CONFIGURED` —— 不是不想发，是发不出去；
     * - `QUOTA_EXCEEDED` —— 想通知但没能力了，穿透它就是无上限烧钱。
     */
    @Test
    fun `critical override never penetrates master off unconfigured or quota`() = runBlocking {
        val cases = listOf(
            Triple(SkipReason.MASTER_OFF, GateVerdict.BLOCKED_BY_MASTER, { FakeChannel(MAIL) }),
            Triple(SkipReason.NOT_CONFIGURED, GateVerdict.ALLOW, { FakeChannel(MAIL, configured = false) }),
            Triple(
                SkipReason.QUOTA_EXCEEDED, GateVerdict.ALLOW,
                { FakeChannel(MAIL, rules = FakeRules(limit = 2, used = 2)) }
            )
        )
        assertEquals("穿透表变了就要跟着改这条用例", 3, cases.size)

        for ((reason, verdict, channelOf) in cases) {
            val channel = channelOf()
            val dispatcher = NotificationDispatcher(gate = { verdict }, retry = FAST_RETRY)
            dispatcher.register(channel)
            assertEquals(
                "CRITICAL 也不该穿透 $reason",
                DeliveryOutcome.Skipped(reason),
                dispatcher.emit(event(level = NotifyLevel.CRITICAL))[MAIL]
            )
            assertEquals(0, channel.deliverCalls)
        }
    }

    /** 兜底**关闭**时，CRITICAL 一档都不穿透（这是用户显式选择的结果）。 */
    @Test
    fun `a disabled critical override penetrates nothing`() = runBlocking {
        val dnd = FakeChannel(MAIL)
        val dndDispatcher = NotificationDispatcher(
            gate = { GateVerdict.BLOCKED_BY_DND },
            criticalOverride = { false },
            retry = FAST_RETRY
        )
        dndDispatcher.register(dnd)
        assertEquals(
            DeliveryOutcome.Skipped(SkipReason.QUIET_HOURS),
            dndDispatcher.emit(event(level = NotifyLevel.CRITICAL))[MAIL]
        )
        assertEquals(0, dnd.deliverCalls)

        val sceneOff = FakeChannel(MAIL, acceptsScene = false)
        val sceneDispatcher = NotificationDispatcher(criticalOverride = { false }, retry = FAST_RETRY)
        sceneDispatcher.register(sceneOff)
        assertEquals(
            DeliveryOutcome.Skipped(SkipReason.SCENE_OFF),
            sceneDispatcher.emit(event(level = NotifyLevel.CRITICAL))[MAIL]
        )
        assertEquals(0, sceneOff.deliverCalls)
    }


    /**
     * 未装配 [NotificationDispatcher] 的兜底取值口（null）视为**开启**。
     *
     * 理由同 `gate` 的 null 语义：不能因为装配层漏接就让关键通知被静默。
     */
    @Test
    fun `a missing critical override is treated as enabled`() = runBlocking {
        val sceneOff = FakeChannel(MAIL, acceptsScene = false)
        val dispatcher = NotificationDispatcher(criticalOverride = null, retry = FAST_RETRY)
        dispatcher.register(sceneOff)

        assertEquals(
            DeliveryOutcome.Sent,
            dispatcher.emit(event(level = NotifyLevel.CRITICAL))[MAIL]
        )
    }

    /** 兜底只对 CRITICAL 生效：WARNING 事件在兜底开着时也照常被场景开关拦下。 */
    @Test
    fun `the override only applies to critical events`() = runBlocking {
        val sceneOff = FakeChannel(MAIL, acceptsScene = false)
        val dispatcher = NotificationDispatcher(criticalOverride = { true }, retry = FAST_RETRY)
        dispatcher.register(sceneOff)

        assertEquals(
            DeliveryOutcome.Skipped(SkipReason.SCENE_OFF),
            dispatcher.emit(event(level = NotifyLevel.WARNING))[MAIL]
        )
    }


    // ══════════ 通报到位（anyConfirmedSent） ══════════

    /**
     * 只有推送成功 → **false**。
     *
     * 推送是 fire-and-forget 广播，恒返回 `Sent`。拿它当"通报到位"会让
     * `TrafficAutoOffGuard` 的判据恒真：SMTP 没配好也照样断网，用户只看到设备坏了。
     */
    @Test
    fun `push only success is not a confirmed delivery`() = runBlocking {
        val push = FakeChannel.pushLike()
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Sent, results[PushChannel.ID])
        assertFalse("推送没有送达确认，不能算通报到位", results.anyConfirmedSent())
    }

    /** 可确认渠道真的投出去了 → true。 */
    @Test
    fun `confirmable channel sent is a confirmed delivery`() = runBlocking {
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(FakeChannel(MAIL))

        assertTrue(dispatcher.emit(event()).anyConfirmedSent())
    }

    /**
     * 可确认渠道被总开关拦下 → **true**（用户主动选的静默不该让自动关网永久不执行）。
     */
    @Test
    fun `gate skipped confirmable channel still counts as reported`() = runBlocking {
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(gate = { GateVerdict.BLOCKED_BY_MASTER }, retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.MASTER_OFF), results[MAIL])
        assertTrue("总开关是用户自己关的，不算投递失败", results.anyConfirmedSent())
    }

    /** SMTP 没配全 → false（用户根本收不到通报，这时候断网就是无声故障）。 */
    @Test
    fun `unconfigured confirmable channel is not reported`() = runBlocking {
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(FakeChannel(MAIL, configured = false))

        assertFalse(dispatcher.emit(event()).anyConfirmedSent())
    }

    /**
     * 场景没勾选 → **true**（2026-09-09 补齐）。
     *
     * 「在邮件设置里取消勾选 traffic80」与「关掉通知总开关」是同一类**主动选择**。
     * 补齐之前只认 `GATE`，于是前者会让自动关网**永久不执行** ——
     * 用户开着"到量自动关网"，却因为少勾一个邮件场景而从来不生效，
     * 正是 `GATE` 那一档想避免的"假开关"，只是换了个入口进来。
     */
    @Test
    fun `scene off confirmable channel counts as reported`() = runBlocking {
        val mail = FakeChannel(MAIL, acceptsScene = false)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.SCENE_OFF), results[MAIL])
        assertTrue("场景是用户自己取消勾选的，不算投递失败", results.anyConfirmedSent())
    }

    /**
     * 级别不够（本机短信渠道的最低级别门槛）→ **true**，与 `SCENE_OFF` 同类。
     *
     * "只用短信通知我 CRITICAL" 也是用户主动调高了门槛，不该让自动关网永久不执行。
     */
    @Test
    fun `level too low confirmable channel counts as reported`() = runBlocking {
        val sms = FakeChannel(
            LOCAL_SMS,
            outcomes = listOf(DeliveryOutcome.Skipped(SkipReason.LEVEL_TOO_LOW))
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.LEVEL_TOO_LOW), results[LOCAL_SMS])
        assertTrue(results.anyConfirmedSent())
    }

    /**
     * 每日配额用尽 → **false**（刻意与上面两档相反）。
     *
     * 这一档是「**想通知但没能力了**」，与"SMTP 没配全"同类，**不是**用户选的静默。
     * 把它算成"通报到位"就等于：短信配额烧完的那天，设备会一边发不出通知一边把网关掉。
     */
    @Test
    fun `quota exceeded confirmable channel is not reported`() = runBlocking {
        val sms = FakeChannel(
            LOCAL_SMS,
            outcomes = listOf(DeliveryOutcome.Skipped(SkipReason.QUOTA_EXCEEDED))
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(sms)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.QUOTA_EXCEEDED), results[LOCAL_SMS])
        assertFalse("配额烧完不等于用户收到了通报", results.anyConfirmedSent())
    }

    /**
     * 逐档遍历 [SkipReason]，把"哪些算通报到位"整张表钉死。
     *
     * 单独写这一条是因为**新增枚举值时最容易漏的就是这张表** ——
     * 漏了只会让自动关网在某个入口下静默地永不执行（或者相反：没通知到也照样断网）。
     */
    @Test
    fun `confirmed delivery table covers every skip reason`() = runBlocking {
        val expected = mapOf(
            SkipReason.MASTER_OFF to true,
            SkipReason.QUIET_HOURS to true,
            SkipReason.SCENE_OFF to true,
            SkipReason.LEVEL_TOO_LOW to true,
            SkipReason.NOT_CONFIGURED to false,
            SkipReason.QUOTA_EXCEEDED to false
        )
        assertEquals("新增了 SkipReason 却没登记到这张表里", SkipReason.entries.size, expected.size)

        for ((reason, counts) in expected) {
            val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
            dispatcher.register(
                FakeChannel(MAIL, outcomes = listOf(DeliveryOutcome.Skipped(reason)))
            )
            assertEquals(
                "Skipped($reason) 的通报到位判定",
                counts, dispatcher.emit(event()).anyConfirmedSent()
            )
        }
    }


    /** 重试完仍然失败 → false（原语义：通知没发出去就不关网）。 */
    @Test
    fun `failed confirmable channel is not reported`() = runBlocking {
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(
            FakeChannel(MAIL, outcomes = listOf(DeliveryOutcome.Failed("ConnectException", retryable = true)))
        )

        assertFalse(dispatcher.emit(event()).anyConfirmedSent())
    }

    /** 一个可确认渠道都没参与（只投了 push）→ false，不是"没有反对意见就通过"。 */
    @Test
    fun `no confirmable channel involved is not reported`() = runBlocking {
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(FakeChannel.pushLike())
        dispatcher.register(FakeChannel(MAIL))

        val results = dispatcher.emit(event(channels = setOf(PushChannel.ID)))

        assertFalse(results.anyConfirmedSent())
    }

    // ══════════ 渠道限定 ══════════

    /**
     * `channels = {push}` → 只投 push，mail **一次都不被碰到**。
     *
     * 这条是告警聚合更新的既有语义（同 type+level 只累加 count 时要推送、不该再发一封邮件），
     * 也是设备短信"邮件与推送各自 emit"的基础。
     */
    @Test
    fun `channel restriction delivers to push only`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL, recordsHistory = true)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)

        val results = dispatcher.emit(event(channels = setOf(PushChannel.ID)))

        assertEquals(DeliveryOutcome.Sent, results[PushChannel.ID])
        assertFalse("被限定掉的渠道不该出现在结果里", results.containsKey(MAIL))
        assertEquals(1, push.deliverCalls)
        assertEquals("限定 push 时邮件渠道一次都不该被调用", 0, mail.deliverCalls)
        assertTrue("没投递就不该有记录", mail.history.isEmpty())
    }

    /** `channels = null` → 投给全部已注册渠道。 */
    @Test
    fun `null channels delivers to every registered channel`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)

        val results = dispatcher.emit(event(channels = null))

        assertEquals(2, results.size)
        assertEquals(1, push.deliverCalls)
        assertEquals(1, mail.deliverCalls)
    }

    // ══════════ 渠道排除（exclude） ══════════

    /**
     * `exclude = {push}` → 其余渠道全投，push **不投、不产生 Skipped、不进结果**。
     *
     * 「不产生 Skipped」是刻意的：被排除的渠道不是"没发出去"，而是"这条投递路径不负责它"
     * （新短信与电池的推送另有触发源）。记成 `Skipped` 会在排查时被读成漏了一条通知，
     * 还会把它算进 [DeliveryReport.anyConfirmedSent] 的候选里。
     */
    @Test
    fun `exclude skips the listed channel without a skipped outcome`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL, recordsHistory = true)
        val webhook = FakeChannel(WEBHOOK)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)
        dispatcher.register(webhook)

        val results = dispatcher.emit(event(exclude = setOf(PushChannel.ID)))

        assertFalse("被排除的渠道不该出现在结果里", results.containsKey(PushChannel.ID))
        assertEquals("被排除的渠道一次都不该被调用", 0, push.deliverCalls)
        assertEquals(DeliveryOutcome.Sent, results[MAIL])
        assertEquals(DeliveryOutcome.Sent, results[WEBHOOK])
        assertEquals(2, results.size)
    }

    /** `exclude` 为空集 = 不排除任何渠道（与 null 同义，别让空集变成"全排除"）。 */
    @Test
    fun `empty exclude excludes nothing`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)

        assertEquals(2, dispatcher.emit(event(exclude = emptySet())).size)
    }

    /** 认不出的渠道 id 放在 `exclude` 里不影响任何人（黑名单是"别投给谁"，不是"必须存在谁"）。 */
    @Test
    fun `unknown id in exclude changes nothing`() = runBlocking {
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        assertEquals(DeliveryOutcome.Sent, dispatcher.emit(event(exclude = setOf("nope")))[MAIL])
    }

    /**
     * 同时给了 `channels` 与 `exclude` → **以 `channels` 为准**。
     *
     * 以白名单为准是因为它语义更窄：多投一条通知（尤其是花钱的短信渠道）比少投更难收场。
     * 这一条只钉**投递行为**；"必须同时打一条 WARN、不能静默"由
     * [NotificationDispatcherWarnLogTest] 钉（那条要读 [AppLogger] 的内存缓冲，
     * 得先把日志开关打开，所以单独放在一个 Robolectric 用例里）。
     */
    @Test
    fun `channels wins over exclude`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)

        val results = dispatcher.emit(
            event(channels = setOf(PushChannel.ID), exclude = setOf(PushChannel.ID))
        )

        assertEquals("以 channels 为准：只投 push", 1, results.size)
        assertEquals(DeliveryOutcome.Sent, results[PushChannel.ID])
        assertEquals("exclude 被忽略，不能把 push 也排掉", 1, push.deliverCalls)
        assertEquals(0, mail.deliverCalls)
    }

    // ══════════ 触发源口径（哪条事件投给哪几条渠道） ══════════

    /**
     * **设备新短信 / 验证码**（`SmsForwardController.forwardSms`）：投 mail + webhook +
     * local_sms，**不投 push**。
     *
     * 这条是 2026-09-10 那个假开关的回归线：此前写死 `channels = {mail}`，用户在 Webhook /
     * 本机短信里勾了「短信正文」「验证码」永远不触发。而 push 必须继续排除 ——
     * 推送侧由 `DataScheduler` 用自己的去重键（"每轮只推最新一条"）单独 emit，
     * 两边同时投会把同一条短信推两遍。
     */
    @Test
    fun `new sms event reaches every channel except push`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL, recordsHistory = true)
        val webhook = FakeChannel(WEBHOOK)
        val localSms = FakeChannel(LOCAL_SMS, recordsHistory = true)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)
        dispatcher.register(webhook)
        dispatcher.register(localSms)

        // 与 SmsForwardController.forwardSms 的口径一致：scene = sms / verification，level = INFO。
        for (scene in listOf(NotifyScenes.SMS, NotifyScenes.VERIFICATION)) {
            val results = dispatcher.emit(
                event(scene = scene, level = NotifyLevel.INFO, exclude = setOf(PushChannel.ID))
            )
            assertEquals("$scene 应投给除 push 之外的三条渠道", 3, results.size)
            assertEquals(DeliveryOutcome.Sent, results[MAIL])
            assertEquals(DeliveryOutcome.Sent, results[WEBHOOK])
            assertEquals(DeliveryOutcome.Sent, results[LOCAL_SMS])
            assertFalse("$scene 不能进推送（会与 DataScheduler 重复）", results.containsKey(PushChannel.ID))
        }
        assertEquals(0, push.deliverCalls)
    }

    /**
     * **电池事件**（`BackendService.emitBattery`）：同样是"除 push 之外都投"。
     *
     * 排除 push 是核实过的事实约束：app 的 `NotifyService` 只对 `sms`/`verification` 与
     * `download`/`tunnel` 有分流分支，`type = "battery"` 会掉进 `maybeNotifyNewAlerts`
     * 被当成一条阈值告警（`id = 0`）并推进告警游标，把随后到达的真告警吞掉。
     * 这条断言红了就说明有人把电池放进了推送，而 app 侧那套镜像还没补。
     */
    @Test
    fun `battery event reaches every channel except push`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL, recordsHistory = true)
        val webhook = FakeChannel(WEBHOOK)
        val localSms = FakeChannel(LOCAL_SMS, recordsHistory = true)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)
        dispatcher.register(webhook)
        dispatcher.register(localSms)

        val results = dispatcher.emit(
            event(scene = NotifyScenes.BATTERY, exclude = setOf(PushChannel.ID))
        )

        assertEquals(3, results.size)
        assertEquals(DeliveryOutcome.Sent, results[MAIL])
        assertEquals(DeliveryOutcome.Sent, results[WEBHOOK])
        assertEquals(DeliveryOutcome.Sent, results[LOCAL_SMS])
        assertFalse("电池事件不能进推送", results.containsKey(PushChannel.ID))
        assertEquals(0, push.deliverCalls)
    }

    /**
     * **告警聚合更新**与**各渠道的发送测试**继续用白名单，语义不变。
     *
     * 聚合只推送、不发邮件（稳态超标否则会变成邮件轰炸）；各渠道的发送测试端点只走被测的
     * 那一条链路（投给别人等于按一次测试收到三种通知）。这两处**不该**换成 exclude ——
     * 它们要表达的就是"只给指定的这一个"。
     */
    @Test
    fun `whitelist only paths stay single channel`() = runBlocking {
        val push = FakeChannel.pushLike()
        val mail = FakeChannel(MAIL)
        val webhook = FakeChannel(WEBHOOK)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(push)
        dispatcher.register(mail)
        dispatcher.register(webhook)

        // 告警聚合更新
        assertEquals(setOf(PushChannel.ID), dispatcher.emit(event(channels = setOf(PushChannel.ID))).keys)
        // Webhook 的发送测试
        assertEquals(
            setOf(WEBHOOK),
            dispatcher.emit(event(scene = NotifyScenes.TEST, manual = true, channels = setOf(WEBHOOK))).keys
        )
    }



    // ══════════ 有界重试 ══════════

    /**
     * 第 1 次可重试失败、第 2 次成功 → `emit` 返回 [DeliveryOutcome.Sent]，
     * 且投递记录**只有一条成功**（不是"1 失败 + 1 成功"）。
     *
     * 用户看到一条失败记录就会去查一个并不存在的问题，这是重试要解决的核心场景。
     */
    @Test
    fun `retryable failure then success reports a single success`() = runBlocking {
        val mail = FakeChannel(
            MAIL, recordsHistory = true,
            outcomes = listOf(
                DeliveryOutcome.Failed("SocketTimeoutException: read timed out", retryable = true),
                DeliveryOutcome.Sent
            )
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertEquals(DeliveryOutcome.Sent, results[MAIL])

        assertEquals(2, mail.deliverCalls)
        assertEquals("整轮只写一条记录", 1, mail.history.size)
        assertEquals(DeliveryOutcome.Sent, mail.history.single().first)
        assertEquals("记录里要带得出真实尝试次数", 2, mail.history.single().second)
        assertEquals("整轮只申请一次 WakeLock（withRound 只包一次）", 1, mail.rounds)
    }

    /** 不可重试失败 → **只尝试 1 次**（账号密码错重试三次结果一样，只会多两条失败记录）。 */
    @Test
    fun `non retryable failure is attempted once`() = runBlocking {
        val mail = FakeChannel(
            MAIL, recordsHistory = true,
            outcomes = listOf(DeliveryOutcome.Failed("AuthenticationFailedException: 535", retryable = false))
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        val results = dispatcher.emit(event())

        assertTrue(results[MAIL] is DeliveryOutcome.Failed)

        assertEquals("不可重试的错误不该连打三次", 1, mail.deliverCalls)
        assertEquals(1, mail.history.single().second)
    }

    /** 一直可重试失败 → 停在上限，不会无限重试。 */
    @Test
    fun `retryable failures stop at the attempt cap`() = runBlocking {
        val mail = FakeChannel(
            MAIL,
            outcomes = listOf(DeliveryOutcome.Failed("ConnectException", retryable = true))
        )
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        dispatcher.emit(event())

        assertEquals(FAST_RETRY.maxAttempts, mail.deliverCalls)
    }

    /** 退避序列比上限短时用最后一个值兜住，不能越界崩掉一次投递。 */
    @Test
    fun `backoff list shorter than the attempt cap still completes`() = runBlocking {
        val mail = FakeChannel(
            MAIL,
            outcomes = listOf(DeliveryOutcome.Failed("ConnectException", retryable = true))
        )

        val dispatcher = NotificationDispatcher(
            retry = RetryPolicy(maxAttempts = 4, backoffMs = listOf(0L))
        )
        dispatcher.register(mail)

        dispatcher.emit(event())

        assertEquals(4, mail.deliverCalls)
    }

    // ══════════ 渠道隔离 ══════════

    /** 一个渠道抛异常不影响另一个渠道（异常转 Failed，且按不可重试处理）。 */
    @Test
    fun `an exploding channel does not affect the others`() = runBlocking {
        val bad = FakeChannel("bad", throws = { IllegalStateException("boom") })
        val good = FakeChannel("good")
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(bad)
        dispatcher.register(good)

        val results = dispatcher.emit(event())

        val failure = results["bad"]
        assertTrue(failure is DeliveryOutcome.Failed)
        assertFalse("抛出来的异常拿不准是否瞬时错，按不可重试处理", (failure as DeliveryOutcome.Failed).retryable)
        assertEquals("不可重试 → 只试一次", 1, bad.deliverCalls)
        assertEquals("前一个渠道炸了不影响后一个", DeliveryOutcome.Sent, results["good"])
        assertEquals(1, good.deliverCalls)
    }

    /**
     * [CancellationException] 原样穿透，**不能**被转成 `Failed`。
     *
     * 本仓纪律：取消不是投递失败。吞掉它会让"停止服务"变成一堆假失败记录。
     */
    @Test
    fun `cancellation propagates instead of becoming a failure`() {
        val mail = FakeChannel(MAIL, recordsHistory = true, throws = { CancellationException("service stopping") })

        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)

        try {
            runBlocking { dispatcher.emit(event()) }
            fail("CancellationException 应当原样抛出")
        } catch (e: CancellationException) {
            // 预期
        }
        assertEquals("取消不该触发重试", 1, mail.deliverCalls)
        assertTrue("取消不是投递结果，不能写记录", mail.history.isEmpty())
    }

    // ══════════ 注册表 ══════════

    /** 同 id 重复注册替换旧的：装配跑第二遍时不至于投两份。 */
    @Test
    fun `re-registering the same id replaces the old channel`() = runBlocking {
        val first = FakeChannel(MAIL)
        val second = FakeChannel(MAIL)

        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(first)
        dispatcher.register(second)

        dispatcher.emit(event())

        assertEquals(0, first.deliverCalls)
        assertEquals(1, second.deliverCalls)
    }

    /** 停机后 emit 是空操作：组件都停了，不能再往里投。 */
    @Test
    fun `shutdown clears the registry`() = runBlocking {
        val mail = FakeChannel(MAIL)

        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(mail)
        dispatcher.shutdown()

        assertTrue(dispatcher.emit(event()).isEmpty())
        assertEquals(0, mail.deliverCalls)
    }

    // ══════════ 配额的 check-then-act 是原子的 ══════════

    /**
     * 会**真的记账**的假渠道，带一个可控的"投递中"暂停点。
     *
     * 形状照三条真渠道：`rules.sentToday()` 读计数器、`deliver` 拿到结论之后自己 `consume`
     * （记账在渠道，判定在分发器，见 [ChannelRules]）。计数器用 [AtomicInteger] 是因为
     * 这个用例本来就要让两个协程并发进来。
     *
     * @param entered 进到 [deliver] 里就 complete —— 用例靠它知道"第一条已经在飞了"。
     * @param release [deliver] 会一直等它 —— 用例靠它把两条投递的时序卡在扣配额之前重叠。
     */
    private class QuotaChannel(
        override val id: String = LOCAL_SMS,
        private val limit: Int,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>
    ) : NotifyChannel {
        val used = AtomicInteger(0)
        val deliveries = AtomicInteger(0)

        override val recordsHistory: Boolean = false
        override val respectsMasterGate: Boolean = false
        override val hasDeliveryConfirmation: Boolean = true
        override val rules: ChannelRules = object : ChannelRules {
            override fun minLevel(): NotifyLevel = NotifyLevel.INFO
            override fun dailyLimit(): Int = limit
            override fun sentToday(): Int = used.get()
        }

        override fun isConfigured(): Boolean = true
        override fun accepts(scene: String): Boolean = true

        override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
            deliveries.incrementAndGet()
            entered.complete(Unit)
            release.await()
            used.incrementAndGet()
            return DeliveryAttempt(DeliveryOutcome.Sent)
        }
    }

    /**
     * **配额只剩 1 条时，两条并发事件只能放行一条。**
     *
     * 这是账单防线的回归线：`rules.hasQuota()`（分发器）与 `store.consume()`（渠道）是两次
     * 独立加锁，中间隔着一次真实发信。同一个 notifier 供 5 个触发源 + 2 个 `/test` 共用，
     * 没有那把按渠道分的锁时两边都会读到"还有余量"→ 两边都发 → 在本机短信这条按条计费的
     * 渠道上就是**多花的一条钱**，而用户设的每日上限完全没起作用。
     *
     * 时序是卡死的，不靠 sleep 赌：第一条进到 deliver 里（配额还没扣）之后才放第二条进来，
     * 第二条此刻若能越过配额判定就一定会发出去。
     */
    @Test
    fun `配额剩 1 时两条并发只放行一条`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val channel = QuotaChannel(limit = 1, entered = entered, release = release)
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
        dispatcher.register(channel)

        val first = async { dispatcher.emit(event()) }
        // 第一条已经在 deliver 里，此时它还没 consume —— 正是那个危险窗口。
        entered.await()
        val second = async { dispatcher.emit(event()) }
        // 让第二条跑到它自己能跑到的地方为止（有锁 = 停在锁上；没锁 = 一路冲到 deliver）。
        yield()
        release.complete(Unit)

        val outcomes = listOf(first.await()[LOCAL_SMS], second.await()[LOCAL_SMS])

        assertEquals("有且只有一条该发出去", 1, outcomes.count { it is DeliveryOutcome.Sent })
        assertEquals(
            "另一条必须是配额用尽（不是失败，也不是静默丢弃）",
            listOf(DeliveryOutcome.Skipped(SkipReason.QUOTA_EXCEEDED)),
            outcomes.filter { it !is DeliveryOutcome.Sent }
        )
        assertEquals("deliver 只该被调一次 —— 调两次就是真发了两条", 1, channel.deliveries.get())
        assertEquals("配额只该被扣一条", 1, channel.used.get())
    }

    // ══════════ 诊断与 deliver 同进同出（`/test` 的 attempted_at 判据） ══════════

    /**
     * **`diagnosticsOf(id)` 非 null ⇔ 本轮走到过 `deliver()` 且它返回了。**
     *
     * 两个 `/test` 端点把它翻成线上契约「`attempted_at` 出现 ⇔ 本轮发起过投递」，
     * 客户端再据此决定要不要渲染「HTTP 状态码」「计入今日配额」那几行。在本机短信这条
     * 按条计费的渠道上判错一次，用户就会以为"没发出去"而再按一次测试 —— 多花一条话费。
     *
     * 三种入口逐个钉住：跳过（一次都没投）、投过（Sent / Failed）、渠道没注册。
     */
    @Test
    fun `诊断与 deliver 同进同出`() = runBlocking {
        // ① 跳过：一次都没投 → 不能有诊断（有就说明回读到了别人那一次的结果）
        val skipped = FakeChannel(MAIL, configured = false, diagnostics = DIAGNOSTICS)
        val onSkip = NotificationDispatcher(retry = FAST_RETRY)
        onSkip.register(skipped)
        val skipReport = onSkip.emit(event())

        assertEquals(DeliveryOutcome.Skipped(SkipReason.NOT_CONFIGURED), skipReport[MAIL])
        assertEquals(0, skipped.deliverCalls)
        assertNull("一次都没投却带回了诊断", skipReport.diagnosticsOf(MAIL))

        // ② 投过：无论成功还是失败，诊断都必须跟着这一次的结果回来
        for (outcome in listOf(DeliveryOutcome.Sent, DeliveryOutcome.Failed("boom", retryable = false))) {
            val delivered = FakeChannel(MAIL, outcomes = listOf(outcome), diagnostics = DIAGNOSTICS)
            val dispatcher = NotificationDispatcher(retry = FAST_RETRY)
            dispatcher.register(delivered)
            val report = dispatcher.emit(event())

            assertEquals(outcome, report[MAIL])
            assertEquals(1, delivered.deliverCalls)
            assertEquals("outcome=$outcome 时诊断丢了", DIAGNOSTICS, report.diagnosticsOf(MAIL))
        }

        // ③ 渠道没注册：结果与诊断**一起**缺，不会出现"没结果却有诊断"
        val empty = NotificationDispatcher(retry = FAST_RETRY).emit(event())
        assertNull(empty[MAIL])
        assertNull(empty.diagnosticsOf(MAIL))
    }

    /**
     * 整轮预算到点 → **不再开始新的一次尝试**，但仍然带回**刚才那次**的结果与诊断。
     *
     * 这条是上面那个不变量最容易破的一处：预算曾经是 `withTimeoutOrNull` 包住整段重试，
     * 于是它会把在飞的那一次 `deliver` 拦腰砍掉 —— 结果既拿不到诊断（客户端于是以为
     * "没投过"），本机短信那侧还可能"短信已经发出去、配额却没扣"。
     * 判据改成尝试之间的截止时间之后，两种后果一起消失。
     */
    @Test
    fun `预算到点不再重试但仍带回上一次尝试的诊断`() = runBlocking {
        val mail = FakeChannel(
            MAIL,
            outcomes = listOf(DeliveryOutcome.Failed("timeout", retryable = true)),
            diagnostics = DIAGNOSTICS,
            deliverDelayMs = SLOW_DELIVER_MS
        )
        // 预算比单次 deliver 还短：第一次照常跑完（不拦腰砍），之后不该再开第二次
        val dispatcher = NotificationDispatcher(retry = FAST_RETRY, roundBudgetMs = TIGHT_BUDGET_MS)
        dispatcher.register(mail)

        val report = dispatcher.emit(event())

        assertEquals("预算到点后不该再重试", 1, mail.deliverCalls)
        assertEquals(
            "结果要是那次真实的失败，不是合成的「预算用尽」",
            DeliveryOutcome.Failed("timeout", retryable = true),
            report[MAIL]
        )
        assertEquals("投过就必须带回诊断", DIAGNOSTICS, report.diagnosticsOf(MAIL))
    }
}


