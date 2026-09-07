package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * [DeviceAuth] 单元测试 —— 严格设备独立性的验签核心，纯 JVM 无 Android 依赖。
 *
 * 覆盖两条客户端签名编码路径，这是最容易出错的地方：
 * - DER（Android Keystore 的 `SHA256withECDSA` 输出）
 * - raw r||s 64 字节（WebCrypto `ECDSA` 输出）
 */
class DeviceAuthTest {

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun spkiBase64(pub: java.security.PublicKey): String =
        Base64.getEncoder().encodeToString(pub.encoded)

    /** 模拟 Android 侧：DER 编码签名。 */
    private fun signDer(priv: java.security.PrivateKey, data: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(priv)
            update(data.toByteArray(Charsets.UTF_8))
            sign()
        }

    /** 模拟 WebCrypto 侧：把 DER 签名拆成 raw r||s（各 32 字节，左侧补零）。 */
    private fun derToRaw(der: ByteArray): ByteArray {
        // SEQUENCE(0x30) len INTEGER(0x02) rLen r INTEGER(0x02) sLen s
        var i = 2
        // 长度域可能是 0x81 开头的长格式，P-256 下不会出现，这里断言以免测试自身失真
        assertTrue("P-256 DER 签名长度域应为单字节", der[1].toInt() and 0x80 == 0)
        assertEquals(0x02, der[i].toInt())
        val rLen = der[i + 1].toInt()
        val r = der.copyOfRange(i + 2, i + 2 + rLen)
        i += 2 + rLen
        assertEquals(0x02, der[i].toInt())
        val sLen = der[i + 1].toInt()
        val s = der.copyOfRange(i + 2, i + 2 + sLen)

        fun pad32(v: ByteArray): ByteArray {
            val stripped = v.dropWhile { it == 0.toByte() }.toByteArray()
            require(stripped.size <= 32)
            return ByteArray(32 - stripped.size) + stripped
        }
        return pad32(r) + pad32(s)
    }

    private fun canonical() = DeviceAuth.canonicalString(
        method = "post",
        uri = "/api/network/mode?dry=1",
        timestampMs = "1756370000000",
        nonce = "test-nonce-0001"
    )

    @Test
    fun `canonical string uses uppercase method and newline separators`() {
        val s = canonical()
        val lines = s.split('\n')
        // 4 行：METHOD / URI / TS / NONCE。请求体不在签名内（原因见 DeviceAuth 类注释）。
        assertEquals(4, lines.size)
        assertEquals("POST", lines[0])
        assertEquals("/api/network/mode?dry=1", lines[1])
        assertEquals("1756370000000", lines[2])
        assertEquals("test-nonce-0001", lines[3])
    }


    @Test
    fun `der signature from keystore style signer verifies`() {
        val kp = newKeyPair()
        val data = canonical()
        val der = signDer(kp.private, data)
        val sig = Base64.getEncoder().encodeToString(der)
        assertTrue(DeviceAuth.verifySignature(spkiBase64(kp.public), data, sig))
    }

    @Test
    fun `raw r-s signature from webcrypto style signer verifies`() {
        val kp = newKeyPair()
        val data = canonical()
        val raw = derToRaw(signDer(kp.private, data))
        assertEquals(64, raw.size)
        // WebCrypto 侧一般用 base64url 传输，这里刻意走 base64url 分支
        val sig = DeviceAuth.base64UrlNoPad(raw)
        assertTrue(DeviceAuth.verifySignature(spkiBase64(kp.public), data, sig))
    }

    @Test
    fun `signature over different canonical string is rejected`() {
        val kp = newKeyPair()
        val der = signDer(kp.private, canonical())
        val sig = Base64.getEncoder().encodeToString(der)
        val tampered = DeviceAuth.canonicalString(
            "POST", "/api/network/mode?dry=2", "1756370000000", "test-nonce-0001"
        )
        assertFalse(DeviceAuth.verifySignature(spkiBase64(kp.public), tampered, sig))
    }

    @Test
    fun `signature from another device key is rejected`() {
        val victim = newKeyPair()
        val attacker = newKeyPair()
        val data = canonical()
        val sig = Base64.getEncoder().encodeToString(signDer(attacker.private, data))
        assertFalse(DeviceAuth.verifySignature(spkiBase64(victim.public), data, sig))
    }

    @Test
    fun `malformed signature and public key are rejected without throwing`() {
        val kp = newKeyPair()
        val data = canonical()
        assertFalse(DeviceAuth.verifySignature(spkiBase64(kp.public), data, "!!!not-base64!!!"))
        assertFalse(DeviceAuth.verifySignature(spkiBase64(kp.public), data, ""))
        assertFalse(DeviceAuth.verifySignature("not-a-key", data, "AAAA"))
    }

    @Test
    fun `fingerprint is stable per key and differs across keys`() {
        val a = newKeyPair()
        val b = newKeyPair()
        val fpA1 = DeviceAuth.fingerprintOf(spkiBase64(a.public))
        val fpA2 = DeviceAuth.fingerprintOf(spkiBase64(a.public))
        val fpB = DeviceAuth.fingerprintOf(spkiBase64(b.public))
        assertNotNull(fpA1)
        assertEquals(fpA1, fpA2)
        assertFalse(fpA1 == fpB)
        // base64url(SHA-256) 无填充 = 43 字符
        assertEquals(43, fpA1!!.length)
        assertFalse("指纹不能含 base64 标准字符集的 +/=", fpA1.any { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `fingerprint rejects garbage that is not an EC public key`() {
        // 若不校验可解析性，任意字节串都能算出"指纹"并占用配对配额
        assertNull(DeviceAuth.fingerprintOf(Base64.getEncoder().encodeToString(ByteArray(32) { 1 })))
        assertNull(DeviceAuth.fingerprintOf(""))
        assertNull(DeviceAuth.fingerprintOf("%%%"))
    }

    @Test
    fun `timestamp freshness honours drift window`() {
        val now = 1_756_370_000_000L
        assertTrue(DeviceAuth.isTimestampFresh(now.toString(), now))
        assertTrue(DeviceAuth.isTimestampFresh((now - DeviceAuth.MAX_TIMESTAMP_DRIFT_MS + 1).toString(), now))
        assertTrue(DeviceAuth.isTimestampFresh((now + DeviceAuth.MAX_TIMESTAMP_DRIFT_MS - 1).toString(), now))
        assertFalse(DeviceAuth.isTimestampFresh((now - DeviceAuth.MAX_TIMESTAMP_DRIFT_MS - 1).toString(), now))
        assertFalse(DeviceAuth.isTimestampFresh((now + DeviceAuth.MAX_TIMESTAMP_DRIFT_MS + 1).toString(), now))
        assertFalse(DeviceAuth.isTimestampFresh(null, now))
        assertFalse(DeviceAuth.isTimestampFresh("not-a-number", now))
    }

    @Test
    fun `nonce cache accepts once and rejects replay`() {
        val cache = DeviceAuth.NonceCache()
        assertTrue(cache.accept("n1"))
        assertFalse("同一 nonce 第二次必须拒绝，否则可重放", cache.accept("n1"))
        assertTrue(cache.accept("n2"))
        assertFalse(cache.accept(""))
    }

    @Test
    fun `nonce cache evicts expired entries instead of growing without bound`() {
        val cache = DeviceAuth.NonceCache(ttlMs = 1_000, maxEntries = 4)
        val t0 = 1_000_000L
        repeat(5) { assertTrue(cache.accept("old-$it", t0)) }
        // 超过 maxEntries 后下一次 accept 触发清理；此时全部旧项已过期
        assertTrue(cache.accept("fresh", t0 + 5_000))
        assertTrue("过期项应被清掉", cache.size() <= 4)
    }
}
