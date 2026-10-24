package com.ufi_axis_core.api.pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ApprovalPushNudger] 的调度语义（2026-10-21）。
 *
 * 守的是四件事，任何一条退化都会让「App 弹窗快起来」这个目标悄悄失效（不会报错，只是变慢）：
 * 1. 一轮确实发满 [ApprovalPushNudger.DEFAULT_SCHEDULE_MS] 的帧数；
 * 2. 请求一旦不是 pending（机主已批准/拒绝、或 TTL 过期）就**立刻停**，不做无用唤醒；
 * 3. 同一 sid 只跑一轮（Web 刷页面重发同一个 sid 不该把机主手机刷屏）；
 * 4. 全局预算与并发上限生效（端点免鉴权，谁都能触发）。
 *
 * 用 Robolectric 是因为 [ApprovalPushNudger] 里会打 AppLogger（底层 android.util.Log）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalPushNudgerTest {

    private val fastSchedule = listOf(0L, 100L, 200L)

    @Test
    fun `新 sid 按时间表发满帧`() = runTest {
        val stamps = mutableListOf<Long>()
        val nudger = ApprovalPushNudger(
            push = { stamps += testScheduler.currentTime },
            isStillPending = { true },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("sid-1")
        advanceUntilIdle()

        assertEquals("帧数必须等于时间表长度", fastSchedule.size, stamps.size)
        assertEquals("帧必须落在时间表上", listOf(0L, 100L, 200L), stamps)
    }

    @Test
    fun `请求不再是 pending 就立刻停`() = runTest {
        var pushCount = 0
        val nudger = ApprovalPushNudger(
            push = { pushCount++ },
            // 第一帧发出去之后就"机主批准了"
            isStillPending = { pushCount == 0 },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("sid-1")
        advanceUntilIdle()

        assertEquals("批准后不该继续唤醒（每帧都会拉一次列表）", 1, pushCount)
    }

    @Test
    fun `同一 sid 重复触发只跑一轮`() = runTest {
        var pushCount = 0
        val nudger = ApprovalPushNudger(
            push = { pushCount++ },
            isStillPending = { true },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("sid-1")
        nudger.onNewApproval("sid-1")
        advanceTimeBy(50)
        nudger.onNewApproval("sid-1")
        advanceUntilIdle()

        assertEquals("幂等刷新不能把帧数翻倍", fastSchedule.size, pushCount)
    }

    @Test
    fun `全局预算用完就不再唤醒`() = runTest {
        var pushCount = 0
        val nudger = ApprovalPushNudger(
            push = { pushCount++ },
            isStillPending = { true },
            scheduleMs = fastSchedule,
            scope = this
        )

        // 逐轮推进：每轮都跑完再开下一轮，避开并发上限（那由上面那条用例之外的常量管）
        repeat(ApprovalPushNudger.MAX_BURSTS_PER_WINDOW + 1) { i ->
            nudger.onNewApproval("sid-$i")
            advanceUntilIdle()
        }

        assertEquals(
            "第 ${ApprovalPushNudger.MAX_BURSTS_PER_WINDOW + 1} 轮必须被预算挡掉",
            ApprovalPushNudger.MAX_BURSTS_PER_WINDOW * fastSchedule.size,
            pushCount
        )
    }

    @Test
    fun `无在线客户端时跳帧但不结束整轮`() = runTest {
        var pushCount = 0
        var live = false
        val nudger = ApprovalPushNudger(
            push = { pushCount++ },
            isStillPending = { true },
            hasLiveClient = { live },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("sid-1")
        advanceTimeBy(50)
        assertEquals("没有 WS 客户端时不该白发", 0, pushCount)

        // App 在这之后连上了：剩下的帧必须照发，否则"App 刚连回来"这个场景又要等 30s
        live = true
        advanceUntilIdle()
        assertEquals("第 2、3 帧必须补上", 2, pushCount)
    }

    @Test
    fun `单帧发送失败不影响后续帧`() = runTest {
        var attempts = 0
        var successes = 0
        val nudger = ApprovalPushNudger(
            push = {
                attempts++
                if (attempts == 1) error("boom") else successes++
            },
            isStillPending = { true },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("sid-1")
        advanceUntilIdle()

        assertEquals("三帧都要尝试", fastSchedule.size, attempts)
        assertEquals("失败那一帧之后仍要有两帧成功", 2, successes)
    }

    @Test
    fun `空 sid 不触发任何帧`() = runTest {
        var pushCount = 0
        val nudger = ApprovalPushNudger(
            push = { pushCount++ },
            isStillPending = { true },
            scheduleMs = fastSchedule,
            scope = this
        )

        nudger.onNewApproval("   ")
        advanceUntilIdle()

        assertEquals(0, pushCount)
        assertTrue("默认时间表必须是「多帧」，否则退化成单次推送", ApprovalPushNudger.DEFAULT_SCHEDULE_MS.size > 1)
    }
}
