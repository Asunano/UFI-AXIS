package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Goform SMS 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 短信列表查询
 * - 发送/删除/已读标记
 */
class GoformSmsClient(private val client: GoformClient) {
    private val tag = "GoformSms"

    suspend fun getSmsList(page: Int = 0, perPage: Int = 50): JsonObject? {
        if (!client.ensureLogin()) return null
        return try {
            val base = client.baseUrl()
            val url = "$base/goform/goform_get_cmd_process?isTest=false&multi_data=1&cmd=sms_data_total&page=$page&data_per_page=$perPage&mem_store=1&tags=10&order_by=order+by+id+desc&_=${System.currentTimeMillis()}"
            val response = client.httpGet(url)
            val responseBody = response.bodyAsText()

            if (responseBody.length > MAX_RESPONSE_BODY) {
                AppLogger.w(tag, "getSmsList: response too large (${responseBody.length}B), rejecting")
                return null
            }

            if (response.status == HttpStatusCode.OK && responseBody.isNotEmpty() && !client.isAuthFailure(responseBody)) {
                client.parseJson(responseBody).jsonObject
            } else {
                client.invalidateSession()
                if (client.ensureLogin()) {
                    val retryResp = client.httpGet(url)
                    val retryBody = retryResp.bodyAsText()
                    if (retryResp.status == HttpStatusCode.OK && retryBody.isNotEmpty() && !client.isAuthFailure(retryBody)) {
                        client.parseJson(retryBody).jsonObject
                    } else null
                } else null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform getSmsList failed", e)
            null
        }
    }

    suspend fun sendSms(phoneNumber: String, message: String): Boolean {
        val messageBody = message.toByteArray(Charsets.UTF_16BE)
            .joinToString("") { "%02x".format(it) }
        val resp = client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SEND_SMS",
            "Number" to phoneNumber, "MessageBody" to messageBody,
            "ID" to "-1", "encode_type" to "0"
        ))
        return client.isGoformSuccess(resp)
    }

    suspend fun deleteSms(msgId: String): Boolean =
        client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "DELETE_SMS",
            "msg_id" to "$msgId;", "notCallback" to "true"
        )))

    suspend fun markSmsRead(msgId: String): Boolean =
        client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_MSG_READ",
            "msg_id" to "$msgId;", "tag" to "0"
        )))

    companion object {
        private const val MAX_RESPONSE_BODY = 262_144
    }
}
