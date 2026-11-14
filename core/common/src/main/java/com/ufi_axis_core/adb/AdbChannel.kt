package com.ufi_axis_core.adb

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 受管的 adb 长连接（2026-10-31，屏幕投屏用）。
 *
 * ## 与 [AdbClient] 的分工
 * [AdbClient] 是"一次会话里顺序调若干动作"的门面（装机器用法），由调用方管理连接生命周期。
 * 本类是"长期存活、断了能自己爬起来"的通道，给**需要反复开流**的场景用：
 * 每帧截一张图（`exec:screencap -p`）、一次投屏会话（`localabstract:scrcpy`）。
 *
 * ## 为什么需要它，而不是每次 new 一个 AdbClient
 * - **连接贵**：CNXN/AUTH 握手 + READY 往返，不能每帧握一次。
 * - **adbd 有并发连接上限**：泄漏的连接会把后续所有 adb 操作堵死（包括 `AdbShellExecutor` 那条）。
 * - **半死连接**：socket 已经断了但 `isConnected` 还是 true 的那种状态必须有人负责作废。
 *
 * ## ★ keyDir 必须是 `context.cacheDir`
 * [AdbCrypto] 在 keyDir 里找不到 `adbkey` 时会**生成新密钥**。而 `AdbShellExecutor` 把
 * `ANDROID_ADB_HOME` 指到了 `context.cacheDir`，那里躺着生产上**已被设备授权**的 key。
 * 指向别处 → 新 key → adbd 弹 RSA 授权 → **无屏设备没人能点「允许」**，
 * 于是整条特权通道连不上，而日志里只会看到"连接超时"。
 *
 * ## 线程模型
 * 内部串行化（[AdbClient] 本身要求顺序调用）。`stream()` 可以在任意线程调用，
 * 但**返回的 [AdbStream] 的读写要由调用方自己保证不并发**（一个流一个使用者）。
 */
class AdbChannel(
    keyDir: File,
    private val host: String = LOOPBACK,
    private val port: Int = AdbConnection.DEFAULT_PORT,
    private val connectTimeoutMs: Int = 5_000,
    /** 设备要求点「允许」时回调；无屏设备上应当直接视为失败并告警（见类注释） */
    private val onAwaitingAuth: (() -> Unit)? = null,
) {

    private val crypto = AdbCrypto(keyDir)
    private val lock = Any()
    private var client: AdbClient? = null

    /** 成功建连次数，供测试与排障断言"只重连了一次"这类语义 */
    private val connects = AtomicInteger(0)
    val connectCount: Int get() = connects.get()

    val isConnected: Boolean get() = synchronized(lock) { client?.isConnected == true }

    /**
     * 打开一个 adb 服务并返回原始流。
     *
     * 连接已失效时**自动重连一次**再重试；重试仍失败则把异常抛给调用方
     * （由它决定是"这一帧跳过"还是"整个会话结束"）。
     *
     * @throws AdbException 建连失败、服务被拒、超时
     */
    fun stream(service: String, timeoutMs: Long = 10_000L): AdbStream =
        synchronized(lock) {
            try {
                requireClientLocked().openStream(service, timeoutMs)
            } catch (e: AdbConnectionClosedException) {
                // 明确的"连接已死"：作废重连一次是止损，不是掩盖错误
                reconnectLocked()
                requireClientLocked().openStream(service, timeoutMs)
            } catch (e: AdbTimeoutException) {
                // 超时的语义模糊：可能是服务慢（scrcpy 冷启动要 1-2 秒），
                // 也可能是 socket 半死。只在"确认已不连通"时重连，否则原样抛出，
                // 避免把一次慢启动变成两次慢启动。
                if (client?.isConnected == true) throw e
                reconnectLocked()
                requireClientLocked().openStream(service, timeoutMs)
            }
        }

    /** 主动作废当前连接（下一次 [stream] 重连）。用于上层判定"这条通道不可信"时。 */
    fun invalidate() {
        synchronized(lock) { discardLocked() }
    }

    /**
     * 推文件到设备（SYNC 协议，复用同一条连接，不另起 `adb push` 子进程）。
     *
     * 投屏前把 `scrcpy-server.jar`（约 88 KB）送进 `/data/local/tmp` 就走这里。
     * 阻塞调用，调用方应在 IO 线程上执行。
     */
    fun push(localFile: File, remotePath: String, mode: String = "0644") {
        synchronized(lock) {
            try {
                requireClientLocked().push(localFile, remotePath, mode)
            } catch (e: AdbConnectionClosedException) {
                reconnectLocked()
                requireClientLocked().push(localFile, remotePath, mode)
            }
        }
    }

    fun close() {
        synchronized(lock) { discardLocked() }
    }

    // ── 内部 ──

    private fun requireClientLocked(): AdbClient {
        val existing = client
        if (existing != null && existing.isConnected) return existing
        // 已断开的旧连接要先 discard（它会关 socket），否则直接 connectLocked() 只是把
        // `client` 换成新对象，旧 socket 没人关。
        if (existing != null) discardLocked()
        return connectLocked()
    }

    private fun reconnectLocked(): AdbClient {
        discardLocked()
        return connectLocked()
    }

    private fun connectLocked(): AdbClient {
        val c = AdbClient(crypto)
        c.connect(
            host = host,
            port = port,
            connectTimeoutMs = connectTimeoutMs,
            onAwaitingAuth = onAwaitingAuth,
        )
        client = c
        connects.incrementAndGet()
        return c
    }

    private fun discardLocked() {
        client?.let { runCatching { it.close() } }
        client = null
    }

    companion object {
        /** 设备本机 adbd（`service.adb.tcp.port` 默认 5555，由 AdbShellExecutor 兜底设置） */
        const val LOOPBACK = "127.0.0.1"
    }
}
