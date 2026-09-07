package com.ufi_axis_core.util

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shell 命令执行器
 *
 * 特权命令通过 ADB shell (uid 2000) 执行，替代原 su root shell。
 * ADB shell 覆盖 pm/settings/am/svc/content/reboot/dumpsys 等所有场景。
 * 当 ADB shell 不可用时，fallback 到普通 shell（部分命令无需特权）。
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
     * 参数化执行：直接以 argv 列表启动进程，不经过 `sh -c` 字符串解析，
     * 避免对插值外部/设备侧输入做 shell 解析带来的命令注入面。
     * 适用于无需 shell 特性（管道、重定向、通配符、变量展开）的简单命令。
     * 行为与 [execute] 一致：合并 stdout/stderr、限制输出上限、超时杀进程。
     */
    suspend fun executeCommand(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            var process: Process? = null
            try {
                process = ProcessBuilder(args).redirectErrorStream(true).start()
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

    /** 便捷重载：直接传多个参数而非列表。 */
    suspend fun executeCommand(
        vararg args: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = executeCommand(args.toList(), timeoutMs)

    /**
     * 以特权权限执行命令
     * 优先通过 ADB shell (uid 2000)，失败时 fallback 到普通 shell。
     *
     * ADB shell 可执行：pm/settings/am/svc/content/reboot/dumpsys/getprop 等。
     * 不再依赖 su 进程。
     */
    suspend fun executeAsRoot(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            // 优先: ADB shell
            if (AdbShellExecutor.isAvailable) {
                val result = AdbShellExecutor.execute(command, timeoutMs)
                // ADB shell 返回了有效结果（非连接级错误）
                if (result.exitCode != -1 || result.stdout.isNotBlank()) {
                    return@withTimeout result
                }
                // ADB shell 连接级失败，fallback
                AppLogger.w("ShellExecutor", "ADB shell failed, falling back to normal shell: ${result.stderr}")
            }
            // Fallback: 普通 shell（部分命令不需要特权）
            execute(command, timeoutMs)
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
     * 批量执行多条特权命令（逐条执行，无并发限流）。
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
     * 优先直接 File I/O（world-readable），失败时 fallback 到 shell cat
     */
    suspend fun readSystemFile(path: String): String? {
        // 优先直接读取（/proc/meminfo, /proc/stat 等 world-readable）
        try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                return file.readText()
            }
        } catch (_: Exception) {}
        // Fallback: shell cat（参数化执行，避免路径经 sh -c 解析带来的注入面）
        val result = executeCommand("cat", path)
        return if (result.isSuccess) result.stdout else null
    }

    /**
     * 检查是否具有特权 shell（ADB shell 可用即视为有特权）
     */
    suspend fun hasRootAccess(): Boolean {
        if (AdbShellExecutor.isAvailable) return true
        // 兼容检查: 普通 shell 是否为 root（理论上不会）
        val result = executeCommand("id")
        return result.isSuccess && result.stdout.contains("uid=0")
    }
}
