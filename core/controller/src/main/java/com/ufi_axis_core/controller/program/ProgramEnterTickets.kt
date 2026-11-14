package com.ufi_axis_core.controller.program

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方程序 Gateway 的一次性入口票据（2026-11-10）。
 *
 * ## 为什么需要
 *
 * Gateway 的鉴权是**写在 core:8088 上的 httpOnly cookie**。内置 Web 面板先访问 8088，
 * 之后打开 `设备IP:<externalPort>` 会自动带上；但 App 用系统浏览器打开时，浏览器
 * 没有 App 的 OkHttp cookie，只能看到 401。
 *
 * 这里让 `/api/programs/{id}/open` 额外签一张 60 秒有效的一次性票据，返回
 * `/apps/{id}/enter?t=...`。任何浏览器（App 的系统浏览器、Web、手动粘贴）访问这个
 * 公开地址时，core 种下同一个 cookie 并 302 到程序端口 —— 不需要 App 内嵌 WebView，
 * 也不需要把 cookie 暴露给客户端 JavaScript。
 *
 * ## 安全边界
 *
 * - 票据 192 位随机、单次消费、60 秒过期；拿到票据的人只能进这一个程序的门；
 * - 票据本身不替代程序自己的登录口令（OpenList 的 admin 等仍是第一道门）；
 * - 入口地址不经过 `/api` 鉴权，但**只**接受有效票据，不泄露程序列表。
 */
object ProgramEnterTickets {
    private const val TTL_MS = 60_000L

    private data class Entry(val programId: String, val expiresAt: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** 为 [programId] 签一张新票据；返回 URL-safe token。 */
    fun issue(programId: String): String {
        purgeExpired()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[token] = Entry(programId, System.currentTimeMillis() + TTL_MS)
        return token
    }

    /**
     * 消费票据：成功返回 true，并立即从表里删除（一次性）。
     * 票据不属于该程序 / 已过期 / 已用过，均返回 false。
     */
    fun consume(programId: String, token: String): Boolean {
        if (token.isBlank()) return false
        // 先核对、后删除：拿 A 的票据去撞 B 的入口时必须原样保留，
        // 否则一个猜错 id 的请求就能把合法用户的票据烧掉（DoS）。
        val entry = tickets[token] ?: return false
        if (entry.programId != programId) return false
        if (System.currentTimeMillis() > entry.expiresAt) {
            tickets.remove(token, entry)
            return false
        }
        return tickets.remove(token, entry)
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val it = tickets.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAt < now) it.remove()
        }
    }
}
