package com.ufi_axis.adbcore

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * ADB 认证密钥管理。
 *
 * 职责：
 * 1. 首次运行时生成 RSA-2048 密钥对并持久化（必须持久化，否则设备每次连接都会重新弹授权框）
 * 2. 对设备下发的 20 字节 token 做 SHA1withRSA 签名
 * 3. 把公钥重编码为 adb 特有的二进制格式（恰好 524 字节）并 base64
 *
 * 注意：不依赖任何 Android API，纯 JVM 可测。
 */
class AdbCrypto(private val keyDir: File, private val keyName: String = "adbkey") {

    private val privateKeyFile: File get() = File(keyDir, keyName)
    private val publicKeyFile: File get() = File(keyDir, "$keyName.pub")

    /** 懒加载的密钥对，避免重复读盘 */
    @Volatile
    private var cached: KeyPair? = null

    /**
     * 获取（或首次生成）密钥对。
     * 私钥以 PKCS#8 DER 落盘，公钥以 X.509 DER 落盘便于排障。
     */
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cached?.let { return it }
        val dir = keyDir
        if (!dir.exists()) dir.mkdirs()

        val kp = if (privateKeyFile.isFile && privateKeyFile.length() > 0) {
            try {
                loadKeyPair()
            } catch (e: Exception) {
                // 密钥文件损坏 → 删掉重新生成，避免永久卡死
                privateKeyFile.delete()
                publicKeyFile.delete()
                generateKeyPair()
            }
        } else {
            generateKeyPair()
        }
        cached = kp
        return kp
    }

    /** 当前使用的公钥（供日志展示指纹） */
    fun publicKey(): RSAPublicKey = getOrCreateKeyPair().public as RSAPublicKey

    /** 删除本地密钥（调试用；删除后设备会重新弹授权） */
    fun reset() {
        cached = null
        privateKeyFile.delete()
        publicKeyFile.delete()
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        privateKeyFile.writeBytes(kp.private.encoded)
        publicKeyFile.writeBytes(kp.public.encoded)
        return kp
    }

    private fun loadKeyPair(): KeyPair {
        val privBytes = privateKeyFile.readBytes()
        val factory = KeyFactory.getInstance("RSA")
        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privBytes)) as RSAPrivateKey
        // 公钥由私钥结构反推，避免依赖 .pub 文件（该文件可能被用户误删）
        val pub = derivePublicKey(factory, priv)
        return KeyPair(pub, priv)
    }

    /** 由 PKCS#8 私钥解出对应公钥（ADB 只关心 n/e，这里从私钥的 CRT 参数取模数） */
    private fun derivePublicKey(factory: KeyFactory, priv: RSAPrivateKey): RSAPublicKey {
        if (publicKeyFile.isFile && publicKeyFile.length() > 0) {
            try {
                return factory.generatePublic(
                    java.security.spec.X509EncodedKeySpec(publicKeyFile.readBytes())
                ) as RSAPublicKey
            } catch (_: Exception) {
                // 落到下面用自造公钥
            }
        }
        val keySpec = java.security.spec.RSAPublicKeySpec(priv.modulus, BigInteger.valueOf(65537))
        return factory.generatePublic(keySpec) as RSAPublicKey
    }

    /**
     * 用私钥对 token 签名。
     *
     * adb 官方（`adb_auth.cpp`）使用 **SHA1withRSA** 对 20 字节 token 直接签名。
     * 误用 SHA256withRSA 会导致设备静默拒绝（表现为又收到一次 AUTH TOKEN）。
     * 2048-bit 密钥的签名结果恒为 256 字节。
     */
    fun signToken(token: ByteArray): ByteArray {
        val priv = getOrCreateKeyPair().private
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(priv)
        sig.update(token)
        return sig.sign()
    }

    /**
     * 生成 AUTH(TYPE_RSAPUBLICKEY) 需要的 payload：
     * `base64(adbPublicKeyBinary) + " " + keyName + "\u0000"`
     *
     * 结尾的 `\0` 不能漏，否则设备解析失败。
     */
    fun publicKeyPayload(): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(encodePublicKeyBinary())
        val text = "$b64 $KEY_COMMENT\u0000"
        return text.toByteArray(Charsets.US_ASCII)
    }

    /** 只取二进制部分（单测用） */
    fun encodePublicKeyBinary(): ByteArray = encodePublicKeyBinary(publicKey())

    companion object {
        /** 写在公钥 payload 里的注释串，设备端会显示在授权弹窗的顶部 */
        const val KEY_COMMENT = "ufi-axis@installer"

        /** RSA-2048 → 2048 / 32 = 64 个 32-bit word */
        const val MODULUS_WORDS = 64

        /** 单个 word 字节数 */
        private const val WORD_BYTES = 4

        /** modulus / rr 各自的字节长度 */
        const val MODULUS_BYTES = MODULUS_WORDS * WORD_BYTES // 256

        /** 完整公钥二进制长度 = 4 + 4 + 256 + 256 + 4 */
        const val PUBLIC_KEY_BINARY_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4 // 524

        private val TWO_POW_32: BigInteger = BigInteger.ONE.shiftLeft(32)
        private val TWO_POW_2W: BigInteger = BigInteger.ONE.shiftLeft(2 * 32 * MODULUS_WORDS)

        /**
         * 把 JCA 公钥编码为 adb 需要的结构（全小端）：
         * ```
         * struct RSAPublicKey {
         *     uint32 modulus_size_words;   // 64
         *     uint32 n0inv;                // -1 / n[0] mod 2^32
         *     uint8  modulus[256];         // n，按 32-bit word 小端排列
         *     uint8  rr[256];              // 2^(2*32*64) mod n，同样排列
         *     uint32 exponent;             // e
         * };
         * ```
         */
        fun encodePublicKeyBinary(pub: RSAPublicKey): ByteArray {
            val modulus = pub.modulus
            val exponent = pub.publicExponent

            require(modulus.bitLength() == 2048) {
                "只支持 2048 位 RSA 公钥，当前为 ${modulus.bitLength()} 位"
            }

            // modulus 必须是正数且恰好 64 word
            val n = modulus
            val e = exponent

            // n0inv = -(n^-1) mod 2^32
            val n0 = n.and(BigInteger.valueOf(0xFFFFFFFFL))
            val n0inv = n0.modInverse(TWO_POW_32).negate().mod(TWO_POW_32)

            // rr = 2^(2 * 32 * words) mod n
            val rr = TWO_POW_2W.mod(n)

            val out = ByteArray(PUBLIC_KEY_BINARY_SIZE)
            var p = 0

            putLE32(out, p, MODULUS_WORDS.toLong()); p += 4
            putLE32(out, p, n0inv.toLong()); p += 4

            writeWordsLE(out, p, n, MODULUS_BYTES); p += MODULUS_BYTES
            writeWordsLE(out, p, rr, MODULUS_BYTES); p += MODULUS_BYTES

            putLE32(out, p, e.toLong()); p += 4

            return out
        }

        /**
         * 按 32-bit word 小端写出 BigInteger 的低 [byteCount] 字节。
         *
         * 不能直接 reverse(BigInteger.toByteArray())：toByteArray 会带符号位补 0，
         * 长度不固定，reverse 后整体偏移就错了。这里逐 word 从低位取。
         */
        private fun writeWordsLE(dst: ByteArray, offset: Int, value: BigInteger, byteCount: Int) {
            val mask = BigInteger.valueOf(0xFFFFFFFFL)
            var cur = value
            var p = offset
            val words = byteCount / WORD_BYTES
            for (i in 0 until words) {
                val word = cur.and(mask).toLong()
                putLE32(dst, p, word)
                p += WORD_BYTES
                cur = cur.shiftRight(32)
            }
        }

        private fun putLE32(dst: ByteArray, offset: Int, value: Long) {
            dst[offset] = (value and 0xFF).toByte()
            dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
