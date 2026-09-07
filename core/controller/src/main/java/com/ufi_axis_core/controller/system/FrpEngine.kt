package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.BinaryComponentStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * frpc (FRP client) 引擎管理器 —— **多实例**
 *
 * 每个通道（`configs/<name>.toml`）对应一个独立的 frpc 进程，可以同时运行任意多条：
 * frpc 本身没有单实例限制，只需保证不同配置之间的 proxy 名 / remotePort 不撞（那是 frps 端的约束）。
 *
 * 每条实例各自持有：进程句柄、状态、失败原因、输出缓冲、生命周期锁。
 * 因此"启动 B"不会影响"正在跑的 A"，一条实例卡在停不掉也不会阻塞另一条的启停。
 *
 * 二进制来源：可选组件，用户按需下载到 filesDir/components/frpc（见 BinaryComponentStore）。
 * 2026-09-01 起不再随 APK 内置（解压后约 14MB，只有用内网穿透的用户才需要）。
 */
class FrpEngine(private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "FrpEngine"

        /** 启动后同步确认进程存活的等待时间（ms）：配置有误时 frpc 会在此期间退出 */
        private const val STARTUP_PROBE_MS = 1500L

        /** 单条实例输出缓冲保留的最大行数（进程内存，非文件） */
        internal const val MAX_OUTPUT_LINES = 200

        /**
         * 扫描 /proc 杀掉 cmdline **某个参数精确等于** [match] 的进程。
         *
         * [match] 必须是能唯一标识目标实例的绝对路径 / token（FRP 用配置文件路径，CF 用 token），
         * 不能用二进制路径 —— 多实例下那会把所有兄弟进程一起杀掉。
         *
         * @return 杀掉的进程数；-1 表示 /proc 不可读，调用方需要退回 pkill
         */
        internal fun killByProcScan(match: String): Int {
            val pids = try {
                File("/proc").list()?.filter { it.all(Char::isDigit) } ?: return -1
            } catch (_: Exception) {
                return -1
            }
            if (pids.isEmpty()) return -1
            var killed = 0
            var readable = false
            for (pid in pids) {
                val args = try {
                    val f = File("/proc/$pid/cmdline")
                    if (!f.canRead()) continue
                    readable = true
                    // cmdline 以 \0 分隔参数：按参数逐个精确比较，不用 contains ——
                    // 后者会把"命令行里恰好提到这个路径"的无关进程也当成目标
                    f.readBytes().toString(Charsets.UTF_8).split('\u0000')
                } catch (_: Exception) {
                    continue
                }
                if (args.any { it == match }) {
                    try {
                        val p = ProcessBuilder(listOf("kill", "-9", pid)).redirectErrorStream(true).start()
                        p.waitFor(1, TimeUnit.SECONDS)
                        p.destroyForcibly()
                        killed++
                    } catch (_: Exception) {}
                }
            }
            return if (readable) killed else -1
        }

        /**
         * 通道名安全化：只替换文件名非法字符与路径分隔符，保留中文等可读字符。
         * 保存、读取、删除、启动必须统一走这里，否则会出现"保存成功但读不到"（保存时改名、读取时没改）
         * 以及路径穿越写到 configs 目录之外的问题。
         *
         * 逗号一并按非法字符处理：名字要进 `AppSettings` 的"期望在跑"CSV 集合，
         * 含逗号会被解析成两个不存在的名字，看护随即把真名自愈掉 —— 表现为"自动重连开着但从不生效"。
         */
        fun sanitizeName(name: String): String = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|,\\x00-\\x1F]"), "_")
            .replace("..", "_")
            .take(64)

        /**
         * 通道名是否可直接作为文件名使用（非空且 sanitize 不会改动它）。
         * 用于在写入前拒绝会被静默改名的名字 —— 否则 `a:b` 与 `a?b` 都落到 `a_b.toml`，
         * 后建的会无声覆盖先建的。
         */
        fun isValidName(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.isNotBlank() && sanitizeName(trimmed) == trimmed
        }
    }

    /**
     * 单条通道实例的全部运行期状态。
     *
     * [process] 必须 @Volatile：写在 [lock] 内，但 `isRunning` 会被路由线程在锁外读。
     * [lock] 是**每条实例一把**：停 A（最长 5 秒）不会挡住启动 B。
     */
    private class Slot(val name: String) {
        val lock = ReentrantLock()
        @Volatile var process: Process? = null
        @Volatile var status: TunnelStatus = TunnelStatus.Stopped
        @Volatile var stopping = false
        /** 最近一次失败原因（启动失败或强制停止失败）；成功启动/成功停止会清空 */
        @Volatile var lastError: String = ""
        val output = StringBuilder()
        /** 运行期日志文件写入器：启动时建、停止时关掉并删文件；非空即代表"这份日志正在落盘" */
        @Volatile var logWriter: java.io.Writer? = null

        fun alive(): Boolean = process?.isAlive == true
    }

    /** name → 实例槽位。进程退出后槽位仍保留，这样日志与失败原因不会随进程消失。 */
    private val slots = ConcurrentHashMap<String, Slot>()

    /** frpc --version 探测结果缓存（二进制在运行期不会变） */
    @Volatile private var cachedVersion: String? = null

    private fun slot(name: String): Slot = slots.computeIfAbsent(name) { Slot(it) }

    /** 可选组件仓库（无状态，纯文件系统访问，多实例共存无副作用） */
    private val componentStore = BinaryComponentStore(appContext)

    /** frpc 二进制路径（可选组件目录，未安装时该文件不存在） */
    private val binaryPath: String
        get() = componentStore.pathOf(BinaryComponentStore.ID_FRPC)

    /** frpc 组件是否已安装 */
    fun isInstalled(): Boolean = componentStore.isInstalled(BinaryComponentStore.ID_FRPC)

    /** frpc 工作目录 */
    private val workDir: File
        get() = File(appContext.filesDir, "frp").apply { if (!exists()) mkdirs() }

    /** 多通道配置目录：每个通道一个 <name>.toml 文件 */
    private val configsDir: File
        get() = File(workDir, "configs").apply { if (!exists()) mkdirs() }

    /** 运行期日志目录：每个实例一个 <name>.log，**停止后整份删除** */
    private val logsDir: File
        get() = File(workDir, "logs").apply { if (!exists()) mkdirs() }

    // ── 配置文件 CRUD（磁盘是通道列表的唯一真源）──

    /** 列出所有已保存的通道配置文件名（不含扩展名） */
    fun listConfigFiles(): List<String> = try {
        val dir = configsDir
        if (!dir.exists()) emptyList()
        else dir.listFiles { f -> f.isFile && f.extension.equals("toml", true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    } catch (e: Exception) {
        // 目录被外部删掉 / 权限异常时不要把 IO 异常抛到路由层变 500
        AppLogger.w(TAG, "Failed to list FRP configs: ${e.message}")
        emptyList()
    }

    /** 读取某个通道的 TOML 文本（不存在或读失败返回 null） */
    fun readConfigFile(name: String): String? {
        if (!isValidName(name)) return null
        return try {
            val file = File(configsDir, "${sanitizeName(name)}.toml")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read FRP config [$name]: ${e.message}")
            null
        }
    }

    /**
     * 保存某个通道的 TOML 配置文本。
     * 先写唯一命名的临时文件再 rename 提交：直接 writeText 会先截断再写，写一半掉电/被杀就留下残缺配置；
     * 临时名带纳秒后缀，保证并发保存同一通道不会互相写同一个 tmp 而提交出交错内容。
     */
    fun saveConfigFile(name: String, tomlText: String): Boolean {
        if (!isValidName(name)) {
            AppLogger.w(TAG, "Reject FRP config name [$name]: illegal characters")
            return false
        }
        var tmp: File? = null
        return try {
            val target = File(configsDir, "${sanitizeName(name)}.toml")
            val tmpFile = File(configsDir, "${sanitizeName(name)}.toml.${System.nanoTime()}.tmp")
            tmp = tmpFile
            java.io.FileOutputStream(tmpFile).use { out ->
                out.write(tomlText.toByteArray())
                out.flush()
                // 只 rename 不 sync：元数据可能先于数据落盘，掉电后剩一个长度对但内容为空洞的文件
                try { out.fd.sync() } catch (_: Exception) {}
            }
            // 同目录同文件系统的 rename 覆盖是原子的；失败就直接失败并**保留原配置**
            if (!tmpFile.renameTo(target)) {
                tmpFile.delete()
                AppLogger.e(TAG, "Failed to commit FRP config [$name]; original kept")
                return false
            }
            AppLogger.i(TAG, "FRP config file saved: ${target.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save FRP config file: ${e.message}")
            try { tmp?.delete() } catch (_: Exception) {}
            false
        }
    }

    /** 只删磁盘文件；槽位的处置由 [stopAndDelete] 在实例锁内负责 */
    private fun deleteConfigFileOnDisk(name: String): Boolean {
        if (!isValidName(name)) return false
        return try {
            val file = File(configsDir, "${sanitizeName(name)}.toml")
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to delete FRP config [$name]: ${e.message}")
            false
        }
    }

    /** 获取某个通道配置文件绝对路径（供 -c 启动使用） */
    fun configFilePath(name: String): String =
        File(configsDir, "${sanitizeName(name)}.toml").absolutePath

    /**
     * 探测 frpc 版本。
     * 结果进程内缓存：`/api/tunnel/status` 会被 UI 每 2~5 秒轮询，不缓存就等于每次都 fork 一个进程
     * 并在 Ktor 请求线程上最长 waitFor 5 秒。
     */
    fun probeVersion(): String? {
        cachedVersion?.let { return it }
        val binary = File(binaryPath)
        if (!binary.exists()) return null
        return try {
            val pb = ProcessBuilder(binary.absolutePath, "--version")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.destroyForcibly()
            val parsed = output.trim().takeIf { it.isNotBlank() }
            // 解析不出来也缓存成 unknown：否则每次 /status 都会再 fork 一次
            cachedVersion = parsed ?: "unknown"
            parsed
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to probe frpc version: ${e.message}")
            cachedVersion = "unknown"
            null
        }
    }

    /**
     * 清掉版本缓存。组件被重新安装（版本可能变了）或卸载后必须调用，
     * 否则 `/api/tunnel/status` 会一直报旧版本号。
     */
    fun invalidateVersionCache() {
        cachedVersion = null
    }

    // ── 生命周期 ──
    /**
     * 启动某个通道（多实例：不会影响其它正在运行的通道）。
     *
     * 整条实例的启停持它自己的锁：连点"启动"会串行执行，不会 fork 出两个同名 frpc
     * 把前一个变成停不掉的孤儿进程。已在运行 → 幂等返回 true。
     *
     * 返回 true 之前会等待 [STARTUP_PROBE_MS] 确认进程还活着；确认之前**不发布运行态**，
     * 避免这段时间里 `/status` 先宣布 Running、UI 亮"运行中"随后又跳回失败。
     */
    fun start(name: String): Boolean {
        if (!isValidName(name)) return false
        val target = name.trim()
        val s = slot(target)
        return s.lock.withLock {
            // 幂等判定必须在**所有前置校验之前**：已经在跑的实例不该因为"配置文件此刻被外部删了"
            // 而被写上一条与它无关的 lastError（UI 会显示"运行中"却挂着"配置文件不存在"）
            syncSlot(s)
            if (s.alive()) return@withLock true   // 已在跑同一条：幂等

            val configPath = configFilePath(target)
            // 前置校验失败只写本实例的 lastError，不影响别的实例
            val binary = File(binaryPath)
            if (!binary.exists()) return@withLock fail(s, "frpc 组件未安装，请先在「组件管理」中下载")
            val cfgFile = File(configPath)
            if (!cfgFile.exists()) {
                fail(s, "配置文件不存在：$configPath")
                // 配置都不在了就别留个空槽位：否则 /status 会长期多出一条名字已不存在的 Error 实例，
                // 而且没有任何路径能把它清掉（停止/删除都会先按名字找配置）
                slots.remove(target, s)
                return@withLock false
            }
            val toml = try { cfgFile.readText() } catch (_: Exception) { "" }
            if (toml.isBlank()) return@withLock fail(s, "配置文件为空")
            // frpc 没有 serverAddr（或值为空）会秒退，提前给出人话原因
            val serverAddr = toml.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("serverAddr") || it.startsWith("server_addr") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'')
            if (serverAddr.isNullOrBlank()) {
                return@withLock fail(s, "配置缺少 serverAddr（或为空），frpc 无法连接服务端")
            }

            // 清掉可能残留的同配置进程（按**配置文件路径**精确匹配，不会碰到别的通道）
            killLingering(cfgFile.absolutePath)

            try {
                val pb = ProcessBuilder(binary.absolutePath, "-c", cfgFile.absolutePath)
                pb.directory(workDir)
                pb.redirectErrorStream(true)
                s.stopping = false
                // 先开日志文件再起 drainer：运行期全量日志落盘，启动阶段的报错也不会漏
                openLogFile(s)
                val proc = pb.start()
                AppLogger.i(TAG, "frpc [$target] spawned with ${cfgFile.absolutePath}")

                // 先排空 stdout（管道满了 frpc 会被阻塞）；此时 s.process 仍是旧值/null，
                // drainer 收尾的 `s.process === proc` 守卫会让它不去污染状态
                val streamDone = startDrainer(s, proc, s.logWriter)

                if (proc.waitFor(STARTUP_PROBE_MS, TimeUnit.MILLISECONDS)) {
                    val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
                    // 等 drainer 收完已产生的输出再取尾巴，否则秒退时缓冲往往还是空的
                    try { streamDone.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                    s.status = TunnelStatus.Error
                    s.lastError = "frpc 启动后立即退出（exit=$exit）：${tail(s)}"
                    AppLogger.w(TAG, "frpc [$target] ${s.lastError}")
                    // 没起来就不算"运行期"：关掉刚打开的日志文件并删掉，失败原因已经在 lastError 里
                    closeLogFile(s, delete = true)
                    return@withLock false
                }

                s.process = proc
                s.status = TunnelStatus.Running
                s.lastError = ""
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start frpc [$target]: ${e.message}", e)
                s.status = TunnelStatus.Error
                s.lastError = "启动 frpc 失败：${e.message}"
                closeLogFile(s, delete = true)
                false
            }
        }
    }

    /**
     * 前置校验失败出口：只记录本实例的原因，不改写它的运行态。
     * 实例仍在运行时**不写 lastError** —— 否则"运行中"的实例会挂着一条与它无关的失败原因。
     */
    private fun fail(s: Slot, reason: String): Boolean {
        AppLogger.e(TAG, "frpc [${s.name}] $reason")
        if (!s.alive()) {
            s.lastError = reason
            s.status = TunnelStatus.Error
        }
        return false
    }

    /**
     * 停止某条实例。
     * @return 进程确实不在了；false = 强杀后仍存活（此时**保留句柄**并置为 Error，
     *         以便后续 stop / 状态自愈还能继续处理，不会变成谁都看不见的孤儿进程）
     */
    fun stop(name: String): Boolean {
        val s = slots[name.trim()] ?: return true   // 没有这条实例 = 本来就没跑
        return s.lock.withLock { stopLocked(s) }
    }

    /**
     * 停止全部实例；返回是否全部确认停下。
     * `slots.values` 是弱一致视图：迭代期间可能有新实例被创建（另一个客户端并发点了启动），
     * 所以最后再复查一次 `anyRunning()`，避免"接口说全停了、其实有一条刚起来"。
     */
    fun stopAll(): Boolean {
        var allDead = true
        for (s in slots.values) {
            if (!s.lock.withLock { stopLocked(s) }) allDead = false
        }
        return allDead && !anyRunning()
    }

    /**
     * 停并删某条实例：整个"读运行态 → 停 → 删配置 → 丢弃槽位"复合操作在**实例锁内**完成。
     *
     * 必须如此：`start()` 要到存活探测通过之后才把句柄写进槽位，如果删除路径在锁外判断
     * "有没有在跑"，就会在那段窗口里认为它没跑 → 删掉配置并移除槽位 → `start()` 随后把句柄
     * 写进一个已经脱离 `slots` 的孤儿槽位，此后 `/status` 看不到它、`stop` 也停不了它。
     *
     * @return 是否真的删掉了（正在运行且停不掉、或磁盘删除失败 → false，配置**保留**）
     */
    fun stopAndDelete(name: String): Boolean {
        if (!isValidName(name)) return false
        val target = sanitizeName(name)
        val s = slot(target)
        return s.lock.withLock {
            syncSlot(s)
            if (s.alive() && !stopLocked(s)) {
                AppLogger.w(TAG, "Refuse to delete FRP config [$target]: process still running")
                return@withLock false
            }
            // 没在跑时不会走 stopLocked，日志文件得在这里收尾（启动失败过的实例可能还挂着 writer）
            closeLogFile(s, delete = true)
            val ok = deleteConfigFileOnDisk(target)
            if (ok) slots.remove(target, s)
            ok
        }
    }

    /**
     * 清理孤儿 frpc：后端进程被系统杀掉（LMK / 强停服务）后，frpc 子进程会被 init 收养继续运行，
     * 但句柄与槽位随进程一起消失 —— `/status` 显示"未运行"、停止键也不会出现，隧道却在用户
     * 不知情的情况下持续对外暴露。
     *
     * 因此服务启动时按"我们自己的配置文件路径"精确清一遍：宁可停掉，也不留下谁都管不了的进程。
     * 只在**本进程还没有任何实例**时执行，避免误伤正常运行中的实例。
     */
    fun reapOrphans() {
        if (slots.isNotEmpty()) return
        val names = listConfigFiles()
        if (names.isEmpty()) return
        AppLogger.i(TAG, "Reaping orphan frpc processes for ${names.size} config(s)")
        names.forEach { killLingering(configFilePath(it)) }
    }

    private fun stopLocked(s: Slot): Boolean {
        val proc = s.process
        if (proc == null) {
            s.stopping = false
            if (s.status != TunnelStatus.Error) s.status = TunnelStatus.Stopped
            // 兜底清一次可能存在的残留进程（句柄早就丢了但进程还活着的情况）
            killLingering(configFilePath(s.name))
            closeLogFile(s, delete = true)
            return true
        }
        s.stopping = true
        var dead = true
        try {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                dead = proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error while stopping frpc [${s.name}]: ${e.message}")
            dead = !proc.isAlive
        }
        if (!dead) {
            // 句柄杀不掉：退回按配置路径精确 kill，避免"接口说停了、进程还在跑"
            AppLogger.w(TAG, "frpc [${s.name}] still alive after destroyForcibly, falling back to pid kill")
            killLingering(configFilePath(s.name))
            dead = !proc.isAlive
        }
        if (!dead) {
            // 关键：**不要**把句柄丢掉。丢了就再也没人能停它，UI 的停止键还会被禁用。
            s.stopping = false
            s.status = TunnelStatus.Error
            s.lastError = "frpc 强制停止失败，进程可能仍在运行"
            AppLogger.e(TAG, "frpc [${s.name}] could not be stopped; keeping handle for retry")
            return false
        }
        s.process = null
        s.status = TunnelStatus.Stopped
        s.stopping = false
        // 停成功后清掉遗留失败原因，避免旧横幅一直挂在详情页
        s.lastError = ""
        // 约定：日志只在运行期保留，停止即连文件一起清掉
        closeLogFile(s, delete = true)
        AppLogger.i(TAG, "frpc [${s.name}] stopped")
        return true
    }

    /**
     * 状态自愈：进程已死但状态还没被 drainer 更新时纠正为"未运行"。
     * 调用方必须已持有该实例的锁。
     */
    private fun syncSlot(s: Slot) {
        val proc = s.process ?: return
        if (!proc.isAlive) {
            AppLogger.w(TAG, "frpc [${s.name}] died unexpectedly")
            s.process = null
            if (!s.stopping) {
                s.status = TunnelStatus.Error
                if (s.lastError.isBlank()) s.lastError = "frpc 进程意外退出：${tail(s)}"
            }
        }
    }

    // ── 状态查询 ──

    /** 某条实例是否在运行 */
    fun isRunning(name: String): Boolean = slots[name.trim()]?.alive() == true

    /** 是否有任意实例在运行 */
    fun anyRunning(): Boolean = slots.values.any { it.alive() }

    /** 正在运行的通道名（按名字排序） */
    fun runningNames(): List<String> = slots.values.filter { it.alive() }.map { it.name }.sorted()

    /** 某条实例的状态（带尽力自愈；tryLock 不阻塞状态查询） */
    fun statusOf(name: String): TunnelStatus {
        val s = slots[name.trim()] ?: return TunnelStatus.Stopped
        heal(s)
        return s.status
    }

    /** 某条实例最近一次失败原因（空=无） */
    fun lastErrorOf(name: String): String = slots[name.trim()]?.lastError ?: ""

    /** 某条实例的输出日志 */
    fun outputOf(name: String): String {
        val s = slots[name.trim()] ?: return ""
        return synchronized(s.output) { s.output.toString() }
    }

    /** 清空某条实例的日志缓冲（日志在设备端进程内存里，客户端删不掉，只能由后端清） */
    fun clearOutput(name: String) {
        val s = slots[name.trim()] ?: return
        synchronized(s.output) { s.output.clear() }
        truncateLogFile(s)
    }

    /** 清空全部实例的日志缓冲（含运行期日志文件） */
    fun clearAllOutput() {
        for (s in slots.values) {
            synchronized(s.output) { s.output.clear() }
            truncateLogFile(s)
        }
    }

    /** 有运行期状态的实例快照（供 `/status`；不含日志正文，日志走单独端点避免每轮传几百行） */
    fun snapshot(): List<InstanceStatus> {
        for (s in slots.values) heal(s)
        return slots.values
            .map { InstanceStatus(it.name, it.alive(), it.status, it.lastError) }
            .sortedBy { it.name }
    }

    private fun heal(s: Slot) {
        // tryLock：启动/停止正在进行时直接读当前值，不把状态查询堵在几秒的启动流程后面
        if (s.lock.tryLock()) {
            try { syncSlot(s) } finally { s.lock.unlock() }
        }
    }

    // ── 运行期日志文件 ──

    /** 某条实例的运行期日志文件（只在运行期存在） */
    private fun logFile(name: String): File = File(logsDir, "${sanitizeName(name)}.log")

    /**
     * 读运行期日志文件尾部（默认最多 256 KB）。
     * 内存缓冲只留最近 [MAX_OUTPUT_LINES] 行，想看完整启动过程必须读文件；
     * 但整份可能很大，必须限长 —— 否则一次 `/log` 请求就能把响应体和内存撑爆。
     */
    fun readLogTail(name: String, maxBytes: Int = 256 * 1024): String {
        val f = logFile(name)
        return try {
            if (!f.exists()) return ""
            val len = f.length()
            if (len <= maxBytes) f.readText()
            else java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - maxBytes)
                val buf = ByteArray(maxBytes)
                val read = raf.read(buf)
                // 掉头那行必然被切半，直接丢掉
                String(buf, 0, if (read > 0) read else 0).substringAfter('\n')
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read frpc log file [$name]: ${e.message}")
            ""
        }
    }

    /** 启动时新建（截断）日志文件；开不出来只是没有落盘日志，不该影响启动 */
    private fun openLogFile(s: Slot) {
        closeLogFile(s, delete = false)
        s.logWriter = try {
            java.io.BufferedWriter(java.io.FileWriter(logFile(s.name), false))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cannot open log file for frpc [${s.name}]: ${e.message}")
            null
        }
    }

    /** 关闭日志文件；[delete] = 按约定把整份日志一起清掉 */
    private fun closeLogFile(s: Slot, delete: Boolean) {
        try { s.logWriter?.close() } catch (_: Exception) {}
        s.logWriter = null
        if (delete) try { logFile(s.name).delete() } catch (_: Exception) {}
    }

    /** 清日志：文件删掉；若实例仍在运行则重新开一份，保证后续输出继续落盘 */
    private fun truncateLogFile(s: Slot) {
        val wasWriting = s.logWriter != null
        closeLogFile(s, delete = true)
        if (wasWriting && s.alive()) openLogFile(s)
    }

    // ── 内部 ──

    private fun tail(s: Slot, count: Int = 3): String =
        synchronized(s.output) { s.output.toString() }
            .trim().lines().filter { it.isNotBlank() }
            .takeLast(count).joinToString(" | ").take(300)
            .ifBlank { "无输出" }

    /**
     * 起 stdout 排空线程。
     * @return 一个在**读流结束时**（早于抢实例锁的状态收尾）计数归零的闭锁，
     *         供启动探测在秒退时等待输出收齐 —— 不能用 Thread.join：收尾块要抢同一把锁，
     *         而调用方此刻正持有它，join 只会白等到超时。
     */
    private fun startDrainer(s: Slot, procRef: Process, writer: java.io.Writer?): CountDownLatch {
        val streamDone = CountDownLatch(1)
        Thread({
            try {
                val reader = procRef.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    AppLogger.d("frpc-stdout", "[${s.name}] $l")
                    // 全量落盘（逐行 flush：进程被强杀时最后几行才不会留在缓冲里丢掉）。
                    // 用本轮捕获的 writer，不能每行去读 s.logWriter：旧进程的 drainer 收尾时
                    // 新一轮可能已经换过 writer，那些残余行会落进新实例的日志里。
                    try { writer?.let { w -> w.write(l); w.write("\n"); w.flush() } } catch (_: Exception) {}
                    synchronized(s.output) {
                        s.output.appendLine(l.take(2048))
                        val lines = s.output.lines()
                        if (lines.size > MAX_OUTPUT_LINES) {
                            s.output.clear()
                            s.output.append(lines.takeLast(MAX_OUTPUT_LINES).joinToString("\n"))
                        }
                    }
                }
            } catch (_: Exception) {}
            AppLogger.d(TAG, "frpc [${s.name}] stdout stream ended")
            streamDone.countDown()
            // 主动 stop() 或已被新进程接管时不要覆盖状态（否则正常停止/重启会误报 Error）
            s.lock.withLock {
                if (!s.stopping && s.process === procRef) {
                    s.process = null
                    s.status = TunnelStatus.Error
                    if (s.lastError.isBlank()) s.lastError = "frpc 进程退出：${tail(s)}"
                }
            }
        }, "frpc-drainer-${s.name}").apply {
            isDaemon = true
            start()
        }
        return streamDone
    }

    /**
     * 清理残留 frpc 进程 —— **只针对 [match] 这一条实例**。
     *
     * 先扫 `/proc/<pid>/cmdline` 精确匹配参数（配置文件绝对路径）再 `kill -9 <pid>`，
     * 这样既不依赖 toybox 是否带 `pkill -f`，也不会杀掉本引擎其它通道或同机其它应用的 frpc。
     * `/proc` 读不到（hidepid）时才退回 `pkill -f <配置路径>`（仍是精确匹配）。
     */
    private fun killLingering(match: String) {
        if (killByProcScan(match) < 0) {
            runKill(listOf("pkill", "-9", "-f", match))
        }
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
    }

    private fun runKill(cmd: List<String>): Boolean = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val exited = p.waitFor(2, TimeUnit.SECONDS)
        val code = if (exited) p.exitValue() else -1
        p.destroyForcibly()
        exited && code == 0
    } catch (_: Exception) {
        false
    }

    /** 单条实例的对外状态快照 */
    data class InstanceStatus(
        val name: String,
        val running: Boolean,
        val status: TunnelStatus,
        val lastError: String
    )

    /** 隧道状态 */
    enum class TunnelStatus {
        Stopped, Running, Error
    }
}
