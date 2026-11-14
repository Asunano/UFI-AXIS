package com.ufi_axis_core.controller.carrier.unicom

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 联通 App 登录的加密参数（2026-11-07，从 probe 的 `unicom_api.py` + `minicrypto.py` 移植）。
 *
 * 两条规则都不能改，否则服务端只会回"密码错误"：
 * 1. 明文 = **值 + 6 位随机数字**，再 PKCS#1 v1.5 加密、base64（不再 urlencode）；
 * 2. `appId` 是 159 位模板串，其中 7 个 `{d}` 换成随机数字 —— 每次请求都重新生成。
 *
 * 单独拎出来是为了能测：`encryptWith` 收任意公钥，单测用自己生成的 1024 位钥匙
 * 加密再解密，验证"值 + 6 位数字"这条拼接和填充没错。
 */
internal object UnicomLoginCrypto {

    /** 联通 App 登录用的 1024 位公钥（SPKI base64，与 probe 同一把）。 */
    internal const val LOGIN_PUBKEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDc+CZK9bBA9IU+gZUOc6FUGu7yO9WpTNB0PzmgFBh96Mg1WrovD1oqZ+eIF4LjvxKXGOdI79JRdve9NPhQo07+uqGQgE4imwNnRx7PFtCRryiIEcUoavuNtuRVoBAm6qdB0SrctgaqGfLgKvZHOnwTjyNqjBUxzMeQlEC2czEMSwIDAQAB"

    private const val APPID_TEMPLATE =
        "{d}f{d}af{d}{d}ad{d}912d306b5053abf90c7ebbb695887bc" +
            "870ae0706d573c348539c26c5c0a878641fcc0d3e90acb9be1e6ef858a" +
            "59af546f3c826988332376b7d18c8ea2398ee3a9c3db947e2471d32a49{d}{d}"

    private val random = SecureRandom()

    fun encrypt(value: String): String = encryptWith(LOGIN_PUBKEY, value)

    /** 供单测用：可指定公钥。 */
    internal fun encryptWith(spkiBase64: String, value: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val plaintext = (value + randomDigits(6)).toByteArray()
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }

    fun appId(): String {
        var out = APPID_TEMPLATE
        while (out.contains("{d}")) out = out.replaceFirst("{d}", random.nextInt(10).toString())
        return out
    }

    const val APPID_LENGTH = 159

    private fun randomDigits(n: Int): String =
        buildString { repeat(n) { append(random.nextInt(10)) } }
}
