package com.ufi_axis_core.util

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份包的口令加密（PBKDF2-HMAC-SHA256 + AES-256-GCM）。
 *
 * ## 威胁模型
 *
 * 备份包会被用户拷到网盘、聊天软件、旧手机上，必须假定**攻击者拿到了整个文件、
 * 可以离线无限次尝试**。所以这里的重点不是"能解密"，而是把单次口令尝试的成本抬高，
 * 让弱口令之外的爆破在经济上不成立。
 *
 * ## 参数选择
 *
 * - **KDF**：PBKDF2-HMAC-SHA256。Android 平台自带，不引入新依赖；Argon2id 更好但需要
 *   额外 native 库，对一个跑在随身 WiFi 上的服务不值得。
 * - **轮数 [DEFAULT_ITERATIONS]**：60 万。仓库里配对密码用 12 万，那是每次登录都要算的
 *   在线校验、必须够快；备份包一辈子只算两次（导出、导入），成本该顶到用户能忍的上限。
 *   实测设备过慢时可下调，但**不得低于 [MIN_ITERATIONS]**（OWASP 对 PBKDF2-SHA256 的下限）。
 *   实际轮数与盐写进 manifest，所以将来调参不会让旧包解不开。
 * - **AES-256-GCM**：认证加密，密文被改一个 bit 就解不开，不需要额外 HMAC。
 * - **每次导出都用新盐 + 新 IV**：同一口令导出两次，密文完全不同；GCM 的 IV 复用会
 *   直接泄露明文异或值，所以 IV 绝不能固定或复用。
 *
 * ## 为什么要 AAD
 *
 * [encrypt] / [decrypt] 都要求传入 manifest 的规范化字节作为 AAD。效果是把密文与它的
 * manifest **绑死**：篡改 manifest 里的任何字段（比如把轮数改小、把 `encrypted` 改成
 * false、或者把这段密文搬到另一个包的 manifest 下）都会导致解密失败，而不是解出可疑内容。
 */
object BackupCrypto {

    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /** 默认 KDF 轮数，见类注释。 */
    const val DEFAULT_ITERATIONS = 600_000

    /** KDF 轮数下限：低于此值不接受，无论 manifest 里写了什么。 */
    const val MIN_ITERATIONS = 210_000

    /**
     * KDF 轮数上限。
     *
     * 下限防的是「攻击者把包里的轮数改成 1 让口令瞬间可爆破」，上限防的是反方向：
     * manifest 里的轮数来自**不可信的备份文件**，`import` / `preview` 时会被直接喂给
     * PBKDF2。没有上限的话，一个把 `kdf.iterations` 写成 `Int.MAX_VALUE` 的包
     * （或只是手滑多写两个零的正常包）就能让设备上的 core 单线程跑上几十分钟 ——
     * 一次请求把服务卡死，属于可被配对端触发的 DoS。
     *
     * 取值 500 万，是默认值 [DEFAULT_ITERATIONS] 的约 8.3 倍：给未来上调参数留足余量，
     * 同时把最坏情况钉在"用户能忍受的等待"量级。
     */
    const val MAX_ITERATIONS = 5_000_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /**
     * 口令最短长度。
     *
     * 12 位不是随手定的：配合 60 万轮 PBKDF2，一个 12 位随机字符口令的离线爆破成本
     * 已经超出个人攻击者的范围。低于这个长度时轮数再高也救不回来，所以直接拒绝而不是
     * 只给个"强度弱"的提示。
     */
    const val MIN_PASSPHRASE_LENGTH = 12

    /** 口令错误或密文被篡改。**刻意不区分这两种情况** —— 区分了就是给攻击者确认口令的信号。 */
    class InvalidPassphraseException : GeneralSecurityException("口令错误或备份文件已损坏")

    /** 加密结果。IV 需要与密文一起存进包里（IV 不是秘密，但必须原样保留）。 */
    class Sealed(val iv: ByteArray, val cipherText: ByteArray)

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun newIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * 校验口令是否可用于加密备份。
     *
     * @return 不合格时返回面向用户的原因，合格返回 null
     */
    fun validatePassphrase(passphrase: String): String? = when {
        passphrase.length < MIN_PASSPHRASE_LENGTH ->
            "口令至少需要 $MIN_PASSPHRASE_LENGTH 个字符"
        passphrase.isBlank() -> "口令不能为空白字符"
        else -> null
    }

    fun seal(
        plain: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        aad: ByteArray
    ): Sealed {
        val key = deriveKey(passphrase, salt, iterations)
        val iv = newIv()
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return Sealed(iv, cipher.doFinal(plain))
    }

    /**
     * @throws InvalidPassphraseException 口令错误、密文被改、或 [aad] 与加密时不一致
     */
    fun open(
        sealed: Sealed,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        aad: ByteArray
    ): ByteArray {
        require(iterations >= MIN_ITERATIONS) {
            // 防的是「攻击者把包里的轮数改成 1 让口令瞬间可爆破」。虽然 AAD 已经能拦住
            // 改 manifest 的行为，这里再挡一道：解密参数的下限不该由被解密的文件说了算。
            "KDF 轮数低于安全下限（$iterations < $MIN_ITERATIONS）"
        }
        require(iterations <= MAX_ITERATIONS) {
            // 反方向的上限：见 [MAX_ITERATIONS]。同样不接受文件说了算。
            "KDF 轮数超过上限（$iterations > $MAX_ITERATIONS）"
        }
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(sealed.cipherText)
        } catch (e: AEADBadTagException) {
            throw InvalidPassphraseException()
        } catch (e: javax.crypto.BadPaddingException) {
            // 部分实现在 tag 校验失败时抛的是父类，一并归到同一个出口
            throw InvalidPassphraseException()
        }
    }

    /**
     * 派生 256 位密钥。
     *
     * 用完清零 [PBEKeySpec] 内部的口令副本 —— JVM 上做不到彻底（String 常量池、GC 复制），
     * 但把能控制的那一份抹掉仍然减少了口令停留在堆里的窗口。调用方也应在用完后
     * 对自己的 `CharArray` 做 `fill('\u0000')`。
     */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
