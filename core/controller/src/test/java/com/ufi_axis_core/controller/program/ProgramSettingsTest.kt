package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 程序端口设置的纯值校验（2026-11-10）。
 *
 * 这三类错误都只能靠单测盯：值域写错、端口互斥漏判、覆盖值把清单默认顶掉 ——
 * 任何一个都会表现为"设置保存了，但程序绑在旧端口 / 根本起不来"。
 */
class ProgramSettingsTest {

    private val manifest = ProgramManifest(
        id = "demo",
        name = "Demo",
        artifact = ProgramManifest.Artifact(
            url = "https://example.com/demo.tar.gz",
            sha256 = "a".repeat(64)
        ),
        run = ProgramManifest.Run(internalPort = 5244, externalPort = 18080)
    )

    @Test
    fun `没有覆盖值时用清单默认端口`() {
        assertEquals(5244, ProgramSettings.internal(null, manifest))
        assertEquals(18080, ProgramSettings.external(null, manifest))
        val meta = ProgramInstalledMeta(id = "demo")
        assertEquals(5244, ProgramSettings.internal(meta, manifest))
        assertEquals(18080, ProgramSettings.external(meta, manifest))
    }

    @Test
    fun `覆盖值生效且越界值回落清单`() {
        val meta = ProgramInstalledMeta(id = "demo", internalPort = 19000, externalPort = 19001)
        assertEquals(19000, ProgramSettings.internal(meta, manifest))
        assertEquals(19001, ProgramSettings.external(meta, manifest))
        // 0 = 未设置；越界（如旧数据里写进 80）也按未设置处理，不能把非法端口带到 bind
        assertEquals(5244, ProgramSettings.internal(ProgramInstalledMeta(id = "demo", internalPort = 80), manifest))
        assertEquals(18080, ProgramSettings.external(ProgramInstalledMeta(id = "demo", externalPort = 70000), manifest))
    }

    @Test
    fun `直连模式不要求外部端口合法`() {
        assertNull(ProgramSettings.validate(19000, 0, "direct"))
        assertNull(ProgramSettings.validate(19000, 19000, "direct"))
    }

    @Test
    fun `网关模式要求外部端口合法且不等于内部端口`() {
        assertNull(ProgramSettings.validate(5244, 18080, "gateway"))
        assertEquals("外部端口不能与内部端口相同", ProgramSettings.validate(18080, 18080, "gateway"))
        assertEquals(
            "经后台转发时外部端口必须在 1024–65535",
            ProgramSettings.validate(5244, 0, "gateway")
        )
    }

    @Test
    fun `值域与访问方式校验`() {
        assertEquals("内部端口必须在 1024–65535", ProgramSettings.validate(80, 18080, "direct"))
        assertEquals("内部端口必须在 1024–65535", ProgramSettings.validate(70000, 18080, "direct"))
        assertEquals("访问方式只能是 direct / gateway", ProgramSettings.validate(5244, 18080, "proxy"))
    }
}
