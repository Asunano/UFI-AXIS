package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SettingKey
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
 */
internal class GoformSettingWriter(
    private val client: GoformClient,
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
     *
     * 下发走 [GoformClient.goformPostIdempotent]：设置类命令对同一取值幂等，会话失效时
     * 重登重试一次是安全的。不这么做的后果就是「切换网络制式第一次必定失败、再点一次才成」
     * —— 读路径早有这套重试，写路径一直没有。
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
        val body = LinkedHashMap<String, String>()
        body["goformId"] = spec.command
        body.putAll(spec.encode(params))
        return when (val result = client.goformPostIdempotent(body)) {
            // 设备表过态：body 说成功就是成功，说失败就是**设备明确拒绝**（不可重试）。
            is GoformWriteResult.Accepted ->
                if (client.isGoformSuccess(result.body)) WriteOutcome.Ok else WriteOutcome.Failed
            // 重登并重试一次后仍然会话失效：命令没进固件，属于可重试，别报成 500。
            is GoformWriteResult.SessionLost ->
                WriteOutcome.Unavailable("设备后台会话已失效，重新登录后仍未受理本次设置，请稍后重试")
            // 连不上设备：detail 是英文原文，只进日志，不进给用户看的文案。
            is GoformWriteResult.Unreachable -> {
                AppLogger.w(TAG, "$key write unreachable: ${result.detail}")
                WriteOutcome.Unavailable("与设备后台通信失败，请确认设备在线后重试")
            }
        }
    }


    private companion object {
        const val TAG = "GoformWrite"
    }
}
