package com.ufi_axis_core.api.pairing

import java.util.concurrent.ConcurrentHashMap

/**
 * 黑名单命中后的**冷却**（2026-10-15）。
 *
 * ## 它解决什么
 * 拉黑是"明确拒绝"，但机主随时可能**解除**拉黑（改主意、误拉黑）。解除的那一刻，
 * 被拉黑者如果正在反复尝试，就能立刻重新开始爆破 —— 解除动作本身成了一个可利用的窗口。
 * 冷却把这条窗口焊死：**命中过黑名单的键，在冷却期内即使已解除拉黑也照样拒绝**。
 * 冷却只随时间衰减，**不随解除操作清除**。
 *
 * ## 阶梯
 * 首次命中 → [firstCooldownMs]（10 分钟）；连续命中达到 [escalateAfter]（5 次）→
 * [escalatedCooldownMs]（1 小时）。"连续"有 [countResetMs]（24 小时）的衰减窗口：
 * 隔天再来一次不该继承昨天的档位，否则一次误触会永久把档位顶到最高。
 *
 * ## 与退避（[PairingAttemptLimiter]）的分工
 * 退避管"失败尝试"（密码错、签名错…）——它是**渐进的**，正常用户最坏等几秒；
 * 冷却管"明确被拒绝的身份"——它是**果断的**，10 分钟起步。
 * 两者的键一样（指纹 + hwId），但语义与生命周期不同，所以是两个类、两份状态。
 *
 * ## 为什么是内存态
 * 与 [PairingAttemptLimiter] 同一条理由：攻击者无法触发 core 重启（重启接口在鉴权块内，
 * 而它正是不持有有效凭据的那一方）；落盘反而会让一份写坏的计数文件把合法用户关在门外。
 */
class PairingBlockCooldown(
    private val firstCooldownMs: Long = 10 * 60 * 1000L,
    private val escalateAfter: Int = 5,
    private val escalatedCooldownMs: Long = 60 * 60 * 1000L,
    private val countResetMs: Long = 24 * 60 * 60 * 1000L
) {

    private class Entry(
        var count: Int = 0,
        var lastHitAt: Long = 0L,
        var cooldownUntil: Long = 0L
    )

    private val byFingerprint = ConcurrentHashMap<String, Entry>()
    private val byHwId = ConcurrentHashMap<String, Entry>()

    /**
     * 记录一次黑名单命中（两条键各自记录）。
     *
     * @return true 表示本次命中把该键**升到了长冷却档**（调用方据此写一条更醒目的事件）。
     */
    fun recordHit(fingerprint: String?, hwId: String?): Boolean {
        val a = hit(byFingerprint, fingerprint)
        val b = hit(byHwId, hwId)
        return a || b
    }

    /**
     * 该键还需要冷却多久（毫秒）。两条键取最严；0 = 不在冷却中。
     *
     * **不看黑名单本身**：调用方先判黑名单、再判冷却。这样"解除拉黑后仍在冷却"
     * 是天然成立的（两份状态互不依赖）。
     */
    fun remainingMs(fingerprint: String?, hwId: String?): Long {
        val now = System.currentTimeMillis()
        return maxOf(remaining(byFingerprint, fingerprint, now), remaining(byHwId, hwId, now))
    }

    /** 当前连续命中次数（取两条键里最大的）。供测试与诊断使用。 */
    fun hitCount(fingerprint: String?, hwId: String?): Int {
        val now = System.currentTimeMillis()
        return maxOf(countOf(byFingerprint, fingerprint, now), countOf(byHwId, hwId, now))
    }

    /**
     * 带节流的命中记录：距上次命中不足 [minIntervalMs] 时**仍然续冷却，但返回 false**。
     *
     * ## 为什么需要
     * L2 命中发生在**每一次已鉴权请求**上 —— 被拉黑设备的客户端还在轮询，每秒可能撞好几次。
     * 每次都写一条事件会把事件中心刷爆（环形上限虽然会裁，但那几百行足以把真正有用的事件挤走）。
     * 节流窗口内的命中只续冷却、不重复上报。
     *
     * @return true = 本次值得上报（距上次命中已超过节流窗口）
     */
    fun recordHitThrottled(fingerprint: String?, hwId: String?, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = maxOf(lastHitAt(byFingerprint, fingerprint), lastHitAt(byHwId, hwId))
        recordHit(fingerprint, hwId)
        return now - last >= minIntervalMs
    }

    private fun lastHitAt(map: ConcurrentHashMap<String, Entry>, key: String?): Long {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return 0L
        return map[k]?.lastHitAt ?: 0L
    }

    private fun hit(map: ConcurrentHashMap<String, Entry>, key: String?): Boolean {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return false
        if (map.size >= PRUNE_THRESHOLD) map.entries.removeAll { it.value.cooldownUntil < System.currentTimeMillis() }
        val now = System.currentTimeMillis()
        val entry = map.getOrPut(k) { Entry() }
        synchronized(entry) {
            // 衰减：隔了很久再来一次，不继承旧档位
            if (now - entry.lastHitAt > countResetMs) entry.count = 0
            entry.count++
            entry.lastHitAt = now
            val cooldown = if (entry.count >= escalateAfter) escalatedCooldownMs else firstCooldownMs
            entry.cooldownUntil = now + cooldown
            return entry.count >= escalateAfter
        }
    }

    private fun remaining(map: ConcurrentHashMap<String, Entry>, key: String?, now: Long): Long {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return 0
        val until = map[k]?.cooldownUntil ?: return 0
        return (until - now).coerceAtLeast(0)
    }

    private fun countOf(map: ConcurrentHashMap<String, Entry>, key: String?, now: Long): Int {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return 0
        val entry = map[k] ?: return 0
        // 与 hit() 同一口径：衰减窗口之外的历史计数不算数
        return if (now - entry.lastHitAt > countResetMs) 0 else entry.count
    }

    private companion object {
        /** 条目数超过它才做一次清理，避免每次命中都扫全表。 */
        private const val PRUNE_THRESHOLD = 512
    }
}
