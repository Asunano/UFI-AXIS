package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.RetryPolicy
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.util.AppLogger

/**
 * 写入侧的 profile 接入点 —— [GoformFieldMapper] 的对称物（计划书阶段 2）。
 *
 * ## 为什么写入侧没有"透传"回退
 *
 * 读侧的 kill switch 是 `profile = null` → 原样透传，这有意义：不归一化也能吐数据。
 * 写侧没有这个概念 —— 没有 `goformId` 就发不出请求。所以本类持有**非空** profile，
 * 调用方传 null 时回落到 [ZteGoformProfile]：字段归一化可以关，写命令表不能关。
 *
 * profile 没登记某个 [SettingKey] 时返回 false 并打日志（等价于"该设备不支持这一项"），
 * 而不是发一个空命令出去。
 *
 * ## 校验在这里生效
 *
 * [com.ufi_axis_core.deviceschema.WriteSpec.validate] 不通过就不下发。设备侧表单是
 * 字符串拼接，虽然 [GoformCodec.buildSetFormBody] 已经做了百分号编码，值域校验仍然要留 ——
 * 编码只防注入，不防"把频段设成 999"。
 *
 * ## 一条写入要问 spec 三件事（阶段 0 批 2 接上的三个字段）
 *
 * 1. **发哪条命令**：[WriteSpec.commandOf] 非空时按取值选，否则用 [WriteSpec.command]。
 *    设备把「同一个用户动作的开 / 关」做成两条不同 goformId 是常态（移动数据开
 *    `CONNECT_NETWORK`、关 `DISCONNECT_NETWORK`），这段选择必须留在 profile ——
 *    放回调用点就是「换设备时静默发错命令」。
 * 2. **失败能不能重发**：[WriteSpec.retry]，见 [sendBySpec]。
 * 3. **要不要换备用命令再试**：[WriteSpec.fallback]，见 [writeWithFallback]。
 *
 * ## 为什么决策逻辑全在 companion 的函数里
 *
 * 本类的构造参数是接口 [GoformTransport]（阶段 1 已接口化），端到端路径**可以**注入假对象了。
 * 而「选命令 / 拼 body / 选重试路径 / 把传输层结果收成三态 / 要不要兜底」这几件事都抽成了
 * 以 lambda 收传输层与日志出口的 internal 函数：生产路径传 [client] 的方法，单测传假发送器
 * （`GoformSettingWriterDecisionTest`）。这样 HTTP 之外的整条写路径都能被断言。
 */
internal class GoformSettingWriter(
    private val client: GoformTransport,
    profile: DeviceProfile?,
) {
    private val profile: DeviceProfile = profile ?: ZteGoformProfile

    /** 单值写操作（`value` 是约定的参数名）。 */
    suspend fun write(key: SettingKey, value: Any?): Boolean = write(key, mapOf("value" to value))

    /** 多参数写操作。 */
    suspend fun write(key: SettingKey, params: Map<String, Any?>): Boolean =
        writeChecked(key, params).ok

    /** 单值写操作，返回三态结果（校验失败要回给客户端时用，见 [WriteOutcome]）。 */
    suspend fun writeChecked(key: SettingKey, value: Any?): WriteOutcome =
        writeChecked(key, mapOf("value" to value))

    /**
     * 多参数写操作，返回三态结果。
     *
     * 校验不通过时**直接返回，不发请求** —— 这既是正确性也是安全性（设备侧表单是字符串拼接）。
     * 下发与兜底的判据见 [writeWithFallback] / [sendBySpec]。
     */
    suspend fun writeChecked(key: SettingKey, params: Map<String, Any?>): WriteOutcome {
        val spec = profile.writeSpec(key)
        if (spec == null) {
            AppLogger.w(TAG, "${profile.id} 未登记写入项 $key，忽略本次写入")
            return WriteOutcome.Failed
        }
        spec.validate(params)?.let { reason ->
            AppLogger.w(TAG, "$key 参数被拒绝：$reason")
            return WriteOutcome.Rejected(reason)
        }
        val warn: (String) -> Unit = { AppLogger.w(TAG, it) }
        return writeWithFallback(key, spec, params, warn) { each ->
            sendBySpec(
                key = key,
                spec = each,
                params = params,
                postIdempotent = { body -> client.writeIdempotent(body) },
                postPlain = { body -> client.write(body) },
                isSuccess = { body -> client.isSuccess(body) },
                warn = warn,
            )
        }
    }

    internal companion object {
        const val TAG = "GoformWrite"

        /** 会话失效（重登重试后仍然失效）的用户可见文案。 */
        const val SESSION_LOST_MESSAGE = "设备后台会话已失效，重新登录后仍未受理本次设置，请稍后重试"

        /** 传输层失败（连不上 / 超时 / 结果无从判断）的用户可见文案。 */
        const val UNREACHABLE_MESSAGE = "与设备后台通信失败，请确认设备在线后重试"

        /**
         * 本次要发的 `goformId`。
         *
         * [WriteSpec.commandOf] 非空时**覆盖** [WriteSpec.command]；两者都是纯函数，
         * 同一份 params 永远得到同一个命令名（profile 侧的硬性约束）。
         * [WriteSpec.command] 仍然必填，是默认命令名与日志/断言用的标识。
         */
        fun resolveCommand(spec: WriteSpec, params: Map<String, Any?>): String =
            spec.commandOf?.invoke(params) ?: spec.command

        /** 下发用的表单体：`goformId` + spec 自己编码出来的参数。 */
        fun buildBody(spec: WriteSpec, params: Map<String, Any?>): Map<String, String> {
            val body = LinkedHashMap<String, String>()
            body["goformId"] = resolveCommand(spec, params)
            body.putAll(spec.encode(params))
            return body
        }

        /**
         * 要不要换备用命令再试一次。
         *
         * ## 判据是「主结果不是 [WriteOutcome.Ok]」，不是「仅 [WriteOutcome.Failed]」
         *
         * 来源是被搬进来的那段现状代码：`setMobileData` 原本是「第一条 `goformPost` 返回
         * **不成功或 null** 就发第二条」，而 `goformPost` 返回 null 覆盖了三件事 ——
         * 会话失效、连不上设备、AD 算不出来。这三件在 writer 里分别落成
         * `Unavailable(SessionLost)` 与 `Unavailable(Unreachable)`：只在 `Failed` 时兜底，
         * 就会漏掉今天**确实会发第二条**的两种情形，那是行为变更，不是重构。
         *
         * [WriteOutcome.Rejected] 到不了这里 —— 校验失败时根本没发请求，调用方已提前返回。
         */
        fun shouldTryFallback(primary: WriteOutcome, spec: WriteSpec): Boolean =
            primary !is WriteOutcome.Ok && spec.fallback != null

        /**
         * 「主命令 →（不成功时）备用命令」的编排。
         *
         * 三条刻意的选择：
         * - **备用命令的结果就是最终结果**，不是「取两者里较好的那个」：现状 `setMobileData`
         *   返回的就是第二条命令的判断结果。
         * - **备用命令不跑 validate**：主 spec 的 validate 已经对同一份 params 跑过，
         *   params 在两条命令之间没有任何改动；而现状的第二条命令根本没有校验 ——
         *   在这里补一次就可能把今天必发的第二条拦下来。所以备用 spec 自己登记了 validate 也跳过。
         * - **只试一次、不递归**：`fallback.fallback` 忽略并 warn。选日志而不是抛异常/断言，
         *   是因为嵌套 fallback 属于 profile 登记错误（配置问题），不该让用户点一下之后崩在写路径上。
         *
         * 兜底触发时**必须留一行日志**（主命令名 + 结果类型 + 备用命令名）：这条链路曾经静默
         * 发生过一次，排障时完全看不出设备到底收到了哪条命令 —— 可观测性在这里是硬要求。
         *
         * @param send 怎么把一条 spec 发出去（生产是 [sendBySpec]，单测是假发送器）
         * @param warn 日志出口（生产是 [AppLogger.w]）
         */
        suspend fun writeWithFallback(
            key: SettingKey,
            spec: WriteSpec,
            params: Map<String, Any?>,
            warn: (String) -> Unit,
            send: suspend (WriteSpec) -> WriteOutcome,
        ): WriteOutcome {
            val primary = send(spec)
            if (!shouldTryFallback(primary, spec)) return primary
            val fallback = spec.fallback ?: return primary
            warn(
                "$key 主命令 ${resolveCommand(spec, params)} 未成功（${outcomeTag(primary)}），" +
                    "改发备用命令 ${resolveCommand(fallback, params)}"
            )
            if (fallback.fallback != null) {
                warn(
                    "$key 的备用命令 ${resolveCommand(fallback, params)} 自己还登记了 fallback" +
                        " —— 已忽略：兜底只试一次，不递归"
                )
            }
            return send(fallback)
        }

        /**
         * 按 spec 发一条命令，并把两条传输层路径的结果收敛成同一套三态。
         *
         * ## 重试路径由 spec 说了算
         *
         * - [RetryPolicy.RETRY_ON_SESSION_LOSS] → [GoformTransport.writeIdempotent]
         *   （会话失效时重登并重试一次）。漏标这一项就会复发「切换网络制式第一次必定失败、
         *   再点一次才成」那个 bug —— 读路径早有这套重试，写路径一直没有。
         * - [RetryPolicy.NEVER] → [DeviceTransport.write]（**不重试**）。动作类 / 有副作用的
         *   命令（重启、关机、恢复出厂、改后台口令）重发一次的后果分别是再重启一次、在断电设备上
         *   白等一轮、出厂口令下的第二次擦除、拿旧口令再登一次。
         *
         * 两条路径的返回类型不同（[GoformWriteResult] vs `String?`），`NEVER` 分支也必须收敛成
         * 同样的三态：`null` 是「会话失效 / 连不上 / AD 算不出」被 [DeviceTransport.write] 压平
         * 后的同一个值，无法再分开，所以统一报 `Unavailable` 且用与 `Unreachable` **相同**的文案
         * —— 在这里猜是哪一种就是编造信息。
         */
        suspend fun sendBySpec(
            key: SettingKey,
            spec: WriteSpec,
            params: Map<String, Any?>,
            postIdempotent: suspend (Map<String, String>) -> GoformWriteResult,
            postPlain: suspend (Map<String, String>) -> String?,
            isSuccess: (String) -> Boolean,
            warn: (String) -> Unit,
        ): WriteOutcome {
            val body = buildBody(spec, params)
            return when (spec.retry) {
                RetryPolicy.RETRY_ON_SESSION_LOSS -> {
                    val result = postIdempotent(body)
                    // 连不上设备：detail 是英文原文，只进日志，不进给用户看的文案。
                    if (result is GoformWriteResult.Unreachable) {
                        warn("$key write unreachable: ${result.detail}")
                    }
                    interpretRetryable(result, isSuccess)
                }
                RetryPolicy.NEVER -> interpretPlain(postPlain(body), isSuccess)
            }
        }

        /** 可重试路径（[GoformTransport.writeIdempotent]）的结果 → 三态。 */
        fun interpretRetryable(
            result: GoformWriteResult,
            isSuccess: (String) -> Boolean,
        ): WriteOutcome = when (result) {
            // 设备表过态：body 说成功就是成功，说失败就是**设备明确拒绝**（不可重试）。
            is GoformWriteResult.Accepted ->
                if (isSuccess(result.body)) WriteOutcome.Ok else WriteOutcome.Failed
            // 重登并重试一次后仍然会话失效：命令没进固件，属于可重试，别报成 500。
            is GoformWriteResult.SessionLost -> WriteOutcome.Unavailable(SESSION_LOST_MESSAGE)
            is GoformWriteResult.Unreachable -> WriteOutcome.Unavailable(UNREACHABLE_MESSAGE)
        }

        /** 不重试路径（[DeviceTransport.write]）的结果 → 三态。`null` 只能报成不可用。 */
        fun interpretPlain(body: String?, isSuccess: (String) -> Boolean): WriteOutcome = when {
            body == null -> WriteOutcome.Unavailable(UNREACHABLE_MESSAGE)
            isSuccess(body) -> WriteOutcome.Ok
            else -> WriteOutcome.Failed
        }

        /** 兜底日志里的结果标签（只说类型，不带设备原文与参数值）。 */
        fun outcomeTag(outcome: WriteOutcome): String = when (outcome) {
            is WriteOutcome.Ok -> "Ok"
            is WriteOutcome.Failed -> "Failed/设备明确拒绝"
            is WriteOutcome.Unavailable -> "Unavailable/未被受理"
            is WriteOutcome.Rejected -> "Rejected/参数被拒"
        }
    }
}
