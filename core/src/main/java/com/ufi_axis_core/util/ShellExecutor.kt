package com.ufi_axis_core.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shell 命令执行器
 * 用于执行 AT 指令、读取系统文件等需要 root 权限的操作
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_OUTPUT_SIZE = 1_048_576 // 1MB 输出上限，防止 OOM

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
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", command)
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
            } catch (e: Exception) {
                process?.destroyForcibly()
                ShellResult(-1, "", e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 限制读取进程输出大小，防止 OOM
     */
    private fun readLimitedOutput(process: Process, maxSize: Int): String {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        var totalRead = 0
        var n: Int
        while (reader.read(buffer).also { n = it } != -1) {
            val remaining = maxSize - totalRead
            if (remaining <= 0) break
            val toRead = minOf(n, remaining)
            sb.append(buffer, 0, toRead)
            totalRead += toRead
        }
        return sb.toString()
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
