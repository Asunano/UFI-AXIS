@file:OptIn(ExperimentalSerializationApi::class)

package com.ufi_axis_core.api.update

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.WebResourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Web 前端自动更新管理器（2026-08-18，与后端 UpdateManager 平行的独立通道）。
 *
 * 职责（Core 从 root version.json 的 `web` 对象自拉取 Web ZIP 并覆盖安装）：
 * - [checkAndUpdate]：GET [AppSettings.updateUrl]（版本清单 JSON）→ 取 `web` 对象 →
 *   比对当前内置/覆盖 web 版本（[WebResourceManager.getVersionInfo].version）→
 *   有新版则下载 apkUrl（ZIP）→ SHA-256 校验 → [WebResourceManager.installFromZip] 原子覆盖 assets/web；
 * - [statusToMap]：状态供前端 GET /api/web/status 轮询。
 *
 * 与 backend UpdateManager 的区别：
 * - Web 是 ZIP 资源覆盖，不需要 APK 静默安装 / 守护脚本 / 进程重启；installFromZip 同步原子替换。
 * - 当前版本来自 web/version.json 的 version 字段（bundled 或 override），而非 BuildConfig。
 * - 镜像前缀（GitHub 被墙加速）、HTTPS 强制、清单 JSON 解析逻辑与 backend 保持一致。
 *
 * 版本清单 web 对象格式（与 frontend/backend 同结构，双名兼容）：
 * `{"web":{"version":"0.2","apkUrl":"https://.../web-0.2.zip","apkSha256":"<64位hex>"}}`
 */
class WebUpdateManager(
    private val context: Context,
    private val settings: AppSettings,
    private val webResourceManager: WebResourceManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** 更新互斥：checkAndUpdate 统一入口 CAS，防并发/重入 */
    private val updateMutex = AtomicBoolean(false)

    enum class State { IDLE, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED, NEED_PUSH }

    data class UpdateStatus(
        val state: State = State.IDLE,
        val progress: Int = 0,
        val message: String = "",
        val currentVersion: String? = null,
        val latestVersion: String? = null
    )

    @Volatile
    var status: UpdateStatus = UpdateStatus(currentVersion = currentWebVersion())
        private set

    /** web 版本清单对象（与 backend VersionManifest 同字段、双名兼容） */
    @Serializable
    private data class WebVersionManifest(
        @JsonNames("versionName") val version: String? = null,
        @JsonNames("apkUrl") val apk_url: String? = null,
        @JsonNames("apkSha256") val sha256: String? = null,
        @JsonNames("apkSize") val apk_size: Long? = null
    )

    /** GitHub 相关域名（命中即走镜像） */
    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com"
    )

    private val MAX_WEB_ZIP_BYTES = 50L * 1024 * 1024

    /** 触发检查 + 自动更新（异步；立即返回当前状态，进度经 [status] 轮询） */
    fun checkAndUpdate() {
        scope.launch {
            if (status.state in setOf(State.DOWNLOADING, State.VERIFYING, State.INSTALLING) ||
                !updateMutex.compareAndSet(false, true)
            ) {
                AppLogger.i(TAG, "checkAndUpdate(web) 忽略重复触发 (state=${status.state})")
                return@launch
            }
            try {
                val current = currentWebVersion()
                status = UpdateStatus(State.DOWNLOADING, 0, "正在检查 Web 更新源...", current)

                // ① 拉取版本清单（update_url → version.json）；GitHub 域名自动拼镜像前缀
                val manifestUrl = applyMirrorToUrl(settings.updateUrl)
                requireSecureUrl(manifestUrl, "更新源")
                val manifestJson = fetchUrl(manifestUrl, timeoutMs = 15_000)
                    ?: throw NeedPushException("更新源不可达: $manifestUrl")
                val root = try {
                    json.parseToJsonElement(manifestJson).jsonObject
                } catch (e: Exception) {
                    throw NeedPushException("更新源不是版本清单 JSON: ${e.message}")
                }
                val obj = root["web"]?.jsonObject
                    ?: throw NeedPushException("版本清单缺少 web 对象（未配置 Web 更新）")
                val manifest = try {
                    json.decodeFromJsonElement(WebVersionManifest.serializer(), obj)
                } catch (e: Exception) {
                    throw NeedPushException("web 对象解析失败: ${e.message}")
                }
                val latest = manifest.version
                    ?: throw NeedPushException("web 对象缺少 version 字段")
                val apkUrl = manifest.apk_url
                    ?: throw NeedPushException("web 对象缺少 apk_url 字段")
                // sha256 必填——缺失即拒绝更新（防旧格式/防篡改）
                val expectedSha = manifest.sha256
                    ?: throw NeedPushException("web 对象缺少 sha256 字段（旧格式已拒绝更新）")

                // ② 版本比对（禁止降级）
                if (!current.isNullOrBlank() && compareVersions(latest, current) <= 0) {
                    status = UpdateStatus(State.IDLE, 0, "Web 已是最新版本 ($current)", current, latest)
                    return@launch
                }
                status = UpdateStatus(State.DOWNLOADING, 0, "发现新版 Web v$latest，开始下载...", current, latest)

                // ③ 下载 ZIP（写临时文件 + 流式 SHA-256 计算）
                requireSecureUrl(apkUrl, "Web 下载地址")
                val tmpFile = File(context.filesDir, "web_update.tmp.zip")
                tmpFile.delete()
                val actualSha = try {
                    downloadWebZip(applyMirrorToUrl(apkUrl), tmpFile)
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw NeedPushException("Web ZIP 下载失败: ${e.message}")
                }
                if (!actualSha.equals(expectedSha.trim().lowercase(Locale.ROOT), ignoreCase = true)) {
                    tmpFile.delete()
                    throw Exception("Web ZIP SHA-256 校验失败（VERIFY_FAIL）：期望 $expectedSha，实际 $actualSha")
                }

                // ④ 原子安装（覆盖 assets/web；失败回滚保留旧版）
                status = UpdateStatus(State.INSTALLING, 100, "校验通过，正在安装 Web...", current, latest)
                val result = try {
                    webResourceManager.installFromZip(FileInputStream(tmpFile))
                } finally {
                    tmpFile.delete()
                }
                if (result.isFailure) {
                    throw Exception("Web 安装失败: ${result.exceptionOrNull()?.message}")
                }
                status = UpdateStatus(State.DONE, 100, "Web 已更新到 v$latest", current, latest)
                AppLogger.i(TAG, "checkAndUpdate(web) 成功: v$latest")
            } catch (e: NeedPushException) {
                // 更新源不可用（不可达/非 JSON/下载失败）→ NEED_PUSH，引导前端走手动上传兜底
                AppLogger.w(TAG, "checkAndUpdate(web) 更新源不可用: ${e.message}")
                status = status.copy(state = State.NEED_PUSH, message = "Web 更新源不可用，可手动上传 ZIP：${e.message}")
            } catch (e: Exception) {
                AppLogger.w(TAG, "checkAndUpdate(web) 失败: ${e.message}")
                status = status.copy(state = State.FAILED, message = e.message ?: "Web 更新失败")
            } finally {
                updateMutex.set(false)
            }
        }
    }

    /** 当前 Web 版本（bundled 或 override 的 web/version.json 的 version 字段） */
    private fun currentWebVersion(): String? {
        return try {
            webResourceManager.getVersionInfo()["version"] as? String
        } catch (e: Exception) {
            AppLogger.w(TAG, "currentWebVersion 异常: ${e.message}")
            null
        }
    }

    /** 更新状态 → JSON map（state 统一小写以匹配前端状态机约定） */
    fun statusToMap(): Map<String, Any?> = mapOf(
        "state" to status.state.name.lowercase(),
        "progress" to status.progress,
        "message" to status.message,
        "current_version" to status.currentVersion,
        "latest_version" to status.latestVersion
    )

    // ── 工具（与 backend UpdateManager 一致）──

    /** GitHub 域名 URL 前拼接 [AppSettings.updateMirrorBase]；非 GitHub 原样返回；空串=直连 */
    private fun applyMirrorToUrl(urlStr: String): String {
        if (urlStr.isBlank()) return urlStr
        val base = settings.updateMirrorBase.trim().trimEnd('/')
        if (base.isEmpty()) return urlStr
        val host = try { URL(urlStr).host?.lowercase() } catch (e: Exception) { return urlStr }
        if (host.isNullOrBlank()) return urlStr
        if (host in GITHUB_HOSTS || host.endsWith(".githubusercontent.com")) {
            return "$base/$urlStr"
        }
        return urlStr
    }

    /** GET URL 返回文本（版本清单） */
    private fun fetchUrl(urlStr: String, timeoutMs: Int): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
            instanceFollowRedirects = true
        }
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    } catch (e: Exception) {
        AppLogger.w(TAG, "fetchUrl 失败: ${e.message}")
        null
    }

    /** 更新源/APK 地址强制 HTTPS（本地调试 IP 例外） */
    private fun requireSecureUrl(urlStr: String, what: String) {
        val u = try {
            URL(urlStr)
        } catch (e: Exception) {
            throw Exception("$what 不是合法 URL: $urlStr")
        }
        if (u.protocol.equals("https", ignoreCase = true)) return
        val host = u.host ?: ""
        val isLocalDebug = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.3") ||
            host.endsWith(".local")
        if (!isLocalDebug) {
            throw Exception("$what 必须使用 HTTPS（当前协议: ${u.protocol}）")
        }
    }

    /**
     * 流式下载 Web ZIP 到临时文件（不落全量内存，适配低端设备），返回文件 SHA-256 hex。
     * 下载前删旧临时文件；contentLength 超 50MB 提前失败。
     */
    private fun downloadWebZip(urlStr: String, target: File): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 60_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw Exception("Web ZIP 下载失败: HTTP ${conn.responseCode}")
            val declared = conn.contentLengthLong
            if (declared > 0 && declared > MAX_WEB_ZIP_BYTES) {
                throw Exception("Web ZIP 超过 ${MAX_WEB_ZIP_BYTES / 1024 / 1024}MB 限制")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            }
            conn.disconnect(); conn = null
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 语义化版本比较（a>b 返回正，相等 0，a<b 负） */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.trim().split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 更新源不可用异常——catch 后置 NEED_PUSH（区别于一般失败 FAILED） */
    private class NeedPushException(message: String) : Exception(message)

    private companion object {
        const val TAG = "WebUpdateManager"
    }
}
