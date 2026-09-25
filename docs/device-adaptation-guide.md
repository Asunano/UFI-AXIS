# 设备适配指南

> 回答一个问题：**接一台新的随身 WiFi / UFI 设备，要改哪些地方。**
>
> 这份文档描述**代码现状**，不是计划。每条判据旁边都标了它成立的时间点（如「2026-09-25 批 A3 起」），
> 看到与代码不符的地方**以代码为准**。
> 改造的过程与被否掉的方案在 `docs/device-plugin-framework-plan.md`，两份文档的分工是：
> 那份记「为什么这么设计、哪些方案被否掉了」，这份记「照着做」。

---

## 0. 能力边界：现在什么是协议无关的，什么还是 goform 专有的

**装配层与上层已经协议无关**（2026-09-25 批 A3 起）。`ComponentFactory.buildNetworkGraph()`
现在只认两个契约类型：

```kotlin
val transport = runtime.plugin.createTransport(
    TransportConfig(
        deviceIp = deviceIp,
        port = settings.goformPort,
        password = settings.goformPassword
    )
)
val deviceHub = DeviceHub(
    runtime.plugin.createAdapter(
        transport = transport,
        commandProfile = runtime.commandProfile,
        normalizeProfile = runtime.profile,
    )
)
```

旧版文档写的那句 `val goform = transport as? GoformClient ?: error("…装配层只支持 goform 系插件")`
**已经不在装配层了** —— 它搬进了 `ZteF50Plugin.createAdapter`，也就是**造 transport 的那个插件自己**
（「谁造的谁认识」）。六个 `Goform*Client` 的 `new` 同样搬进了插件
（`ZteGoformAdapter` 的公开构造函数）。所以：

- **接 goform 系新设备**（另一个型号 / 另一家 OEM 同协议）→ 一个 profile + 一个 plugin，
  adapter 直接复用 `ZteGoformAdapter`。
- **接非 goform 设备**（飞猫等）→ **框架支持了**：自己写一份 `DeviceAdapter`（六个域），
  在自己的 `createAdapter` 里造自己的客户端。装配层一行都不用改。

### 还剩下的 goform 专有面（接第二种协议前必须知道）

| # | 事实 | 它是不是缺口 |
| --- | --- | --- |
| 1 | 六个 `Goform*Client` + `GoformSettingWriter` + `GoformTransport` 只住在 `:core:goform`，只有 `:core:device-plugins` 认识它们 | **不是缺口，是设计**。守门测试 `GoformTransportVisibilityGuardTest` 钉住这条 |
| 2 | 5 处**契约泄漏**：域接口的签名上带着设备侧词汇（见第 5 节） | **是缺口**，但改它要动对外契约。接第二种协议前先在那 5 处立中立别名 |
| 3 | `DeviceTransport` 的 14 个方法是「HTTP + 批量 cmd 读 + 参数 map 写 + 5 个会话动作」的形状（`read(List<String>)` / `write(Map<String,String>)`） | **半个缺口**。第二种协议要么塞得进这个形状，要么先扩契约（只做加法） |
| 4 | 命名遗留：`GoformQoS` / `GoformSessionLog`（住 `:core:common`，是**出向 HTTP 通道**的 QoS 与日志，与协议无关）、`AppSettings` 的 `qosGoform*` 配置键、`DeviceTransport.updateGoformPassword`、各模块自己的局部名（`GoformNetworkInfo` / `preFetchedGoform` / `etGoformIp` …） | **不是缺口**。改名会动配置键与对外字段，登记在 `UpperLayerGoformFreeGuardTest.ALLOWED` 的 A~D 组 |
| 5 | 安装器（`scripts/UFI-AXIS-Core-install-Android/goform/`，package `com.ufi_axis.installer.goform`）有**独立的第二套 goform 实现**，零 profile 抽象 | **刻意不纳入插件化**（一次性装机流程、不共享组件图）。接新设备时它要另写一套 |

「上层不许出现 `Goform`」这条现在有测试守着（`UpperLayerGoformFreeGuardTest`，2026-09-25 批 A4）：
`core/api`、`core/collector`、`core/scheduler`、`core/controller`、`core/src` 五处的**真代码**
（剥掉注释与字符串字面量之后）里，含 `Goform` 的标识符必须与那张 allowlist **逐文件精确相等**。
装配层那一组（原来 8 个符号）在批 A3 已清零。

---

## 1. 分层地图：什么东西该放在哪一层

| 模块 | 放什么 | 硬约束 |
| --- | --- | --- |
| `core/contract` | 对外**冻结区**：canonical 字段名、错误码、`Capability`（11 项）、端点常量 | 只增不改。删要先 `@Deprecated` 一版（**零引用项例外**：全仓 grep 确认无消费者可直接删） |
| `core/device-schema` | `DeviceProfile` / `SmsSpec` / `SettingKey`（29 项）/ `WriteSpec` / `FieldSpec` / `FieldNormalizer` / `NormalizedFields`，以及**各设备的 profile 实现**（`profile/ZteGoformProfile.kt` 等） | **纯 JVM**，只依赖 `:core:contract`（`build.gradle.kts` 写明：不许依赖 `:core:common` / `:core:goform`） |
| `core/device-spi` | 插件**契约层**：`DevicePlugin` / `DeviceTransport` / `TransportConfig` / `PlatformAdapter` / `AtTransport` / `CpuInfoPlatform` / `DeviceTuning` / `ProbeEnv` + `BuildInfo` / `DeviceRuntime` / `WriteOutcome`，以及 `adapter/` 下的 `DeviceHub` / `DeviceAdapter` / 六个域接口 / `BandSelection` / `AclEntry` / `AclSnapshot` / `SendVerdict` / `SendOutcome` / `SmsMeta` | **不得依赖任何具体协议实现**（`goform` / `collector` / `controller` 一个都不许 —— goform 反过来依赖它，加进来立刻成环）。是 Android library（`PlatformAdapter.platform(ctx)` 要 `Context`） |
| `core/device-plugins` | **插件实现**：`PluginRegistry` + 每设备一个 package（`zte/f50/`）+ 协议 adapter（`zte/ZteGoformAdapter.kt`）+ 平台实现（`platform/sprd/`）+ `probe/ProbeEnvCollector` | 一个 module、每设备一个 package。**不做「一设备一 Gradle module」**。它是**唯一**允许同时认识契约层与具体协议的模块 |
| `core/goform` | goform 协议实现：`GoformClient`（实现模块内那层 `GoformTransport`）+ 6 个客户端 + `GoformSettingWriter` + `GoformFieldMapper` | 对 `device-spi` 与 `device-schema` 都必须是 `api(...)`（`NormalizedFields` / `DeviceTransport` 上了公开签名） |
| `core/collector` `core/controller` `core/scheduler` `core/api` | 采集 / 控制 / 调度 / route。**不该有设备知识** | 这些层里出现设备字段名、命令名、值域，就是欠账。它们只依赖 `:core:device-spi`（+ schema），**不许**依赖 `:core:device-plugins` |
| `core/src`（`ComponentFactory`） | **唯一** `implementation(project(":core:device-plugins"))` 的地方 | 装配层是唯一知道「有哪些插件」的地方 |

依赖方向以各模块 `build.gradle.kts` 为准，核过的几条：
`device-schema → contract`；`device-spi → api(contract) + api(device-schema)`；
`goform → api(device-spi) + api(device-schema) + common`；
`device-plugins → api(device-spi) + device-schema + goform + common`；
`core（装配层）→ device-plugins`（全仓仅此一处）。

一句话：**设备知识往 `device-schema` 与 `device-plugins` 走，平台知识往 `PlatformAdapter` 走，
其余各层只认接口。**

---

## 2. 接一台新设备：最小路径

四件东西 + 一行注册：**一个 `DeviceProfile`、一个 `DevicePlugin`、一个 `DeviceAdapter`、
（平台不同时）一个 `PlatformAdapter`**，然后 `PluginRegistry.ALL` 加一项。

### 2.1 写 profile（`core/device-schema/.../profile/`）

新建 `XxxProfile.kt`，实现 `DeviceProfile`（参考 `ZteGoformProfile`，1695 行，
**大部分是命令表与字段表，不是逻辑**）。**13 个成员，其中 8 个有默认实现**
（下表 12 行 —— `lteAllBandsMask` / `nrAllBandsMask` 合并成一行了）：

| 成员 | 作用 | 可省？ |
| --- | --- | --- |
| `id` | profile 标识（如 `"zte-goform"`），会出现在 `/api/diagnose` 的 `active` 上 | 否 |
| `displayName` | 人读名 | 否 |
| `readSpecs()` | 字段表：canonical 名 ← 设备原名（+ 单位换算、解码器） | 否 |
| `cmdsFor(group)` | 每个 `FieldGroup`（10 组）要发哪几条读命令 | 否 |
| `writeSpec(key)` | `SettingKey` → 写命令（命令名 + `encode` + `validate` + `retry` + `fallback` + `commandOf`） | 可（返回 null = 该设备不支持这个写动作） |
| `soloCmds(group)` | 必须单发的命令（不能与同组别的合批）。F50 只有 `WIFI_CLIENTS` 的 `station_list` | 可（默认空） |
| `fullStatusCmds()` | 全量 dump 的**分批**命令表（`List<List<String>>`，外层就是批次边界；F50 是三批共 96 项） | 可（默认空 = 该设备不提供 dump） |
| `structuralDecoder(group)` | 结构解码器：把嵌套 / 双重编码的响应摊平成扁平 JsonObject。**必须是纯函数** | 可（默认 null） |
| `lteAllBandsMask()` / `nrAllBandsMask()` | 「解锁全部频段」时下发的全集掩码。⚠ **`null` ≠ 空串**：`null` = 该设备不需要显式下发全集，调用方折叠成空串（= 不发限制）并打一条 WARN | 可（默认 null） |
| `aclDefaultMode()` | 设备**没上报** ACL mode 字段时按哪个档位理解（F50 = `"2"` 黑名单）。`null` = 保持空值原样往上传，**不许自己编一个档位** | 可（默认 null） |
| `qrCodeFileNames(chip, ssidIndex)` | WiFi 二维码图的候选文件名（按尝试顺序，**实现方自己去重**）。空列表 = 该设备不支持从后台取图 | 可（默认空） |
| `smsSpec()` | 短信规格（`sendParams` / `listQuery` / `deleteParams` / `markReadParams` / `sentTag` / `failedTag` / `encodeBody`） | 可（返回 null = 不支持短信） |

⚠ `aclDefaultMode()` 是 2026-09-25 新增的成员，它的存在理由值得一读：此前 `GoformWifiClient`
两处直接写 `ZteGoformProfile.ACL_MODE_BLACKLIST`，绕过了 `commandProfile` 这条唯一通道 ——
换设备后仍然拿 ZTE 的 `"2"` 兜底，而 `"2"` 在别家设备上可能是白名单、也可能压根没有这个字段。
结果是界面显示「黑名单生效」而设备其实在放行所有人，**这种错没有任何测试会变红**。

⚠ `SettingKey` 是**冻结区里的枚举，实际 29 项**。新设备如果有独有的写动作，
先问「这是不是已有 key 的变体」，确实是新动作才加 key —— 加了就是所有 profile 都要面对的新成员。
短信**刻意不走** `SettingKey`（`encode` 必须是纯函数而 `sms_time` 要读时钟、下发后还有回读确认、
编码是短信专有的），所以它有独立的 `smsSpec()`。

### 2.2 写 plugin（`core/device-plugins/.../<厂>/<型号>/`）

**9 个成员，一个不能少**（`id` / `displayName` / `capabilities` / `profile` / `createTransport` /
`createAdapter` / `platform` / `tuning` / `probe`；签名与 `DevicePlugin.kt` 逐字对齐，照抄能编译）：

```kotlin
object XxxPlugin : DevicePlugin {

    override val id = "vendor-model"              // 稳定标识，进日志、进 /api/diagnose 的 plugin_id
    override val displayName = "Vendor Model"

    override val capabilities: Set<Capability> = setOf(
        // 见 2.4。必须 setOf(...) 这种不可变集合，且不许在这里 new 别的东西
    )

    override fun profile(): DeviceProfile = XxxProfile

    override fun createTransport(cfg: TransportConfig): DeviceTransport =
        XxxClient(deviceIp = cfg.deviceIp, port = cfg.port, password = cfg.password)

    override fun createAdapter(
        transport: DeviceTransport,
        commandProfile: DeviceProfile,
        normalizeProfile: DeviceProfile?,
    ): DeviceAdapter {
        // 转型是合法的：transport 就是本插件 createTransport 造的那一份
        val client = transport as? XxxClient ?: error(
            "插件 $id 收到的传输层不是 XxxClient（实际是 ${transport::class.java.name}）—— " +
                "说明有人换掉了本插件的 transport 实现"
        )
        return XxxAdapter(
            id = id,
            capabilities = capabilities,
            transport = client,
            commandProfile = commandProfile,
            normalizeProfile = normalizeProfile,
        )
    }

    override fun platform(ctx: Context): PlatformAdapter = SprdPlatform()   // 或你自己的

    override fun tuning(): DeviceTuning = DeviceTuning(
        downloadThrottleWarnC = 0f,               // 见 2.5，全部是实测值
        downloadThrottleCriticalC = 0f,
        downloadThrottleForcePauseOffsetC = 0f,   // 必须 > 0
        thermalJitterC = 0f,
        bootGraceMs = 0L,
    )

    override suspend fun probe(env: ProbeEnv): Int {
        // 见 2.6：只读 env，零 I/O
        return 0
    }
}
```

三条容易踩的：

1. **`profile()` 与 `capabilities` 不许在里面 new** —— `PluginContractTest` 断言多次调用返回同一个对象。
2. **`createAdapter` 里不许再调 `createTransport()`** —— 整图只许有一份传输层实例
   （它持有会话 cookie、版本号、登录退避与 HTTP 连接池，造第二份等于两套会话互相顶下线）。
   装配层把自己造的那一份递进来，同一个实例还要进 `ComponentGraph.NetworkGraph.goformClient`
   （改设备后台口令走的就是它）。
3. **`normalizeProfile` 可空，不许用非空兜底把它填上** —— `/api/diagnose` 的
   `normalization_enabled` 就是从「它是不是 null」推出来的，兜底之后那个排障开关会永远报 `true`。
   而 `commandProfile` **永远非空**：字段归一化可以关，命令表不能关。

### 2.3 写 adapter（六个域）

`DeviceAdapter` 的 8 个成员：`id` / `capabilities` + `sim` / `device` / `network` / `wifi` /
`signal` / `sms`。**六个域字段全部非空** —— 判据只有一份，在 `capabilities`（理由见第 10 节反模式 1）。

goform 系的实现形态（`ZteGoformAdapter`）值得照抄：

- **公开构造函数**收 `id + capabilities + transport + commandProfile + normalizeProfile`，
  六个协议客户端在它的委派实参里各 `new` **一次**；
- **主构造函数收六个客户端但降成 `internal`**，只服务本模块单测
  （`ZteGoformAdapterBandSelectionTest` 要 mock 掉客户端才能把「adapter ↔ 客户端」那条边界单独暴露）。
  跨模块拿不到它 —— 装配层想绕过公开入口自己造客户端是**编译不过**的；
- 六个域各是一个 `private class`，**每个方法只做一行委派**，不加任何逻辑
  （校验、会话重登、三态判定、归一化全在客户端与 profile 里）。

`ZteGoformAdapter` 公开构造函数的逐字签名：

```kotlin
constructor(
    id: String,
    capabilities: Set<Capability>,
    transport: GoformClient,
    commandProfile: DeviceProfile,
    normalizeProfile: DeviceProfile?,
)
```

它内部的六行 `new`（双 profile 的分工在这里一眼看全）：

```kotlin
simClient = GoformSimClient(transport, commandProfile)
deviceClient = GoformDeviceClient(transport, commandProfile)
networkClient = GoformNetworkClient(transport, commandProfile)
wifiClient = GoformWifiClient(transport, normalizeProfile, commandProfile)
signalClient = GoformSignalClient(transport, normalizeProfile, commandProfile)
smsClient = GoformSmsClient(transport, commandProfile)
```

**纯写的三个（sim / device / network）只收 `commandProfile`**：它们不持有字段映射器，
可空 profile 在那里无从生效，留一个死参数只会让人误以为「字段归一化在这三个类里起作用」。
`sms` 同理（没有参数表就发不出短信）。

⚠ **每个客户端只许 new 一次**：`GoformWifiClient` 带 `lastQrCodeFailure`、
`GoformFieldMapper` 带 warn-once 集合，多一份实例就是行为变化。

`DeviceHub` 是上层唯一认识的类型（8 个成员：`adapterId` / `capabilities` + 六个域）。
它现在**只有转发** —— 没有缓存、没有重试、没有日志、没有能力校验（门禁在 route 层）。
它存在的两个理由：① 上层只依赖这一个类型，换 adapter 时改动落在装配层那一处；
② 后续跨设备的公共口径（写操作日志、能力集出口）有一个地方可落。

### 2.4 声明 capabilities

`Capability` 当前 **11 项**。**只声明这台设备真正支持的**：

- **10 个写侧域**：`SMS` / `SIM_SLOT_SWITCH` / `BAND_LOCK` / `CELL_LOCK` / `NETWORK_MODE` /
  `SAMBA` / `USB_DEBUG` / `FOTA` / `PERFORMANCE_MODE` / `TRAFFIC_LIMIT`
  → 声明了就必须在 profile 里有对应的 `WriteSpec`（`SMS` 是 `smsSpec() != null`），
  `PluginContractTest` **单向**断言这一条。
- **1 个纯读侧域**：`BATTERY`（这个型号**出厂带不带电池**）。
  它没有写 route、将来也不会有（电池是硬件，不存在「开关电池」），
  所以进 `CapabilityGateTest.exempt` 的永久豁免与 `PluginContractTest.READ_ONLY_CAPABILITIES` 白名单。

⚠ **不声明 `BATTERY` 的后果是「读数照发、可信度另说」**（2026-09-24 批 M 推翻了批 L 的「抹成 -1」）：
`percent` 就是系统报的值（F50 上恒为 50），battery map 里多一个 `supported=false`；
变的是 `DataScheduler` **不入库、不判电池告警**。
F50 就是不声明的 —— 它**读得到**一个恒为 50 的假值，任何「读一次看能不能读到」的设计在它上面都会得出错误结论。

不加反向断言（「有 `WriteSpec` 就必须有 capability」）：`Capability` 是**功能域**（11），
`SettingKey` 是**写入项**（29），照反向断言写出来一上线必红，而且它逼着人给每个 key 编一个域。
没有 capability 的写操作照旧不拦。

**不该进 capabilities 的东西**：任何**运行时会变**的可用性（AT 通道通不通、root 通道在不在）。
那些由 `/api/at/status` 的 `connected` 与 shell 的 root 上报实时表达 ——
塞进这个静态集合就是「假开关」（集合说能用、实际早断了）。

### 2.5 填 tuning

5 个字段，**全部是设备实测值**，每个的 KDoc 都写明了「哪些近名阈值不归它管」与消费点：

| 字段 | 管什么 | 谁在读 | F50 |
| --- | --- | --- | --- |
| `downloadThrottleWarnC` | 下载限速一档（砍并发 + 全局限速） | `DownloadManager` | 75f |
| `downloadThrottleCriticalC` | 下载限速二档（更狠一档，**还不是暂停**） | `DownloadManager` | 85f |
| `downloadThrottleForcePauseOffsetC` | 相对 critical 的偏移量，越线 = **暂停全部下载**。**必须 > 0**（`PluginContractTest` 守着；填 0 等于删掉第 3 档） | `DownloadManager` | 10f（即 95°C） |
| `thermalJitterC` | **用户告警**的回差（不是限速的）。零回差 + 边沿触发 = 阈值附近微抖导致告警风暴 | `AlertEngine(temperatureHysteresisC)` | 3f |
| `bootGraceMs` | 预热期：不入库、不判本地告警（WS 推送照常） | `DataScheduler(bootGraceDeviceDefaultMs)` | 90_000L |

后两个由 `ComponentFactory` 递成**单个数值**而不是整个 `DeviceTuning`，这是刻意的：
`:core:alert` / `:core:scheduler` 不该认识插件层，而且把整包递进去就等于把三套同名不同义的
温度阈值摆进同一个作用域。

⚠ **最容易踩的坑**：同一个「温度阈值」概念在仓里是**三件不同的事** ——
下载限速 75/85（本类，`Float`，摄氏度）、采集降频/熔断 70/80（`AppSettings.monitorThermal*`，
**`Int` 毫摄氏度**，用户可配）、用户告警 65/75（`AlertEngine`，`Double`，带 3°C 回差，用户可配）。
**`downloadThrottleWarnC = 75f` 与 `AlertEngine.temperatureCritical = 75.0` 数值相同、语义相反。**
看到「两边都是 75」就接线 = 静默改掉告警行为，**单测不会红**。

`downloadThrottle*` 只作为**首次创建配置时的默认值**与 `migrateConfig()` 的**抬升目标值**；
用户一旦写过 `PUT /api/download/config`，以用户配置为准。

### 2.6 写 probe()

```kotlin
override suspend fun probe(env: ProbeEnv): Int
```

给这台设备打分，**分数最高者被选中**。约束：

- **只读 `ProbeEnv` 里已备好的廉价指纹**：`cpuInfoPlatform`（已 trim + 小写的 `/proc/cpuinfo`
  **全文**）、`androidBuild`（`BuildInfo`：brand / model / device / manufacturer / sdkInt）、
  `goformLdReachable`。
- **禁止 I/O**：不发 AT、不登录设备、不读文件、不发请求。采集已经在 `ProbeEnvCollector` 里做过了
  （装配层采**一次**后共享给所有插件 —— 各插件读到的值必须一致，否则打分之间没有可比性）。
- **平台判据不要自己抄**：用 `CpuInfoPlatform.isSpreadtrum` / `isQualcomm`
  （marker 是 `sprd`/`spreadtrum`/`unisoc` 与 `qualcomm`/`qcom`，大小写不敏感）。
  那个类存在的唯一理由就是 4.6 消掉了两份不一致的 marker 列表。
- 返回 **0 = 不适用**，越大越匹配。负分同样当不适用。
- **不许猜**：没有实测依据的判据不要写。`ZteF50Plugin` 刻意**不做** `Build.*` 匹配 ——
  全仓拿不到 F50 的真机取值，编一个只会得到恒不命中的死代码，或者更糟：命中别家设备。
  它的两条判据是 `goformLdReachable`（60 分，**唯一准入条件**）+ 展锐 marker（+20，只加分），
  满分 80，留出的余量给以后的 `Build.*`。
- **并列同分**取 `PluginRegistry.ALL` 里声明顺序靠前者 + 一条 WARN
  （并列意味着判据不足以区分，该补判据）。
- `probe()` 抛异常按 0 分处理 + WARN，**整轮选型继续**；`CancellationException` 原样抛出去。

`ProbeEnv.goformLdReachable` 为什么是整套自动选型的关键：想靠读设备版本字段认设备，
可**读这些字段本身就需要一个 profile**（鸡生蛋）。`LD` 免登录、免 profile，
一个固定 cmd 名就能取，于是绕开了那个死循环。采集实现是裸 `HttpURLConnection`、
硬超时 1.5s、**不做** `GoformClient` 那套 localhost 回落（启动路径上只许发一次）。

### 2.7 注册

`PluginRegistry`（**新增一台设备后唯一需要改的公共代码**）：

```kotlin
val ALL: List<DevicePlugin> = listOf(ZteF50Plugin, XxxPlugin)
val DEFAULT: DevicePlugin = ZteF50Plugin    // 认不出设备时的兜底
```

**编译期注册**，不做运行时动态加载（DexClassLoader / 反射扫 dex / ServiceLoader 都被明确否掉了：
单 APK 交付，那一套换来的是签名校验、保留规则、R8 裁剪、崩溃归因全套代价）。
`DEFAULT` 的存在理由：认不出型号**不能**导致整个后端不工作。

### 2.8 选型是怎么发生的

`DeviceRuntime.resolve(plugins, default, configuredId, normalizationEnabled, probeEnv, warn, info)`，
五条互斥路径、顺序即优先级：

1. `field_normalization_enabled = false`（排障开关）→ 先打一条 WARN、`profile` 置 null，
   **但插件照样选、probe 照样跑**（归一化能关，传输层与命令表不能关）。
2. `device_profile_id` 非空且匹配 → `CONFIGURED`。**匹配两种 id，先新口径后旧口径**：
   先 `plugin.id`（`zte-f50`），再 `plugin.profile().id`（`zte-goform`，**老部署填的就是这个**）；
   都按 trim + 大小写不敏感比。命中旧口径打 **INFO 而不是 WARN**（用户填的值是对的）。
   ⚠ 这条命中时**一次 `probe()` 都不发** —— 配置永远是最高优先级。
3. 两种 id 都匹配不上 → `FALLBACK`，回落 `DEFAULT` + WARN（「可选」清单两种 id 都列）。
   同样**不发 probe**：填错了就该看见那条 WARN。
4. `device_profile_id` 空白 → **probe 打分**。最高分 > 0 → `PROBED` + INFO；
   全部 ≤ 0 → `DEFAULT`（**不是** `FALLBACK` —— 没人配错），且**这条路径刻意不打日志**
   （LD 探不通时 `ProbeEnvCollector` 已经打过一条 WARN，再补一条只是重复噪音）。
5. `profile` 是否为 null 只由排障开关决定，与选中谁无关。

选型**只在组件图构造时发生一次**，改配置要重启后台服务。**不做运行时热切换**
（所以 `DeviceRuntime` 刻意没有 `switchPlugin()`；组件图重建时各级缓存都是新的）。

---

## 3. 六个域接口逐个说

方法数与返回类型口径（逐个数过，2026-09-25 批 C2 之后的形态）：

| 域 | 接口 | 成员数 | 返回类型口径 |
| --- | --- | --- | --- |
| sim | `SimControl` | 1 方法 | `switchSimSlot(slot): WriteOutcome` |
| device | `DeviceControl` | 13 方法（全是写） | 10 个 `Boolean` + 3 个 `WriteOutcome`（`setRestartSchedule` / `setDhcpSetting` / `cellLock`） |
| network | `NetworkControl` | 11 方法（全是写） | 6 个 `Boolean` + 5 个 `WriteOutcome`（`setBearerPreference` / `lockLteBands` / `lockNrBands` / `setDataLimit` / `calibrateFlow`） |
| wifi | `WifiControl` | 15 方法 + 1 属性 | 写 8：4 `Boolean` + 4 `WriteOutcome`；读 7：2 裸 `JsonObject?`、1 `NormalizedFields`（**非空**）、1 `NormalizedFields?`、1 `AclSnapshot?`、1 `Map<String,String>`、1 `Pair<ByteArray,String>?`；属性 `lastQrCodeFailure: String` |
| signal | `SignalSource` | 16 方法 + 1 属性 | **只有读**：8 个 `NormalizedFields?`、7 个 `JsonObject` / `JsonObject?`、1 个 `JsonArray?`；属性 `profileId: String?` |
| sms | `SmsControl` | 5 方法 | `getSmsList → JsonObject?`、`sendSms → SendOutcome`、`deleteSms` / `markSmsRead → Boolean`、`getSmsMeta → SmsMeta?` |

六个域是**一批一个地长出来**的：`sim`（批 A1）、`device`（批 A2a）、`network` / `wifi`（批 A2b）、
`signal`（批 B2）、`sms`（批 C1），wifi 读侧与 `setAccessControlList`（批 C2）。
每批加一个、连同实现与调用点一起落地，**不预先声明空成员** ——
声明了没人实现的成员就是死代码。加第七个域时沿用这条口径。

### 为什么返回类型不齐整（别顺手统一）

这是**刻意保留的既有形状**，每一处的判据都一样：统一它就不是「纯接缝迁移」，
而是改 route 的响应形状 —— 那是行为变更。

- **`Boolean` vs `WriteOutcome`**：回 `WriteOutcome` 的那些是**已经接了值域校验三态**的
  （`Rejected` = 参数没过校验、根本没发请求 → route 回 400 `OUT_OF_RANGE` 而不是 500）。
  其余仍是两态 `Boolean`，因为它们对外就只有两态。`WriteOutcome` 的四态是
  `Ok` / `Rejected(reason)` / `Failed` / `Unavailable(reason)`，分界是
  **「固件有没有收下这条命令」**：`Failed` 不可重试（设备表过态了），
  `Unavailable` 可重试 → route 该回 503 而不是 500。
- **`NormalizedFields` vs 裸 `JsonObject`**：前者 = **过了归一化闸门**（第 4 节），
  后者 = **原样透传、形状不同、或本身就是一份报告**，每一个都在自己的 KDoc 里写明是哪一种。
  把透传那几个升成 `NormalizedFields` 是**谎报**（里面确实有设备原名）；
  把 8 个里任何一个降成裸 `JsonObject` 会丢掉刚立起来的类型保证。
  典型的三种「合理的不合理」：
  - `getTrafficStats()` 是**混合体**（设备原始响应 + 归一化结果覆盖上去，为了留住没登记的
    `realtime_*`），所以是 `JsonObject`；需要纯归一化出口的是 `getDataUsage()`。
  - `getNeighborCellInfo()` **归一化过**，但它从结果里取出**一个成员**再返回 ——
    出去的是数组本身，套 `NormalizedFields`（「一份字段对象」）名不副实，所以是 `JsonArray?`。
  - `getFullStatusMasked()` 只做**脱敏**：脱敏 ≠ 归一化，`maskDump` 把敏感值换成 `***`、
    **字段名一个都不动**，而且它连闸门都不走（profile 为 null 时原样返回）。
- **`getWifiSettingsMerged()` 是唯一的非空契约**：两个查询都失败时给 `NormalizedFields.EMPTY`
  （内容就是 `{}`）。那不是后门 —— 它不接受任何入参，一个空对象里没有字段名可言。

### 三个协议无关的结论类型为什么住在契约层

`SendVerdict` / `SendOutcome` / `SmsMeta` 原本嵌在 `GoformSmsClient` 里，`AclEntry` / `AclSnapshot`
原本在 goform 模块 —— 它们的语义是协议无关的，而且出现在域接口的签名上，所以搬到了
`:core:device-spi/adapter/`（字段名、枚举值名与 KDoc 逐字未变）。

`SendVerdict` 的五档里 **`REJECTED` 与 `NO_RESPONSE` 必须分开**，判据只有一个：
**我们知不知道设备没收到这条发送请求**。知道没收到 → 重试安全；不知道 → 重试可能是第二条真短信、
第二笔话费。所以 `REJECTED` 是唯一**可重试**且**不计配额**的一档，`NO_RESPONSE` 反过来
（不可重试、但配额要计）。

`BandSelection`（`All` / `Only(bands)`）收的是**意图**而不是掩码串：
「下发给设备的频段全集掩码」是 goform 的实现细节，放到协议无关的契约上等于规定
「别人家的设备也得用掩码串表达全部频段」。翻译只在 adapter 里一行
（`All -> allBands()`，那个折叠 + WARN 的唯一归属地仍在 `GoformNetworkClient.lteAllBands()`）。
它**没有「保持不变」这一档**：现有调用点每次都同时下发 LTE 与 NR 两条，没有那条路径。

---

## 4. `NormalizedFields`：唯一构造途径，与它真正的语义

### 唯一构造途径

- 构造函数 `private`；
- 伴生对象里只有一个 **`internal fun of(values: JsonObject)`**，`internal` 的作用域是
  **`:core:device-schema` 这一个 Gradle 模块**（归一化层自己的模块），
  它**只被 `FieldNormalizer.normalizeToFields` 调用**；
- 对 `:core:goform` / `:core:api` / `:core:scheduler` / `:core` 这些**全部**消费方来说，
  除了真的跑一次归一化没有别的构造入口（Kotlin 编译期拒绝）；
- 刻意**不提供** `NormalizedFields(someJsonObject)` 这类公开工厂。

为什么要这么麻烦：在它之前，「字段名已经归一化」这件事只由方法体里那句
`fields.normalize(GROUP, raw)` 保证 —— **靠纪律，不靠类型**。后果很具体：
接第二台设备时写一个同样返回 `JsonObject`、但忘了调归一化的实现，编译通过、接口「实现」了、
route 拿到的字段名全错、前端静默显示空值，**没有任何测试会红**。

（一处 JVM 细节，别被字节码骗了：`private constructor` + 伴生工厂会生成一个合成桥
`public NormalizedFields(JsonObject, DefaultConstructorMarker)`，末参是 Kotlin 运行时内部标记类型，
**Kotlin 侧看不到也调不了**。`FieldNormalizerTest` 有一条反射断言钉住「除了这个合成桥没有别的公开构造」。）

### ⚠ 语义是「过了归一化层」，**不是**「字段名一定是 canonical」

**这一条最容易被下一个人理解错。** 判据在 `FieldNormalizer.normalizeToFields`：

```kotlin
if (raw == null) return null
if (profile == null) return NormalizedFields.of(raw)      // 闸门关着，原样包起来
return NormalizedFields.of(normalize(raw, profile, group, legacy))
```

`profile == null` = 排障开关 `field_normalization_enabled=false`，这时**不调用 `normalize`**，
把设备原始响应**原样**包起来返回（同一个实例，**字段名还是设备原名**）。所以：

- 本类型保证的是「这份数据**流经过**归一化闸门」，也就是「没有绕过闸门的旁路」；
- 「字段名是 canonical」**只在归一化开着时成立**（线上默认开着）；
- 唯一无条件 canonical 的是 `GoformFieldMapper.NORMALIZE_ALWAYS` 里的组
  （今天只有 `FieldGroup.TRAFFIC_LIMIT`）—— 那些组连排障模式也归一化。

另外两条边界，逐条对齐改造前的行为：**`null` 进 `null` 出**（上层用 `null` 区分「查询失败」
与「查到了但字段为空」，归一化不能把失败变成空对象）；消费点用 `.values` 解包成 `JsonObject`，
**解包出来的内容与改造前逐字一致**。

---

## 5. 已知契约泄漏清单（接第二种协议前必须先在这里立中立别名）

这 5 处的共同点：**协议无关的域接口签名上，带着 goform 的词汇**，而且都是对外入参或对外键名，
改它就要同时改 route 与两端客户端。所以它们被**写明而不修**。

| # | 位置 | 泄漏的是什么 | 接第二种协议前要做的事 |
| --- | --- | --- | --- |
| 1 | `NetworkControl.setConnectionMode(mode)` | 值域是**设备侧原值** `"auto_dial"` / `"manual_dial"` | 立中立枚举（自动 / 手动），把设备原值的映射搬进各自实现侧。⚠ 现在两端各发一套别名、route 统一映射成这两个设备原值 —— 那个归一化落在 route 里 |
| 2 | `NetworkControl.setBearerPreference(preference)` | 值域是**两者的并集**：contract 的 `NetworkMode` 别名（大小写不敏感）**或**设备侧 `BearerPreference` 原值（如 `Only_5G`） | 把设备原值那一路收进各自实现侧，签名只留 `NetworkMode`。它是兼容期产物（旧客户端直接发设备原值，映射对 Bearer 取值幂等，所以两种都收） |
| 3 | `NetworkControl.calibrateFlow(target, value)` | `target` 的 `"data"` / `"time"` 是设备侧词汇，且 route 原样透传 | 同 1 |
| 4 | `WifiControl.setWifiBand(chip)` | `chip` 是 goform 词汇（`"chip1"` = 2.4G / `"chip2"` = 5G），且是对外入参（`POST /api/wifi/band` 原样透传、route 刻意只认这两个值） | 立中立别名（2.4G / 5G）。别把本参数当成协议无关的 |
| 5 | `WifiControl.getCurrentWifiConfig()` | map 的**键名是设备侧 CamelCase 原名**（`AuthMode` / `EncrypType` / `SSID` / `Password` / `ChipIndex` / `ApMaxStationNumber` / `ApBroadcastDisabled`） | 它**没有上层消费点**（三个写入口在实现侧自己用它读回当前值），上接口只为「域接口覆盖面 = 客户端 public 成员」这条口径完整。要收就连同三个写入口一起收 |

第 5 条还有一个**读明文 / 写 base64 的不对称**要记住：`Password` 返回的是**明文**
（设备侧是 base64，实现侧已经解过一次），而回写设备时要的是 base64 ——
任何拿这个值回写的地方**必须自己编一次**，既不能原样发，也不能再解一次
（明文不是合法 base64 时会拿到空串）。

另外两处不算「泄漏」但同样是设备形状，写在这里免得被当成 bug：
`WifiControl.getWifiModuleInfo()` / `getWifiSettings()` 返回裸 `JsonObject` 是**原样透传**，
它们是 `getWifiSettingsMerged()` 的两个输入（归一化必须一次看到全部输入才能按别名链定优先级），
其中 `getWifiModuleInfo` 本身就是已登记的诊断端点例外
（`DeviceFields.UNSTABLE_ENDPOINTS` 里的 `GET /api/wifi/module-info`）。

---

## 6. signal 域的不对称：第 1 层字段映射在消费方

其它五个域的口径是「设备知识全在实现侧」。**signal 域不是**：
`SignalCollector` 的第 1 层（设备字段名 → canonical）由**消费方**拿着 `DeviceProfile` 自己调
`FieldNormalizer.normalize(…, FieldGroup.SIGNAL, …)` 完成，而 `SignalSource.getSignalInfo()`
出来的是**设备原始响应**。

这是**刻意保留**的（批 B2 只解耦、不搬迁），理由三条（出处：`SignalSource` 的类 KDoc）：

1. **搬走第 1 层会把三层编排拆开。** `SignalCollector` 的第 2 层（拿服务小区 PCI 去
   `neighbor_cell_info` 数组里匹配）与第 3 层（Android Telephony 兜底）**需要原始响应与归一化结果
   同时在场** —— 第 2 层读的是原始响应里的邻区数组，PCI 却取自第 1 层的归一化结果。
   把第 1 层搬到实现侧，这两份就得分两次跨接口传回来。
2. **「消费方拿 profile 做映射」本身不是 goform 专属设计。** profile 是**按设备一份**的
   （`DevicePlugin.profile()`），第二台设备那份同样由装配层注入到 `SignalCollector`，
   所以这个形状对非 goform 设备照样成立，**不是障碍**。
3. **风险不对称。** 搬迁会动对外 signal JSON 的字段名（WS `signal` 频道与 REST 共用同一份输出，
   key 集合是既有契约），收益只是「看起来与其它五个域整齐」。

同一条例外也记在 `DataHub` 类 KDoc 的「归一化在设备客户端层做」那条纪律下面。

顺带一条同源的设计：三个排障 / 诊断方法（`getFullStatus` / `getFullStatusMasked` /
`diagnoseFieldCoverage`）也在 signal 域上，因为它们是**按设备**的排障出口而不是 goform 专有 ——
而且**适配新设备时这两份输出就是 TODO 清单**，对第二台设备的价值比对第一台更大。

---

## 7. 平台适配（`PlatformAdapter`）

什么时候需要写新的：**新设备的 SoC 平台与现有实现不同**（现有只有展锐 `SprdPlatform`）。
与「设备型号」正交 —— 同平台的不同型号可以共用一个 adapter，所以它放在
`platform/<平台>/` 而不是 `<厂>/<型号>/`：**`zte/f50/` 放「这台设备是什么」，
`platform/sprd/` 放「它跑在什么平台上」**。

4 个成员：

| 成员 | 说明 |
| --- | --- |
| `name` | 平台名。⚠ `SprdPlatform` 用的是**大写 `"SPREADTRUM"`** —— 它与 `/api/at/platform` 下发的取值同源，改它是对外变更 |
| `atTransports()` | AT 下发通道候选列表，按序 probe，第一条成功即选中。一条都不可用时返回**空列表**，不要返回「probe 永远 false 的假实现」 |
| `readTemperature()` | 原始热区读数（摄氏度）。**读不到返 null，不许返 0**（0 会被当成真实读数，于是热保护静默失效）。它**不承载任何阈值判断** |
| `restartNetworkStack(at)` | 「这台设备怎么重启网络栈」的知识（发哪几条、什么顺序、等多久、怎么判成功）。**执行通道由调用方注入** |

**刻意没有的两个成员**（别加回来）：

- `privilegeEscalation()` —— 实测提权只有一条实际路径（ADB 自连 `localhost:5555`，uid 2000），
  Samba `root preexec` 那条的执行入口是**死代码**。不为不存在的路径造抽象。
  另外：`hasRootAccess()` 的真实语义是「ADB 通道可用」，**不是 uid=0**。
- `readBattery()` —— 电量主路径是 Android `BatteryManager`（通用 framework API，不是平台知识），
  而且它需要 `Context`，会给 adapter 引入第一个 Android 依赖。
  `Capability.BATTERY` 因此改由**插件声明的设备事实**驱动，不靠一次真实读取。

两条与「两个 adapter 撞名」有关的纪律（2026-09-25 批 A1 之后 `DeviceAdapter` 出现，
「adapter」这个词在本仓有两个意思，别混）：

- **`PlatformAdapter` 必须写成无状态的** —— `DevicePlugin.platform(ctx)` 的契约是
  **每次调用都新建实例**。装配层已经改成整个组件图共享一份（`ComponentFactory.build()` 里的
  `val platform`），但实现本身仍要无状态：`SprdPlatform` 的网络栈重启互斥锁就是因此留在
  `NetworkController`（锁放实现里 = 每个实例各锁自己 = 等于没锁）。
- **`DeviceAdapter`（六个域）反过来是有状态的**：它持有六个客户端实例，每个只许 new 一次，
  生命周期跟组件图。这两个「adapter」的纪律正好相反。

`SprdPlatform` 的三个成员都已落到实处：`atTransports()` 返回唯一实现 `ServiceCallAtExecutor`；
`restartNetworkStack` 是 `AT+SFUN=5` → `delay(500)` → `AT+SFUN=4` → `delay(2000)`，
判成功的依据是**回显串含 `OK`**；`readTemperature()` 2026-09-25 起**委托 `ThermalZones.readMax()`**
（`:core:common`，全仓唯一的热区遍历），本方法只做毫度 → 摄氏度换算。

### AT 通道

- `AtTransport` 在 `core/device-spi`（零 import、零 Android 类型）：`name` / `probe()` /
  `sendCommand(command, slot, timeoutMs)` / `reset()`。
- 具体实现在 `platform/<平台>/`（展锐是 `ServiceCallAtExecutor`，fork `/system/bin/service` 打 HAL）。
- `ATChannel`（`core/collector`）是**策略层**：全局互斥、500ms 最小间隔、指数退避、20 次失败熔断。
  它留在 collector，不跟着插件走 —— 实现类**不要**再各做一套限流，两层叠在一起只会让
  「最小命令间隔」变成一个没人说得清的值。
- `AtTransport.probe()` **不许真发一条 AT**：`init()` 在服务启动路径上，一次 modem 往返会拖慢整个 core 起步。
- ⚠ `AtTransport.name` 是对外可见的（`/api/at/status` 的 `method` 字段），**不许改**。

---

## 8. 对外契约（改设备时会被观察到的地方）

### `GET /api/diagnose` → `device_profile`（6 键）

| 键 | 取值 |
| --- | --- |
| `active` | 当前生效的**可空** profile 的 id；关归一化时是**空串** |
| `configured` | `device_profile_id` 的**原值**（可能是 plugin id 也可能是 profile id，不做归一） |
| `normalization_enabled` | 归一化 profile 是否非空 |
| `status` | 四态：`disabled` / `default` / `configured` / `fallback`（由 `configured` 与 `active` 比出来的既有判据） |
| `plugin_id` | 选中插件的 id，**恒非空**，不受排障开关影响 |
| `selection` | 四态：`configured` / `probed` / `default` / `fallback`（`Selection.wire`，显式写死而不是 `name.lowercase()`） |

⚠ **`status` 与 `selection` 可以互相矛盾**：填了 plugin id 时选型是成功的（`selection=configured`），
但 `active` 是 profile id、`configured != active` → `status` 判成 `fallback`。
**看选型结果以 `selection` 为准**（`HttpServer` 那段注释写明了这是刻意不修的既有行为）。
另外 `selection` 隐含「探测通不通」：零配置下 `probed` = 后台可达且有插件认领，`default` = 没人认领。
它**不是探测结果的正式出口** —— 要看细节请看启动日志。

### `GET /api/device/capabilities`

```json
{ "plugin_id": "zte-f50", "capabilities": ["sms", "sim_slot_switch", "..."] }
```

**数组不是 map**（map 形态下旧客户端分不出「新增能力」与「不支持」）。按枚举声明序。

### 缺能力时的写请求

**501 + `ErrorCode.NOT_SUPPORTED`**，由 `HttpServer` 的 `StatusPages` 统一出口抛出
（`exception<CapabilityMissing>`，必须排在 `exception<Throwable>` 之前）。
门禁当前 **12 处 `requireCapability(...)` 覆盖 10 个域**（SimRoutes 1 / RootSmsRoutes 1 /
NetworkRoutes 3 / DeviceRoutes 7）—— 同一个域的**所有写入口**都要拦，放行任一入口等于留了绕过门禁的路。

三种「不可用」**不许混成一个码**：设备不支持 → 501 `NOT_SUPPORTED`；
通道不可用 → 503 `UNAVAILABLE`；参数越界 → 400 `OUT_OF_RANGE`。

### `/api/at/platform` 与 `/api/at/status` 的 `platform`

取值是 `ATChannel.Platform` 的**大写枚举名**：`SPREADTRUM` / `QUALCOMM` / `UNKNOWN`。
判据是 `/proc/cpuinfo` **全文** contains（marker 见 `CpuInfoPlatform`，大小写不敏感）。
它回答「这台机器看起来是什么平台」，**与 `PlatformAdapter.name` 刻意分开** ——
比如配置里硬指定了 F50 插件、实际插在一台高通机器上：前者仍是 `SPREADTRUM`，后者是高通，
而「这台其实不是展锐」正是这个字段的排障价值。⚠ `connected = false` 时整个 map 里**没有** `platform` 键。

### battery map（8 键）

`supported` / `level` / `scale` / `percent` / `temperature` / `voltage` / `is_charging` / `plugged`。
`supported = false`（未声明 `Capability.BATTERY`）时**不改任何读数**；变的是 core 不入库、不做电池告警。
`supported` 在 `try` 之外先填 —— 它不依赖任何一次读取，而恰恰是「读数可信吗」这个答案。

---

## 9. 守门测试会拦你什么

| 测试（模块） | 条数 | 拦什么 |
| --- | --- | --- |
| `PluginContractTest`（device-plugins） | **13** | 注册表非空；id / displayName 非空且 id 全局唯一；`DEFAULT` 是 `ALL` 里那个对象；`byId` 返回同一对象、未登记返 null；`profile()` 多次返回同一对象；`tuning()` 值域自洽（含 `forcePauseOffset > 0`）；`capabilities` 非空且多次读返回同一集合；**声明了 capability 必须有对应 WriteSpec / smsSpec**；对照表覆盖 `Capability` 全部取值且**两张白名单交集为空**；`probe` 在后台不可达时必返 0、对同一 env 幂等；**`createAdapter` 交出的 adapter 的 id / capabilities 与插件一致**；**六个域字段非空**（防 `lateinit`） |
| `UpperLayerGoformFreeGuardTest`（device-plugins） | 2 | 上层五处（api / collector / scheduler / controller / core-src）的 main 源码里含 `Goform` 的标识符必须与 allowlist **逐文件精确相等**（多一个 → 红；登记了但代码里没有了 → 也红）。判据只看真代码：注释、字符串字面量、字符字面量全剥掉，**但保留字符串模板里的表达式**。第 2 条测「剥注释与字面量的实现本身可用」 |
| `GoformTransportVisibilityGuardTest`（goform） | 2 | `GoformTransport` 不许出现在 `core/goform` 之外（扫 core 下全部 `.kt`、**剥注释后**再判，注释里提名字是允许的）。第 2 条反向钉「core/goform 内部确实在用它」（≥ 9 个文件），防止符号改名后第 1 条空转 |
| `GoformCommandTableGuardTest`（goform） | 9 | 读命令表**内容冻结**（10 组逐组逐字、**含顺序**）；`soloCmds` 只有 `WIFI_CLIENTS` 的 `station_list`；`fullStatusCmds` 三批冻结；命令表里不许出现小写 `lte_snr`（那是 canonical，设备真名是 `Lte_snr`）；`cmds` 取 `commandProfile` 而不是 `normalizeProfile`；关归一化后 cmds 仍非空且等于命令表；关归一化时 `coverageReport` 不向设备发查询 |
| `CapabilityWireTest`（contract） | 4 | 11 个 wire 取值**逐个写死** + 值域大小 == 11。**改它就是改 API** |
| `CapabilityGateTest`（api） | 4 | 每个 `Capability` 至少被一处 route 门禁引用（防「定了不用」）；豁免表有上限且理由要分「还没有 route」与「本质上不该有」两类 |
| `DeviceRuntimeTest`（device-spi） | 18 | 选型五条路径与它们的日志、`Selection.wire` 写死、有配置时 probe 一次都不许被调用 |
| `DeviceHubTest`（device-spi） | 10 | `DeviceHub` 的每个成员都真的转发到 adapter（六个域 + id + capabilities） |
| `CpuInfoPlatformTest`（device-spi） | 9 | 平台 marker 判据（含 `unisoc` 单独出现时也判展锐） |
| `FieldNormalizerTest`（device-schema） | 35 | 归一化行为 + **`NormalizedFields` 除合成桥外没有公开构造函数**（反射断言） |
| `ZteGoformProfileTest`（device-schema） | 127 | F50 命令表与参数键逐字冻结、频段全集掩码、二维码候选去重、`writeSpec` 与 `SettingKey` 逐项对齐、retry 策略逐项 |
| `SprdPlatformTest`（device-plugins） | 10 | `AT+SFUN=5/4` 的命令序列、500/2000ms 的等待（虚拟时钟断到精确值）、失败路径 |
| `ZteGoformAdapterBandSelectionTest`（device-plugins） | 3 | `BandSelection.All` 最终传给客户端的取值就是 `lteAllBands()` / `nrAllBands()`（mock 客户端，**不触达传输层**） |
| `GoformNormalizeAlwaysTest`（goform） | 11 | 排障开关的豁免**没有扩散**到其它字段组（除 `TRAFFIC_LIMIT` 外每组 `assertSame(入参, 出参)`） |

### ⚠ 两个跨模块扫源码的守门测试必须 `--rerun-tasks`

`UpperLayerGoformFreeGuardTest` 与 `GoformTransportVisibilityGuardTest` 扫的是**别的 module 的源码**，
Gradle 不把那些文件当作本任务的输入。于是「有人在 `:core:api` 里写了越界引用」这件事，
在守门测试所在模块没变的情况下任务会报 `UP-TO-DATE`，**一声不响地不跑**。
本仓因为这一条出过「全绿」的假绿两次。收尾时必须：

```
gradlew :core:device-plugins:testDebugUnitTest --rerun-tasks
gradlew :core:goform:test --rerun-tasks
```

两者都用 `assumeTrue` 找仓库根（找不到就**跳过**而不是假失败 —— 一条永远红的守门测试最后一定被人 `@Ignore`）。

---

## 10. 反模式（这些做法会被拦，或者会咬人）

1. **把域字段写成可空**（`val sim: SimControl?`）—— 「这台设备支不支持切卡」的**唯一**判据是
   `capabilities`，route 门禁在请求到达 adapter 之前就回 501。再用一个可空字段表达同一件事就是
   **两份判据**，早晚分叉（能力集说支持、字段却是 null，或反过来），到时候没人说得清哪份是真的。
   同理不许用 `lateinit` / `Delegates.notNull()`：编译能过，真正取域时才炸，而取域的地方是线上请求路径。
2. **读侧返回裸 `JsonObject` 绕过 `NormalizedFields`** —— 写一个返回 `JsonObject`、
   但忘了调归一化的实现，**编译通过、接口「实现」了、字段名全错、没有任何测试会红**。
   需要归一化保证的出口一律用 `NormalizedFields`（那个类型在实现侧的模块里造不出来）；
   确实是原样透传的，在 KDoc 里写明**是哪一种透传**。
3. **往 route / collector / controller 塞设备知识** —— 命令名、字段原名、值域、文件名模板，
   一律进 profile 或对应 SPI 面。现在有 `UpperLayerGoformFreeGuardTest` 守着。
4. **把设备不支持的功能写成注释** —— 用 `Capability` 表达，让 UI 能灰掉、让 route 能回 501。
5. **往 `NORMALIZE_ALWAYS` 加字段组** —— 每加一组，排障开关就少一块可观察面。
   消费端容忍多形态才是正解（例：`station_list` 可能是真数组、也可能是数组的 JSON 字符串，
   用三态返回处理，**解析失败 ≠ 空列表**）。
6. **在 `profile()` / `capabilities` 里 new 对象** —— 守门测试会红。
7. **在 `createAdapter` 里再造一份 transport 或多造一份客户端** —— 会话互相顶下线；
   `GoformWifiClient` 的 `lastQrCodeFailure` 与 mapper 的 warn-once 集合都是可变状态。
8. **改 wire 名或枚举对外取值** —— `Capability.wire`、`Selection.wire`、`AtTransport.name`、
   `PlatformAdapter.name`、`ATChannel.Platform` 名都是对外契约。映射要写在枚举上，
   **不要在下发处 `name.lowercase()`**（那等于把 Kotlin 标识符变成 API）。
9. **日志文案里拼变动内容** —— `AppLogger.repeatGate` 按「级别+tag+完整消息」60s 折叠，
   拼了 `e.message` / IP / 设备数据会让基数发散、折叠失效、日志刷爆。
   `ProbeEnvCollector` 与 `DeviceRuntime` 的 WARN 都是固定文案（带插件 id，**不带异常 message**）。
10. **把「拿不到」当成「拿到了」** —— 这条坑过三次：温度读失败返 0 被判成「最凉」、
    无电池设备恒报 50% 被入库成真实曲线、`station_list` 解析失败被当成「所有设备都离开了」。
11. **给死代码造抽象** —— Samba preexec 那条提权路径的执行入口没人调，
    所以 `PlatformAdapter` 里没有 `privilegeEscalation()`。同理**不预先声明空的域成员**。
12. **指望配置能救** —— 选型只在组件图构造时算一次，改配置要重启后台服务。
13. **顺手把不齐整的返回类型统一了** —— 见第 3 节。那不是重构，是改 route 的响应形状。

---

## 11. 验证清单

### 静态（必须全过）

```
gradlew :core:device-spi:test :core:contract:test
gradlew :core:device-plugins:testDebugUnitTest --rerun-tasks   # 必须，见第 9 节
gradlew :core:goform:test --rerun-tasks                        # 必须，见第 9 节
gradlew :core:device-schema:test :core:common:test :core:controller:test :core:api:test
gradlew :core:assembleBenchmark                                # 出包默认 benchmark 变体
```

⚠ 库模块**没有** benchmark 变体（回落 release），所以单模块编译检查用 `compileDebugKotlin`。

### 真机（框架改动**只有真机能证明行为不变**）

1. **接口快照**：改造前后各抓一份 `/api/dashboard` `/api/device` `/api/network` `/api/monitor`，
   比对键集与形状（**允许值变，不允许键集变**）。
2. **`field_coverage`**（`/api/diagnose?fields=1`）：10 个组全部 `queried=true`；
   每组 `missing` 的每一项都要能解释（驻网制式 / 功能未开启 / 字段名不对）；解释不了的一律登记。
   ⚠ `missing` 列的是**设备侧字段名**，与 `hit_source` 的 value 同口径。
3. **15 条写操作**逐条点：重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 / 手动拨号与挂断 /
   切连接模式 / 改 SSID / 改密码 / 改功率 / 开关 WiFi / 切 WiFi 频段 / 发短信 / 删短信 / 标已读。
   （拨号与「开关移动数据」是**不同的 key、不同的失败路径**，必须分开点 ——
   前者没有兜底命令，后者主命令失败还会发 `SET_DATA_ENABLED`。）
   ⚠ **切 WiFi 频段会重启 WiFi 模块，正连着 WiFi 的客户端（包括发起请求的那台）会掉线**。
4. **排障开关**：关掉 `field_normalization_enabled` 重启后，字段应变回设备原名、
   `/api/diagnose` 的 `normalization_enabled` 变 false、
   而**写操作照旧能用**（命令表不受它影响）、`selection` 与 `plugin_id` 不变。
5. **日志**：日志总开关**关着**时，排障 WARN 仍应落地（WARN/ERROR 无视总开关），INFO/DEBUG 不落地。
6. **`createTransport()` 只有真机能验**：`PluginContractTest` 刻意不调它（`new GoformClient`
   会起 Ktor client、`AppLogger` 又依赖 `android.util.Log`，JVM 单测里只会拿到
   `RuntimeException: Stub!`）。装配层造完就要连设备，这一条靠真机冒烟覆盖。

---

## 12. 已知陷阱与未结欠账

| # | 事实 | 影响 |
| --- | --- | --- |
| 1 | `TRAFFIC_LIMIT` 的 `monthly_rx_bytes` ← 设备 `monthly_tx_bytes`（**刻意交叉**，2026-09-01 实测：ZTE 从「模块看 PC」的视角命名，与 canonical 相反） | 看到上下行交叉**不要当 bug 修**。掰正只在 `ZteGoformProfile` 一处 |
| 2 | `SIGNAL` 组有一批 `lte_*` 注册项与 `CELL_INFO` 组的 `Lte_*` 是同一物理量、大小写/命名不同 | 需要在 **LTE 驻网**下再抓一份 `field_coverage` 才能分清「死注册项」与「NR 驻网所以没返」 |
| 3 | `UpgMode` / `sleep_sysIdleTimeToSleep` / `BearerPreference` 读不回状态，但有 `WriteSpec`（三者都在 `DEVICE_SETTINGS` 的命令表里 —— 也就是**查了但设备不给**；这条是真机观察，**本次未核实**） | **写得进、读不出**。UI 该按「读不到 = 不知道」显示，不许假装是关 |
| 4 | `DownloadManager.migrateConfig()` 的**地板判据**仍是硬编码（`LEGACY_TEMP_WARN_FLOOR_C = 70f` / `LEGACY_TEMP_CRITICAL_FLOOR_C = 80f`，已提成常量但**没接 tuning**），只有**抬升目标值**接了 `DeviceTuning` | 换设备时「低于多少才抬」还是 F50 的经验值 |
| 5 | `DownloadManager` 有**第二份**电量读法（硬编码 `/sys/class/power_supply/battery/capacity`），服务下载限速第 4 档 | 未接 `Capability.BATTERY` 的声明结果 |
| 6 | 安装器（`scripts/UFI-AXIS-Core-install-Android/goform/`）有**独立的第二套 goform 实现** | **刻意不纳入插件化**。接新设备时它要另写一套。共享持久化键与对外报文格式仍以 `core/contract` + API 手册为真源 |
| 7 | `ROOT_SHELL` / `AT_CHANNEL` 刻意不做成 `Capability` | 它们是运行时状态，看 `/api/at/status` 的 `connected` 与 shell 的 root 上报 |
| 8 | `PlatformAdapter.restartNetworkStack` 的 `false` **无法区分「本平台不支持」与「这次失败了」** | 照搬现状：唯一调用点把它原样放进 `/api/network/band` 的 `network_restarted`（布尔只有两态）。不支持该动作的平台返回 `false`、**不要抛异常** |
| 9 | `SprdPlatform.restartNetworkStack` 的 `catch (e: Exception)` 会把 `CancellationException` 也吞掉 | 照搬的既有缺陷（同批的 `ServiceCallAtExecutor` 是先 catch 再 throw 的）。改它会改变取消语义 |
| 10 | `DeviceProfiles`（`:core:device-schema`）已 `@Deprecated`，但**还不能删** | 它在纯 JVM 模块、被所有人依赖，委托 `PluginRegistry` 会反向成环。⚠ 它的 KDoc 里「等 `SignalCollector` / `DataScheduler` 那两处默认参数也改掉」这句**已经过期** —— 那两处 2026-09-25 就改成无默认值了；现在拦着它的只剩「本模块自己的测试还拿注册表当断言对象」 |

**旧版列为欠账、现在已经清掉的三条**（别再照着旧文档找）：
① 六个客户端 + writer 收进插件 —— 批 A3 做完；
② `SignalCollector` / `DataScheduler` 的 `= ZteGoformProfile` 默认参数 —— 2026-09-25 改成**非空、无默认值**；
③ `GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST` —— 改成走 `profile.aclDefaultMode()`。

---

## 13. 一页速查：接一台新设备

```
1. core/device-schema/.../profile/XxxProfile.kt          ← 命令表 + 字段表 + 写命令 + ACL 默认档 + SMS
2. core/device-plugins/.../<厂>/<型号>/XxxPlugin.kt       ← 9 个成员：id / displayName / capabilities /
                                                            profile / createTransport / createAdapter /
                                                            platform / tuning / probe
3. core/device-plugins/.../<厂>/XxxAdapter.kt             ← DeviceAdapter：id + capabilities + 六个域
                                                            （公开构造收 transport + 两份 profile，
                                                             主构造收客户端并降成 internal）
4. 非 goform 协议的话：自己那层传输实现（实现 DeviceTransport 的 14 个方法）
5. core/device-plugins/.../PluginRegistry.kt              ← ALL 加一项（DEFAULT 通常不动）
6. SoC 平台也是新的：core/device-plugins/.../platform/<平台>/  ← PlatformAdapter + AtTransport 实现
7. 有新的写动作：core/device-schema/.../DeviceProfile.kt 的 SettingKey ← 加枚举项（冻结区，慎重）
8. 有新的功能域：core/contract/.../Capabilities.kt ← 加枚举项 + wire 名 + route 门禁 + 两端镜像
                                                     + PluginContractTest 的 writeKeysOf / READ_ONLY
9. 跑第 11 节的静态清单（两条守门测试记得 --rerun-tasks）
10. 真机过第 11 节的六项
11. 接第二种协议前先清第 5 节那 5 处契约泄漏
```

**不需要改**：`core/api` 的 route（除非有新功能域）、`core/collector`、`core/controller`、
`core/scheduler`、`HttpServer`、`DataHub`、`ComponentFactory`、
app / web 的业务代码（除了新功能域的 capability 镜像与置灰）。

**装配层一行都不用改**：`ComponentFactory.buildNetworkGraph` 只调
`plugin.createTransport(...)` + `plugin.createAdapter(...)`，两个返回类型都在 `:core:device-spi`。
这是这套框架的收益所在，也是 `UpperLayerGoformFreeGuardTest` 守的那条接缝。
