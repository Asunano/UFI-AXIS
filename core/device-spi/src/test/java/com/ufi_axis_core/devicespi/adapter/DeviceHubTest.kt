package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.devicespi.WriteOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [DeviceHub] 的转发契约（2026-09-25 批 A1 立，批 A2a 加 device 域，批 A2b 加 network / wifi 域，
 * 批 B2 加 signal 域，批 C1 加 sms 域）。
 *
 * 本批的 hub **只有转发**（理由见 [DeviceHub] 的 KDoc），所以这里只钉三件事：
 * 各成员确实来自传入的 adapter、能力集不被加工、域接口的返回值原样透出。
 * 不在这里测 `GoformSimClient` / `GoformDeviceClient` 的行为 —— 那是 `:core:goform` 的事。
 *
 * 各域**不给每个方法各写一条**：那是在测 Kotlin 的方法委派，没有信息量。
 * 钉住的只有「`hub.<域>` 就是 adapter 给的那个对象」与「一次调用的入参 / 返回值不被中途加工」。
 */
class DeviceHubTest {

    private class FakeSim(private val outcome: WriteOutcome) : SimControl {
        var lastSlot: String? = null
        override suspend fun switchSimSlot(slot: String): WriteOutcome {
            lastSlot = slot
            return outcome
        }
    }

    /**
     * device 域的假实现。只有 [cellLock] 记录入参并回可控结果（它是回 [WriteOutcome] 的那一档，
     * 转发链路上最容易被加工的就是这个返回值）；其余方法回固定值，本类不断言它们。
     */
    private class FakeDevice(private val outcome: WriteOutcome) : DeviceControl {
        var lastCellLock: Triple<String, String, String>? = null

        override suspend fun rebootDevice(): Boolean = true
        override suspend fun factoryReset(): Boolean = true
        override suspend fun shutdownDevice(): Boolean = true
        override suspend fun setDebugMode(enabled: Boolean): Boolean = true
        override suspend fun setIndicatorLight(enabled: Boolean): Boolean = true
        override suspend fun setPerformanceMode(mode: Int): Boolean = true
        override suspend fun setSambaSetting(enabled: Boolean): Boolean = true
        override suspend fun setRestartSchedule(enabled: Boolean, time: String): WriteOutcome = outcome
        override suspend fun changePassword(oldPassword: String, newPassword: String): Boolean = true
        override suspend fun setFotaEnabled(enabled: Boolean): Boolean = true
        override suspend fun setDhcpSetting(
            lanIp: String, lanNetmask: String, dhcpType: String,
            dhcpStart: String, dhcpEnd: String, dhcpLease: String
        ): WriteOutcome = outcome

        override suspend fun cellLock(pci: String, earfcn: String, networkType: String): WriteOutcome {
            lastCellLock = Triple(pci, earfcn, networkType)
            return outcome
        }

        override suspend fun unlockAllCell(): Boolean = true
    }

    /** network 域的假实现：只有 [lockLteBands] 记录入参（[BandSelection] 是本批新加的那个类型）。 */
    private class FakeNetwork(private val outcome: WriteOutcome) : NetworkControl {
        var lastLteSelection: BandSelection? = null

        override suspend fun setMobileData(enabled: Boolean): Boolean = true
        override suspend fun setBearerPreference(preference: String): WriteOutcome = outcome
        override suspend fun connectNetwork(): Boolean = true
        override suspend fun disconnectNetwork(): Boolean = true
        override suspend fun setConnectionMode(mode: String): Boolean = true

        override suspend fun lockLteBands(bands: BandSelection): WriteOutcome {
            lastLteSelection = bands
            return outcome
        }

        override suspend fun lockNrBands(bands: BandSelection): WriteOutcome = outcome
        override suspend fun unlockAllBands(): Boolean = true
        override suspend fun setDataLimit(
            enabled: Boolean,
            limitValue: Long?, limitUnit: String?,
            alertPercent: String?, autoClear: Boolean?,
            clearDate: String?
        ): WriteOutcome = outcome

        override suspend fun calibrateFlow(target: String, value: String): WriteOutcome = outcome
        override suspend fun setRoaming(enabled: Boolean): Boolean = true
    }

    /** wifi 域的假实现（批 C2 后读写都在接口上）。本类只断言「是同一个对象」+ 读侧转发。 */
    private class FakeWifi(
        private val outcome: WriteOutcome,
        private val connectedClients: NormalizedFields? = null,
    ) : WifiControl {
        override suspend fun setWifiConfig(
            ssid: String?, authMode: String?, encrypType: String?, passphrase: String?,
            maxStaNum: Int?, broadcastDisabled: Int?, chipIndex: String?
        ): WriteOutcome = outcome

        override suspend fun setWifiPower(level: Int): Boolean = true
        override suspend fun setWifiSSID(ssid: String): Boolean = true
        override suspend fun setWifiEnabled(enabled: Boolean): Boolean = true
        override suspend fun setWifiBand(chip: String): WriteOutcome = outcome
        override suspend fun setWifiPassword(password: String): Boolean = true
        override suspend fun setWifiSleep(time: String): WriteOutcome = outcome

        override suspend fun setAccessControlList(
            black: List<AclEntry>, white: List<AclEntry>, mode: String?
        ): WriteOutcome = outcome

        override suspend fun getAccessControlList(): AclSnapshot? = null
        override suspend fun getWifiModuleInfo(): JsonObject? = null
        override suspend fun getWifiSettings(): JsonObject? = null
        override suspend fun getWifiSettingsMerged(): NormalizedFields = NormalizedFields.EMPTY
        override suspend fun getConnectedClients(): NormalizedFields? = connectedClients
        override suspend fun getCurrentWifiConfig(): Map<String, String> = emptyMap()
        override suspend fun getWifiQrCode(chip: String, ssidIndex: Int): Pair<ByteArray, String>? = null
        override val lastQrCodeFailure: String get() = ""
    }

    /**
     * signal 域的假实现（**只有读方法**）。只有 [getSignalInfo] 回可控结果并记录被调次数 ——
     * 转发链路上要钉的就是「hub.signal 是 adapter 给的那个对象」与「返回值不被中途加工」。
     * 其余 15 个方法回 null / 空报告，本类不断言它们。
     */
    private class FakeSignal(private val info: JsonObject?) : SignalSource {
        var signalInfoCalls = 0

        override val profileId: String? get() = "fake-profile"

        override suspend fun getSignalInfo(): JsonObject? {
            signalInfoCalls++
            return info
        }

        override suspend fun getConnectionInfo(): NormalizedFields? = null
        override suspend fun getNetworkInformation(): JsonObject? = null
        override suspend fun getDeviceInfo(): JsonObject? = null
        override suspend fun getDeviceIdentity(): NormalizedFields? = null
        override suspend fun getDeviceVersion(): NormalizedFields? = null
        override suspend fun getTrafficStats(): JsonObject? = null
        override suspend fun getFullStatus(): JsonObject? = null
        override suspend fun getFullStatusMasked(): JsonObject? = null
        override suspend fun diagnoseFieldCoverage(): JsonObject = JsonObject(emptyMap())
        override suspend fun getCellInfo(): NormalizedFields? = null
        override suspend fun getNeighborCellInfo(): JsonArray? = null
        override suspend fun getLanSettings(): NormalizedFields? = null
        override suspend fun queryDeviceSettings(): NormalizedFields? = null
        override suspend fun getBandLockStatus(): NormalizedFields? = null
        override suspend fun getDataUsage(): NormalizedFields? = null
    }

    /**
     * sms 域的假实现。只有 [sendSms] 记录入参并回可控结果（发信结论是这条链路上最不能被
     * 中途加工的返回值 —— 上层拿它决定要不要重试，而重试一条短信等于一笔话费）；
     * 其余方法回固定值，本类不断言它们。
     */
    private class FakeSms(private val outcome: SendOutcome) : SmsControl {
        var lastSend: Pair<String, String>? = null

        override suspend fun getSmsList(page: Int, perPage: Int): JsonObject? = null

        override suspend fun sendSms(phoneNumber: String, message: String): SendOutcome {
            lastSend = phoneNumber to message
            return outcome
        }

        override suspend fun deleteSms(msgId: String): Boolean = true
        override suspend fun markSmsRead(msgId: String, read: Boolean): Boolean = true
        override suspend fun getSmsMeta(): SmsMeta? = null
    }

    private class FakeAdapter(
        override val id: String,
        override val capabilities: Set<Capability>,
        override val sim: SimControl,
        override val device: DeviceControl = FakeDevice(WriteOutcome.Ok),
        override val network: NetworkControl = FakeNetwork(WriteOutcome.Ok),
        override val wifi: WifiControl = FakeWifi(WriteOutcome.Ok),
        override val signal: SignalSource = FakeSignal(null),
        override val sms: SmsControl = FakeSms(SendOutcome(SendVerdict.SENT, "")),
    ) : DeviceAdapter

    @Test
    fun `hub 的三个成员全部转发到传入的 adapter`() {
        val sim = FakeSim(WriteOutcome.Ok)
        val adapter = FakeAdapter("fake-device", setOf(Capability.SIM_SLOT_SWITCH), sim)
        val hub = DeviceHub(adapter)

        assertEquals("fake-device", hub.adapterId)
        assertSame(sim, hub.sim)
        assertSame(adapter.capabilities, hub.capabilities)
    }

    @Test
    fun `device 域转发到传入的 adapter`() {
        val device = FakeDevice(WriteOutcome.Ok)
        val adapter = FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), device)

        assertSame(device, DeviceHub(adapter).device)
    }

    @Test
    fun `device 域的入参与返回值原样透出`() {
        val rejected = WriteOutcome.Rejected("pci 必须是 0..1007 的整数")
        val device = FakeDevice(rejected)
        val hub = DeviceHub(FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), device))

        val outcome = runBlocking { hub.device.cellLock("100", "1850", "LTE") }

        assertSame(rejected, outcome)
        assertEquals(Triple("100", "1850", "LTE"), device.lastCellLock)
    }

    @Test
    fun `network 域转发到传入的 adapter，入参与返回值原样透出`() {
        val rejected = WriteOutcome.Rejected("频段号必须是 1..255 的纯数字列表")
        val network = FakeNetwork(rejected)
        val adapter = FakeAdapter(
            "fake-device", emptySet(), FakeSim(WriteOutcome.Ok), network = network
        )
        val hub = DeviceHub(adapter)

        assertSame(network, hub.network)
        val outcome = runBlocking { hub.network.lockLteBands(BandSelection.Only("1,3")) }

        assertSame(rejected, outcome)
        assertEquals(BandSelection.Only("1,3"), network.lastLteSelection)
    }

    @Test
    fun `wifi 域转发到传入的 adapter`() {
        val wifi = FakeWifi(WriteOutcome.Ok)
        val adapter = FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), wifi = wifi)

        assertSame(wifi, DeviceHub(adapter).wifi)
    }

    @Test
    fun `wifi 域读侧 getConnectedClients 的返回值原样透出`() {
        val clients = NormalizedFields.EMPTY
        val wifi = FakeWifi(WriteOutcome.Ok, connectedClients = clients)
        val adapter = FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), wifi = wifi)
        val hub = DeviceHub(adapter)

        assertSame(clients, runBlocking { hub.wifi.getConnectedClients() })
    }

    @Test
    fun `signal 域转发到传入的 adapter，读取结果原样透出`() {
        // 设备原名刻意留在这份假响应里：hub 不许对读侧结果做任何加工
        // （signal 的第 1 层映射在消费方 SignalCollector，见 SignalSource 的 KDoc）。
        val raw = JsonObject(mapOf("Nr_snr" to JsonPrimitive("17")))
        val signal = FakeSignal(raw)
        val adapter = FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), signal = signal)
        val hub = DeviceHub(adapter)

        assertSame(signal, hub.signal)
        assertSame(raw, runBlocking { hub.signal.getSignalInfo() })
        assertEquals(1, signal.signalInfoCalls)
        assertEquals("fake-profile", hub.signal.profileId)
    }


    @Test
    fun `sms 域转发到传入的 adapter，入参与发信结论原样透出`() {
        // NO_RESPONSE 是"拿不到设备表态"那一档：上层据此判**不可重试**，
        // 所以 hub 一旦把结论换掉，代价是重复发短信或漏计配额。
        val unconfirmed = SendOutcome(SendVerdict.NO_RESPONSE, "resp=null")
        val sms = FakeSms(unconfirmed)
        val adapter = FakeAdapter("fake-device", emptySet(), FakeSim(WriteOutcome.Ok), sms = sms)
        val hub = DeviceHub(adapter)

        assertSame(sms, hub.sms)
        val outcome = runBlocking { hub.sms.sendSms("13800138000", "hi") }

        assertSame(unconfirmed, outcome)
        assertEquals("13800138000" to "hi", sms.lastSend)
    }


    @Test
    fun `能力集原样可见、不被 hub 加工`() {
        val caps = setOf(Capability.SIM_SLOT_SWITCH, Capability.SMS)
        val hub = DeviceHub(FakeAdapter("fake-device", caps, FakeSim(WriteOutcome.Ok)))

        assertEquals(caps, hub.capabilities)
    }

    @Test
    fun `switchSimSlot 的返回值原样透出`() {
        val rejected = WriteOutcome.Rejected("x")
        val sim = FakeSim(rejected)
        val hub = DeviceHub(FakeAdapter("fake-device", emptySet(), sim))

        val outcome = runBlocking { hub.sim.switchSimSlot("2") }

        assertSame(rejected, outcome)
        assertEquals("2", sim.lastSlot)
    }
}
