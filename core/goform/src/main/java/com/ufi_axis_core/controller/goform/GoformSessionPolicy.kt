package com.ufi_axis_core.controller.goform

/**
 * 会话生命周期的**判定与调参**：什么时候可以直接用缓存、什么时候要校验、什么时候必须重登，
 * 以及两条踩过坑的保护（官方后台让位窗口、登录失败指数退避）。
 *
 * 抽成独立对象的理由与 [GoformWritePolicy] 一致：判定里**没有 HTTP**，抽出来才能在不起网络、
 * 不起设备（[GoformClient] 一构造就起 Ktor client，`AppLogger` 又依赖 `android.util.Log`）的
 * 前提下用单测钉住下面这几条，而不是靠读代码相信：
 *
 *  - 「强制校验」真的**绕过了 60s 校验间隔**，而默认路径**仍然遵守**它；
 *  - 让位窗口 / 登录退避**不因为"强制"被绕过**。
 *
 * 真正的一次 `cmd=RD` 往返仍然在 `GoformClient.validateSession()` 里，本对象只回答「该不该发」。
 *
 * ## 时间常量为什么住在这里
 *
 * 它们是**判定的一部分**（判定就是拿"过了多久"跟它们比），放在 [GoformClient] 里的话单测只能
 * 自己抄一遍数字 —— 那就变成「测试和生产各有一份 60s」，改一处另一处不会红。
 */
internal object GoformSessionPolicy {

    /** 每 60s 验证一次会话（一次轻量 `cmd=RD` GET）。 */
    internal const val SESSION_VALIDATION_INTERVAL_MS = 60_000L

    // 2026-08-23: TTL 从 300s 缩到 90s —— ZTE 设备实际会话超时通常 1-3 分钟，
    // 太长的 TTL 会让确保登录一直走缓存路径，等到下一次真断了才一起 invalidate 一波。
    internal const val SESSION_CACHE_TTL_MS = 90_000L

    /** 登录失败退避的基数：backoff = base * 2^failCount（封顶 [MAX_LOGIN_BACKOFF_MS]）。 */
    internal const val BASE_LOGIN_COOLDOWN_MS = 1500L

    // 2026-08-23: 退避上限从 60s 降到 10s —— 之前连续 session 失效会让退避叠到 60s，
    // 前端轮询全被拦截，造成「长时间断连，必须手动重启核心服务」体感。10s 已足够
    // 让设备 goform 释放被官方后台占用的 session，又不超出用户耐心。
    internal const val MAX_LOGIN_BACKOFF_MS = 10_000L

    /** 让位退避：检测到官方后台在线时主动避让 30s。 */
    internal const val GIVE_WAY_DURATION_MS = 30_000L

    /** 指数退避的移位上限（1500 * 2^6 = 96s，已远超 [MAX_LOGIN_BACKOFF_MS]，再大没有意义）。 */
    private const val MAX_BACKOFF_SHIFT = 6

    /** 拿到会话前该做什么。 */
    internal enum class SessionGate {
        /** 缓存里那份就能用，一个字节都不用发。 */
        USE_CACHED,

        /** 发一次轻量 `cmd=RD` 校验；通过就继续用这份会话，不通过才重登。 */
        VALIDATE,

        /** 直接走完整登录流程（LD → LOGIN_MULTI_USER / LOGIN）。 */
        LOGIN,
    }

    /**
     * 判定这一次 `ensureSession()` 该走哪条路。
     *
     * @param loggedIn 当前会话快照是否处于登录态
     * @param sinceValidatedMs 距上次成功校验/登录过了多久（`now - lastValidatedAt`）
     * @param forceValidate **强制校验**：只跳过 [SESSION_VALIDATION_INTERVAL_MS] 这道间隔，
     *   让本次一定发出那一次 `cmd=RD`。它**不是**「强制重登」——
     *   重登太重（要走 LD + LOGIN 两三个往返），而且会去撞 [inLoginBackoff] 与
     *   [shouldGiveWay] 两条保护，反而制造新的首次失败。校验**失败**时才落到 [SessionGate.LOGIN]。
     *
     * 判定顺序就是既有代码的三行 if，逐条对应（改造时逐位保持）：
     *  1. 没登录态 ⇒ [SessionGate.LOGIN]；
     *  2. 有登录态且没到校验周期（且不是强制）⇒ [SessionGate.USE_CACHED]；
     *  3. 有登录态且还在 TTL 内 ⇒ [SessionGate.VALIDATE]；
     *  4. 超出 TTL ⇒ 这份会话按陈旧处理，[SessionGate.LOGIN]。
     *
     * 注意第 4 条对强制校验也成立：TTL 都过了，再花一次 GET 去校验一个大概率已死的会话不划算，
     * 而「强制」要保证的是**下发前会话是新鲜的**，重登同样满足这一点。
     */
    internal fun gate(
        loggedIn: Boolean,
        sinceValidatedMs: Long,
        forceValidate: Boolean = false,
        validationIntervalMs: Long = SESSION_VALIDATION_INTERVAL_MS,
        cacheTtlMs: Long = SESSION_CACHE_TTL_MS,
    ): SessionGate = when {
        !loggedIn -> SessionGate.LOGIN
        !forceValidate && sinceValidatedMs < validationIntervalMs -> SessionGate.USE_CACHED
        sinceValidatedMs < cacheTtlMs -> SessionGate.VALIDATE
        else -> SessionGate.LOGIN
    }

    /**
     * 是否还在**官方后台让位窗口**里（此刻一律不许尝试登录）。
     *
     * 设备只给一个后台会话：检测到「result=session」（官方 Web UI 正在用）之后硬抢会变成
     * 双方互相踢，表现是两边都时断时续。所以撞见一次就主动躲开 [GIVE_WAY_DURATION_MS]。
     *
     * `lastGiveWayAt > 0` 这个条件不能省：0 是「从来没让过位」的哨兵值，省掉它会让
     * 冷启动后的前 30s 全部被判成在让位窗口内。
     */
    internal fun shouldGiveWay(
        lastGiveWayAt: Long,
        now: Long,
        giveWayDurationMs: Long = GIVE_WAY_DURATION_MS,
    ): Boolean = lastGiveWayAt > 0L && (now - lastGiveWayAt) < giveWayDurationMs

    /** 当前失败次数对应的退避时长；[failCount] <= 0 时为 0（没有失败就没有退避）。 */
    internal fun loginBackoffMs(
        failCount: Int,
        baseCooldownMs: Long = BASE_LOGIN_COOLDOWN_MS,
        maxBackoffMs: Long = MAX_LOGIN_BACKOFF_MS,
    ): Long =
        if (failCount <= 0) 0L
        else (baseCooldownMs * (1L shl failCount.coerceAtMost(MAX_BACKOFF_SHIFT)))
            .coerceAtMost(maxBackoffMs)

    /**
     * 是否还在**登录退避**里（此刻一律不许再尝试登录）。
     *
     * 2026-08-24 的结论（不许回退）：不论当前 `isLoggedIn` 是否为 true，只要有失败记录就应用退避。
     * 否则在连续登录失败且 `isLoggedIn=false` 时会陷入无退避的死循环，压满 QoS。
     *
     * `lastLoginAttempt > 0L` 同样是哨兵判断：一次都没尝试过时不该被退避拦住。
     */
    internal fun inLoginBackoff(
        failCount: Int,
        lastLoginAttempt: Long,
        now: Long,
        baseCooldownMs: Long = BASE_LOGIN_COOLDOWN_MS,
        maxBackoffMs: Long = MAX_LOGIN_BACKOFF_MS,
    ): Boolean =
        failCount > 0 &&
            lastLoginAttempt > 0L &&
            (now - lastLoginAttempt) < loginBackoffMs(failCount, baseCooldownMs, maxBackoffMs)
}
