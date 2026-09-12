package com.ufi_axis_core.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ADB Shell 执行器 — 即用即取型（on-demand）
 *
 * 每次请求启动一个独立的 `adb -s localhost:PORT shell <cmd>` 进程，执行完成后立即销毁，
 * 不再维护持久化交互式 shell 会话。
 *
 * 相比旧的持久会话方案：
 *  - 不存在阻塞式 readLine 无法被 withTimeout 中断的问题（改用 process.waitFor(timeout) 真正限时）；
 *  - 单条命令挂死/超时只会销毁该进程，不会泄漏 IO 线程、拖垮整个特权通道；
 *  - 进程级隔离，命令之间互不影响。
 *
 * 复用内置 adb 二进制自连接 localhost:5555（uid 2000 shell）。
 * 每次 execute 先 ensureConnected（幂等，已连接则立即返回），再起进程执行。
 * 通过 `cmd; echo MARKER$?` 回显取设备侧命令的真实退出码。
 */
object AdbShellExecutor {

    private const val TAG = "AdbShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_OUTPUT_SIZE = 1_048_576
    private const val ADB_PORT = 5555

    @Volatile private var adbBinaryPath: String? = null
    private var homeDir: String = "/data/local/tmp"

    @Volatile var isAvailable: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * 初始化：设置 adb 二进制路径并做一次连通性检测（供 [isAvailable] 参考）。
     * 不再启动持久 shell；真正的连接在每次 [execute] 时按需建立。
     */
    suspend fun init(adbPath: String, context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        adbBinaryPath = adbPath
        homeDir = context?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val adb = File(adbPath)
        if (!adb.exists() || !adb.canExecute()) {
            lastError = "adb binary not found or not executable: $adbPath"
            AppLogger.e(TAG, lastError!!)
            isAvailable = false
            return@withContext false
        }
        // 尝试确保 adbd 正在监听（app 用户可能无权 setprop，忽略失败）
        try {
            ProcessBuilder("sh", "-c", "setprop service.adb.tcp.port $ADB_PORT 2>/dev/null")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        val connected = ensureConnected()
        isAvailable = connected
        if (connected) {
            AppLogger.i(TAG, "ADB shell executor ready (on-demand, port=$ADB_PORT)")
        } else {
            AppLogger.w(TAG, "ADB not connected yet: $lastError (will retry on demand)")
        }
        connected
    }

    /**
     * 执行单条命令（即用即取）。
     * 真实的执行失败/超时都返回 [ShellExecutor.ShellResult]（exitCode=-1），便于上层
     * [ShellExecutor.executeAsRoot] 回退到普通 shell；但**协程取消照常向上抛**。
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellExecutor.ShellResult = withContext(Dispatchers.IO) {
        val adbPath = adbBinaryPath
        if (adbPath == null) {
            lastError = "adb path not set"
            return@withContext ShellExecutor.ShellResult(-1, "", lastError!!)
        }
        if (!ensureConnected()) {
            isAvailable = false
            return@withContext ShellExecutor.ShellResult(-1, "", "adb not connected: $lastError")
        }
        try {
            runAdbShellCommand(adbPath, command, timeoutMs)
        } catch (e: CancellationException) {
            // 取消必须重抛。早期实现把它转成 exitCode=-1 的普通结果，于是调用方的
            // withTimeout / scheduler stop() 不再经由这一帧解栈，而且「设备拒绝」和
            // 「我们正在关停」变得无法区分 —— 要不要回退到普通 shell 是调用方的决定，
            // 不该由一个被丢弃的取消信号替它做。
            throw e
        } catch (e: Exception) {
            ShellExecutor.ShellResult(-1, "", e.message ?: "unknown")
        }
    }

    suspend fun batchExecute(
        commands: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): List<ShellExecutor.ShellResult> {
        if (commands.isEmpty()) return emptyList()
        // 即用即取：每条命令独立进程顺序执行（无持久会话可复用）
        return commands.map { execute(it, timeoutMs) }
    }

    suspend fun checkAvailability(): Boolean {
        val result = execute("id", 5000)
        isAvailable = result.isSuccess && result.stdout.contains("uid=")
        return isAvailable
    }

    fun shutdown() {
        isAvailable = false
        adbBinaryPath = null
    }

    // ── 内部实现 ──

    private suspend fun runAdbShellCommand(
        adbPath: String,
        command: String,
        timeoutMs: Long
    ): ShellExecutor.ShellResult {
        val marker = "___EXIT_${System.nanoTime()}___"
        val fullCmd = "$command; echo $marker\$?"
        val process = ProcessBuilder(adbPath, "-s", "localhost:$ADB_PORT", "shell", fullCmd)
            .apply { withEnv() }
            .redirectErrorStream(true)
            .start()
        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = readLimitedOutput(process, MAX_OUTPUT_SIZE)
            if (!finished) {
                process.destroyForcibly()
                ShellExecutor.ShellResult(-1, output.trim(), "ADB shell timed out after ${timeoutMs}ms")
            } else {
                parseWithExitMarker(output, marker)
            }
        } catch (e: CancellationException) {
            // 协程被取消：先销毁进程再上抛，由 execute() 转成 ShellResult
            try { process.destroyForcibly() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            try { process.destroyForcibly() } catch (_: Exception) {}
            ShellExecutor.ShellResult(-1, "", e.message ?: "unknown")
        }
    }

    private fun parseWithExitMarker(output: String, marker: String): ShellExecutor.ShellResult {
        val stdoutLines = mutableListOf<String>()
        var exitCode = 0
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith(marker)) {
                exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: 0
            } else {
                stdoutLines.add(raw)
            }
        }
        return ShellExecutor.ShellResult(exitCode, stdoutLines.joinToString("\n").trim(), "")
    }

    private fun ensureConnected(): Boolean {
        val adbPath = adbBinaryPath ?: return false
        try {
            val p = ProcessBuilder(adbPath, "connect", "localhost:$ADB_PORT")
                .apply { withEnv() }
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            val out = readLimitedOutput(p, 4096)
            if (!finished) try { p.destroyForcibly() } catch (_: Exception) {}
            val ok = out.contains("connected", ignoreCase = true)
            if (!ok) lastError = "adb connect failed: $out"
            return ok
        } catch (e: Exception) {
            lastError = "ensureConnected exception: ${e.message}"
            return false
        }
    }

    private fun ProcessBuilder.withEnv(): ProcessBuilder = apply {
        environment()["ANDROID_ADB_HOME"] = homeDir
        environment()["ANDROID_USER_HOME"] = homeDir
        environment()["HOME"] = homeDir
        environment()["ANDROID_SDK_HOME"] = homeDir
    }

    private fun readLimitedOutput(process: Process, maxSize: Int): String {
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        process.inputStream.bufferedReader().use { reader ->
            var totalRead = 0
            var n: Int
            while (reader.read(buffer).also { n = it } != -1) {
                val remaining = maxSize - totalRead
                if (remaining <= 0) break
                val toRead = minOf(n, remaining)
                sb.append(buffer, 0, toRead)
                totalRead += toRead
            }
        }
        return sb.toString()
    }
}
