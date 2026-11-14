package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.program.ProcFs
import com.ufi_axis_core.util.program.ProgramPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 单个第三方程序的进程槽：启动、停止、健康判定、日志抽干、退出归因。
 *
 * ## 不变量
 *
 * 运行状态只以进程对象为准，不以持久化状态为准。持久化状态为 running 而进程已退出时，
 * UI 会显示「运行中」，停止操作无响应，看护每轮巡检都会失败一次。因此 [Phase] 为派生值，
 * `state.json` 只记录期望状态与历史。
 *
 * ## `start` 同步、`stop` 挂起的原因
 *
 * - `start` 中耗时的是等待健康检查，该步骤必须异步（否则 HTTP 请求会阻塞 20s）。
 *   因此它在同步完成 spawn 后将等待交给协程，立即返回 pid。
 * - `stop` 需要等待 SIGTERM 的 grace 期，本质是等待，使用挂起语义更自然。
 *   `onDestroy` 中不允许挂起，另提供不等待的 [stopNow]。
 *
 * ## 崩溃归因
 *
 * | code | 含义 | 处理 |
 * |---|---|---|
 * | 0 | 程序正常退出 | 不重启，清除 desired |
 * | 137 | SIGKILL：主动终止、LMK 回收或用户终止 | 以 `stopping` 标志区分 |
 * | 143 | SIGTERM：本进程发出的停止信号 | 正常退出 |
 * | 其他 | 崩溃 | 交给 [ProgramSupervisor] 退避重启 |
 *
 * 137 无法精确归因（内核不提供原因），对外措辞统一为「疑似被系统回收」。
 */
class ProgramRuntime(
    private val paths: ProgramPaths,
    private val store: ProgramStore,
    /** 系统可用内存（MB）。装配层注入 ActivityManager 实现，单测注入固定值。 */
    private val availableMemMb: () -> Long?,
    /** 是否需要互联网（清单 requires.network=online-only）。装配层注入 ConnectivityManager。 */
    private val networkAvailable: () -> Boolean = { true },
    /**
     * Gateway 的 cookie 密钥。装配层从 prefs 取（缺失则生成一次并持久化）。
     * 传空数组表示不启用 Gateway（仅影响访问入口，不影响程序本身）。
     */
    private val gatewaySecret: ByteArray = ByteArray(0),
    /** 物理内存总量（MB）。用于多实例的**总量预算**；拿不到就退回保守值。 */
    private val totalMemMb: () -> Long? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /**
     * 进程退出回调（id, code, reason）。
     *
     * 用可变属性而不是构造参数：Runtime 与 [ProgramSupervisor] **互相依赖**
     * （Runtime 退出时要通知 Supervisor，Supervisor 要调 Runtime 启动）。
     * 构造参数会逼出 `lateinit` 或一个中间接口；setter 是最直白的破环方式。
     */
    @Volatile
    var onExit: (id: String, code: Int, reason: String) -> Unit = { _, _, _ -> }

    enum class Phase { NOT_INSTALLED, INSTALLED, STARTING, RUNNING, STOPPING, FAILED }

    data class Snapshot(
        val id: String,
        val phase: Phase,
        val pid: Int,
        val rssMb: Long,
        val uptimeMs: Long,
        val lastError: String,
        val lastExitCode: Int?
    )

    /**
     * 单个程序的运行时状态。
     *
     * 每个 id 对应一个槽位，因此多个程序可同时运行；各槽位的状态完全隔离：
     * 端口、代际号、日志管道、Gateway 与健康监视线程不共享。
     *
     * 字段均为 `@Volatile`：写入发生在 [lock] 内，读取（snapshot / 路由 / 看护）发生在锁外，
     * 仅保证可见性。任何读-改-写的复合状态必须在 `synchronized(lock)` 中执行。
     */
    private class Slot(val id: String) {
        @Volatile var proc: Process? = null

        /**
         * 进程 pid。`android.os.Process` 的反射取 pid 在部分机型上会失败；
         * spawn 后按 cmdline 扫一次 /proc 作为兜底，否则 RSS、进程树、健康采样全是 0/-1。
         */
        @Volatile var pid: Int = -1

        @Volatile var phase: Phase = Phase.INSTALLED
        @Volatile var startedAt: Long = 0L
        @Volatile var lastError: String = ""

        /** 是否处于主动停止流程（用于退出归因：主动停止不计为崩溃）。 */
        @Volatile var stopping: Boolean = false

        /**
         * 代际号。每次 spawn / stop 递增。
         *
         * 旧进程的退出事件可能在新进程启动之后才到达（`waitFor` 协程调度延迟）。
         * 若无代际号，一次重启会被旧进程的退出事件误判为新进程崩溃，导致无限重启；
         * 此类缺陷难以复现与定位。
         */
        var generation: Int = 0

        var watcher: Job? = null
        var healthJob: Job? = null
        var gateway: ProgramGateway? = null
        var stdoutSink: ProgramLogSink? = null
        var stderrSink: ProgramLogSink? = null
        var pumpThreads: List<Thread> = emptyList()
    }

    /** id → 运行时槽位。没有跑过、也没停过的程序不会在这里留下条目。 */
    private val slots = java.util.concurrent.ConcurrentHashMap<String, Slot>()

    /**
     * 状态迁移的临界区。
     *
     * 使用单一全局锁而非每个槽位一把锁：启动路径中「端口探活 → 回收孤儿 → spawn」是一组
     * 跨槽位的资源判断（端口为全局资源），无法用两把锁分离。持有锁期间不做长 I/O 的约束
     * 不适用于启动路径（其本身需要执行适配器 preflight）。
     */
    private val lock = Any()

    /** 最近一次启动/停止涉及的 id；仅供保留的无参重载使用，其他调用应使用带 id 的重载。 */
    @Volatile
    private var lastTouchedId: String = ""

    // ── 查询 ──

    /**
     * 该程序的数据目录当前是否可用。
     *
     * 看护每轮巡检时调用。外部存储被移除时启动必然失败，但这属于环境不满足而非程序故障；
     * 若不在看护层拦截，失败会被累计并最终触发熔断，用户重新插入存储卡后仍需手动启动。
     */
    fun isStorageReady(id: String): Boolean {
        val meta = store.readMeta(id) ?: return false
        val kind = ProgramPaths.parseStorage(meta.storage)
        if (kind != ProgramPaths.StorageKind.EXTERNAL) return true
        return paths.isStorageWritable(paths.dataDir(id, kind))
    }

    /**
     * 该程序的内容目录当前是否可用。
     *
     * 与 [isStorageReady] 分开判断：内容目录（上传物、媒体库）可能单独位于 SD 卡或 U 盘，
     * 数据目录则位于内部存储。仅检查数据卷时，数据在内部、内容在外部存储的程序在拔出
     * 外部存储后仍显示正常，直到程序写入文件失败才暴露。
     *
     * 内容目录未配置（空串）表示使用内部默认位置，与数据卷同盘，始终可用。
     */
    fun isContentReady(id: String): Boolean {
        val meta = store.readMeta(id) ?: return true
        if (meta.contentDir.isBlank()) return true
        // 只做只读判断，不写入探针文件：本方法在列表轮询（10s）中反复调用，
        // 每轮向 SD 卡写入临时文件没有意义。真实写入探测由安装时的 requireContentSpace 完成。
        val root = java.io.File(meta.contentDir)
        if (!root.exists()) return false
        val dir = paths.contentDir(id, meta.contentDir)
        return (if (dir.exists()) dir else root).canWrite()
    }


    /** 是否存在任意运行中的程序。安装与卸载校验不得使用本方法，应按 id 调用 [isRunning] 的重载。 */
    fun isRunning(): Boolean = slots.values.any { it.proc?.isAlive == true }

    /** 所有运行中程序的整棵进程树 RSS 之和（MB）。无程序运行时为 0。 */
    fun totalRssMb(): Long = slots.values
        .filter { it.proc?.isAlive == true }
        .sumOf { slot -> (ProcFs.readTreeRssKb(slot.pid) ?: 0L) / 1024 }

    /**
     * 多实例的内存总量治理：超出预算时停止占用最大的实例（依据见 [ProgramMemoryBudget]）。
     *
     * 由看护每轮调用（10s 一次），不在各程序自身的监视循环中执行：预算是全局量，
     * 每个实例独立判断会得出互相矛盾的结论。
     *
     * @return 被停止的程序 id；未超预算或无可停止对象时返回 null
     */
    suspend fun enforceMemoryBudget(): String? {
        val budget = ProgramMemoryBudget.budgetMb(totalMemMb())
        if (budget <= 0) return null
        val rss = LinkedHashMap<String, Long>()
        for (slot in slots.values) {
            if (slot.proc?.isAlive != true || slot.phase == Phase.STOPPING) continue
            rss[slot.id] = (ProcFs.readTreeRssKb(slot.pid) ?: 0L) / 1024
        }
        val total = rss.values.sum()
        if (total <= budget) return null
        val victim = ProgramMemoryBudget.pickVictim(rss) ?: return null
        val msg = "内存总量 ${total}MB 超出预算 ${budget}MB，停止占用最大的程序"
        lifecycle(victim, msg)
        AppLogger.w(TAG, "[$victim] $msg")
        runCatching { stop(victim, msg, graceS = 5) }
        // 必须同时清除 desired：否则看护在 10s 后会将刚被淘汰的程序重新拉起，
        // 内存与预算均未变化，形成「停止-启动-停止」的无限循环，后果比不治理更严重。
        runCatching {
            store.writeState(victim, store.readState(victim).copy(desired = false))
        }.onFailure { AppLogger.w(TAG, "清除 desired 失败: ${it.message}") }
        return victim
    }

    /** 这个程序当前在跑吗。 */
    fun isRunning(id: String): Boolean = slots[id]?.proc?.isAlive == true

    /** 最近启动/停止过的程序 id。仅为兼容旧调用保留；新调用应使用带 id 的重载。 */
    fun currentId(): String = lastTouchedId

    /** 任意一个在跑的程序的 pid（多个时取第一个）。 */
    fun pid(): Int = slots.values.firstOrNull { it.proc?.isAlive == true }?.pid ?: -1

    fun pid(id: String): Int = slots[id]?.takeIf { it.proc?.isAlive == true }?.pid ?: -1

    fun phase(): Phase = slots[lastTouchedId]?.phase ?: Phase.NOT_INSTALLED

    fun phase(id: String): Phase = slots[id]?.phase ?: Phase.NOT_INSTALLED

    fun lastError(): String = slots[lastTouchedId]?.lastError.orEmpty()

    fun lastError(id: String): String = slots[id]?.lastError.orEmpty()

    /**
     * 运行快照。phase 每次均由「该 id 的进程是否存活」与「是否已安装」重新派生，
     * 不使用缓存值：缓存值可能未反映最近一次崩溃。
     *
     * 槽位按 id 隔离，因此不会将 A 的进程状态应用到 B；该约束由数据结构保证，
     * 无需额外校验。
     */
    fun snapshot(id: String): Snapshot {
        val slot = slots[id]
        val ph = slot?.phase ?: Phase.NOT_INSTALLED
        val alive = slot?.proc?.isAlive == true
        val pid = if (alive) (slot?.pid ?: -1) else -1
        val installed = store.isInstalled(id)
        val derived = when {
            ph == Phase.FAILED -> Phase.FAILED
            alive && ph == Phase.STARTING -> Phase.STARTING
            alive && ph == Phase.STOPPING -> Phase.STOPPING
            alive -> Phase.RUNNING
            installed -> Phase.INSTALLED
            else -> Phase.NOT_INSTALLED
        }
        val started = slot?.startedAt ?: 0L
        return Snapshot(
            id = id,
            phase = derived,
            pid = pid,
            // 按整棵进程树求和：两段式启动的程序（Syncthing 的监视进程与工作进程）内存由子进程占用
            rssMb = if (pid > 0) (ProcFs.readTreeRssKb(pid) ?: 0L) / 1024 else 0L,
            uptimeMs = if (alive && started > 0) System.currentTimeMillis() - started else 0L,
            lastError = if (slot != null) slot.lastError else store.readState(id).lastExitReason,
            lastExitCode = store.readState(id).lastExitCode
        )
    }

    /** 取（或惰性建）某个 id 的槽位。调用方应持有 [lock]，保证 "取-建" 不并发。 */
    private fun slotOf(id: String): Slot {
        slots[id]?.let { return it }
        val fresh = Slot(id)
        val prev = slots.putIfAbsent(id, fresh)
        return prev ?: fresh
    }

    // ── 孤儿回收 ──

    /**
     * 回收 core 被系统终止后遗留的进程。
     *
     * 触发时机：启动前检测到端口被占用。判据为 cmdline 包含本程序的绝对路径。
     * 仅按 pid 查找会错误终止复用了同一 pid 的无关进程，因此 [ProcFs.findByCmdline]
     * 内部同时校验 `starttime`。
     *
     * 该步骤不可省略：core 被系统终止时，若子进程未被一并回收，它会继续占用端口，
     * 导致新一轮 core 无法启动，用户侧表现为启动无响应。
     *
     * @return 回收的进程数
     */
    fun reclaimOrphans(id: String, entry: String): Int {
        val bin = paths.binFile(id, entry)
        // 不能因为 `bin` 不存在就直接返回：卸载会先删二进制，而 core 崩溃留下的孤儿进程
        // 仍在跑（direct 模式下还占着端口对外服务），此时 `isRunning` 因为是空槽而返回 false，
        // 卸载流程照常放行 —— 没有这一步就永远收不回来。
        val needle = if (bin.exists()) bin.absolutePath else paths.dirOf(id).absolutePath
        val found = ProcFs.findByCmdline(needle)
        if (found.isEmpty()) return 0
        val self = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        var killed = 0
        for (st in found) {
            if (st.pid == self) continue
            AppLogger.w(TAG, "发现遗留进程 pid=${st.pid} state=${st.state}，尝试回收")
            ProcFs.signalTree(st.pid, selfPid = self, signal = 15)
            if (!waitGone(st.pid, 2_000)) ProcFs.signalTree(st.pid, selfPid = self, signal = 9)
            if (waitGone(st.pid, 2_000)) killed++
        }
        return killed
    }

    // ── 启动 ──

    /**
     * 启动。失败时不 spawn，直接返回原因，以区分「未启动」与「启动后崩溃」：
     * 前者不计为崩溃，不计入熔断；后者由 [onExit] 交给 Supervisor。
     */
    fun start(manifest: ProgramManifest): Result<Int> {
        // 所有失败路径都必须留下可读记录。
        //
        // 不能仅依赖 AppLogger：core 的日志有总开关（`log_enabled`），关闭后 AppLogger
        // 不再写入 logcat，启动失败将完全无法追踪。
        // 因此 lifecycle.log 直接写入文件，不经过任何开关。
        val result = runCatching { startInner(manifest) }.getOrElse { e ->
            Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
        }
        // 记录完整 cause 链：`ExceptionInInitializerError` 自身的 message 为 null，
        // 实际原因位于 cause 中；仅记录顶层异常会丢失类型信息。
        result.onFailure { lifecycle(manifest.id, "启动失败: " + describeThrowable(it)) }
        return result
    }

    /**
     * 程序生命周期事件的单行记录（启动、失败、崩溃、熔断）。
     *
     * 写入 `log/programs/<id>/lifecycle.log`（外部存储，用户与排障人员可直接查看）。
     * 文件超过 256KB 时清空重写。此处只记录事件，业务输出由 stdout/stderr 记录。
     */
    /** 将异常链压缩为一行（类名 + message，最多 6 层），用于写入诊断日志。 */
    private fun describeThrowable(e: Throwable): String = buildString {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) append(" <- ")
            append(cur.javaClass.name)
            cur.message?.let { append(": ").append(it.take(200)) }
            cur = cur.cause
            depth++
        }
    }

    fun lifecycle(id: String, message: String) {
        runCatching {
            val dir = paths.logDir(id)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "lifecycle.log")
            if (f.exists() && f.length() > 256 * 1024) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $message\n")
        }
        // 同时写入 AppLogger：日志开关开启时进入 app.log；关闭时 AppLogger 不产生任何输出，
        // 因此上述文件写入是主要记录途径。
        AppLogger.w(TAG, "[$id] $message")
    }

    private fun startInner(manifest: ProgramManifest): Result<Int> {
        val id = manifest.id
        synchronized(lock) {
            // 按 id 取槽位：不同程序的启动互不影响；同一个 id 重复启动仍然幂等拒绝。
            val slot = slotOf(id)
            slot.proc?.takeIf { it.isAlive }?.let {
                return when {
                    slot.phase == Phase.FAILED -> Result.failure(
                        IllegalStateException("$id 已启动但未就绪，请先停止或重启")
                    )
                    slot.phase == Phase.STOPPING -> Result.failure(
                        IllegalStateException("$id 正在停止，请稍候")
                    )
                    else -> Result.success(slot.pid)
                }
            }

            val meta = store.readMeta(id)
            if (meta == null) {
                lifecycle(id, "启动中止：尚未安装（app.json 缺失或无法解析）")
                return Result.failure(IllegalStateException("$id 尚未安装"))
            }
            val bin = paths.binFile(id, meta.entry)
            if (!bin.exists() || bin.length() == 0L) {
                lifecycle(id, "启动中止：二进制缺失 $bin"); return Result.failure(IllegalStateException("$id 的可执行文件缺失，请重新安装"))
            }

            // 有效端口：优先使用用户在「设置」中保存的覆盖值。
            // 所有使用端口的位置都必须使用这两个值，不得读取 manifest.run.internalPort。
            val internalPort = ProgramSettings.internal(meta, manifest)
            val externalPort = ProgramSettings.external(meta, manifest)

            // ⓪ 外部存储：数据目录不在内部时，先确认该存储是否仍可用。
            //    存储卡移除后挂载点通常仍然存在，因此以探针写入为判据，而非 exists()（见 isStorageWritable）。
            val storage = ProgramPaths.parseStorage(meta.storage)
            if (storage == ProgramPaths.StorageKind.EXTERNAL) {
                val dataDir = paths.dataDir(id, storage)
                if (!paths.isStorageWritable(dataDir)) {
                    val msg = "外部存储不可用（已拔出或未挂载）：${dataDir.absolutePath}"
                    slot.lastError = msg
                    lifecycle(id, "启动中止：$msg")
                    return Result.failure(IllegalStateException(msg))
                }
            }

            // ⓪.4 内容目录：数据卷在内部、内容卷在 SD/U 盘时，拔卡后数据卷仍可用，
            //     但程序写入上传物或媒体库会失败。与外部数据卷相同，按环境不满足拦截，
            //     不累计失败、不触发熔断，重新插入后自动恢复。
            if (!isContentReady(id)) {
                val msg = "内容目录所在存储不可用（已拔出或未挂载）：${meta.contentDir}"
                slot.lastError = msg
                lifecycle(id, "启动中止：$msg")
                return Result.failure(IllegalStateException(msg))
            }

            // ⓪.5 网络依赖：online-only 且没有 validated 网络时，起来也会一直失败。
            if (manifest.requires.network == "online-only" && !networkAvailable()) {
                val msg = "该程序需要联网，但当前设备没有可用的互联网连接"
                slot.lastError = msg
                lifecycle(id, "启动中止：$msg")
                return Result.failure(IllegalStateException(msg))
            }

            // ① 内存水位：可用内存不足时拒绝启动，避免启动后被 LMK 回收。
            val avail = availableMemMb()
            if (avail != null && avail < manifest.resources.startMinAvailMb) {
                val msg = "可用内存不足（${avail}MB < ${manifest.resources.startMinAvailMb}MB），已跳过启动"
                slot.lastError = msg
                lifecycle(id, msg); return Result.failure(IllegalStateException(msg))
            }

            // ⓪.7 内容目录：声明了 content 的程序，内容可能位于 SD 卡。
            //     存储卡被移除时必须拒绝启动，否则程序会在其他位置自行创建目录，
            //     使数据实际位置与用户预期不一致。
            if (manifest.content.enabled && manifest.content.required) {
                val contentDir = paths.contentDir(id, meta.contentDir)
                if (!paths.isStorageWritable(contentDir)) {
                    val msg = "内容目录不可用（SD 卡已拔出或未挂载）：${contentDir.absolutePath}"
                    slot.lastError = msg
                    lifecycle(id, "启动中止：$msg")
                    return Result.failure(IllegalStateException(msg))
                }
            }

            // ①.5 磁盘：数据目录所在卷至少保留 [MIN_FREE_DISK_MB]，低于该值拒绝启动。
            //     否则空间耗尽会表现为程序异常退出或写入中断，比拒绝启动更难排查。
            val dataDirForCheck = paths.dataDir(id, storage)
            var probe: File? = dataDirForCheck
            while (probe != null && !probe.exists()) probe = probe.parentFile
            val freeMb = (probe?.usableSpace ?: 0L) / (1024L * 1024L)
            if (freeMb in 1 until MIN_FREE_DISK_MB) {
                val msg = "存储空间不足（${freeMb}MB < ${MIN_FREE_DISK_MB}MB），已跳过启动"
                slot.lastError = msg
                lifecycle(id, msg); return Result.failure(IllegalStateException(msg))
            }

            // ①.6 数据配额：超限时拒绝启动，使占用不再继续增长；运行中仅告警，
            //     不终止进程，也不修改用户数据。清单中 data_quota_mb=0 表示不限制。
            if (manifest.resources.dataQuotaMb > 0) {
                val quotaBytes = manifest.resources.dataQuotaMb * 1024L * 1024L
                val used = paths.usedBytes(id, storage, meta.contentDir)
                if (used > quotaBytes) {
                    val msg = "占用 ${used / 1024 / 1024}MB 已超过配额 ${manifest.resources.dataQuotaMb}MB（含二进制），请清理后再启动"
                    slot.lastError = msg
                    lifecycle(id, msg); return Result.failure(IllegalStateException(msg))
                }
            }

            // ①.2 总量预算：每个程序的 rss_* 按单程序计算，多个程序的阈值之和可能超过
            //     设备物理内存。此处按「运行中实例的实际 RSS + 本程序的 warn 阈值」做准入判断，
            //     超出预算则拒绝启动。
            val budgetMb = ProgramMemoryBudget.budgetMb(totalMemMb())
            val runningRssMb = totalRssMb()
            if (budgetMb > 0 && runningRssMb + manifest.resources.rssWarnMb > budgetMb) {
                val msg = "内存总量预算不足：已用 ${runningRssMb}MB + 本程序约 ${manifest.resources.rssWarnMb}MB" +
                    " > 预算 ${budgetMb}MB，请先停止其它程序"
                slot.lastError = msg
                lifecycle(id, "启动中止：$msg")
                return Result.failure(IllegalStateException(msg))
            }

            // ② 端口：被占时先尝试回收孤儿，再重试一次
            if (!isPortFree(HOST, internalPort)) {
                AppLogger.w(TAG, "端口 $internalPort 被占，尝试回收遗留进程")
                reclaimOrphans(id, meta.entry)
                if (!isPortFree(HOST, internalPort)) {
                    val msg = "端口 $internalPort 被其他程序占用"
                    slot.lastError = msg
                    lifecycle(id, msg)
                    return Result.failure(IllegalStateException(msg))
                }
            }

            // ③ 适配器 preflight：启动前的初始化，例如 OpenList 将监听地址绑定到回环。
            //
            // 首次执行时完成一次性初始化（设置管理员口令、生成初始配置），完成状态由
            // `state.initialized` 记录。若遗漏该步骤，程序仍可运行，但会监听 0.0.0.0：
            // 健康检查返回正常，实际监听地址却为 `*:5244`，Gateway 的访问控制不生效。
            val accessMode = meta.accessMode
            val adapterId = manifest.firstRun?.adapter
            val adapter = if (adapterId.isNullOrBlank()) null else ProgramAdapterRegistry.get(adapterId)
            // 每次启动都执行：preflight 是幂等的（配置已存在时仅校正监听地址）。
            // 若改为只在首次执行，用户切换访问模式后不会生效，必须卸载重装。
            if (adapter != null) {
                val ctx = ProgramContext(
                    id = id,
                    binPath = bin,
                    dataDir = paths.dataDir(id, storage),
                    confDir = paths.confDir(id, storage),
                    runDir = paths.runDir(id),
                    contentDir = paths.contentDir(id, meta.contentDir),
                    contentEnabled = manifest.content.enabled,
                    internalPort = internalPort,
                    accessMode = accessMode
                )
                val r = runCatching { adapter.preflight(ctx) }
                    .getOrElse { e -> Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName)) }
                if (r.isFailure) {
                    val msg = "首次初始化失败：${r.exceptionOrNull()?.message ?: "未知原因"}"
                    slot.lastError = msg
                    lifecycle(id, msg)
                    return Result.failure(IllegalStateException(msg))
                }
                store.writeState(id, store.readState(id).copy(initialized = true))
                lifecycle(id, "首次初始化完成")
            }

            // ④ 参数展开：未知占位符在这里失败，绝不会把 "{FOO}" 当字面量传给程序
            val env = ProgramArgs.env(
                paths, id, meta.entry,
                internalPort, externalPort, storage,
                contentRoot = meta.contentDir,
                accessMode = accessMode,
            )
            val argv = ProgramArgs.expand(manifest.run.argv, env).getOrElse { e ->
                return Result.failure(IllegalStateException(e.message ?: "启动参数展开失败"))
            }
            if (argv.isEmpty()) return Result.failure(IllegalStateException("启动参数为空"))
            val extraEnv = ProgramArgs.expandEnv(manifest.run.env, env).getOrElse { e ->
                return Result.failure(IllegalStateException(e.message ?: "环境变量展开失败"))
            }
            // 环境变量白名单：清单来自远端（见 docs/ACKNOWLEDGED-RISKS.md R3），
            // 而 `putAll(extraEnv)` 在 TMPDIR/HOME/PATH 之后执行、能覆盖一切 ——
            // 一个 `LD_PRELOAD={DATA}/libx.so` 就能在"sha256 从未变过"的合法二进制里执行任意代码。
            val rejectedEnv = extraEnv.keys.filterNot { it in ALLOWED_ENV_KEYS }
            if (rejectedEnv.isNotEmpty()) {
                slot.lastError = "清单要求设置不允许的环境变量：${rejectedEnv.joinToString("、")}"
                AppLogger.w(TAG, "拒绝启动 $id：${slot.lastError}")
                return Result.failure(IllegalStateException(slot.lastError))
            }
            val fullArgv = listOf(env.binPath) + argv

            // ④ spawn
            val cwd = ProgramArgs.expand(listOf(manifest.run.cwd), env)
                .getOrElse { e -> return Result.failure(IllegalStateException(e.message ?: "工作目录展开失败")) }
                .firstOrNull()?.takeIf { it.isNotBlank() } ?: env.dataDir
            val cwdDir = File(cwd).apply { if (!exists()) mkdirs() }
            // 工作目录必须落在本程序目录内：清单若把它指到 core 的私有目录，程序就能以 core
            // 的身份读 shared_prefs、改别的程序的数据（两者同 uid）。
            val programRoot = File(env.dataDir).parentFile?.canonicalPath.orEmpty()
            if (programRoot.isBlank() ||
                !cwdDir.canonicalPath.startsWith(programRoot + File.separator)
            ) {
                slot.lastError = "工作目录不在程序目录内：${cwdDir.canonicalPath}"
                AppLogger.w(TAG, "拒绝启动 $id：${slot.lastError}")
                return Result.failure(IllegalStateException(slot.lastError))
            }
            val p = try {
                ProcessBuilder(fullArgv).apply {
                    directory(cwdDir)
                    environment().apply {
                        // Android 没有 /etc/resolv.conf 与 /tmp：Go 程序依赖 TMPDIR，
                        // 未设置时会在当前目录创建临时文件，污染数据目录。
                        put("TMPDIR", env.cacheDir)
                        put("HOME", env.dataDir)
                        put("PATH", "/system/bin:/system/xbin")
                        putAll(extraEnv)
                    }
                    redirectErrorStream(false)
                }.start()
            } catch (e: Exception) {
                // 最常见：SELinux 拒 exec（EACCES）、缺解释器（ENOENT）、架构不符（ENOEXEC）
                val msg = "启动失败：${e.message ?: e.javaClass.simpleName}"
                slot.lastError = msg
                AppLogger.w(TAG, "spawn 失败 $id: $msg")
                return Result.failure(IllegalStateException(msg))
            }

            slot.proc = p
            lastTouchedId = id
            // 反射 pid 失败时按 cmdline 扫 /proc 兜底；否则 RSS/进程树/健康采样全是 0/-1。
            slot.pid = p.pidOrNull().takeIf { it > 0 }
                ?: ProcFs.findByCmdline(paths.binFile(id, meta.entry).absolutePath)
                    .firstOrNull()?.pid
                ?: -1
            slot.stopping = false
            slot.startedAt = System.currentTimeMillis()
            slot.generation++
            val myGen = slot.generation
            slot.phase = Phase.STARTING
            slot.lastError = ""

            // ④.5 Gateway：程序只绑定回环地址，对外访问由 Gateway 转发。
            // 对外端口在清单端口被占用时顺序顺延，并将实际值写回 app.json。
            if (accessMode == "gateway" && (meta.externalPort > 0 || manifest.run.externalPort > 0) &&
                gatewaySecret.isNotEmpty()
            ) {
                val desiredPort = externalPort
                val actualPort = allocateGatewayPort(desiredPort)
                if (actualPort != meta.externalPort) {
                    store.updateMeta(id) { it.copy(externalPort = actualPort) }
                    lifecycle(id, "Gateway 端口 $desiredPort 不可用，改用 $actualPort")
                }
                val g = ProgramGateway(
                    appId = id,
                    externalPort = actualPort,
                    internalPort = internalPort,
                    secret = gatewaySecret,
                    scope = scope
                )
                g.start().onFailure { lifecycle(id, "Gateway 启动失败：${it.message}") }
                slot.gateway = g
            }

            // ⑤ 日志抽干：必须持续读取，否则管道缓冲区（64KB）写满后程序会阻塞。
            // `log_quota_mb` 为每程序总量，由两路流（stdout/stderr）与 maxFiles 份滚动文件
            // 平摊。若按每文件计算，配额 32MB 时实际占用可达 192MB（32×3×2）。
            val quota = ProgramLogSink.perFileQuotaBytes(manifest.resources.logQuotaMb)
            slot.stdoutSink = ProgramLogSink(File(paths.logDir(id), "stdout.log"), quota, ProgramLogSink.MAX_FILES)
            slot.stderrSink = ProgramLogSink(File(paths.logDir(id), "stderr.log"), quota, ProgramLogSink.MAX_FILES)
            slot.pumpThreads = listOf(
                pump(p.inputStream, slot.stdoutSink!!, "prog-$id-out"),
                pump(p.errorStream, slot.stderrSink!!, "prog-$id-err")
            )

            // ⑥ 退出监听（代际号防串扰）
            slot.watcher = scope.launch(Dispatchers.IO) {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                handleExit(id, myGen, code)
            }

            // ⑦ 健康等待（不阻塞调用方）
            slot.healthJob = scope.launch(Dispatchers.IO) {
                val ok = waitHealthy(manifest, p, slot.pid, internalPort)
                if (!ok) {
                    // 不能只写入 FAILED 后返回：进程仍存活时 snapshot 会将其派生为 RUNNING，
                    // start 也会因「已有进程」返回成功，重试操作不会产生任何效果。
                    val reason = "启动后 ${manifest.run.health.startupGraceMaxS}s 内未就绪（首次建库可能较慢，可调大 startup_grace_max_s）"
                    lifecycle(id, "启动超时：$reason")
                    failAndStop(id, reason, manifest.run.stop.graceS, myGen)
                    return@launch
                }
                synchronized(lock) {
                    if (slot.generation == myGen && p.isAlive) slot.phase = Phase.RUNNING
                }
                AppLogger.i(TAG, "$id 已就绪（pid=${p.pidOrNull()}）")
                lifecycle(id, "已就绪")

                // ── 运行中监控 ──
                // 启动后仍需持续监控：存储被移除、服务无响应只能在此处发现。
                var fails = 0
                var lastRssWarnAt = 0L
                var lastQuotaWarnAt = 0L
                val intervalMs = manifest.run.health.intervalS * 1000L
                while (isActive && p.isAlive) {
                    delay(intervalMs)
                    if (!p.isAlive || slot.generation != myGen) break

                    // 存储先查：数据目录没了，健康检查必然失败，但原因完全不同
                    if (storage == ProgramPaths.StorageKind.EXTERNAL &&
                        !paths.isStorageWritable(paths.dataDir(id, storage))
                    ) {
                        lifecycle(id, "外部存储断开，停止程序（数据可能不完整）")
                        store.writeState(
                            id,
                            store.readState(id).copy(lastExitReason = "外部存储断开")
                        )
                        stop(id, "外部存储断开", manifest.run.stop.graceS)
                        break
                    }

                    // ── 资源分级 ─────────────────────────────
                    // 执行清单中的 rss_* 与 max_processes：内存达到 kill/restart 阈值、
                    // 进程数超过上限时停止程序。数据配额仍为告警级，不终止进程。
                    val pidForSample = slot.pid
                    if (pidForSample > 0) {
                        // 必须按整棵进程树求和：仅读取主 pid 时，监视进程仅占用十几 MB，
                        // 工作进程的数百 MB 不会计入，rss_* 三档阈值实际不生效。
                        val rssMb = (ProcFs.readTreeRssKb(pidForSample) ?: 0L) / 1024
                        if (rssMb >= manifest.resources.rssKillMb) {
                            val reason = "内存 ${rssMb}MB 超过 kill 阈值 ${manifest.resources.rssKillMb}MB"
                            lifecycle(id, reason)
                            failAndStop(id, reason, manifest.run.stop.graceS, myGen)
                            break
                        }
                        if (rssMb >= manifest.resources.rssRestartMb) {
                            val reason = "内存 ${rssMb}MB 超过 restart 阈值 ${manifest.resources.rssRestartMb}MB"
                            lifecycle(id, reason)
                            failAndStop(id, reason, manifest.run.stop.graceS, myGen)
                            break
                        }
                        if (rssMb >= manifest.resources.rssWarnMb &&
                            System.currentTimeMillis() - lastRssWarnAt > 5 * 60_000L
                        ) {
                            lastRssWarnAt = System.currentTimeMillis()
                            lifecycle(id, "内存 ${rssMb}MB 超过 warn 阈值 ${manifest.resources.rssWarnMb}MB")
                        }

                        val procCount = 1 + ProcFs.descendantsOf(pidForSample).size
                        if (procCount > manifest.resources.maxProcesses) {
                            val reason = "进程数 $procCount 超过上限 ${manifest.resources.maxProcesses}"
                            lifecycle(id, reason)
                            failAndStop(id, reason, manifest.run.stop.graceS, myGen)
                            break
                        }
                    }

                    // 内容目录可能位于 SD 卡：运行期间被移除会导致程序持续写入失败。
                    // 判据与 dataDir 相同：以探针写入判断，而非 exists()。
                    if (manifest.content.enabled && manifest.content.required &&
                        !paths.isStorageWritable(paths.contentDir(id, meta.contentDir))
                    ) {
                        lifecycle(id, "内容目录不可用（SD 卡已拔出），停止程序")
                        stop(id, "内容目录不可用", manifest.run.stop.graceS)
                        break
                    }

                    if (manifest.resources.dataQuotaMb > 0 &&
                        System.currentTimeMillis() - lastQuotaWarnAt > 5 * 60_000L
                    ) {
                        val quota = manifest.resources.dataQuotaMb * 1024L * 1024L
                        val used = paths.usedBytes(id, storage, meta.contentDir)
                        if (used > quota) {
                            lastQuotaWarnAt = System.currentTimeMillis()
                            lifecycle(id, "占用（二进制+数据）${used / 1024 / 1024}MB 超过配额 ${manifest.resources.dataQuotaMb}MB")
                        }
                    }

                    if (healthOnce(manifest, internalPort)) {
                        fails = 0
                    } else {
                        fails++
                        lifecycle(id, "健康检查失败 $fails/${manifest.run.health.failThreshold}")
                        if (fails >= manifest.run.health.failThreshold) {
                            lifecycle(id, "连续健康检查失败，停止并交由看护重启")
                            failAndStop(id, "健康检查连续失败", manifest.run.stop.graceS, myGen)
                            break
                        }
                    }
                }
            }

            AppLogger.i(TAG, "已启动 $id（端口 $internalPort，pid=${slot.pid}）")
            return Result.success(slot.pid)
        }
    }

    // ── 停止 ──

    /**
     * 优雅停止：SIGTERM → 等 grace → SIGKILL → 清进程树。
     *
     * **调用方必须先清除 `desired`**，否则看护会在数秒后重新启动该程序。
     */
    suspend fun stop(id: String, reason: String, graceS: Int = 10): Result<Unit> = withContext(Dispatchers.IO) {
        val slot = synchronized(lock) {
            val s = slots[id] ?: return@withContext Result.success(Unit)
            val cur = s.proc
            if (cur == null || !cur.isAlive) {
                s.phase = if (store.isInstalled(id)) Phase.INSTALLED else Phase.NOT_INSTALLED
                return@withContext Result.success(Unit)
            }
            s.stopping = true
            s.generation++
            s.phase = Phase.STOPPING
            s
        }

        val p = slot.proc ?: return@withContext Result.success(Unit)
        val pid = slot.pid.takeIf { it > 0 } ?: p.pidOrNull()
        AppLogger.i(TAG, "停止程序 $id pid=$pid（$reason）")

        // 先向整棵进程树发送 SIGTERM（叶子优先）：仅终止父进程会使子进程由 init 收养，端口仍被占用。
        if (pid > 0) ProcFs.signalTree(pid, signal = 15)
        runCatching { p.destroy() }

        if (!waitGone(p, graceS * 1000L)) {
            AppLogger.w(TAG, "优雅停止超时，强制结束 pid=$pid；${ProcFs.describeTree(pid)}")
            if (pid > 0) ProcFs.signalTree(pid, signal = 9)
            runCatching { p.destroyForcibly() }
            if (!waitGone(p, 3_000)) {
                // D 状态（不可中断的 I/O 等待）无法终止，记录错误后不再重试。
                slot.lastError = "进程处于不可中断状态，无法结束（可能需重启设备）"
                AppLogger.e(TAG, slot.lastError)
            }
        }

        runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        // 先关闭访问入口：进程已停止，继续转发只会把请求发往无人监听的端口。
        runCatching { slot.gateway?.stop() }
        slot.gateway = null
        // 子进程兜底清理：父进程退出后，其子进程被 init 收养，ppid 链断开，
        // 只能按 cmdline（bin/<entry> 绝对路径）查找。无法获取 pid 时这是唯一手段。
        val meta = store.readMeta(id)
        if (meta != null) {
            val leftovers = reclaimOrphans(id, meta.entry)
            if (leftovers > 0) AppLogger.w(TAG, "停止后清理残留进程 ${leftovers} 个")
        }
        joinPumps(slot)
        closeSinks(slot)
        slot.healthJob?.cancel()
        slot.healthJob = null

        synchronized(lock) {
            if (slot.proc === p) {
                slot.proc = null
                slot.pid = -1
            }
            if (slot.proc == null) {
                slot.phase = if (store.isInstalled(id)) Phase.INSTALLED else Phase.NOT_INSTALLED
            }
        }
        Result.success(Unit)
    }

    /**
     * 停止所有运行中的程序（用于「全部停止」操作与停机流程）。
     *
     * 逐个串行停止：并行停止会同时对多棵进程树发信号，故障时无法区分来源；
     * 实例数量为 1–4 个，也不足以抵消并行的复杂度。返回第一个失败原因，
     * 但不影响其余实例继续停止：停机流程中部分成功优于全部放弃。
     */
    suspend fun stopAll(reason: String, graceS: Int = 10): Result<Unit> {
        var firstError: Throwable? = null
        for (id in slots.keys.toList()) {
            val r = stop(id, reason, graceS)
            if (r.isFailure && firstError == null) firstError = r.exceptionOrNull()
        }
        return firstError?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** 兼容旧名：语义等同于 [stopAll]；多实例下不存在「当前那一个」的概念。 */
    suspend fun stopCurrent(reason: String, graceS: Int = 10): Result<Unit> = stopAll(reason, graceS)

    /**
     * 运行中/启动中失败：先停进程，再把槽位落到 FAILED 并通知看护。
     *
     * 不能只写入 phase=FAILED 后返回：进程仍存活时 snapshot 会将其派生为 RUNNING，
     * start 也会因「进程存活」直接返回成功，重试操作不会产生任何效果。
     */
    private suspend fun failAndStop(id: String, reason: String, graceS: Int, myGen: Int) {
        runCatching { stop(id, reason, graceS) }
        val slot = slots[id]
        synchronized(lock) {
            // stop 会使 generation 递增；若期间有新进程启动，generation 更大，此时不得覆盖状态。
            if (slot != null && slot.generation == myGen + 1) {
                slot.phase = Phase.FAILED
                slot.lastError = reason
            }
        }
        val stopped = slot?.proc == null
        if (stopped) onExit(id, -1, reason)
    }

    /**
     * 不等待的停止（`onDestroy` 用）。
     *
     * 服务正在退出时没有 10s 可以等：先 SIGTERM，1 秒后补 SIGKILL。
     * 剩下的交给内核 —— core 进程一死，同进程组的子进程通常会被一起回收。
     */
    fun stopNow(reason: String) {
        // 对每个运行中的实例发送信号。先收集再操作，避免遍历期间被退出回调修改 map。
        val live = slots.values.filter { it.proc?.isAlive == true }
        if (live.isEmpty()) return
        for (slot in live) {
            val p = slot.proc ?: continue
            synchronized(lock) {
                slot.stopping = true
                slot.generation++
                slot.phase = Phase.STOPPING
            }
            val pid = slot.pid
            if (pid > 0) ProcFs.signalTree(pid, signal = 15)
            runCatching { p.destroy() }
            scope.launch(Dispatchers.IO) {
                delay(1_000)
                if (p.isAlive) {
                    if (pid > 0) ProcFs.signalTree(pid, signal = 9)
                    runCatching { p.destroyForcibly() }
                }
            }
        }
        AppLogger.i(TAG, "已发出停止信号（$reason），共 ${live.size} 个实例")
    }

    /**
     * 服务停机：先向所有实例发送停止信号，再取消运行时自身的协程。
     *
     * 顺序不可颠倒：`scope.cancel()` 会使 [stopNow] 中延迟 1 秒补发 SIGKILL 的协程无法执行，
     * 阻塞在 SIGTERM 上的程序将成为孤儿进程。
     */
    fun shutdown() {
        stopNow("服务停机")
        scope.cancel()
    }

    // ── 内部 ──

    /**
     * 取子进程 pid。
     *
     * **Android 的 `java.lang.Process` 没有 `pid()`**：该方法属于 Java 9 API，Android 仅提供
     * `isAlive` / `destroyForcibly` / `waitFor(timeout)`，`android.jar` 中不含 `pid()`，
     * 直接调用 `p.pid()` 无法通过编译。实际实现类（`ProcessImpl`）通常提供该方法，
     * 因此通过反射获取；获取失败返回 -1。
     *
     * **-1 属于可接受的降级而非错误**：所有依赖 pid 的路径都有 cmdline 兜底
     * （见 [reclaimOrphans]：按 `bin/<entry>` 的绝对路径查找进程，比按 pid 更可靠，
     * 因为孙进程的 cmdline 也包含该路径）。
     */
    private fun Process.pidOrNull(): Int = runCatching {
        val m = javaClass.getMethod("pid")
        (m.invoke(this) as? Long)?.toInt() ?: (m.invoke(this) as? Int)
    }.getOrNull() ?: -1

    private fun handleExit(id: String, myGen: Int, code: Int) {
        val slot = slots[id] ?: return
        var wasStopping = false
        synchronized(lock) {
            // 旧代际的退出事件：可能来自刚被替换的上一个进程，直接丢弃；
            // 否则一次重启会被误判为新进程崩溃，导致无限重启。
            if (slot.generation != myGen) return
            slot.proc = null
            slot.pid = -1
            slot.phase = if (code == 0) Phase.INSTALLED else Phase.FAILED
            wasStopping = slot.stopping
        }
        runCatching { slot.gateway?.stop() }
        slot.gateway = null
        closeSinks(slot)
        val reason = when {
            wasStopping -> "用户停止"
            code == 0 -> "程序自行退出"
            code == 137 -> "疑似被系统回收（SIGKILL）"
            code == 143 -> "收到 SIGTERM"
            else -> "崩溃退出（code=$code）"
        }
        if (id.isNotBlank()) {
            val st = store.readState(id)
            store.writeState(
                id,
                st.copy(
                    lastExitCode = code,
                    lastExitReason = reason,
                    lastStopAt = System.currentTimeMillis()
                )
            )
        }
        AppLogger.w(TAG, "程序退出 $id: code=$code（$reason）")
        // 退出事件同样必须留痕：AppLogger 关闭后，退出原因只能由此行记录。
        if (id.isNotBlank()) lifecycle(id, "退出: code=$code（$reason）")
        if (id.isNotBlank()) onExit(id, code, reason)
    }

    /**
     * 等待健康检查通过。两段式：`startupGraceS` 内每 1s 一次（快速就绪），之后每 3s 一次，
     * 直到 `startupGraceMaxS`。首次建库较慢不计为失败：OpenList 首次启动需迁移数据库。
     */
    private suspend fun waitHealthy(
        manifest: ProgramManifest,
        p: Process,
        spawnPid: Int,
        internalPort: Int = manifest.run.internalPort
    ): Boolean {
        val h = manifest.run.health
        val startAt = System.currentTimeMillis()
        val fastUntil = startAt + h.startupGraceS * 1000L
        var hardDeadline = startAt + h.startupGraceMaxS * 1000L
        val extendDeadline = startAt + h.startupGraceExtendMaxS * 1000L
        val pid = spawnPid.takeIf { it > 0 } ?: p.pidOrNull()

        /** CPU ticks、RSS 与进程状态；读取失败返回 null（不计为进展）。 */
        fun sample(): Triple<Long, Long, Char>? {
            val st = ProcFs.readStat(pid) ?: return null
            return Triple(st.utimeTicks + st.stimeTicks, ProcFs.readTreeRssKb(pid) ?: 0L, st.state)
        }

        var last = sample()
        var lastProgressAt = startAt
        var sawProgress = false

        while (System.currentTimeMillis() < hardDeadline) {
            if (!p.isAlive) return false
            if (healthOnce(manifest, internalPort)) return true

            delay(if (System.currentTimeMillis() < fastUntil) 1000 else 3000)

            // ── 进展检测 ──
            val cur = sample()
            if (cur != null && last != null) {
                val cpuGrew = cur.first > last.first
                val rssGrew = cur.second > last.second + 512   // 半 MB 阈值，用于滤除噪声
                // D = 不可中断睡眠（等待 I/O）：表示进程仍在工作，只是尚未返回结果
                val doingIo = cur.third == 'D'
                if (cpuGrew || rssGrew || doingIo) {
                    lastProgressAt = System.currentTimeMillis()
                    sawProgress = true
                }
            }
            last = cur

            val now = System.currentTimeMillis()
            // 长时间无进展即判定为卡死，早于硬性上限得出结论。
            if (now - lastProgressAt > h.stallTimeoutS * 1000L) {
                lifecycle(manifest.id, "启动疑似卡死：${h.stallTimeoutS}s 内无 CPU/内存/IO 进展")
                return false
            }
            // 有进展时允许推迟硬性上限，但不超过 extend 上限。
            if (sawProgress && now > hardDeadline - 30_000L && hardDeadline < extendDeadline) {
                hardDeadline = minOf(now + 60_000L, extendDeadline)
                lifecycle(manifest.id, "仍在初始化，延长等待至 ${(hardDeadline - startAt) / 1000}s")
            }
        }
        return false
    }

    /**
     * 一次健康探测。
     *
     * 判定顺序是刻意的：先确认可连接或进程存活，再校验响应内容。
     * OpenList 的 `/ping` 若要求登录，仅接受 200 会把正常实例判为卡死。
     */
    /**
     * 端口当前是否可绑定（用于「设置」保存前的校验）。
     *
     * [wildcard] = true 时按 0.0.0.0 探测：Gateway 监听所有网卡，仅探测 127.0.0.1
     * 会遗漏其他进程绑定在局域网地址上的冲突。
     */
    fun isPortAvailable(port: Int, wildcard: Boolean = false): Boolean =
        isPortFree(if (wildcard) "0.0.0.0" else HOST, port)

    fun healthOnce(manifest: ProgramManifest, internalPort: Int = manifest.run.internalPort): Boolean {
        val h = manifest.run.health
        return when (h.kind) {
            // 按 id 判活：不能以「是否有任意程序运行」代替，否则会把 A 的存活当作 B 的健康状态。
            "process" -> isRunning(manifest.id)
            "tcp" -> isPortOpen(HOST, internalPort, h.timeoutMs)
            else -> httpHealth(manifest, internalPort)
        }
    }

    private fun httpHealth(manifest: ProgramManifest, internalPort: Int): Boolean {
        val h = manifest.run.health
        val url = "http://$HOST:$internalPort${h.path}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = h.timeoutMs
                readTimeout = h.timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code !in h.acceptStatus) return false
            if (h.expectContains.isEmpty()) {
                true
            } else {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { readPrefix(it, 4096) }.orEmpty()
                body.contains(h.expectContains)
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readPrefix(ins: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = ins.read(buf, read, max - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun pump(ins: InputStream, sink: ProgramLogSink, name: String): Thread =
        Thread({
            runCatching {
                InputStreamReader(ins, Charsets.UTF_8).use { r ->
                    // 不用 `BufferedReader.readLine()`：它没有长度上限，程序输出一行几百 MB
                    // 就能把 core 的堆吃满（同 uid 的程序本来也能杀 core，但这条是"顺手"的）。
                    while (true) {
                        val line = readBoundedLine(r, MAX_LOG_LINE_CHARS) ?: break
                        sink.append(line)
                    }
                }
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    /**
     * 按行读，但单行最多 [max] 个字符；超出的部分丢弃并标记 `…[截断]`。
     *
     * @return null = 流已结束且没有剩余内容
     */
    private fun readBoundedLine(r: java.io.Reader, max: Int): String? {
        val sb = StringBuilder()
        var truncated = false
        while (true) {
            val c = r.read()
            if (c < 0) {
                return if (sb.isEmpty() && !truncated) null else sb.toString() + if (truncated) "…[截断]" else ""
            }
            if (c == '\n'.code) {
                return sb.toString() + if (truncated) "…[截断]" else ""
            }
            if (sb.length < max) sb.append(c.toChar()) else truncated = true
        }
    }

    private fun joinPumps(slot: Slot) {
        val threads = slot.pumpThreads
        slot.pumpThreads = emptyList()
        threads.forEach { t -> runCatching { t.join(1_000) } }
    }

    private fun closeSinks(slot: Slot) {
        runCatching { slot.stdoutSink?.close() }
        runCatching { slot.stderrSink?.close() }
        slot.stdoutSink = null
        slot.stderrSink = null
    }

    /**
     * 为 Gateway 分配可用端口。
     *
     * 18080–18099 的 20 个端口在多实例场景下不足（每个 gateway 程序占用一个，且可能被
     * 其他进程占用），因此顺序为：清单指定的 desired → desired 之后的 20 个 → 18080–18299
     * 全段。以能否 bind 判断占用，已被监听的端口自然被排除。
     */
    private fun allocateGatewayPort(desired: Int): Int {
        if (desired > 0 && isPortFree("0.0.0.0", desired)) return desired
        if (desired > 0) {
            for (port in (desired + 1)..minOf(desired + 20, 65535)) {
                if (isPortFree("0.0.0.0", port)) return port
            }
        }
        for (port in 18080..18299) {
            if (isPortFree("0.0.0.0", port)) return port
        }
        return desired
    }

    /** 端口是否空闲（可绑定即无监听）。 */
    private fun isPortFree(host: String, port: Int): Boolean = runCatching {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(host, port))
            true
        }
    }.getOrDefault(false)

    /** 端口是否可连接（存在监听）。 */
    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 500): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    private fun waitGone(pid: Int, timeoutMs: Long): Boolean {
        if (pid <= 0) return true
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!ProcFs.isAlive(pid)) return true
            Thread.sleep(100)
        }
        return !ProcFs.isAlive(pid)
    }

    /**
     * 按 [Process] 对象等待退出。
     *
     * 优先于按 pid 等待：Android 的 `Process` 无法获取 pid 时（见 [pidOrNull]），
     * `waitGone(-1)` 会立即返回 true，但进程可能仍存活，导致误判为已停止。
     */
    private fun waitGone(p: Process, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive) return true
            Thread.sleep(100)
        }
        return !p.isAlive
    }

    companion object {
        /** 单行日志上限：程序输出畸形长行时只截断，不撑爆堆。 */
        private const val MAX_LOG_LINE_CHARS = 64 * 1024

        /**
         * 允许清单设置的环境变量。
         *
         * 只放行「描述程序自己怎么跑」的键；`LD_*` / `*_PRELOAD` / `PATH` 一律不放 ——
         * 它们能改变加载器行为（等于代码注入），而清单来自远端、无签名（R3）。
         */
        private val ALLOWED_ENV_KEYS = setOf(
            "TMPDIR", "HOME", "TZ", "LANG", "LC_ALL", "TERM",
            "GODEBUG", "GOMAXPROCS", "GOMEMLIMIT",
            "UMASK", "USER",
        )

        private const val TAG = "ProgramRuntime"

        /** 程序一律绑回环；对外由 Gateway 负责（`bind=any` 时由清单自己决定监听地址） */
        const val HOST = "127.0.0.1"

        /** 数据卷至少保留的可用空间（MB）。低于该值拒绝启动，由用户清理空间。 */
        const val MIN_FREE_DISK_MB = 512L

    }
}
