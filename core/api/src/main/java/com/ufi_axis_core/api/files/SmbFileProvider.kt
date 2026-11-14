package com.ufi_axis_core.api.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * SMB / CIFS [FileProvider] 实现，基于 smbj（SMB 2/3，不走已被各家系统默认关掉的 SMB1）。
 *
 * 与 [FtpFileProvider] 同构的单连接串行模型：一条 SMB 会话被 [Mutex] 串行化复用。
 * SMB 建连代价比 FTP 还高（TCP + 协议协商 + NTLM 三轮往返），所以 [DiskShare] 必须缓存，
 * 只在探活失败时才重建 —— 每个操作都新开会话会让列目录从几十毫秒退化到秒级。
 *
 * 路径口径：对外仍是正斜杠、相对 [StorageSourceConfig.basePath] 的路径；
 * 对内转成反斜杠、相对**共享根**的 SMB 路径（见 [toSmbPath] / [toExternalPath]）。
 */
class SmbFileProvider(private val config: StorageSourceConfig) : FileProvider {

    override val id: String = "smb:${config.id}"
    override val label: String = config.label
    override val protocol: String = "smb"
    override val readonly: Boolean = false
    override val capabilities: Set<FileProvider.Capability> = setOf(
        FileProvider.Capability.LIST,
        FileProvider.Capability.READ,
        FileProvider.Capability.WRITE,
        FileProvider.Capability.DELETE,
        FileProvider.Capability.RENAME,
        FileProvider.Capability.MOVE,
        FileProvider.Capability.COPY,
        FileProvider.Capability.MKDIR,
        FileProvider.Capability.UPLOAD,
        FileProvider.Capability.DOWNLOAD,
        // FTP / WebDAV 都报不出容量，SMB 有 FSCTL 级别的 share information，可以真报。
        FileProvider.Capability.DISK_USAGE
    )

    companion object {
        private const val MAX_LIST_ENTRIES = 2000
        private const val DEFAULT_PORT = 445
        private const val COPY_BUFFER = 64 * 1024
    }

    private val lock = Mutex()

    @Volatile private var client: SMBClient? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var session: Session? = null
    @Volatile private var share: DiskShare? = null

    // ───────── 连接管理 ─────────

    /**
     * 确保 SMB 会话可用，返回缓存或新建的 [DiskShare]。
     */
    private fun ensureConnected(): DiskShare {
        val existing = share
        // connection.isConnected 是唯一可靠的探活判据 —— DiskShare 自己没有状态查询方法，
        // 而对共享根发一次 list 当心跳意味着每个操作都多一次往返，不值得。
        if (existing != null && connection?.isConnected == true) return existing
        closeQuietly()

        if (config.share.isBlank()) {
            // 共享名是 SMB 的必填项：没有它连不到任何东西，早报错比在 connectShare 里
            // 拿一个 STATUS_BAD_NETWORK_NAME 更容易让人看懂。
            throw FileProvider.ProviderException("SMB 共享名未配置")
        }

        val smbConfig = SmbConfig.builder()
            .withTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
            // soTimeout 给读写留出余量：大文件传输期间单包间隔可能接近协议超时。
            .withSoTimeout(config.timeoutSec.toLong() * 2, TimeUnit.SECONDS)
            .build()

        val newClient = SMBClient(smbConfig)
        try {
            val newConnection = newClient.connect(config.host, if (config.port > 0) config.port else DEFAULT_PORT)
            val auth = AuthenticationContext(
                config.username,
                config.password.toCharArray(),
                config.domain.ifBlank { null }
            )
            val newSession = newConnection.authenticate(auth)
            val newShare = newSession.connectShare(config.share) as? DiskShare
                ?: throw FileProvider.ProviderException("不是磁盘共享：${config.share}")
            client = newClient
            connection = newConnection
            session = newSession
            share = newShare
            return newShare
        } catch (e: Throwable) {
            closeQuietly()
            try { newClient.close() } catch (_: Exception) {}
            if (e is CancellationException) throw e
            throw mapError(e)
        }
    }

    /**
     * 带锁 + IO 线程执行 SMB 操作，并统一把底层异常翻译成 [FileProvider.ProviderException]。
     */
    private suspend fun <T> withShare(block: (DiskShare) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(ensureConnected())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw mapError(e)
            }
        }
    }

    // ───────── FileProvider 实现 ─────────

    override suspend fun list(path: String): ProviderListResult = withShare { share ->
        val smbPath = toSmbPath(path)
        val entries = share.list(smbPath)
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { toProviderFileInfo(it, smbPath) }
            // 与 LocalFileProvider 同一排序：目录优先，再按名字（忽略大小写）。
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
        val external = toExternalPath(smbPath)
        ProviderListResult(
            files = entries.take(MAX_LIST_ENTRIES),
            path = external,
            parent = parentOf(external),
            truncated = entries.size > MAX_LIST_ENTRIES
        )
    }

    override suspend fun info(path: String): ProviderFileInfo = withShare { share ->
        val smbPath = toSmbPath(path)
        val all = share.getFileInformation(smbPath)
        val attrs = all.basicInformation.fileAttributes
        val isDir = hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        val external = toExternalPath(smbPath)
        ProviderFileInfo(
            name = external.substringAfterLast('/').ifBlank { "/" },
            path = external,
            isDirectory = isDir,
            size = if (isDir) 0L else all.standardInformation.endOfFile,
            lastModified = all.basicInformation.lastWriteTime.toEpochMillis(),
            permissions = permsOf(isDir, hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_READONLY)),
            isSymlink = hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT),
            source = id
        )
    }

    override suspend fun read(path: String, encoding: String?): ProviderReadResult = withShare { share ->
        val smbPath = toSmbPath(path)
        val charset = LocalFileProvider.charsetOf(encoding)
        if (share.folderExists(smbPath) || !share.fileExists(smbPath)) {
            return@withShare LocalFileProvider.buildReadResult(
                "[不是文件或不存在]", 0L,
                truncated = true, reason = LocalFileProvider.REASON_NOT_FILE
            )
        }
        val size = share.getFileInformation(smbPath).standardInformation.endOfFile
        if (size > LocalFileProvider.MAX_READ_SIZE) {
            return@withShare LocalFileProvider.buildReadResult(
                "[文件过大: $size bytes，超过 ${LocalFileProvider.MAX_READ_SIZE / 1024}KB 限制，不支持在线查看]",
                size, truncated = true, reason = LocalFileProvider.REASON_TOO_LARGE
            )
        }
        val bytes = openRead(share, smbPath).use { f ->
            f.inputStream.use { ins -> readAtMost(ins, LocalFileProvider.MAX_READ_SIZE) }
        }
        if (bytes.take(4096).any { it == 0.toByte() }) {
            return@withShare LocalFileProvider.buildReadResult(
                "[二进制文件，不支持在线查看]", size,
                truncated = true, reason = LocalFileProvider.REASON_BINARY
            )
        }
        val text = String(bytes, charset)
        LocalFileProvider.buildReadResult(
            text, size, truncated = false,
            encoding = charset.name().lowercase(),
            suspect = charset == Charsets.UTF_8 && text.contains('\uFFFD')
        )
    }

    override suspend fun write(path: String, content: String, encoding: String?) = withShare { share ->
        val smbPath = toSmbPath(path)
        val bytes = content.toByteArray(LocalFileProvider.charsetOf(encoding))
        openWrite(share, smbPath).use { f ->
            f.outputStream.use { out -> out.write(bytes) }
        }
    }

    override suspend fun delete(path: String): Boolean = withShare { share ->
        val smbPath = toSmbPath(path)
        when {
            share.folderExists(smbPath) -> {
                share.rmdir(smbPath, true)
                true
            }
            share.fileExists(smbPath) -> {
                share.rm(smbPath)
                true
            }
            else -> false
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withShare { share ->
        val src = toSmbPath(oldPath)
        val dst = toSmbPath(newPath)
        openForRename(share, src).use { it.rename(dst, true) }
        true
    }

    override suspend fun move(source: String, destination: String): Map<String, Any> = withShare { share ->
        val src = toSmbPath(source)
        val dst = toSmbPath(destination)
        if (!exists(share, src)) {
            return@withShare mapOf<String, Any>("success" to false, "error" to "源文件或目录不存在")
        }
        if (exists(share, dst)) {
            return@withShare mapOf<String, Any>("success" to false, "error" to "目标已存在")
        }
        // SMB 的 rename 本身就支持跨目录（只要在同一共享内），不需要 FTP 那种 copy+delete 回退。
        openForRename(share, src).use { it.rename(dst, true) }
        val destExists = exists(share, dst)
        val sourceGone = !exists(share, src)
        mapOf(
            "success" to (destExists && sourceGone),
            "dest_exists" to destExists,
            "source_deleted" to sourceGone,
            "fallback_copy" to false,
            "integrity_ok" to (destExists && sourceGone)
        )
    }

    override suspend fun copy(source: String, destination: String): Boolean = withShare { share ->
        copyEntry(share, toSmbPath(source), toSmbPath(destination))
        true
    }

    override suspend fun mkdir(path: String): Boolean = withShare { share ->
        val smbPath = toSmbPath(path)
        if (smbPath.isEmpty()) return@withShare true
        mkdirRecursive(share, smbPath)
        true
    }

    override suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo> {
        // SMB 没有服务端搜索；靠客户端递归遍历的话每层目录一次往返，在 NAS 上慢到不可用。
        throw FileProvider.ProviderUnsupportedException("SMB 不支持搜索")
    }

    override suspend fun diskUsage(): List<ProviderVolumeInfo> = withShare { share ->
        val info = share.shareInformation
        val total = info.totalSpace
        // callerFreeSpace 是"当前用户配额内的剩余"，有配额的 NAS 上比 freeSpace 更贴近真实可写量。
        val free = info.callerFreeSpace.takeIf { it in 1..total } ?: info.freeSpace
        listOf(
            ProviderVolumeInfo(
                label = config.share,
                mountPath = "//${config.host}/${config.share}",
                totalBytes = total,
                usedBytes = (total - free).coerceAtLeast(0L),
                availBytes = free
            )
        )
    }

    override suspend fun downloadStream(path: String): InputStream = withShare { share ->
        val smbPath = toSmbPath(path)
        val file = openRead(share, smbPath)
        val stream = try {
            file.inputStream
        } catch (e: Throwable) {
            try { file.close() } catch (_: Exception) {}
            throw e
        }
        // 句柄必须跟着流一起活：调用方（下载 / 播放）关流时才能释放服务端的 open file。
        object : FilterInputStream(stream) {
            override fun close() {
                try { super.close() } catch (_: Exception) {}
                try { file.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun uploadStream(path: String, input: InputStream, size: Long) {
        withShare { share ->
            val smbPath = toSmbPath(path)
            openWrite(share, smbPath).use { f ->
                f.outputStream.use { out -> input.copyTo(out, COPY_BUFFER) }
            }
        }
    }

    // ───────── 资源管理 ─────────

    /**
     * 断开会话并释放资源。顺序必须是 share → session → connection → client，
     * 反过来关会让上层对象在已关闭的传输上发 SMB2 CLOSE / LOGOFF 而抛异常。
     */
    fun close() {
        closeQuietly()
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    private fun closeQuietly() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        share = null
        session = null
        connection = null
    }

    // ───────── 路径转换 ─────────

    /**
     * 外部路径（`/dir/file.txt`，相对 basePath）→ SMB 路径（`dir\file.txt`，相对共享根）。
     *
     * 返回空串表示共享根（smbj 用 `""` 表示根目录）。
     */
    private fun toSmbPath(path: String): String {
        // 必须在拼接前拒 `..`：smbj 不做归一化，会把 `..` 原样发给服务端，
        // 于是客户端能靠它跳出 basePath 访问共享里的其它目录。
        if (path.contains("..")) {
            throw FileProvider.ProviderException("非法路径（含 ..）：$path")
        }
        val base = config.basePath.replace('\\', '/').trim('/')
        val rel = path.replace('\\', '/').trim().trim('/')
        return listOf(base, rel)
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .replace(Regex("/+"), "/")
            .replace('/', '\\')
    }

    /**
     * SMB 路径 → 外部路径，供 [ProviderFileInfo.path] 使用。
     *
     * 客户端拿到的 path 必须能再喂回本 provider，所以要把 basePath 前缀剥掉。
     */
    private fun toExternalPath(smbPath: String): String {
        val forward = smbPath.replace('\\', '/').trim('/')
        val base = config.basePath.replace('\\', '/').trim('/')
        val stripped = if (base.isNotEmpty() && (forward == base || forward.startsWith("$base/"))) {
            forward.removePrefix(base).trimStart('/')
        } else {
            forward
        }
        return "/$stripped"
    }

    private fun parentOf(externalPath: String): String? {
        val p = externalPath.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) null else p.substring(0, idx).ifEmpty { "/" }
    }

    private fun joinSmb(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent\\$name"

    // ───────── 内部工具 ─────────

    private fun openRead(share: DiskShare, smbPath: String): SmbFile = share.openFile(
        smbPath,
        setOf(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )

    private fun openWrite(share: DiskShare, smbPath: String): SmbFile = share.openFile(
        smbPath,
        setOf(AccessMask.GENERIC_WRITE),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OVERWRITE_IF,
        null
    )

    /**
     * 重命名要用 MAXIMUM_ALLOWED 打开句柄；目录必须走 openDirectory，
     * 用 openFile 开目录会被服务端以 STATUS_FILE_IS_A_DIRECTORY 拒绝。
     */
    private fun openForRename(share: DiskShare, smbPath: String): DiskEntry =
        if (share.folderExists(smbPath)) {
            share.openDirectory(
                smbPath,
                setOf(AccessMask.MAXIMUM_ALLOWED),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } else {
            share.openFile(
                smbPath,
                setOf(AccessMask.MAXIMUM_ALLOWED),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }

    private fun exists(share: DiskShare, smbPath: String): Boolean =
        share.fileExists(smbPath) || share.folderExists(smbPath)

    /**
     * SMB 的 mkdir 不递归，必须逐段创建。
     */
    private fun mkdirRecursive(share: DiskShare, smbPath: String) {
        var current = ""
        for (segment in smbPath.split('\\')) {
            if (segment.isEmpty()) continue
            current = joinSmb(current, segment)
            if (!share.folderExists(current)) {
                share.mkdir(current)
            }
        }
    }

    /**
     * 递归复制。SMB 有服务端 copy chunk，但各家 NAS 支持度不一，统一走流式最稳。
     */
    private fun copyEntry(share: DiskShare, src: String, dst: String) {
        if (share.folderExists(src)) {
            mkdirRecursive(share, dst)
            for (entry in share.list(src)) {
                val name = entry.fileName
                if (name == "." || name == "..") continue
                copyEntry(share, joinSmb(src, name), joinSmb(dst, name))
            }
        } else {
            openRead(share, src).use { source ->
                openWrite(share, dst).use { target ->
                    source.inputStream.use { ins ->
                        target.outputStream.use { out -> ins.copyTo(out, COPY_BUFFER) }
                    }
                }
            }
        }
    }

    private fun toProviderFileInfo(info: FileIdBothDirectoryInformation, parentSmbPath: String): ProviderFileInfo {
        val attrs = info.fileAttributes
        val isDir = hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        return ProviderFileInfo(
            name = info.fileName,
            path = toExternalPath(joinSmb(parentSmbPath, info.fileName)),
            isDirectory = isDir,
            size = if (isDir) 0L else info.endOfFile,
            lastModified = info.lastWriteTime.toEpochMillis(),
            permissions = permsOf(isDir, hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_READONLY)),
            isSymlink = hasAttr(attrs, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT),
            source = id
        )
    }

    private fun hasAttr(attrs: Long, attr: FileAttributes): Boolean = (attrs and attr.value) != 0L

    /**
     * SMB 只有「只读」这一位，没有 POSIX 权限位，拼一个形似 rwx 的串给 UI 用。
     */
    private fun permsOf(isDir: Boolean, readonly: Boolean): String {
        val head = if (isDir) 'd' else '-'
        val trio = if (readonly) "r-x" else "rwx"
        return "$head$trio$trio$trio"
    }

    /**
     * 最多读取 [maxBytes] 字节。与 FtpFileProvider.read() 同一写法 ——
     * InputStream.readNBytes 要 API 33，minSdk 31 用不了。
     */
    private fun readAtMost(ins: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var total = 0
        while (total < buf.size) {
            val n = ins.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return buf.copyOf(total)
    }

    /**
     * 统一错误映射。所有 catch 都走这里，避免三个 provider 各写一套 message 口径。
     */
    private fun mapError(e: Throwable): FileProvider.ProviderException = when (e) {
        is FileProvider.ProviderException -> e
        is SMBApiException -> when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED ->
                FileProvider.ProviderAuthException("SMB 认证失败（${e.status}）")
            // 最常见的一种配错：共享名必须出现在消息里，否则用户只会看到一串 NT 状态码。
            NtStatus.STATUS_BAD_NETWORK_NAME ->
                FileProvider.ProviderException("共享名不存在：${config.share}")
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                FileProvider.ProviderException("路径不存在")
            NtStatus.STATUS_SHARING_VIOLATION ->
                FileProvider.ProviderException("文件被占用")
            else -> FileProvider.ProviderException("SMB 错误：${e.status}", e)
        }
        is SocketTimeoutException ->
            FileProvider.ProviderTimeoutException("SMB 连接超时：${config.host}:${if (config.port > 0) config.port else DEFAULT_PORT}")
        is SocketException ->
            FileProvider.ProviderTimeoutException("SMB 连接失败：${e.message}")
        is IOException -> {
            val msg = e.message ?: ""
            if (msg.contains("connect", ignoreCase = true) || msg.contains("timeout", ignoreCase = true)) {
                FileProvider.ProviderTimeoutException("SMB 连接失败：$msg")
            } else {
                FileProvider.ProviderException("SMB IO 错误：$msg", e)
            }
        }
        else -> FileProvider.ProviderException("SMB 操作失败：${e.message}", e)
    }
}
