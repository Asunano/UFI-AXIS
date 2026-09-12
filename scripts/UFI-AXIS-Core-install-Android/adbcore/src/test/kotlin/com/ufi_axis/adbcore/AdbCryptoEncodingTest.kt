package com.ufi_axis.adbcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * 公钥编码与签名正确性 —— 这是最容易出「差一个字节就静默失败」的地方。
 */
class AdbCryptoEncodingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val twoPow32: BigInteger = BigInteger.ONE.shiftLeft(32)


    private fun newKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `公钥二进制恰好 524 字节且字段布局正确`() {
        val pub = newKeyPair().public as RSAPublicKey
        val blob = AdbCrypto.encodePublicKeyBinary(pub)

        // 4 + 4 + 256 + 256 + 4
        assertEquals(524, blob.size)
        assertEquals(AdbCrypto.PUBLIC_KEY_BINARY_SIZE, blob.size)

        // modulus_size_words = 64
        assertEquals(64, AdbProtocol.getInt(blob, 0))

        // exponent 在最后 4 字节，应为 65537
        assertEquals(65537, AdbProtocol.getInt(blob, 520))
    }

    @Test
    fun `n0inv 满足 n0 乘 n0inv 同余于 -1 模 2的32次`() {
        val pub = newKeyPair().public as RSAPublicKey
        val blob = AdbCrypto.encodePublicKeyBinary(pub)

        val n0inv = BigInteger.valueOf(AdbProtocol.getInt(blob, 4).toLong() and 0xFFFFFFFFL)
        val n0 = pub.modulus.and(BigInteger.valueOf(0xFFFFFFFFL))

        val product = n0.multiply(n0inv).mod(twoPow32)
        val expected = twoPow32.subtract(BigInteger.ONE) // -1 mod 2^32

        assertEquals(
            "n0 * n0inv 应当 ≡ -1 (mod 2^32)",
            expected,
            product
        )
    }

    @Test
    fun `modulus 以小端 word 排列并与原值一致`() {
        val pub = newKeyPair().public as RSAPublicKey
        val blob = AdbCrypto.encodePublicKeyBinary(pub)

        // 逐 word 读出并拼回
        var rebuilt = BigInteger.ZERO
        for (i in 0 until 64) {
            val word = AdbProtocol.getInt(blob, 8 + i * 4).toLong() and 0xFFFFFFFFL
            rebuilt = rebuilt.or(BigInteger.valueOf(word).shiftLeft(32 * i))
        }
        assertEquals("modulus 重组后应等于原模数", pub.modulus, rebuilt)

        // 首 word 小端字节序
        val firstWord = pub.modulus.and(BigInteger.valueOf(0xFFFFFFFFL)).toLong()
        assertEquals(firstWord.toByte(), blob[8])
    }

    @Test
    fun `rr 等于 2的2乘32乘64次方模n`() {
        val pub = newKeyPair().public as RSAPublicKey
        val blob = AdbCrypto.encodePublicKeyBinary(pub)

        var rrRebuilt = BigInteger.ZERO
        for (i in 0 until 64) {
            val word = AdbProtocol.getInt(blob, 8 + 256 + i * 4).toLong() and 0xFFFFFFFFL
            rrRebuilt = rrRebuilt.or(BigInteger.valueOf(word).shiftLeft(32 * i))
        }

        val expectedRr = BigInteger.ONE.shiftLeft(2 * 32 * 64).mod(pub.modulus)
        assertEquals("rr 应为 2^(2*32*64) mod n", expectedRr, rrRebuilt)

        // rr 必须小于模数
        assertTrue(rrRebuilt < pub.modulus)
    }

    @Test
    fun `对照向量 —— 与 Python 参考实现逐字段一致`() {
        // 参考实现：p=nextprime(1.6*2^1023+12345)，q=nextprime(1.4*2^1023+67890)，n=p*q
        val n = BigInteger(
            "18097523399934163944883994036373293834687888729491390490743892021508799323437900326197826948882967715793598755861512512438233393915793468450112040972906099134313985304236351804317877609670044102333252820351682467056014077177342224920657776592003975789696805159202631951841482536844190156261588956409522509412220112070956507010875477436223021504149079777319551543585680567338402246512982973413679141599547494508793870828852856176616664859971468304361819842913781345589139351778214170299843344018880141363103526632764391512041332902837855519652257460192897594439946303224970427274277037724419850739323350234255139785173",
            10
        )
        assertEquals("对照模数应为 2048 bit", 2048, n.bitLength())

        val n0 = n.and(BigInteger.valueOf(0xFFFFFFFFL))
        val n0inv = n0.modInverse(twoPow32).negate().mod(twoPow32)
        assertEquals("n0inv 应与 Python 参考实现一致", 2483345539L, n0inv.toLong())

        val rr = BigInteger.ONE.shiftLeft(2 * 32 * 64).mod(n)
        assertEquals(
            "rr 应与 Python 参考实现一致",
            BigInteger(
                "3067776146736570925439076156636383191096055397862835722160842385224395539614768666717763175250208032646183079042677643977535991962501699408495603375003118787224173373909915225737497693828326609703915331898758300436976643971772185655986294238642164432589689447242633090832813852446424050139540379097706545341800888036459754025965454173350932871497151180043629612514043415690953367227114818069988831192248790563655031427465089352476826745142928781747265109257965279277941155237340982080766430277441814688274396901795875675644057204268300778566350294325059039806010311127010701981117496846498821768841396056205759318766",
                10
            ),
            rr
        )

        // 编码后必须满足全部结构约束
        val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(n, BigInteger.valueOf(65537))
        ) as RSAPublicKey
        val blob = AdbCrypto.encodePublicKeyBinary(pub)
        assertEquals(524, blob.size)
        assertEquals(64, AdbProtocol.getInt(blob, 0))
        assertEquals(2483345539L, AdbProtocol.getInt(blob, 4).toLong() and 0xFFFFFFFFL)
        assertEquals(65537, AdbProtocol.getInt(blob, 520))

        // modulus 小端重组后等于 n
        var rebuilt = BigInteger.ZERO
        for (i in 0 until 64) {
            val w = AdbProtocol.getInt(blob, 8 + i * 4).toLong() and 0xFFFFFFFFL
            rebuilt = rebuilt.or(BigInteger.valueOf(w).shiftLeft(32 * i))
        }
        assertEquals("modulus 重组应等于原模数", n, rebuilt)
    }

    @Test
    fun `公钥 payload 以空字符结尾且包含注释与 base64`() {
        val dir = tempFolder.newFolder("keys")
        val crypto = AdbCrypto(dir)
        val payload = crypto.publicKeyPayload()

        assertTrue("payload 必须以 \\0 结尾", payload.last() == 0.toByte())

        val text = String(payload, Charsets.US_ASCII)
        assertTrue("应包含注释串", text.contains(AdbCrypto.KEY_COMMENT))

        val b64Part = text.substringBefore(' ')
        val decoded = Base64.getDecoder().decode(b64Part)
        assertEquals("base64 解出的二进制应为 524 字节", 524, decoded.size)
    }

    @Test
    fun `签名长度为 256 字节且可被公钥验证`() {
        val dir = tempFolder.newFolder("keys2")
        val crypto = AdbCrypto(dir)
        val token = ByteArray(AdbProtocol.TOKEN_SIZE) { (it * 7).toByte() }

        val signature = crypto.signToken(token)
        assertEquals("2048 位密钥的签名长度应为 256", 256, signature.size)

        // 用标准 SHA1withRSA 验证，确认没有误用 SHA256
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(crypto.getOrCreateKeyPair().public)
        verifier.update(token)
        assertTrue("签名应能被 SHA1withRSA 验签通过", verifier.verify(signature))
    }

    @Test
    fun `签名算法确实是 SHA1 而非 SHA256`() {
        val dir = tempFolder.newFolder("keys3")
        val crypto = AdbCrypto(dir)
        val token = ByteArray(AdbProtocol.TOKEN_SIZE) { 1 }

        val actual = crypto.signToken(token)

        // 参考值：用同一密钥分别按 SHA1 与 SHA256 签名，应与 SHA1 结果一致
        val expectedSha1 = Signature.getInstance("SHA1withRSA").run {
            initSign(crypto.getOrCreateKeyPair().private)
            update(token)
            sign()
        }
        val sha256 = Signature.getInstance("SHA256withRSA").run {
            initSign(crypto.getOrCreateKeyPair().private)
            update(token)
            sign()
        }

        assertTrue(
            "签名必须与 SHA1withRSA 结果一致；若失败说明误用了 SHA256",
            actual.contentEquals(expectedSha1)
        )
        assertFalse(
            "签名不应等于 SHA256withRSA 的结果",
            actual.contentEquals(sha256)
        )
    }

    @Test
    fun `密钥持久化 —— 二次读取得到同一对密钥`() {
        val dir = tempFolder.newFolder("keys4")
        val crypto1 = AdbCrypto(dir)
        val pub1 = crypto1.publicKeyPayload()

        // 新的实例（模拟重启 App）应从磁盘读回同一密钥
        val crypto2 = AdbCrypto(dir)
        val pub2 = crypto2.publicKeyPayload()

        assertTrue("重启后公钥应保持不变，否则设备每次都要重新授权", pub1.contentEquals(pub2))
    }

    @Test
    fun `密钥文件损坏时自动重新生成而不崩溃`() {
        val dir = tempFolder.newFolder("keys5")
        java.io.File(dir, "adbkey").writeBytes(byteArrayOf(1, 2, 3, 4))

        val crypto = AdbCrypto(dir)
        val payload = crypto.publicKeyPayload() // 不应抛异常
        assertTrue(payload.isNotEmpty())
        assertEquals(524, crypto.encodePublicKeyBinary().size)
    }
}
