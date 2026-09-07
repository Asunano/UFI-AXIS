package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [decodeServiceCallText] 的解码回归测试。
 *
 * 为什么值得单测：AT 通道的正确性全押在这一个纯函数上（字节序错一位，`+CSQ: 20,99` 就会变成乱码），
 * 而它上游是设备上的 `service call`，真机联调成本远高于在 JVM 里造几条 dump。
 * 这里用 [encodeAsServiceCallDump] 反向构造输入 —— 编码/解码两侧独立实现，字节序写错任何一边都会挂。
 */
class ServiceCallParcelTest {

    /**
     * 把文本编码成 `service call` 会打印的样子：
     * 内存是 UTF-16LE，`service` 按 32 位字打印大端文本 ⇒ 打印顺序是 `hi_hi hi_lo lo_hi lo_lo`。
     */
    private fun encodeAsServiceCallDump(text: String): String {
        val units = text.map { it.code }
        val sb = StringBuilder("Result: Parcel(\n  0x00000000: ")
        var i = 0
        while (i < units.size) {
            val lo = units[i]
            val hi = if (i + 1 < units.size) units[i + 1] else 0
            sb.append(
                "%02x%02x%02x%02x ".format(hi shr 8, hi and 0xFF, lo shr 8, lo and 0xFF)
            )
            i += 2
        }
        // 尾部预览区：解码必须在这里截断，否则预览里的点号会被当数据
        sb.append("'..........')")
        return sb.toString()
    }

    @Test
    fun `单个字解码出两个 ASCII 字符`() {
        // 手写而非用 encode 生成：钉死字节序这件事本身
        val dump = "  0x00000000: 004b004f  '.O.K'"
        assertEquals("OK", decodeServiceCallText(dump))
    }

    @Test
    fun `典型 AT 响应往返一致且保留行结构`() {
        val decoded = decodeServiceCallText(encodeAsServiceCallDump("\r\n+CSQ: 20,99\r\n\r\nOK\r\n"))
        assertEquals("+CSQ: 20,99\nOK", decoded)
    }

    @Test
    fun `Parcel 头部的异常码与长度不会污染结果`() {
        // 00000000 = 异常码，0000000b = 字符串长度；两者的码元都 < 32，应被可打印过滤丢掉
        val dump = "  0x00000000: 00000000 0000000b 004b004f  '............'"
        assertEquals("OK", decodeServiceCallText(dump))
    }

    @Test
    fun `偏移量前缀不被当作数据字`() {
        // 0x00000000 里的 8 位十六进制紧贴 x，\b 不成立 ⇒ 不参与解码。
        // 若这条失效，每行都会多解出两个 NUL（被过滤后看不出来），
        // 但换成 0x0041004200 这类地址时就会凭空多出字符，故单独钉一条。
        val dump = "  0x00000041: 004b004f  '.O.K'"
        assertEquals("OK", decodeServiceCallText(dump))
    }

    @Test
    fun `预览区内容不参与解码`() {
        val dump = "  0x00000000: 004b004f  'deadbeef cafebabe'"
        assertEquals("OK", decodeServiceCallText(dump))
    }

    @Test
    fun `空输入与纯噪声返回空串`() {
        assertEquals("", decodeServiceCallText(""))
        assertEquals("", decodeServiceCallText("Result: Parcel(\n  0x00000000: 00000000  '....')"))
    }

    @Test
    fun `多行 dump 全部参与解码`() {
        val decoded = decodeServiceCallText(
            """
            Result: Parcel(
              0x00000000: 00000000 0000000c 00490041  '........A.I.'
              0x00000010: 004f000d 0000004b  '..O.K...'
            """.trimIndent()
        )
        // AI + CR + O + K → 行归一后是两行
        assertTrue("实际=$decoded", decoded.startsWith("AI"))
        assertTrue("实际=$decoded", decoded.contains("OK"))
    }
}
