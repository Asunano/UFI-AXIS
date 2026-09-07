package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import java.nio.charset.Charset

/**
 * Goform 响应体编解码 helper（F25 最小安全抽取，从 [GoformClient] 抽出的纯函数）。
 *
 * ZTE modem 返回 GBK 编码的中文（如 network_provider），但 HTTP 响应头未声明 charset。
 * JSON 结构本身是纯 ASCII，所以任何非 ASCII 字节都是中文字符的编码。
 * 策略：UTF-8 往返验证 — 解码后重新编码，字节不一致则说明不是合法 UTF-8，回退 GBK。
 *
 * 纯函数、无状态、不依赖 [GoformClient] 实例，便于单测与复用。
 */
object GoformCodec {

    /**
     * 从原始字节解码为字符串，自动处理 GBK/UTF-8 编码。
     * 与 [GoformClient.bodyAsTextWithGbkFallback] 逻辑一致，但接受 ByteArray 参数，
     * 用于诊断场景（先 readBytes 记录字节数，再解码）。
     */
    fun decodeBody(rawBytes: ByteArray): String {
        // 快速判断：无非 ASCII 字节则直接返回（绝大多数 JSON 响应走这条路）
        if (rawBytes.none { it < 0 }) {
            return String(rawBytes, Charsets.US_ASCII)
        }
        // UTF-8 往返验证：解码后重新编码，字节一致 = 合法 UTF-8
        val utf8 = String(rawBytes, Charsets.UTF_8)
        val isValidUtf8 = rawBytes.contentEquals(utf8.toByteArray(Charsets.UTF_8))
        if (isValidUtf8) {
            return utf8
        }
        // 非 UTF-8 → 回退 GBK
        AppLogger.w("GoformCodec", "Non-UTF-8 response detected, falling back to GBK")
        return try {
            String(rawBytes, Charset.forName("GBK"))
        } catch (_: Exception) {
            utf8
        }
    }

    /**
     * 拼 `goform_set_cmd_process` 的表单体（`application/x-www-form-urlencoded`）。
     *
     * ## 为什么必须编码（计划书 4.7）
     *
     * 这里的值直接来自用户输入（WiFi SSID / 密码、APN、短信内容）。不编码时：
     * - 值里的 `&` 会把后面的内容变成**新参数**（`SSID=a&goformId=REBOOT_DEVICE` 就是一次注入）；
     * - 值里的 `=` 与 `+` 会被设备侧的表单解析器错读（`+` 解成空格 —— base64 后的密码里
     *   很容易出现 `+`，这正是"密码设了但连不上"的成因）；
     * - 中文 SSID 依赖设备猜字符集。
     *
     * ## 为什么改这里是安全的
     *
     * 签名 `AD` 由 `sha256(sha256(wa+cr) + RD)` 得出，**与参数值无关**
     * （见 `GoformClient.computeAd`），所以编码前/编码后签名都一样，不会导致验签失败。
     *
     * `isTest=false` 与 `AD=` 是固定值，不参与编码。
     */
    fun buildSetFormBody(params: Map<String, String>, ad: String): String = buildString {
        append("isTest=false")
        params.forEach { (k, v) ->
            // 调用方历史上会自带一个 isTest=false；去重避免设备侧看到两份
            if (k == "isTest") return@forEach
            append('&').append(formEncode(k)).append('=').append(formEncode(v))
        }
        append("&AD=").append(ad)
    }

    /**
     * `application/x-www-form-urlencoded` 的值编码。
     *
     * 用 UTF-8：设备的官方 Web UI 也是 UTF-8 页面，表单默认按页面字符集编码。
     * （响应侧的 GBK 回退只影响读，见 [decodeBody]。）
     */
    private fun formEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
