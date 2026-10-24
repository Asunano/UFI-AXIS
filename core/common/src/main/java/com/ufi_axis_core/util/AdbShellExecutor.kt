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

    // ── `adb connect` 的重试与复用（2026-10-14）──
    //
    // 原来每次 execute 都跑一遍 `adb connect`，且**只等一次 5s**。首次 connect 常常正好落在
    // 「daemon not running; starting now at tcp:5037」上——server 正在 fork，5s 内拿不到
    // "connected" 就判失败。真机日志里这就是 `ADB not connected yet` 与 `executor ready`
    // 交替出现的来源，直接后果是 keepalive 脚本的写盘/拉起成片失败（68 条 rc=1）。
    //
    // 两条改动：① 失败后重试一次（此时 server 已起来，第二次基本必成）；② 成功后 60s 内
    // 不再重复 connect（连接在 adb server 里是持久的，没必要每条命令握一次手）。
    private const val CONNECT_ATTEMPTS = 2
    private const val CONNECT_TIMEOUT_SEC = 4L
    private const val CONNECT_RETRY_DELAY_MS = 400L
    private const val CONNECT_CACHE_TTL_MS = 60_000L

    @Volatile private var adbBinaryPath: String? = null
    private var homeDir: String = "/data/local/tmp"

    /** 最近一次 `adb connect` 成功的时刻（单调性无关，仅用于 60s 复用窗口判定）。 */
    @Volatile private var lastConnectOkAtMs = 0L

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
            val result = runAdbShellCommand(adbPath, command, timeoutMs)
            // 命令级失败（超时/管道异常）说明这条连接已经不可信：作废复用窗口，
            // 让下一次调用重新 `adb connect`，而不是在坏连接上继续重试到天荒地老。
            if (result.exitCode == -1) {
                lastConnectOkAtMs = 0L
                isAvailable = false
            }
            result
        } catch (e: CancellationException) {
            // 取消必须重抛。早期实现把它转成 exitCode=-1 的普通结果，于是调用方的
            // withTimeout / scheduler stop() 不再经由这一帧解栈，而且「设备拒绝」和
            // 「我们正在关停」变得无法区分 —— 要不要回退到普通 shell 是调用方的决定，
            // 不该由一个被丢弃的取消信号替它做。
            throw e
        } catch (e: Exception) {
            lastConnectOkAtMs = 0L
            isAvailable = false
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
        lastConnectOkAtMs = 0L
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
            // 2026-10-13：这里**不能**用「读到 EOF」的 readLimitedOutput。
            // adb client 会 fork 一个常驻 adb server，server 继承 client 的 stdout 管道；
            // client 退出（或被杀）后写端仍被 server 持有，读到 EOF 会**永久阻塞** ——
            // 而阻塞读不响应协程取消，外层的 withTimeout 也救不回来。真机上表现为
            // KeepAliveWatchdog 打完 "launching anyway" 后再无任何日志、adb 进程变僵尸。
            // 改用有界轮询读取：拿到已缓冲的输出就走，绝不无限等 EOF。
            val output = readLimitedOutputBounded(process, MAX_OUTPUT_SIZE, if (finished) 1_500L else 800L)
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

        // 复用窗口：连接在 adb server 侧是持久的，60s 内不必再握一次手。
        // 这一步同时消掉了「每条命令都可能撞上 server 冷启动」这个最大的失败来源。
        val now = System.currentTimeMillis()
        if (isAvailable && lastConnectOkAtMs > 0L && now - lastConnectOkAtMs < CONNECT_CACHE_TTL_MS) {
            return true
        }

        var lastOut = ""
        for (attempt in 1..CONNECT_ATTEMPTS) {
            val out = try {
                val p = ProcessBuilder(adbPath, "connect", "localhost:$ADB_PORT")
                    .apply { withEnv() }
                    .redirectErrorStream(true)
                    .start()
                val finished = p.waitFor(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                // 同 runAdbShellCommand：adb 可能 fork 常驻 server 继承管道，禁止读到 EOF
                val text = readLimitedOutputBounded(p, 4096, if (finished) 1_000L else 500L)
                if (!finished) try { p.destroyForcibly() } catch (_: Exception) {}
                text
            } catch (e: Exception) {
                lastError = "ensureConnected exception: ${e.message}"
                return false
            }

            lastOut = out
            if (out.contains("connected", ignoreCase = true)) {
                lastConnectOkAtMs = System.currentTimeMillis()
                isAvailable = true
                return true
            }
            // 首次失败多半是「daemon not running; starting now」——server 正在 fork。
            // 稍等再试一次，第二次基本必成（见 CONNECT_ATTEMPTS 注释）。
            if (attempt < CONNECT_ATTEMPTS) {
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        lastError = "adb connect failed after $CONNECT_ATTEMPTS attempts: $lastOut"
        return false
    }

    private fun ProcessBuilder.withEnv(): ProcessBuilder = apply {
        environment()["ANDROID_ADB_HOME"] = homeDir
        environment()["ANDROID_USER_HOME"] = homeDir
        environment()["HOME"] = homeDir
        environment()["ANDROID_SDK_HOME"] = homeDir
    }

    /**
     * 有界读取子进程输出：只在 [graceMs] 内轮询 `available()`，拿到已缓冲的数据就返回，
     * **绝不等待 EOF**。
     *
     * 为什么不能用「读到 EOF」的读法：adb client 会 fork 常驻 adb server，server 继承
     * client 的 stdout 管道写端。client 退出/被杀后写端仍被 server 持有，`read()` 永远等不到
     * EOF → 永久阻塞；而阻塞读不响应协程取消，外层 `withTimeout` 也中断不了它。
     * 真机实测表现为看门狗拉起流程静默卡死、adb 进程变僵尸。
     */
    private fun readLimitedOutputBounded(process: Process, maxSize: Int, graceMs: Long): String {
        val sb = StringBuilder()
        val buffer = ByteArray(8192)
        val input = process.inputStream
        val deadline = System.currentTimeMillis() + graceMs
        var idleSince = System.currentTimeMillis()
        while (sb.length < maxSize) {
            val available = try { input.available() } catch (_: Exception) { 0 }
            if (available > 0) {
                val n = try { input.read(buffer, 0, minOf(available, buffer.size)) } catch (_: Exception) { -1 }
                if (n < 0) break
                sb.append(String(buffer, 0, n, Charsets.UTF_8))
                idleSince = System.currentTimeMillis()
                continue
            }
            val now = System.currentTimeMillis()
            // 进程已退出且静默 200ms（数据已取尽），或超出宽限预算 → 收工
            if ((!process.isAlive && now - idleSince >= 200) || now >= deadline) break
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
        return sb.toString()
    }
}
