package com.ufi_axis_core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `MediaDecodeGate` 的并发契约测试（2026-10-13，Phase 2）。
 *
 * ## 为什么必须测"峰值并发"而不是"能跑通"
 * 闸门的作用**只**体现在并发度上：`Semaphore(1)` 与"没有闸门"在单线程下行为完全一样，
 * 只有在并发下才分得出来。而这类回归的症状是"内存涨上去"，**不会**有任何异常或日志 ——
 * 所以必须用量化的并发计数把它钉住。
 *
 * ## 这些用例真正防的是什么
 *  · [MediaDecodeGate.heavy] 的许可数被调大 → 并发两张 4K HEVC 就可能把 1.5GB 的设备打 OOM；
 *  · [MediaDecodeGate.light] 的许可数被调大 → 图片网格一次性解几十张降采样图；
 *  · 有人把闸门从"取许可"改成"什么都不做"（例如为了修某个超时）→ 上面两条一起失效。
 *
 * ## 不测什么
 * `Semaphore` 不可重入这件事**没法**用测试表达 —— 重入的表现是永久挂起，
 * 测它等于让测试自己挂住。它只能靠 `MediaDecodeGate` 的 KDoc 约定 + 代码审查守住。
 */
class MediaDecodeGateTest {

    /**
     * 并发跑 [n] 个任务，每个任务在**许可内**停一小会儿，返回观察到的**峰值并发数**。
     *
     * ⚠️ 计数必须在 `withPermit { }` **里面**。第一版把它写在了外面，量到的自然是
     * "有多少个任务同时在跑"（恒等于 n），而不是"有多少个拿到了许可" ——
     * 于是闸门明明是对的、测试却全红。测并发闸门时，"测量点在哪"就是全部。
     *
     * 用 `delay` 而不是忙等：`delay` 会让出线程，于是拿到许可的任务真的会重叠，
     * 而不是被 `Dispatchers.Default` 的线程数卡住（那样测出来的是线程数、不是许可数）。
     */
    private fun peakConcurrency(n: Int, gate: Semaphore): Int = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        (1..n).map {
            async(Dispatchers.Default) {
                gate.withPermit {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(40)          // 停在许可里，给别的任务机会尝试进入
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()
        peak.get()
    }

    @Test
    fun `heavy 闸门同时只允许一次解码在飞`() {
        val peak = peakConcurrency(n = 8, gate = MediaDecodeGate.heavy)
        assertEquals(
            "heavy 必须串行（许可数 1）。并发两张 4K HEVC 就可能把这台 1.5GB 的设备打 OOM —— " +
                "而且 ffmpeg 单次最长 20s，许可数调大会让内存峰值成倍上涨。实际峰值 $peak",
            1, peak
        )
    }

    @Test
    fun `light 闸门的并发不超过许可数`() {
        val peak = peakConcurrency(n = 10, gate = MediaDecodeGate.light)
        assertTrue(
            "light 的峰值并发应 <= 2（许可数），实际 $peak —— " +
                "调大会让图片网格一次性解几十张降采样图",
            peak <= 2
        )
        assertTrue("light 至少要能并行 2 个，否则列表滚动的体感退化成完全串行", peak >= 2)
    }

    /**
     * 顺序调用不应累积占用：每个 `withPermit` 块结束后许可必须归还。
     *
     * 这条防的是"某次改动把 `withPermit` 换成 `acquire()` 却漏了 `release()`"——
     * 那会让闸门在跑满 N 次之后**永久锁死**，表现为"媒体页用一会儿之后所有缩略图都不出"，
     * 且没有任何日志。
     */
    @Test
    fun `许可在块结束后归还`() = runBlocking {
        repeat(20) {
            MediaDecodeGate.heavy.withPermit { }
            MediaDecodeGate.light.withPermit { }
        }
        // 跑完 20 轮之后仍应立即拿到许可；拿不到就会卡在这里（测试超时即失败）
        assertEquals(1, peakConcurrency(n = 2, gate = MediaDecodeGate.heavy))
        assertEquals(2, peakConcurrency(n = 2, gate = MediaDecodeGate.light))
    }
}
