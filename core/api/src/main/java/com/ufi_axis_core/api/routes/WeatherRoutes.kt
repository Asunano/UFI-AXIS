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
import java.net.URLEncoder

/**
 * 天气（`/api/weather`，2026-09-17）：上游是 Open-Meteo 的免费接口，**无需 API key**。
 *
 * ## 为什么放在 core 而不是 app 直连
 * 1. app 连的是本机热点，出网这一跳本来就要经过设备；由 core 统一出网，app 侧只有一条
 *    「找 core 要数据」的路径，跟其余所有数据同构。
 * 2. Open-Meteo 免费额度按调用数计，多端（app / web 面板）各自直连等于成倍消耗；
 *    走 core 就只有一份缓存、一份配额。
 * 3. 位置是**设备级配置**（这台随身 WiFi 在哪），不是某个手机的本地偏好，所以存在
 *    [AppSettings] 里、由两端共享。
 *
 * ## 端点
 * - `GET  /api/weather` —— 当前天气 + 今日最高/最低。默认用已保存的坐标，
 *   也可用 `?lat=&lon=` 临时覆盖（城市搜索结果的预览用得上）。
 * - `GET  /api/weather/search?name=` —— 城市搜索（Open-Meteo Geocoding 代理）。
 * - `GET  /api/weather/config` / `PUT /api/weather/config` —— 开关、城市名、坐标、单位。
 *
 * ## 上游节流
 * `/api` 下没有限流，对上游的唯一保护就是 TTL 缓存（[CacheTTL.WEATHER_NOW]）。
 * 缓存 key 带坐标与单位，切城市不会读到上一个城市的值。
 */
class WeatherRoutes(
    private val settings: AppSettings,
    private val cache: ResponseCache
) {

    fun register(route: Route) {
        route.route("/weather") {

            get {
                val cfg = read(settings)
                // 坐标优先取 query（城市搜索结果的即时预览），其次取已保存配置。
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull() ?: cfg.latitude
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull() ?: cfg.longitude
                val city = call.request.queryParameters["city"]?.takeIf { it.isNotBlank() } ?: cfg.city

                if (!validCoord(lat, lon)) {
                    // 没配过位置不是错误，是"还没设置"。回 200 + configured=false，
                    // 客户端据此显示「去设置城市」而不是一个红色报错。
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "configured" to false,
                                "enabled" to cfg.enabled,
                                "message" to "尚未设置城市"
                            )
                        )
                    )
                    return@get
                }

                val unit = if (cfg.unit == UNIT_FAHRENHEIT) UNIT_FAHRENHEIT else UNIT_CELSIUS
                val key = "weather:now:%.3f,%.3f:%s".format(lat, lon, unit)
                val data = try {
                    cache.getOrPut(key, CacheTTL.WEATHER_NOW) {
                        val raw = withContext(Dispatchers.IO) { fetchUrl(forecastUrl(lat, lon, unit)) }
                            ?: throw IllegalStateException("上游无响应")
                        parseForecast(raw, city, unit)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "天气获取失败: ${e.message}")
                    call.respondFail(
                        HttpStatusCode.BadGateway, ErrorCode.OPERATION_FAILED,
                        "天气数据获取失败：${e.message}"
                    )
                    return@get
                }
                call.respond(data)
            }

            /**
             * 城市搜索。结果缓存 1 天：同一个搜索词的经纬度不会变，而用户改城市时
             * 往往会来回搜同几个名字。
             */
            get("/search") {
                val name = call.request.queryParameters["name"]?.trim().orEmpty()
                if (name.length < MIN_QUERY_LEN) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "搜索词至少 $MIN_QUERY_LEN 个字符"
                    )
                    return@get
                }
                val lang = call.request.queryParameters["lang"]?.takeIf { it.isNotBlank() } ?: "zh"
                val key = "weather:geo:$lang:${name.lowercase()}"
                val data = try {
                    cache.getOrPut(key, CacheTTL.WEATHER_GEOCODE) {
                        val raw = withContext(Dispatchers.IO) { fetchUrl(geocodeUrl(name, lang)) }
                            ?: throw IllegalStateException("上游无响应")
                        parseGeocode(raw)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "城市搜索失败: ${e.message}")
                    call.respondFail(
                        HttpStatusCode.BadGateway, ErrorCode.OPERATION_FAILED,
                        "城市搜索失败：${e.message}"
                    )
                    return@get
                }
                call.respond(data)
            }

            /** 未写入过或 JSON 损坏时回落默认值（不报错）。 */
            get("/config") {
                call.respond(
                    Json.parseToJsonElement(
                        ConfigJson.encodeToString(WeatherConfig.serializer(), read(settings))
                    )
                )
            }

            /** 字段级合并；body 是上述字段的任意子集。与 `PUT /api/notifications/config` 同语义。 */
            put("/config") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    merge(read(settings), patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "天气配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                validate(merged)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@put
                }
                settings.weatherConfigJson =
                    ConfigJson.encodeToString(WeatherConfig.serializer(), merged)
                // 换了城市/单位后旧坐标的缓存留着没意义，顺手清掉（也让 WS 推一次 data_changed）
                cache.invalidate("weather:now:*")
                call.respond(toJsonElement(mapOf("success" to true, "config" to merged)))
            }
        }
    }

    companion object {
        private const val TAG = "WeatherRoutes"

        const val UNIT_CELSIUS = "celsius"
        const val UNIT_FAHRENHEIT = "fahrenheit"

        private const val MIN_QUERY_LEN = 1
        private const val TIMEOUT_MS = 8_000
        private const val MAX_GEOCODE_RESULTS = 10
        private const val HOURLY_POINTS = 24

        /**
         * 上游域名写死在代码里，**不做成用户可填的 URL**：可填 URL 等于把 core 变成任意
         * 外网请求的代理（SSRF）。同理只允许 https —— core 的 networkSecurityConfig 只给
         * 127.0.0.1 放行明文。
         */
        private const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"

        /** 上游返回体宽松解析：第三方随时会加字段。 */
        private val UpstreamJson = Json { isLenient = true; ignoreUnknownKeys = true }

        fun read(settings: AppSettings): WeatherConfig {
            val raw = settings.weatherConfigJson ?: return WeatherConfig()
            return try {
                ConfigJson.decodeFromString(WeatherConfig.serializer(), raw)
            } catch (e: Exception) {
                WeatherConfig()
            }
        }

        internal fun merge(current: WeatherConfig, patch: JsonObject): WeatherConfig {
            val effective = patch.filterValues { it !is JsonNull }
            if (effective.isEmpty()) return current
            val base = ConfigJson.encodeToJsonElement(WeatherConfig.serializer(), current).jsonObject
            return ConfigJson.decodeFromJsonElement(
                WeatherConfig.serializer(),
                JsonObject(base + effective)
            )
        }

        /** 返回第一条约束违规说明；全部合法返回 null。 */
        internal fun validate(c: WeatherConfig): String? = when {
            c.latitude !in -90.0..90.0 -> "纬度（latitude）必须在 -90..90 之间，收到 ${c.latitude}"
            c.longitude !in -180.0..180.0 -> "经度（longitude）必须在 -180..180 之间，收到 ${c.longitude}"
            c.unit != UNIT_CELSIUS && c.unit != UNIT_FAHRENHEIT ->
                "温度单位（unit）只能是 $UNIT_CELSIUS 或 $UNIT_FAHRENHEIT，收到 ${c.unit}"
            c.city.length > 64 -> "城市名（city）过长"
            else -> null
        }

        /**
         * `0,0` 视为"没配过"：那是几内亚湾的公海，不会是任何人的所在地，
         * 用它当哨兵值省掉一个 `configured` 布尔字段的双真源问题。
         */
        internal fun validCoord(lat: Double, lon: Double): Boolean =
            (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0

        internal fun forecastUrl(lat: Double, lon: Double, unit: String): String = buildString {
            append(FORECAST_BASE)
            append("?latitude=").append("%.4f".format(lat))
            append("&longitude=").append("%.4f".format(lon))
            append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,")
            append("is_day,precipitation,weather_code,wind_speed_10m")
            append("&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset")
            // 2026-09-19：24 小时温度曲线。forecast_hours=24 从"当前小时"起算，
            // 所以 hourly 数组的 0 号元素就是现在这一小时，客户端不需要再按时间戳找当前位置。
            append("&hourly=temperature_2m&forecast_hours=").append(HOURLY_POINTS)
            append("&timezone=auto&forecast_days=1")
            append("&temperature_unit=").append(unit)
        }

        internal fun geocodeUrl(name: String, lang: String): String = buildString {
            append(GEOCODE_BASE)
            append("?name=").append(URLEncoder.encode(name, "UTF-8"))
            append("&count=").append(MAX_GEOCODE_RESULTS)
            append("&language=").append(URLEncoder.encode(lang, "UTF-8"))
            append("&format=json")
        }

        /**
         * 把上游返回体压成客户端要的扁平结构。
         *
         * 刻意在 core 侧做完这一步（含 weather_code → 中文描述），而不是把原始体透传给
         * app / web 各自翻译：那样两端的文案与图标映射一定会分叉。
         */
        internal fun parseForecast(raw: String, city: String, unit: String): JsonElement {
            val root = UpstreamJson.parseToJsonElement(raw).jsonObject
            root["error"]?.jsonPrimitive?.booleanOrNull?.let { isError ->
                if (isError) {
                    val reason = root["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    throw IllegalStateException(reason)
                }
            }
            val cur = root["current"]?.jsonObject ?: throw IllegalStateException("上游缺少 current 字段")
            val daily = root["daily"]?.jsonObject
            val hourly = root["hourly"]?.jsonObject
            val code = cur["weather_code"]?.jsonPrimitive?.intOrNull ?: -1
            val isDay = (cur["is_day"]?.jsonPrimitive?.intOrNull ?: 1) == 1

            return toJsonElement(
                mapOf(
                    "configured" to true,
                    "city" to city,
                    "temperature" to (cur["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                    "apparent_temperature" to
                        (cur["apparent_temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                    "humidity" to (cur["relative_humidity_2m"]?.jsonPrimitive?.intOrNull ?: 0),
                    "precipitation" to (cur["precipitation"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                    "wind_speed" to (cur["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                    "weather_code" to code,
                    "description" to describe(code),
                    "is_day" to isDay,
                    "temp_max" to firstDouble(daily, "temperature_2m_max"),
                    "temp_min" to firstDouble(daily, "temperature_2m_min"),
                    "sunrise" to firstString(daily, "sunrise"),
                    "sunset" to firstString(daily, "sunset"),
                    // 24 小时温度曲线：两个等长数组，index 0 = 当前小时。
                    // 拆成两个扁平数组而不是 [{time,temp}] 对象数组：序列化体积更小，
                    // 客户端画折线时也是分别拿 y 值与 x 轴标签，不需要成对遍历。
                    "hourly_times" to stringArray(hourly, "time"),
                    "hourly_temperatures" to doubleArray(hourly, "temperature_2m"),
                    "unit" to unit,
                    "timezone" to (root["timezone"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "updated_at" to System.currentTimeMillis()
                )
            )
        }

        internal fun parseGeocode(raw: String): JsonElement {
            val root = UpstreamJson.parseToJsonElement(raw).jsonObject
            val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
            val list = results.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                mapOf(
                    "name" to (o["name"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "latitude" to lat,
                    "longitude" to lon,
                    "country" to (o["country"]?.jsonPrimitive?.contentOrNull ?: ""),
                    // admin1 = 省/州，用于区分同名城市（比如两个"朝阳"）
                    "admin1" to (o["admin1"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "timezone" to (o["timezone"]?.jsonPrimitive?.contentOrNull ?: "")
                )
            }
            return toJsonElement(mapOf("results" to list, "total" to list.size))
        }

        /** WMO 天气代码（WW）→ 中文描述。表见 Open-Meteo 文档的 "WMO Weather interpretation codes"。 */
        internal fun describe(code: Int): String = when (code) {
            0 -> "晴"
            1 -> "晴间多云"
            2 -> "多云"
            3 -> "阴"
            45 -> "有雾"
            48 -> "雾凇"
            51 -> "小毛毛雨"
            53 -> "毛毛雨"
            55 -> "大毛毛雨"
            56, 57 -> "冻雨（毛毛雨）"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75 -> "大雪"
            77 -> "雪粒"
            80 -> "小阵雨"
            81 -> "阵雨"
            82 -> "强阵雨"
            85 -> "小阵雪"
            86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷阵雨伴冰雹"
            else -> "未知"
        }

        private fun firstDouble(daily: JsonObject?, field: String): Double =
            daily?.get(field)?.jsonArray?.firstOrNull()?.jsonPrimitive?.doubleOrNull ?: 0.0

        private fun firstString(daily: JsonObject?, field: String): String =
            daily?.get(field)?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: ""

        private fun doubleArray(obj: JsonObject?, field: String): List<Double> =
            obj?.get(field)?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?: emptyList()

        private fun stringArray(obj: JsonObject?, field: String): List<String> =
            obj?.get(field)?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

        /**
         * 拉一段文本。与 `UpdateManager.fetchUrl` 同型（core 侧没有 OkHttp，用 JDK 原生）。
         * 必须在 [Dispatchers.IO] 上调用：Netty worker 只有 1 个线程，阻塞它等于整机停摆。
         */
        private fun fetchUrl(urlStr: String): String? = try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            try {
                // 上游 400 也带 JSON 错误体（{"error":true,"reason":...}），要读出来当原因回传
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "fetchUrl 失败: ${e.message}")
            null
        }
    }
}

/**
 * 天气配置（设备级，两端共享）。
 *
 * `enabled` 只管「客户端要不要在标题栏显示天气」；即便关掉，接口本身仍可访问
 * （设置页要能在关闭状态下先搜城市、看一眼再打开）。
 */
@Serializable
data class WeatherConfig(
    val enabled: Boolean = false,
    /** 城市显示名（搜索结果里选中的那条，可能带省份后缀）。 */
    val city: String = "",
    /** `0,0` = 未设置，见 [WeatherRoutes.validCoord]。 */
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val unit: String = WeatherRoutes.UNIT_CELSIUS
)
