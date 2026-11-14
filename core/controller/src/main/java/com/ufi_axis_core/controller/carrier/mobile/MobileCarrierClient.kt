package com.ufi_axis_core.controller.carrier.mobile

import com.ufi_axis_core.controller.carrier.CarrierHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.ufi_axis_core.controller.carrier.CarrierCookie
import com.ufi_axis_core.controller.carrier.CarrierCookieJar
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 中国移动 App 域 H5 客户端（2026-10-31，从 probe 的 `app_login.py` + `MobileProbe._fetch_app` 移植）。
 *
 * 只做三件事：发码、验证码登录、8 个取数接口。会话 Cookie 由调用方通过 [MobileCookieJar]
 * 持有并加密落盘；本类不碰磁盘、不写日志。
 *
 * ## 协议要点（别照直觉改）
 * - envelope 的 `cid/en/t/sn/cv/st/sv/sp` 全填 `"0"`，`xc="web"`，`prov="100"`，`city="0000"`，
 *   `tel="99999999999"`；
 * - 验证码字段名是 `verifyCode`，`sendSmsFlag=0`，`sx` 必填；
 * - `x-token = enc(xk_path_ts_nonce)`，`x-sign = md5(token_ts_nonce_null)`；
 * - 请求体是 **Base64 密文**，不是 URL 编码表单；Content-Type 仍是 form-urlencoded。
 */
class MobileCarrierClient(
    http: OkHttpClient? = null,
    val cookieJar: CarrierCookieJar = CarrierCookieJar(),
) {
    /**
     * 必须显式把 cookieJar 挂到 OkHttp 上。
     *
     * 2026-10-31 真机事故：构造函数里有 cookieJar 字段，但 `defaultClient()` 没调
     * `.cookieJar(...)` —— 发码/登录都成功，Cookie 全丢；账单接口回 409，UI 表现成
     * 「登录成功但界面没变化」。
     */
    private val http: OkHttpClient = (http ?: defaultClient())
        .newBuilder()
        .cookieJar(cookieJar)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** 发码；返回 `rspBody.sx`（登录时必须回填）。 */
    suspend fun sendCode(phone: String): String {
        val body = post(PATH_SEND, buildJsonObject {
            put("cellNum", phone)
            put("channlCode", "2")
        })
        requireOk(body, "发码")
        return body.rspBody().get("sx")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw MobileCarrierException(body.retCode(), "发码成功但没有返回 sx 票据，登录会失败")
    }

    /** 验证码登录；成功后 Cookie 已在 [cookieJar] 里。 */
    suspend fun login(phone: String, code: String, sx: String) {
        val body = post(PATH_LOGIN, buildJsonObject {
            put("cellNum", phone)
            put("verifyCode", code)
            put("sendSmsFlag", 0)
            put("sx", sx)
            put("channlCode", "2")
        })
        requireOk(body, "登录")
    }

    /**
     * 取 8 个接口。
     *
     * 任一接口失败不会抛异常：该键对应一个 `{"__error": "..."}` 的 JSON，
     * 由 parser 转成 warnings —— 余额/流量能显示比整页失败有用。
     */
    suspend fun fetch(phone: String): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for ((name, path) in ENDPOINTS) {
            out[name] = runCatching {
                val body = post(path, buildJsonObject { put("cellNum", phone) })
                // 运营商鉴权失败经常是 HTTP 200 + retCode=400006；不检查就会把空 rspBody
                // 当成功，parser 输出一屏 0.00。必须在取数层就把 retCode 变成错误。
                requireOk(body, name)
                body
            }.getOrElse { error ->
                buildJsonObject { put("__error", error.message ?: error.javaClass.simpleName) }
            }
        }
        return out
    }

    private suspend fun post(path: String, reqBody: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val xk = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis().toString()
        val nonce = digits(8)
        val envelope = buildJsonObject {
            put("cid", "0"); put("en", "0"); put("t", "0"); put("sn", "0")
            put("cv", "0"); put("st", "0"); put("sv", "0"); put("sp", "0")
            put("xk", xk); put("xc", "web"); put("imei", ""); put("nt", ""); put("sb", "")
            put("prov", "100"); put("city", "0000"); put("tel", "99999999999")
            put("reqBody", reqBody)
        }
        val body = MobileCrypto.encryptRequest(envelope.toString())
        val token = MobileCrypto.encryptToken("${xk}_${path}_${ts}_$nonce")
        val request = Request.Builder()
            .url(HOST + path)
            .post(body.toRequestBody(FORM))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("x-time", ts)
            .header("x-nonce", nonce)
            .header("x-token", token)
            .header("x-sign", MobileCrypto.sign(token, ts, nonce))
            .header("TRACE", "${xk}_${path}_${ts}_${alnum(20)}")
            .header("x-qen", "1")
            .header("Origin", "https://wx.10086.cn")
            .header("Referer", "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery")
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 只给形状不给内容：上游原文里有 cellNum / userName / 余额，异常消息会进 warnings
                // 与 WARN 日志（后者恒落盘、可经 /api/debug-logs 取走）。
                throw MobileCarrierException(null, "$path HTTP ${response.code}（响应 ${text.length} 字节，内容不回显）")
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw MobileCarrierException(null, "$path 响应不是 JSON（${text.length} 字节，内容不回显）") }
            val encrypted = root["body"]?.jsonPrimitive?.contentOrNull
                ?: throw MobileCarrierException(null, "$path 响应缺少 body")
            val plain = runCatching { MobileCrypto.decryptResponse(encrypted) }
                .getOrElse { throw MobileCarrierException(null, "$path 响应解密失败（key 可能已轮换）") }
            runCatching { json.parseToJsonElement(plain).jsonObject }
                .getOrElse { throw MobileCarrierException(null, "$path 解密后不是 JSON（${plain.length} 字节，内容不回显）") }
        }
    }

    private fun requireOk(body: JsonObject, action: String) {
        val code = body.retCode()
        if (code != null && code !in OK_CODES) {
            val message = body.rspBody().get("retDesc")?.jsonPrimitive?.contentOrNull
                ?: body["retDesc"]?.jsonPrimitive?.contentOrNull
                ?: body["retMsg"]?.jsonPrimitive?.contentOrNull
            throw MobileCarrierException(code, "${action}失败（${code ?: "?"}）：${message ?: "未返回原因"}")
        }
    }

    private fun JsonObject.rspBody(): JsonObject =
        this["rspBody"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.retCode(): String? =
        this["retCode"]?.jsonPrimitive?.contentOrNull

    private fun digits(n: Int): String = buildString {
        repeat(n) { append('0' + random.nextInt(10)) }
    }

    private fun alnum(n: Int): String {
        val pool = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString { repeat(n) { append(pool[random.nextInt(pool.length)]) } }
    }

    companion object {
        private val OK_CODES = setOf("000000", "0", "SUCCESS", "success")
        private const val HOST = "https://h.app.coc.10086.cn"
        private const val PATH_SEND = "/biz-orange/LH/uamrandcodeH5/sendMsgLoginH5"
        private const val PATH_LOGIN = "/biz-orange/LH/uamrandcodeloginH5/loginH5"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        val ENDPOINTS: LinkedHashMap<String, String> = linkedMapOf(
            "app-plan" to "/biz-orange/BH/newPlanRemainQry/getNewPlanRemainQry",
            "app-fee" to "/biz-orange/BN/realFeeQuery/getRealFee",
            "app-user" to "/biz-orange/BN/userInformationService/getUserInformation",
            "app-planq" to "/biz-orange/BN/userPlanQry/getUserPlanQryNew",
            "app-bills" to "/biz-orange/BH/newBillsQry/getNewBills",
            "app-points" to "/biz-orange/BN/scoreQueryService/getScoreQuery",
            "app-flow" to "/biz-orange/BH/userFlowInfoQry/getFlowInfoQry",
            "app-accout" to "/biz-orange/BH/accoutQry/getAccoutInfo",
        )

        // 复用进程级连接池：见 CarrierHttp 的说明（原先每次调用都新建一个 OkHttpClient）
        private fun defaultClient(): OkHttpClient = CarrierHttp.mobile
    }
}

/** 可预期的上游失败：验证码错、会话过期、签名被拒。 */
class MobileCarrierException(
    val retCode: String?,
    message: String,
) : IOException(message)
