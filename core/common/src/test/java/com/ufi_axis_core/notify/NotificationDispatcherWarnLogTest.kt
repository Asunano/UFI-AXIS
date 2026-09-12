package com.ufi_axis_core.notify

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「[NotifyEvent.channels] 与 [NotifyEvent.exclude] 同时给值时必须留一条 WARN」的专门用例。
 *
 * ## 为什么单独一个类
 *
 * 断言要读 [AppLogger] 的内存缓冲，而那需要两个前提，都与 [NotificationDispatcherTest] 的
 * 轻量口径冲突：
 * 1. **日志总开关得打开** —— `AppSettings.DEFAULT_LOG_ENABLED` 是 **false**，
 *    默认状态下 `AppLogger` 一条都不记（`active` 为假直接返回）；
 * 2. **需要 Robolectric** —— 开了开关之后 `AppLogger` 会真的调 `android.util.Log`，
 *    裸 JVM 单测里那是个抛 `Stub!` 的桩。
 *
 * `AppLogger` 是单例 object，所以这里照 `AppLoggerSwitchTest` 的做法：用例自己开开关，
 * [tearDown] 恢复出厂默认，避免影响同模块其它测试。
 *
 * 刻意**不调用** `AppLogger.init()`：init 会去 `/sdcard/Download/…` 建目录，单测不该在
 * 开发机上留文件。不 init → `logDir == null` → 落盘直接返回，内存缓冲不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationDispatcherWarnLogTest {

    @After
    fun tearDown() {
        AppLogger.restoreSwitches(
            logEnabled = AppSettings.DEFAULT_LOG_ENABLED,
            coreLogEnabled = AppSettings.DEFAULT_CORE_LOG_ENABLED,
            debugMode = AppSettings.DEFAULT_DEBUG_MODE
        )
        AppLogger.clearBuffer()
    }

    /** 不做任何事的假渠道：本用例只关心分发器打了什么日志。 */
    private class NoopChannel(override val id: String) : NotifyChannel {
        override val recordsHistory: Boolean = false
        override val respectsMasterGate: Boolean = false
        override val hasDeliveryConfirmation: Boolean = false
        override fun isConfigured(): Boolean = true
        override fun accepts(scene: String): Boolean = true
        override suspend fun deliver(event: NotifyEvent): DeliveryAttempt =
            DeliveryAttempt(DeliveryOutcome.Sent)

    }

    /**
     * 白名单与黑名单同时给值 → 打一条 **WARN**，不静默。
     *
     * 静默采纳一个等于把"触发源没想清楚要投给谁"这个歧义埋起来；而 release 构建只保留
     * WARN/ERROR，落在 INFO 里等于事后完全查不到。级别也一并断言（`[WARN]` 标签），
     * 降级成 INFO 就等于这条线索在真机上消失。
     */
    @Test
    fun `同时给出 channels 与 exclude 会打一条 WARN`() = runBlocking {
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = false)
        AppLogger.clearBuffer()

        val dispatcher = NotificationDispatcher()
        dispatcher.register(NoopChannel(PushChannel.ID))
        dispatcher.emit(
            NotifyEvent(
                scene = NotifyScenes.BATTERY,
                level = NotifyLevel.WARNING,
                title = "t",
                body = "b",
                channels = setOf(PushChannel.ID),
                exclude = setOf(PushChannel.ID)
            )
        )

        assertTrue(
            "两者同时给值必须留一条 WARN（含 scene 与两个集合），不能静默采纳其中一个",
            AppLogger.getBufferedLogs().any {
                "[WARN]" in it && "以 channels 为准" in it && NotifyScenes.BATTERY in it
            }
        )
    }

    /** 只给 `exclude`（正常用法）**不该**打这条 WARN —— 否则每条新短信都会留一行噪声。 */
    @Test
    fun `只给 exclude 不打 WARN`() = runBlocking {
        AppLogger.restoreSwitches(logEnabled = true, coreLogEnabled = true, debugMode = false)
        AppLogger.clearBuffer()

        val dispatcher = NotificationDispatcher()
        dispatcher.register(NoopChannel(PushChannel.ID))
        dispatcher.emit(
            NotifyEvent(
                scene = NotifyScenes.SMS,
                level = NotifyLevel.INFO,
                title = "t",
                body = "b",
                exclude = setOf(PushChannel.ID)
            )
        )

        assertTrue(
            "exclude 是正常用法，不该产生告警日志",
            AppLogger.getBufferedLogs().none { "以 channels 为准" in it }
        )
    }
}
