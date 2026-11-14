package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位符展开（重点是 2026-11-05 新增的 `{CONTENT}`）。
 *
 * 为什么值得测：占位符是**清单与运行时之间唯一的参数通道**，展开错了不会编译失败，
 * 只会在真机上把字面量 `{CONTENT}` 当成路径传给程序 —— 表现为"目录名就叫 {CONTENT}"，
 * 而且要等到第一次写盘才看得见。
 */
class ProgramArgsTest {

    private fun env() = ProgramArgs.Env(
        dataDir = "/d",
        confDir = "/c",
        contentDir = "/sdcard/UFI-AXIS/content/demo",
        cacheDir = "/cache",
        binPath = "/b/demo",
        runDir = "/r",
        internalPort = 18100,
        externalPort = 18101,
        bindHost = "127.0.0.1",
    )

    @Test
    fun `CONTENT 展开成内容目录`() {
        assertEquals(
            "/sdcard/UFI-AXIS/content/demo/photos",
            ProgramArgs.expandOne("{CONTENT}/photos", env())
        )
    }

    @Test
    fun `未知占位符报错并列出可用项（含 CONTENT）`() {
        val error = runCatching { ProgramArgs.expandOne("{NOPE}", env()) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("必须点名未知占位符：$error", error.contains("{NOPE}"))
        assertTrue("可用项里要有 CONTENT：$error", error.contains("{CONTENT}"))
    }

    @Test
    fun `BIND 按访问方式展开`() {
        assertEquals("127.0.0.1:18100", ProgramArgs.expandOne("{BIND}:{INTERNAL_PORT}", env()))
        assertEquals(
            "0.0.0.0:18100",
            ProgramArgs.expandOne("{BIND}:{INTERNAL_PORT}", env().copy(bindHost = "0.0.0.0")),
        )
    }

    @Test
    fun `多个占位符可以混用`() {
        assertEquals(
            "/d:/c:/sdcard/UFI-AXIS/content/demo",
            ProgramArgs.expandOne("{DATA}:{CONF}:{CONTENT}", env())
        )
    }
}
