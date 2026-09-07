package com.ufi_axis_core.util

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自适应信号量 — 支持运行时动态调整许可数
 *
 * 底层使用 kotlinx.coroutines [Semaphore]，其许可上限在创建时即固定（= [maxPermits]）。
 *
 * 旧实现的 bug：用 `Semaphore(initialPermits)` 当容量，却在扩容分支里"双 release"企图把
 * 可用许可推高到 target（可能 > initial），导致 `availablePermits` 超过初始容量，
 * 抛 `IllegalStateException: The number of released permits cannot be greater than X`
 * （DataScheduler 调 adaptiveAdjust(8,4) 触发扩容即崩）。
 *
 * 修复：
 *  - 底层 Semaphore 容量直接取 [maxPermits]（许可上限）；
 *  - 构造后通过一次性"排空"（acquire max-initial 次）把可用许可降到 [initialPermits]；
 *  - 扩容（current < target）：release 时多 release 一次，把可用许可逐步推高到 target（≤ max，不越界）；
 *  - 收缩（current > target）：release 时吞掉这次 release，把可用许可逐步降到 target。
 */
class AdaptiveSemaphore(
    private val initialPermits: Int,
    private val minPermits: Int = 1,
    private val maxPermits: Int = 10
) {
    init {
        require(initialPermits in minPermits..maxPermits) {
            "initialPermits($initialPermits) must be within [minPermits=$minPermits, maxPermits=$maxPermits]"
        }
    }

    // 容量取 maxPermits，保证扩容双 release 不会越界
    private val semaphore = Semaphore(maxPermits)
    private val _currentPermits = AtomicInteger(initialPermits)
    @Volatile private var targetPermits = initialPermits
    private val initMutex = Mutex()
    @Volatile private var drained = false

    val availablePermits: Int get() = semaphore.availablePermits
    val totalPermits: Int get() = _currentPermits.get()
    val target: Int get() = targetPermits

    fun adjustTo(newPermits: Int) {
        val clamped = newPermits.coerceIn(minPermits, maxPermits)
        val old = targetPermits
        if (old != clamped) {
            targetPermits = clamped
            AppLogger.d("AdaptiveSemaphore", "Target permits: $old -> $clamped (current total=${_currentPermits.get()}, available=${semaphore.availablePermits})")
        }
    }

    private suspend fun ensureInitialized() {
        if (drained) return
        initMutex.withLock {
            if (!drained) {
                // 底层容量是 maxPermits，初始可用需降到 initialPermits
                val toDrain = maxPermits - initialPermits
                if (toDrain > 0) {
                    repeat(toDrain) { semaphore.acquire() }
                }
                drained = true
                AppLogger.d("AdaptiveSemaphore", "Initialized: total=$initialPermits, capacity=$maxPermits, drained=$toDrain")
            }
        }
    }

    suspend fun acquire() {
        ensureInitialized()
        semaphore.acquire()
    }

    suspend fun tryAcquire(): Boolean {
        ensureInitialized()
        return semaphore.tryAcquire()
    }

    fun release() {
        while (true) {
            val current = _currentPermits.get()
            val target = targetPermits
            when {
                current > target -> {
                    // 收缩：吞掉这次 release，永久减 1 个许可
                    if (_currentPermits.compareAndSet(current, current - 1)) {
                        AppLogger.d("AdaptiveSemaphore", "Contracted permit: $current -> ${current - 1}")
                        return
                    }
                }
                current < target -> {
                    // 扩容：多 release 一次，把可用许可推高（capacity=max，不会越界）
                    if (_currentPermits.compareAndSet(current, current + 1)) {
                        semaphore.release()
                        semaphore.release()
                        AppLogger.d("AdaptiveSemaphore", "Expanded permit: $current -> ${current + 1}")
                        return
                    }
                }
                else -> {
                    // 正常释放
                    semaphore.release()
                    return
                }
            }
        }
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
