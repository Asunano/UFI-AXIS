package com.ufi_axis_core.controller.screen

import android.content.Context
import com.ufi_axis_core.adb.AdbChannel
import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * 投屏会话的生命周期（2026-10-31）。
 *
 * ## 一条会话从建立到拆掉的完整顺序
 * ```
 * ServerSocket(127.0.0.1:0)          ← 必须最先，端口要拿去建 reverse
 *   → adb reverse @scrcpy tcp:P      ← 让 adbd 当 @scrcpy 的监听者
 *   → 推 jar + detached 起 server     ← server 主动 connect() 到 @scrcpy
 *   → accept(P, 20s)                 ← 拿到与 server 的双向 socket
 *   → 交给 WS 泵
 * ```
 * 顺序错一步就会 `Connection refused`：server 起来时如果 reverse 还没建，它连不到任何东西
 * 就直接退出（2026-10-31 实机就是这个表现）。
 *
 * ## 为什么要每次重推 jar
 * scrcpy server 退出时会**删掉自己的 jar**（`cleanup=true` 的副作用，实测）。
 * 所以"上次推过了"永远不能当作理由跳过推送。见 [ScrcpyServerLauncher.start]。
 *
 * ## 单会话
 * 一个 core 同时只服务一个投屏客户端（用户需求是"看这台设备"，不是"多人围观"）。
 * 第二个请求直接返回 [Outcome.Busy]，由路由层映射成 409/1013。
 */
class ScreenSessionManager(
    private val context: Context,
    private val channel: AdbChannel,
) {

    sealed interface Outcome {
        data class Ready(val session: Session) : Outcome
        /** [reason] 是给路由层映射 HTTP 码用的稳定标识，不是给人看的文案 */
        data class Failed(val reason: Failure, val detail: String) : Outcome
    }

    enum class Failure { BUSY, ADB_UNAVAILABLE, REVERSE_FAILED, SERVER_START_FAILED, NO_CONNECTION }

    /**
     * 一条活动的会话。**只持有资源，不做清理** —— 拆 reverse、杀 server 都是阻塞/挂起操作，
     * 必须由 [ScreenSessionManager.stop] 在协程里做，不能塞进 `close()` 这种同步回调
     * （同步回调里调不了 suspend 函数，硬塞只能 fire-and-forget，清理就成了不确定的）。
     */
    /**
     * 一条投屏会话。
     *
     * ## ★ scrcpy 会开**两条**连接，不是一条（2026-10-31 实机踩到）
     * `DesktopConnection.open()` 在 connect 模式下依次执行：
     * ```
     * if (video)   videoSocket   = connect("scrcpy")
     * if (audio)   audioSocket   = connect("scrcpy")
     * if (control) controlSocket = connect("scrcpy")
     * ```
     * 也就是说 `@scrcpy` 这一个名字会被连 **video 次 + audio 次 + control 次**，
     * 每一条都是独立的连接。我们原来只 accept 一次：视频能看，但控制消息被写进了
     * **视频** socket —— 服务端那条通路根本不读它，表现就是"画面正常、点哪都没反应"。
     */
    class Session internal constructor(
        val pid: Int,
        /** 视频通道（设备 → 浏览器） */
        val videoSocket: Socket,
        /** 控制通道（浏览器 → 设备）。第二条 accept 拿到的那个。 */
        val controlSocket: Socket,
        private val listenSocket: ServerSocket,
    ) : AutoCloseable {
        /** 下行（设备→浏览器）字节数，供 /api/screen/status 排障 */
        @Volatile var downstreamBytes: Long = 0L

        /** 上行（浏览器→设备）字节数。控制消息走这条；为 0 就说明一条都没到 core。 */
        @Volatile var upstreamBytes: Long = 0L

        @Volatile private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { videoSocket.close() }
            runCatching { controlSocket.close() }
            // 监听口留到会话结束再关：accept() 之后立刻关它虽然按 Java 语义不影响已 accept
            // 的 socket，但这条路径现在是唯一的线索来源，少一个可疑变量比省一个 fd 值。
            runCatching { listenSocket.close() }
        }
    }

    private val launcher = ScrcpyServerLauncher(context, channel)
    private val adbCli = AdbCli(context)
    private val mutex = Mutex()

    @Volatile private var current: Session? = null

    val isActive: Boolean get() = current != null

    /**
     * 设备侧截屏（2026-10-31）。
     *
     * 为什么不截浏览器画布：画布是**编码后**的 848 高度画面，有压缩损失，也受降档影响；
     * 排障要的是设备原生像素。走 `exec:screencap -p`（不是 shell 文本通道，PNG 会被破坏）。
     *
     * 不要求有活动会话：`screencap` 与 scrcpy 互不干扰，用户在没连投屏时也可能想截一张。
     */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val stream = try {
            channel.stream("exec:screencap -p", timeoutMs = 5_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "截屏开流失败：${e.message}")
            return@withContext null
        }
        try {
            val out = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (!stream.awaitData(1_000)) {
                    if (stream.isClosed) break else continue
                }
                val chunk = stream.readAvailable(64 * 1024)
                if (chunk.isEmpty()) {
                    if (stream.isClosed) break else continue
                }
                out.write(chunk)
                if (out.size() > MAX_SCREENSHOT_BYTES) {
                    AppLogger.w(TAG, "截屏超过 ${MAX_SCREENSHOT_BYTES}B，丢弃")
                    return@withContext null
                }
                // 见到 PNG 的 IEND 块就说明整张图收完了，立刻返回 ——
                // 否则会一直等到 5s 截止，然后把这 5s 里收到的**半张图**当成完整 PNG 下发
                // （客户端表现为"解码失败/花屏"，日志里却没有任何线索）。
                if (endswithIend(out)) {
                    return@withContext out.toByteArray()
                }
            }
            if (out.size() > 0) {
                AppLogger.w(TAG, "截屏在 5s 内未收到 IEND（已收 ${out.size()}B），按失败处理")
            }
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "截屏失败：${e.message}")
            null
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * 缓冲区是否已经以 PNG 的 `IEND` 块结尾（`00 00 00 00 49 45 4E 44 AE 42 60 82`）。
     *
     * 只认**末尾**这 12 字节：PNG 允许在 IEND 前有任意块，但 IEND 必须是最后一个。
     */
    private fun endswithIend(out: java.io.ByteArrayOutputStream): Boolean {
        if (out.size() < PNG_IEND.size) return false
        val tail = out.toByteArray()
        return PNG_IEND.indices.all { tail[tail.size - PNG_IEND.size + it] == PNG_IEND[it] }
    }

    /** 当前会话的字节计数快照；无会话返回 null。 */
    fun stats(): Triple<Long, Long, Int>? = current?.let {
        Triple(it.downstreamBytes, it.upstreamBytes, it.pid)
    }

    /**
     * 建立会话。**不抛异常**：启动失败是预期内的失败模式（设备不支持、adb 掉了、端口被占），
     * 上层需要的是"哪一类失败"而不是堆栈。
     */
    suspend fun start(
        acceptTimeoutMs: Int = 20_000,
        params: ScrcpyServerLauncher.VideoParams = ScrcpyServerLauncher.VideoParams.DEFAULT,
    ): Outcome = mutex.withLock {
        // 新连接**接管**旧会话，而不是回 BUSY（2026-10-31 用户反馈后改的）。
        //
        // 原来这里直接返回 BUSY，理由是"同时只服务一个客户端"。但浏览器刷新、切路由、
        // 断网重连都会产生"旧 WS 还没被 Ktor 判定断开、新连接已经来了"的窗口 ——
        // 那个窗口里用户看到的是「已有会话在进行」，而实际上他什么也没在看。
        // 单客户端的诉求是"不要两个人抢屏幕"，不是"刷新一下就得等 30 秒"。
        // 前置校验必须**在踢旧会话之前**：adb 一时不可用（被系统冻结/资产还没解出来）
        // 时，原来的顺序会先把用户正在看的画面杀掉，再返回"adb 不可用"—— 结果是最坏的：
        // 旧的没了、新的也没建起来。
        if (adbCli.adbPath() == null) {
            return Outcome.Failed(Failure.ADB_UNAVAILABLE, "内置 adb 不可执行")
        }

        evictCurrentLocked()

        // 上一次 core 被 kill -9 时来不及 stop，这里顺手清掉残留进程与 reverse。
        // reverse 不清会留在 adb server 里，下一轮建同名 reverse 时行为不确定。
        launcher.cleanupStale()
        adbCli.removeReverse(SERVICE)

        // ① 先监听
        val server = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = acceptTimeoutMs }
        } catch (e: Exception) {
            return Outcome.Failed(Failure.NO_CONNECTION, "监听本地端口失败：${e.message}")
        }
        val port = server.localPort

        // ② 再建 reverse（adbd 开始监听 @scrcpy）
        val reverse = adbCli.reverseToLocal(SERVICE, port)
        if (!reverse.isSuccess) {
            runCatching { server.close() }
            return Outcome.Failed(Failure.REVERSE_FAILED, reverse.output)
        }

        // ③ 最后起 server（它会连过来）
        val startOutcome = launcher.start(params)
        val started = when (startOutcome) {
            is ScrcpyServerLauncher.StartOutcome.Ok -> startOutcome
            is ScrcpyServerLauncher.StartOutcome.Failed -> {
                adbCli.removeReverse(SERVICE)
                runCatching { server.close() }
                // 把 server 自己的说法带出去：用户看到的是原因，不是"详见日志"
                return Outcome.Failed(Failure.SERVER_START_FAILED, startOutcome.detail)
            }
        }

        // ④ accept。阻塞调用放 IO 线程；soTimeout 保证不会永久卡住
        // ① 第一条连接 = 视频
        val videoSocket = try {
            withContext(Dispatchers.IO) { server.accept() }
        } catch (e: SocketTimeoutException) {
            AppLogger.w(TAG, "accept 超时 ${acceptTimeoutMs}ms，回收 server pid=${started.pid}")
            cleanupFailedStart(server, started.pid)
            return Outcome.Failed(Failure.NO_CONNECTION, "scrcpy-server 未在 ${acceptTimeoutMs}ms 内连接")
        } catch (e: CancellationException) {
            // 客户端在"连接中"就断了（刷新页面/切换设备）：收完尾再把取消原样抛出去。
            AppLogger.w(TAG, "accept 期间被取消，回收 server pid=${started.pid}")
            cleanupFailedStart(server, started.pid)
            throw e
        } catch (e: Exception) {
            cleanupFailedStart(server, started.pid)
            return Outcome.Failed(Failure.NO_CONNECTION, "accept 失败：${e.message}")
        }

        // ② 第二条连接 = 控制（audio=false，所以只有这两条）。
        //    顺序由服务端决定：先 video 后 control，不能靠"谁先来"猜。
        val controlSocket = try {
            withContext(Dispatchers.IO) { server.accept() }
        } catch (e: CancellationException) {
            AppLogger.w(TAG, "等控制通道期间被取消，回收 server pid=${started.pid}")
            cleanupFailedStart(server, started.pid, videoSocket)
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "控制通道未建立：${e.message}")
            cleanupFailedStart(server, started.pid, videoSocket)
            return Outcome.Failed(Failure.NO_CONNECTION, "第二条连接（控制通道）未建立：${e.message}")
        }

        val session = Session(started.pid, videoSocket, controlSocket, server)
        current = session
        AppLogger.i(TAG, "投屏会话建立 pid=${started.pid} port=$port")
        Outcome.Ready(session)
    }

    /**
     * 启动失败时的收尾（**必须在取消态下也能跑完**）。
     *
     * `server.accept()` 在协程被取消时会抛 `CancellationException`；而 `launcher.stop()` /
     * `adbCli.removeReverse()` 都是挂起函数，在已取消的协程里**执行 block 之前就再抛一次** ——
     * 原先的 `catch (e: Exception)` 把 CE 当普通错误接住，随后的清理全被跳过：
     * 监听 socket 不关（scrcpy-server 连上来后卡在 backlog，一直编码、屏幕常亮）、
     * 已 accept 的 socket 不关、进程与 reverse 都不回收。
     *
     * 所以清理统一走 `NonCancellable`，且**每个失败分支都要在清理完之后把 CE 原样抛出**。
     */
    private suspend fun cleanupFailedStart(server: ServerSocket?, pid: Int?, video: Socket? = null) {
        withContext(NonCancellable) {
            runCatching { video?.close() }
            if (pid != null) runCatching { launcher.stop(pid) }
            runCatching { adbCli.removeReverse(SERVICE) }
            runCatching { server?.close() }
        }
    }

    /**
     * 回收当前会话（幂等）。**不抛异常**：接管路径上任何一步失败都不该阻断新会话建立。
     */
    private suspend fun evictCurrentLocked() {
        val old = current ?: return
        current = null
        AppLogger.w(TAG, "接管：回收上一个会话 pid=${old.pid}")
        withContext(NonCancellable) {
            runCatching { old.close() }
            runCatching { launcher.stop(old.pid) }
            runCatching { adbCli.removeReverse(SERVICE) }
        }
    }

    /**
     * 主动结束会话（幂等）：关 socket → 杀 server → 拆 reverse。
     *
     * [target] 是调用方**自己的**那条会话：只有它仍是当前会话时才收尾。
     * 少这个判断会误杀新会话 —— 接管之后，旧连接 `finally` 里的 `stop()` 跑得比
     * 新连接建立晚一步时，`current` 已经是别人的了（这条路径实测很容易撞上：
     * 刷新页面时旧 WS 的收尾和新 WS 的握手几乎是同时的）。
     */
    suspend fun stop(target: Session? = null) = mutex.withLock {
        val s = current ?: return@withLock
        if (target != null && s !== target) return@withLock
        current = null
        // WS 的 `finally` 里调到这里时，协程往往已经是取消态 —— 不套 NonCancellable 的话
        // kill 与 removeReverse 都会在真正执行前就抛掉，留下孤儿进程与残留 reverse。
        withContext(NonCancellable) {
            runCatching { s.close() }
            runCatching { launcher.stop(s.pid) }
            runCatching { adbCli.removeReverse(SERVICE) }
            AppLogger.i(TAG, "投屏会话结束 pid=${s.pid}")
        }
    }

    /**
     * 停机路径专用（非挂起）：[BackendService] 的收尾块是同步代码。
     *
     * 用 2 秒超时包住：停机流程里卡住比漏清更糟 —— 超时后残留由下次
     * [start] 开头的 `cleanupStale()` 兜底。
     */
    fun shutdownBlocking(timeoutMs: Long = 2_000L) {
        runBlocking { withTimeoutOrNull(timeoutMs) { destroy() } }
    }

    /** 服务销毁时调用：先把会话拆了，再清残留进程 */
    suspend fun destroy() {
        stop()
        launcher.cleanupStale()
    }

    companion object {
        private const val TAG = "ScreenSessionManager"

        /** 与 `ScrcpyServerLauncher.JAR_NAME` 无关：这是 abstract socket 名，scrcpy 协议固定 */
        const val SERVICE = "scrcpy"

        /** 单张截图上限定 8MB（plan §4.2） */
        private const val MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024

        /** PNG 结束块：`length=0` + "IEND" + CRC。 */
        private val PNG_IEND = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        /** 资产文件在私有目录的落点（仅用于排障日志，实际路径由 launcher 决定） */
        fun cacheJar(context: Context): File = File(context.cacheDir, "screen/${ScrcpyServerLauncher.JAR_NAME}")
    }
}
