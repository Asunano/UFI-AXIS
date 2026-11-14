package com.ufi_axis_core.api

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode

/**
 * 能力集门禁（计划书 §7 的 3.3）—— **route 层唯一允许判「设备支持不支持」的地方**。
 *
 * ## 为什么是抛异常而不是在 handler 里 `if` + 回响应
 *
 * 门禁要覆盖十几个写端点，逐个写 `if (…) { respondFail(501, …); return@post }` 的代价不是
 * 行数，而是**每个 handler 各自决定状态码与文案** —— 一处写成 503、一处忘了 return，
 * 都不会有任何编译期或测试期信号。所以 route 侧只负责「声明这个动作需要哪个能力」，
 * 状态码 / 错误码 / 文案这三件事只在本文件定义一次，出口统一在 `HttpServer` 的 `StatusPages`。
 *
 * ## 为什么 [CapabilityMissing] 定在 `:core:api` 而不是 `:core:contract`
 *
 * 三条，任一都足够：
 *
 * 1. **它不是对外契约**。真正跨到 HTTP 边界上的是 501 与 [ErrorCode.NOT_SUPPORTED]，
 *    那两个已经在冻结区里了；异常类型只在 core 内部从 route 传到 `StatusPages`。
 *    往冻结区里塞一个客户端永远看不到的类型，只会让「冻结区 = 对外协议」这条纪律变模糊。
 * 2. **抛的人和捕的人都看得见这里**：抛在 `:core:api` 的 route，捕在 `:core:network` 的
 *    `HttpServer`，而 `:core:network` 依赖 `:core:api`。
 * 3. **`:core:network` 看不见 `:core:contract`**（`:core:api` 是用 `implementation` 依赖
 *    contract 的，不传递）。所以「501 + NOT_SUPPORTED」这组取值必须由本文件带过去 ——
 *    这也正是 [CapabilityMissing.status] / [CapabilityMissing.errorCode] 挂在异常上的原因，
 *    而不是让 `HttpServer` 自己去引 contract（那要多加一条模块依赖，只为了两个常量）。
 */

/**
 * 当前设备没有声明某个 [Capability]，本次写操作**没有下发**。
 *
 * 由 [requireCapability] 抛出，由 `HttpServer` 的 `StatusPages` 统一映射成
 * [status] + [errorCode]。**不要 catch 它** —— 谁 catch 就等于把「不支持」变回了
 * 一次静默失败（接口 200、点了没反应），那正是阶段 3 要消灭的行为。
 *
 * @property capability 缺失的功能域。它的 `wire` 名会进响应文案，方便前端定位到具体开关。
 */
class CapabilityMissing(val capability: Capability) : RuntimeException(
    "当前设备不支持该功能（capability=${capability.wire}）"
) {

    /**
     * 缺失能力的对外 wire 名（如 `samba`）。
     *
     * 为什么要单独开一个 `String`：捕这个异常的 `HttpServer` 在 `:core:network`，
     * 而那个模块**看不见 `Capability` 这个类型**（`:core:api` 是用 `implementation` 依赖
     * `:core:contract` 的，不传递）。它只需要往日志里写一个名字，为此给 network 加一条
     * contract 依赖不值得 —— 所以这里把它降成字符串递过去。
     * `:core:api` 内部（含单测）请直接用 [capability]。
     */
    val capabilityWire: String get() = capability.wire

    /**
     * **501**。语义是「这台设备做不到」，与 503（设备离线/会话失效，可重试）
     * 和 400（值域非法）严格分开，见 [ErrorCode.NOT_SUPPORTED] 与计划书 §11.6。
     */
    val status: HttpStatusCode get() = HttpStatusCode.NotImplemented

    /** 失败信封的 `code`，恒为 [ErrorCode.NOT_SUPPORTED]。 */
    val errorCode: String get() = ErrorCode.NOT_SUPPORTED

    /**
     * 给用户看的一句话。
     *
     * 带上 `wire` 名是刻意的：能力集下发的就是这个名字，前端拿它就能定位到该灰掉的那个开关，
     * 不需要再按端点路径猜。文案本身不分域定制 —— 分域文案属于 UI 层（3.5 / 3.6），
     * core 只保证「说清楚是不支持、不是出错了」。
     */
    val userMessage: String get() = "当前设备不支持该功能（${capability.wire}），已忽略本次操作"
}

/**
 * 门禁本体：当前设备能力集里没有 [capability] 就抛 [CapabilityMissing]。
 *
 * 用法（接收者是**不可变**的能力集，装配层在构造组件图时定死）：
 *
 * ```kotlin
 * post("/samba") {
 *     dataHub.deviceCapabilities.requireCapability(Capability.SAMBA)
 *     // 到这里就一定支持，后面是原有逻辑
 * }
 * ```
 *
 * 放在 handler 的**第一行**（读请求体之前）：不支持的设备上连参数校验都不必做，
 * 也不会有「设备那半边没写、core 那半边写了」的半成功（见 [Capability.TRAFFIC_LIMIT]）。
 *
 * 为什么接收者是 `Set<Capability>` 而不是 `DataHub`：门禁不该知道能力集是从哪个对象上取的。
 * `RootSmsRoutes` 就没有 `RouteContext`／`DataHub`，它直接持有这个 Set。
 */
fun Set<Capability>.requireCapability(capability: Capability) {
    if (capability !in this) throw CapabilityMissing(capability)
}
