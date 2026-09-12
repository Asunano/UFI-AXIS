package com.ufi_axis_core.util

import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户可见日志 — 输出到 /sdcard/Download/UFI-AXIS/log/core/crash/（文件管理器可直接查看）。
 *
 * 与 AppLogger（应用私有目录 /data/.../logs/）互补：ERROR 级日志与崩溃堆栈镜像到
 * Download 目录，便于在设备上直接排查"服务无法链接"等问题。
 *
 * 2026-09-12 起崩溃堆栈镜像收进 `log/core/crash/crash.log`，与 [com.ufi_axis_core.UfiAxisCoreApplication]
 * 的逐次崩溃 dump（crash/crash_<ms>.txt）同目录，不再平铺在 `log/core/` 根。
 */
object DownloadLog {

    // 2026-09-12：崩溃堆栈镜像收进 `log/core/crash/crash.log`（与 CrashHandler 逐次 dump 同目录，见 [LogPaths]）。
    private val dir = File(LogPaths.crashLogDir())

    /** 单文件上限 1MB，超限保留尾部 200KB。 */
    private const val MAX_BYTES = 1_048_576L
    private const val KEEP_BYTES = 200L * 1024

    @Synchronized
    fun append(fileName: String, message: String) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val f = File(dir, fileName)
            if (f.exists() && f.length() > MAX_BYTES) {
                val bytes = f.readBytes()
                f.writeBytes(bytes.copyOfRange((bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0), bytes.size))
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            f.appendText("[$ts] $message\n")
        } catch (_: Exception) {
            // 日志失败静默（外部存储不可用等），不影响业务
        }
    }
}

/**
 * 协程安全的 runCatching：CancellationException 一律 rethrow。
 *
 * 在协程里用普通 runCatching 包 suspend 调用会吞掉 CancellationException，破坏取消
 * 机制——在 Ktor/Netty 上下文中会引发 CompletionHandlerException（event loop 线程
 * 死亡 → HTTP 服务器瘫痪，表现为"服务无法链接"）。异步并行段（async/runCatching）
 * 一律使用本函数。
 */
suspend fun <T> runCatchingSafe(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
