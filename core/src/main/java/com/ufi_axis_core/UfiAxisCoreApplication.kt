package com.ufi_axis_core

import android.app.Application
import android.os.StrictMode
import com.ufi_axis_core.service.BackendService
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class UfiAxisCoreApplication : Application() {

    companion object {
        @Volatile
        var instance: UfiAxisCoreApplication? = null
            private set

        /** `logs/crash_*.txt` 的保留份数上限。 */
        private const val MAX_CRASH_FILES = 20
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── StrictMode：debug 与 release 均启用轻量主线程 IO 守卫 ──
        // 仅 penaltyLog（不崩溃/不阻塞），用于发现主线程 disk/network 违规，
        // 不影响性能敏感路径（违规发生时才记录日志）。release 也开启，避免主线程 IO 无守卫。
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )

        // 创建数据目录 (优先使用 app 私有目录，root 环境下尝试 /data/ufiaxis)
        val dataDir = tryGetRootDataDir() ?: File(filesDir, "ufiaxis")
        listOf("db", "logs", "cache", "config").forEach {
            File(dataDir, it).mkdirs()
        }

        try {
            // 开关必须在 init 之后、第一条日志之前恢复：否则关掉日志的用户
            // 每次进程启动仍会写一条启动行 + 建一个当天的日志文件。
            //
            // 2026-09-04：改用 restoreSwitches 一次性恢复**三层**。此前这里只恢复了
            // log_enabled / core_log_enabled，debug_mode 只在 ComponentFactory.build() 里恢复——
            // 而 build() 只在 BackendService 真正起来时才跑。于是 autoStartOnBoot=false、
            // 或进程只被 BootReceiver / InstallReceiver 拉起时，AppLogger 的详细级别一直用
            // 进程内初值（当时硬编码 true）→ 用户明明关了「详细日志」，core 重启后 DEBUG/INFO
            // 又全量落盘。这就是「日志总是会自动开启」的 core 侧根因。
            val settings = AppSettings.getInstance(this)
            AppLogger.restoreSwitches(
                logEnabled = settings.logEnabled,
                coreLogEnabled = settings.coreLogEnabled,
                debugMode = settings.debugMode
            )
            AppLogger.init(dataDir)
            AppLogger.i("App", "UFI-AXIS-Core started, dataDir=${dataDir.absolutePath}")
        } catch (e: Exception) {
            // AppLogger 初始化失败不影响后续逻辑（文件写入会静默跳过）
        }

        // ── 全局未捕获异常处理器：崩溃日志持久化 + 确保进程按系统默认行为退出/重启 ──
        // 单一 handler，覆盖 Application 默认 handler 后务必 rethrow（调用原 defaultHandler），
        // 保证 crash 落盘且进程退出由系统处理（headless 服务靠 START_STICKY / AMS 重启）。
        // 不依赖 UI Looper / Toast，避免 headless 场景遗漏或阻塞。
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // CompletionHandlerException 是 kotlinx-coroutines 内部已知良性问题（子协程取消时
            // ChildCompletion 通知父协程失败），对业务无影响，跳过以免误杀进程。
            // 注意: 该类是 @InternalCoroutinesApi，不能用 is 判断，按类名比对。
            val isBenign = throwable.javaClass.name == "kotlinx.coroutines.CompletionHandlerException"
            if (isBenign) {
                AppLogger.w("CrashHandler", "Coroutine completion handler issue (benign) on '${thread.name}': ${throwable.message}")
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                // 2026-09-04：崩溃前的日志现场。崩溃不受日志开关约束，但此前 crash 文件里
                // 只有堆栈 —— 关掉详细日志后（release 默认）上游线索一条不留，等于拿到一份
                // 无法定位的崩溃。AppLogger 的内存缓冲在详细日志关闭时保留 WARN/ERROR 链，
                // 这里把它一并落盘。
                val recent = runCatching { AppLogger.dumpRecentForCrash() }.getOrDefault(emptyList())
                val context = if (recent.isEmpty()) {
                    "(log buffer empty —— 日志总开关或 core 日志被关闭)"
                } else {
                    recent.joinToString("\n")
                }
                val headline = "FATAL CRASH in thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}"
                val crashLog = "$headline\n$sw" +
                    "\n----- Recent core log buffer (${recent.size} entries) -----\n$context\n"
                // T18 同类：堆栈/异常消息里可能带 token（如 URL query），落盘前统一脱敏一次。
                // AppLogger.e 内部会脱敏，但下面两处直接写文件的路径此前是原文。
                val safeCrashLog = AppLogger.desensitize(crashLog)
                // 1) 记录到日志
                AppLogger.e("CrashHandler", crashLog)
                // 2) 持久化到文件（AppLogger 崩溃时可能来不及 flush，确保落盘）
                //
                // ── 两份 dump 的分工（刻意冗余，不是重复）──
                // 这一份在**私有目录**：不需要外部存储权限、存储没挂载也能写，是崩溃留证的底线；
                // 每次崩溃一个独立文件（`crash_<ms>.txt`，[MAX_CRASH_FILES] 份轮换），
                // 所以崩溃重启循环的每一轮都能分别取回；它的绝对路径还会被
                // [AppSettings.recordCrash] 记下，供 `GET /api/service/crash` 回读（见下方 4）。
                // 代价是这台设备没有 root，用户拿文件管理器进不去 —— 只能经 API 取。
                // 下面第 3 步那一份在 Download 下：用户能直接翻，但它是 append 到单个
                // `crash.log`（[DownloadLog] 1MB 上限、超限只保尾部 200KB），
                // 连续崩溃会把最早那几次挤掉，且依赖外部存储可用。
                // 两者的失效场景与取回方式都不重叠，缺任一份都会丢掉一类崩溃现场。
                val crashFile = File(dataDir, "logs/crash_${System.currentTimeMillis()}.txt")
                crashFile.parentFile?.mkdirs()
                try { crashFile.writeText(safeCrashLog) } catch (_: Exception) {}
                // 只保留最近 MAX_CRASH_FILES 份：这些 crash_*.txt 平铺在 logs/ 下，
                // 不在 AppLogger.cleanOldLogs() 的日期目录扫描范围内 —— 此前**完全没有上限**，
                // 崩溃重启循环能把它们堆到磁盘满。
                trimCrashFiles(File(dataDir, "logs"))
                // 3) 2026-08-22：镜像到用户可见的 Download 目录（完整堆栈，文件管理器直接查看）
                com.ufi_axis_core.util.DownloadLog.append("crash.log", safeCrashLog.replace("\n", "\n    "))
                // 4) 2026-09-04：留一个持久化标记，供 `GET /api/service/crash` 回读 ——
                // core 崩溃后由 keepalive / START_STICKY 自动拉起，前端此前完全无感。
                // 必须在 rethrow 之前写且用 commit()（见 AppSettings.recordCrash）。
                runCatching {
                    AppSettings.getInstance(this).recordCrash(
                        timestamp = System.currentTimeMillis(),
                        summary = AppLogger.desensitize(headline),
                        file = crashFile.absolutePath
                    )
                }
            } catch (_: Exception) {
                // 日志/落盘失败绝不可吞掉原始异常
            }
            // 3) 务必 rethrow：让系统默认行为继续执行（进程退出 + 受 START_STICKY/AMS 重启）
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 自动启动后端服务（如果已启用）
        val settings = AppSettings.getInstance(this)
        if (settings.autoStartOnBoot && !BackendService.isRunning) {
            try {
                BackendService.start(this)
                AppLogger.i("App", "BackendService auto-started from Application")
            } catch (e: Exception) {
                AppLogger.e("App", "Failed to auto-start BackendService", e)
            }
        }
    }

    private fun tryGetRootDataDir(): File? {
        return try {
            val dir = File("/data/ufiaxis")
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (e: Exception) {
            null
        }
    }

    /** `logs/crash_*.txt` 只留最近 [MAX_CRASH_FILES] 份（按修改时间，删最旧）。 */
    private fun trimCrashFiles(logsDir: File) {
        try {
            val files = logsDir.listFiles { f ->
                f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt")
            }?.sortedBy { it.lastModified() } ?: return
            val excess = files.size - MAX_CRASH_FILES
            if (excess > 0) files.take(excess).forEach { it.delete() }
        } catch (_: Exception) {
            // 裁剪失败不能影响崩溃主流程
        }
    }
}
