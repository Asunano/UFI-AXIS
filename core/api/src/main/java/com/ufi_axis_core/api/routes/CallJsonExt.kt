package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.goform.WriteOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 请求体解析器：宽松模式，忽略未知字段。
 */
private val RequestJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * 安全读取 JSON 对象请求体。
 *
 * 【为什么不用 Ktor 的 receive<JsonObject>()】
 * ContentNegotiation 的反序列化路径会经 serializerForTypeInfo 反射查找
 * JsonObject 的序列化器；JsonObject 使用 @Serializable(with = JsonObjectSerializer::class)，
 * 该反射查找在 Android 运行时会失败，抛出
 * JsonConvertException("Failed to convert request body to class kotlinx.serialization.json.JsonObject")
 * 并返回 HTTP 500，导致所有带 body 的 POST/PUT 接口全部不可用。
 *
 * 本函数直接读取文本再手动解析，完全绕开序列化器查找，因此稳定可靠。
 * 新增路由请统一使用本函数，不要再写 receive<JsonObject>()。
 *
 * 行为约定：
 * - 空 body 视为空 JSON 对象，便于「无参数」POST 复用同一写法；
 * - body 非法或不是 JSON 对象时抛 BadRequestException（返回 400，而非 500）。
 */
suspend fun ApplicationCall.receiveJsonObject(): JsonObject {
    val text = try {
        receiveText()
    } catch (e: Exception) {
        throw BadRequestException("Failed to read request body: ${e.message}")
    }
    if (text.isBlank()) return JsonObject(emptyMap())
    return try {
        RequestJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw BadRequestException("Request body is not a valid JSON object: ${e.message}")
    }
}

/**
 * 统一失败响应（C01）。
 *
 * 等价于 `call.respond(status, ResponseHelper.fail(code, message, extra))`，
 * 只是让路由里的失败分支能写成一行。**迁移旧代码时 status 与 message 必须原样保留**，
 * 否则就从"统一信封"变成了行为变更。
 *
 * @see ResponseHelper.fail
 */
suspend fun ApplicationCall.respondFail(
    status: HttpStatusCode,
    code: String,
    message: String,
    extra: Map<String, Any?> = emptyMap()
) {
    respond(status, ResponseHelper.fail(code, message, extra))
}

/**
 * 写操作被值域校验拒绝时回 400 `OUT_OF_RANGE`；否则什么都不做（计划书 9.5）。
 *
 * ```kotlin
 * val outcome = deviceClient.cellLock(pci, earfcn, networkType)
 * if (call.respondRejected(outcome)) return@post   // 参数非法，已回 400
 * val success = outcome.ok                          // 剩下两态沿用原逻辑
 * ```
 *
 * 只接管 `Rejected` 一态是有意的：`Ok` / `Failed` 的响应形状（`{"success": bool}`、
 * 200 还是 500）各端点历史上不一致，客户端在看这些字段，**这里不顺手"统一"**。
 *
 * @return true 表示已经写过响应，调用方必须立刻 return
 */
suspend fun ApplicationCall.respondRejected(outcome: WriteOutcome): Boolean {
    val reason = (outcome as? WriteOutcome.Rejected)?.reason ?: return false
    respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, reason)
    return true
}
