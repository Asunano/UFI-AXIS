package com.ufi_axis.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 国家/地区检测器（2026-08-10：前端更新源自动切换镜像的依据）。
 *
 * 依次尝试 3 个地理源（每个超时 8s），返回 ISO 3166-1 alpha-2 国家码
 * （如 "CN"/"US"），全部失败返回 null：
 *  1. `https://api.ip.sb/geoip`        JSON，取 `country_code`（主源，== "CN" 即中国大陆）
 *  2. `https://my.ippure.com/v1/info`  JSON，取 `countryCode`
 *  3. `https://ping0.cc/geo`           纯文本多行，第 2 行开头的中文国家名 → 映射表
 *
 * 本对象无状态；检测结果由调用方（ToolsModule / 设置页）持久化到
 * [AppPreferences.lastCountry] 缓存，避免每次检查更新都触发外网地理请求。
 */
object GeoDetector {

    private const val TIMEOUT_SECONDS = 8L
    private const val USER_AGENT = "UFI-AXIS-App/1.0"

    private val geoClient: OkHttpClient by lazy {
        OkHttpClientProvider.shared.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** ping0.cc 第 2 行中文国家名 → 国家码映射表（未命中返回 null） */
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

    /**
     * 检测当前网络出口国家/地区代码。
     * 依次尝试 3 个地理源，任一成功立即返回；全部失败返回 null。
     * 阻塞网络 IO 已切到 [Dispatchers.IO]。
     */
    suspend fun detectCountry(): String? = withContext(Dispatchers.IO) {
        // ① 主源：api.ip.sb/geoip → country_code（失败则继续尝试备用源）
        detectFromJson("https://api.ip.sb/geoip", "country_code")
            // ② 备用 1：my.ippure.com/v1/info → countryCode
            ?: detectFromJson("https://my.ippure.com/v1/info", "countryCode")
            // ③ 备用 2：ping0.cc/geo → 纯文本，第 2 行中文国家名映射
            ?: detectFromText()
    }

    /** 从 JSON 地理源中取国家码字段（源不可达/字段缺失返回 null） */
    private fun detectFromJson(url: String, field: String): String? {
        val json = fetchText(url) ?: return null
        return try {
            JSONObject(json).optString(field, "").trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 从 ping0.cc 纯文本第 2 行中文国家名映射国家码（解析失败返回 null） */
    private fun detectFromText(): String? {
        val text = fetchText("https://ping0.cc/geo") ?: return null
        val nonEmptyLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (nonEmptyLines.size < 2) return null
        val line2 = nonEmptyLines[1]
        for ((name, code) in COUNTRY_NAME_MAP) {
            if (line2.startsWith(name)) return code
        }
        return null
    }

    /** GET 并返回响应体文本（失败返回 null；不抛异常） */
    private fun fetchText(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            geoClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }
}
