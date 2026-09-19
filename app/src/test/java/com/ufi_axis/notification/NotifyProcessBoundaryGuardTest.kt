package com.ufi_axis.notification

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「状态栏通知只在 :ufi_notify 发射 + 保活时主进程不被 Worker 周期拉起」源码护栏。
 *
 * 被守护的不变量：
 * 1. `NotificationCenter.notify` / `sendTestNotification` 在非通知进程必须转交，不得本地 `NotificationManagerCompat.notify`；
 * 2. `GuardScheduler.syncSchedule` 在前台保活开启时必须 `cancelUniqueWork`，禁止 enqueue 主进程 Worker。
 */
class NotifyProcessBoundaryGuardTest {

    private val centerPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotificationCenter.kt"
    private val guardSchedulerPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/GuardScheduler.kt"
    private val dispatchReceiverPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotifyDispatchReceiver.kt"

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'")
    }

    private fun functionBody(code: String, name: String): String {
        val sig = Regex("""fun\s+$name\s*\(""").find(code)
            ?: throw AssertionError("找不到函数 $name")
        var i = sig.range.last
        while (i < code.length && code[i] != '{') i++
        if (i >= code.length) throw AssertionError("$name 没有函数体")
        var depth = 0
        val start = i
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("$name 函数体不完整")
    }

    @Test
    fun notify_mustDispatchWhenNotInNotifyProcess() {
        val body = functionBody(source(centerPath), "notify")
        assertTrue(
            "notify() 必须在非 :ufi_notify 进程转交（isNotifyProcess / PROCESS_NOTIFY）：" +
                "状态栏通知唯一发射进程，主进程本地 notify 会导致双发与游标分叉。\\n实际代码片段应含 isNotifyProcess。",
            body.contains("isNotifyProcess()") && body.contains("dispatchScene")
        )
    }

    @Test
    fun sendTestNotification_mustDispatchWhenNotInNotifyProcess() {
        val body = functionBody(source(centerPath), "sendTestNotification")
        assertTrue(
            "sendTestNotification() 主进程路径必须 dispatchTest 转交 :ufi_notify。",
            body.contains("isNotifyProcess()") && body.contains("dispatchTest")
        )
    }

    @Test
    fun syncSchedule_mustCancelWorkerWhenKeepAliveOn() {
        val body = functionBody(source(guardSchedulerPath), "syncSchedule")
        assertTrue(
            "syncSchedule 必须在前台保活开启时 cancelUniqueWork：" +
                "Worker 跑在主进程，保活开启后仍 enqueue 会在后台周期拉起主进程。",
            body.contains("KEY_GUARD_FOREGROUND_KEEPALIVE") &&
                body.contains("cancelUniqueWork")
        )
        // 保活分支必须排在 enqueue 之前：先判 keep-alive 再判 guard/master
        val keepAliveAt = body.indexOf("KEY_GUARD_FOREGROUND_KEEPALIVE")
        val enqueueAt = body.indexOf("enqueueUniquePeriodicWork")
        assertTrue(
            "保活判断必须在 enqueue 之前（keepAlive@$keepAliveAt / enqueue@$enqueueAt）。",
            keepAliveAt in 0 until enqueueAt
        )
    }

    @Test
    fun dispatchReceiver_mustHandleSceneAndTest() {
        val code = source(dispatchReceiverPath)
        assertTrue(
            "NotifyDispatchReceiver 必须处理场景通知转交（EXTRA_NOTIFY_SCENE）。",
            code.contains("EXTRA_NOTIFY_SCENE") && code.contains("handleSceneRequest")
        )
        assertTrue(
            "NotifyDispatchReceiver 必须处理测试通知转交（EXTRA_NOTIFY_TEST）。",
            code.contains("EXTRA_NOTIFY_TEST")
        )
    }
}
