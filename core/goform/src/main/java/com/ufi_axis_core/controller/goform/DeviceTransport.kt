package com.ufi_axis_core.controller.goform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

/**
 * 设备传输层（Anti-Corruption Layer, F9）契约接口。
 *
 * 目的：让上层模块（collector / controller / scheduler / api）依赖此接口而非具体的
 * [GoformClient]，从而与 ZTE Goform 协议细节解耦，便于替换实现、单测以及后续分阶段迁移。
 *
 * 约定：
 * - 仅声明**跨模块**真正用到的公开实例方法（共 14 个，含 [Closeable.close]）。
 *   `core/goform` 内部才需要的 goform 协议成员在 [GoformTransport] 那一层，不上这里。
 *   ⚠ [GoformTransport] 因为 Kotlin 的 `EXPOSED_PARAMETER_TYPE` 检查**不得不是 public**
 *   （6 个客户端是 public class、构造点在 `:core`），所以「不上这里」现在靠**纪律 + 守门测试**
 *   （`GoformTransportVisibilityGuardTest`）维持，不是靠语言约束。详见 [GoformTransport] 的 KDoc。
 * - **登录握手与请求签名不在本接口上**：LD / LOGIN / LOGIN_MULTI_USER / RD 取值、
 *   `AD` 的计算、Cookie 存取、会话 TTL 与登录退避**全是实现细节**
 *   （[GoformClient] 里的 `ensureSession` / `validateSession` / `computeAd` / `storeCookie`
 *   一律 `private`）。接口上只有**会话生命周期动作**：[ensureLogin] / [invalidateSession] /
 *   [resetLogin] / [logout] / [updateGoformPassword] —— 换协议时这五个动作还在，取值方式全变。
 *   唯一的刻意例外是 [GoformTransport.sha256Hex]：改后台密码要与登录握手**共用同一份哈希真源**
 *   （`DeviceProfile.kt` 的 `BACKEND_PASSWORD` 注释记了「不许在 profile 里复制第二份」），
 *   所以它上了 module 内那一层，而不是留成 private。
 * - 没有 companion 静态工具需要暴露：原来挂在 [GoformClient] companion 上的
 *   `mapNetworkType()` 已于 2026-08-29 删除（值映射搬进 `ZteGoformProfile`，见计划书 13.2.4）。
 * - 实现见 [GoformClient]（经 [GoformTransport] 直接实现本接口）。
 *
 * 迁移状态（2026-08-29，设备适配层计划书 3.3 已完成）：跨模块调用点全部走接口 ——
 * `RouteContext.goformClient` / `NetworkDeps.goformClient` / `ComponentGraph.NetworkGraph.goformClient`
 * 均为本接口类型；`ComponentFactory` 是唯一知道具体实现的地方（它负责构造）。`TODO(F9)` 标记已清零。
 *
 * 注意分层：本接口只负责「怎么发请求」，**不负责字段语义** —— 设备字段名 / 取值域 / 命令表归
 * `DeviceProfile`（`core/device-schema`）。所以 `GoformSignalClient` 等 5 个客户端不再各抽一层接口，
 * 换设备靠换 profile，换协议靠换本接口的实现（见计划书 3.3 落地说明）。
 */
interface DeviceTransport : Closeable {

    /** 返回设备 base url（http://ip[:port]）。 */
    fun baseUrl(): String

    /** 确保已登录（带 TTL 缓存与退避），返回是否处于登录态。 */
    suspend fun ensureLogin(): Boolean

    /**
     * 标记 session 失效（清登录态 + 强制下次重登）。
     *
     * **关键**：不增加 [GoformClient] 内部的 `consecutiveLoginFailures`（登录退避计数）
     * —— 退避计数仅在**真正登录失败**时递增；仅是校验失败/被官方后台顶下线等不应叠加退避，
     * 否则长会话运行会因偶发心跳校验失败把退避叠到 60s，前端轮询全被拦截（实际表现
     * 为「长时间断连，需要手动重启核心服务」）。
     */
    fun invalidateSession()

    /** 重置登录态（含失败计数清零）。通常在用户改密码 / Core 重连成功后调用。 */
    fun resetLogin()

    /** 更新设备后台密码并重置登录态（符号名沿用 `goform*`：那是配置键的一部分）。 */
    fun updateGoformPassword(newPwd: String)

    /** 通用查询：批量 cmd，返回 JsonObject（TTL 缓存命中时直接返回）。 */
    suspend fun read(commands: List<String>): JsonObject?

    /** 通用查询：单个 cmd，返回 JsonElement。 */
    suspend fun readOne(command: String): JsonElement?

    /** 通用 POST（goform_set_cmd_process），返回响应体或 null。 */
    suspend fun write(params: Map<String, String>): String?

    /** 登出。 */
    suspend fun logout(): Boolean

    /** 解码设备返回的文本：base64 解码 + 字符集还原（GBK/UTF-8 兼容）。 */
    fun decodeDeviceText(input: String): String

    /** 调整 QoS 并发许可（委托给 GoformQoS）。 */
    fun adjustQoS(permits: Int)

    /** 返回当前 QoS 状态快照。 */
    fun getQosStatus(): Map<String, Any>

    /** 设置 QoS 开关（当前为 no-op，逻辑已委托 GoformQoS 单例）。 */
    fun setQosEnabled(enabled: Boolean)
}
