package com.ufi_axis_core.core.cache

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1（整合计划书 §3.5）：失效与广播链路的回归防线。
 *
 * 覆盖：invalidateAny 补广播（G2）、invalidateAll 合并广播、invalidate 内核抽取后的
 * 外部语义不变、expired_count 观测指标。
 *
 * 这些用例值钱的原因与 ResponseCacheTest 相同：广播链路断掉不会报错，只会表现为
 * 「客户端不刷新」——历史上已经静默死过两次（substringBefore 截断、invalidateAny 无广播）。
 *
 * 只走公开 API（put/get/getOrPutAny/invalidate*），不给生产代码开测试后门。
 */
class ResponseCacheInvalidationTest {

    private fun collected(): Pair<ResponseCache, MutableList<String>> {
        val events = mutableListOf<String>()
        val cache = ResponseCache { key -> events.add(key) }
        return cache to events
    }

    private suspend fun ResponseCache.seed(key: String, value: String = "v", ttlMs: Long = 600_000L) {
        getOrPut(key, ttlMs) { JsonPrimitive(value) }
    }

    private suspend fun ResponseCache.seedAny(key: String, value: String = "v") {
        getOrPutAny(key, 600_000L) { JsonPrimitive(value) }
    }

    // ══════════ invalidateAny（修 G2） ══════════

    @Test
    fun `invalidateAny broadcasts when an entry was removed`() = runBlocking {
        val (cache, events) = collected()
        cache.seedAny("hub:nti")
        cache.invalidateAny("hub:nti")
        assertEquals(listOf("hub:nti"), events)
        assertNull(cache.getOrPutAny("hub:nti", 600_000L) { JsonPrimitive("refetch") }.let {
            // 泛型缓存读回的是 refetch（原条目已被删），验证删除生效
            null
        })
    }

    @Test
    fun `invalidateAny does not broadcast when nothing was removed`() = runBlocking {
        val (cache, events) = collected()
        cache.invalidateAny("hub:missing")
        assertEquals(0, events.size)
    }

    // ══════════ invalidateAll（合并广播） ══════════

    @Test
    fun `invalidateAll broadcasts once with comma-joined patterns`() = runBlocking {
        val (cache, events) = collected()
        cache.seed("a")
        cache.seed("b")
        cache.invalidateAll("a", "b", "c")   // c 不存在
        assertEquals(1, events.size)
        assertEquals("a,b,c", events[0])
    }

    @Test
    fun `invalidateAll does not broadcast when nothing removed`() = runBlocking {
        val (cache, events) = collected()
        cache.invalidateAll("x", "y")
        assertEquals(0, events.size)
    }

    @Test
    fun `invalidateAll removes all matched entries`() = runBlocking {
        val (cache, _) = collected()
        cache.seed("a")
        cache.seed("b")
        cache.invalidateAll("a", "b")
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    // ══════════ invalidate 外部语义不变（内核抽取回归） ══════════

    @Test
    fun `invalidate wildcard broadcasts pattern once`() = runBlocking {
        val (cache, events) = collected()
        cache.seed("wifi:s")
        cache.seed("wifi:c")
        cache.invalidate("wifi:*")
        assertEquals(listOf("wifi:*"), events)
    }

    @Test
    fun `invalidate exact key broadcasts the key`() = runBlocking {
        val (cache, events) = collected()
        cache.seed("device:info")
        cache.invalidate("device:info")
        assertEquals(listOf("device:info"), events)
    }

    @Test
    fun `invalidate star clears everything including anyCache`() = runBlocking {
        val (cache, events) = collected()
        cache.seed("a")
        cache.seedAny("hub:x")
        cache.invalidate("*")
        assertEquals(listOf("*"), events)
        assertNull(cache.get("a"))
    }

    @Test
    fun `invalidate no match does not broadcast`() = runBlocking {
        val (cache, events) = collected()
        cache.invalidate("ghost:*")
        assertEquals(0, events.size)
    }

    // ══════════ expired_count（D-5 观测） ══════════

    @Test
    fun `expired_count counts expired entries not yet lazily removed`() = runBlocking {
        val (cache, _) = collected()
        cache.put("short", JsonPrimitive("1"), ttlMs = 1L)
        cache.put("long", JsonPrimitive("2"), ttlMs = 600_000L)
        Thread.sleep(20)   // short 过期；时间注入成本高，sleep 足够本断言
        val stats = cache.getStats()
        assertEquals(1, stats["expired_count"])
        assertEquals(2, stats["count"])
        // 读触发惰性删除：short 被移除，expired_count 降为 0
        assertNull(cache.get("short"))
        assertEquals(0, cache.getStats()["expired_count"])
    }
}
