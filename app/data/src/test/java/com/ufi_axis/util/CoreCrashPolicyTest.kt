package com.ufi_axis.util

import com.ufi_axis.data.model.CoreCrashResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core 崩溃提醒的展示判据（2026-10-23）。
 *
 * 这一组用例守着「三个月前的旧崩溃不再每次启动弹一次」：
 * - `crashed` 对旧记录恒为 true，必须靠 `recent` 挡掉；
 * - 本地已提示过的同一时间戳不再提示；
 * - 老 core 没有 `recent` 字段时不能把提示全吞掉（默认按宽松处理）。
 */
class CoreCrashPolicyTest {

    private val crashAt = 1_700_000_000_000L

    private fun report(
        crashed: Boolean = true,
        timestamp: Long = crashAt,
        ageMs: Long = 0L,
        recent: Boolean = true
    ) = CoreCrashResponse(
        crashed = crashed,
        timestamp = timestamp,
        ageMs = ageMs,
        recent = recent
    )

    @Test
    fun `没崩过不提示`() {
        assertFalse(CoreCrashPolicy.shouldPrompt(report(crashed = false), shownAt = 0L))
    }

    @Test
    fun `时间戳无效不提示`() {
        assertFalse(CoreCrashPolicy.shouldPrompt(report(timestamp = 0L), shownAt = 0L))
    }

    @Test
    fun `窗口内的崩溃提示一次`() {
        assertTrue(CoreCrashPolicy.shouldPrompt(report(ageMs = 60_000L), shownAt = 0L))
    }

    @Test
    fun `同一次崩溃已提示过就不再提示`() {
        assertFalse(CoreCrashPolicy.shouldPrompt(report(), shownAt = crashAt))
    }

    @Test
    fun `core 判定超过窗口的旧崩溃不提示`() {
        assertFalse(
            CoreCrashPolicy.shouldPrompt(
                report(ageMs = 90L * 24 * 60 * 60 * 1000, recent = false),
                shownAt = 0L
            )
        )
    }

    @Test
    fun `老 core 不带 recent 字段时保持原有行为`() {
        // age_ms / recent 都是默认值：-1 / true —— 不能让升级 App 后一条提示都看不到。
        assertTrue(
            CoreCrashPolicy.shouldPrompt(
                CoreCrashResponse(crashed = true, timestamp = crashAt),
                shownAt = 0L
            )
        )
    }

    @Test
    fun `老 core 已提示过的仍按时间戳去重`() {
        assertFalse(
            CoreCrashPolicy.shouldPrompt(
                CoreCrashResponse(crashed = true, timestamp = crashAt),
                shownAt = crashAt
            )
        )
    }
}
