package com.ufi_axis_core.api.middleware

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.DeviceRequestVerifier
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey

/**
 * `/api` 鉴权拦截器（严格设备独立性，2026-08-28 重写）。
 *
 * 每个请求必须同时满足（判定逻辑全在 [DeviceRequestVerifier] 里，本类只做参数搬运与错误映射）：
 * 1. `Authorization: Bearer <token>` —— token 的 SHA-256 命中某台**已配对设备**的记录
 *    （明文 token 从不落盘，见 `PairedDeviceRecord.tokenHash`）；
 * 2. `X-Timestamp` 在 ±5min 窗口内；
 * 3. `X-Signature` 是该设备**公钥**对 `METHOD\nURI\nTS\nNONCE` 的有效 ECDSA-SHA256 签名；
 * 4. `X-Nonce` 在窗口内未被用过。
 *
 * ## 与旧实现的差别（都是必须改的）
 * - 旧的比的是**全局** `settings.token`：所有设备共用一个 token，无法定向吊销，
 *   任一设备泄漏 token 等于全部沦陷。现在 token 按设备签发，删记录即吊销。
 * - 旧的签名分支是 `if (timestamp != null || signature != null)`——客户端不发头就完全跳过，
 *   而当时没有任何客户端在发，等于签名机制根本不存在。现在**强制**。
 * - 旧的用 HMAC + 共享 secret；现在用设备私钥签、服务端用记录里的公钥验，无共享秘密。
 *
 * 鉴权通过后把设备指纹写入 [DEVICE_FINGERPRINT_KEY]，供访问日志与业务侧归因。
 */
class AuthMiddleware(
    private val verifier: DeviceRequestVerifier
) {
    private val tag = "AuthMiddleware"

    fun install(route: Route) {
        route.intercept(ApplicationCallPipeline.Plugins) {
            val uri = call.request.uri
            if (isPublic(uri)) {
                proceed()
                return@intercept
            }

            val authHeader = call.request.header(HttpHeaders.Authorization)
            val token = authHeader?.takeIf { it.startsWith(BEARER_PREFIX) }
                ?.removePrefix(BEARER_PREFIX)?.trim()

            val result = verifier.verify(
                token = token,
                timestamp = call.request.header(HEADER_TIMESTAMP),
                nonce = call.request.header(HEADER_NONCE),
                signature = call.request.header(HEADER_SIGNATURE),
                method = call.request.httpMethod.value,
                uri = uri
            )

            when (result) {
                is DeviceRequestVerifier.Result.Ok -> {
                    call.attributes.put(DEVICE_FINGERPRINT_KEY, result.device.fingerprint)
                    // 请求日志由 HttpServer 的访问日志拦截器统一记录（带状态码与耗时）。
                    proceed()
                }
                DeviceRequestVerifier.Result.MissingToken -> {
                    call.logReject("no token")
                    call.rejectAuth("Missing Authorization", ErrorCode.UNAUTHORIZED)
                    finish()
                }
                DeviceRequestVerifier.Result.UnknownToken -> {
                    call.logReject("token not in paired store")
                    call.rejectAuth("Invalid token", ErrorCode.UNAUTHORIZED)
                    finish()
                }
                DeviceRequestVerifier.Result.DeviceKeyMissing -> {
                    call.logReject("paired record has no pubkey (re-pair required)")
                    call.rejectAuth("Device must re-pair", ErrorCode.INVALID_DEVICE_KEY)
                    finish()
                }
                DeviceRequestVerifier.Result.MissingSignature -> {
                    call.logReject("missing ts/nonce/sig header")
                    call.rejectAuth(
                        "Missing $HEADER_TIMESTAMP / $HEADER_NONCE / $HEADER_SIGNATURE",
                        ErrorCode.INVALID_SIGNATURE
                    )
                    finish()
                }
                DeviceRequestVerifier.Result.StaleTimestamp -> {
                    call.logReject("stale timestamp ts=${call.request.header(HEADER_TIMESTAMP)} now=${System.currentTimeMillis()}")
                    call.rejectAuth("Timestamp out of range", ErrorCode.INVALID_SIGNATURE)
                    finish()
                }
                DeviceRequestVerifier.Result.BadSignature -> {
                    call.logReject("signature mismatch")
                    call.rejectAuth("Invalid signature", ErrorCode.INVALID_SIGNATURE)
                    finish()
                }
                DeviceRequestVerifier.Result.Replayed -> {
                    call.logReject("nonce replayed")
                    call.rejectAuth("Nonce replayed", ErrorCode.INVALID_SIGNATURE)
                    finish()
                }
            }
        }
    }

    /**
     * 免鉴权放行：健康检查、WebSocket（自带 query 签名鉴权，见 WebSocketManager）、
     * 配对端点、Web 配对页。
     *
     * 配对端点本就挂在 root 而非 /api，这里再放行一次是纵深防御：将来若被移进 /api，
     * 也不会因为"配对时还没有 token"而变成死锁。
     */
    private fun isPublic(uri: String): Boolean =
        uri == "/health" || uri.startsWith("/ws/") ||
            uri.startsWith("/pairing/") || uri == "/pair" || uri == "/"

    /**
     * 每一条拒绝都要留下原因。
     * 2026-09-02：之前只有验签失败/重放两条分支打日志，缺 token / token 不认识 /
     * 时间戳过期是静音的 —— 线上出现"同一台手机大部分接口 200、少数接口 444"时
     * 完全无从判断卡在哪一步。
     */
    private fun ApplicationCall.logReject(reason: String) {
        AppLogger.w(
            tag,
            "auth reject: $reason — ${request.httpMethod.value} ${request.uri} from ${request.local.remoteAddress}"
        )
    }

    /**
     * 统一失败响应。沿用历史上的自定义 444 状态码（客户端已按它判"需要重新登录"），
     * 载荷补上 `code` 以对齐 `{error, code}` 契约。
     */
    private suspend fun ApplicationCall.rejectAuth(message: String, code: String) {
        respond(
            HttpStatusCode(444, "Unauthorized"),
            toJsonElement(mapOf("error" to message, "code" to code))
        )
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        const val HEADER_TIMESTAMP = "X-Timestamp"
        const val HEADER_NONCE = "X-Nonce"
        const val HEADER_SIGNATURE = "X-Signature"

        /** 鉴权通过的设备指纹，供访问日志/业务归因读取。 */
        val DEVICE_FINGERPRINT_KEY = AttributeKey<String>("UfiAxisDeviceFingerprint")
    }
}
