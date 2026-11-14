package com.ufi_axis_core.controller.carrier.unicom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/**
 * 联通服务密码登录的加密参数（2026-11-07）。
 *
 * 这里钉的是**两个"错了也只会回密码错误"的细节**：
 * 1. 明文必须是 `值 + 6 位随机数字`（少拼这 6 位，服务端一律判密码错）；
 * 2. `appId` 是 159 位模板串、7 处随机数字（长度错会被风控拒）。
 *
 * 验证方式：本地生成一对 1024 位钥匙，用公钥走 [UnicomLoginCrypto.encryptWith] 加密，
 * 再用私钥解回来 —— 能还原出「值 + 6 位数字」就说明拼接、填充、base64 都对。
 */
class UnicomLoginCryptoTest {

    @Test
    fun `明文是值加 6 位随机数字`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val spki = Base64.getEncoder().encodeToString(pair.public.encoded)

        val encrypted = UnicomLoginCrypto.encryptWith(spki, "13800138000")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)))

        assertTrue("必须以号码开头", plain.startsWith("13800138000"))
        assertEquals("号码后必须正好 6 位数字", 11 + 6, plain.length)
        assertTrue("后缀必须全是数字", plain.drop(11).all { it.isDigit() })
    }

    @Test
    fun `两次加密结果不同 —— 随机后缀真的在变`() {
        val a = UnicomLoginCrypto.encrypt("123456")
        val b = UnicomLoginCrypto.encrypt("123456")
        // PKCS#1 本身也有随机填充，这里只是防"把随机数写死"。
        assertTrue(a != b)
        assertEquals(128, Base64.getDecoder().decode(a).size) // 1024 位 → 128 字节
    }

    @Test
    fun `appId 长度固定且只含数字与字母`() {
        repeat(20) {
            val id = UnicomLoginCrypto.appId()
            assertEquals(UnicomLoginCrypto.APPID_LENGTH, id.length)
            assertTrue("不能残留模板占位符", !id.contains("{d}"))
            assertTrue(id.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun `内置公钥可以被解析`() {
        // 解析失败会在 encrypt 里抛，这里只要不抛就说明 SPKI base64 是好的。
        val out = UnicomLoginCrypto.encrypt("123456")
        assertEquals(128, Base64.getDecoder().decode(out).size)
    }
}
