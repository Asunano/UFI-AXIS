package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.ConfigJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 今日诗词（`/api/poetry`，2026-09-18）。上游是 [今日诗词](https://www.jinrishici.com/doc/) 的
 * v2 接口，**无需注册、无需 API key**。
 *
 * ## 智能推荐是上游做的，不是我们做的
 * 它按**发起请求的 IP** 解析地区、抓当地实时天气、再结合北京时间与农历日期匹配标签
 * （气象：晴/雨/雪/寒冷/炎热…；时间：日出/正午/晚上/凌晨…；日期：春夏秋冬/节日…；
 * 地理：华南/江南/长安…），命中的标签在响应的 `matchTags` 里回传。
 *
 * 所以「夏天只出夏天的诗」这件事**不需要我们传 tag**（v2 也没有 tag 参数），
 * 而是必须保证请求从**设备**发出 —— 这也正好是把它放在 core 的理由：
 * app 直连时 IP 虽然也经设备出网，但地区解析、token 归属都会随客户端漂移。
 *
 * ## Token 的正确用法
 * 裸调 `one.json` 也能成功，但上游会**每次都新签一个 token**，而同一 IP 签出多个 token
 * 会让它认为是多个用户、拉低推荐质量。所以：
 * 1. 首次请求不带 token，从响应里**收割** `token` 字段存进 [AppSettings.poetryToken]（永久有效）；
 * 2. 之后每次请求都带 `X-User-Token` 头。
 *
 * 不需要单独调 `/token` 端点 —— `one.json` 的响应里就带。
 *
 * ## 缓存
 * 上游对每个 token **预生成并缓存推荐结果，约 10 分钟更新一次**（响应里的 `cacheAt`）。
 * 所以 TTL 取同一档（[CacheTTL.POETRY]）：更短只是重复拿到同一句、白打请求。
 */
class PoetryRoutes(
    private val settings: AppSettings,
    private val cache: ResponseCache
) {

    fun register(route: Route) {
        route.route("/poetry") {

            /** 当前推荐的一句诗。`?refresh=1` 绕过本地缓存（上游 10 分钟内仍可能是同一句）。 */
            get {
                val refresh = call.request.queryParameters["refresh"] == "1"
                if (refresh) cache.invalidate(CACHE_KEY)
                val data = try {
                    cache.getOrPut(CACHE_KEY, CacheTTL.POETRY) {
                        val raw = withContext(Dispatchers.IO) { fetchOne(settings.poetryToken) }
                            ?: throw IllegalStateException("上游无响应")
                        parseOne(raw, settings)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "诗词获取失败: ${e.message}")
                    call.respondFail(
                        HttpStatusCode.BadGateway, ErrorCode.OPERATION_FAILED,
                        "诗词获取失败：${e.message}"
                    )
                    return@get
                }
                call.respond(data)
            }

            get("/config") {
                call.respond(
                    Json.parseToJsonElement(
                        ConfigJson.encodeToString(PoetryConfig.serializer(), read(settings))
                    )
                )
            }

            /** 字段级合并，与 `PUT /api/weather/config` 同语义。 */
            put("/config") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    merge(read(settings), patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "诗词配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                settings.poetryConfigJson =
                    ConfigJson.encodeToString(PoetryConfig.serializer(), merged)
                call.respond(toJsonElement(mapOf("success" to true, "config" to merged)))
            }
        }
    }

    companion object {
        private const val TAG = "PoetryRoutes"

        private const val CACHE_KEY = "poetry:one"
        private const val TIMEOUT_MS = 8_000

        /** 上游域名写死、强制 https：理由同 WeatherRoutes（可配置 URL = 任意外网代理）。 */
        private const val ONE_URL = "https://v2.jinrishici.com/one.json"

        private val UpstreamJson = Json { isLenient = true; ignoreUnknownKeys = true }

        fun read(settings: AppSettings): PoetryConfig {
            val raw = settings.poetryConfigJson ?: return PoetryConfig()
            return try {
                ConfigJson.decodeFromString(PoetryConfig.serializer(), raw)
            } catch (e: Exception) {
                PoetryConfig()
            }
        }

        internal fun merge(current: PoetryConfig, patch: JsonObject): PoetryConfig {
            val effective = patch.filterValues { it !is JsonNull }
            if (effective.isEmpty()) return current
            val base = ConfigJson.encodeToJsonElement(PoetryConfig.serializer(), current).jsonObject
            return ConfigJson.decodeFromJsonElement(
                PoetryConfig.serializer(),
                JsonObject(base + effective)
            )
        }

        /**
         * 把上游返回体压成客户端要的扁平结构，并**顺手把 token 落盘**。
         *
         * 收割 token 放在这里而不是路由里：只有解析成功才说明这次调用是有效的，
         * 失败响应里的 token 不值得存。
         */
        internal fun parseOne(raw: String, settings: AppSettings): JsonElement {
            val root = UpstreamJson.parseToJsonElement(raw).jsonObject
            val status = root["status"]?.jsonPrimitive?.contentOrNull
            if (status != null && status != "success") {
                val reason = root["errMessage"]?.jsonPrimitive?.contentOrNull ?: status
                throw IllegalStateException(reason)
            }
            // 首次调用（未带 token）时上游会签发一个；存下来，之后都带着它请求
            root["token"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != settings.poetryToken }
                ?.let {
                    settings.poetryToken = it
                    AppLogger.i(TAG, "已保存今日诗词 token（同一设备固定一个，保证推荐质量）")
                }

            val data = root["data"]?.jsonObject ?: throw IllegalStateException("上游缺少 data 字段")
            val origin = data["origin"]?.jsonObject
            val tags = data["matchTags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            return toJsonElement(
                mapOf(
                    // 核心：推荐的那一句
                    "content" to (data["content"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "title" to (origin?.get("title")?.jsonPrimitive?.contentOrNull ?: ""),
                    "dynasty" to (origin?.get("dynasty")?.jsonPrimitive?.contentOrNull ?: ""),
                    "author" to (origin?.get("author")?.jsonPrimitive?.contentOrNull ?: ""),
                    // 全篇（可能多段），详情页展示
                    "full_content" to (
                        origin?.get("content")?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList()
                        ),
                    // 整诗翻译，部分诗词才有
                    "translate" to (
                        origin?.get("translate")?.let { el ->
                            (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        } ?: emptyList()
                        ),
                    // 上游据此推荐的标签（季节/天气/时辰/地理），前端可直接显示为"推荐理由"
                    "match_tags" to tags,
                    "popularity" to (data["popularity"]?.jsonPrimitive?.intOrNull ?: 0),
                    "updated_at" to System.currentTimeMillis()
                )
            )
        }

        /**
         * 拉一次 `one.json`。带 token 时必须放在 `X-User-Token` 头里（不是 query）。
         *
         * 必须在 [Dispatchers.IO] 上调用：Netty worker 只有 1 个线程。
         */
        private fun fetchOne(token: String?): String? = try {
            val conn = (URL(ONE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("X-User-Token", it) }
                instanceFollowRedirects = true
            }
            try {
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "fetchOne 失败: ${e.message}")
            null
        }
    }
}

/**
 * 今日诗词配置（设备级，两端共享）。
 *
 * 只有一个开关：要不要在客户端标题栏下方显示。没有"选标签"这类选项 ——
 * 标签是上游按地区/天气/时间自动匹配的，我们无从指定（见 [PoetryRoutes] 的说明）。
 */
@Serializable
data class PoetryConfig(
    val enabled: Boolean = false,
    /** 是否连出处一起显示（关闭时标题栏只显示诗句本身）。 */
    val show_origin: Boolean = true
)
