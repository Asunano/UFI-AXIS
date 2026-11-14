package com.ufi_axis_core.api.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * FTP/FTPS [FileProvider] 实现，基于 Apache Commons Net。
 *
 * 单连接串行模型：FTPClient 不支持并发命令，所有操作通过 [Mutex] 串行化。
 * 连接管理采用懒连接 + NOOP 心跳检测：首次操作时建连，之后每次操作前检查连接活性。
 */
class FtpFileProvider(private val config: StorageSourceConfig) : FileProvider {

    override val id: String = "ftp:${config.id}"
    override val label: String = config.label
    override val protocol: String = "ftp"
    override val readonly: Boolean = false
    override val capabilities: Set<FileProvider.Capability> = setOf(
        FileProvider.Capability.LIST,
        FileProvider.Capability.READ,
        FileProvider.Capability.WRITE,
        FileProvider.Capability.DELETE,
        FileProvider.Capability.RENAME,
        FileProvider.Capability.MKDIR,
        FileProvider.Capability.UPLOAD,
        FileProvider.Capability.DOWNLOAD
    )

    companion object {
        private const val MAX_LIST_ENTRIES = 2000
        private const val MAX_READ_BYTES = 512L * 1024 // 512 KB
    }

    private val lock = Mutex()
    private var ftpClient: FTPClient? = null

    /**
     * 确保 FTP 连接可用。如未连接或连接已断开，重新建立连接。
     */
    private fun ensureConnected(): FTPClient {
        val existing = ftpClient
        if (existing != null && existing.isConnected) {
            // 尝试发送 NOOP 检测连接活性
            try {
                if (existing.sendNoOp()) return existing
            } catch (_: Exception) {
                // 连接已断开，重新连接
            }
            try { existing.disconnect() } catch (_: Exception) {}
        }

        val client = if (config.useTls) {
            FTPSClient("TLS", false).apply {
                // 隐式 TLS 不在此处理；显式 TLS 在 connect 后调用 execPBROT
            }
        } else {
            FTPClient()
        }

        client.controlEncoding = config.encoding
        client.connectTimeout = config.timeoutSec * 1000
        client.defaultTimeout = config.timeoutSec * 1000

        try {
            client.connect(config.host, config.port)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                throw FileProvider.ProviderException("FTP connection refused (reply code: $reply)")
            }
        } catch (e: java.net.ConnectException) {
            throw FileProvider.ProviderTimeoutException("FTP connection refused: ${config.host}:${config.port}")
        } catch (e: java.net.SocketTimeoutException) {
            throw FileProvider.ProviderTimeoutException("FTP connection timed out: ${config.host}:${config.port}")
        } catch (e: FileProvider.ProviderException) {
            throw e
        } catch (e: Exception) {
            throw FileProvider.ProviderException("FTP connection failed: ${e.message}", e)
        }

        // TLS：保护数据通道
        if (client is FTPSClient) {
            try {
                client.execPBSZ(0)
                client.execPROT("P")
            } catch (e: Exception) {
                throw FileProvider.ProviderException("FTP TLS data channel setup failed: ${e.message}", e)
            }
        }

        // 登录
        val loginOk = try {
            client.login(config.username, config.password)
        } catch (e: Exception) {
            throw FileProvider.ProviderAuthException("FTP login failed: ${e.message}")
        }
        if (!loginOk) {
            val replyCode = client.replyCode
            client.disconnect()
            if (replyCode == 530 || replyCode == 531) {
                throw FileProvider.ProviderAuthException("FTP authentication failed (reply code: $replyCode)")
            }
            throw FileProvider.ProviderException("FTP login failed (reply code: $replyCode)")
        }

        // 设置传输模式
        client.setFileType(FTP.BINARY_FILE_TYPE)

        if (config.passive) {
            client.enterLocalPassiveMode()
        }

        // 切换到 basePath
        if (config.basePath.isNotBlank() && config.basePath != "/") {
            val changed = client.changeWorkingDirectory(config.basePath)
            if (!changed) {
                throw FileProvider.ProviderException("FTP basePath not found: ${config.basePath}")
            }
        }

        client.soTimeout = config.timeoutSec * 1000
        client.dataTimeout = java.time.Duration.ofSeconds(config.timeoutSec.toLong())

        ftpClient = client
        return client
    }

    /**
     * 带锁执行 FTP 操作。
     */
    private suspend fun <T> withClient(block: suspend (FTPClient) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val client = ensureConnected()
            block(client)
        }
    }

    // ───────── FileProvider 实现 ─────────

    override suspend fun list(path: String): ProviderListResult = withClient { client ->
        val targetPath = normalizePath(path)
        // 尝试 MLSD（结构化列表），失败则退化到 LIST
        val ftpFiles = try {
            val mlsd = client.mlistDir(targetPath)
            if (mlsd != null && mlsd.isNotEmpty()) mlsd else client.listFiles(targetPath)
        } catch (_: Exception) {
            client.listFiles(targetPath)
        }
        val files = (ftpFiles ?: emptyArray())
            .filter { it.name != "." && it.name != ".." }
            .take(MAX_LIST_ENTRIES)
            .map { toProviderFileInfo(it, targetPath) }
        val parent = targetPath.removeSuffix("/").substringBeforeLast("/", "").ifBlank { null }
        ProviderListResult(
            files = files,
            path = targetPath,
            parent = parent,
            truncated = (ftpFiles?.size ?: 0) - 2 > MAX_LIST_ENTRIES
        )
    }

    override suspend fun info(path: String): ProviderFileInfo = withClient { client ->
        val targetPath = normalizePath(path)
        // 尝试 MLST
        val ftpFile = try {
            client.mlistFile(targetPath)
        } catch (_: Exception) {
            null
        }
        if (ftpFile != null) {
            return@withClient toProviderFileInfo(ftpFile, targetPath.substringBeforeLast("/", "/"))
        }
        // 退化：列父目录并查找
        val parentDir = targetPath.substringBeforeLast("/", "/")
        val name = targetPath.substringAfterLast("/")
        val files = client.listFiles(parentDir) ?: emptyArray()
        val found = files.find { it.name == name }
            ?: throw FileProvider.ProviderException("FTP resource not found: $targetPath")
        toProviderFileInfo(found, parentDir)
    }

    override suspend fun read(path: String, encoding: String?): ProviderReadResult = withClient { client ->
        val targetPath = normalizePath(path)
        val stream = client.retrieveFileStream(targetPath)
            ?: throw FileProvider.ProviderException("FTP read failed: ${client.replyString?.trim()}")
        try {
            val buf = ByteArray(MAX_READ_BYTES.toInt())
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            val bytes = buf.copyOf(total)
            val charset = try {
                if (encoding.isNullOrBlank()) Charsets.UTF_8 else java.nio.charset.Charset.forName(encoding)
            } catch (_: Exception) {
                Charsets.UTF_8
            }
            stream.close()
            if (!client.completePendingCommand()) {
                throw FileProvider.ProviderException("FTP completePendingCommand failed after read")
            }
            ProviderReadResult(
                content = String(bytes, charset),
                encoding = charset.name(),
                size = bytes.size.toLong(),
                truncated = total >= MAX_READ_BYTES.toInt()
            )
        } catch (e: FileProvider.ProviderException) {
            throw e
        } catch (e: Exception) {
            // 确保流关闭
            try { stream.close() } catch (_: Exception) {}
            try { client.completePendingCommand() } catch (_: Exception) {}
            throw FileProvider.ProviderException("FTP read error: ${e.message}", e)
        }
    }

    override suspend fun write(path: String, content: String, encoding: String?) = withClient { client ->
        val targetPath = normalizePath(path)
        val charset = try {
            if (encoding.isNullOrBlank()) Charsets.UTF_8 else java.nio.charset.Charset.forName(encoding)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val bytes = content.toByteArray(charset)
        val ok = client.storeFile(targetPath, ByteArrayInputStream(bytes))
        if (!ok) {
            throw FileProvider.ProviderException("FTP write failed: ${client.replyString?.trim()}")
        }
    }

    override suspend fun delete(path: String): Boolean = withClient { client ->
        val targetPath = normalizePath(path)
        // 先尝试删文件
        val fileDeleted = client.deleteFile(targetPath)
        if (fileDeleted) return@withClient true
        // 再尝试删目录（递归）
        deleteDirectoryRecursive(client, targetPath)
        true
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withClient { client ->
        val ok = client.rename(normalizePath(oldPath), normalizePath(newPath))
        if (!ok) {
            throw FileProvider.ProviderException("FTP rename failed: ${client.replyString?.trim()}")
        }
        true
    }

    override suspend fun move(source: String, destination: String): Map<String, Any> {
        throw FileProvider.ProviderUnsupportedException("FTP does not support cross-directory move")
    }

    override suspend fun copy(source: String, destination: String): Boolean {
        throw FileProvider.ProviderUnsupportedException("FTP does not support server-side copy")
    }

    override suspend fun mkdir(path: String): Boolean = withClient { client ->
        val targetPath = normalizePath(path)
        val ok = client.makeDirectory(targetPath)
        if (!ok) {
            throw FileProvider.ProviderException("FTP mkdir failed: ${client.replyString?.trim()}")
        }
        true
    }

    override suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo> {
        throw FileProvider.ProviderUnsupportedException("FTP search not supported")
    }

    override suspend fun diskUsage(): List<ProviderVolumeInfo> {
        throw FileProvider.ProviderUnsupportedException("FTP disk usage not supported")
    }

    override suspend fun downloadStream(path: String): InputStream = withClient { client ->
        val targetPath = normalizePath(path)
        val stream = client.retrieveFileStream(targetPath)
            ?: throw FileProvider.ProviderException("FTP download failed: ${client.replyString?.trim()}")
        // 包装流：关闭时自动调用 completePendingCommand
        object : FilterInputStream(stream) {
            private var completed = false
            override fun close() {
                try { super.close() } catch (_: Exception) {}
                if (!completed) {
                    completed = true
                    try { client.completePendingCommand() } catch (_: Exception) {}
                }
            }
        }
    }

    override suspend fun uploadStream(path: String, input: InputStream, size: Long) = withClient { client ->
        val targetPath = normalizePath(path)
        val ok = client.storeFile(targetPath, input)
        if (!ok) {
            throw FileProvider.ProviderException("FTP upload failed: ${client.replyString?.trim()}")
        }
    }

    // ───────── 资源管理 ─────────

    /**
     * 断开 FTP 连接并释放资源。
     */
    fun close() {
        try {
            val client = ftpClient
            if (client != null && client.isConnected) {
                client.logout()
                client.disconnect()
            }
        } catch (_: Exception) {
            // 静默关闭
        }
        ftpClient = null
    }

    // ───────── 内部工具 ─────────

    /**
     * 递归删除 FTP 目录。
     */
    private fun deleteDirectoryRecursive(client: FTPClient, path: String) {
        val files = client.listFiles(path) ?: return
        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            val fullPath = "$path/${f.name}"
            if (f.isDirectory) {
                deleteDirectoryRecursive(client, fullPath)
            } else {
                val ok = client.deleteFile(fullPath)
                if (!ok) throw FileProvider.ProviderException("FTP delete file failed: $fullPath")
            }
        }
        val ok = client.removeDirectory(path)
        if (!ok) throw FileProvider.ProviderException("FTP remove directory failed: $path")
    }

    /**
     * FTPFile 转 ProviderFileInfo。
     */
    private fun toProviderFileInfo(ftpFile: FTPFile, parentPath: String): ProviderFileInfo {
        val parent = parentPath.removeSuffix("/")
        val filePath = if (parent == "/" || parent.isEmpty()) {
            "/${ftpFile.name}"
        } else {
            "$parent/${ftpFile.name}"
        }
        return ProviderFileInfo(
            name = ftpFile.name,
            path = filePath,
            isDirectory = ftpFile.isDirectory,
            size = ftpFile.size,
            lastModified = ftpFile.timestamp?.timeInMillis ?: 0L,
            permissions = buildPermissionsString(ftpFile),
            isSymlink = ftpFile.isSymbolicLink,
            source = id
        )
    }

    /**
     * 从 FTPFile 构建 rwx 权限字符串。
     */
    private fun buildPermissionsString(f: FTPFile): String {
        val sb = StringBuilder()
        sb.append(if (f.isDirectory) 'd' else if (f.isSymbolicLink) 'l' else '-')
        // user
        sb.append(if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION)) 'r' else '-')
        sb.append(if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION)) 'w' else '-')
        sb.append(if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION)) 'x' else '-')
        // group
        sb.append(if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION)) 'r' else '-')
        sb.append(if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION)) 'w' else '-')
        sb.append(if (f.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION)) 'x' else '-')
        // world
        sb.append(if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION)) 'r' else '-')
        sb.append(if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION)) 'w' else '-')
        sb.append(if (f.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)) 'x' else '-')
        return sb.toString()
    }

    /**
     * 路径归一化。
     */
    private fun normalizePath(path: String): String {
        val p = path.trim()
        return if (p.isEmpty() || p == "/") "/" else p
    }
}
