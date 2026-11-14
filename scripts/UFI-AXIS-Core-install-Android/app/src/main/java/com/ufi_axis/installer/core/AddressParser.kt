package com.ufi_axis.installer.core

import com.ufi_axis.adbcore.AdbClient

/**
 * 设备地址解析。对齐 bat 脚本的行为：
 * - 默认 `192.168.0.1`
 * - 未带端口则补 `:5555`
 * - 另外抽出一个「纯 IP」，供健康检查 `http://<ip>:8088/health` 使用
 *
 * 容错是刻意保留的（与脚本一致），但每一次「替用户改了输入」都会通过
 * [Parsed.warning] 说明原因，由调用方写进日志，不做静默替换。
 */
object AddressParser {

    const val DEFAULT_IP = "192.168.0.1"
    const val DEFAULT_ADB_PORT = 5555

    /** 健康检查端口。与 [AdbClient.HEALTH_PORT] 同一个真源，避免两处 8088 漂移。 */
    const val DEFAULT_HEALTH_PORT = AdbClient.HEALTH_PORT

    /** 输入无法被安全解析（典型是裸 IPv6）。调用方应提示用户重填，不做静默兜底。 */
    class InvalidAddressException(message: String) : IllegalArgumentException(message)

    data class Parsed(
        /** 形如 192.168.0.1:5555，用于 adb connect */
        val hostPort: String,
        /** 纯主机名/IP，用于健康检查；IPv6 保留方括号，healthUrl 才是合法 URL */
        val host: String,
        val port: Int,
        /** 解析过程中做过的容错处理说明；null 表示输入被原样采用 */
        val warning: String? = null
    ) {
        /** 健康检查 URL */
        fun healthUrl(healthPort: Int = DEFAULT_HEALTH_PORT): String =
            "http://$host:$healthPort/health"
    }

    /**
     * 解析用户输入。
     *
     * 支持的形式：
     * - `192.168.0.1`            → 192.168.0.1:5555
     * - `192.168.0.1:6000`       → 原样保留端口
     * - `  192.168.0.1  `        → 去空格
     * - `[::1]:5555`             → IPv6 必须带方括号
     *
     * 注意：bat 脚本里 `!ADDR:"=!` 是去掉引号，这里也做等价处理。
     *
     * @throws InvalidAddressException 裸 IPv6 等无法区分「地址冒号」与「端口分隔符」的输入
     */
    fun parse(input: String?): Parsed {
        val warnings = mutableListOf<String>()
        val original = (input ?: "").trim()
        val cleaned = original.replace("\"", "").replace(" ", "")
        if (cleaned != original) warnings += "已忽略输入中的引号与空格"

        var raw = cleaned
        if (raw.isEmpty()) {
            raw = DEFAULT_IP
            warnings += "未填写设备地址，按默认 $DEFAULT_IP 处理"
        }

        val beforePrefix = raw
        raw = raw.removePrefix("adb").removePrefix("connect").trim()
        if (raw != beforePrefix) warnings += "已剥离粘贴进来的 adb connect 前缀"
        if (raw.isEmpty()) {
            raw = DEFAULT_IP
            warnings += "剥离前缀后地址为空，按默认 $DEFAULT_IP 处理"
        }

        // 裸 IPv6 无法区分地址里的冒号和端口分隔符，判非法（与 RemoteAdbActivity.parseGoform 同判据）
        if (!raw.startsWith("[") && raw.count { it == ':' } > 1) {
            throw InvalidAddressException("IPv6 请写成 [::1]:$DEFAULT_ADB_PORT 形式")
        }

        val host: String
        val portStr: String?
        when {
            raw.startsWith("[") -> {
                val end = raw.indexOf(']')
                if (end < 0) throw InvalidAddressException("IPv6 缺少右方括号，请写成 [::1]:$DEFAULT_ADB_PORT 形式")
                // host 带着方括号，healthUrl 拼出来才是合法的 http://[::1]:8088/health
                host = raw.substring(0, end + 1)
                val rest = raw.substring(end + 1)
                portStr = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> throw InvalidAddressException("方括号后只能接「:端口」，请写成 [::1]:$DEFAULT_ADB_PORT 形式")
                }
            }
            raw.contains(':') -> {
                host = raw.substringBefore(':')
                portStr = raw.substringAfter(':')
            }
            else -> {
                host = raw
                portStr = null
            }
        }

        val parsedPort = portStr?.toIntOrNull()
        val port = when {
            portStr == null -> DEFAULT_ADB_PORT
            parsedPort == null -> {
                warnings += "端口「$portStr」不是数字，已按默认端口 $DEFAULT_ADB_PORT 连接"
                DEFAULT_ADB_PORT
            }
            parsedPort !in 1..65535 -> {
                warnings += "端口 $parsedPort 超出 1~65535，已按默认端口 $DEFAULT_ADB_PORT 连接"
                DEFAULT_ADB_PORT
            }
            else -> parsedPort
        }
        if (host.isBlank() || host == "[]") warnings += "地址中没有主机名，连接大概率会失败"
        // hostPort 按实际连接的 host:port 重建：端口回落时不能再回显原文，否则失败信息
        // 和真正探测的端口对不上；原始输入已经进了 warning
        return Parsed("$host:$port", host, port, warnings.joinToString("；").ifEmpty { null })
    }
}
