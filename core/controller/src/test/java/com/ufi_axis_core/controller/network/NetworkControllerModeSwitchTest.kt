package com.ufi_axis_core.controller.network

import android.content.Context
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.SignalSource
import com.ufi_axis_core.devicespi.adapter.WifiControl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `NetworkController.setNetworkMode` 的编排判据（2026-10-31）。
 *
 * 背景（真机行为）：`SET_BEARER_PREFERENCE` 要让 modem 换 RAT 重新驻网，当前 PDP/PPP 必然被拆掉，
 * 而设备**不会自己重拨** —— 用户看到的就是「切完制式，移动数据变成关，得手动再开一次」。
 * 所以 controller 在下发前读一次连接状态，写成功后按这个意图补发一次 `CONNECT_NETWORK`。
 *
 * 这里只钉**三条边界**（也是这段代码唯一会伤到用户的地方）：
 *   1. 切换前连着 → 写成功后要恢复；
 *   2. 切换前是关的 → **一条数据命令都不许发**（会直接产生流量费）；
 *   3. 读不到连接状态 → 同样不发（"不知道"不能当"开着"）。
 * 另外顺带钉住「下发失败不恢复」：制式没被受理时设备压根没断网，多发一条是纯噪音。
 *
 * 为什么不需要 Robolectric：这条路径只碰接口与 `AppLogger`（`Log.*` 在单测里抛异常时
 * AppLogger 自己 catch 掉了，见它的 `log()`），没有调用任何 Android 系统服务。
 */
class NetworkControllerModeSwitchTest {

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

    /** 造一份只带 `ppp_status` 的连接状态；null = 字段缺失（模拟读到了别的分组）。 */
    private fun connectionInfo(pppStatus: String?): NormalizedFields {
        val fields = mockk<NormalizedFields>(relaxed = true)
        every { fields.values } returns JsonObject(
            if (pppStatus == null) emptyMap()
            else mapOf(DeviceFields.Connection.PPP_STATUS to JsonPrimitive(pppStatus))
        )
        return fields
    }

    @Test
    fun `切换前连着数据时，写成功后把移动数据重新打开`() = runTest {
        coEvery { signal.getConnectionInfo() } returns connectionInfo("ppp_connected")
        coEvery { network.setBearerPreference("5G_ONLY") } returns WriteOutcome.Ok
        coEvery { network.setMobileData(true) } returns true

        val outcome = controller.setNetworkMode("5G_ONLY")

        assertTrue("制式切换本身仍然是成功", outcome.ok)
        coVerify(exactly = 1) { network.setBearerPreference("5G_ONLY") }
        coVerify(exactly = 1) { network.setMobileData(true) }
    }

    @Test
    fun `切换前数据是关的，不许替用户打开`() = runTest {
        coEvery { signal.getConnectionInfo() } returns connectionInfo("ppp_disconnected")
        coEvery { network.setBearerPreference("ONLY_LTE") } returns WriteOutcome.Ok

        controller.setNetworkMode("ONLY_LTE")

        coVerify(exactly = 0) { network.setMobileData(any()) }
    }

    @Test
    fun `读不到连接状态时不恢复，宁可少恢复也不凭空开数据`() = runTest {
        coEvery { signal.getConnectionInfo() } returns null
        coEvery { network.setBearerPreference("ONLY_LTE") } returns WriteOutcome.Ok

        controller.setNetworkMode("ONLY_LTE")

        coVerify(exactly = 0) { network.setMobileData(any()) }
    }

    @Test
    fun `ppp_status 字段缺失也算读不到`() = runTest {
        coEvery { signal.getConnectionInfo() } returns connectionInfo(null)
        coEvery { network.setBearerPreference("AUTO") } returns WriteOutcome.Ok

        controller.setNetworkMode("AUTO")

        coVerify(exactly = 0) { network.setMobileData(any()) }
    }

    @Test
    fun `下发失败时不恢复数据`() = runTest {
        coEvery { signal.getConnectionInfo() } returns connectionInfo("ppp_connected")
        coEvery { network.setBearerPreference("5G_ONLY") } returns WriteOutcome.Failed

        val outcome = controller.setNetworkMode("5G_ONLY")

        assertFalse("失败原样透传给 route", outcome.ok)
        coVerify(exactly = 0) { network.setMobileData(any()) }
    }

    @Test
    fun `第一次恢复被设备拒绝时重试一次`() = runTest {
        coEvery { signal.getConnectionInfo() } returns connectionInfo("ppp_connected")
        coEvery { network.setBearerPreference("5G_ONLY") } returns WriteOutcome.Ok
        // 设备重注册期间第一条 CONNECT_NETWORK 可能不被接受：第二次要再试
        coEvery { network.setMobileData(true) } returnsMany listOf(false, true)

        val outcome = controller.setNetworkMode("5G_ONLY")

        coVerify(exactly = 2) { network.setMobileData(true) }
        // 恢复的尝试次数不影响主结果
        assertTrue(outcome.ok)
    }
}
