package com.ufi_axis_core.api.terminal

import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * ttyd 进程管理（真 PTY 终端，2026-10-06）。
 *
 * ## 与既有能力的边界
 * 终端页此前只有「单命令 exec」模式（ShellRoutes 逐条跑命令）。真 PTY 由 assets/shell/ttyd
 * （静态 aarch64 二进制，Release 4.x，libwebsockets 实现）提供：core 把它起在 127.0.0.1 的
 * 一个本地端口上，浏览器经 core 的 WS 反代路由连接 —— **ttyd 不监听外部接口**，
 * 所有外部流量仍过 AuthMiddleware 鉴权闸门。
 *
 * ## 生命周期
 * - 按需启动：第一次有客户端拿票据时才 spawn（`ensureStarted()`），不开关常驻进程；
 * - 惰性退出：不设保活，core 进程死则子进程死（同 uid，无 root）；
 * - 端口冲突自愈：`waitForReady` 失败时 kill 掉重来一次；再失败则向上报错。
 *
 * ## 安全
 * - 开关 `ttyd_enabled` 默认关（AppSettings）；关着时 TtydRoutes 一律 403，不会 spawn；
 * - `-W`（writable）必须带：ttyd 默认只读，不带它终端就只是个显示器；
 * - 凭据模式用 `--credential` 太弱（basic auth 明文），改由 core 反代层负责鉴权，
 *   ttyd 本体只绑 127.0.0.1，外部根本摸不到它。
 *
 * 线程安全：[AtomicReference] 持进程引用 + synchronized(start)。
 */
class TtydManager(
    /** assets/shell/ttyd 释放后的绝对路径（AssetExtractor.getPath(context, "ttyd")）。 */
    private val binaryPath: String,
    /** 本地监听端口（127.0.0.1）。 */
    private val port: Int = DEFAULT_PORT,
    /** 注入进程启动器，单测替换。 */
    private val processStarter: (List<String>) -> Process = { cmd ->
        ProcessBuilder(cmd).redirectErrorStream(true).start()
    },
) {
    private val processRef = AtomicReference<Process?>(null)

    /**
     * 抽干子进程输出的线程（2026-10-06 修）。
     *
     * **为什么必须有**：`redirectErrorStream(true)` 让 ttyd 的 stdout/stderr 并成一条管道，
     * 而管道缓冲区只有 64KB。没人读的话，ttyd 写完缓冲就在 `write()` 上永久阻塞 ——
     * 表现为长会话用一阵子后输出停摆（且 `isAlive` 仍为 true，`ensureStarted` 不会救），
     * 页面重连也没用。这不是理论风险：ttyd 每帧都有 libwebsockets 日志。
     *
     * 线程是 daemon + 只读丢弃：不解析（会话内容由 WS 反代那条路走，与本管道无关），
     * 抽到 EOF 自然结束，进程被杀时读立刻返回 -1。
     */
    private fun drainOutput(p: Process) {
        val t = Thread({
            runCatching { p.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
        }, "ttyd-drain")
        t.isDaemon = true
        t.start()
    }

    /** ttyd 是否在跑（进程活着即算）。 */
    val isRunning: Boolean
        get() = processRef.get()?.isAlive == true

    /**
     * 确保 ttyd 在跑，返回它实际监听的本地端口。
     *
     * @throws TtydStartException 二进制缺失 / 两次启动都失败
     */
    @Synchronized
    fun ensureStarted(): Int {
        processRef.get()?.takeIf { it.isAlive }?.let { return port }

        // 上一次异常退出的残留进程（isAlive=false 但端口还占着）——显式 destroy 兜底
        processRef.getAndSet(null)?.runCatching { destroyForcibly() }

        val bin = File(binaryPath)
        if (!bin.exists() || !bin.canExecute()) {
            throw TtydStartException("ttyd 二进制不可用：$binaryPath（未释放或无执行权限）")
        }

        // 参数取舍：
        //  -W writable：不开就只读，终端没意义；
        //  -6 不开（IPv6 优先会连不上 127.0.0.1 的 IPv4 回环）；
        //  --max-clients 4：与 WebSocketManager 的连接数上限量级一致；
        //  --once 不用 —— 反代层断开后我们希望 ttyd 保住 shell 会话，页面重连还能回去。
        val cmd = listOf(
            binaryPath,
            "-W",                       // 可写（交互式 shell 必需）
            "-p", port.toString(),      // 监听端口
            "-i", "127.0.0.1",          // 只绑回环：外部流量必须走 core 反代
            "--max-clients", "4",
            // 2026-10-07 修复：**不要**加 `-b "/"`。ttyd 的 WS 路径 = base_path + "ws"，
            // 传 "/" 会变成 "//ws"，反代连 /ws 反而对不上（r12 真机 1006 的另一半原因）。
            // 默认 base path（空）时端点就是 /ws，与本端 TtydRoutes 的反代 URL 一致。
            "/system/bin/sh", "-l",     // 登录 shell；设备上 mksh 是常态
        )
        val lastError = runCatching {
            val p = processStarter(cmd)
            processRef.set(p)
            drainOutput(p)
            waitForReady(p)
            port
        }.getOrElse { last ->
            processRef.get()?.runCatching { destroyForcibly() }
            processRef.set(null)
            throw TtydStartException("ttyd 启动失败：${last.message}", last)
        }
        return lastError
    }

    /** 优雅终止（退出终端页时不必调 —— 惰性退出已覆盖，留给「服务停止」钩子用）。 */
    fun stop() {
        processRef.getAndSet(null)?.runCatching { destroyForcibly() }
        AppLogger.i(TAG, "ttyd stopped")
    }

    /** 轮询等端口就绪：ttyd 是 C 程序，fork 后立刻连接偶发 connection refused，给最多 2s。 */
    private fun waitForReady(p: Process) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) throw IllegalStateException("ttyd 启动即退出，exit=${p.exitValue()}")
            if (isPortOpen("127.0.0.1", port)) return
            Thread.sleep(50)
        }
        throw IllegalStateException("ttyd 端口 $port 2s 内未就绪")
    }

    private fun isPortOpen(host: String, port: Int): Boolean =
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 200) }
            true
        }.getOrDefault(false)

    class TtydStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "TtydManager"

        /** 本地端口。选 7682（ttyd 官方默认）+ 偏移，避开 goform 80/8080/8088 等常见占用。 */
        const val DEFAULT_PORT = 17682
    }
}
