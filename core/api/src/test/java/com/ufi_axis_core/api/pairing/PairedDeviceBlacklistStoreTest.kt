package com.ufi_axis_core.api.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配对设备黑名单 store 的单元测试（2026-10-15）。
 *
 * 重点守三类行为，它们各自对应一个"看起来能用、实际会出事"的坑：
 * 1. **两条键都要生效**：只按指纹 → 清 App 数据即绕过；只按 hwId → 改包即绕过；
 * 2. **空 hwId 不参与判定**：Web 端恒为空串，若把空串当键，一台 Web 被拉黑会连坐所有 Web；
 * 3. **幂等 + 不改变拉黑时间**：UI 上重复点"拉黑"不该让"拉黑时间"一直往后跳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairedDeviceBlacklistStoreTest {

    private lateinit var context: Context
    private lateinit var store: PairedDeviceBlacklistStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // prefs 是进程级共享的：每个用例先清干净，避免互相污染
        context.getSharedPreferences("paired_blacklist", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = PairedDeviceBlacklistStore(context)
    }

    private fun device(fp: String, hwId: String = "", name: String = "设备 $fp") = BlockedDevice(
        fingerprint = fp,
        hwId = hwId,
        name = name,
        reason = "",
        blockedAt = System.currentTimeMillis(),
        blockedBy = "self"
    )

    @Test
    fun `空名单不拦任何设备`() {
        assertFalse(store.isBlocked("fp-a"))
        assertFalse(store.isBlocked("fp-a", "hw-a"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `按指纹命中`() = runBlocking {
        store.block(device("fp-a", "hw-a"))
        assertTrue("指纹应命中", store.isBlocked("fp-a"))
        assertTrue("指纹 + hwId 也应命中", store.isBlocked("fp-a", "hw-a"))
        assertFalse("别的指纹不受影响", store.isBlocked("fp-b"))
    }

    @Test
    fun `按 hwId 命中 —— 清 App 数据换了密钥也拦得住`() = runBlocking {
        store.block(device("fp-old", "hw-same"))
        // 清数据后：新指纹、同 hwId
        assertTrue("同 hwId 的新指纹应命中", store.isBlocked("fp-new", "hw-same"))
    }

    @Test
    fun `空 hwId 不参与判定 —— 一台 Web 被拉黑不该连坐其他 Web`() = runBlocking {
        store.block(device("fp-web", hwId = ""))
        assertTrue("被拉黑的那台 Web 仍按指纹命中", store.isBlocked("fp-web", ""))
        assertFalse(
            "另一台没有 hwId 的 Web 不该被连坐",
            store.isBlocked("fp-other-web", "")
        )
        assertFalse("传 null 同理", store.isBlocked("fp-other-web", null))
    }

    @Test
    fun `重复拉黑是幂等的且不改变拉黑时间`() = runBlocking {
        val first = device("fp-a", "hw-a", name = "旧名字")
        assertTrue("首次应新增", store.block(first))
        val blockedAtBefore = store.entries().single().blockedAt

        val again = first.copy(name = "新名字", blockedAt = blockedAtBefore + 60_000)
        assertFalse("重复拉黑应返回 false", store.block(again))

        val entry = store.entries().single()
        assertEquals("仍只有一条", 1, store.entries().size)
        assertEquals("名称应刷新", "新名字", entry.name)
        assertEquals("拉黑时间不应被改写", blockedAtBefore, entry.blockedAt)
    }

    @Test
    fun `达到上限后拒绝新增`() = runBlocking {
        repeat(PairedDeviceBlacklistStore.MAX_ENTRIES) { i ->
            store.block(device("fp-$i", "hw-$i"))
        }
        assertTrue("应已满", store.isFull())
        val threw = runCatching { store.block(device("fp-overflow", "hw-overflow")) }.isFailure
        assertTrue("超限应抛 IllegalStateException（由上层映射成 409）", threw)
        assertEquals(PairedDeviceBlacklistStore.MAX_ENTRIES, store.entries().size)
    }

    @Test
    fun `解除单条与全部解除`() = runBlocking {
        store.block(device("fp-a", "hw-a"))
        store.block(device("fp-b", "hw-b"))

        assertTrue("解除存在的条目应返回 true", store.unblock("fp-a"))
        assertFalse("再解除一次应返回 false", store.unblock("fp-a"))
        assertFalse("被解除后不再命中", store.isBlocked("fp-a", "hw-a"))
        assertTrue("另一条不受影响", store.isBlocked("fp-b", "hw-b"))

        assertEquals("全部解除应返回条数", 1, store.clear())
        assertFalse(store.isBlocked("fp-b", "hw-b"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `落盘后可被新实例读到`() = runBlocking {
        store.block(device("fp-a", "hw-a", name = "客厅手机"))
        val reopened = PairedDeviceBlacklistStore(context)
        val entry = reopened.entries().single()
        assertEquals("fp-a", entry.fingerprint)
        assertEquals("hw-a", entry.hwId)
        assertEquals("客厅手机", entry.name)
        assertTrue("新实例也应命中", reopened.isBlocked("fp-a"))
    }

    @Test
    fun `列表按拉黑时间倒序`() = runBlocking {
        val now = System.currentTimeMillis()
        store.block(device("fp-old").copy(blockedAt = now - 10_000))
        store.block(device("fp-new").copy(blockedAt = now))
        assertEquals(listOf("fp-new", "fp-old"), store.entries().map { it.fingerprint })
    }
}
