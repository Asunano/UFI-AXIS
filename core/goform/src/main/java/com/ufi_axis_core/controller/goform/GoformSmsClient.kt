package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.SmsSpec
import com.ufi_axis_core.util.AppLogger
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.TimeZone

/**
 * Goform SMS 客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 短信列表查询
 * - 发送/删除/已读标记
 *
 * ## 设备事实全在 [SmsSpec] 里，这里只剩流程
 *
 * 参数表 / 正文编码 / `sms_time` 格式 / 信箱 tag 取值都来自 `profile.smsSpec()`
 * （ZTE 的实现见 `ZteSmsSpec`，实测依据也写在那边，**不在这里抄第二份**）。
 * 留在本类的是流程与调参：登录前置判断、发完回读确认的循环、回读次数与间隔、
 * 响应体大小上限、日志脱敏。
 *
 * ## 为什么 [profile] 是非空的
 *
 * 与 [GoformSettingWriter] 同一口径：字段归一化可以关（排障开关 → profile = null），
 * **写命令表不能关** —— 没有参数表就发不出短信。所以本类要求调用方（`ComponentFactory`）
 * 先把 profile 定下来，构造参数也**不给默认值**：默认值等于把选型逻辑散进每个客户端的签名，
 * 换设备要改 N 处且漏一处不报错。
 */
class GoformSmsClient(
    private val client: GoformClient,
    profile: DeviceProfile,
) {
    private val tag = "GoformSms"

    /**
     * 本机型的短信规则；`null` = 该设备不声明短信能力（`Capability.SMS` 缺失）。
     *
     * 为 null 时每个方法**保持自己原有的「失败」返回形态**（null / false / REJECTED）并打一行 WARN，
     * 不抛异常、不改签名 —— 上层已有的失败分支就是这条路的处理方式。ZTE profile 返回非 null，
     * 所以线上走不到这里；这条分支是为第二台设备准备的。
     */
    private val spec: SmsSpec? = profile.smsSpec()

    /** 取 spec，为空时打一行能看出是哪个入口的 WARN。 */
    private fun specOrNull(caller: String): SmsSpec? {
        val s = spec
        if (s == null) {
            AppLogger.w(tag, "$caller: 当前设备 profile 未声明短信支持（smsSpec() = null），本次操作不下发")
        }
        return s
    }

    /**
     * 拼信箱查询 URL：短信专有键来自 [SmsSpec.listQuery]，通用键在这里补。
     *
     * `isTest=false` / `multi_data=1` 对 goform 的每一次 GET 都一样，`_=<毫秒>` 是防缓存且依赖时钟，
     * 三者都不属于设备的短信知识，所以不在 spec 里（见 `SmsSpec.listQuery` 的注释）。
     *
     * ## ⚠ 这里**故意**是直接字符串拼接，不许过 URL encoder
     *
     * `order_by` 的值是字面量 `order+by+id+desc` —— query string 里的 `+` 代表**空格**，
     * 设备侧拿到的是 `order by id desc` 这条 SQL 片段。一旦对参数逐个 urlEncode，
     * `+` 会变成 `%2B`，设备收到字面加号，排序**静默失效**（不报错、只是顺序不对，
     * 而回读确认依赖 id 降序）。改这段前先真机抓包。
     */
    private fun buildSmsListUrl(spec: SmsSpec, page: Int, perPage: Int): String {
        val smsQuery = spec.listQuery(page, perPage).entries.joinToString("&") { "${it.key}=${it.value}" }
        return "${client.baseUrl()}/goform/goform_get_cmd_process?isTest=false&multi_data=1&" +
            "$smsQuery&_=${System.currentTimeMillis()}"
    }

    /**
     * 发送结论。设备回 `success` 只代表受理，最终状态要回读信箱 `tag` 才知道。
     *
     * ## 为什么 [REJECTED] 与 [NO_RESPONSE] 必须分成两档
     *
     * 上层（`LocalSmsDelivery.classify`）拿这个枚举决定**要不要重试**，而重试一条短信
     * 等于可能再花一笔话费。判据只有一个：**我们知不知道设备没收到这条发送请求**。
     * - 知道没收到（[REJECTED]）→ 重试安全，不会重复发；
     * - 不知道（[NO_RESPONSE]）→ 请求可能已经落到固件里、短信可能已经发出去了，
     *   重试就是第二条真短信、第二笔钱。
     *
     * 合成一档的代价是实测过的：`goformPost` 返回 null（请求没走完 / 响应读不出来）
     * 与「设备明确回了一个失败结果」都落进 REJECTED，整档判可重试 → 后者安全、
     * 前者会重复计费。所以分开的是"设备有没有表态"，不是"失败原因"。
     */
    enum class SendVerdict {
        /** 信箱 tag=2：设备确认已发出 */
        SENT,
        /** 信箱 tag=3：设备侧发送失败 */
        FAILED,
        /** 设备已受理，但短时间内没给最终状态（不代表失败） */
        PENDING,
        /**
         * **设备明确拒收**：请求到了设备、设备也回了响应，只是结果是"没受理"
         * （参数不合法、固件忙、或 SEND_SMS 根本没被投出去——如未登录）。
         *
         * 设备说了"我没收下"，所以重试不会重复发 → 这是唯一**可重试**的一档，
         * 也是唯一**不计配额**的一档（两件事必须同时成立，见 `LocalSmsDelivery`）。
         */
        REJECTED,
        /**
         * **拿不到设备的表态**：请求没走完 / 响应无法解析 / 超时（`goformPost` 返回 null）。
         *
         * 无法排除"设备其实已经收下并发出了"，所以**不可重试**（重试可能是第二笔话费），
         * 而配额**要计**（宁可少发一条，也不要漏计导致真实发送超过用户设的上限）。
         */
        NO_RESPONSE,
    }

    data class SendOutcome(val verdict: SendVerdict, val detail: String)


    suspend fun getSmsList(page: Int = 0, perPage: Int = 50): JsonObject? {
        val spec = specOrNull("getSmsList") ?: return null
        if (!client.ensureLogin()) return null
        return try {
            val url = buildSmsListUrl(spec, page, perPage)
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
     * 参数表（命令名 / 键名 / `sms_time` 格式 / 正文编码）来自 [SmsSpec.sendParams]，
     * 那四条设备事实与它们的实测依据都写在 profile 侧（ZTE 见 `ZteSmsSpec`）。
     * **时钟在这里读**：`System.currentTimeMillis()` + 本机时区由调用点传进 spec ——
     * spec 必须是纯函数才能在没有设备的情况下被断言，而「缺 `sms_time` / 格式不对」
     * 正是"短信能收不能发"的历史根因。
     *
     * **为什么必须回读**：`{"result":"success"}` 只表示固件把这条短信**收进发送队列**，
     * 与"运营商真的发出去了"是两件事。2026-09-01 实测就出现过 `result=success` 但对端
     * 收不到。ZTE 把最终状态写在信箱行的 `tag` 上（判据取自 [SmsSpec.sentTag] /
     * [SmsSpec.failedTag]），所以发完回读几次就能拿到真实结论，而不是让 UI 显示一个假的"发送成功"。
     *
     * **失败分两档**（判据见 [SendVerdict]）：设备回了响应但结果是拒绝 → [SendVerdict.REJECTED]；
     * 请求没走完 / 响应读不出来 → [SendVerdict.NO_RESPONSE]（不知道设备有没有已经发出去）。
     */
    suspend fun sendSms(phoneNumber: String, message: String): SendOutcome {
        val number = phoneNumber.trim()
        if (number.isEmpty() || message.isEmpty()) {
            AppLogger.w(tag, "sendSms rejected: number or body is empty")
            return SendOutcome(SendVerdict.REJECTED, "号码或内容为空")
        }
        // profile 不声明短信支持 → 一个字节都没发出去，与"未登录"同一判据（设备确定没收到）→
        // REJECTED。这台设备重试也不会成功，但至少不会重复计费、也不会误计配额。
        val spec = specOrNull("sendSms") ?: return SendOutcome(
            SendVerdict.REJECTED,
            "当前设备 profile 未声明短信支持，发送请求未发出（详见日志）"
        )
        // 先确认会话：登录不上时 SEND_SMS **一个字节都没发出去**，属于"确定没收到" →
        // REJECTED（可重试）。不先判的话它会和真正的"请求半路断了"一起变成 goformPost
        // 返回 null，被迫按 NO_RESPONSE（不可重试）处理 —— 而 UFI 的会话被官方后台挤掉
        // 是常态，那样一次会话抖动就会让 CRITICAL 通知永久丢失。
        if (!client.ensureLogin()) {
            AppLogger.w(tag, "sendSms rejected: not logged in, SEND_SMS never dispatched")
            return SendOutcome(SendVerdict.REJECTED, "设备未登录，发送请求未发出（详见日志）")
        }
        // 时钟只在这里读一次（spec 里读不了，见上面的 KDoc）
        val params = spec.sendParams(number, message, System.currentTimeMillis(), TimeZone.getDefault())
        // 先记下发送前的最大信箱 id：回读时只认新出现的行，避免把历史同号短信当成本次结果
        val baselineId = maxSmsId()
        val resp = client.goformPost(params)
        if (resp == null) {
            // 拿不到设备的表态：会话中途失效 / AD 计算失败 / HTTP 非 200 / 网络异常。
            // 请求**可能已经落到固件里**，所以这里绝不能报可重试 —— 重试就是可能的第二笔话费。
            AppLogger.e(
                tag,
                "sendSms 无法确认结果: to=${maskNumber(number)} chars=${message.length} " +
                    "sms_time=${params["sms_time"]} resp=null（看 NET/goform 与 GoformClient 日志）"
            )
            return SendOutcome(
                SendVerdict.NO_RESPONSE,
                "请求未走完 / 响应无法解析 / 超时，无法确认设备是否已发出"
            )
        }
        if (!client.isGoformSuccess(resp)) {
            // 设备回了响应、结果是拒绝 —— 它明确说了"没收下"，所以重试不会重复发。
            AppLogger.w(
                tag,
                "sendSms rejected by device: to=${maskNumber(number)} chars=${message.length} " +
                    "sms_time=${params["sms_time"]} resp=${resp.take(160)}"
            )
            return SendOutcome(SendVerdict.REJECTED, "设备明确拒收（详见日志）")
        }
        AppLogger.i(tag, "sendSms accepted by device: to=${maskNumber(number)} chars=${message.length}")
        val outcome = verifySend(spec, number, baselineId)
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
     *
     * 循环本身是**流程**（留在客户端），循环里的**判据** tag 取值是设备事实，
     * 所以由调用方把 [spec] 传进来（换设备 tag 会变，循环不变）。
     */
    private suspend fun verifySend(spec: SmsSpec, number: String, baselineId: Long): SendOutcome {
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
                    spec.sentTag() -> return SendOutcome(SendVerdict.SENT, "设备已发出")
                    spec.failedTag() -> return SendOutcome(
                        SendVerdict.FAILED,
                        "设备侧发送失败（信箱 tag=${spec.failedTag()}）：常见原因是短信中心号码(SMSC)未设置、SIM 未开通短信、" +
                            "欠费/未实名或被运营商拦截"
                    )
                    // 其它 tag（ZTE 上 4 = 草稿/待发）继续等下一轮
                }
            }
        }
        return SendOutcome(
            SendVerdict.PENDING,
            "设备已受理，但 ${VERIFY_ATTEMPTS * VERIFY_INTERVAL_MS / 1000}s 内未在信箱看到最终状态"
        )
    }



    suspend fun deleteSms(msgId: String): Boolean {
        val spec = specOrNull("deleteSms") ?: return false
        return client.isGoformSuccess(client.goformPost(spec.deleteParams(listOf(msgId))))
    }

    suspend fun markSmsRead(msgId: String, read: Boolean = true): Boolean {
        val spec = specOrNull("markSmsRead") ?: return false
        return client.isGoformSuccess(client.goformPost(spec.markReadParams(listOf(msgId), read)))
    }

    /** Goform 短信元数据（总数 / 未读），用于替代 ContentResolver 计数 */
    data class SmsMeta(val total: Int, val unread: Int)

    suspend fun getSmsMeta(): SmsMeta? {
        val spec = specOrNull("getSmsMeta") ?: return null
        if (!client.ensureLogin()) return null
        return try {
            // 只要计数，不要数据行：page=0 / data_per_page=1（其余键与 getSmsList 完全一致）
            val url = buildSmsListUrl(spec, page = 0, perPage = 1)
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

        /**
         * 发送后回读确认：3 次 × 1.2s，最多给请求加约 3.6s（用户显式操作，可接受）。
         *
         * 这是按 ZTE 实测出来的时间预算，属于调参而不是设备命令表，所以**不进 [SmsSpec]**
         * （阶段 4 归 `DeviceTuning`）。
         */
        private const val VERIFY_ATTEMPTS = 3
        private const val VERIFY_INTERVAL_MS = 1_200L

        /** 日志里的号码只留前 3 后 2（发送失败要看是不是号码串错了，但不该把完整号码写进日志）。 */
        internal fun maskNumber(number: String): String =
            if (number.length <= 5) "***" else number.take(3) + "***" + number.takeLast(2)
    }
}
