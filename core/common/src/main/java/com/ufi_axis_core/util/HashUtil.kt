package com.ufi_axis_core.util

import java.security.MessageDigest

/**
 * 哈希工具：设备密码哈希与常量时间比较。
 *
 * 原先这里还有 HMAC-SHA256 的请求签名（`hmacSha256` / `generateSignature` /
 * `verifySignature`），随「每设备非对称签名」上线一并删除：HMAC 需要双方共享
 * 同一个 secret，配对设备越多副本越多，与设备独立性目标冲突。
 * 请求签名现在在 [DeviceAuth] 里用 ECDSA P-256 完成。
 */
object HashUtil {

    /**
     * 计算 SHA-256 哈希
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.toHex()
    }

    /**
     * 常量时间字符串比较，防止时序攻击
     * 2026-08-11：由 private 放开为 internal，供 AppSettings.verifyDevicePassword 复用（设备密码哈希比较）。
     */
    internal fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
