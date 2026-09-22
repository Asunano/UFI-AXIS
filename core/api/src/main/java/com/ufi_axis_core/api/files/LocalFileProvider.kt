package com.ufi_axis_core.api.files

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 本地文件系统的 [FileProvider] 实现。
 *
 * 所有操作基于 `java.io.File` / `java.nio.file`，不调用 root / ShellExecutor。
 * 仅允许访问用户存储（内部存储 + SD 卡），系统目录一律拒绝。
 *
 * 与 [com.ufi_axis_core.api.routes.FileRoutes] 中的同名私有方法**有意重复**：
 * Phase 1 的设计目标是"加一个抽象层但不改动任何已有路由行为"，
 * 所以 FileRoutes 里的原始方法暂时保留，后续 Phase 2 再逐步切换。
 */
class LocalFileProvider : FileProvider {

    override val id: String = "local"
    override val label: String = "设备存储"
    override val protocol: String = "local"
    override val readonly: Boolean = false
    override val capabilities: Set<FileProvider.Capability> = FileProvider.Capability.values().toSet()

    // ───────── 常量 ─────────

    companion object {
        const val INTERNAL_STORAGE = "/storage/emulated/0"
        const val MAX_READ_SIZE = 512 * 1024 // 512 KB
        const val MAX_DOWNLOAD_SIZE = 50L * 1024 * 1024 // 50 MB

        const val DEFAULT_SEARCH_DEPTH = 3
        const val MAX_SEARCH_DEPTH = 8
        const val MAX_SEARCH_RESULTS = 50
        const val SEARCH_TIMEOUT_MS = 10_000L

        const val REASON_TOO_LARGE = "too_large"
        const val REASON_BINARY = "binary"
        const val REASON_NOT_FILE = "not_file"

        /**
         * 把客户端给的编码名解析成 [Charset]。
         * 不认的名字回落 UTF-8 而不是报错。
         */
        fun charsetOf(name: String?): Charset = runCatching {
            if (name.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(name)
        }.getOrDefault(Charsets.UTF_8)

        /**
         * 由文件名判定可解压的归档类型。
         */
        fun archiveKindOf(name: String): String? {
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
         * 按 RFC 6266 / RFC 5987 拼 Content-Disposition。
         */
        fun contentDisposition(disposition: String, fileName: String): String {
            val ascii = fileName
                .map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }
                .joinToString("")
                .ifBlank { "download" }
            val encoded = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            return "$disposition; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }

        /**
         * `/read` 的响应构建（Provider 层的版本）。
         */
        fun buildReadResult(
            content: String,
            size: Long,
            truncated: Boolean,
            encoding: String = "utf-8",
            reason: String? = null,
            suspect: Boolean = false
        ) = ProviderReadResult(
            content = content,
            encoding = encoding,
            size = size,
            truncated = truncated,
            reason = reason,
            encodingSuspect = suspect
        )
    }

    // ───────── FileProvider 实现 ─────────

    override suspend fun list(path: String): ProviderListResult {
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        val dir = File(realPath)
        if (!dir.exists() || !dir.isDirectory) {
            return ProviderListResult(
                files = emptyList(),
                path = realPath,
                parent = parentOf(realPath),
                truncated = false
            )
        }
        return withContext(Dispatchers.IO) {
            val raw = dir.listFiles().orEmpty()
            purgeStaleParts(raw.toList())
            val files = raw
                .filter { !it.name.endsWith(UploadSessionStore.PART_SUFFIX) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { entryToProviderInfo(it) }
            ProviderListResult(
                files = files,
                path = realPath,
                parent = parentOf(realPath)
            )
        }
    }

    override suspend fun info(path: String): ProviderFileInfo {
        val realPath = safeResolveForRead(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        val f = File(realPath)
        if (!f.exists()) {
            throw FileProvider.ProviderException("文件不存在: $path")
        }
        return ProviderFileInfo(
            name = f.name,
            path = realPath,
            isDirectory = f.isDirectory,
            size = f.length(),
            lastModified = f.lastModified(),
            permissions = permsOf(f),
            isSymlink = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false),
            source = id
        )
    }

    override suspend fun read(path: String, encoding: String?): ProviderReadResult {
        val charset = charsetOf(encoding)
        val realPath = safeResolveForRead(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        return withContext(Dispatchers.IO) {
            val f = File(realPath)
            if (!f.isFile) {
                return@withContext buildReadResult(
                    "[不是文件或不存在]", 0L,
                    truncated = true, reason = REASON_NOT_FILE
                )
            }
            val fileSize = f.length()
            if (fileSize > MAX_READ_SIZE) {
                return@withContext buildReadResult(
                    "[文件过大: $fileSize bytes，超过 ${MAX_READ_SIZE / 1024}KB 限制，不支持在线查看]",
                    fileSize, truncated = true, reason = REASON_TOO_LARGE
                )
            }
            val bytes = f.readBytes()
            if (bytes.take(4096).any { it == 0.toByte() }) {
                return@withContext buildReadResult(
                    "[二进制文件，不支持在线查看]", fileSize,
                    truncated = true, reason = REASON_BINARY
                )
            }
            val text = String(bytes, charset)
            val suspect = charset == Charsets.UTF_8 && text.contains('\uFFFD')
            buildReadResult(
                text, fileSize, truncated = false,
                encoding = charset.name().lowercase(), suspect = suspect
            )
        }
    }

    override suspend fun write(path: String, content: String, encoding: String?) {
        val charset = charsetOf(encoding)
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        withContext(Dispatchers.IO) {
            val f = File(realPath)
            f.parentFile?.mkdirs()
            f.writeText(content, charset)
        }
    }

    override suspend fun delete(path: String): Boolean {
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        val danger = isDangerousDeletePath(realPath)
        if (danger != null) {
            throw FileProvider.ProviderException(danger)
        }
        return withContext(Dispatchers.IO) {
            val f = File(realPath)
            if (!f.exists()) {
                false
            } else {
                val deleted = f.deleteRecursively()
                deleted && !f.exists()
            }
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        val safeOld = safeResolve(oldPath)
            ?: throw FileProvider.ProviderException("Invalid old path: $oldPath")
        val safeNew = safeResolve(newPath)
            ?: throw FileProvider.ProviderException("Invalid new path: $newPath")
        return withContext(Dispatchers.IO) {
            runCatching {
                Files.move(Paths.get(safeOld), Paths.get(safeNew), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun move(source: String, destination: String): Map<String, Any> {
        val safeSource = safeResolve(source)
            ?: throw FileProvider.ProviderException("Invalid source path: $source")
        val safeDest = safeResolve(destination)
            ?: throw FileProvider.ProviderException("Invalid destination path: $destination")
        return withContext(Dispatchers.IO) {
            val src = File(safeSource)
            val dst = File(safeDest)
            if (!src.exists()) return@withContext mapOf<String, Any>("success" to false, "error" to "源文件或目录不存在")
            if (dst.exists()) return@withContext mapOf<String, Any>("success" to false, "error" to "目标已存在")
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
    }

    override suspend fun copy(source: String, destination: String): Boolean {
        val safeSource = safeResolve(source)
            ?: throw FileProvider.ProviderException("Invalid source path: $source")
        val safeDest = safeResolve(destination)
            ?: throw FileProvider.ProviderException("Invalid destination path: $destination")
        return withContext(Dispatchers.IO) {
            runCatching { copyRecursively(File(safeSource), File(safeDest)) }.getOrDefault(false)
        }
    }

    override suspend fun mkdir(path: String): Boolean {
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        return withContext(Dispatchers.IO) {
            val f = File(realPath)
            val created = f.mkdirs()
            created || f.isDirectory
        }
    }

    override suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo> {
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        val root = File(realPath)
        if (!root.exists()) return emptyList()
        val clampedDepth = maxDepth.coerceIn(1, MAX_SEARCH_DEPTH)
        return withContext(Dispatchers.IO) {
            val found = mutableListOf<ProviderFileInfo>()
            val completed = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                try {
                    for (f in root.walkTopDown().maxDepth(clampedDepth)) {
                        currentCoroutineContext().ensureActive()
                        if (f == root || !f.name.contains(query, ignoreCase = true)) continue
                        found += entryToProviderInfo(f)
                        if (found.size >= MAX_SEARCH_RESULTS) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
                true
            } != null
            found
        }
    }

    override suspend fun diskUsage(): List<ProviderVolumeInfo> {
        return withContext(Dispatchers.IO) { collectDiskUsage() }
    }

    override suspend fun downloadStream(path: String): InputStream {
        val realPath = safeResolveForRead(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        val f = File(realPath)
        if (!f.isFile) {
            throw FileProvider.ProviderException("文件不存在: $path")
        }
        return f.inputStream()
    }

    override suspend fun uploadStream(path: String, input: InputStream, size: Long) {
        val realPath = safeResolve(path)
            ?: throw FileProvider.ProviderException("Invalid path: $path")
        withContext(Dispatchers.IO) {
            val destFile = File(realPath)
            destFile.parentFile?.mkdirs()
            val tmpFile = File.createTempFile("ufi_provider_upload_", null, destFile.parentFile)
            try {
                tmpFile.outputStream().use { out -> input.copyTo(out) }
                Files.copy(
                    tmpFile.toPath(), destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
                )
            } finally {
                tmpFile.delete()
            }
        }
    }

    // ───────── 公开工具方法（供 FileRoutes 直接调用）─────────

    /**
     * 解析并校验路径：仅允许内部存储与 SD 卡，拒绝任何系统目录。
     */
    fun safeResolve(path: String): String? {
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
            var ancestor = f
            while (ancestor.parentFile != null && !ancestor.exists()) ancestor = ancestor.parentFile!!
            val canonAncestor = runCatching { ancestor.canonicalPath }.getOrNull() ?: return null
            val suffix = base.removePrefix(ancestor.path).replace(Regex("/+"), "/")
            "$canonAncestor$suffix".replace(Regex("/+"), "/")
        }
        return if (isUserStoragePath(resolved)) resolved else null
    }

    /**
     * 读取/下载前的二次校验：跟随符号链接，确认解析后的真实路径仍在用户存储范围内。
     */
    fun safeResolveForRead(path: String): String? {
        val resolved = safeResolve(path) ?: return null
        val real = runCatching { File(resolved).canonicalPath }.getOrNull() ?: return null
        return if (isUserStoragePath(real)) real else null
    }

    fun isSafePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("..")) return false
        val blocked = setOf(';', '|', '`', '$', '"', '\'', '\n', '\r', '\u0000')
        if (path.any { it in blocked }) return false
        return true
    }

    fun isUserStoragePath(path: String): Boolean {
        val userPrefixes = listOf("/sdcard", "/storage", "/mnt/media_rw")
        return userPrefixes.any { path == it || path.startsWith("$it/") }
    }

    fun isDangerousDeletePath(path: String): String? {
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

    fun entryToFileMap(file: File, basePath: String): Map<String, Any> {
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

    fun permsOf(f: File): String {
        val r = if (f.canRead()) 'r' else '-'
        val w = if (f.canWrite()) 'w' else '-'
        val x = if (f.canExecute()) 'x' else '-'
        val d = if (f.isDirectory) 'd' else '-'
        return "$d$r$w$x$r$w$x$r$w$x"
    }

    fun copyRecursively(src: File, dst: File): Boolean {
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

    fun collectDiskUsage(): List<ProviderVolumeInfo> {
        val disks = mutableListOf<ProviderVolumeInfo>()
        runCatching {
            val sf = StatFs(INTERNAL_STORAGE)
            val total = sf.totalBytes
            if (total > 0) {
                val avail = sf.availableBytes
                val used = total - avail
                disks.add(ProviderVolumeInfo("内部存储", INTERNAL_STORAGE, total, used, avail))
            }
        }
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
                    disks.add(ProviderVolumeInfo("SD卡", d.absolutePath, total, total - avail, avail))
                }
            }
        }
        if (disks.isEmpty()) {
            disks.add(ProviderVolumeInfo("内部存储", INTERNAL_STORAGE, 0, 0, 0))
        }
        return disks
    }

    fun diskMap(label: String, mount: String, total: Long, used: Long, avail: Long): Map<String, String> {
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

    fun formatSize(bytes: Long): String {
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

    fun digestFile(f: File, algo: String): String {
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

    fun parentOf(path: String): String? {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) null else p.substring(0, idx).ifEmpty { "/" }
    }

    fun purgeStaleParts(entries: List<File>) {
        val now = System.currentTimeMillis()
        for (f in entries) {
            if (!f.isFile || !f.name.endsWith(UploadSessionStore.PART_SUFFIX)) continue
            if (UploadSessionStore.isStalePart(f, now, UploadSessionStore.DEFAULT_TTL_MS)) {
                runCatching { f.delete() }
            }
        }
    }

    fun freeSpaceOf(dir: String): Long =
        runCatching { StatFs(dir).availableBytes }.getOrDefault(-1L)

    fun uniqueChildName(dir: File, name: String): String {
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

    // ───────── 内部辅助 ─────────

    private fun entryToProviderInfo(file: File): ProviderFileInfo {
        return ProviderFileInfo(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = file.length(),
            lastModified = file.lastModified(),
            permissions = permsOf(file),
            isSymlink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false),
            source = id
        )
    }
}
