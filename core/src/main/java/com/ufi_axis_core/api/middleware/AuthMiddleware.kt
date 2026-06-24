package com.ufi_axis_core.api.middleware

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.HashUtil
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class AuthMiddleware(
    private val settings: AppSettings
) {
    private val tag = "AuthMiddleware"
    private val maxTimestampDriftMs: Long = 5 * 60 * 1000L

    fun install(route: Route) {
        route.intercept(ApplicationCallPipeline.Plugins) {
            val uri = call.request.uri
            if (uri == "/health" || uri.startsWith("/ws/")) {
                proceed()
                return@intercept
            }

            val authHeader = call.request.header(HttpHeaders.Authorization)
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, toJsonElement(mapOf("error" to "Missing Authorization")))
                finish()
                return@intercept
            }

            val requestToken = authHeader.removePrefix("Bearer ").trim()
            if (requestToken != settings.token) {
                call.respond(HttpStatusCode.Unauthorized, toJsonElement(mapOf("error" to "Invalid token")))
                finish()
                return@intercept
            }

            val timestamp = call.request.header("X-Timestamp")
            val signature = call.request.header("X-Signature")
            if (timestamp != null || signature != null) {
                if (timestamp == null || signature == null) {
                    call.respond(HttpStatusCode.Unauthorized, toJsonElement(mapOf("error" to "Both X-Timestamp and X-Signature are required")))
                    finish()
                    return@intercept
                }
                val ts = timestamp.toLongOrNull()
                if (ts == null || Math.abs(System.currentTimeMillis() - ts) > maxTimestampDriftMs) {
                    call.respond(HttpStatusCode.Unauthorized, toJsonElement(mapOf("error" to "Timestamp out of range")))
                    finish()
                    return@intercept
                }
                if (!HashUtil.verifySignature(requestToken, timestamp, "", signature, settings.secret)) {
                    AppLogger.w(tag, "Signature verification failed for token=$requestToken ts=$timestamp")
                    call.respond(HttpStatusCode.Unauthorized, toJsonElement(mapOf("error" to "Invalid signature")))
                    finish()
                    return@intercept
                }
            }

            AppLogger.d(tag, "${call.request.httpMethod.value} $uri from ${call.request.local.remoteAddress}")
            proceed()
        }
    }
}
