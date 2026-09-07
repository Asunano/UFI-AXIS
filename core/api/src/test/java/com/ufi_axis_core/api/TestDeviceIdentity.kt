package com.ufi_axis_core.api

import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceAuth
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * 测试用「客户端设备身份」：一对 P-256 密钥 + 配对 + 每请求签名。
 *
 * 严格设备独立性上线后，任何受保护端点的测试都必须像真实客户端一样
 * 走「挑战-验签配对 → 拿到该设备独占 token → 每请求带 X-Timestamp/X-Nonce/X-Signature」。
 * 把这套样板收敛到一处，避免每个测试各写一份签名逻辑（那正是两份实现漂移的开端）。
 *
 * 签名用 JDK 的 `SHA256withECDSA`（DER 输出），与 App 侧 Keystore 行为一致。
 */
class TestDeviceIdentity(val deviceName: String = "test-device") {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /** X.509 SPKI DER 的 base64，与客户端上报的 `device_pubkey` 同格式。 */
    val publicKeySpki: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** 服务端会据公钥算出的同一指纹。 */
    val fingerprint: String = requireNotNull(DeviceAuth.fingerprintOf(publicKeySpki))

    /** 配对成功后拿到的该设备独占 token（明文只在此处保留，用于构造请求头）。 */
    var token: String = ""
        private set

    /** 对任意字符串签名，输出 base64url（无填充）DER。 */
    fun sign(data: String): String {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data.toByteArray(Charsets.UTF_8))
            sign()
        }
        return DeviceAuth.base64UrlNoPad(der)
    }

    /**
     * 走完整配对流程（取挑战 → 签挑战 → confirm），并记住返回的 token。
     *
     * @return 配对结果，便于用例断言失败分支
     */
    fun pair(
        manager: PairingManager,
        settings: AppSettings,
        password: String,
        hwId: String? = null
    ): PairingManager.PairingConfirmResult {
        settings.enterPairingMode()
        val challenge = manager.issueChallenge()
        val result = manager.confirm(
            code = settings.pairingCode,
            pubKeySpki = publicKeySpki,
            challenge = challenge,
            signature = sign(challenge),
            password = password,
            deviceName = deviceName,
            ip = "127.0.0.1",
            hwId = hwId
        )
        if (result is PairingManager.PairingConfirmResult.Success) {
            token = result.token
        }
        return result
    }

    /**
     * 给请求打上鉴权 + 签名头。
     *
     * @param uri 必须与服务端看到的 `call.request.uri` 完全一致（含 query），否则验签必失败
     */
    fun authorize(builder: HttpRequestBuilder, method: String, uri: String) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "test-nonce-${nonceSeq.incrementAndGet()}"
        builder.header(HttpHeaders.Authorization, "Bearer $token")
        builder.header(AuthMiddleware.HEADER_TIMESTAMP, timestamp)
        builder.header(AuthMiddleware.HEADER_NONCE, nonce)
        builder.header(
            AuthMiddleware.HEADER_SIGNATURE,
            sign(DeviceAuth.canonicalString(method, uri, timestamp, nonce))
        )
    }

    private companion object {
        /** nonce 必须每请求唯一，否则会撞上服务端的重放拦截。 */
        val nonceSeq = AtomicLong(0)
    }
}
