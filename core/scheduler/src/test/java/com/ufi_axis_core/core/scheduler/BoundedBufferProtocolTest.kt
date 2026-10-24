package com.ufi_axis_core.core.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [offerCounterBounded] / [drainCounterBounded] 的回归护栏。
 *
 * 这套「计数 ↔ 容器」配对协议里曾**同时**藏着两个 off-by-one，两个都让监控数据没被记录下来。
 *
 * ## 缺陷一：drain 少取一条（2026-10-14 修）
 * 旧实现是 `val n = counter.updateAndGet { cur -> ... cur - 1 }` —— `updateAndGet` 给的是
 * **减过之后的新值**，再判 `if (n <= 0) break` ⇒ **计数为 1 时直接 break、根本不 poll**。
 * 净效果：每次 drain 只取走「计数 − 1」条。
 *
 * 它只在「产出速率 ≈ 1 条 / 刷写周期」时才显形 —— 也就是**没有前端连接**的常态：
 * 每个指标 30s 只产出 1 条 → drain 恒返回空 → `totalFlushed == 0` → 既不写库、
 * 也不打 "Flushed buffers"（那行只在 > 0 时打），图表整段空白。
 * 真机实测 09:26→11:48 共 142 分钟零产出，11:48:05 前端一连上、11:48:16 立刻恢复。
 *
 * ## 缺陷二：逐出时多减计数（2026-10-14 修）
 * `poll()` + `offer()` 是**净零**（队列长度不变），但旧实现在逐出分支里还做了 `counter - 1`
 * ⇒ 计数比队列实际长度少 1 ⇒ **上界失效**：队列每 2 次 offer 稳定涨 1 条而无界增长，
 * 而 drain 只按计数取 ⇒ 积压永远清不干净，写进库的是**很旧的时间戳**（图表时间轴错位）。
 *
 * 下面的用例分别钉死这两个缺陷，外加一条不变量断言（`计数 == 队列长度`）。
 */
class BoundedBufferProtocolTest {

    /** 按协议把队列取空（`drainCounterBounded` 只往 list 里塞，不返回）。 */
    private fun <T> ConcurrentLinkedQueue<T>.drainAll(counter: AtomicInteger): List<T> {
        val out = mutableListOf<T>()
        drainCounterBounded(counter, out)
        return out
    }

    @Test
    fun `缺陷一回归：计数为 1 时 drain 必须取走那一条`() {
        val queue = ConcurrentLinkedQueue<String>()
        val counter = AtomicInteger(0)
        queue.offerCounterBounded("only", counter, maxSize = 50)

        val out = queue.drainAll(counter)

        assertEquals("计数为 1 时 drain 取空了 —— drain 的 off-by-one 回归", listOf("only"), out)
        assertEquals(0, counter.get())
        assertTrue("队列应已清空", queue.isEmpty())
    }

    @Test
    fun `drain 取走计数声明的全部元素`() {
        val queue = ConcurrentLinkedQueue<Int>()
        val counter = AtomicInteger(0)
        repeat(7) { queue.offerCounterBounded(it, counter, maxSize = 50) }

        assertEquals((0 until 7).toList(), queue.drainAll(counter))
        assertTrue(queue.isEmpty())
        assertEquals(0, counter.get())
    }

    @Test
    fun `缺陷二回归：逐出后计数必须等于队列长度且不超上界`() {
        val maxSize = 50
        val overflow = 60
        val queue = ConcurrentLinkedQueue<Int>()
        val counter = AtomicInteger(0)
        // 灌满之后再超灌，逼出多次逐出
        repeat(maxSize + overflow) { queue.offerCounterBounded(it, counter, maxSize = maxSize) }

        assertEquals(
            "逐出后计数与队列长度漂移了 —— 逐出分支多减了计数（上界失效的根因）",
            queue.size,
            counter.get()
        )
        assertTrue("队列长度不得超过上界（实际 ${queue.size}）", queue.size <= maxSize)

        // 逐出丢的是最旧的：保留的应是最后 maxSize 条
        val out = queue.drainAll(counter)
        assertEquals(maxSize, out.size)
        assertEquals("保留的应是最后 $maxSize 条", (overflow until (maxSize + overflow)).toList(), out)
    }

    @Test
    fun `空队列 drain 不产出也不改计数`() {
        val queue = ConcurrentLinkedQueue<String>()
        val counter = AtomicInteger(0)
        assertEquals(emptyList<String>(), queue.drainAll(counter))
        assertEquals(0, counter.get())
    }

    @Test
    fun `计数与队列漂移时 drain 回滚占减`() {
        // 模拟漂移：计数说有 3 条，队列其实是空的
        val queue = ConcurrentLinkedQueue<String>()
        val counter = AtomicInteger(3)
        assertEquals(emptyList<String>(), queue.drainAll(counter))
        assertEquals("回滚后计数应原样保留（既不丢名额也不为负）", 3, counter.get())
    }
}
