package com.ufi_axis_core.api.pairing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 配对密码错误尝试限制（内存态，进程重启清零）。
 * （被校验的密码在存储层叫 `devicePassword*`：那是持久化 key 的一部分，改名会让存量配置读不出来。）
 *
 * 两层防护，都**只统计失败**，从不限制正常请求频率：
 *
 * 1. **每 IP 硬锁**：[windowMs]（默认 15 分钟）内最多 [maxAttempts]（默认 5）次密码错误，
 *    达到上限 [isLocked] 返回 true，路由层回 429 `PASSWORD_LOCKED`。
 * 2. **全局节流**：单 IP 锁定可以被"换 IP"绕过（局域网内轮换源地址成本极低）。
 *    全局窗口 [globalWindowMs] 内累计失败超过 [globalThreshold] 后，
 *    [throttleDelayMs] 返回一个递增延时（每多 1 次失败 +[globalDelayStepMs]，上限
 *    [globalMaxDelayMs]），路由层在校验密码**之前** delay 这么久。
 *
 * ## 为什么第 2 层是"节流"而不是"全局硬锁"
 * 全局硬锁能把爆破打死，但同时把**设备主人**也锁在门外：任何一个能进局域网的人
 * 都可以靠 20 次乱输密码让所有客户端在 15 分钟内无法配对/改密码。这与既往
 * QoS 限流过激导致正常操作频繁超时是同一类错误。递增延时把爆破吞吐压到
 * ≤1 次 / [globalMaxDelayMs]，而正常用户最坏只是等几秒——不存在被拒绝的可能。
 *
 * 一次**成功**的密码校验会同时清掉该 IP 计数与全局计数（[reset]）：
 * 能给出正确密码即证明是主人，"正在被爆破"的假设不再成立，无需继续惩罚后续操作。
 * 攻击者无法利用这一点——触发它本身就需要正确密码。
 *
 * 供 confirm / change-password / DELETE 设备 三类密码操作共用同一实例，
 * 避免攻击者通过切换端点绕过锁定。
 */
class PasswordAttemptLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 15 * 60 * 1000L,
    private val globalThreshold: Int = 20,
    private val globalWindowMs: Long = 15 * 60 * 1000L,
    private val globalDelayStepMs: Long = 500L,
    private val globalMaxDelayMs: Long = 5_000L
) {
    private data class Counter(var count: Int, var windowStart: Long)

    private val counters = ConcurrentHashMap<String, Counter>()

    private val globalCount = AtomicInteger(0)
    private val globalWindowStart = AtomicLong(0)

    /** 当前 IP 是否已锁定（窗口内失败次数达到上限）。 */
    fun isLocked(ip: String): Boolean {
        if (ip.isBlank()) return false
        synchronized(counters) {
            val counter = counters[ip] ?: return false
            val now = System.currentTimeMillis()
            if (now - counter.windowStart >= windowMs) {
                counters.remove(ip)
                return false
            }
            return counter.count >= maxAttempts
        }
    }

    /**
     * 校验密码前应等待的毫秒数（全局节流，见类文档）。
     * 未超过 [globalThreshold] 时恒为 0——正常用户完全不受影响。
     */
    fun throttleDelayMs(): Long {
        // 必须持 globalCount 锁：窗口起点与计数是一对状态，
        // 无锁读可能读到"窗口已滚动但计数还没清零"的中间态，从而对正常用户凭空加几秒延时。
        synchronized(globalCount) {
            val now = System.currentTimeMillis()
            if (now - globalWindowStart.get() >= globalWindowMs) {
                return 0
            }
            val over = globalCount.get() - globalThreshold
            if (over <= 0) return 0
            return (over.toLong() * globalDelayStepMs).coerceAtMost(globalMaxDelayMs)
        }
    }

    /**
     * 记录一次失败（同时累加全局计数）。
     * @return true 表示本次失败后该 IP 达到锁定阈值（调用方应回 429），false 表示仍可继续尝试（回 401）。
     */
    fun recordFailure(ip: String): Boolean {
        recordGlobalFailure()
        if (ip.isBlank()) return false
        synchronized(counters) {
            val now = System.currentTimeMillis()
            // 局域网内换源 IP 成本极低，只增不删的话 counters 会随爆破次数无界增长。
            // 过期条目本身已无判定意义（isLocked 遇到就删），这里顺手批量清掉。
            if (counters.size >= PRUNE_THRESHOLD) {
                counters.entries.removeAll { now - it.value.windowStart >= windowMs }
            }
            val counter = counters.getOrPut(ip) { Counter(0, now) }
            if (now - counter.windowStart >= windowMs) {
                counter.count = 0
                counter.windowStart = now
            }
            counter.count++
            return counter.count >= maxAttempts
        }
    }

    private fun recordGlobalFailure() {
        val now = System.currentTimeMillis()
        synchronized(globalCount) {
            if (now - globalWindowStart.get() >= globalWindowMs) {
                globalWindowStart.set(now)
                globalCount.set(0)
            }
            globalCount.incrementAndGet()
        }
    }

    /**
     * 清除计数（密码校验**成功**后调用）：该 IP 的失败计数 + 全局节流计数一起归零。
     * 正确密码即主人身份的证明，见类文档"为什么第 2 层是节流"。
     */
    fun reset(ip: String) {
        synchronized(globalCount) {
            globalCount.set(0)
            globalWindowStart.set(0)
        }
        if (ip.isBlank()) return
        synchronized(counters) { counters.remove(ip) }
    }

    private companion object {
        /** counters 超过这个条目数时才做一次过期清理，避免每次失败都扫全表。 */
        private const val PRUNE_THRESHOLD = 128
    }
}
