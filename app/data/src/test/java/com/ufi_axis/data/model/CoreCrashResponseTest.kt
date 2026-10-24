package com.ufi_axis.data.model

import com.ufi_axis.util.AppJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CoreCrashResponse` 的新旧兼容（2026-10-23）。
 *
 * 新增 `age_ms` / `recent` 后，**老 core 不带这两个字段**必须仍然能解析、且行为不收紧
 * （`recent` 默认 true）。这条坏了的表现是：升级 App 后所有崩溃提醒静默消失。
 */
class CoreCrashResponseTest {

    @Test
    fun `老 core 的响应缺少新字段时默认宽松`() {
        val raw = """
            {"success":true,"ok":true,"crashed":true,"timestamp":1700000000000,
             "summary":"FATAL CRASH in thread 'main': java.lang.IllegalStateException",
             "file":"/data/user/0/com.ufi_axis_core/files/logs/crash_1700000000000.txt",
             "uptime_ms":12345}
        """.trimIndent()

        val report = AppJson.decodeFromString<CoreCrashResponse>(raw)

        assertEquals(-1L, report.ageMs)
        assertTrue("老 core 没给判据时必须按宽松处理，否则提示会全部消失", report.recent)
        assertTrue(report.crashed)
    }

    @Test
    fun `新 core 的 age 与 recent 能解析`() {
        val raw = """
            {"success":true,"crashed":true,"timestamp":1700000000000,
             "summary":"boom","file":"/f","uptime_ms":12345,
             "age_ms":604800001,"recent":false}
        """.trimIndent()

        val report = AppJson.decodeFromString<CoreCrashResponse>(raw)

        assertEquals(604_800_001L, report.ageMs)
        assertFalse(report.recent)
    }
}
