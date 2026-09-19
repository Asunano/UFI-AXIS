package com.ufi_axis.installer.goform

/**
 * 单次 goform_set_cmd_process 的判定结果，区分三件事：
 *  - [Accepted] 设备确认执行（响应体里 result=success 等）；
 *  - [SessionLost] 会话失效 / 鉴权失败（可重登重试）；
 *  - [Unreachable] 传输层不可达（网络错、超时）；
 *  - [Rejected] 连上了但设备侧拒绝（result 非成功、有 Error 等）。
 */
sealed class GoformWriteResult {
    data class Accepted(val body: String) : GoformWriteResult()
    data object SessionLost : GoformWriteResult()
    data class Unreachable(val reason: String) : GoformWriteResult()
    data class Rejected(val body: String?) : GoformWriteResult()
}
