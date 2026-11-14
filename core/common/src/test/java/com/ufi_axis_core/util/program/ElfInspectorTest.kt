package com.ufi_axis_core.util.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ElfInspector] 的单测。
 *
 * 为什么值得给"读二进制头"写单测：这里判错的后果是**装上一个必然起不来的程序**，
 * 而失败信息会以"点了启动没反应"的形式出现在用户面前，排查成本极高。
 * 三个分支（静态 / bionic 动态 / glibc 动态）在真机上对应三种完全不同的结局，必须钉死。
 */
class ElfInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 最小 ELF 构造 ──

    /**
     * 布局：`ehdr(64) + 4×phdr(56) = 288`，interp 字符串紧跟其后。
     * 所有偏移都是按这个布局算的 —— 改布局必须同步改 [PAYLOAD_OFFSET]。
     */
    private fun buildElf(
        machine: Int = 0xB7,
        interp: String? = null,
        align: Long = 0x1000,
        eType: Int = 3
    ): ByteArray {
        val interpBytes = interp?.toByteArray(Charsets.UTF_8)?.plus(byteArrayOf(0))
        val total = PAYLOAD_OFFSET + (interpBytes?.size ?: 0) + 16
        val out = ByteArray(total.toInt())

        // e_ident
        out[0] = 0x7F
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // little endian
        put16(out, 16, eType)
        put16(out, 18, machine)
        put64(out, 32, 64L)  // e_phoff
        put16(out, 54, 56)   // e_phentsize
        put16(out, 56, if (interpBytes != null) 2 else 1) // e_phnum

        // PT_LOAD
        writePhdr(out, 0, type = 1, offset = 0, filesz = 0, align = align)

        if (interpBytes != null) {
            writePhdr(out, 1, type = 3, offset = PAYLOAD_OFFSET, filesz = interpBytes.size.toLong(), align = 1)
            System.arraycopy(interpBytes, 0, out, PAYLOAD_OFFSET.toInt(), interpBytes.size)
        }
        return out
    }

    private fun writePhdr(
        out: ByteArray,
        index: Int,
        type: Long,
        offset: Long,
        filesz: Long,
        align: Long
    ) {
        val base = (64 + index * 56)
        put32(out, base + 0, type)
        put64(out, base + 8, offset)
        put64(out, base + 32, filesz)
        put64(out, base + 48, align)
    }

    private fun fileOf(bytes: ByteArray): File = tmp.newFile("bin").apply { writeBytes(bytes) }

    // ── 用例 ──

    @Test
    fun `无解释器的静态二进制通过`() {
        val f = fileOf(buildElf(interp = null))
        val info = ElfInspector.inspect(f).getOrThrow()
        assertEquals(ElfInspector.Linkage.STATIC, info.linkage)
        assertTrue(info.runnable)
        assertNull(ElfInspector.validate(f))
    }

    @Test
    fun `android linker64 的动态二进制通过`() {
        val f = fileOf(buildElf(interp = "/system/bin/linker64"))
        val info = ElfInspector.inspect(f).getOrThrow()
        assertEquals(ElfInspector.Linkage.ANDROID_DYNAMIC, info.linkage)
        assertTrue(info.runnable)
        assertNull(ElfInspector.validate(f))
    }

    @Test
    fun `glibc 解释器必须拒装并说明原因`() {
        val f = fileOf(buildElf(interp = "/lib/ld-linux-aarch64.so.1"))
        val info = ElfInspector.inspect(f).getOrThrow()
        assertEquals(ElfInspector.Linkage.FOREIGN_DYNAMIC, info.linkage)
        assertFalse(info.runnable)
        val reason = ElfInspector.validate(f)
        assertNotNull(reason)
        assertTrue("原因里应点出解释器问题: $reason", reason!!.contains("ld-linux"))
    }

    @Test
    fun `musl 解释器同样拒装`() {
        val f = fileOf(buildElf(interp = "/lib/ld-musl-aarch64.so.1"))
        assertEquals(
            ElfInspector.Linkage.FOREIGN_DYNAMIC,
            ElfInspector.inspect(f).getOrThrow().linkage
        )
    }

    @Test
    fun `未知解释器按最保守处理`() {
        val f = fileOf(buildElf(interp = "/opt/weird/loader"))
        assertEquals(
            ElfInspector.Linkage.UNKNOWN_DYNAMIC,
            ElfInspector.inspect(f).getOrThrow().linkage
        )
        assertNotNull(ElfInspector.validate(f))
    }

    @Test
    fun `非 ELF 文件被拒绝`() {
        val f = tmp.newFile("page.html").apply { writeText("<html>404 not found</html>") }
        assertTrue(ElfInspector.inspect(f).isFailure)
        assertNotNull(ElfInspector.validate(f))
    }

    @Test
    fun `架构不符被拒绝`() {
        val f = fileOf(buildElf(machine = 0x3E)) // EM_X86_64
        val reason = ElfInspector.validate(f)
        assertNotNull(reason)
        assertTrue(reason!!.contains("架构不匹配"))
    }

    @Test
    fun `文件过小被拒绝而不是崩溃`() {
        val f = tmp.newFile("tiny").apply { writeBytes(ByteArray(10)) }
        assertTrue(ElfInspector.inspect(f).isFailure)
    }

    @Test
    fun `截断的 program header 表被识别`() {
        // 头里写着 phoff=64 / phnum=1 / phentsize=56，但文件只剩 70 字节
        val f = fileOf(buildElf(interp = null).copyOf(70))
        assertTrue(ElfInspector.inspect(f).isFailure)
    }

    @Test
    fun `页对齐被记录但不影响通过`() {
        val f = fileOf(buildElf(interp = null, align = 0x1000))
        val info = ElfInspector.inspect(f).getOrThrow()
        assertEquals(0x1000L, info.minLoadAlign)
        assertNull("4KB 对齐在目标机型上可用，不应拒装", ElfInspector.validate(f))
    }

    private companion object {
        /** ehdr(64) + 4 个 phdr(56×4) */
        const val PAYLOAD_OFFSET = 64L + 56L * 4

        fun put16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun put32(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }

        fun put64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }
}
