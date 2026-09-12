package com.ufi_axis.ui.screens

import com.ufi_axis.data.model.NotifyLevelOptionDto
import com.ufi_axis.data.model.SmsForwardConfig
import com.ufi_axis.data.model.WebhookConfigResponse
import com.ufi_axis.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三条渠道 `/config` 里 `levels` 的解析与渲染。
 *
 * 钉死两件事：
 * 1. **新形状**（`[{"name":"info","label":"提示"}]`）能解出中文名 —— 那是 core 2026-09-11 起的契约；
 * 2. **老形状**（`["info","warning"]`）不许抛。这一条是回归防线：`levels` 声明成
 *    `List<NotifyLevelOptionDto>` 而不做版本容错时，旧固件会让 kotlinx 抛
 *    `JsonDecodingException`（`ignoreUnknownKeys` 管不了类型不匹配），表现是**整个渠道页读不出配置**。
 *
 * 用 [AppJson] 而不是新建 Json：容错必须在**实际生效的那份**配置下成立
 * （`coerceInputValues` 等选项会影响解析路径）。
 */
class NotifyLevelsContractTest {

    @Test
    fun `新形状：对象数组解出 wire 名与中文名`() {
        val json = """
            {"levels":[{"name":"info","label":"提示"},{"name":"critical","label":"严重"}]}
        """.trimIndent()
        val cfg = AppJson.decodeFromString(WebhookConfigResponse.serializer(), json)

        assertEquals(
            listOf(
                NotifyLevelOptionDto(name = "info", label = "提示"),
                NotifyLevelOptionDto(name = "critical", label = "严重")
            ),
            cfg.levels
        )
        assertEquals("提示", notifyLevelLabel(cfg.levels, "info"))
    }

    @Test
    fun `老形状：字符串数组不抛，退化成只有 wire 名`() {
        val json = """{"levels":["info","warning","critical"]}"""
        val cfg = AppJson.decodeFromString(WebhookConfigResponse.serializer(), json)

        assertEquals(listOf("info", "warning", "critical"), cfg.levels.map { it.name })
        assertTrue("老固件没有 label，必须是空串而不是编一个", cfg.levels.all { it.label.isEmpty() })
        // 拿不到中文名时原样显示 wire 名：至少还能对着代码查，显示"未知"就断了线索。
        assertEquals("warning", notifyLevelLabel(cfg.levels, "warning"))
    }

    @Test
    fun `缺字段：老固件完全不回 levels 时是空表`() {
        val cfg = AppJson.decodeFromString(WebhookConfigResponse.serializer(), "{}")
        assertTrue(cfg.levels.isEmpty())
        assertEquals("info", notifyLevelLabel(cfg.levels, "info"))
    }

    @Test
    fun `邮件渠道的 levels 与 sendable 走同一套形状`() {
        val json = """
            {"enabled":true,"sendable":true,"levels":[{"name":"info","label":"提示"}]}
        """.trimIndent()
        val cfg = AppJson.decodeFromString(SmsForwardConfig.serializer(), json)

        assertTrue(cfg.sendable)
        assertEquals("提示", notifyLevelLabel(cfg.levels, "info"))
    }

    /** 老固件不回 `sendable` 时必须落到 false —— 宁可说"配置不完整"，不许承诺发不出去的事。 */
    @Test
    fun `邮件渠道缺 sendable 时默认不可发`() {
        val cfg = AppJson.decodeFromString(SmsForwardConfig.serializer(), """{"enabled":true}""")
        assertTrue(!cfg.sendable)
    }
}
