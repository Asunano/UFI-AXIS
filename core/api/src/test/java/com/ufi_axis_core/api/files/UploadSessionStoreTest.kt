package com.ufi_axis_core.api.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [UploadSessionStore] 的设计前提护栏。
 *
 * 这些都不是「实现细节」，每一条被改坏都会以很难定位的方式炸掉：
 * 1. `received` 读文件实际长度 —— 改成内存计数器 ⇒ core 重启后续传从 0 开始；
 * 2. 文件名编解码可往返 —— 名字含点时切错 ⇒ 续传永远匹配不上，等于没有断点续传；
 * 3. 不同会话不同 `.ufipart` —— 共用一个文件 ⇒ 两个标签页并发追加把文件写坏；
 * 4. 过期连文件一起删 —— 只摘会话不删文件 ⇒ 每次超时都留一份垃圾；
 * 5. 时钟回跳仍能过期 —— 只判 `now - last > ttl` ⇒ 时间被往前调过的会话永不过期。
 */
class UploadSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可控时钟：过期判定全靠它，用真实时间就只能靠 sleep。 */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    /**
     * 写 n 字节，**并把 mtime 对齐到注入的假时钟**。
     *
     * 对齐这一步不是形式主义：`findResumable` / `isStalePart` 判过期用的是
     * `文件 mtime` 与 `nowMs()` 的差，而 `nowMs()` 在测试里是假时钟（1000L 量级）、
     * 文件 mtime 是真实墙钟（1.7e12 量级）。不对齐的话 mtime 永远"在未来"，
     * 会被过期判定（那条特判是为了应对系统时间回跳）当成陈旧分片删掉，
     * 于是所有续传用例都假失败。生产环境两者都是真实时钟，不存在这个问题。
     */
    private fun writeBytes(f: File, n: Int, clock: FakeClock? = null) {
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(n))
        if (clock != null) f.setLastModified(clock.now)
    }

    // ── 前提 1：received 以文件实际长度为准 ──

    @Test
    fun receivedReadsActualFileLength() {
        val dir = tmp.newFolder("d")
        val store = UploadSessionStore(nowMs = FakeClock())
        val s = store.open(dir.absolutePath, "a.mp4", 100)

        assertEquals("还没落盘时应为 0", 0L, s.received)
        writeBytes(s.partFile, 40)
        assertEquals("必须反映文件真实长度，而不是内存计数器", 40L, s.received)
    }

    @Test
    fun revivedSessionRecoversProgressFromFileAlone() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock()
        // 第一个 store 代表「core 重启前」
        val before = UploadSessionStore(nowMs = clock)
        val s = before.open(dir.absolutePath, "movie.part2.mkv", 1_000)
        writeBytes(s.partFile, 640, clock)

        // 第二个 store 代表「core 重启后」：内存表是空的，只剩磁盘上的 .ufipart
        val after = UploadSessionStore(nowMs = clock)
        assertEquals(0, after.size())
        val revived = after.findResumable(dir.absolutePath, "movie.part2.mkv", 1_000)
        assertNotNull("凭文件名就应该能恢复会话", revived)
        assertEquals("进度必须从文件长度恢复", 640L, revived!!.received)
        assertEquals("会话 id 必须沿用文件名里的那个", s.id, revived.id)
    }

    // ── 前提 2：文件名编解码可往返（含点的名字） ──

    @Test
    fun partNameRoundTripsWithDottedFileName() {
        val name = "my.video.2026.final.mp4"
        val encoded = UploadSessionStore.partNameOf(name, 123_456L, "abcDEF123_-")
        val meta = UploadSessionStore.parsePartName(encoded)
        assertNotNull(meta)
        assertEquals("名字里的点不能把切分弄错", name, meta!!.name)
        assertEquals(123_456L, meta.size)
        assertEquals("abcDEF123_-", meta.sessionId)
    }

    @Test
    fun parsePartNameRejectsForeignFiles() {
        // 普通用户文件不能被误认成分片（否则 /list 的清理会删用户数据）
        assertNull(UploadSessionStore.parsePartName("video.mp4"))
        assertNull(UploadSessionStore.parsePartName(".hidden"))
        assertNull(UploadSessionStore.parsePartName("a.b.ufipart")) // 缺 size 段
        assertNull(UploadSessionStore.parsePartName(".a.notanumber.sid.ufipart"))
    }

    // ── 前提 3：不同会话 → 不同 .ufipart（并发隔离） ──

    @Test
    fun concurrentSessionsForSameFileUseDistinctPartFiles() {
        val dir = tmp.newFolder("d")
        val store = UploadSessionStore(nowMs = FakeClock())
        val a = store.open(dir.absolutePath, "same.bin", 50)
        val b = store.open(dir.absolutePath, "same.bin", 50)

        assertFalse("两个会话共用一个 part 文件就会互相追加写坏", a.partFile.name == b.partFile.name)
    }

    // ── 前提 4：过期清理连文件一起删 ──

    @Test
    fun expiredSessionDeletesPartFile() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock()
        val store = UploadSessionStore(ttlMs = 1_000L, nowMs = clock)
        val s = store.open(dir.absolutePath, "x.bin", 10)
        writeBytes(s.partFile, 5)
        assertTrue(s.partFile.isFile)

        clock.now += 2_000L
        assertNull("超时后不应再取到会话", store.find(s.id))
        assertFalse("只摘会话不删文件就是在制造垃圾", s.partFile.exists())
    }

    @Test
    fun discardRemovesSessionAndFile() {
        val dir = tmp.newFolder("d")
        val store = UploadSessionStore(nowMs = FakeClock())
        val s = store.open(dir.absolutePath, "y.bin", 10)
        writeBytes(s.partFile, 3)

        store.discard(s.id)
        assertNull(store.find(s.id))
        assertFalse(s.partFile.exists())
    }

    // ── 前提 5：时钟回跳仍能过期 ──

    @Test
    fun sessionWithFutureTimestampIsTreatedAsExpired() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock(now = 10_000L)
        val store = UploadSessionStore(ttlMs = 1_000L, nowMs = clock)
        val s = store.open(dir.absolutePath, "z.bin", 10)

        // 系统时间被往回调：lastUsedAt 落在"未来"
        clock.now = 5_000L
        assertNull("now - future 是负数，不特判就永不过期", store.find(s.id))
    }

    @Test
    fun stalePartDetectionHandlesFutureMtime() {
        val f = tmp.newFile("a.bin")
        // mtime 在未来 → 判为过期，否则这个文件永远清不掉
        f.setLastModified(9_999L)
        assertTrue(UploadSessionStore.isStalePart(f, now = 1_000L, ttlMs = 5_000L))
    }

    @Test
    fun freshPartIsNotStale() {
        val f = tmp.newFile("b.bin")
        f.setLastModified(1_000L)
        assertFalse("正在传的分片 mtime 一直在刷新，不能被误删", UploadSessionStore.isStalePart(f, 2_000L, 5_000L))
        assertTrue("超过 ttl 才算过期", UploadSessionStore.isStalePart(f, 7_000L, 5_000L))
    }

    // ── 续传的匹配判据 ──

    @Test
    fun resumeRequiresBothNameAndSizeToMatch() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock()
        val store = UploadSessionStore(nowMs = clock)
        val s = store.open(dir.absolutePath, "clip.mp4", 900)
        writeBytes(s.partFile, 100, clock)

        val fresh = UploadSessionStore(nowMs = clock)
        assertNull("大小不同就是另一个文件，续传会拼出坏文件", fresh.findResumable(dir.absolutePath, "clip.mp4", 901))
        assertNull("名字不同不该续传", fresh.findResumable(dir.absolutePath, "other.mp4", 900))
        assertNotNull(fresh.findResumable(dir.absolutePath, "clip.mp4", 900))
    }

    @Test
    fun resumeSkipsAndDeletesStalePart() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock(now = 100_000L)
        val store = UploadSessionStore(ttlMs = 1_000L, nowMs = clock)
        val s = store.open(dir.absolutePath, "old.bin", 500)
        writeBytes(s.partFile, 200)
        // 把 mtime 推到很久以前，模拟"core 重启前留下的陈旧分片"
        s.partFile.setLastModified(1L)

        val fresh = UploadSessionStore(ttlMs = 1_000L, nowMs = clock)
        assertNull("陈旧分片不该被续传", fresh.findResumable(dir.absolutePath, "old.bin", 500))
        assertFalse("而且应该顺手删掉", s.partFile.exists())
    }

    // ── complete 的幂等 ──

    @Test
    fun completeIsIdempotentWithinWindow() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock()
        val store = UploadSessionStore(nowMs = clock)
        val s = store.open(dir.absolutePath, "ok.bin", 10)

        store.complete(s.id, "/sdcard/ok.bin", 10)
        assertNull("会话应已摘除", store.find(s.id))
        val again = store.findCompleted(s.id)
        assertNotNull("客户端重传 complete 时必须还能回同样的成功，否则是一次假失败", again)
        assertEquals("/sdcard/ok.bin", again!!.path)

        clock.now += UploadSessionStore.COMPLETED_TTL_MS + 1
        assertNull("窗口过后就该清掉，不能无界增长", store.findCompleted(s.id))
    }

    // ── 淘汰 ──

    @Test
    fun evictionDeletesOldestPartFile() {
        val dir = tmp.newFolder("d")
        val clock = FakeClock()
        val store = UploadSessionStore(maxEntries = 2, nowMs = clock)
        val first = store.open(dir.absolutePath, "1.bin", 10)
        writeBytes(first.partFile, 4)
        clock.now += 10
        store.open(dir.absolutePath, "2.bin", 10)
        clock.now += 10
        // 开第三个会触发淘汰最久未活动的那条
        store.open(dir.absolutePath, "3.bin", 10)

        assertNull(store.find(first.id))
        assertFalse("淘汰不删文件等于把淘汰变成制造垃圾", first.partFile.exists())
    }

    @Test
    fun nextIndexFollowsReceivedBytes() {
        val dir = tmp.newFolder("d")
        val store = UploadSessionStore(nowMs = FakeClock())
        val s = store.open(dir.absolutePath, "n.bin", 1_000)
        val chunk = 100L

        assertEquals(0L, s.nextIndex(chunk))
        writeBytes(s.partFile, 250)
        // 250 / 100 = 2 ⇒ 下一片是 index 2（第 0、1 片已满，第 2 片收了一半）
        assertEquals(2L, s.nextIndex(chunk))
    }
}
