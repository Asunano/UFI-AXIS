package com.ufi_axis_core.controller.goform

/**
 * 一次 `goform_set_cmd_process` 往返的**设备表态**三态。
 *
 * ## 为什么需要它
 *
 * `GoformClient.goformPost()` 返回 `String?`，null 把三件完全不同的事压成了一个值：
 * 「会话失效，命令根本没进固件」「连不上设备」「设备收下了并回了失败」。
 * 上层只能一律当失败处理，于是
 *  - 读路径（`queryInternal`）能在会话失效时重登重试一次、用户完全无感；
 *  - 写路径没有这个能力，一次会话抖动就等于一次用户可见的失败。
 * 而 UFI 的会话被设备官方 Web UI 顶掉、或被制式切换后的模块重注册弄失效是**常态**，
 * 所以「第一次点必失败、再点一次就好」在写操作上是必然，不是偶发。
 *
 * ## 三态的判据（改动这里前先读完）
 *
 * 分界线是**固件有没有受理这条命令**，而不是「有没有报错」：
 *  - [Accepted]：拿到了设备的业务响应体（HTTP 200 且不是登录页 / 鉴权失败体）。
 *    命令已经进了固件，body 里说成功还是失败都算「设备表过态了」，**重发无意义**。
 *  - [SessionLost]：没登录 / AD 前置查询拿不到 wa·cr·RD / 设备回登录页或鉴权失败体 /
 *    HTTP 非 200。这些情况下 set 命令**没有**落到固件，重登后重发既安全也必要。
 *  - [Unreachable]：传输层就断了（连不上、读超时、响应解码失败），**拿不到设备表态**。
 *    对幂等的设置命令重发本身安全，但重发解决不了"设备不在线"，所以不重试，
 *    直接把真因带给调用方。
 *
 * 非幂等命令（`SEND_SMS`）**不要**用这套三态去自动重试：见
 * [GoformSmsClient.SendVerdict.NO_RESPONSE] 的注释 —— 那里重试一次可能是第二笔话费。
 */
sealed interface GoformWriteResult {

    /** 设备回了业务响应体（内容可能是成功也可能是失败，由 `isGoformSuccess` 判定）。 */
    data class Accepted(val body: String) : GoformWriteResult

    /** 会话失效，命令未被固件受理 —— 唯一会触发重试的一态。 */
    data object SessionLost : GoformWriteResult

    /**
     * 传输层失败，拿不到设备表态。
     *
     * @param detail 英文原文的异常信息（日志与排查用，不直接展示给用户）
     */
    data class Unreachable(val detail: String) : GoformWriteResult
}
