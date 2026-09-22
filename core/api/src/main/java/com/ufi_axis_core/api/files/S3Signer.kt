package com.ufi_axis_core.api.files

import okhttp3.Request
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS SigV4 手动签名器（service = `s3`）。
 *
 * 不依赖任何 AWS SDK —— 签名算法本身只是 HMAC-SHA256 + 规范化字符串拼接，
 * JCA 原生就够。AWS SDK 体积 ~15MB，对一个设备端 app 来说不可接受。
 *
 * 线程安全：所有方法无状态，可并发调用。
 */
internal object S3Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val TERMINATOR = "aws4_request"

    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    /** ISO8601 紧凑格式，用于 x-amz-date 头。 */
    private val FMT_DATETIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** 日期部分（yyyyMMdd），用于 credential scope。 */
    private val FMT_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    // ───────── RFC 3986 编码 ─────────

    /**
     * RFC 3986 百分号编码。
     *
     * Unreserved set: A-Z a-z 0-9 - . _ ~
     * 其余全部 %XX（大写十六进制）。
     *
     * java.net.URLEncoder 不适用：它把空格编码成 `+`、不编码 `*`、编码 `~`。
     */
    fun rfc3986Encode(value: String): String {
        val sb = StringBuilder(value.length * 2)
        // 按 UTF-8 字节逐字节处理
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (isUnreserved(c)) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_UPPER[c shr 4])
                sb.append(HEX_UPPER[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    private fun isUnreserved(c: Int): Boolean = when {
        c in 'A'.code..'Z'.code -> true
        c in 'a'.code..'z'.code -> true
        c in '0'.code..'9'.code -> true
        c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code -> true
        else -> false
    }

    // ───────── SHA-256 ─────────

    /** 计算字节数组的 SHA-256 十六进制串。 */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.toHexString()
    }

    // ───────── 签名入口 ─────────

    /**
     * 给请求加 AWS SigV4 签名头，返回带 Authorization / x-amz-date / x-amz-content-sha256 的新请求。
     *
     * @param payloadSha256 请求体的 SHA-256 十六进制串。空体传 [EMPTY_SHA256]；
     *   流式上传传 [UNSIGNED_PAYLOAD]（不预读整个流算 hash）。
     * @param nowMillis 时间戳外部传入 —— 签名里嵌了精确到秒的时间，单测必须能固定它。
     */
    fun sign(
        request: Request,
        accessKey: String,
        secretKey: String,
        region: String,
        payloadSha256: String,
        nowMillis: Long
    ): Request {
        val instant = Instant.ofEpochMilli(nowMillis)
        val dateTime = FMT_DATETIME.format(instant)   // 20130524T000000Z
        val dateOnly = FMT_DATE.format(instant)        // 20130524
        val credentialScope = "$dateOnly/$region/$SERVICE/$TERMINATOR"

        // 先往原始请求里加上必须签名的头，再用完整的头列表去算 canonical request
        val augmented = request.newBuilder()
            .header("x-amz-date", dateTime)
            .header("x-amz-content-sha256", payloadSha256)
            .build()

        // ── Step 1: Canonical Request ──
        val url = augmented.url
        val method = augmented.method

        // Canonical URI：路径分段 RFC3986 编码，`/` 不编码
        val canonicalUri = canonicalUri(url.encodedPath)

        // Canonical Query String
        val canonicalQuery = canonicalQueryString(url)

        // Canonical Headers + Signed Headers
        // 必须签名：host, x-amz-date, x-amz-content-sha256，再加上请求中已有的其它 x-amz-* 头
        val headerMap = sortedMapOf<String, String>()
        for (name in augmented.headers.names()) {
            val lower = name.lowercase()
            val value = augmented.headers.values(name).joinToString(",") { trimHeaderValue(it) }
            headerMap[lower] = value
        }
        // 确保 host 在里面
        if ("host" !in headerMap) {
            val hostHeader = if (url.port == defaultPort(url.scheme)) url.host
            else "${url.host}:${url.port}"
            headerMap["host"] = hostHeader
        }

        val canonicalHeaders = buildString {
            for ((k, v) in headerMap) {
                append(k).append(':').append(v).append('\n')
            }
        }
        val signedHeaders = headerMap.keys.joinToString(";")

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadSha256)
        }

        // ── Step 2: String To Sign ──
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(dateTime).append('\n')
            append(credentialScope).append('\n')
            append(canonicalRequestHash)
        }

        // ── Step 3: Signing Key ──
        val kDate = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateOnly)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, SERVICE)
        val kSigning = hmacSha256(kService, TERMINATOR)

        // ── Step 4: Signature ──
        val signature = hmacSha256(kSigning, stringToSign).toHexString()

        val authorization = "$ALGORITHM Credential=$accessKey/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        return augmented.newBuilder()
            .header("Authorization", authorization)
            .build()
    }

    // ───────── 内部工具 ─────────

    /**
     * 规范化 URI 路径：对每个路径分段重新做 RFC 3986 编码。
     * OkHttp 的 encodedPath 已经是百分号编码过的，我们需要先解码再用自己的编码器重编。
     */
    private fun canonicalUri(encodedPath: String): String {
        if (encodedPath.isEmpty() || encodedPath == "/") return "/"
        val segments = encodedPath.split('/')
        return segments.joinToString("/") { segment ->
            if (segment.isEmpty()) ""
            else rfc3986Encode(percentDecode(segment))
        }
    }

    /**
     * 百分号解码。
     */
    private fun percentDecode(s: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            // 非 %XX 序列，按 UTF-8 编码当前字符
            for (b in s[i].toString().toByteArray(Charsets.UTF_8)) {
                bytes.add(b)
            }
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 规范化查询字符串：按 key 排序（key 相同时按 value 排序），key 和 value 均 RFC3986 编码。
     */
    private fun canonicalQueryString(url: okhttp3.HttpUrl): String {
        val size = url.querySize
        if (size == 0) return ""
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in 0 until size) {
            val key = rfc3986Encode(url.queryParameterName(i))
            val value = rfc3986Encode(url.queryParameterValue(i) ?: "")
            pairs.add(key to value)
        }
        pairs.sortWith(compareBy({ it.first }, { it.second }))
        return pairs.joinToString("&") { "${it.first}=${it.second}" }
    }

    /**
     * Trim 头部值并折叠内部连续空白为单个空格。
     */
    private fun trimHeaderValue(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
