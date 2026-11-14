package com.ufi_axis_core.controller.carrier.mobile;

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 移动 App 域 H5 的请求/响应加密与签名（2026-10-31，从 probe 的 `h5_api.py` / `app_login.py` 移植）。
 *
 * 算法（线上 bundle 还原，已与抓包逐字节对拍）：
 * - 请求体 = Base64(AES-128-CBC/PKCS7(json_envelope, REQ_KEY, IV))
 * - 响应体 = Base64(AES-128-CBC/PKCS7(body, RESP_KEY, IV))
 * - x-token = Base64(AES-128-CBC(xk_path_ts_nonce, REQ_KEY, IV))
 * - x-sign  = MD5(token_ts_nonce_null) 的小写十六进制
 *
 * 三个 key 是**前端常量**，运营商改版就会变；集中在这里，失败时返回上游错误，不要静默。
 */
object MobileCrypto {
    private val REQ_KEY = "foorettD7vcBawt3".toByteArray(Charsets.UTF_8)
    private val RESP_KEY = "UVic06tpXgMNiApm".toByteArray(Charsets.UTF_8)
    private val IV = "9791027341711819".toByteArray(Charsets.UTF_8)

    fun encryptRequest(plain: String): String = encrypt(plain, REQ_KEY)

    fun decryptResponse(base64: String): String = decrypt(base64, RESP_KEY)

    /** x-token 与请求体同一套 key/iv。 */
    fun encryptToken(material: String): String = encrypt(material, REQ_KEY)

    fun sign(token: String, timestamp: String, nonce: String, suffix: String = "null"): String =
        md5Hex("${token}_${timestamp}_${nonce}_$suffix")

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(plain: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(base64: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        val plain = cipher.doFinal(Base64.getDecoder().decode(base64))
        return String(plain, Charsets.UTF_8)
    }
}
