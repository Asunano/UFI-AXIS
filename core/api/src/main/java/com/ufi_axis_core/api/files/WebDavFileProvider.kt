package com.ufi_axis_core.api.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV [FileProvider] 实现，基于 OkHttp。
 *
 * 使用标准 WebDAV 方法（PROPFIND / GET / PUT / DELETE / MKCOL / MOVE / COPY）
 * 通过 HTTP(S) 操作远程文件系统。不依赖第三方 WebDAV 库。
 *
 * XML 解析使用 `javax.xml.parsers.DocumentBuilderFactory`（Android 原生可用）。
 */
class WebDavFileProvider(private val config: StorageSourceConfig) : FileProvider {

    override val id: String = "webdav:${config.id}"
    override val label: String = config.label
    override val protocol: String = "webdav"
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
        FileProvider.Capability.DOWNLOAD
    )

    companion object {
        private const val MAX_LIST_ENTRIES = 2000
        private const val MAX_READ_BYTES = 512L * 1024 // 512 KB

        /**
         * 发给 WebDAV 服务端的 User-Agent。
         *
         * 用常规浏览器 UA 而不是 `okhttp/…`：见 [client] 里那段注释 ——
         * 不少网盘的 WAF 只拦"非浏览器 UA 的下载请求"，PROPFIND 照过，
         * 于是症状是"目录能看、视频点开 502"，从我们这一侧完全看不出原因。
         */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** RFC 1123 日期格式（WebDAV getlastmodified 标准格式） */
        private val RFC1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        /** PROPFIND 请求体（allprop） */
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:allprop/>
</D:propfind>"""
    }

    /**
     * 拆解用户填的 host —— 这里必须宽容，因为 WebDAV 服务商给的**就是一整条 URL**
     * （「WebDAV 地址：https://pan.moe/dav」），用户照抄进「主机」框是必然的。
     *
     * 2026-09-21 修：此前直接 `append(config.host); append(":"); append(port)`，
     * host 里带了路径就会拼出 `https://pan.moe/dav:443/`——OkHttp 把它解析成
     * host=`pan.moe` + path=`/dav:443/`，请求打到一个不存在的路径上，表现为莫名 404；
     * host 带尾斜杠则拼出 `https://pan.moe/:443/dav`，多数站点对未知路径回首页 HTML，
     * 表现为 `Unexpected <!`（XML 解析器撞上 `<!DOCTYPE html>`）。两种都极难自查。
     *
     * @return (纯主机名[:端口段], host 里携带的路径前缀)
     */
    private val hostAndPathPrefix: Pair<String, String> = run {
        var raw = config.host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
        // host 里可能带路径（`pan.moe/dav`）：第一个 `/` 之后全部算路径前缀
        val slash = raw.indexOf('/')
        val pathInHost = if (slash >= 0) raw.substring(slash) else ""
        if (slash >= 0) raw = raw.substring(0, slash)
        raw to pathInHost.trimEnd('/')
    }

    private val baseUrl: String = buildString {
        val https = config.useTls
        append(if (https) "https" else "http")
        append("://")
        append(hostAndPathPrefix.first)
        // 默认端口不写进 URL：443/https 与 80/http 写出来虽合法，但 port<=0 时
        // 会拼出 `:0` 直接连不上，统一在这里判掉最省事。
        val defaultPort = if (https) 443 else 80
        if (config.port > 0 && config.port != defaultPort) {
            append(":")
            append(config.port)
        }
        // host 里携带的路径在前，basePath 在后 —— 用户把整条 URL 填进 host 时
        // basePath 通常还是默认的 `/`，这个顺序能让两种填法都落到同一个地址。
        append(hostAndPathPrefix.second)
        val bp = config.basePath.trim().let { if (it.startsWith("/")) it else "/$it" }
        append(bp.removeSuffix("/").takeIf { it != "/" } ?: "")
    }


    private val credential: String = Credentials.basic(config.username, config.password)

    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", credential)
                    // ── UA 与 Accept-Encoding（2026-09-21）──
                    //
                    // OkHttp 默认发 `User-Agent: okhttp/4.x`。相当多的网盘与它们前面的 WAF
                    // 对**非浏览器 UA 的文件下载**直接拦（列目录的 PROPFIND 往往放行），
                    // 表现就是"目录能看、GET 文件回 502/403"。给一个常规浏览器 UA 是
                    // rclone / RaiDrive 这类客户端同样在做的事，不是投机取巧。
                    //
                    // `identity` 则是明确告诉上游**别压缩**：视频/压缩包本来压不动，
                    // 而 nginx 对大文件开 gzip 是 502/504 的经典成因。
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept-Encoding", "identity")
                    .build()
                chain.proceed(req)
            }

        if (config.trustAllCerts) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        builder.build()
    }

    // ───────── FileProvider 实现 ─────────

    override suspend fun list(path: String): ProviderListResult = withContext(Dispatchers.IO) {
        val entries = propfind(path, depth = 1)
        // 第一个条目通常是目录自身，排除
        val parentHref = normalizePath(path)
        val files = entries.filter { normalizePath(it.path) != parentHref }
            .take(MAX_LIST_ENTRIES)
        val parent = path.removeSuffix("/").substringBeforeLast("/", "").ifBlank { null }
        ProviderListResult(
            files = files,
            path = path,
            parent = parent,
            truncated = entries.size - 1 > MAX_LIST_ENTRIES
        )
    }

    override suspend fun info(path: String): ProviderFileInfo = withContext(Dispatchers.IO) {
        val entries = propfind(path, depth = 0)
        entries.firstOrNull()
            ?: throw FileProvider.ProviderException("Resource not found: $path")
    }

    override suspend fun read(path: String, encoding: String?): ProviderReadResult = withContext(Dispatchers.IO) {
        val url = resolveUrl(path)
        val request = Request.Builder().url(url).get().build()
        val response = executeRequest(request)
        val body = response.body ?: throw FileProvider.ProviderException("Empty response body")
        val bytes = body.byteStream().use { stream ->
            val buf = ByteArray(MAX_READ_BYTES.toInt())
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            buf.copyOf(total)
        }
        val charset = try {
            if (encoding.isNullOrBlank()) Charsets.UTF_8 else java.nio.charset.Charset.forName(encoding)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: bytes.size.toLong()
        ProviderReadResult(
            content = String(bytes, charset),
            encoding = charset.name(),
            size = contentLength,
            truncated = contentLength > MAX_READ_BYTES
        )
    }

    override suspend fun write(path: String, content: String, encoding: String?) = withContext(Dispatchers.IO) {
        val charset = try {
            if (encoding.isNullOrBlank()) Charsets.UTF_8 else java.nio.charset.Charset.forName(encoding)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val bytes = content.toByteArray(charset)
        val url = resolveUrl(path)
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url(url).put(body).build()
        executeRequest(request).close()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val url = resolveUrl(path)
        val request = Request.Builder().url(url).delete().build()
        executeRequest(request).close()
        true
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        moveOrCopy(oldPath, newPath, method = "MOVE")
        true
    }

    override suspend fun move(source: String, destination: String): Map<String, Any> = withContext(Dispatchers.IO) {
        moveOrCopy(source, destination, method = "MOVE")
        mapOf("success" to true, "source" to source, "destination" to destination)
    }

    override suspend fun copy(source: String, destination: String): Boolean = withContext(Dispatchers.IO) {
        moveOrCopy(source, destination, method = "COPY")
        true
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        val url = resolveUrl(path.removeSuffix("/") + "/")
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .build()
        executeRequest(request).close()
        true
    }

    override suspend fun search(path: String, query: String, maxDepth: Int): List<ProviderFileInfo> {
        throw FileProvider.ProviderUnsupportedException("WebDAV search not supported")
    }

    override suspend fun diskUsage(): List<ProviderVolumeInfo> {
        throw FileProvider.ProviderUnsupportedException("WebDAV disk usage not supported")
    }

    override suspend fun downloadStream(path: String): InputStream = downloadStream(path, 0L)

    /**
     * 带偏移的读取 —— 走 HTTP `Range`。
     *
     * ## 为什么 **offset 为 0 也带 Range**
     * 不带 Range 的整文件 GET 在很多网盘 / 反代前面是条死路：同一个源
     * PROPFIND 列目录完全正常，而 GET 视频直接回 `502 Bad Gateway`（nginx）——
     * 表现就是"目录能看、视频点开报错"（ExoPlayer 报 2004）。
     * `Range: bytes=0-` 是浏览器与播放器的常规首包形态，兼容性明显更好。
     *
     * ## 服务器忽略 Range 时的兜底
     * 回 200（而不是 206）说明它不认 Range：此时响应体是从 0 开始的整个文件，
     * 必须自己丢掉前 [startOffset] 个字节，否则 seek 后拿到的是**错位数据** ——
     * 画面花屏或直接解码失败，而且不会有任何报错，属于最难查的一类。
     */
    override suspend fun downloadStream(path: String, startOffset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val url = resolveUrl(path)
            val from = startOffset.coerceAtLeast(0L)
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$from-")
                .get()
                .build()
            val response = executeRequest(request)
            val stream = response.body?.byteStream()
                ?: throw FileProvider.ProviderException("Empty response body")
            if (from > 0L && response.code != 206) {
                var remaining = from
                while (remaining > 0) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0) {
                        if (stream.read() < 0) break
                        remaining -= 1
                    } else {
                        remaining -= skipped
                    }
                }
            }
            stream
        }

    override suspend fun uploadStream(path: String, input: InputStream, size: Long) = withContext(Dispatchers.IO) {
        val url = resolveUrl(path)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                input.use { stream ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }
        val request = Request.Builder().url(url).put(body).build()
        executeRequest(request).close()
    }

    // ───────── 资源管理 ─────────

    /**
     * 释放 OkHttp 资源。调用后不应再使用此 provider。
     */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ───────── 内部方法 ─────────

    /**
     * 执行 PROPFIND 请求并解析 multistatus XML。
     */
    private fun propfind(path: String, depth: Int): List<ProviderFileInfo> {
        val url = resolveUrl(if (path.endsWith("/") || path.isBlank()) path else "$path/")
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .build()
        val response = executeRequest(request)
        val xml = response.body?.string() ?: return emptyList()
        return parseMultistatus(xml, path)
    }

    /**
     * 解析 multistatus XML 响应。
     */
    private fun parseMultistatus(xml: String, requestPath: String): List<ProviderFileInfo> {
        val result = mutableListOf<ProviderFileInfo>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val respElem = responses.item(i) as? Element ?: continue
                val href = textContent(respElem, "DAV:", "href") ?: continue
                val displayName = textContent(respElem, "DAV:", "displayname")
                val contentLength = textContent(respElem, "DAV:", "getcontentlength")?.toLongOrNull() ?: 0L
                val lastModified = textContent(respElem, "DAV:", "getlastmodified")?.let { parseDate(it) } ?: 0L
                val contentType = textContent(respElem, "DAV:", "getcontenttype") ?: ""
                val isCollection = isCollection(respElem)

                // 从 href 提取相对路径
                val decodedHref = try {
                    URLDecoder.decode(href, "UTF-8")
                } catch (_: Exception) {
                    href
                }
                val relativePath = extractRelativePath(decodedHref)
                val name = displayName
                    ?: relativePath.removeSuffix("/").substringAfterLast("/").ifBlank { "/" }

                result.add(
                    ProviderFileInfo(
                        name = name,
                        path = relativePath,
                        isDirectory = isCollection,
                        size = contentLength,
                        lastModified = lastModified,
                        permissions = if (isCollection) "drwxr-xr-x" else "-rw-r--r--",
                        isSymlink = false,
                        source = id
                    )
                )
            }
        } catch (e: Exception) {
            throw FileProvider.ProviderException("Failed to parse WebDAV response: ${e.message}", e)
        }
        return result
    }

    /**
     * 判断一个 response 元素是否代表集合（目录）。
     */
    private fun isCollection(respElem: Element): Boolean {
        val propstats = respElem.getElementsByTagNameNS("DAV:", "propstat")
        for (i in 0 until propstats.length) {
            val propstat = propstats.item(i) as? Element ?: continue
            val props = propstat.getElementsByTagNameNS("DAV:", "prop")
            for (j in 0 until props.length) {
                val prop = props.item(j) as? Element ?: continue
                val resType = prop.getElementsByTagNameNS("DAV:", "resourcetype")
                for (k in 0 until resType.length) {
                    val rt = resType.item(k) as? Element ?: continue
                    if (rt.getElementsByTagNameNS("DAV:", "collection").length > 0) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 从 response 元素中获取指定 DAV 属性的文本内容。
     */
    private fun textContent(respElem: Element, ns: String, localName: String): String? {
        val propstats = respElem.getElementsByTagNameNS(ns, "propstat")
        for (i in 0 until propstats.length) {
            val propstat = propstats.item(i) as? Element ?: continue
            val props = propstat.getElementsByTagNameNS(ns, "prop")
            for (j in 0 until props.length) {
                val prop = props.item(j) as? Element ?: continue
                val elems = prop.getElementsByTagNameNS(ns, localName)
                if (elems.length > 0) {
                    return elems.item(0)?.textContent?.trim()
                }
            }
        }
        // Fallback: 直接在 respElem 下查找（非标准但常见）
        val direct = respElem.getElementsByTagNameNS(ns, localName)
        return if (direct.length > 0) direct.item(0)?.textContent?.trim() else null
    }

    /**
     * 解析 RFC 1123 / RFC 850 等日期格式。
     */
    private fun parseDate(date: String): Long {
        return try {
            RFC1123.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * [baseUrl] 里的路径部分（host 携带的前缀 + basePath），用于从 href 里剥掉。
     *
     * 不能只用 `config.basePath`：用户把整条 WebDAV 地址填进「主机」框时，
     * 真正的前缀在 [hostAndPathPrefix] 里，只剥 basePath 会让返回的 path 多带一段
     * `/dav`，客户端再拿它请求就变成 `/dav/dav/...`。
     */
    private val urlPathPrefix: String = run {
        val bp = config.basePath.trim().removeSuffix("/").let {
            if (it.isBlank() || it == "/") "" else if (it.startsWith("/")) it else "/$it"
        }
        (hostAndPathPrefix.second + bp).ifBlank { "" }
    }

    /**
     * 从 href 中提取相对于 [urlPathPrefix] 的路径。
     *
     * href 可能是绝对 URL（`http://host:port/dav/sub/f.txt`）或绝对路径（`/dav/sub/f.txt`），
     * 两种都要归一到「去掉前缀后的路径」，否则客户端拿到的 path 没法再喂回本 provider。
     */
    private fun extractRelativePath(href: String): String {
        // ① 剥掉 scheme://host[:port]，只留路径部分
        val pathOnly = if (href.contains("://")) {
            val afterScheme = href.substringAfter("://")
            val slash = afterScheme.indexOf('/')
            if (slash < 0) "/" else afterScheme.substring(slash)
        } else {
            if (href.startsWith("/")) href else "/$href"
        }
        // ② 去掉 URL 路径前缀
        val relative = if (urlPathPrefix.isNotBlank() && pathOnly.startsWith(urlPathPrefix)) {
            pathOnly.removePrefix(urlPathPrefix)
        } else {
            pathOnly
        }
        return relative.ifBlank { "/" }
    }


    /**
     * MOVE / COPY 操作。
     */
    private fun moveOrCopy(src: String, dst: String, method: String) {
        val srcUrl = resolveUrl(src)
        val dstUrl = resolveUrl(dst)
        val request = Request.Builder()
            .url(srcUrl)
            .method(method, null)
            .header("Destination", dstUrl)
            .header("Overwrite", "F")
            .build()
        executeRequest(request).close()
    }

    /**
     * 构造完整 URL（对路径分段编码，保留 `/`）。
     */
    private fun resolveUrl(path: String): String {
        val cleanPath = path.let { if (it.startsWith("/")) it else "/$it" }
        val encoded = cleanPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "$baseUrl$encoded"
    }

    /**
     * 路径归一化（去尾 `/`，空变 `/`）。
     */
    private fun normalizePath(path: String): String {
        val p = path.removeSuffix("/")
        return p.ifBlank { "/" }
    }

    /**
     * 执行请求并处理错误映射。
     */
    private fun executeRequest(request: Request): okhttp3.Response {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            throw FileProvider.ProviderTimeoutException("WebDAV request timed out: ${e.message}")
        } catch (e: java.net.ConnectException) {
            throw FileProvider.ProviderTimeoutException("WebDAV connection failed: ${e.message}")
        } catch (e: javax.net.ssl.SSLException) {
            throw FileProvider.ProviderException("WebDAV SSL error: ${e.message}", e)
        } catch (e: Exception) {
            throw FileProvider.ProviderException("WebDAV request failed: ${e.message}", e)
        }
        val code = response.code
        when {
            code in 200..299 || code == 207 -> {
                // 拿到 200 不等于拿到 WebDAV 响应：地址指错时多数站点会回首页 HTML，
                // 让 XML 解析器撞上 `<!DOCTYPE html>` 抛出「Unexpected <!」——
                // 那条信息对用户毫无指向性，所以在这里按 Content-Type 提前拦掉。
                val ct = response.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/html")) {
                    response.close()
                    throw FileProvider.ProviderException(
                        "该地址返回的是网页而不是 WebDAV 响应（$baseUrl）。" +
                            "请确认「主机」只填域名、WebDAV 路径填到「远端路径」，例如 " +
                            "主机 pan.moe + 远端路径 /dav"
                    )
                }
            }
            code == 401 || code == 403 ->
                throw FileProvider.ProviderAuthException("WebDAV 认证失败（HTTP $code），请检查用户名与密码")
            code == 404 ->
                throw FileProvider.ProviderException("WebDAV 路径不存在（HTTP 404）：$baseUrl")
            code == 405 ->
                throw FileProvider.ProviderException(
                    "服务端不支持 PROPFIND（HTTP 405）：$baseUrl 可能不是 WebDAV 端点"
                )
            code == 409 ->
                throw FileProvider.ProviderException("WebDAV 冲突（HTTP 409）：父目录可能不存在")
            else -> {
                val raw = response.body?.string().orEmpty()
                // 网关类错误（502/504）的响应体是一整页 nginx HTML。原样塞进异常消息，
                // 最后会以 `<html><head><title>502 …` 的形态出现在用户的提示条里 ——
                // 那既看不懂也占满屏幕。所以 HTML 一律折成一句人话，只保留状态码。
                //
                // 但**响应头要带上**：502 到底是"上游真的挂了"还是"WAF 把我们拦了"，
                // 只能靠 Server / Via / X-Request-Id 这类头区分。少了它，从这一侧
                // 看到的永远只是一句"502"，再怎么试都是盲猜。
                val looksHtml = raw.trimStart().startsWith("<", ignoreCase = true)
                val hints = listOfNotNull(
                    response.header("Server")?.let { "server=$it" },
                    response.header("Via")?.let { "via=$it" },
                    response.header("X-Request-Id")?.let { "reqId=$it" },
                    response.header("Location")?.let { "redirect→${it.take(60)}" },
                    response.header("Content-Type")?.let { "ct=$it" }
                ).joinToString(" ")
                response.close()
                val detail = when {
                    looksHtml && code in 502..504 -> "远端网关无响应（可能在限流、拦截 UA 或后端不可用）"
                    looksHtml -> "服务端返回了网页而不是 WebDAV 响应"
                    else -> raw.take(200)
                }
                throw FileProvider.ProviderException(
                    "WebDAV 错误 HTTP $code: $detail" + if (hints.isNotEmpty()) " [$hints]" else ""
                )
            }
        }
        return response

    }
}
