package com.ufi_axis_core.controller.sms

/**
 * 短信验证码提取 —— **全 core 唯一实现**。
 *
 * ## 为什么要有这个文件（2026-09-08）
 *
 * 在此之前同一件事有两份独立实现，且判据不同：
 *
 * - `MailTemplate.extractCode(body)`（邮件路径用）：提示词 `验证码/校验码/动态码/口令/code/OTP`，
 *   4-8 位数字，**完全没有位置约束** —— 只要正文含提示词，就取全文第一个 4-8 位数字。
 * - `SmsController.extractCode(...)`（验证码入库用）：提示词 `验证码/校验码/动态码/确认码/密码`，
 *   只认 **4 位或 6 位**，取关键词前后 ±20 字符窗口内的**第一个**匹配。
 *
 * 这带来三个真实故障：
 *
 * 1. **两边都会抓错号码**（旧 `SmsController` 的窗口并没有真正解决这个问题）：
 *    「您的余额 567890 元，验证码 1234」—— 关键词在第 14 字，`14 - 20 < 0` 使窗口左边界被夹到 0，
 *    于是 `567890` 也落在窗口内且位置更靠前，两份实现都会取它。邮件主题变成「验证码：567890」，
 *    而主题是收件箱列表里唯一可见的信息。
 * 2. **邮件被静默丢弃**：`SmsForwardController.forwardSms` 用提取结果决定场景
 *    （抓到码 → `verification`，否则 → `sms`），再用 `scene !in cfg.scenes` 拦。
 *    「【X】确认码 1234」在旧邮件实现里抓不到码（它没有「确认码」）→ 判成 `sms` 场景 →
 *    只勾了「验证码」的用户收不到这封邮件，而他以为验证码转发是开着的。
 * 3. **验证码库漏收**：银行常见的 8 位验证码旧入库实现一律不认（只认 4/6 位），
 *    通知 Tab 里永远看不到。
 *
 * 现在两处都委托到这里。判据：
 * - 提示词 = 两边的**并集**，`ignoreCase`（对 `code` / `OTP` 才有意义）
 * - 位数 = **4-8**（收回以前漏掉的 5/7/8 位）
 * - 窗口 ±[WINDOW_CHARS] 字符，且**取窗口内距离关键词最近的那个数字**，同距优先取关键词之后的。
 *   这一条才是真正修掉故障 1 的地方：「取第一个」在关键词靠前时必然被前面的无关数字截胡，
 *   而「取最近的」符合中文短信「验证码 1234」「1234 是您的验证码」两种真实语序。
 *
 * ## 不要在这里加"是不是垃圾短信"之类的判断
 * 这个对象只回答「这条短信里的验证码是什么」。拦截判定在 `SmsFilter`，
 * 两者的关系是：拦截规则可以配置「验证码短信豁免关键词拦截」，那时才会来问这里。
 * 把两件事混进同一个函数，会让豁免行为随调用路径漂移 —— 那正是上面故障 2 的成因。
 */
internal object SmsCodeExtractor {

    /**
     * @param code 提取到的验证码
     * @param keyword 命中的提示词（原样返回声明形式，用于落库的 `keyword` 字段）
     */
    data class Match(val code: String, val keyword: String)

    /**
     * 提示词。顺序即优先级：靠前的先尝试，与旧 `SmsController` 实现一致
     * （「验证码」优先于「密码」，避免「初始密码 x，验证码 y」取到前者）。
     *
     * 「密码」偏宽（「您的初始密码是 123456」也会被判成验证码），但旧验证码库一直认它，
     * 去掉会让部分存量记录不再被识别，故保留。
     */
    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "确认码", "口令", "密码", "code", "OTP"
    )

    /** 关键词前后各取多少字符作为找数字的窗口。 */
    private const val WINDOW_CHARS = 20

    /** 4-8 位纯数字，且前后不能再接数字（避免从长数字串里截一段）。 */
    private val DIGIT_PATTERN = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    /**
     * 从短信正文里提取验证码。无匹配返回 null。
     *
     * 匹配过程：先在**全文**上跑一次数字正则拿到全部候选（只跑一次，这是 5s 轮询上的热路径；
     * 也避免切片破坏 `(?<!\d)`/`(?!\d)` 的上下文而误匹配被截断的长数字串），
     * 再按 [KEYWORDS] 顺序逐个找提示词的每一次出现，取该次出现窗口内**距离最近**的候选。
     */
    fun find(body: String): Match? {
        if (body.isBlank()) return null
        val candidates = DIGIT_PATTERN.findAll(body).toList()
        if (candidates.isEmpty()) return null

        for (keyword in KEYWORDS) {
            var searchStart = 0
            while (searchStart <= body.length) {
                val kwIndex = body.indexOf(keyword, searchStart, ignoreCase = true)
                if (kwIndex < 0) break
                val kwEnd = kwIndex + keyword.length

                val best = candidates
                    .filter { it.range.first >= kwIndex - WINDOW_CHARS && it.range.last < kwEnd + WINDOW_CHARS }
                    // 距离 = 到关键词边界的字符数；forward=false 的（关键词之前）同距时让位
                    .minWithOrNull(
                        compareBy(
                            { if (it.range.first >= kwEnd) it.range.first - kwEnd else kwIndex - it.range.last },
                            { if (it.range.first >= kwEnd) 0 else 1 }
                        )
                    )
                if (best != null) return Match(code = best.groupValues[1], keyword = keyword)

                searchStart = kwEnd
            }
        }
        return null
    }
}
