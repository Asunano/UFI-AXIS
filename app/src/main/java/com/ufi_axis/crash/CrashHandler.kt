package com.ufi_axis.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.ufi_axis.util.AppLogBuffer
import com.ufi_axis.util.UfiLogPaths
import kotlin.system.exitProcess
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器：在进程崩溃时把栈信息、设备信息与近期 logcat 写入
 * `Download/UFI-AXIS/log/app/crash/<yyyy-MM-dd>/crash_<HH-mm-ss>.log`，
 * 方便用户通过文件管理器直接查看。随后仍交给系统默认处理器
 * （保留系统崩溃提示并终止进程）。下次启动由 MainActivity 检测并弹窗展示。
 *
 * 路径来自 [UfiLogPaths]（app 侧唯一一处路径约定）。2026-09-11 之前这里自己拼
 * `Download/UFI-AXIS/<yyyy-MM-dd>/`，**直接污染品牌根目录** —— 那正是 core
 * `ComponentFactory.migrateLegacyLogsOnce()` 要清掉的形态。旧文件由
 * [migrateLegacyCrashLogsOnce] 一次性搬到新位置。
 *
 * 2026-09-04「release 默认关日志、但闪退必须留证据」：
 * - 崩溃**不受日志开关约束**（关掉日志≠放弃崩溃证据），但此前崩溃文件里只有
 *   「设备信息 + 堆栈 + 整机 logcat」，堆栈上游发生了什么全靠 logcat 里捞 —— 而
 *   `logcat -d` 是**全设备**日志，系统噪音把自己那几行冲得找不着。现在改为：
 *   1. 先 dump [AppLogBuffer]（app 自己的内存环形缓冲，关掉详细日志时里面是 WARN/ERROR 链）；
 *   2. logcat 只取**本进程**（`--pid`）且行数减半，只作为补充。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val MAX_CRASH_FILES = 50
    private const val LOGCAT_LINES = 150
    private const val BUFFER_DUMP_LINES = 200

    /** 改造前的私有回退崩溃目录（`filesDir/crash`），只在一次性搬迁里出现。 */
    private const val LEGACY_PRIVATE_CRASH_DIR = "crash"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
        // 搬迁刻意**同步**跑在这里：MainActivity 组合时就会读 [latestCrash] 弹窗，
        // 放到后台线程会让「升级后第一次启动」有概率看不到升级前那次崩溃。
        // 代价是主线程上几次 renameTo（小文件、只有首次真正做事）。
        migrateLegacyCrashLogsOnce()
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

    /** 当日崩溃日志目录：`log/app/crash/<yyyy-MM-dd>/`，外部存储不可写时走私有回退。 */
    private fun crashDirForToday(): File? = UfiLogPaths.appCrashDir(appContext)

    @Synchronized
    private fun writeCrashLog(thread: Thread, ex: Throwable) {
        val dir = crashDirForToday() ?: return

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
        val files = dir.listFiles { f -> UfiLogPaths.isCrashFileName(f.name) }
            ?.sortedBy { it.lastModified() } ?: return
        files.take(maxOf(0, files.size - MAX_CRASH_FILES)).forEach { it.delete() }
    }

    // region 一次性搬迁：`UFI-AXIS/<日期>/crash_*.log` → `UFI-AXIS/log/app/crash/<日期>/`
    // 幂等靠三件事：进程内 [legacyMigrated] 标志、判据只认「日期目录 + crash_*.log」、
    // 搬完即删（renameTo 成功源文件就不存在，第二次跑匹配不到任何东西）。
    // **不会碰 core 的地盘**：品牌根下 core 的东西全在 `log/` 里，而 `log` 不是日期目录名，
    // 所以只认 `yyyy-MM-dd` 这一条判据就够（见 [UfiLogPaths.isDateDirName]）。
    // 将来不再需要支持从 2026-09-11 之前的版本直升时，可以把这一段连同标志位一起删掉。

    @Volatile
    private var legacyMigrated = false

    private fun migrateLegacyCrashLogsOnce() {
        if (legacyMigrated) return
        legacyMigrated = true
        runCatching {
            // 两处旧位置各自搬到**同一介质**的新位置：跨介质（filesDir → /sdcard）的 renameTo
            // 会直接失败，把私有目录里的旧崩溃搬到外部存储只会静默丢件。
            val jobs = buildList<Pair<File, (String) -> File?>> {
                UfiLogPaths.legacyBrandRoot()?.let { root ->
                    add(root to { date -> UfiLogPaths.appCrashDir(appContext, date) })
                }
                add(
                    File(appContext.filesDir, LEGACY_PRIVATE_CRASH_DIR) to { date ->
                        UfiLogPaths.privateFallbackDir(appContext, UfiLogPaths.appCrashRelative(date))
                            .takeIf { it.exists() || it.mkdirs() }
                    }
                )
            }
            for ((base, targetOf) in jobs) {
                val dateDirs = base.listFiles { f ->
                    f.isDirectory && UfiLogPaths.isDateDirName(f.name)
                } ?: continue
                for (dateDir in dateDirs) {
                    val target = targetOf(dateDir.name) ?: continue
                    val crashes = dateDir.listFiles { f ->
                        f.isFile && UfiLogPaths.isCrashFileName(f.name)
                    } ?: continue
                    crashes.forEach { f ->
                        val dst = File(target, f.name)
                        // 同名（同一天同一秒）几乎不可能；真撞上就保留已有的那份，不覆盖证据
                        if (!dst.exists()) f.renameTo(dst)
                    }
                    // 搬空了就把旧日期目录一并收掉，品牌根才真的干净
                    if (dateDir.listFiles().isNullOrEmpty()) dateDir.delete()
                }
            }
        }
    }
    // endregion

    // region UI 读取接口
    /** 崩溃日志文件（跨日期子目录 + 外部/私有两处），按修改时间倒序。 */
    fun listCrashFiles(context: Context): List<File> {
        val result = mutableListOf<File>()
        UfiLogPaths.appCrashRoots(context.applicationContext).forEach { root ->
            if (root.isDirectory) collectCrashFiles(root, result)
        }
        return result.sortedByDescending { it.lastModified() }
    }

    /** 收集崩溃根目录下各日期子目录里的 dump。 */
    private fun collectCrashFiles(crashRoot: File, out: MutableList<File>) {
        crashRoot.listFiles { f -> f.isDirectory }?.forEach { dateDir ->
            dateDir.listFiles { f -> f.isFile && UfiLogPaths.isCrashFileName(f.name) }
                ?.let { out.addAll(it) }
        }
    }

    fun latestCrash(context: Context): File? = listCrashFiles(context).firstOrNull()

    fun clearCrashes(context: Context) {
        UfiLogPaths.appCrashRoots(context.applicationContext).forEach { root ->
            if (!root.isDirectory) return@forEach
            root.listFiles()?.forEach { dateDir ->
                if (dateDir.isDirectory) {
                    dateDir.listFiles()?.forEach { it.delete() }
                    dateDir.delete()
                } else {
                    dateDir.delete()
                }
            }
        }
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
