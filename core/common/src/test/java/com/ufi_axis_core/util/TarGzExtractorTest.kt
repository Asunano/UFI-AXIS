package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * [TarGzExtractor] 回归用例。
 *
 * 之所以要测：frp 官方只发 `frp_<ver>_linux_arm64.tar.gz`，而 JDK 没有 tar 支持，
 * 这个手写的 512 字节头解析器是组件安装链路上唯一没有第三方库兜底的一环。
 */
class TarGzExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `提取压缩包内指定文件`() {
        val payload = "#!/fake-frpc-binary\n".repeat(50).toByteArray()
        val archive = writeTarGz(
            tmp.newFile("frp.tar.gz"),
            "frp_0.65.0_linux_arm64/LICENSE" to "license text".toByteArray(),
            "frp_0.65.0_linux_arm64/frpc" to payload
        )
        val out = File(tmp.root, "frpc")

        val size = TarGzExtractor.extractEntry(archive, "frpc", out, 1024 * 1024).getOrThrow()

        assertEquals(payload.size.toLong(), size)
        assertTrue(out.readBytes().contentEquals(payload))
    }

    @Test
    fun `包内不存在目标条目时失败`() {
        val archive = writeTarGz(
            tmp.newFile("noentry.tar.gz"),
            "frp_0.65.0_linux_arm64/frps" to "server".toByteArray()
        )
        val result = TarGzExtractor.extractEntry(archive, "frpc", File(tmp.root, "frpc"), 1024)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("frpc") == true)
    }

    @Test
    fun `条目超过上限时失败且不写出文件`() {
        val archive = writeTarGz(
            tmp.newFile("big.tar.gz"),
            "dir/frpc" to ByteArray(4096) { 1 }
        )
        val out = File(tmp.root, "frpc")

        val result = TarGzExtractor.extractEntry(archive, "frpc", out, 1024)

        assertTrue(result.isFailure)
        assertTrue("超限时不应写出文件", !out.exists())
    }

    /**
     * 只按 basename 匹配，包内路径一律忽略 —— 这是防 zip-slip 的关键行为：
     * 哪怕条目名是 `../../frpc`，也只会写到调用方指定的 [out]。
     */
    @Test
    fun `包内路径含上跳段时仍只写到指定目标`() {
        val payload = "safe".toByteArray()
        val archive = writeTarGz(tmp.newFile("evil.tar.gz"), "../../../frpc" to payload)
        val out = File(tmp.root, "frpc")

        TarGzExtractor.extractEntry(archive, "frpc", out, 1024).getOrThrow()

        assertTrue(out.readBytes().contentEquals(payload))
    }

    // ── 测试用最小 tar 写入器（只写普通文件条目，checksum 留空：读取侧不校验）──

    private fun writeTarGz(target: File, vararg entries: Pair<String, ByteArray>): File {
        val tar = ByteArrayOutputStream()
        for ((name, data) in entries) {
            tar.write(tarHeader(name, data.size.toLong()))
            tar.write(data)
            val pad = (512 - data.size % 512) % 512
            tar.write(ByteArray(pad))
        }
        // 归档结束标记：两个全零块
        tar.write(ByteArray(1024))
        GZIPOutputStream(target.outputStream()).use { it.write(tar.toByteArray()) }
        return target
    }

    private fun tarHeader(name: String, size: Long): ByteArray {
        val h = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        require(nameBytes.size < 100) { "测试用例名字过长" }
        nameBytes.copyInto(h, 0)
        // size：offset 124，11 位八进制 + NUL
        val octal = size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII)
        octal.copyInto(h, 124)
        h[135] = 0
        // typeflag：offset 156，'0' = 普通文件
        h[156] = '0'.code.toByte()
        return h
    }
}
