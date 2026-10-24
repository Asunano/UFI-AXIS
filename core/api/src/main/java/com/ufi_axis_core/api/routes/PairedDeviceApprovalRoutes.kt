package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.pairing.ApprovalPushNudger
import com.ufi_axis_core.api.pairing.PairedDeviceApprovalStore
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.pairing.PairingNotificationMask
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * 新设备批准端点（2026-10-15）。
 *
 * ## 公开端点（root，免鉴权 —— 请求方此时还没有任何凭据）
 * - `POST /pairing/request-approval`  `{sid, device_pubkey, device_name}` → `{sid, expires_in, status}`
 * - `GET  /pairing/approval-status?sid=` → `{status}`（pending / approved / rejected / expired）
 *
 * **这两个端点永不返回配对码**：返回了就等于请求方自己批准自己，整套机制只剩形式。
 * 码只出现在已鉴权的待批准列表（App 弹窗显示）与机主配置的外部通知里。
 *
 * ## 已鉴权端点（挂 /api 内，Bearer + 签名）
 * - `GET  /api/pairing/approvals`                    → 待批准列表（**含码**，供 App 弹窗）
 * - `POST /api/pairing/approvals/{sid}/approve`      → 机主点「允许」
 * - `POST /api/pairing/approvals/{sid}/reject`       → 机主点「拒绝」
 *
 * ## 防刷
 * 请求本身是**幂等**的（同一 sid 只保留一条，重复请求不换码、不新建），加上
 * [PairedDeviceApprovalStore.MAX_PENDING] 上限与 15 分钟 TTL，待批准表始终有界。
 * 同一 sid 反复请求只会刷新展示信息 —— Web 刷新页面不会把机主的手机刷屏。
 *
 * 推送（2026-10-21）另外单独设闸，四道叠加：
 * 1. 只有**新** sid 才触发一轮唤醒（幂等命中不重推，否则刷新一次就给 App 多一轮）；
 * 2. 来源 IP 的新请求数上限（[PairingManager.allowApprovalRequest]，5 次 / 15 分钟）；
 * 3. [ApprovalPushNudger] 的全局预算（6 轮 / 60 秒）与并发上限（4 轮）；
 * 4. 每帧前复查 `pending`，机主一批准就停。
 */
class PairedDeviceApprovalRoutes(
    private val pairingManager: PairingManager,
    /**
     * 新请求登记后的回调（2026-10-15），用于发**外部通知**（邮件 / Webhook / 系统通知）。
     *
     * 用回调而不是直接依赖通知分发器：本类在 `core:api`、分发器在装配层，
     * 由装配层注入比让路由反向依赖清楚（与 `PairingRoutes.onBlockedDevice` 同款）。
     * 传 null 时只登记、不通知（测试与未装配场景）。
     *
     * ⚠ 这条受 [PairingManager.allowApprovalNotification] 的**全局限速**约束
     *（15 分钟 3 条），因为外部通知会把机主的手机叫醒。
     */
    private val onApprovalRequested: (suspend (PairedDeviceApprovalStore.Pending) -> Unit)? = null,
    /**
     * 「新请求已登记」回调（2026-10-21），用于向**在线的已配对 App** 主动推送唤醒帧。
     *
     * 与 [onApprovalRequested] 分开是刻意的：那个是外部通知（受 15 分钟 3 条的限速），
     * 这里是 App 内弹窗通道 —— 语义与配额都不同。混成一个回调的后果很具体：
     * 外部通知被限速时，App 弹窗会跟着一起不推（`notified` 置位是同一个判据）。
     *
     * 只在**新** sid 上触发；幂等命中（Web 刷新页面重发同一个 sid）不重推。
     * 回调必须**立即返回**（装配层传的是 `ApprovalPushNudger.onNewApproval`，内部只 `launch`），
     * 否则会把 `request-approval` 的响应也拖住。
     */
    private val onApprovalPending: ((PairedDeviceApprovalStore.Pending) -> Unit)? = null
) {

    /**
     * 公开端点（root，免鉴权）。
     *
     * ⚠ 调用方是 `HttpServer.configureRouting` 里的 `route("/pairing") { ... }` ——
     * 前缀已经在那层加过了，所以这里必须写**相对路径**。
     * 2026-10-17 修：之前写成 `"/pairing/request-approval"`，实际注册成
     * `/pairing/pairing/request-approval`，Web 端拿到 404、轮询又把任何失败当成"过期"，
     * 表现就是"申请验证码显示配对申请已过期"。
     */
    fun registerPublic(route: Route) {
        route.apply {
            post("/request-approval") { handleRequest(call) }
            get("/approval-status") { handleStatus(call) }
        }
    }

    /**
     * 已鉴权端点（挂 `/api` 内）。
     *
     * 这里写**完整**的 `/pairing/...` 前缀（与 `PairedDevicesRoutes` 一致）：
     * 调用方在 `route("/api")` 里直接 register，外层只加了 `/api`。
     */
    fun registerAuthed(route: Route) {
        route.apply {
            get("/pairing/approvals") { handleList(call) }
            post("/pairing/approvals/{sid}/approve") { handleDecision(call, approve = true) }
            post("/pairing/approvals/{sid}/reject") { handleDecision(call, approve = false) }
        }
    }

    @Serializable
    private data class RequestBody(
        val sid: String = "",
        val device_pubkey: String = "",
        val device_name: String? = null
    )

    private fun sidParam(call: ApplicationCall): String = call.parameters["sid"].orEmpty()

    private fun clientIp(call: ApplicationCall): String = call.request.local.remoteAddress

    /**
     * 来源的**掩码**展示（2026-10-15）。
     *
     * 规则只有一份实现：[PairingNotificationMask.source]。三处展示（App 弹窗、外部通知、
     * 事件中心）共用它 —— 各写一遍迟早出现"弹窗里遮住了、邮件里漏出去了"。
     */
    private fun maskedSource(call: ApplicationCall): String =
        PairingNotificationMask.source(clientIp(call))

    private suspend fun handleRequest(call: ApplicationCall) {
        val body = try { call.receive<RequestBody>() } catch (e: Exception) { null }
        val sid = body?.sid?.trim().orEmpty()
        if (sid.isEmpty()) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "sid required")
            return
        }
        // 2026-10-24：批准策略已关时**不登记**。旧页面/旧客户端仍可能拿着"上一次 403"来
        // request-approval；放行会把一条没人需要的请求重新弹到机主面前。
        // 回 501 而不是 403：Web 端的 requestApproval 把 501 解释为"设备端没启用批准，
        // 继续走正常配对"，正好是关闭后的正确下一步。
        if (!pairingManager.approvalRequired()) {
            call.respondFail(
                HttpStatusCode.NotImplemented,
                ErrorCode.NOT_SUPPORTED,
                "本设备端未启用批准流程"
            )
            return
        }
        // 限速（2026-10-15，方案 §6.4）：只对**新请求**计数。
        // 幂等命中已有 sid（Web 刷新页面）必须放行 —— 否则用户刷几次页面就把自己限住了。
        //
        // 2026-10-21 修正（审计 §A2）：**被拒绝**的 sid 算"新请求"。
        // 拒绝是终态，复用同一个 sid 会让请求方在 15 分钟 TTL 内永远读到 `rejected`，
        // 机主也无法再批准 —— 那就是个死胡同。现在它按新请求重建（见 store.request）。
        val statusBefore = pairingManager.approvalStatus(sid)
        val isExisting = statusBefore == PairedDeviceApprovalStore.STATUS_PENDING ||
            statusBefore == PairedDeviceApprovalStore.STATUS_APPROVED
        if (!isExisting && !pairingManager.allowApprovalRequest(clientIp(call))) {
            call.respondFail(
                HttpStatusCode.TooManyRequests,
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many approval requests, retry later"
            )
            return
        }
        val pending = pairingManager.requestApproval(
            sid = sid,
            pubKeySpki = body?.device_pubkey,
            deviceName = body?.device_name,
            source = maskedSource(call)
        )
        if (pending == null) {
            // 未装配批准 store，或公钥解析不了 —— 两种情况请求方都做不了什么，
            // 如实回"当前不可用"，而不是假装成功让它一直轮询。
            call.respondFail(
                HttpStatusCode.NotImplemented,
                ErrorCode.NOT_SUPPORTED,
                "本设备端未启用批准流程"
            )
            return
        }
        AppLogger.i(TAG, "Approval requested sid=${sid.take(8)} source=${pending.source}")
        // 主动推送（2026-10-21）：让**在线的已配对 App** 立刻去拉列表弹窗。
        //
        // 只在新 sid 上触发：幂等命中 = Web 刷新页面重发同一个 sid，再推一轮等于
        // 「用户刷几次页面，机主手机就被多唤醒几轮」。推送的限速在 ApprovalPushNudger 里，
        // 那条链比外部通知更宽（这里是 App 内弹窗，不是把人叫醒的邮件），所以两者分开判。
        //
        // 回调内部只 `launch`，不阻塞这里 —— 响应必须跟着就走，Web 端还等着它进等待态。
        if (!isExisting) {
            runCatching { onApprovalPending?.invoke(pending) }
                .onFailure { AppLogger.w(TAG, "approval push nudge failed: ${it.message}") }
        }
        // 外部通知：**每条请求只发一次**（`notified` 置位）—— 同一 sid 的重复请求是幂等的，
        // 但通知不能跟着幂等，否则 Web 刷新一次机主就多收一封邮件。
        // 外加全局限速：通知会把机主的手机叫醒，而发起请求是**任何人**都能做的。
        if (!pending.notified && pairingManager.allowApprovalNotification()) {
            pending.notified = true
            runCatching { onApprovalRequested?.invoke(pending) }
                .onFailure { AppLogger.w(TAG, "approval notify failed: ${it.message}") }
        }
        // ★ 响应里**没有** code 字段 —— 见类注释
        call.respond(
            toJsonElement(
                mapOf(
                    "sid" to pending.sid,
                    "status" to pairingManager.approvalStatus(sid),
                    "expires_in" to ((pending.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                )
            )
        )
    }

    private suspend fun handleStatus(call: ApplicationCall) {
        val sid = call.request.queryParameters["sid"].orEmpty()
        if (sid.isBlank()) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "sid required")
            return
        }
        // 2026-10-21（审计 §C4）：这条链路免鉴权、且请求方等待期一直在轮询，
        // 加一道很宽的每 IP 上限，只拦"跑飞的循环"（120 次 / 10 秒）。
        if (!pairingManager.allowApprovalStatusQuery(clientIp(call))) {
            call.respondFail(
                HttpStatusCode.TooManyRequests,
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many status queries, retry later"
            )
            return
        }
        call.respond(toJsonElement(mapOf("status" to pairingManager.approvalStatus(sid))))
    }

    private suspend fun handleList(call: ApplicationCall) {
        val devices = pairingManager.pendingApprovals().map { p ->
            mapOf(
                "sid" to p.sid,
                "code" to p.code,
                "device_name" to p.deviceName,
                "source" to p.source,
                "requested_at" to p.requestedAt,
                "expires_at" to p.expiresAt,
                "approved" to p.approved,
                "rejected" to p.rejected
            )
        }
        call.respond(toJsonElement(mapOf("approvals" to devices)))
    }

    private suspend fun handleDecision(call: ApplicationCall, approve: Boolean) {
        val sid = sidParam(call)
        if (sid.isBlank()) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "sid required")
            return
        }
        val ok = if (approve) pairingManager.approveDevice(sid) else pairingManager.rejectDevice(sid)
        if (!ok) {
            // 过期 / 已被拒绝 / 不存在 —— 对机主来说都是"这条已经不用处理了"
            call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Approval request not found or expired")
            return
        }
        AppLogger.i(TAG, "Approval ${if (approve) "granted" else "denied"} sid=${sid.take(8)}")
        call.respond(toJsonElement(mapOf("success" to true)))
    }

    private companion object {
        /** 与 PairingManager / PairingRoutes 共用同一日志标签，配对全流程可一次过滤出来。 */
        private const val TAG = "Pairing"
    }
}
