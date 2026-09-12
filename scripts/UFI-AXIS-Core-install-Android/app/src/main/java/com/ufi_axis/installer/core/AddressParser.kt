package com.ufi_axis.installer.core

/**
 * 设备地址解析。对齐 bat 脚本的行为：
 * - 默认 `192.168.0.1`
 * - 未带端口则补 `:5555`
 * - 另外抽出一个「纯 IP」，供健康检查 `http://<ip>:8088/health` 使用
 */
object AddressParser {

    const val DEFAULT_IP = "192.168.0.1"
    const val DEFAULT_ADB_PORT = 5555
    const val DEFAULT_HEALTH_PORT = 8088

    data class Parsed(
        /** 形如 192.168.0.1:5555，用于 adb connect */
        val hostPort: String,
        /** 纯主机名/IP，用于健康检查 */
        val host: String,
        val port: Int
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
     *
     * 注意：bat 脚本里 `!ADDR:"=!` 是去掉引号，这里也做等价处理。
     */
    fun parse(input: String?): Parsed {
        val trimmed = (input ?: "").trim().replace("\"", "").replace(" ", "")
        var raw = trimmed.ifEmpty { DEFAULT_IP }

        // 去掉可能被用户粘进来的 adb 命令前缀
        raw = raw.removePrefix("adb").removePrefix("connect").trim()

        return if (raw.contains(':')) {
            val idx = raw.lastIndexOf(':')
            val host = raw.substring(0, idx)
            val portStr = raw.substring(idx + 1)
            val port = portStr.toIntOrNull() ?: DEFAULT_ADB_PORT
            Parsed(raw, host, port)
        } else {
            Parsed("$raw:$DEFAULT_ADB_PORT", raw, DEFAULT_ADB_PORT)
        }
    }
}
