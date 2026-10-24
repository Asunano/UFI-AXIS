package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机下载历史反序列化（2026-10-24）。
 *
 * 回归点：`source` 必须被读回。原实现漏读它，导致所有记录在 `loadPhoneDownloadHistory`
 * 的 "只保留 audio" 过滤里被丢掉并回写空数组 —— 用户看到的是"重启后历史被清空"。
 */
class PhoneDownloadHistoryCodecTest {

    @Test
    fun `source 字段必须被读回`() {
        val json = """
            [
              { "fileName": "a.mp3", "fileSize": 123, "sourcePath": "/m/a.mp3",
                "downloadedAt": 1700000000000, "status": "completed", "source": "audio" }
            ]
        """.trimIndent()

        val items = decodePhoneDownloadHistory(json)

        assertEquals(1, items.size)
        assertEquals("audio", items.single().source)
        assertEquals("a.mp3", items.single().fileName)
        assertEquals(123L, items.single().fileSize)
        assertEquals("/m/a.mp3", items.single().sourcePath)
        assertEquals(1700000000000L, items.single().downloadedAt)
        assertEquals("completed", items.single().status)
    }

    @Test
    fun `旧记录没有 source 时回落空串`() {
        val json = """
            [
              { "fileName": "old.mp3", "fileSize": 1, "sourcePath": "/m/old.mp3",
                "downloadedAt": 1, "status": "completed" }
            ]
        """.trimIndent()

        val items = decodePhoneDownloadHistory(json)

        assertEquals("", items.single().source)
        // 老记录按既有设计不进入音乐下载记录（没有来源就无法判断归属）。
        assertTrue(items.filter { it.source == "audio" }.isEmpty())
    }

    @Test
    fun `audio 记录不会被来源过滤误删`() {
        val json = """
            [
              { "fileName": "a.mp3", "fileSize": 1, "sourcePath": "/m/a.mp3",
                "downloadedAt": 1, "status": "completed", "source": "audio" },
              { "fileName": "b.jpg", "fileSize": 1, "sourcePath": "/m/b.jpg",
                "downloadedAt": 2, "status": "completed", "source": "image" }
            ]
        """.trimIndent()

        val kept = decodePhoneDownloadHistory(json).filter { it.source == "audio" }

        assertEquals("image 记录仍应被过滤，audio 不能", 1, kept.size)
        assertEquals("a.mp3", kept.single().fileName)
    }
}
