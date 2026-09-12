package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.sms.SmsController
import com.ufi_axis_core.controller.sms.SmsFilter
import com.ufi_axis_core.controller.sms.SmsRuleStore
import com.ufi_axis_core.core.database.SmsRule
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class RootSmsRoutes(
    private val smsController: SmsController,
    private val dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null,
    /**
     * 拦截规则与拦截记录（2026-09-08）。null = 降级装配，相关端点回 503。
     *
     * 挂在 `/api/sms` 下而不是新开一个 `/api/sms-filter`：规则和记录都是短信功能的一部分，
     * 而 `/api/sms` 的 owner 本来就是这个类 —— 拆出去只会让「短信相关端点在哪」这个问题多一个答案。
     */
    private val ruleStore: SmsRuleStore? = null
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

            // ═════════════ 拦截规则（号码黑名单 + 关键词）═════════════
            //
            // 路由顺序说明：`/sms/{id}` 这个参数化 GET 在上面已经注册，但 Ktor 对**常量**路径段
            // 的评分高于参数段，所以 `/sms/rules`、`/sms/blocked` 会命中下面这些常量路由，
            // 不会被 `{id}` 抢走（`{id}` 那条还会先被 `toLongOrNull() ?: 0` 挡成 400）。
            //
            // 刻意**没有** `/rules/test`（试算）：去掉正则之后
            // contains/equals/prefix/suffix 的行为是可预测的，「我的规则有没有生效」
            // 由 `hit_count` + 拦截记录回答，比试算更贴近真实使用。
            // 也**没有** configVersion 版本门：规则是逐条增删改，不存在整对象覆盖问题。

            get("/rules") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@get }
                val rules = store.listRules()
                call.respond(toJsonElement(mapOf(
                    "rules" to rules,
                    "count" to rules.size,
                    // 客户端要做「验证码豁免」这类文案提示，把可选值一并给出，省得两端各硬编码一份
                    "scopes" to SmsFilter.SCOPES.toList(),
                    "match_types" to SmsFilter.MATCH_TYPES.toList()
                )))
            }

            post("/rules") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@post }
                val p = call.receiveJsonObject()
                val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val scope = p["scope"]?.jsonPrimitive?.contentOrNull ?: SmsFilter.SCOPE_BODY
                val matchType = p["match_type"]?.jsonPrimitive?.contentOrNull ?: SmsFilter.MATCH_CONTAINS
                validateRule(pattern, scope, matchType)?.let { call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, it); return@post }

                val id = store.addRule(
                    SmsRule(
                        enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        scope = scope,
                        match_type = matchType,
                        pattern = pattern,
                        note = p["note"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                )
                if (id == null) { call.respondFilterUnavailable(); return@post }
                call.respond(toJsonElement(mapOf("success" to true, "id" to id)))
            }

            // 字段级合并：只覆盖请求体里出现的字段，缺失字段保留现值。
            // 与 `/sms-forward/config` 同一套语义 —— 全量覆盖会让「只想切 enabled」的客户端
            // 把 pattern/scope 一起重置掉。
            put("/rules/{id}") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@put }
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid id")
                    return@put
                }
                val current = store.findRule(id)
                if (current == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "rule not found")
                    return@put
                }
                val p = call.receiveJsonObject()
                val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull?.trim() ?: current.pattern
                val scope = p["scope"]?.jsonPrimitive?.contentOrNull ?: current.scope
                val matchType = p["match_type"]?.jsonPrimitive?.contentOrNull ?: current.match_type
                validateRule(pattern, scope, matchType)?.let { call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, it); return@put }

                store.updateRule(
                    current.copy(
                        enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: current.enabled,
                        scope = scope,
                        match_type = matchType,
                        pattern = pattern,
                        note = p["note"]?.jsonPrimitive?.contentOrNull ?: current.note
                    )
                )
                call.respond(toJsonElement(mapOf("success" to true, "id" to id)))
            }

            delete("/rules/{id}") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@delete }
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid id")
                    return@delete
                }
                val deleted = store.deleteRule(id)
                // deleted=0 表示该 id 已不存在，对客户端仍算成功：目标状态「它不在列表里」已达成。
                call.respond(toJsonElement(mapOf("success" to true, "id" to id, "deleted" to deleted)))
            }

            // ═════════════ 拦截记录 ═════════════
            //
            // keyset 游标分页，形态与 `/api/alerts/list` 一致（DAO 层全仓没有 OFFSET）。
            // 游标用两个显式参数而不是 base64 串：这组记录只有一种排序，
            // 没有 AlertRoutes 那种「多维过滤要跟游标一起编码」的需求。
            get("/blocked") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@get }
                val limit = (call.request.queryParameters["limit"] ?: "50").toIntOrNull()?.coerceIn(1, 200) ?: 50
                val cursorTs = call.request.queryParameters["cursor_ts"]?.toLongOrNull()
                val cursorId = call.request.queryParameters["cursor_id"]?.toLongOrNull()
                val records = store.listBlocked(cursorTs, cursorId, limit)
                val last = records.lastOrNull()
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "total" to store.countBlocked(),
                    "next_cursor_ts" to last?.blocked_at,
                    "next_cursor_id" to last?.id,
                    "has_more" to (records.size >= limit)
                )))
            }

            delete("/blocked") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@delete }
                store.clearBlocked()
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            delete("/blocked/{id}") {
                val store = ruleStore ?: run { call.respondFilterUnavailable(); return@delete }
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid id")
                    return@delete
                }
                val deleted = store.deleteBlocked(id)
                call.respond(toJsonElement(mapOf("success" to true, "id" to id, "deleted" to deleted)))
            }
        }
    }

    /**
     * 规则入参校验。返回 null = 合法，否则是给用户看的原因。
     *
     * `pattern` 空串必须拦下：`contains ""` 会命中**每一条**短信，等于一键静默全部消息。
     * （`SmsFilter` 里还有一道同样的守卫 —— 判定侧不信任 DB 内容，因为 DB 可能被外部改坏。）
     */
    private fun validateRule(pattern: String, scope: String, matchType: String): String? = when {
        pattern.isBlank() -> "规则内容（pattern）不能为空"
        scope !in SmsFilter.SCOPES -> "匹配范围（scope）必须是 ${SmsFilter.SCOPES.joinToString("/")}"
        matchType !in SmsFilter.MATCH_TYPES -> "匹配方式（match_type）必须是 ${SmsFilter.MATCH_TYPES.joinToString("/")}"
        else -> null
    }

    /** 降级装配（没有 Room DAO）下的统一回复：503 而不是 404，让客户端知道是能力缺失不是路径写错。 */
    private suspend fun io.ktor.server.application.ApplicationCall.respondFilterUnavailable() {
        respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE, "设备端未提供短信拦截功能")
    }
}