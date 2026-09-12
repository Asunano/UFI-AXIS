package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.controller.notify.LocalSmsChannel
import com.ufi_axis_core.controller.notify.LocalSmsConfig
import com.ufi_axis_core.controller.notify.LocalSmsConfigStore
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryAttempt
import com.ufi_axis_core.notify.DeliveryOutcome

import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.hasQuota
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LocalSmsChannel] 的**渠道级契约 + 三道刹车**：四个常量、`accepts` / `isConfigured`、
 * 级别门槛、每日配额（含跨天重置）。
 *
 * 为什么在 `:core:api` 而不是 `:core:controller`：这几条必须拿**真实例**来问，而渠道要
 * `LocalSmsConfigStore`（`Context` → SharedPreferences）与一个 `GoformSmsClient`
 * —— `:core:controller` 的单测源集只有 junit，构造不出 `Context` 也没有 mockk。
 * 这个模块两样都有（同 [WebhookChannelContractTest]），所以放这里，
 * **不给生产代码加参数注入/接口抽象**去迎合测试。
 * 纯判定（裁剪 / 号码 / 结论映射）的用例在 `:core:controller` 的 `LocalSmsDeliveryTest`。
 *
 * ## 这条渠道的测试为什么必须验"**没有**真的发送"
 *
 * 它按条计费。"跳过了"与"发出去了但失败了"在 `DeliveryOutcome` 上都不是 `Sent`，
 * 但在账单上差一条短信 —— 所以凡是"本不该发"的用例都用 `coVerify` 钉住 `sendSms`
 * 被调了几次。三道刹车的**判定**自 2026-09-10 起在 `NotificationDispatcher`
 * （见 `ChannelRules`），本类验的是本渠道交出去的取值与记账。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSmsChannelContractTest {

    private lateinit var store: LocalSmsConfigStore
    private lateinit var smsClient: GoformSmsClient
    private lateinit var channel: LocalSmsChannel

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = LocalSmsConfigStore(context)
        smsClient = mockk()
        channel = LocalSmsChannel(store = store, smsClient = smsClient)
    }

    /** 一次性把配置写进真 prefs（`accepts` / `isConfigured` / 三道刹车都从那里读）。 */
    private fun save(
        enabled: Boolean = true,
        number: String = "13800138000",
        minLevel: NotifyLevel = NotifyLevel.CRITICAL,
        dailyLimit: Int = LocalSmsConfig.DEFAULT_DAILY_LIMIT,
        scenes: Set<String> = emptySet()
    ) = store.save(
        LocalSmsConfig(
            enabled = enabled,
            targetNumber = number,
            minLevel = minLevel,
            dailyLimit = dailyLimit,
            scenes = scenes
        )
    )

    private fun event(level: NotifyLevel = NotifyLevel.CRITICAL) = NotifyEvent(
        scene = NotifyScenes.TRAFFIC_80,
        level = level,
        title = "移动数据已关闭",
        body = "套餐用量达到上限"
    )

    private fun stubSend(verdict: GoformSmsClient.SendVerdict, detail: String = "d") {
        coEvery { smsClient.sendSms(any(), any()) } returns
            GoformSmsClient.SendOutcome(verdict, detail)
    }

    // ══════════════════ 渠道级常量 ══════════════════

    /**
     * 渠道 id 是 `local_sms`，**不是** `sms`。
     *
     * `sms` 是**场景** id（`NotifyScenes.SMS`，"设备收到新短信"）。两套字符串撞名之后
     * `channels = setOf("sms")` 与 `scenes.contains("sms")` 在阅读时会混成一件事。
     */
    @Test
    fun `渠道 id 是 local_sms 且不与 sms 场景撞名`() {
        assertEquals("local_sms", channel.id)
        assertEquals(LocalSmsChannel.ID, channel.id)
        assertFalse("渠道 id 不能等于场景 id", channel.id == NotifyScenes.SMS)
    }

    /** 受总闸约束：短信发出去就收不回来，还花了钱。用户关了总开关就是不想收。 */
    @Test
    fun `本机短信受全局总闸约束`() {
        assertTrue(channel.respectsMasterGate)
    }

    /** 有送达确认：信箱 tag=2 是设备确认已发出 —— 自动关网的"通报到位"判定认它。 */
    @Test
    fun `本机短信有送达确认`() {
        assertTrue(channel.hasDeliveryConfirmation)
    }

    /** 留投递记录：这条渠道花钱，"今天到底发出去几条"必须查得到。 */
    @Test
    fun `本机短信留投递记录`() {
        assertTrue(channel.recordsHistory)
    }

    // ══════════════════ 配置齐不齐（经过真 prefs 一圈） ══════════════════

    @Test
    fun `开关关闭时渠道判定未配置齐全`() {
        save(enabled = false)
        assertFalse(channel.isConfigured())
    }

    @Test
    fun `号码为空或非法时渠道判定未配置齐全`() {
        save(number = "")
        assertFalse("空号码 = 还没配完", channel.isConfigured())

        save(number = "12")
        assertFalse("太短", channel.isConfigured())

        save(number = "138abc0000")
        assertFalse("含字母", channel.isConfigured())
    }

    @Test
    fun `启用且号码合法时渠道判定配置齐全`() {
        save(number = "138-0013-8000")
        assertTrue("带分隔符的写法也算合法", channel.isConfigured())
    }

    // ══════════════════ 场景勾选 ══════════════════

    @Test
    fun `accepts 只认勾选集里的场景`() {
        save(scenes = setOf(NotifyScenes.CONNECTIVITY, NotifyScenes.TRAFFIC_80))

        assertTrue(channel.accepts(NotifyScenes.CONNECTIVITY))
        assertTrue(channel.accepts(NotifyScenes.TRAFFIC_80))
        assertFalse(channel.accepts(NotifyScenes.SMS))
        assertFalse(channel.accepts(NotifyScenes.DOWNLOAD))
    }

    /** 空勾选集 = 一个场景都不投（默认就是空集，防"空集当全选"那种便利写法）。 */
    @Test
    fun `空勾选集时任何场景都不投`() {
        save(scenes = emptySet())
        for (scene in NotifyScenes.ALL) {
            assertFalse("场景 $scene 在空勾选集下被放行了", channel.accepts(scene))
        }
    }

    // ══════════════════ 刹车 A：最低级别（取值口，判定在分发器） ══════════════════
    //
    // 2026-09-10「第二步：规则同构」：级别与配额的**判定**从 deliver() 搬到了
    // NotificationDispatcher（三条渠道共用一份顺序，见 ChannelRules），所以这里验的是
    // 本渠道把取值交出去交对了没有。"级别不够就不发 / 配额用尽就不发"的用例在
    // :core:common 的 NotificationDispatcherTest。

    /** [LocalSmsChannel.rules] 现读 prefs：改完配置立刻生效，不存在"改了要重启"。 */
    @Test
    fun `rules 现读配置里的级别门槛与每日上限`() {
        save(minLevel = NotifyLevel.CRITICAL, dailyLimit = 5)
        val rules = channel.rules!!
        assertEquals(NotifyLevel.CRITICAL, rules.minLevel())
        assertEquals(5, rules.dailyLimit())

        // 不重建渠道、只改存储 —— 缓存过取值的实现会在这里红
        save(minLevel = NotifyLevel.INFO, dailyLimit = 20)
        assertEquals(NotifyLevel.INFO, rules.minLevel())
        assertEquals(20, rules.dailyLimit())
    }

    @Test
    fun `级别达到门槛时正常投递`() = runBlocking {
        save(minLevel = NotifyLevel.WARNING)
        stubSend(GoformSmsClient.SendVerdict.SENT)

        assertEquals(DeliveryOutcome.Sent, channel.deliver(event(NotifyLevel.WARNING)).outcome)

        coVerify(exactly = 1) { smsClient.sendSms(any(), any()) }
    }

    // ══════════════════ 刹车 B：每日配额（记账在渠道） ══════════════════

    /** 成功一条 → 今日已用 +1、剩余 -1。UI 上那行"今日 N/M"读的就是这两个数。 */
    @Test
    fun `受理一条就计一条配额`() = runBlocking {
        save(dailyLimit = 3)
        stubSend(GoformSmsClient.SendVerdict.SENT)

        channel.deliver(event())

        assertEquals(1, store.sentToday())
        assertEquals(2, store.quotaRemaining(3))
        assertEquals("rules 读的是同一个计数器", 1, channel.rules!!.sentToday())
    }

    /**
     * 配额用尽时 [ChannelRules.hasQuota] 报 false —— 分发器据此回 `Skipped(QUOTA_EXCEEDED)`
     * 并且**不调 deliver**（那条断言在 `NotificationDispatcherTest`）。
     *
     * 这里验的是账单防线的**取值侧**：真发满 2 条之后这个布尔必须翻。
     */
    @Test
    fun `配额耗尽后 hasQuota 报 false`() = runBlocking {
        save(dailyLimit = 2)
        stubSend(GoformSmsClient.SendVerdict.SENT)

        channel.deliver(event())
        assertTrue("发了 1/2，还有余量", channel.rules!!.hasQuota())
        channel.deliver(event())

        assertFalse("2/2 已满", channel.rules!!.hasQuota())
        coVerify(exactly = 2) { smsClient.sendSms(any(), any()) }
        assertEquals(2, store.sentToday())
        assertEquals(0, store.quotaRemaining(2))
    }

    /**
     * **按本地日期跨天重置**，不是"24 小时滑动窗口"。
     *
     * 用户理解的是"今天还剩几条"；滑动窗口会出现"明明一整天没发却说超额"
     * （昨天 23:50 那条还在窗口里）。
     */
    @Test
    fun `配额按本地日期跨天重置`() {
        val today = "2026-09-09"
        val tomorrow = "2026-09-10"

        assertEquals(1, store.consume(today))
        assertEquals(2, store.consume(today))
        assertEquals(2, store.sentToday(today))

        assertEquals("换一天就归零", 0, store.sentToday(tomorrow))
        assertEquals(5, store.quotaRemaining(5, tomorrow))
        assertTrue(store.hasQuota(2, tomorrow))
        assertFalse("同一天里 2/2 已满", store.hasQuota(2, today))

        // 新的一天从 1 重新数，不是从昨天的 2 接着加
        assertEquals(1, store.consume(tomorrow))
        assertEquals(0, store.sentToday(today))
    }

    /** 用户把上限从大调小之后剩余数不能变负（界面上"今日 5/2"已经够怪了，别再来个 -3）。 */
    @Test
    fun `上限调小后剩余配额钳到 0`() {
        repeat(5) { store.consume() }
        assertEquals(0, store.quotaRemaining(2))
        assertFalse(store.hasQuota(2))
    }

    /** 设备**明确拒收**（REJECTED）不计配额 —— 那一档是可重试的，计了就会一次重试扣三条。 */
    @Test
    fun `设备未受理时不计配额且判为可重试`() = runBlocking {
        save()
        stubSend(GoformSmsClient.SendVerdict.REJECTED, "设备明确拒收")

        val outcome = channel.deliver(event()).outcome

        assertTrue(outcome is DeliveryOutcome.Failed)
        assertTrue("会话抖动值得再试一次", (outcome as DeliveryOutcome.Failed).retryable)
        assertEquals("没受理就没花钱，不该扣配额", 0, store.sentToday())
    }

    /**
     * 拿不到设备表态（NO_RESPONSE）**计配额、不重试**。
     *
     * 这一档 2026-09-09 从 REJECTED 里拆出来：请求没走完时无法排除"短信其实已经发出去了"，
     * 重试就是真发第二条、真花第二笔钱；而配额要计，否则漏计会让实发条数超过用户设的上限。
     */
    @Test
    fun `拿不到设备表态时计配额且不重试`() = runBlocking {
        save()
        stubSend(GoformSmsClient.SendVerdict.NO_RESPONSE, "resp=null")

        val attempt = channel.deliver(event())
        val outcome = attempt.outcome

        assertTrue(outcome is DeliveryOutcome.Failed)
        assertFalse("重试可能是第二笔话费", (outcome as DeliveryOutcome.Failed).retryable)
        assertEquals("可能已经发出去了，宁可多算一条", 1, store.sentToday())
        assertEquals("NO_RESPONSE", (attempt.diagnostics as LocalSmsChannel.Attempt).verdict)
    }

    /** 信箱 tag=3（设备侧发送失败）**计配额、不重试** —— 它进过发送队列，且重试改不了结果。 */
    @Test
    fun `设备侧发送失败计配额且不重试`() = runBlocking {
        save()
        stubSend(GoformSmsClient.SendVerdict.FAILED, "信箱 tag=3")

        val outcome = channel.deliver(event()).outcome

        assertTrue(outcome is DeliveryOutcome.Failed)
        assertFalse((outcome as DeliveryOutcome.Failed).retryable)
        assertEquals(1, store.sentToday())
    }

    // ══════════════════ /test 端点要读的那份诊断 ══════════════════

    /**
     * `POST /api/notify/sms/test` 的响应要回"这次算不算进配额 / 还剩几条" ——
     * [DeliveryOutcome] 里没有这两样，所以渠道把 [LocalSmsChannel.Attempt] 挂在
     * [DeliveryAttempt.diagnostics] 上**随本次结果一起**交出来。
     *
     * 刻意不再用"渠道上的可变快照 + 路由事后回读"：那种写法在 `Skipped`（一次都没投）
     * 与并发投递两种时序下都会让 `/test` 回上一次的数字（见 `DeliveryAttempt` 的注释）。
     */
    @Test
    fun `投递结果里带出本次的配额结算`() = runBlocking {
        save(dailyLimit = 5)
        stubSend(GoformSmsClient.SendVerdict.SENT)

        val attempt = channel.deliver(event())
        val diagnostics = attempt.diagnostics as LocalSmsChannel.Attempt

        assertEquals(DeliveryOutcome.Sent, attempt.outcome)
        assertEquals("SENT", diagnostics.verdict)
        assertTrue(diagnostics.countedTowardQuota)
        assertEquals(1, diagnostics.sentToday)
        assertEquals(4, diagnostics.quotaRemaining)
    }


    // ══════════════════ 默认值 ══════════════════

    /**
     * 默认值必须是**最保守**的那一端：关着、没号码、只投 CRITICAL、一天 5 条、一个场景都不勾。
     *
     * 这条防的是"默认值被顺手放宽"——那种改动不会让任何测试变红，只会让用户在升级后
     * 某天收到一堆短信账单。
     */
    @Test
    fun `默认配置是最保守的一端`() {
        val d = LocalSmsConfig()

        assertFalse(d.enabled)
        assertEquals("", d.targetNumber)
        assertEquals(NotifyLevel.CRITICAL, d.minLevel)
        assertEquals(5, d.dailyLimit)
        assertEquals(emptySet<String>(), d.scenes)
        assertTrue("短信会半夜把人叫醒，默认必须跟随免打扰", d.respectDnd)
    }
}
