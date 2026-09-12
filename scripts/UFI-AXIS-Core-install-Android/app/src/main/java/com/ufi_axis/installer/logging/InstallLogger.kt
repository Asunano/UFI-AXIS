package com.ufi_axis.installer.logging

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque

/**
 * 安装日志。
 *
 * - 内存：SharedFlow 供界面实时追加，同时保留有限行数供界面首次进入时回填
 * - 落盘：`getExternalFilesDir()/log/install_<时间戳>.log`（免存储权限）
 *   外存不可用时回退到 `filesDir/log/`
 *
 * 全程不抛异常：日志系统本身不应中断安装流程。
 */
object InstallLogger {

    private const val MAX_BUFFERED_LINES = 2000

    private val _lines = MutableSharedFlow<LogLine>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val lines: SharedFlow<LogLine> = _lines

    /** 供界面首次进入时回填历史 */
    private val buffered = ArrayDeque<LogLine>(MAX_BUFFERED_LINES)

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    /** 最近一次日志文件，供分享使用 */
    @Volatile
    var lastLogFile: File? = null
        private set

    /** 开始一次新的安装日志会话 */
    @Synchronized
    fun startSession(context: Context) {
        closeQuietly()
        buffered.clear()

        val ts = LogLineFormat.stamp(System.currentTimeMillis())
        val dir = resolveLogDir(context)
        if (dir != null && (dir.exists() || dir.mkdirs())) {
            val file = File(dir, "install_$ts.log")
            currentFile = file
            lastLogFile = file
            writer = try {
                BufferedWriter(FileWriter(file, false))
            } catch (_: Exception) {
                null
            }
        }

        info("==== UFI-AXIS 安装日志 $ts ====")
    }

    /** 优先应用专属外部目录，其次内部目录 */
    private fun resolveLogDir(context: Context): File? {
        val external = context.getExternalFilesDir(null)
        return if (external != null) File(external, "log") else File(context.filesDir, "log")
    }

    /** 结束会话，写入结束语 */
    @Synchronized
    fun endSession(success: Boolean, reason: String? = null) {
        if (success) {
            info("===== 安装成功 =====")
        } else {
            error("===== 安装失败: ${reason ?: "未知原因"} =====")
        }
        closeQuietly()
    }

    fun info(message: String) = log(LogLine.Level.INFO, message)
    fun ok(message: String) = log(LogLine.Level.OK, message)
    fun warn(message: String) = log(LogLine.Level.WARN, message)
    fun error(message: String) = log(LogLine.Level.ERROR, message)

    /** 原样追加设备返回的多行输出，统一加前缀便于区分 */
    fun output(text: String, prefix: String = "    ") {
        text.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { info(prefix + it.trimEnd()) }
    }

    @Synchronized
    private fun log(level: LogLine.Level, message: String) {
        val line = LogLine(System.currentTimeMillis(), level, message)

        if (buffered.size >= MAX_BUFFERED_LINES) buffered.pollFirst()
        buffered.addLast(line)
        _lines.tryEmit(line)

        try {
            writer?.apply {
                write(line.format())
                newLine()
                // 关键节点立即落盘，崩溃时也能保住现场
                if (level != LogLine.Level.INFO) flush()
            }
        } catch (_: Exception) {
            // 落盘失败不影响主流程
        }
    }

    /** 强制刷新到磁盘 */
    @Synchronized
    fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    /** 当前缓冲的所有日志文本（用于复制到剪贴板 / 写入文件） */
    @Synchronized
    fun snapshot(): String = buffered.joinToString("\n") { it.format() }

    /** 当前缓冲的结构化日志（用于界面回填，保留颜色分级） */
    @Synchronized
    fun bufferedLines(): List<LogLine> = buffered.toList()

    @Synchronized
    fun clearBuffer() {
        buffered.clear()
    }

    @Synchronized
    private fun closeQuietly() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
