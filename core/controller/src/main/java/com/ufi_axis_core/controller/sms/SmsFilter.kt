package com.ufi_axis_core.controller.sms

/**
 * 短信拦截判定 —— **全 core 唯一判据**，纯函数。
 *
 * ## 为什么必须是纯函数 + 内存快照
 *
 * 一条入站短信有三条互相独立的处理路径（邮件转发 / WS 推送 / 验证码入库），
 * 再加上列表、计数、联系人聚合，一共 6 个地方要问同一个问题「这条该不该拦」。
 * 其中 `DataScheduler.collectSmsCache` 跑在 5s 轮询上 —— 判定函数内部
 * **绝不能读 prefs、绝不能查 DB**，否则每 5 秒就多出一批 SharedPreferences /
 * Room 查询，而且六个接入点各查一次还会互相不一致。
 *
 * 规则以 [RuleSnapshot] 形式传进来，快照的持有与失效在 [SmsRuleStore]。
 * 需要 lowercase 之类的预处理都在快照构建时做完（[Rule.patternLower]），
 * 不在每条短信上重算。
 *
 * ## 匹配语义（决策已定，**不实现正则**）
 *
 * - [Rule.matchType]：`contains` / `equals` / `prefix` / `suffix`，全部忽略大小写；
 * - [Rule.scope]：`sender` 只看发件人，`body` 只看正文，`both` 两者任一命中；
 * - `enabled = false` 的规则直接跳过（防假开关：UI 关掉了引擎就必须真的不看）；
 * - 命中多条时返回**第一条**（按快照顺序，即 `created_at DESC`）。
 *
 * ## 验证码豁免
 *
 * 开关开启且这条被判定为验证码时，跳过 `scope = body` 的规则，但
 * **`scope = sender` 以及 `scope = both` 的 sender 侧仍然生效** ——
 * 拉黑一个号码是明确意图，不该被「它发来的是验证码」绕过；关键词是模糊匹配，
 * 误伤验证码的代价最高，所以只豁免关键词那一侧。
 *
 * 「是不是验证码」的判定统一走 [SmsCodeExtractor]，不在这里另写一份，
 * 否则豁免行为会随调用路径漂移。
 */
object SmsFilter {

    // ── scope 取值 ──
    const val SCOPE_SENDER = "sender"
    const val SCOPE_BODY = "body"
    const val SCOPE_BOTH = "both"

    /** 合法 scope 集合（API 层校验用）。 */
    val SCOPES: Set<String> = setOf(SCOPE_SENDER, SCOPE_BODY, SCOPE_BOTH)

    // ── matchType 取值 ──
    const val MATCH_CONTAINS = "contains"
    const val MATCH_EQUALS = "equals"
    const val MATCH_PREFIX = "prefix"
    const val MATCH_SUFFIX = "suffix"

    /** 合法 matchType 集合（API 层校验用）。 */
    val MATCH_TYPES: Set<String> = setOf(MATCH_CONTAINS, MATCH_EQUALS, MATCH_PREFIX, MATCH_SUFFIX)

    /**
     * 快照里的轻量规则。
     *
     * 只带判定要用的 5 个字段 —— `note` / `hit_count` / `created_at` 是展示与统计用的，
     * 放进热路径的快照里只会让每次规则变更多复制一堆无关数据。
     */
    data class Rule(
        val id: Long,
        val enabled: Boolean,
        val scope: String,
        val matchType: String,
        val pattern: String
    ) {
        /** 预处理：小写形式在快照构建时算一次，不在每条短信上重算。 */
        internal val patternLower: String = pattern.lowercase()
    }

    /**
     * 判定快照。构造即冻结，由 [SmsRuleStore] 以 `@Volatile` 持有并在规则变更后整体替换 ——
     * 不做原地修改，读侧因此不需要加锁。
     */
    class RuleSnapshot(
        val rules: List<Rule>,
        val exemptVerificationCode: Boolean
    ) {
        /**
         * 一条可用规则都没有 → 六个接入点可以直接短路，连 lowercase 都不用做。
         *
         * 用 `val` 在构造时算一次，不用 `get()`：这个判据每条短信都要问一遍
         * （`collectSmsCache` 是 5s 热路径），getter 每次都会重新遍历整个规则列表。
         */
        val isEmpty: Boolean = rules.none { it.enabled && it.pattern.isNotBlank() }

        companion object {
            /** 空快照（还没加载完 / 降级装配没有 DAO 时用，语义 = 谁都不拦）。 */
            val EMPTY = RuleSnapshot(emptyList(), exemptVerificationCode = true)
        }
    }

    sealed interface Verdict {
        /** 放行。 */
        object Allow : Verdict

        /**
         * 拦截。带上命中规则的**快照**（pattern/scope/matchType），
         * 因为拦截记录要在规则被删之后仍然读得懂。
         */
        data class Block(
            val ruleId: Long,
            val pattern: String,
            val scope: String,
            val matchType: String
        ) : Verdict
    }

    /**
     * 判定单条短信。
     *
     * @param isVerificationCode 这条是不是验证码（由调用方用 [SmsCodeExtractor] 算好传进来；
     *   之所以不在这里算，是因为豁免开关关闭时根本不需要跑提取器 —— 见 [SmsRuleStore.evaluate]）
     */
    fun evaluate(
        sender: String,
        body: String,
        snapshot: RuleSnapshot,
        isVerificationCode: Boolean
    ): Verdict {
        if (snapshot.rules.isEmpty()) return Verdict.Allow

        // lowercase 一次，供全部规则复用（规则侧的 patternLower 已在快照构建时算好）
        val senderLower = sender.lowercase()
        val bodyLower = body.lowercase()
        val exempt = snapshot.exemptVerificationCode && isVerificationCode

        for (rule in snapshot.rules) {
            if (!rule.enabled) continue
            // 空 pattern 用 contains 匹配会命中一切短信，等于一键静默全部消息，直接跳过。
            // 必须用 isBlank() 而不是 isEmpty()：判据要和 [RuleSnapshot.isEmpty] 的 pattern.isNotBlank()
            // 完全一致。用 isEmpty() 时纯空白 pattern（DB 被外部改动写进来的）不会被跳过，而它一旦
            // 与其它有效规则共存，快照就不是 empty、六个接入点都不会短路，contains(" ") 命中几乎
            // 所有短信 → 全部短信被静默拦掉。
            if (rule.patternLower.isBlank()) continue

            val checkSender = rule.scope == SCOPE_SENDER || rule.scope == SCOPE_BOTH
            // 豁免只砍掉正文侧：scope=body 整条失效，scope=both 退化成只看 sender。
            val checkBody = (rule.scope == SCOPE_BODY || rule.scope == SCOPE_BOTH) && !exempt

            val hit = (checkSender && matches(senderLower, rule)) ||
                (checkBody && matches(bodyLower, rule))
            if (hit) {
                return Verdict.Block(
                    ruleId = rule.id,
                    pattern = rule.pattern,
                    scope = rule.scope,
                    matchType = rule.matchType
                )
            }
        }
        return Verdict.Allow
    }

    /**
     * 单侧匹配。[target] 必须已经是小写形式。
     *
     * 未知 matchType 一律**不匹配**：宁可「规则不生效」也不要「规则拦错东西」——
     * 拦截是静默丢消息，误拦的代价远高于漏拦。API 层已经按 [MATCH_TYPES] 校验过入参，
     * 走到这里还能出现未知值只可能是 DB 被外部改坏。
     */
    private fun matches(target: String, rule: Rule): Boolean = when (rule.matchType) {
        MATCH_CONTAINS -> target.contains(rule.patternLower)
        MATCH_EQUALS -> target == rule.patternLower
        MATCH_PREFIX -> target.startsWith(rule.patternLower)
        MATCH_SUFFIX -> target.endsWith(rule.patternLower)
        else -> false
    }
}
