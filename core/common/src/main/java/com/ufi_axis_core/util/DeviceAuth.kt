package com.ufi_axis_core.util

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备身份与请求签名的**唯一权威实现**（严格设备独立性 / 方案 Y）。
 *
 * 三端必须逐字节一致，任何改动都要同步：
 * - core：本文件（验签方）
 * - app ：`com.ufi_axis.util.DeviceKeyStore`（Android Keystore 签名）
 * - web ：`web/src/composables/deviceIdentity.ts`（WebCrypto 签名）
 *
 * ## 为什么是非对称而不是 HMAC
 * HMAC 的 secret 是可复制的字符串，配对设备越多副本越多，任一副本泄漏等于全部沦陷；
 * 且无法证明「请求确实来自那台设备」。ECDSA P-256 的私钥存在 Keystore / IndexedDB 的
 * 不可导出密钥里，JS 与应用代码都取不到，token 泄漏也无法伪造请求。
 *
 * ## 规范签名串
 * ```
 * METHOD \n URI \n TIMESTAMP_MS \n NONCE
 * ```
 * - `METHOD` 大写（GET / POST / ...）
 * - `URI` 是**服务端看到的 path + query**（Ktor `call.request.uri`），含原始百分号编码。
 *   把 query 纳入签名是必要的：`/api/files/download?path=...` 这类端点的语义全在 query 上。
 * - `TIMESTAMP_MS` 毫秒 Unix 时间戳的十进制字符串
 * - `NONCE` 客户端生成的随机串（建议 16 字节 base64url）。**时间戳窗口 + nonce 去重**共同
 *   构成防重放：仅有时间戳时，攻击者抓到一个已签名请求可在窗口内无限重放。
 *
 * ## 为什么请求体不在签名内（已知残余风险）
 * 覆盖 body 需要在鉴权拦截器里把请求体读出来算哈希，而 Ktor 默认不缓存请求体——
 * 要么装 `DoubleReceive` 把**每个** body 缓冲进内存，要么下游 `call.receive()` 直接失败。
 * 本服务跑在手机上且存在 `/api/files/upload`（200MB）、`/api/update/upload` 等流式上传路由，
 * 为了给 JSON 请求加一层完整性而把上传全量缓冲进内存是不可接受的代价。
 *
 * 签名在这里要保证的是「请求来自持有私钥的那台设备，且不是重放」，method+uri+ts+nonce
 * 已经足够。残余风险是**局域网内的主动 MITM 可以篡改在途请求体**——但同一攻击者本就能
 * 读到明文 HTTP 里的 Bearer token，威胁模型上并不因为签名覆盖 body 而实质改变。
 *
 * ## 签名编码：两端不同，core 归一化
 * Android Keystore 的 `SHA256withECDSA` 输出 **DER**（约 70-72 字节）；
 * WebCrypto 的 `ECDSA` 输出 **raw r||s**（固定 64 字节）。让两个客户端各自转换容易出错，
 * 所以统一由本文件判长度归一化：64 字节 → 按 raw 处理并包成 DER，其余按 DER 处理。
 *
 * ## 公钥编码
 * 两端统一上报 **X.509 SPKI DER 的 base64**（WebCrypto `exportKey('spki')`，
 * Android `PublicKey.encoded`）。设备指纹 = `base64url(SHA-256(SPKI))`，无填充。
 */
object DeviceAuth {

    /** 时间戳允许的漂移（毫秒）。同时也是 nonce 的最短保留时长。 */
    const val MAX_TIMESTAMP_DRIFT_MS: Long = 5 * 60 * 1000L

    /** ECDSA raw 签名长度（P-256：r 32 字节 + s 32 字节）。 */
    private const val RAW_ECDSA_P256_LEN = 64

    private const val TAG = "DeviceAuth"

    // ── 编码工具 ──────────────────────────────────────────────

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val mimeDecoder: Base64.Decoder = Base64.getMimeDecoder()

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { "%02x".format(it) }

    fun base64UrlNoPad(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    /**
     * 宽松 base64 解码：同时接受标准 base64（含 +/ 与 = 填充）与 base64url（-_ 无填充）。
     * 客户端实现差异（尤其手写的 base64url）容易在这里出岔子，解码端宽松可以少一类线上故障。
     */
    fun decodeBase64(value: String): ByteArray? = runCatching {
        val trimmed = value.trim()
        if (trimmed.contains('-') || trimmed.contains('_')) {
            // base64url：补齐填充后再解，Base64.getUrlDecoder 对缺失填充是宽容的
            urlDecoder.decode(trimmed)
        } else {
            mimeDecoder.decode(trimmed)
        }
    }.getOrNull()

    // ── 规范签名串 ────────────────────────────────────────────

    /**
     * 拼装规范签名串。**客户端必须用完全相同的拼法**，否则验签必失败。
     *
     * @param uri 服务端可见的 path + query（Ktor `call.request.uri`）
     */
    fun canonicalString(
        method: String,
        uri: String,
        timestampMs: String,
        nonce: String
    ): String = buildString {
        append(method.uppercase())
        append('\n')
        append(uri)
        append('\n')
        append(timestampMs)
        append('\n')
        append(nonce)
    }

    // ── 公钥 ──────────────────────────────────────────────────

    /** 解析 SPKI base64 公钥；格式非法返回 null（绝不抛，调用方按验签失败处理）。 */
    fun parsePublicKey(spkiBase64: String): PublicKey? = runCatching {
        val der = decodeBase64(spkiBase64) ?: return null
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }.getOrNull()

    /**
     * 设备指纹 = base64url(SHA-256(SPKI DER))，无填充，43 字符。
     *
     * 由**服务端**据上报公钥重新计算，绝不采信客户端自报的指纹字符串 —— 否则指纹与公钥
     * 可以不匹配，攻击者就能拿自己的密钥去冒充别人的指纹记录。
     *
     * @return 指纹；公钥非法返回 null
     */
    fun fingerprintOf(spkiBase64: String): String? {
        val der = decodeBase64(spkiBase64) ?: return null
        if (der.isEmpty()) return null
        // 必须真的能解析成 EC 公钥，否则任意字节串都能算出"指纹"占用配额
        if (parsePublicKey(spkiBase64) == null) return null
        return base64UrlNoPad(sha256(der))
    }

    // ── 验签 ──────────────────────────────────────────────────

    /**
     * 验证 ECDSA-SHA256 签名。
     *
     * @param signatureBase64 DER 或 raw(r||s, 64 字节) 编码的签名，base64 / base64url 均可
     * @return 仅在解析与验签全部成功时为 true；任何异常都归为 false（fail-secure）
     */
    fun verifySignature(publicKey: PublicKey, canonical: String, signatureBase64: String): Boolean {
        val raw = decodeBase64(signatureBase64) ?: return false
        val der = normalizeToDer(raw) ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(canonical.toByteArray(Charsets.UTF_8))
                verify(der)
            }
        }.getOrElse { false }
    }

    /** 便捷重载：直接给 SPKI base64。 */
    fun verifySignature(spkiBase64: String, canonical: String, signatureBase64: String): Boolean {
        val key = parsePublicKey(spkiBase64) ?: return false
        return verifySignature(key, canonical, signatureBase64)
    }

    /**
     * 把 WebCrypto 的 raw r||s 签名包成 DER；已是 DER 的原样返回。
     *
     * 判据是长度而非内容：P-256 的 raw 签名恒为 64 字节，而 DER 编码的 P-256 签名
     * 长度在 68-72 之间（最短也 >64，因为多了 SEQUENCE/INTEGER 头），不会撞车。
     */
    internal fun normalizeToDer(signature: ByteArray): ByteArray? {
        if (signature.isEmpty()) return null
        if (signature.size != RAW_ECDSA_P256_LEN) return signature
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val body = r.size + s.size
        // P-256 下 body 最大 72 < 128，长度域恒为单字节
        return ByteArrayOutputStream().apply {
            write(0x30)
            write(body)
            write(r)
            write(s)
        }.toByteArray()
    }

    /** DER INTEGER：去掉多余前导 0，最高位为 1 时补一个 0 字节（保持正数语义）。 */
    private fun derInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        var body = value.copyOfRange(start, value.size)
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return byteArrayOf(0x02, body.size.toByte()) + body
    }

    // ── 时间戳与 nonce ────────────────────────────────────────

    /** 时间戳是否在允许窗口内。非数字 / 超窗 → false。 */
    fun isTimestampFresh(timestampMs: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = timestampMs?.toLongOrNull() ?: return false
        return Math.abs(nowMs - ts) <= MAX_TIMESTAMP_DRIFT_MS
    }

    /**
     * Nonce 去重缓存：时间戳窗口内同一 nonce 只接受一次，配合窗口构成防重放。
     *
     * 用 `putIfAbsent` 做原子判定，避免并发请求下同一 nonce 被两次放行。
     * 容量上限防内存膨胀：超限时按插入时间清理过期项，仍超限则整体清空（宁可让少量请求
     * 重签，也不能无界增长）。
     */
    class NonceCache(
        private val ttlMs: Long = MAX_TIMESTAMP_DRIFT_MS * 2,
        private val maxEntries: Int = 20_000
    ) {
        private val seen = ConcurrentHashMap<String, Long>()

        /** @return true = 首次出现（放行）；false = 重复（拒绝） */
        fun accept(nonce: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (nonce.isBlank()) return false
            if (seen.size > maxEntries) evict(nowMs)
            val previous = seen.putIfAbsent(nonce, nowMs)
            if (previous == null) return true
            // 已存在但早已过期 → 视为过期条目未及时清理，仍按重复拒绝（保守）
            return false
        }

        private fun evict(nowMs: Long) {
            seen.entries.removeAll { nowMs - it.value > ttlMs }
            if (seen.size > maxEntries) {
                AppLogger.w(TAG, "nonce 缓存超限（${seen.size}），整体清空")
                seen.clear()
            }
        }

        fun size(): Int = seen.size

        fun clear() = seen.clear()
    }
}
