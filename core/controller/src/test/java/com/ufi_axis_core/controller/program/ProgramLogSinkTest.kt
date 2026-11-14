package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `log_quota_mb` 的折算口径（[ProgramLogSink.perFileQuotaBytes]）。
 *
 * 这条以前是错的：把"每程序总量"直接当成"每文件上限"用，`32` 实际能占到 192MB
 * （32 × 3 份滚动 × 2 路流）。账面对不上的后果不是崩溃，而是**用户的磁盘被悄悄吃掉**，
 * 而且清单里写得明明白白 32MB —— 这类"数字说谎"的 bug 只能靠断言钉住。
 */
class ProgramLogSinkTest {

    @Test
    fun `32MB 总量折到单文件后总量不超过 32MB`() {
        val perFile = ProgramLogSink.perFileQuotaBytes(32)
        val worst = perFile * 2 * ProgramLogSink.MAX_FILES
        assertTrue(
            "最坏情况 ${worst / 1024 / 1024}MB 不能超过清单声明的 32MB",
            worst <= 32L * 1024 * 1024
        )
        // 32MB / 6 = 5.33MB
        assertEquals(5_592_405L, perFile)
    }

    @Test
    fun `配额给小了也不会退化成零`() {
        val perFile = ProgramLogSink.perFileQuotaBytes(1)
        assertEquals("低于 1MB 时统一按 1MB 兜底", 1024L * 1024L, perFile)
        assertEquals(0, ProgramLogSink.perFileQuotaBytes(0).compareTo(1024L * 1024L))
    }

    @Test
    fun `参数退化时不除零，且总量仍守得住`() {
        // streams/maxFiles 传 0 会被夹到 1：槽位 = 1，单文件配额 = 总量 32MB，
        // 最坏占用仍然是 32MB —— 这才是"不除零"的正确退化，不是退化成 1MB。
        val perFile = ProgramLogSink.perFileQuotaBytes(32, streams = 0, maxFiles = 0)
        assertEquals(32L * 1024 * 1024, perFile)
    }
}
