package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LogEntry.parseCoreLine] 单测。
 *
 * core 侧行格式由 `AppLogger.log()` 固定为 `HH:mm:ss.SSS [LEVEL] [TAG] message`，
 * 且**同时被 web 端解析**（`web/src/views/settings/SettingsView.vue`）。这里锁住解析行为，
 * 防止后续改 core 日志格式时 app 端静默退化成「整行塞进 message」。
 */
class LogEntryParseTest {

    @Test
    fun `parses standard core line`() {
        val e = LogEntry.parseCoreLine("12:13:26.321 [DEBUG] [AuthMiddleware] GET /api/system/cpu", 0)
        assertEquals("12:13:26.321", e.time)
        assertEquals(LogLevel.DEBUG, e.level)
        assertEquals("AuthMiddleware", e.tag)
        assertEquals("GET /api/system/cpu", e.message)
        assertEquals(LogSource.CORE, e.source)
        assertEquals(LogKind.RUNTIME, e.kind)
    }

    @Test
    fun `NET tag prefix is classified as network`() {
        val e = LogEntry.parseCoreLine("12:13:26.321 [INFO] [NET/HTTP] GET /api/x → 200 5ms", 1)
        assertEquals(LogKind.NETWORK, e.kind)
        assertEquals("NET/HTTP", e.tag)
    }

    @Test
    fun `bare NET tag is network but NETWORKISH is not`() {
        assertEquals(
            LogKind.NETWORK,
            LogEntry.parseCoreLine("12:13:26.321 [INFO] [NET] x", 2).kind
        )
        // 只认精确的 NET 或 NET/ 前缀，避免把 NETWORKMANAGER 之类的业务 tag 误判成网络日志
        assertEquals(
            LogKind.RUNTIME,
            LogEntry.parseCoreLine("12:13:26.321 [INFO] [NETWORKMANAGER] x", 3).kind
        )
    }

    @Test
    fun `multiline message such as stack trace is kept intact`() {
        val raw = "12:13:26.321 [ERROR] [StatusPages] boom\n\tat a.b.C.d(C.kt:1)"
        val e = LogEntry.parseCoreLine(raw, 4)
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("boom\n\tat a.b.C.d(C.kt:1)", e.message)
    }

    @Test
    fun `unparsable line falls back to whole-line message`() {
        val e = LogEntry.parseCoreLine("something [ERROR] not our format", 5)
        assertEquals("", e.time)
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("-", e.tag)
        assertEquals("something [ERROR] not our format", e.message)
    }

    @Test
    fun `format round-trips the core line`() {
        val raw = "12:13:26.321 [WARN] [Tools] something odd"
        assertEquals(raw, LogEntry.parseCoreLine(raw, 6).format())
    }
}
