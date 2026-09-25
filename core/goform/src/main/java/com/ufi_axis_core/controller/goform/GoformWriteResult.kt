package com.ufi_axis_core.controller.goform

/**
 * 一次 `goform_set_cmd_process` 往返的**设备表态**三态。
 *
 * ## 为什么需要它
 *
 * `GoformClient.goformPost()` 返回 `String?`，null 把三件完全不同的事压成了一个值：
 * 「会话失效，命令根本没进固件」「连不上设备」「设备收下了并回了失败」。
 * 只要压成一个值，上层就只能一律当失败处理 —— 而 UFI 的会话被设备官方 Web UI 顶掉、
 * 或被制式切换后的模块重注册弄失效是**常态**。
 *
 * ## 「第一次点必失败」到 2026-09-26 为止的完整结论（三分法，改动前读完）
 *
 * 这句话曾经写成"写路径没有重试能力，所以首次失败是必然"。**那已经是过期事实**：
 * 现在写路径有三个入口，各自重试什么是明确分工的，只有第一条还留着那个必然性 ——
 * 而那一条上的命令**本来就不容重发**。
 *
 * | 入口 | [SessionLost]（确定没发出） | [Accepted] + body 说失败 | 用在哪 |
 * |---|---|---|---|
 * | `GoformTransport.write` | 不重试 | 不重试 | `RetryPolicy.NEVER`：REBOOT / SHUTDOWN / FACTORY_RESET、删信箱、标已读 —— 重发一次就是再重启一次 |
 * | `GoformTransport.writeIdempotent` | 重试一次 | 重试一次 | `SET_*` 这类**对同一取值幂等**的设置命令（2026-09-11 / 09-15 两批修的就是它） |
 * | `GoformTransport.writeSessionSafe` | 重试一次 | **不重试** | `SEND_SMS`：非幂等，但"确定没发出"可以安全重发（2026-09-26 新增） |
 *
 * 本次（2026-09-26）新增第三条的原因：`SEND_SMS` 是全仓最后一条走 `write` 的**用户发起**
 * 写命令，于是"冷启动 / 会话被官方后台顶掉后第一次发短信必 HTTP 500、再点一次就好"在它
 * 身上一直是必然（第一次的失败路径顺带作废了会话，第二次因此走全新登录）。短信**读**路径
 * 改走 ContentResolver 之后，没有任何东西在给 goform 会话续期（校验 60s / 缓存 90s），
 * 这条必然性只会更容易撞上。
 *
 * ## 三态的判据（改动这里前先读完）
 *
 * 分界线是**固件有没有受理这条命令**，而不是「有没有报错」：
 *  - [Accepted]：拿到了设备的业务响应体（HTTP 200 且不是登录页 / 鉴权失败体）。
 *    命令已经进了固件，body 里说成功还是失败都算「设备表过态了」，**重发对非幂等命令不安全**。
 *  - [SessionLost]：没登录 / AD 前置查询拿不到 wa·cr·RD / 设备回登录页或鉴权失败体 /
 *    HTTP 非 200。这些情况下 set 命令**没有**落到固件，重登后重发既安全也必要。
 *  - [Unreachable]：传输层就断了（连不上、读超时、响应解码失败），**拿不到设备表态**。
 *    对幂等的设置命令重发本身安全，但重发解决不了"设备不在线"，所以不重试，
 *    直接把真因带给调用方。
 *
 * 非幂等命令（`SEND_SMS`）**不要**整套照抄这三态去自动重试：只有 [SessionLost] 那一态
 * 可以（见上表第三行与 `SendVerdict.REJECTED` 的注释，那个枚举在 `:core:device-spi`）。
 * [Accepted] + 业务失败重发一次就可能是第二笔话费。
 */
sealed interface GoformWriteResult {

    /** 设备回了业务响应体（内容可能是成功也可能是失败，由 `isGoformSuccess` 判定）。 */
    data class Accepted(val body: String) : GoformWriteResult

    /**
     * 会话失效，命令未被固件受理。
     *
     * 三个写入口里有两个会为它重登重试一次（`writeIdempotent` / `writeSessionSafe`；
     * `write` 一次都不重试），它也是 `writeSessionSafe` 唯一肯重发的一态：
     * 确定没发出 ⇒ 重发不会重复计费。
     */
    data object SessionLost : GoformWriteResult

    /**
     * 传输层失败，拿不到设备表态。
     *
     * @param detail 英文原文的异常信息（日志与排查用，不直接展示给用户）
     */
    data class Unreachable(val detail: String) : GoformWriteResult
}
