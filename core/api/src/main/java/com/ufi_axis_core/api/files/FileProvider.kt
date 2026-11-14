package com.ufi_axis_core.api.files

import java.io.InputStream

/**
 * 存储提供者抽象 — 所有文件操作的统一接口。
 *
 * 本地设备存储由 [LocalFileProvider] 实现；远程存储（FTP / WebDAV 等）
 * 后续各自实现此接口并注册到 [FileProviderRegistry] 即可接入现有路由。
 *
 * ## 设计约束
 * - 所有方法均为 `suspend`，实现方可在内部切到 `Dispatchers.IO`。
 * - 不抛受检异常；失败通过 [ProviderException] 及其子类表达。
 * - [capabilities] 声明当前实例支持的操作子集，调用方可据此在 UI 层灰掉不可用按钮。
 */
interface FileProvider {

    /** 唯一标识，用于路径前缀解析。本地固定为 `"local"`。 */
    val id: String

    /** 用户可见的显示名。 */
    val label: String

    /** 协议标识（`"local"` / `"ftp"` / `"webdav"` 等）。 */
    val protocol: String

    /** 是否只读。 */
    val readonly: Boolean

    /** 当前实例支持的能力集合。 */
    val capabilities: Set<Capability>

    // ───────── 能力枚举 ─────────

    enum class Capability {
        LIST, READ, WRITE, DELETE, RENAME, MOVE, COPY, MKDIR,
        SEARCH, UPLOAD, DOWNLOAD, STREAM,
        EXTRACT, COMPRESS, CHECKSUM, DISK_USAGE
    }

    // ───────── 文件操作 ─────────

    /** 列出目录内容。 */
    suspend fun list(path: String): ProviderListResult

    /** 获取单个文件/目录的元信息。 */
    suspend fun info(path: String): ProviderFileInfo

    /** 读取文本文件内容。[encoding] 为客户端请求的编码名，可为 null（默认 UTF-8）。 */
    suspend fun read(path: String, encoding: String? = null): ProviderReadResult

    /** 写入文本文件。 */
    suspend fun write(path: String, content: String, encoding: String? = null)

    /** 删除文件或目录（递归）。返回是否成功。 */
    suspend fun delete(path: String): Boolean

    /** 重命名（或原地移动）。 */
    suspend fun rename(oldPath: String, newPath: String): Boolean

    /** 移动。返回结果 map 与现有 API 一致。 */
    suspend fun move(source: String, destination: String): Map<String, Any>

    /** 复制。 */
    suspend fun copy(source: String, destination: String): Boolean

    /** 创建目录（含中间目录）。 */
    suspend fun mkdir(path: String): Boolean

    /** 搜索。[query] 为文件名关键词，[maxDepth] 为最大递归深度。 */
    suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo>

    /** 磁盘/卷使用量。 */
    suspend fun diskUsage(): List<ProviderVolumeInfo>

    /** 返回可直接读取的输入流（用于下载 / 流式播放）。 */
    suspend fun downloadStream(path: String): InputStream

    /**
     * 从 [startOffset] 字节处开始读 —— **播放器 seek 与断点续传的唯一入口**。
     *
     * 默认实现是"整文件流 + skip"：语义正确，但对远端协议意味着从头重新传一遍，
     * seek 到片尾要等很久。能用协议原语做偏移读的实现**必须覆盖**它
     * （HTTP/WebDAV/S3 用 `Range` 头，FTP 用 REST，SMB 用 seek）。
     *
     * 为什么需要它：`/api/files/stream` 以前对远端源直接忽略客户端的 `Range` 头、
     * 每次都发一个不带 Range 的整文件 GET。后果有两条：① 播放器无法 seek；
     * ② 相当多的网盘 / CDN 对"不带 Range 的大文件 GET"直接回 502 —— 于是
     * 视频根本放不出来（ExoPlayer 报 2004 ERROR_CODE_IO_BAD_HTTP_STATUS）。
     */
    suspend fun downloadStream(path: String, startOffset: Long): InputStream {
        val stream = downloadStream(path)
        if (startOffset <= 0L) return stream
        var remaining = startOffset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // skip 返回 0 不代表到了流尾（可能只是这次没读到），再试一次单字节读确认
                if (stream.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        return stream
    }

    /** 将输入流写入指定路径（用于上传）。 */
    suspend fun uploadStream(path: String, input: InputStream, size: Long)

    // ───────── 异常体系 ─────────

    open class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class ProviderAuthException(message: String = "认证失败", cause: Throwable? = null) : ProviderException(message, cause)
    class ProviderTimeoutException(message: String = "操作超时", cause: Throwable? = null) : ProviderException(message, cause)
    class ProviderUnsupportedException(message: String = "操作不支持", cause: Throwable? = null) : ProviderException(message, cause)
}
