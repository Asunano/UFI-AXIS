package com.ufi_axis_core.core.cache

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * API 响应缓存 — 减少对 Goform/Shell 等慢速数据源的重复查询。
 *
 * ## 设计
 * - 线程安全（ConcurrentHashMap + Mutex 防缓存击穿）
 * - TTL 自动过期（get 时惰性清理）
 * - 模式匹配失效（支持通配符 *）
 * - 硬上限保护（MAX_ENTRIES，防内存溢出）
 *
 * ## 使用
 * ```kotlin
 * val data = cache.getOrPut("device:info", 60_000L) {
 *     fetchDeviceInfo() // suspend
 * }
 * cache.invalidate("wifi:*") // 写操作后失效
 * ```
 */
class ResponseCache(
    /** 失效回调 — 用于 WebSocket 推送 data_changed 通知 */
    private val onInvalidate: (suspend (String) -> Unit)? = null
) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>() // 每 key 独立锁，防同 key 并发穿透

    /** 硬上限 — 超过时清空最旧条目 */
    private val maxEntries = 200

    /**
     * 获取缓存值（未过期），不存在或过期返回 null
     */
    fun get(key: String): JsonElement? {
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    /**
     * 获取或计算缓存值，带防击穿锁。
     * @param key 缓存键（建议格式: "domain:sub" 如 "device:info"）
     * @param ttlMs 生存时间（毫秒）
     * @param fetcher 数据获取函数（仅 miss 时调用一次）
     */
    suspend fun getOrPut(key: String, ttlMs: Long, fetcher: suspend () -> JsonElement): JsonElement {
        // 快速路径：缓存命中直接返回
        get(key)?.let { return it }

        // 慢路径：按 key 加锁，防止同 key 并发穿透
        val keyLock = locks.computeIfAbsent(key) { Mutex() }
        return keyLock.withLock {
            // 双重检查
            get(key)?.let { return it }

            val data = fetcher()
            putInternal(key, data, ttlMs)
            data
        }
    }

    /**
     * 直接写入缓存
     */
    fun put(key: String, data: JsonElement, ttlMs: Long) {
        putInternal(key, data, ttlMs)
    }

    /**
     * 按模式失效缓存条目，并异步触发通知回调。
     * - "*" 清空全部
     * - "wifi:*" 失效所有 wifi: 开头的 key
     * - "device:info" 精确失效
     */
    suspend fun invalidate(pattern: String) {
        val removed = when (pattern) {
            "*" -> {
                val size = cache.size
                cache.clear()
                AppLogger.d("ResponseCache", "Cleared all cache ($size entries)")
                size
            }
            else -> {
                val regex = globToRegex(pattern)
                val iterator = cache.keys.iterator()
                var count = 0
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (regex.matches(key)) {
                        iterator.remove()
                        count++
                    }
                }
                if (count > 0) {
                    AppLogger.d("ResponseCache", "Invalidated pattern=$pattern, removed $count entries")
                }
                count
            }
        }
        // 通知 WebSocket 客户端数据已变更
        if (removed > 0 && onInvalidate != null) {
            try {
                val changedType = pattern.substringBefore(":")
                onInvalidate.invoke(changedType)
            } catch (_: Exception) {}
        }
    }

    /**
     * 获取缓存统计信息
     */
    fun getStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val entries = cache.entries.map { (k, v) ->
            mapOf(
                "key" to k,
                "age_ms" to (now - v.timestamp),
                "ttl_ms" to v.ttlMs,
                "expired" to isExpired(v)
            )
        }.sortedByDescending { (it["age_ms"] as Long) }

        return mapOf(
            "count" to cache.size,
            "max_entries" to maxEntries,
            "entries" to entries.take(50) // 最多返回 50 条详情
        )
    }

    /** 清除所有缓存数据 */
    fun clear() {
        cache.clear()
    }

    // ── internal ──

    private fun putInternal(key: String, data: JsonElement, ttlMs: Long) {
        // 硬上限保护：超过时淘汰最旧的 50%
        if (cache.size >= maxEntries) {
            val toRemove = maxEntries / 2
            cache.entries
                .sortedBy { it.value.timestamp }
                .take(toRemove)
                .forEach { cache.remove(it.key) }
            AppLogger.w("ResponseCache", "Max entries ($maxEntries) reached, evicted $toRemove oldest")
        }
        cache[key] = CacheEntry(data, System.currentTimeMillis(), ttlMs)
    }

    private fun isExpired(entry: CacheEntry): Boolean =
        System.currentTimeMillis() - entry.timestamp > entry.ttlMs

    private fun globToRegex(pattern: String): Regex {
        val escaped = Regex.escape(pattern).replace("\\*", ".*")
        return Regex("^$escaped\$")
    }

    // ── data class ──

    private data class CacheEntry(
        val data: JsonElement,
        val timestamp: Long,
        val ttlMs: Long
    )
}

/**
 * 预定义的缓存 TTL 常量（毫秒）
 */
object CacheTTL {
    // ════════════════════════════════════
    // 【可缓存数据】— 变化缓慢，长 TTL
    // ════════════════════════════════════

    /** 设备信息（型号、内核）— 基本不变，10分钟（原5分钟） */
    const val DEVICE_INFO = 600_000L

    /** 设备设置（LED、性能模式等）— 偶尔变化，5分钟（原60秒） */
    const val DEVICE_SETTINGS = 300_000L

    /** WiFi 设置（SSID/密码/芯片）— 偶尔变化，2分钟（原30秒） */
    const val WIFI_SETTINGS = 120_000L

    /** LAN/DHCP 设置 — 基本不变，10分钟（原2分钟） */
    const val LAN_SETTINGS = 600_000L

    /** SIM 卡信息 — 几乎不变，15分钟（原5分钟） */
    const val SIM_INFO = 900_000L

    /** APN 配置 — 基本不变，5分钟（原2分钟） */
    const val APN_CONFIG = 300_000L

    /** 频段锁定状态 — 偶尔变化，5分钟（原30秒） */
    const val BAND_STATUS = 300_000L

    /** 流量限额 — 偶尔变化，5分钟（原60秒） */
    const val TRAFFIC_LIMIT = 300_000L

    /** 访问控制列表 — 偶尔变化，5分钟（原60秒） */
    const val ACCESS_CONTROL = 300_000L

    /** 设备信息 Goform 完整状态 — 5分钟（原2分钟） */
    const val GOFORM_FULL_STATUS = 300_000L

    /** 设备身份信息（msisdn/IMEI/IMSI/版本等）— 几乎不变，30分钟（原5分钟） */
    const val DEVICE_IDENTITY = 1_800_000L

    /** 设备固件版本 — 几乎不变，30分钟（原10分钟） */
    const val DEVICE_VERSION = 1_800_000L

    // ════════════════════════════════════
    // 【实时数据】— 变化快，短 TTL 维持不变
    // ════════════════════════════════════

    /** WiFi 客户端列表 — 实时数据，保持15秒不变 */
    const val WIFI_CLIENTS = 15_000L

    /** 基站信息 — 实时数据（移动中变化），保持20秒不变 */
    const val CELL_INFO = 20_000L
}
