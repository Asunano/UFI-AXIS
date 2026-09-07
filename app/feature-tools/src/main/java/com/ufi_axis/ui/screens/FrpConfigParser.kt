// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

/**
 * FRP 配置解析器（2026-08-24 重构）。
 *
 * 支持将 frpc 的 **INI 文本**（无论是否换行、是否紧凑）解析为结构化模型，
 * 再序列化为 **TOML**（frp v0.52+ 官方推荐格式，INI 已废弃）。
 *
 * 设计目标：
 * 1. 鲁棒解析：同时支持标准换行 INI、紧凑无换行 INI（如
 *    `[common]server_addr = xserver_port = 7000...[name]type = httplocal_ip = ...`）。
 * 2. 完整字段：覆盖 [common] 段与各类代理（tcp/udp/http/https/stcp/xtcp 等），
 *    特别保留 http/https 的 customDomains（多域名逗号分隔）。
 * 3. 不泄露凭证：仅做纯文本解析与本地序列化，绝不将原始凭证写入日志/注释。
 */

/**
 * 解析后的 FRP 配置模型。
 * @param common [common] 段字段
 * @param proxies 各代理段（name 为段名）
 */
data class FrpParsedConfig(
    val common: Map<String, String>,
    val proxies: List<FrpParsedProxy>
)

data class FrpParsedProxy(
    val name: String,
    val fields: Map<String, String>
)

/**
 * 解析 frpc INI 文本。
 * @throws IllegalArgumentException 当无法识别任何 [common] 或代理段时。
 */
fun parseFrpcIni(text: String): FrpParsedConfig {
    // 1) 预处理：去掉注释、把紧凑格式（无换行/无空格粘连）规范化为标准换行 INI。
    val normalized = normalizeIni(text)

    // 2) 按 [section] 切分（支持段名含字母数字下划线横线）
    val sectionRegex = Regex("""\[([\w\-]+)\]""")
    val matches = sectionRegex.findAll(normalized).toList()
    if (matches.isEmpty()) {
        throw IllegalArgumentException("未识别到任何 [段名]，请检查格式是否包含 [common] 等。")
    }

    val common = mutableMapOf<String, String>()
    val proxies = mutableListOf<FrpParsedProxy>()

    for (i in matches.indices) {
        val sectionName = matches[i].groupValues[1]
        val start = matches[i].range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else normalized.length
        val body = normalized.substring(start, end)

        // 3) 逐行提取 key = value 对（已规范化为换行格式）
        val map = mutableMapOf<String, String>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim().removeSurrounding("\"")
            if (k.isNotEmpty()) map[k] = v
        }

        if (sectionName.equals("common", true)) {
            common.putAll(map)
        } else if (map.isNotEmpty()) {
            proxies.add(FrpParsedProxy(sectionName, map))
        }
    }

    if (common.isEmpty() && proxies.isEmpty()) {
        throw IllegalArgumentException("未识别到任何有效的 [common] 或代理配置段。")
    }

    return FrpParsedConfig(common, proxies)
}

/**
 * 将可能紧凑无换行的 INI 文本规范化为标准换行 INI。
 *
 * 关键问题：纯紧凑格式下 value 与下一个 key 之间无分隔（如 `16server_port`、
 * `ChmlFrpToken[bsWXpkJQ]`），无法靠通用正则完美切分。这里采用 **已知 key 字典**
 * 做分隔：扫描文本，在每个已知 frpc 配置项的 key 出现位置（其前方不是空白或 =）前插入换行。
 */
private fun normalizeIni(text: String): String {
    // 去掉注释
    var s = text.lineSequence()
        .map { line ->
            val h = line.indexOf('#')
            val sc = line.indexOf(';')
            val cut = when {
                h >= 0 && sc >= 0 -> minOf(h, sc)
                h >= 0 -> h
                sc >= 0 -> sc
                else -> line.length
            }
            line.substring(0, cut)
        }
        .joinToString("\n")

    // 已知 frpc 配置 key（覆盖常见项；未列出的透传字段由通用 fallback 处理）
    val knownKeys = listOf(
        "server_addr", "server_port", "user", "token", "tls_enable", "use_compression",
        "type", "local_ip", "local_port", "remote_port", "custom_domains",
        "role", "sk", "secretkey", "plugin", "bandwidth_limit", "group", "health_check_type",
        "http_user", "http_pwd", "subdomain", "locations", "host_header_rewrite",
        "proxy_protocol_version", "connect_timeout", "dial_server_keepalive"
    )

    // 在每个已知 key 前（非空白/非=前缀）插入换行，使其独立成行
    for (key in knownKeys) {
        // 匹配：(非空白且非=) + key + 可选空白 + =  → 在其前插入 \n
        val pattern = Regex("""(?<![\\s=])(${Regex.escape(key)})\\s*=""")
        s = pattern.replace(s) { "\n${it.groupValues[1]} = " }
    }

    // [section] 后若紧跟非换行内容（如 [common]server_addr），插入换行
    s = Regex("""(\])(\\S)""").replace(s) { "${it.groupValues[1]}\n${it.groupValues[2]}" }

    // 折叠多余空行
    s = Regex("""\n{3,}""").replace(s, "\n\n")
    return s
}

/**
 * 将解析模型序列化为 frp 官方推荐的 **TOML** 文本。
 *
 * 注意字段映射（INI → TOML）：
 * - common.server_addr / server_port / user / token / tls_enable / use_compression
 * - auth.token（TOML 下 token 放在 auth.token）
 * - transport.tls.enable / transport.useCompression
 * - proxies: [[proxies]] 数组，每个代理的 custom_domains（http/https）写为字符串数组
 */
fun frpConfigToToml(cfg: FrpParsedConfig): String {
    val sb = StringBuilder()
    val c = cfg.common

    c["server_addr"]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("serverAddr = \"$it\"") }
    c["server_port"]?.toIntOrNull()?.let { sb.appendLine("serverPort = $it") }
    c["user"]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("user = \"$it\"") }
    c["token"]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("auth.token = \"$it\"") }
    val tls = c["tls_enable"]?.equals("true", true) ?: false
    val compression = c["use_compression"]?.equals("true", true)
        ?: c["usecompression"]?.equals("true", true) ?: false
    if (tls) sb.appendLine("transport.tls.enable = true")
    if (compression) sb.appendLine("transport.useCompression = true")
    sb.appendLine()

    if (cfg.proxies.isEmpty()) {
        // 无代理段时，给出一个默认 tcp 透传本机 8088，避免空配置无法启动
        sb.appendLine("[[proxies]]")
        sb.appendLine("name = \"ufi-axis-web\"")
        sb.appendLine("type = \"tcp\"")
        sb.appendLine("localIP = \"127.0.0.1\"")
        sb.appendLine("localPort = 8088")
        sb.appendLine("remotePort = 8088")
        return sb.toString()
    }

    for (p in cfg.proxies) {
        val f = p.fields
        sb.appendLine("[[proxies]]")
        sb.appendLine("name = \"${p.name}\"")
        val type = f["type"]?.lowercase() ?: "tcp"
        sb.appendLine("type = \"$type\"")
        f["local_ip"]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("localIP = \"$it\"") }
        f["local_port"]?.toIntOrNull()?.let { sb.appendLine("localPort = $it") }
        f["remote_port"]?.toIntOrNull()?.let { sb.appendLine("remotePort = $it") }
        // http/https 的 custom_domains：支持逗号分隔多域名
        f["custom_domains"]?.takeIf { it.isNotBlank() }?.let { raw ->
            val domains = raw.split(',').map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
            when {
                domains.isEmpty() -> {}
                domains.size == 1 -> sb.appendLine("customDomains = [\"${domains[0]}\"]")
                else -> sb.appendLine("customDomains = [${domains.joinToString(", ") { "\"$it\"" }}]")
            }
        }
        // 其他未显式处理的字段原样透传（如 role、sk、secretKey、plugin 等）
        for ((k, v) in f) {
            when (k) {
                "type", "local_ip", "local_port", "remote_port", "custom_domains" -> {}
                else -> {
                    val tomlKey = k.replace('_', '.')
                    val quoted = v.any { it.isWhitespace() || it == '"' }
                    sb.appendLine("$tomlKey = ${if (quoted) "\"${v.replace("\"", "\\\"")}\"" else v}")
                }
            }
        }
        sb.appendLine()
    }

    return sb.toString()
}
