package com.ufi_axis_core.util

import kotlinx.serialization.json.JsonObject
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Goform 设备通信 QoS 控制器
 *
 * 通过自适应信号量限制并发 HTTP 请求到 ZTE 设备 Goform 接口的数量，
 * 防止设备过载。内置短 TTL 缓存，减少重复查询。
 *
 * ## 核心功能
 * - **查询限流**: query 信号量默认 4 并发（1~8 动态调整），读操作高并发
 * - **设置限流**: set 信号量默认 2 并发（1~4 动态调整），写操作低并发
 * - **TTL 缓存**: 相同查询在 TTL 内返回缓存结果（默认 2s，高负载时自动延长）
 * - **非阻塞执行**: 后台任务获取不到信号量时静默跳过
 *
 * ## 设计原则
 * 与 ShellQoS 平行，控制后端到设备的 HTTP 通信层（而非前端到后端的请求层）。
 * GoformClient 的 queryInternal/querySingleInternal/goformPostInternal 三个核心方法
 * 通过 withQueryPermit/withSetPermit 接入，60+ 公共方法无需改动。
 */
object GoformQoS {

    private const val DEFAULT_QUERY_PERMITS = 4
    private const val DEFAULT_SET_PERMITS = 2
    private const val DEFAULT_CACHE_TTL_MS = 2000L
    private const val MAX_CACHE_SIZE = 30  // 缓存硬上限

    private val querySemaphore = AdaptiveSemaphore(
        initialPermits = DEFAULT_QUERY_PERMITS, minPermits = 1, maxPermits = 8
    )
    private val setSemaphore = AdaptiveSemaphore(
        initialPermits = DEFAULT_SET_PERMITS, minPermits = 1, maxPermits = 4
    )

    private val cacheLock = Any()

    private val queryCache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    @Volatile
    private var cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS

    // 统计
    private val totalQueries = AtomicInteger(0)
    private val cacheHits = AtomicInteger(0)
    private val totalSets = AtomicInteger(0)

    // ==================== 核心执行方法 ====================

    /**
     * 带限流的查询操作（GET 请求）
     * GoformClient.queryInternal / querySingleInternal 调用
     */
    suspend fun <T> withQueryPermit(block: suspend () -> T): T {
        totalQueries.incrementAndGet()
        return querySemaphore.withPermit { block() }
    }

    /**
     * 带限流的设置操作（POST 请求）
     * GoformClient.goformPostInternal 调用
     */
    suspend fun <T> withSetPermit(block: suspend () -> T): T {
        totalSets.incrementAndGet()
        return setSemaphore.withPermit { block() }
    }

    /**
     * 非阻塞尝试查询
     * 获取不到信号量时返回 null，适用于后台采集可跳过的场景
     */
    suspend fun <T> tryWithQueryPermit(block: suspend () -> T): T? {
        return if (querySemaphore.tryAcquire()) {
            totalQueries.incrementAndGet()
            try {
                block()
            } finally {
                querySemaphore.release()
            }
        } else null
    }

    /**
     * 非阻塞尝试设置
     */
    suspend fun <T> tryWithSetPermit(block: suspend () -> T): T? {
        return if (setSemaphore.tryAcquire()) {
            totalSets.incrementAndGet()
            try {
                block()
            } finally {
                setSemaphore.release()
            }
        } else null
    }

    // ==================== 缓存 ====================

    /**
     * 获取缓存的查询结果
     * @return 缓存的 JsonObject，过期或不存在时返回 null
     */
    fun getCachedQuery(key: String): JsonObject? {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            queryCache[key]?.let { entry ->
                if (now - entry.timestamp < cacheTtlMs) {
                    cacheHits.incrementAndGet()
                    return entry.result
                }
            }
        }
        return null
    }

    /**
     * 写入查询缓存
     */
    fun cacheQuery(key: String, result: JsonObject) {
        synchronized(cacheLock) {
            queryCache[key] = CacheEntry(result, System.currentTimeMillis())
        }
        pruneExpiredCache()
    }

    // ==================== 状态查询 ====================

    /** 查询信号量当前可用许可数 */
    val queryAvailablePermits: Int get() = querySemaphore.availablePermits

    /** 查询信号量当前实际许可数 */
    val queryTotalPermits: Int get() = querySemaphore.totalPermits

    /** 查询信号量目标许可数 */
    val queryTargetPermits: Int get() = querySemaphore.target

    /** 设置信号量当前可用许可数 */
    val setAvailablePermits: Int get() = setSemaphore.availablePermits

    /** 设置信号量当前实际许可数 */
    val setTotalPermits: Int get() = setSemaphore.totalPermits

    /** 缓存条目数 */
    val cacheSize: Int get() = synchronized(cacheLock) { queryCache.size }

    /** 当前缓存 TTL */
    val currentCacheTtlMs: Long get() = cacheTtlMs

    /** 获取完整 QoS 状态（供诊断 API 返回） */
    fun getStatus(): Map<String, Any> = mapOf(
        "query_available" to querySemaphore.availablePermits,
        "query_total" to querySemaphore.totalPermits,
        "query_target" to querySemaphore.target,
        "set_available" to setSemaphore.availablePermits,
        "set_total" to setSemaphore.totalPermits,
        "set_target" to setSemaphore.target,
        "cache_size" to synchronized(cacheLock) { queryCache.size },
        "cache_ttl_ms" to cacheTtlMs,
        "stats" to mapOf(
            "total_queries" to totalQueries.get(),
            "cache_hits" to cacheHits.get(),
            "total_sets" to totalSets.get()
        )
    )

    // ==================== 动态调整 ====================

    /**
     * 更新缓存 TTL
     * @param ttlMs 新 TTL，限制在 500ms ~ 30000ms 之间
     */
    fun updateCacheTtl(ttlMs: Long) {
        cacheTtlMs = ttlMs.coerceIn(500, 30_000)
        AppLogger.i("GoformQoS", "Cache TTL updated to ${cacheTtlMs}ms")
    }

    /**
     * 自适应调整 — 由 DataScheduler 性能监控循环调用
     *
     * @param targetQueryPermits 查询信号量目标（1~8）
     * @param targetSetPermits 设置信号量目标（1~4）
     */
    fun adaptiveAdjust(targetQueryPermits: Int, targetSetPermits: Int) {
        querySemaphore.adjustTo(targetQueryPermits)
        setSemaphore.adjustTo(targetSetPermits)
    }

    /** 清空查询缓存 */
    fun clearCache() {
        synchronized(cacheLock) {
            queryCache.clear()
        }
        AppLogger.i("GoformQoS", "Query cache cleared")
    }

    // ==================== 内部方法 ====================

    private fun pruneExpiredCache() {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val iterator = queryCache.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value.timestamp > cacheTtlMs) {
                    iterator.remove()
                }
            }
        }
    }

    private data class CacheEntry(
        val result: JsonObject,
        val timestamp: Long
    )
}
