package com.ufi_axis.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APP 侧日志落盘（2026-08-28 新增）。
 *
 * 在此之前手机端日志**只在内存**（[AppLogBuffer] 500 条环形缓冲），杀进程即丢，
 * 用户想事后翻「昨天那次连不上是什么错」根本没有文件可看。
 *
 * 目录与 core 对称，都在用户可直接浏览的公共 Download 下：
 * ```
 * /sdcard/Download/UFI-AXIS/log/app/<yyyy-MM-dd>/runtime.log   运行日志
 *                                               net.log       网络日志（tag 前缀 NET）
 *                                               error.log     WARN/ERROR
 * ```
 * 超过 [MAX_FILE_BYTES] 轮转成 `*.log.1`（只留一代），整个 app 日志根目录不超过
 * [MAX_DIR_BYTES]，并且只保留最近 [KEEP_DAYS] 天 —— 这三道闸门是为了避免重演
 * core 那次「几天涨到 500MB」。
 *
 * **与 core 完全隔离**：[deleteAll] 只删手机上的 app 日志，core 的日志文件由
 * `DELETE /api/debug-logs/files` 单独删，互不牵连。
 *
 * 落盘时机由 [AppLogBuffer.add] 统一触发（那是 app 侧所有日志的唯一汇聚点），
 * 级别策略与 [DebugLog] 一致：WARN/ERROR 只要总开关开着就落盘，DEBUG/INFO 还要
 * [DebugLog.enabled]（详细日志）打开。
 */
object AppFileLogger {

    private const val TAG = "AppFileLogger"

    /** 用户可直接查看的 app 日志根目录。 */
    private val USER_LOG_ROOT = File("/sdcard/Download/UFI-AXIS/log/app")

    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val MAX_DIR_BYTES = 40L * 1024 * 1024
    private const val KEEP_DAYS = 7

    /** 合法相对路径（`2026-08-28/net.log` / `2026-08-28/error.log.1`），防目录穿越。 */
    private val LOG_FILE_NAME = Regex("""^\d{4}-\d{2}-\d{2}/(runtime|net|error)\.log(\.1)?$""")

    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 日志根目录；外部存储不可写时回退到 `filesDir/logs`（保证极端情况下也有地方写）。 */
    @Volatile
    private var root: File? = null

    /** key = "类型_日期"，跨天自动切换。 */
    private val writers = HashMap<String, BufferedWriter>()

    @Volatile
    private var lastWriteDate: String? = null

    /** 在 `UfiAxisApplication.onCreate()` 调用一次；未初始化时 [append] 直接跳过。 */
    fun init(context: Context) {
        synchronized(lock) {
            root = if (USER_LOG_ROOT.exists() || USER_LOG_ROOT.mkdirs()) {
                USER_LOG_ROOT
            } else {
                Log.w(TAG, "external log dir unavailable, fallback to private dir")
                File(context.filesDir, "logs").also { it.mkdirs() }
            }
            lastWriteDate = dateFormat.format(Date())
            cleanOldLogs()
        }
    }

    /** 当前日志目录绝对路径，UI 上直接显示给用户去文件管理器找。 */
    fun dirPath(): String = root?.absolutePath ?: USER_LOG_ROOT.absolutePath

    /**
     * 落盘一条日志。由 [AppLogBuffer.add] 调用。
     *
     * 分类规则：WARN/ERROR → `error.log`；其余按 [LogKind] 分到 `net.log` / `runtime.log`。
     * 一条日志只写一个文件，不做镜像 —— 镜像等于体积翻倍，而错误在筛选里本来就能挑出来。
     */
    fun append(entry: LogEntry) {
        if (!DebugLog.active) return
        if (entry.level < LogLevel.WARN && !DebugLog.enabled) return
        val base = root ?: return
        val fileName = when {
            entry.level >= LogLevel.WARN -> "error"
            entry.kind == LogKind.NETWORK -> "net"
            else -> "runtime"
        }
        synchronized(lock) {
            val date = dateFormat.format(Date())
            if (lastWriteDate != date) {
                lastWriteDate = date
                cleanOldLogs()
            }
            val key = "${fileName}_$date"
            try {
                val dayDir = File(base, date)
                if (!dayDir.exists() && !dayDir.mkdirs()) return
                val current = File(dayDir, "$fileName.log")
                var writer = writers.getOrPut(key) { BufferedWriter(FileWriter(current, true), 4096) }
                if (current.exists() && current.length() > MAX_FILE_BYTES) {
                    runCatching { writer.close() }
                    writers.remove(key)
                    val rotated = File(dayDir, "$fileName.log.1")
                    if (rotated.exists()) rotated.delete()
                    current.renameTo(rotated)
                    writer = BufferedWriter(FileWriter(current, false), 4096)
                    writers[key] = writer
                    enforceTotalBudget()
                }
                writer.write(entry.format())
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                // 写失败（存储被拔、权限撤销）不能影响业务，丢掉 writer 下次重建
                runCatching { writers.remove(key)?.close() }
                Log.w(TAG, "write failed: ${e.message}")
            }
        }
    }

    /** 关闭所有 writer（关日志开关时释放句柄）。 */
    fun closeWriters() {
        synchronized(lock) {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }
    }

    /** 所有 app 日志文件（展平日期子目录）。 */
    private fun allFiles(): List<File> {
        val base = root ?: return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { day -> day.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() }
            ?: emptyList()
    }

    private fun relativeName(f: File): String = "${f.parentFile?.name}/${f.name}"

    /** 文件列表（新 → 旧），name 是 `日期/文件名` 相对路径。 */
    fun listFiles(): List<LogFileInfo> = allFiles()
        .sortedByDescending { it.lastModified() }
        .map { LogFileInfo(relativeName(it), it.length(), it.lastModified()) }

    fun totalBytes(): Long = allFiles().sumOf { it.length() }

    /** 读某个文件的尾部（默认 256KB）；名字非法或文件不存在返回 null。 */
    fun readTail(name: String, maxBytes: Int = 256 * 1024): String? {
        val base = root ?: return null
        if (!LOG_FILE_NAME.matches(name)) return null
        val f = File(base, name)
        if (!f.isFile) return null
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val from = (len - maxBytes).coerceAtLeast(0L)
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                if (from > 0) text.substringAfter('\n', text) else text
            }
        } catch (e: Exception) {
            Log.w(TAG, "readTail failed: ${e.message}")
            null
        }
    }

    /** 删除**手机端**全部日志文件，返回释放字节数。不影响 core 侧任何文件。 */
    fun deleteAll(): Long {
        synchronized(lock) {
            closeWriters()
            var freed = 0L
            allFiles().forEach {
                val size = it.length()
                if (it.delete()) freed += size
            }
            root?.listFiles()
                ?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }
                ?.forEach { it.delete() }
            return freed
        }
    }

    /** 按日期目录清理过期日志（保留最近 [KEEP_DAYS] 天），再校一次总量预算。 */
    private fun cleanOldLogs() {
        val base = root ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        base.listFiles()?.forEach { day ->
            if (!day.isDirectory) return@forEach
            val date = runCatching { dateFormat.parse(day.name) }.getOrNull() ?: return@forEach
            if (date.time < cutoff) {
                day.listFiles()?.forEach { it.delete() }
                day.delete()
            }
        }
        enforceTotalBudget()
    }

    /** 超预算时按修改时间从旧到新删，直到回到预算内。 */
    private fun enforceTotalBudget() {
        val files = allFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= MAX_DIR_BYTES) return
        for (f in files) {
            if (total <= MAX_DIR_BYTES) break
            val size = f.length()
            // 正在写的 writer 先关，否则删除后 writer 指向已删 inode，日志静默丢失
            val key = "${f.name.removeSuffix(".log.1").removeSuffix(".log")}_${f.parentFile?.name}"
            runCatching { writers.remove(key)?.close() }
            if (f.delete()) total -= size
        }
    }
}

/** 日志文件条目（app 侧本地文件，与 core 的 `DebugLogFileItem` 同构）。 */
data class LogFileInfo(
    val name: String,
    val size: Long,
    val modified: Long
)
