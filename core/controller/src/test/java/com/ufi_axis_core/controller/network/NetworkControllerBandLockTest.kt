package com.ufi_axis_core.controller.network

import android.content.Context
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.BandSelection
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.SignalSource
import com.ufi_axis_core.devicespi.adapter.WifiControl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `NetworkController.lockBands` 的写入口径（2026-11-08）。
 *
 * 背景：用户反馈「全部解锁」在界面上变成了“全部频段取消勾选”。核心问题是
 * `band-status` 用 `"0"` / `"all"` 表示“没有限制”，客户端归一化成空集后，
 * 空集到底是“全部允许”还是“一个都不允许”必须由写入口径钉死。
 *
 * 本测试只钉三条：
 * 1. `unlockAll = true` 对 LTE / NR 各下发一次 [BandSelection.All] —— 这是“全选”，
 *    不是空列表；adapter 会把它换成 profile 登记的全频段掩码。
 * 2. 显式锁定传 `Only("...")`；某一组为 null 时下发 `Only("")`（清掉该组限制），
 *    对应界面上的“该组全选 / 不限制”。
 * 3. 两组都为空且 `unlockAll = false` 直接 `no_bands`，**一条写都不发** ——
 *    这正是旧 UI「未选任何频段点锁定所选」会误走 unlock 的反面判据。
 */
class NetworkControllerBandLockTest {

    private val context: Context = mockk(relaxed = true)
    private val atChannel: ATChannel = mockk(relaxed = true)
    private val network: NetworkControl = mockk(relaxed = true)
    private val wifi: WifiControl = mockk(relaxed = true)
    private val signal: SignalSource = mockk(relaxed = true)
    private val platform: PlatformAdapter = mockk(relaxed = true)

    private lateinit var controller: NetworkController

    @Before
    fun setup() {
        controller = NetworkController(context, atChannel, network, wifi, signal, platform)
    }

    @Test
    fun `unlockAll sends All for both RATs`() = runTest {
        coEvery { network.lockLteBands(any()) } returns WriteOutcome.Ok
        coEvery { network.lockNrBands(any()) } returns WriteOutcome.Ok

        val result = controller.lockBands(null, null, unlockAll = true)

        assertTrue("解锁本身应成功", result.success)
        assertEquals("auto", result.mode)
        coVerify(exactly = 1) { network.lockLteBands(BandSelection.All) }
        coVerify(exactly = 1) { network.lockNrBands(BandSelection.All) }
    }

    @Test
    fun `explicit lock sends Only lists and null group clears that RAT`() = runTest {
        coEvery { network.lockLteBands(any()) } returns WriteOutcome.Ok
        coEvery { network.lockNrBands(any()) } returns WriteOutcome.Ok

        val result = controller.lockBands("3,8", null)

        assertTrue(result.success)
        assertEquals("lte_only", result.mode)
        coVerify(exactly = 1) { network.lockLteBands(BandSelection.Only("3,8")) }
        coVerify(exactly = 1) { network.lockNrBands(BandSelection.Only("")) }
    }

    @Test
    fun `both empty without unlockAll is rejected before any write`() = runTest {
        val result = controller.lockBands(null, null)

        assertFalse("空列表不是合法的锁定目标", result.success)
        assertEquals("no_bands", result.mode)
        coVerify(exactly = 0) { network.lockLteBands(any()) }
        coVerify(exactly = 0) { network.lockNrBands(any()) }
    }
}
