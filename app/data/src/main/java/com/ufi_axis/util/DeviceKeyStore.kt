package com.ufi_axis.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 设备身份密钥（严格设备独立性 / App 侧）。
 *
 * 私钥生成在 **Android Keystore** 里，`setUserAuthenticationRequired(false)`、不可导出：
 * 应用代码只能"请 Keystore 用它签名"，拿不到私钥字节。这一点是整套设计的基石——
 * 设备身份不再是一个可复制的字符串（旧实现是 ANDROID_ID 派生的 `deviceHwid`，
 * 任何拿到设备的人都能读出来并在别处冒充），而是一个**无法搬走**的能力。
 *
 * 与 core 的约定见 `com.ufi_axis_core.util.DeviceAuth`：
 * - 公钥上报 X.509 SPKI DER 的 base64；
 * - 指纹 = `base64url(SHA-256(SPKI))`，无填充（服务端会自己算一遍，客户端这份仅用于本地展示）；
 * - 签名算法 `SHA256withECDSA`，Keystore 输出 **DER**，core 会归一化，无需客户端转 raw。
 *
 * ## 清除应用数据 / 卸载重装
 * Keystore 条目随应用数据一起被清除 → 生成新密钥 → **新指纹 = 新设备**，需要重新配对并占一个
 * 配对槽位。这是刻意的：既然身份必须不可伪造，就不可能同时做到"清数据后还认得出是同一台"
 * （那需要一个可复制的稳定字符串，而可复制就等于可伪造）。`deviceHwid` 仍然上报，
 * 但只用于服务端合并重复记录这种便利功能，不参与任何安全判定。
 *
 * ## StrongBox
 * 优先请求 StrongBox（独立安全芯片）；设备不支持时 Keystore 会抛
 * `StrongBoxUnavailableException`，回退到 TEE。两者对本协议等价，只是硬件隔离强度不同。
 */
object DeviceKeyStore {

    private const val TAG = "DeviceKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ufi-axis-device-identity"
    private const val SIGN_ALGORITHM = "SHA256withECDSA"

    private val lock = Any()

    /** 公钥（SPKI DER 的 base64，标准字母表带填充）。首次调用会生成密钥对。 */
    fun publicKeySpki(): String = synchronized(lock) {
        val entry = loadOrCreate()
        Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * 本机设备指纹（= 服务端会算出的同一个值），仅用于本地展示/对比。
     * **鉴权与配额判定一律以服务端计算结果为准**，客户端这份不上报。
     */
    fun fingerprint(): String = synchronized(lock) {
        val der = loadOrCreate().certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * 用设备私钥签名，返回 base64（标准字母表）。
     *
     * @param canonical 规范签名串，必须由 [canonicalString] 拼出
     * @return 签名 base64；Keystore 异常（如密钥被系统失效）时返回 null，调用方按"无法签名"处理
     */
    fun sign(canonical: String): String? = synchronized(lock) {
        runCatching {
            val privateKey = loadOrCreate().privateKey as PrivateKey
            val signature = Signature.getInstance(SIGN_ALGORITHM).apply {
                initSign(privateKey)
                update(canonical.toByteArray(Charsets.UTF_8))
            }.sign()
            Base64.encodeToString(signature, Base64.NO_WRAP)
        }.onFailure { DebugLog.w(TAG, "签名失败: ${it.message}") }.getOrNull()
    }

    /**
     * 规范签名串，必须与 core 的 `DeviceAuth.canonicalString` **逐字节一致**：
     * `METHOD \n URI \n TIMESTAMP_MS \n NONCE`
     *
     * @param uri 服务端可见的 path + query（含原始百分号编码），不含 scheme/host
     */
    fun canonicalString(method: String, uri: String, timestampMs: String, nonce: String): String =
        "${method.uppercase()}\n$uri\n$timestampMs\n$nonce"

    /** 生成 16 字节随机 nonce 的 base64url（无填充）。 */
    fun newNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * 删除密钥（解除配对时调用）。
     * 下次使用会生成全新密钥 = 全新设备身份，旧配对记录彻底作废。
     */
    fun reset() {
        synchronized(lock) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
                DebugLog.i(TAG, "设备身份密钥已删除")
            }.onFailure { DebugLog.w(TAG, "删除设备身份密钥失败: ${it.message}") }
        }
    }

    private fun loadOrCreate(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        generate(strongBox = true)
        return keyStore.let {
            it.load(null)
            it.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // 明确不要求用户认证：后台服务与通知调度都要能签名，不能依赖屏幕解锁
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
            DebugLog.i(TAG, "已生成设备身份密钥（StrongBox=$strongBox）")
        } catch (e: Exception) {
            if (strongBox) {
                // StrongBoxUnavailableException 及其在部分 ROM 上的变体：回退 TEE
                DebugLog.i(TAG, "StrongBox 不可用，回退 TEE: ${e.message}")
                generate(strongBox = false)
            } else {
                throw e
            }
        }
    }
}
