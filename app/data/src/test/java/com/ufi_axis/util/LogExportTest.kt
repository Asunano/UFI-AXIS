package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 日志导出内容与文件名的单测（2026-10-30 M1）。
 *
 * 断言的是**排障时真正要读的东西**：版本、来源、筛选、条数、时间范围、行格式。
 * 这些字段写错不会崩，只会让拿到文件的人基于错误上下文排查 —— 所以逐字钉住。
 *
 * 固定时区与时钟，避免“机器在 UTC 时测试红”。
 */
class LogExportTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val exportedAt: Long =
        ZonedDateTime.of(2026, 10, 30, 20, 31, 29, 0, zone).toInstant().toEpochMilli()

    private fun entry(
        time: String,
        level: LogLevel,
        tag: String,
        message: String,
        seq: Long = 0,
        source: LogSource = LogSource.CORE,
    ) = LogEntry(
        seq = seq,
        time = time,
        source = source,
        level = level,
        kind = LogKind.of(tag),
        tag = tag,
        message = message,
    )

    private fun meta(
        entries: List<LogEntry> = listOf(
            entry("19:02:11.123", LogLevel.WARN, "NET/HTTP", "GET /api/x -> 500"),
            entry("20:29:03.456", LogLevel.WARN, "NET/HTTP", "GET /api/y -> 503"),
        ),
        source: LogSource = LogSource.CORE,
        kind: LogKind? = LogKind.NETWORK,
        level: LogLevel? = LogLevel.WARN,
        query: String = "timeout",
        isSelection: Boolean = false,
        model: String? = "ZTE MU300",
        baseUrl: String = "http://192.168.0.1:8088",
    ) = LogExportMeta(
        exportedAt = exportedAt,
        appVersionName = "1.2.3",
        source = source,
        deviceBaseUrl = baseUrl,
        deviceModel = model,
        kind = kind,
        level = level,
        query = query,
        entries = entries,
        isSelection = isSelection,
    )

    @Test
    fun `文件头包含排查所需的全部上下文`() {
        val text = buildLogExportText(meta(), zone)

        assertTrue(text.startsWith("# UFI-AXIS 日志导出\n"))
        assertTrue("导出时间必须带时区", text.contains("导出时间：2026-10-30 20:31:29 +08:00"))
        assertTrue(text.contains("App 版本：1.2.3"))
        assertTrue(text.contains("来源：设备 core"))
        assertTrue(text.contains("设备地址：http://192.168.0.1:8088"))
        assertTrue(text.contains("设备型号：ZTE MU300"))
        assertTrue(text.contains("筛选：类型=网络；级别=WARN；搜索=timeout"))
        assertTrue(text.contains("条数：2"))
        assertTrue("时间范围取首尾记录", text.contains("时间范围：19:02:11.123 ~ 20:29:03.456"))
        assertTrue(text.contains("行格式：HH:mm:ss.SSS [LEVEL] [tag] message"))
        assertTrue(text.contains("隐私提示："))
    }

    @Test
    fun `日志行与 LogEntry format 逐字一致`() {
        val text = buildLogExportText(meta(), zone)
        assertTrue(text.contains("19:02:11.123 [WARN] [NET/HTTP] GET /api/x -> 500"))
        assertTrue(text.contains("20:29:03.456 [WARN] [NET/HTTP] GET /api/y -> 503"))
        assertTrue("必须以换行结尾", text.endsWith("\n"))
    }

    @Test
    fun `设备型号与地址为空时省略对应行`() {
        val text = buildLogExportText(meta(model = null, baseUrl = ""), zone)
        assertFalse(text.contains("设备型号："))
        assertFalse(text.contains("设备地址："))
        // 其它字段不受影响
        assertTrue(text.contains("条数：2"))
    }

    @Test
    fun `空记录也能得到合法头部`() {
        val text = buildLogExportText(meta(entries = emptyList()), zone)
        assertTrue(text.contains("条数：0"))
        assertTrue(text.contains("时间范围：—"))
        // 分隔线之后不应有任何日志行
        val afterSeparator = text.substringAfter("────────────────────────────────────────\n", missingDelimiterValue = "")
        assertEquals("", afterSeparator)
    }

    @Test
    fun `手机侧来源与全部筛选的文案`() {
        val text = buildLogExportText(
            meta(
                source = LogSource.APP,
                kind = null,
                level = null,
                query = "",
                baseUrl = "",
                model = null,
            ),
            zone,
        )
        assertTrue(text.contains("来源：手机 APP"))
        assertTrue(text.contains("筛选：类型=全部；级别=全部；搜索=无"))
    }

    @Test
    fun `崩溃导出头部包含文件与时间且正文原样`() {
        val crashAt = exportedAt - 60_000
        val text = buildCrashExportText(
            fileName = "crash_123456789.log",
            fileModifiedAt = crashAt,
            exportedAt = exportedAt,
            appVersionName = "1.2.3",
            content = "java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)",
            zone = zone,
        )
        assertTrue(text.startsWith("# UFI-AXIS 崩溃日志导出\n"))
        assertTrue(text.contains("导出时间：2026-10-30 20:31:29 +08:00"))
        assertTrue(text.contains("崩溃写入时间：2026-10-30 20:30:29 +08:00"))
        assertTrue(text.contains("文件：crash_123456789.log"))
        assertTrue(text.contains("来源：手机 APP 崩溃 dump"))
        assertTrue("正文必须原样保留", text.endsWith("java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)"))
    }

    @Test
    fun `单文件导出头部标出截断状态`() {
        val full = buildLogFileExportText(
            fileName = "2026-10-30/app.log",
            exportedAt = exportedAt,
            appVersionName = "1.2.3",
            sourceLabel = "设备 core",
            content = "line-1\nline-2",
            truncated = false,
            zone = zone,
        )
        assertTrue(full.contains("文件：2026-10-30/app.log"))
        assertTrue(full.contains("内容范围：完整文件"))
        assertTrue(full.endsWith("line-1\nline-2"))

        val tail = buildLogFileExportText(
            fileName = "2026-10-30/app.log",
            exportedAt = exportedAt,
            appVersionName = "1.2.3",
            sourceLabel = "设备 core",
            content = "tail-only",
            truncated = true,
            zone = zone,
        )
        assertTrue(tail.contains("内容范围：仅文件尾部（单次读取上限）"))
    }

    @Test
    fun `打包 zip 含 README 与 logs 目录`() {
        val zip = buildLogBundleZip(
            sourceLabel = "设备 core",
            exportedAt = exportedAt,
            appVersionName = "1.2.3",
            files = listOf(
                "2026-10-30/app.log" to "a".toByteArray(),
                "crash/crash_1.txt" to "b".toByteArray(),
            ),
            zone = zone,
        )
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("README.txt", "logs/2026-10-30/app.log", "logs/crash/crash_1.txt"), entries.keys)
        val readme = entries.getValue("README.txt")
        assertTrue(readme.contains("文件数：2"))
        assertTrue(readme.contains("2026-10-30/app.log"))
        assertEquals("a", entries["logs/2026-10-30/app.log"])
        assertEquals("b", entries["logs/crash/crash_1.txt"])
    }

    @Test
    fun `打包文件名`() {
        assertEquals(
            "ufi-axis-logs_core_bundle_20261030-203129.zip",
            defaultLogBundleFileName(LogSource.CORE, exportedAt, zone),
        )
        assertEquals(
            "ufi-axis-logs_app_bundle_20261030-203129.zip",
            defaultLogBundleFileName(LogSource.APP, exportedAt, zone),
        )
    }

    @Test
    fun `文件名-多选导出`() {
        val name = defaultLogExportFileName(meta(isSelection = true), zone)
        assertEquals("ufi-axis-logs_core_selected-2_20261030-203129.txt", name)
    }

    @Test
    fun `文件名-筛选导出`() {
        assertEquals(
            "ufi-axis-logs_core_net-warn_20261030-203129.txt",
            defaultLogExportFileName(meta(), zone),
        )
        assertEquals(
            "ufi-axis-logs_app_all-all_20261030-203129.txt",
            defaultLogExportFileName(
                meta(source = LogSource.APP, kind = null, level = null),
                zone,
            ),
        )
    }

    @Test
    fun `文件名只含 ascii 安全字符`() {
        val name = defaultLogExportFileName(meta(isSelection = true), zone)
        assertTrue(name, Regex("^[a-z0-9._-]+$").matches(name))
    }
}
