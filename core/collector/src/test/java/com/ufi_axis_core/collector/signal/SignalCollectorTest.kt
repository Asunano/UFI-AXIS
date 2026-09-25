package com.ufi_axis_core.collector.signal

import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.devicespi.adapter.SignalSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader

/**
 * [SignalCollector] 的**对外 key 集合**契约（2026-09-25 批 B2）。
 *
 * ## 这个用例是为什么加的
 *
 * 批 B2 把构造参数从 `GoformSignalClient` 换成域接口 [SignalSource]。这次改动唯一的风险是
 * 「**对外 signal 字段名静默变化**」—— 本类的输出同时喂 WS `signal` 频道与
 * `/api/network/signal`，key 集合是既有对外契约，改了不会有任何编译错误，
 * 只会让两端前端静默显示空值。所以这里钉死三件事：
 *
 * 1. 给定一份真机形状的 SIGNAL 原始响应，输出的 **key 集合逐个相等**（不是"包含"，是相等）；
 * 2. 这些 key 全部来自 `DeviceFields.Signal`（**没有任何设备原名**漏出来，
 *    也没有未登记字段 —— allowlist 是唯一出口）；
 * 3. 两条取数路径（`preFetchedGoform` 预取 / 经 [SignalSource.getSignalInfo] 自己查）
 *    产出**同一个 key 集合** —— 后者正是本批新接上的那条缝。
 *
 * ## 夹具与期望值是怎么定的
 *
 * `zte_f50_goform_signal_raw.json` 按 `ZteGoformProfile.cmdsFor(FieldGroup.SIGNAL)` 的
 * 16 项查询逐项铺出来（NR 与部分 LTE 字段藏在 `network_information` 容器里，
 * 与固件一致），另外故意塞了一个 `unknown_firmware_field` 与一个顶层 `rssi`（信号条数
 * 0-5，**不是** dBm，不在 RSSI 的别名链里）。
 *
 * 期望 key 由三段构成，与 [SignalCollector] 的三层一一对应：
 * - 第 1 层：profile 登记且本夹具命中的 SIGNAL canonical（`lte_cell_id` 刻意没给 source
 *   → 缺失即省略 key，这一条同时守住"缺失不补 null"）；
 * - 第 1 层尾巴：`ServingCell.derive` 的 6 个派生字段（NR 优先）；
 * - 第 3 层：`network_registered` —— goform 没有这个字段，只能由 Android Telephony 补。
 *
 * 第 2 层（PCI 匹配邻区）在本夹具上**不触发**：第 1 层已经给出 `sinr` 与 `rsrq`，
 * 这正是它的进入条件（`!containsKey(SINR) || !containsKey(RSRQ)`）。夹具里仍带着
 * `neighbor_cell_info`，因为真机响应里它就在那儿。
 */
class SignalCollectorTest {

    /**
     * [SignalSource] 的测试替身：只有 [getSignalInfo] 给东西，其余 15 个方法回 null。
     *
     * 刻意手写而不是 mock：本类要证明的正是「signal 域接口**在 goform 之外**也能实现」，
     * 而一个 17 个成员的假实现就是那个证明。[calls] 用来确认 collector 真的走了这条缝。
     */
    private class FakeSignalSource(private val raw: JsonObject?) : SignalSource {
        var calls = 0

        override val profileId: String? get() = ZteGoformProfile.id

        override suspend fun getSignalInfo(): JsonObject? {
            calls++
            return raw
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

    private fun loadRaw(name: String): JsonObject =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .bufferedReader().use(BufferedReader::readText)
            .let { Json.parseToJsonElement(it).jsonObject }

    /**
     * Telephony 层的替身。本夹具下只有 `network_registered` 真的取自它
     * （前两层不含 Android 网络注册状态），其余三个 stub 是为了「万一走到就有确定值」，
     * 不是本类的断言对象。
     */
    private fun telephony(): TelephonyCollector = mockk<TelephonyCollector>().also {
        every { it.getSignalInfo() } returns emptyMap()
        every { it.getOperatorName() } returns "CHN-UNICOM"
        every { it.getNetworkType() } returns "5G"
        every { it.isNetworkRegistered() } returns true
    }

    /** 改造前后必须逐个相等的 key 集合（28 个）。全部是 `DeviceFields.Signal` 的 canonical。 */
    private val expectedKeys: Set<String> = setOf(
        // 服务小区合并值 + 基础项
        DeviceFields.Signal.RSRP,
        DeviceFields.Signal.SINR,
        DeviceFields.Signal.RSRQ,
        DeviceFields.Signal.RSSI,
        DeviceFields.Signal.RAT,
        DeviceFields.Signal.CELL_ID,
        DeviceFields.Signal.OPERATOR,
        // 5G(NR) 专属
        DeviceFields.Signal.NR_ARFCN,
        DeviceFields.Signal.NR_BAND,
        DeviceFields.Signal.NR_BAND_WIDTH,
        DeviceFields.Signal.NR_SIGNAL_STRENGTH,
        DeviceFields.Signal.NR_SNR,
        DeviceFields.Signal.NR_PCI,
        DeviceFields.Signal.NR_CELL_ID,
        // 4G(LTE) 专属（`lte_cell_id` 不在内：夹具没有 `Lte_cell_id`，缺失即省略 key）
        DeviceFields.Signal.LTE_ARFCN,
        DeviceFields.Signal.LTE_BAND,
        DeviceFields.Signal.LTE_BAND_WIDTH,
        DeviceFields.Signal.LTE_SIGNAL_STRENGTH,
        DeviceFields.Signal.LTE_SNR,
        DeviceFields.Signal.LTE_PCI,
        DeviceFields.Signal.LTE_CA_STATUS,
        // ServingCell.derive 的派生字段（NR 优先）
        DeviceFields.Signal.BAND,
        DeviceFields.Signal.BAND_LABEL,
        DeviceFields.Signal.ARFCN,
        DeviceFields.Signal.BAND_WIDTH,
        DeviceFields.Signal.SIGNAL_STRENGTH,
        DeviceFields.Signal.PCI,
        // 第 3 层专属：goform 没有这个字段
        DeviceFields.Signal.NETWORK_REGISTERED,
    )

    @Test
    fun `预取 goform 数据时输出的 key 集合与改造前逐个相等`() {
        val raw = loadRaw("zte_f50_goform_signal_raw.json")
        val collector = SignalCollector(FakeSignalSource(null), telephony(), ZteGoformProfile)

        val out = runBlocking { collector.collect(raw) }

        assertEquals(
            "对外 signal 字段名是既有契约（WS signal 频道 + /api/network/signal 共用），不许静默变化",
            expectedKeys, out.keys,
        )
        assertEquals(28, out.size)
    }

    @Test
    fun `经 SignalSource 自己查时 key 集合完全相同，且真的走了域接口`() {
        val raw = loadRaw("zte_f50_goform_signal_raw.json")
        val source = FakeSignalSource(raw)
        val collector = SignalCollector(source, telephony(), ZteGoformProfile)

        val out = runBlocking { collector.collect() }

        assertEquals(1, source.calls)
        assertEquals(expectedKeys, out.keys)
    }

    @Test
    fun `输出里没有设备原名、没有未登记字段`() {
        val raw = loadRaw("zte_f50_goform_signal_raw.json")
        val collector = SignalCollector(FakeSignalSource(null), telephony(), ZteGoformProfile)

        val out = runBlocking { collector.collect(raw) }

        // allowlist 是唯一出口：每个 key 都必须是冻结区登记的 canonical
        val unknown = out.keys - DeviceFields.Signal.ALL.toSet()
        assertTrue("出现了不在 DeviceFields.Signal 里的 key: $unknown", unknown.isEmpty())
        // 抽查四个最容易漏出来的：两个设备原名、一个容器命令、一个只该走 traffic 频道的字段
        for (deviceName in listOf("Nr_snr", "Lte_pci", "network_information", "realtime_rx_thrpt")) {
            assertTrue("设备原名 $deviceName 不许出现在 signal map 里", deviceName !in out.keys)
        }
        // 顶层 rssi 是信号条数 0-5，不是 dBm —— canonical rssi 必须来自 Nr_signal_strength
        assertEquals(-72, out[DeviceFields.Signal.RSSI])
    }

    @Test
    fun `取值形态与改造前一致：数值型给 Int、字符串型保持 String`() {
        val raw = loadRaw("zte_f50_goform_signal_raw.json")
        val collector = SignalCollector(FakeSignalSource(null), telephony(), ZteGoformProfile)

        val out = runBlocking { collector.collect(raw) }

        // NR 优先：rsrp 取 nr_rsrp 而不是 lte_rsrp
        assertEquals(-85, out[DeviceFields.Signal.RSRP])
        assertEquals(17, out[DeviceFields.Signal.SINR])
        // NCI 值域超 Int，必须保持字符串（这是 SignalCollector 按 isString 分流的理由）
        assertEquals("1234567890123", out[DeviceFields.Signal.CELL_ID])
        // 频段前缀由 core 拼，前端直接显示
        assertEquals("n78", out[DeviceFields.Signal.BAND_LABEL])
        assertEquals(301, out[DeviceFields.Signal.PCI])
        assertEquals(true, out[DeviceFields.Signal.NETWORK_REGISTERED])
    }

    @Test
    fun `buildRecord 的六列取自同一份输出`() {
        val raw = loadRaw("zte_f50_goform_signal_raw.json")
        val collector = SignalCollector(FakeSignalSource(null), telephony(), ZteGoformProfile)

        val record = runBlocking { collector.buildRecord(collector.collect(raw)) }

        assertEquals(-85, record.rsrp)
        assertEquals(17, record.sinr)
        assertEquals(-11, record.rsrq)
        assertEquals(-72, record.rssi)
        assertEquals("CHN-UNICOM", record.operator)
    }
}
