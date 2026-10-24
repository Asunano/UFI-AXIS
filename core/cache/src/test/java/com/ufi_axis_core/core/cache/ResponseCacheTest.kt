package com.ufi_axis_core.core.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ResponseCache] 防击穿锁的**回收判据**回归防线。
 *
 * 为什么这些用例值钱：这层锁的作用是「同一个 key 同时来 N 个请求，只让一个去打 goform」。
 * 判活判错的后果不会报错、也不会在日志里留痕 —— 只会表现为设备侧突然多出几倍的
 * goform 请求（发热、QoS 被压、随机断连），而且恰恰对最慢、最需要保护的 key 失效。
 *
 * 判据历经三版：`retainAll(activeKeys)` → `!mutex.isLocked` → 显式 waiter 引用计数。
 * 前两版都栽在「拿到 Mutex 之后、真正加锁之前」这个窗口上：那一刻 key 既不在缓存里
 * （fetcher 还没返回），mutex 也还不是 locked，于是被当成垃圾清掉，下一个协程
 * `computeIfAbsent` 拿到的是一把**新锁**，两者并发进临界区。
 *
 * 计数版的命门反过来是**泄漏**：只要有一条路径漏了 -1，那个 key 的锁就永远回收不掉。
 * 所以正常返回 / fetcher 抛异常 / 协程被取消三条路径都各有一条用例钉住。
 *
 * 用反射读私有的 `locks`：这些不变量是实现内部的，不该为了测试给生产代码开一个
 * 只有测试用的公开访问器。
 */
class ResponseCacheTest {

    // ══════════ 反射探针 ══════════

    private fun ResponseCache.lockEntries(): Map<*, *> {
        val f = ResponseCache::class.java.getDeclaredField("locks")
        f.isAccessible = true
        return f.get(this) as Map<*, *>
    }

    /** 读一个 LockEntry 的 waiter 计数。 */
    private fun waitersOf(entry: Any?): Int {
        assertNotNull("锁条目不该在这个时刻被回收", entry)
        val f = entry!!.javaClass.getDeclaredField("waiters")
        f.isAccessible = true
        return (f.get(entry) as AtomicInteger).get()
    }

    // ══════════ 计数必须归零（三条退出路径） ══════════

    @Test
    fun `waiter count returns to zero after a normal fetch`() = runBlocking {
        val cache = ResponseCache()
        cache.getOrPut("dev:info", 60_000L) { JsonPrimitive("v") }
        assertEquals(0, waitersOf(cache.lockEntries()["dev:info"]))
    }

    /** fetcher 抛异常也必须 -1，否则这把锁永远回收不掉。 */
    @Test
    fun `waiter count returns to zero when the fetcher throws`() = runBlocking {
        val cache = ResponseCache()
        try {
            cache.getOrPut("dev:info", 60_000L) { throw IllegalStateException("goform down") }
            fail("fetcher 的异常应当原样向上抛")
        } catch (e: IllegalStateException) {
            // 预期
        }
        assertEquals(0, waitersOf(cache.lockEntries()["dev:info"]))
    }

    /** 协程取消同理 —— 停机时整批采集协程一起被 cancel，这是最常见的路径。 */
    @Test
    fun `waiter count returns to zero when the coroutine is cancelled`() = runBlocking {
        val cache = ResponseCache()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            cache.getOrPut("dev:info", 60_000L) {
                entered.complete(Unit)
                delay(60_000L)
                JsonPrimitive("never")
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, waitersOf(cache.lockEntries()["dev:info"]))
    }

    /** 泛型缓存走同一套 acquire/release，"any:" 前缀那份也要归零。 */
    @Test
    fun `getOrPutAny releases its waiter on the exception path too`() = runBlocking {
        val cache = ResponseCache()
        try {
            cache.getOrPutAny<String>("hub:network", 60_000L) { throw IllegalStateException("boom") }
            fail("fetcher 的异常应当原样向上抛")
        } catch (e: IllegalStateException) {
            // 预期
        }
        assertEquals(0, waitersOf(cache.lockEntries()["any:hub:network"]))
    }

    // ══════════ 回收判据 ══════════

    /**
     * 正在被使用的锁必须活过清理，否则同 key 的第二个协程会拿到新锁并再打一次 fetcher。
     *
     * `hot:key` 的 fetcher 还没返回时它**不在缓存里**，正是历史上被误当垃圾回收的时刻。
     */
    @Test
    fun `a lock in use survives the cleanup sweep so single-flight still holds`() = runBlocking {
        val cache = ResponseCache()
        val calls = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            cache.getOrPut("hot:key", 60_000L) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                JsonPrimitive("v1")
            }
        }
        entered.await()

        // 把 locks 推过清理阈值，并借 getOrPut 的 finally 触发一次清理
        repeat(OVER_CLEAN_THRESHOLD) { i -> cache.getOrPut("cold:$i", 60_000L) { JsonPrimitive(i) } }
        assertNotNull("fetcher 在飞的锁不能被回收", cache.lockEntries()["hot:key"])

        val second = launch(Dispatchers.Default) {
            cache.getOrPut("hot:key", 60_000L) {
                calls.incrementAndGet()
                JsonPrimitive("v2")
            }
        }
        // 若 second 拿到的是一把新锁，它会在这段时间里立刻跑第二遍 fetcher
        delay(300L)
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals("同 key 的 fetcher 只该跑一次", 1, calls.get())
    }

    /** 反过来：空闲且已离开缓存的锁必须真的被回收，不然 locks 会随运行时间无限增长。 */
    @Test
    fun `idle locks whose keys left the cache are reclaimed`() = runBlocking {
        val cache = ResponseCache()
        // 主缓存条目上限 200（LRU），400 个 key 意味着一半会被淘汰出缓存
        repeat(400) { i -> cache.getOrPut("cold:$i", 60_000L) { JsonPrimitive(i) } }
        val size = cache.lockEntries().size
        assertTrue("locks 不应随 key 数无限增长，实际=$size", size <= 210)
    }

    /**
     * goform 熔断期间：只回旧值，不再调 fetcher（2026-10-17）。
     *
     * 这是"设备后台坏了，页面仍显示上次数据"的实现。它与 `setReadThroughPaused`
     * （用户停止服务）是两个独立闸门，这里单独验证第二个。
     */
    @Test
    fun `goform 不健康时只回旧值且不调 fetcher`() = runBlocking {
        val cache = ResponseCache()
        var calls = 0

        // ttl=0 → 跨过 1ms 后必然"过期"
        val first = cache.getOrPut("device:identity", 0L) { calls++; JsonPrimitive("v1") }
        assertEquals("v1", first.jsonPrimitive.content)
        // `isExpired` 是 `elapsed > ttl`，同毫秒内 ttl=0 还不算过期；
        // 显式跨过 1ms，避免恢复后的读取因时钟粒度直接命中旧值（CI 上偶发）。
        kotlinx.coroutines.delay(5)

        cache.setGoformUnhealthy(true)
        val stale = cache.getOrPut("device:identity", 0L) { calls++; JsonPrimitive("v2") }
        assertEquals("熔断期间必须回旧值", "v1", stale.jsonPrimitive.content)
        assertEquals("熔断期间绝不能再调 fetcher（那就是又去打 goform）", 1, calls)

        cache.setGoformUnhealthy(false)
        val fresh = cache.getOrPut("device:identity", 0L) { calls++; JsonPrimitive("v3") }
        assertEquals("恢复后应重新取新值", "v3", fresh.jsonPrimitive.content)
        assertEquals(2, calls)
    }

    /** 两个闸门互相独立：goform 恢复不能把"用户停止服务"的闸门一起打开。 */
    @Test
    fun `两个闸门互不覆盖`() = runBlocking {
        val cache = ResponseCache()
        var calls = 0
        cache.getOrPut("k", 0L) { calls++; JsonPrimitive("v1") }

        cache.setReadThroughPaused(true)   // 用户停止服务
        cache.setGoformUnhealthy(true)     // 设备后台坏了
        cache.setGoformUnhealthy(false)    // goform 恢复
        // 但服务仍处于"停止"状态 → 仍应回旧值
        val v = cache.getOrPut("k", 0L) { calls++; JsonPrimitive("v2") }
        assertEquals("v1", v.jsonPrimitive.content)
        assertEquals("用户停止服务期间不该调 fetcher", 1, calls)
    }

    private companion object {
        /** 比 `LOCKS_CLEAN_THRESHOLD`（200）多一截，确保清理真的被触发。 */
        const val OVER_CLEAN_THRESHOLD = 260
    }
}
