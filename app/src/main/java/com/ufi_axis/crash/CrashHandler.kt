package com.ufi_axis.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import com.ufi_axis.util.AppLogBuffer
import kotlin.system.exitProcess
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器：在进程崩溃时把栈信息、设备信息与近期 logcat 写入
 * 公共下载目录 `Download/UFI-AXIS/<yyyy-MM-dd>/crash_<HH-mm-ss>.log`，
 * 方便用户通过文件管理器直接查看。随后仍交给系统默认处理器
 * （保留系统崩溃提示并终止进程）。下次启动由 MainActivity 检测并弹窗展示。
 *
 * 2026-09-04「release 默认关日志、但闪退必须留证据」：
 * - 崩溃**不受日志开关约束**（关掉日志≠放弃崩溃证据），但此前崩溃文件里只有
 *   「设备信息 + 堆栈 + 整机 logcat」，堆栈上游发生了什么全靠 logcat 里捞 —— 而
 *   `logcat -d` 是**全设备**日志，系统噪音把自己那几行冲得找不着。现在改为：
 *   1. 先 dump [AppLogBuffer]（app 自己的内存环形缓冲，关掉详细日志时里面是 WARN/ERROR 链）；
 *   2. logcat 只取**本进程**（`--pid`）且行数减半，只作为补充。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val BASE_DIR_NAME = "UFI-AXIS"
    private const val MAX_CRASH_FILES = 50
    private const val LOGCAT_LINES = 150
    private const val BUFFER_DUMP_LINES = 200

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        runCatching { writeCrashLog(thread, ex) }
        // 交给系统默认处理，保留原生崩溃提示并终止进程
        defaultHandler?.uncaughtException(thread, ex)
            ?: run {
                ex.printStackTrace()
                exitProcess(1)
            }
    }

    /**
     * 崩溃日志根目录：`Download/UFI-AXIS/`。
     * 优先使用公共 Download 目录（用户可直接通过文件管理器查看）；
     * 若外部存储不可用则回退到应用内部 filesDir/crash/。
     */
    private fun crashBaseDir(): File {
        // 优先尝试公共 Download 目录
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDir != null) {
            val base = File(publicDir, BASE_DIR_NAME)
            if (base.exists() || base.mkdirs()) return base
        }
        // 回退到应用内部存储
        val fallback = File(appContext.filesDir, "crash")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    /** 当日崩溃日志子目录：`<baseDir>/yyyy-MM-dd/` */
    private fun crashDirForToday(): File {
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(crashBaseDir(), dateDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    private fun writeCrashLog(thread: Thread, ex: Throwable) {
        val dir = crashDirForToday()

        val now = Date()
        val stamp = SimpleDateFormat("HH-mm-ss", Locale.US).format(now)
        val file = File(dir, "crash_$stamp.log")

        FileWriter(file, false).use { writer ->
            val pw = PrintWriter(writer)
            pw.println("===== UFI-AXIS CRASH REPORT =====")
            pw.println("Time     : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            pw.println("Thread   : ${thread.name} (id=${thread.id})")
            pw.println("Package  : ${appContext.packageName}")
            pw.println("AppVer   : ${appVersion()}")
            pw.println("Android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("Device   : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            pw.println("Brand    : ${Build.BRAND}")
            pw.println("MemAvail : ${availableMemoryMB()} MB")
            pw.println()
            pw.println("----- Exception -----")
            ex.printStackTrace(pw)
            pw.println()
            pw.println("----- App log buffer (last $BUFFER_DUMP_LINES entries) -----")
            runCatching {
                val recent = AppLogBuffer.snapshot().takeLast(BUFFER_DUMP_LINES)
                if (recent.isEmpty()) {
                    pw.println("(empty —— 日志总开关或手机端日志被关闭)")
                } else {
                    recent.forEach { pw.println(it.format()) }
                }
            }.onFailure { pw.println("(buffer dump failed: ${it.message})") }
            pw.println()
            pw.println("----- Recent logcat (own process pid=${Process.myPid()}, last $LOGCAT_LINES lines) -----")
            runCatching {
                val proc = Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-t", "$LOGCAT_LINES", "--pid=${Process.myPid()}"))
                proc.inputStream.bufferedReader().use { pw.println(it.readText()) }
                proc.destroy()
            }.onFailure { pw.println("(logcat capture failed: ${it.message})") }
            pw.flush()
        }

        trimOldFiles(dir)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    }.getOrDefault("unknown")

    private fun availableMemoryMB(): Long = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }.getOrDefault(-1)

    private fun trimOldFiles(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() } ?: return
        files.take(maxOf(0, files.size - MAX_CRASH_FILES)).forEach { it.delete() }
    }

    // region UI 读取接口
    /**
     * 列出所有崩溃日志文件（跨日期子目录），按修改时间倒序。
     * 同时扫描公共 Download 目录和应用内部 fallback 目录。
     */
    fun listCrashFiles(context: Context): List<File> {
        val result = mutableListOf<File>()
        // 扫描公共 Download 目录
        val publicBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicBase != null) {
            val base = File(publicBase, BASE_DIR_NAME)
            if (base.exists()) collectCrashFiles(base, result)
        }
        // 扫描应用内部 fallback 目录
        val internalDir = File(context.applicationContext.filesDir, "crash")
        if (internalDir.exists()) {
            result.addAll(
                internalDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
                    ?.toList() ?: emptyList()
            )
        }
        return result.sortedByDescending { it.lastModified() }
    }

    /** 递归收集日期子目录下的崩溃日志 */
    private fun collectCrashFiles(baseDir: File, out: MutableList<File>) {
        // 直接子文件（旧格式 crash_yyyy-MM-dd_HH-mm-ss.log）
        baseDir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?.let { out.addAll(it) }
        // 日期子目录（新格式 yyyy-MM-dd/crash_HH-mm-ss.log）
        baseDir.listFiles { f -> f.isDirectory }?.forEach { subDir ->
            subDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
                ?.let { out.addAll(it) }
        }
    }

    fun latestCrash(context: Context): File? = listCrashFiles(context).firstOrNull()

    fun clearCrashes(context: Context) {
        // 清理公共目录
        val publicBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicBase != null) {
            val base = File(publicBase, BASE_DIR_NAME)
            if (base.exists()) {
                base.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory) subDir.listFiles()?.forEach { it.delete() }
                    else subDir.delete()
                }
            }
        }
        // 清理内部 fallback 目录
        val internalDir = File(context.applicationContext.filesDir, "crash")
        internalDir.listFiles()?.forEach { it.delete() }
    }

    // 2026-08-11：闪退日志弹窗去重——记录"已读"的崩溃时间戳，避免同一进程重复弹出
    private const val PREFS_NAME = "ufi_crash_logs"
    private const val KEY_LAST_SHOWN = "last_shown_timestamp"

    /** 标记崩溃日志已展示过（写入 SharedPreferences） */
    fun markCrashShown(context: Context, timestamp: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SHOWN, timestamp)
            .apply()
    }

    /** 检查某崩溃日志是否已展示过（lastModified 相等即视为已读） */
    fun isCrashAlreadyShown(context: Context, timestamp: Long): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SHOWN, 0L) == timestamp
    }
    // endregion
}
