package com.ufi_axis_core.controller.sms

import com.ufi_axis_core.core.database.SmsBlockedLog
import com.ufi_axis_core.core.database.SmsBlockedLogDao
import com.ufi_axis_core.core.database.SmsRule
import com.ufi_axis_core.core.database.SmsRuleDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [SmsRuleStore] 的拦截记录去重、命中计数与快照重建。
 *
 * 用手写 fake DAO 而不是内存 Room：`:core:controller` 的单测源集只有 junit，
 * 而这里要验证的全是 store 自己的逻辑（合并 path / 去重键的选择 / 增量累加），
 * 与 SQL 无关。Room 的 SQL 由 `:core:database` 的 androidTest 覆盖。
 */
class SmsRuleStoreTest {

    // ── fake DAO ──

    private class FakeRuleDao : SmsRuleDao {
        val rules = mutableListOf<SmsRule>()
        /** ruleId → (累计 delta, 最后一次 now)，验证 flush 用的是**增量** UPDATE。 */
        val bumps = mutableMapOf<Long, Pair<Int, Long>>()
        private var nextId = 1L

        override suspend fun getAll(): List<SmsRule> = rules.sortedByDescending { it.created_at }
        override suspend fun getEnabled(): List<SmsRule> = rules.filter { it.enabled }
        override suspend fun insert(rule: SmsRule): Long {
            val id = nextId++
            rules.add(rule.copy(id = id))
            return id
        }
        override suspend fun update(rule: SmsRule) {
            val i = rules.indexOfFirst { it.id == rule.id }
            if (i >= 0) rules[i] = rule
        }
        override suspend fun deleteById(id: Long): Int =
            if (rules.removeAll { it.id == id }) 1 else 0
        override suspend fun deleteAll() = rules.clear()
        override suspend fun countAll(): Int = rules.size
        override suspend fun bumpHit(id: Long, delta: Int, now: Long) {
            val prev = bumps[id]
            bumps[id] = ((prev?.first ?: 0) + delta) to now
        }
    }

    private class FakeBlockedLogDao : SmsBlockedLogDao {
        val rows = mutableListOf<SmsBlockedLog>()
        var trimmedTo: Int? = null
        private var nextId = 1L

        /**
         * 置 true 时在「查已有记录」与「插入」之间让出调度。
         *
         * 用来暴露 [SmsRuleStore.recordBlocked] 那段 check-then-act：不加锁时两个协程
         * 会各自查到「没有记录」然后各插一行。单线程 runBlocking + yield 就能确定性复现，
         * 不需要真并行（fake 的 MutableList 也就不用做线程安全）。
         */
        var interleave = false

        override suspend fun insert(record: SmsBlockedLog): Long {
            if (interleave) yield()
            val id = nextId++
            rows.add(record.copy(id = id))
            return id
        }
        override suspend fun update(record: SmsBlockedLog) {
            val i = rows.indexOfFirst { it.id == record.id }
            if (i >= 0) rows[i] = record
        }
        override suspend fun getPaged(cTs: Long?, cId: Long?, limit: Int): List<SmsBlockedLog> =
            rows.sortedWith(compareByDescending<SmsBlockedLog> { it.blocked_at }.thenByDescending { it.id })
                .filter { cTs == null || it.blocked_at < cTs || (it.blocked_at == cTs && it.id < (cId ?: 0)) }
                .take(limit)
        override suspend fun countAll(): Int = rows.size
        override suspend fun findByMsgId(msgId: Long): SmsBlockedLog? {
            if (interleave) yield()
            return rows.filter { it.msg_id == msgId }.maxByOrNull { it.blocked_at }
        }
        override suspend fun findRecentBySender(sender: String, since: Long): SmsBlockedLog? =
            rows.filter { it.sender == sender && it.blocked_at >= since }.maxByOrNull { it.blocked_at }
        override suspend fun deleteById(id: Long): Int = if (rows.removeAll { it.id == id }) 1 else 0
        override suspend fun deleteAll() = rows.clear()
        override suspend fun trimTo(maxRows: Int) { trimmedTo = maxRows }
    }

    /**
     * 第 [failAt] 次 `bumpHit` 抛 [CancellationException]，用来验证 flush 被取消时
     * 「已经写盘成功的那部分不回灌内存」。
     */
    private class CancelOnNthBumpDao : SmsRuleDao {
        val rules = mutableListOf<SmsRule>()
        /** ruleId → 累计写入的 delta。 */
        val bumps = mutableMapOf<Long, Int>()
        var failAt = Int.MAX_VALUE
        private var calls = 0
        private var nextId = 1L

        override suspend fun getAll(): List<SmsRule> = rules
        override suspend fun getEnabled(): List<SmsRule> = rules.filter { it.enabled }
        override suspend fun insert(rule: SmsRule): Long {
            val id = nextId++
            rules.add(rule.copy(id = id))
            return id
        }
        override suspend fun update(rule: SmsRule) {
            val i = rules.indexOfFirst { it.id == rule.id }
            if (i >= 0) rules[i] = rule
        }
        override suspend fun deleteById(id: Long): Int = if (rules.removeAll { it.id == id }) 1 else 0
        override suspend fun deleteAll() = rules.clear()
        override suspend fun countAll(): Int = rules.size
        override suspend fun bumpHit(id: Long, delta: Int, now: Long) {
            calls++
            if (calls == failAt) throw CancellationException("flush cancelled mid-batch")
            bumps[id] = (bumps[id] ?: 0) + delta
        }
    }


    private fun store(
        ruleDao: SmsRuleDao? = FakeRuleDao(),
        logDao: SmsBlockedLogDao? = FakeBlockedLogDao(),
        exempt: Boolean = true,
        fullBody: Boolean = false
    ) = SmsRuleStore(ruleDao, logDao, { exempt }, { fullBody })

    private fun block(ruleId: Long = 1L) = SmsFilter.Verdict.Block(
        ruleId = ruleId, pattern = "中奖", scope = SmsFilter.SCOPE_BODY, matchType = SmsFilter.MATCH_CONTAINS
    )

    // ══════════ 去重：同一条短信多路径只产生一行 ══════════

    @Test
    fun `same msg_id from multiple paths merges into one row`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        s.recordBlocked(101L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.PUSH)
        s.recordBlocked(101L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.VC)

        assertEquals("同一 msg_id 只应有一行记录", 1, logDao.rows.size)
        assertEquals("push,vc", logDao.rows[0].blocked_path)
    }

    @Test
    fun `merging the same path twice does not duplicate it`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        s.recordBlocked(101L, "10086", "x", block(), SmsRuleStore.Path.PUSH)
        s.recordBlocked(101L, "10086", "x", block(), SmsRuleStore.Path.PUSH)

        assertEquals(1, logDao.rows.size)
        assertEquals("push", logDao.rows[0].blocked_path)
    }

    @Test
    fun `different msg_ids produce separate rows`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        s.recordBlocked(101L, "10086", "x", block(), SmsRuleStore.Path.PUSH)
        s.recordBlocked(102L, "10086", "y", block(), SmsRuleStore.Path.PUSH)

        assertEquals(2, logDao.rows.size)
    }

    /**
     * 同一条短信被两条**写路径并发**处理时也只留一行。
     *
     * 现实里这两条路径是邮件（`SmsForwardController.forwardSms`）与 `DataScheduler` 的
     * push/vc，跑在不同协程上；`msg_id` 只是普通索引、没有唯一约束，DB 层拦不住重复。
     * 不加锁时两个协程都会在「查」这一步看到「没有记录」，然后各插一行 ——
     * 界面上一条短信出现两次，命中次数也双计。
     */
    @Test
    fun `concurrent recordBlocked with the same msg_id still yields one row`() = runBlocking {
        val logDao = FakeBlockedLogDao().apply { interleave = true }
        val s = store(logDao = logDao)

        val mail = launch { s.recordBlocked(101L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.MAIL) }
        val push = launch { s.recordBlocked(101L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.PUSH) }
        mail.join()
        push.join()

        assertEquals("并发写同一 msg_id 只应有一行", 1, logDao.rows.size)
        assertEquals(
            setOf(SmsRuleStore.Path.MAIL, SmsRuleStore.Path.PUSH),
            logDao.rows[0].blocked_path.split(',').toSet()
        )
    }


    /**
     * 邮件路径拿不到设备侧短信 id（`forwardSms` 只有 from/body/timestamp），msg_id 落 0，
     * 这时按 sender + 60s 窗口去重 —— 否则邮件那条会永远独立成行，同一条短信在界面上出现两次。
     */
    @Test
    fun `msg_id zero falls back to sender window dedupe`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        s.recordBlocked(0L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.MAIL)
        s.recordBlocked(0L, "10086", "恭喜您中奖了", block(), SmsRuleStore.Path.PUSH)

        assertEquals(1, logDao.rows.size)
        assertEquals("mail,push", logDao.rows[0].blocked_path)
    }

    @Test
    fun `sender window dedupe does not merge different senders`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        s.recordBlocked(0L, "10086", "x", block(), SmsRuleStore.Path.MAIL)
        s.recordBlocked(0L, "95533", "x", block(), SmsRuleStore.Path.MAIL)

        assertEquals(2, logDao.rows.size)
    }

    /** 60 秒窗口外的同号码算新的一条（否则同一个营销号一整天只留一条记录）。 */
    @Test
    fun `sender window dedupe expires after the window`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)

        // 手工塞一条 2 分钟前的记录，模拟窗口外
        logDao.insert(
            SmsBlockedLog(
                msg_id = 0L, sender = "10086", snippet = "old", rule_id = 1L,
                rule_pattern = "中奖", rule_scope = SmsFilter.SCOPE_BODY, rule_match = SmsFilter.MATCH_CONTAINS,
                blocked_path = SmsRuleStore.Path.MAIL,
                blocked_at = System.currentTimeMillis() - 120_000L
            )
        )
        s.recordBlocked(0L, "10086", "新的一条", block(), SmsRuleStore.Path.MAIL)

        assertEquals(2, logDao.rows.size)
    }

    // ══════════ 记录内容 ══════════

    @Test
    fun `record keeps a rule snapshot so it stays readable after the rule is deleted`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)
        s.recordBlocked(
            101L, "10086", "恭喜您中奖了",
            SmsFilter.Verdict.Block(7L, "中奖", SmsFilter.SCOPE_BOTH, SmsFilter.MATCH_PREFIX),
            SmsRuleStore.Path.PUSH
        )
        val row = logDao.rows.single()
        assertEquals(7L, row.rule_id)
        assertEquals("中奖", row.rule_pattern)
        assertEquals(SmsFilter.SCOPE_BOTH, row.rule_scope)
        assertEquals(SmsFilter.MATCH_PREFIX, row.rule_match)
    }

    /** 隐私默认值：只留 120 字 snippet，body 空串。 */
    @Test
    fun `full body is not stored unless the switch is on`() = runBlocking {
        val longBody = "垃圾短信正文".repeat(50)

        val offDao = FakeBlockedLogDao()
        store(logDao = offDao, fullBody = false)
            .recordBlocked(1L, "10086", longBody, block(), SmsRuleStore.Path.PUSH)
        assertEquals("", offDao.rows.single().body)
        assertEquals(120, offDao.rows.single().snippet.length)

        val onDao = FakeBlockedLogDao()
        store(logDao = onDao, fullBody = true)
            .recordBlocked(1L, "10086", longBody, block(), SmsRuleStore.Path.PUSH)
        assertEquals(longBody, onDao.rows.single().body)
    }

    /** 上限写死 500，insert 之后立刻裁剪（照 AlertEngine 的做法）。 */
    @Test
    fun `insert triggers ring trim with the hardcoded cap`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        store(logDao = logDao).recordBlocked(1L, "10086", "x", block(), SmsRuleStore.Path.PUSH)
        assertEquals(500, logDao.trimmedTo)
    }

    /** 合并已有记录时不裁剪：没有新行，裁剪只是白跑一次 DELETE。 */
    @Test
    fun `merging an existing row does not trim again`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)
        s.recordBlocked(1L, "10086", "x", block(), SmsRuleStore.Path.PUSH)
        logDao.trimmedTo = null
        s.recordBlocked(1L, "10086", "x", block(), SmsRuleStore.Path.VC)
        assertNull(logDao.trimmedTo)
    }

    // ══════════ 命中计数 ══════════

    @Test
    fun `hits accumulate in memory and flush as an incremental update`() = runBlocking {
        val ruleDao = FakeRuleDao()
        val s = store(ruleDao = ruleDao)

        // 三次不同短信命中同一条规则
        s.recordBlocked(1L, "10086", "x", block(ruleId = 5L), SmsRuleStore.Path.PUSH)
        s.recordBlocked(2L, "10086", "y", block(ruleId = 5L), SmsRuleStore.Path.PUSH)
        s.recordBlocked(3L, "10086", "z", block(ruleId = 5L), SmsRuleStore.Path.PUSH)
        // 还没到 flush 间隔 → 一次 UPDATE 都没发
        assertTrue("命中次数应先在内存累加", ruleDao.bumps.isEmpty())

        s.flushHits()
        assertEquals(3, ruleDao.bumps[5L]?.first)
        assertTrue("last_hit_at 应被刷新", (ruleDao.bumps[5L]?.second ?: 0L) > 0L)
    }

    @Test
    fun `flush is a no-op when nothing is pending`() = runBlocking {
        val ruleDao = FakeRuleDao()
        val s = store(ruleDao = ruleDao)
        s.flushHits()
        assertTrue(ruleDao.bumps.isEmpty())
    }

    /**
     * flush 写到一半被取消：只回灌**还没写成功**的那部分。
     *
     * 原实现在 `CancellationException` 分支把整个 batch 都还回 `pendingHits`，
     * 而 batch 里包含循环中已经写盘成功的 ruleId —— 下一次 flush 会把它们再加一遍，
     * 命中次数越取消越大。「命中 N 次」是用户判断规则有没有生效的唯一线索，
     * 数字偏大比偏小更难解释。
     */
    @Test
    fun `cancelled flush does not double count deltas already written`() = runBlocking {
        val ruleDao = CancelOnNthBumpDao().apply { failAt = 2 }
        val s = SmsRuleStore(ruleDao, FakeBlockedLogDao(), { true }, { false })

        // 两条不同规则各命中一次 → batch 里两个 ruleId
        s.recordBlocked(1L, "10086", "x", block(ruleId = 5L), SmsRuleStore.Path.PUSH)
        s.recordBlocked(2L, "10086", "y", block(ruleId = 6L), SmsRuleStore.Path.PUSH)

        try {
            s.flushHits()
            fail("第二次 bumpHit 应当抛出 CancellationException")
        } catch (e: CancellationException) {
            // 预期：取消照旧向上抛，不被吞掉
        }
        assertEquals("取消前只写成功了一条", 1, ruleDao.bumps.size)

        // 补写剩下那条
        ruleDao.failAt = Int.MAX_VALUE
        s.flushHits()

        assertEquals("两条规则最终都要写到", 2, ruleDao.bumps.size)
        assertTrue(
            "已写成功的那条不该被再加一遍，实际=${ruleDao.bumps}",
            ruleDao.bumps.values.all { it == 1 }
        )
    }


    // ══════════ 快照 ══════════

    @Test
    fun `reload builds a snapshot from enabled rules only`() = runBlocking {
        val ruleDao = FakeRuleDao()
        ruleDao.insert(SmsRule(enabled = true, scope = SmsFilter.SCOPE_BODY, match_type = SmsFilter.MATCH_CONTAINS, pattern = "中奖"))
        ruleDao.insert(SmsRule(enabled = false, scope = SmsFilter.SCOPE_BODY, match_type = SmsFilter.MATCH_CONTAINS, pattern = "贷款"))
        val s = store(ruleDao = ruleDao)

        // 重载之前是空快照 = 谁都不拦（启动窗口的刻意取舍）
        assertTrue(s.evaluate("10086", "恭喜您中奖了") is SmsFilter.Verdict.Allow)

        s.reloadRules()
        assertTrue(s.hasActiveRules())
        assertTrue(s.isBlocked("10086", "恭喜您中奖了"))
        assertTrue("停用规则不该进快照", !s.isBlocked("10086", "低息贷款"))
    }

    /** CRUD 之后必须自动重载 —— 忘了重载就是「加了规则却不生效」。 */
    @Test
    fun `adding a rule reloads the snapshot immediately`() = runBlocking {
        val s = store()
        s.addRule(SmsRule(enabled = true, scope = SmsFilter.SCOPE_SENDER, match_type = SmsFilter.MATCH_EQUALS, pattern = "10086"))
        assertTrue(s.isBlocked("10086", "任意正文"))
    }

    @Test
    fun `deleting a rule reloads the snapshot immediately`() = runBlocking {
        val s = store()
        val id = s.addRule(SmsRule(enabled = true, scope = SmsFilter.SCOPE_SENDER, match_type = SmsFilter.MATCH_EQUALS, pattern = "10086"))!!
        assertTrue(s.isBlocked("10086", "x"))
        s.deleteRule(id)
        assertTrue(!s.isBlocked("10086", "x"))
    }

    @Test
    fun `toggling enabled off stops the rule from matching`() = runBlocking {
        val ruleDao = FakeRuleDao()
        val s = store(ruleDao = ruleDao)
        val id = s.addRule(SmsRule(enabled = true, scope = SmsFilter.SCOPE_SENDER, match_type = SmsFilter.MATCH_EQUALS, pattern = "10086"))!!
        s.updateRule(ruleDao.getAll().single { it.id == id }.copy(enabled = false))
        assertTrue(!s.isBlocked("10086", "x"))
    }

    /** 豁免开关的值在**重载时**冻结进快照，所以改开关必须重载（否则是假开关）。 */
    @Test
    fun `exempt flag is captured into the snapshot on reload`() = runBlocking {
        val ruleDao = FakeRuleDao()
        ruleDao.insert(SmsRule(enabled = true, scope = SmsFilter.SCOPE_BODY, match_type = SmsFilter.MATCH_CONTAINS, pattern = "银行"))

        val exemptOn = SmsRuleStore(ruleDao, FakeBlockedLogDao(), { true }, { false })
        exemptOn.reloadRules()
        assertTrue("验证码短信应被豁免", !exemptOn.isBlocked("95533", "【某银行】验证码 1234"))

        val exemptOff = SmsRuleStore(ruleDao, FakeBlockedLogDao(), { false }, { false })
        exemptOff.reloadRules()
        assertTrue("关掉豁免后同一条要被拦", exemptOff.isBlocked("95533", "【某银行】验证码 1234"))
    }

    // ══════════ 旧 blacklist 搬迁 ══════════

    @Test
    fun `legacy blacklist becomes both-scope contains rules`() = runBlocking {
        val ruleDao = FakeRuleDao()
        val s = SmsRuleStore(ruleDao, FakeBlockedLogDao(), { true }, { false })
        s.startStartupMaintenance(listOf("10086", "  ", "中奖"))
        // startStartupMaintenance 是 fire-and-forget，等它把快照建起来
        var waited = 0
        while (ruleDao.rules.size < 2 && waited < 2000) {
            Thread.sleep(20); waited += 20
        }
        assertEquals(2, ruleDao.rules.size)
        assertTrue(ruleDao.rules.all { it.scope == SmsFilter.SCOPE_BOTH && it.match_type == SmsFilter.MATCH_CONTAINS })
    }

    // ══════════ 降级装配 ══════════

    @Test
    fun `store without daos never blocks and never crashes`() = runBlocking {
        val s = SmsRuleStore(null, null, { true }, { false })
        s.reloadRules()
        assertTrue(!s.hasActiveRules())
        assertTrue(s.evaluate("10086", "恭喜您中奖了") is SmsFilter.Verdict.Allow)
        // 写记录 / flush / 列表都必须安静地什么也不做
        s.recordBlocked(1L, "10086", "x", block(), SmsRuleStore.Path.PUSH)
        s.flushHits()
        assertEquals(0, s.listBlocked(null, null, 10).size)
        assertEquals(0, s.countBlocked())
        assertEquals(0, s.listRules().size)
    }

    // ══════════ keyset 游标分页 ══════════

    @Test
    fun `keyset pagination walks without gaps or duplicates`() = runBlocking {
        val logDao = FakeBlockedLogDao()
        val s = store(logDao = logDao)
        val base = System.currentTimeMillis()
        repeat(5) { i ->
            logDao.insert(
                SmsBlockedLog(
                    msg_id = (i + 1).toLong(), sender = "10086", snippet = "s$i", rule_id = 1L,
                    rule_pattern = "中奖", rule_scope = SmsFilter.SCOPE_BODY, rule_match = SmsFilter.MATCH_CONTAINS,
                    blocked_path = SmsRuleStore.Path.PUSH, blocked_at = base + i
                )
            )
        }

        val seen = mutableListOf<Long>()
        var cTs: Long? = null
        var cId: Long? = null
        while (true) {
            val page = s.listBlocked(cTs, cId, 2)
            if (page.isEmpty()) break
            seen.addAll(page.map { it.msg_id })
            cTs = page.last().blocked_at
            cId = page.last().id
        }
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), seen)
    }
}
