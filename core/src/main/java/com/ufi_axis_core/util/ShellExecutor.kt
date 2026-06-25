package com.ufi_axis_core.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shell 命令执行器
 * 用于执行 AT 指令、读取系统文件等需要 root 权限的操作
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_OUTPUT_SIZE = 1_048_576 // 1MB 输出上限，防止 OOM

    /** 持久化 root shell，复用同一个 su 进程避免重复 fork */
    private val rootShell = PersistentRootShell()

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 执行普通 shell 命令
     * 使用 ProcessBuilder + redirectErrorStream(true) 合并 stdout/stderr，
     * 避免管道缓冲区满导致死锁；协程超时 + 进程超时双重保障。
     * finally 块确保超时后子进程被杀死，防止进程泄漏。
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            var process: Process? = null
            try {
                process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = readLimitedOutput(process, MAX_OUTPUT_SIZE)
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    ShellResult(-1, output.trim(), "Process timed out and was killed")
                } else {
                    ShellResult(process.exitValue(), output.trim(), "")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                process?.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process?.destroyForcibly()
                ShellResult(-1, "", e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 以 root 权限执行命令 (su -c)
     * 使用 ProcessBuilder + redirectErrorStream(true) 合并 stdout/stderr，
     * 避免管道缓冲区满导致死锁；协程超时 + 进程超时双重保障。
     * finally 块确保超时后子进程被杀死，防止进程泄漏。
     */
    suspend fun executeAsRoot(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            rootShell.execute(command)
        }
    }

    /**
     * 限制读取进程输出大小，防止 OOM。
     * 使用 .use {} 确保 BufferedReader / InputStream 在完成后关闭，
     * 避免每次 shell 执行泄漏文件描述符导致 "Too many open files"。
     */
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

    /**
     * 批量执行多条 root 命令（逐条执行，无并发限流）。
     * 提供给前端路由绕过 ShellQoS 直接调用，避免用户操作被设备通信限流影响。
     */
    suspend fun batchExecuteAsRoot(
        commands: List<String>,
        timeoutMs: Long = 30_000L
    ): BatchResult {
        val results = commands.map { executeAsRoot(it, timeoutMs) }
        return BatchResult(results, results.map { it.isSuccess })
    }

    /**
     * 读取 /proc 或 /sys 文件内容
     */
    suspend fun readSystemFile(path: String): String? {
        val result = execute("cat $path")
        return if (result.isSuccess) result.stdout else null
    }

    /**
     * 检查是否具有 root 权限
     */
    suspend fun hasRootAccess(): Boolean {
        val result = executeAsRoot("id")
        return result.isSuccess && result.stdout.contains("uid=0")
    }
}

/**
 * 持久化 root shell，复用同一个 [su] 进程避免重复 fork。
 *
 * 核心原理：启动一个交互式 [su] 进程后通过 stdin 发命令、stdout 读结果，
 * 用唯一 marker 分隔每次命令的输出边界。
 *
 * 线程安全：通过 [Mutex] 保证同一时刻只有一个协程在读写 shell 管道。
 * 自动恢复：检测到 shell 进程死亡后，下次执行自动重启。
 */
private class PersistentRootShell {

    private var shellProcess: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val mutex = Mutex()

    /**
     * 在持久化 root shell 中执行一条命令。
     *
     * 写入格式：`command 2>&1`（合并 stderr），然后 `echo MARKER$?` 同时输出
     * 结束标记和退出码。读取 stdout 直到找到 MARKER，解析退出码。
     */
    suspend fun execute(command: String): ShellExecutor.ShellResult = mutex.withLock {
        ensureRunning()

        val marker = "___END_${System.nanoTime()}___"
        writer?.let { w ->
            w.write("$command 2>&1\necho ${marker}\$?\n")
            w.flush()
        } ?: return@withLock ShellExecutor.ShellResult(-1, "", "Persistent shell not running")

        val output = StringBuilder()
        var exitCode = -1
        var foundMarker = false

        while (true) {
            val line = try {
                reader?.readLine()
            } catch (e: Exception) {
                null
            }
            if (line == null) {
                // shell 进程异常结束
                destroy()
                return@withLock ShellExecutor.ShellResult(
                    exitCode,
                    output.toString().trim(),
                    "Persistent shell process terminated unexpectedly"
                )
            }
            if (line.startsWith(marker)) {
                exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                foundMarker = true
                break
            }
            output.appendLine(line)
        }

        if (!foundMarker) {
            destroy()
            return@withLock ShellExecutor.ShellResult(-1, output.toString().trim(), "Failed to read command marker")
        }

        ShellExecutor.ShellResult(exitCode, output.toString().trim(), "")
    }

    /**
     * 确保 root shell 正在运行。如果进程已死或未启动，自动重启。
     */
    private fun ensureRunning() {
        if (shellProcess?.isAlive != true) {
            destroy()
            shellProcess = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            writer = OutputStreamWriter(shellProcess!!.outputStream)
            reader = shellProcess!!.inputStream.bufferedReader()
        }
    }

    /**
     * 清理资源。
     */
    private fun destroy() {
        try {
            shellProcess?.destroyForcibly()
        } catch (e: Exception) {
            AppLogger.w("ShellExecutor", "Failed to destroy shell process: ${e.message}")
        }
        try {
            writer?.close()
        } catch (e: Exception) {
            AppLogger.w("ShellExecutor", "Failed to close shell writer: ${e.message}")
        }
        writer = null
        try {
            reader?.close()
        } catch (e: Exception) {
            AppLogger.w("ShellExecutor", "Failed to close shell reader: ${e.message}")
        }
        reader = null
    }
}
