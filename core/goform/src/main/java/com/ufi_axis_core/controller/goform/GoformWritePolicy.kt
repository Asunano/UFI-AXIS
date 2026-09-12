package com.ufi_axis_core.controller.goform

/**
 * 幂等写操作的**重试与分类策略**。
 *
 * 抽成独立对象只有一个理由：这两件事是纯判定，抽出来就能在不起 HTTP、不起设备的前提下
 * 用单测钉住"会话失效重试一次 / 明确拒绝不重试 / 重试有上限"这三条，而不是靠读代码相信。
 * 真正的 HTTP 往返仍然在 [GoformClient] 里。
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
     * 这一次结果要不要再试一次。
     *
     * @param attemptNo 刚刚结束的是第几次尝试（从 1 开始）
     *
     * 判据只有一条：**命令没被固件受理**才重试，也就是只有
     * [GoformWriteResult.SessionLost]。
     *  - [GoformWriteResult.Accepted]：设备已经表过态（哪怕 body 里是失败）→ 明确拒绝，
     *    同样的取值再发一次还是同样的结果，重试只是白等一个往返；
     *  - [GoformWriteResult.Unreachable]：连不上设备，重试解决不了，应该把真因报给用户。
     */
    fun shouldRetry(attemptNo: Int, result: GoformWriteResult): Boolean =
        attemptNo < MAX_ATTEMPTS && result is GoformWriteResult.SessionLost

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

    private const val HTTP_OK = 200
}
