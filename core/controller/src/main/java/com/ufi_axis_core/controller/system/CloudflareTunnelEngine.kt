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
 * Cloudflare Tunnel (cloudflared) 引擎管理器 —— **多实例，仅 token 模式**
 *
 * 1. **remote-managed only**：每条隧道只存一个 token（`cloudflared/tunnels/<name>.token`），
 *    入站规则（ingress）全部在 Cloudflare Dashboard 配置，本地不需要 config.yml —— 带 `--token`
 *    运行时 cloudflared 会忽略本地 ingress。
 * 2. **多实例**：每条隧道一个独立进程，可以同时运行任意多条（cloudflared 没有单实例限制）。
 *    注意同一个 token 只应被一条实例使用，否则会变成同一隧道的多个 connector。
 * 3. 每条实例各自持有进程句柄、状态、失败原因、日志缓冲与生命周期锁。
 *
 * 二进制来源：可选组件，用户按需下载到 filesDir/components/cloudflared（见 BinaryComponentStore）。
 * 2026-09-01 起不再随 APK 内置（解压后约 36MB，曾占 core APK 压缩体积的三分之一）。
 */
class CloudflareTunnelEngine(private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "CfTunnelEngine"

        /** 启动后同步确认进程存活的等待时间（ms）：token 无效时 cloudflared 会在此期间退出 */
        private const val STARTUP_PROBE_MS = 1500L
    }

    /** 单条隧道实例的全部运行期状态（每条一把锁：停 A 不挡启动 B） */
    private class Slot(val name: String) {
        val lock = ReentrantLock()
        @Volatile var process: Process? = null
        @Volatile var status: FrpEngine.TunnelStatus = FrpEngine.TunnelStatus.Stopped
        @Volatile var stopping = false
        @Volatile var lastError: String = ""
        /** 启动时使用的 token，用于按参数精确清理残留进程；**不写日志** */
        @Volatile var tokenUsed: String = ""
        val output = StringBuilder()
        /** 运行期日志文件写入器：启动时建、停止时关掉并删文件 */
        @Volatile var logWriter: java.io.Writer? = null

        fun alive(): Boolean = process?.isAlive == true
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    @Volatile private var cachedVersion: String? = null

    private fun slot(name: String): Slot = slots.computeIfAbsent(name) { Slot(it) }

    /** 可选组件仓库（无状态，纯文件系统访问，多实例共存无副作用） */
    private val componentStore = BinaryComponentStore(appContext)

    /** cloudflared 二进制路径（可选组件目录，未安装时该文件不存在） */
    private val binaryPath: String
        get() = componentStore.pathOf(BinaryComponentStore.ID_CLOUDFLARED)

    /** cloudflared 组件是否已安装 */
    fun isInstalled(): Boolean = componentStore.isInstalled(BinaryComponentStore.ID_CLOUDFLARED)

    private val workDir: File
        get() = File(appContext.filesDir, "cloudflared").apply { if (!exists()) mkdirs() }

    /** 多隧道目录：每条隧道一个 <name>.token 文件（磁盘是隧道列表的唯一真源） */
    private val tunnelsDir: File
        get() = File(workDir, "tunnels").apply { if (!exists()) mkdirs() }

    /** 运行期日志目录：每个实例一个 <name>.log，**停止后整份删除** */
    private val logsDir: File
        get() = File(workDir, "logs").apply { if (!exists()) mkdirs() }

    // ── token 文件 CRUD ──

    /** 列出所有已保存的隧道名 */
    fun listTunnels(): List<String> = try {
        val dir = tunnelsDir
        if (!dir.exists()) emptyList()
        else dir.listFiles { f -> f.isFile && f.extension.equals("token", true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    } catch (e: Exception) {
        AppLogger.w(TAG, "Failed to list CF tunnels: ${e.message}")
        emptyList()
    }

    /** 读取某条隧道的 token（不存在返回 null） */
    fun readToken(name: String): String? {
        if (!FrpEngine.isValidName(name)) return null
        return try {
            val file = File(tunnelsDir, "${FrpEngine.sanitizeName(name)}.token")
            if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read CF token [$name]: ${e.message}")
            null
        }
    }

    /** 隧道是否已存在 */
    fun tunnelExists(name: String): Boolean = try {
        FrpEngine.isValidName(name) && File(tunnelsDir, "${FrpEngine.sanitizeName(name)}.token").exists()
    } catch (_: Exception) {
        false
    }

    /**
     * 保存（新建或更新）某条隧道的 token。
     * token 内部含空白字符必然不是合法 token（多半是把整条 `cloudflared ... --token X` 命令粘进来了），
     * 直接拒绝比让 cloudflared 秒退更好定位。写入用唯一命名的临时文件 + rename 原子提交。
     */
    fun saveTunnel(name: String, token: String): Boolean {
        if (!FrpEngine.isValidName(name)) {
            AppLogger.w(TAG, "Reject CF tunnel name [$name]: illegal characters")
            return false
        }
        val clean = token.trim()
        if (clean.isEmpty()) {
            AppLogger.w(TAG, "Reject CF tunnel [$name]: empty token")
            return false
        }
        if (clean.any { it.isWhitespace() }) {
            AppLogger.w(TAG, "Reject CF tunnel [$name]: token contains whitespace")
            return false
        }
        var tmp: File? = null
        return try {
            val target = File(tunnelsDir, "${FrpEngine.sanitizeName(name)}.token")
            val tmpFile = File(tunnelsDir, "${FrpEngine.sanitizeName(name)}.token.${System.nanoTime()}.tmp")
            tmp = tmpFile
            java.io.FileOutputStream(tmpFile).use { out ->
                out.write(clean.toByteArray())
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            // rename 覆盖失败就直接失败并**保留原 token**（先删目标再重试会毁掉可用 token）
            if (!tmpFile.renameTo(target)) {
                tmpFile.delete()
                AppLogger.e(TAG, "Failed to commit CF token [$name]; original kept")
                return false
            }
            // 注意：不要把 token 写进日志
            AppLogger.i(TAG, "CF tunnel [$name] token saved (${clean.length} chars)")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save CF tunnel [$name]: ${e.message}")
            try { tmp?.delete() } catch (_: Exception) {}
            false
        }
    }

    /** 只删磁盘上的 token 文件；槽位处置由 [stopAndDelete] 在实例锁内负责 */
    private fun deleteTokenOnDisk(name: String): Boolean {
        if (!FrpEngine.isValidName(name)) return false
        return try {
            val file = File(tunnelsDir, "${FrpEngine.sanitizeName(name)}.token")
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to delete CF tunnel [$name]: ${e.message}")
            false
        }
    }

    /** 探测 cloudflared 版本（成功与失败都缓存，避免 /status 轮询反复 fork 进程） */
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
            // cloudflared version 2024.x.x ...
            val parsed = output.lineSequence().firstOrNull()
                ?.substringAfter("version")?.trim()
                ?.takeIf { it.isNotBlank() }
            cachedVersion = parsed ?: "unknown"
            parsed
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to probe cloudflared version: ${e.message}")
            cachedVersion = "unknown"
            null
        }
    }

    /** 清掉版本缓存（组件重装/卸载后调用，否则 /status 会一直报旧版本号） */
    fun invalidateVersionCache() {
        cachedVersion = null
    }

    // ── 生命周期 ──

    /**
     * 启动某条隧道（多实例：不影响其它正在运行的隧道）。已在跑同一条 → 幂等 true。
     *
     * 返回 true 之前会等待 [STARTUP_PROBE_MS] 确认进程还活着：token 无效时 cloudflared 会秒退，
     * 存活确认之前不发布运行态，避免"接口说成功、界面几秒后自己变回未运行"。
     */
    fun start(name: String): Boolean {
        if (!FrpEngine.isValidName(name)) return false
        val target = name.trim()
        val s = slot(target)
        return s.lock.withLock {
            // 幂等判定必须在所有前置校验之前：已经在跑的实例不该因为"token 文件此刻被删了"
            // 而被写上一条与它无关的失败原因
            syncSlot(s)
            if (s.alive()) return@withLock true

            val token = readToken(target)
            if (token.isNullOrBlank()) {
                fail(s, "隧道 [$target] 不存在或 token 为空")
                // token 都不在了就别留空槽位（同 FrpEngine：/status 会多出一条清不掉的幽灵实例）
                slots.remove(target, s)
                return@withLock false
            }
            val binary = File(binaryPath)
            if (!binary.exists()) return@withLock fail(s, "cloudflared 组件未安装，请先在「组件管理」中下载")

            // 只清理**用同一个 token** 的残留进程，不会碰到别的隧道
            killLingering(token)

            try {
                val pb = ProcessBuilder(
                    binary.absolutePath, "tunnel", "--no-autoupdate", "run", "--token", token
                )
                pb.directory(workDir)
                pb.redirectErrorStream(true)
                s.stopping = false
                s.tokenUsed = token
                // 先开日志文件再起 drainer，token 无效导致的秒退原因才会完整落盘
                openLogFile(s)
                val proc = pb.start()
                AppLogger.i(TAG, "cloudflared [$target] spawned")

                val streamDone = startDrainer(s, proc, s.logWriter)

                if (proc.waitFor(STARTUP_PROBE_MS, TimeUnit.MILLISECONDS)) {
                    val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
                    try { streamDone.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                    s.status = FrpEngine.TunnelStatus.Error
                    s.lastError = "cloudflared 启动后立即退出（exit=$exit）：${tail(s)}"
                    AppLogger.w(TAG, "cloudflared [$target] ${s.lastError}")
                    // 没起来就不算"运行期"：关掉刚打开的日志文件并删掉
                    closeLogFile(s, delete = true)
                    return@withLock false
                }

                s.process = proc
                s.status = FrpEngine.TunnelStatus.Running
                s.lastError = ""
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start cloudflared [$target]: ${e.message}", e)
                s.status = FrpEngine.TunnelStatus.Error
                s.lastError = "启动 cloudflared 失败：${e.message}"
                closeLogFile(s, delete = true)
                false
            }
        }
    }

    /** 前置校验失败出口：实例仍在运行时不写 lastError，避免"运行中"挂着无关的失败原因 */
    private fun fail(s: Slot, reason: String): Boolean {
        AppLogger.e(TAG, "cloudflared [${s.name}] $reason")
        if (!s.alive()) {
            s.lastError = reason
            s.status = FrpEngine.TunnelStatus.Error
        }
        return false
    }

    /**
     * 停止某条实例。
     * @return 进程确实不在了；false = 强杀后仍存活（保留句柄并置 Error，不把进程变成谁都看不见的孤儿）
     */
    fun stop(name: String): Boolean {
        val s = slots[name.trim()] ?: return true
        return s.lock.withLock { stopLocked(s) }
    }

    /**
     * 停止全部实例；返回是否全部确认停下。
     * `slots.values` 是弱一致视图，迭代期间可能有新实例被创建，故最后复查一次 `anyRunning()`。
     */
    fun stopAll(): Boolean {
        var allDead = true
        for (s in slots.values) {
            if (!s.lock.withLock { stopLocked(s) }) allDead = false
        }
        return allDead && !anyRunning()
    }

    /**
     * 停并删某条隧道：整个"读运行态 → 停 → 删 token → 丢弃槽位"在**实例锁内**完成。
     * 锁外做这套判定会与 `start()` 的存活探测窗口竞态，把进程变成 `/status` 看不见、也停不掉的孤儿
     * （CF 更糟：槽位一丢，`tokenUsed` 也没了，连按 token 精确清理的手段都不存在）。
     */
    fun stopAndDelete(name: String): Boolean {
        if (!FrpEngine.isValidName(name)) return false
        val target = FrpEngine.sanitizeName(name)
        val s = slot(target)
        return s.lock.withLock {
            syncSlot(s)
            if (s.alive() && !stopLocked(s)) {
                AppLogger.w(TAG, "Refuse to delete CF tunnel [$target]: process still running")
                return@withLock false
            }
            // 没在跑时不会走 stopLocked，日志文件得在这里收尾
            closeLogFile(s, delete = true)
            val ok = deleteTokenOnDisk(target)
            if (ok) slots.remove(target, s)
            ok
        }
    }

    /**
     * 清理孤儿 cloudflared：后端进程被系统杀掉后子进程会被 init 收养继续跑，句柄与槽位却已丢失，
     * 界面显示"未运行"且无法停止。服务启动时按**已保存的 token** 精确清一遍。
     * 只在本进程还没有任何实例时执行。
     */
    fun reapOrphans() {
        if (slots.isNotEmpty()) return
        val names = listTunnels()
        if (names.isEmpty()) return
        AppLogger.i(TAG, "Reaping orphan cloudflared processes for ${names.size} tunnel(s)")
        names.forEach { name -> readToken(name)?.takeIf { it.isNotBlank() }?.let { killLingering(it) } }
    }

    private fun stopLocked(s: Slot): Boolean {
        val proc = s.process
        if (proc == null) {
            s.stopping = false
            if (s.status != FrpEngine.TunnelStatus.Error) s.status = FrpEngine.TunnelStatus.Stopped
            if (s.tokenUsed.isNotBlank()) killLingering(s.tokenUsed)
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
            AppLogger.w(TAG, "Error while stopping cloudflared [${s.name}]: ${e.message}")
            dead = !proc.isAlive
        }
        if (!dead) {
            AppLogger.w(TAG, "cloudflared [${s.name}] still alive after destroyForcibly, falling back to pid kill")
            if (s.tokenUsed.isNotBlank()) killLingering(s.tokenUsed)
            dead = !proc.isAlive
        }
        if (!dead) {
            // 不要丢句柄：丢了就再也没人能停它，UI 的停止键还会被禁用
            s.stopping = false
            s.status = FrpEngine.TunnelStatus.Error
            s.lastError = "cloudflared 强制停止失败，进程可能仍在运行"
            AppLogger.e(TAG, "cloudflared [${s.name}] could not be stopped; keeping handle for retry")
            return false
        }
        s.process = null
        s.status = FrpEngine.TunnelStatus.Stopped
        s.stopping = false
        s.lastError = ""
        // 约定：日志只在运行期保留，停止即连文件一起清掉
        closeLogFile(s, delete = true)
        AppLogger.i(TAG, "cloudflared [${s.name}] stopped")
        return true
    }

    private fun syncSlot(s: Slot) {
        val proc = s.process ?: return
        if (!proc.isAlive) {
            AppLogger.w(TAG, "cloudflared [${s.name}] died unexpectedly")
            s.process = null
            if (!s.stopping) {
                s.status = FrpEngine.TunnelStatus.Error
                if (s.lastError.isBlank()) s.lastError = "cloudflared 进程意外退出：${tail(s)}"
            }
        }
    }

    // ── 状态查询 ──

    fun isRunning(name: String): Boolean = slots[name.trim()]?.alive() == true

    fun anyRunning(): Boolean = slots.values.any { it.alive() }

    fun runningNames(): List<String> = slots.values.filter { it.alive() }.map { it.name }.sorted()

    fun statusOf(name: String): FrpEngine.TunnelStatus {
        val s = slots[name.trim()] ?: return FrpEngine.TunnelStatus.Stopped
        heal(s)
        return s.status
    }

    fun lastErrorOf(name: String): String = slots[name.trim()]?.lastError ?: ""

    fun outputOf(name: String): String {
        val s = slots[name.trim()] ?: return ""
        return synchronized(s.output) { s.output.toString() }
    }

    fun clearOutput(name: String) {
        val s = slots[name.trim()] ?: return
        synchronized(s.output) { s.output.clear() }
        truncateLogFile(s)
    }

    fun clearAllOutput() {
        for (s in slots.values) {
            synchronized(s.output) { s.output.clear() }
            truncateLogFile(s)
        }
    }

    /** 有运行期状态的实例快照（供 `/status`；不含日志正文） */
    fun snapshot(): List<FrpEngine.InstanceStatus> {
        for (s in slots.values) heal(s)
        return slots.values
            .map { FrpEngine.InstanceStatus(it.name, it.alive(), it.status, it.lastError) }
            .sortedBy { it.name }
    }

    private fun heal(s: Slot) {
        if (s.lock.tryLock()) {
            try { syncSlot(s) } finally { s.lock.unlock() }
        }
    }

    // ── 运行期日志文件 ──

    private fun logFile(name: String): File = File(logsDir, "${FrpEngine.sanitizeName(name)}.log")

    /** 读运行期日志文件尾部（默认最多 256 KB，避免一次响应把内存撑爆） */
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
                String(buf, 0, if (read > 0) read else 0).substringAfter('\n')
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read cloudflared log file [$name]: ${e.message}")
            ""
        }
    }

    /** 启动时新建（截断）日志文件；开不出来只是没有落盘日志，不影响启动 */
    private fun openLogFile(s: Slot) {
        closeLogFile(s, delete = false)
        s.logWriter = try {
            java.io.BufferedWriter(java.io.FileWriter(logFile(s.name), false))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cannot open log file for cloudflared [${s.name}]: ${e.message}")
            null
        }
    }

    /** 关闭日志文件；[delete] = 把整份日志一起清掉 */
    private fun closeLogFile(s: Slot, delete: Boolean) {
        try { s.logWriter?.close() } catch (_: Exception) {}
        s.logWriter = null
        if (delete) try { logFile(s.name).delete() } catch (_: Exception) {}
    }

    /** 清日志：文件删掉；仍在运行则重开一份让后续输出继续落盘 */
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
     * @return 读流结束（早于抢实例锁的状态收尾）即归零的闭锁，供启动探测等输出收齐；
     *         不能用 Thread.join —— 收尾块要抢同一把锁，而调用方此刻持锁。
     */
    private fun startDrainer(s: Slot, procRef: Process, writer: java.io.Writer?): CountDownLatch {
        val streamDone = CountDownLatch(1)
        Thread({
            try {
                val reader = procRef.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    AppLogger.d("cf-stdout", "[${s.name}] $l")
                    // 全量落盘（逐行 flush，强杀时最后几行不丢）。用本轮捕获的 writer：
                    // 旧进程 drainer 的残余行不能落进新一轮的日志文件。
                    try { writer?.let { w -> w.write(l); w.write("\n"); w.flush() } } catch (_: Exception) {}
                    synchronized(s.output) {
                        s.output.appendLine(l.take(2048))
                        val lines = s.output.lines()
                        if (lines.size > FrpEngine.MAX_OUTPUT_LINES) {
                            s.output.clear()
                            s.output.append(lines.takeLast(FrpEngine.MAX_OUTPUT_LINES).joinToString("\n"))
                        }
                    }
                }
            } catch (_: Exception) {}
            AppLogger.d(TAG, "cloudflared [${s.name}] stdout stream ended")
            streamDone.countDown()
            s.lock.withLock {
                if (!s.stopping && s.process === procRef) {
                    s.process = null
                    s.status = FrpEngine.TunnelStatus.Error
                    if (s.lastError.isBlank()) s.lastError = "cloudflared 进程退出：${tail(s)}"
                }
            }
        }, "cf-drainer-${s.name}").apply {
            isDaemon = true
            start()
        }
        return streamDone
    }

    /**
     * 清理残留 cloudflared 进程 —— **只针对用同一个 token 的那条实例**：
     * 扫 `/proc/<pid>/cmdline` 精确匹配 token 参数后 `kill -9 <pid>`；`/proc` 不可读时退回 `pkill -f`。
     * 不能按二进制路径匹配（会把其它隧道一起杀），更不能用 `killall`（会杀掉同机其它应用的 cloudflared）。
     */
    private fun killLingering(token: String) {
        if (FrpEngine.killByProcScan(token) < 0) {
            runKill(listOf("pkill", "-9", "-f", token))
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
}
