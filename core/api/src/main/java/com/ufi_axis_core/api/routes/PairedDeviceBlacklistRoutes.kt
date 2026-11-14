package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.pairing.PairingManager.BlacklistResult
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * 配对设备黑名单端点（2026-10-15；挂 /api 鉴权块内，Bearer token 保护）。
 *
 * - GET    /api/pairing/blacklist                  → 200 {devices:[{fingerprint, hw_id, name, reason, blocked_at, blocked_by}]}
 * - POST   /api/pairing/blacklist                  → 200 {success, already?}
 * - DELETE /api/pairing/blacklist/{fingerprint}    → 200 {success}
 * - POST   /api/pairing/blacklist/clear            → 200 {success, removed}
 *
 * ## 鉴权口径
 * 只有 Bearer + 设备签名一条：设备是单机单人场景，能登录就说明已持有配对凭据。
 * 原先还要求一次配对密码，为的是防"被偷走的一台已配对设备把机主所有设备拉黑"；
 * 这个风险在单机单人场景下由「本机不可拉黑」+ 管理入口只在设置页承担。
 *
 * ## 为什么服务端还要拒绝"拉黑本机"
 * UI 会置灰，但 UI 不是安全边界：直接用 API 拉黑自己，等于一键把自己踢下线，
 * 而且解除拉黑需要已鉴权请求 —— 没有任何设备能救它（除了清数据重配）。
 * 判定放在 [PairingManager.blockDevice] 里（`selfFingerprint` 来自鉴权中间件）。
 *
 * ## 与"移除设备"的关系
 * 两者独立：移除设备 = 撤销授权（对方可以拿密码重新配对）；
 * 拉黑 = 明确拒绝（重新配对/密码登录都被 L1 拦掉）。
 * **移除设备不会解除拉黑** —— 否则"踢掉它"会顺手把"禁止它回来"一起取消，那不是机主的意思。
 */
class PairedDeviceBlacklistRoutes(
    private val pairingManager: PairingManager
) {

    fun register(route: Route) {
        route.apply {
            get("/pairing/blacklist") { handleList(call) }
            post("/pairing/blacklist") { handleBlock(call) }
            // clear 必须注册在 {fingerprint} 之前语义上更清晰；Ktor 的路由匹配按段数与字面量
            // 优先，`/pairing/blacklist/clear` 不会被 `{fingerprint}` 抢走。
            post("/pairing/blacklist/clear") { handleClear(call) }
            delete("/pairing/blacklist/{fingerprint}") { handleUnblock(call) }
        }
    }

    @Serializable
    private data class BlockBody(
        val fingerprint: String? = null,
        val hw_id: String? = null,
        val name: String? = null,
        val reason: String? = null
    )

    private fun fingerprintParam(call: ApplicationCall): String =
        call.parameters.getAll("fingerprint")?.joinToString("/") ?: ""

    /**
     * 发起请求的**本机**设备指纹（鉴权中间件写入 attributes）。
     * 取不到时返回 null —— 那时不做"拉黑本机"判定（但仍会走其余校验）。
     */
    private fun selfFingerprint(call: ApplicationCall): String? =
        call.attributes.getOrNull(AuthMiddleware.DEVICE_FINGERPRINT_KEY)

    private fun toPayload(device: com.ufi_axis_core.api.pairing.BlockedDevice): Map<String, Any?> = mapOf(
        "fingerprint" to device.fingerprint,
        "hw_id" to device.hwId,
        "name" to device.name,
        "reason" to device.reason,
        "blocked_at" to device.blockedAt,
        "blocked_by" to device.blockedBy
    )

    private suspend fun handleList(call: ApplicationCall) {
        call.respond(
            toJsonElement(
                mapOf(
                    "devices" to pairingManager.listBlocked().map { toPayload(it) },
                    "available" to pairingManager.blacklistAvailable()
                )
            )
        )
    }

    private suspend fun handleBlock(call: ApplicationCall) {
        val body = try { call.receive<BlockBody>() } catch (e: Exception) { null }
        when (
            val result = pairingManager.blockDevice(
                fingerprint = body?.fingerprint.orEmpty(),
                hwId = body?.hw_id,
                name = body?.name,
                reason = body?.reason,
                selfFingerprint = selfFingerprint(call)
            )
        ) {
            is BlacklistResult.Success -> call.respond(toJsonElement(mapOf("success" to true)))
            is BlacklistResult.AlreadyBlocked ->
                call.respond(toJsonElement(mapOf("success" to true, "already" to true)))
            is BlacklistResult.InvalidTarget ->
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "fingerprint required")
            is BlacklistResult.SelfBlock ->
                call.respondFail(
                    HttpStatusCode.BadRequest,
                    ErrorCode.BAD_REQUEST,
                    "不能拉黑本机：拉黑后没有设备能解除，请改用「移除设备」"
                )
            is BlacklistResult.Full ->
                call.respondFail(
                    HttpStatusCode.Conflict,
                    ErrorCode.BLACKLIST_FULL,
                    "黑名单已满，请先解除几条"
                )
            is BlacklistResult.NotFound ->
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND, "Device not in blacklist")
            is BlacklistResult.Unavailable ->
                call.respondFail(
                    HttpStatusCode.NotImplemented,
                    ErrorCode.NOT_SUPPORTED,
                    "本设备端未启用黑名单功能"
                )
        }
    }

    private suspend fun handleUnblock(call: ApplicationCall) {
        // 主人密码门（0.1.0 起这处就要密码，本版原样保留，只是改由 OwnerGate 统一判定）。
        // 解除黑名单 = 放一条已被拒绝的凭据重新尝试。
        val body = runCatching { call.receiveJsonObject() }.getOrNull()
        // [owner] DELETE /api/pairing/blacklist/{fingerprint}
        if (!call.requireOwner(body.ownerPassword())) return
        when (val result = pairingManager.unblockDevice(fingerprintParam(call))) {
            is BlacklistResult.Success -> call.respond(toJsonElement(mapOf("success" to true)))
            is BlacklistResult.NotFound ->
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND, "Device not in blacklist")
            is BlacklistResult.InvalidTarget ->
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "fingerprint required")
            is BlacklistResult.Unavailable ->
                call.respondFail(
                    HttpStatusCode.NotImplemented,
                    ErrorCode.NOT_SUPPORTED,
                    "本设备端未启用黑名单功能"
                )
            else ->
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "unexpected state")
        }
    }

    private suspend fun handleClear(call: ApplicationCall) {
        // 与单条解封是**同一能力**：只拦单条等于留一条绕行路（一次性放行所有被拒过的凭据）。
        val body = runCatching { call.receiveJsonObject() }.getOrNull()
        // [owner] POST /api/pairing/blacklist/clear
        if (!call.requireOwner(body.ownerPassword())) return
        when (val result = pairingManager.clearBlacklist()) {
            is BlacklistResult.Success ->
                call.respond(toJsonElement(mapOf("success" to true, "removed" to result.removed)))
            is BlacklistResult.Unavailable ->
                call.respondFail(
                    HttpStatusCode.NotImplemented,
                    ErrorCode.NOT_SUPPORTED,
                    "本设备端未启用黑名单功能"
                )
            else ->
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "unexpected state")
        }
    }
}
