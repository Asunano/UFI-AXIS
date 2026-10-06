package com.ufi_axis_core.api.terminal

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端 PTY 票据（真 PTY 终端，2026-10-06）。
 *
 * 复用 [com.ufi_axis_core.api.media.MediaTicketStore] 的设计（那边的注释就是完整论证），
 * 差异只有两点，都源于「终端是长连接、媒体是 Range 短请求」：
 *
 * 1. **绑定会话而不仅是资源**：媒体票只授权一个路径；终端票额外记一个 sessionId，
 *    WS 反代用它把同一浏览器标签页的多次重连归到同一条 ttyd 会话（ttyd 的 `--once`
 *    没开，服务端会保留 shell 会话直到超时）。票据不作会话隔离 —— 但 ttyd 反代路由
 *    由 core 全权中转，这个 sessionId 就是将来做「踢掉某个终端」的句柄。
 * 2. **票据上限更小**：终端场景最多一两个并发，4 条封顶，超限淘汰最久未用的。
 *
 * 纯 JVM，判据可单测。
 */
class TtydTicketStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val sessionId: String, @Volatile var lastUsedAt: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    val ttlSeconds: Long get() = ttlMs / 1000

    fun issue(sessionId: String): String {
        purgeExpired()
        evictIfNeeded()
        val bytes = ByteArray(TICKET_BYTES)
        random.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[ticket] = Entry(sessionId, nowMs())
        return ticket
    }

    /** 校验并续期；返回 sessionId，无效返回 null。 */
    fun resolve(ticket: String?): String? {
        if (ticket.isNullOrBlank()) return null
        val entry = tickets[ticket] ?: return null
        val now = nowMs()
        if (now - entry.lastUsedAt > ttlMs) {
            tickets.remove(ticket, entry)
            return null
        }
        entry.lastUsedAt = now
        return entry.sessionId
    }

    fun revoke(ticket: String?) {
        if (!ticket.isNullOrBlank()) tickets.remove(ticket)
    }

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

    private fun evictIfNeeded() {
        while (tickets.size >= maxEntries) {
            val oldest = tickets.entries.minByOrNull { it.value.lastUsedAt } ?: return
            tickets.remove(oldest.key, oldest.value)
        }
    }

    companion object {
        /** 终端页一次会话通常几分钟到几十分钟；30 分钟滑动过期。 */
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L

        /** 最多同时 4 张有效票（多标签页 + 快速刷新）。 */
        const val DEFAULT_MAX_ENTRIES = 4

        private const val TICKET_BYTES = 32
    }
}
