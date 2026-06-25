package com.ufi_axis_core.util

import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shell 命令 QoS 控制器
 *
 * 通过自适应信号量限制并发 shell 进程数量，防止 CPU 占用和温度飙升。
 * 内置短 TTL 缓存、批量合并、DSL 构建器，为智能化 shell 调度提供统一 API。
 *
 * ## 核心功能
 * - **自适应限流**: root shell 默认 3 并发（1~4 动态调整），普通 shell 5 并发（2~8）
 * - **TTL 缓存**: 相同命令在 TTL 内返回缓存结果（默认 2s，高负载时自动延长）
 * - **批量合并**: 多条命令合并为单次 shell 调用，减少进程创建开销
 * - **DSL 构建器**: `ShellQoS.batch { root(...); cached(...) }` 便捷 API
 * - **非阻塞执行**: 后台任务获取不到信号量时静默跳过
 */
object ShellQoS {

    private const val DEFAULT_ROOT_PERMITS = 3
    private const val NORMAL_PERMITS = 5
    private const val DEFAULT_CACHE_TTL_MS = 2000L
    private const val MAX_CACHE_SIZE = 50  // 缓存硬上限，防止 shell 输出累积
    private const val MAX_CACHED_OUTPUT_SIZE = 16_384  // 缓存截断上限 16KB，避免大 stdout 撑爆内存

    private val rootSemaphore = AdaptiveSemaphore(
        initialPermits = DEFAULT_ROOT_PERMITS, minPermits = 1, maxPermits = 4
    )
    private val normalSemaphore = AdaptiveSemaphore(
        initialPermits = NORMAL_PERMITS, minPermits = 2, maxPermits = 8
    )

    private val cacheLock = Any()

    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val batchCache = object : LinkedHashMap<String, BatchCacheItem>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BatchCacheItem>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    @Volatile
    private var cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS

    // ==================== 批量统计 ====================

    private val batchCount = AtomicInteger(0)
    private val commandsSaved = AtomicInteger(0)

    // ==================== 核心执行方法 ====================

    /** 带限流的 root shell 执行 */
    suspend fun executeAsRoot(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult {
        return rootSemaphore.withPermit {
            ShellExecutor.executeAsRoot(command, timeoutMs)
        }
    }

    /** 带限流的普通 shell 执行 */
    suspend fun execute(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult {
        return normalSemaphore.withPermit {
            ShellExecutor.execute(command, timeoutMs)
        }
    }

    // ==================== 缓存执行 ====================

    /**
     * 带缓存的 root shell 执行
     * 相同命令在 TTL 内返回缓存结果，适用于状态查询类命令
     * （如 isProcessRunning、getprop、CPU 信息读取等）
     */
    suspend fun executeAsRootCached(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult {
        val cacheKey = normalizeCommand(command)
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            cache[cacheKey]?.let { entry ->
                if (now - entry.timestamp < cacheTtlMs) return entry.result
            }
        }
        val result = executeAsRoot(command, timeoutMs)
        synchronized(cacheLock) {
            cache[cacheKey] = CacheEntry(truncateForCache(result), System.currentTimeMillis())
        }
        pruneExpiredCache()
        return result
    }

    /**
     * 带缓存的普通 shell 执行
     */
    suspend fun executeCached(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult {
        val cacheKey = normalizeCommand(command)
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            cache[cacheKey]?.let { entry ->
                if (now - entry.timestamp < cacheTtlMs) return entry.result
            }
        }
        val result = execute(command, timeoutMs)
        synchronized(cacheLock) {
            cache[cacheKey] = CacheEntry(truncateForCache(result), System.currentTimeMillis())
        }
        pruneExpiredCache()
        return result
    }

    // ==================== 批量执行 ====================

    /**
     * 批量执行多条 root 命令（合并为单次 su -c 调用）
     *
     * 命令用 `;` 分隔（非 `&&`），允许单条失败不影响后续。
     * 使用唯一分隔符标记每条命令输出边界，解析后返回 [BatchResult]。
     * 仅消耗 1 个信号量许可，显著减少并发压力。
     *
     * @param commands 要执行的命令列表
     * @param timeoutMs 总超时时间
     */
    suspend fun batchExecuteAsRoot(
        commands: List<String>,
        timeoutMs: Long = 30_000L
    ): BatchResult {
        if (commands.isEmpty()) return BatchResult(emptyList(), emptyList())
        if (commands.size == 1) {
            val r = executeAsRoot(commands[0], timeoutMs)
            return BatchResult(listOf(r), listOf(r.isSuccess))
        }
        val separator = "__QOS_B${System.nanoTime()}__"
        val script = buildBatchScript(commands, separator)
        val raw = executeAsRoot(script, timeoutMs)
        batchCount.incrementAndGet()
        commandsSaved.addAndGet(commands.size - 1)
        return parseBatchResult(raw, separator, commands.size, raw.exitCode)
    }

    /**
     * 批量执行多条普通命令（合并为单次 sh -c 调用）
     */
    suspend fun batchExecute(
        commands: List<String>,
        timeoutMs: Long = 30_000L
    ): BatchResult {
        if (commands.isEmpty()) return BatchResult(emptyList(), emptyList())
        if (commands.size == 1) {
            val r = execute(commands[0], timeoutMs)
            return BatchResult(listOf(r), listOf(r.isSuccess))
        }
        val separator = "__QOS_B${System.nanoTime()}__"
        val script = buildBatchScript(commands, separator)
        val raw = execute(script, timeoutMs)
        batchCount.incrementAndGet()
        commandsSaved.addAndGet(commands.size - 1)
        return parseBatchResult(raw, separator, commands.size, raw.exitCode)
    }

    /**
     * 带缓存的批量 root 执行（整批结果缓存）
     * cache key = 所有命令用 | 连接
     */
    suspend fun batchExecuteAsRootCached(
        commands: List<String>,
        timeoutMs: Long = 30_000L
    ): BatchResult {
        if (commands.isEmpty()) return BatchResult(emptyList(), emptyList())
        val cacheKey = "BATCH:" + commands.sorted().joinToString("|")
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            batchCache[cacheKey]?.let { entry ->
                if (now - entry.timestamp < cacheTtlMs) return entry.result
            }
        }
        val result = batchExecuteAsRoot(commands, timeoutMs)
        synchronized(cacheLock) {
            batchCache[cacheKey] = BatchCacheItem(truncateBatchForCache(result), System.currentTimeMillis())
        }
        pruneBatchCache(now)
        return result
    }

    // ==================== DSL 构建器 ====================

    /**
     * ShellQoS DSL 入口 — 命令构建器风格
     *
     * 用法：
     * ```kotlin
     * val result = ShellQoS.batch {
     *     root("pm disable com.example")
     *     root("pm uninstall -k --user 0 com.example")
     *     cached("getprop persist.sys.timezone")
     * }
     * ```
     *
     * 命令按类型分组执行：root 命令合并为一次 su -c，
     * cached 命令走缓存路径，普通命令合并为一次 sh -c。
     * 结果按添加顺序返回。
     */
    suspend fun batch(block: BatchBuilder.() -> Unit): BatchResult {
        val builder = BatchBuilder().apply(block)
        val tagged = builder.build()
        if (tagged.isEmpty()) return BatchResult(emptyList(), emptyList())

        // 按类型分组，记录原始索引
        val rootIndices = mutableListOf<Int>()
        val rootCmds = mutableListOf<String>()
        val normalIndices = mutableListOf<Int>()
        val normalCmds = mutableListOf<String>()
        val cachedIndices = mutableListOf<Int>()
        val cachedCmds = mutableListOf<String>()

        tagged.forEachIndexed { index, cmd ->
            when (cmd.type) {
                BatchCommand.Type.ROOT -> { rootIndices.add(index); rootCmds.add(cmd.command) }
                BatchCommand.Type.NORMAL -> { normalIndices.add(index); normalCmds.add(cmd.command) }
                BatchCommand.Type.CACHED -> { cachedIndices.add(index); cachedCmds.add(cmd.command) }
            }
        }

        // 执行各分组
        val allResults = arrayOfNulls<ShellExecutor.ShellResult>(tagged.size)
        val allSuccesses = arrayOfNulls<Boolean>(tagged.size)

        if (rootCmds.isNotEmpty()) {
            val r = batchExecuteAsRoot(rootCmds)
            r.results.forEachIndexed { i, result ->
                allResults[rootIndices[i]] = result
                allSuccesses[rootIndices[i]] = r.successes[i]
            }
        }
        if (normalCmds.isNotEmpty()) {
            val r = batchExecute(normalCmds)
            r.results.forEachIndexed { i, result ->
                allResults[normalIndices[i]] = result
                allSuccesses[normalIndices[i]] = r.successes[i]
            }
        }
        for ((i, cmd) in cachedCmds.withIndex()) {
            val r = executeCached(cmd)
            allResults[cachedIndices[i]] = r
            allSuccesses[cachedIndices[i]] = r.isSuccess
        }

        return BatchResult(
            allResults.map { it ?: ShellExecutor.ShellResult(-1, "", "") },
            allSuccesses.map { it ?: false }
        )
    }

    // ==================== 非阻塞执行 ====================

    /**
     * 非阻塞尝试 root shell 执行
     * 用于后台任务（ADB keep-alive、SMS poll 等），
     * 获取不到信号量时返回 null，调用方静默跳过本轮
     */
    suspend fun tryExecuteAsRoot(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult? {
        return if (rootSemaphore.tryAcquire()) {
            try {
                ShellExecutor.executeAsRoot(command, timeoutMs)
            } finally {
                rootSemaphore.release()
            }
        } else null
    }

    /**
     * 非阻塞尝试普通 shell 执行
     */
    suspend fun tryExecute(
        command: String,
        timeoutMs: Long = 30_000L
    ): ShellExecutor.ShellResult? {
        return if (normalSemaphore.tryAcquire()) {
            try {
                ShellExecutor.execute(command, timeoutMs)
            } finally {
                normalSemaphore.release()
            }
        } else null
    }

    // ==================== 状态查询 ====================

    /** root 信号量当前可用许可数 */
    val rootAvailablePermits: Int get() = rootSemaphore.availablePermits

    /** root 信号量当前实际许可数（动态值） */
    val rootTotalPermits: Int get() = rootSemaphore.totalPermits

    /** root 信号量目标许可数 */
    val rootTargetPermits: Int get() = rootSemaphore.target

    /** 普通信号量当前可用许可数 */
    val normalAvailablePermits: Int get() = normalSemaphore.availablePermits

    /** 普通信号量当前实际许可数 */
    val normalTotalPermits: Int get() = normalSemaphore.totalPermits

    /** 缓存条目数 */
    val cacheSize: Int get() = synchronized(cacheLock) { cache.size }

    /** 当前缓存 TTL */
    val currentCacheTtlMs: Long get() = cacheTtlMs

    /** 批量执行统计：总批次数 + 节省的命令数 */
    val batchStats: Map<String, Int> get() = mapOf(
        "total_batches" to batchCount.get(),
        "commands_saved" to commandsSaved.get()
    )

    /** 获取完整 QoS 状态（供 API 返回） */
    fun getStatus(): Map<String, Any> = mapOf(
        "root_available" to rootSemaphore.availablePermits,
        "root_total" to rootSemaphore.totalPermits,
        "root_target" to rootSemaphore.target,
        "normal_available" to normalSemaphore.availablePermits,
        "normal_total" to normalSemaphore.totalPermits,
        "cache_size" to synchronized(cacheLock) { cache.size },
        "cache_ttl_ms" to cacheTtlMs,
        "batch_stats" to mapOf(
            "total_batches" to batchCount.get(),
            "total_commands_saved" to commandsSaved.get()
        )
    )

    // ==================== 动态调整 ====================

    /**
     * 更新缓存 TTL
     * @param ttlMs 新 TTL，限制在 500ms ~ 30000ms 之间
     */
    fun updateCacheTtl(ttlMs: Long) {
        cacheTtlMs = ttlMs.coerceIn(500, 30_000)
        AppLogger.i("ShellQoS", "Cache TTL updated to ${cacheTtlMs}ms")
    }

    /**
     * 更新 root 信号量许可数（即时生效）
     * AdaptiveSemaphore 在后续 release 调用中逐步逼近目标值。
     */
    fun updateRootPermits(permits: Int) {
        val clamped = permits.coerceIn(1, 4)
        rootSemaphore.adjustTo(clamped)
        clearCache()
        AppLogger.i("ShellQoS", "Root permits adjusted to $clamped (target: ${rootSemaphore.target}, current: ${rootSemaphore.totalPermits})")
    }

    /**
     * 自适应调整 — 由 DataScheduler 性能监控循环调用
     * 根据 CPU 负载、温度等指标动态调整 root 信号量许可数
     */
    fun adaptiveAdjust(targetRootPermits: Int) {
        rootSemaphore.adjustTo(targetRootPermits)
    }

    /** 清空命令缓存（包括普通缓存和批量缓存） */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            batchCache.clear()
        }
    }

    /** 重置批量统计计数器 */
    fun resetBatchStats() {
        batchCount.set(0)
        commandsSaved.set(0)
    }

    // ==================== 内部方法 ====================

    /**
     * 标准化缓存 key：去除时间戳等动态参数，标准化空白，提高缓存命中率。
     *
     * 处理场景：
     * - 命令中包含 `_=timestamp` 后缀（如 URL 防缓存参数）
     * - 多余空白/换行
     */
    private fun normalizeCommand(cmd: String): String {
        return cmd.replace(Regex("_=\\d+"), "")  // 去除 _=timestamp
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 构建批量脚本：命令间插入唯一分隔符用于输出解析
     */
    private fun buildBatchScript(commands: List<String>, separator: String): String {
        val sb = StringBuilder()
        commands.forEachIndexed { index, cmd ->
            if (index > 0) sb.append(" ; echo \"$separator\" ; ")
            sb.append(cmd)
        }
        sb.append(" ; echo \"$separator\"")
        return sb.toString()
    }

    /**
     * 解析批量执行输出：按分隔符分割，每段对应一条命令的 stdout
     */
    private fun parseBatchResult(
        raw: ShellExecutor.ShellResult,
        separator: String,
        expectedCount: Int,
        exitCode: Int
    ): BatchResult {
        val segments = raw.stdout.split(separator)
        val results = mutableListOf<ShellExecutor.ShellResult>()
        val successes = mutableListOf<Boolean>()

        for (i in 0 until expectedCount) {
            val segment = segments.getOrNull(i)?.trim() ?: ""
            // 批量命令无法获取单条 exit code，
            // 用输出非空 + 无 "error"/"not found" 关键词作为粗略判断
            val success = segment.isNotEmpty() &&
                !segment.contains("not found", ignoreCase = true) &&
                !segment.contains("No such file", ignoreCase = true)
            results.add(ShellExecutor.ShellResult(exitCode, segment, raw.stderr))
            successes.add(success)
        }

        return BatchResult(results, successes)
    }

    private fun pruneExpiredCache() {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value.timestamp > cacheTtlMs) {
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 截断 ShellResult 的 stdout/stderr 再缓存，防止大输出撑爆内存。
     * 返回截断后的副本，原结果不受影响。
     */
    private fun truncateForCache(result: ShellExecutor.ShellResult): ShellExecutor.ShellResult {
        val stdout = if (result.stdout.length > MAX_CACHED_OUTPUT_SIZE)
            result.stdout.take(MAX_CACHED_OUTPUT_SIZE) else result.stdout
        val stderr = if (result.stderr.length > MAX_CACHED_OUTPUT_SIZE)
            result.stderr.take(MAX_CACHED_OUTPUT_SIZE) else result.stderr
        return if (stdout !== result.stdout || stderr !== result.stderr)
            result.copy(stdout = stdout, stderr = stderr) else result
    }

    /**
     * 截断 BatchResult 中每条 ShellResult 的输出再缓存。
     */
    private fun truncateBatchForCache(result: BatchResult): BatchResult {
        val truncated = result.results.map { truncateForCache(it) }
        return if (truncated != result.results) BatchResult(truncated, result.successes) else result
    }

    private fun pruneBatchCache(now: Long) {
        synchronized(cacheLock) {
            val iterator = batchCache.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value.timestamp > cacheTtlMs) {
                    iterator.remove()
                }
            }
        }
    }

    private open class CacheEntry(
        open val result: ShellExecutor.ShellResult,
        val timestamp: Long
    )

    private data class BatchCacheItem(val result: BatchResult, val timestamp: Long)
}

/**
 * 批量执行结果
 *
 * @param results 每条命令的 ShellResult（按原始顺序）
 * @param successes 每条命令的成功/失败状态
 */
data class BatchResult(
    val results: List<ShellExecutor.ShellResult>,
    val successes: List<Boolean>
) {
    /** 是否所有命令都成功 */
    val allSuccess: Boolean get() = successes.all { it }

    /** 第一个失败的命令索引，全部成功时返回 null */
    val firstFailure: Int? get() = successes.indexOfFirst { !it }.takeIf { it >= 0 }

    /** 获取指定索引命令的 stdout（trim 后） */
    fun stdout(index: Int): String = results.getOrNull(index)?.stdout?.trim() ?: ""
}

/**
 * DSL 命令构建器
 *
 * 通过 [root]、[cached]、[plain] 方法声明命令类型，
 * [ShellQoS.batch] 自动按类型分组执行并合并结果。
 */
class BatchBuilder {
    private val commands = mutableListOf<BatchCommand>()

    /** 添加 root 命令（走 batchExecuteAsRoot） */
    fun root(cmd: String) {
        commands.add(BatchCommand(cmd, BatchCommand.Type.ROOT))
    }

    /** 添加普通命令（走 batchExecute） */
    fun plain(cmd: String) {
        commands.add(BatchCommand(cmd, BatchCommand.Type.NORMAL))
    }

    /** 添加缓存命令（走 executeCached，单条执行） */
    fun cached(cmd: String) {
        commands.add(BatchCommand(cmd, BatchCommand.Type.CACHED))
    }

    internal fun build(): List<BatchCommand> = commands.toList()
}

/**
 * DSL 中的单条命令声明
 */
data class BatchCommand(
    val command: String,
    val type: Type
) {
    enum class Type { ROOT, NORMAL, CACHED }
}
