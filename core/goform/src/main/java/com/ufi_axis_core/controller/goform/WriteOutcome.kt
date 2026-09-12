package com.ufi_axis_core.controller.goform

/**
 * 写入结果三态（计划书 9.5）。
 *
 * 原来所有写操作都返回 `Boolean`，于是**"参数非法"和"设备没写成"长得一样**：
 * route 一律回 500 `{"success": false}`，客户端既看不出是自己传错了，也拿不到原因，
 * 只能靠翻 core 日志。这里把"被 [com.ufi_axis_core.deviceschema.WriteSpec.validate]
 * 拒绝"单独拆出来，route 才能回 400 + `OUT_OF_RANGE` + 具体原因。
 *
 * 为什么放在 `:core:goform`：它出现在本模块公开方法的返回类型上，而
 * `:core:api` / `:core:controller` / `:core` 只依赖 `:core:goform`（不依赖 device-schema，
 * 也拿不到它 `implementation` 进来的 contract），放别处调用方会看不见类型。
 */
sealed interface WriteOutcome {

    /** 命令已下发且设备回了成功。 */
    data object Ok : WriteOutcome

    /**
     * 参数没过校验，**没有向设备发任何请求**。
     *
     * @param reason 面向调用者的原因（如 `"频段号 999 超出 1..255"`），可直接回给客户端。
     *   profile 的 `validate` 返回的就是这个字符串，不含设备字段名。
     */
    data class Rejected(val reason: String) : WriteOutcome

    /**
     * 命令进了固件、设备**明确回了失败**（或 profile 没登记这个写入项 = 该设备不支持这一项）。
     *
     * 这一态**不可重试**：设备已经表过态，同样的取值再发一次还是同样的结果。
     */
    data object Failed : WriteOutcome

    /**
     * 命令**没有被设备受理**：会话失效（core 已按 [GoformWritePolicy] 重登并重试过一次，
     * 仍然失效），或与设备的传输层就断了（连不上 / 超时）。
     *
     * 与 [Failed] 的分界是「固件有没有收下这条命令」，而不是「有没有报错」，
     * 完整判据见 [GoformWriteResult]。这一态语义上**可重试** —— 用户稍后再操作一次是有
     * 意义的 —— 所以 route 该回 503 `UNAVAILABLE`，不要回 500：500 在客户端只会显示成
     * 「服务器内部错误」，用户无从判断该不该再点一次，这正是网络制式"第一次必失败"
     * 被误报成服务端故障的原因。
     *
     * @param reason 面向用户的中文原因，可直接进响应文案（不含设备字段名与英文枚举）
     */
    data class Unavailable(val reason: String) : WriteOutcome

    /** 兼容既有 `Boolean` 调用点。 */
    val ok: Boolean get() = this is Ok
}
