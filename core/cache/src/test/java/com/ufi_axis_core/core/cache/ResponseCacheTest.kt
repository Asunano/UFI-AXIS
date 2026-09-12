package com.ufi_axis_core.core.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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

    private companion object {
        /** 比 `LOCKS_CLEAN_THRESHOLD`（200）多一截，确保清理真的被触发。 */
        const val OVER_CLEAN_THRESHOLD = 260
    }
}
