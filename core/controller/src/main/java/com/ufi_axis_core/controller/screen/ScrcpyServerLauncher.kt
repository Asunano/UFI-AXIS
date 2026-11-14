package com.ufi_axis_core.controller.screen

import android.content.Context
import com.ufi_axis_core.adb.AdbChannel
import com.ufi_axis_core.util.AdbShellExecutor
import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * scrcpy-server 的推送、拉起与清理（2026-10-31）。
 *
 * ## 这个类只负责"让进程活着"，不负责视频
 * 视频由 `ScreenSessionManager` 通过 [AdbChannel.stream]("localabstract:scrcpy") 取，
 * 两者不耦合：server 起来了但没人连，会在 `cleanup=true` 的实际语义下空转；
 * 流断了但进程还在，由 [stop] / [cleanupStale] 兜。
 *
 * ## 三个必须守住的约束
 *
 * 1. **detached 启动**：`nohup setsid` + `$!`。用 `AdbShellExecutor.execute` 直接跑 server
 *    会被它的 `waitFor(30s) → destroyForcibly` 杀掉，表现为"连上 30 秒后画面断且没有日志"。
 *    也不能用 `echo $$; exec app_process …`：`exec` 之后 shell 就是 server，永不返回，同样卡满超时。
 * 2. **参数是 server 侧格式**：`snake_case=value`、无 `--` 前缀、码率写字节数。
 *    写成 `--max-size=1280` 一类的客户端 CLI 格式，server 根本解析不了。
 * 3. **版本号必须与 jar 完全一致**：server 的 `argv[0]` 就是版本号。
 *
 * ## 为什么 kill 前要看 `/proc/<pid>/cmdline`
 * pid 会被回收重用。只凭一个存了几小时的 pid 文件去 `kill -9`，可能杀掉别人的进程。
 * 先读 cmdline 确认里面确实有我们的 jar 名，才动手。
 */
class ScrcpyServerLauncher(
    private val context: Context,
    private val channel: AdbChannel,
) {

    sealed interface StartOutcome {
        data class Ok(val pid: Int, val remoteJar: String) : StartOutcome

        /** [detail] 会一路传到浏览器：启动失败时用户看到的就是它，不是"详见日志" */
        data class Failed(val detail: String) : StartOutcome
    }

    /**
     * 推送并拉起。失败返回 null，具体原因看日志（不抛异常：启动失败是**预期内**的失败模式，
     * 上层要回退到"设备不可用"的文案，而不是 500）。
     */
    suspend fun start(params: VideoParams = VideoParams.DEFAULT): StartOutcome = withContext(Dispatchers.IO) {
        try {
            val jar = extractAsset()
            channel.push(jar, REMOTE_JAR)
            AppLogger.i(TAG, "pushed ${jar.length()} B → $REMOTE_JAR")

            val cmd = buildStartCommand(args = argsFor(params))
            val r = AdbShellExecutor.execute(cmd, START_TIMEOUT_MS)
            val pid = parsePid(r.stdout)
            if (pid == null) {
                return@withContext StartOutcome.Failed(
                    "拿不到 PID（exit=${r.exitCode}）：${r.stdout.take(160)}"
                )
            }
            // 进程可能一闪而过（jar 坏了 / 参数错）。确认它真的在，并把 cmdline 记进日志 ——
            // 这是排"起不来"最快的一条线索。
            val cmdline = readCmdline(pid)
            if (cmdline == null) {
                // 起来又立刻退了。把 server 自己的最后几行带出去 —— 它 90% 会说
                // "Connection refused"（reverse 没就绪）或 jar 损坏。
                return@withContext StartOutcome.Failed(
                    "scrcpy-server 起后即退：${readLogTail()}"
                )
            }
            if (!cmdline.contains(PROC_MARKER)) {
                return@withContext StartOutcome.Failed(
                    "pid $pid 不是 scrcpy-server（cmdline=${cmdline.take(120)}）"
                )
            }
            AppLogger.i(TAG, "scrcpy-server up pid=$pid")
            StartOutcome.Ok(pid, REMOTE_JAR)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是"启动失败"：把它当失败返回，上层会在已取消的 call 上再写响应，
            // 而且会掩盖"这次启动是被主动放弃的"这一事实。
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "启动异常：${e.message}")
            StartOutcome.Failed("启动异常：${e.message}")
        }
    }

    /** 读 server 自己的日志尾巴（启动失败时最有用的一行） */
    private suspend fun readLogTail(): String {
        val r = AdbShellExecutor.execute("tail -3 $LOG_FILE 2>/dev/null", 5_000)
        return r.stdout.trim().ifEmpty { "<无输出>" }
    }

    /**
     * 停掉一条会话的 server 进程（幂等：进程已不在也算成功）。
     *
     * ## 为什么不是直接 kill -9
     *
     * scrcpy-server 启动时若开了 `stay_awake` / `power_on`，会改设备的**全局设置**
     * （`stay_on_while_plugged_in`、`show_touches` 等），靠正常退出路径里的 `CleanUp` 恢复。
     * 原先两条停机路径全是 `kill -9` —— 恢复代码永远不执行，而且下一轮 server 会把
     * "已被改过的值"当成原始值记住并写回，改动就此**永久化**（设备从此插电不熄屏）。
     *
     * 顺序：调用方已经关了两条 socket → server 读到 EOF 会自己走正常退出 → 等 1.8s；
     * 还在就 SIGTERM（ART 会跑 shutdown hook）；最后才 SIGKILL 兜底。
     */
    suspend fun stop(pid: Int) = withContext(Dispatchers.IO) {
        try {
            // 先确认这个 pid 还是我们的 server（pid 可能已被系统回收给别的进程）
            val cmdline = readCmdline(pid)
            if (cmdline == null) {
                AdbShellExecutor.execute("rm -f $PID_FILE", 5_000)
                return@withContext
            }
            if (!cmdline.contains(PROC_MARKER)) {
                AppLogger.w(TAG, "pid=$pid 已不是 scrcpy-server（cmdline 不匹配），只清 pid 文件")
                AdbShellExecutor.execute("rm -f $PID_FILE", 5_000)
                return@withContext
            }

            if (awaitExit(pid, attempts = 12)) {
                AppLogger.i(TAG, "scrcpy-server 自行退出（socket 已关）pid=$pid")
            } else {
                AdbShellExecutor.execute("kill -TERM $pid 2>/dev/null", 5_000)
                if (awaitExit(pid, attempts = 8)) {
                    AppLogger.i(TAG, "scrcpy-server 收到 SIGTERM 后退出 pid=$pid")
                } else {
                    AppLogger.w(TAG, "scrcpy-server 未响应 SIGTERM，改用 SIGKILL pid=$pid")
                    AdbShellExecutor.execute("kill -9 $pid 2>/dev/null", 5_000)
                }
            }
            AdbShellExecutor.execute("rm -f $PID_FILE", 5_000)
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止失败 pid=$pid：${e.message}")
        }
    }

    /** 轮询 `/proc/<pid>` 是否消失；每次约 150ms，返回 true = 已退出。 */
    private suspend fun awaitExit(pid: Int, attempts: Int): Boolean {
        repeat(attempts) {
            val alive = AdbShellExecutor.execute("test -d /proc/$pid && echo 1 || echo 0", 3_000)
                .stdout.trim() == "1"
            if (!alive) return true
            kotlinx.coroutines.delay(150)
        }
        return false
    }

    /**
     * 清理上一次残留（core 被 kill -9 时来不及 [stop]）。
     *
     * 只在 cmdline 命中我们的 jar 名时才动手；否则只删 pid 文件，避免误杀。
     */
    suspend fun cleanupStale() = withContext(Dispatchers.IO) {
        try {
            val pidText = AdbShellExecutor.execute("cat $PID_FILE 2>/dev/null", 5_000).stdout.trim()
            val pid = pidText.toIntOrNull()
            if (pid == null) return@withContext
            val cmdline = readCmdline(pid)
            if (cmdline != null && cmdline.contains(PROC_MARKER)) {
                // 残留进程的 socket 已经不在我们手里，只能靠信号：先 TERM，再 KILL。
                AdbShellExecutor.execute("kill -TERM $pid 2>/dev/null", 5_000)
                if (!awaitExit(pid, attempts = 8)) {
                    AdbShellExecutor.execute("kill -9 $pid 2>/dev/null", 5_000)
                }
                AppLogger.w(TAG, "清理残留 server pid=$pid")
            }
            AdbShellExecutor.execute("rm -f $PID_FILE", 5_000)
        } catch (e: Exception) {
            AppLogger.w(TAG, "清理残留失败：${e.message}")
        }
    }

    // ── 内部 ──

    /** 读 `/proc/<pid>/cmdline`；进程不在返回 null（读不到就是不在，不必区分原因） */
    private suspend fun readCmdline(pid: Int): String? {
        val r = AdbShellExecutor.execute("tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null", 5_000)
        return r.stdout.takeIf { it.isNotBlank() }
    }

    /**
     * 把内置 assets 释放到私有目录并校验 SHA-256。
     *
     * 校验不是形式主义：jar 走 git 存二进制，一旦被 LFS/文本化工具改坏，
     * 直接 gzip 解压失败，报错会变成"app_process 起不来"，很难指向真因。
     */
    private fun extractAsset(): File {
        val out = File(context.cacheDir, "screen/$JAR_NAME")
        out.parentFile?.mkdirs()
        val needExtract = !out.isFile || out.length() != ASSET_SIZE || sha256(out) != ASSET_SHA256
        if (needExtract) {
            context.assets.open("screen/$JAR_NAME").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256(out)
            check(actual == ASSET_SHA256) {
                "scrcpy-server.jar 校验失败：期望 $ASSET_SHA256，实际 $actual"
            }
        }
        return out
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 画面参数（2026-10-31）。
     *
     * ## 为什么允许自定义，但仍然**钳制**
     * 用户要能自己调分辨率和码率（隧道下想更省、局域网下想要更高帧率），
     * 但 `max_size` / `max_fps` 是直接进设备编码器配置的，放开任意值等于把编码器
     * 交给客户端随便配：`max_fps=1000` 会让弱 SoC 直接发热降频，`max_size=4096`
     * 可能让编码器初始化失败。所以**开放区间、不开放越界**：
     *
     * - `maxSize` 240–1280，并对齐到 8 的倍数（编码器要求）
     * - `maxFps` 5–60
     * - `bitRate` 500k–16M
     *
     * 越界不是报错而是**钳到边界**：用户拖滑块时不该被 400 打断，日志里会记实际值。
     */
    data class VideoParams(
        val maxSize: Int = 848,
        val maxFps: Int = 20,
        val bitRate: Int = 2_000_000,
    ) {
        companion object {
            const val MIN_SIZE = 240
            const val MAX_SIZE = 1280
            const val MIN_FPS = 5
            const val MAX_FPS = 60
            const val MIN_BITRATE = 500_000
            const val MAX_BITRATE = 16_000_000

            val DEFAULT = VideoParams()

            fun sanitize(size: Int?, fps: Int?, bitRate: Int?): VideoParams {
                val s = ((size ?: DEFAULT.maxSize).coerceIn(MIN_SIZE, MAX_SIZE) / 8) * 8
                val f = (fps ?: DEFAULT.maxFps).coerceIn(MIN_FPS, MAX_FPS)
                val b = (bitRate ?: DEFAULT.bitRate).coerceIn(MIN_BITRATE, MAX_BITRATE)
                return VideoParams(s.coerceAtLeast(MIN_SIZE), f, b)
            }
        }
    }

    companion object {
        private const val TAG = "ScrcpyServerLauncher"

        /**
         * ★ 与 assets 里的 jar、以及 web 侧 `@yume-chan/scrcpy` 的 options 类三方锁死。
         *
         * 2026-10-31 决定钉 3.3.3 而不是上游最新的 5.0.1：
         * `@yume-chan/scrcpy-decoder-tinyh264` 没有 3.x 版本，其 2.1.0 依赖的
         * `h264ParseConfiguration` 在 3.x 库里已被删除 —— 换 5.0.1 就要自己写浏览器解码器。
         * 换版本时**三处一起改**：这里、web 的 `ScrcpyOptionsX_Y`、assets 里的 jar。
         */
        const val SERVER_VERSION = "3.3.3"

        const val JAR_NAME = "scrcpy-server.jar"
        const val REMOTE_DIR = "/data/local/tmp/ufi"
        const val REMOTE_JAR = "$REMOTE_DIR/$JAR_NAME"
        private const val PID_FILE = "$REMOTE_DIR/scrcpy.pid"

        /**
         * 判断"这个 pid 是不是我们的 server"只能靠 **argv**，不能靠 jar 名。
         *
         * `app_process` 的 argv 是 `app_process / com.genymobile.scrcpy.Server 3.3.3 …`，
         * jar 路径只在 `CLASSPATH` **环境变量**里（`/proc/<pid>/environ`）。
         * 2026-10-31 真机踩到：用 `cmdline.contains("scrcpy-server.jar")` 判断，
         * 永远为假 → 每次都判"没起来" → 自己把刚建好的会话拆掉，
         * 现象是"点连接立刻失败，设备上连进程和 jar 都不剩"。
         */
        private const val PROC_MARKER = "com.genymobile.scrcpy.Server"

        /** server 自己的 stdout/stderr。丢到 /dev/null 的话，起不来时只能靠猜。 */
        private const val LOG_FILE = "$REMOTE_DIR/scrcpy.log"

        private const val ASSET_SIZE = 90_164L
        private const val ASSET_SHA256 =
            "7e70323ba7f259649dd4acce97ac4fefbae8102b2c6d91e2e7be613fd5354be0"

        private const val START_TIMEOUT_MS = 15_000L

        /**
         * 服务端参数（v1 固定，不接受前端覆盖）。
         *
         * 实测设备：480x854 / density 200 / 只有软件 H.264 编码器（`c2.android.avc.encoder`）。
         * `max_size=1280` 在这台机器上等于不缩放，留着是为了将来更高分辨率的机型。
         *
         * `stay_awake=true` 是 M0 实测后加的：设备 display 初始 `state OFF`，
         * 不阻止休眠的话会话中途会变成黑屏（采集到纯色帧）。
         */
        val SERVER_ARGS: List<String> = listOf(
            "max_size=1280",
            "max_fps=20",
            "video_bit_rate=2000000",
            "audio=false",
            "control=true",
            "video=true",
            "clipboard_autosync=false",
            "cleanup=true",
            "power_on=true",
            "stay_awake=true",
            "send_device_meta=true",
            "send_dummy_byte=true",
            "send_codec_meta=true",
        )

        /**
         * 拼启动命令。**纯函数，单测覆盖**（这条命令写错的代价是"画面永远不出来"，
         * 而它又无法在 CI 里真跑，所以只能靠断言锁住）。
         */
        /** 按参数生成 server 参数（其余项与 [SERVER_ARGS] 一致，只覆盖三个可变项） */
        internal fun argsFor(params: VideoParams): List<String> =
            SERVER_ARGS.map { arg ->
                when {
                    arg.startsWith("max_size=") -> "max_size=${params.maxSize}"
                    arg.startsWith("max_fps=") -> "max_fps=${params.maxFps}"
                    arg.startsWith("video_bit_rate=") -> "video_bit_rate=${params.bitRate}"
                    else -> arg
                }
            }

        internal fun buildStartCommand(
            version: String = SERVER_VERSION,
            args: List<String> = SERVER_ARGS,
            remoteJar: String = REMOTE_JAR,
            remoteDir: String = REMOTE_DIR,
            pidFile: String = PID_FILE,
            logFile: String = LOG_FILE,
        ): String {
            val serverArgv = (listOf(version) + args).joinToString(" ")
            val inner = "CLASSPATH=$remoteJar exec app_process / com.genymobile.scrcpy.Server $serverArgv"
            // nohup setsid：脱离 adb shell 的会话，否则 shell 一断 server 就被 SIGHUP 带走。
            // $! 由**设备侧** shell 展开（Kotlin 这边用 ${'$'} 转义，避免被当成模板）。
            return "mkdir -p $remoteDir; " +
                "nohup setsid sh -c '$inner' >$remoteDir/scrcpy.log 2>&1 & " +
                "P=\$!; echo \$P > $pidFile; echo __PID__\$P"
        }

        /**
         * 从启动命令的输出里取 PID。**纯函数，单测覆盖**。
         *
         * `AdbShellExecutor` 会在命令后追加 `echo MARKER$?`，所以输出里可能混有退出码行；
         * 这里只认自己那个前缀，别的行一律忽略。
         */
        internal fun parsePid(stdout: String): Int? =
            Regex("__PID__(\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()
    }
}
