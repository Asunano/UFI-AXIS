package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [BinaryComponentStore] 的纯函数部分：ELF 体检与 id 白名单。
 *
 * 之所以要测：组件二进制现在来自公网直链，安装前这层体检是"拿到 .deb / x86 版本 /
 * 一个 HTML 错误页"时唯一的拦截点；id 校验则是 HTTP 路径参数直接拼文件名的防线。
 */
class BinaryComponentStoreValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** ELF 头：magic + EI_CLASS(64位) + e_machine 小端 */
    private fun elfHeader(eiClass: Int, machine: Int): ByteArray {
        val h = ByteArray(64)
        h[0] = 0x7F
        h[1] = 'E'.code.toByte()
        h[2] = 'L'.code.toByte()
        h[3] = 'F'.code.toByte()
        h[4] = eiClass.toByte()
        h[5] = 1 // 小端
        h[16] = 2 // e_type = ET_EXEC
        h[18] = (machine and 0xFF).toByte()
        h[19] = ((machine shr 8) and 0xFF).toByte()
        return h
    }

    @Test
    fun `aarch64 可执行文件通过体检`() {
        val f = tmp.newFile("frpc")
        f.writeBytes(elfHeader(eiClass = 2, machine = 0xB7))
        assertNull(BinaryComponentStore.validateAarch64Elf(f))
    }

    @Test
    fun `x86_64 二进制被拒绝`() {
        val f = tmp.newFile("wrong-arch")
        f.writeBytes(elfHeader(eiClass = 2, machine = 0x3E)) // EM_X86_64
        val err = BinaryComponentStore.validateAarch64Elf(f)
        assertTrue(err?.contains("架构不匹配") == true)
    }

    @Test
    fun `32 位 ELF 被拒绝`() {
        val f = tmp.newFile("elf32")
        f.writeBytes(elfHeader(eiClass = 1, machine = 0xB7))
        val err = BinaryComponentStore.validateAarch64Elf(f)
        assertTrue(err?.contains("64 位") == true)
    }

    @Test
    fun `非 ELF 内容被拒绝`() {
        val f = tmp.newFile("error-page")
        f.writeText("<html><body>404 Not Found</body></html>")
        val err = BinaryComponentStore.validateAarch64Elf(f)
        assertTrue(err?.contains("ELF") == true)
    }

    @Test
    fun `文件过小被拒绝`() {
        val f = tmp.newFile("tiny")
        f.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))
        assertTrue(BinaryComponentStore.validateAarch64Elf(f)?.contains("过小") == true)
    }

    @Test
    fun `合法 id 原样返回`() {
        assertEquals("frpc", BinaryComponentStore.requireValidId("frpc"))
        assertEquals("cloudflared", BinaryComponentStore.requireValidId("cloudflared"))
    }

    @Test
    fun `含路径分隔符或上跳段的 id 被拒绝`() {
        for (bad in listOf("", "../frpc", "a/b", "a\\b", "..", "FRPC", "frpc ", "-frpc")) {
            var threw = false
            try {
                BinaryComponentStore.requireValidId(bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("id '$bad' 应被拒绝", threw)
        }
    }
}
