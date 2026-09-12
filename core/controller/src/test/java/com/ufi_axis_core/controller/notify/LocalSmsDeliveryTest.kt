package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.NotifyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalSmsDelivery] 的纯判定：正文裁剪、号码校验、级别门槛、固件结论映射。
 *
 * 为什么测这个对象而不测 [LocalSmsChannel]：渠道要 `LocalSmsConfigStore`（`Context` → prefs）
 * 与真实固件，`:core:controller` 的单测源集只有 junit，两样都构造不出来
 * （分法同 `WebhookDeliveryTest` / `MailDeliveryTest`）。
 * 渠道级常量、配额闸与"配额耗尽时一条都不发"那几条在 `:core:api` 的
 * `LocalSmsChannelContractTest` 里（那个模块有 Robolectric + mockk）。
 *
 * 这些用例每一条都对应一笔真实话费：裁剪错了会被固件拆成两条（两倍费用）、
 * 号码校验漏了会让每次投递都撞一次拒收、`tag=3` 判成可重试会把一条发不出去的短信连打三次。
 */
class LocalSmsDeliveryTest {

    private companion object {
        /** `【UFI-AXIS】` = 10 个 UTF-16 码元；再加标题后的全角冒号 = 固定开销 11。 */
        const val OVERHEAD = 11

        /** 单条预算，与被测常量同源（写死 70 会让改常量时这个测试仍然绿）。 */
        val BUDGET = LocalSmsDelivery.SINGLE_SMS_CHARS

        fun outcome(verdict: GoformSmsClient.SendVerdict, detail: String = "d") =
            GoformSmsClient.SendOutcome(verdict, detail)
    }

    // ══════════════════ 正文拼装与裁剪 ══════════════════

    @Test
    fun `正文格式是 前缀 标题 冒号 正文`() {
        assertEquals(
            "【UFI-AXIS】设备离线：移动数据已断开",
            LocalSmsDelivery.compose("设备离线", "移动数据已断开")
        )
    }

    /** 正文为空时不留一个孤零零的冒号（"【UFI-AXIS】设备离线：" 看着像被截断了）。 */
    @Test
    fun `正文为空时不拼冒号`() {
        assertEquals("【UFI-AXIS】设备离线", LocalSmsDelivery.compose("设备离线", ""))
        assertEquals("【UFI-AXIS】设备离线", LocalSmsDelivery.compose("设备离线", "   "))
    }

    /** 刚好 70 字：**一个字都不裁**，也不加省略号（多加一个字符就要多计一条短信）。 */
    @Test
    fun `刚好 70 字不裁剪`() {
        val title = "设备温度过高"
        val body = "x".repeat(BUDGET - OVERHEAD - title.length)

        val out = LocalSmsDelivery.compose(title, body)

        assertEquals("拼出来应当正好用满预算", BUDGET, out.length)
        assertEquals("【UFI-AXIS】$title：$body", out)
        assertFalse("刚好装满不该出现省略号", out.endsWith("…"))
    }

    /** 71 字：裁到 69 字 + 省略号 = 正好 70，仍然只有一条。 */
    @Test
    fun `超一个字就裁剪并以省略号结尾`() {
        val title = "设备温度过高"
        val body = "x".repeat(BUDGET - OVERHEAD - title.length + 1)

        val out = LocalSmsDelivery.compose(title, body)

        assertEquals("省略号也算一个码元，裁剪后应当正好用满预算", BUDGET, out.length)
        assertTrue(out.endsWith("…"))
        assertTrue("前缀必须留着（它是自激循环的第二道标记）", out.startsWith(LocalSmsDelivery.BODY_PREFIX))
    }

    /** 远超预算（几百字的短信正文转发）也只发一条。 */
    @Test
    fun `远超预算仍然只裁成一条`() {
        val out = LocalSmsDelivery.compose("新短信", "啊".repeat(500))

        assertTrue("绝不能超预算 —— 超了固件就拆成多条按段计费", out.length <= BUDGET)
        assertTrue(out.endsWith("…"))
    }

    /**
     * emoji 正好卡在裁剪边界上时**不能被切成半个代理对**。
     *
     * 一个 emoji 是两个 UTF-16 码元；切一半之后对端收到的是一个乱码方框，
     * 而验证码/营销短信里 emoji 很常见。这里刻意把 emoji 摆在第 69 个码元的位置上。
     */
    @Test
    fun `裁剪不切开 emoji 的代理对`() {
        val title = "警告"
        // 让 emoji 的高位代理正好落在"要保留的最后一个码元"上（下标 BUDGET-2）。
        val leading = "x".repeat(BUDGET - 2 - OVERHEAD - title.length)
        val body = leading + "\uD83D\uDE00" + "y".repeat(10)

        val out = LocalSmsDelivery.compose(title, body)

        assertTrue(out.length <= BUDGET)
        assertTrue(out.endsWith("…"))
        assertFalse("emoji 要么完整保留、要么整个丢掉，不能留半个", out.contains('\uD83D'))
        assertFalse(out.contains('\uDE00'))
        assertNoLoneSurrogate(out)
    }

    /** emoji 完整落在预算内时原样保留（别为了省事把非 BMP 字符一律剔掉）。 */
    @Test
    fun `预算内的 emoji 原样保留`() {
        val out = LocalSmsDelivery.compose("测试", "电量告急\uD83D\uDD0B")

        assertEquals("【UFI-AXIS】测试：电量告急\uD83D\uDD0B", out)
        assertNoLoneSurrogate(out)
    }

    private fun assertNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                assertTrue("下标 $i 是孤立的高位代理", i + 1 < s.length && s[i + 1].isLowSurrogate())
                i += 2
            } else {
                assertFalse("下标 $i 是孤立的低位代理", c.isLowSurrogate())
                i += 1
            }
        }
    }

    // ══════════════════ 号码校验 ══════════════════

    @Test
    fun `合法号码`() {
        for (n in listOf(
            "13800138000",          // 手机号
            "+8613800138000",       // 带国际前缀
            "138-0013-8000",        // 用户习惯的短横线写法
            "138 0013 8000",        // 空格分隔
            "10086",                // 运营商短号
            "110"                   // 3 位，下限
        )) {
            assertTrue("应当判为合法：$n", LocalSmsDelivery.isValidNumber(n))
        }
    }

    @Test
    fun `非法号码`() {
        for (n in listOf(
            "",                        // 空 = 还没配完
            "   ",                     // 只有空白
            "12",                      // 去掉分隔符后只剩 2 位，短于下限
            "1".repeat(21),            // 超过 20 位上限
            "138abc0000",              // 含字母
            "138*0013#000",            // 含拨号符号（本渠道不支持 DTMF 那套）
            "8613800138000+",          // + 不在首位
            "++8613800138000",         // 两个 +
            "+",                       // 只有 +
            "138\n0013",               // 含换行（复制粘贴常带进来）
            "+86-138-0013-8000-x"      // 尾部有非法字符
        )) {
            assertFalse("应当判为非法：$n", LocalSmsDelivery.isValidNumber(n))
        }
    }

    @Test
    fun `分隔符在交给固件之前去掉`() {
        assertEquals("13800138000", LocalSmsDelivery.normalizeNumber("138-0013-8000"))
        assertEquals("+8613800138000", LocalSmsDelivery.normalizeNumber(" +86 138 0013 8000 "))
    }

    /** 日志与投递记录里只留前 3 后 2：够认出串没串号，又不落完整手机号。 */
    @Test
    fun `号码脱敏只留首尾`() {
        assertEquals("138***00", LocalSmsDelivery.maskNumber("138-0013-8000"))
        assertEquals("***", LocalSmsDelivery.maskNumber("110"))
    }

    // ══════════════════ 配置齐不齐 ══════════════════

    @Test
    fun `开关关闭或号码非法都算未配置齐全`() {
        assertFalse("开关关着", LocalSmsDelivery.isConfigured(cfg(enabled = false)))
        assertFalse("号码空着", LocalSmsDelivery.isConfigured(cfg(number = "")))
        assertFalse("号码非法", LocalSmsDelivery.isConfigured(cfg(number = "12")))
        assertTrue(LocalSmsDelivery.isConfigured(cfg()))
    }

    private fun cfg(enabled: Boolean = true, number: String = "13800138000") =
        LocalSmsConfig(enabled = enabled, targetNumber = number)

    // ══════════════════ 级别门槛 ══════════════════
    //
    // 2026-09-10「第二步：规则同构」：门槛的**判定**搬到了 NotificationDispatcher
    // （三条渠道共用一份，见 ChannelRules），所以这里只剩两件本渠道自己的事 ——
    // 默认取值，以及判定所依赖的那条"声明序 = 严重度序"前提。
    // 判定本身的用例在 :core:common 的 NotificationDispatcherTest。

    /** 默认只投 CRITICAL —— INFO 档里有"新短信/验证码"，按条计费接上去就是账单事故。 */
    @Test
    fun `默认最低级别只放行 CRITICAL`() {
        assertEquals(NotifyLevel.CRITICAL, LocalSmsConfig().minLevel)
    }

    /**
     * [NotifyLevel] 的**声明序就是严重度序**（INFO < WARNING < CRITICAL）。
     *
     * 分发器的门槛判定是 `event.level.ordinal >= rules.minLevel().ordinal`，
     * 一旦有人在枚举中间插一档或调换顺序，那行比较会静默地按错误的严重度放行 ——
     * 表现是"最低级别设成 CRITICAL 却收到一堆 INFO 短信"。
     */
    @Test
    fun `级别的声明序就是严重度序`() {
        assertEquals(
            listOf(NotifyLevel.INFO, NotifyLevel.WARNING, NotifyLevel.CRITICAL),
            NotifyLevel.entries.sortedBy { it.ordinal }
        )
    }


    // ══════════════════ 固件结论 → 投递结果 ══════════════════

    /** 信箱 tag=2 是本渠道的送达确认。 */
    @Test
    fun `SENT 映射成 Sent`() {
        assertEquals(
            DeliveryOutcome.Sent,
            LocalSmsDelivery.classify(outcome(GoformSmsClient.SendVerdict.SENT))
        )
    }

    /** tag=3：设备明确说发不出去（SMSC 未设 / 欠费 / 被拦）→ 重试只会再烧一条话费。 */
    @Test
    fun `FAILED 不可重试`() {
        val result = LocalSmsDelivery.classify(
            outcome(GoformSmsClient.SendVerdict.FAILED, "信箱 tag=3")
        )
        val failed = result as DeliveryOutcome.Failed

        assertFalse("tag=3 重试改不了结果", failed.retryable)
        assertTrue(failed.error.contains("信箱 tag=3"))
    }

    /**
     * PENDING **不可重试、也不算 Sent**。
     *
     * 不重试：可能已经发出去了，重试就是第二条话费。
     * 不算 Sent：`hasDeliveryConfirmation = true` 意味着 `Sent` 会让自动关网直接断网 ——
     * 报一个没确认的结果就是把那个判据变回恒真（2026-09-09 修掉的 P1）。
     */
    @Test
    fun `PENDING 既不可重试也不算送达`() {
        val result = LocalSmsDelivery.classify(outcome(GoformSmsClient.SendVerdict.PENDING))

        assertTrue(result is DeliveryOutcome.Failed)
        assertFalse((result as DeliveryOutcome.Failed).retryable)
    }

    /**
     * PENDING 的文案必须写明「可能已发出」。
     *
     * 只显示一个干巴巴的"失败"，用户会去手动重发 —— 那才是真花钱。文案是这一档唯一的
     * 防线（映射本身按设计就是 Failed），所以这条断言钉的是**用户看到的字**。
     */
    @Test
    fun `PENDING 文案写明可能已发出`() {
        val failed = LocalSmsDelivery.classify(
            outcome(GoformSmsClient.SendVerdict.PENDING, "3.6s 内未看到最终状态")
        ) as DeliveryOutcome.Failed

        assertTrue("必须说明设备没回报最终状态", failed.error.contains("未回报最终状态"))
        assertTrue("必须说明可能已发出，否则用户会手动重发", failed.error.contains("可能已发出"))
        assertTrue("要带上固件给的原因", failed.error.contains("3.6s 内未看到最终状态"))
    }

    /** 设备**明确回了拒收**：它说了"没收下"，重试不会重复发 → 唯一可重试的一档。 */
    @Test
    fun `REJECTED 可重试`() {
        val result = LocalSmsDelivery.classify(outcome(GoformSmsClient.SendVerdict.REJECTED))

        assertTrue((result as DeliveryOutcome.Failed).retryable)
    }

    /**
     * `NO_RESPONSE`（请求没走完 / 响应无法解析 / 超时）**不可重试**。
     *
     * 这是 2026-09-09 拆档修掉的重复计费风险：拿不到设备表态时无法排除"其实已经发出去了"，
     * 重试就是**真发第二条短信、真花第二笔钱**。文案必须写明这一点，否则用户会自己去重发。
     */
    @Test
    fun `NO_RESPONSE 不可重试且文案写明无法确认`() {
        val failed = LocalSmsDelivery.classify(
            outcome(GoformSmsClient.SendVerdict.NO_RESPONSE, "resp=null")
        ) as DeliveryOutcome.Failed

        assertFalse("拿不到设备表态时重试会重复计费", failed.retryable)
        assertTrue(failed.error.contains("无法确认设备是否已发出"))
        assertTrue(failed.error.contains("不会自动重发"))
    }

    /**
     * 两档"没发出去"的差异就是本次修复的全部：**只有"设备明确拒收"可重试**。
     *
     * 合成一档之前，`goformPost` 返回 null 也被判成可重试 → 一次超时会让同一条通知
     * 被真发两次（而配额只记一次）。
     */
    @Test
    fun `明确拒收与拿不到表态在可重试性与配额上正好相反`() {
        val rejected = outcome(GoformSmsClient.SendVerdict.REJECTED)
        val noResponse = outcome(GoformSmsClient.SendVerdict.NO_RESPONSE)

        assertTrue(
            "设备说了没收下 → 重试安全",
            (LocalSmsDelivery.classify(rejected) as DeliveryOutcome.Failed).retryable
        )
        assertFalse(
            "拿不到表态 → 重试可能是第二笔话费",
            (LocalSmsDelivery.classify(noResponse) as DeliveryOutcome.Failed).retryable
        )

        assertFalse("明确拒收不计配额：一条都没发出去", LocalSmsDelivery.countsTowardQuota(rejected))
        assertTrue(
            "拿不到表态要计配额：可能已经发出去了，漏计会让实发超过每日上限",
            LocalSmsDelivery.countsTowardQuota(noResponse)
        )
    }

    /**
     * 配额只在**排除不了"已经发出去"**时才计 —— 而唯一能排除的那一档（设备明确拒收）
     * 恰好是唯一可重试的，于是重试永远不会重复扣配额。
     * 这条不变式一旦被改坏，一次会话抖动就会连扣三条配额（或者反过来，重试重复发短信）。
     */
    @Test
    fun `只有 REJECTED 不计入配额，且它正是唯一可重试的一档`() {
        for (verdict in GoformSmsClient.SendVerdict.entries) {
            val o = outcome(verdict)
            val counted = LocalSmsDelivery.countsTowardQuota(o)
            val retryable = (LocalSmsDelivery.classify(o) as? DeliveryOutcome.Failed)?.retryable == true

            assertEquals(
                "$verdict：'计配额' 与 '可重试' 必须互斥，否则要么重试重复扣配额、要么重试重复计费",
                counted, !retryable
            )
        }
        assertFalse(LocalSmsDelivery.countsTowardQuota(outcome(GoformSmsClient.SendVerdict.REJECTED)))
        assertTrue(LocalSmsDelivery.countsTowardQuota(outcome(GoformSmsClient.SendVerdict.SENT)))
        assertTrue(LocalSmsDelivery.countsTowardQuota(outcome(GoformSmsClient.SendVerdict.FAILED)))
        assertTrue(LocalSmsDelivery.countsTowardQuota(outcome(GoformSmsClient.SendVerdict.PENDING)))
        assertTrue(LocalSmsDelivery.countsTowardQuota(outcome(GoformSmsClient.SendVerdict.NO_RESPONSE)))
    }

    // ══════════════════ 配置校验 ══════════════════

    @Test
    fun `配置校验拒掉越界的每日上限与未知场景`() {
        assertTrue(
            LocalSmsConfig.validate(LocalSmsConfig(dailyLimit = 0))!!.contains("daily_limit")
        )
        assertTrue(
            LocalSmsConfig.validate(LocalSmsConfig(dailyLimit = 51))!!.contains("daily_limit")
        )
        assertTrue(
            LocalSmsConfig.validate(LocalSmsConfig(scenes = setOf("traffic_80")))!!.contains("traffic_80")
        )
        assertTrue(
            LocalSmsConfig.validate(LocalSmsConfig(targetNumber = "abc"))!!.contains("target_number")
        )
        // 号码为空是合法的（= 还没配完），不该在 PUT 时报错
        assertEquals(null, LocalSmsConfig.validate(LocalSmsConfig()))
    }
}
