package com.ufi_axis_core.controller.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SmsForwardWindow] 的窗口选取：漏发回归线、批次上限、水位推进。
 *
 * 为什么测这个对象而不测 `BackendService.forwardLatestSmsIfNew`：那一侧要 `Context`、
 * ContentResolver、SMTP 与 WakeLock，`:core:controller` 的单测源集只有 junit，一样都构造不出来
 * （分法同 [MailDelivery] / `LocalSmsDelivery`）。
 *
 * 本文件第一条用例就是 2026-09-09 修掉的那个 bug 的回归线：**真实来信与本机发出的短信
 * 几乎同时落库、来信在先**时，旧实现（只看最新一条）会把那封邮件永久跳过。
 */
class SmsForwardWindowTest {

    private companion object {
        const val NOW = 1_700_000_000_000L

        /** 与 `BackendService.SMS_MAX_AGE_MS` 同口径（24h）。 */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        fun received(id: Long, ageMs: Long = 0L) =
            SmsController.SmsMessage(id, "+8610086", "body-$id", NOW - ageMs, false, "received")

        fun sent(id: Long, ageMs: Long = 0L) =
            SmsController.SmsMessage(id, "+8613800138000", "sent-$id", NOW - ageMs, true, "sent")

        fun plan(
            messages: List<SmsController.SmsMessage>,
            lastForwardedId: Long,
            maxBatch: Int = SmsForwardWindow.MAX_BATCH_PER_SCAN,
        ) = SmsForwardWindow.plan(messages, lastForwardedId, NOW, MAX_AGE_MS, maxBatch)
    }

    // ══════════════════ 漏发回归线 ══════════════════

    /**
     * **来信在前、自己发的在后**：两条都在窗口里，来信必须照发。
     *
     * 旧实现取 `getLatest()` 看到 #11（sent）就把水位推到 11 并返回，#10 那条真实来信
     * 从此永远不会被再看到 —— 邮件永久丢失。本机短信渠道每天最多发 5 条，
     * 这个时序会被反复撞上。
     */
    @Test
    fun `来信在前本机发出在后时来信不漏发`() {
        val result = plan(listOf(sent(11), received(10)), lastForwardedId = 9)

        assertEquals("那条来信必须进投递批次", listOf(10L), result.toForward.map { it.id })
        assertEquals("水位推到窗口最大 id（含自己发出的那条）", 11L, result.highWaterMark)
        assertEquals(0, result.skipped)
    }

    /** 设备自己发出的短信绝不投递（自激循环防护依赖这条，见 `LocalSmsChannel` 类头）。 */
    @Test
    fun `只投 received 方向`() {
        val result = plan(listOf(received(10), sent(11), sent(12)), lastForwardedId = 9)

        assertEquals(listOf(10L), result.toForward.map { it.id })
        assertEquals(12L, result.highWaterMark)
    }

    /** 多条来信按 id 升序投递：邮件到达顺序要和短信到达顺序一致。 */
    @Test
    fun `多条来信按 id 升序逐条投递`() {
        val result = plan(listOf(received(13), received(11), sent(12), received(14)), lastForwardedId = 10)

        assertEquals(listOf(11L, 13L, 14L), result.toForward.map { it.id })
        assertEquals(14L, result.highWaterMark)
    }

    // ══════════════════ 水位 ══════════════════

    /** 窗口里没有比水位更新的短信 → 什么都不做，水位原样返回（调用方据此直接退出）。 */
    @Test
    fun `没有新短信时水位不动`() {
        val result = plan(listOf(received(10), sent(9)), lastForwardedId = 10)

        assertTrue(result.toForward.isEmpty())
        assertEquals(10L, result.highWaterMark)
        assertEquals(0, result.skipped)
    }

    /**
     * 水位 -1 = 首次落基线：**一条都不发**，只把水位推到最大 id。
     * 否则第一次启用邮件就会把整个收件箱当成新短信发出去。
     */
    @Test
    fun `首次落基线不发信只推水位`() {
        val result = plan(listOf(received(7), received(8), sent(9)), lastForwardedId = -1)

        assertTrue("首次启用不许补发历史短信", result.toForward.isEmpty())
        assertEquals(9L, result.highWaterMark)
    }

    /** 超龄短信（换 id 体系 / 首次装机时收件箱里的旧信）不投递，但水位照样越过它。 */
    @Test
    fun `超龄短信不投递但水位照样推进`() {
        val old = received(11, ageMs = MAX_AGE_MS + 1)
        val fresh = received(12)

        val result = plan(listOf(old, fresh), lastForwardedId = 10)

        assertEquals(listOf(12L), result.toForward.map { it.id })
        assertEquals(12L, result.highWaterMark)
    }

    /** 刚好卡在新鲜度上限上的那条**要发**（边界含等号，别让 24h 整变成静默丢弃）。 */
    @Test
    fun `刚好等于新鲜度上限的短信仍然投递`() {
        val result = plan(listOf(received(11, ageMs = MAX_AGE_MS)), lastForwardedId = 10)

        assertEquals(listOf(11L), result.toForward.map { it.id })
    }

    // ══════════════════ 批次上限 ══════════════════

    /**
     * 上限之内全发。上限本身取 20 —— 首次启用或长时间离线后一次性发几十封邮件会被
     * SMTP 服务商判成异常发信。
     */
    @Test
    fun `恰好等于上限时全部投递`() {
        val msgs = (1L..SmsForwardWindow.MAX_BATCH_PER_SCAN).map { received(100 + it) }

        val result = plan(msgs, lastForwardedId = 100)

        assertEquals(SmsForwardWindow.MAX_BATCH_PER_SCAN, result.toForward.size)
        assertEquals(0, result.skipped)
    }

    /** 超上限时只留**最新的** N 条，水位仍推到最大 id，并把放弃条数报给调用方去打 WARN。 */
    @Test
    fun `超出上限时只处理最新的 N 条并报出跳过数`() {
        val total = 25
        val msgs = (1L..total).map { received(100 + it) }

        val result = plan(msgs, lastForwardedId = 100, maxBatch = 20)

        assertEquals(20, result.toForward.size)
        assertEquals("留的必须是最新那 20 条", 106L, result.toForward.first().id)
        assertEquals(125L, result.toForward.last().id)
        assertEquals("被放弃的 5 条要报出来，否则用户不知道漏了什么", 5, result.skipped)
        assertEquals("水位一律推到最大 id，不能让这 5 条下一轮又被扫一遍", 125L, result.highWaterMark)
    }

    /** 被上限截掉的只能是**来信**：sent 与超龄的那些本来就不进批次，不该算进 skipped。 */
    @Test
    fun `跳过数只统计真实来信`() {
        val msgs = buildList {
            addAll((1L..12L).map { received(100 + it) })
            addAll((13L..20L).map { sent(100 + it) })
        }

        val result = plan(msgs, lastForwardedId = 100, maxBatch = 10)

        assertEquals(10, result.toForward.size)
        assertEquals(2, result.skipped)
        assertEquals(120L, result.highWaterMark)
    }
}
