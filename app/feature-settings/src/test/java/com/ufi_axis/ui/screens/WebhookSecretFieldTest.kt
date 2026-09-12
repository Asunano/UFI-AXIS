package com.ufi_axis.ui.screens

import com.ufi_axis.data.model.WebhookConfigResponse
import com.ufi_axis.data.model.WebhookPresetDto
import com.ufi_axis.data.model.WebhookSecretTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预设要求填的那一样东西」与存储之间的换算（[resolveSecretField]）。
 *
 * 为什么值得单测：存储里只有**最终**的 url / body_template，marker 已经被替换掉了，
 * "哪一段是用户填的"只能靠预设默认值的前后缀反推。这条规则出错的表现全是静默的 ——
 * 少替换一个尖括号 → core 判定"配置未填完"、一条都不投；切错前后缀 → 保存时把用户
 * 手工改过的地址吞掉。两样在界面上都看不出原因，所以判据必须钉住。
 */
class WebhookSecretFieldTest {

    private val bark = WebhookPresetDto(
        name = "BARK",
        display_name = "Bark",
        user_fills = "服务器地址 + device key（URL 末段）",
        secret_label = "Device Key",
        secret_marker = "<device_key>",
        secret_target = WebhookSecretTarget.URL,
        url = "https://api.day.app/<device_key>",
        body_template = """{"title":"{{title}}"}"""
    )

    private val pushplus = WebhookPresetDto(
        name = "PUSHPLUS",
        display_name = "PushPlus",
        user_fills = "请求体模板里的 PushPlus Token",
        secret_label = "PushPlus Token",
        secret_marker = "<token>",
        secret_target = WebhookSecretTarget.BODY,
        url = "https://www.pushplus.plus/send",
        body_template = """{"token":"<token>","title":"{{title}}"}"""
    )

    private val custom = WebhookPresetDto(
        name = "CUSTOM",
        display_name = "自定义",
        secret_label = "",
        secret_marker = "",
        secret_target = WebhookSecretTarget.NONE
    )

    private fun config(
        url: String = "",
        bodyTemplate: String = "",
        preset: String = "CUSTOM"
    ) = WebhookConfigResponse(preset = preset, url = url, body_template = bodyTemplate)

    // ── URL 档（Bark / ntfy / 企业微信 / 飞书 / Server 酱） ──────────────────────

    @Test
    fun `未填时切出空串并判定未填`() {
        val field = resolveSecretField(config(url = bark.url, preset = "BARK"), bark)!!
        assertEquals("Device Key", field.label)
        assertEquals("https://api.day.app/", field.prefix)
        assertEquals("", field.suffix)
        // 预设默认值里 marker 还在，切出来的就是 marker 本身 → 含 `<` ⇒ 未填
        assertEquals("<device_key>", field.value)
        assertFalse(field.filled)
    }

    @Test
    fun `填好后切出用户填的那一段`() {
        val field = resolveSecretField(
            config(url = "https://api.day.app/abc123", preset = "BARK"),
            bark
        )!!
        assertEquals("abc123", field.value)
        assertTrue(field.filled)
        // 写回时拼回前后缀，marker 连尖括号一起被换掉
        assertEquals("https://api.day.app/abc123", field.merged("abc123"))
    }

    // ── 写回（含清空） ──────────────────────────────────────────────────────────

    @Test
    fun `写回时替换整段 marker 而不是只换括号里的名字`() {
        val field = resolveSecretField(config(url = bark.url, preset = "BARK"), bark)!!
        val merged = field.merged("abc123")
        assertEquals("https://api.day.app/abc123", merged)
        // 留下裸 `<` 就会被 core 判成"没配完"、永远不投递
        assertFalse(merged.contains("<"))
    }

    @Test
    fun `写回时忽略首尾空白`() {
        val field = resolveSecretField(config(url = bark.url, preset = "BARK"), bark)!!
        assertEquals("https://api.day.app/abc123", field.merged("  abc123  "))
    }

    @Test
    fun `清空写回整段 marker 而不是空串`() {
        val field = resolveSecretField(
            config(url = "https://api.day.app/abc123", preset = "BARK"),
            bark
        )!!
        // 拼成空会得到 `https://api.day.app/`：不含裸 `<` ⇒ core 判成配置完整 ⇒ 假成功
        assertEquals(bark.url, field.merged(""))
        assertEquals(bark.url, field.merged("   "))
    }

    @Test
    fun `清空后当前值里重新含 marker 且判成未填`() {
        val filled = resolveSecretField(
            config(url = "https://api.day.app/abc123", preset = "BARK"),
            bark
        )!!
        val cleared = filled.merged("")
        assertTrue(cleared.contains(bark.secret_marker))

        // 用清空后的值再解一次：simple 输入框仍在（结构没变），但状态回到"未填"
        val after = resolveSecretField(config(url = cleared, preset = "BARK"), bark)!!
        assertEquals(bark.secret_marker, after.value)
        assertFalse(after.filled)
    }

    @Test
    fun `body 档清空写回的是带 marker 的模板`() {
        val field = resolveSecretField(
            config(
                url = pushplus.url,
                bodyTemplate = """{"token":"tk-9","title":"{{title}}"}""",
                preset = "PUSHPLUS"
            ),
            pushplus
        )!!
        assertEquals(pushplus.body_template, field.merged(""))
    }


    @Test
    fun `marker 在中间时前后缀都要留住`() {
        val wecom = WebhookPresetDto(
            name = "WECOM",
            secret_label = "机器人 Key",
            secret_marker = "<key>",
            secret_target = WebhookSecretTarget.URL,
            url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<key>"
        )
        val field = resolveSecretField(
            config(url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=K-1", preset = "WECOM"),
            wecom
        )!!
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=", field.prefix)
        assertEquals("", field.suffix)
        assertEquals("K-1", field.value)
    }

    @Test
    fun `后缀非空时不把它算进用户填的那一段`() {
        val serverChan = WebhookPresetDto(
            name = "SERVERCHAN",
            secret_label = "SENDKEY",
            secret_marker = "<SENDKEY>",
            secret_target = WebhookSecretTarget.URL,
            url = "https://sctapi.ftqq.com/<SENDKEY>.send"
        )
        val field = resolveSecretField(
            config(url = "https://sctapi.ftqq.com/SCT123.send", preset = "SERVERCHAN"),
            serverChan
        )!!
        assertEquals(".send", field.suffix)
        assertEquals("SCT123", field.value)
    }

    @Test
    fun `前后缀对不上就不给简易输入框`() {
        // 用户把服务器换成了自建 Bark
        val field = resolveSecretField(
            config(url = "https://bark.example.com/abc123", preset = "BARK"),
            bark
        )!!
        assertNull(field.value)
        assertFalse(field.filled)
    }

    @Test
    fun `长度不足前后缀之和时不给简易输入框`() {
        // 只剩前缀的一部分：startsWith 会成立，但中间那段的下标会越界
        val field = resolveSecretField(config(url = "https://api.day", preset = "BARK"), bark)!!
        assertNull(field.value)
    }

    // ── BODY 档（PushPlus） ──────────────────────────────────────────────────────

    @Test
    fun `body 档读的是请求体模板而不是 URL`() {
        val field = resolveSecretField(
            config(
                url = pushplus.url,
                bodyTemplate = """{"token":"tk-9","title":"{{title}}"}""",
                preset = "PUSHPLUS"
            ),
            pushplus
        )!!
        assertEquals(WebhookSecretTarget.BODY, field.target)
        assertEquals("请求体模板", field.targetLabel)
        assertEquals("tk-9", field.value)
        assertTrue(field.filled)
    }

    // ── 没有"那一样东西"的档 ────────────────────────────────────────────────────

    @Test
    fun `自定义档没有简易输入框`() {
        assertNull(resolveSecretField(config(), custom))
    }

    @Test
    fun `认不出预设时没有简易输入框`() {
        assertNull(resolveSecretField(config(url = "https://x.example.com"), null))
    }

    @Test
    fun `marker 没出现在声明的那一处时退化成全手填`() {
        // core 有单测钉住这种不一致，真发生时 app 不许猜位置
        val broken = bark.copy(url = "https://api.day.app/<other>")
        assertNull(resolveSecretField(config(url = broken.url, preset = "BARK"), broken))
    }

    @Test
    fun `认不出的 secret_target 退化成全手填`() {
        val future = bark.copy(secret_target = "header")
        assertNull(resolveSecretField(config(url = bark.url, preset = "BARK"), future))
    }

    @Test
    fun `secret_label 缺失时回落到 user_fills`() {
        val noLabel = bark.copy(secret_label = "")
        val field = resolveSecretField(config(url = bark.url, preset = "BARK"), noLabel)!!
        assertEquals(bark.user_fills, field.label)
    }
}
