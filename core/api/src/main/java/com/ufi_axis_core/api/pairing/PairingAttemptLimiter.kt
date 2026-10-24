package com.ufi_axis_core.api.pairing

import java.util.concurrent.ConcurrentHashMap

/**
 * 配对 / 登录失败的**双键指数退避**（2026-10-15）。
 *
 * ## 为什么在既有按 IP 限速之外还要它
 * 既有节流是**每 IP** 的（[PasswordAttemptLimiter] + 路由层的 `rateLimited`）。它的前提是
 * "换源地址有成本"，而在本设备所处的场景里这个前提不成立：局域网内换 IP、走隧道、挂代理，
 * 成本都极低。**指纹与 hwId 是跟着设备走的** —— 换 IP 甩不掉它们。三条键并存、取最严，
 * 不是重复建设，而是"不同维度各自失效"的互补。
 *
 * ## 退避曲线
 * 1s → 2s → 4s → 8s → 30s → 2min → 10min（封顶）。**成功即清零**：
 * 能成功配对/登录就证明是主人，"正在被爆破"的假设不再成立，继续惩罚只会伤到自己。
 *
 * 为什么是"递增延时"而不是"硬锁"：硬锁能把爆破打死，但也能把**设备主人**锁在门外 ——
 * 任何能进局域网的人靠连续乱试就能让所有人 10 分钟进不来（与 [PasswordAttemptLimiter]
 * 里记的"QoS 限流过激"是同一类错误）。递增延时把爆破吞吐压到 ≤1 次 / 10 分钟，
 * 而正常用户最坏只是等几秒。
 *
 * ## 为什么是内存态（进程重启清零）
 * 与 [PasswordAttemptLimiter] 同一口径。攻击者**无法**触发 core 重启：重启接口在 `/api`
 * 鉴权块内，而被攻击者此时正是不持有有效凭据的那一方。反过来，落盘会带来一个更糟的后果 ——
 * 一份被写坏的计数文件能让合法用户凭空背上一段冷却（2026-09-08 配对存储损坏事故的同类形态）。
 *
 * ## 黑名单命中不计数
 * 命中是**终态**（由黑名单那一路直接拒 + 冷却），把它算进失败计数没有意义：
 * 被拉黑者本来就被拒，计数只会让日志与事件更吵。调用方负责跳过。
 */
class PairingAttemptLimiter(
    /** 退避阶梯（毫秒）。默认见 [DEFAULT_DELAYS_MS]。 */
    private val delaysMs: LongArray = DEFAULT_DELAYS_MS
) {

    private class Counter(var count: Int)

    private val byFingerprint = ConcurrentHashMap<String, Counter>()
    private val byHwId = ConcurrentHashMap<String, Counter>()

    /**
     * 这次尝试应施加的等待毫秒数（取两条键里最严的那个）。
     *
     * 两条键都空时恒为 0 —— 与既有 IP 限速一样，正常用户完全不受影响。
     */
    fun delayMsFor(fingerprint: String?, hwId: String?): Long =
        maxOf(delayFor(byFingerprint, fingerprint), delayFor(byHwId, hwId))

    /** 记录一次失败：两条键各自累加。 */
    fun recordFailure(fingerprint: String?, hwId: String?) {
        bump(byFingerprint, fingerprint)
        bump(byHwId, hwId)
    }

    /**
     * 清除计数（**成功**后调用）：两条键一起清零。
     * 见类文档"成功即清零"。
     */
    fun reset(fingerprint: String?, hwId: String?) {
        remove(byFingerprint, fingerprint)
        remove(byHwId, hwId)
    }

    /** 当前连续失败次数（取两条键里最大的）。供测试与诊断使用。 */
    fun failureCount(fingerprint: String?, hwId: String?): Int =
        maxOf(countOf(byFingerprint, fingerprint), countOf(byHwId, hwId))

    private fun delayFor(map: ConcurrentHashMap<String, Counter>, key: String?): Long {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return 0
        val count = map[k]?.count ?: return 0
        if (count <= 0) return 0
        // 第 1 次失败不加延时（count=1 → delays[0] 是"第 2 次尝试"的等待）。
        // 用 (count-1) 索引：第 2 次失败后等 delays[0]，…，超出阶梯取最后一档（封顶）。
        val idx = (count - 1).coerceIn(0, delaysMs.size - 1)
        return delaysMs[idx]
    }

    private fun bump(map: ConcurrentHashMap<String, Counter>, key: String?) {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return
        // 与 PasswordAttemptLimiter 同一条理由：局域网内伪造指纹/hwId 成本很低
        //（生成一对新密钥即可），只增不删会让 map 随爆破次数无界增长。
        if (map.size >= PRUNE_THRESHOLD) prune(map)
        val counter = map.getOrPut(k) { Counter(0) }
        synchronized(counter) { counter.count++ }
    }

    private fun remove(map: ConcurrentHashMap<String, Counter>, key: String?) {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return
        map.remove(k)
    }

    private fun countOf(map: ConcurrentHashMap<String, Counter>, key: String?): Int {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return 0
        return map[k]?.count ?: 0
    }

    /**
     * 超阈值时清掉"只失败过一次"的条目（它们对判定几乎没有贡献，且大概率是伪造出来的）。
     * 这一步刻意不碰失败次数高的条目 —— 那些才是真正在爆破的身份。
     */
    private fun prune(map: ConcurrentHashMap<String, Counter>) {
        map.entries.removeAll { it.value.count <= 1 }
        // 极端情况（全部都在多次失败）兜底：整体清空，宁可放过一轮也不让内存无界增长。
        if (map.size >= PRUNE_THRESHOLD) map.clear()
    }

    companion object {
        /** 默认退避阶梯：1s → 2s → 4s → 8s → 30s → 2min → 10min（封顶）。 */
        val DEFAULT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 30_000, 120_000, 600_000)

        /** 条目数超过它才做一次清理，避免每次失败都扫全表。 */
        private const val PRUNE_THRESHOLD = 512
    }
}
