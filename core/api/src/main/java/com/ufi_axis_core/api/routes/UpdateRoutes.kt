package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.update.UpdateManager
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备更新路由（2026-08-10，后端自拉取 + 前端推送兜底；2026-08-10 C5 新增前端 App 更新转发）。
 *
 * - POST /api/update/check      前端触发检查：后端拉 update_url 版本清单 → 有新版自动下载+安装
 * - GET  /api/update/status     更新状态（idle/downloading/verifying/installing/done/failed + 进度）
 * - POST /api/update/upload     兜底：前端推送 APK（multipart file 字段）→ 存 /sdcard/Download/UFI-AXIS/update/
 * - POST /api/update/install-local  安装已上传的本地 APK（兜底第二段）
 * - POST /api/update/reset      重置状态
 * - GET  /api/update/frontend-info  前端 App 更新信息（仓库根 version.json 的 frontend 对象，5min 缓存）
 * - GET  /api/update/frontend-apk   前端 App APK 代理下载（域名白名单防 SSRF）
 * - GET  /api/update/backend-info   后端 core 自身更新信息（清单 backend 对象，只检查不安装，5min 缓存）
 */
class UpdateRoutes(
    private val updateManager: UpdateManager
) {
    /** 上传接口动态请求体上限（透传 UpdateManager：清单 apkSize×1.2+10MB 缓冲，未知回落 100MB） */
    fun uploadLimitBytes(): Long = updateManager.uploadLimitBytes()

    private val UPDATE_DIR = "/sdcard/Download/UFI-AXIS/update"

    /** frontend-apk 代理允许的 GitHub 域名（防 SSRF 白名单） */
    private val ALLOWED_PROXY_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    fun register(route: Route) {
        route.route("/update") {

            // 触发检查 + 自动更新（后端主导下载安装）
            post("/check") {
                updateManager.checkAndUpdate()
                call.respond(toJsonElement(updateManager.statusToMap()))
            }

            // 查询更新状态（P1 B2：若处于 INSTALLING，顺带读守护脚本日志 RESULT 行，
            // 使「脚本已失败但 Core 未重启」的场景也能及时反映）
            get("/status") {
                updateManager.refreshResultFromLogIfInstalling()
                call.respond(toJsonElement(updateManager.statusToMap()))
            }

            // 兜底：前端推送 APK（multipart，field=file）——用法与 FileRoutes /upload 完全一致
            // P0-8：增加 50MB 大小限制 + APK magic 头（PK\x03\x04）校验，防上传接口滥用/非 APK 文件
            // P1 A3/E25：上传互斥（download/install 进行中返回 409）+ 写临时名 .part 完成后 rename 原子提交
            post("/upload") {
                if (!updateManager.tryBeginUpload()) {
                    call.respondFail(
                        HttpStatusCode.Conflict,
                        ErrorCode.UPDATE_IN_PROGRESS,
                        "更新进行中（下载/安装/其他上传），请稍后重试"
                    )
                    return@post
                }
                var savedPath: String? = null
                var uploadError: String? = null
                try {
                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FileItem -> {
                                    if (part.name == "file") {
                                        try {
                                            File(UPDATE_DIR).mkdirs()
                                            val partFile = File(UPDATE_DIR, "ufi-core-uploaded.apk.part")
                                            val target = File(UPDATE_DIR, "ufi-core-uploaded.apk")
                                            part.streamProvider().use { input ->
                                                // APK magic 头校验：PK\x03\x04（ZIP 魔数）
                                                val header = ByteArray(4)
                                                var headerRead = 0
                                                while (headerRead < 4) {
                                                    val n = input.read(header, headerRead, 4 - headerRead)
                                                    if (n < 0) break
                                                    headerRead += n
                                                }
                                                if (headerRead < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte() ||
                                                    header[2] != 0x03.toByte() || header[3] != 0x04.toByte()
                                                ) {
                                                    throw IllegalArgumentException("不是有效的 APK 文件（magic 头不符）")
                                                }
                                                partFile.outputStream().use { out ->
                                                    out.write(header, 0, headerRead)
                                                    val buf = ByteArray(64 * 1024)
                                                    while (true) {
                                                        val n = input.read(buf)
                                                        if (n < 0) break
                                                        out.write(buf, 0, n)
                                                    }
                                                }
                                            }
                                            // 原子提交：.part → 最终文件名（并发上传只会写各自的 .part，rename 覆盖）
                                            if (target.exists()) target.delete()
                                            if (!partFile.renameTo(target)) {
                                                partFile.delete()
                                                throw IllegalArgumentException("APK 文件提交失败")
                                            }
                                            savedPath = target.absolutePath
                                        } catch (e: Exception) {
                                            AppLogger.w("UpdateRoutes", "upload 失败: ${e.message}")
                                            uploadError = e.message ?: "上传失败"
                                            File(UPDATE_DIR, "ufi-core-uploaded.apk.part").delete()
                                        }
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                    } catch (e: Exception) {
                        // receiveMultipart / forEachPart 顶层异常（连接中断、格式错误等）
                        AppLogger.w("UpdateRoutes", "upload multipart 异常: ${e.message}")
                        uploadError = e.message ?: "上传失败"
                    }
                } finally {
                    updateManager.finishUpload(success = savedPath != null, errorMessage = uploadError, apkPath = savedPath)
                }
                if (savedPath != null) {
                    call.respond(toJsonElement(mapOf("ok" to true, "apk_path" to savedPath)))
                } else {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, (uploadError ?: "未收到 APK 文件"))
                }
            }

            // 兜底：安装已上传的本地 APK
            post("/install-local") {
                val body = call.receiveJsonObject()
                val path = body["apk_path"]?.jsonPrimitive?.contentOrNull
                if (path == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 apk_path")
                    return@post
                }
                val ok = updateManager.installLocalApk(path)
                call.respond(toJsonElement(mapOf("ok" to ok, "status" to updateManager.statusToMap())))
            }

            // 重置状态
            post("/reset") {
                updateManager.reset()
                call.respond(toJsonElement(updateManager.statusToMap()))
            }

            // ══════════ 前端 App 更新转发（2026-08-10 C5）══════════
            // 前端 App 经 Core 拉取/下载（设备网络更稳，防 GitHub 被墙），
            // Core 仅做转发，不参与前端 APK 安装（前端自己走系统安装器）。

            // 前端 App 更新信息：拉取仓库根 version.json → frontend 对象
            // 拉取失败返回 502，前端显示「更新源不可用」
            get("/frontend-info") {
                val info = updateManager.fetchFrontendUpdateInfo()
                if (info == null) {
                    call.respondFail(
                        HttpStatusCode.BadGateway,
                        ErrorCode.UPSTREAM_FAILED,
                        "更新源不可用"
                    )
                    return@get
                }
                call.respond(toJsonElement(mapOf(
                    "version" to (info.version ?: ""),
                    "versionCode" to (info.versionCode ?: 0),
                    "changelog" to (info.changelog ?: ""),
                    "apk_url" to (info.apk_url ?: ""),
                    "sha256" to (info.sha256 ?: "")
                )))
            }

            // ══════════ 后端 core 更新信息（2026-09-06：只检查，不安装）══════════
            // POST /check 的语义是「检查+下载+校验+安装+重启」一条龙，Web 不能拿它当"检查"用。
            // 本端点只读清单 backend 对象做版本比对，不触碰 UpdateManager 的状态机，
            // 前端据此「先展示 当前→最新 + changelog，用户二次确认后」再打 POST /check。
            // 拉取失败返回 502，前端显示「更新源不可用」。
            get("/backend-info") {
                val info = updateManager.fetchBackendUpdateInfo()
                if (info == null) {
                    call.respondFail(
                        HttpStatusCode.BadGateway,
                        ErrorCode.UPSTREAM_FAILED,
                        "更新源不可用"
                    )
                    return@get
                }
                call.respond(toJsonElement(mapOf(
                    "current_version" to info.currentVersion,
                    "latest_version" to info.latestVersion,
                    "has_update" to info.hasUpdate,
                    "changelog" to info.changelog,
                    "apk_url" to info.apkUrl,
                    "apk_size" to info.apkSize,
                    "sha256" to info.sha256
                )))
            }

            // 前端 App APK 代理下载：?url=<apk_url>（域名白名单防 SSRF）
            // 校验通过后 HttpURLConnection 流式转发字节，Content-Type/Content-Length 透传
            get("/frontend-apk") {
                val urlStr = call.request.queryParameters["url"]?.trim().orEmpty()
                if (urlStr.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 url 参数")
                    return@get
                }
                // 域名白名单：仅允许 github.com / objects.githubusercontent.com 或与清单 frontend.apk_url 同源
                if (!isAllowedApkProxyUrl(urlStr)) {
                    AppLogger.w("UpdateRoutes", "frontend-apk 拒绝非白名单 URL: $urlStr")
                    call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "URL 不在白名单")
                    return@get
                }
                // ① 打开上游连接（此阶段可安全 respond 502）
                var upstream: HttpURLConnection? = null
                try {
                    upstream = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                        instanceFollowRedirects = true
                    }
                    if (upstream.responseCode !in 200..299) {
                        call.respondFail(
                            HttpStatusCode.BadGateway,
                            ErrorCode.UPSTREAM_FAILED,
                            "上游下载失败: HTTP ${upstream.responseCode}"
                        )
                        return@get
                    }
                } catch (e: Exception) {
                    AppLogger.w("UpdateRoutes", "frontend-apk 上游连接失败: ${e.message}")
                    call.respondFail(
                        HttpStatusCode.BadGateway,
                        ErrorCode.UPSTREAM_FAILED,
                        "上游连接失败: ${e.message}"
                    )
                    return@get
                }
                // ② 流式转发（响应头已提交，异常仅记录，不再 respond）
                val conn = upstream ?: run {
                    call.respondFail(HttpStatusCode.BadGateway, ErrorCode.UPSTREAM_FAILED, "上游连接为空")
                    return@get
                }
                try {
                    val rawContentType = conn.contentType
                        ?.takeIf { it.isNotBlank() }
                        ?: "application/vnd.android.package-archive"
                    val contentType = try {
                        ContentType.parse(rawContentType)
                    } catch (e: Exception) {
                        ContentType.Application.OctetStream
                    }
                    val contentLength = conn.contentLengthLong
                    if (contentLength > 0) {
                        call.response.header(HttpHeaders.ContentLength, contentLength.toString())
                    }
                    call.respondOutputStream(contentType) {
                        withContext(Dispatchers.IO) {
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    this@respondOutputStream.write(buf, 0, n)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w("UpdateRoutes", "frontend-apk 流式转发中断: ${e.message}")
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * frontend-apk 代理域名白名单（防 SSRF）：
     * 仅允许 github.com / objects.githubusercontent.com，或与清单 frontend.apk_url 同源。
     */
    private fun isAllowedApkProxyUrl(urlStr: String): Boolean {
        val host = try { URL(urlStr).host?.lowercase() } catch (e: Exception) { return false }
        if (host.isNullOrBlank()) return false
        if (host in ALLOWED_PROXY_HOSTS) return true
        // 与清单 apk_url 同源（如自建 CDN/镜像时放行同 host）
        val manifestUrl = updateManager.cachedFrontendApkUrl()
        if (manifestUrl != null) {
            val manifestHost = try { URL(manifestUrl).host?.lowercase() } catch (e: Exception) { null }
            if (!manifestHost.isNullOrBlank() && host == manifestHost) return true
        }
        return false
    }
}

/** UpdateStatus → JSON map（供路由与前端展示；state 统一小写以匹配前端状态机约定） */
fun UpdateManager.statusToMap(): Map<String, Any?> = mapOf(
    "state" to status.state.name.lowercase(),
    "progress" to status.progress,
    "message" to status.message,
    "current_version" to status.currentVersion,
    "latest_version" to status.latestVersion,
    "apk_path" to status.apkPath
)
