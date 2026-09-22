@file:OptIn(ExperimentalSerializationApi::class)

package com.ufi_axis_core.api.components

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.BinaryComponentMeta
import com.ufi_axis_core.util.BinaryComponentStore
import com.ufi_axis_core.util.TarGzExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可选二进制组件管理器（2026-09-01：frpc / cloudflared 不再随 APK 分发）。
 *
 * 背景：这两个二进制解压后合计约 50MB，压缩后占 core APK 的一半体积，而只有用内网穿透的
 * 用户才需要。改为按需下载后 APK 从约 60MB 降到约 34MB。
 *
 * 职责：
 * - [listComponents]：组件清单（已装版本 / 远端最新版本 / 体积 / 状态），供前端渲染；
 * - [install]：从 [AppSettings.updateUrl] 的 `components` 段取直链 → 镜像前缀 → 流式下载并
 *   同步算 SHA-256 → 校验 → （tar.gz 则提取 entry）→ ELF 体检 → 原子安装；
 * - [installFromUpload]：本地上传兜底（无外网环境），按文件头自动识别裸二进制 / tar.gz，
 *   跳过 sha256 比对（没有期望值），安装后跑 `--version` 回填版本；
 * - [uninstall]：删除二进制与元数据（"是否有实例在跑"的前置检查由路由层负责，那里才看得到引擎）。
 *
 * 与 backend [com.ufi_axis_core.api.update.UpdateManager] / WebUpdateManager 的一致之处：
 * 镜像前缀、HTTPS 强制、流式下载 + SHA-256、清单 JSON 双名解析全部沿用同一套约定。
 * 不同之处：**不做自动下载**——组件动辄几十 MB，可能在计费流量上，必须用户显式点击。
 */
class ComponentManager(
    private val context: Context,
    private val settings: AppSettings,
    private val store: BinaryComponentStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** 同一时刻只允许一个安装任务（组件体积大，并发下载只会互相拖慢并翻倍占盘） */
    private val busy = AtomicBoolean(false)

    enum class State { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, INSTALLING, DONE, FAILED }

    data class Progress(
        val id: String = "",
        val state: State = State.IDLE,
        val percent: Int = 0,
        val message: String = ""
    )

    @Volatile
    var progress: Progress = Progress()
        private set

    /** 远端清单缓存（`components` 段）；null 表示尚未成功拉取过 */
    @Volatile
    private var manifestCache: Map<String, ComponentManifest>? = null

    @Volatile
    private var manifestError: String = ""

    /** 清单是否正在后台拉取（见 [fetchManifestAsync]），经 [statusToMap] 暴露给前端做加载态 */
    @Volatile
    private var manifestLoading: Boolean = false


    /** version.json 的 components.<id> 对象 */
    @Serializable
    data class ComponentManifest(
        val upstream: String? = null,
        @JsonNames("versionName") val version: String? = null,
        @JsonNames("apkUrl") val url: String? = null,
        @JsonNames("apkSha256") val sha256: String? = null,
        @JsonNames("apkSize") val size: Long? = null,
        /** raw = 直接就是可执行文件；tar.gz = 需要从包内取 [entry] */
        val archive: String? = null,
        val entry: String? = null
    )

    /** 内置组件目录：决定前端展示哪些组件、以及每个组件的用途说明 */
    data class CatalogItem(val id: String, val displayName: String, val description: String)

    private val catalog = listOf(
        CatalogItem(
            BinaryComponentStore.ID_FRPC,
            "frpc",
            "FRP 内网穿透客户端，用于把本机端口映射到自有 frps 服务器"
        ),
        CatalogItem(
            BinaryComponentStore.ID_CLOUDFLARED,
            "cloudflared",
            "Cloudflare Tunnel 客户端，用于通过 Cloudflare 边缘网络暴露本机服务"
        )
    )

    // ── 查询 ──

    /** 组件是否已安装（引擎启动前的前置判断走这里） */
    fun isInstalled(id: String): Boolean = store.isInstalled(id)

    /**
     * 组件列表：本地状态 + 远端清单。
     *
     * [refresh] 为 true 时**同步**重新拉清单（前端点「检查更新」用，可以让用户等）。
     *
     * 2026-09-03：refresh=false 且缓存为空时不再同步 `fetchManifest()`。
     * 原来那样等于让"进页面"这个动作硬等一次 15s 超时的外网请求，前端只能干等或先显示空列表 ——
     * 用户的观感就是"必须点检查更新才出内容"。现在改成：本地状态（已装/未装/占用）立刻返回，
     * 清单在后台协程里补，期间 [statusToMap] 的 `manifest_loading=true`，前端据此显示加载态并稍后回读。
     */
    fun listComponents(refresh: Boolean = false): List<Map<String, Any?>> {
        if (refresh) fetchManifest() else if (manifestCache == null) fetchManifestAsync()
        val remote = manifestCache ?: emptyMap()
        return catalog.map { item ->
            val meta = store.readMeta(item.id)
            val installed = store.isInstalled(item.id)
            val r = remote[item.id]
            val latest = r?.version?.trim().orEmpty()
            val current = meta?.version?.trim().orEmpty()
            val updateAvailable = installed && latest.isNotEmpty() && current.isNotEmpty() &&
                compareVersions(latest, current) > 0
            mapOf(
                "id" to item.id,
                "name" to item.displayName,
                "description" to item.description,
                "installed" to installed,
                "installed_version" to current,
                "installed_size" to (if (installed) store.binaryFile(item.id).length() else 0L),
                "source" to (meta?.source ?: ""),
                "installed_at" to (meta?.installedAt ?: 0L),
                "latest_version" to latest,
                "download_size" to (r?.size ?: 0L),
                "archive" to (r?.archive ?: ""),
                "upstream" to (r?.upstream ?: ""),
                "available" to (r?.url?.isNotBlank() == true),
                "update_available" to updateAvailable
            )
        }
    }

    /** 安装进度 → JSON map（state 小写，与 /api/update/status 等既有约定一致） */
    fun statusToMap(): Map<String, Any?> = mapOf(
        "id" to progress.id,
        "state" to progress.state.name.lowercase(),
        "percent" to progress.percent,
        "message" to progress.message,
        "manifest_error" to manifestError,
        // 清单正在后台拉取：前端据此显示"正在获取最新版本"并过一会儿回读，而不是让用户点「检查更新」
        "manifest_loading" to manifestLoading,
        "dir_bytes" to store.totalBytes()
    )

    // ── 远端安装 ──

    /**
     * 触发下载安装（异步；立即返回，进度经 [statusToMap] 轮询）。
     * @return 失败原因；null 表示已受理
     */
    fun install(id: String): String? {
        val validId = runCatching { BinaryComponentStore.requireValidId(id) }.getOrNull()
            ?: return "非法组件 id"
        if (catalog.none { it.id == validId }) return "未知组件: $validId"
        if (!busy.compareAndSet(false, true)) return "已有组件正在安装中，请稍候"
        progress = Progress(validId, State.DOWNLOADING, 0, "准备下载...")
        scope.launch {
            try {
                doInstall(validId)
            } catch (e: Exception) {
                AppLogger.w(TAG, "install[$validId] 失败: ${e.message}")
                progress = Progress(validId, State.FAILED, progress.percent, e.message ?: "安装失败")
            } finally {
                busy.set(false)
            }
        }
        return null
    }

    private fun doInstall(id: String) {
        if (manifestCache == null) fetchManifest()
        val m = manifestCache?.get(id)
            ?: throw Exception("更新源缺少 components.$id（${manifestError.ifBlank { "清单未包含该组件" }}）")
        val url = m.url?.takeIf { it.isNotBlank() } ?: throw Exception("components.$id 缺少 url")
        // sha256 必填：组件是要被执行的二进制，无校验的公网下载不可接受（与 UpdateManager 同一条硬规则）
        val expectedSha = m.sha256?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("components.$id 缺少 sha256，已拒绝安装")
        val isArchive = m.archive.equals("tar.gz", ignoreCase = true)
        val entry = if (isArchive) (m.entry?.takeIf { it.isNotBlank() } ?: id) else null
        requireSecureUrl(url, "组件下载地址")

        // 磁盘预检：tar.gz 峰值 = 压缩包 + 解出的二进制，按 3 倍声明体积预留
        val declared = m.size ?: 0L
        val need = if (declared > 0) declared * (if (isArchive) 3 else 2) else 96L * 1024 * 1024
        val free = store.dir.usableSpace
        if (free in 1 until need) {
            throw Exception("磁盘空间不足：需要约 ${need / 1024 / 1024}MB，可用 ${free / 1024 / 1024}MB")
        }

        val download = store.tempFile("$id.download")
        val extracted = store.tempFile("$id.bin")
        try {
            progress = Progress(id, State.DOWNLOADING, 0, "正在下载 $id v${m.version ?: "?"}...")
            val actualSha = downloadTo(applyMirrorToUrl(url), download) { pct ->
                progress = progress.copy(state = State.DOWNLOADING, percent = pct)
            }

            progress = Progress(id, State.VERIFYING, 100, "正在校验 SHA-256...")
            if (!actualSha.equals(expectedSha.lowercase(Locale.ROOT), ignoreCase = true)) {
                throw Exception("SHA-256 校验失败：期望 $expectedSha，实际 $actualSha")
            }

            val binary = if (isArchive) {
                progress = Progress(id, State.EXTRACTING, 100, "正在从压缩包提取 $entry...")
                TarGzExtractor.extractEntry(download, entry!!, extracted, MAX_BINARY_BYTES).getOrThrow()
                extracted
            } else {
                download
            }

            progress = Progress(id, State.INSTALLING, 100, "正在安装...")
            store.install(
                id, binary,
                BinaryComponentMeta(
                    id = id,
                    version = m.version?.trim().orEmpty(),
                    sha256 = expectedSha.lowercase(Locale.ROOT),
                    source = BinaryComponentStore.SOURCE_REMOTE,
                    installedAt = System.currentTimeMillis()
                )
            ).getOrThrow()
            progress = Progress(id, State.DONE, 100, "$id v${m.version ?: ""} 已安装")
            AppLogger.i(TAG, "组件安装完成: $id v${m.version}")
        } finally {
            download.delete()
            extracted.delete()
        }
    }

    // ── 本地上传兜底 ──

    /**
     * 从上传流安装组件（无外网环境兜底）。
     *
     * 按文件头自动识别：`1F 8B` 走 tar.gz 提取，其余按裸二进制处理（ELF 体检在 [store] 里做）。
     * 没有期望 sha256 可比，故只记录实际值，元数据标 [BinaryComponentStore.SOURCE_MANUAL]。
     */
    fun installFromUpload(id: String, body: InputStream): Result<String> = runCatching {
        val validId = BinaryComponentStore.requireValidId(id)
        require(catalog.any { it.id == validId }) { "未知组件: $validId" }
        // 互斥必须在 try 之外获取：放进 try 的话，"抢锁失败"也会走到 finally 里 busy.set(false)，
        // 等于把别人正在持有的锁释放掉。
        if (!busy.compareAndSet(false, true)) throw Exception("已有组件正在安装中，请稍候")
        val upload = store.tempFile("$validId.upload")
        val extracted = store.tempFile("$validId.bin")
        try {
            progress = Progress(validId, State.INSTALLING, 0, "正在接收上传...")
            val sha = MessageDigest.getInstance("SHA-256")
            var total = 0L
            upload.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BINARY_BYTES) throw Exception("上传体积超过 ${MAX_BINARY_BYTES / 1024 / 1024}MB 限制")
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                }
            }
            require(total > 0) { "上传内容为空" }

            val head = upload.inputStream().use { ins -> ByteArray(2).also { ins.read(it) } }
            val isGzip = head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
            val binary = if (isGzip) {
                progress = Progress(validId, State.EXTRACTING, 50, "正在从压缩包提取 $validId...")
                TarGzExtractor.extractEntry(upload, validId, extracted, MAX_BINARY_BYTES).getOrThrow()
                extracted
            } else {
                upload
            }

            progress = Progress(validId, State.INSTALLING, 90, "正在安装...")
            store.install(
                validId, binary,
                BinaryComponentMeta(
                    id = validId,
                    version = "",
                    sha256 = sha.digest().joinToString("") { "%02x".format(it) },
                    source = BinaryComponentStore.SOURCE_MANUAL,
                    installedAt = System.currentTimeMillis()
                )
            ).getOrThrow()
            // 手动上传没有版本信息，只能安装后现场探测
            val probed = probeVersion(validId)
            if (!probed.isNullOrBlank()) store.updateMeta(validId) { it.copy(version = probed) }
            progress = Progress(validId, State.DONE, 100, "$validId 已安装（本地上传${if (probed.isNullOrBlank()) "" else " v$probed"}）")
            probed.orEmpty()
        } catch (e: Exception) {
            progress = Progress(validId, State.FAILED, progress.percent, e.message ?: "上传安装失败")
            throw e
        } finally {
            upload.delete()
            extracted.delete()
            busy.set(false)
        }
    }

    // ── 卸载 ──

    /** 卸载组件（调用方须先确认没有实例在跑） */
    fun uninstall(id: String): Result<Unit> = runCatching {
        val validId = BinaryComponentStore.requireValidId(id)
        require(store.isInstalled(validId)) { "$validId 尚未安装" }
        require(store.remove(validId)) { "$validId 删除失败" }
        if (progress.id == validId) progress = Progress()
    }

    /** 服务启动时清理上次中断的下载临时文件 */
    fun cleanupTemp() = store.cleanupTemp()

    /**
     * 启动期维护：清临时文件 + 旧版迁移，**异步执行，绝不占用启动路径**。
     *
     * 早期版本在 `ComponentFactory.build()` 里同步调这两个方法，于是启动被拖在
     * 「正在初始化组件...」——迁移里要搬几十 MB 的二进制，还要跑 `--version` 探测版本
     * （那次探测又没有读超时，见 [probeVersion]）。HTTP 服务在 build() 之后才起，
     * 任何一步卡住就是"通知栏一直显示正在启动"。
     *
     * 迁移晚几秒完成没有代价：隧道看护每轮都会重新查 `isInstalled()`。
     */
    fun startStartupMaintenance() {
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                store.cleanupTemp()
                migrateLegacyBinaries()
                AppLogger.i(TAG, "组件启动期维护完成（${System.currentTimeMillis() - t0}ms）")
            } catch (e: Exception) {
                AppLogger.w(TAG, "组件启动期维护失败: ${e.message}")
            }
        }
    }

    /**
     * 旧版迁移（2026-09-01）：把内置时代残留在 `filesDir/shell/` 的 frpc / cloudflared
     * 搬进组件目录，避免用户升级后隧道突然报"组件未安装"。
     * 迁移来的二进制没有可信版本号，安装后现场探测；来源标 legacy 以便前端提示可更新。
     * 本方法在下一个大版本后可删除。
     */
    fun migrateLegacyBinaries() {
        val shellDir = File(context.filesDir, "shell")
        for (item in catalog) {
            val legacy = File(shellDir, item.id)
            if (!legacy.exists() || legacy.length() <= 0) continue
            if (store.isInstalled(item.id)) {
                legacy.delete()
                continue
            }
            val result = store.install(
                item.id, legacy,
                BinaryComponentMeta(
                    id = item.id,
                    source = BinaryComponentStore.SOURCE_LEGACY,
                    installedAt = System.currentTimeMillis()
                )
            )
            if (result.isFailure) {
                AppLogger.w(TAG, "旧版组件迁移失败[${item.id}]: ${result.exceptionOrNull()?.message}")
                continue
            }
            val probed = probeVersion(item.id)
            if (!probed.isNullOrBlank()) store.updateMeta(item.id) { it.copy(version = probed) }
            AppLogger.i(TAG, "旧版组件已迁入组件目录: ${item.id} v${probed.orEmpty()}")
        }
    }

    /**
     * 跑 `<binary> --version` 取版本号（取第一行末尾的版本片段）。
     * 只在"没有可信版本来源"时使用（手动上传 / 旧版迁移），远端安装直接用清单值。
     *
     * ⚠️ 读输出**必须带超时**：这里执行的是外部下载来的二进制，可能什么都不往 stdout 写、
     * 也可能压根不退出（架构不符 / 动态链接失败 / 等输入）。早期实现直接
     * `inputStream.readLine()` 再 `waitFor(3s)`，`readLine()` 会先无限期阻塞，
     * 后面那个 3 秒超时永远走不到 —— 一旦发生就是整条调用链挂死。
     */
    private fun probeVersion(id: String): String? {
        val binary = store.binaryFile(id)
        if (!binary.exists()) return null
        var proc: Process? = null
        return try {
            val p = ProcessBuilder(binary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            proc = p
            val line = readFirstLineWithin(p, PROBE_TIMEOUT_SEC)
            // frpc: "frpc version 0.65.0"；cloudflared: "cloudflared version 2025.8.1 (built ...)"
            Regex("""(\d+(?:\.\d+){1,3})""").find(line)?.value
        } catch (e: Exception) {
            AppLogger.w(TAG, "probeVersion[$id] 失败: ${e.message}")
            null
        } finally {
            runCatching { proc?.destroyForcibly() }
        }
    }

    /** 在 [timeoutSec] 内读子进程第一行输出，超时返回空串（读线程被弃用，随进程 destroy 结束）。 */
    private fun readFirstLineWithin(p: Process, timeoutSec: Long): String {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "component-probe").apply { isDaemon = true }
        }
        return try {
            executor.submit<String> { p.inputStream.bufferedReader().readLine().orEmpty() }
                .get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "probeVersion 读取超时/失败（${timeoutSec}s）: ${e.javaClass.simpleName}")
            ""
        } finally {
            executor.shutdownNow()
        }
    }

    // ── 清单拉取 ──

    /**
     * 后台拉一次清单（不阻塞调用方）。同一时刻只允许一个在飞：
     * 前端「进页面 + 每 1.5s 回读」会连续调 listComponents，不去重会把外网请求打成风暴。
     */
    private fun fetchManifestAsync() {
        if (manifestLoading) return
        manifestLoading = true
        scope.launch {
            try {
                fetchManifest()
            } finally {
                manifestLoading = false
            }
        }
    }

    /** 同步拉取 `version.json` 的 components 段并写入缓存；失败只记 [manifestError]，不抛 */
    private fun fetchManifest() {
        try {
            val manifestUrl = applyMirrorToUrl(settings.updateUrl)
            requireSecureUrl(manifestUrl, "更新源")
            val text = fetchUrl(manifestUrl, 15_000) ?: throw Exception("更新源不可达: $manifestUrl")
            val root = json.parseToJsonElement(text).jsonObject
            val obj = root["components"]?.jsonObject
                ?: throw Exception("更新源缺少 components 段（请更新 version.json）")
            manifestCache = obj.mapValues { (_, v) ->
                json.decodeFromJsonElement(ComponentManifest.serializer(), v)
            }
            manifestError = ""
        } catch (e: Exception) {
            manifestError = e.message ?: "组件清单拉取失败"
            AppLogger.w(TAG, "fetchManifest 失败: $manifestError")
        }
    }

    // ── 网络工具（与 backend UpdateManager / WebUpdateManager 保持同一套约定）──

    /** GitHub 域名 URL 前拼接 [AppSettings.updateMirrorBase]；非 GitHub 原样返回；空串=直连 */
    private fun applyMirrorToUrl(urlStr: String): String {
        if (urlStr.isBlank()) return urlStr
        val base = settings.updateMirrorBase.trim().trimEnd('/')
        if (base.isEmpty()) return urlStr
        val host = try { URL(urlStr).host?.lowercase() } catch (e: Exception) { return urlStr }
        if (host.isNullOrBlank()) return urlStr
        if (host in GITHUB_HOSTS || host.endsWith(".githubusercontent.com")) return "$base/$urlStr"
        return urlStr
    }

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

    /** 下载地址强制 HTTPS（本地调试 IP 例外） */
    private fun requireSecureUrl(urlStr: String, what: String) {
        val u = try { URL(urlStr) } catch (e: Exception) { throw Exception("$what 不是合法 URL: $urlStr") }
        if (u.protocol.equals("https", ignoreCase = true)) return
        val host = u.host ?: ""
        val isLocalDebug = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.") || host.endsWith(".local")
        if (!isLocalDebug) throw Exception("$what 必须使用 HTTPS（当前协议: ${u.protocol}）")
    }

    /**
     * 流式下载到 [target]（不落全量内存，适配低端设备），返回文件 SHA-256 hex。
     * [onProgress] 在 Content-Length 已知时按百分比回调。
     */
    private fun downloadTo(urlStr: String, target: File, onProgress: (Int) -> Unit): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw Exception("下载失败: HTTP ${conn.responseCode}")
            val declared = conn.contentLengthLong
            if (declared > MAX_BINARY_BYTES) {
                throw Exception("下载体积 ${declared / 1024 / 1024}MB 超过 ${MAX_BINARY_BYTES / 1024 / 1024}MB 限制")
            }
            target.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastPct = -1
            conn.inputStream.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (written > MAX_BINARY_BYTES) throw Exception("下载体积超过上限")
                        if (declared > 0) {
                            val pct = ((written * 100) / declared).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
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
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private companion object {
        const val TAG = "ComponentManager"

        /** 单个组件上限：cloudflared 裸二进制约 36MB，留足余量同时挡住异常大文件 */
        const val MAX_BINARY_BYTES = 96L * 1024 * 1024

        /** `--version` 探测的读超时：跑的是外部二进制，绝不允许无上限等待 */
        const val PROBE_TIMEOUT_SEC = 3L

        val GITHUB_HOSTS = listOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com"
        )
    }
}
