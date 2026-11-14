package com.ufi_axis_core.util

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 单文件提取器（2026-09-01：frp 官方 release 只提供 `frp_<ver>_linux_arm64.tar.gz`）。
 *
 * 为什么不用 commons-compress：那会给 APK 加回约 700KB 与一串传递依赖，
 * 而本次改造的目的正是缩包；这里只需要"从 tar 里取出一个已知 basename 的普通文件"，
 * 手写 tar 头解析约 60 行就够（JDK 自带 [GZIPInputStream]，只缺 tar 一层）。
 *
 * 安全约定（务必保持）：
 * - **绝不使用包内路径**。只用 basename 做匹配，写入调用方指定的 [target]，
 *   因此天然免疫 `../` 路径穿越与符号链接攻击（zip-slip）。
 * - 只接受普通文件条目（typeflag `0` / `\0`），目录/软链/设备节点等一律跳过。
 * - 单条目大小超过 [maxBytes] 直接失败，防解压炸弹。
 *
 * ## 同名条目取最大的那个（2026-11-09）
 *
 * 不能假设 basename 在包里唯一。Syncthing 的 `syncthing-linux-arm64-*.tar.gz` 里同时有：
 * - `.../etc/freebsd-rc/syncthing`（1.7KB 启动脚本）
 * - `.../etc/firewall-ufw/syncthing`（175B 防火墙脚本）
 * - `.../syncthing`（31.7MB 真正的二进制）
 *
 * 按"第一个匹配"会提取到脚本，设备端 [com.ufi_axis_core.util.program.ElfInspector]
 * 会以"不是 ELF"拒绝安装 —— 清单看起来合法，装的时候才炸。真正的可执行文件通常是
 * 同名条目里最大的那个，所以这里选 **size 最大** 的普通文件；对唯一条目行为不变。
 */
object TarGzExtractor {

    private const val BLOCK = 512

    /**
     * 从 [archive]（.tar.gz）中提取 basename 等于 [entryName] 的**最大**普通文件到 [target]。
     *
     * @return 成功时为写出的字节数；未找到条目或超限时 Result.failure
     */
    fun extractEntry(archive: File, entryName: String, target: File, maxBytes: Long): Result<Long> = runCatching {
        GZIPInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024)).use { ins ->
            readLargestEntry(ins, entryName, target, maxBytes)
        }
    }

    /**
     * 顺序扫描整个 tar（不能提前返回：后面可能还有更大的同名条目），把最大的匹配项写到
     * [target]。先写 `<target>.candidate` 再 rename，避免中途失败留下半截目标文件。
     */
    private fun readLargestEntry(ins: InputStream, entryName: String, target: File, maxBytes: Long): Long {
        val header = ByteArray(BLOCK)
        val parent = target.absoluteFile.parentFile
            ?: throw IllegalStateException("目标目录不存在: ${target.absolutePath}")
        val candidate = File(parent, "${target.name}.candidate")
        var bestSize = -1L
        var sawMatch = false

        try {
            while (true) {
                if (!readFully(ins, header)) throw IllegalStateException("tar 包提前结束，未找到 $entryName")
                // 连续的全零块代表归档结束
                if (header.all { it == 0.toByte() }) break

                val name = parseString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val typeFlag = header[156]
                val isRegular = typeFlag == 0.toByte() || typeFlag == '0'.code.toByte()
                val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK
                val matches = isRegular && name.substringAfterLast('/') == entryName

                if (!matches) {
                    if (!skipFully(ins, padded)) throw IllegalStateException("tar 包提前结束，未找到 $entryName")
                    continue
                }

                sawMatch = true
                if (size > maxBytes) {
                    throw IllegalStateException("$entryName 解包后 $size 字节，超过上限 $maxBytes")
                }
                if (size > bestSize) {
                    // 覆盖上一个候选：同为 candidate 文件，rename 到 target 时只保留最大的
                    candidate.delete()
                    candidate.outputStream().use { out -> copyExactly(ins, out, size) }
                    bestSize = size
                } else if (!skipFully(ins, size)) {
                    throw IllegalStateException("tar 包提前结束，未找到 $entryName")
                }

                // 匹配条目读完后跳过 tar 的 512 字节对齐填充
                if (!skipFully(ins, padded - size)) {
                    throw IllegalStateException("tar 包提前结束，未找到 $entryName")
                }
            }

            if (!sawMatch || bestSize <= 0) {
                throw IllegalStateException(
                    if (sawMatch) "$entryName 在 tar 包内为空文件" else "tar 包内不存在 $entryName"
                )
            }

            target.delete()
            if (!candidate.renameTo(target)) {
                candidate.copyTo(target, overwrite = true)
                candidate.delete()
            }
            return bestSize
        } catch (e: Throwable) {
            candidate.delete()
            throw e
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
