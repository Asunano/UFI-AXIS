package com.ufi_axis_core.deviceplugins.zte

import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSimClient
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.BandSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [BandSelection] → goform 取值的翻译（2026-09-25 批 A2b）。
 *
 * ## 为什么值得有
 *
 * 批 A2b 把「解锁全部频段」从**取值**（`NetworkController` 先问
 * `GoformNetworkClient.lteAllBands()` 拿频段全集掩码串再下发）改成了**意图**
 * （[BandSelection.All]，掩码串只在本模块的 adapter 里出现）。那次改动唯一的风险就是
 * 「翻译错了、下发的取值变了」，这里钉的就是它：
 *
 * 1. `All` 传给 `GoformNetworkClient` 的取值**就是** `lteAllBands()` / `nrAllBands()` 的返回值
 *    （= 改造前 `NetworkController` 拿到的那个串，逐字未变）；
 * 2. LTE 用 `lteAllBands()`、NR 用 `nrAllBands()`，**没有抄反**（两个方法同形状、
 *    只差三个字母，抄反的后果是给 NR 发一串 LTE 频段号 —— 静默的错配）；
 * 3. `Only` 的取值原样透传，空串仍然原样发（空串 = 解除该 RAT 限制）。
 *
 * ## 为什么 mock `GoformNetworkClient` 而不是造个假传输层
 *
 * `GoformTransport` 有守门测试（`GoformTransportVisibilityGuardTest`）钉着
 * 「不许跨出 `core/goform`」，而它扫的是 `core` 目录下**全部** `.kt`（测试源码也算）。
 * 断言点本来也不在传输层：本批的改动全部发生在「adapter ↔ 客户端」这条边界上，
 * mock 掉客户端正好把它单独暴露出来。
 *
 * ## 为什么不扩 `GoformAllBandsMaskTest`
 *
 * 那个用例在 `:core:goform`，测的是「profile 没给掩码 → 折叠成空串 + 不抛异常」，
 * 本批一行没改、仍然有效。但它**看不到** `ZteGoformAdapter`（模块依赖方向是
 * device-plugins → goform，反过来不成立），而 `All` → 掩码的翻译只存在于 adapter 里。
 * 所以这条只能落在本模块，不是重复。
 */
class ZteGoformAdapterBandSelectionTest {

    /** 真实取值：本批的判据是「与改造前一致」，所以掩码取生产 profile 的那一份。 */
    private val lteMask: String = ZteGoformProfile.lteAllBandsMask()!!
    private val nrMask: String = ZteGoformProfile.nrAllBandsMask()!!

    private val client = mockk<GoformNetworkClient>(relaxed = true)

    private val network = ZteGoformAdapter(
        id = "zte-f50",
        capabilities = emptySet(),
        simClient = mockk<GoformSimClient>(relaxed = true),
        deviceClient = mockk<GoformDeviceClient>(relaxed = true),
        networkClient = client,
        wifiClient = mockk<GoformWifiClient>(relaxed = true),
        signalClient = mockk<GoformSignalClient>(relaxed = true),
        smsClient = mockk<GoformSmsClient>(relaxed = true),
    ).network

    private fun stubMasks() {
        every { client.lteAllBands() } returns lteMask
        every { client.nrAllBands() } returns nrMask
        coEvery { client.lockLteBands(any()) } returns WriteOutcome.Ok
        coEvery { client.lockNrBands(any()) } returns WriteOutcome.Ok
    }

    @Test
    fun `LTE 的 All 下发的取值就是 lteAllBands 的返回值`() = runBlocking {
        stubMasks()

        network.lockLteBands(BandSelection.All)

        coVerify(exactly = 1) { client.lockLteBands(lteMask) }
        verify(exactly = 1) { client.lteAllBands() }
        // 抄反守门：LTE 这一路不许去问 NR 的全集
        verify(exactly = 0) { client.nrAllBands() }
    }

    @Test
    fun `NR 的 All 下发的取值就是 nrAllBands 的返回值`() = runBlocking {
        stubMasks()
        // 两个掩码取值不同，上面那条「没抄反」的断言才有意义
        assertNotEquals(lteMask, nrMask)

        network.lockNrBands(BandSelection.All)

        coVerify(exactly = 1) { client.lockNrBands(nrMask) }
        verify(exactly = 1) { client.nrAllBands() }
        verify(exactly = 0) { client.lteAllBands() }
    }

    @Test
    fun `Only 的取值原样下发，且不去问频段全集`() = runBlocking {
        stubMasks()

        network.lockLteBands(BandSelection.Only("1,3"))
        // 空串 = 解除该 RAT 限制（改造前「未选某个 RAT」走的就是空串这条路）
        network.lockNrBands(BandSelection.Only(""))

        coVerify(exactly = 1) { client.lockLteBands("1,3") }
        coVerify(exactly = 1) { client.lockNrBands("") }
        verify(exactly = 0) { client.lteAllBands() }
        verify(exactly = 0) { client.nrAllBands() }
    }
}
