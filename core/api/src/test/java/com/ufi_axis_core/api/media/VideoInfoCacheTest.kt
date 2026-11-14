package com.ufi_axis_core.api.routes

import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
import java.io.File

/**
 * G8（FFmpeg 接入计划书 §6.1）：`GET /api/media/video-info` 的**缓存生命周期**测试。
 *
 * 2026-10-05 审查修复的四条行为在这里锁定（此前全部缺失，坏缓存曾永久 500）：
 * 1. 探测成功 → 落 `video_info/<id>.json`，第二次请求命中缓存（`source=cache`）；
 * 2. 缓存内容损坏（非 JSON）→ **自愈**：删缓存重探，返回 200 而不是 500；
 * 3. 探测失败 → 404 + `.fail` 冷却标记落盘；冷却期内第二次请求**不再触发探测**（立即 404）；
 * 4. `DELETE /thumbnail-cache?type=video` 连带清 `video_info/`（审查遗留补口）。
 *
 * ## ffmpeg 探测怎么伪造
 * 真探测要加载 native `.so`（Robolectric 下必挂），但路由的探测入口是
 * `FfmpegThumbnailService.probeVideoInfo` —— 它第一步就 `ensureLoaded()` 失败返回 null，
 * 恰好等价于"ffmpeg 不可用"的失败路径，正好覆盖 3/4；成功路径（1/2）由测试**直接预写
 * 缓存文件**模拟"上次探测成功"，不依赖 native。真机上的成功路径由 §6.3 端到端清单验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoInfoCacheTest {

    private lateinit var context: android.content.Context
    private lateinit var settings: AppSettings
    private lateinit var routes: MediaRoutes

    @Before
    fun setup() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        routes = MediaRoutes(context, settings, ResponseCache(null), null)
        // 每个用例干净起见清掉缓存目录（id 命名固定，跨用例残留会串）
        File(context.filesDir, "video_info").deleteRecursively()
    }

    /** id=1 在 MediaStore 里不存在（Robolectric 空库），探测必然失败 → 404 路径。 */
    private fun getVideoInfo(id: Long = 1L): Pair<HttpStatusCode, String> {
        var result: Pair<HttpStatusCode, String>? = null
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
                routing { route("/api") { routes.register(this) } }
            }
            val resp = client.get("/api/media/video-info?id=$id")
            result = resp.status to resp.bodyAsText()
        }
        return result!!
    }

    private fun videoInfoDir(): File = File(context.filesDir, "video_info")

    // ── 行为 3a：探测失败 → 404 + .fail 标记落盘 ──

    @Test
    fun `探测失败回404并落冷却标记`() {
        val (status, body) = getVideoInfo()
        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(body.contains("无法解析"))
        val failMark = File(videoInfoDir(), "1.json.fail")
        assertTrue("404 必须落 .fail 冷却标记（审查修复）", failMark.isFile)
        // 标记内容是"冷却到期时间戳"：6h 后
        val until = failMark.readText().trim().toLongOrNull()
        assertTrue(until != null && until > System.currentTimeMillis())
    }

    // ── 行为 3b：冷却期内第二次请求不再探测（立即 404）──

    @Test
    fun `冷却期内第二次请求立即404不再探测`() {
        getVideoInfo() // 第一次：失败 + 落标记
        val (status, body) = getVideoInfo()
        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(body.contains("冷却中"))
        // 额外断言：标记没被清掉（清了就等于失去冷却语义）
        assertTrue(File(videoInfoDir(), "1.json.fail").isFile)
    }

    // ── 行为 1：缓存命中 ──

    @Test
    fun `预写缓存命中返回source=cache且不触发探测`() {
        // 直接预写"上次探测成功"的缓存：路由读缓存路径不碰 ffmpeg
        videoInfoDir().mkdirs()
        File(videoInfoDir(), "7.json").writeText(
            """{"duration_s":12.5,"width":1920,"height":1080,"codec":"h264","pix_fmt":"yuv420p","bit_rate":4000000,"fps_num":30,"fps_den":1}"""
        )
        val (status, body) = getVideoInfo(id = 7L)
        assertEquals(HttpStatusCode.OK, status)
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("cache", obj["source"]!!.jsonPrimitive.content)
        // 扁平化：duration_ms = 12.5s * 1000
        assertEquals(12500L, obj["duration_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("h264", obj["codec"]!!.jsonPrimitive.content)
    }

    // ── 行为 2：坏缓存自愈 ──

    @Test
    fun `坏缓存触发自愈而不是永久500`() {
        videoInfoDir().mkdirs()
        File(videoInfoDir(), "9.json").writeText("{{{ 不是 JSON")
        val (status, body) = getVideoInfo(id = 9L)
        // 审查修复前：解析失败 → 永久 500。修复后：删缓存重探；本环境 ffmpeg 不可用
        // → 探测失败回 404（绝不是 500），且坏缓存文件已被删除。
        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue("实际 body=$body", body.contains("元信息解析失败"))
        assertFalse("坏缓存必须被删除（自愈）", File(videoInfoDir(), "9.json").isFile)
        // 自愈重探失败与主路径同口径：也落 .fail 冷却
        assertTrue(File(videoInfoDir(), "9.json.fail").isFile)
    }

    // ── 行为 4：DELETE /thumbnail-cache 连带清 video_info ──

    @Test
    fun `清空缩略图缓存连带清理video_info`() {
        videoInfoDir().mkdirs()
        File(videoInfoDir(), "11.json").writeText("{}")
        File(videoInfoDir(), "12.json.fail").writeText("${System.currentTimeMillis() + 1000}")
        File(videoInfoDir(), "not_a_cache.txt").writeText("留着")

        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { route("/api") { routes.register(this) } }
            }
            val resp = client.delete("/api/media/thumbnail-cache?type=video")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(2, obj["video_info_removed"]!!.jsonPrimitive.content.toInt())
        }
        assertFalse(File(videoInfoDir(), "11.json").isFile)
        assertFalse(File(videoInfoDir(), "12.json.fail").isFile)
        // 非缓存文件不动（只认 .json/.json.fail 两个后缀）
        assertTrue(File(videoInfoDir(), "not_a_cache.txt").isFile)
    }

    // ── 参数校验（顺带锁定，属同一端点的行为契约）──

    @Test
    fun `缺少id参数回400`() {
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { route("/api") { routes.register(this) } }
            }
            val resp = client.get("/api/media/video-info")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }
}
