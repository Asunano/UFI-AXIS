package com.ufi_axis_core.api.media

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体流播放票据（2026-09-14）。
 *
 * ## 为什么需要它
 * `/api` 下的一切都要过 [com.ufi_axis_core.api.middleware.AuthMiddleware]：Bearer token +
 * `X-Timestamp` / `X-Nonce` / `X-Signature` 四个**请求头**。而浏览器的 `<audio src>` / `<video src>`
 * 由渲染引擎发起请求，**无法附加自定义头** —— 这正是 web 端此前只能用 `fetch` 把整个文件读成 blob
 * 再喂给 `<audio>` 的原因（100MB 上限、必须下载完才能播、电影根本进不了预览）。
 *
 * ## 为什么不用「query 里带签名」那一套（WebSocket 的做法）
 * 播放器对同一个 URL 会发几十上百个 Range 请求，而 URL 是固定的 ⇒ query 里的 `nonce` 也固定 ⇒
 * 第二个请求就会被判**重放**（`DeviceRequestVerifier.Result.Replayed`）。而且每个 Range 都要做一次
 * P-256 验签。所以那条路对流式播放根本走不通，只有短时票据可行。
 *
 * ## 语义（三条都是有意的）
 * 1. **可重复使用**：播放器每个 Range 请求都带同一个票据。一次性票据会让第二个请求当场 401、
 *    播放中断 —— 这是本方案最容易踩错的一点。
 * 2. **滑动过期**：每次成功使用都续期 [ttlMs]（默认 10min）。两小时的电影能连续播完，
 *    而分享出去的 URL 在停止播放 10 分钟后就是废纸。
 * 3. **只授权一个文件**：票据绑定签发时那一条**已通过路径校验的真实路径**，
 *    泄漏也只泄漏那一个正在播的文件，不是 `/api` 通行证。
 *
 * 线程安全：[ConcurrentHashMap] + 票据条目内的 `@Volatile` 时间戳。签发/校验都在 Ktor 的
 * 请求线程上跑，没有额外同步开销（相对每 64KB 一次的磁盘读+socket 写完全可忽略）。
 *
 * 纯 JVM 实现（无 Android 依赖），判据可单测。
 */
class MediaTicketStore(
    /** 滑动过期窗口：距上次使用超过这个时长即失效。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 同时有效的票据上限；超出时淘汰最久未使用的那条，防止内存无界增长。 */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 注入时钟，供单测推进时间。 */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private class Entry(val path: String, @Volatile var lastUsedAt: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** 票据有效期（秒），回给客户端做续签参考。 */
    val ttlSeconds: Long get() = ttlMs / 1000

    /**
     * 为一条**已通过路径校验**的真实路径签发票据。
     *
     * 调用方必须先跑完 `safeResolveForRead`：票据一旦签出，`/media/stream` 就只认这条路径、
     * 不再重新解析用户输入 —— 把校验放在签发前是这套设计能成立的前提。
     */
    fun issue(realPath: String): String {
        purgeExpired()
        evictIfNeeded()
        val bytes = ByteArray(TICKET_BYTES)
        random.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[ticket] = Entry(realPath, nowMs())
        return ticket
    }

    /**
     * 校验票据并**续期**。
     *
     * @return 票据对应的真实路径；票据不存在或已过期返回 null。
     */
    fun resolve(ticket: String?): String? {
        if (ticket.isNullOrBlank()) return null
        val entry = tickets[ticket] ?: return null
        val now = nowMs()
        if (now - entry.lastUsedAt > ttlMs) {
            // 过期即摘除：留着只会让后续请求每次都走一遍同样的减法
            tickets.remove(ticket, entry)
            return null
        }
        entry.lastUsedAt = now
        return entry.path
    }

    /** 主动作废（用户关闭播放器时可调，非必需 —— 滑动过期本身就会回收）。 */
    fun revoke(ticket: String?) {
        if (!ticket.isNullOrBlank()) tickets.remove(ticket)
    }

    /** 当前有效票据数（诊断/单测用）。 */
    fun size(): Int {
        purgeExpired()
        return tickets.size
    }

    private fun purgeExpired() {
        val now = nowMs()
        val it = tickets.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastUsedAt > ttlMs) it.remove()
        }
    }

    /**
     * 容量淘汰：过期清理之后仍然超限，说明短时间内签了太多票，摘掉最久未使用的那条。
     *
     * 用循环而不是一次性算出要删几条：并发签发时 size 会变，循环判据更稳。
     */
    private fun evictIfNeeded() {
        while (tickets.size >= maxEntries) {
            val oldest = tickets.entries.minByOrNull { it.value.lastUsedAt } ?: return
            tickets.remove(oldest.key, oldest.value)
        }
    }

    companion object {
        /** 默认滑动过期：10 分钟。够长以覆盖暂停/缓冲，够短以让泄漏的 URL 迅速失效。 */
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** 同时有效票据上限。正常一次只播一个文件，64 条足够覆盖多标签页/快速切换。 */
        const val DEFAULT_MAX_ENTRIES = 64

        /** 票据随机字节数（32 字节 = 256bit，Base64url 后 43 字符）。 */
        private const val TICKET_BYTES = 32
    }
}
