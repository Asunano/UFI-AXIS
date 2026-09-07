package com.ufi_axis_core.util

/**
 * `service call` 输出解码 —— 把 Binder Parcel 的 hex dump 还原成文本。
 *
 * ## 为什么需要它
 * 展锐平台的 AT 通道是一次 Binder 调用（`vendor.sprd.hardware.tool.IToolControl` /
 * `vendor.sprd.hardware.log.ILogControl`），而我们没有那两个接口的 stub，只能借
 * `/system/bin/service call` 走通用路径。`service` 命令把返回的 Parcel 按 32 位字打印成
 * 十六进制 dump，形如：
 *
 * ```
 * Result: Parcel(
 *   0x00000000: 00000000 0000000b 004b004f ... '..........O.K...')
 * ```
 *
 * 尾部单引号里那段是 `service` 自己做的"可打印字符"预览，**不能用**：非 ASCII 一律被替换成
 * `.`，而且长响应会被截断。真正的数据在前面的 hex 字里，需要自己解码。
 *
 * ## 解码规则（三步）
 * 1. **按行切到第一个 `'` 之前** —— 后面是预览区，不是数据；
 * 2. 取出所有独立的 8 位十六进制字（`\b[0-9a-fA-F]{8}\b`）。行首的 `0x00000000:` 是偏移量，
 *    因为紧贴在 `x` 后面，`\b` 不成立，天然被排除；
 * 3. 每个字**逆序取字节**（打印出来的是 32 位字的大端文本，内存里是小端），
 *    每两字节拼一个 UTF-16LE 码元。
 *
 * Parcel 头部的异常码与字符串长度也会被一起解码，但它们的码元都 < 32，被下面的可打印过滤丢掉，
 * 不需要额外跳过 —— 这也是刻意不去猜"前几个字是头部"的原因：不同接口的头部长度并不一致。
 *
 * ## 与上游 Go 实现的差异
 * 参照的行为来自 UFI-TOOLS 的 `send_at.go`（同样是 `service call` + hex 解码），但有一处改进：
 * 它把 CR/LF 一并当作不可打印字符**丢掉**，于是 `\r\n+CSQ: 20,99\r\n\r\nOK\r\n` 会被压成
 * `+CSQ: 20,99OK` —— 行结构没了，上层再想按行取字段就得靠猜。这里把 CR/LF 归一成 `\n`
 * 并合并连续空行，AT 响应的行结构得以保留。
 */
private val HEX_WORD = Regex("""\b[0-9a-fA-F]{8}\b""")

/** 连续换行合并：AT 响应里 `\r\n\r\n` 很常见，逐个保留只会得到一堆空行。 */
private val MULTI_NEWLINE = Regex("\n{2,}")

/**
 * 解码 `service call` 的 stdout。
 *
 * @param raw `service call` 的完整输出（可含多行、可含 `Result: Parcel(` 包裹）
 * @return 还原出的文本；没有任何可打印内容时返回空串
 */
fun decodeServiceCallText(raw: String): String {
    val out = StringBuilder()
    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.substringBefore('\'')
        HEX_WORD.findAll(line).forEach { match ->
            val word = match.value
            // 小端：打印文本的最后一个字节才是内存里的第 0 字节
            val b0 = word.substring(6, 8).toInt(16)
            val b1 = word.substring(4, 6).toInt(16)
            val b2 = word.substring(2, 4).toInt(16)
            val b3 = word.substring(0, 2).toInt(16)
            appendCodeUnit(out, (b1 shl 8) or b0)
            appendCodeUnit(out, (b3 shl 8) or b2)
        }
    }
    return MULTI_NEWLINE.replace(out, "\n").trim()
}

/** 可打印才写入；CR/LF 归一成 `\n`（见文件头注释的"与上游差异"）。 */
private fun appendCodeUnit(out: StringBuilder, code: Int) {
    when {
        code == 13 || code == 10 -> out.append('\n')
        code >= 32 && code != 127 -> out.append(code.toChar())
    }
}
