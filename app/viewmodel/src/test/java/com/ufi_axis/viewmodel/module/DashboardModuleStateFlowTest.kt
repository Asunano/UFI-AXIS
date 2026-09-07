package com.ufi_axis.viewmodel.module

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.DashboardSummaryResponse
import com.ufi_axis.data.model.TrafficLimitConfig
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.repository.AlertPrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * F4 — ViewModel/State 行为测试（Turbine）。
 *
 * 以 [DashboardModule]（持有 [DashboardState] 的 StateFlow）为代表性 ViewModel，
 * 用 Turbine 断言 `dashboardState` 在 load / error / retry 三个分支的发射序列。
 *
 * 依赖（app:viewmodel testImplementation 已含 robolectric / mockito / coroutines-test，
 * 本次新增 turbine）：
 *  - Robolectric 提供真实 Application Context（DashboardModule 构造时建 CacheManager / Room）。
 *  - UfiAxisApi 用 Mockito 接口 mock（interface 可 mock，无需子类覆写）。
 *
 * 注意：不调用 module.init()，避免 WebSocket 连接 / 网络观察等副作用；直接驱动 refreshDashboard。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardModuleStateFlowTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var api: UfiAxisApi
    private lateinit var wsRepo: WebSocketRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var moduleScope: CoroutineScope
    private lateinit var module: DashboardModule

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        api = mock()
        wsRepo = WebSocketRepository("http://localhost:8080", "test-token")
        networkMonitor = NetworkMonitor(appContext)
        // 与 ConcurrencyTest 8b17861 保持一致：module 用独立可控 scope（共享 testDispatcher/
        // 同一虚拟时钟但不带 BackgroundWork 标记），构造时 7 个 stateIn(WhileSubscribed) 派生
        // 协程成为该 scope 的子任务，测试结尾显式 cancel() 带走，既不泄漏又不阻塞 advanceUntilIdle。
        moduleScope = CoroutineScope(testDispatcher + SupervisorJob())
        module = DashboardModule(api, wsRepo, networkMonitor, appContext, moduleScope, AlertPrefsRepository())
        // 关键（第二层修复，与 8b17861 的 scope 修法配套）：
        // refreshDashboardInternal 成功路径会无条件调 saveToCache() → CacheManager.saveDataAsync
        // 在 CacheManager 自己的 CoroutineScope(Dispatchers.IO) 上 fire-and-forget 启动 Room 事务。
        // 虚拟时钟的 advanceUntilIdle() 等不到真实 IO；用例结束得飞快后，上一个用例的事务仍挂在
        // 共享的 Room 单例连接上，与下一个用例的新事务交错 → Robolectric shadow 抛
        // "Illegal connection pointer" → 被 test scheduler 记为未捕获异常 → 下个 runTest 报
        // UncaughtExceptionsBeforeTest。本测试类只用 dashboardState 发射序列做断言、不读缓存，
        // 故在此 shutdown CacheManager（onCleared 公开入口，且未 connect 的 WS disconnect 是 null-safe），
        // 令后续 saveDataAsync 全部短路，从根上消除跨用例的真实 IO 竞态（与文件 docstring
        // “避免 WebSocket / 网络观察等副作用”的既有立场一致）。
        module.onCleared()
    }

    @After
    fun tearDown() {
        moduleScope.cancel()
        Dispatchers.resetMain()
    }

    private fun summary(rx: Long) = DashboardSummaryResponse(
        traffic_limit = TrafficLimitConfig(monthly_rx_bytes = rx, monthly_tx_bytes = 0)
    )

    @Test
    fun `load branch emits loading then success`() = testScope.runTest {
        whenever(api.getDashboardSummary()).thenReturn(summary(100))

        module.dashboardState.test {
            val initial = awaitItem()
            assertFalse(initial.isLoading)

            module.refreshDashboard()
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val done = awaitItem()
            assertFalse(done.isLoading)
            assertNull(done.errorMessage)
            assertEquals(100L, done.trafficLimitConfig?.monthly_rx_bytes)
        }
    }

    @Test
    fun `error branch emits loading then error message`() = testScope.runTest {
        whenever(api.getDashboardSummary()).thenThrow(RuntimeException("boom"))

        module.dashboardState.test {
            awaitItem() // initial

            module.refreshDashboard()
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val error = awaitItem()
            assertFalse(error.isLoading)
            assertNotNull(error.errorMessage)
        }
    }

    @Test
    fun `retry branch emits loading error then loading success`() = testScope.runTest {
        whenever(api.getDashboardSummary())
            .thenThrow(RuntimeException("boom"))
            .thenReturn(summary(200))

        module.dashboardState.test {
            awaitItem() // initial

            module.refreshDashboard()
            advanceUntilIdle()
            awaitItem() // loading
            val error = awaitItem()
            assertNotNull(error.errorMessage)

            // 网络恢复后重试
            module.refreshDashboard()
            advanceUntilIdle()
            val loading2 = awaitItem()
            assertTrue(loading2.isLoading)
            val ok = awaitItem()
            assertFalse(ok.isLoading)
            assertEquals(200L, ok.trafficLimitConfig?.monthly_rx_bytes)
        }
    }
}
