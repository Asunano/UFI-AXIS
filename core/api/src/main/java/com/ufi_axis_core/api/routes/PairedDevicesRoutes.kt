package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.pairing.PairingManager.RemoveResult
import com.ufi_axis_core.api.pairing.PairingManager.RenameResult
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.PairedDeviceRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * 配对设备管理端点（挂 /api 鉴权块内，Bearer token 保护）。
 *
 * - GET    /api/pairing/devices             → 200 {devices:[{fingerprint, device_name, last_seen, created_at}]}
 * - PATCH  /api/pairing/devices/{fingerprint} → 200 {success, device_name}（device_name 1-32 字符）
 * - DELETE /api/pairing/devices/{fingerprint} → 200 {success}（需设备密码；双因素）
 *
 * 删除即吊销：每台设备持有独占 token（记录里只存哈希），删掉记录后该 token 立即失效，
 * 其余设备不受影响——不再需要「移除最后一台就轮换全局凭据」那种连坐式设计。
 *
 * 错误沿用 `{error, code}`：401 MISSING_PASSWORD / INVALID_PASSWORD、404 DEVICE_NOT_FOUND、
 * 400 INVALID_DEVICE_NAME、429 PASSWORD_LOCKED。
 */
class PairedDevicesRoutes(
    private val pairingManager: PairingManager
) {

    fun register(route: Route) {
        route.apply {
            get("/pairing/devices") { handleList(call) }
            patch("/pairing/devices/{fingerprint}") { handlePatch(call) }
            delete("/pairing/devices/{fingerprint}") { handleDelete(call) }
        }
    }

    @Serializable
    private data class RenameBody(val device_name: String? = null)

    @Serializable
    private data class DeleteBody(val password: String? = null)

    private fun fingerprintParam(call: ApplicationCall): String =
        call.parameters.getAll("fingerprint")?.joinToString("/") ?: ""

    private fun clientIp(call: ApplicationCall): String = call.request.local.remoteAddress

    private fun recordToPayload(record: PairedDeviceRecord): Map<String, Any?> = mapOf(
        "fingerprint" to record.fingerprint,
        "device_name" to record.deviceName,
        "last_seen" to record.lastSeen,
        "created_at" to record.createdAt
    )

    private suspend fun handleList(call: ApplicationCall) {
        call.respond(toJsonElement(mapOf("devices" to pairingManager.listDevices().map { recordToPayload(it) })))
    }

    private suspend fun handlePatch(call: ApplicationCall) {
        val fp = fingerprintParam(call)
        val body = try { call.receive<RenameBody>() } catch (e: Exception) { null }
        val name = body?.device_name.orEmpty()
        when (val result = pairingManager.renameDevice(fp, name)) {
            is RenameResult.Success -> {
                call.respond(toJsonElement(mapOf("success" to true, "device_name" to result.deviceName)))
            }
            is RenameResult.InvalidName -> {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_DEVICE_NAME, "device_name must be 1-32 characters")
            }
            is RenameResult.DeviceNotFound -> {
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND, "Device not found")
            }
        }
    }

    private suspend fun handleDelete(call: ApplicationCall) {
        val fp = fingerprintParam(call)
        val body = try { call.receive<DeleteBody>() } catch (e: Exception) { null }
        val password = body?.password
        // 与 /pairing/* 共用同一全局失败计数：换端点也绕不开节流（正常情况恒为 0）
        val throttle = pairingManager.passwordThrottleDelayMs()
        if (throttle > 0) delay(throttle)
        when (val result = pairingManager.removeDevice(fp, password, clientIp(call))) {
            is RemoveResult.Success -> {
                call.respond(toJsonElement(mapOf("success" to true)))
            }
            is RemoveResult.MissingPassword -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.MISSING_PASSWORD, "Password required")
            }
            is RemoveResult.InvalidPassword -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, "Invalid device password")
            }
            is RemoveResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
            is RemoveResult.DeviceNotFound -> {
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND, "Device not found")
            }
        }
    }
}
