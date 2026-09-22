package com.ufi_axis_core.api.files

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S3 兼容对象存储的 [FileProvider] 实现，基于 OkHttp + 手写 SigV4（见 [S3Signer]）。
 *
 * **刻意不引入 AWS SDK**：aws-sdk-android 的 s3 模块加上传递依赖 ~15MB，而本 provider
 * 只用到 ListObjectsV2 / HEAD / GET / PUT / DELETE / DeleteObjects 六个操作，
 * 签名算法本身是几十行 HMAC 拼接，自己写比背一个 SDK 划算得多。
 *
 * 与 FTP / SMB 的单连接串行模型不同，S3 是无状态 HTTP，不需要 [kotlinx.coroutines.sync.Mutex]：
 * 每个请求自带签名，并发打过去没有任何共享状态。
 *
 * 路径口径：对外是正斜杠、相对 [StorageSourceConfig.basePath] 的路径；
 * 对内是 S3 key（无前导斜杠，basePath 作为 key 前缀）。见 [toKey] / [toExternalPath]。
 */
class S3FileProvider(private val config: StorageSourceConfig) : FileProvider {

    override val id: String = "s3:${config.id}"
    override val label: String = config.label
    override val protocol: String = "s3"
    override val readonly: Boolean = false
    override val capabilities: Set<FileProvider.Capability> = setOf(
        FileProvider.Capability.LIST,
        FileProvider.Capability.READ,
        FileProvider.Capability.WRITE,
        FileProvider.Capability.DELETE,
        FileProvider.Capability.RENAME,
        FileProvider.Capability.COPY,
        FileProvider.Capability.MKDIR,
        FileProvider.Capability.UPLOAD,
        FileProvider.Capability.DOWNLOAD
        // 以下能力**故意不给**，理由写在这里而不是散在各方法里：
        // - MOVE：目录移动只能逐 key copy+delete，中途失败会留下半个目录。不提供比提供后搞坏数据好。
        // - SEARCH：ListObjectsV2 只能按前缀过滤，没有子串匹配。"搜索"等于把整个 bucket 列一遍。
        // - DISK_USAGE：对象存储的 S3 API 里没有"容量"这个概念，报不出来。
        // - STREAM / EXTRACT / COMPRESS / CHECKSUM：都需要在设备本地跑计算或服务端配合，这里都没有。
    )

    companion object {
        private const val TAG = "S3FileProvider"

        /** 单页条目数，S3 的 max-keys 上限就是 1000。 */
        private const val PAGE_SIZE = 1000

        /** 列目录最多翻 2 页（2000 条），与其它三个 provider 的 MAX_LIST_ENTRIES 对齐。 */
        private const val MAX_LIST_PAGES = 2

        /** DeleteObjects 单批上限，S3 协议硬限制。 */
        private const val DELETE_BATCH = 1000

        /**
         * 递归删除 / 复制的对象数上限。
         * 10 万个对象的前缀要跑好几分钟，而这里没有任何进度上报通道，用户只会看到界面卡死。
         */
        private const val MAX_RECURSIVE_OBJECTS = 10_000

        /** 单次 PUT 的服务端上限（S3 协议规定 5GB，再大必须分片上传）。 */
        private const val MAX_SINGLE_PUT_BYTES = 5L * 1024 * 1024 * 1024
    }

    // ───────── endpoint / URL 构造 ─────────

    private val scheme: String = if (config.useTls) "https" else "http"

    /**
     * 服务端主机名。用户粘贴 endpoint 时十有八九会把 `https://` 一起带上，
     * 直接塞进 HttpUrl 会变成非法 host，所以这里剥干净。
     */
    private val host: String = when {
        config.endpoint.isNotBlank() -> config.endpoint
            .removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
        else -> "s3.${config.region}.amazonaws.com"
    }

    private val bucket: String = config.bucket.trim()

    /**
     * basePath 归一成 key 前缀：空串或 `something/`（有尾斜杠、无前导斜杠）。
     * S3 没有目录，"远端根路径"实质就是一段 key 前缀。
     */
    private val keyPrefix: String = config.basePath
        .replace('\\', '/')
        .trim()
        .trim('/')
        .let { if (it.isEmpty()) "" else "$it/" }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
        .build()

    // ───────── FileProvider 实现 ─────────

    override suspend fun list(path: String): ProviderListResult = withContext(Dispatchers.IO) {
        val prefix = dirPrefixOf(toKey(path))
        val entries = mutableListOf<ProviderFileInfo>()
        var token: String? = null
        var pages = 0
        var moreLeft = false

        while (true) {
            val page = listObjects(prefix, delimiter = "/", token = token)
            for (p in page.prefixes) {
                entries += dirInfo(p)
            }
            for (o in page.objects) {
                // mkdir 和 AWS / MinIO 控制台都会为"目录"建一个 0 字节的 `photos/` 占位对象。
                // 它和 CommonPrefixes 里的 `photos/` 是同一个东西，不过滤掉的话目录旁边
                // 会多出一个同名空文件。
                if (o.key == prefix) continue
                entries += fileInfo(o)
            }
            pages++
            token = page.nextToken
            if (!page.truncated || token == null) break
            if (pages >= MAX_LIST_PAGES) {
                moreLeft = true
                break
            }
        }

        // 与 LocalFileProvider 同一排序：目录优先，再按名字（忽略大小写）。
        val sorted = entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val external = toExternalPath(prefix)
        ProviderListResult(
            files = sorted,
            path = external,
            parent = parentOf(external),
            truncated = moreLeft
        )
    }

    override suspend fun info(path: String): ProviderFileInfo = withContext(Dispatchers.IO) {
        val key = toKey(path)
        val external = toExternalPath(key)
        if (key.isEmpty()) {
            // bucket 根（或 basePath 根）：S3 没有对应对象，造一条目录信息出来
            return@withContext ProviderFileInfo(
                name = "/", path = "/", isDirectory = true,
                size = 0L, lastModified = 0L,
                permissions = PERM_DIR, isSymlink = false, source = id
            )
        }
        val head = headObject(key)
        if (head != null) {
            return@withContext ProviderFileInfo(
                name = external.trimEnd('/').substringAfterLast('/').ifBlank { "/" },
                path = external,
                isDirectory = false,
                size = head.size,
                lastModified = head.lastModified,
                permissions = PERM_FILE,
                isSymlink = false,
                source = id
            )
        }
        // HEAD 404 不代表不存在：`photos` 可能只是一堆 `photos/xxx` 的公共前缀，
        // 没有对应的占位对象。用一次 max-keys=1 的列举确认它是不是"目录"。
        val probe = listObjects(dirPrefixOf(key), delimiter = "/", token = null, maxKeys = 1)
        if (probe.objects.isNotEmpty() || probe.prefixes.isNotEmpty()) {
            return@withContext dirInfo(dirPrefixOf(key))
        }
        throw FileProvider.ProviderException("对象不存在：$path")
    }

    override suspend fun read(path: String, encoding: String?): ProviderReadResult = withContext(Dispatchers.IO) {
        val key = toKey(path)
        val charset = LocalFileProvider.charsetOf(encoding)

        // 先 HEAD 再 GET：多一次往返，但换来"文件过大时一个字节都不下载"。
        // 对象存储按流量计费，为了显示一句"文件过大"白拉 512KB 不合适。
        val head = if (key.isEmpty() || key.endsWith("/")) null else headObject(key)
        if (head == null) {
            return@withContext LocalFileProvider.buildReadResult(
                "[不是文件或不存在]", 0L,
                truncated = true, reason = LocalFileProvider.REASON_NOT_FILE
            )
        }
        if (head.size > LocalFileProvider.MAX_READ_SIZE) {
            return@withContext LocalFileProvider.buildReadResult(
                "[文件过大: ${head.size} bytes，超过 ${LocalFileProvider.MAX_READ_SIZE / 1024}KB 限制，不支持在线查看]",
                head.size, truncated = true, reason = LocalFileProvider.REASON_TOO_LARGE
            )
        }

        val request = Request.Builder()
            .url(objectUrl(key))
            .get()
            .header("Range", "bytes=0-${LocalFileProvider.MAX_READ_SIZE - 1}")
            .build()
        val bytes = execute(request, S3Signer.EMPTY_SHA256).use { response ->
            response.body?.byteStream()?.use { readAtMost(it, LocalFileProvider.MAX_READ_SIZE) }
                ?: ByteArray(0)
        }

        if (bytes.take(4096).any { it == 0.toByte() }) {
            return@withContext LocalFileProvider.buildReadResult(
                "[二进制文件，不支持在线查看]", head.size,
                truncated = true, reason = LocalFileProvider.REASON_BINARY
            )
        }
        val text = String(bytes, charset)
        LocalFileProvider.buildReadResult(
            text, head.size, truncated = false,
            encoding = charset.name().lowercase(),
            suspect = charset == Charsets.UTF_8 && text.contains('\uFFFD')
        )
    }

    override suspend fun write(path: String, content: String, encoding: String?) = withContext(Dispatchers.IO) {
        val key = requireObjectKey(path)
        val bytes = content.toByteArray(LocalFileProvider.charsetOf(encoding))
        val request = Request.Builder()
            .url(objectUrl(key))
            .put(bytes.toRequestBody(OCTET_STREAM))
            .build()
        // 正文很小，老老实实算真实 SHA-256（比 UNSIGNED-PAYLOAD 多一层完整性保护）。
        execute(request, S3Signer.sha256Hex(bytes)).close()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val key = requireObjectKey(path)

        // 单个对象：HEAD 命中且 key 不以 `/` 结尾，就是普通文件
        if (!key.endsWith("/") && headObject(key) != null) {
            deleteObject(key)
            return@withContext true
        }

        // "目录"：把前缀下所有 key 收齐后批量删（占位对象 `photos/` 本身也在这批里）
        val prefix = dirPrefixOf(key)
        val keys = collectKeys(prefix)
        if (keys.isEmpty()) {
            // 空目录只剩占位对象的情况（listObjects 已经覆盖，这里是兜底）
            if (headObject(prefix) != null) {
                deleteObject(prefix)
                return@withContext true
            }
            return@withContext false
        }
        deleteBatch(keys)
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val oldKey = requireObjectKey(oldPath)
        val newKey = requireObjectKey(newPath)

        if (isDirectoryKey(oldKey)) {
            // 逐 key copy+delete 跑到一半失败会留下半个改了名的树，而 S3 没有事务可以回滚。
            throw FileProvider.ProviderUnsupportedException("S3 不支持目录重命名")
        }

        copyObject(oldKey, newKey)
        // copy 成了但 delete 失败时必须如实返回 false：两份副本同时存在比"改名失败、用户重试"糟得多。
        return@withContext try {
            deleteObject(oldKey)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "rename: 复制成功但删除源对象失败 key=$oldKey: ${e.message}")
            false
        }
    }

    override suspend fun move(source: String, destination: String): Map<String, Any> {
        // 不在 capabilities 里，但接口要求这个方法存在。
        throw FileProvider.ProviderUnsupportedException("S3 不支持移动，请用复制后删除")
    }

    override suspend fun copy(source: String, destination: String): Boolean = withContext(Dispatchers.IO) {
        val srcKey = requireObjectKey(source)
        val dstKey = requireObjectKey(destination)

        if (!isDirectoryKey(srcKey)) {
            copyObject(srcKey, dstKey)
            return@withContext true
        }

        val srcPrefix = dirPrefixOf(srcKey)
        val dstPrefix = dirPrefixOf(dstKey)
        val keys = collectKeys(srcPrefix)
        for (k in keys) {
            val suffix = k.removePrefix(srcPrefix)
            copyObject(k, dstPrefix + suffix)
        }
        true
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        val key = requireObjectKey(path)
        val placeholder = dirPrefixOf(key)
        // 建一个 0 字节、key 以 `/` 结尾的占位对象 —— AWS / MinIO 控制台就是这么做的，
        // 这样用户新建的"文件夹"在控制台和本 app 里都看得见（否则空目录压根不存在）。
        val request = Request.Builder()
            .url(objectUrl(placeholder))
            .put(ByteArray(0).toRequestBody(OCTET_STREAM))
            .build()
        execute(request, S3Signer.EMPTY_SHA256).close()
        true
    }

    override suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo> {
        throw FileProvider.ProviderUnsupportedException("S3 不支持搜索")
    }

    override suspend fun diskUsage(): List<ProviderVolumeInfo> {
        throw FileProvider.ProviderUnsupportedException("S3 不报容量")
    }

    override suspend fun downloadStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val key = requireObjectKey(path)
        val request = Request.Builder().url(objectUrl(key)).get().build()
        val response = execute(request, S3Signer.EMPTY_SHA256)
        val stream = response.body?.byteStream() ?: run {
            response.close()
            throw FileProvider.ProviderException("响应体为空：$path")
        }
        // Response 必须跟着流一起活：调用方关流时才能把连接还给连接池，否则连接泄漏。
        object : FilterInputStream(stream) {
            override fun close() {
                try { super.close() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun uploadStream(path: String, input: InputStream, size: Long) {
        withContext(Dispatchers.IO) {
            val key = requireObjectKey(path)
            if (size > MAX_SINGLE_PUT_BYTES) {
                throw FileProvider.ProviderException("单文件上传上限 5GB（超过需分片上传，暂未支持）")
            }
            if (!config.useTls) {
                // UNSIGNED-PAYLOAD 意味着请求体不参与签名；再叠加明文 http，
                // 路径上任何人都能改字节而两端都发现不了。
                AppLogger.w(
                    TAG,
                    "上传走 http + UNSIGNED-PAYLOAD，请求体没有任何完整性保护：$host"
                )
            }
            val body = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    input.use { stream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                }
            }
            val request = Request.Builder().url(objectUrl(key)).put(body).build()
            // 不预读整个流算 hash：大文件要么全缓存到内存要么落盘临时文件，两者都不可接受。
            execute(request, S3Signer.UNSIGNED_PAYLOAD).close()
        }
    }

    // ───────── 资源管理 ─────────

    /**
     * 释放 OkHttp 资源。调用后不应再使用此 provider。
     */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ───────── 路径 / key 转换 ─────────

    /**
     * 外部路径（`/photos/a.jpg`，相对 basePath）→ S3 key（`prefix/photos/a.jpg`，无前导斜杠）。
     */
    private fun toKey(path: String): String {
        // 必须在拼接前拒 `..`：S3 不做路径归一化，`prefix/../other` 会被当成字面 key，
        // 但 basePath 的隔离意图就此失效（而且不同实现对 `..` 的处理并不一致）。
        if (path.contains("..")) {
            throw FileProvider.ProviderException("非法路径（含 ..）：$path")
        }
        val rel = path.replace('\\', '/').trim().trimStart('/')
        // 前导斜杠必须剥掉：S3 允许 key 以 `/` 开头，但那会凭空造出一个名字为空的目录层级。
        return (keyPrefix + rel).replace(Regex("/+"), "/").trimStart('/')
    }

    /** S3 key → 外部路径。 */
    private fun toExternalPath(key: String): String {
        val stripped = if (keyPrefix.isNotEmpty() && key.startsWith(keyPrefix)) {
            key.removePrefix(keyPrefix)
        } else {
            key
        }
        return "/" + stripped.trim('/')
    }

    /** 要求路径能落到一个具体 key 上（不能是 bucket / basePath 根）。 */
    private fun requireObjectKey(path: String): String {
        val key = toKey(path)
        if (key.isEmpty()) throw FileProvider.ProviderException("非法路径（指向存储根）：$path")
        return key
    }

    /** key → 目录前缀（空串保持空串，其余补一个尾斜杠）。 */
    private fun dirPrefixOf(key: String): String {
        val trimmed = key.trimEnd('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    private fun parentOf(externalPath: String): String? {
        val p = externalPath.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) null else p.substring(0, idx).ifEmpty { "/" }
    }

    /**
     * 判断一个 key 是不是"目录"：key 本身带尾斜杠，或该前缀下至少有一个对象。
     */
    private fun isDirectoryKey(key: String): Boolean {
        if (key.endsWith("/")) return true
        val probe = listObjects(dirPrefixOf(key), delimiter = "/", token = null, maxKeys = 1)
        return probe.objects.isNotEmpty() || probe.prefixes.isNotEmpty()
    }

    // ───────── URL 构造 ─────────

    /**
     * 拼对象 URL。
     *
     * - `pathStyle = true` → `{scheme}://{host}[:{port}]/{bucket}/{key}`
     * - `pathStyle = false` → `{scheme}://{bucket}.{host}[:{port}]/{key}`
     *
     * 虚拟主机式下 Host 头里也带 bucket 前缀（OkHttp 从 URL 派生 Host，[S3Signer] 也一样），
     * 两边必须完全一致，否则签名校验必挂。
     */
    private fun objectUrl(key: String, query: List<Pair<String, String?>> = emptyList()): HttpUrl {
        if (bucket.isEmpty()) throw FileProvider.ProviderException("S3 Bucket 未配置")
        val builder = HttpUrl.Builder()
            .scheme(scheme)
            .host(if (config.pathStyle) host else "$bucket.$host")
        if (config.port > 0) builder.port(config.port)
        if (config.pathStyle) builder.addPathSegment(bucket)
        if (key.isNotEmpty()) builder.addPathSegments(key)
        encodeQuery(query)?.let { builder.encodedQuery(it) }
        return builder.build()
    }

    /** bucket 级 URL（列举 / 批量删除用）。 */
    private fun bucketUrl(query: List<Pair<String, String?>>): HttpUrl = objectUrl("", query)

    /**
     * 自己按 RFC 3986 编码查询串，而不是交给 `addQueryParameter`。
     *
     * OkHttp 的查询编码比 RFC 3986 宽松（例如 `+` / `/` 原样保留），而签名用的是严格 RFC 3986；
     * 让线上字节和签名输入出自同一套编码器，可以彻底排掉"签名对不上"这类只能靠 403 发现的问题。
     */
    private fun encodeQuery(params: List<Pair<String, String?>>): String? {
        if (params.isEmpty()) return null
        return params.joinToString("&") { (k, v) ->
            val ek = S3Signer.rfc3986Encode(k)
            if (v == null) ek else "$ek=${S3Signer.rfc3986Encode(v)}"
        }
    }

    // ───────── S3 操作 ─────────

    private data class S3Object(val key: String, val size: Long, val lastModified: Long)

    private data class ListPage(
        val prefixes: List<String>,
        val objects: List<S3Object>,
        val truncated: Boolean,
        val nextToken: String?
    )

    /**
     * ListObjectsV2。[delimiter] 传 null 表示不分层（递归列出前缀下所有对象）。
     */
    private fun listObjects(
        prefix: String,
        delimiter: String?,
        token: String?,
        maxKeys: Int = PAGE_SIZE
    ): ListPage {
        val query = mutableListOf<Pair<String, String?>>()
        query += "list-type" to "2"
        query += "max-keys" to maxKeys.toString()
        if (prefix.isNotEmpty()) query += "prefix" to prefix
        if (delimiter != null) query += "delimiter" to delimiter
        if (token != null) query += "continuation-token" to token

        val request = Request.Builder().url(bucketUrl(query)).get().build()
        val xml = execute(request, S3Signer.EMPTY_SHA256).use { it.body?.string() ?: "" }
        return parseListResult(xml)
    }

    private data class HeadInfo(val size: Long, val lastModified: Long)

    /**
     * HEAD 对象。404 返回 null（调用方据此再判断是不是"目录"）。
     */
    private fun headObject(key: String): HeadInfo? {
        val request = Request.Builder().url(objectUrl(key)).head().build()
        val response = execute(request, S3Signer.EMPTY_SHA256, expectSuccess = false)
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw failureOf(it)
            val size = it.header("Content-Length")?.toLongOrNull() ?: 0L
            val lastModified = it.header("Last-Modified")?.let { s -> parseRfc1123(s) } ?: 0L
            return HeadInfo(size, lastModified)
        }
    }

    private fun deleteObject(key: String) {
        val request = Request.Builder().url(objectUrl(key)).delete().build()
        execute(request, S3Signer.EMPTY_SHA256).close()
    }

    /**
     * 服务端复制（CopyObject）：PUT 目标 key + `x-amz-copy-source` 头，字节不经过设备。
     */
    private fun copyObject(srcKey: String, dstKey: String) {
        // copy-source 的值要 URL 编码但保留 `/`，否则带空格 / 中文的 key 会被服务端判成非法。
        val encodedSource = "/$bucket/" + srcKey.split('/').joinToString("/") { S3Signer.rfc3986Encode(it) }
        val request = Request.Builder()
            .url(objectUrl(dstKey))
            .put(ByteArray(0).toRequestBody(OCTET_STREAM))
            .header("x-amz-copy-source", encodedSource)
            .build()
        execute(request, S3Signer.EMPTY_SHA256).close()
    }

    /**
     * 收齐前缀下所有 key（完整翻页），超过 [MAX_RECURSIVE_OBJECTS] 直接拒绝。
     */
    private fun collectKeys(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        var token: String? = null
        while (true) {
            val page = listObjects(prefix, delimiter = null, token = token)
            page.objects.forEach { keys += it.key }
            if (keys.size > MAX_RECURSIVE_OBJECTS) {
                throw FileProvider.ProviderException("目录下对象超过 $MAX_RECURSIVE_OBJECTS 个，请在对象存储控制台批量删除")
            }
            token = page.nextToken
            if (!page.truncated || token == null) break
        }
        return keys
    }

    /**
     * DeleteObjects 批量删除，每批 [DELETE_BATCH] 个（S3 协议硬上限）。
     *
     * 返回 false 表示至少有一个对象删失败 —— 这个接口即使部分失败也回 200，
     * 必须解析响应体里的 `<Error>` 才知道真实结果。
     */
    private fun deleteBatch(keys: List<String>): Boolean {
        var allOk = true
        for (chunk in keys.chunked(DELETE_BATCH)) {
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<Delete><Quiet>true</Quiet>")
                for (k in chunk) {
                    append("<Object><Key>").append(xmlEscape(k)).append("</Key></Object>")
                }
                append("</Delete>")
            }
            val body = xml.toByteArray(Charsets.UTF_8)
            val request = Request.Builder()
                .url(bucketUrl(listOf("delete" to null)))
                .post(body.toRequestBody(XML_MEDIA))
                // Content-MD5 是 AWS 对 DeleteObjects 的强制要求（MinIO 不查，但带着无害）。
                .header("Content-MD5", base64Md5(body))
                .build()
            val responseXml = execute(request, S3Signer.sha256Hex(body)).use { it.body?.string() ?: "" }
            if (countDeleteErrors(responseXml) > 0) allOk = false
        }
        return allOk
    }

    // ───────── HTTP 执行与错误映射 ─────────

    /**
     * 签名 + 发送。[expectSuccess] 为 false 时把非 2xx 响应原样返回给调用方（HEAD 要自己判 404）。
     */
    private fun execute(request: Request, payloadSha256: String, expectSuccess: Boolean = true): Response {
        val signed = S3Signer.sign(
            request = request,
            accessKey = config.username,
            secretKey = config.password,
            region = config.region.ifBlank { "us-east-1" },
            payloadSha256 = payloadSha256,
            nowMillis = System.currentTimeMillis()
        )
        val response = try {
            client.newCall(signed).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw mapError(e)
        }
        if (expectSuccess && !response.isSuccessful) {
            throw failureOf(response)
        }
        return response
    }

    /**
     * 把 S3 的错误响应翻译成 [FileProvider.ProviderException]。
     *
     * S3 的错误是 XML：`<Error><Code>NoSuchBucket</Code><Message>…</Message></Error>`，
     * HTTP 状态码本身区分度很低（权限、签名、Region 不匹配都可能是 403），必须看 Code。
     */
    private fun failureOf(response: Response): FileProvider.ProviderException {
        val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
        try { response.close() } catch (_: Exception) {}
        val code = extractXmlText(body, "Code") ?: ""
        val message = extractXmlText(body, "Message") ?: ""

        return when {
            code == "SignatureDoesNotMatch" ->
                FileProvider.ProviderAuthException("签名校验失败，请检查 Secret Key")
            code == "InvalidAccessKeyId" ->
                FileProvider.ProviderAuthException("Access Key 不存在")
            code == "AccessDenied" ->
                FileProvider.ProviderAuthException("权限不足")
            // Bucket 名写错是头号配错，消息里必须带上名字，否则用户只会看到 404。
            code == "NoSuchBucket" ->
                FileProvider.ProviderException("Bucket 不存在：${config.bucket}")
            code == "NoSuchKey" ->
                FileProvider.ProviderException("对象不存在")
            code == "PermanentRedirect" || response.code == 301 -> {
                val suggested = extractXmlText(body, "Region")
                    ?: extractXmlText(body, "Endpoint")
                    ?: "（服务端未给出）"
                FileProvider.ProviderException("Region 不匹配，服务端提示应使用：$suggested")
            }
            code == "InvalidObjectState" ->
                FileProvider.ProviderException("对象在归档存储层，需先解冻")
            code == "EntityTooLarge" ->
                FileProvider.ProviderException("对象超过服务端单次上传上限")
            code.isNotEmpty() ->
                FileProvider.ProviderException("S3 错误：$code${if (message.isNotEmpty()) "（$message）" else ""}")
            response.code == 401 || response.code == 403 ->
                FileProvider.ProviderAuthException("S3 认证失败（HTTP ${response.code}）")
            response.code == 404 ->
                FileProvider.ProviderException("对象不存在（HTTP 404）")
            else ->
                FileProvider.ProviderException("S3 错误：HTTP ${response.code}")
        }
    }

    /**
     * 网络层异常映射。与 SmbFileProvider.mapError 同口径，避免四个 provider 各写一套文案。
     */
    private fun mapError(e: Throwable): FileProvider.ProviderException = when (e) {
        is FileProvider.ProviderException -> e
        is SocketTimeoutException ->
            FileProvider.ProviderTimeoutException("S3 请求超时：$host")
        is ConnectException ->
            FileProvider.ProviderTimeoutException("S3 连接失败：${e.message}")
        is UnknownHostException ->
            FileProvider.ProviderException("域名解析失败：$host")
        is javax.net.ssl.SSLException ->
            FileProvider.ProviderException("S3 TLS 错误：${e.message}", e)
        is IOException -> {
            val msg = e.message ?: ""
            if (msg.contains("timeout", ignoreCase = true) || msg.contains("connect", ignoreCase = true)) {
                FileProvider.ProviderTimeoutException("S3 连接失败：$msg")
            } else {
                FileProvider.ProviderException("S3 IO 错误：$msg", e)
            }
        }
        else -> FileProvider.ProviderException("S3 操作失败：${e.message}", e)
    }

    // ───────── XML 解析 ─────────

    /**
     * 解析 ListObjectsV2 响应。
     *
     * 刻意关掉命名空间感知（WebDavFileProvider 那边是开着的）：S3 响应把
     * `http://s3.amazonaws.com/doc/2006-03-01/` 声明为默认命名空间，但各家兼容实现
     * （MinIO / OSS / COS / R2）对这个声明的处理并不统一，按本地名取元素最省事也最稳。
     */
    private fun parseListResult(xml: String): ListPage {
        if (xml.isBlank()) return ListPage(emptyList(), emptyList(), false, null)
        return try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val prefixes = mutableListOf<String>()
            val cp = doc.getElementsByTagName("CommonPrefixes")
            for (i in 0 until cp.length) {
                val elem = cp.item(i) as? Element ?: continue
                // 注意：顶层也有一个 <Prefix>（回显请求参数），所以只能在 CommonPrefixes 里面取
                childText(elem, "Prefix")?.let { prefixes += it }
            }

            val objects = mutableListOf<S3Object>()
            val contents = doc.getElementsByTagName("Contents")
            for (i in 0 until contents.length) {
                val elem = contents.item(i) as? Element ?: continue
                val key = childText(elem, "Key") ?: continue
                val size = childText(elem, "Size")?.toLongOrNull() ?: 0L
                val lastModified = childText(elem, "LastModified")?.let { parseIso8601(it) } ?: 0L
                objects += S3Object(key, size, lastModified)
            }

            val truncated = doc.getElementsByTagName("IsTruncated").let {
                (it.item(0) as? Element)?.textContent?.trim().equals("true", ignoreCase = true)
            }
            val nextToken = (doc.getElementsByTagName("NextContinuationToken").item(0) as? Element)
                ?.textContent?.trim()?.ifBlank { null }

            ListPage(prefixes, objects, truncated, nextToken)
        } catch (e: Exception) {
            throw FileProvider.ProviderException("S3 列举响应解析失败：${e.message}", e)
        }
    }

    /** 统计 DeleteObjects 响应里的 `<Error>` 条数。 */
    private fun countDeleteErrors(xml: String): Int {
        if (xml.isBlank()) return 0
        return try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            doc.getElementsByTagName("Error").length
        } catch (_: Exception) {
            // 解析不了就当没错：这里只用于"部分失败"判定，误报一个 false 比抛异常更难排查
            0
        }
    }

    /**
     * 从错误响应里抠单个标签的文本。错误体很小且结构固定，不值得起一个 DOM。
     */
    private fun extractXmlText(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim().ifBlank { null }
    }

    private fun childText(elem: Element, tag: String): String? {
        val nodes = elem.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.ifBlank { null }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ───────── 元信息构造 ─────────

    private fun dirInfo(prefix: String): ProviderFileInfo {
        val external = toExternalPath(prefix)
        return ProviderFileInfo(
            name = prefix.trimEnd('/').substringAfterLast('/').ifBlank { "/" },
            path = external,
            isDirectory = true,
            // S3 的"目录"只是一段公共前缀，既没有大小也没有修改时间
            size = 0L,
            lastModified = 0L,
            permissions = PERM_DIR,
            isSymlink = false,
            source = id
        )
    }

    private fun fileInfo(o: S3Object): ProviderFileInfo = ProviderFileInfo(
        name = o.key.trimEnd('/').substringAfterLast('/'),
        path = toExternalPath(o.key),
        isDirectory = false,
        size = o.size,
        lastModified = o.lastModified,
        permissions = PERM_FILE,
        isSymlink = false,
        // S3 没有符号链接
        source = id
    )

    // ───────── 小工具 ─────────

    private fun parseIso8601(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun parseRfc1123(s: String): Long = try {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(s)).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun base64Md5(data: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data))

    /**
     * 最多读取 [maxBytes] 字节。与 FtpFileProvider / SmbFileProvider 同一写法 ——
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
}

/**
 * S3 既没有 POSIX 权限位也没有默认暴露 ACL，这两个串纯粹是给 UI 占位用的，
 * 口径跟 WebDavFileProvider 保持一致（同为 HTTP 系 provider，便于对照）。
 */
private const val PERM_DIR = "drwxr-xr-x"
private const val PERM_FILE = "-rw-r--r--"

private val OCTET_STREAM = "application/octet-stream".toMediaType()
private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
