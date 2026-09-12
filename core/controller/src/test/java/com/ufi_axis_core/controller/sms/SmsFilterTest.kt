package com.ufi_axis_core.controller.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SmsFilter] 回归防线。
 *
 * 为什么这些用例值钱：拦截是**静默丢消息**的功能 —— 判定错了，用户看到的是
 * 「邮件没来」「通知没弹」「列表里少一条」，从界面到日志都没有线索指向规则。
 * 所以四种 matchType、三种 scope、enabled 开关、验证码豁免的四种组合每一条都要钉住。
 */
class SmsFilterTest {

    private fun rule(
        id: Long = 1L,
        enabled: Boolean = true,
        scope: String = SmsFilter.SCOPE_BODY,
        matchType: String = SmsFilter.MATCH_CONTAINS,
        pattern: String
    ) = SmsFilter.Rule(id, enabled, scope, matchType, pattern)

    private fun snapshot(vararg rules: SmsFilter.Rule, exempt: Boolean = false) =
        SmsFilter.RuleSnapshot(rules.toList(), exemptVerificationCode = exempt)

    private fun verdict(
        sender: String,
        body: String,
        snap: SmsFilter.RuleSnapshot,
        isVerificationCode: Boolean = false
    ) = SmsFilter.evaluate(sender, body, snap, isVerificationCode)

    // ══════════ 四种 matchType ══════════

    @Test
    fun `contains matches anywhere in the target`() {
        val snap = snapshot(rule(pattern = "中奖"))
        assertTrue(verdict("10086", "恭喜您中奖了", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("10086", "话费余额不足", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `equals requires the whole target to match`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_SENDER, matchType = SmsFilter.MATCH_EQUALS, pattern = "10086"))
        assertTrue(verdict("10086", "任意正文", snap) is SmsFilter.Verdict.Block)
        // 子串不算：`equals` 的存在意义就是「只拦这个号码，不拦 +8610086、100861」
        assertTrue(verdict("+8610086", "任意正文", snap) is SmsFilter.Verdict.Allow)
        assertTrue(verdict("100861", "任意正文", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `prefix matches only at the start`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_SENDER, matchType = SmsFilter.MATCH_PREFIX, pattern = "+86"))
        assertTrue(verdict("+8613800138000", "x", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("13800138000", "x", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `suffix matches only at the end`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_SENDER, matchType = SmsFilter.MATCH_SUFFIX, pattern = "8000"))
        assertTrue(verdict("13800138000", "x", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("80001380013", "x", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `all match types ignore case`() {
        val snap = snapshot(
            rule(id = 1, matchType = SmsFilter.MATCH_CONTAINS, pattern = "LoAn")
        )
        assertTrue(verdict("x", "apply for a loan today", snap) is SmsFilter.Verdict.Block)
    }

    /** 未知 matchType 一律不匹配：误拦的代价远高于漏拦。 */
    @Test
    fun `unknown match type never blocks`() {
        val snap = snapshot(rule(matchType = "regex", pattern = ".*"))
        assertTrue(verdict("x", "任意正文 .*", snap) is SmsFilter.Verdict.Allow)
    }

    // ══════════ 三种 scope ══════════

    @Test
    fun `scope sender only looks at the sender`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_SENDER, pattern = "10086"))
        assertTrue(verdict("10086", "无关正文", snap) is SmsFilter.Verdict.Block)
        // 这正是旧 blacklist 的 bug：正文里提到 10086 的正常短信也被拦
        assertTrue(verdict("95533", "请回复 10086 查询余额", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `scope body only looks at the body`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_BODY, pattern = "10086"))
        assertTrue(verdict("95533", "请回复 10086 查询余额", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("10086", "无关正文", snap) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `scope both hits on either side`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_BOTH, pattern = "10086"))
        assertTrue(verdict("10086", "无关正文", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("95533", "请回复 10086 查询", snap) is SmsFilter.Verdict.Block)
        assertTrue(verdict("95533", "无关正文", snap) is SmsFilter.Verdict.Allow)
    }

    // ══════════ 防假开关 ══════════

    @Test
    fun `disabled rule does not take effect`() {
        val snap = snapshot(rule(enabled = false, pattern = "中奖"))
        assertTrue(verdict("10086", "恭喜您中奖了", snap) is SmsFilter.Verdict.Allow)
    }

    /** 空 pattern 用 contains 会命中一切短信 —— 等于一键静默全部消息，必须跳过。 */
    @Test
    fun `blank pattern is skipped instead of matching everything`() {
        val snap = snapshot(rule(pattern = ""), rule(id = 2, pattern = "   "))
        assertTrue(verdict("10086", "任意正文", snap) is SmsFilter.Verdict.Allow)
    }

    /**
     * 纯空白 pattern 与**有效规则共存**时也必须被跳过。
     *
     * 这一条盯的是判据不一致：`RuleSnapshot.isEmpty` 用的是 `pattern.isNotBlank()`，
     * 而 `evaluate` 的跳过守卫曾经用 `patternLower.isEmpty()`。只要 DB 里被外部写进一条
     * 纯空白 pattern，再有任意一条正常规则让快照不算 empty，六个接入点就都不会短路，
     * `contains(" ")` 命中几乎所有短信 —— 后果是**全部短信被静默拦掉**，
     * 界面上只看到「邮件没来、通知没弹」，一点线索都没有。
     */
    @Test
    fun `blank pattern coexisting with a valid rule still blocks nothing extra`() {
        val snap = snapshot(
            rule(id = 1, pattern = "   "),
            rule(id = 2, pattern = "中奖")
        )
        // 快照里有有效规则 → 不是 empty，判定会真跑一遍全部规则
        assertTrue(!snap.isEmpty)
        // 含空格的普通短信不该被那条空白规则拦住
        assertTrue(verdict("10086", "您的话费余额为 12.34 元", snap) is SmsFilter.Verdict.Allow)
        // 有效规则照旧生效
        assertTrue(verdict("10086", "恭喜您中奖了", snap) is SmsFilter.Verdict.Block)
    }

    // ══════════ 验证码豁免 ══════════

    @Test
    fun `verification code is exempt from body rules`() {
        val snap = snapshot(rule(id = 7, scope = SmsFilter.SCOPE_BODY, pattern = "银行"), exempt = true)
        val body = "【某银行】您的验证码 1234，请勿泄露"
        // 不是验证码 → 照拦
        assertTrue(verdict("95533", body, snap, isVerificationCode = false) is SmsFilter.Verdict.Block)
        // 是验证码 → 放行（关键词是模糊匹配，误伤验证码代价最高）
        assertTrue(verdict("95533", body, snap, isVerificationCode = true) is SmsFilter.Verdict.Allow)
    }

    @Test
    fun `verification code is NOT exempt from sender rules`() {
        val snap = snapshot(rule(id = 8, scope = SmsFilter.SCOPE_SENDER, pattern = "95533"), exempt = true)
        val v = verdict("95533", "您的验证码 1234", snap, isVerificationCode = true)
        // 拉黑号码是明确意图，不该被「这次发来的是验证码」绕过
        assertTrue(v is SmsFilter.Verdict.Block)
        assertEquals(8L, (v as SmsFilter.Verdict.Block).ruleId)
    }

    @Test
    fun `scope both falls back to sender-only while exempt`() {
        val snap = snapshot(rule(id = 9, scope = SmsFilter.SCOPE_BOTH, pattern = "95533"), exempt = true)
        // sender 侧命中 → 仍拦
        assertTrue(verdict("95533", "您的验证码 1234", snap, isVerificationCode = true) is SmsFilter.Verdict.Block)
        // 只有 body 侧命中 → 豁免生效，放行
        assertTrue(verdict("10086", "请拨 95533，验证码 1234", snap, isVerificationCode = true) is SmsFilter.Verdict.Allow)
        // 同一条短信在非验证码判定下 body 侧照拦（证明上一条是豁免而不是压根没匹配上）
        assertTrue(verdict("10086", "请拨 95533，验证码 1234", snap, isVerificationCode = false) is SmsFilter.Verdict.Block)
    }

    @Test
    fun `exempt switch off means verification codes are filtered like everything else`() {
        val snap = snapshot(rule(scope = SmsFilter.SCOPE_BODY, pattern = "银行"), exempt = false)
        assertTrue(verdict("95533", "【某银行】验证码 1234", snap, isVerificationCode = true) is SmsFilter.Verdict.Block)
    }

    // ══════════ 多规则 / 快照 ══════════

    @Test
    fun `first matching rule in snapshot order wins`() {
        val snap = snapshot(
            rule(id = 1, pattern = "中奖"),
            rule(id = 2, pattern = "恭喜")
        )
        val v = verdict("10086", "恭喜您中奖了", snap)
        assertEquals(1L, (v as SmsFilter.Verdict.Block).ruleId)
    }

    /** Block 里带的是规则**快照**：记录要在规则被删之后仍然读得懂。 */
    @Test
    fun `block carries a snapshot of the matched rule`() {
        val snap = snapshot(rule(id = 42, scope = SmsFilter.SCOPE_SENDER, matchType = SmsFilter.MATCH_PREFIX, pattern = "+86"))
        val v = verdict("+8613800138000", "x", snap) as SmsFilter.Verdict.Block
        assertEquals(42L, v.ruleId)
        assertEquals("+86", v.pattern)
        assertEquals(SmsFilter.SCOPE_SENDER, v.scope)
        assertEquals(SmsFilter.MATCH_PREFIX, v.matchType)
    }

    @Test
    fun `empty snapshot allows everything`() {
        assertTrue(verdict("10086", "恭喜您中奖了", SmsFilter.RuleSnapshot.EMPTY) is SmsFilter.Verdict.Allow)
        assertTrue(SmsFilter.RuleSnapshot.EMPTY.isEmpty)
    }

    /** 全是停用规则的快照也算 empty —— 六个接入点靠这个短路，连 lowercase 都省掉。 */
    @Test
    fun `snapshot with only disabled rules counts as empty`() {
        assertTrue(snapshot(rule(enabled = false, pattern = "中奖")).isEmpty)
        assertTrue(!snapshot(rule(pattern = "中奖")).isEmpty)
    }
}
