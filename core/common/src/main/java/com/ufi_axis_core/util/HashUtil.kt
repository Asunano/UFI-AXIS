package com.ufi_axis_core.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 哈希工具：配对密码派生与常量时间比较。
 * （调用方符号沿用 `devicePassword*` / `device_password_*`：那是持久化 key 的一部分，
 * 改名会让存量配置读不出来，所以只统一注释口径。）
 *
 * 原先这里还有 HMAC-SHA256 的请求签名（`hmacSha256` / `generateSignature` /
 * `verifySignature`），随「每设备非对称签名」上线一并删除：HMAC 需要双方共享
 * 同一个 secret，配对设备越多副本越多，与设备独立性目标冲突。
 * 请求签名现在在 [DeviceAuth] 里用 ECDSA P-256 完成。
 */
object HashUtil {

    /**
     * PBKDF2-HMAC-SHA256 迭代轮数。
     *
     * 只在设置/校验配对密码时各跑一次（配对确认、改密、删设备、解绑），
     * 不在任何热路径上；低端机上大约几百毫秒，可接受。
     * 在线爆破另有 `PasswordAttemptLimiter`（每来源 15min 5 次 + 全局节流）兜底，
     * 这里的轮数是给**离线**爆破加成本用的。
     */
    const val PBKDF2_ITERATIONS = 120_000

    /** salt 字节数。每次设置密码重新生成，与哈希一起落盘。 */
    const val SALT_BYTES = 16

    /**
     * 计算 SHA-256 哈希
     *
     * 注意：**不要**再用它派生密码。它是单轮无 salt 的，离线爆破速度是百亿量级/秒，
     * 且无 salt 可被彩虹表直接命中。留着是因为 v1 旧密码哈希需要它才能校验并就地升级
     * （见 [AppSettings.verifyDevicePassword]），以及 goform 协议自身要求的裸 sha256。
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.toHex()
    }

    /**
     * PBKDF2-HMAC-SHA256 派生（配对密码的当前算法，v2）。
     *
     * 用平台自带的 `SecretKeyFactory`，不需要引入任何依赖。
     * 派生完立刻 `clearPassword()` 抹掉 [PBEKeySpec] 内部的 char 数组副本，
     * 少留一份明文在堆上。
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    /** 生成随机 salt。 */
    fun randomSalt(bytes: Int = SALT_BYTES): ByteArray =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }

    /** hex → 字节。解析失败（长度为奇数或含非 hex 字符）返回空数组。 */
    internal fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty() || hex.length % 2 != 0) return ByteArray(0)
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            ByteArray(0)
        }
    }

    /**
     * 常量时间字符串比较，防止时序攻击
     * 2026-08-11：由 private 放开为 internal，供 AppSettings.verifyDevicePassword 复用（配对密码哈希比较）。
     *
     * 2026-09-08：原实现第一句是 `if (a.length != b.length) return false` —— 长度不同直接
     * 提前返回，等于把「长度是否相同」这一位信息通过响应时间漏出去；后面那个逐字符异或
     * 也只在长度相等时才真的是常量时间。现在先把两侧各自摘成**定长** 32 字节 SHA-256，
     * 再用 `MessageDigest.isEqual`（JDK 保证不提前返回）比较：
     * 比较耗时与输入长度、内容都无关，长度信息不再可观测。
     * 摘要只用于比较，不落盘、不进日志。
     */
    internal fun constantTimeEquals(a: String, b: String): Boolean {
        val digestA = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(Charsets.UTF_8))
        val digestB = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(digestA, digestB)
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
