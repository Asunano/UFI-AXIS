package com.ufi_axis_core.core.cache

import kotlinx.serialization.json.JsonElement

/**
 * 序列化响应缓存 — 缓存已构建的 JsonElement，避免高频端点重复序列化。
 *
 * ## 与 ResponseCache 的区别
 * - [ResponseCache] 缓存**数据获取**结果（goform/shell 查询，TTL 秒~分钟级）
 * - [JsonResponseCache] 缓存**最终 JSON 响应**（跳过 toJsonElement + 数据聚合，TTL 毫秒级）
 *
 * ## 适用场景
 * 前端 10s 轮询 dashboard/summary → 3s 内多次请求共享同一 JsonElement，
 * 省去 Map→JsonElement 递归转换和 7 个数据源的重复聚合。
 *
 * ## 线程安全
 * synchronized 保护读写，JsonElement 不可变所以读无需加锁。
 * 最坏情况（两个线程同时 miss）无害 — 两者都计算并写入，后者覆盖前者。
 */
class JsonResponseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    private data class Entry(
        val data: JsonElement,
        val expiresAt: Long
    )

    // LRU LinkedHashMap — 淘汰最久未访问的条目，防止无界增长
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }
    private val lock = Any()

    /**
     * 获取缓存的响应。未命中或已过期返回 null。
     */
    fun get(key: String): JsonElement? = synchronized(lock) {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        entry.data  // JsonElement 不可变，安全共享
    }

    /**
     * 写入缓存。
     * @param key 缓存键（通常为端点路径）
     * @param data 预构建的 JsonElement
     * @param ttlMs 生存时间（毫秒）
     */
    fun put(key: String, data: JsonElement, ttlMs: Long) {
        synchronized(lock) {
            entries[key] = Entry(data, System.currentTimeMillis() + ttlMs)
        }
    }

    /**
     * 清除全部缓存
     */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * 缓存统计（调试用）
     */
    fun stats(): Map<String, Any> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val valid = entries.filter { it.value.expiresAt > now }
        return mapOf(
            "total_entries" to entries.size,
            "valid_entries" to valid.size,
            "max_entries" to maxEntries,
            "keys" to valid.keys.toList()
        )
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 20
    }
}
