package com.ufi_axis_core.api.routes

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.MimeTypes

import io.ktor.http.*
import io.ktor.http.content.*
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

/**
 * 文件管理路由 — 无 root 的本地文件管理器（基础 NAS 能力）。
 *
 * 全部操作基于 java.io.File / java.nio.file，不再调用 su / ShellExecutor。
 * 仅允许访问用户存储（内部存储 + SD 卡），任何指向系统目录的路径都被拒绝。
 */
class FileRoutes {

    companion object {
        private const val MAX_READ_SIZE = 512 * 1024 // 512 KB 文本读取上限
        private const val MAX_DOWNLOAD_SIZE = 50L * 1024 * 1024 // 50 MB 下载上限
        private const val INTERNAL_STORAGE = "/storage/emulated/0"

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
                call.respond(toJsonElement(mapOf("isExternalStorageManager" to granted)))
            }

            get("/list") {
                val path = call.request.queryParameters["path"] ?: INTERNAL_STORAGE
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
                    val files = dir.listFiles().orEmpty()
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .map { entryToFileMap(it, realPath) }
                    call.respond(toJsonElement(mapOf(
                        "files" to files, "path" to realPath, "parent" to parentOf(realPath)
                    )))
                }
            }

            get("/info") {
                val filePath = call.request.queryParameters["path"] ?: ""
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
                val realPath = safeResolveForRead(filePath) ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid path")
                    return@get
                }
                val f = File(realPath)
                if (!f.isFile) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "文件不存在")
                    return@get
                }
                val fileName = f.name
                val mimeType = MimeTypes.fromFileName(fileName)
                val fileSize = f.length()
                val rangeHeader = call.request.header(HttpHeaders.Range)

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
                        call.respondFail(HttpStatusCode.RequestedRangeNotSatisfiable,
                            ErrorCode.BAD_REQUEST, "range not satisfiable")
                        return@get
                    }
                    val contentLength = safeEnd - start + 1
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$safeEnd/$fileSize")
                    call.response.header(HttpHeaders.ContentLength, contentLength.toString())
                    call.response.header(HttpHeaders.ContentDisposition, contentDisposition("inline", fileName))
                    call.respondOutputStream(ContentType.parse(mimeType), HttpStatusCode.PartialContent) {
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
                                val buffer = ByteArray(8192)
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
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                    call.response.header(HttpHeaders.ContentDisposition, contentDisposition("inline", fileName))
                    call.respondOutputStream(ContentType.parse(mimeType)) {
                        withContext(Dispatchers.IO) { f.inputStream().use { it.copyTo(this@respondOutputStream) } }
                    }
                }
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
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

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
