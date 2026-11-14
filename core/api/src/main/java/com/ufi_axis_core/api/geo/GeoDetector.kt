package com.ufi_axis_core.api.geo

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 出网国家/地区检测（2026-09-18 从 app 侧搬到 core）。
 *
 * ## 为什么在 core
 * 判据是**出网 IP 的归属**，而设备才是真正的出网点：app 走设备热点时出网 IP 与 core 相同，
 * 但检测放在 app 会让每个客户端各存一份结果、彼此可能不一致（换 WiFi、开代理都会让某一端
 * 测出别的答案）。放在 core 就只有一份真源，两端读同一个值。
 *
 * ## 三个源（顺序即优先级，任一成功立刻返回；全失败返回 null）
 * 1. `https://api.ip.sb/geoip`       —— JSON，取 `country_code`
 * 2. `https://my.ippure.com/v1/info` —— JSON，取 `countryCode`
 * 3. `https://ping0.cc/geo`          —— 纯文本多行，第 2 行开头的中文国家名 → [COUNTRY_NAME_MAP]
 *
 * 用 JDK 原生 [HttpURLConnection]（core 模块没有 OkHttp），与 `WeatherRoutes.fetchUrl` /
 * `PoetryRoutes.fetchOne` 同型：8s 超时 + 固定 User-Agent + 必须跑在 [Dispatchers.IO] 上。
 *
 * ## 结果存哪
 * [AppSettings.geoCountry] + [AppSettings.geoDetectedAt]。前者进备份白名单（换设备通常还在
 * 同一地区），后者不进 —— 那是"什么时候测的"，跟着备份走只会让新设备以为刚测过。
 */
object GeoDetector {

    private const val TAG = "GeoDetector"
    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT = "UFI-AXIS-Core/1.0"

    /**
     * 结果视为过期的阈值：7 天。
     *
     * 国家/地区几乎不变，所以正常情况下**一次检测管很久**；7 天只是"用户把设备带出国"
     * 这类罕见情况的兜底。刻意不做成可配置项：它不是用户需要理解的旋钮。
     */
    const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /** 上游返回体宽松解析：第三方随时会加字段。 */
    private val UpstreamJson = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * 串行化闸门：启动自动检测与 `POST /api/geo/detect` 可能同时发生，
     * 没有它就会并发打三个上游、并且两次写同一对 prefs 键（后写的赢，纯浪费）。
     */
    private val mutex = Mutex()

    /** ping0.cc 第 2 行中文国家名 → 国家码（未命中返回 null）。 */
    private val COUNTRY_NAME_MAP: Map<String, String> = mapOf(
        // 注意顺序：更长的「中国香港/中国澳门/中国台湾」必须排在「中国」之前，
        // 否则 startsWith("中国") 会先命中，导致 HK/MO/TW 误判为 CN
        "中国香港" to "HK",
        "中国澳门" to "MO",
        "中国台湾" to "TW",
        "中国" to "CN",
        "美国" to "US",
        "日本" to "JP",
        "韩国" to "KR",
        "德国" to "DE",
        "英国" to "GB",
        "法国" to "FR",
        "俄罗斯" to "RU",
        "新加坡" to "SG",
        "加拿大" to "CA",
        "澳大利亚" to "AU",
        "印度" to "IN",
        "意大利" to "IT",
        "西班牙" to "ES",
        "荷兰" to "NL",
        "瑞士" to "CH",
        "瑞典" to "SE",
        "芬兰" to "FI",
        "波兰" to "PL",
        "乌克兰" to "UA",
        "巴西" to "BR",
        "泰国" to "TH",
        "马来西亚" to "MY",
        "越南" to "VN",
        "印度尼西亚" to "ID",
        "菲律宾" to "PH",
        "土耳其" to "TR",
        "阿联酋" to "AE",
        "沙特阿拉伯" to "SA",
        "以色列" to "IL",
        "南非" to "ZA",
        "墨西哥" to "MX",
        "阿根廷" to "AR",
        "新西兰" to "NZ",
        "爱尔兰" to "IE",
        "奥地利" to "AT",
        "比利时" to "BE",
        "挪威" to "NO",
        "丹麦" to "DK",
        "葡萄牙" to "PT",
        "希腊" to "GR",
        "捷克" to "CZ",
        "匈牙利" to "HU",
        "罗马尼亚" to "RO",
        "埃及" to "EG"
    )

    /** 已保存的结果是否需要重测：没测过，或距上次检测超过 [STALE_AFTER_MS]。 */
    fun isStale(settings: AppSettings): Boolean {
        if (settings.geoCountry.isBlank()) return true
        val age = System.currentTimeMillis() - settings.geoDetectedAt
        // age < 0 = 系统时间被往前调过（设备没有 RTC 电池时很常见）：当成过期重测一次，
        // 否则那条记录会因为"未来的时间戳"永远不过期。
        return age < 0 || age >= STALE_AFTER_MS
    }

    /**
     * 按需检测：[isStale] 为 false 时**直接跳过、不出网**。
     *
     * @return 当前生效的国家码（跳过时即已保存的值），检测失败返回 null。
     */
    suspend fun refreshIfStale(settings: AppSettings): String? {
        if (!isStale(settings)) return settings.geoCountry.ifBlank { null }
        return detectAndSave(settings)
    }

    /**
     * 强制检测一次并落盘（`POST /api/geo/detect` 走这条）。失败返回 null 且**不动已保存的值** ——
     * 一次网络抖动不该把"上次测出来的 CN"擦成"未检测"。
     */
    suspend fun detectAndSave(settings: AppSettings): String? = mutex.withLock {
        val country = detectCountry()
        if (country == null) {
            AppLogger.w(TAG, "国家/地区检测失败：三个地理源都没给出结果（保留已有值）")
            return@withLock null
        }
        settings.geoCountry = country
        settings.geoDetectedAt = System.currentTimeMillis()
        AppLogger.i(TAG, "国家/地区检测结果：$country")
        country
    }

    /**
     * 检测当前出网国家/地区代码（ISO 3166-1 alpha-2，如 `CN`/`US`）。
     * 依次尝试三个源，任一成功立即返回；全部失败返回 null。
     */
    suspend fun detectCountry(): String? = withContext(Dispatchers.IO) {
        detectFromJson("https://api.ip.sb/geoip", "country_code")
            ?: detectFromJson("https://my.ippure.com/v1/info", "countryCode")
            ?: detectFromText()
    }

    /** 从 JSON 源取国家码字段（源不可达 / 字段缺失 / 不是 JSON 都返回 null）。 */
    private fun detectFromJson(url: String, field: String): String? {
        val raw = fetchUrl(url) ?: return null
        return try {
            UpstreamJson.parseToJsonElement(raw).jsonObject[field]
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "解析 $url 失败: ${e.message}")
            null
        }
    }

    /** 从 ping0.cc 纯文本第 2 行的中文国家名映射国家码（解析失败返回 null）。 */
    private fun detectFromText(): String? {
        val text = fetchUrl("https://ping0.cc/geo") ?: return null
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return null
        val line2 = lines[1]
        for ((name, code) in COUNTRY_NAME_MAP) {
            if (line2.startsWith(name)) return code
        }
        return null
    }

    /**
     * 拉一段文本（失败返回 null，不抛）。与 `WeatherRoutes.fetchUrl` 同型。
     *
     * 必须在 [Dispatchers.IO] 上调用：Netty worker 只有 1 个线程，阻塞它等于整机停摆。
     */
    private fun fetchUrl(urlStr: String): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream?.bufferedReader()?.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "fetchUrl 失败($urlStr): ${e.message}")
        null
    }
}
