package com.ufi_axis_core.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 哈希与签名工具
 * 用于 API 认证（HMAC-SHA256 签名）和 Token 哈希
 */
object HashUtil {

    /**
     * 计算 HMAC-SHA256 签名
     * 用于 API 请求签名: HMAC-SHA256(token + timestamp + body, secret)
     */
    fun hmacSha256(data: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.toHex()
    }

    /**
     * 计算 SHA-256 哈希
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.toHex()
    }

    /**
     * 生成 API 签名
     * 签名格式: HMAC-SHA256(token + timestamp + body, secret)
     */
    fun generateSignature(token: String, timestamp: String, body: String, secret: String): String {
        val payload = token + timestamp + body
        return hmacSha256(payload, secret)
    }

    /**
     * 验证 API 签名
     */
    fun verifySignature(
        token: String,
        timestamp: String,
        body: String,
        signature: String,
        secret: String
    ): Boolean {
        val expected = generateSignature(token, timestamp, body, secret)
        return constantTimeEquals(expected, signature)
    }

    /**
     * 常量时间字符串比较，防止时序攻击
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
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
