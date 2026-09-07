package com.ufi_axis_core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PairedDeviceStore 单元测试（阶段 2）。
 *
 * 覆盖：旧 pairedFingerprints 迁移 / 读写持久化 / createdAt 沿用 / rename / remove /
 * 文件损坏兜底 / 空设备名回退指纹。
 *
 * 2026-08-24 新增（hwId 去重，修复「清除应用数据后重新配对导致设备列表无限增长」）：
 * hwId 持久化与 findByHwId 命中 / 空 hwId 不参与匹配 / 空 hwId 不覆盖已有值 /
 * rename 保留 hwId / 旧版 JSON 缺字段反序列化为空串（向后兼容）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairedDeviceStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // 每个用例独立 Robolectric 环境，filesDir 隔离
        context = ApplicationProvider.getApplicationContext()
        AppSettings(context).resetAll()
    }

    private fun settings(): AppSettings = AppSettings(context).apply {
        resetAll()
    }

    @Test
    fun `migrates legacy pairedFingerprints into records when file missing`() {
        val s = settings()
        s.pairedFingerprints = listOf("fp-1", "fp-2")
        s.pairedAt = 1700000000000L

        val store = PairedDeviceStore(context, s)

        val records = store.list()
        assertEquals(2, records.size)
        assertEquals("fp-1", records[0].fingerprint)
        assertEquals("fp-1", records[0].deviceName)
        assertEquals(1700000000000L, records[0].createdAt)
        assertEquals(1700000000000L, records[0].lastSeen)
        assertFalse(store.isEmpty())
    }

    @Test
    fun `upsert persists and reloads from file`() {
        val s = settings()
        val store = PairedDeviceStore(context, s)
        store.upsert("fp-1", "Device One", 1000L)

        val reloaded = PairedDeviceStore(context, s)
        val records = reloaded.list()
        assertEquals(1, records.size)
        assertEquals("fp-1", records[0].fingerprint)
        assertEquals("Device One", records[0].deviceName)
        assertEquals(1000L, records[0].createdAt)
        assertEquals(1000L, records[0].lastSeen)
    }

    @Test
    fun `upsert preserves createdAt on refresh`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L)
        store.upsert("fp-1", "Device One Renamed", 2000L)

        val records = store.list()
        assertEquals(1, records.size)
        assertEquals(1000L, records[0].createdAt)
        assertEquals(2000L, records[0].lastSeen)
        assertEquals("Device One Renamed", records[0].deviceName)
    }

    @Test
    fun `rename updates device name and returns false for missing device`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L)

        assertTrue(store.rename("fp-1", "Renamed"))
        assertEquals("Renamed", store.list().first().deviceName)
        assertFalse(store.rename("missing", "X"))
    }

    @Test
    fun `remove deletes record and updates fingerprints`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L)
        store.upsert("fp-2", "Device Two", 2000L)

        assertTrue(store.remove("fp-1"))
        assertFalse(store.remove("fp-1"))
        assertEquals(listOf("fp-2"), store.fingerprints())
    }

    @Test
    fun `clear empties records`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L)
        store.clear()
        assertTrue(store.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `corrupted file falls back to empty list`() {
        val s = settings()
        File(context.filesDir, "paired_devices.json").writeText("{ not valid json !!!")

        val store = PairedDeviceStore(context, s)
        assertTrue(store.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `empty deviceName falls back to fingerprint`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "", 1000L)
        assertEquals("fp-1", store.list().first().deviceName)
    }

    // ────────────────────────────────────────────────
    // 2026-08-24 修复「清除应用数据后重新配对导致设备列表无限增长」：hwId 去重
    // ────────────────────────────────────────────────

    @Test
    fun `upsert persists hwId and findByHwId locates the record`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L, "H1")

        val found = store.findByHwId("H1")
        assertNotNull("hwId=H1 应能命中记录", found)
        assertEquals("fp-1", found!!.fingerprint)
        assertEquals("H1", found.hwId)

        // 落盘 + 重新加载后 hwId 仍在（向后兼容字段需 encodeDefaults=true）
        val reloaded = PairedDeviceStore(context, settings())
        assertEquals("H1", reloaded.findByHwId("H1")?.hwId)
    }

    @Test
    fun `findByHwId ignores blank hwId to avoid merging legacy records`() {
        val store = PairedDeviceStore(context, settings())
        // 历史遗留记录：部署本特性前配对，hwId 为空串
        store.upsert("legacy-1", "Legacy One", 1000L)
        store.upsert("legacy-2", "Legacy Two", 2000L)

        assertNull("空 hwId 不得匹配任何记录", store.findByHwId(""))
        assertNull("空白 hwId 不得匹配任何记录", store.findByHwId("   "))
        assertNull("未知 hwId 无匹配", store.findByHwId("H-unknown"))
        assertEquals("两条历史记录都不应被误合并", 2, store.list().size)
    }

    @Test
    fun `upsert with blank hwId preserves existing hwId`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L, "H1")
        // 旧版客户端登录（未上报 device_hwid）不应把已知硬件标识抹成空
        store.upsert("fp-1", "Device One", 2000L, "")

        assertEquals("H1", store.list().first().hwId)
        assertEquals("fp-1", store.findByHwId("H1")?.fingerprint)
    }

    @Test
    fun `rename keeps hwId intact`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L, "H1")
        assertTrue(store.rename("fp-1", "Renamed"))

        val record = store.list().first()
        assertEquals("Renamed", record.deviceName)
        assertEquals("H1", record.hwId)
    }

    @Test
    fun `legacy json without hwId field decodes to empty hwId`() {
        val s = settings()
        // 模拟旧版 paired_devices.json（无 hwId 字段）→ ignoreUnknownKeys + 默认值兜底
        File(context.filesDir, "paired_devices.json").writeText(
            """[{"fingerprint":"fp-old","deviceName":"Old","lastSeen":1000,"createdAt":1000}]"""
        )

        val store = PairedDeviceStore(context, s)
        val records = store.list()
        assertEquals(1, records.size)
        assertEquals("fp-old", records[0].fingerprint)
        assertEquals("", records[0].hwId)
        assertNull("旧记录 hwId 为空，不参与去重", store.findByHwId(""))
    }
}
