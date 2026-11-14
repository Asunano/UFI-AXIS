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
import kotlinx.serialization.Serializable

/**
 * 配对设备管理端点（挂 /api 鉴权块内，Bearer token 保护）。
 *
 * - GET    /api/pairing/devices             → 200 {devices:[{fingerprint, device_name, last_seen, created_at}]}
 * - PATCH  /api/pairing/devices/{fingerprint} → 200 {success, device_name}（device_name 1-32 字符）
 * - DELETE /api/pairing/devices/{fingerprint} → 200 {success}
 *
 * 鉴权只有 Bearer + 设备签名一条：设备是单机单人场景，能登录就说明已持有配对凭据；
 * 删除一台已配对设备只是撤销授权，对方要回来仍需配对密码，所以不再额外要一次密码。
 * （配对密码在存储层的符号名沿用 `devicePassword*`：那是持久化 key 的一部分，
 * 改名会让存量设备读不出已设置的密码。）
 *
 * 删除即吊销：每台设备持有独占 token（记录里只存哈希），删掉记录后该 token 立即失效，
 * 其余设备不受影响——不再需要「移除最后一台就轮换全局凭据」那种连坐式设计。
 *
 * 错误沿用 `{error, code}`：404 DEVICE_NOT_FOUND、400 INVALID_DEVICE_NAME。
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

    private fun fingerprintParam(call: ApplicationCall): String =
        call.parameters.getAll("fingerprint")?.joinToString("/") ?: ""

    private fun recordToPayload(record: PairedDeviceRecord): Map<String, Any?> = mapOf(
        "fingerprint" to record.fingerprint,
        "device_name" to record.deviceName,
        "last_seen" to record.lastSeen,
        "created_at" to record.createdAt,
        // 2026-10-15 新增：拉黑时要把 hwId 一起记下来（辅助键，见 PairedDeviceBlacklistStore）。
        // 纯增量字段，旧客户端忽略它；它可伪造，所以只用于"提高绕过门槛"，不参与任何授权判定。
        "hw_id" to record.hwId
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
        // 主人密码门（0.1.0 起这处就要密码，本版原样保留，只是改由 OwnerGate 统一判定）。
        // 移除一台已配对设备 = 当场吊销它的独占 token，属于不可逆操作。
        val body = runCatching { call.receiveJsonObject() }.getOrNull()
        // [owner] DELETE /api/pairing/devices/{fingerprint}
        if (!call.requireOwner(body.ownerPassword())) return
        when (val result = pairingManager.removeDevice(fingerprintParam(call))) {
            is RemoveResult.Success -> {
                call.respond(toJsonElement(mapOf("success" to true)))
            }
            is RemoveResult.DeviceNotFound -> {
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND, "Device not found")
            }
        }
    }
}
