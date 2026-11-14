package com.ufi_axis_core.controller.carrier.cloud

import com.ufi_axis_core.controller.carrier.CarrierHttp
import com.ufi_axis_core.controller.carrier.CarrierCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 云上充值端（ysv2.iot098.cn）物联卡客户端（2026-11-08）。
 *
 * 该站点**只有 HTTP**（HTTPS 证书不匹配），且登录只需充值号；Cookie 是 HttpOnly、
 * 有效期约 100 天。Cookie 只存在 core 的加密账号里，绝不下发客户端。
 *
 * 这里刻意不实现充值/购买/发票等写操作，只做账单页需要的只读查询：
 * login → card/get → order/list(effectiveType=1)。
 */
open class CloudCardApiException(
    val code: Int?,
    val apiMessage: String?,
    message: String = apiMessage ?: "云上卡接口失败（code=$code）",
) : Exception(message)

/** `code=14`：会话失效，需要重新登录。 */
class CloudCardAuthRequiredException(message: String? = null) :
    CloudCardApiException(14, message, message ?: "云上卡会话已失效，请重新登录")

/** `code=23`：refresh 限流，退避后再试。 */
class CloudCardRateLimitedException(message: String? = null) :
    CloudCardApiException(23, message, message ?: "当前刷新人数过多，请稍后再试")

/**
 * `code=27`：登录需要验证（2026-11-09 小鲸卡）。
 *
 * `verifyType` 对应文档 `data.type`：0 安全密码 / 1 短信验证码 / 2 手机号后四位。
 * 小鲸卡本卡是 2；云上卡不会走到这里。`maskedPhone` 是 `data.phone`，用于提示用户。
 */
class CloudCardNeedVerifyException(
    val verifyType: Int?,
    val maskedPhone: String?,
    message: String? = null,
) : CloudCardApiException(27, message, message ?: "登录需要验证")

data class CloudCardSnapshot(
    val cardNo: String?,
    val iccid: String?,
    val expiresAt: String?,
    val usedMb: Double?,
    val freeMb: Double?,
    val realUsedMb: Double?,
    val state: Int?,
    val officialRealName: Int?,
    val balanceYuan: Double?,
    val flowRefreshTime: String?,
)

data class CloudPlan(
    val name: String,
    val totalMb: Double?,
    val usedMb: Double?,
    val effectiveTime: String?,
    val expirationTime: String?,
    val seriesName: String?,
)

/** 订单详情（账单页“订单详情”卡）。 */
data class CloudOrder(
    val name: String,
    val amountYuan: Double?,
    val paidYuan: Double?,
    val statusText: String,
    val payTypeText: String?,
    val createdAt: String?,
    val effectiveTime: String?,
    val expirationTime: String?,
)

/** 原始订单里的真实额度；客户端订单接口已被替换成虚量，只能用开票原始订单推算。 */
data class CloudRealOrder(
    val name: String,
    val flowRealMb: Double?,
    val flowUsedMb: Double?,
    val effectiveType: Int?,
    val state: Int?,
    val expirationTime: String?,
)

/** 登录返回的套餐系列配置里，账单页只关心展示名。 */
data class CloudSeries(
    val name: String?,
    val showName: String?,
)

class CloudCardClient(
    http: OkHttpClient? = null,
    val cookieJar: CarrierCookieJar = CarrierCookieJar(),
    private val baseUrl: String = BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = (http ?: defaultClient())
        .newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
            )
        }
        .build()

    suspend fun login(cardNo: String, verify: String? = null): CloudSeries {
        val form = FormBody.Builder().add("card", cardNo).apply {
            verify?.takeIf { it.isNotBlank() }?.let { add("verify", it) }
        }.build()
        val data = execute(
            Request.Builder()
                .url(baseUrl + "/app/card/login")
                .post(form)
                .build()
        )
        return CloudSeries(
            name = data.string("name"),
            showName = data.string("showName"),
        )
    }

    suspend fun cardInfo(): CloudCardSnapshot =
        execute(Request.Builder().url(baseUrl + "/app/client/card/get").get().build())
            .toSnapshot()

    suspend fun plans(): List<CloudPlan> {
        val data = execute(
            Request.Builder()
                .url(
                    (baseUrl + "/app/client/order/get/list").toHttpUrl().newBuilder()
                        .addQueryParameter("effectiveType", "1")
                        .addQueryParameter("page", "1")
                        .addQueryParameter("size", "10")
                        .build()
                )
                .get()
                .build()
        )
        return data["list"]?.asArray()?.mapNotNull { it.asObject()?.toPlan() }.orEmpty()
    }

    /** 套餐充值订单（不传 effectiveType = 全部订单，含历史/失败单）。 */
    suspend fun orders(): List<CloudOrder> {
        val data = execute(
            Request.Builder()
                .url(
                    (baseUrl + "/app/client/order/get/list").toHttpUrl().newBuilder()
                        .addQueryParameter("page", "1")
                        .addQueryParameter("size", "100")
                        .build()
                )
                .get()
                .build()
        )
        return data["list"]?.asArray()?.mapNotNull { it.asObject()?.toOrder() }.orEmpty()
    }

    /**
     * 可开票订单（原始实体）里的 `flowReal` / `flowUsed`。
     *
     * 客户端订单接口把 flowReal/flowUsed 替换成了虚量值；只有这个接口保留真实额度。
     * 用来推算真实总量/真实剩余，失败时调用方降级为只显示 realUsed。
     */
    suspend fun realOrders(): List<CloudRealOrder> {
        val data = execute(
            Request.Builder()
                .url(
                    (baseUrl + "/app/card/invoice/submit/record/client/wait/order/list")
                        .toHttpUrl().newBuilder()
                        .addQueryParameter("orderType", "0")
                        .addQueryParameter("page", "1")
                        .addQueryParameter("size", "100")
                        .build()
                )
                .get()
                .build()
        )
        val list = data["setMealOrderList"]?.asArray() ?: data["list"]?.asArray()
        return list?.mapNotNull { it.asObject()?.toRealOrder() }.orEmpty()
    }

    /** 可选：让服务端立即向上游同步；调用方必须自己做 2~5 分钟节流。 */
    suspend fun refresh() {
        execute(Request.Builder().url(baseUrl + "/app/client/card/refresh").get().build())
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
                throw CloudCardApiException(
                    null,
                    null,
                    "云上卡返回不是 JSON（HTTP ${response.code}）"
                )
            }
            val code = payload["code"]?.jsonPrimitive?.intOrNull
            if (code != 0) {
                val msg = payload["msg"]?.jsonPrimitive?.contentOrNull
                when (code) {
                    14 -> throw CloudCardAuthRequiredException(msg)
                    23 -> throw CloudCardRateLimitedException(msg)
                    27 -> {
                        val data = payload["data"]?.asObject()
                        throw CloudCardNeedVerifyException(
                            verifyType = data?.int("type"),
                            maskedPhone = data?.string("phone"),
                            message = msg,
                        )
                    }
                    else -> throw CloudCardApiException(code, msg)
                }
            }
            payload["data"]?.asObject() ?: JsonObject(emptyMap())
        }
    }

    companion object {
        /** 站点只支持 HTTP；HTTPS 证书是腾讯云 CDN 的，校验必失败。 */
        const val BASE_URL = "http://ysv2.iot098.cn"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

        private fun defaultClient(): OkHttpClient = CarrierHttp.cloud
    }
}

internal fun JsonObject.toSnapshot(): CloudCardSnapshot = CloudCardSnapshot(
    cardNo = string("card"),
    iccid = string("iccid"),
    expiresAt = string("expirationTime"),
    usedMb = double("used"),
    freeMb = double("free"),
    realUsedMb = double("realUsed"),
    state = int("state"),
    officialRealName = int("officialRealName"),
    balanceYuan = double("balance"),
    flowRefreshTime = string("flowRefreshTime"),
)

internal fun JsonObject.toPlan(): CloudPlan? {
    val name = string("setMealName") ?: return null
    return CloudPlan(
        name = name,
        totalMb = double("flowVirtual") ?: double("flowReal"),
        usedMb = double("flowUsedVirtual") ?: double("flowUsed"),
        effectiveTime = string("effectiveTime"),
        expirationTime = string("expirationTime"),
        seriesName = get("card")?.asObject()?.get("series")?.asObject()?.string("name"),
    )
}

private fun JsonObject.toRealOrder(): CloudRealOrder? {
    val flowReal = double("flowReal")
    if (flowReal == null) return null
    return CloudRealOrder(
        name = string("setMealName") ?: "套餐订单",
        flowRealMb = flowReal,
        flowUsedMb = double("flowUsed"),
        effectiveType = int("effectiveType"),
        state = int("state"),
        expirationTime = string("expirationTime"),
    )
}

private fun JsonObject.toOrder(): CloudOrder? {
    val name = string("setMealName") ?: string("outTradeNo") ?: return null
    return CloudOrder(
        name = name,
        amountYuan = double("amount"),
        paidYuan = double("paymentAmount"),
        statusText = when (int("state")) {
            0 -> "等待"
            1 -> "进行中"
            2 -> "成功"
            3 -> "失败"
            4 -> "退款"
            -1 -> "删除"
            else -> "未知"
        },
        payTypeText = when (int("payType")) {
            0 -> "余额"
            1 -> "支付宝"
            2 -> "微信"
            3 -> "接口"
            4 -> "批量"
            else -> null
        },
        createdAt = string("createTime"),
        effectiveTime = string("effectiveTime"),
        expirationTime = string("expirationTime"),
    )
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

private fun JsonObject.double(key: String): Double? =
    get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? =
    runCatching { jsonArray }.getOrNull()
