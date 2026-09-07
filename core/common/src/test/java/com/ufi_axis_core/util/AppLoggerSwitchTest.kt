package com.ufi_axis_core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppLogger 三层开关的回归防线（对应 2026-09-04「日志总是会自动开启」）。
 *
 * 缺陷回顾：`bufferEnabled`（= `debug_mode` 的进程内镜像）初值硬编码 `true`，而持久化默认值是
 * false，且只有 `ComponentFactory.build()` 会回灌。于是所有**不走 build 的进程启动路径**
 * （autoStartOnBoot=false、BootReceiver / InstallReceiver 拉起）在整个进程生命周期里
 * 详细日志都是开的 —— 用户关掉、core 重启后又自己写起来。
 *
 * 因此这里钉两件事：
 * 1. **进程内初值不得比持久化默认值更宽松**（[初值不放行 DEBUG]）；
 * 2. [AppLogger.restoreSwitches] 三层都要真的生效（[恢复三层]、[总闸关掉后一条不记]）。
 *
 * 刻意**不调用** `AppLogger.init()`：init 会去 `/sdcard/Download/...` 建目录，
 * 单测不该在开发机上留文件。不 init → `logDir == null` → `writeToFile` 直接返回，
 * 内存缓冲的行为不受影响，正好是本测试要断言的部分。
 *
 * 注意 AppLogger 是单例 object，用例之间会串状态，所以每个用例自己先 restoreSwitches 到已知态，
 * [tearDown] 再恢复成出厂默认，避免影响同模块其它测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLoggerSwitchTest {

    @After
    fun tearDown() {
        AppLogger.restoreSwitches(
            logEnabled = AppSettings.DEFAULT_LOG_ENABLED,
            coreLogEnabled = AppSettings.DEFAULT_CORE_LOG_ENABLED,
            debugMode = AppSettings.DEFAULT_DEBUG_MODE
        )
        AppLogger.clearBuffer()
    }

    @Test
    fun `初值不放行 DEBUG`() {
        // 不碰任何 setter，直接用 object 的初始状态：等价于「进程刚起来、还没恢复持久化值」。
        // DEFAULT_DEBUG_MODE 是 false，所以这一刻就不该有 DEBUG 进缓冲。
        assertEquals(false, AppSettings.DEFAULT_DEBUG_MODE)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "boot-window-debug-line")
        assertTrue(
            "进程启动窗口内 DEBUG 被放行了 —— bufferEnabled 的初值又比持久化默认值宽松了",
            AppLogger.getBufferedLogs().none { "boot-window-debug-line" in it }
        )
    }

    @Test
    fun `恢复三层`() {
        // debug_mode = false → DEBUG 不进内存缓冲。
        // 注意断言只覆盖**内存缓冲**：WARN/ERROR 是否落盘由 writeToFile 决定，
        // 而本测试刻意没 init()（不想在开发机上建 /sdcard 目录），所以落盘不在断言范围内。
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = false)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "verbose-off-debug")
        assertTrue(
            "debug_mode=false 时 DEBUG 不应进缓冲",
            AppLogger.getBufferedLogs().none { "verbose-off-debug" in it }
        )
        assertTrue("三层里只关详细级别，总闸仍应是开的", AppLogger.isLogEnabled())

        // debug_mode = true → DEBUG 进缓冲
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = true)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "verbose-on-debug")
        assertTrue(
            "debug_mode=true 时 DEBUG 应进缓冲",
            AppLogger.getBufferedLogs().any { "verbose-on-debug" in it }
        )
    }

    @Test
    fun `关掉详细日志后 WARN 与 ERROR 仍进缓冲`() {
        // 2026-09-04：修「关掉详细日志 → GET /api/debug-logs 恒空 → web 卡片永远『暂无日志』」。
        // 根因是缓冲写入条件 `bufferEnabled && level >= bufferMinLevel` 的第一项就是 debug_mode，
        // 关掉后 bufferMinLevel = ERROR 这一支成了死代码。缓冲同时是崩溃 dump 的唯一上下文来源，
        // 所以这条断言同时守着「崩溃现场有内容」。
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = false)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "quiet-debug")
        AppLogger.i("SwitchTest", "quiet-info")
        AppLogger.w("SwitchTest", "quiet-warn")
        AppLogger.e("SwitchTest", "quiet-error")
        val logs = AppLogger.getBufferedLogs()
        assertTrue("DEBUG 不该进缓冲", logs.none { "quiet-debug" in it })
        assertTrue("INFO 不该进缓冲", logs.none { "quiet-info" in it })
        assertTrue("WARN 必须进缓冲（崩溃现场靠它）", logs.any { "quiet-warn" in it })
        assertTrue("ERROR 必须进缓冲，否则 /api/debug-logs 恒空", logs.any { "quiet-error" in it })
        assertTrue("崩溃 dump 应能取到这些行", AppLogger.dumpRecentForCrash().any { "quiet-error" in it })
    }

    @Test
    fun `关闭详细日志只丢弃历史 DEBUG 不丢错误链`() {
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = true)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "history-debug")
        AppLogger.e("SwitchTest", "history-error")
        AppLogger.setDebugMode(false)
        val logs = AppLogger.getBufferedLogs()
        assertTrue("关掉详细日志后历史 DEBUG 应被丢弃", logs.none { "history-debug" in it })
        assertTrue("关掉详细日志不该连历史 ERROR 一起清掉", logs.any { "history-error" in it })
    }

    @Test
    fun `总闸关掉后一条不记`() {
        AppLogger.restoreSwitches(logEnabled = false, coreLogEnabled = true, debugMode = true)
        AppLogger.clearBuffer()
        AppLogger.d("SwitchTest", "master-off-debug")
        AppLogger.e("SwitchTest", "master-off-error")
        assertTrue("log_enabled=false 必须连 ERROR 都不记", AppLogger.getBufferedLogs().isEmpty())
        assertEquals(false, AppLogger.isLogEnabled())
    }

    @Test
    fun `core 子开关关掉后 core 侧不记`() {
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = false, debugMode = true)
        AppLogger.clearBuffer()
        AppLogger.e("SwitchTest", "core-off-error")
        assertTrue("core_log_enabled=false 时 core 侧不该再记录", AppLogger.getBufferedLogs().isEmpty())
        assertEquals(false, AppLogger.isLogEnabled())
    }
}
