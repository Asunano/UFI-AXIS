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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * G8（FFmpeg 接入计划书 §6.1）：`GET /api/media/thumbnail` 的**缓存命中层 + 来源标记**测试。
 *
 * ## 为什么不测全链路（§6.1 的 ThumbnailChainTest 意图）
 * `thumbnailBytes` 的三级来源（MMR → ffmpeg → 404）每一级都压在 `MediaMetadataRetriever` /
 * native `.so` 上，Robolectric 下要么返回不可控的 shadow 值、要么根本加载不了 ——
 * 纯 JVM mock 需要把三个来源抽成接口，那是重构不是测试。三级来源的**短路顺序**由代码
 * 结构保证（early return / `recoverCatching`），真机行为由计划书 §6.3 端到端清单验收。
 * 这里锁定的是不依赖解码器的**外层契约**：缓存命中、来源头、ETag/304。
 *
 * G4 的 `X-Thumb-Source` 是 app 端"要不要本机抽帧回传"的决策依据，标错会导致
 * app 无限重抽 —— 所以它是必须被测试锁住的契约，不是实现细节。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThumbnailChainTest {

    private lateinit var context: android.content.Context
    private lateinit var settings: AppSettings
    private lateinit var routes: MediaRoutes

    @Before
    fun setup() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        // /thumbnail 有 isGranted 权限门（Robolectric 默认 DENIED → 403）；
        // 授予媒体读取权限才能测它后面的缓存/来源逻辑。VideoInfo 端点无此门，不受影响。
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        settings = AppSettings(context)
        settings.resetAll()
        routes = MediaRoutes(context, settings, ResponseCache(null), null)
        File(context.filesDir, "thumbs").deleteRecursively()
    }

    /** 预写一张"客户端回传"的缩略图（≥1B 即视为有效缓存）。 */
    private fun seedCache(id: Long, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        File(dir, "video_$id.jpg").writeBytes(bytes)
    }

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
    fun `缓存命中返回200且X-Thumb-Source为cache`() = withApp {
        seedCache(id = 42L)
        val resp = client.get("/api/media/thumbnail?type=video&id=42")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("cache", resp.headers["X-Thumb-Source"])
        assertTrue(resp.headers[HttpHeaders.ETag]!!.contains("video-42"))
        assertEquals("private, max-age=86400", resp.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `IfNoneMatch命中ETag回304`() = withApp {
        seedCache(id = 43L)
        val first = client.get("/api/media/thumbnail?type=video&id=43")
        val etag = first.headers[HttpHeaders.ETag]!!
        val second = client.get("/api/media/thumbnail?type=video&id=43") {
            headers.append(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    @Test
    fun `无缓存且ffmpeg不可用时回404带reason`() = withApp {
        // Robolectric 空库：MMR/系统缩略图都失败，ffmpeg .so 加载失败 → 全链路无来源
        val resp = client.get("/api/media/thumbnail?type=video&id=99")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        // reason 要能独立看懂（修 F1 的语义：客户端把它显示给用户）
        val reason = body["reason"]!!.jsonPrimitive.content
        assertTrue("实际 reason=$reason", reason.contains("系统缩略图") || reason.contains("自行生成"))
        // 失败响应不带 X-Thumb-Source（没有"来源"可言）
        assertEquals(null, resp.headers["X-Thumb-Source"])
    }

    @Test
    fun `非法size被夹取不报错`() = withApp {
        seedCache(id = 44L)
        // size 参数只影响现算路径，缓存命中路径无视它 —— 传个离谱值也不该炸
        val resp = client.get("/api/media/thumbnail?type=video&id=44&size=999999")
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
