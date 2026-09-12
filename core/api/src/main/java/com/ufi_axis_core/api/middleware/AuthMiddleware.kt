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
                    call.rejectRevoked("Missing Authorization", ErrorCode.UNAUTHORIZED)
                    finish()
                }
                DeviceRequestVerifier.Result.UnknownToken -> {
                    call.logReject("token not in paired store")
                    call.rejectRevoked("Invalid token", ErrorCode.UNAUTHORIZED)
                    finish()
                }
                DeviceRequestVerifier.Result.DeviceKeyMissing -> {
                    call.logReject("paired record has no pubkey (re-pair required)")
                    call.rejectRevoked("Device must re-pair", ErrorCode.INVALID_DEVICE_KEY)
                    finish()
                }
                DeviceRequestVerifier.Result.StoreUnavailable -> {
                    call.logReject("paired store degraded (unreadable) — cannot decide, answering retryable 503")
                    call.rejectUnavailable()
                    finish()
                }
                DeviceRequestVerifier.Result.MissingSignature -> {
                    call.logReject("missing ts/nonce/sig header")
                    call.rejectRetryable(
                        "Missing $HEADER_TIMESTAMP / $HEADER_NONCE / $HEADER_SIGNATURE",
                        ErrorCode.INVALID_SIGNATURE
                    )
                    finish()
                }
                DeviceRequestVerifier.Result.StaleTimestamp -> {
                    call.logReject("stale timestamp ts=${call.request.header(HEADER_TIMESTAMP)} now=${System.currentTimeMillis()}")
                    call.rejectRetryable("Timestamp out of range", ErrorCode.STALE_TIMESTAMP)
                    finish()
                }
                DeviceRequestVerifier.Result.BadSignature -> {
                    call.logReject("signature mismatch")
                    call.rejectRetryable("Invalid signature", ErrorCode.INVALID_SIGNATURE)
                    finish()
                }
                DeviceRequestVerifier.Result.Replayed -> {
                    call.logReject("nonce replayed")
                    call.rejectRetryable("Nonce replayed", ErrorCode.INVALID_SIGNATURE)
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
     * 失败响应分三档，档位本身就是给客户端的指令（2026-09-08 拆分，此前全是 444）。
     *
     * | 状态码 | 语义 | 客户端该做的事 |
     * | --- | --- | --- |
     * | 444 | 凭据真的不作数了（没带 token / token 不认识 / 记录没公钥） | 清凭据、重新配对 |
     * | 401 | 这次请求本身有问题（时间戳超窗 / 签名缺失或不对 / nonce 重放） | **保留凭据**，修好参数重试 |
     * | 503 | 服务端的配对存储读不出来，判不了 | **保留凭据**，退避重试 |
     *
     * 为什么必须拆：444 在客户端是"清空 token 回配对页"的信号（App 的 RetrofitClient、
     * Web 的 useApi 都这么处理）。把"手机时钟漂了 6 分钟"和"配对文件损坏"也映射成 444，
     * 等于让一个可自愈的临时故障去销毁用户凭据 —— 2026-09-08 事故里
     * 用户被迫重新输密码的最后一环就是这个映射。
     */
    private suspend fun ApplicationCall.rejectRevoked(message: String, code: String) {
        respondAuthFailure(HttpStatusCode(444, "Unauthorized"), message, code)
    }

    /** 可重试的请求级失败（时间戳/签名/重放）：401，客户端**不要**丢凭据。 */
    private suspend fun ApplicationCall.rejectRetryable(message: String, code: String) {
        respondAuthFailure(HttpStatusCode.Unauthorized, message, code)
    }

    /**
     * 配对存储降级：503。
     *
     * 这里绝不能回 444。降级意味着服务端**不知道**请求方是否已配对，
     * 而回 444 是在断言"你没配对"—— 客户端据此清空 token，一次文件损坏就变成全员重新配对。
     * 带上 `Retry-After`，让客户端有明确的退避依据。
     */
    private suspend fun ApplicationCall.rejectUnavailable() {
        response.headers.append(HttpHeaders.RetryAfter, STORE_RETRY_AFTER_SECONDS)
        respondAuthFailure(
            HttpStatusCode.ServiceUnavailable,
            "Paired device store unavailable, retry later",
            ErrorCode.AUTH_STORE_UNAVAILABLE
        )
    }

    /** 统一失败体形状：`{error, code}`（与 `ResponseHelper` 的失败信封对齐）。 */
    private suspend fun ApplicationCall.respondAuthFailure(
        status: HttpStatusCode,
        message: String,
        code: String
    ) {
        respond(status, toJsonElement(mapOf("error" to message, "code" to code)))
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        const val HEADER_TIMESTAMP = "X-Timestamp"
        const val HEADER_NONCE = "X-Nonce"
        const val HEADER_SIGNATURE = "X-Signature"

        /** 降级态 503 的 `Retry-After`（秒）。够短，好让存储恢复后客户端很快回到正常。 */
        private const val STORE_RETRY_AFTER_SECONDS = "10"

        /** 鉴权通过的设备指纹，供访问日志/业务归因读取。 */
        val DEVICE_FINGERPRINT_KEY = AttributeKey<String>("UfiAxisDeviceFingerprint")
    }
}
