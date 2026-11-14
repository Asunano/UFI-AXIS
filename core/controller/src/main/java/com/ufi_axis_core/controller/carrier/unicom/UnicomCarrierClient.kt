package com.ufi_axis_core.controller.carrier.unicom

import com.ufi_axis_core.controller.carrier.CarrierHttp
import com.ufi_axis_core.controller.carrier.CarrierCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 中国联通扫码链路（2026-10-31，从 probe 的 `unicom_qr.py` + `UnicomProbe.web_exchange/fetch_web` 移植）。
 *
 * 流程：
 * ```
 * genqr (PNG + unisecid) → qrcode_hbt 轮询 (00/10/11) → qrcode_logon (piw)
 *   → wologin/redi → mall/checklogin (换商城会话) → mxx 余量/余额
 * ```
 *
 * 坑：
 * - 心跳/换票必须带 `callback`、`_` 和 `X-Requested-With: XMLHttpRequest`；
 * - `piw` 要手工写成 `.10010.com` Cookie；
 * - 扫码只拿 web SSO，必须换商城会话，否则 mxx 返回 `999999`；
 * - 会话约 90 分钟硬过期（probe 实测）；服务密码登录的 token_online 可以续期，
 *   纯扫码会话不能静默续期。
 */
class UnicomCarrierClient(
    http: OkHttpClient? = null,
    val cookieJar: CarrierCookieJar = CarrierCookieJar(),
) {
    /** 同移动：`cookieJar` 必须挂到 OkHttp，否则二维码换票后一个 Cookie 都留不下。 */
    private val http: OkHttpClient = (http ?: defaultClient())
        .newBuilder()
        .cookieJar(cookieJar)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 服务密码登录拿到的 `token_online` 与当次 `appId`。
     *
     * 这两个值只用于**换 Cookie**（`onLine.htm`）与后续续期；登录成功后由
     * `CarrierBillService` 存进账号记录，下次会话过期可以直接换票，不必再问密码。
     */
    private var tokenOnline: String = ""
    private var loginAppId: String = ""

    fun restoreToken(token: String, appId: String) {
        tokenOnline = token
        loginAppId = appId
    }

    fun tokenOnline(): String = tokenOnline

    fun appId(): String = loginAppId

    /**
     * 服务密码登录（2026-11-07，从 probe 的 `unicom_api.py:login_password` 移植）。
     *
     * ```
     * POST m.client.10010.com/mobileService/login.htm
     *   mobile   = RSA(手机号 + 6 位随机数字)
     *   password = RSA(服务密码 + 6 位随机数字)
     *   appId    = 159 位模板串（7 处随机数字）
     * → {code:"0", token_online:"…"} → POST onLine.htm 换 Cookie
     * ```
     *
     * 与扫码链路的区别：**不经过 uac/mall**，拿到的就是 App 域会话，
     * 查询要打 `m.client.10010.com`（见 [fetch]），不需要商城票。
     */
    suspend fun loginWithPassword(phone: String, password: String): String = withContext(Dispatchers.IO) {
        val app = UnicomLoginCrypto.appId()
        val form = FormBody.Builder()
            .add("version", VERSION)
            .add("mobile", UnicomLoginCrypto.encrypt(phone))
            .add("reqtime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .add("deviceModel", "Android")
            .add("netWay", "Wifi")
            .add("isR4", "0")
            .add("password", UnicomLoginCrypto.encrypt(password))
            .add("appId", app)
            .build()
        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(form)
            .header("User-Agent", UA_APP)
            .header("Referer", "$BASE_URL/")
            .build()
        val text = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnicomCarrierException("登录 HTTP ${response.code}")
            body
        }
        val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw UnicomCarrierException("登录响应不是 JSON（${text.length} 字节，内容不回显）")
        }
        val code = payload["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code != "0" && code != "0000") {
            val desc = payload["dsc"]?.jsonPrimitive?.contentOrNull
                ?: payload["desc"]?.jsonPrimitive?.contentOrNull
                ?: ""
            // code=2 有双义：描述里出现「短信」= 账号被强制短信登录（不是密码错）。
            if (code == "2" && desc.contains("短信")) {
                // 联通对 code=2 的文案有两种来源：账号被强制短信登录、或号码/密码不被接受后
                // 引导改用短信。两者对用户的下一步是一样的 —— 都只能去扫码。
                throw UnicomPasswordException(
                    "联通要求这个号码改用短信验证码登录（账号可能被风控或换了设备），请改用扫码登录",
                    forcedSms = true,
                )
            }
            throw UnicomPasswordException(
                when (code) {
                    "4102", "4103", "3102", "2" -> "服务密码错误或需要短信验证"
                    else -> "登录失败（code=$code${if (desc.isBlank()) "" else " $desc"}）"
                }
            )
        }
        tokenOnline = payload["token_online"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (tokenOnline.isBlank()) throw UnicomCarrierException("登录成功但没有返回 token_online")
        loginAppId = app
        renew()
        phone
    }

    /**
     * 用 `token_online` 换一次 Cookie（`onLine.htm`）。
     *
     * 服务密码登录成功后必须走这一步：`login.htm` 只回 token，业务接口认的是 Cookie。
     */
    suspend fun renew(): Boolean = withContext(Dispatchers.IO) {
        if (tokenOnline.isBlank()) return@withContext false
        // 必须复用登录时的 appId。token_online 在联通侧和设备指纹/渠道绑定，
        // 每次 renew 换一个新的 159 位 appId，会把“续期”变成一次陌生设备登录，
        // 表现就是 cookie 很快失效、用户频繁回到扫码页。
        val app = loginAppId.ifBlank {
            UnicomLoginCrypto.appId().also { loginAppId = it }
        }
        val form = FormBody.Builder()
            .add("token_online", tokenOnline)
            .add("appId", app)
            .add("version", VERSION)
            .add("deviceModel", "Android")
            .build()
        val request = Request.Builder()
            .url(ONLINE_URL)
            .post(form)
            .header("User-Agent", UA_APP)
            .header("Referer", "$BASE_URL/")
            .build()
        val text = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnicomCarrierException("换 Cookie HTTP ${response.code}")
            body
        }
        val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = payload?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code.isNotBlank() && code != "0") {
            throw UnicomPasswordException("会话换票失败（code=$code），请重新登录")
        }
        payload?.get("token_online")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { tokenOnline = it }
        loginAppId = app
        cookieJar.snapshot().isNotEmpty()
    }

    /** 拉二维码 PNG；同时把 `unisecid` 存进 CookieJar。 */
    suspend fun startQr(): ByteArray = withContext(Dispatchers.IO) {
        val url = "https://uac.10010.com/oauth2/genqr?timestamp=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://uac.10010.com/portal/homeLogin")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UnicomCarrierException("二维码拉取失败 HTTP ${response.code}")
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: throw UnicomCarrierException("二维码为空")
        }
    }

    /** 轮询心跳；返回 resultcode（00 等待 / 10 已扫 / 11 已授权 / 02 超时 / -1 失败 / 14 需先登录 App）。 */
    suspend fun pollQr(): String = withContext(Dispatchers.IO) {
        val sec = cookieJar.value("unisecid")
            ?: throw UnicomCarrierException("没有 unisecid Cookie，请重新拉二维码")
        val cb = "jQuery${System.currentTimeMillis()}_${(1000..9999).random()}"
        val url = "https://uac.10010.com/qrcode/qrcode_hbt?callback=$cb&secsnid=$sec&_=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://uac.10010.com/portal/homeLogin")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnicomCarrierException("心跳 HTTP ${response.code}")
            parseJsonp(text)?.get("resultcode")?.takeIf { it.isNotBlank() }
                ?: throw UnicomCarrierException("心跳返回无法解析（${text.length} 字节，内容不回显）")
        }
    }

    /** 已授权后换票并落地会话，再换商城会话。 */
    suspend fun completeLogin(): String? = withContext(Dispatchers.IO) {
        val sec = cookieJar.value("unisecid")
            ?: throw UnicomCarrierException("没有 unisecid Cookie")
        val cb = "jQuery${System.currentTimeMillis()}_${(1000..9999).random()}"
        val logonUrl = "https://uac.10010.com/portal/qrcode/qrcode_logon?callback=$cb&secsnid=$sec&_=${System.currentTimeMillis()}"
        val logon = Request.Builder()
            .url(logonUrl)
            .header("User-Agent", UA)
            .header("Referer", "https://uac.10010.com/portal/homeLogin")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val data = http.newCall(logon).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnicomCarrierException("换票 HTTP ${response.code}")
            parseJsonp(text) ?: throw UnicomCarrierException("换票返回无法解析（${text.length} 字节，内容不回显）")
        }
        if (data["resultcode"] != "01") throw UnicomCarrierException("换票失败：${data["resultcode"]}")
        android.util.Log.w("UnicomCarrier", "qrcode_logon ok, piw=${!data["piw"].isNullOrBlank()}")
        data["piw"]?.takeIf { it.isNotBlank() }?.let { cookieJar.putRaw("piw", it, ".10010.com") }

        // 落地会话（会写 JUT / _uop_id 等 Cookie）
        val redi = Request.Builder()
            .url("https://uac.10010.com/wologin/redi?redirectURL=https://m.client.10010.com/")
            .header("User-Agent", UA)
            .build()
        http.newCall(redi).execute().use { response ->
            if (!response.isSuccessful) throw UnicomCarrierException("落地会话 HTTP ${response.code}")
            android.util.Log.w("UnicomCarrier", "wologin/redi HTTP ${response.code}; JUT=${cookieJar.value("JUT") != null}, uop=${cookieJar.value("_uop_id") != null}")
        }
        exchangeMall()
    }

    /** `piw/JUT` → 商城会话；不换会 mxx 999999。 */
    suspend fun exchangeMall(): String? = withContext(Dispatchers.IO) {
        val jut = cookieJar.value("JUT")
            ?: throw UnicomCarrierException("没有 JUT Cookie，先完成扫码")
        val uop = cookieJar.value("_uop_id").orEmpty()
        val url = "https://www.10010.com/mall/service/check/checklogin?=${System.currentTimeMillis()}"
        val body = FormBody.Builder()
            .add("_uop_id", uop)
            .add("jutThird", jut)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", UA)
            .header("Origin", "https://imgxx.client.10010.com")
            .header("Referer", "https://imgxx.client.10010.com/")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnicomCarrierException("商城换会话 HTTP ${response.code}")
            val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw UnicomCarrierException("商城换会话返回不是 JSON（${text.length} 字节，内容不回显）")
            val rawLogin = payload["isLogin"]
            // 联通这个字段在不同省份/网关下会出现 boolean true、字符串 "true"、数字 1 三种形态。
            // 直接按 toString 归一化，别依赖 booleanOrNull 的类型推断。
            val loginText = rawLogin?.toString()?.trim('"')?.lowercase()
            val login = loginText == "true" || loginText == "1"
            android.util.Log.w(
                "UnicomCarrier",
                "mall checklogin HTTP ${response.code}, isLogin=$rawLogin, parsed=$login (${rawLogin?.javaClass?.simpleName})"
            )
            if (!login) throw UnicomCarrierException("商城会话换取失败（${text.length} 字节，内容不回显）")
            (payload["userInfo"] as? JsonObject)
                ?.get("usernumber")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 取余量与余额。会话失效抛 [UnicomAuthRequiredException]。
     *
     * 两条链路：
     * - **扫码登录**：有 `JUT` → 换商城票 → 打 `mxx`；
     * - **服务密码登录**：没有 JUT/商城票，直接打 `m.client`（App 域），
     *   这也是官方 App 自己的查询通道。
     */
    suspend fun fetch(): Pair<JsonObject, JsonObject> = withContext(Dispatchers.IO) {
        if (cookieJar.value("unicomMallUid") == null && cookieJar.value("JUT") != null) {
            // 扫码链路补齐商城票；失败就退回 App 域，而不是直接判"需要重新登录"
            runCatching { exchangeMall() }
        }
        if (cookieJar.value("unicomMallUid") == null) {
            return@withContext fetchAppDomain()
        }
        val packageBody = FormBody.Builder()
            .add("duanlianjieabc", "")
            .add("channelCode", "")
            .add("serviceType", "")
            .add("saleChannel", "")
            .add("externalSources", "")
            .add("contactCode", "")
            .add("ticket", "")
            .add("ticketPhone", "")
            .add("ticketChannel", "")
            .add("version", "WT")
            .add("userNumber", "")
            .add("language", "chinese")
            .build()
        val pkg = postMxx(PACKAGE_URL, packageBody)
        val balance = postMxx(BALANCE_URL, FormBody.Builder().build())
        pkg to balance
    }

    private fun postMxx(url: String, body: FormBody): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", UA_APP)
            .header("Origin", "https://imgxx.client.10010.com")
            .header("Referer", "https://imgxx.client.10010.com/")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (text == "999998" || text == "999999") throw UnicomAuthRequiredException()
            if (!response.isSuccessful) throw UnicomCarrierException("mxx HTTP ${response.code}")
            return runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw UnicomCarrierException("mxx 返回不是 JSON（${text.length} 字节，内容不回显）")
            }
        }
    }

    /** App 域（`m.client.10010.com`）查询：服务密码登录后的通道。 */
    private suspend fun fetchAppDomain(): Pair<JsonObject, JsonObject> = withContext(Dispatchers.IO) {
        val packageBody = FormBody.Builder()
            .add("duanlianjieabc", "")
            .add("channelCode", "")
            .add("serviceType", "")
            .add("saleChannel", "")
            .add("externalSources", "")
            .add("contactCode", "")
            .add("ticket", "")
            .add("ticketPhone", "")
            .add("ticketChannel", "")
            .add("version", "WT")
            .add("userNumber", "")
            .add("language", "chinese")
            .build()
        val pkg = postApp(PACKAGE_APP_URL, packageBody)
        val balance = postApp(BALANCE_APP_URL, FormBody.Builder().build())
        pkg to balance
    }

    private fun postApp(url: String, body: FormBody): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", UA_APP)
            .header("Referer", "$BASE_URL/")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (text == "999998" || text == "999999") throw UnicomAuthRequiredException()
            if (!response.isSuccessful) throw UnicomCarrierException("联通 HTTP ${response.code}")
            return runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw UnicomCarrierException("联通返回不是 JSON（${text.length} 字节，内容不回显）")
            }
        }
    }

    private fun parseJsonp(text: String): Map<String, String>? {
        val m = Regex("""\((\{.*\})\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL).find(text.trim()) ?: return null
        val obj = runCatching { json.parseToJsonElement(m.groupValues[1]).jsonObject }.getOrNull() ?: return null
        return obj.mapValues { (_, v) -> v.toString().trim('"') }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val UA_APP = "unicom{version:android@11.0900}"

        private const val BASE_URL = "https://m.client.10010.com"
        private const val LOGIN_URL = "$BASE_URL/mobileService/login.htm"
        private const val ONLINE_URL = "$BASE_URL/mobileService/onLine.htm"
        private const val PACKAGE_APP_URL =
            "$BASE_URL/servicequerybusiness/operationservice/queryOcsPackageFlowLeftContentRevisedInJune"
        private const val BALANCE_APP_URL =
            "$BASE_URL/servicequerybusiness/balancenew/accountBalancenew.htm"
        private const val VERSION = "android@11.0900"

        private const val PACKAGE_URL =
            "https://mxx.client.10010.com/servicequerybusiness/operationservice/queryOcsPackageFlowLeftContentRevisedInJune"
        private const val BALANCE_URL =
            "https://mxx.client.10010.com/servicequerybusiness/balancenew/accountBalancenew.htm"

        private fun defaultClient(): OkHttpClient = CarrierHttp.unicom
    }
}

class UnicomCarrierException(message: String) : IOException(message)

/** 会话失效（999998/999999 / 没 JUT）：上层应清 Cookie 并回到扫码态。 */
class UnicomAuthRequiredException : IOException("联通会话已失效，请重新扫码")

/** 服务密码登录被拒（密码错 / 账号被强制短信）。`message` 直接给用户看。 */
class UnicomPasswordException(
    message: String,
    val forcedSms: Boolean = false,
) : IOException(message)
