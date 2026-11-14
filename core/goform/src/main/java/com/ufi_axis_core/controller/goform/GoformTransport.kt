package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.devicespi.DeviceTransport
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject

/**
 * goform 协议传输层契约（**只许 `core/goform` module 内部使用**）。
 *
 * 它在 [DeviceTransport] 的 14 个公开方法之外，追加 6 个客户端与 [GoformSettingWriter]
 * 真正用到、但**跨模块没有任何调用方**的 9 个成员
 * （2026-09-26 先新增 [writeSessionSafe]，同日再新增 [ensureFreshLogin]，原来是 7 个）。

 *
 * ## 为什么是 `public`，以及为什么它仍然「不对外」
 *
 * 本来定的是 `internal`（公开面零扩大）。**2026-09-23 实测这条路走不通**：
 * 6 个客户端（`GoformSignalClient` 等）是 **public class**，构造点在别的 module
 * （`ComponentFactory.buildNetworkGraph`，`:core`），所以 Kotlin 会报
 * `'public' function exposes its 'internal' parameter type 'GoformTransport'`。
 * 唯一能编过的绕法是 `@Suppress("EXPOSED_PARAMETER_TYPE")`，而编译器对它明确不背书
 * （"the compiler behavior is UNSPECIFIED and WILL NOT BE PRESERVED"）——
 * 那种东西不许留在主线上。
 *
 * 所以改成 `public` + **一条纪律**（用户裁决，方案 B）：
 *
 * > **`core/goform` 之外的任何文件都不许 import / 引用 `GoformTransport`。**
 * > 上层（route / collector / controller / scheduler）一律只认 [DeviceTransport] 的 14 个方法。
 *
 * 这条纪律由 `GoformTransportVisibilityGuardTest` 扫源码守住 —— 语言层面拦不住的，
 * 用守门测试拦。等阶段 2 把这 6 个客户端连同传输层一起收进插件 module 之后，
 * 它们可以整体变 `internal`，那时本接口才能真正收窄（届时删掉这段说明与守门测试）。
 *
 * 顺带一个事实：`internal` 接口继承 public 接口本身是合法的，挡住方案 A 的不是继承，
 * 而是「public 构造函数的参数类型不能是 internal」这条检查。别把结论记错。
 *
 * ## 为什么叫 `Goform*`
 *
 * [isAuthFailure] / [isSuccess] / [writeIdempotent] / [sha256Hex] **本来就是 goform 协议事实**
 * （设备怎么表达鉴权失败、怎么表达业务成功、哪些命令幂等、签名用哪个摘要），
 * 给它们起一个中立名字是自欺。协议味留在名字上，换协议时才看得见边界在哪。
 *
 * ## 第二个协议实现怎么进来
 *
 * 只要实现本接口（它自然也满足 [DeviceTransport]），6 个客户端与 [GoformSettingWriter]
 * 就能照原样装配 —— 构造参数吃的是这个类型，不是具体类 [GoformClient]。
 */
interface GoformTransport : DeviceTransport {

    /** 解析出真正可达的 base url（文件类端点不走 [ensureLogin]，但同样要先把 base url 定下来）。 */
    suspend fun ensureBaseUrlResolved()

    /**
     * **强制校验**版的 [ensureLogin]：绕过实现内部的会话校验间隔，下发前先确认会话还活着。
     *
     * 语义三条（判据与踩坑记录见 `GoformClient.ensureFreshLogin` 的 KDoc）：
     *  1. 只跳过**校验间隔**，强制做一次轻量校验（goform 上是一次 `cmd=RD` GET）；
     *  2. **校验失败才重登** —— 不是每次都强制重登；
     *  3. 官方后台让位窗口与登录退避**照旧生效**。
     *
     * ## 成本与适用范围（⚠ 改调用点前必读）
     *
     * 每次调用多一次轻量 GET。这对**用户发起的单次操作**（手动发一条短信）可以接受，
     * 但**不许**接到自动 / 批量路径上（SMS 转发、日报、告警通知这类调度器驱动的高频调用）——
     * 那会给设备平白多出等量请求，占满 QoS 许可，把读路径一起拖慢。
     * 那些路径继续用 [ensureLogin]。
     *
     * ## 为什么给默认实现
     *
     * 默认体就是 [ensureLogin]，也就是「不具备强制校验能力的实现 ⇒ 退回原语义」。
     * 这样第二个协议实现（以及单测里的手写假传输层）不必为这个 goform 专属的优化被迫改签名，
     * 而漏实现的后果最多是"没优化"，不会是"行为错"。
     *
     * 目前唯一调用点：`GoformSmsClient.sendSms`。
     */
    suspend fun ensureFreshLogin(): Boolean = ensureLogin()


    /** 直接 GET 一个设备侧 URL（文件类端点用：二维码图片、短信附件等），**必须带 session Cookie**。 */
    suspend fun httpGet(url: String): HttpResponse

    /** 把响应体解析成 JsonObject，失败返回 null（只打一条 WARN）。 */
    fun parseJson(body: String): JsonObject?

    /** 会话/鉴权失败判定（JSON 按 `result` 精确等值，HTML 才退回子串匹配）。 */
    fun isAuthFailure(body: String): Boolean

    /** **幂等**写操作专用入口：会话失效时重登并只重试一次，三态结果见 [GoformWriteResult]。 */
    suspend fun writeIdempotent(params: Map<String, String>): GoformWriteResult

    /**
     * **非幂等但"没发出就该重发"**的写操作专用入口（`SEND_SMS`）。
     *
     * 只对 [GoformWriteResult.SessionLost]（命令确定没落到固件）重登重试一次；
     * 设备回了 200 业务体但 body 说失败的那一路**绝不重发**（排除不了已进发送队列 ⇒
     * 重复计费）。与 [write] / [writeIdempotent] 的分工表写在
     * `GoformClient.writeSessionSafe` 的 KDoc 里，改动前先读那张表。
     */
    suspend fun writeSessionSafe(params: Map<String, String>): GoformWriteResult

    /** 设备业务响应是否表示成功。 */
    fun isSuccess(body: String?): Boolean

    /**
     * SHA-256 十六进制摘要。
     *
     * **不许改名、不许在别处复制第二份** —— 登录握手与改密都用这一份（`DeviceProfile.kt:333`
     * 与 `GoformDeviceClient` 的 `changePassword` 都记了「与登录握手共用同一份真源」这条结论）。
     */
    fun sha256Hex(input: String): String
}
