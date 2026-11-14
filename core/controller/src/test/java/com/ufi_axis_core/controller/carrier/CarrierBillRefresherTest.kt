package com.ufi_axis_core.controller.carrier

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 账单后台刷新的纯逻辑（2026-11-09）。
 *
 * 验证三件事：
 * 1. 只刷 [CarrierBillRefreshSource.refreshTargets] 给出的账号；
 * 2. 每个账号都带 `force=true`（否则会命中 5 分钟内存缓存，等于没刷）；
 * 3. 单个账号失败不阻断后面的账号。
 */
class CarrierBillRefresherTest {

    private class FakeSource(
        private val targets: List<String>,
        private val failOn: Set<String> = emptySet(),
    ) : CarrierBillRefreshSource {
        val calls = mutableListOf<Pair<String, Boolean>>()

        override fun refreshTargets(): List<String> = targets

        override suspend fun billSnapshotOf(accountId: String, force: Boolean): CarrierBillResult {
            calls += accountId to force
            if (accountId in failOn) throw CarrierUpstreamException("boom")
            return CarrierBillResult(sampleBill(), cachedAt = 1L)
        }
    }

    @Test
    fun `refreshAll 刷新全部目标并强制拉取`() = runBlocking {
        val source = FakeSource(listOf("a", "b"))
        CarrierBillRefresher(source, perAccountDelayMs = 0).refreshAll()

        assertEquals(listOf("a" to true, "b" to true), source.calls)
    }

    @Test
    fun `单个账号失败不影响后面的账号`() = runBlocking {
        val source = FakeSource(listOf("a", "b"), failOn = setOf("a"))
        CarrierBillRefresher(source, perAccountDelayMs = 0).refreshAll()

        assertEquals(listOf("a" to true, "b" to true), source.calls)
    }

    @Test
    fun `没有可刷新的账号时不做任何请求`() = runBlocking {
        val source = FakeSource(emptyList())
        CarrierBillRefresher(source, perAccountDelayMs = 0).refreshAll()

        assertEquals(emptyList<Pair<String, Boolean>>(), source.calls)
    }

    private companion object {
        fun sampleBill() = CarrierBill(
            carrier = CarrierKind.MOBILE,
            phoneMasked = "138****8000",
            planName = null,
            ownerName = null,
            tenureMonths = null,
            starLevel = null,
            balanceYuan = null,
            lastMonthBillYuan = null,
            currentBillYuan = null,
            currentBillPeriod = null,
            currentBillAsOf = null,
            points = null,
            flow = CarrierFlow(
                generalUsedGb = 0.0,
                generalTotalGb = 0.0,
                generalRemainingGb = 0.0,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
            ),
            monthlyUsedGb = null,
            voice = null,
            sms = null,
            packages = emptyList(),
            warnings = emptyList(),
        )
    }
}
