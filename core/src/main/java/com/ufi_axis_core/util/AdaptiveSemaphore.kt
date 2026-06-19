package com.ufi_axis_core.util

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自适应信号量 — 支持运行时动态调整许可数
 *
 * kotlinx.coroutines [Semaphore] 创建后 permits 不可变。
 * 本包装器通过在 release() 时"吞掉"或"额外释放"许可，
 * 逐步将实际许可数逼近 [adjustTo] 设定的目标值。
 *
 * - 收缩（target < current）：release 时少释放一次，吞掉一个许可
 * - 扩展（target > current）：release 时多释放一次，增加一个许可
 *
 * 线程安全：通过 AtomicInteger 保护 currentPermits 的 CAS 操作。
 *
 * @param initialPermits 初始许可数
 * @param minPermits 最小许可数（adjustTo 下限）
 * @param maxPermits 最大许可数（adjustTo 上限）
 */
class AdaptiveSemaphore(
    initialPermits: Int,
    private val minPermits: Int = 1,
    private val maxPermits: Int = 10
) {
    private val semaphore = Semaphore(initialPermits)

    private val _currentPermits = AtomicInteger(initialPermits)

    @Volatile
    private var targetPermits = initialPermits

    /** 当前可用许可数（底层 Semaphore 实时值） */
    val availablePermits: Int get() = semaphore.availablePermits

    /** 当前实际许可总数 */
    val totalPermits: Int get() = _currentPermits.get()

    /** 目标许可数（adjustTo 设定的值） */
    val target: Int get() = targetPermits

    /**
     * 动态调整目标许可数。
     * 调整在后续 release() 调用中逐步生效，每次 release 变化 1 个许可。
     */
    fun adjustTo(newPermits: Int) {
        val clamped = newPermits.coerceIn(minPermits, maxPermits)
        val old = targetPermits
        if (old != clamped) {
            targetPermits = clamped
            AppLogger.d("AdaptiveSemaphore", "Target permits: $old -> $clamped")
        }
    }

    suspend fun acquire() {
        semaphore.acquire()
    }

    fun tryAcquire(): Boolean = semaphore.tryAcquire()

    fun release() {
        val current = _currentPermits.get()
        val target = targetPermits

        if (current > target) {
            // 收缩：吞掉这次 release，减少一个许可
            if (_currentPermits.compareAndSet(current, current - 1)) {
                // 不调用 semaphore.release()，许可被"吞掉"
                return
            }
            // CAS 失败，走正常 release
        } else if (current < target) {
            // 扩展：额外释放一个许可
            if (_currentPermits.compareAndSet(current, current + 1)) {
                semaphore.release() // 额外释放，增加可用许可
            }
        }

        semaphore.release()
    }

    /**
     * 带许可的挂起执行。
     * acquire → block → release（try-finally 保证释放）
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
