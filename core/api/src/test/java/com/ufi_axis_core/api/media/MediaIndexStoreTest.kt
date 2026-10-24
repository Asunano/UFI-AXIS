package com.ufi_axis_core.api.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MediaIndexStore` 的契约测试（2026-10-13）。
 *
 * ## 这个测试真正要钉住的是什么
 * 不是"缓存能存能取"这种显然的事，而是四条**出错时症状很难归因**的边界：
 *  1. 命中路径**不得**再打 loader —— 这是"不要实时调用 android.process.media"的量化形式，
 *     用调用计数断言，而不是靠读代码相信它。
 *  2. "这一类不可用"（未授权 / 查询失败）与"这一类真的没有文件"必须**区分开**：
 *     前者不放 key（读路径据此走实时查询），后者放一个空列表。
 *     混为一谈的后果是客户端显示"这台设备没有媒体"——最误导人的一种失败。
 *  3. `invalidate()` 必须**作废在飞的那一轮**：否则删除文件时正在跑的那轮整库扫描
 *     会把刚删掉的文件写回快照，表现成"删了但还在，过一会儿又自己没了"。
 *  4. 「重新扫描」连点多次只能留下一组追赶刷新，不能叠成 N 组。
 *
 * ## 为什么用可注入的 TTL 而不是 sleep
 * 过期路径要验证，但 `SNAPSHOT_TTL_MS` 是 10 分钟。让测试 `Thread.sleep` 十分钟显然不行，
 * 所以 `MediaIndexStore` 的 TTL / 刷新周期做成了构造参数（生产装配用默认值）。
 */
class MediaIndexStoreTest {

    private val keys = listOf("video", "audio")

    /**
     * 可控 loader：按 kind 计数，可注入失败、可加闸门阻塞。
     *
     * 用"调用次数"作为断言对象是刻意的 —— 它直接对应"有没有跨进程打 MediaProvider"，
     * 比断言返回值更能锁住本次修复的意图。
     */
    private class FakeLoader {
        private val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** 这些 kind 返回 null（= 未授权 / 查询失败）。 */
        @Volatile
        var failKeys: Set<String> = emptySet()

        /** 非 null 时，每次 loader 调用都会在这里等待放行。 */
        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        /** 第一次 loader 调用时 complete（用来"等刷新真的进到 loader 里"）。 */
        @Volatile
        var started: CompletableDeferred<Unit>? = null

        suspend fun load(key: String): List<Map<String, Any?>>? {
            calls.merge(key, 1, Int::plus)
            started?.complete(Unit)
            gate?.await()
            if (key in failKeys) return null
            return listOf(mapOf("id" to 1L, "path" to "/sdcard/$key/a.mp3"))
        }

        fun count(key: String): Int = calls[key] ?: 0

        /** 所有 kind 的调用总次数 = 跨进程查询次数。 */
        fun total(): Int = calls.values.sum()
    }

    private fun storeWith(loader: FakeLoader, ttlMs: Long = MediaIndexStore.SNAPSHOT_TTL_MS) =
        MediaIndexStore(ttlMs = ttlMs).apply {
            attachLoader(keys) { loader.load(it) }
        }

    // ─────────────────── 命中路径：不再打 MediaProvider ───────────────────

    @Test
    fun snapshotOrLoad_coldStart_loadsOnce_thenServedFromMemory() = runTest {
        val loader = FakeLoader()
        val store = storeWith(loader)

        val first = store.snapshotOrLoad()
        assertNotNull("冷启动必须同步拉一次，否则首屏永远是空的", first)
        assertEquals("每个 kind 各拉一次", 1, loader.count("video"))
        assertEquals(1, loader.count("audio"))

        val second = store.snapshotOrLoad()
        assertSame("新鲜快照必须原样复用同一个对象", first, second)
        assertEquals(
            "第二次读不得再打 loader —— 这正是「不要实时调用 android.process.media」的量化断言",
            1, loader.count("video")
        )
        assertEquals(1, loader.count("audio"))

        store.stop()
    }

    @Test
    fun refresh_force_reloadsEvenWhenSnapshotIsFresh() = runTest {
        val loader = FakeLoader()
        val store = storeWith(loader)

        store.refresh(force = true)
        store.refresh(force = true)
        assertEquals("force 必须无视 TTL 重拉", 2, loader.count("video"))

        // 非 force 且已有新鲜快照 → 直接复用，不再打 loader
        store.refresh(force = false)
        assertEquals(2, loader.count("video"))

        store.stop()
    }

    @Test
    fun snapshotOrLoad_staleSnapshot_isReturnedImmediately_andRefreshedInBackground() =
        runBlocking {
            val loader = FakeLoader()
            val store = storeWith(loader, ttlMs = 50L)

            store.refresh(force = true)
            val stale = store.anySnapshot()!!
            delay(90L)  // 让它过期
            assertNull("过期后 freshSnapshot 应为 null", store.freshSnapshot())
            assertNotNull("但 anySnapshot 仍应给得出旧值", store.anySnapshot())

            val served = store.snapshotOrLoad()
            // 核心契约：有旧快照时**立刻**返回旧对象，不等一轮新查询（读永不阻塞在刷新上）
            assertSame("过期快照应被立刻返回，而不是等刷新", stale, served)

            delay(400L)  // 等后台补刷跑完
            assertTrue("后台补刷应已发生（loader 调用应超过冷启动那 2 次）", loader.total() > 2)
            assertNotSame(
                "补刷必须写入一份**新的**快照对象，而不是原样留着旧的。" +
                    "注意这里不能断言 freshSnapshot() != null —— 本用例的 TTL 只有 50ms，" +
                    "等到补刷完成时新快照自己又过期了（真实 TTL 是 10 分钟，不会这样）。",
                stale, store.anySnapshot()
            )

            store.stop()
        }

    // ─────────────────── "不可用" ≠ "空库" ───────────────────

    @Test
    fun refresh_emptyLibrary_stillCoversKind() = runTest {
        val store = MediaIndexStore()
        store.attachLoader(keys) { emptyList() }

        store.refresh(force = true)
        val snap = store.anySnapshot()!!

        assertTrue(
            "已授权但库里一个文件都没有 → 仍然**覆盖**这个 kind（放一个空列表）",
            snap.covers("video")
        )
        assertEquals(0, snap.of("video").size)

        store.stop()
    }

    @Test
    fun refresh_perKindFailure_withNoPrevious_leavesKindUncovered() = runTest {
        val loader = FakeLoader()
        loader.failKeys = setOf("audio")  // 一上来 audio 就不可用（未授权）
        val store = storeWith(loader)

        store.refresh(force = true)
        val snap = store.anySnapshot()
        assertNotNull("video 成功，所以整轮不算失败", snap)
        assertTrue(snap!!.covers("video"))
        assertFalse(
            "不可用的 kind 必须**不放 key**（而不是写空列表）——" +
                "写空会让客户端显示成「这台设备没有音乐」，读路径也就没法区分该不该走实时查询",
            snap.covers("audio")
        )

        store.stop()
    }

    @Test
    fun refresh_perKindFailure_withPrevious_keepsPreviousData() = runTest {
        val loader = FakeLoader()
        val store = storeWith(loader)

        store.refresh(force = true)
        assertTrue(store.anySnapshot()!!.covers("audio"))

        // audio 之后变得不可用（权限被撤）：应沿用上一份好数据，而不是抹成空
        loader.failKeys = setOf("audio")
        store.refresh(force = true)
        val snap = store.anySnapshot()!!
        assertTrue("应沿用上一份数据", snap.covers("audio"))
        assertEquals(1, snap.of("audio").size)

        store.stop()
    }

    @Test
    fun refresh_allKindsFail_keepsPreviousSnapshot() = runTest {
        val loader = FakeLoader()
        val store = storeWith(loader)

        store.refresh(force = true)
        val before = store.anySnapshot()!!

        loader.failKeys = keys.toSet()  // 全部失败
        store.refresh(force = true)

        assertSame("整轮全失败时必须保留旧快照，不能清空", before, store.anySnapshot())

        store.stop()
    }

    // ─────────────────── 竞态：invalidate 作废在飞的一轮 ───────────────────

    /**
     * 对应真实场景：`DELETE /api/files/delete` 删掉文件后调 `invalidate()`，
     * 而此刻正好有一轮整库扫描在飞 —— 它读到的是**删除前**的库。
     * 没有代次校验的话那轮回来会把删掉的文件写回快照，用户刷新列表又看到它。
     */
    @Test
    fun invalidate_duringInFlightRefresh_discardsThatRound() = runBlocking {
        val loader = FakeLoader()
        val store = storeWith(loader)

        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        loader.gate = gate
        loader.started = started

        val job = launch(Dispatchers.Default) { store.refresh(force = true) }
        started.await()          // 刷新已进到 loader 里（正在"扫描"）

        store.invalidate()       // 删除路径：作废在飞的一轮
        gate.complete(Unit)      // 放行那轮
        job.join()

        assertNull(
            "在飞那轮读到的是改动前的库，必须被丢弃 —— 否则刚删掉的文件会被写回快照，" +
                "表现成「删了但还在，过一会儿又自己没了」",
            store.anySnapshot()
        )

        store.stop()
    }

    // ─────────────────── 竞态：追赶刷新合并 ───────────────────

    @Test
    fun scheduleChaseRefresh_coalescesRapidRequests() = runBlocking {
        val loader = FakeLoader()
        val store = storeWith(loader)

        store.refresh(force = true)
        val baseline = loader.total()

        // 连点 5 次「重新扫描」：每次一组追赶（2 个延迟点）= 每次最多 2 轮 × 2 个 kind
        repeat(5) { store.scheduleChaseRefresh(listOf(60L, 60L)) }
        delay(700L)

        val added = loader.total() - baseline
        assertTrue(
            "连点 5 次只应留下一组追赶（最多 2 轮 × 2 kind = 4 次调用），实际新增 $added 次 —— " +
                "若没合并会跑出约 20 次，等于把「不要实时调用」的初衷又推翻",
            added <= 4
        )

        store.stop()
    }

    @Test
    fun stop_cancelsChaseRefresh() = runBlocking {
        val loader = FakeLoader()
        val store = storeWith(loader)

        store.refresh(force = true)
        val baseline = loader.total()

        store.scheduleChaseRefresh(listOf(200L, 200L))
        store.stop()   // 立刻停：追赶协程应被取消
        delay(500L)

        assertEquals("stop() 之后追赶刷新不得再跑", baseline, loader.total())
    }

    // ─────────────────── TTL 判定本身 ───────────────────

    @Test
    fun snapshot_isExpired_usesTtlBoundary() = runTest {
        val store = storeWith(FakeLoader())
        store.refresh(force = true)
        val snap = store.anySnapshot()!!

        assertFalse(snap.isExpired(snap.timestampMs, MediaIndexStore.SNAPSHOT_TTL_MS))
        assertFalse(snap.isExpired(snap.timestampMs + MediaIndexStore.SNAPSHOT_TTL_MS - 1))
        assertTrue(snap.isExpired(snap.timestampMs + MediaIndexStore.SNAPSHOT_TTL_MS))

        store.stop()
    }
}
