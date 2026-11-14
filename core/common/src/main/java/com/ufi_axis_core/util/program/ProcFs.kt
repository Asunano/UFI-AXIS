package com.ufi_axis_core.util.program

import com.ufi_axis_core.util.AppLogger
import java.io.File

/**
 * `/proc` 读取器：进程存活、内存、状态、父子关系、命令行匹配。
 *
 * ## 为什么全部走 `/proc` 而不是 Java API
 *
 * - `ActivityManager.getRunningAppProcesses()` 从 Android 8 起只返回自己进程；
 * - `ProcessHandle`（Java 9）在 Android 上支持不完整，且拿不到 `starttime`；
 * - `Process.pid()` 只给我们直接子进程的 pid，**孙进程一个都看不到**，而第三方程序
 *   fork 是常态（将来接入的程序更可能如此）。
 *
 * `/proc` 是唯一能同时满足"同 uid 可读""能看到整棵进程树""能拿到防 pid 复用的
 * `starttime`"的接口。代价是所有解析都得手写，且**任何一项读不到都必须退化成 null
 * 而不是抛异常** —— 这些调用都挂在监控循环上，一次 IOException 不该把看护打断。
 *
 * ## pid 复用：为什么必须同时看 starttime
 *
 * 进程退出后 pid 会被复用。若只记 pid，core 重启后按 pid 找到的很可能是**另一个进程**，
 * 于是"清理孤儿"会杀掉无辜的进程。判据必须是三元组：
 * `pid` + `starttime`（`/proc/<pid>/stat` 第 22 字段）+ `cmdline` 含我们的绝对路径。
 */
object ProcFs {

    private const val TAG = "ProcFs"

    /** `/proc/<pid>/stat` 里我们关心的几个字段 */
    data class Stat(
        val pid: Int,
        /** R/S/D/Z/T …；`D` = 不可中断睡眠（IO hang），`Z` = 僵尸 */
        val state: Char,
        val ppid: Int,
        /** 开机以来的时钟滴答数（第 22 字段），**防 pid 复用的关键** */
        val starttime: Long,
        val utimeTicks: Long,
        val stimeTicks: Long
    )

    private val USER_HZ: Long by lazy {
        // Android/Linux 上 USER_HZ 恒为 100；读不到也不影响相对计算（CPU% 用差分）
        100L
    }

    // ── 单进程读取 ──

    /**
     * 解析 `/proc/<pid>/stat`。
     *
     * **必须从最后一个 `)` 之后开始切分**：第 2 个字段 `comm` 允许包含空格和括号
     * （进程名可以叫 `foo) bar`），从前往后split 会整体错位。
     */
    fun readStat(pid: Int): Stat? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val close = text.lastIndexOf(')')
        if (close < 0 || close + 2 >= text.length) return@runCatching null
        val f = text.substring(close + 1).trim().split(' ')
        // f[0]=state(3) f[1]=ppid(4) … f[11]=utime(14) f[12]=stime(15) … f[19]=starttime(22)
        if (f.size < 20) return@runCatching null
        Stat(
            pid = pid,
            state = f[0].firstOrNull() ?: '?',
            ppid = f[1].toIntOrNull() ?: -1,
            starttime = f[19].toLongOrNull() ?: -1L,
            utimeTicks = f[11].toLongOrNull() ?: 0L,
            stimeTicks = f[12].toLongOrNull() ?: 0L
        )
    }.getOrNull()

    /** 常驻内存（KB）。读 `/proc/<pid>/status` 的 `VmRSS`。 */
    fun readRssKb(pid: Int): Long? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter("VmRSS:")?.trim()?.substringBefore(' ')?.toLongOrNull()
        }
    }.getOrNull()

    /**
     * 整棵进程树的 RSS 之和（KB）。主进程与所有子孙都读不到时返回 null。
     *
     * ## 为什么需要它（2026-11-04 实测的漏算）
     *
     * Syncthing 的 `serve` 是"监视器 + 真身"两段式：`ProcessBuilder` 拿到的是监视器，
     * 它再 fork/exec 出真正干活的进程。只读主 pid 的 `VmRSS` 看到的是**十几 MB 的壳**，
     * 真身吃多少完全不可见 —— 于是 `rss_warn/restart/kill_mb` 三档对这类程序形同虚设。
     * 同类结构还有 npm 系的 `node` 包装、部分 Java 启动器、以及任何自带守护逻辑的程序。
     *
     * ## 为什么求和而不是取最大值
     *
     * "取最大"在单子孙时够用，但多工作进程时同样漏；求和对"一壳多子"的所有形态都成立。
     * 代价是 **CoW 共享页会被重复计数**（fork 之后父子各算一份），所以这个值是**上界**。
     * 用它做 kill 判定时阈值要留余量；清单里的 `rss_*` 本来就按峰值给，方向一致。
     *
     * @return 求和结果；一个都读不到（进程已退出 / 权限不足）时返回 null，调用方应按"无数据"处理
     */
    fun readTreeRssKb(pid: Int): Long? {
        var sum = 0L
        var any = false
        for (p in listOf(pid) + descendantsOf(pid)) {
            val kb = readRssKb(p) ?: continue
            any = true
            sum += kb
        }
        return if (any) sum else null
    }

    /** 交换分区使用（KB）。低端机有 zram 时，RSS 高但一部分其实在压缩内存里。 */
    fun readSwapKb(pid: Int): Long? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmSwap:") }
                ?.substringAfter("VmSwap:")?.trim()?.substringBefore(' ')?.toLongOrNull()
        }
    }.getOrNull()

    /** 命令行（NUL 分隔 → 空格连接）。读不到（权限/已退出）返回 null。 */
    fun readCmdline(pid: Int): String? = runCatching {
        val bytes = File("/proc/$pid/cmdline").readBytes()
        if (bytes.isEmpty()) return@runCatching null
        String(bytes, Charsets.UTF_8).trimEnd('\u0000').replace('\u0000', ' ').trim()
    }.getOrNull()

    fun isAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        val st = readStat(pid) ?: return false
        return st.state != 'Z' && st.state != 'X'
    }

    /** 进程状态字符（R/S/D/Z/T）；读不到返回 null */
    fun stateOf(pid: Int): Char? = readStat(pid)?.state

    // ── 全表扫描 ──

    fun allPids(): List<Int> = runCatching {
        File("/proc").listFiles()
            ?.mapNotNull { it.name.toIntOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 直接子进程（`ppid == pid`） */
    fun childrenOf(pid: Int): List<Int> = allPids().filter { p ->
        p != pid && readStat(p)?.ppid == pid
    }

    /**
     * 整棵子树（含孙子）。BFS + 深度上限，防进程树被恶意构造得极深时死循环。
     *
     * 停止顺序必须是**先叶子后根**：先杀父进程会让子进程被 init 收养，
     * 之后 `ppid` 链就断了，再想按树找就找不到（只能靠 cmdline 匹配）。
     */
    fun descendantsOf(rootPid: Int, maxDepth: Int = 8): List<Int> {
        val out = mutableListOf<Int>()
        var frontier = listOf(rootPid)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = mutableListOf<Int>()
            for (p in frontier) {
                val kids = childrenOf(p)
                out += kids
                next += kids
            }
            frontier = next
            depth++
        }
        return out
    }

    /**
     * 按 cmdline 匹配进程（用于回收"core 重启后遗留的孤儿"）。
     *
     * @param needle 绝对路径片段（我们的 `bin/<entry>` 绝对路径）
     * @param expectedStartTime 非空时要求 starttime 一致（防 pid 复用）
     */
    fun findByCmdline(needle: String, expectedStartTime: Long? = null): List<Stat> {
        if (needle.isBlank()) return emptyList()
        val out = mutableListOf<Stat>()
        for (pid in allPids()) {
            val st = readStat(pid) ?: continue
            if (st.state == 'Z') continue
            val cmd = readCmdline(pid) ?: continue
            if (!cmd.contains(needle)) continue
            if (expectedStartTime != null && st.starttime != expectedStartTime) continue
            out += st
        }
        return out
    }

    /** CPU 使用率（0..1，按单核归一）。需要两次采样做差分，调用方负责保存上一次的 ticks。 */
    fun cpuRatio(stat: Stat, prevTicks: Long, elapsedMs: Long): Double {
        if (elapsedMs <= 0) return 0.0
        val ticks = stat.utimeTicks + stat.stimeTicks
        val delta = (ticks - prevTicks).coerceAtLeast(0L)
        val cpuMs = delta * 1000.0 / USER_HZ
        return (cpuMs / elapsedMs).coerceIn(0.0, 4.0)
    }

    fun totalTicks(stat: Stat): Long = stat.utimeTicks + stat.stimeTicks

    /** 系统可用内存（MB），来自 `/proc/meminfo` 的 `MemAvailable`。读不到返回 null。 */
    fun availableMemMb(): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.substringAfter("MemAvailable:")?.trim()?.substringBefore(' ')?.toLongOrNull()
                ?.let { it / 1024 }
        }
    }.getOrNull()

    /**
     * 把信号发给整棵进程树。
     *
     * **顺序是先叶子后根**：先杀父进程会让子进程被 init 收养，`ppid` 链立刻断掉，
     * 之后再想按树清理就只能靠 cmdline 匹配（而且可能已经匹配不到了）。
     *
     * @param selfPid 调用方自己的 pid（= core）。**必须排除**：`descendantsOf` 在 pid 异常
     *   （-1 / 0）时可能把无关进程算进来，误杀 core 就等于自杀。
     *   单测里传 -1 可跳过 `android.os.Process` 调用。
     */
    fun signalTree(rootPid: Int, selfPid: Int = -1, signal: Int = 15): Int {
        if (rootPid <= 0) return 0
        val self = if (selfPid > 0) selfPid else runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        if (rootPid == self) return 0

        // descendantsOf 是 BFS（父在前）；reversed 后叶子在前，最后才是根
        val targets = descendantsOf(rootPid).reversed() + rootPid
        var sent = 0
        for (p in targets) {
            if (p == self) continue
            if (runCatching { android.os.Process.sendSignal(p, signal) }.isSuccess) sent++
        }
        return sent
    }

    /** 诊断用：把一棵进程树描述成一行（日志里看清是谁没退） */
    fun describeTree(rootPid: Int): String = runCatching {
        val kids = descendantsOf(rootPid)
        "root=$rootPid children=${kids.joinToString(",")}"
    }.getOrDefault("root=$rootPid (读取失败)")

    fun logTree(tag: String, rootPid: Int) {
        AppLogger.w(tag, "进程树 ${describeTree(rootPid)}")
    }
}
