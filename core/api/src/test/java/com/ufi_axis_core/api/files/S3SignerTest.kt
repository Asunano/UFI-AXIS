package com.ufi_axis_core.api.files

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [S3Signer] 的 AWS 官方 SigV4 测试向量验证。
 *
 * 向量出自 AWS 文档「Examples: Signature Calculations for Amazon S3」。
 * **这些期望值是外部权威基准，任何情况下都不要为了让代码通过而改动它们** ——
 * 签名器只要和参考实现差一个字节，打到真实 S3 端点上就是一个没有任何线索的 403。
 */
class S3SignerTest {

    private companion object {
        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val REGION = "us-east-1"
        const val HOST = "examplebucket.s3.amazonaws.com"

        /** 所有官方向量都固定在 2013-05-24T00:00:00Z。 */
        val FIXED_TIME: Long = Instant.parse("2013-05-24T00:00:00Z").toEpochMilli()
    }

    /** 从 Authorization 头里抠出 Signature= 后面那串。 */
    private fun signatureOf(request: Request): String {
        val auth = request.header("Authorization")
            ?: error("Authorization header missing")
        return auth.substringAfter("Signature=").trim()
    }

    // ───────── 官方向量 ─────────

    /**
     * 官方向量 1：GET Object（带 Range 头）。
     */
    @Test
    fun officialVector_getObject() {
        val request = Request.Builder()
            .url("https://$HOST/test.txt")
            .get()
            .header("Host", HOST)
            .header("Range", "bytes=0-9")
            .build()

        val signed = S3Signer.sign(
            request = request,
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            region = REGION,
            payloadSha256 = S3Signer.EMPTY_SHA256,
            nowMillis = FIXED_TIME
        )

        assertEquals(
            "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signatureOf(signed)
        )
        // 顺带确认签名头都挂上去了，且 SignedHeaders 含 host/range/x-amz-*
        assertEquals("20130524T000000Z", signed.header("x-amz-date"))
        assertEquals(S3Signer.EMPTY_SHA256, signed.header("x-amz-content-sha256"))
        assertTrue(
            signed.header("Authorization")!!.contains(
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"
            )
        )
        assertTrue(
            signed.header("Authorization")!!.startsWith(
                "AWS4-HMAC-SHA256 Credential=$ACCESS_KEY/20130524/$REGION/s3/aws4_request, "
            )
        )
    }

    /**
     * 官方向量 2：PUT Object。
     *
     * 这条同时覆盖两件事：key 里的 `$` 必须被编码成 `%24`（RFC 3986 里 `$` 不是 unreserved），
     * 以及非 x-amz-* 的自定义头（Date / x-amz-storage-class）也要进签名列表。
     */
    @Test
    fun officialVector_putObject() {
        val body = "Welcome to Amazon S3.".toByteArray(Charsets.UTF_8)
        val payloadHash = S3Signer.sha256Hex(body)
        // 官方文档给出的 body hash，先自校验一遍 sha256Hex
        assertEquals(
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
            payloadHash
        )

        val request = Request.Builder()
            .url("https://$HOST/test\$file.text")
            .put(body.toRequestBody())
            .header("Host", HOST)
            .header("Date", "Fri, 24 May 2013 00:00:00 GMT")
            .header("x-amz-storage-class", "REDUCED_REDUNDANCY")
            .build()

        val signed = S3Signer.sign(
            request = request,
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            region = REGION,
            payloadSha256 = payloadHash,
            nowMillis = FIXED_TIME
        )

        assertEquals(
            "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            signatureOf(signed)
        )
        assertTrue(
            signed.header("Authorization")!!.contains(
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class"
            )
        )
    }

    /**
     * 官方向量 3：GET Bucket Lifecycle（`?lifecycle` 这种无值查询参数）。
     *
     * 无值参数的规范形式是 `lifecycle=`（等号保留、值为空），漏掉等号签名就错。
     */
    @Test
    fun officialVector_getBucketLifecycle() {
        val request = Request.Builder()
            .url("https://$HOST/?lifecycle")
            .get()
            .header("Host", HOST)
            .build()

        val signed = S3Signer.sign(
            request = request,
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            region = REGION,
            payloadSha256 = S3Signer.EMPTY_SHA256,
            nowMillis = FIXED_TIME
        )

        assertEquals(
            "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            signatureOf(signed)
        )
    }

    /**
     * 官方向量 4：GET Bucket（List Objects），带两个查询参数。
     */
    @Test
    fun officialVector_listObjects() {
        val request = Request.Builder()
            .url("https://$HOST/?max-keys=2&prefix=J")
            .get()
            .header("Host", HOST)
            .build()

        val signed = S3Signer.sign(
            request = request,
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            region = REGION,
            payloadSha256 = S3Signer.EMPTY_SHA256,
            nowMillis = FIXED_TIME
        )

        assertEquals(
            "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            signatureOf(signed)
        )
    }

    // ───────── RFC 3986 编码器 ─────────

    /**
     * RFC 3986 编码器的行为约束。
     *
     * 这几条正是 `java.net.URLEncoder` 会做错的地方：空格编成 `+`、`*` 不编码、`~` 反而被编码。
     */
    @Test
    fun rfc3986Encoder_matchesUnreservedSet() {
        // unreserved：原样保留
        assertEquals("AZaz09-._~", S3Signer.rfc3986Encode("AZaz09-._~"))
        // 空格必须是 %20 而不是 +
        assertEquals("a%20b", S3Signer.rfc3986Encode("a b"))
        // URLEncoder 不编码 *，SigV4 要求编码
        assertEquals("%2A", S3Signer.rfc3986Encode("*"))
        // `/` 在本函数里也要编码（调用方自己按段拆分后再拼 `/`）
        assertEquals("%2F", S3Signer.rfc3986Encode("/"))
        // 其它常见保留字符
        assertEquals("%24", S3Signer.rfc3986Encode("$"))
        assertEquals("%2B", S3Signer.rfc3986Encode("+"))
        assertEquals("%3D", S3Signer.rfc3986Encode("="))
        // 多字节 UTF-8：逐字节编码，十六进制大写
        assertEquals("%E4%B8%AD", S3Signer.rfc3986Encode("中"))
    }

    /**
     * key 里带空格和中文时，canonical URI 走的是同一套编码器。
     *
     * 没有官方向量可比，只能验两件事：① 签名能算出来且形如 64 位十六进制；
     * ② 同一个 key 无论以「原始字符」还是「预编码形式」传进 URL，签出来必须一致 ——
     * 否则说明 canonical URI 没有做「先解码再重编码」的归一。
     */
    @Test
    fun keyWithSpaceAndChinese_encodesConsistently() {
        fun sign(url: String): String {
            val request = Request.Builder().url(url).get().header("Host", HOST).build()
            return signatureOf(
                S3Signer.sign(
                    request, ACCESS_KEY, SECRET_KEY, REGION,
                    S3Signer.EMPTY_SHA256, FIXED_TIME
                )
            )
        }

        val fromRaw = sign("https://$HOST/photos/我的 照片.jpg")
        val fromEncoded = sign("https://$HOST/photos/%E6%88%91%E7%9A%84%20%E7%85%A7%E7%89%87.jpg")

        assertEquals(fromEncoded, fromRaw)
        assertTrue(fromRaw.matches(Regex("[0-9a-f]{64}")))
    }

    // ───────── 查询参数排序 ─────────

    /**
     * 查询参数必须按 key 排序后参与签名，所以 URL 里的书写顺序不能影响签名结果。
     */
    @Test
    fun queryParams_sortedBeforeSigning() {
        fun sign(url: String): String {
            val request = Request.Builder().url(url).get().header("Host", HOST).build()
            return signatureOf(
                S3Signer.sign(
                    request, ACCESS_KEY, SECRET_KEY, REGION,
                    S3Signer.EMPTY_SHA256, FIXED_TIME
                )
            )
        }

        // ListObjectsV2 的典型参数组合，三种书写顺序
        val a = sign("https://$HOST/?list-type=2&prefix=a/&delimiter=/")
        val b = sign("https://$HOST/?prefix=a/&delimiter=/&list-type=2")
        val c = sign("https://$HOST/?delimiter=/&list-type=2&prefix=a/")

        assertEquals(a, b)
        assertEquals(a, c)

        // 参数变了签名必须变（防止排序实现把参数整段丢掉也能"通过"上面三条）
        val different = sign("https://$HOST/?list-type=2&prefix=b/&delimiter=/")
        assertTrue(a != different)
    }

    /**
     * 时间戳必须按 UTC 格式化：签名里的 scope 日期与 x-amz-date 都取自同一个 instant。
     */
    @Test
    fun timestamp_formattedAsUtc() {
        val request = Request.Builder().url("https://$HOST/a.txt").get().build()
        val signed = S3Signer.sign(
            request, ACCESS_KEY, SECRET_KEY, REGION,
            S3Signer.EMPTY_SHA256,
            Instant.parse("2026-09-21T07:08:09Z").toEpochMilli()
        )
        assertEquals("20260921T070809Z", signed.header("x-amz-date"))
        assertTrue(signed.header("Authorization")!!.contains("/20260921/$REGION/s3/aws4_request"))
    }
}
