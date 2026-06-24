package com.ufi_axis_core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread pool that dynamically adjusts its maximum concurrency based on system resource usage.
 *
 * Pool size is governed by CPU usage and available memory thresholds, with the dispatcher
 * automatically reconfigured when conditions change.
 *
 * 优化：复用 Dispatchers.Default.limitedParallelism() 创建的视图，
 * 避免每个 adjustByPerformance 周期都创建新的协程调度器实例。
 */
class DynamicThreadPool {
    private val tag = "DynamicThreadPool"

    private val currentPoolSize = AtomicInteger(INITIAL_POOL_SIZE)

    @Volatile
    private var maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE

    @Volatile
    private var dispatcher: CoroutineDispatcher = createDispatcher(DEFAULT_MAX_POOL_SIZE)

    private val _poolInfo = MutableStateFlow(ThreadPoolInfo(CORE_POOL_SIZE, maxPoolSize, currentPoolSize.get()))

    /** Observable stream of current thread pool configuration. */
    val poolInfo: StateFlow<ThreadPoolInfo> = _poolInfo.asStateFlow()

    /**
     * Adjusts the thread pool based on current system resource usage.
     *
     * @param cpuUsage CPU usage percentage (0–100).
     * @param freeMemory free memory in bytes.
     */
    @Synchronized
    fun adjustByPerformance(cpuUsage: Float, freeMemory: Long) {
        val newMaxSize = when {
            cpuUsage > CPU_HIGH_THRESHOLD -> MIN_POOL_SIZE
            cpuUsage > CPU_MEDIUM_THRESHOLD -> LOW_POOL_SIZE
            freeMemory < FREE_MEMORY_THRESHOLD -> MIN_POOL_SIZE
            else -> DEFAULT_MAX_POOL_SIZE
        }.coerceAtLeast(MIN_POOL_SIZE)

        if (newMaxSize != maxPoolSize) {
            AppLogger.i(tag, "Adjusting thread pool: $maxPoolSize -> $newMaxSize (cpu=$cpuUsage%, freeMem=${freeMemory / 1024 / 1024}MB)")
            maxPoolSize = newMaxSize
            currentPoolSize.set(newMaxSize)
            dispatcher = createDispatcher(newMaxSize)
            _poolInfo.value = ThreadPoolInfo(CORE_POOL_SIZE, maxPoolSize, currentPoolSize.get())
        }
    }

    /** Returns the current [CoroutineDispatcher] configured for this pool. */
    fun getDispatcher(): CoroutineDispatcher = dispatcher

    /** Returns a snapshot of the current thread pool configuration. */
    fun getThreadPoolInfo(): ThreadPoolInfo {
        return ThreadPoolInfo(
            corePoolSize = CORE_POOL_SIZE,
            maxPoolSize = maxPoolSize,
            currentPoolSize = currentPoolSize.get()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createDispatcher(parallelism: Int): CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(parallelism)

    private companion object {
        const val CORE_POOL_SIZE = 1
        const val DEFAULT_MAX_POOL_SIZE = 4
        const val INITIAL_POOL_SIZE = 1
        const val MIN_POOL_SIZE = 1
        const val LOW_POOL_SIZE = 2
        const val CPU_HIGH_THRESHOLD = 80
        const val CPU_MEDIUM_THRESHOLD = 50
        const val FREE_MEMORY_THRESHOLD = 100_000_000L
    }
}

data class ThreadPoolInfo(
    val corePoolSize: Int,
    val maxPoolSize: Int,
    val currentPoolSize: Int
)
