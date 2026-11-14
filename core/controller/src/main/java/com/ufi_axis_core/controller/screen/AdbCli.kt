package com.ufi_axis_core.controller.screen

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AssetExtractor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极简 adb 客户端调用器（2026-10-31）。
 *
 * ## 为什么投屏还需要 adb 二进制
 * 因为 `adb reverse` 是 **adb server** 的功能，不是 adbd 的。scrcpy-server 会 `connect()`
 * 到 abstract socket `@scrcpy`，必须有东西在**监听**它 —— 那是 `adb reverse` 的职责。
 * 用 adbcore 的 `openStream("localabstract:scrcpy")` 不行：对 adbd 而言 `localabstract:`
 * 永远是"我去连"，方向反了（2026-10-31 实机验证：裸跑 server 报 Connection refused）。
 *
 * ## 成本
 * 每次会话只调 1–2 次（建立/拆除 reverse），不是每帧。视频与控制走的是
 * [AdbChannel] 之外的独立 TCP socket，不经过 adb。
 *
 * ## 与 [com.ufi_axis_core.util.AdbShellExecutor] 的分工
 * 那个是 `adb -s localhost:5555 shell <cmd>`（在设备上执行命令）；
 * 这个是 `adb reverse/list-forward`（操作 adb server 本身）。两者都用同一个 adb 二进制。
 *
 * ## ★ 每条命令都必须带 `-s localhost:5555`（2026-10-31 真机踩到）
 * 设备本机的 adb server 里**不止一个设备条目**。实测 `adb devices` 输出：
 * ```
 * 192.168.0.1:5555   device   ← 某个功能连的
 * emulator-5554      device   ← 历史残留
 * localhost:5555     device   ← AdbShellExecutor 自连接的那个
 * ```
 * 三条其实指向同一台机器，但 adb 只按名字判重。不带 `-s` 时所有命令直接失败：
 * `adb: error: more than one device/emulator`（rc=1）。
 *
 * 表现极具误导性：`reverse` 失败 → server 起不来 → WS 立刻关闭 →
 * 浏览器只看到「流解析失败：ExactReadable ended」，看起来像解码问题。
 */
class AdbCli(private val context: Context) {

    data class Result(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    @Volatile
    private var cachedPath: String? = null

    /** adb 二进制路径；找不到返回 null（调用方应报"adb 不可用"，不要假装成功） */
    fun adbPath(): String? {
        cachedPath?.let { return it }
        val path = runCatching { AssetExtractor.getPath(context, "adb") }.getOrNull() ?: return null
        return if (File(path).let { it.exists() && it.canExecute() }) {
            cachedPath = path
            path
        } else null
    }

    suspend fun run(vararg args: String, timeoutMs: Long = 10_000L): Result =
        withContext(Dispatchers.IO) {
            val adb = adbPath()
                ?: return@withContext Result(-1, "adb binary not found")
            try {
                val process = ProcessBuilder(listOf(adb, "-s", SERIAL) + args).apply {
                    // 与 AdbShellExecutor/AppManager 同款：把 HOME 指向 cacheDir，
                    // 否则 adb 会去读 /data/.android 之类的不可读目录。
                    val home = context.cacheDir.absolutePath
                    environment()["ANDROID_ADB_HOME"] = home
                    environment()["ANDROID_USER_HOME"] = home
                    environment()["HOME"] = home
                    environment()["ANDROID_SDK_HOME"] = home
                    redirectErrorStream(true)
                }.start()
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                // 与 AdbShellExecutor 同因同果：adb client 会 fork 常驻 adb server，
                // server 继承了 stdout 写端，读到 EOF 会永久阻塞 —— 只能有界轮询。
                val output = readBounded(process, if (finished) 1_500L else 800L)
                if (!finished) {
                    process.destroyForcibly()
                    Result(-1, "adb ${args.joinToString(" ")} timed out（output=${output.trim()}）")
                } else {
                    Result(process.exitValue(), output.trim())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result(-1, "adb ${args.joinToString(" ")} failed: ${e.message}")
            }
        }

    /**
     * 建立 reverse：让 adbd 在 `@scrcpy` 上监听，把连接转给本机 `tcp:$localPort`。
     *
     * `--remove-all` 不用 —— 会踩掉 ttyd 或别的功能可能建的 reverse。
     */
    suspend fun reverseToLocal(service: String, localPort: Int): Result {
        val r = run("reverse", "localabstract:$service", "tcp:$localPort")
        if (r.isSuccess) {
            AppLogger.i(TAG, "reverse $service → tcp:$localPort ok")
        } else {
            AppLogger.w(TAG, "reverse 失败: ${r.output.take(200)}")
        }
        return r
    }

    /** 拆除 reverse（幂等：没建过也返回成功） */
    suspend fun removeReverse(service: String): Result =
        run("reverse", "--remove", "localabstract:$service")

    private fun readBounded(process: Process, graceMs: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val input = process.inputStream
        val deadline = System.currentTimeMillis() + graceMs
        var idleSince = System.currentTimeMillis()
        while (sb.length < 8192) {
            val available = runCatching { input.available() }.getOrDefault(0)
            if (available > 0) {
                val n = runCatching { input.read(buf, 0, minOf(available, buf.size)) }.getOrDefault(-1)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                idleSince = System.currentTimeMillis()
                continue
            }
            val now = System.currentTimeMillis()
            if ((!process.isAlive && now - idleSince >= 200) || now >= deadline) break
            runCatching { Thread.sleep(20) }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "AdbCli"

        /**
         * 必须显式指定：设备本机 adb server 同时挂着 `192.168.0.1:5555`、`emulator-5554`、
         * `localhost:5555` 三个条目（同一台机器），不带 `-s` 会 `more than one device/emulator`。
         */
        const val SERIAL = "localhost:5555"
    }
}
