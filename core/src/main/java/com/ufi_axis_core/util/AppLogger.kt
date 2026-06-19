package com.ufi_axis_core.util

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 统一日志管理器
 * 支持 Logcat 输出 + 文件持久化（按天滚动）
 *
 * 日志文件存储路径: /data/ufiaxis/logs/
 * - app_yyyy-MM-dd.log   应用日志
 * - at_yyyy-MM-dd.log    AT 指令日志
 * - error_yyyy-MM-dd.log 错误日志
 */
object AppLogger {

    private const val TAG = "UFI-AXIS-Core"
    private const val MAX_LOG_FILES = 7  // 保留最近 7 天
    private const val MAX_BUFFER_SIZE = 500  // 内存缓冲区最大条目数

    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
    enum class LogType(val prefix: String) {
        APP("app"), AT("at"), ERROR("error")
    }

    private var logDir: File? = null
    private val logBuffer = ConcurrentLinkedQueue<String>()

    @Volatile
    private var bufferEnabled: Boolean = true  // 缓冲区总开关

    @Volatile
    private var bufferMinLevel: LogLevel = LogLevel.DEBUG  // 调试模式默认缓存全部级别

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 初始化日志目录
     */
    fun init(baseDir: File) {
        logDir = File(baseDir, "logs").also { it.mkdirs() }
        cleanOldLogs()
    }

    /**
     * 设置调试模式：
     * - 开启: 缓冲区缓存全部级别日志 (DEBUG+)
     * - 关闭: 完全停止缓冲区写入并清空已有日志（Logcat 和文件持久化不受影响）
     */
    fun setDebugMode(enabled: Boolean) {
        bufferEnabled = enabled
        bufferMinLevel = if (enabled) LogLevel.DEBUG else LogLevel.ERROR
        if (!enabled) {
            logBuffer.clear()
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, LogType.APP, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, LogType.APP, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, LogType.APP, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, LogType.ERROR, tag, message)
        throwable?.let {
            log(LogLevel.ERROR, LogType.ERROR, tag, it.stackTraceToString())
        }
    }

    /**
     * 记录 AT 指令日志
     */
    fun at(direction: String, command: String, response: String = "") {
        val entry = "[$direction] $command${if (response.isNotEmpty()) " -> $response" else ""}"
        log(LogLevel.INFO, LogType.AT, "AT", entry)
    }

    /**
     * 获取内存缓冲区中的日志条目
     * @param level 按级别过滤 (DEBUG/INFO/WARN/ERROR)，null 表示不过滤
     * @param limit 返回最大条目数
     */
    fun getBufferedLogs(level: String? = null, limit: Int = 200): List<String> {
        val all = logBuffer.toList()
        val filtered = if (level != null) all.filter { it.contains("[$level]") } else all
        return filtered.takeLast(limit)
    }

    /**
     * 清空内存缓冲区
     */
    fun clearBuffer() { logBuffer.clear() }

    private fun log(level: LogLevel, type: LogType, tag: String, message: String) {
        val time = LocalTime.now().format(timeFormat)
        val entry = "$time [${level.name}] [$tag] $message"

        // Logcat 输出
        when (level) {
            LogLevel.DEBUG -> Log.d("$TAG/$tag", message)
            LogLevel.INFO -> Log.i("$TAG/$tag", message)
            LogLevel.WARN -> Log.w("$TAG/$tag", message)
            LogLevel.ERROR -> Log.e("$TAG/$tag", message)
        }

        // 文件持久化
        writeToFile(type, entry)

        // 内存环形缓冲区（受 bufferEnabled 和 bufferMinLevel 控制）
        if (bufferEnabled && level.ordinal >= bufferMinLevel.ordinal) {
            logBuffer.add(entry)
            while (logBuffer.size > MAX_BUFFER_SIZE) logBuffer.poll()
        }
    }

    private fun writeToFile(type: LogType, entry: String) {
        val dir = logDir ?: return
        val date = LocalDate.now().format(dateFormat)
        val file = File(dir, "${type.prefix}_$date.log")

        try {
            PrintWriter(FileWriter(file, true)).use { pw ->
                pw.println(entry)
            }
        } catch (e: Exception) {
            // 写日志文件失败时只输出到 Logcat
            Log.e("$TAG/Logger", "Failed to write log file: ${e.message}")
        }
    }

    /**
     * 清理过期日志
     */
    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - MAX_LOG_FILES * 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter {
            it.isFile && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }
}
