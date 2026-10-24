package com.ufi_axis.viewmodel.module

import com.ufi_axis.viewmodel.state.PhoneDownloadHistoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 手机下载历史的反序列化（2026-10-24 从 `FileManagerModule` 抽出来，便于单测）。
 *
 * ## 为什么单独成文件
 * 原实现内联在 `loadPhoneDownloadHistory()` 里，漏读 `source` 的回归没有任何测试能发现：
 * 记录写进 JSON 是带 `source` 的，读回来却被构造成空串，随后被"只保留 audio"的过滤
 * 全部丢掉并回写空数组 —— 用户看到的就是"下载历史重启即清空"。
 *
 * 纯 JVM 函数：不碰 Context / SharedPreferences，测试直接喂 JSON 文本即可。
 */
internal fun decodePhoneDownloadHistory(jsonStr: String): List<PhoneDownloadHistoryItem> =
    Json.parseToJsonElement(jsonStr).jsonArray.map { element ->
        val obj = element.jsonObject
        PhoneDownloadHistoryItem(
            fileName = obj.string("fileName"),
            fileSize = obj.long("fileSize"),
            sourcePath = obj.string("sourcePath"),
            downloadedAt = obj.long("downloadedAt"),
            status = obj.string("status").ifBlank { "completed" },
            // ★ 必须读回来。漏了这一行，下面"只保留 audio"的过滤会把**所有**记录丢掉。
            // 旧版本记录没有这个字段 → 空串，仍按原有设计被过滤掉（见 loadPhoneDownloadHistory）。
            source = obj.string("source")
        )
    }

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

private fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
