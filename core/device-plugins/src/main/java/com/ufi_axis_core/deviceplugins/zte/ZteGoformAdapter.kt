package com.ufi_axis_core.deviceplugins.zte

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSimClient
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.AclEntry
import com.ufi_axis_core.devicespi.adapter.AclSnapshot
import com.ufi_axis_core.devicespi.adapter.BandSelection
import com.ufi_axis_core.devicespi.adapter.DeviceAdapter
import com.ufi_axis_core.devicespi.adapter.DeviceControl
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.SendOutcome
import com.ufi_axis_core.devicespi.adapter.SignalSource
import com.ufi_axis_core.devicespi.adapter.SimControl
import com.ufi_axis_core.devicespi.adapter.SmsControl
import com.ufi_axis_core.devicespi.adapter.SmsMeta
import com.ufi_axis_core.devicespi.adapter.WifiControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * goform 系设备的 [DeviceAdapter] 实现（2026-09-25 批 A1 立，批 A2a 加 device 域，
 * 批 A2b 加 network / wifi 域，批 B2 加 signal 域，批 C1 加 sms 域）。
 *
 * 位置就该在本模块：`:core:device-plugins` 是**唯一**允许认识具体协议（`Goform*Client`）
 * 的模块，契约层 `:core:device-spi` 不许依赖 `:core:goform`（会成环）。
 *
 * 每个域的实现**只做一行委派**，不加任何逻辑 —— 校验、会话重登、三态判定全都还在
 * `Goform*Client` / `GoformSettingWriter` 里，本批是纯接缝迁移，行为一字不变。
 * 唯一的例外是 [BandSelection] 的翻译（见 [GoformNetwork.bandsOf]）：那不是新增逻辑，
 * 是把 `NetworkController` 原来做的那一步原样搬进来。
 *
 * 放在 `zte` 包而不是 `zte/f50`：它绑的是 **goform 协议**（ZTE 系共用），
 * 不是 F50 这一个型号。
 *
 * @param id 与 `DevicePlugin.id` 同源（`ZteF50Plugin.createAdapter` 取自它自己的属性）。
 * @param capabilities 与 `DevicePlugin.capabilities` 同源，同上。
 * @param simClient 现有的 goform SIM 客户端（批 A1 接入）。
 * @param deviceClient 现有的 goform 设备控制客户端（批 A2a 接入）。
 * @param networkClient 现有的 goform 网络控制客户端（批 A2b 接入）。
 * @param wifiClient 现有的 goform WiFi 客户端（批 A2b 接入写侧，批 C2 起**整体迁走**：
 *   7 个写方法 + `setAccessControlList` + 7 个读方法 + `lastQrCodeFailure` 全在
 *   [WifiControl] 上，装配层不再把它交给任何调用点）。
 * @param signalClient 现有的 goform 信号 / 设备信息查询客户端（批 B2 接入，**整体迁走**：
 *   它的 16 个查询方法与 `profileId` 全在 [SignalSource] 上，装配层不再把它交给任何调用点）。
 * @param smsClient 现有的 goform 短信客户端（批 C1 接入，**整体迁走**：
 *   5 个 public 方法与 3 个结论类型全在 [SmsControl] 上，装配层不再把它交给任何调用点）。
 *
 * ## 构造入口：公开的那个只收「传输层 + 两份 profile」（2026-09-25 批 A3）
 *
 * 批 A2b ~ C2 期间这里是 **6 个客户端 + id + capabilities = 8 个参数**，六个客户端由装配层
 * （`ComponentFactory.buildNetworkGraph`）`new` 出来递进来。当时刻意不收束：域接口还在
 * 一批一批长出来，先冻结一层参数容器等于每加一个域都要动它两次。
 *
 * 2026-09-25 批 A3 复核过一次并**停手**，理由是 `GoformWifiClient` 的读侧（+
 * `setAccessControlList`）与整个 sms 域**还没有域接口** —— 装配层必须把这两个具体实例交给
 * 5 个上层消费点，而没有任何 `:core:device-spi` 类型能承载它们，插件也就无法把实例交回去。
 * 让本类另造一份 `GoformWifiClient` 更不行：它带可变状态（`lastQrCodeFailure`、
 * mapper 的 warn-once 集合），造两份就是行为变化。
 * 批 C1（sms 域）与批 C2（WiFi 读侧）把这两笔债还完之后，A3 要收的只剩「`new` 搬进插件」，
 * 本批就是那一步：
 *
 * - **公开的次构造函数**收 `id + capabilities + transport + 两份 profile`，
 *   六个客户端在它的委派实参里各 `new` **一次**（`GoformClient` 那一份 transport 共享）。
 *   于是「造 goform 客户端」这件事彻底不出 `:core:device-plugins`，
 *   装配层不再需要认识任何 `Goform*` 符号（`ComponentFactory` 里那句
 *   `transport as? GoformClient` 随之移进 `ZteF50Plugin.createAdapter`）。
 * - **主构造函数仍然收六个客户端，但降成 `internal`**：它现在只服务本模块的单测
 *   （`ZteGoformAdapterBandSelectionTest` 要 mock 掉 `GoformNetworkClient` 才能把
 *   「adapter ↔ 客户端」那条边界单独暴露出来）。跨模块拿不到它 ——
 *   装配层想绕过公开入口自己造客户端是**编译不过**的。
 *
 * ⚠ 每个客户端**只许 new 一次**：`GoformWifiClient` 带 `lastQrCodeFailure`、
 * `GoformFieldMapper` 带 warn-once 集合，多一份实例就是行为变化。
 */
class ZteGoformAdapter internal constructor(
    override val id: String,
    override val capabilities: Set<Capability>,
    simClient: GoformSimClient,
    deviceClient: GoformDeviceClient,
    networkClient: GoformNetworkClient,
    wifiClient: GoformWifiClient,
    signalClient: GoformSignalClient,
    smsClient: GoformSmsClient,
) : DeviceAdapter {

    /**
     * 装配层唯一能用的入口：六个客户端在这里各 `new` 一次，全部共享同一份 [transport]。
     *
     * 参数值域与批 C2 之前装配层那六行**逐字一致**（这是本批「零行为变化」的判据）：
     * - `signal` / `wifi` 吃**双 profile** —— 可空的 [normalizeProfile] 管归一化
     *   （排障开关关掉就是 null，`/api/diagnose` 的 `normalization_enabled` 靠这个 null），
     *   非空的 [commandProfile] 管命令表；
     * - `network` / `device` / `sim` 是**纯写**客户端，只收 [commandProfile]：
     *   它们不持有 `GoformFieldMapper`，可空 profile 在那里无从生效，
     *   留一个死参数只会让人误以为「字段归一化在这三个类里起作用」；
     * - `sms` 同样只收非空那份：字段归一化可以关，短信命令表不能关
     *   （没有参数表就发不出短信），口径同 `GoformSettingWriter`。
     *
     * @param transport 插件自己造的那一份 `GoformClient`（`DevicePlugin.createTransport` 的产物）。
     *   **整图只有一份** —— 它持有会话与连接池，造第二份等于两套会话互相顶下线。
     */
    constructor(
        id: String,
        capabilities: Set<Capability>,
        transport: GoformClient,
        commandProfile: DeviceProfile,
        normalizeProfile: DeviceProfile?,
    ) : this(
        id = id,
        capabilities = capabilities,
        simClient = GoformSimClient(transport, commandProfile),
        deviceClient = GoformDeviceClient(transport, commandProfile),
        networkClient = GoformNetworkClient(transport, commandProfile),
        wifiClient = GoformWifiClient(transport, normalizeProfile, commandProfile),
        signalClient = GoformSignalClient(transport, normalizeProfile, commandProfile),
        smsClient = GoformSmsClient(transport, commandProfile),
    )

    override val sim: SimControl = GoformSim(simClient)

    override val device: DeviceControl = GoformDevice(deviceClient)

    override val network: NetworkControl = GoformNetwork(networkClient)

    override val wifi: WifiControl = GoformWifi(wifiClient)

    override val signal: SignalSource = GoformSignal(signalClient)

    override val sms: SmsControl = GoformSmsAdapter(smsClient)


    private class GoformSim(private val client: GoformSimClient) : SimControl {
        override suspend fun switchSimSlot(slot: String): WriteOutcome =
            client.switchSimSlot(slot)
    }

    private class GoformDevice(private val client: GoformDeviceClient) : DeviceControl {
        override suspend fun rebootDevice(): Boolean = client.rebootDevice()

        override suspend fun factoryReset(): Boolean = client.factoryReset()

        override suspend fun shutdownDevice(): Boolean = client.shutdownDevice()

        override suspend fun setDebugMode(enabled: Boolean): Boolean = client.setDebugMode(enabled)

        override suspend fun setIndicatorLight(enabled: Boolean): Boolean =
            client.setIndicatorLight(enabled)

        override suspend fun setPerformanceMode(mode: Int): Boolean =
            client.setPerformanceMode(mode)

        override suspend fun setSambaSetting(enabled: Boolean): Boolean =
            client.setSambaSetting(enabled)

        override suspend fun setRestartSchedule(enabled: Boolean, time: String): WriteOutcome =
            client.setRestartSchedule(enabled, time)

        override suspend fun changePassword(oldPassword: String, newPassword: String): Boolean =
            client.changePassword(oldPassword, newPassword)

        override suspend fun setFotaEnabled(enabled: Boolean): Boolean =
            client.setFotaEnabled(enabled)

        override suspend fun setDhcpSetting(
            lanIp: String, lanNetmask: String, dhcpType: String,
            dhcpStart: String, dhcpEnd: String, dhcpLease: String
        ): WriteOutcome =
            client.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)

        override suspend fun cellLock(pci: String, earfcn: String, networkType: String): WriteOutcome =
            client.cellLock(pci, earfcn, networkType)

        override suspend fun unlockAllCell(): Boolean = client.unlockAllCell()
    }

    private class GoformNetwork(private val client: GoformNetworkClient) : NetworkControl {
        override suspend fun setMobileData(enabled: Boolean): Boolean =
            client.setMobileData(enabled)

        override suspend fun setBearerPreference(preference: String): WriteOutcome =
            client.setBearerPreference(preference)

        override suspend fun connectNetwork(): Boolean = client.connectNetwork()

        override suspend fun disconnectNetwork(): Boolean = client.disconnectNetwork()

        override suspend fun setConnectionMode(mode: String): Boolean =
            client.setConnectionMode(mode)

        override suspend fun lockLteBands(bands: BandSelection): WriteOutcome =
            client.lockLteBands(bandsOf(bands, client::lteAllBands))

        override suspend fun lockNrBands(bands: BandSelection): WriteOutcome =
            client.lockNrBands(bandsOf(bands, client::nrAllBands))

        override suspend fun unlockAllBands(): Boolean = client.unlockAllBands()

        override suspend fun setDataLimit(
            enabled: Boolean,
            limitValue: Long?, limitUnit: String?,
            alertPercent: String?, autoClear: Boolean?,
            clearDate: String?
        ): WriteOutcome =
            client.setDataLimit(enabled, limitValue, limitUnit, alertPercent, autoClear, clearDate)

        override suspend fun calibrateFlow(target: String, value: String): WriteOutcome =
            client.calibrateFlow(target, value)

        override suspend fun setRoaming(enabled: Boolean): Boolean = client.setRoaming(enabled)

        private companion object {
            /**
             * [BandSelection] → goform 要的频段取值串。
             *
             * 这是 `NetworkController.lockBands` 的 unlockAll 分支**原样搬过来**的那一步：
             * 「全部频段」在 goform 上就是下发 profile 登记的频段全集掩码。
             * 掩码只在这里出现，契约层看到的只有意图（理由见 [BandSelection]）。
             *
             * profile 没登记掩码时的空串折叠 + WARN **不在这里重新实现** ——
             * 唯一的归属地是 [GoformNetworkClient.lteAllBands] / [GoformNetworkClient.nrAllBands]，
             * [allBands] 就是它们。
             */
            fun bandsOf(selection: BandSelection, allBands: () -> String): String =
                when (selection) {
                    BandSelection.All -> allBands()
                    is BandSelection.Only -> selection.bands
                }
        }
    }

    /**
     * wifi 域：`GoformWifiClient` 的**全部 public 成员**都在这里委派（批 C2）。
     *
     * 批 A2b 时这里只有 7 个写方法，读方法（WiFi 状态 / 热点信息 / 已连客户端 / 二维码 /
     * 接入控制名单）与 `setAccessControlList` 留在客户端上、由装配层直接交给读侧调用点。
     * 批 C2 把它们一并迁进 [WifiControl]（`AclEntry` / `AclSnapshot` 随之搬到契约层），
     * 于是装配层不再把这个客户端交给任何上层消费点。
     *
     * 一行逻辑都不加 —— 分批查询的命令表、归一化闸门、二维码候选文件名与字节头校验、
     * 名单的分号串拆装全都还在 `GoformWifiClient` / `GoformSettingWriter` / profile 里。
     */
    private class GoformWifi(private val client: GoformWifiClient) : WifiControl {
        override suspend fun setWifiConfig(
            ssid: String?,
            authMode: String?,
            encrypType: String?,
            passphrase: String?,
            maxStaNum: Int?,
            broadcastDisabled: Int?,
            chipIndex: String?
        ): WriteOutcome =
            client.setWifiConfig(
                ssid, authMode, encrypType, passphrase, maxStaNum, broadcastDisabled, chipIndex
            )

        override suspend fun setWifiPower(level: Int): Boolean = client.setWifiPower(level)

        override suspend fun setWifiSSID(ssid: String): Boolean = client.setWifiSSID(ssid)

        override suspend fun setWifiEnabled(enabled: Boolean): Boolean =
            client.setWifiEnabled(enabled)

        override suspend fun setWifiBand(chip: String): WriteOutcome = client.setWifiBand(chip)

        override suspend fun setWifiPassword(password: String): Boolean =
            client.setWifiPassword(password)

        override suspend fun setWifiSleep(time: String): WriteOutcome = client.setWifiSleep(time)

        override suspend fun setAccessControlList(
            black: List<AclEntry>,
            white: List<AclEntry>,
            mode: String?
        ): WriteOutcome = client.setAccessControlList(black, white, mode)

        override suspend fun getAccessControlList(): AclSnapshot? = client.getAccessControlList()

        override suspend fun getWifiModuleInfo(): JsonObject? = client.getWifiModuleInfo()

        override suspend fun getWifiSettings(): JsonObject? = client.getWifiSettings()

        override suspend fun getWifiSettingsMerged(): NormalizedFields =
            client.getWifiSettingsMerged()

        override suspend fun getConnectedClients(): NormalizedFields? = client.getConnectedClients()

        override suspend fun getCurrentWifiConfig(): Map<String, String> =
            client.getCurrentWifiConfig()

        override suspend fun getWifiQrCode(chip: String, ssidIndex: Int): Pair<ByteArray, String>? =
            client.getWifiQrCode(chip, ssidIndex)

        override val lastQrCodeFailure: String get() = client.lastQrCodeFailure
    }

    /**
     * signal 域：**只有读方法**，`GoformSignalClient` 的 16 个查询一个不少地在这里委派。
     *
     * ⚠ 第 1 层字段映射（设备原名 → canonical）**不在这里** —— 它在消费方
     * `SignalCollector`，理由见 [SignalSource] 的类 KDoc。本类一行逻辑都不加，
     * 归一化、脱敏、覆盖率报告全都还在 `GoformSignalClient` / `GoformFieldMapper` 里。
     */
    private class GoformSignal(private val client: GoformSignalClient) : SignalSource {
        override val profileId: String? get() = client.profileId

        override suspend fun getSignalInfo(): JsonObject? = client.getSignalInfo()

        override suspend fun getConnectionInfo(): NormalizedFields? = client.getConnectionInfo()

        override suspend fun getNetworkInformation(): JsonObject? = client.getNetworkInformation()

        override suspend fun getDeviceInfo(): JsonObject? = client.getDeviceInfo()

        override suspend fun getDeviceIdentity(): NormalizedFields? = client.getDeviceIdentity()

        override suspend fun getDeviceVersion(): NormalizedFields? = client.getDeviceVersion()

        override suspend fun getTrafficStats(): JsonObject? = client.getTrafficStats()

        override suspend fun getFullStatus(): JsonObject? = client.getFullStatus()

        override suspend fun getFullStatusMasked(): JsonObject? = client.getFullStatusMasked()

        override suspend fun diagnoseFieldCoverage(): JsonObject = client.diagnoseFieldCoverage()

        override suspend fun getCellInfo(): NormalizedFields? = client.getCellInfo()

        override suspend fun getNeighborCellInfo(): JsonArray? = client.getNeighborCellInfo()

        override suspend fun getLanSettings(): NormalizedFields? = client.getLanSettings()

        override suspend fun queryDeviceSettings(): NormalizedFields? = client.queryDeviceSettings()

        override suspend fun getBandLockStatus(): NormalizedFields? = client.getBandLockStatus()

        override suspend fun getDataUsage(): NormalizedFields? = client.getDataUsage()
    }

    /**
     * sms 域：`GoformSmsClient` 的 5 个 public 方法一个不少地在这里委派。
     *
     * 类名刻意是 `GoformSmsAdapter` 而不是 `GoformSms`：后者与被委派的
     * `GoformSmsClient` 只差一个 `Client`，在这个文件里读起来分不清哪个是壳哪个是实现。
     *
     * 一行逻辑都不加 —— 参数表、登录前置、发完回读确认的循环、日志脱敏全都还在
     * `GoformSmsClient` / `ZteSmsSpec` 里。`maskNumber` 是那边的 `internal` 工具函数，
     * 不在本域接口上（理由见 [SmsControl] 的类 KDoc）。
     */
    private class GoformSmsAdapter(private val client: GoformSmsClient) : SmsControl {
        override suspend fun getSmsList(page: Int, perPage: Int): JsonObject? =
            client.getSmsList(page, perPage)

        override suspend fun sendSms(phoneNumber: String, message: String): SendOutcome =
            client.sendSms(phoneNumber, message)

        override suspend fun deleteSms(msgId: String): Boolean = client.deleteSms(msgId)

        override suspend fun markSmsRead(msgId: String, read: Boolean): Boolean =
            client.markSmsRead(msgId, read)

        override suspend fun getSmsMeta(): SmsMeta? = client.getSmsMeta()
    }
}
