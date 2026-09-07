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
 * ## 边界（重要）
 * 这一层限的是 **core → 设备** 的并发，**不是客户端 → core 的请求数**。
 * `/api` 下的接口没有任何请求级限流（`QoSMiddleware` 已于 2026-08-25 删除，见 `HttpServer` 的
 * `/api` 块注释）：web 开一个页面就会并发发十几个请求，限流会把正常批量加载判成滥用。
 * 因此这里只提供**阻塞等许可**的 [withQueryPermit] / [withSetPermit] —— 排队会慢，但不会
 * 让客户端拿到 429 或空数据。**不要**再加"抢不到许可就返回 null / 跳过"的变体给路由用：
 * 那等于把限流的代价变成前端字段缺失（历史上首屏卡片空白就是这么来的）。
 *
 * ## 核心功能
 * - **查询限流**: query 信号量默认 4 并发（1~8 动态调整），读操作高并发
 * - **设置限流**: set 信号量默认 2 并发（1~4 动态调整），写操作低并发
 * - **TTL 缓存**: 相同查询在 TTL 内返回缓存结果（默认 2s，高负载时自动延长）
 *
 * 并发数与 TTL 由 `DataScheduler` 的性能监控循环按 CPU 温度 / 使用率动态调整
 * （85°C 触发温度熔断：收到最低档 + 清缓存 + 暂停一轮）。
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
        "set_available" to setSemaphore.availablePermits,
        "set_total" to setSemaphore.totalPermits,
        "cache_size" to synchronized(cacheLock) { queryCache.size },
        "cache_ttl_ms" to cacheTtlMs
    )

    // ==================== 动态调整 ====================

    /**
     * 更新缓存 TTL
     * @param ttlMs 新 TTL，限制在 500ms ~ 30000ms 之间
     */
    fun updateCacheTtl(ttlMs: Long) {
        val next = ttlMs.coerceIn(500, 30_000)
        // 2026-08-28：同 ShellQoS，只在真的变化时打日志（调用方是 30s 的性能监控循环）
        if (next == cacheTtlMs) return
        cacheTtlMs = next
        AppLogger.i("GoformQoS", "Cache TTL updated to ${cacheTtlMs}ms")
    }

    /**
     * 自适应调整 — 由 DataScheduler 性能监控循环调用
     *
     * @param targetQueryPermits 查询信号量目标（1~8）
     * @param targetSetPermits 设置信号量目标（1~4）
     */
    fun adaptiveAdjust(targetQueryPermits: Int, targetSetPermits: Int) {
        val oldQ = querySemaphore.target
        val oldS = setSemaphore.target
        querySemaphore.adjustTo(targetQueryPermits)
        setSemaphore.adjustTo(targetSetPermits)
        if (oldQ != targetQueryPermits || oldS != targetSetPermits) {
            AppLogger.d("GoformQoS", "Adaptive adjust: Q($oldQ->$targetQueryPermits, avail=${querySemaphore.availablePermits}), S($oldS->$targetSetPermits, avail=${setSemaphore.availablePermits})")
        }
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
