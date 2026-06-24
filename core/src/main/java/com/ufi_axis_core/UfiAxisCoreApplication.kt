package com.ufi_axis_core

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ufi_axis_core.service.BackendService
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class UfiAxisCoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 创建数据目录 (优先使用 app 私有目录，root 环境下尝试 /data/ufiaxis)
        val dataDir = tryGetRootDataDir() ?: File(filesDir, "ufiaxis")
        listOf("db", "logs", "cache", "config").forEach {
            File(dataDir, it).mkdirs()
        }

        try {
            AppLogger.init(dataDir)
            AppLogger.i("App", "UFI-AXIS-Core started, dataDir=${dataDir.absolutePath}")
        } catch (e: Exception) {
            // AppLogger 初始化失败不影响后续逻辑（文件写入会静默跳过）
        }

        // ── 全局未捕获异常处理器：防止静默崩溃 + 崩溃日志持久化 ──
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // CompletionHandlerException 是 kotlinx-coroutines 内部已知问题：
                // 子协程取消时 ChildCompletion 通知父协程失败导致，对业务无影响，
                // 跳过以免被默认处理器杀死进程。
                // 注意: CompletionHandlerException 是 @InternalCoroutinesApi，不能用 is 判断
                if (throwable.javaClass.name == "kotlinx.coroutines.CompletionHandlerException") {
                    AppLogger.w("CrashHandler", "Coroutine completion handler issue (benign) on '${thread.name}': ${throwable.message}")
                    return@setDefaultUncaughtExceptionHandler
                }

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashLog = "FATAL CRASH in thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}\n${sw}"
                AppLogger.e("CrashHandler", crashLog)

                // 持久化崩溃日志到文件（AppLogger 可能在崩溃时来不及 flush）
                val crashFile = File(dataDir, "logs/crash_${System.currentTimeMillis()}.txt")
                crashFile.parentFile?.mkdirs()
                crashFile.writeText(crashLog)

                // Toast 提示用户（从任意线程）
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "应用崩溃: ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                // 最后兜底：至少让默认处理器执行
            }
            // 继续执行默认行为（进程退出）
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
}
