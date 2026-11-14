package com.ufi_axis.installer.goform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

/**
 * Goform 防腐层契约（移植自 core/goform 的 [GoformGateway]，去掉 QoS 相关方法）。
 *
 * 上层只需要依赖此接口即可与 ZTE 设备私有协议解耦；具体实现见 [GoformClient]。
 * 所有请求（读/写）都依赖登录态（携带 Set-Cookie 返回的 session）。
 */
interface GoformGateway : Closeable {

    /** 返回设备 base url（http://ip[:port]）。 */
    fun baseUrl(): String

    /** 确保已登录（带 TTL 缓存与退避），返回是否处于登录态。 */
    suspend fun ensureLogin(): Boolean

    /** 标记 session 失效（清登录态，强制下次重登）；不增加登录退避计数。 */
    fun invalidateSession()

    /** 重置登录态（含失败计数清零）。改密码 / 重连成功后调用。 */
    fun resetLogin()

    /** 更新设备后台密码并重置登录态。 */
    fun updateGoformPassword(newPwd: String)

    /** 通用查询：批量 cmd，返回 JsonObject（null = 失败）。 */
    suspend fun query(commands: List<String>): JsonObject?

    /** 通用查询：单个 cmd，返回 JsonElement（null = 失败）。 */
    suspend fun querySingle(command: String): JsonElement?

    /** 通用 POST（goform_set_cmd_process），返回响应体或 null。 */
    suspend fun goformPost(params: Map<String, String>): String?

    /** 登出。 */
    suspend fun logout(): Boolean

    /** Base64 解码（GBK/UTF-8 兼容）。 */
    fun base64Decode(input: String): String
}
