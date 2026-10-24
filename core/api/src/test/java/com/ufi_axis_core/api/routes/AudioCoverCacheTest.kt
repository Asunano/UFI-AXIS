package com.ufi_axis_core.api.routes

import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `GET /api/media/cover` 的**落盘缓存层**测试（2026-10-13）。
 *
 * ## 被守护的事故
 * `/cover` 原来是**纯实时**的：每次都走 `audioCoverBytes` → 内嵌封面（MMR）→ 失败再
 * `loadThumbnail`。列表是**按行**请求封面的，所以滑动音频列表就是几十次并发解码压在
 * `android.process.media` 里（471MB 的直接来源之一）。
 *
 * 现在第一层是落盘缓存：命中就完全不碰 MediaProvider / codec。
 * 本测试锁的就是"命中路径零解码"这条契约 —— 它没有可观测的解码计数器，
 * 所以用**结果正确性**间接证明：没有真实媒体库、没有任何解码器的情况下
 *（Robolectric 空库），只要磁盘上有图，端点就必须给出正确的字节与 Content-Type。
 *
 * 反过来说：如果哪天有人把落盘缓存这一层删掉/短路掉，这些用例会立刻失败 ——
 * 因为那时唯一的图源就只剩解码，而在空库上必然 404。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioCoverCacheTest {

    private lateinit var context: android.content.Context
    private lateinit var settings: AppSettings
    private lateinit var routes: MediaRoutes

    @Before
    fun setup() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        // /cover 有 isGranted(AUDIO) 权限门，Robolectric 默认 DENIED → 403，
        // 授权后才能测它后面的缓存逻辑。
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        settings = AppSettings(context)
        settings.resetAll()
        routes = MediaRoutes(context, settings, ResponseCache(null), null)
        File(context.filesDir, "thumbs").deleteRecursively()
    }

    private fun thumbDir(): File = File(context.filesDir, "thumbs").apply { mkdirs() }

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
                routing { route("/api") { routes.register(this) } }
            }
            block()
        }

    @Test
    fun `jpg落盘缓存命中直接响应`() = withApp {
        File(thumbDir(), "audio_cover_7.jpg").writeText("JPEG-BYTES")

        val resp = client.get("/api/media/cover?id=7")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("JPEG-BYTES", resp.bodyAsText())
        // 内容类型由**扩展名**还原：内嵌封面可能是 JPEG 也可能是 PNG，
        // 存成一个固定后缀会让 PNG 被声明成 JPEG
        assertEquals("image/jpeg", resp.headers[HttpHeaders.ContentType])
        assertEquals("\"media-cover-7\"", resp.headers[HttpHeaders.ETag])
        assertEquals("private, max-age=86400", resp.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `png落盘缓存命中按扩展名还原内容类型`() = withApp {
        File(thumbDir(), "audio_cover_8.png").writeText("PNG-BYTES")

        val resp = client.get("/api/media/cover?id=8")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("PNG-BYTES", resp.bodyAsText())
        assertEquals(
            "PNG 封面必须以 image/png 交付，不能一律声明成 JPEG",
            "image/png", resp.headers[HttpHeaders.ContentType]
        )
    }

    @Test
    fun `空文件视为未命中不当作有效缓存`() = withApp {
        // 半写残留（进程被杀）：长度 0 的文件不能当成命中，
        // 否则客户端会拿到一张 0 字节的"封面"
        File(thumbDir(), "audio_cover_10.jpg").writeText("")

        val resp = client.get("/api/media/cover?id=10")

        // 空库 + 无有效缓存 → 解码也拿不到 → 404（而不是 200 空响应）
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `IfNoneMatch命中ETag回304`() = withApp {
        File(thumbDir(), "audio_cover_11.jpg").writeText("X")

        val first = client.get("/api/media/cover?id=11")
        val etag = first.headers[HttpHeaders.ETag]!!
        val second = client.get("/api/media/cover?id=11") {
            headers.append(HttpHeaders.IfNoneMatch, etag)
        }

        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    /**
     * 冷却中的失败**不得**再去解码 —— 这是"无封面的歌每次都重跑 MMR + 解码"的护栏。
     *
     * 判据用"响应 404 且冷却标记仍在"：若代码没走冷却短路，它会去解码，
     * 失败后重新写一份冷却标记（时间戳变了）。这里断言时间戳**没变**，
     * 就说明它压根没进解码那条路。
     */
    @Test
    fun `失败冷却期内直接404且不重复解码`() = withApp {
        val until = System.currentTimeMillis() + 60L * 60 * 1000
        val mark = File(thumbDir(), "audio_cover_12.fail")
        mark.writeText(until.toString())

        val resp = client.get("/api/media/cover?id=12")

        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertEquals(
            "冷却期内不得重跑解码：标记时间戳应原样不动",
            until.toString(), mark.readText().trim()
        )
    }

    /**
     * 冷却过期后应恢复正常流程。
     *
     * 判据是"标记被换成了一份**新的未来**时间戳"，而不是"标记消失"：
     * 过期标记确实会被清掉，但紧接着 `audioCoverBytes` 在空库上必然失败，
     * 于是 `markCoverFailed` 又写了一份新的 —— 标记"消失"从来不是这条路的终态。
     * 时间戳从过去变成未来，正好证明它确实走完了"清标记 → 尝试解码 → 重新记失败"。
     */
    @Test
    fun `冷却过期后清掉旧标记并重新记录失败`() = withApp {
        val stale = System.currentTimeMillis() - 1000L
        val mark = File(thumbDir(), "audio_cover_13.fail")
        mark.writeText(stale.toString())

        val resp = client.get("/api/media/cover?id=13")

        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue("过期的冷却标记应已被清掉并重新记录", mark.isFile)
        val after = mark.readText().trim().toLongOrNull() ?: 0L
        assertTrue(
            "标记时间戳应从过去($stale)变成未来（实际 $after）—— 说明确实走了正常流程而非被冷却短路",
            after > System.currentTimeMillis()
        )
    }

    @Test
    fun `缺少id回400`() = withApp {
        val resp = client.get("/api/media/cover")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("id"))
    }
}
