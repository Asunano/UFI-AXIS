package com.ufi_axis_core.controller.goform

/**
 * 写操作的**重试与分类策略**，外加驱动重试的那圈循环（[runAttempts]）。
 *
 * 抽成独立对象只有一个理由：判定与循环里都**没有 HTTP**，抽出来就能在不起网络、不起设备的
 * 前提下用单测钉住"会话失效重试一次 / 明确拒绝不重试 / 重试有上限 / 非幂等命令绝不因业务
 * 失败重发"这几条，而不是靠读代码相信。真正的一次往返仍然在 [GoformClient.goformPostOnce]
 * 里，由 [runAttempts] 的 `send` 形参注入（同 `GoformSettingWriter.sendBySpec` 的分法）。
 */
internal object GoformWritePolicy {

    /**
     * 一次幂等写最多尝试的次数：**2 = 原始一次 + 重试一次**。
     *
     * 不许改大。设备侧的登录本身带指数退避（`GoformClient.ensureSession` 的 `failCount`），
     * 无界重试会在会话真的登不上时把退避一路叠满，最后所有读路径也一起被 `loginMutex` 拦掉
     * —— 那就是历史上"长时间断连、必须重启核心服务"的成因。
     */
    const val MAX_ATTEMPTS = 2

    /**
     * 这一次结果要不要因为**会话失效**再试一次。
     *
     * @param attemptNo 刚刚结束的是第几次尝试（从 1 开始）
     *
     * 判据：命令没被固件受理，也就是只有 [GoformWriteResult.SessionLost]。
     *  - [GoformWriteResult.Unreachable]：连不上设备，重试解决不了，应该把真因报给用户；
     *  - [GoformWriteResult.Accepted]：设备已经表过态，body 说失败的那一路由
     *    [shouldRetryBusinessFailure] 单独判（两者互斥，调用方按顺序问一次）。
     */
    fun shouldRetry(attemptNo: Int, result: GoformWriteResult): Boolean =
        attemptNo < MAX_ATTEMPTS && result is GoformWriteResult.SessionLost

    /**
     * 写操作在「设备回了 200 业务体、但 body 说失败」时，要不要重登后再试一次。
     *
     * 2026-09-15：原来这一路被无条件当成「设备明确拒绝」，直接 502 +「设备拒绝了本次
     * 网络制式切换」，不重登也不重试。真机上**每次冷启动后第一次切制式必然命中**它 ——
     * 固件在会话陈旧时并不总回登录页/非 200，也会回 200 + 业务失败体，于是
     * [classify] 把它归到 [GoformWriteResult.Accepted]，`isAuthFailure` 也认不出来。
     * 第二次点就好，正是因为第一次写已经顺带刷新了会话与 goform 快照。
     *
     * 2026-09-26 补一条边界（这段曾被读成「所有写操作都该这么重试」，不是）：
     * 上面那段只对**幂等**命令成立。写路径现在是三分法，各自重试什么见
     * [GoformWriteResult] 的那张表 —— `write` 什么都不重试、`writeIdempotent` 两种都重试、
     * `writeSessionSafe` 只重试 [GoformWriteResult.SessionLost]。
     *
     * 为什么重发不总是安全的：本判定只在调用方显式开了 `retryOnBusinessFailure` 时才被问
     * （[runAttempts] 的门控）。设置类命令（`SET_*` 这类**对同一取值幂等**的）开着它；
     * `SEND_SMS` 走 `writeSessionSafe`，**显式关掉** —— 设备回了 200 业务体意味着命令已经
     * 进过固件，重发有可能是第二条真短信、第二笔话费（判据见 [GoformWriteResult.Accepted]）。
     *
     * 上限仍是 [MAX_ATTEMPTS]：真的是参数被拒时，第二次会拿到同样的失败体，然后如实报错。
     */
    fun shouldRetryBusinessFailure(attemptNo: Int, result: GoformWriteResult, bodySuccess: Boolean): Boolean =
        attemptNo < MAX_ATTEMPTS && result is GoformWriteResult.Accepted && !bodySuccess


    /**
     * HTTP 层结果 → [GoformWriteResult]。
     *
     * @param statusCode HTTP 状态码
     * @param authFailure `GoformClient.isAuthFailure(body)` 的结论（登录页 / `result` 落在
     *   鉴权失败取值域里）
     *
     * 为什么非 200 也算 [GoformWriteResult.SessionLost]：固件在会话失效时的表现并不统一 ——
     * 可能回 200 + `login.html`，也可能直接回非 200。而**业务拒绝一定是 200 + 业务体**
     * （固件先鉴权、再解析 goformId、最后才落配置）。所以"非 200"归到可重试一侧不会把
     * 真正的拒绝误判成会话问题。
     */
    fun classify(statusCode: Int, authFailure: Boolean, body: String): GoformWriteResult =
        if (statusCode != HTTP_OK || authFailure) GoformWriteResult.SessionLost
        else GoformWriteResult.Accepted(body)

    /**
     * 一次写操作的**全部尝试**：最终结果 + 实际发了几次。
     *
     * `attempts` 只用于日志与断言（"第几次才成"是这个坑唯一能从外部看出来的痕迹）。
     */
    internal data class WriteAttempts(val result: GoformWriteResult, val attempts: Int)

    /**
     * 驱动"发一次 → 看结果 → 该不该重发"这圈循环。**两种重试语义由调用方分开开关。**
     *
     * 2026-09-26：本函数是从 `GoformClient.postMeasured` 原样搬出来的（循环体、判定顺序、
     * 日志文案逐字未变），搬出来的唯一目的是让下面这条红线**能被单测钉住**而不必起 HTTP：
     *
     * > `SEND_SMS` 在「设备回 200 + 业务失败体」时**绝不重发** —— 那一档命令已经进过固件，
     * > 重发有可能是第二条真短信、第二笔话费。
     *
     * 两个开关的分工：
     *  - [retryOnSessionLost]：命令**确定没落到固件**（[GoformWriteResult.SessionLost]）时重登重试。
     *    它同时是整圈循环的总闸 —— 关掉就一次都不重试（`write()` 就是这条）。
     *  - [retryOnBusinessFailure]：设备回了 200 业务体但 body 说失败时，**也**重登重试一次。
     *    只有对同一取值幂等的 `SET_*` 才许开（见 [shouldRetryBusinessFailure]）。
     *
     * 重试**必须**在 set 许可归还之后发起（[send] 内部的 `withSetPermit` 已退出），理由与
     * `GoformClient.queryInternal` 那段注释一致：持一个许可再去申请第二个，在许可被自适应
     * 调节压到 1 时就是永久挂死。上限由 [MAX_ATTEMPTS] 兜，不会无界重试。
     *
     * @param goformId 只用于日志（"第一次为什么被拒"必须能对上是哪条命令）
     * @param isSuccess 设备业务体是否表示成功（口径来自 `GoformClient.isSuccess`）
     * @param invalidateSession 业务失败重发前作废会话，让下一次 [send] 重登
     * @param warn 打 WARN 的出口（注入而非直接调 `AppLogger`，否则单测里断言不到）
     * @param send 发一次并给出设备表态
     */
    internal suspend fun runAttempts(
        goformId: String?,
        retryOnSessionLost: Boolean,
        retryOnBusinessFailure: Boolean,
        isSuccess: (String) -> Boolean,
        invalidateSession: () -> Unit,
        warn: (String) -> Unit,
        send: suspend () -> GoformWriteResult,
    ): WriteAttempts {
        var attemptNo = 1
        var result = send()
        // 两种可重试的失败（互斥，按顺序问一次）：
        //  ① 会话失效：登录页 / 非 200 / AD 算不出来；
        //  ② 设备回 200 但业务体说失败：固件在会话陈旧时也会走这一路，历史上被当成
        //     「设备明确拒绝」直接 502，表现为「冷启动后第一次切制式必失败，再点一次就好」。
        while (retryOnSessionLost && attemptNo < MAX_ATTEMPTS) {
            val acceptedBody = (result as? GoformWriteResult.Accepted)?.body
            if (shouldRetry(attemptNo, result)) {
                attemptNo++
                warn("[goform_set] session lost, re-login and retry (attempt=$attemptNo/$MAX_ATTEMPTS)")
            } else if (retryOnBusinessFailure && shouldRetryBusinessFailure(
                    attemptNo, result, acceptedBody?.let { isSuccess(it) } ?: true)
            ) {
                attemptNo++
                // 这条是 WARN 而不是 DEBUG：release/benchmark 包只保留 WARN 以上，
                // 而「第一次为什么被拒」只能靠设备回的这段 body 定性。
                warn(
                    "[goform_set] device returned business failure for " +
                        "goformId=$goformId, body=${acceptedBody?.take(BODY_LOG_CHARS)}; " +
                        "re-login and retry once (attempt=$attemptNo/$MAX_ATTEMPTS)"
                )
                // 陈旧会话是这一路最常见的成因，重发前先把会话作废，让下一次 send 重登。
                invalidateSession()
            } else {
                break
            }
            result = send()
        }
        return WriteAttempts(result, attemptNo)
    }

    /** 日志里最多带多少字符的设备响应体（够定性，又不至于把一整页 HTML 刷进 logcat）。 */
    internal const val BODY_LOG_CHARS = 200

    private const val HTTP_OK = 200
}
