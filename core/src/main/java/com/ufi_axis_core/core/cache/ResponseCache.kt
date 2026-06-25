package com.ufi_axis_core.core.cache

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * API 响应缓存 — 减少对 Goform/Shell 等慢速数据源的重复查询。
 *
 * ## 设计
 * - 线程安全（synchronized + Mutex 防缓存击穿）
 * - TTL 自动过期（get 时惰性清理）
 * - 模式匹配失效（支持通配符 *）
 * - **LRU 淘汰**（LinkedHashMap accessOrder=true，条目数超限自动淘汰最久未访问）
 * - **字节上限保护**（~2MB，超过时从 LRU 端持续淘汰至 80% 阈值）
 * - **泛型缓存**（getOrPutAny，合并 DataHub.typedCache，消除独立缓存层）
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
    /** 条目硬上限 — 超过时自动淘汰 LRU 条目（LinkedHashMap） */
    private val maxEntries = 200

    /** 字节硬上限 — 超过时从 LRU 端持续淘汰直到低于 80% 阈值（~2MB） */
    private val maxBytes = 2_000_000

    /** 泛型缓存条目上限 */
    private val anyCacheMaxEntries = 50

    /** 锁对象，保护 LinkedHashMap 并发访问 */
    private val lock = Any()

    // ═══════════════════════════════════════════════════════════
    // 第一部分：主缓存（JsonElement，200 条 LRU + 2MB 字节上限）
    // ═══════════════════════════════════════════════════════════

    /**
     * 主缓存 — LinkedHashMap with accessOrder=true (LRU)。
     *
     * removeEldestEntry 在每次 put 后自动检查，超出 maxEntries 时淘汰最久未访问的条目。
     * 无需手动排序或批量删除，O(1) 淘汰。
     */
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 第二部分：泛型缓存（Any，50 条 LRU，取代 DataHub.typedCache）
    // ═══════════════════════════════════════════════════════════

    /**
     * 任意类型泛型缓存 — 取代 DataHub.typedCache。
     * 共享同样的 LRU 淘汰策略，消除独立缓存层。
     * 使用 "any:" 前缀隔离 key 空间，避免与主缓存冲突。
     */
    private val anyCache = object : LinkedHashMap<String, AnyCacheEntry<*>>(anyCacheMaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnyCacheEntry<*>>?): Boolean {
            return size > anyCacheMaxEntries
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 第三部分：防击穿锁（每 key 独立 Mutex，防止同 key 并发穿透）
    // ═══════════════════════════════════════════════════════════

    private val locks = ConcurrentHashMap<String, Mutex>()

    // ═══════════════════════════════════════════════════════════
    // 第四部分：公开 API — 主缓存
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取缓存值（未过期），不存在或过期返回 null
     */
    fun get(key: String): JsonElement? {
        synchronized(lock) {
            val entry = cache[key] ?: return null
            if (isExpired(entry)) {
                cache.remove(key)
                return null
            }
            return entry.data
        }
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
     * - "*" 清空全部（包括泛型缓存）
     * - "wifi:*" 失效所有 wifi: 开头的 key
     * - "device:info" 精确失效
     */
    suspend fun invalidate(pattern: String) {
        val removed = synchronized(lock) {
            when (pattern) {
                "*" -> {
                    val size = cache.size
                    cache.clear()
                    anyCache.clear()
                    AppLogger.d("ResponseCache", "Cleared all cache ($size entries)")
                    size
                }
                else -> {
                    val regex = globToRegex(pattern)
                    var count = 0
                    val cit = cache.entries.iterator()
                    while (cit.hasNext()) {
                        if (regex.matches(cit.next().key)) {
                            cit.remove()
                            count++
                        }
                    }
                    val ait = anyCache.entries.iterator()
                    while (ait.hasNext()) {
                        if (regex.matches(ait.next().key)) {
                            ait.remove()
                            count++
                        }
                    }
                    if (count > 0) {
                        AppLogger.d("ResponseCache", "Invalidated pattern=$pattern, removed $count entries")
                    }
                    count
                }
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
        synchronized(lock) {
            val entries = cache.entries.map { (k, v) ->
                mapOf(
                    "key" to k,
                    "age_ms" to (now - v.timestamp),
                    "ttl_ms" to v.ttlMs,
                    "expired" to isExpired(v),
                    "size_bytes" to estimateSize(v.data)
                )
            }.sortedByDescending { (it["age_ms"] as Long) }
            return mapOf(
                "count" to cache.size,
                "max_entries" to maxEntries,
                "any_cache_count" to anyCache.size,
                "total_bytes_estimate" to cache.entries.sumOf { estimateSize(it.value.data) },
                "entries" to entries.take(50)
            )
        }
    }

    /** 清除所有缓存数据 */
    fun clear() {
        synchronized(lock) {
            cache.clear()
            anyCache.clear()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 第五部分：公开 API — 泛型缓存
    // ═══════════════════════════════════════════════════════════

    /**
     * 泛型缓存 getOrPut — 取代第三方独立缓存层，支持任意类型。
     *
     * 与主缓存共享锁但隔离 key 空间，避免类型冲突。
     * 适用于 DataHub 中少量、低频变化的强类型数据（如 [com.ufi_axis_core.api.GoformNetworkInfo]）。
     *
     * @param key 缓存键（如 "hub:network-type-info"）
     * @param ttlMs 生存时间（毫秒）
     * @param fetcher 数据获取函数（仅 miss 时调用一次）
     */
    suspend fun <T> getOrPutAny(key: String, ttlMs: Long, fetcher: suspend () -> T): T {
        // 快速路径
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val entry = anyCache[key] as? AnyCacheEntry<T>
            if (entry != null && !entry.isExpired()) return entry.data
        }

        // 慢路径：按 key 加锁（"any:" 前缀隔离 key 空间）
        val keyLock = locks.computeIfAbsent("any:$key") { Mutex() }
        return keyLock.withLock {
            synchronized(lock) {
                @Suppress("UNCHECKED_CAST")
                val recheck = anyCache[key] as? AnyCacheEntry<T>
                if (recheck != null && !recheck.isExpired()) return recheck.data
            }
            val data = fetcher()
            synchronized(lock) {
                anyCache[key] = AnyCacheEntry(data, System.currentTimeMillis(), ttlMs)
            }
            data
        }
    }

    /**
     * 精确失效泛型缓存条目
     */
    fun invalidateAny(key: String) {
        synchronized(lock) {
            anyCache.remove(key)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 第六部分：内部方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 写入主缓存。
     *
     * LinkedHashMap 自动处理 LRU 淘汰（条目数上限），
     * 此处补充字节上限保护：超过 maxBytes 时从 LRU 端持续淘汰直到低于 80% 阈值。
     */
    private fun putInternal(key: String, data: JsonElement, ttlMs: Long) {
        synchronized(lock) {
            cache[key] = CacheEntry(data, System.currentTimeMillis(), ttlMs)

            // 字节上限保护：超过时从 LRU 端（迭代器头部）持续淘汰
            var totalBytes = cache.entries.sumOf { estimateSize(it.value.data) }
            if (totalBytes > maxBytes) {
                val threshold = (maxBytes * 0.8).toLong()
                val it = cache.entries.iterator()
                var removed = 0
                while (it.hasNext() && totalBytes > threshold) {
                    val entry = it.next()
                    totalBytes -= estimateSize(entry.value.data)
                    it.remove()
                    removed++
                }
                AppLogger.w("ResponseCache", "Byte limit ($maxBytes) reached, evicted $removed entries, current=$totalBytes bytes")
            }
        }
    }

    private fun isExpired(entry: CacheEntry): Boolean =
        System.currentTimeMillis() - entry.timestamp > entry.ttlMs

    /** 缓存已编译的 glob 正则，避免每次 invalidate 重复编译 */
    private val globRegexCache = ConcurrentHashMap<String, Regex>()
    
    private fun globToRegex(pattern: String): Regex {
        return globRegexCache.getOrPut(pattern) {
            Regex("^${Regex.escape(pattern).replace("\\*", ".*")}$")
        }
    }

    /**
     * 估算 JsonElement 的内存占用（字节）。
     * JSON 字符串的 UTF-16 长度 × 2，粗略但足够用于淘汰决策。
     */
    private fun estimateSize(element: JsonElement): Int {
        return element.toString().length * 2
    }

    // ═══════════════════════════════════════════════════════════
    // 内部数据类
    // ═══════════════════════════════════════════════════════════

    private data class CacheEntry(
        val data: JsonElement,
        val timestamp: Long,
        val ttlMs: Long
    )

    private data class AnyCacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttlMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }
}

/**
 * 预定义的缓存 TTL 常量（毫秒）
 */
object CacheTTL {
    // ════════════════════════════════════════════════════════
    // 【可缓存数据】— 变化缓慢，长 TTL
    // ════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════
    // 【实时数据】— 变化快，短 TTL 维持不变
    // ════════════════════════════════════════════════════════

    /** WiFi 客户端列表 — 实时数据，保持15秒不变 */
    const val WIFI_CLIENTS = 15_000L

    /** 基站信息 — 实时数据（移动中变化），保持20秒不变 */
    const val CELL_INFO = 20_000L
}