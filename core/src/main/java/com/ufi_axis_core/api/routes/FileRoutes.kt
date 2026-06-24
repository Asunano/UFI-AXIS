package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.MimeTypes
import com.ufi_axis_core.util.ShellExecutor

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

class FileRoutes {

    companion object {
        private const val MAX_READ_SIZE = 512 * 1024 // 512 KB text read limit
        private const val MAX_DOWNLOAD_SIZE = 50L * 1024 * 1024 // 50 MB download limit
    }

    fun register(route: Route) {
        route.route("/files") {
            get("/list") {
                val path = call.request.queryParameters["path"] ?: "/sdcard"
                val force = call.request.queryParameters["force"]?.toBoolean() ?: false
                if (!isSafePath(path)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@get
                }

                // Path restriction: only allow user storage paths unless force=true
                if (!force && !isUserStoragePath(path)) {
                    call.respond(toJsonElement(mapOf(
                        "files" to emptyList<String>(),
                        "path" to path, "parent" to parentOf(path),
                        "error" to "PROTECTED_PATH",
                        "message" to "此路径为系统目录，请在工具页启用 Root 模式后访问"
                    )))
                    return@get
                }

                // Resolve symlink chains: /sdcard → /storage/self/primary → /storage/emulated/0
                val realPath = resolveRealPath(path) ?: path

                // Try ls with --time-style=long-iso (GNU coreutils)
                // BusyBox ls 不支持此参数但可能仍以 exit 0 退出，需检查输出内容
                var result = rootShell("ls -la --time-style=long-iso \"$realPath\"")
                var hasIsoTime = result.isSuccess
                    && !result.stdout.contains("Unknown option")
                    && !result.stdout.contains("unrecognized option")
                    && !result.stdout.contains("invalid option")

                if (!hasIsoTime) {
                    result = rootShell("ls -la \"$realPath\"")
                    // BusyBox ls 默认输出 ISO 格式日期 (YYYY-MM-DD HH:MM)，需检测
                    if (result.isSuccess && result.stdout.isNotBlank()) {
                        val sampleLine = result.stdout.lines().firstOrNull {
                            it.isNotBlank() && !it.startsWith("total")
                        } ?: ""
                        // ISO 日期模式: "2025-01-08" 在 size 之后
                        if (Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(sampleLine)) {
                            hasIsoTime = true
                        }
                    }
                }

                // If ls succeeded, check output is actually parseable
                val lines = if (result.isSuccess) {
                    result.stdout.lines().filter { it.isNotBlank() && !it.startsWith("total") }
                } else emptyList()

                val files = if (result.isSuccess && lines.isNotEmpty()) {
                    lines.mapNotNull { parseLsLine(it, realPath, hasIsoTime) }
                } else if (result.isSuccess) {
                    // ls succeeded but no output — truly empty directory
                    emptyList()
                } else {
                    // ls failed — try find as last resort (batch: 3N → 1)
                    val findResult = rootShell("find \"$realPath\" -maxdepth 1 ! -path \"$realPath\"")
                    if (findResult.isSuccess && findResult.stdout.isNotBlank()) {
                        val entries = findResult.stdout.lines().filter { it.isNotBlank() }
                        // Single script to collect all file info at once
                        val script = buildString {
                            append("for f in ")
                            append(entries.joinToString(" ") { "\"$it\"" })
                            append("; do ")
                            append("n=\$(basename \"\$f\"); ")
                            append("s=\$(stat -L -c '%s %Y' \"\$f\" 2>/dev/null || echo '0 0'); ")
                            append("d=\$(test -d \"\$f\" && echo d || echo f); ")
                            append("l=\$(test -L \"\$f\" && echo l || echo r); ")
                            append("echo \"\$n|\$s|\$d|\$l\"; ")
                            append("done")
                        }
                        val batchResult = rootShell(script)
                        batchResult.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                            val parts = line.split("|")
                            if (parts.size < 4) return@mapNotNull null
                            val name = parts[0].trim()
                            if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                            val sizeTime = parts[1].trim().split(" ")
                            val sz = sizeTime.getOrNull(0)?.toLongOrNull() ?: 0L
                            val mt = sizeTime.getOrNull(1)?.toLongOrNull()?.let { it * 1000 } ?: 0L
                            val isDir = parts[2].trim() == "d"
                            val isSym = parts[3].trim() == "l"
                            mapOf(
                                "name" to name, "path" to "$realPath/$name",
                                "isDirectory" to isDir, "size" to sz,
                                "lastModified" to mt, "permissions" to "",
                                "isSymlink" to isSym
                            )
                        }
                    } else {
                        // All methods failed — return diagnostic error
                        val errMsg = result.stdout.trim().take(300).ifBlank { "未知错误" }
                        call.respond(toJsonElement(mapOf(
                            "files" to emptyList<String>(),
                            "path" to realPath, "parent" to parentOf(realPath),
                            "error" to errMsg,
                            "message" to "无法列出目录内容。请检查：\n1. 存储权限是否已授权\n2. 路径是否正确\n3. 尝试启用 Root 模式"
                        )))
                        return@get
                    }
                }

                call.respond(toJsonElement(mapOf(
                    "files" to files,
                    "path" to realPath, "parent" to parentOf(realPath)
                )))
            }

            get("/info") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@get
                }

                val name = filePath.substringAfterLast("/")

                // Batch: stat + test -d + test -L → 1 shell call
                val infoScript = """stat -L "$filePath" 2>/dev/null; echo "|__DIR__|"; test -d "$filePath" && echo yes || echo no; echo "|__SYM__|"; test -L "$filePath" && echo yes || echo no"""
                val infoResult = rootShell(infoScript)
                val sections = infoResult.stdout.split("|__DIR__|", "|__SYM__|")

                val statOutput = sections.getOrNull(0) ?: ""
                val dirCheck = sections.getOrNull(1)?.trim() ?: ""
                val symCheck = sections.getOrNull(2)?.trim() ?: ""

                var isDir = statOutput.contains("directory", ignoreCase = true) || dirCheck == "yes"
                val isSymlink = !statOutput.contains("symbolic link") && symCheck == "yes"

                val size = Regex("Size:\\s*(\\d+)").find(statOutput)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                // Try multiple stat output formats for permissions
                val perms = tryStatFormat(filePath)

                val owner = Regex("Uid:.*?/\\)\\s*(\\S+)").find(statOutput)?.groupValues?.get(1) ?: ""
                val group = Regex("Gid:.*?/\\)\\s*(\\S+)").find(statOutput)?.groupValues?.get(1) ?: ""

                val mtime = Regex("Modify:\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?)")
                    .find(statOutput)?.groupValues?.get(1)?.trim() ?: ""
                val epoch = parseModifyTime(mtime)

                call.respond(toJsonElement(mapOf(
                    "name" to name, "path" to filePath, "isDirectory" to isDir,
                    "size" to size, "lastModified" to epoch, "permissions" to perms,
                    "owner" to owner, "group" to group, "isSymlink" to isSymlink
                )))
            }

            post("/read") {
                val body = call.receive<JsonObject>()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                // -L follows symlinks for correct size
                val sizeResult = rootShell("stat -L -c %s \"$filePath\" 2>/dev/null")
                val fileSize = sizeResult.stdout.trim().toLongOrNull() ?: 0
                if (fileSize > MAX_READ_SIZE) {
                    call.respond(toJsonElement(mapOf(
                        "content" to "[文件过大: ${fileSize} bytes，超过 ${MAX_READ_SIZE / 1024}KB 限制，不支持在线查看]",
                        "encoding" to "utf-8", "size" to fileSize
                    )))
                    return@post
                }
                val fileTypeResult = rootShell("file -b \"$filePath\" 2>/dev/null")
                val fileType = fileTypeResult.stdout.trim().lowercase()
                if (fileType.contains("executable") || fileType.contains("shared object") ||
                    fileType.contains("data") || fileType.contains("image") || fileType.contains("archive")) {
                    call.respond(toJsonElement(mapOf(
                        "content" to "[二进制文件: $fileType，不支持在线查看]",
                        "encoding" to "utf-8", "size" to fileSize
                    )))
                    return@post
                }
                val result = rootShell("cat \"$filePath\" 2>/dev/null")
                val content = if (result.stdout.length > MAX_READ_SIZE)
                    result.stdout.take(MAX_READ_SIZE) + "\n... [截断]"
                else result.stdout
                call.respond(toJsonElement(mapOf(
                    "content" to content, "encoding" to "utf-8", "size" to content.length
                )))
            }

            post("/write") {
                val body = call.receive<JsonObject>()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = body["content"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                // Use base64 to safely write content (avoids shell escaping issues)
                val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
                val result = rootShell(
                    "mkdir -p \"${parentOf(filePath)}\" && echo '$b64' | base64 -d > \"$filePath\""
                )
                call.respond(toJsonElement(mapOf("success" to result.isSuccess)))
            }

            post("/delete") {
                val body = call.receive<JsonObject>()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                // 安全校验：禁止删除系统关键目录
                val dangerCheck = isDangerousDeletePath(filePath)
                if (dangerCheck != null) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                        "success" to false, "error" to dangerCheck
                    )))
                    return@post
                }
                // 校验路径存在
                val exists = rootShell("test -e \"$filePath\" && echo yes").stdout.trim() == "yes"
                if (!exists) {
                    call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf(
                        "success" to false, "error" to "文件或目录不存在"
                    )))
                    return@post
                }
                val result = rootShell("rm -rf \"$filePath\"")
                val stillExists = rootShell("test -e \"$filePath\" && echo yes").stdout.trim() == "yes"
                call.respond(toJsonElement(mapOf(
                    "success" to (result.isSuccess && !stillExists),
                    "deleted" to result.isSuccess,
                    "still_exists" to stillExists
                )))
            }

            post("/rename") {
                val body = call.receive<JsonObject>()
                val oldPath = body["old_path"]?.jsonPrimitive?.contentOrNull ?: ""
                val newPath = body["new_path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(oldPath) || !isSafePath(newPath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                val result = rootShell("mv \"$oldPath\" \"$newPath\"")
                call.respond(toJsonElement(mapOf("success" to result.isSuccess)))
            }

            post("/move") {
                val body = call.receive<JsonObject>()
                val source = body["source"]?.jsonPrimitive?.contentOrNull ?: ""
                val dest = body["destination"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(source) || !isSafePath(dest)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }

                // ① 校验来源存在
                val srcExists = rootShell("test -e \"$source\" && echo yes").stdout.trim() == "yes"
                if (!srcExists) {
                    call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf(
                        "success" to false, "error" to "源文件或目录不存在"
                    )))
                    return@post
                }

                // ② 防止递归移动：目标路径是源路径的子目录 → 死循环
                val srcNormalized = source.trimEnd('/')
                val destNormalized = dest.trimEnd('/')
                if (destNormalized.startsWith("$srcNormalized/")) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                        "success" to false, "error" to "不能将目录移动到自身内部"
                    )))
                    return@post
                }

                // ③ 记录源文件/目录的信息（大小校验用）
                val isDir = rootShell("test -d \"$source\" && echo yes").stdout.trim() == "yes"
                val srcSize = if (!isDir) {
                    rootShell("stat -L -c %s \"$source\" 2>/dev/null").stdout.trim().toLongOrNull() ?: -1L
                } else -1L

                // ④ 目标已存在时标记
                val destAlreadyExists = rootShell("test -e \"$dest\" && echo yes").stdout.trim() == "yes"

                // ⑤ 优先尝试 mv（同一文件系统下直接原子移动）
                var moved = false
                var usedFallback = false
                var integrityOk = false

                val mvResult = rootShell("mv \"$source\" \"$dest\"")
                moved = mvResult.isSuccess
                if (moved) {
                    // mv 成功 → 验证目标完整性
                    integrityOk = verifyMoveIntegrity(source, dest, isDir, srcSize)
                    if (!integrityOk) {
                        // mv 后完整性校验失败（极低概率），尝试恢复
                        rootShell("mv \"$dest\" \"$source\"")
                        moved = false
                    }
                }

                if (!moved) {
                    // ⑥ mv 失败（如跨文件系统），改用 cp + rm
                    val copyResult = rootShell("cp -rf \"$source\" \"$dest\"")
                    if (copyResult.isSuccess) {
                        integrityOk = verifyMoveIntegrity(source, dest, isDir, srcSize)
                        if (integrityOk) {
                            rootShell("rm -rf \"$source\"")
                            moved = true
                            usedFallback = true
                        } else {
                            // cp 后完整性校验失败 → 清理目标 → 保留源文件
                            rootShell("rm -rf \"$dest\"")
                        }
                    }
                }

                // 验证最终结果
                val destExists = rootShell("test -e \"$dest\" && echo yes").stdout.trim() == "yes"
                val sourceGone = rootShell("test -e \"$source\" && echo yes").stdout.trim() != "yes"
                call.respond(toJsonElement(mapOf(
                    "success" to (moved && integrityOk && destExists && sourceGone),
                    "dest_exists" to destExists,
                    "source_deleted" to sourceGone,
                    "dest_already_existed" to destAlreadyExists,
                    "fallback_copy" to usedFallback,
                    "integrity_ok" to integrityOk
                )))
            }

            post("/copy") {
                val body = call.receive<JsonObject>()
                val source = body["source"]?.jsonPrimitive?.contentOrNull ?: ""
                val dest = body["destination"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(source) || !isSafePath(dest)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                val result = rootShell("cp -rf \"$source\" \"$dest\"")
                call.respond(toJsonElement(mapOf("success" to result.isSuccess)))
            }

            post("/mkdir") {
                val body = call.receive<JsonObject>()
                val dirPath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(dirPath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                val result = rootShell("mkdir -p \"$dirPath\"")
                call.respond(toJsonElement(mapOf("success" to result.isSuccess)))
            }

            // 搜索文件（按名称模糊匹配）
            get("/search") {
                val path = call.request.queryParameters["path"] ?: "/storage/emulated/0"
                val query = call.request.queryParameters["query"] ?: ""
                val maxDepth = call.request.queryParameters["depth"]?.toIntOrNull() ?: 3
                if (!isSafePath(path) || query.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path or empty query")))
                    return@get
                }
                val realPath = resolveRealPath(path) ?: path
                // 使用 find + grep 搜索，限制深度和结果数
                val result = rootShell("find \"$realPath\" -maxdepth $maxDepth -iname \"*$query*\" 2>/dev/null | head -50")
                val files = if (result.isSuccess && result.stdout.isNotBlank()) {
                    result.stdout.lines().filter { it.isNotBlank() }.map { filePath ->
                        val name = filePath.substringAfterLast("/")
                        val isDir = filePath.endsWith("/") || rootShell("test -d \"$filePath\" && echo d").stdout.trim() == "d"
                        mapOf("name" to name, "path" to filePath, "isDirectory" to isDir)
                    }
                } else emptyList()
                call.respond(toJsonElement(mapOf("files" to files, "query" to query)))
            }

            // 磁盘用量（智能检测内部存储和SD卡）
            get("/disk-usage") {
                val result = rootShell("df -h 2>/dev/null")
                val lines = result.stdout.lines()
                    .filter { it.isNotBlank() && !it.startsWith("Filesystem") }

                val disks = mutableListOf<Map<String, String>>()
                var sdFound = false
                val seenFs = mutableSetOf<String>()

                for (line in lines) {
                    val parts = line.split("\\s+".toRegex())
                    val mount = parts.getOrNull(5) ?: continue
                    val fs = parts.getOrNull(0) ?: ""
                    if (fs in seenFs) continue

                    val label = when {
                        mount == "/storage/emulated/0" || mount == "/sdcard" -> "内部存储"
                        mount == "/data" -> "内部存储"
                        mount.startsWith("/mnt/media_rw/") -> { sdFound = true; "SD卡" }
                        mount.startsWith("/storage/") && mount != "/storage/emulated/0"
                            && !mount.startsWith("/storage/emulated/0/")
                            && mount.substringAfter("/storage/").let {
                                it.isNotBlank() && it.none { c -> c == '/' }
                            } -> { sdFound = true; "SD卡" }
                        else -> continue
                    }

                    // Prefer /storage/emulated/0 over /data for internal
                    if (label == "内部存储" && disks.any { it["label"] == "内部存储" }
                        && mount == "/data") continue
                    seenFs.add(fs)

                    disks.add(mapOf(
                        "filesystem" to fs,
                        "size" to (parts.getOrNull(1) ?: ""),
                        "used" to (parts.getOrNull(2) ?: ""),
                        "available" to (parts.getOrNull(3) ?: ""),
                        "usePercent" to (parts.getOrNull(4) ?: ""),
                        "mount" to mount,
                        "label" to label
                    ))
                }

                // Fallback: probe /storage/ for SD card if df didn't find one
                if (!sdFound) {
                    val lsResult = rootShell("ls -1 /storage/ 2>/dev/null")
                    if (lsResult.isSuccess) {
                        for (dir in lsResult.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }) {
                            if (dir == "emulated" || dir == "self") continue
                            val check = rootShell("df -h \"/storage/$dir\" 2>/dev/null")
                            val dfLine = check.stdout.lines().firstOrNull { l ->
                                l.isNotBlank() && !l.startsWith("Filesystem")
                            } ?: continue
                            val parts = dfLine.split("\\s+".toRegex())
                            if (parts.size >= 6) {
                                disks.add(mapOf(
                                    "filesystem" to (parts[0]),
                                    "size" to (parts.getOrNull(1) ?: ""),
                                    "used" to (parts.getOrNull(2) ?: ""),
                                    "available" to (parts.getOrNull(3) ?: ""),
                                    "usePercent" to (parts.getOrNull(4) ?: ""),
                                    "mount" to "/storage/$dir",
                                    "label" to "SD卡"
                                ))
                            }
                        }
                    }
                }

                call.respond(toJsonElement(mapOf("disks" to disks)))
            }

            // 创建空文件 (touch)
            post("/touch") {
                val body = call.receive<JsonObject>()
                val filePath = body["path"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@post
                }
                val result = rootShell("touch \"$filePath\"")
                call.respond(toJsonElement(mapOf("success" to result.isSuccess)))
            }

            // 文件下载（二进制安全，通过 base64 传输）
            get("/download") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@get
                }
                val realPath = resolveRealPath(filePath) ?: filePath
                // 检查文件大小
                val sizeResult = rootShell("stat -L -c %s \"$realPath\" 2>/dev/null")
                val fileSize = sizeResult.stdout.trim().toLongOrNull() ?: 0
                if (fileSize <= 0 || fileSize > MAX_DOWNLOAD_SIZE) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "文件不存在或超过${MAX_DOWNLOAD_SIZE / 1024 / 1024}MB限制")))
                    return@get
                }
                // 检测 MIME 类型
                val mimeResult = rootShell("file -b --mime-type \"$realPath\" 2>/dev/null")
                val mimeType = mimeResult.stdout.trim().ifBlank { "application/octet-stream" }
                // 前后端都用 Ktor Channel 流式传输，避免大文件 OOM（base64 方案已废弃）
                val fileName = realPath.substringAfterLast("/")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                call.respondOutputStream(ContentType.parse(mimeType)) {
                    withContext(Dispatchers.IO) {
                        val process = Runtime.getRuntime().exec("su -c cat \"$realPath\"")
                        try {
                            process.inputStream.use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    write(buffer, 0, read)
                                }
                            }
                        } finally {
                            process.waitFor()
                            process.destroy()
                        }
                    }
                }
            }

            // 流式文件传输（支持 HTTP Range，用于媒体播放和图片查看）
            get("/stream") {
                val filePath = call.request.queryParameters["path"] ?: ""
                if (!isSafePath(filePath)) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Invalid path")))
                    return@get
                }
                val realPath = resolveRealPath(filePath) ?: filePath
                val fileName = realPath.substringAfterLast("/")
                val mimeType = MimeTypes.fromFileName(fileName)

                withContext(Dispatchers.IO) {
                    // Use root shell to check file existence and size (bypass Scoped Storage)
                    val statResult = rootShell("stat -L -c %s \"$realPath\" 2>/dev/null")
                    val fileSize = statResult.stdout.trim().toLongOrNull()
                    if (fileSize == null || fileSize <= 0) {
                        call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf("error" to "文件不存在")))
                        return@withContext
                    }

                    val rangeHeader = call.request.header(HttpHeaders.Range)

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        // Partial content (206)
                        val ranges = rangeHeader.removePrefix("bytes=").split(",")
                        val firstRange = ranges[0].trim()
                        val parts = firstRange.split("-")
                        val start = parts[0].toLongOrNull() ?: 0L
                        val end = parts.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
                        val safeEnd = minOf(end, fileSize - 1)
                        val contentLength = safeEnd - start + 1

                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$safeEnd/$fileSize")
                        call.response.header(HttpHeaders.ContentLength, contentLength.toString())
                        call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$fileName\"")

                        call.respondOutputStream(ContentType.parse(mimeType), HttpStatusCode.PartialContent) {
                            // Use root shell dd to read range (bypass Scoped Storage)
                            val skipBlock = start / 8192
                            val countBlocks = contentLength / 8192 + 1
                            val ddCmd = "dd if=\"$realPath\" bs=8192 skip=$skipBlock count=$countBlocks 2>/dev/null"
                            val process = Runtime.getRuntime().exec("su -c $ddCmd")
                            try {
                                val skipOffset = (start % 8192).toInt()
                                if (skipOffset > 0) {
                                    process.inputStream.skip(skipOffset.toLong())
                                }
                                val buffer = ByteArray(8192)
                                var remaining = contentLength
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = process.inputStream.read(buffer, 0, toRead)
                                    if (read <= 0) break
                                    write(buffer, 0, read)
                                    remaining -= read
                                }
                            } finally {
                                process.destroy()
                            }
                        }
                    } else {
                        // Full content (200)
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                        call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$fileName\"")

                        call.respondOutputStream(ContentType.parse(mimeType)) {
                            // Use root shell cat to read file (bypass Scoped Storage)
                            val process = Runtime.getRuntime().exec("su -c cat \"$realPath\"")
                            try {
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = process.inputStream.read(buffer)
                                    if (read <= 0) break
                                    write(buffer, 0, read)
                                }
                            } finally {
                                process.destroy()
                            }
                        }
                    }
                }
            }

            // 文件上传（multipart/form-data）
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
                                    val fileName = part.originalFileName ?: "uploaded_file"
                                    if (!isSafePath(targetDir)) {
                                        throw IllegalArgumentException("Invalid target path")
                                    }
                                    val destPath = "${targetDir.trimEnd('/')}/$fileName"
                                    val tmpFile = File.createTempFile("ufi_upload_", null)
                                    try {
                                        // Stream uploaded file data directly to temp file
                                        part.streamProvider().use { input ->
                                            withContext(Dispatchers.IO) {
                                                tmpFile.outputStream().use { out ->
                                                    savedSize += input.copyTo(out)
                                                }
                                            }
                                        }
                                        // 用 root shell 移动 temp → target（跨文件系统 & 绕过 Scoped Storage 限制）
                                        val destParent = destPath.substringBeforeLast("/")
                                        rootShell("mkdir -p \"$destParent\"")
                                        val cpResult = rootShell("cp \"$tmpFile\" \"$destPath\" && rm -f \"$tmpFile\"")
                                        if (!cpResult.isSuccess) {
                                            // 最后回退：Java File API（可能受 Scoped Storage 限制）
                                            val destFile = File(destPath)
                                            destFile.parentFile?.mkdirs()
                                            if (destFile.exists()) destFile.delete()
                                            tmpFile.copyTo(destFile, overwrite = true)
                                            tmpFile.delete()
                                        }
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
                        call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("success" to false, "error" to "No file received")))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Invalid target path"))))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, toJsonElement(mapOf("success" to false, "error" to "Upload failed: ${e.message}")))
                }
            }
        }
    }

    /** Try root execution first, fallback to normal shell.
 * 直连 ShellExecutor 旁路 QoS，因为前端发起的文件操作不应受后端设备通信限流影响。 */
    private suspend fun rootShell(command: String): ShellExecutor.ShellResult {
        val result = ShellExecutor.executeAsRoot(command)
        return if (result.isSuccess) result else ShellExecutor.execute(command)
    }

    /** Resolve symlink chains (e.g., /sdcard → /storage/self/primary → /storage/emulated/0). */
    private suspend fun resolveRealPath(path: String): String? {
        val result = rootShell("realpath \"$path\" 2>/dev/null")
        val resolved = result.stdout.trim()
        return if (result.isSuccess && resolved.isNotBlank() && resolved != path) resolved else null
    }

    /** Try to get permission string from stat in a single call (3 → 1). */
    private suspend fun tryStatFormat(filePath: String): String {
        // Single stat call with both human-readable and octal formats
        val result = rootShell("stat -L -c '%A %a' \"$filePath\" 2>/dev/null")
        if (result.isSuccess) {
            val parts = result.stdout.trim().split(" ")
            // Prefer human-readable (e.g. -rw-r--r--)
            if (parts.isNotEmpty() && parts[0].matches(Regex("[-rwxsStTd]{10}"))) return parts[0]
            // Fallback to octal (e.g. 644, 755)
            if (parts.size >= 2 && parts[1].matches(Regex("\\d{3,4}"))) return parts[1]
        }
        return ""
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("..")) return false
        val blocked = setOf(';', '|', '`', '$', '"', '\'', '\n', '\r', '\u0000')
        if (path.any { it in blocked }) return false
        return true
    }

    /** Check if path is under user-accessible storage (not system directories). */
    private fun isUserStoragePath(path: String): Boolean {
        val userPrefixes = listOf("/sdcard", "/storage/", "/mnt/media_rw/")
        return userPrefixes.any { path == it || path.startsWith(it) }
    }

    /**
     * 检测是否为危险删除路径（系统关键目录），防止误删导致设备变砖。
     * @return null 表示安全，非 null 为错误描述
     */
    private fun isDangerousDeletePath(path: String): String? {
        val normalized = path.trimEnd('/').lowercase()
        // 绝对禁止的根级系统目录
        val forbiddenRoots = setOf(
            "/", "/system", "/data", "/data/data", "/data/system",
            "/vendor", "/product", "/odm", "/etc", "/bin", "/sbin",
            "/system/bin", "/system/xbin", "/system/etc",
            "/boot", "/recovery", "/cache", "/data/local/tmp",
            "/storage", "/storage/emulated", "/storage/self"
        )
        if (normalized in forbiddenRoots) return "禁止删除系统关键目录：$path"
        // 禁止删除存储根目录自身
        if (normalized == "/sdcard" || normalized == "/storage/emulated/0") return "禁止删除存储根目录"
        return null
    }

    /**
     * 验证移动/复制操作的完整性。
     * 对文件：比较源与目标的大小。
     * 对目录：验证目标存在且非空。
     */
    private suspend fun verifyMoveIntegrity(
        source: String, dest: String, isDir: Boolean, srcSize: Long
    ): Boolean {
        if (isDir) {
            // 目录：校验目标存在且不是空壳
            val destExists = rootShell("test -d \"$dest\" && echo yes").stdout.trim() == "yes"
            if (!destExists) return false
            val contentCount = rootShell("ls -A \"$dest\" 2>/dev/null | head -3 | wc -l").stdout.trim().toIntOrNull() ?: 0
            return contentCount > 0
        } else {
            // 文件：目标必须存在
            val destExists = rootShell("test -f \"$dest\" && echo yes").stdout.trim() == "yes"
            if (!destExists) return false
            // 源大小已知时做大小匹配；未知时（stat 失败）只校验目标存在即视为通过
            if (srcSize <= 0) return true
            val destSize = rootShell("stat -L -c %s \"$dest\" 2>/dev/null").stdout.trim().toLongOrNull() ?: -1L
            return destSize == srcSize
        }
    }

    private fun parentOf(path: String): String? {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) null else p.substring(0, idx).ifEmpty { "/" }
    }

    /**
     * Parse a single `ls -la` output line into a file entry map.
     *
     * With --time-style=long-iso:
     *   perms links owner group size date time name
     *   -rw-rw-rw-  1 root root  1234 2024-01-15 10:30 file.txt
     *
     * Without --time-style (legacy):
     *   perms links owner group size month day time/year name
     *   -rw-rw-rw-  1 root root  1234 Jan 15 10:30 file.txt
     */
    private fun parseLsLine(line: String, basePath: String, hasIsoTime: Boolean): Map<String, Any>? {
        if (line.isBlank()) return null
        val parts = line.split("\\s+".toRegex())

        val minParts = if (hasIsoTime) 8 else 9
        if (parts.size < minParts) return null

        val perms = parts[0]
        val isDir = perms.startsWith("d")
        val isSymlink = perms.startsWith("l")
        val size = parts[4].toLongOrNull() ?: 0

        // Name starts after fixed columns
        // iso: perms(0) links(1) owner(2) group(3) size(4) date(5) time(6) → name from index 7
        // legacy: perms(0) links(1) owner(2) group(3) size(4) month(5) day(6) time/year(7) → name from index 8
        val nameStartIdx = if (hasIsoTime) 7 else 8
        val rawName = parts.drop(nameStartIdx).joinToString(" ")

        val name = if (isSymlink) {
            // Symlink: "name -> target", strip the arrow and target
            val arrowIdx = rawName.indexOf(" -> ")
            if (arrowIdx > 0) rawName.substring(0, arrowIdx).trim() else rawName.trim()
        } else {
            rawName.trim()
        }

        if (name.isBlank() || name == "." || name == "..") return null

        val epoch = if (hasIsoTime) {
            parseModifyTime("${parts[5]} ${parts[6]}")
        } else {
            parseLegacyTime("${parts[5]} ${parts[6]} ${parts[7]}")
        }

        return mapOf(
            "name" to name, "path" to "$basePath/$name",
            "isDirectory" to isDir, "size" to size,
            "lastModified" to epoch, "permissions" to perms,
            "isSymlink" to isSymlink
        )
    }

    private fun parseModifyTime(mtime: String): Long {
        if (mtime.isBlank()) return 0L
        // Try yyyy-MM-dd HH:mm
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            return sdf.parse(mtime)?.time ?: 0L
        } catch (_: Exception) {}
        // Try yyyy-MM-dd HH:mm:ss
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            return sdf.parse(mtime)?.time ?: 0L
        } catch (_: Exception) {}
        // Try epoch seconds (from stat -c %Y)
        try {
            val epoch = mtime.substringBefore(".").toLongOrNull()
            if (epoch != null && epoch > 0) return epoch * 1000
        } catch (_: Exception) {}
        return 0L
    }

    private fun parseLegacyTime(timeStr: String): Long {
        // Try "MMM dd HH:mm" (current year files)
        try {
            val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.ENGLISH)
            return sdf.parse(timeStr)?.time ?: 0L
        } catch (_: Exception) {}
        // Try "MMM dd yyyy" (older files)
        try {
            val sdf = java.text.SimpleDateFormat("MMM dd yyyy", java.util.Locale.ENGLISH)
            return sdf.parse(timeStr)?.time ?: 0L
        } catch (_: Exception) {}
        return 0L
    }
}
