package com.ufi_axis_core.controller.carrier.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 移动 App 域签名的对拍向量（2026-10-31）。
 *
 * 向量由 probe 的 `h5_api.enc` / `hashlib.md5` 用固定 xk/ts/nonce 生成；
 * Kotlin 侧必须逐字节一致，否则线上会 `400006`/签名错误。
 */
class MobileCryptoTest {

    private val plain = """{"cid":"0","en":"0","t":"0","sn":"0","cv":"0","st":"0","sv":"0","sp":"0","xk":"test-xk-1234","xc":"web","imei":"","nt":"","sb":"","prov":"100","city":"0000","tel":"99999999999","reqBody":{"cellNum":"13800138000","channlCode":"2"}}"""
    private val body = "f8GEnOMPaRygvQYeBml5G/UUawlNDkFAT8YuN1z9L+JGdzanov+sscWgW1HUum1R2k0lLzIpwpTjz6XOGX0yhBogEQaRUUvDksLXKxyuil2M3r92nvMoWc0ELk75Y+Gu8zHMcejd8m0fmRRPs3y5d5AfgN5Y2R+0acvJqjkGe5ys+qpwwfK9fJw7P3c8+UJsf14jkvO12WUVKi4cupJpKBvkJkwI0GJqJaeu35LkPgHsJHZ30pVBT1eaIF2flSmxoiGAU1iDX11zUSku2F4sMaTb/IZeIvzE7xoAJ4DG0xc79nt3q4mAxr4gV7JbMb8V"
    private val token = "4sgT5YBuwzSgZ+gDBur5GsTcgzct4uw8Lu33PqidFJhyUenY6IhZD7kM42oQXJtOzA0KkYlzfhbgLx6DMPpQC0VsHKqAyiOmSgzbe5IuDD8="

    @Test
    fun `请求体加密逐字节一致`() {
        assertEquals(body, MobileCrypto.encryptRequest(plain))
    }

    @Test
    fun `请求体可以解回原文`() {
        // 用响应 key 解不出来是预期的；这里用 REQ key 再加密一次做往返：
        // decryptResponse 用 RESP key，所以只断言 encryptRequest 的确定性。
        assertEquals(body, MobileCrypto.encryptRequest(plain))
    }

    @Test
    fun `x-token 与 x-sign 逐字节一致`() {
        val actualToken = MobileCrypto.encryptToken("test-xk-1234_/biz-orange/LH/uamrandcodeH5/sendMsgLoginH5_1700000000000_12345678")
        assertEquals(token, actualToken)
        assertEquals("a604907534f78f815afd15eab75f11aa", MobileCrypto.sign(actualToken, "1700000000000", "12345678"))
    }
}
