package com.ufi_axis_core.util

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 单文件提取器（2026-09-01：frp 官方 release 只提供 `frp_<ver>_linux_arm64.tar.gz`）。
 *
 * 为什么不用 commons-compress：那会给 APK 加回约 700KB 与一串传递依赖，
 * 而本次改造的目的正是缩包；这里只需要"从 tar 里取出唯一一个已知名字的普通文件"，
 * 手写 tar 头解析约 60 行就够（JDK 自带 [GZIPInputStream]，只缺 tar 一层）。
 *
 * 安全约定（务必保持）：
 * - **绝不使用包内路径**。只用 basename 做匹配，写入调用方指定的 [target]，
 *   因此天然免疫 `../` 路径穿越与符号链接攻击（zip-slip）。
 * - 只接受普通文件条目（typeflag `0` / `\0`），目录/软链/设备节点等一律跳过。
 * - 单条目大小超过 [maxBytes] 直接失败，防解压炸弹。
 */
object TarGzExtractor {

    private const val BLOCK = 512

    /**
     * 从 [archive]（.tar.gz）中提取 basename 等于 [entryName] 的第一个普通文件到 [target]。
     *
     * @return 成功时为写出的字节数；未找到条目或超限时 Result.failure
     */
    fun extractEntry(archive: File, entryName: String, target: File, maxBytes: Long): Result<Long> = runCatching {
        GZIPInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024)).use { ins ->
            readEntry(ins, entryName, target, maxBytes)
        }
    }

    /** 顺序扫描 tar 头块直到命中 [entryName]；命中即写出并返回字节数，扫完/出错抛异常 */
    private fun readEntry(ins: InputStream, entryName: String, target: File, maxBytes: Long): Long {
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(ins, header)) throw IllegalStateException("tar 包提前结束，未找到 $entryName")
            // 连续的全零块代表归档结束
            if (header.all { it == 0.toByte() }) throw IllegalStateException("tar 包内不存在 $entryName")

            val name = parseString(header, 0, 100)
            val size = parseOctal(header, 124, 12)
            val typeFlag = header[156]
            val isRegular = typeFlag == 0.toByte() || typeFlag == '0'.code.toByte()
            val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK

            if (isRegular && name.substringAfterLast('/') == entryName) {
                if (size <= 0) throw IllegalStateException("$entryName 在 tar 包内为空文件")
                if (size > maxBytes) {
                    throw IllegalStateException("$entryName 解包后 $size 字节，超过上限 $maxBytes")
                }
                target.delete()
                target.outputStream().use { out -> copyExactly(ins, out, size) }
                return size
            }

            if (!skipFully(ins, padded)) throw IllegalStateException("tar 包提前结束，未找到 $entryName")
        }
    }

    /** tar 头字段是定长 ASCII，以 NUL 或空格结尾 */
    private fun parseString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        while (end < offset + len && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    /** size / mode 等字段是八进制 ASCII 文本 */
    private fun parseOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val text = parseString(buf, offset, len).trim { it == ' ' || it == '\u0000' }
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: throw IllegalStateException("tar 头 size 字段非法: '$text'")
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = ins.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(ins: InputStream, bytes: Long): Boolean {
        var left = bytes
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) return false
            left -= n
        }
        return true
    }

    private fun copyExactly(ins: InputStream, out: java.io.OutputStream, bytes: Long) {
        var left = bytes
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IllegalStateException("tar 数据段提前结束，剩余 $left 字节")
            out.write(buf, 0, n)
            left -= n
        }
    }
}
