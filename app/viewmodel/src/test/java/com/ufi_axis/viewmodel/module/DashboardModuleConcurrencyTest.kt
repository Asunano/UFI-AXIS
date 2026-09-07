package com.ufi_axis.viewmodel.module

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.MonitorHistoryResponse
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.repository.AlertPrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock
import java.util.concurrent.atomic.AtomicInteger

/**
 * DashboardModule 并发控制单元测试（T-P2-3：8 类监控历史由 Semaphore(4) 限制最大并发）。
 *
 * 思路：
 *  - 用 UfiAxisApi 的假实现（委托给 Mockito mock，仅覆写 getMonitorHistory）模拟后端时延，
 *    并在调用期间统计“同时进行中的拉取数 (maxConcurrent)”。
 *  - DashboardModule 的 scope 用「共享 runTest testScheduler 的独立 CoroutineScope」，
 *    delay(50) 被虚拟化，从而能稳定观察到信号量对并发的约束。
 *    不能直接用 backgroundScope：coroutines-test 1.9.0 的 advanceUntilIdle() 只驱动“前台事件”，
 *    而 backgroundScope 派发的任务带 BackgroundWork 标记、属于“后台事件”，永远不会被执行
 *    （见 TestCoroutineScheduler.advanceUntilIdle = advanceUntilIdleOr { events.none { it.isForeground } }）。
 *  - 断言：最大并发数不得超过 4（信号量被误删时 8 路会同时进入 getMonitorHistory，断言将失败）。
 *
 * 依赖（见 app/viewmodel/build.gradle.kts 的 testImplementation）：
 *  - junit:junit
 *  - org.robolectric:robolectric（提供 Context；DashboardModule 构造时会建 CacheManager/Room DB）
 *  - org.mockito:mockito-core + org.mockito.kotlin:mockito-kotlin（构造 UfiAxisApi 假实现）
 *  - org.jetbrains.kotlinx:kotlinx-coroutines-test（runTest / advanceUntilIdle）
 *
 * 注意：本测试是 4 个测试中最重的一个——它触发 Room 数据库在 Robolectric 下的构建。
 * 若 Room 在 unit test 下构建失败，可改用 androidTest（Instrumentation）或将 CacheManager 改为可注入。
 *
 * 注：JUnit4 的 @Test 方法不能是 suspend，故用 runBlocking 桥接，内部再 runTest 以获得虚拟时钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardModuleConcurrencyTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `loadMonitorHistory caps concurrent fetches at Semaphore(4)`() = runBlocking {
        runTest {
            val api = FakeMonitorApi()
            // 不能把 module 的 scope 设为 backgroundScope：coroutines-test 1.9.0 的
            // TestCoroutineScheduler.advanceUntilIdle() 只跑到“没有前台事件”为止，
            // 而 backgroundScope 派发的任务带 BackgroundWork 标记（后台事件），永远不会被执行。
            // 正确做法：给 module 一个共享 testScheduler 但不含 BackgroundWork 的独立 scope，
            // 使其任务成为前台事件（可被 advanceUntilIdle 驱动），并在测试结尾显式 cancel，
            // 带走构造时 7 个 stateIn(WhileSubscribed) 派生协程，避免测试结束时泄漏。
            val moduleScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val module = DashboardModule(
                api = api,
                webSocketRepository = WebSocketRepository("http://localhost:8080", "test-token"),
                networkMonitor = NetworkMonitor(appContext),
                appContext = appContext,
                scope = moduleScope,
                alertPrefs = AlertPrefsRepository()
            )

            module.loadMonitorHistory(hours = 24, force = true)
            advanceUntilIdle()

            // 关键不变量：并发拉取的“进行中”任务数不得超过 4（Semaphore(4) 的约束）
            assertTrue(
                "最大并发数 ${api.maxConcurrent.get()} 不应超过 4（Semaphore(4) 约束被破坏）",
                api.maxConcurrent.get() <= 4
            )
            // 健全性检查：8 类历史都应被尝试拉取
            assertEquals(8, api.callCount.get())

            // 清理：取消独立 scope，带走 7 个 stateIn 派生协程，避免测试结束时泄漏
            moduleScope.cancel()
        }
    }

    /**
     * 仅实现 getMonitorHistory：记录并发数 + 模拟后端时延，其余接口方法委托给 Mockito mock（本测试不会调用）。
     * 通过类委托 `by mock<UfiAxisApi>()` 避免手写 100+ 个接口方法。
     */
    private class FakeMonitorApi : UfiAxisApi by mock<UfiAxisApi>() {
        val maxConcurrent = AtomicInteger(0)
        val callCount = AtomicInteger(0)
        private val active = AtomicInteger(0)

        override suspend fun getMonitorHistory(
            type: String,
            hours: Int,
            points: Int,
            // 2026-09-03：接口新增可选 bucket_ms（客户端显式桶宽），override 必须跟着加形参；
            // 本测试只关心并发度，不校验桶宽。
            bucketMs: Long?
        ): MonitorHistoryResponse {
            callCount.incrementAndGet()
            val cur = active.incrementAndGet()
            maxConcurrent.updateAndGet { k -> kotlin.math.max(k, cur) }
            try {
                delay(50) // 虚拟时钟下由 runTest 调度，模拟后端时延
            } finally {
                active.decrementAndGet()
            }
            return MonitorHistoryResponse(
                type = type,
                points = emptyList(),
                count = 0,
                raw_count = 0,
                period_hours = hours,
                bucket_seconds = 0,
                bucket_ms = bucketMs ?: 0L
            )
        }
    }
}
