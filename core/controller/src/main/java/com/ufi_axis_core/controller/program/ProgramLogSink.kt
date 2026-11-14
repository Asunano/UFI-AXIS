package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 第三方程序的日志落盘器（`log/programs/<id>/stdout.log`）。
 *
 * ## 为什么不复用 `AppLogger`
 *
 * `AppLogger` 的布局是固定的 `<root>/<yyyy-MM-dd>/{app,at,error}.log`，且 2026-10-13 起
 * **取消了轮转与总量上限**（按用户要求）。第三方程序是"别人写的、输出量不可控"的东西：
 * OpenList 有访问日志，一个扫描请求就能刷几百行。灌进 `app.log` 的后果是
 * **把 core 自己的排障记录冲掉**，而且用户想清理时无从下手。
 *
 * 所以这里独立实现，只有三件事：滚动、限长、不阻塞。
 *
 * ## 为什么必须持续读（哪怕不写）
 *
 * `redirectErrorStream(false)` 时 stdout/stderr 各是一条管道，内核缓冲区只有 64KB。
 * 没人读 → 程序写满缓冲就阻塞在 `write()` 上 → **进程活着但完全不动**，
 * `isAlive` 还是 true，看护不会救。这是 ttyd 踩过的坑（见 `TtydManager.drainOutput`）。
 *
 * 所以策略是：**读永远继续，写按配额**。超配额时丢弃并每 N 行记一条"已丢弃 N 行"，
 * 让日志本身说明"这里被截断了"，而不是静默丢。
 */
class ProgramLogSink(
    private val file: File,
    private val quotaBytes: Long,
    private val maxFiles: Int = 3
) {
    private val lock = Any()
    private var writer: OutputStreamWriter? = null
    private var written = 0L
    private var dropped = 0L

    private fun ensureOpen(): OutputStreamWriter? {
        writer?.let { return it }
        return runCatching {
            file.parentFile?.mkdirs()
            val w = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
            writer = w
            written = file.length()
            w
        }.getOrNull()
    }

    /** 追加一行（自动补换行）。任何 I/O 异常都吞掉并关闭 —— 日志写不进去不该影响程序。 */
    fun append(line: String) {
        synchronized(lock) {
            val w = ensureOpen() ?: return
            runCatching {
                if (written >= quotaBytes) {
                    dropped++
                    if (dropped % 500L == 1L) {
                        w.write("[ufi] 日志超出配额 ${quotaBytes / 1024}KB，已丢弃 $dropped 行\n")
                        w.flush()
                    }
                    return
                }
                w.write(line)
                w.write("\n")
                // 按**字节**累加：`line.length` 是 UTF-16 字符数，一行中文按 UTF-8 占 3 倍，
            // 含全角字符的日志会让 `log_quota_mb=32` 实际写到 100MB。
            written += line.toByteArray(Charsets.UTF_8).size + 1L
                w.flush()
                if (written >= quotaBytes) rotate()
            }.onFailure {
                runCatching { w.close() }
                writer = null
            }
        }
    }

    /**
     * 滚动：`stdout.log → stdout.log.1 → .2`，最旧的丢弃。
     *
     * 用 `renameTo` 而不是重写文件：滚动发生在程序还在写的时候，
     * 重写会让"正写到一半的行"丢失。
     */
    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        runCatching {
            File("${file.absolutePath}.${maxFiles - 1}").delete()
            for (i in maxFiles - 2 downTo 1) {
                val from = File("${file.absolutePath}.$i")
                if (from.exists()) from.renameTo(File("${file.absolutePath}.${i + 1}"))
            }
            file.renameTo(File("${file.absolutePath}.1"))
        }.onFailure { AppLogger.w(TAG, "日志滚动失败 ${file.name}: ${it.message}") }
        written = 0
    }

    companion object {
        const val TAG = "ProgramLog"

        /** 每路日志保留的文件数上限（含当前文件）。与 [rotate] 的下标算法绑定，改一处必须改另一处。 */
        const val MAX_FILES = 3

        /**
         * 把清单里的"每程序日志总量（MB）"折算成"单个文件的配额（字节）"。
         *
         * 为什么要折：`log_quota_mb` 的语义是**每个程序总共能占多少日志**，
         * 而实际落盘是 `stdout` + `stderr` 两路、每路还滚动保留 [MAX_FILES] 份。
         * 直接把总量当单文件配额用，`32` 会变成 `32 × 3 × 2 = 192MB` —— 账面对不上，
         * 用户看着清单以为只占 32MB。
         *
         * 下限 1MB：配额给得再小也不该把每行都丢掉，否则日志形同没有。
         */
        fun perFileQuotaBytes(totalMb: Int, streams: Int = 2, maxFiles: Int = MAX_FILES): Long {
            val total = totalMb.coerceAtLeast(0).toLong() * 1024L * 1024L
            val slots = (streams.coerceAtLeast(1) * maxFiles.coerceAtLeast(1)).toLong()
            return (total / slots).coerceAtLeast(1024L * 1024L)
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
        }
    }
}
