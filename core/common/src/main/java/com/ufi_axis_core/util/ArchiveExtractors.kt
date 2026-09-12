package com.ufi_axis_core.util

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 文件系统归档工具：解压 zip / tar / tar.gz(.tgz) 与单文件 gzip，打 zip。
 *
 * 安全约定（与 [WebResourceManager.unzipChecked] 一致，缺一不可）：
 * - 条目名先归一化（反斜杠→`/`、去掉前导 `/`），再查穿越；`..` 段直接拒绝。
 * - 每个写出文件都用 canonicalPath 比对解压目标根，越界即抛 SecurityException（zip-slip 防护）。
 * - 解压后总大小设上限，防解压炸弹（2GB）。
 * - tar 只接受普通文件（`0`/`\0`）与目录（`5`）条目；软链 / 设备节点等一律跳过（不写出、不跟随）。
 */
object ArchiveExtractors {

    /** 解压后总大小上限（2GB）。 */
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    /** 解压 zip 到 [destDir]（destDir 必须已存在或可被创建）。 */
    fun extractZip(input: InputStream, destDir: File) {
        val destPath = destDir.canonicalPath
        var total = 0L
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = normalizeEntryName(entry.name)
                if (entryName.isEmpty()) throw SecurityException("ZIP 条目名为空")
                val outFile = File(destDir, entryName)
                assertInside(outFile, destPath, entry.name)
                if (entry.isDirectory || entryName.endsWith('/')) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os ->
                        val n = zis.copyTo(os)
                        total += n
                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("解压后超过 2GB 上限")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 解压 tar（[gzip]=true 时先过 GZIPInputStream）。 */
    fun extractTar(input: InputStream, destDir: File, gzip: Boolean) {
        val destPath = destDir.canonicalPath
        var total = 0L
        val ins = if (gzip) GZIPInputStream(BufferedInputStream(input, 64 * 1024)) else BufferedInputStream(input, 64 * 1024)
        ins.use {
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(ins, header)) break
                // 连续全零块标记归档结束；遇到首个全零块即停止（不会漏条目，条目块非空）
                if (header.all { it == 0.toByte() }) break
                val rawName = parseName(header)
                val name = normalizeEntryName(rawName)
                if (name.isEmpty()) throw SecurityException("tar 条目名为空")
                val size = parseOctal(header, 124, 12)
                if (size < 0) throw IllegalStateException("tar 条目大小非法: $rawName")
                val typeFlag = header[156]
                val isDir = typeFlag == '5'.code.toByte()
                val isRegular = typeFlag == 0.toByte() || typeFlag == '0'.code.toByte()
                val outFile = File(destDir, name)
                assertInside(outFile, destPath, rawName)
                if (isDir) {
                    outFile.mkdirs()
                    skipFully(ins, blockAligned(size))
                } else if (isRegular) {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os -> copyExactly(ins, os, size) }
                    total += size
                    if (total > MAX_TOTAL_BYTES) throw IllegalStateException("解压后超过 2GB 上限")
                    skipFully(ins, blockAligned(size) - size)
                } else {
                    // 跳过其它类型（软链 / 设备 / 硬链等），不写出、不跟随
                    skipFully(ins, blockAligned(size))
                }
            }
        }
    }

    /** 单文件 gzip 解压（如 `xxx.log.gz` → `xxx.log`）。 */
    fun extractGz(src: File, destDir: File, outName: String) {
        val destPath = destDir.canonicalPath
        val out = File(destDir, outName)
        assertInside(out, destPath, outName)
        GZIPInputStream(src.inputStream().buffered()).use { gin ->
            out.outputStream().use { os -> gin.copyTo(os) }
        }
    }

    /** 把 [sources] 逐个打成 zip 写入 [dest]（dest 由调用方保证不存在后再调）。 */
    fun zipPaths(sources: List<File>, dest: File) {
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            for (src in sources) {
                addToZip(src, src.name, zos)
            }
        }
    }

    // ── 内部 ──

    private const val BLOCK = 512

    private fun normalizeEntryName(raw: String): String {
        return raw.replace('\\', '/').trimStart('/')
    }

    /** 归一化后若含 `..` 段或脱离 destDir 根，抛安全异常。 */
    private fun assertInside(outFile: File, destPath: String, rawName: String) {
        if (normalizeEntryName(rawName).split('/').any { it == ".." }) {
            throw SecurityException("归档条目路径穿越: $rawName")
        }
        val canonical = outFile.canonicalPath
        if (canonical != destPath && !canonical.startsWith(destPath + File.separator)) {
            throw SecurityException("归档条目路径穿越: $rawName")
        }
    }

    private fun blockAligned(size: Long): Long = ((size + BLOCK - 1) / BLOCK) * BLOCK

    private fun addToZip(file: File, entryName: String, zos: ZipOutputStream) {
        val entry = if (file.isDirectory) ZipEntry("$entryName/") else ZipEntry(entryName)
        zos.putNextEntry(entry)
        if (!file.isDirectory) {
            runCatching {
                file.inputStream().buffered(64 * 1024).use { it.copyTo(zos) }
            }.also { zos.closeEntry() }
                .onFailure { throw it }
        } else {
            zos.closeEntry()
            file.listFiles()?.forEach { child ->
                addToZip(child, "$entryName/${child.name}", zos)
            }
        }
    }

    private fun parseName(header: ByteArray): String {
        // ustar 扩展头：prefix @345(155B) + name @0(100B)
        val prefix = parseString(header, 345, 155)
        val name = parseString(header, 0, 100)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        while (end < offset + len && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun parseOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val text = parseString(buf, offset, len).trim { it == ' ' || it == '\u0000' }
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: throw IllegalStateException("tar 头 size 字段非法")
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
        if (bytes < 0) return false
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
