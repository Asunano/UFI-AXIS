package com.ufi_axis_core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于系统资源使用率动态调整「目标并行度」的指标追踪器。
 *
 * 真实协程调度仍然通过 Dispatchers.IO 执行（DataScheduler 均为 IO 密集型任务），
 * 本类仅负责计算当前设备允许的目标并行度，供 DataScheduler.getAdaptiveDelay() 计算自适应间隔。
 *
 * 过去曾使用 Dispatchers.Default.limitedParallelism() 创建动态调度器，
 * 但从未有协程在该调度器上启动，因此已剥离 dispatcher 相关代码以消除 API 误导和维护成本。
 */
class DynamicThreadPool {
    private val tag = "DynamicThreadPool"

    private val currentPoolSize = AtomicInteger(INITIAL_POOL_SIZE)

    @Volatile
    private var maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE

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
            _poolInfo.value = ThreadPoolInfo(CORE_POOL_SIZE, maxPoolSize, currentPoolSize.get())
        }
    }

    /** Returns a snapshot of the current thread pool configuration. */
    fun getThreadPoolInfo(): ThreadPoolInfo {
        return ThreadPoolInfo(
            corePoolSize = CORE_POOL_SIZE,
            maxPoolSize = maxPoolSize,
            currentPoolSize = currentPoolSize.get()
        )
    }

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