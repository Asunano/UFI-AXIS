package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.backup.BackupAssembler
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.database.SmsRuleDao
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份导出 / 恢复的往返测试。
 *
 * 这套用例的存在理由很直接：备份包一旦格式或加密写错，用户拿到的是一个**永远解不开的
 * 文件**，而且往往在需要它的那一刻才发现。所以必须在 CI 里锁住「导出的包能被导入回来」。
 *
 * `AppDatabase` 用 mockk 而不是内存 Room：这里验的是打包 / 加密 / 写回配置这条链路，
 * 拦截规则那一段只要"能被跳过、不影响其余段"就够了，引入真实 Room 只会把测试变慢变脆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRoutesTest {

    private lateinit var ctx: Context
    private lateinit var settings: AppSettings
    private lateinit var routes: BackupRoutes

    private val passphrase = "correct horse battery"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        settings = AppSettings(ctx)
        settings.resetAll()

        val ruleDao = mockk<SmsRuleDao>(relaxed = true)
        coEvery { ruleDao.getAll() } returns emptyList()
        val database = mockk<AppDatabase>(relaxed = true)
        coEvery { database.smsRuleDao() } returns ruleDao

        routes = BackupRoutes(settings, BackupAssembler(ctx, settings, database))
    }

    private fun Application.mount() {
        install(ContentNegotiation) { json() }
        routing { routes.register(this) }
    }

    // ── 加密往返 ──

    @Test
    fun `encrypted package round trips settings`() {
        settings.port = 9999
        settings.goformIp = "10.0.0.1"
        settings.monitorRetentionDays = 42

        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = true)

            // 改成别的值，确认导入真的把它改回来了（而不是"本来就没变"）
            settings.port = 8088
            settings.goformIp = "192.168.0.1"
            settings.monitorRetentionDays = 7

            val resp = importPackage(pack, passphrase)
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(9999, settings.port)
            assertEquals("10.0.0.1", settings.goformIp)
            assertEquals(42, settings.monitorRetentionDays)
        }
    }

    @Test
    fun `plain package round trips settings`() {
        settings.port = 9001
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            settings.port = 8088
            assertEquals(HttpStatusCode.OK, importPackage(pack, null).status)
            assertEquals(9001, settings.port)
        }
    }

    // ── 口令与完整性 ──

    @Test
    fun `wrong passphrase is rejected on import`() {
        settings.port = 9002
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = true)
            settings.port = 8088
            val resp = importPackage(pack, "correct horse batterY")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            // 关键：被拒之后配置必须**没有**被部分写入
            assertEquals(8088, settings.port)
        }
    }

    @Test
    fun `encrypted package without passphrase is rejected`() {
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = true)
            assertEquals(HttpStatusCode.Unauthorized, importPackage(pack, null).status)
        }
    }

    @Test
    fun `export refuses weak passphrase`() {
        testApplication {
            application { mount() }
            val resp = client.post("/backup/export") {
                contentType(ContentType.Application.Json)
                setBody("""{"encrypted":true,"passphrase":"short"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        settings.port = 9003
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = true)
            val broken = mutateEntry(pack, BackupRoutes.ENTRY_PAYLOAD_ENC) { bytes ->
                bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
            }
            settings.port = 8088
            val resp = importPackage(broken, passphrase)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(8088, settings.port)
        }
    }

    @Test
    fun `tampered manifest breaks decryption`() {
        // AAD 绑定的价值就在这条：改 manifest（这里把轮数改大）必须导致解密失败，
        // 而不是"用被改过的参数解出内容"。
        settings.port = 9004
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = true)
            val broken = mutateEntry(pack, BackupRoutes.ENTRY_MANIFEST) { bytes ->
                val text = bytes.toString(Charsets.UTF_8)
                text.replace(
                    "\"iterations\":${com.ufi_axis_core.util.BackupCrypto.DEFAULT_ITERATIONS}",
                    "\"iterations\":${com.ufi_axis_core.util.BackupCrypto.DEFAULT_ITERATIONS + 1}"
                ).toByteArray()
            }
            val resp = importPackage(broken, passphrase)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun `plain package with broken payload is rejected by checksum`() {
        // 不加密的包没有 GCM tag 兜底，全靠 manifest 里的 sha256。
        settings.port = 9005
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            val broken = mutateEntry(pack, BackupRoutes.ENTRY_PAYLOAD_PLAIN) { it.copyOf(it.size - 4) }
            settings.port = 8088
            assertEquals(HttpStatusCode.BadRequest, importPackage(broken, null).status)
            assertEquals(8088, settings.port)
        }
    }

    // ── 预览 ──

    @Test
    fun `preview reports sections without applying anything`() {
        settings.port = 9006
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            settings.port = 8088

            val body = client.post("/backup/preview") {
                contentType(ContentType.Application.OctetStream)
                setBody(pack)
            }.json()

            assertTrue(body["sections"]!!.toString().contains(BackupAssembler.PATH_SETTINGS))
            assertEquals(false, body["encrypted"]?.jsonPrimitive?.booleanOrNull)
            // 预览绝不能落地任何改动
            assertEquals(8088, settings.port)
        }
    }

    // ── merge 与 replace ──

    @Test
    fun `merge keeps keys absent from the package`() {
        settings.port = 9007
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            // 包里没有这一项（导出时它还不存在），merge 必须保留现值
            settings.qosShellMaxConcurrent = 7
            assertEquals(HttpStatusCode.OK, importPackage(pack, null, replace = false).status)
            assertEquals(7, settings.qosShellMaxConcurrent)
            assertEquals(9007, settings.port)
        }
    }

    @Test
    fun `replace drops keys absent from the package`() {
        settings.port = 9008
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            settings.qosShellMaxConcurrent = 7
            assertEquals(HttpStatusCode.OK, importPackage(pack, null, replace = true).status)
            // replace 语义是"回到包描述的状态"，包里没有的项要回默认值
            assertEquals(3, settings.qosShellMaxConcurrent)
            assertEquals(9008, settings.port)
        }
    }

    @Test
    fun `import reports needs restart when port changes`() {
        settings.port = 9009
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            val body = importPackage(pack, null).json()
            assertTrue(body["needs_restart"]?.jsonPrimitive?.booleanOrNull == true)
        }
    }

    // ── 版本兼容 ──

    @Test
    fun `newer format version is refused`() {
        testApplication {
            application { mount() }
            val pack = exportPackage(encrypted = false)
            val broken = mutateEntry(pack, BackupRoutes.ENTRY_MANIFEST) { bytes ->
                bytes.toString(Charsets.UTF_8)
                    .replace("\"format\":${BackupRoutes.FORMAT_VERSION}", "\"format\":999")
                    .toByteArray()
            }
            // 宁可拒绝也不猜着解析：未知格式解出来的东西可能被静默写进配置
            assertEquals(HttpStatusCode.BadRequest, importPackage(broken, null).status)
        }
    }

    @Test
    fun `garbage upload is refused`() {
        testApplication {
            application { mount() }
            val resp = client.post("/backup/import") {
                contentType(ContentType.Application.OctetStream)
                setBody("this is not a zip".toByteArray())
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    // ── 客户端段 ──

    @Test
    fun `client sections are carried through and handed back`() {
        testApplication {
            application { mount() }
            val pack = client.post("/backup/export") {
                contentType(ContentType.Application.Json)
                setBody(
                    "{\"encrypted\":false,\"acknowledge_plaintext\":true," +
                        "\"client\":{\"app\":{\"theme\":\"dark\"},\"web\":{\"themeId\":\"default\"}}}"
                )
            }.readBytes()

            val body = importPackage(pack, null).json()
            // core 不解释客户端段，只保证原样带回来
            assertTrue(body["client_app"].toString().contains("dark"))
            assertTrue(body["client_web"].toString().contains("default"))
        }
    }

    // ── 明文导出的确认门（2026-09-12）──

    @Test
    fun `plain export without acknowledgement is refused`() {
        // 明文包里含设备后台密码与隧道凭据，服务端不接受"悄悄导出"：
        // 没有 acknowledge_plaintext=true 就必须 400，且**不能**产出任何文件。
        testApplication {
            application { mount() }
            val resp = client.post("/backup/export") {
                contentType(ContentType.Application.Json)
                setBody("""{"encrypted":false}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `plain export with acknowledgement succeeds`() {
        testApplication {
            application { mount() }
            val resp = client.post("/backup/export") {
                contentType(ContentType.Application.Json)
                setBody("""{"encrypted":false,"acknowledge_plaintext":true}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            // 明文包在响应上留痕，便于抓包侧识别"这条下载没有加密保护"
            assertEquals("true", resp.headers[BackupRoutes.HEADER_PLAINTEXT_FLAG])
        }
    }

    // ── 解压体积上限（zip bomb，2026-09-12）──

    @Test
    fun `inner payload that expands past the limit is refused`() {
        // 上传体积 ≠ 解压体积：下面这个包整体不到 100KB，但内层条目解压后是 32MB。
        // 没有上限时它会被整段读进内存（设备 256MB RAM 上直接 OOM），所以必须 400。
        testApplication {
            application { mount() }
            val inner = ByteArrayOutputStream()
            ZipOutputStream(inner).use { zip ->
                zip.putNextEntry(ZipEntry("core/settings.json"))
                repeat(512) { zip.write(ByteArray(64 * 1024)) } // 全零，deflate 压缩率极高
                zip.closeEntry()
            }
            val payload = inner.toByteArray()
            // 摘要按真实内容算：要先把完整性校验放行，才能验到"解压上限"这一层
            val manifest = """{"format":1,"encrypted":false,"payload_sha256":"${sha256Hex(payload)}"}"""

            val pack = ByteArrayOutputStream()
            ZipOutputStream(pack).use { zip ->
                zip.putNextEntry(ZipEntry(BackupRoutes.ENTRY_MANIFEST))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BackupRoutes.ENTRY_PAYLOAD_PLAIN))
                zip.write(payload)
                zip.closeEntry()
            }
            assertEquals(HttpStatusCode.BadRequest, importPackage(pack.toByteArray(), null).status)
        }
    }

    @Test
    fun `outer entry that expands past the limit is refused`() {
        // 外层 ZIP 同样是不可信输入：一个把 payload 条目写成"解压后 16MB"的包
        // 也不能被无上限读取。
        testApplication {
            application { mount() }
            val pack = ByteArrayOutputStream()
            ZipOutputStream(pack).use { zip ->
                zip.putNextEntry(ZipEntry(BackupRoutes.ENTRY_MANIFEST))
                zip.write("""{"format":1}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BackupRoutes.ENTRY_PAYLOAD_PLAIN))
                repeat(256) { zip.write(ByteArray(64 * 1024)) } // 16MB > 上传上限
                zip.closeEntry()
            }
            assertEquals(HttpStatusCode.BadRequest, importPackage(pack.toByteArray(), null).status)
        }
    }

    // ── 辅助 ──

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private suspend fun io.ktor.client.HttpClient.exportBytes(encrypted: Boolean): ByteArray =
        post("/backup/export") {
            contentType(ContentType.Application.Json)
            setBody(
                // 明文导出必须带上确认标记（core 见 BackupRoutes：没有它直接 400）。
                // 这里相当于「客户端已经把风险给用户看过、用户点了仍然不加密」的那次调用。
                if (encrypted) """{"encrypted":true,"passphrase":"$passphrase"}"""
                else """{"encrypted":false,"acknowledge_plaintext":true}"""
            )
        }.readBytes()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.exportPackage(
        encrypted: Boolean
    ): ByteArray = client.exportBytes(encrypted)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.importPackage(
        pack: ByteArray,
        passphrase: String?,
        replace: Boolean = false
    ): HttpResponse = client.post("/backup/import?mode=${if (replace) "replace" else "merge"}") {
        contentType(ContentType.Application.OctetStream)
        if (passphrase != null) header(BackupRoutes.HEADER_PASSPHRASE, passphrase)
        setBody(pack)
    }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    /** 重打一个 ZIP，只替换指定条目的内容 —— 用来模拟"文件被人改过"。 */
    private fun mutateEntry(
        pack: ByteArray,
        target: String,
        transform: (ByteArray) -> ByteArray
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(pack.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        entries[target] = transform(entries.getValue(target))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
