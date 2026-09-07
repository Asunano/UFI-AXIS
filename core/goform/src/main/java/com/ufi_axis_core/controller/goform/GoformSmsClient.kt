package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Goform SMS 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 短信列表查询
 * - 发送/删除/已读标记
 */
class GoformSmsClient(private val client: GoformClient) {
    private val tag = "GoformSms"

    /** 发送结论。设备回 `success` 只代表受理，最终状态要回读信箱 `tag` 才知道。 */
    enum class SendVerdict {
        /** 信箱 tag=2：设备确认已发出 */
        SENT,
        /** 信箱 tag=3：设备侧发送失败 */
        FAILED,
        /** 设备已受理，但短时间内没给最终状态（不代表失败） */
        PENDING,
        /** 设备直接拒收（参数不合法 / 未登录 / AD 失败） */
        REJECTED,
    }

    data class SendOutcome(val verdict: SendVerdict, val detail: String)


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
                client.parseJson(responseBody)?.jsonObject
            } else {
                AppLogger.w(tag, "getSmsList auth failure, invalidating session (status=${response.status})")
                client.invalidateSession()
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform getSmsList failed", e)
            null
        }
    }

    /**
     * 发送短信（goform `SEND_SMS`）+ **回读设备信箱确认真实结果**。
     *
     * 参数集按 ZTE 固件的实际要求给全：
     * 1. `sms_time`（固件必填，缺了直接判失败，格式见 [formatSmsTime]）；
     * 2. `encode_type=UNICODE` 与 UCS2 正文配套（固件只认 `UNICODE` / `GSM7_default`
     *    这两个枚举，早期写成数值 `0` 属于无效值）；
     * 3. `notCallback=true`，与删除 / 标记已读保持一致。
     *
     * **为什么必须回读**：`{"result":"success"}` 只表示固件把这条短信**收进发送队列**，
     * 与"运营商真的发出去了"是两件事。2026-09-01 实测就出现过 `result=success` 但对端
     * 收不到。ZTE 把最终状态写在信箱行的 `tag` 上（`2`=已发送、`3`=发送失败、`4`=草稿），
     * 所以发完回读几次就能拿到真实结论，而不是让 UI 显示一个假的"发送成功"。
     */
    suspend fun sendSms(phoneNumber: String, message: String): SendOutcome {
        val number = phoneNumber.trim()
        if (number.isEmpty() || message.isEmpty()) {
            AppLogger.w(tag, "sendSms rejected: number or body is empty")
            return SendOutcome(SendVerdict.REJECTED, "号码或内容为空")
        }
        val params = buildSendParams(number, message, formatSmsTime())
        // 先记下发送前的最大信箱 id：回读时只认新出现的行，避免把历史同号短信当成本次结果
        val baselineId = maxSmsId()
        val resp = client.goformPost(params)
        if (!client.isGoformSuccess(resp)) {
            AppLogger.w(
                tag,
                "sendSms rejected by device: to=${maskNumber(number)} chars=${message.length} " +
                    "sms_time=${params["sms_time"]} " +
                    "resp=${resp?.take(160) ?: "null（登录/AD/会话失败，看 NET/goform 与 GoformClient 日志）"}"
            )
            return SendOutcome(SendVerdict.REJECTED, "设备未受理（详见日志）")
        }
        AppLogger.i(tag, "sendSms accepted by device: to=${maskNumber(number)} chars=${message.length}")
        val outcome = verifySend(number, baselineId)
        when (outcome.verdict) {
            SendVerdict.SENT -> AppLogger.i(tag, "sendSms confirmed sent: to=${maskNumber(number)}")
            SendVerdict.FAILED -> AppLogger.e(tag, "sendSms 设备侧发送失败: to=${maskNumber(number)} ${outcome.detail}")
            else -> AppLogger.w(tag, "sendSms 结果未确认: to=${maskNumber(number)} ${outcome.detail}")
        }
        return outcome
    }

    /** 发送前的最大信箱 id；取不到（读失败）返回 -1，此时回读只能按号码匹配。 */
    private suspend fun maxSmsId(): Long =
        getSmsList(page = 0, perPage = 20)?.get("messages")?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.longOrNull }
            ?.maxOrNull() ?: -1L

    /**
     * 回读设备信箱，找本次发送对应的新行并按 `tag` 判定结果。
     *
     * 号码用后 6 位比对：设备回填的号码可能带 `+86` 前缀，全等比对会漏。
     */
    private suspend fun verifySend(number: String, baselineId: Long): SendOutcome {
        val suffix = number.takeLast(6)
        repeat(VERIFY_ATTEMPTS) {
            kotlinx.coroutines.delay(VERIFY_INTERVAL_MS)
            val rows = getSmsList(page = 0, perPage = 20)?.get("messages")?.jsonArray ?: return@repeat
            for (el in rows) {
                val row = el.jsonObject
                val id = row["id"]?.jsonPrimitive?.longOrNull ?: continue
                if (baselineId >= 0 && id <= baselineId) continue
                val num = row["number"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (suffix.isNotEmpty() && !num.endsWith(suffix)) continue
                when (row["tag"]?.jsonPrimitive?.contentOrNull) {
                    TAG_SENT -> return SendOutcome(SendVerdict.SENT, "设备已发出")
                    TAG_SEND_FAILED -> return SendOutcome(
                        SendVerdict.FAILED,
                        "设备侧发送失败（信箱 tag=3）：常见原因是短信中心号码(SMSC)未设置、SIM 未开通短信、" +
                            "欠费/未实名或被运营商拦截"
                    )
                    // tag=4 是草稿/待发，继续等下一轮
                }
            }
        }
        return SendOutcome(
            SendVerdict.PENDING,
            "设备已受理，但 ${VERIFY_ATTEMPTS * VERIFY_INTERVAL_MS / 1000}s 内未在信箱看到最终状态"
        )
    }



    suspend fun deleteSms(msgId: String): Boolean =
        client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "DELETE_SMS",
            "msg_id" to "$msgId;", "notCallback" to "true"
        )))

    suspend fun markSmsRead(msgId: String, read: Boolean = true): Boolean =
        client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_MSG_READ",
            "msg_id" to "$msgId;", "tag" to if (read) "0" else "1"
        )))

    /** Goform 短信元数据（总数 / 未读），用于替代 ContentResolver 计数 */
    data class SmsMeta(val total: Int, val unread: Int)

    suspend fun getSmsMeta(): SmsMeta? {
        if (!client.ensureLogin()) return null
        return try {
            val base = client.baseUrl()
            val url = "$base/goform/goform_get_cmd_process?isTest=false&multi_data=1&cmd=sms_data_total&page=0&data_per_page=1&mem_store=1&tags=10&order_by=order+by+id+desc&_=${System.currentTimeMillis()}"
            val response = client.httpGet(url)
            val responseBody = response.bodyAsText()
            if (response.status == HttpStatusCode.OK && responseBody.isNotEmpty() && !client.isAuthFailure(responseBody)) {
                val json = client.parseJson(responseBody)?.jsonObject
                if (json != null) {
                    SmsMeta(intOf(json["sms_nv_rev_total"]), intOf(json["sms_unread_num"]))
                } else null
            } else {
                AppLogger.w(tag, "getSmsMeta auth failure, invalidating session")
                client.invalidateSession()
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform getSmsMeta failed", e)
            null
        }
    }

    private fun intOf(el: JsonElement?): Int {
        if (el == null) return 0
        return try {
            if (el is JsonPrimitive && el.isString) el.content.toIntOrNull() ?: 0
            else el.jsonPrimitive.intOrNull ?: 0
        } catch (_: Exception) { 0 }
    }

    companion object {
        private const val MAX_RESPONSE_BODY = 262_144

        /** ZTE 信箱行的 tag 语义（0/1=收到，2=已发送，3=发送失败，4=草稿） */
        private const val TAG_SENT = "2"
        private const val TAG_SEND_FAILED = "3"

        /** 发送后回读确认：3 次 × 1.2s，最多给请求加约 3.6s（用户显式操作，可接受） */
        private const val VERIFY_ATTEMPTS = 3
        private const val VERIFY_INTERVAL_MS = 1_200L


        /**
         * `SEND_SMS` 的参数表（抽出来是为了能在没有设备的情况下断言，见 GoformSmsSendParamsTest）。
         *
         * `AD` 不在这里 —— 它由 `GoformClient.computeAd` 统一追加（与参数值无关）。
         */
        internal fun buildSendParams(number: String, message: String, smsTime: String): Map<String, String> =
            linkedMapOf(
                "isTest" to "false",
                "goformId" to "SEND_SMS",
                "notCallback" to "true",
                "Number" to number,
                "sms_time" to smsTime,
                "MessageBody" to toUcs2Hex(message),
                "ID" to "-1",
                "encode_type" to "UNICODE",
            )

        /** UCS2：UTF-16BE 大端字节的小写 hex，无 BOM、无长度前缀。 */
        internal fun toUcs2Hex(message: String): String =
            message.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it) }

        /**
         * `sms_time` = `yy;MM;dd;HH;mm;ss;+TZ`，TZ 是相对 UTC 的**小时**偏移（东八区 → `+8`）。
         *
         * 用设备时区无从得知，取本机时区（core 跑在这台设备上，和固件同一个时钟源）；
         * 半小时制时区给成 `+5.5` 这种小数形式。
         */
        internal fun formatSmsTime(millis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
            val cal = Calendar.getInstance(zone).apply { timeInMillis = millis }
            val offsetMin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60_000
            val sign = if (offsetMin < 0) "-" else "+"
            val absMin = abs(offsetMin)
            val tz = if (absMin % 60 == 0) "$sign${absMin / 60}"
            else sign + String.format(Locale.US, "%.1f", absMin / 60.0)
            return String.format(
                Locale.US, "%02d;%02d;%02d;%02d;%02d;%02d;%s",
                cal.get(Calendar.YEAR) % 100,
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND),
                tz,
            )
        }

        /** 日志里的号码只留前 3 后 2（发送失败要看是不是号码串错了，但不该把完整号码写进日志）。 */
        internal fun maskNumber(number: String): String =
            if (number.length <= 5) "***" else number.take(3) + "***" + number.takeLast(2)
    }
}
