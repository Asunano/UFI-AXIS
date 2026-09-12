package com.ufi_axis_core.controller.sms

import com.ufi_axis_core.core.database.SmsBlockedLog
import com.ufi_axis_core.core.database.SmsBlockedLogDao
import com.ufi_axis_core.core.database.SmsRule
import com.ufi_axis_core.core.database.SmsRuleDao
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 短信拦截规则的**唯一持有者**：内存快照 + 规则 CRUD + 拦截记录 + 命中计数。
 *
 * ## 为什么单独一个类，而不是塞进 SmsController
 *
 * 判定要在 6 个地方发生，而这 6 个地方分属三个模块：
 * `SmsForwardController`（邮件，同模块）、`DataScheduler`（推送 / 验证码入库，`:core:scheduler`）、
 * `SmsController`（列表 / 计数 / 联系人，同模块）。
 * 把快照放进 `SmsController` 会让「发邮件」和「后台轮询」为了问一句「该不该拦」而依赖
 * 一个**读短信的控制器** —— 它自己带着 ContentResolver、goform 客户端和已读状态 DAO，
 * 三个职责毫不相关。独立成 store 之后，快照与两张表只有一个 owner，四个调用点平等注入，
 * 「CRUD 之后必须重载快照」这件事也只有一处能忘。
 *
 * ## 快照
 *
 * [snapshot] 以 `@Volatile` 持有、整体替换（构造即冻结，不原地改），所以读侧零锁 ——
 * `collectSmsCache` 是 5s 热路径，判定不能有锁竞争，更不能查 DB。
 * 规则 CRUD 与豁免开关变化后必须调 [reloadRules]。
 *
 * ## 拦截记录只由写路径产生
 *
 * 邮件 / WS 推送 / 验证码入库这三条**写**路径命中时写记录；列表、计数、联系人聚合这些
 * **读**路径只过滤、不写 —— 否则用户每次下拉刷新短信列表都会刷出一批重复记录。
 * 同理 [SmsRule.hit_count] 也只在写路径累加，不然「命中次数」会变成「你刷了几次列表」。
 */
class SmsRuleStore(
    private val ruleDao: SmsRuleDao?,
    private val logDao: SmsBlockedLogDao?,
    /** 验证码豁免开关（真源在 `AppSettings.smsFilterExemptVerificationCode`）。 */
    private val exemptVerificationCode: () -> Boolean,
    /** 拦截记录是否留存正文全文（真源在 `AppSettings.smsFilterStoreFullBody`，默认 false）。 */
    private val storeFullBody: () -> Boolean,
    /**
     * `config_changed` 广播口（`{ type, data }` → `WebSocketManager.broadcast`）。
     * 用注入而不是直接依赖 `:core:websocket`：`:core:controller` 在它之下，引不到。
     */
    private val broadcaster: (suspend (String, Map<String, Any?>) -> Unit)? = null
) {
    private val tag = "SmsRuleStore"

    /**
     * 拦截记录的 `blocked_path` 取值 —— 三条**写**路径各一个。
     * 读路径（列表 / 计数 / 联系人）不写记录，所以这里没有对应项。
     */
    object Path {
        /** 邮件转发（`SmsForwardController.forwardSms`）。 */
        const val MAIL = "mail"

        /** WS `notification` 推送 → 手机弹通知（`DataScheduler.collectSmsCache`）。 */
        const val PUSH = "push"

        /** 验证码入库（`DataScheduler.scanVerificationCodes`）。 */
        const val VC = "vc"
    }


    /** fire-and-forget 用（启动搬迁 / 广播），与 AlertEngine 的 scope 用法一致。 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 判定快照。初始为空 = 谁都不拦。
     *
     * 启动后到 [reloadRules] 完成之间有一个很短的「规则还没生效」窗口。
     * 这是刻意的取舍：宁可漏拦一条，也不要为了拦截而把 core 启动路径卡在一次 DB 查询上。
     */
    @Volatile
    var snapshot: SmsFilter.RuleSnapshot = SmsFilter.RuleSnapshot.EMPTY
        private set

    /** 待 flush 的命中次数增量：ruleId → delta。 */
    private val pendingHits = HashMap<Long, Int>()
    private val hitsLock = Mutex()

    /**
     * 拦截记录写入串行锁 —— 保护 [recordBlocked] 里「查已有记录 → 合并或插入」这段 check-then-act。
     *
     * 为什么需要：同一条短信会同时被邮件路径（`SmsForwardController.forwardSms`）和
     * `DataScheduler` 的 push / vc 路径处理，三条路径在不同协程里并发跑。`msg_id` 只是普通索引、
     * 没有唯一约束，DB 层拦不住重复 —— 两个协程同时查到「没有记录」就会各插一行，
     * 界面上一条短信出现两次，`bumpHit` 也双计，命中次数虚高。
     *
     * 为什么不复用 [hitsLock]：那把锁在 [flushHits] 里要跨整批 DB UPDATE 持有，复用会让
     * 命中计数落盘和拦截记录写入互相阻塞 —— flush 一次卡住的是三条写路径的判定收尾。
     */
    private val recordLock = Mutex()
    /**
     * 上次 flush 时间。初值取**构造时刻**而不是 0 —— 取 0 会让第一次命中立刻满足
     * `now - 0 >= HIT_FLUSH_INTERVAL_MS`，于是「内存累加」在最常见的场景（收到第一条被拦短信）
     * 完全没发生，退化成每命中一次一条 UPDATE。
     */
    @Volatile private var lastHitFlushAt = System.currentTimeMillis()

    // ══════════════════ 判定 ══════════════════

    /**
     * 按当前快照判定一条短信。
     *
     * 验证码提取器**只在豁免开关开着时才跑** —— 关掉豁免时这条判定与验证码无关，
     * 没必要在 5s 轮询上白跑一遍正则。
     */
    fun evaluate(sender: String, body: String): SmsFilter.Verdict {
        val snap = snapshot
        if (snap.isEmpty) return SmsFilter.Verdict.Allow
        val isVerificationCode =
            if (snap.exemptVerificationCode) SmsCodeExtractor.find(body) != null else false
        return SmsFilter.evaluate(sender, body, snap, isVerificationCode)
    }

    /** 快捷判据：只想知道「拦不拦」的读路径用这个。 */
    fun isBlocked(sender: String, body: String): Boolean =
        evaluate(sender, body) is SmsFilter.Verdict.Block

    /** 当前是否存在启用中的规则。读路径据此短路，连 lowercase 都省掉。 */
    fun hasActiveRules(): Boolean = !snapshot.isEmpty

    // ══════════════════ 快照重建 ══════════════════

    /**
     * 从 DAO 重建内存快照。规则 CRUD、豁免开关变更之后必须调用。
     *
     * 失败时**保留旧快照**：拿不到规则不等于用户删了规则，清成空的等于静默关闭整个拦截功能。
     */
    suspend fun reloadRules() {
        val dao = ruleDao ?: run {
            snapshot = SmsFilter.RuleSnapshot(emptyList(), exemptVerificationCode())
            return
        }
        try {
            val rules = dao.getEnabled().map { it.toFilterRule() }
            snapshot = SmsFilter.RuleSnapshot(rules, exemptVerificationCode())
            AppLogger.i(tag, "拦截规则快照已重建：${rules.size} 条启用，验证码豁免=${snapshot.exemptVerificationCode}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "拦截规则快照重建失败，保留上一份快照：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 启动期一次性工作：旧 `blacklist` 搬迁 + 首次加载快照。
     *
     * 异步跑，不阻塞 core 启动（HTTP 服务要等装配返回才起）。
     *
     * @param legacyBlacklist 由 `SmsForwardController.takeLegacyBlacklistForMigration()` 提供，
     *   已搬过或本来就空时是空列表。
     */
    fun startStartupMaintenance(legacyBlacklist: List<String>) {
        scope.launch {
            if (legacyBlacklist.isNotEmpty()) importLegacyBlacklist(legacyBlacklist)
            reloadRules()
        }
    }

    /**
     * 旧 `SmsForwardConfig.blacklist` → 规则表的一次性搬迁。
     *
     * 旧语义是「同一条目同时匹配发件人和正文的子串」，所以逐字对应
     * `scope = both, match_type = contains`。**不做语义修正**：搬迁的目标是行为不变，
     * 想把号码和关键词分开是用户在新界面里的事。
     */
    private suspend fun importLegacyBlacklist(patterns: List<String>): Int {
        val dao = ruleDao ?: return 0
        var imported = 0
        for (raw in patterns) {
            val pattern = raw.trim()
            if (pattern.isEmpty()) continue
            try {
                dao.insert(
                    SmsRule(
                        enabled = true,
                        scope = SmsFilter.SCOPE_BOTH,
                        match_type = SmsFilter.MATCH_CONTAINS,
                        pattern = pattern,
                        note = "从旧邮件黑名单迁移"
                    )
                )
                imported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "旧黑名单条目搬迁失败，跳过：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (imported > 0) AppLogger.i(tag, "旧邮件黑名单已搬迁为 $imported 条规则（scope=both, match=contains）")
        return imported
    }

    // ══════════════════ 规则 CRUD ══════════════════

    /**
     * 列出全部规则（含停用的）。
     *
     * 顺手 flush 一次内存里的命中计数：界面上「命中 N 次」是用户判断规则有没有生效的唯一线索，
     * 拉列表的时候还差着几次没写盘会让人以为规则没工作。
     */
    suspend fun listRules(): List<SmsRule> {
        flushHits()
        return ruleDao?.getAll() ?: emptyList()
    }

    suspend fun countRules(): Int = ruleDao?.countAll() ?: 0

    /** 新增规则，返回新 id（DAO 缺失时返回 null）。调用方负责先校验入参。 */
    suspend fun addRule(rule: SmsRule): Long? {
        val id = ruleDao?.insert(rule) ?: return null
        onRulesChanged()
        return id
    }

    /** 覆盖一条规则（含 enabled 切换）。 */
    suspend fun updateRule(rule: SmsRule) {
        ruleDao?.update(rule) ?: return
        onRulesChanged()
    }

    /** 按 id 查单条（PUT 时要拿现值做字段级合并）。 */
    suspend fun findRule(id: Long): SmsRule? = ruleDao?.getAll()?.firstOrNull { it.id == id }

    /** 删除规则。返回实际删除行数（0 = 该 id 不存在）。 */
    suspend fun deleteRule(id: Long): Int {
        val deleted = ruleDao?.deleteById(id) ?: 0
        if (deleted > 0) onRulesChanged()
        return deleted
    }

    /**
     * 规则变更后的收尾：重载快照 + 广播 `config_changed`。
     *
     * 顺序不能反 —— 先让 core 自己生效再通知别人，否则客户端收到广播回头 GET
     * 可能拿到还没生效的状态。
     */
    private suspend fun onRulesChanged() {
        reloadRules()
        broadcastRulesChanged()
    }

    /**
     * 广播 `config_changed { type: "sms_rules" }`（多端同步：A 端改规则，B 端在线即时刷新）。
     *
     * 机制照 `AlertEngine.broadcastConfigChanged`：fire-and-forget，路由层不等广播完成 ——
     * 广播要遍历所有订阅连接逐个发帧，让 HTTP 响应等它等于把一个慢客户端的代价转嫁给操作者。
     * 只带 type 与规则条数，不带规则全文：规则可能几十条，客户端本来就要 GET 列表。
     */
    fun broadcastRulesChanged() {
        val send = broadcaster ?: return
        scope.launch {
            try {
                send("config_changed", mapOf("type" to "sms_rules"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "sms_rules config_changed 广播失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ══════════════════ 拦截记录 ══════════════════

    /** 拦截记录读取：keyset 游标分页（形态与 `/api/alerts/list` 一致）。 */
    suspend fun listBlocked(cursorTs: Long?, cursorId: Long?, limit: Int): List<SmsBlockedLog> =
        logDao?.getPaged(cursorTs, cursorId, limit) ?: emptyList()

    suspend fun countBlocked(): Int = logDao?.countAll() ?: 0

    suspend fun deleteBlocked(id: Long): Int = logDao?.deleteById(id) ?: 0

    suspend fun clearBlocked() {
        logDao?.deleteAll()
    }

    /**
     * 写一条拦截记录（**只有三条写路径调用**：mail / push / vc）。
     *
     * 去重：
     * - `msgId != 0` → 按 msg_id 查已有记录，命中就把新 path 合并进 `blocked_path`，不新增行。
     *   一条短信会同时走「邮件」「推送」「验证码入库」，不合并的话一条短信能刷出三行。
     * - `msgId == 0`（邮件路径拿不到设备侧 id）→ 退化成 `sender` + [DEDUPE_WINDOW_MS] 窗口。
     *
     * 失败只记 WARN 不抛：记录写不进去不该让「这条短信被拦住」这件事失败。
     *
     * 「查已有记录 → 合并或插入」这段在 [recordLock] 里串行执行：三条写路径在不同协程并发跑，
     * `msg_id` 只是普通索引、没有唯一约束，不加锁时两个协程会同时判定「还没有记录」而各插一行，
     * 界面上一条短信出现两次，[bumpHit] 也双计。
     */
    suspend fun recordBlocked(
        msgId: Long,
        sender: String,
        body: String,
        block: SmsFilter.Verdict.Block,
        path: String
    ) {
        val dao = logDao ?: return
        val now = System.currentTimeMillis()
        try {
            recordLock.withLock {
                val existing = if (msgId != 0L) {
                    dao.findByMsgId(msgId)
                } else {
                    dao.findRecentBySender(sender, now - DEDUPE_WINDOW_MS)
                }
                if (existing != null) {
                    val merged = mergePath(existing.blocked_path, path)
                    if (merged != existing.blocked_path) {
                        dao.update(existing.copy(blocked_path = merged))
                    }
                } else {
                    dao.insert(
                        SmsBlockedLog(
                            msg_id = msgId,
                            sender = sender,
                            snippet = body.take(SNIPPET_CHARS),
                            // 隐私：全文默认不存。开关在 AppSettings，默认 false。
                            body = if (storeFullBody()) body else "",
                            rule_id = block.ruleId,
                            rule_pattern = block.pattern,
                            rule_scope = block.scope,
                            rule_match = block.matchType,
                            blocked_path = path,
                            blocked_at = now
                        )
                    )
                    // 环形裁剪：照 AlertEngine 的做法，insert 之后立刻裁。上限写死常量。
                    dao.trimTo(MAX_BLOCKED_ROWS)
                }
            }
            bumpHit(block.ruleId, now)
            // 正文只打 50 字，沿用 forwardLatestSmsIfNew 的既有做法。
            AppLogger.i(
                tag,
                "短信被拦：path=$path from=$sender " +
                    "rule=${block.ruleId}(${block.scope}/${block.matchType}) body=${body.take(50)}"

            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "拦截记录写入失败（拦截本身已生效）：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** `blocked_path` 合并：逗号拼接、去重、保留既有顺序。 */
    private fun mergePath(existing: String, added: String): String {
        val paths = LinkedHashSet<String>()
        existing.split(',').forEach { if (it.isNotBlank()) paths.add(it.trim()) }
        if (added.isNotBlank()) paths.add(added.trim())
        return paths.joinToString(",")
    }

    // ══════════════════ 命中计数 ══════════════════

    /**
     * 内存累加 + 到点 flush。
     *
     * 为什么不每次命中直接 UPDATE：命中发生在写路径上，而写路径里最密的那条
     * （`collectSmsCache` 的推送段）跑在 5s 轮询上。为什么不照 `PairedDeviceStore.touch`
     * 那样节流后全量重写：那个模式 2026-09-08 刚引发过配对信息丢失事故 ——
     * 全量重写会把重写期间的并发写整份盖掉。这里 flush 用的是**增量 UPDATE**
     * （`hit_count = hit_count + delta`），并发下也只会多加不会覆盖。
     */
    private suspend fun bumpHit(ruleId: Long, now: Long) {
        hitsLock.withLock {
            pendingHits[ruleId] = (pendingHits[ruleId] ?: 0) + 1
        }
        if (now - lastHitFlushAt >= HIT_FLUSH_INTERVAL_MS) flushHits()
    }

    /**
     * 把内存里累计的命中次数写盘。读规则列表时也会调一次（见 [listRules]）。
     *
     * 写失败时把 delta **还回内存**，下次再试：命中次数是用户判断规则有没有生效的依据，
     * 静默丢掉会让人以为规则没工作。
     *
     * 取消路径只回灌 `remaining`（还没写成功的那部分）。原来回灌的是整个 batch，
     * 而 batch 里包含循环中**已经写盘成功**的 ruleId —— 下一次 flush 会把它们再加一遍，
     * 命中次数越取消越大，比丢几次更难解释。
     */
    suspend fun flushHits() {
        val dao = ruleDao ?: return
        val batch = hitsLock.withLock {
            if (pendingHits.isEmpty()) return
            val copy = HashMap(pendingHits)
            pendingHits.clear()
            copy
        }
        lastHitFlushAt = System.currentTimeMillis()
        // 未落盘的余量：成功一条就移掉一条，取消时回灌的就只是真正没写进去的部分。
        val remaining = HashMap(batch)
        val failed = HashMap<Long, Int>()
        for ((ruleId, delta) in batch) {
            try {
                dao.bumpHit(ruleId, delta, lastHitFlushAt)
                remaining.remove(ruleId)
            } catch (e: CancellationException) {
                // 还回内存后再抛：取消不该吃掉已经发生的命中。
                hitsLock.withLock { remaining.forEach { (k, v) -> pendingHits[k] = (pendingHits[k] ?: 0) + v } }
                throw e
            } catch (e: Exception) {
                remaining.remove(ruleId)
                failed[ruleId] = delta
                AppLogger.w(
                    tag,
                    "命中次数 flush 失败，稍后重试：rule=$ruleId delta=$delta: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )

            }
        }
        if (failed.isNotEmpty()) {
            hitsLock.withLock { failed.forEach { (k, v) -> pendingHits[k] = (pendingHits[k] ?: 0) + v } }
        }
    }

    // ══════════════════ 收尾 ══════════════════

    /**
     * 停机收尾：先落盘再收协程。由 `BackendService.stopAllComponents()` 调。
     *
     * 顺序不能反 —— [scope] 一旦 cancel，后面的 [flushHits] 里的 DB 写就会被取消。
     * 为什么非要 flush：`pendingHits` 只在「下次命中且过了 60s」或 [listRules] 时才落盘，
     * core 被系统杀掉时最后一批命中数会直接丢，而「命中 N 次」是用户判断规则到底有没有生效的
     * 唯一线索 —— 丢掉之后用户看到的是「规则一次都没命中」，会去改规则而不是怀疑统计。
     */
    suspend fun shutdown() {
        flushHits()
        scope.cancel()
    }

    private companion object {
        /** 拦截记录环形上限。**写死常量，不做成配置项** —— 理由见 `SmsBlockedLogDao.trimTo`。 */
        const val MAX_BLOCKED_ROWS = 500

        /** 记录里的正文预览长度（隐私：默认只留这么多）。 */
        const val SNIPPET_CHARS = 120

        /** `msg_id == 0` 时的按发件人去重窗口。 */
        const val DEDUPE_WINDOW_MS = 60_000L

        /** 命中次数 flush 间隔。 */
        const val HIT_FLUSH_INTERVAL_MS = 60_000L
    }
}

/** entity → 快照用的轻量规则。 */
private fun SmsRule.toFilterRule() = SmsFilter.Rule(
    id = id,
    enabled = enabled,
    scope = scope,
    matchType = match_type,
    pattern = pattern
)

