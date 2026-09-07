package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.sms.SmsController
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class RootSmsRoutes(
    private val smsController: SmsController,
    private val dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null
) {
    fun register(route: Route) {
        route.route("/sms") {
            // ── 按号码聚合的联系人列表（后端处理分组，前端直接渲染）──
            get("/contacts") {
                val contacts = smsController.getContactList()
                call.respond(toJsonElement(mapOf(
                    "contacts" to contacts,
                    "count" to contacts.size
                )))
            }

            get("/list") {
                val limit = (call.request.queryParameters["limit"] ?: "50").toIntOrNull()?.coerceIn(1, 200) ?: 50
                val offset = (call.request.queryParameters["offset"] ?: "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
                val folder = call.request.queryParameters["folder"] ?: "all"
                val phone = call.request.queryParameters["phone"]?.takeIf { it.isNotBlank() }
                val messages = when (folder) {
                    "inbox" -> smsController.getInbox(limit, offset, phone)
                    "sent" -> smsController.getSent(limit, offset, phone)
                    else -> smsController.getAll(limit, offset, phone)
                }
                // 仅在传了phone参数时计算总数（按号码过滤），否则返回当前批次大小
                val total = if (phone != null) smsController.getFilteredCount(phone) else messages.size
                call.respond(toJsonElement(mapOf(
                    "messages" to messages.map { mapOf(
                        "id" to it.id,
                        "phoneNumber" to it.address,
                        "content" to it.body,
                        "direction" to it.direction,
                        "timestamp" to it.date,
                        "read" to it.read
                    )},
                    "count" to messages.size,
                    "total" to total
                )))
            }

            get("/count") {
                call.respond(toJsonElement(mapOf(
                    "total" to smsController.getTotalCount(),
                    "unread" to smsController.getUnreadCount()
                )))
            }

            get("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid id")
                    return@get
                }
                val msg = smsController.getById(id)
                if (msg != null) call.respond(toJsonElement(mapOf(
                    "id" to msg.id, "phoneNumber" to msg.address,
                    "content" to msg.body, "direction" to msg.direction,
                    "timestamp" to msg.date, "read" to msg.read
                )))
                else call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "not found")
            }

            post("/send") {
                val body = call.receiveJsonObject()
                val phone = body["phone"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = body["message"]?.jsonPrimitive?.contentOrNull ?: ""
                if (phone.isEmpty() || message.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "phone and message required")
                    return@post
                }
                val result = smsController.send(phone, message)
                call.respond(
                    if (result.success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to result.success, "message" to result.message, "phone" to phone))
                )
            }

            post("/delete") {
                val body = call.receiveJsonObject()
                // 兼容字符串和数字类型的 ID
                val id = body["id"]?.jsonPrimitive?.longOrNull
                    ?: body["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "id required")
                    return@post
                }
                val success = smsController.delete(id)
                call.respond(toJsonElement(mapOf("success" to success, "id" to id)))
            }

            post("/read") {
                val body = call.receiveJsonObject()
                // 兼容字符串和数字类型的 ID
                val id = body["id"]?.jsonPrimitive?.longOrNull
                    ?: body["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: 0L
                val read = body["read"]?.jsonPrimitive?.booleanOrNull ?: true
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "id required")
                    return@post
                }
                val success = if (read) smsController.markAsRead(id) else smsController.markAsUnread(id)
                call.respond(toJsonElement(mapOf("success" to success, "id" to id, "read" to read)))
            }

            // 按对话（号码）批量标记已读 — 前端打开对话时调用
            post("/read-conversation") {
                val body = call.receiveJsonObject()
                val phone = body["phone"]?.jsonPrimitive?.contentOrNull ?: ""
                if (phone.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "phone required")
                    return@post
                }
                val success = smsController.markConversationRead(phone)
                call.respond(toJsonElement(mapOf("success" to success, "phone" to phone)))
            }

            // 全部标记已读
            post("/mark-all-read") {
                val success = smsController.markAllRead()
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 验证码解析缓存（DataScheduler 实时扫描写入，API 读取）
            get("/verification-codes") {
                val codes = smsController.getCachedVerificationCodes()
                call.respond(toJsonElement(mapOf(
                    "codes" to codes.map {
                        mapOf<String, Any>(
                            "msgId" to it.msg_id,
                            "code" to it.code,
                            "source" to it.source,
                            "snippet" to it.snippet,
                            // body = 原短信全文（v8 起入库）；v8 之前的旧记录为空串，客户端回退 snippet
                            "body" to it.body,
                            "timestamp" to it.timestamp,
                            "keyword" to it.keyword
                        )
                    },
                    "count" to codes.size
                )))
            }
        }
    }
}