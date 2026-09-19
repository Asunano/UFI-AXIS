package com.ufi_axis.installer.goform

import java.nio.charset.Charset

/**
 * Goform 响应体编解码 helper（纯函数，移植自 core/goform 的 [GoformCodec]）。
 *
 * ZTE modem 返回 GBK 编码的中文（如 network_provider），但 HTTP 响应头未声明 charset。
 * JSON 结构本身是纯 ASCII，所以任何非 ASCII 字节都是中文字符的编码。
 * 策略：UTF-8 往返验证 — 解码后重新编码，字节不一致则说明不是合法 UTF-8，回退 GBK。
 */
object GoformCodec {

    fun decodeBody(rawBytes: ByteArray): String {
        if (rawBytes.none { it < 0 }) {
            return String(rawBytes, Charsets.US_ASCII)
        }
        val utf8 = String(rawBytes, Charsets.UTF_8)
        val isValidUtf8 = rawBytes.contentEquals(utf8.toByteArray(Charsets.UTF_8))
        if (isValidUtf8) {
            return utf8
        }
        GoformLog.w("GoformCodec", "Non-UTF-8 response detected, falling back to GBK")
        return try {
            String(rawBytes, Charset.forName("GBK"))
        } catch (_: Exception) {
            utf8
        }
    }

    /**
     * 拼 `goform_set_cmd_process` 的表单体（application/x-www-form-urlencoded）。
     *
     * 值直接来自用户输入（WiFi SSID / 密码、APN 等），必须编码，否则 `&`/`=`/`+`
     * 会被设备侧表单解析器错读甚至造成参数注入。
     * 签名 `AD` 由 `sha256(sha256(wa+cr) + RD)` 得出，与参数值无关，编码不影响验签。
     * `isTest=false` 与 `AD=` 是固定值，不参与编码。
     */
    fun buildSetFormBody(params: Map<String, String>, ad: String): String = buildString {
        append("isTest=false")
        params.forEach { (k, v) ->
            if (k == "isTest") return@forEach
            append('&').append(formEncode(k)).append('=').append(formEncode(v))
        }
        append("&AD=").append(ad)
    }

    private fun formEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
