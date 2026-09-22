package com.ufi_axis_core.api.routes

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.files.FileProvider
import com.ufi_axis_core.api.files.FileProviderRegistry
import com.ufi_axis_core.api.files.LocalFileProvider
import com.ufi_axis_core.api.files.RemotePushManager
import com.ufi_axis_core.api.files.UploadSessionStore
import com.ufi_axis_core.api.media.MediaTicketStore
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.MimeTypes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import com.ufi_axis_core.util.ArchiveExtractors

/**
 * 文件管理路由 — 无 root 的本地文件管理器（基础 NAS 能力）。
 *
 * 全部操作基于 java.io.File / java.nio.file，不再调用 su / ShellExecutor。
 * 仅允许访问用户存储（内部存储 + SD 卡），任何指向系统目录的路径都被拒绝。
 *
 * [registry] 提供存储提供者的解析与分发能力。路径以 `remote:` 开头时
 * 路由到对应的远程提供者；否则走本地逻辑（行为与引入 registry 之前完全一致）。
 */
class FileRoutes(private val registry: FileProviderRegistry) {

    /**
     * 便捷访问本地提供者。所有原有的本地逻辑继续通过 FileRoutes 自身的私有方法执行，
     * 此属性仅在需要通过 provider 接口调用本地操作时使用。
     */
    val local: LocalFileProvider
        get() = registry.get(FileProviderRegistry.LOCAL_ID) as LocalFileProvider

    companion object {
        private const val MAX_READ_SIZE = 512 * 1024 // 512 KB 文本读取上限
        private const val MAX_DOWNLOAD_SIZE = 50L * 1024 * 1024 // 50 MB 下载上限
        private const val INTERNAL_STORAGE = "/storage/emulated/0"

        /**
         * 整体上传（`POST /upload`）的请求体上限，**HTTP 层与路由层共用这一个常量**。
         *
         * 定义在这里而不是 `HttpServer`：那边的 `RequestBodyLimit` 插件按路径前缀分流，
         * 上限必须与实现这条路由的类保持一致。这正是仓库已有的做法 ——
         * `HttpServer` 同样引用 `BackupRoutes.MAX_UPLOAD_BYTES`，而且代码里写明了理由：
         * 「两处漂移的后果是路由层按 8MB 设计、HTTP 层按 512KB 拦」，表现为
         * 「小文件能传、稍大的莫名 413」这种极难定位的问题。
         */
        const val UPLOAD_BODY_LIMIT = 200L * 1024 * 1024

        /**
         * 单个分片的请求体上限。给 [UPLOAD_CHUNK_SIZE] 留一倍余量 ——
         * 客户端必须用服务端下发的 chunk_size，超过这个值只可能是客户端有 bug。
         */
        const val CHUNK_BODY_LIMIT = 8L * 1024 * 1024

        /**
         * 分片大小，**由服务端决定并下发**，客户端不得自定。
         *
         * 客户端各自定会导致切片边界不一致，续传时 `received / chunk_size` 算出的
         * 下一片序号就对不上，表现为"续传后文件损坏"。
         *
         * 4MB 的取值：5MB/s 上行下约 1 秒一片，单片失败重传代价小；再小则 HTTP 往返
         * 与**每片都要重算的设备签名**开销占比过高。
         */
        const val UPLOAD_CHUNK_SIZE = 4L * 1024 * 1024

        /**
         * 分片上传的单文件上限 2GB。
         *
         * 真正的约束不是这个数，而是**目标卷的剩余空间** —— 开会话时就用 StatFs 查一次
         * 并直接拒，比传到一半 ENOSPC 好。这个常量只是防住"声明一个荒谬的 size"。
         */
        const val MAX_CHUNKED_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * 开会话时要求的空闲空间余量：`声明大小 + 这个值`。
         * 不留余量的话，传完正好把卡写满，之后连 rename 的元数据都可能写不进去。
         */
        private const val FREE_SPACE_HEADROOM = 64L * 1024 * 1024

        /** 在途分片占用的总量上限（闸门 4）。超了拒绝新会话，避免"垃圾还没清完磁盘已满"。 */
        private const val PART_QUOTA_BYTES = 4L * 1024 * 1024 * 1024

        /**
         * 远端自动改名的最大探测次数。比本地的 9999 小三个数量级 ——
         * 每次探测是一次 FTP/WebDAV 往返，几百次就是分钟级的等待。
         */
        private const val REMOTE_RENAME_PROBES = 20

        /** `/search` 的深度与规模上限：depth 由调用方给，必须夹取，否则一次请求能走遍整卡 */
        private const val DEFAULT_SEARCH_DEPTH = 3
        private const val MAX_SEARCH_DEPTH = 8
        private const val MAX_SEARCH_RESULTS = 50
        private const val SEARCH_TIMEOUT_MS = 10_000L


        /**
         * `/read` 的统一响应。
         *
         * `size` 恒为文件真实字节数（`File.length()`）。之前这个字段在 4 个分支里有 3 种语义
         * （0 / 文件字节数 / content 的 UTF-16 字符数），前端只能靠 content 的中文前缀猜是否截断，
         * 所以这里补一个显式的 `truncated`。
         */
        /**
         * `/read` 的响应信封。
         *
         * @param reason 内容不可用的原因（[REASON_TOO_LARGE] / [REASON_BINARY] / [REASON_NOT_FILE]）；
         *   null 表示 [content] 就是完整文件内容。2026-09-11 新增 —— 此前三种情况都只置
         *   `truncated = true` 并把说明文字塞进 content，客户端无从区分，只能给一句含糊的提示。
         *   `truncated` 保留是为了老客户端仍能判断"内容不可信"。
         * @param suspect 以 UTF-8 解码时出现了替换字符，内容可能是 GBK 等其它编码
         */
        private fun readResponse(
            content: String,
            size: Long,
            truncated: Boolean,
            encoding: String = "utf-8",
            reason: String? = null,
            suspect: Boolean = false
        ) = toJsonElement(mapOf(
            "content" to content,
            "encoding" to encoding,
            "size" to size,
            "truncated" to truncated,
            "reason" to reason,
            "encoding_suspect" to suspect
        ))

        const val REASON_TOO_LARGE = "too_large"
        const val REASON_BINARY = "binary"
        const val REASON_NOT_FILE = "not_file"

        /**
         * 把客户端给的编码名解析成 [Charset]。
         *
         * 不认的名字回落 UTF-8 而不是报错：编码名来自用户在下拉里选的值，
         * 拼错一个字母就让整个文件打不开是不必要的严苛；响应里的 `encoding`
         * 会回显实际使用的那个，客户端能看出请求没被采纳。
         */
        internal fun charsetOf(name: String?): Charset = runCatching {
            if (name.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(name)
        }.getOrDefault(Charsets.UTF_8)


        /**
         * 按 RFC 6266 / RFC 5987 拼 Content-Disposition。
         *
         * 之前是直接把文件名插进 `filename="$name"`：一旦名字里带 `"` 或 `\`，
         * 参数会提前闭合，整个头从那里被截断 —— 比非 ASCII 乱码严重得多。
         * 所以给两个参数：ASCII 兜底的 `filename`（不安全字符换成 `_`）+ 精确的 `filename*`。
         * 换行类字符不用单独处理，Ktor 的 checkHeaderValue 会先拒掉所有 < 0x20。
         */
        internal fun contentDisposition(disposition: String, fileName: String): String {
            val ascii = fileName
                .map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }
                .joinToString("")
                .ifBlank { "download" }
            val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            return "$disposition; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }

        /** 由文件名判定可解压的归档类型；rar/7z 等需原生库，这里不支持。 */
        internal fun archiveKindOf(name: String): String? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".zip") -> "zip"
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "tgz"
                lower.endsWith(".tar") -> "tar"
                lower.endsWith(".gz") -> "gz"
                else -> null
            }
        }

        /**
         * 凭票流式播放的**公开**路径（不在 `/api` 下，见 [registerPublic]）。
         *
         * 刻意放在 `/api` 之外：若把「带 ticket 就放行」做成 `AuthMiddleware` 的例外，
         * 那道唯一鉴权闸门就开了口子，将来任何人加一个 `/api` 端点都得先想清楚会不会被这条例外命中。
         * 独立路径 + `isPublic` 白名单只加 `/media/`，边界一眼可见。
         */
        const val MEDIA_STREAM_PATH = "/media/stream"

        /**
         * 流式传输缓冲区。
         *
         * 2026-09-14 由 8KB 提到 64KB：1080p 视频约 5-15 Mbps，8KB 意味着每秒上千次
         * read/write syscall，而 core 常跑在随身 WiFi 这类弱设备上，CPU 全花在系统调用上。
         * 64KB 是「一次 syscall 搬更多字节」与「不为小文件白占内存」之间的常规折中。
         */
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }

    /**
     * 播放票据存储。
     *
     * 挂在实例上而不是 companion：`HttpServer` 全程复用同一个 [FileRoutes] 对象，
     * [register]（签发）与 [registerPublic]（校验）因此共享同一份票据表；
     * 放 companion 会让"多实例时票据串台"变成一个只在测试里才暴露的隐患。
     */
    private val mediaTicketStore = MediaTicketStore()

    /**
     * 分片上传的会话表。与 [mediaTicketStore] 同样是**实例字段而不是 companion**：
     * 会话绑定这一份路由实例，多实例时互不干扰。
     */
    private val uploadSessions = UploadSessionStore()

    /**
     * 远端上传的暂存根目录。
     *
     * 用 `java.io.tmpdir`（在 Android 上就是本应用的 cache 目录）而不是注入 Context：
     * [FileRoutes] 全程没有 Context（见 [UploadSessionStore] 类注释里记录的同一个约束），
     * 而整体上传那条路径本来就在用 `File.createTempFile`，落点完全一致。
     * 放 cache 目录的额外好处是系统在存储紧张时能自己回收 —— 推送失败留下的暂存文件
     * 即便 TTL 没到也不会把设备写满。
     */
    private val remoteStagingRoot: File =
        File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", RemotePushManager.STAGING_DIR_NAME)

    /**
     * 「core 暂存 → 远端存储源」的推送作业表。同样是实例字段：作业与这一份路由实例同生命周期。
     */
    private val remotePush = RemotePushManager(registry, remoteStagingRoot)


    /**
     * 注册**免鉴权**的凭票流式端点。必须由 `HttpServer` 挂在 `/api` **之外**的顶层 routing 上。
     *
     * 安全边界只有一条：票据里存的是签发时**已通过 `safeResolveForRead` 的真实路径**，
     * 这里不接受、也不解析任何用户给的 path —— 所以本端点没有路径穿越面。
     */
    fun registerPublic(route: Route) {
        route.get(MEDIA_STREAM_PATH) {
            val realPath = mediaTicketStore.resolve(call.request.queryParameters["ticket"]) ?: run {
                // 票据无效/过期一律 403 而不是 401：这里没有"补个头重试"的语义，
                // 客户端该做的是回去重新换票。
                call.respondFail(HttpStatusCode.Forbidden, ErrorCode.UNAUTHORIZED, "票据无效或已过期")
                return@get
            }
            val f = File(realPath)
            if (!f.isFile) {
                call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                return@get
            }
            call.respondFileStream(f)
        }
    }


    fun register(route: Route) {
        route.route("/files") {
            // 返回 Core 自身的存储管理权限状态（前端据此判断是否弹授权引导）
            get("/status") {
                val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
                } else {
                    true
                }
                call.respond(toJsonElement(mapOf(
                    "isExternalStorageManager" to granted,
                    // ── 上传能力位（2026-09-19）──
                    // 客户端据此决定走整体还是分片，并且**不再硬编码上限**。
                    // 老固件不回这些 key，客户端回落到自己那份保守常量即可。
                    "max_upload_bytes" to UPLOAD_BODY_LIMIT,
                    "supports_chunked_upload" to true,
                    "chunk_size" to UPLOAD_CHUNK_SIZE,
                    "max_chunked_upload_bytes" to MAX_CHUNKED_UPLOAD_BYTES,
                    "upload_session_ttl_seconds" to uploadSessions.ttlSeconds,
                    // 远端上传（手机 → core 暂存 → 外部存储源）。老 app 不认这个 key，
                    // 于是继续按"远端不能上传"隐藏入口，不会点到一个必然 400 的动作。
                    "supports_remote_upload" to true,
                    // ── 存储源列表（Phase 1）──
                    "sources" to registry.listSources().map { mapOf(
                        "id" to it.id, "label" to it.label, "protocol" to it.protocol,
                        "enabled" to it.enabled
                    ) }
                )))
            }

            get("/list") {
                val path = call.request.queryParameters["path"] ?: INTERNAL_STORAGE
                // ── remote: 前缀路由（Phase 1）──
                if (path.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try {
                        registry.resolve(path)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@get
                    }
                    val result = provider.list(remotePath)
                    // 路径必须还原成 `remote:<id>/…` 再交给客户端：provider 只认自己那套
                    // 相对路径，原样返回的话客户端拿 `/Server/` 回传就被当成本地绝对路径，
                    // 撞白名单 400，而且它会以为自己在本地、连返回上一级都走错分支。
                    val sourceId = registry.sourceIdOf(path) ?: ""
                    call.respond(toJsonElement(mapOf(
                        "files" to result.files.map { mapOf(
                            "name" to it.name,
                            "path" to registry.toClientPath(sourceId, it.path),
                            "isDirectory" to it.isDirectory, "size" to it.size,
                            "lastModified" to it.lastModified, "permissions" to it.permissions,
                            "isSymlink" to it.isSymlink,
                            "source" to it.source
                        ) },
                        "path" to registry.toClientPath(sourceId, result.path),
                        "parent" to registry.parentClientPath(sourceId, result.path),
                        "truncated" to result.truncated
                    )))
                    return@get
                }
                val realPath = safeResolve(path) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val dir = File(realPath)
                if (!dir.exists() || !dir.isDirectory) {
                    call.respond(toJsonElement(mapOf(
                        "files" to emptyList<String>(), "path" to realPath,
                        "parent" to parentOf(realPath), "error" to "目录不存在"
                    )))
                    return@get
                }
                withContext(Dispatchers.IO) {
                    val raw = dir.listFiles().orEmpty()
                    // ── 分片残留清理（清理闸门 3）──
                    // 这是覆盖「core 被杀 / 设备断电」的那一道：内存里的会话表全丢之后，
                    // 磁盘上的 `.ufipart` 没有任何人知道它存在。选在列目录时顺手清，
                    // 是因为这里已经拿到了 listFiles() 的结果 —— 零额外 IO，
                    // 且不需要定时器、不需要启动扫描、不需要全卷 walk。
                    // 代价：没人访问过的目录里的残留会一直留着。由开会话时的配额闸门兜住，
                    // 而且那种残留本身无害。
                    purgeStaleParts(raw.toList())
                    val files = raw
                        // 分片文件对用户不可见：它是传输中间态，不是"文件"。
                        // 过滤掉之后，`.ufipart` 放在目标目录这个选择对用户就完全透明了。
                        .filter { !it.name.endsWith(UploadSessionStore.PART_SUFFIX) }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .map { entryToFileMap(it, realPath) }
                    call.respond(toJsonElement(mapOf(
                        "files" to files, "path" to realPath, "parent" to parentOf(realPath)
                    )))
                }
            }


            get("/info") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try {
                        registry.resolve(filePath)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@get
                    }
                    val info = provider.info(remotePath)
                    val sourceId = registry.sourceIdOf(filePath) ?: ""
                    call.respond(toJsonElement(mapOf(
                        "name" to info.name,
                        "path" to registry.toClientPath(sourceId, info.path),
                        "isDirectory" to info.isDirectory, "size" to info.size,
                        "lastModified" to info.lastModified, "permissions" to info.permissions,
                        "owner" to "", "group" to "",
                        "isSymlink" to info.isSymlink
                    )))
                    return@get
                }
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val f = File(realPath)
                if (!f.exists()) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                    return@get
                }
                call.respond(toJsonElement(mapOf(
                    "name" to f.name, "path" to realPath,
                    "isDirectory" to f.isDirectory,
                    "size" to f.length(),
                    "lastModified" to f.lastModified(),
                    "permissions" to permsOf(f),
                    "owner" to "", "group" to "",
                    "isSymlink" to runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
                )))
            }

            post("/read") {
                val body = call.receiveJsonObject()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val charset = charsetOf(body["encoding"]?.jsonPrimitive?.contentOrNull)
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try {
                        registry.resolve(filePath)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val result = provider.read(remotePath, body["encoding"]?.jsonPrimitive?.contentOrNull)
                    call.respond(readResponse(
                        result.content, result.size, result.truncated,
                        encoding = result.encoding, reason = result.reason, suspect = result.encodingSuspect
                    ))
                    return@post
                }
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                withContext(Dispatchers.IO) {
                    val f = File(realPath)
                    if (!f.isFile) {
                        call.respond(readResponse(
                            "[不是文件或不存在]", 0L,
                            truncated = true, reason = REASON_NOT_FILE
                        ))
                        return@withContext
                    }
                    val fileSize = f.length()
                    if (fileSize > MAX_READ_SIZE) {
                        call.respond(readResponse(
                            "[文件过大: $fileSize bytes，超过 ${MAX_READ_SIZE / 1024}KB 限制，不支持在线查看]",
                            fileSize, truncated = true, reason = REASON_TOO_LARGE
                        ))
                        return@withContext
                    }
                    val bytes = f.readBytes()
                    if (bytes.take(4096).any { it == 0.toByte() }) {
                        call.respond(readResponse(
                            "[二进制文件，不支持在线查看]", fileSize,
                            truncated = true, reason = REASON_BINARY
                        ))
                        return@withContext
                    }
                    // 到这里 bytes.size <= MAX_READ_SIZE，而 UTF-8 解出的字符数不会多于字节数，
                    // 所以不可能再需要二次截断 —— 旧的 text.length > MAX_READ_SIZE 分支是死代码。
                    val text = String(bytes, charset)
                    // U+FFFD 是解码器遇到非法字节序列时填的替换字符。只在按 UTF-8 读时才作为
                    // "编码可能不对"的信号：用户显式选了 GBK 还出现替换字符，那是文件本身的问题，
                    // 再提示一次"可能不是 GBK"只会让人反复换编码试。
                    val suspect = charset == Charsets.UTF_8 && text.contains('\uFFFD')
                    call.respond(readResponse(
                        text, fileSize, truncated = false,
                        encoding = charset.name().lowercase(), suspect = suspect
                    ))
                }
            }

            post("/write") {
                val body = call.receiveJsonObject()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = body["content"]?.jsonPrimitive?.contentOrNull ?: ""
                val charset = charsetOf(body["encoding"]?.jsonPrimitive?.contentOrNull)
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try {
                        registry.resolve(filePath)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val ok = runCatching {
                        provider.write(remotePath, content, body["encoding"]?.jsonPrimitive?.contentOrNull)
                        true
                    }.getOrDefault(false)
                    call.respond(toJsonElement(mapOf("success" to ok)))
                    return@post
                }
                val realPath = safeResolve(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = File(realPath)
                        f.parentFile?.mkdirs()
                        f.writeText(content, charset)
                        f.exists()
                    }.getOrDefault(false)
                }
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            post("/delete") {
                val body = call.receiveJsonObject()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try {
                        registry.resolve(filePath)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val ok = runCatching { provider.delete(remotePath) }.getOrDefault(false)
                    call.respond(toJsonElement(mapOf("success" to ok, "deleted" to ok)))
                    return@post
                }
                val realPath = safeResolve(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val danger = isDangerousDeletePath(realPath)
                if (danger != null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.DANGEROUS_PATH, danger)
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    val f = File(realPath)
                    if (!f.exists()) {
                        false
                    } else {
                        val deleted = f.deleteRecursively()
                        deleted && !f.exists()
                    }
                }
                call.respond(toJsonElement(mapOf("success" to ok, "deleted" to ok)))
            }

            post("/rename") {
                val body = call.receiveJsonObject()
                val oldPath = body["old_path"]?.jsonPrimitive?.contentOrNull ?: ""
                val newPath = body["new_path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (oldPath.startsWith(FileProviderRegistry.REMOTE_PREFIX) || newPath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    // 远程 rename：两个路径必须属于同一个 provider
                    val (provider, remoteOld) = try { registry.resolve(oldPath) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val (_, remoteNew) = try { registry.resolve(newPath) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val ok = runCatching { provider.rename(remoteOld, remoteNew) }.getOrDefault(false)
                    call.respond(toJsonElement(mapOf("success" to ok)))
                    return@post
                }
                val safeOld = safeResolve(oldPath)
                val safeNew = safeResolve(newPath)
                if (safeOld == null || safeNew == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Files.move(Paths.get(safeOld), Paths.get(safeNew), StandardCopyOption.REPLACE_EXISTING)
                        true
                    }.getOrDefault(false)
                }
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            post("/move") {
                val body = call.receiveJsonObject()
                val source = body["source"]?.jsonPrimitive?.contentOrNull ?: ""
                val dest = body["destination"]?.jsonPrimitive?.contentOrNull ?: ""
                if (source.startsWith(FileProviderRegistry.REMOTE_PREFIX) || dest.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remoteSrc) = try { registry.resolve(source) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val (_, remoteDst) = try { registry.resolve(dest) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val result = runCatching { provider.move(remoteSrc, remoteDst) }.getOrDefault(mapOf("success" to false, "error" to "remote move failed"))
                    call.respond(toJsonElement(result))
                    return@post
                }
                val safeSource = safeResolve(source)
                val safeDest = safeResolve(dest)
                if (safeSource == null || safeDest == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val result = withContext(Dispatchers.IO) {
                    val src = File(safeSource)
                    val dst = File(safeDest)
                    if (!src.exists()) return@withContext mapOf("success" to false, "error" to "源文件或目录不存在")
                    if (dst.exists()) return@withContext mapOf("success" to false, "error" to "目标已存在")
                    val srcSize = if (src.isFile) src.length() else -1L
                    val moved = runCatching {
                        Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        true
                    }.getOrDefault(false)
                    val integrityOk = if (moved) {
                        val srcGone = !src.exists()
                        val dstExists = dst.exists()
                        val sizeOk = if (srcSize > 0) dst.length() == srcSize else true
                        srcGone && dstExists && sizeOk
                    } else false
                    mapOf(
                        "success" to (moved && integrityOk),
                        "dest_exists" to dst.exists(),
                        "source_deleted" to !src.exists(),
                        "fallback_copy" to false,
                        "integrity_ok" to integrityOk
                    )
                }
                call.respond(toJsonElement(result))
            }

            post("/copy") {
                val body = call.receiveJsonObject()
                val source = body["source"]?.jsonPrimitive?.contentOrNull ?: ""
                val dest = body["destination"]?.jsonPrimitive?.contentOrNull ?: ""
                if (source.startsWith(FileProviderRegistry.REMOTE_PREFIX) || dest.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remoteSrc) = try { registry.resolve(source) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val (_, remoteDst) = try { registry.resolve(dest) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val ok = runCatching { provider.copy(remoteSrc, remoteDst) }.getOrDefault(false)
                    call.respond(toJsonElement(mapOf("success" to ok)))
                    return@post
                }
                val safeSource = safeResolve(source)
                val safeDest = safeResolve(dest)
                if (safeSource == null || safeDest == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching { copyRecursively(File(safeSource), File(safeDest)) }.getOrDefault(false)
                }
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            post("/mkdir") {
                val body = call.receiveJsonObject()
                val dirPath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (dirPath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try { registry.resolve(dirPath) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@post
                    }
                    val ok = runCatching { provider.mkdir(remotePath) }.getOrDefault(false)
                    call.respond(toJsonElement(mapOf("success" to ok)))
                    return@post
                }
                val realPath = safeResolve(dirPath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    val f = File(realPath)
                    val created = f.mkdirs()
                    created || f.isDirectory
                }
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            get("/search") {
                val path = call.request.queryParameters["path"] ?: INTERNAL_STORAGE
                val query = call.request.queryParameters["query"] ?: ""
                val maxDepth = (call.request.queryParameters["depth"]?.toIntOrNull() ?: DEFAULT_SEARCH_DEPTH)
                    .coerceIn(1, MAX_SEARCH_DEPTH)
                if (query.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "empty query")
                    return@get
                }
                if (path.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try { registry.resolve(path) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@get
                    }
                    val results = provider.search(remotePath, query, maxDepth)
                    val sourceId = registry.sourceIdOf(path) ?: ""
                    call.respond(toJsonElement(mapOf(
                        "files" to results.map { mapOf(
                            "name" to it.name,
                            "path" to registry.toClientPath(sourceId, it.path),
                            "isDirectory" to it.isDirectory
                        ) },
                        "query" to query, "timed_out" to false
                    )))
                    return@get
                }
                val realPath = safeResolve(path) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val root = File(realPath)
                if (!root.exists()) {
                    call.respond(toJsonElement(mapOf(
                        "files" to emptyList<String>(), "query" to query, "timed_out" to false
                    )))
                    return@get
                }
                withContext(Dispatchers.IO) {
                    val found = mutableListOf<Map<String, Any>>()
                    // 结果收在闭包外的 list 里：超时时才能把已经找到的部分返回。
                    // 必须有墙钟上限 —— 结果数上限只在凑满时短路，一个查不到的关键词
                    // 会把 maxDepth 以内的整棵树走完。
                    val completed = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        try {
                            for (f in root.walkTopDown().maxDepth(maxDepth)) {
                                currentCoroutineContext().ensureActive()
                                if (f == root || !f.name.contains(query, ignoreCase = true)) continue
                                found += mapOf(
                                    "name" to f.name,
                                    "path" to f.absolutePath,
                                    "isDirectory" to f.isDirectory
                                )
                                if (found.size >= MAX_SEARCH_RESULTS) break
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // walkTopDown 遇到无读权限的子目录会抛 AccessDeniedException，
                            // 整个 handler 不该因此 500 —— 返回已收集的部分。
                        }
                        true
                    } != null
                    call.respond(toJsonElement(mapOf(
                        "files" to found, "query" to query, "timed_out" to !completed
                    )))
                }
            }

            get("/disk-usage") {
                val disks = withContext(Dispatchers.IO) { collectDiskUsage() }
                call.respond(toJsonElement(mapOf("disks" to disks)))
            }

            post("/touch") {
                val body = call.receiveJsonObject()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val realPath = safeResolve(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    val f = File(realPath)
                    when {
                        f.exists() -> { f.setLastModified(System.currentTimeMillis()); true }
                        else -> { f.parentFile?.mkdirs(); f.createNewFile() }
                    }
                }
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            get("/download") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    val (provider, remotePath) = try { registry.resolve(filePath) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@get
                    }
                    val stream = provider.downloadStream(remotePath)
                    val name = remotePath.substringAfterLast('/')
                    val mimeType = MimeTypes.fromFileName(name).ifBlank { "application/octet-stream" }
                    call.response.header(HttpHeaders.ContentDisposition, contentDisposition("attachment", name))
                    call.respondOutputStream(ContentType.parse(mimeType)) {
                        withContext(Dispatchers.IO) { stream.use { it.copyTo(this@respondOutputStream) } }
                    }
                    return@get
                }
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val f = File(realPath)
                if (!f.isFile) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                    return@get
                }
                val fileSize = f.length()
                if (fileSize <= 0 || fileSize > MAX_DOWNLOAD_SIZE) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "文件不存在或超过${MAX_DOWNLOAD_SIZE / 1024 / 1024}MB限制")
                    return@get
                }
                val mimeType = MimeTypes.fromFileName(f.name).ifBlank { "application/octet-stream" }
                val fileName = f.name
                call.response.header(HttpHeaders.ContentDisposition, contentDisposition("attachment", fileName))
                call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                call.respondOutputStream(ContentType.parse(mimeType)) {
                    withContext(Dispatchers.IO) { f.inputStream().use { it.copyTo(this@respondOutputStream) } }
                }
            }

            get("/stream") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (filePath.startsWith(FileProviderRegistry.REMOTE_PREFIX)) {
                    // ── 远端流式播放（2026-09-21 补上 Range）──
                    //
                    // 之前这里**忽略客户端的 Range 头**，每次都向远端发一个不带 Range 的整文件 GET。
                    // 两个后果：① 播放器无法 seek；② 相当多的网盘 / 反代对"不带 Range 的大文件 GET"
                    // 直接回 502，于是视频根本放不出来（ExoPlayer 报 2004），而目录列表却是好的 ——
                    // 现象上很像"远端存储坏了"，实际只差一个头。
                    val (provider, remotePath) = try { registry.resolve(filePath) } catch (e: FileProvider.ProviderException) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "Invalid remote path")
                        return@get
                    }
                    val name = remotePath.substringAfterLast('/')
                    val mimeType = MimeTypes.fromFileName(name).ifBlank { "application/octet-stream" }
                    // 总大小：206 必须回 Content-Range，播放器靠它算总时长与可 seek 范围。
                    // 取不到（provider 不给 size / 一次网络失败）时退回 200 全量，功能降级但不报错。
                    val total = try { provider.info(remotePath).size } catch (_: Exception) { -1L }
                    val start = parseRangeStart(call.request.header(HttpHeaders.Range))
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentDisposition, contentDisposition("inline", name))
                    if (start != null && start > 0L && total > 0L && start < total) {
                        val stream = provider.downloadStream(remotePath, start)
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-${total - 1}/$total")
                        call.response.header(HttpHeaders.ContentLength, (total - start).toString())
                        call.respondOutputStream(ContentType.parse(mimeType), HttpStatusCode.PartialContent) {
                            withContext(Dispatchers.IO) { stream.use { it.copyTo(this@respondOutputStream) } }
                        }
                        return@get
                    }
                    val stream = provider.downloadStream(remotePath, 0L)
                    if (total > 0L) call.response.header(HttpHeaders.ContentLength, total.toString())
                    call.respondOutputStream(ContentType.parse(mimeType)) {
                        withContext(Dispatchers.IO) { stream.use { it.copyTo(this@respondOutputStream) } }
                    }
                    return@get
                }
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val f = File(realPath)
                if (!f.isFile) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                    return@get
                }
                call.respondFileStream(f)
            }

            /**
             * 签发媒体流播放票据（2026-09-14）。
             *
             * 只给 **web 端**用：浏览器的 `<audio src>` / `<video src>` 无法附加鉴权头，
             * 拿不到 `/api/files/stream`，此前只能 `fetch` 整个文件成 blob 再播
             * （100MB 上限 + 必须下载完 + 电影进不了预览）。本端点走正常头部鉴权换一张短时票据，
             * 之后浏览器用 `/media/stream?ticket=…` 直接流式播放，Range 与 seek 全由浏览器负责。
             *
             * app 端**不需要**它：ExoPlayer 走 OkHttp，能带头，继续用 `/api/files/stream`。
             *
             * 票据语义见 [MediaTicketStore]：可重复使用（否则第二个 Range 请求就断）、
             * 滑动过期、只授权这一个文件。
             */
            post("/stream-ticket") {
                val body = call.receiveJsonObject()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                // 校验必须在签发前跑完：票据只存解析后的真实路径，`/media/stream` 不再解析用户输入
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val f = File(realPath)
                if (!f.isFile) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                    return@post
                }
                val ticket = mediaTicketStore.issue(realPath)
                call.respond(toJsonElement(mapOf(
                    "ticket" to ticket,
                    // 直接把可用 URL 给出去，省得客户端各自拼（拼错一次就是"能放不能 seek"这类怪问题）
                    "url" to "$MEDIA_STREAM_PATH?ticket=${URLEncoder.encode(ticket, "UTF-8")}",
                    "expires_in" to mediaTicketStore.ttlSeconds,
                    "size" to f.length(),
                    "mime" to MimeTypes.fromFileName(f.name)
                )))
            }


            post("/upload") {
                try {
                    val multipart = call.receiveMultipart()
                    var targetDir = ""
                    var savedPath = ""
                    var savedSize = 0L

                    multipart.forEachPart { part ->
                        try {
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "path") targetDir = part.value
                                }
                                is PartData.FileItem -> {
                                    // 仅取文件名，剔除客户端传入的路径分隔符/遍历片段（如 "../../foo.txt" 或 "sub/a.txt"），
                                    // 否则文件可能写到目标目录之外。
                                    val rawName = part.originalFileName ?: "uploaded_file"
                                    val fileName = File(rawName).name.ifBlank { "uploaded_file" }
                                    val safeTargetDir = safeResolve(targetDir)
                                    if (safeTargetDir == null) throw IllegalArgumentException("Invalid target path")
                                    val destPath = "${safeTargetDir.trimEnd('/')}/$fileName"
                                    // 二次校验：拼接后的目标仍须落在用户存储内
                                    if (safeResolve(destPath) == null) throw IllegalArgumentException("Invalid file path")
                                    val tmpFile = File.createTempFile("ufi_upload_", null)
                                    try {
                                        part.streamProvider().use { input ->
                                            withContext(Dispatchers.IO) {
                                                tmpFile.outputStream().use { out -> savedSize += input.copyTo(out) }
                                            }
                                        }
                                        val destFile = File(destPath)
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching {
                                                destFile.parentFile?.mkdirs()
                                                Files.copy(tmpFile.toPath(), destFile.toPath(),
                                                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                                                tmpFile.delete()
                                                destFile.exists()
                                            }.getOrDefault(false)
                                        }
                                        if (!ok) throw IllegalStateException("写入失败")
                                        savedPath = destPath
                                    } catch (e: Exception) {
                                        tmpFile.delete()
                                        throw e
                                    }
                                }
                                else -> { }
                            }
                        } finally {
                            part.dispose()
                        }
                    }

                    if (savedPath.isNotEmpty()) {
                        call.respond(toJsonElement(mapOf("success" to true, "path" to savedPath, "size" to savedSize)))
                    } else {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "No file received")
                    }
                } catch (e: IllegalArgumentException) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, (e.message ?: "Invalid target path"))
                } catch (e: Exception) {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, "Upload failed: ${e.message}")
                }
            }

            // ───────── 分片上传（2026-09-19）─────────
            //
            // 为什么要它：整体上传断了从零重来，2GB 视频按 5MB/s 算 400 秒，
            // 任何一次抖动全废；而且老实现先落 app 内部存储的临时文件再 Files.copy 到目标，
            // 峰值占用 2× 文件大小、目标在 SD 卡时还是跨卷复制。
            //
            // 三个端点 + 一个删除。语义与所有边缘情况见 [UploadSessionStore] 的类注释。
            // `/upload`（整体）**保留不动**：老客户端与小文件继续走它，不需要多一次往返。

            /**
             * 开会话。同时承担四件事：路径校验、自动改名、空间/配额检查、续传探测。
             *
             * body: `{ path: 目标目录, name: 文件名, size: 总字节数 }`
             * 返回: `{ session_id, chunk_size, received, file_name, renamed, next_index, remote, dest_path }`
             *
             * `renamed` 为 true 时 `file_name` 是自动改名后的结果（`video (1).mp4`）——
             * 客户端**必须**把它显示出来，否则用户以为覆盖了原文件。
             *
             * ## 目标是远端存储源时（`path` 以 `remote:` 开头）
             * `.ufipart` 不能落在目标目录（那在另一台机器上，而且 FTP/WebDAV/SMB/S3 都没有
             * 「按偏移补一块」的原语），改落 core 的**暂存目录**
             * （`<cache>/ufi-remote-upload/<sourceId>/<base64url(远端目录)>`）。
             * 分片与续传逻辑完全不变 —— 它们只认会话里那个已校验过的真实目录。
             * 收尾（`/upload/complete`）时再由 [RemotePushManager] 把整份文件推到远端。
             */
            post("/upload/session") {
                val body = call.receiveJsonObject()
                val dirReq = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val nameReq = body["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val size = body["size"]?.jsonPrimitive?.longOrNull ?: -1L

                // 只取文件名，剔掉客户端可能传来的路径分隔符与遍历片段
                val safeName = File(nameReq).name.ifBlank { "" }
                if (safeName.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "文件名不合法")
                    return@post
                }
                // 不允许客户端自己造 `.ufipart`：那会让分片文件与真实文件混淆
                if (safeName.endsWith(UploadSessionStore.PART_SUFFIX)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "文件名后缀不被允许")
                    return@post
                }
                if (size <= 0 || size > MAX_CHUNKED_UPLOAD_BYTES) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE,
                        "文件大小超出范围（上限 ${MAX_CHUNKED_UPLOAD_BYTES / 1024 / 1024} MB）"
                    )
                    return@post
                }

                val isRemote = dirReq.startsWith(FileProviderRegistry.REMOTE_PREFIX)
                var remoteProvider: FileProvider? = null
                var remoteSourceId = ""
                var remoteRelDir = ""
                // 路径校验在这里一次做完，之后 chunk/complete 只认会话里的真实路径。
                // 这是整套设计的安全前提，与 stream-ticket 同一个模式。
                val realDir: String = if (isRemote) {
                    val resolved = try {
                        registry.resolve(dirReq)
                    } catch (e: FileProvider.ProviderException) {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            e.message ?: "Invalid remote path"
                        )
                        return@post
                    }
                    val provider = resolved.first
                    if (FileProvider.Capability.UPLOAD !in provider.capabilities) {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            "存储源「${provider.label}」不支持上传"
                        )
                        return@post
                    }
                    val sourceId = registry.sourceIdOf(dirReq)
                    if (sourceId.isNullOrEmpty()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "远程路径缺少 sourceId")
                        return@post
                    }
                    remoteProvider = provider
                    remoteSourceId = sourceId
                    remoteRelDir = resolved.second
                    // 顺手清一次孤儿暂存文件（core 被杀 / 断电后的残留），同惰性清理的口径
                    withContext(Dispatchers.IO) { remotePush.purgeStaleStaging() }
                    remotePush.stagingDirFor(sourceId, resolved.second).absolutePath
                } else {
                    safeResolve(dirReq) ?: run {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                        return@post
                    }
                }
                val dirFile = File(realDir)
                if (!withContext(Dispatchers.IO) { dirFile.isDirectory || dirFile.mkdirs() }) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        if (isRemote) "无法创建暂存目录" else "目标目录不存在且无法创建"
                    )
                    return@post
                }

                // 先探续传：命中就直接返回，不做改名（名字在首次开会话时已经定下来了）
                val resumed = withContext(Dispatchers.IO) {
                    uploadSessions.findResumable(realDir, safeName, size)
                }
                if (resumed != null) {
                    call.respond(toJsonElement(mapOf(
                        "session_id" to resumed.id,
                        "chunk_size" to UPLOAD_CHUNK_SIZE,
                        "received" to resumed.received,
                        "next_index" to resumed.nextIndex(UPLOAD_CHUNK_SIZE),
                        "file_name" to resumed.fileName,
                        "renamed" to (resumed.fileName != safeName),
                        "resumed" to true,
                        "remote" to isRemote,
                        "dest_path" to if (isRemote) {
                            registry.toClientPath(
                                remoteSourceId,
                                RemotePushManager.joinRemote(remoteRelDir, resumed.fileName)
                            )
                        } else {
                            File(realDir, resumed.fileName).absolutePath
                        }
                    )))
                    return@post
                }

                // 空间检查：留出余量，否则传完正好写满、连 rename 的元数据都可能写不下。
                // 远端上传查的是**暂存所在的内部存储**，不是远端服务器 —— 远端的余量我们查不到，
                // 那种失败只能在第二阶段（推送）暴露，所以推送失败必须是可见、可重试的。
                val free = withContext(Dispatchers.IO) { freeSpaceOf(realDir) }
                if (free in 1 until (size + FREE_SPACE_HEADROOM)) {
                    call.respondFail(
                        HttpStatusCode(507, "Insufficient Storage"), ErrorCode.OPERATION_FAILED,
                        if (isRemote) {
                            "设备暂存空间不足（需要 ${formatSize(size + FREE_SPACE_HEADROOM)}，可用 ${formatSize(free)}）"
                        } else {
                            "目标存储剩余空间不足（需要 ${formatSize(size + FREE_SPACE_HEADROOM)}，可用 ${formatSize(free)}）"
                        }
                    )
                    return@post
                }
                // 配额闸门：在途分片总量有上限，避免"垃圾还没清完磁盘先满"。
                // 远端走暂存目录的总占用（含还没推完的整文件），本地走会话表。
                val inflight = if (isRemote) {
                    withContext(Dispatchers.IO) { remotePush.stagedBytes() }
                } else {
                    uploadSessions.activeBytes()
                }
                if (inflight + size > PART_QUOTA_BYTES) {
                    call.respondFail(
                        HttpStatusCode.Conflict, ErrorCode.CONFLICT,
                        "同时进行的上传占用过多，请等已有任务完成后再试"
                    )
                    return@post
                }

                // 自动改名（用户已确认的策略 B）：目标已存在时落成 `video (1).mp4`，不静默覆盖。
                // 传大文件时覆盖掉的可能是几 GB 的东西，静默覆盖的风险远高于小文件。
                val provider = remoteProvider
                val finalName = if (isRemote && provider != null) {
                    uniqueRemoteName(provider, remoteRelDir, dirFile, safeName)
                } else {
                    withContext(Dispatchers.IO) { uniqueChildName(dirFile, safeName) }
                }
                val session = uploadSessions.open(realDir, finalName, size)
                call.respond(toJsonElement(mapOf(
                    "session_id" to session.id,
                    "chunk_size" to UPLOAD_CHUNK_SIZE,
                    "received" to 0L,
                    "next_index" to 0L,
                    "file_name" to finalName,
                    "renamed" to (finalName != safeName),
                    "resumed" to false,
                    "remote" to isRemote,
                    "dest_path" to if (isRemote) {
                        registry.toClientPath(
                            remoteSourceId,
                            RemotePushManager.joinRemote(remoteRelDir, finalName)
                        )
                    } else {
                        File(realDir, finalName).absolutePath
                    }
                )))
            }

            /**
             * 收一个分片。**body 是裸二进制**，不是 multipart。
             *
             * 不用 multipart 的两个理由：每片都要解 boundary；而且 Ktor 的 multipart
             * 会把 part 再落一次临时文件 —— 分片方案的全部意义就是避免那次落盘。
             *
             * query: `session`、`index`
             *
             * - `index < next` ⇒ **幂等 200**（客户端因超时重发了已收的片，不能重复追加）
             * - `index > next` ⇒ 409 + 回 `next_index`，客户端据此纠正
             * - 会话不存在/已过期 ⇒ **410 Gone**（不是 404 —— 404 会被客户端误判成
             *   "端点不存在"进而回落到整体上传，那是错的恢复动作）
             */
            put("/upload/chunk") {
                val id = call.request.queryParameters["session"] ?: ""
                val index = call.request.queryParameters["index"]?.toLongOrNull() ?: -1L
                val session = uploadSessions.find(id) ?: run {
                    call.respondFail(HttpStatusCode.Gone, ErrorCode.NOT_FOUND, "上传会话不存在或已过期")
                    return@put
                }
                if (index < 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "index 缺失或非法")
                    return@put
                }
                val next = session.nextIndex(UPLOAD_CHUNK_SIZE)
                if (index < next) {
                    // 已经收过：直接回当前进度，不追加。少了这一条，一次网络重传就会
                    // 把同一片写两遍，最终文件比声明大小长一截。
                    call.respond(toJsonElement(mapOf(
                        "success" to true, "received" to session.received,
                        "next_index" to next, "duplicate" to true
                    )))
                    return@put
                }
                if (index > next) {
                    call.respondFail(
                        HttpStatusCode.Conflict, ErrorCode.CONFLICT, "分片顺序不匹配",
                        mapOf("next_index" to next, "received" to session.received)
                    )
                    return@put
                }

                val part = session.partFile
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        part.parentFile?.mkdirs()
                        // append = true：顺序追加。只支持顺序写是刻意的取舍 ——
                        // 随机写要预分配整个文件 + 维护已收位图，而串行上传本来就是顺序的。
                        java.io.FileOutputStream(part, true).use { out ->
                            call.receiveStream().use { input -> input.copyTo(out) }
                        }
                        part.length()
                    }
                }
                val written = result.getOrNull()
                if (written == null) {
                    val cause = result.exceptionOrNull()
                    // 磁盘中途被别的进程写满：507 且**保留 `.ufipart`** ——
                    // 用户清出空间后可以接着传，这里删掉反而毁了他唯一的补救机会
                    val noSpace = cause?.message?.contains("ENOSPC", ignoreCase = true) == true ||
                        cause?.message?.contains("No space", ignoreCase = true) == true
                    if (noSpace) {
                        call.respondFail(
                            HttpStatusCode(507, "Insufficient Storage"), ErrorCode.OPERATION_FAILED,
                            "存储空间不足，已保留进度，清理空间后可继续",
                            mapOf("received" to session.received)
                        )
                    } else {
                        call.respondFail(
                            HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR,
                            "分片写入失败：${cause?.message ?: "未知错误"}"
                        )
                    }
                    return@put
                }
                // 写过头说明客户端切片与服务端 chunk_size 不一致 —— 继续下去只会拼出坏文件
                if (written > session.declaredSize) {
                    uploadSessions.discard(session.id)
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "累计大小超过声明值，已中止本次上传"
                    )
                    return@put
                }
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "received" to written,
                    "next_index" to session.nextIndex(UPLOAD_CHUNK_SIZE),
                    "complete" to (written == session.declaredSize)
                )))
            }

            /**
             * 收尾：校验总大小后 `renameTo` 目标名。
             *
             * body: `{ session_id }`
             *
             * **幂等**：会话已被删但存在"最近完成"记录时回同样的 success。
             * 少了这一条，客户端的一次重传会看到 410，表现为"明明传好了却报失败"。
             */
            post("/upload/complete") {
                val body = call.receiveJsonObject()
                val id = body["session_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val session = uploadSessions.find(id)
                if (session == null) {
                    val done = uploadSessions.findCompleted(id)
                    if (done != null) {
                        call.respond(toJsonElement(mapOf(
                            "success" to true, "path" to done.path, "size" to done.size, "duplicate" to true,
                            // 远端会话的完成记录里存的是 `remote:<id>/…` 形态，据此回 staged ——
                            // 少了这一位，客户端的一次重传就会把"只到了设备"误报成"上传成功"。
                            // 这里给不出 push_job_id（记录里没存），客户端改用列表接口找那条作业。
                            "staged" to done.path.startsWith(FileProviderRegistry.REMOTE_PREFIX)
                        )))
                        return@post
                    }
                    call.respondFail(HttpStatusCode.Gone, ErrorCode.NOT_FOUND, "上传会话不存在或已过期")
                    return@post
                }

                val part = session.partFile
                val actual = withContext(Dispatchers.IO) { if (part.isFile) part.length() else -1L }
                if (actual != session.declaredSize) {
                    // 成因：客户端切片 bug，或用户在上传途中改了源文件。
                    // 保留半个文件没有意义（用户无从判断它是否完整），直接删。
                    uploadSessions.discard(session.id)
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "大小校验失败：已接收 $actual，声明 ${session.declaredSize}"
                    )
                    return@post
                }

                val dest = session.destFile
                val renamed = withContext(Dispatchers.IO) {
                    runCatching {
                        dest.parentFile?.mkdirs()
                        // 同目录 rename：元数据操作，瞬时完成、零额外空间。
                        // 这正是把 `.ufipart` 放在目标目录（而不是内部存储）换来的收益。
                        part.renameTo(dest)
                    }.getOrDefault(false)
                }
                if (!renamed) {
                    // 目标目录在上传期间被删掉是最常见的成因。留着 `.ufipart` 只是垃圾
                    // （用户没有可行的补救动作），所以删掉并把原因说清。
                    uploadSessions.discard(session.id)
                    call.respondFail(
                        HttpStatusCode.InternalServerError, ErrorCode.OPERATION_FAILED,
                        "落盘失败：目标目录可能已被删除或不可写"
                    )
                    return@post
                }
                val result = uploadSessions.complete(session.id, dest.absolutePath, actual)

                // ── 第二阶段：远端目标的话，这里只是"到了 core"，还没到远端 ──
                // 暂存目录自带 sourceId 与远端相对目录（编码进路径），所以不需要在会话里加字段。
                val staging = remotePush.parseStagingDir(session.dir)
                if (staging != null) {
                    val (sourceId, relDir) = staging
                    val label = registry.listSources().firstOrNull { it.id == sourceId }?.label ?: sourceId
                    val destClient = registry.toClientPath(
                        sourceId, RemotePushManager.joinRemote(relDir, session.fileName)
                    )
                    val job = remotePush.enqueue(
                        sourceId = sourceId,
                        sourceLabel = label,
                        fileName = session.fileName,
                        relDir = relDir,
                        destPath = destClient,
                        stagedFile = dest,
                        totalBytes = actual
                    )
                    // `staged: true` 是给客户端的关键信号：**别提示"上传完成"**，
                    // 这时候文件只在 core 上，真正的落点还要看推送作业。
                    call.respond(toJsonElement(mapOf(
                        "success" to true,
                        "path" to destClient,
                        "size" to actual,
                        "staged" to true,
                        "push_job_id" to job.id,
                        "push_state" to job.state.name
                    )))
                    return@post
                }
                call.respond(toJsonElement(mapOf(
                    "success" to true, "path" to result.path, "size" to result.size
                )))
            }

            /**
             * 取消会话并删除 `.ufipart`（清理闸门 1）。
             *
             * 客户端在"用户点取消"和"页面卸载"两处调它。页面卸载那处必须用
             * `fetch(..., { keepalive: true })` 而不是 `navigator.sendBeacon`：
             * sendBeacon 设不了自定义头，而 `/api` 强制要设备签名。
             *
             * 会话本来就不存在时也回 success：客户端要的是"确保没了"，
             * 报错只会让它误以为清理失败。
             */
            delete("/upload/session") {
                val id = call.request.queryParameters["session"] ?: ""
                withContext(Dispatchers.IO) { uploadSessions.discard(id) }
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // ───────── 远端推送作业（core 暂存 → 外部存储源，2026-09-21）─────────
            //
            // 这四个端点存在的唯一理由：**第二阶段必须可见、可管理**。
            // 手机那一段有进度条，而「core → 远端」发生在之后、在设备上、可能排队几分钟，
            // 如果不暴露出来，用户看到"上传完成"却在远端找不到文件，也没有任何补救入口。

            /**
             * 列出全部推送作业（新的在前，含 6 小时内的已完成/失败记录）。
             * 客户端**轮询**这个端点驱动任务面板。
             */
            get("/remote-push") {
                val jobs = remotePush.list()
                call.respond(toJsonElement(mapOf(
                    "jobs" to jobs.map { pushJobJson(it) },
                    "active_count" to jobs.count { it.active }
                )))
            }

            /**
             * 取消一个在途作业（含删暂存文件）。
             *
             * 已结束的作业返回 `success:false` 而不是报错 —— 客户端拿到的列表可能是 1 秒前的，
             * 点到一个刚刚成功的作业是正常竞态，不该弹错误。
             */
            delete("/remote-push") {
                val id = call.request.queryParameters["job"] ?: ""
                val ok = remotePush.cancel(id)
                call.respond(toJsonElement(mapOf("success" to ok)))
            }

            /**
             * 重试失败/已取消的作业。
             *
             * 暂存文件还在才可能重试 —— 那省掉的正是最贵的一段（手机到 core）。
             * 已被清理时回 409，客户端据此提示"请重新上传"。
             */
            post("/remote-push/retry") {
                val body = call.receiveJsonObject()
                val id = body["job_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val job = remotePush.retry(id)
                if (job == null) {
                    call.respondFail(
                        HttpStatusCode.Conflict, ErrorCode.CONFLICT,
                        "暂存文件已清理或作业不存在，请重新上传"
                    )
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to true, "job" to pushJobJson(job))))
            }

            /** 清掉所有已结束的记录（失败作业的暂存文件一并删除）。 */
            post("/remote-push/clear") {
                val removed = withContext(Dispatchers.IO) { remotePush.clearFinished() }
                call.respond(toJsonElement(mapOf("success" to true, "removed" to removed)))
            }

            // ───────── 归档操作：解压 / 压缩 / 校验和 ─────────


            /**
             * 解压。支持 zip / tar / tar.gz(.tgz) / 单文件 gz。
             * body: { path: 压缩包绝对路径, destination?: 解压目标目录（缺省 = 同目录下去扩展名的同名文件夹） }
             * 所有源/目标路径都走 safeResolveForRead / safeResolve + isUserStoragePath 校验，防越权与 zip-slip。
             */
            post("/extract") {
                val body = call.receiveJsonObject()
                val archivePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val destReq = body["destination"]?.jsonPrimitive?.contentOrNull
                val realArchive = safeResolveForRead(archivePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val src = File(realArchive)
                if (!src.isFile) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "不是文件或不存在")
                    return@post
                }
                val kind = archiveKindOf(src.name)
                if (kind == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "不支持的压缩格式：${src.name}")
                    return@post
                }
                val destDir = if (destReq.isNullOrBlank()) {
                    File(src.parentFile ?: File(INTERNAL_STORAGE), src.nameWithoutExtension.ifBlank { src.name + "_extracted" })
                } else {
                    val r = safeResolve(destReq) ?: run {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid destination")
                        return@post
                    }
                    File(r)
                }
                if (!isUserStoragePath(destDir.canonicalPath)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "目标路径不在用户存储范围内")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        destDir.mkdirs()
                        when (kind) {
                            "zip" -> src.inputStream().buffered().use { ArchiveExtractors.extractZip(it, destDir) }
                            "tgz", "tar" -> src.inputStream().buffered().use { ArchiveExtractors.extractTar(it, destDir, gzip = kind == "tgz") }
                            "gz" -> ArchiveExtractors.extractGz(
                                src, destDir,
                                src.name.removeSuffix(".gz").removeSuffix(".GZ").ifBlank { src.name + ".out" }
                            )
                        }
                        true
                    }.getOrDefault(false)
                }
                if (!ok) {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, "解压失败（可能格式损坏或含非法路径）")
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to true, "destination" to destDir.absolutePath, "kind" to kind)))
            }

            /**
             * 压缩。把一个或多个源打成一个 zip。
             * body: { paths: [绝对路径...], destination?: 目标 zip 路径（缺省 = 首源同目录下 <首源名>.zip） }
             * 目标已存在或与某源是同一文件时拒绝（避免覆盖/自包含）。
             */
            post("/compress") {
                val body = call.receiveJsonObject()
                val paths = body["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
                val destReq = body["destination"]?.jsonPrimitive?.contentOrNull
                if (paths.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少源文件")
                    return@post
                }
                val sources = paths.mapNotNull { safeResolveForRead(it) }
                if (sources.size != paths.size) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "存在非法路径")
                    return@post
                }
                val destFile = if (destReq.isNullOrBlank()) {
                    val first = File(sources.first())
                    File(first.parentFile ?: File(INTERNAL_STORAGE), "${first.nameWithoutExtension.ifBlank { "archive" }}.zip")
                } else {
                    val r = safeResolve(destReq) ?: run {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid destination")
                        return@post
                    }
                    File(r)
                }
                if (!isUserStoragePath(destFile.canonicalPath)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "目标路径不在用户存储范围内")
                    return@post
                }
                if (destFile.exists()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "目标文件已存在")
                    return@post
                }
                if (sources.any { File(it).canonicalPath == destFile.canonicalPath }) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "不能把压缩包压到自身")
                    return@post
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        destFile.parentFile?.mkdirs()
                        ArchiveExtractors.zipPaths(sources.map { File(it) }, destFile)
                        destFile.exists()
                    }.getOrDefault(false)
                }
                if (!ok) {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, "压缩失败")
                    return@post
                }
                call.respond(toJsonElement(mapOf("success" to true, "path" to destFile.absolutePath, "size" to destFile.length())))
            }

            /**
             * 校验和。body: { path, algorithms? }；algorithms 缺省 [md5, sha1, sha256]，仅接受这四者（大小写不敏感）。
             * 返回 { path, algorithms: { md5: "...", sha256: "..." } }。
             */
            post("/checksum") {
                val body = call.receiveJsonObject()
                val target = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val requested = body["algorithms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.map { it.lowercase() }?.filter { it.isNotBlank() }
                val algos = (requested?.takeIf { it.isNotEmpty() } ?: listOf("md5", "sha1", "sha256"))
                    .distinct().filter { it in setOf("md5", "sha1", "sha256", "sha512") }
                if (algos.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "不支持的算法")
                    return@post
                }
                val real = safeResolveForRead(target) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@post
                }
                val f = File(real)
                if (!f.isFile) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "不是文件或不存在")
                    return@post
                }
                val results = withContext(Dispatchers.IO) {
                    algos.associateWith { digestFile(f, it) }
                }
                call.respond(toJsonElement(mapOf("success" to true, "path" to real, "algorithms" to results)))
            }
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /**
     * 清掉这一批 `listFiles()` 结果里过期的 `.ufipart`（清理闸门 3）。
     *
     * 判据是 **mtime**，不是内存里的会话时间戳：core 被杀或断电之后内存表全丢，
     * 文件的修改时间是唯一还活着的活动痕迹。
     *
     * 只删"过期的"：正在传的分片 mtime 一直在刷新，不会被误删。
     */
    private fun purgeStaleParts(entries: List<File>) {
        val now = System.currentTimeMillis()
        for (f in entries) {
            if (!f.isFile || !f.name.endsWith(UploadSessionStore.PART_SUFFIX)) continue
            if (UploadSessionStore.isStalePart(f, now, UploadSessionStore.DEFAULT_TTL_MS)) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     * 目标路径所在卷的可用字节数；拿不到返回 -1（调用方据此跳过检查而不是误判为 0）。
     *
     * 用 `StatFs(目录)` 而不是固定查内部存储：目标可能在 SD 卡上，两个卷的余量毫无关系。
     */
    private fun freeSpaceOf(dir: String): Long =
        runCatching { StatFs(dir).availableBytes }.getOrDefault(-1L)

    /**
     * 从 `Range` 头里取起始字节。只认 `bytes=<start>-...` 这一种形态。
     *
     * 返回 null 表示"没有 Range / 认不出来"，调用方据此回 200 全量 —— 对播放器来说
     * 200 全量是可用的降级（只是不能 seek），而胡乱解析一个 start 会直接放出错位数据。
     * 多段 Range（`bytes=0-99,200-299`）也走 null：那是极少用到的形态，
     * 假装支持比明确不支持更糟。
     */
    private fun parseRangeStart(header: String?): Long? {
        val raw = header?.trim() ?: return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.removePrefix("bytes=").removePrefix("BYTES=").trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash <= 0) return null // `-500`（末尾若干字节）也不支持，交给 200 全量
        return spec.substring(0, dash).trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    /** 推送作业的 JSON 形态。`state` 用小写，与 app 侧的状态字符串口径一致。 */
    private fun pushJobJson(job: RemotePushManager.PushJob): Map<String, Any?> = mapOf(
        "id" to job.id,
        "file_name" to job.fileName,
        "source_id" to job.sourceId,
        "source_label" to job.sourceLabel,
        "dest_path" to job.destPath,
        "state" to job.state.name.lowercase(),
        "progress" to job.progress,
        "sent_bytes" to job.sentBytes,
        "total_bytes" to job.totalBytes,
        "error" to job.error,
        "error_detail" to job.errorDetail,
        "created_at" to job.createdAt,
        "finished_at" to job.finishedAt,
        "retryable" to job.retryable
    )

    /**
     * 远端目录里不冲突的文件名。与本地的 [uniqueChildName] 同一个策略（`name (1).ext`），
     * 但每次探测都是一次**网络往返**，所以上限只有 [REMOTE_RENAME_PROBES] 次。
     *
     * 同时也要避开暂存目录里的同名文件：那意味着有一个还没推完的作业正打算占用这个名字，
     * 撞上去会让两个作业互相覆盖暂存文件。
     *
     * 探测失败（网络不通等）一律当"不存在"：这时候真正的报错该由第二阶段的推送给出，
     * 在开会话时因为探测失败就拒绝上传反而让用户无从下手。
     */
    private suspend fun uniqueRemoteName(
        provider: FileProvider,
        relDir: String,
        stagingDir: File,
        name: String
    ): String {
        suspend fun taken(candidate: String): Boolean {
            if (File(stagingDir, candidate).exists()) return true
            return runCatching {
                provider.info(RemotePushManager.joinRemote(relDir, candidate))
                true
            }.getOrDefault(false)
        }
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..REMOTE_RENAME_PROBES) {
            val candidate = "$stem ($i)$ext"
            if (!taken(candidate)) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    /**
     * 目标目录里不冲突的文件名。已存在时按 `name (1).ext` 递增。
     *
     * 这是用户选定的「自动改名」策略：传大文件时静默覆盖的代价远高于小文件
     * （覆盖掉的可能是几 GB 的东西），而弹确认又会打断流程。
     *
     * 扩展名按**最后一个点**切分，且点在首位时不算扩展名（`.bashrc` 应该变成
     * `.bashrc (1)` 而不是 ` (1).bashrc`）。
     *
     * 上限 9999 次：真撞到那么多同名就直接用带随机后缀的名字，避免死循环。
     */
    private fun uniqueChildName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val candidate = "$stem ($i)$ext"
            if (!File(dir, candidate).exists()) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }


    /**
     * Range 流式响应 —— `/api/files/stream`（头部鉴权，app 端）与 [MEDIA_STREAM_PATH]
     * （票据鉴权，web 端）**共用同一份实现**。
     *
     * 抽出来的理由很实际：两个入口的鉴权方式不同，但「怎么把字节吐出去」必须逐字节一致。
     * 各写一份的话，将来只在其中一处修 Range 边界或缓冲，就会出现「app 能 seek、web 不能」
     * 这类只在一端复现的问题。
     *
     * 语义：
     * - 带 `Range: bytes=…` → 206 + `Content-Range` + `Accept-Ranges`（支持 `bytes=-500` 后缀式）；
     * - 不带 → 200 全量，但仍声明 `Accept-Ranges: bytes`，让播放器知道可以 seek；
     * - `Content-Disposition: inline` —— 不能是 attachment，否则浏览器会当下载处理。
     */
    private suspend fun ApplicationCall.respondFileStream(f: File) {
        val fileName = f.name
        val mimeType = MimeTypes.fromFileName(fileName)
        val fileSize = f.length()
        val rangeHeader = request.header(HttpHeaders.Range)

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val firstRange = rangeHeader.removePrefix("bytes=").split(",")[0].trim()
            val parts = firstRange.split("-")
            val isSuffix = parts[0].isBlank()
            val start = if (isSuffix) {
                // bytes=-500 → 取末尾 500 字节
                val suffix = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                (fileSize - suffix).coerceAtLeast(0L)
            } else {
                parts[0].toLongOrNull() ?: 0L
            }
            val end = if (isSuffix) fileSize - 1 else (parts.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1))
            val safeEnd = minOf(end, fileSize - 1)
            if (start < 0 || start > safeEnd) {
                respondFail(
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    ErrorCode.BAD_REQUEST, "range not satisfiable"
                )
                return
            }
            val contentLength = safeEnd - start + 1
            response.header(HttpHeaders.AcceptRanges, "bytes")
            response.header(HttpHeaders.ContentRange, "bytes $start-$safeEnd/$fileSize")
            response.header(HttpHeaders.ContentLength, contentLength.toString())
            response.header(HttpHeaders.ContentDisposition, contentDisposition("inline", fileName))
            respondOutputStream(ContentType.parse(mimeType), HttpStatusCode.PartialContent) {
                withContext(Dispatchers.IO) {
                    f.inputStream().use { input ->
                        // InputStream.skip 不保证一次跳过全部字节，必须循环补齐
                        var skipped = 0L
                        while (skipped < start) {
                            val n = input.skip(start - skipped)
                            if (n <= 0L) break
                            skipped += n
                        }
                        if (skipped < start) return@withContext
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read <= 0) break
                            write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
            }
        } else {
            response.header(HttpHeaders.AcceptRanges, "bytes")
            response.header(HttpHeaders.ContentLength, fileSize.toString())
            response.header(HttpHeaders.ContentDisposition, contentDisposition("inline", fileName))
            respondOutputStream(ContentType.parse(mimeType)) {
                withContext(Dispatchers.IO) {
                    f.inputStream().use { it.copyTo(this@respondOutputStream, STREAM_BUFFER_SIZE) }
                }
            }
        }
    }

    /** 解析并校验路径：仅允许内部存储与 SD 卡，拒绝任何系统目录。 */
    /**
     * 读取/下载前的二次校验：重新对路径做 realpath（跟随符号链接），
     * 确认解析后的真实路径仍落在用户存储范围内，
     * 防止 safeResolve() 与真正 I/O 之间的 TOCTOU 符号链接逃逸（localhost 限定）。
     * 返回用于实际操作的真实路径；不在范围内返回 null。
     */
    private fun safeResolveForRead(path: String): String? {
        val resolved = safeResolve(path) ?: return null
        val real = runCatching { File(resolved).canonicalPath }.getOrNull() ?: return null
        return if (isUserStoragePath(real)) real else null
    }

    private fun safeResolve(path: String): String? {
        val cleaned = path.replace(Regex("/+"), "/").trimEnd('/')
        if (!isSafePath(cleaned)) return null
        val base = when {
            cleaned == "/sdcard" -> INTERNAL_STORAGE
            cleaned.startsWith("/sdcard/") -> INTERNAL_STORAGE + cleaned.removePrefix("/sdcard")
            else -> cleaned
        }
        val f = File(base)
        val canonical = runCatching { f.canonicalPath }.getOrNull()
        val resolved = if (canonical != null) {
            canonical
        } else {
            // 文件/父链尚不存在：向上找到最近“已存在”的祖先目录，规范化后拼回剩余相对段，
            // 避免原本想创建的嵌套路径（如 /A/B/C.txt）被错误地 relocate 到存储根。
            var ancestor = f
            while (ancestor.parentFile != null && !ancestor.exists()) ancestor = ancestor.parentFile!!
            val canonAncestor = runCatching { ancestor.canonicalPath }.getOrNull() ?: return null
            val suffix = base.removePrefix(ancestor.path).replace(Regex("/+"), "/")
            "$canonAncestor$suffix".replace(Regex("/+"), "/")
        }
        return if (isUserStoragePath(resolved)) resolved else null
    }

    private fun entryToFileMap(file: File, basePath: String): Map<String, Any> {
        return mapOf(
            "name" to file.name,
            "path" to file.absolutePath,
            "isDirectory" to file.isDirectory,
            "size" to file.length(),
            "lastModified" to file.lastModified(),
            "permissions" to permsOf(file),
            "isSymlink" to runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        )
    }

    /** 基于 File 的读/写/执行权限合成 rwxrwxrwx 展示串（进程以单一 uid 运行，三组一致）。 */
    private fun permsOf(f: File): String {
        val r = if (f.canRead()) 'r' else '-'
        val w = if (f.canWrite()) 'w' else '-'
        val x = if (f.canExecute()) 'x' else '-'
        val d = if (f.isDirectory) 'd' else '-'
        return "$d$r$w$x$r$w$x$r$w$x"
    }

    /** 递归复制（Files.copy 不自动递归目录）。 */
    private fun copyRecursively(src: File, dst: File): Boolean {
        return if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles().orEmpty().all { copyRecursively(it, File(dst, it.name)) }
        } else {
            runCatching {
                dst.parentFile?.mkdirs()
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                true
            }.getOrDefault(false)
        }
    }

    private fun collectDiskUsage(): List<Map<String, String>> {
        val disks = mutableListOf<Map<String, String>>()
        // 内部存储
        runCatching {
            val sf = StatFs(INTERNAL_STORAGE)
            val total = sf.totalBytes
            if (total > 0) {
                val avail = sf.availableBytes
                val used = total - avail
                disks.add(diskMap("内部存储", INTERNAL_STORAGE, total, used, avail))
            }
        }
        // SD 卡：枚举 /storage/ 下非 emulated/self 的挂载点
        val storageRoot = File("/storage")
        storageRoot.listFiles().orEmpty().forEach { d ->
            if (!d.isDirectory) return@forEach
            val name = d.name
            if (name == "emulated" || name == "self") return@forEach
            runCatching {
                val sf = StatFs(d.absolutePath)
                val total = sf.totalBytes
                if (total > 0) {
                    val avail = sf.availableBytes
                    disks.add(diskMap("SD卡", d.absolutePath, total, total - avail, avail))
                }
            }
        }
        if (disks.isEmpty()) {
            disks.add(diskMap("内部存储", INTERNAL_STORAGE, 0, 0, 0))
        }
        return disks
    }

    private fun diskMap(label: String, mount: String, total: Long, used: Long, avail: Long): Map<String, String> {
        return mapOf(
            "filesystem" to mount,
            "size" to formatSize(total),
            "used" to formatSize(used),
            "available" to formatSize(avail),
            "usePercent" to if (total > 0) "${used * 100 / total}%" else "0%",
            "mount" to mount,
            "label" to label
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0"
        val gb = 1024.0 * 1024 * 1024
        val mb = 1024.0 * 1024
        val kb = 1024.0
        return when {
            bytes >= gb -> String.format("%.1fG", bytes / gb)
            bytes >= mb -> String.format("%.0fM", bytes / mb)
            bytes >= kb -> String.format("%.0fK", bytes / kb)
            else -> "${bytes}B"
        }
    }

    /** 计算单文件指定算法的十六进制摘要（流式，不整文件入内存）。 */
    private fun digestFile(f: File, algo: String): String {
        val jvmName = when (algo) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            else -> return ""
        }
        val md = MessageDigest.getInstance(jvmName)
        f.inputStream().buffered(64 * 1024).use { ins ->
            val buf = ByteArray(64 * 1024)
            var n = ins.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("..")) return false
        val blocked = setOf(';', '|', '`', '$', '"', '\'', '\n', '\r', '\u0000')
        if (path.any { it in blocked }) return false
        return true
    }

    /** 仅允许内部存储与 SD 卡路径（不含系统目录）。 */
    private fun isUserStoragePath(path: String): Boolean {
        // 前缀带分隔符比对：裸 startsWith("/sdcard") 会放过 `/sdcardXYZ` 这类同前缀的旁路目录
        val userPrefixes = listOf("/sdcard", "/storage", "/mnt/media_rw")
        return userPrefixes.any { path == it || path.startsWith("$it/") }
    }

    private fun isDangerousDeletePath(path: String): String? {
        val normalized = path.trimEnd('/').lowercase()
        val forbiddenRoots = setOf(
            "/", "/system", "/data", "/data/data", "/data/system",
            "/vendor", "/product", "/odm", "/etc", "/bin", "/sbin",
            "/system/bin", "/system/xbin", "/system/etc",
            "/boot", "/recovery", "/cache", "/data/local/tmp",
            "/storage", "/storage/emulated", "/storage/self"
        )
        if (normalized in forbiddenRoots) return "禁止删除系统关键目录：$path"
        if (normalized == "/sdcard" || normalized == "/storage/emulated/0") return "禁止删除存储根目录"
        return null
    }

    private fun parentOf(path: String): String? {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) null else p.substring(0, idx).ifEmpty { "/" }
    }
}
