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
 * 文件损坏与恢复 / 空设备名回退指纹。
 *
 * 2026-08-24 新增（hwId 去重，修复「清除应用数据后重新配对导致设备列表无限增长」）：
 * hwId 持久化与 findByHwId 命中 / 空 hwId 不参与匹配 / 空 hwId 不覆盖已有值 /
 * rename 保留 hwId / 旧版 JSON 缺字段反序列化为空串（向后兼容）。
 *
 * 2026-09-08 改写（Core 重启后手机被踢下线、要求重新输密码）：
 * 原来这里钉的是「文件损坏 → 按空列表兜底」，而那正是事故的放大器 ——
 * 空列表 = 零配对设备 = 所有客户端被判未配对并清空本地 token。
 * 现在钉的是新契约：落盘原子（无 .tmp 残留 + 旧内容留成 .bak）、
 * 损坏先从 .bak 恢复、连 .bak 也读不出来时挪走坏文件并置 degraded。
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

    /**
     * 2026-09-08 事故后的落盘契约：写 `.tmp` → fsync → 把旧内容留成 `.bak` → 原子改名。
     * 这条盯的是"临时文件不许残留、上一份好内容必须留得下来" ——
     * `.bak` 是真文件读不出来时唯一的恢复源，丢了它就只能全员重新配对。
     */
    @Test
    fun `save is atomic - no tmp leftover and previous content kept as bak`() {
        val store = PairedDeviceStore(context, settings())
        store.upsert("fp-1", "Device One", 1000L)   // 首次落盘：还没有旧内容可备份
        store.upsert("fp-2", "Device Two", 2000L)   // 第二次：上一份内容进 .bak

        assertFalse(
            "临时文件必须已被 rename 掉，残留说明替换没走完",
            File(context.filesDir, "paired_devices.json.tmp").exists()
        )
        val bak = File(context.filesDir, "paired_devices.json.bak")
        assertTrue(".bak 应保留上一次落盘的内容", bak.exists())
        val bakText = bak.readText()
        assertTrue(".bak 里应是上一份内容（只有 fp-1）", bakText.contains("fp-1"))
        assertFalse(".bak 不该包含本次新写入的 fp-2", bakText.contains("fp-2"))
    }

    /**
     * 损坏时的第一顺位恢复源是 `.bak`。
     *
     * 2026-09-08 事故：这里原来的契约是"损坏 → 按空列表兜底"，于是一次半截 JSON
     * 就等于零配对设备 → 所有客户端被判未配对 → 清空 token → 重新输密码。
     * 现在必须先尝试 `.bak`，且恢复出来的**凭据字段要完整**（空壳记录过不了鉴权）。
     */
    @Test
    fun `corrupted file falls back to bak without degrading`() {
        val s = settings()
        File(context.filesDir, "paired_devices.json.bak").writeText(
            """[{"fingerprint":"fp-1","deviceName":"Device One","lastSeen":1000,"createdAt":1000,"hwId":"H1","pubKey":"PK","tokenHash":"TH"}]"""
        )
        File(context.filesDir, "paired_devices.json").writeText("{ not valid json !!!")

        val store = PairedDeviceStore(context, s)

        assertFalse("从 .bak 恢复成功不算降级", store.degraded)
        assertEquals(listOf("fp-1"), store.fingerprints())
        assertEquals("PK", store.list().first().pubKey)
        assertNotNull("凭据必须原样恢复，否则记录只是个空壳", store.findByTokenHash("TH"))
    }

    /**
     * 真文件与 `.bak` 都读不出来：不许静默当空存储，要挪走坏文件并置降级位。
     *
     * 降级位的用途见 `AuthMiddleware`：这种情况回「你没配对」（444）是撒谎，
     * 客户端会照约定销毁本地凭据；正确答案是「存储不可用、可重试」（503）。
     */
    @Test
    fun `unreadable store without bak is moved aside and marks degraded`() {
        val s = settings()
        // 旧指纹列表还在：也不许拿它伪造记录（迁移出来的 pubKey/tokenHash 是空串，鉴权照样过不了）
        s.pairedFingerprints = listOf("fp-legacy")
        File(context.filesDir, "paired_devices.json").writeText("{ not valid json !!!")

        val store = PairedDeviceStore(context, s)

        assertTrue("存在但读不出来的存储 → 降级态", store.degraded)
        assertTrue("降级时不得凭旧指纹伪造记录", store.list().isEmpty())
        assertFalse(
            "坏文件必须被挪走，否则会被下一次落盘悄悄覆盖掉证据",
            File(context.filesDir, "paired_devices.json").exists()
        )
        assertTrue(
            "坏文件应保留为 .corrupt 备查",
            File(context.filesDir, "paired_devices.json.corrupt").exists()
        )
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
