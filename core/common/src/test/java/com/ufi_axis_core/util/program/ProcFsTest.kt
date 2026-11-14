package com.ufi_axis_core.util.program

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcFs.readTreeRssKb] 的单测。
 *
 * 为什么值得测：这个函数是**唯一能让"两段式启动"的程序被内存阈值看见**的地方。
 * 写错方向的代价不是报错，而是"监控看着 15MB、真身已经 400MB"，最后表现为
 * 设备整体卡死或者被 LMK 杀掉 —— 现场只会看到"Core 自己没了"。
 *
 * ## 为什么不用"当前 JVM"当被测对象
 *
 * 第一版拿 `ProcessHandle.current()` 比 `single` 与 `tree`，结果间歇性失败：
 * JVM 在两次读取之间做了一次 GC，RSS 直接掉几十 MB，"树求和"当然小于"自身"。
 * 那种断言测的是 GC，不是这里要证的语义。所以改成拿一个**自己拉起的、RSS 稳定的**
 * 子进程当被测目标（`sleep` 常驻期间内存几乎不动）。
 */
class ProcFsTest {

    /** 拉起一个 `sleep` 并等它出现在本进程的进程树里，返回它的 pid。 */
    private fun spawnSleep(seconds: Int = 10): Pair<Process, Int> {
        val proc = ProcessBuilder("sleep", seconds.toString()).start()
        val self = ProcessHandle.current().pid().toInt()
        // 不用 Process.pid()：Android 的 java.lang.Process 没有这个方法（单测跑在 android.jar 上）。
        // 走仓库自己的 /proc 读取器，顺便把"按 cmdline 认子进程"这条路径也走一遍。
        for (i in 0 until 60) {
            Thread.sleep(50)
            val pid = ProcFs.descendantsOf(self)
                .firstOrNull { ProcFs.readCmdline(it).orEmpty().contains("sleep") }
            if (pid != null && (ProcFs.readRssKb(pid) ?: 0L) > 0L) return proc to pid
        }
        proc.destroyForcibly()
        error("子进程没有出现在进程树里")
    }

    @Test
    fun `进程树 RSS 不小于该进程自身`() {
        val (proc, pid) = spawnSleep()
        try {
            val single = ProcFs.readRssKb(pid)
            val tree = ProcFs.readTreeRssKb(pid)

            assertNotNull("子进程的 VmRSS 必须可读", single)
            assertNotNull("至少能读到主进程，树求和不该是 null", tree)
            assertTrue(
                "树求和必须 >= 单进程（single=$single tree=$tree）",
                tree!! >= single!!
            )
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `子进程被枚举进求和范围`() {
        val (proc, childPid) = spawnSleep()
        try {
            val childRss = ProcFs.readRssKb(childPid) ?: 0L
            assertTrue("子进程自身的 RSS 必须可读（child=$childRss）", childRss > 0)

            // 父级（本 JVM）的树求和必须覆盖到它。比较的是"子进程自己的量"，
            // 不受 JVM 自己 GC 抖动的影响。
            val self = ProcessHandle.current().pid().toInt()
            val tree = ProcFs.readTreeRssKb(self)
            assertNotNull(tree)
            assertTrue("树求和至少要覆盖子进程", tree!! >= childRss)
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `读不到任何进程时返回 null 而不是 0`() {
        // 0 会被上层当成"就是用了 0MB"，从而永远不触发阈值；null 才是"没有数据"
        assertNull(ProcFs.readTreeRssKb(-1))
    }
}
