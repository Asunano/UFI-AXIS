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
     * 命令发出去了但没成功：设备回了失败、网络错误，或 profile 没登记这个写入项
     * （= 该设备不支持这一项）。
     */
    data object Failed : WriteOutcome

    /** 兼容既有 `Boolean` 调用点。 */
    val ok: Boolean get() = this is Ok
}
