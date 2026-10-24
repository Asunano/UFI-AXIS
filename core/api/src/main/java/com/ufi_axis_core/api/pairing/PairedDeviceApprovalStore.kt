package com.ufi_axis_core.api.pairing

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 新设备配对的**待批准请求**（2026-10-15）。
 *
 * ## 它解决什么
 * 默认情况下，任何能进局域网的人拿到配对密码就能配一台新设备进来。批准流程把这一步
 * 变成"机主点头才算"：请求方拿不到配对码（码只发给**已配对设备**与机主配置的外部渠道），
 * 所以它无法自己批准自己。
 *
 * ## 三种"通过"方式（任一满足即可）
 * 1. **App 上点允许** —— 机主就在 App 里，一键通过；
 * 2. **回传正确的配对码** —— 机主从外部通知（邮件/Webhook/系统通知）里读到码，
 *    在网页上输入；码正确即证明"机主看到了这次请求"；
 * 3. **请求方本来就是已配对设备** —— 老设备重连不该被拦（见 [PairingManager] 的判定）。
 *
 * ## 为什么码不返回给请求方
 * 返回了就等于请求方自己批准自己 —— 那这套机制只剩形式。
 * `request-approval` 只回 `sid` 与过期时间，码只出现在**已鉴权**的待批准列表与外部通知里。
 *
 * ## 生命周期
 * 待批准请求是**短命**的（[ttlMs] = 15 分钟），所以内存态即可：进程重启后请求作废，
 * 请求方重新发起一次就行（它本来就在轮询）。**批准结果**不在这里 ——
 * 它落在配对记录上（`PairedDeviceStore`），那才是跨重启要留的东西。
 */
class PairedDeviceApprovalStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxPending: Int = MAX_PENDING
) {

    /** 一条待批准请求。 */
    data class Pending(
        val sid: String,
        /** 6 位配对码（字母 + 数字，去易混字符）。**只发给已配对设备与外部通知**。 */
        val code: String,
        val fingerprint: String,
        val deviceName: String,
        /** 请求来源（已掩码，供展示）：`192.168.***.***` / `隧道`。 */
        val source: String,
        val requestedAt: Long,
        val expiresAt: Long,
        /**
         * 登记顺序（单调递增）。
         *
         * 为什么不用 [requestedAt] 排淘汰顺序：同一毫秒内连续来几条时时间戳相等，
         * `minByOrNull { requestedAt }` 取谁是不确定的 —— 淘汰谁变成了随机。
         */
        val seq: Long = 0L,
        @Volatile var approved: Boolean = false,
        @Volatile var rejected: Boolean = false,
        /**
         * 是否已经就这条请求发过外部通知（2026-10-15）。
         *
         * 同一 sid 的重复请求是幂等的，但**通知不能跟着幂等** —— 否则 Web 刷新一次页面
         * 机主就多收一封邮件（那正是"骚扰式爆破"）。发过一次就置位。
         */
        @Volatile var notified: Boolean = false
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt
    }

    private val random = SecureRandom()
    private val seqGen = java.util.concurrent.atomic.AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * 限速（2026-10-15，方案 §6.4）。
     *
     * 请求本身是幂等的，但**新请求不是** —— 换一个 `sid` 就能新建一条。所以：
     * - 按**来源 IP** 限"新请求数"（[MAX_NEW_PER_IP] / [RATE_WINDOW_MS]）；
     * - 按**全局**限"外部通知次数"（[MAX_NOTIFICATIONS_PER_WINDOW] / [RATE_WINDOW_MS]）。
     *
     * 后者是真正要防的那件事：通知会把机主的手机叫醒，而发起请求是**任何人**都能做的。
     * 没有这条，一个陌生人在网页上不停刷新就能把机主的通知栏刷满。
     */
    private val newRequestTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val notificationTimes = mutableListOf<Long>()

    /** 状态轮询的每 IP 计数表（见 [allowStatusQuery]）。 */
    private val statusQueryTimes = ConcurrentHashMap<String, MutableList<Long>>()

    /** 这个来源现在还能不能发起**新**请求（幂等命中已有 sid 时不受限）。 */
    fun allowNewRequest(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = ip.trim().ifEmpty { "unknown" }
        synchronized(newRequestTimes) {
            val times = newRequestTimes.getOrPut(key) { mutableListOf() }
            times.removeAll { now - it >= RATE_WINDOW_MS }
            if (times.size >= MAX_NEW_PER_IP) return false
            times += now
            return true
        }
    }

    /** 现在还能不能发一条外部通知。 */
    fun allowNotification(now: Long = System.currentTimeMillis()): Boolean {
        synchronized(notificationTimes) {
            notificationTimes.removeAll { now - it >= RATE_WINDOW_MS }
            if (notificationTimes.size >= MAX_NOTIFICATIONS_PER_WINDOW) return false
            notificationTimes += now
            return true
        }
    }

    /**
     * 状态轮询的限速（2026-10-21，审计 §C4）。
     *
     * `GET /pairing/approval-status` 免鉴权、无副作用、纯内存查表，但它现在是请求方
     * **等待期的主链路**（web 700ms 一轮）。入口仍然必须有上限：一个写坏的循环
     * （或几十个标签页）就能把 core 的请求处理位吃满，而这条链路恰恰是免鉴权的。
     *
     * 阈值给得很宽 —— 目的是防"跑飞的循环"，不是限制正常轮询：
     * 单标签页 700ms ≈ 1.4 次/秒，就算开 8 个标签页也只有 12 次/秒。
     */
    fun allowStatusQuery(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = ip.trim().ifEmpty { "unknown" }
        synchronized(statusQueryTimes) {
            if (statusQueryTimes.size >= RATE_TABLE_MAX_KEYS) {
                statusQueryTimes.entries.removeAll { entry ->
                    entry.value.removeAll { now - it >= STATUS_WINDOW_MS }
                    entry.value.isEmpty()
                }
            }
            val times = statusQueryTimes.getOrPut(key) { mutableListOf() }
            times.removeAll { now - it >= STATUS_WINDOW_MS }
            if (times.size >= MAX_STATUS_PER_WINDOW) return false
            times += now
            return true
        }
    }

    /**
     * 登记一次待批准请求（同一 `sid` 幂等：重复请求只刷新展示信息，**不换码**）。
     *
     * 幂等很关键：Web 刷新页面会重发同一个 sid 的请求，如果每次都新建一条，
     * 机主的手机会被弹窗/通知刷屏（那正是"骚扰式爆破"）。
     *
     * ## 2026-10-21 修复（审计 §A2）：**被拒绝的条目不参与幂等复用**
     * 之前它会被原样返回，于是请求方拿同一个 sid 重试永远读到 `rejected`，
     * 机主也无法再批准（`reject` 是终态）—— 15 分钟 TTL 内成了死胡同。
     * 「拒绝」的语义是"这一次不行"，不是"这个会话永久不行"，所以按新请求重建
     * （新码、新时间戳、`rejected=false`），请求方重试时会把机主重新叫起来一次。
     */
    fun request(
        sid: String,
        fingerprint: String,
        deviceName: String,
        source: String
    ): Pending {
        val key = sid.trim()
        require(key.isNotEmpty()) { "sid required" }
        prune()
        val existing = pending[key]
        if (existing != null && !existing.isExpired() &&
            existing.fingerprint == fingerprint && !existing.rejected
        ) {
            // 同 sid 同指纹：保留原码与原批准状态，只刷新展示信息。
            // ★ 必须把更新后的对象写回 map 并**返回它** —— 只改 map 不返回新对象的话，
            //   调用方拿到的是旧名字（首测就踩到：展示名没刷新）。
            val updated = if (deviceName.isNotBlank() && existing.deviceName != deviceName) {
                existing.copy(deviceName = deviceName, source = source)
            } else {
                existing
            }
            pending[key] = updated
            return updated
        }
        if (pending.size >= maxPending) {
            // 满了：先清过期的，再按**登记顺序**淘汰最旧的一条 ——
            // 宁可丢一个旧请求，也不让内存无界增长（同一毫秒的几条不能随机淘汰，见 Pending.seq）。
            prune()
            pending.entries.minByOrNull { it.value.seq }?.let { pending.remove(it.key) }
        }
        val now = System.currentTimeMillis()
        val created = Pending(
            sid = key,
            code = newCode(),
            fingerprint = fingerprint,
            deviceName = deviceName,
            source = source,
            requestedAt = now,
            expiresAt = now + ttlMs,
            seq = seqGen.getAndIncrement()
        )
        pending[key] = created
        return created
    }

    /** 查一条请求（过期视为不存在）。 */
    fun get(sid: String): Pending? {
        val p = pending[sid.trim()] ?: return null
        return if (p.isExpired()) null else p
    }

    /**
     * 机主在 App 上点"允许"。
     *
     * @return true = 确实批准了一条待处理请求
     */
    fun approve(sid: String): Boolean {
        val p = get(sid) ?: return false
        if (p.rejected) return false
        p.approved = true
        return true
    }

    /** 机主在 App 上点"拒绝"。 */
    fun reject(sid: String): Boolean {
        val p = get(sid) ?: return false
        p.rejected = true
        p.approved = false
        return true
    }

    /**
     * 取消所有**尚未落终态**的待批准请求（2026-10-24）。
     *
     * 调用时机：`pairing_approval_required` 被关掉。此时批准策略已经不存在，
     * 旧请求继续留在内存里只会让 App/外部通知继续把它当成"待机主处理"，所以必须清掉。
     *
     * 只删 `pending`：`approved` 的请求可能正被请求方用于完成 confirm，删掉会让它在最后
     * 一步读到 `expired`；`rejected` 也是终态，不会再出现在 App 列表。两者都留给 TTL 自然淘汰。
     *
     * 删除后请求方轮询 `approval-status` 会读到 `expired` —— 这正是"这次申请作废"的语义。
     *
     * @return 实际清掉的条数（0 表示原本就没有 pending）
     */
    fun cancelPending(): Int {
        val victims = pending.filterValues { !it.approved && !it.rejected }.keys.toList()
        var removed = 0
        for (sid in victims) {
            val current = pending[sid] ?: continue
            // 二次确认：快照与删除之间可能刚好被 approve/reject，终态条目不能误删。
            if (!current.approved && !current.rejected && pending.remove(sid, current)) {
                removed++
            }
        }
        return removed
    }

    /**
     * 当前**待处理**列表（已鉴权接口用；**含配对码**）。按请求时间倒序。
     *
     * ## 为什么必须过滤掉已批准/已拒绝的条目（2026-10-17 实测 bug）
     * 这份列表驱动的是 App 的批准弹窗。之前把已批准的条目也列出来 ——
     * 于是机主点完「允许」、下一次刷新又读到同一条，弹窗**关不掉、还反复弹回来**。
     *
     * 条目本身**不能删**：Web 端还要靠它轮询到 `approved` 才能继续登录
     *（`statusOf` 读的是同一份 map）。所以只过滤展示，不动存储。
     */
    fun pendingList(): List<Pending> {
        prune()
        return pending.values
            .filter { !it.approved && !it.rejected }
            .sortedByDescending { it.requestedAt }
    }

    /** 请求方轮询用：`pending` / `approved` / `rejected` / `expired`（**不含码**）。 */
    fun statusOf(sid: String): String {
        val p = pending[sid.trim()] ?: return STATUS_EXPIRED
        return when {
            p.isExpired() -> STATUS_EXPIRED
            p.rejected -> STATUS_REJECTED
            p.approved -> STATUS_APPROVED
            else -> STATUS_PENDING
        }
    }

    /**
     * 校验"回传的配对码"是否与这次请求匹配 —— 匹配即视为机主已批准。
     *
     * 比较**大小写不敏感**（2026-10-21，审计 §C5）：码的字母集是大写，但机主是从通知/邮件里
     * 一个字符一个字符敲的，`AB3K9Z` 敲成 `ab3k9z` 完全正常 —— 之前严格相等会让它静默失败，
     * 用户完全看不出原因，还白耗一次 confirm（并轮换掉配对码）。
     *
     * @return true = 码正确（并且顺手把该请求标成已批准，供状态轮询读到）
     */
    fun verifyCode(sid: String, code: String?): Boolean {
        val p = get(sid) ?: return false
        val given = code?.trim().orEmpty()
        if (given.isEmpty() || !given.equals(p.code, ignoreCase = true)) return false
        p.approved = true
        return true
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { it.value.isExpired(now) }
    }

    /**
     * 6 位配对码，字符集去掉易混字符（`I/O/0/1`）—— 码是要**人眼从通知里读、再手输**的，
     * 混淆字符带来的失败比"熵少一点"更伤体验。
     */
    private fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    companion object {
        /** 待批准有效期：与一次性挑战同量级（15 分钟）。 */
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L

        /** 待批准上限：够用即可，满了先丢最旧的。 */
        const val MAX_PENDING = 32

        /** 限速窗口（与待批准 TTL 同量级）。 */
        const val RATE_WINDOW_MS = DEFAULT_TTL_MS

        /** 同一来源 IP 在窗口内最多能发起几条**新**请求（幂等命中不计）。 */
        const val MAX_NEW_PER_IP = 5

        /** 窗口内最多发几条外部通知 —— 防"陌生人靠不停发起请求刷屏机主"。 */
        const val MAX_NOTIFICATIONS_PER_WINDOW = 3

        /** 状态轮询限速窗口（见 [allowStatusQuery]）。 */
        const val STATUS_WINDOW_MS = 10_000L

        /** 窗口内每 IP 最多问多少次状态：120 次 / 10 秒 ≈ 12 次/秒，只为拦住跑飞的循环。 */
        const val MAX_STATUS_PER_WINDOW = 120

        /** 限速表最多保留多少个来源 IP，超过就先清理空桶，防伪造源地址造成无界增长。 */
        const val RATE_TABLE_MAX_KEYS = 512

        const val CODE_LENGTH = 6

        /** 去掉了 I / O / 0 / 1 —— 见 [newCode]。 */
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXPIRED = "expired"
    }
}
