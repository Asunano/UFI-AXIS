package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * BT Tracker 管理器
 *
 * 从远程源获取 Tracker 列表并缓存到本地，支持定时自动更新。
 * 缓存文件 bt-trackers.txt 在 aria2 启动时注入到 aria2.conf 的 bt-tracker 参数中，
 * 也可以在 aria2 运行中通过 changeGlobalOption RPC 热更新。
 *
 * 数据源格式: _aria2.txt 变体 — 逗号分隔的单行 URI 列表
 * 例: https://cf.trackerslist.com/best_aria2.txt
 */
class TrackerManager(
    private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "TrackerManager"
    }

    @Serializable
    data class TrackerMeta(
        val lastUpdated: Long = 0L,
        val sourceUrl: String = "",
        val trackerCount: Int = 0,
        val status: String = "idle"  // idle / updating / ok / error: ...
    )

    private val trackerDir = File(appContext.filesDir, "aria2").also { it.mkdirs() }
    private val trackerCacheFile = File(trackerDir, "bt-trackers.txt")
    private val metaFile = File(trackerDir, "tracker-meta.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var meta = TrackerMeta()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scheduledJob: Job? = null

    init {
        loadMeta()
    }

    /** 获取缓存的 Tracker 列表（逗号分隔字符串），null 表示无缓存 */
    fun getCachedTrackers(): String? {
        return if (trackerCacheFile.exists()) {
            trackerCacheFile.readText().trim().takeIf { it.isNotBlank() }
        } else null
    }

    /** 获取元信息 */
    fun getMeta(): TrackerMeta = meta

    /**
     * 启动定时自动更新
     * @param intervalHours 更新间隔（小时），0 或负数则不启动
     * @param sourceUrl Tracker 列表源 URL
     * @param customList 用户自定义附加 Tracker（逗号分隔）
     */
    fun startAutoUpdate(intervalHours: Int, sourceUrl: String, customList: String) {
        scheduledJob?.cancel()
        if (intervalHours <= 0) return

        // 首次检查：如果缓存过期或不存在，立即拉取
        scope.launch {
            if (isStale(intervalHours) || getCachedTrackers() == null) {
                refreshNow(sourceUrl, customList)
            }
        }

        // 定时周期拉取
        scheduledJob = scope.launch {
            while (isActive) {
                delay(intervalHours * 3600_000L)
                refreshNow(sourceUrl, customList)
            }
        }
        AppLogger.i(TAG, "Auto-update started: interval=${intervalHours}h, source=$sourceUrl")
    }

    /** 停止定时更新 */
    fun stopAutoUpdate() {
        scheduledJob?.cancel()
        scheduledJob = null
    }

    /**
     * 立即刷新 Tracker 列表
     * @return true 表示成功
     */
    suspend fun refreshNow(sourceUrl: String, customList: String): Boolean {
        meta = meta.copy(status = "updating")
        saveMeta()
        return try {
            val fetched = fetchTrackers(sourceUrl)
            if (fetched != null) {
                // 合并自定义 Tracker
                val combined = if (customList.isNotBlank()) {
                    // 去重合并
                    val existing = fetched.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val custom = customList.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    (existing + custom).distinct().joinToString(",")
                } else fetched

                trackerCacheFile.writeText(combined)
                val count = combined.split(",").count { it.trim().isNotBlank() }
                meta = meta.copy(
                    lastUpdated = System.currentTimeMillis(),
                    sourceUrl = sourceUrl,
                    trackerCount = count,
                    status = "ok"
                )
                saveMeta()
                AppLogger.i(TAG, "Updated $count trackers from $sourceUrl")
                true
            } else {
                meta = meta.copy(status = "error: fetch returned empty")
                saveMeta()
                AppLogger.w(TAG, "Tracker fetch returned empty/null from $sourceUrl")
                false
            }
        } catch (e: Exception) {
            meta = meta.copy(status = "error: ${e.message}")
            saveMeta()
            AppLogger.w(TAG, "Tracker refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private fun fetchTrackers(url: String): String? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "UFI-AXIS-Download/1.0")
            conn.connect()
            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().readText().trim()
                conn.disconnect()
                // _aria2.txt 格式：逗号分隔单行
                // 也兼容换行分隔的格式（去除空行后用逗号拼接）
                if (text.contains("\n")) {
                    text.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && (it.startsWith("http") || it.startsWith("udp") || it.startsWith("ws")) }
                        .joinToString(",")
                        .takeIf { it.isNotBlank() }
                } else {
                    text.ifBlank { null }
                }
            } else {
                AppLogger.w(TAG, "Tracker fetch HTTP $code from $url")
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tracker fetch exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun isStale(intervalHours: Int): Boolean {
        if (meta.lastUpdated == 0L) return true
        return (System.currentTimeMillis() - meta.lastUpdated) > intervalHours * 3600_000L
    }

    private fun saveMeta() {
        try {
            metaFile.writeText(json.encodeToString(meta))
        } catch (_: Exception) {}
    }

    private fun loadMeta() {
        try {
            if (metaFile.exists()) {
                val text = metaFile.readText()
                if (text.isNotBlank()) {
                    meta = json.decodeFromString<TrackerMeta>(text)
                }
            }
        } catch (_: Exception) {}
    }
}
