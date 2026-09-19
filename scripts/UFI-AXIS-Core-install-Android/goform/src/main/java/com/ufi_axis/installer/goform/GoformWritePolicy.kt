package com.ufi_axis.installer.goform

/**
 * 写操作重试策略（移植自 core/goform 的 [GoformWritePolicy]，仅保留最小逻辑）。
 *
 * 设置类命令对同一取值幂等，重发安全；非幂等命令（如发短信）调用方应使用
 * [GoformClient.goformPost] 而非 [GoformClient.goformPostIdempotent]，避免重复执行。
 */
object GoformWritePolicy {
    const val MAX_ATTEMPTS = 2

    /** 仅在「会话失效」时重登后重试一次。 */
    fun shouldRetry(attemptNo: Int, result: GoformWriteResult): Boolean =
        attemptNo < MAX_ATTEMPTS && result is GoformWriteResult.SessionLost
}
