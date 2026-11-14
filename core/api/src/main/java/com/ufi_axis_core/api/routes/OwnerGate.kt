package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.pairing.OwnerAuth
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 破坏性写操作的「主人密码」门 —— **默认关闭，可显式打开**。
 *
 * ## 设计取舍：宁可少拦，不要多打扰
 *
 * 2026-10-30 的越权审查版本把这道门铺到 40 个端点（装 APK、装程序、组件更新、隧道增删改、
 * 改配置……）。实机用下来的反馈是「装个程序也要输密码」，日常操作被频繁打断。
 * 本版的口径回到「够用就好」：
 *
 * - **一个总开关**（[AppSettings.ownerGateEnabled]，默认 `false`）管全部 —— 关着时这道门
 *   对任何端点都直接放行，行为与没有它完全一致；
 * - 打开后**只盖不可逆操作**：[DestructiveEndpoints] 里那几条（恢复出厂 / 恢复默认配置 /
 *   恢复备份 / root shell / 自定义 shell 任务 / 删全部日志 / 移除配对设备 / 解封黑名单）；
 * - 安装、更新、组件、隧道、程序启停**一律不在此列** —— 这类操作可重做，拦它的收益
 *   远小于打断用户的成本。
 *
 * ## 为什么是 object + 回调，而不是拦截器
 *
 * 两条，任一条都足够：
 *
 * 1. **密码在请求体里，而 Ktor 的请求体只能消费一次**。在 `Plugins` 阶段读走
 *    （`call.receiveText()` / `receiveJsonObject()`）会让 handler 里的 `receiveJsonObject()`
 *    拿到空对象 —— 表现为「要了密码之后所有功能都参数缺失」。所以门禁只能由 handler
 *    在**读完 body 之后**显式调用。
 * 2. **判据要从装配层来**。`PairingManager` 是 `HttpServer` 的构造参数，而各 Route
 *    拿不到它；给多个 Route 各加一个构造参数会牵动大量测试与装配代码。
 *    同目录的 `WriteGate` 已经因为同样的理由选了「object + 装配层注入」。
 *
 * ## 为什么未装配时是「放行」而不是「拒绝」
 *
 * 与 `WriteGate.updateBusy` 的默认值 `{ false }` 同口径：单元测试、局部装配（只起一个
 * Route 的测试）不该被迫先装配配对管理器。代价是「装配层漏装 = 门禁静默失效」，
 * 这一点由装配守卫测试兜住（断言 [isInstalled] 为 true），不靠在生产代码里 fail-closed ——
 * 那会让所有忘记装配的开发环境与老测试一起红，最后被人一把改回放行。
 *
 * ## 为什么没有「一次性票据」
 *
 * 2026-10-30 那版配了 `OwnerTokenStore`（密码换 10 分钟票据，之后带 `X-Owner-Token`），
 * 但签发端点 `POST /api/pairing/owner-token` 一直没实现，客户端也从没送过这个头 ——
 * 也就是说它从头到尾是死代码。本版不恢复它：留着只会让人以为「有票据机制」。
 * 真要做，应该是「先有客户端弹一次密码框」的同一批改动里再加，而不是先躺一个空壳。
 */
object OwnerGate {

    /**
     * 装配层是否已经注入判据。**只给装配守卫测试用**，业务代码不要读它
     * （读了就会出现「装没装」两套分支，而正确的分支只有一条）。
     */
    @Volatile
    var isInstalled: Boolean = false
        private set

    /**
     * 总开关（`settings.ownerGateEnabled`，默认 false）。
     *
     * 未装配时为 false —— 与「未装配即放行」同一取向：缺省状态不该开始要密码。
     */
    @Volatile
    private var enabled: () -> Boolean = { false }

    /**
     * 设备是否设过配对密码。
     *
     * false = 全新设备（或用户主动清空），此时**不需要密码**：否则首次配置流程会把自己锁死。
     * 与 0.1.0 的 `ConfigRoutes` `/reset` 原判据逐字一致。
     */
    @Volatile
    var passwordSet: () -> Boolean = { false }
        private set

    /** 校验 + 计数 + 锁定，全部复用 `PairingManager.verifyOwnerPassword`（同一份节流）。 */
    @Volatile
    var verify: (password: String?, ip: String) -> OwnerAuth = { _, _ -> OwnerAuth.Missing }
        private set

    /** 校验前应等待的毫秒数（全局递增节流，复用 `PairingManager.passwordThrottleDelayMs`）。 */
    @Volatile
    var throttleDelayMs: () -> Long = { 0L }
        private set

    /** 门当前是否真的会拦人（开关打开 + 已设过密码）。诊断与测试用。 */
    fun isActive(): Boolean = enabled() && passwordSet()

    /** 仅测试用：回到「未装配」状态。 */
    fun resetForTests() {
        isInstalled = false
        enabled = { false }
        passwordSet = { false }
        verify = { _, _ -> OwnerAuth.Missing }
        throttleDelayMs = { 0L }
    }

    /**
     * 一次性装配全部判据。分开赋值会让「密码已设但校验器还没装」这种中间态存在，
     * 而这个中间态恰好是 fail-open，所以只提供这一个入口。
     */
    fun install(
        enabled: () -> Boolean,
        passwordSet: () -> Boolean,
        verify: (password: String?, ip: String) -> OwnerAuth,
        throttleDelayMs: () -> Long,
    ) {
        this.enabled = enabled
        this.passwordSet = passwordSet
        this.verify = verify
        this.throttleDelayMs = throttleDelayMs
        this.isInstalled = true
    }
}

/**
 * 请求体里的 `password` 字段。
 *
 * 统一在这里取，避免各 handler 各写一份 `body["password"]?.jsonPrimitive?.contentOrNull`
 * 而在某处漏掉 `jsonPrimitive` 的类型安全（对象/数组值会让 `.jsonPrimitive` 抛异常）。
 */
fun JsonObject?.ownerPassword(): String? = (this?.get("password") as? JsonPrimitive)?.contentOrNull

/**
 * 破坏性写操作的主人密码门。
 *
 * 用法（handler 里读完 body 之后立刻过门）：
 *
 * ```kotlin
 * post("/factory-reset") {
 *     val body = runCatching { call.receiveJsonObject() }.getOrNull()
 *     if (!call.requireOwner(body.ownerPassword())) return@post
 *     // 到这里才允许执行
 * }
 * ```
 *
 * @param password 请求体里的 `password`；为空时回落到请求头 `X-Owner-Password`。
 * @return true = 放行（开关未打开、本设备从未设过密码、或密码正确）；false = 已经回过响应，
 *   调用方必须**立刻** `return@post`。
 */
suspend fun ApplicationCall.requireOwner(password: String?): Boolean {
    // 总开关关着 / 没设过密码 → 不打扰。两处判据都在 OwnerGate 里，别在 handler 里再判一次。
    if (!OwnerGate.isActive()) return true

    // body 优先，缺失时回落到请求头。
    //
    // 为什么两种取法都收：**客户端的统一重试路径只加一个头**。App / Web 在 401
    // MISSING_PASSWORD 之后重发同一个请求，加头不需要知道这个端点的 body 是 JSON
    // 还是文件流，也不用改请求体形状；而 handler 侧仍然优先读 body，
    // 于是「开关打开 + 老客户端直接带密码」这条既有路径不受影响。
    val effective = password?.takeIf { it.isNotBlank() } ?: request.headers[HEADER_OWNER_PASSWORD]

    val delayMs = OwnerGate.throttleDelayMs()
    if (delayMs > 0) delay(delayMs)

    return when (OwnerGate.verify(effective, request.local.remoteAddress)) {
        OwnerAuth.Ok -> true
        OwnerAuth.Missing -> {
            respondFail(
                HttpStatusCode.Unauthorized,
                ErrorCode.MISSING_PASSWORD,
                "该操作已开启主人密码保护，请输入配对密码"
            )
            false
        }
        OwnerAuth.Wrong -> {
            respondFail(
                HttpStatusCode.Unauthorized,
                ErrorCode.INVALID_PASSWORD,
                "配对密码错误"
            )
            false
        }
        OwnerAuth.Locked -> {
            respondFail(
                HttpStatusCode.TooManyRequests,
                ErrorCode.PASSWORD_LOCKED,
                "配错次数过多，请稍后再试"
            )
            false
        }
    }
}

/**
 * 主人密码的请求头名（供**请求体不是 JSON** 的端点用，目前只有备份导入）。
 *
 * 为什么需要它：`POST /api/backup/import` 的 body 就是备份包本身（直接读原始流），
 * 把密码塞进 JSON 会破坏包格式、塞进 URL query 会被访问日志与反代日志记下来。
 * 头是这三者里唯一不落日志、又不动 body 的位置。
 */
const val HEADER_OWNER_PASSWORD = "X-Owner-Password"

/**
 * 只从请求头取密码再过门（等价于 `requireOwner(null)`）。
 *
 * 给「body 不是 JSON」的端点用（备份导入、二进制上传）：那类端点读不了 body 里的字段。
 * 普通端点直接调 [requireOwner] 即可 —— 它已经会在 body 缺失时回落到同一个头，
 * 所以这里不需要再手动取头。
 */
suspend fun ApplicationCall.requireOwnerFromHeader(): Boolean = requireOwner(null)
