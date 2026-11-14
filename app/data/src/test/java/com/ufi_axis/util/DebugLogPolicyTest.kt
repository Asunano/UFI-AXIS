package com.ufi_axis.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志门禁的纯函数单测（2026-10-30 F1）。
 *
 * 为什么单测一个私有门禁：`DebugLog.emit` 会调 `android.util.Log`，JVM 单测一碰就抛
 * `Method not mocked`，于是“总开关关了还记不记 WARN”这件事在测试里一直没被钉住。
 * 现在把判定抽成 [DebugLog.shouldEmit]，与 core `AppLogger.shouldEmit` 逐条对齐。
 */
class DebugLogPolicyTest {

    @Test
    fun `总开关关闭时 WARN 与 ERROR 仍记录`() {
        assertTrue(DebugLog.shouldEmit(LogLevel.WARN, masterEnabled = false, appEnabled = true))
        assertTrue(DebugLog.shouldEmit(LogLevel.ERROR, masterEnabled = false, appEnabled = true))
    }

    @Test
    fun `总开关关闭时 INFO 与 DEBUG 不记录`() {
        assertFalse(DebugLog.shouldEmit(LogLevel.INFO, masterEnabled = false, appEnabled = true))
        assertFalse(DebugLog.shouldEmit(LogLevel.DEBUG, masterEnabled = false, appEnabled = true))
    }

    @Test
    fun `app 子开关关闭时连错误也不记`() {
        assertFalse(DebugLog.shouldEmit(LogLevel.ERROR, masterEnabled = true, appEnabled = false))
        assertFalse(DebugLog.shouldEmit(LogLevel.WARN, masterEnabled = true, appEnabled = false))
    }

    @Test
    fun `总开关打开时四个级别都记录`() {
        LogLevel.entries.forEach { level ->
            assertTrue(level.name, DebugLog.shouldEmit(level, masterEnabled = true, appEnabled = true))
        }
    }
}
