package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单校验与退避策略的单测（纯 JVM，不起 Android）。
 *
 * 选这两块测，是因为它们**都是"改一个数字就出事"的地方**：
 * - 清单校验漏一条 → 装上一个跑不起来的程序，或者一个暴露在局域网的端口；
 * - 退避算错 → 崩溃循环把设备拖到重启风暴里。
 */
class ProgramManifestTest {

    private val sha = "a".repeat(64)

    private fun json(
        id: String = "demo",
        schema: Int = 1,
        sha256: String = sha,
        archive: String = "tar.gz",
        entry: String = "demo",
        internalPort: Int = 5244,
        externalPort: Int = 18080,
        bind: String = "loopback",
        backoff: String = "[5,15,60]",
        rss: String = "128,220,360",
        root: Boolean = false,
        env: String = ""
    ): String = """
        {
          "schema": $schema,
          "id": "$id",
          "name": "Demo",
          "artifact": {
            "url": "https://example.com/demo.tar.gz",
            "sha256": "$sha256",
            "size": 1000,
            "archive": "$archive",
            "entry": "$entry"
          },
          "run": {
            "argv": ["server", "--data", "{DATA}"],
            "internal_port": $internalPort,
            "bind": "$bind",
            "external_port": $externalPort
          },
          "resources": {
            "rss_warn_mb": ${rss.split(",")[0]},
            "rss_restart_mb": ${rss.split(",")[1]},
            "rss_kill_mb": ${rss.split(",")[2]}
          },
          "restart": { "backoff_s": $backoff },
          "requires": { "root": $root }
          $env
        }
    """.trimIndent()

    private fun errorsOf(text: String): List<String> {
        val m = ProgramManifestCodec.parse(text).getOrThrow()
        return ProgramManifestCodec.validate(m)
    }

    @Test
    fun `合法清单通过`() {
        assertEquals(emptyList<String>(), errorsOf(json()))
    }

    @Test
    fun `未知字段被忽略`() {
        val text = json(env = ""","future_field": {"x": 1}""")
        assertEquals(emptyList<String>(), errorsOf(text))
    }

    @Test
    fun `schema 过高直接拒绝且不再报其他错`() {
        val e = errorsOf(json(schema = 2))
        assertEquals(1, e.size)
        assertTrue(e[0].contains("高于本端支持"))
    }

    @Test
    fun `id 非法被拒绝`() {
        assertTrue(errorsOf(json(id = "Demo")).any { it.contains("非法 id") })
        assertTrue(errorsOf(json(id = "../etc")).any { it.contains("非法 id") })
    }

    @Test
    fun `sha256 必须 64 位十六进制`() {
        assertTrue(errorsOf(json(sha256 = "abcd")).any { it.contains("sha256") })
    }

    @Test
    fun `保留端口被拒绝`() {
        // 8088 是 core 自己
        assertTrue(errorsOf(json(internalPort = 8088)).any { it.contains("保留端口") })
        // 17682 是 ttyd
        assertTrue(errorsOf(json(externalPort = 17682)).any { it.contains("保留端口") })
    }

    @Test
    fun `对外端口不能等于内部端口`() {
        assertTrue(
            errorsOf(json(internalPort = 5244, externalPort = 5244))
                .any { it.contains("不能等于") }
        )
    }

    @Test
    fun `loopback 必须给对外端口`() {
        assertTrue(
            errorsOf(json(bind = "loopback", externalPort = 0))
                .any { it.contains("external_port") }
        )
    }

    @Test
    fun `内存阈值必须递增`() {
        assertTrue(
            errorsOf(json(rss = "220,128,360")).any { it.contains("内存阈值") }
        )
    }

    @Test
    fun `退避表必须非递减`() {
        assertTrue(
            errorsOf(json(backoff = "[60,15,5]")).any { it.contains("非递减") }
        )
    }

    @Test
    fun `需要 root 的程序被拒绝`() {
        assertTrue(errorsOf(json(root = true)).any { it.contains("需要 root") })
    }

    @Test
    fun `tar_gz 缺 entry 被拒绝`() {
        assertTrue(
            errorsOf(json(archive = "tar.gz", entry = "")).any { it.contains("entry") }
        )
    }

    @Test
    fun `未注册的适配器被拒绝`() {
        val text = json(env = ""","first_run": {"adapter": "not_registered"}""")
        assertTrue(errorsOf(text).any { it.contains("未注册的适配器") })
    }

    @Test
    fun `非 https 的公网地址被拒绝`() {
        val text = json().replace("https://example.com/demo.tar.gz", "http://example.com/demo.tar.gz")
        val m = ProgramManifestCodec.parse(text).getOrThrow()
        val r = ProgramManifestCodec.requireSecureUrl(m.artifact.url)
        assertTrue(r.isFailure)
    }

    @Test
    fun `内容目录的默认落点只能是 internal 或 external`() {
        val errs = errorsOf(json(env = """, "content": { "enabled": true, "default_root": "usb" }"""))
        assertTrue(errs.any { it.contains("content.default_root") })
    }

    @Test
    fun `声明内容目录的清单通过校验`() {
        val errs = errorsOf(json(env = """, "content": { "enabled": true, "default_root": "external" }"""))
        assertEquals(emptyList<String>(), errs)
    }

    @Test
    fun `JSON 语法错误返回失败而不是抛异常`() {
        assertTrue(ProgramManifestCodec.parse("{ not json").isFailure)
    }
}

/**
 * 退避策略单测。
 *
 * 这里没有"大概对就行"的余地：`delayMs` 越界会变成负数延迟（立即重启），
 * `isFailure` 判反会让正常运行的程序被判成崩溃循环。
 */
class ProgramBackoffTest {

    @Test
    fun `按次数取表`() {
        val table = listOf(5, 15, 60, 300, 900)
        assertEquals(5_000L, ProgramBackoff.delayMs(0, table))
        assertEquals(15_000L, ProgramBackoff.delayMs(1, table))
        assertEquals(900_000L, ProgramBackoff.delayMs(4, table))
    }

    @Test
    fun `超出表长取最后一个值而不是溢出`() {
        val table = listOf(5, 15, 60)
        assertEquals(60_000L, ProgramBackoff.delayMs(3, table))
        assertEquals(60_000L, ProgramBackoff.delayMs(99, table))
    }

    @Test
    fun `空表有兜底而不是崩`() {
        assertEquals(60_000L, ProgramBackoff.delayMs(0, emptyList()))
    }

    @Test
    fun `负次数被夹到第一个`() {
        assertEquals(5_000L, ProgramBackoff.delayMs(-1, listOf(5, 15)))
    }

    @Test
    fun `短命退出算失败`() {
        assertTrue(ProgramBackoff.isFailure(uptimeMs = 3_000))
    }

    @Test
    fun `长命退出不算失败`() {
        assertFalse(ProgramBackoff.isFailure(uptimeMs = 10 * 60_000))
    }

    @Test
    fun `crash loop window 作为健康阈值`() {
        assertTrue(ProgramBackoff.isFailure(uptimeMs = 90_000, healthyUptimeMs = 120_000))
        assertFalse(ProgramBackoff.isFailure(uptimeMs = 120_000, healthyUptimeMs = 120_000))
    }

    @Test
    fun `达到上限才熔断`() {
        assertFalse(ProgramBackoff.shouldOpen(7, 8))
        assertTrue(ProgramBackoff.shouldOpen(8, 8))
        assertTrue(ProgramBackoff.shouldOpen(9, 8))
    }
}
