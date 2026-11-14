package com.ufi_axis_core.api.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MediaTicketStore] 的三条语义护栏。
 *
 * 这三条都不是「实现细节」而是**设计前提**，任一条被改坏都会以很难定位的方式炸掉：
 * 1. 可重复使用 —— 改成一次性 ⇒ 浏览器第二个 Range 请求就 403，表现为「点了能放一秒就断」；
 * 2. 滑动过期 —— 改成固定过期 ⇒ 长电影播到一半突然断流；
 * 3. 只认签发时那条路径 —— 若改成从请求里取 path ⇒ 凭一张票能读任意文件。
 */
class MediaTicketStoreTest {

    /** 可控时钟：票据的过期判定全靠它，用真实时间就只能靠 sleep。 */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun ticketIsReusableAcrossManyRequests() {
        val clock = FakeClock()
        val store = MediaTicketStore(nowMs = clock)
        val ticket = store.issue("/storage/emulated/0/Music/a.flac")

        // 播放器对同一 URL 会连发几十上百个 Range 请求，每次都带同一张票
        repeat(50) {
            assertEquals("/storage/emulated/0/Music/a.flac", store.resolve(ticket))
        }
    }

    @Test
    fun ticketSlidesExpiryOnUse() {
        val clock = FakeClock()
        val store = MediaTicketStore(ttlMs = 1_000L, nowMs = clock)
        val ticket = store.issue("/storage/emulated/0/Movies/m.mp4")

        // 每 900ms 用一次：累计远超 TTL，但每次都续期 → 两小时的电影能连续播完
        repeat(10) {
            clock.now += 900L
            assertEquals("/storage/emulated/0/Movies/m.mp4", store.resolve(ticket))
        }

        // 停止播放后超过 TTL 未使用 → 失效（分享出去的 URL 变废纸）
        clock.now += 1_001L
        assertNull(store.resolve(ticket))
    }

    @Test
    fun unknownOrBlankTicketIsRejected() {
        val store = MediaTicketStore()
        assertNull(store.resolve(null))
        assertNull(store.resolve(""))
        assertNull(store.resolve("   "))
        assertNull(store.resolve("not-a-real-ticket"))
    }

    @Test
    fun ticketsAreUniquePerIssue() {
        val store = MediaTicketStore()
        val a = store.issue("/storage/emulated/0/Music/a.flac")
        val b = store.issue("/storage/emulated/0/Music/a.flac")
        // 同一个文件两次签发也必须是不同票据：复用会让「作废一次」牵连另一个播放会话
        assertNotEquals(a, b)
    }

    @Test
    fun capacityEvictionDropsLeastRecentlyUsed() {
        val clock = FakeClock()
        // maxEntries=3 的语义是「最多同时存活 3 条」：插入前淘汰，所以第 4 次签发才会触发
        val store = MediaTicketStore(maxEntries = 3, nowMs = clock)

        val first = store.issue("/storage/emulated/0/Music/1.flac")
        clock.now += 10
        val second = store.issue("/storage/emulated/0/Music/2.flac")
        clock.now += 10
        val third = store.issue("/storage/emulated/0/Music/3.flac")
        clock.now += 10
        // 让 first 变成"最近使用过"，second 因此成为最久未用的那条
        assertEquals("/storage/emulated/0/Music/1.flac", store.resolve(first))
        clock.now += 10

        // 第 4 张：容量已满 → 淘汰最久未用（second）
        val fourth = store.issue("/storage/emulated/0/Music/4.flac")

        assertNull("最久未使用的票据应被淘汰", store.resolve(second))
        assertEquals("刚用过的票据必须还在", "/storage/emulated/0/Music/1.flac", store.resolve(first))
        assertEquals("/storage/emulated/0/Music/3.flac", store.resolve(third))
        assertEquals("/storage/emulated/0/Music/4.flac", store.resolve(fourth))
        assertEquals("存活数不得超过上限", 3, store.size())
    }

    @Test
    fun revokeInvalidatesImmediately() {
        val store = MediaTicketStore()
        val ticket = store.issue("/storage/emulated/0/Music/a.flac")
        store.revoke(ticket)
        assertNull(store.resolve(ticket))
    }

    @Test
    fun expiredEntriesDoNotLeak() {
        val clock = FakeClock()
        val store = MediaTicketStore(ttlMs = 1_000L, nowMs = clock)
        repeat(5) { store.issue("/storage/emulated/0/Music/$it.flac") }
        assertEquals(5, store.size())

        clock.now += 1_001L
        assertEquals("过期票据必须被回收，否则长期运行会无界增长", 0, store.size())
    }
}
