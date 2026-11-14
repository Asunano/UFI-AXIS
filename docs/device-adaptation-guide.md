# 设备适配指南

> 适用范围：**接入新的随身 WiFi / UFI 设备时需要修改的位置。**
>
> 本文描述**代码现状**。文档与代码不一致时**以代码为准**。
> 设计取舍与被否决方案的记录不属于本文范围。

---

## 0. 能力边界：协议无关部分与 goform 专有部分

**装配层与上层已与协议无关**。`ComponentFactory.buildNetworkGraph()`
只依赖两个契约类型：

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

类型断言 `val goform = transport as? GoformClient ?: error("…装配层只支持 goform 系插件")`
**不在装配层**：它位于 `ZteF50Plugin.createAdapter`，即**创建 transport 的插件自身**
（由创建方完成类型收敛）。六个 `Goform*Client` 的 `new` 同样位于插件内
（`ZteGoformAdapter` 的公开构造函数）。因此：

- **接入 goform 系新设备**（另一型号或采用同协议的其他 OEM）→ 一份 profile 加一个 plugin，
  adapter 直接复用 `ZteGoformAdapter`。
- **接入非 goform 设备**（如飞猫）→ 框架已支持：实现一份 `DeviceAdapter`（六个域），
  在自身的 `createAdapter` 中创建对应客户端。装配层无需修改。

### 剩余的 goform 专有部分（接入第二种协议前须知）

| # | 事实 | 是否属于缺口 |
| --- | --- | --- |
| 1 | 六个 `Goform*Client` + `GoformSettingWriter` + `GoformTransport` 仅存在于 `:core:goform`，仅 `:core:device-plugins` 可见 | **不属于缺口，属于设计**。守门测试 `GoformTransportVisibilityGuardTest` 固定该约束 |
| 2 | 5 处**契约泄漏**：域接口的签名上带着设备侧词汇（见第 5 节） | **属于缺口**，但修改会变更对外契约。接入第二种协议前应在这 5 处建立中立别名 |
| 3 | `DeviceTransport` 的 14 个方法是「HTTP + 批量 cmd 读 + 参数 map 写 + 5 个会话动作」的形状（`read(List<String>)` / `write(Map<String,String>)`） | **部分属于缺口**。第二种协议需适配该形状，或先扩展契约（仅做加法） |
| 4 | 命名遗留：`GoformQoS` / `GoformSessionLog`（位于 `:core:common`，是**出向 HTTP 通道**的 QoS 与日志，与协议无关）、`AppSettings` 的 `qosGoform*` 配置键、`DeviceTransport.updateGoformPassword`、各模块自己的局部名（`GoformNetworkInfo` / `preFetchedGoform` / `etGoformIp` …） | **不属于缺口**。改名会变更配置键与对外字段，已在 `UpperLayerGoformFreeGuardTest.ALLOWED` 的 A~D 组登记 |
| 5 | 安装器（`scripts/UFI-AXIS-Core-install-Android/goform/`，package `com.ufi_axis.installer.goform`）有**独立的第二套 goform 实现**，零 profile 抽象 | **刻意不纳入插件化**（一次性装机流程，不共享组件图）。接入新设备时需另写一套 |

「上层不得出现 `Goform`」由 `UpperLayerGoformFreeGuardTest` 守住：
`core/api`、`core/collector`、`core/scheduler`、`core/controller`、`core/src` 五处的**实际代码**
（剥离注释与字符串字面量后）中，含 `Goform` 的标识符必须与 allowlist **逐文件精确相等**。
装配层一组已清零。

---

## 1. 分层地图：各类实现的归属层

| 模块 | 放什么 | 硬约束 |
| --- | --- | --- |
| `core/contract` | 对外**冻结区**：canonical 字段名、错误码、`Capability`（11 项）、端点常量 | 只增不改。删要先 `@Deprecated` 一版（**零引用项例外**：仓库内 grep 确认无消费方后可直接删除） |
| `core/device-schema` | `DeviceProfile` / `SmsSpec` / `SettingKey`（29 项）/ `WriteSpec` / `FieldSpec` / `FieldNormalizer` / `NormalizedFields`，以及**各设备的 profile 实现**（`profile/ZteGoformProfile.kt` 等） | **纯 JVM**，只依赖 `:core:contract`（`build.gradle.kts` 明确要求：不得依赖 `:core:common` / `:core:goform`） |
| `core/device-spi` | 插件**契约层**：`DevicePlugin` / `DeviceTransport` / `TransportConfig` / `PlatformAdapter` / `AtTransport` / `CpuInfoPlatform` / `DeviceTuning` / `ProbeEnv` + `BuildInfo` / `DeviceRuntime` / `WriteOutcome`，以及 `adapter/` 下的 `DeviceHub` / `DeviceAdapter` / 六个域接口 / `BandSelection` / `AclEntry` / `AclSnapshot` / `SendVerdict` / `SendOutcome` / `SmsMeta` | **不得依赖任何具体协议实现**（`goform` / `collector` / `controller` 均不得依赖：goform 反向依赖该模块，引入后立即形成环）。是 Android library（`PlatformAdapter.platform(ctx)` 要 `Context`） |
| `core/device-plugins` | **插件实现**：`PluginRegistry` + 每设备一个 package（`zte/f50/`）+ 协议 adapter（`zte/ZteGoformAdapter.kt`）+ 平台实现（`platform/sprd/`）+ `probe/ProbeEnvCollector` | 一个 module、每设备一个 package。**不做「一设备一 Gradle module」**。它是**唯一**允许同时认识契约层与具体协议的模块 |
| `core/goform` | goform 协议实现：`GoformClient`（实现模块内那层 `GoformTransport`）+ 6 个客户端 + `GoformSettingWriter` + `GoformFieldMapper` | 对 `device-spi` 与 `device-schema` 都必须是 `api(...)`（`NormalizedFields` / `DeviceTransport` 上了公开签名） |
| `core/collector` `core/controller` `core/scheduler` `core/api` | 采集 / 控制 / 调度 / route。**不该有设备知识** | 这些层出现设备字段名、命令名或值域即属设计缺陷。它们只依赖 `:core:device-spi`（+ schema），**不得**依赖 `:core:device-plugins` |
| `core/src`（`ComponentFactory`） | **唯一** `implementation(project(":core:device-plugins"))` 的地方 | 装配层是唯一知道「有哪些插件」的地方 |

依赖方向以各模块 `build.gradle.kts` 为准，主要几条：
`device-schema → contract`；`device-spi → api(contract) + api(device-schema)`；
`goform → api(device-spi) + api(device-schema) + common`；
`device-plugins → api(device-spi) + device-schema + goform + common`；
`core（装配层）→ device-plugins`（仓库内仅此一处）。

归纳：**设备知识集中在 `device-schema` 与 `device-plugins`，平台知识集中在 `PlatformAdapter`，
其余各层只依赖接口。**

---

## 2. 接入新设备：最小路径

四类实现加一行注册：**一个 `DeviceProfile`、一个 `DevicePlugin`、一个 `DeviceAdapter`，
以及（平台不同时）一个 `PlatformAdapter`**，随后在 `PluginRegistry.ALL` 中增加一项。

### 2.1 写 profile（`core/device-schema/.../profile/`）

新建 `XxxProfile.kt`，实现 `DeviceProfile`（参考 `ZteGoformProfile`，1695 行，
**大部分是命令表与字段表，不含逻辑**）。**13 个成员，其中 8 个有默认实现**
（下表 12 行，`lteAllBandsMask` / `nrAllBandsMask` 合并为一行）：

| 成员 | 作用 | 是否可省略 |
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
| `aclDefaultMode()` | 设备**没上报** ACL mode 字段时按哪个档位理解（F50 = `"2"` 黑名单）。`null` = 保持空值原样上报，**不得自行指定档位** | 可（默认 null） |
| `qrCodeFileNames(chip, ssidIndex)` | WiFi 二维码图的候选文件名（按尝试顺序，**实现方自己去重**）。空列表 = 该设备不支持从后台取图 | 可（默认空） |
| `smsSpec()` | 短信规格（`sendParams` / `listQuery` / `deleteParams` / `markReadParams` / `sentTag` / `failedTag` / `encodeBody`） | 可（返回 null = 不支持短信） |

⚠ `aclDefaultMode()` 的存在理由：若由 `GoformWifiClient` 直接引用
`ZteGoformProfile.ACL_MODE_BLACKLIST`，即绕过 `commandProfile` 这条唯一通道，换设备后
仍以 ZTE 的 `"2"` 兜底，而 `"2"` 在其他设备上可能是白名单，也可能不存在该字段。
此时界面显示「黑名单生效」而设备实际放行所有客户端，且**没有测试会失败**。

⚠ `SettingKey` 是**冻结区内的枚举，共 29 项**。新设备若有独有写动作，
应先判断它是否为已有 key 的变体；确属新动作才新增 key —— 新增后所有 profile 都需实现该成员。
短信**刻意不走** `SettingKey`（`encode` 必须是纯函数而 `sms_time` 要读时钟、下发后还有回读确认、
编码是短信专有的），所以它有独立的 `smsSpec()`。

### 2.2 写 plugin（`core/device-plugins/.../<厂>/<型号>/`）

**9 个成员，均不可省略**（`id` / `displayName` / `capabilities` / `profile` / `createTransport` /
`createAdapter` / `platform` / `tuning` / `probe`；签名与 `DevicePlugin.kt` 保持一致）：

```kotlin
object XxxPlugin : DevicePlugin {

    override val id = "vendor-model"              // 稳定标识，进日志、进 /api/diagnose 的 plugin_id
    override val displayName = "Vendor Model"

    override val capabilities: Set<Capability> = setOf(
        // 见 2.4。必须使用 setOf(...) 这类不可变集合，且不得在此创建其他对象
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

三条约束：

1. **不得在 `profile()` 与 `capabilities` 中创建对象** —— `PluginContractTest` 断言多次调用返回同一个对象。
2. **`createAdapter` 不得再调用 `createTransport()`** —— 整个组件图只允许一份传输层实例
   （它持有会话 cookie、版本号、登录退避与 HTTP 连接池；创建第二份会使两套会话互相使对方下线）。
   装配层将自身创建的实例传入，同一实例还要进入 `ComponentGraph.NetworkGraph.goformClient`
   （修改设备后台口令即通过该实例）。
3. **`normalizeProfile` 可为空，不得以非空默认值填充** —— `/api/diagnose` 的
   `normalization_enabled` 就是从「它是不是 null」推出来的，兜底之后那个排障开关会永远报 `true`。
   而 `commandProfile` **永远非空**：字段归一化可以关，命令表不能关。

### 2.3 写 adapter（六个域）

`DeviceAdapter` 的 8 个成员：`id` / `capabilities` + `sim` / `device` / `network` / `wifi` /
`signal` / `sms`。**六个域字段全部非空** —— 判据只有一份，在 `capabilities`（理由见第 10 节反模式 1）。

goform 系实现形态（`ZteGoformAdapter`）可作为参考：

- **公开构造函数**收 `id + capabilities + transport + commandProfile + normalizeProfile`，
  六个协议客户端在它的委派实参里各 `new` **一次**；
- **主构造函数收六个客户端但降成 `internal`**，只服务本模块单测
  （`ZteGoformAdapterBandSelectionTest` 需 mock 客户端，才能单独覆盖 adapter 与客户端的边界）。
  该构造跨模块不可见：装配层若绕过公开入口自行创建客户端，将**无法通过编译**；
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

其内部六行 `new`（双 profile 的分工在此完整可见）：

```kotlin
simClient = GoformSimClient(transport, commandProfile)
deviceClient = GoformDeviceClient(transport, commandProfile)
networkClient = GoformNetworkClient(transport, commandProfile)
wifiClient = GoformWifiClient(transport, normalizeProfile, commandProfile)
signalClient = GoformSignalClient(transport, normalizeProfile, commandProfile)
smsClient = GoformSmsClient(transport, commandProfile)
```

**三个纯写域（sim / device / network）只接收 `commandProfile`**：它们不持有字段映射器，
可空 profile 在其中不生效；保留无实际作用的参数会误示「字段归一化在这三个类中起作用」。
`sms` 同理（没有参数表就发不出短信）。

⚠ **每个客户端只允许 new 一次**：`GoformWifiClient` 带 `lastQrCodeFailure`、
`GoformFieldMapper` 带 warn-once 集合，多一份实例就是行为变化。

`DeviceHub` 是上层唯一认识的类型（8 个成员：`adapterId` / `capabilities` + 六个域）。
它**仅做转发**：不含缓存、重试、日志与能力校验（门禁在 route 层）。
其存在理由有两点：① 上层只依赖这一个类型，替换 adapter 时改动集中在装配层一处；
② 跨设备的公共逻辑（写操作日志、能力集出口）有统一落点。

### 2.4 声明 capabilities

`Capability` 当前 **11 项**。**只声明这台设备真正支持的**：

- **10 个写侧域**：`SMS` / `SIM_SLOT_SWITCH` / `BAND_LOCK` / `CELL_LOCK` / `NETWORK_MODE` /
  `SAMBA` / `USB_DEBUG` / `FOTA` / `PERFORMANCE_MODE` / `TRAFFIC_LIMIT`
  → 声明了就必须在 profile 里有对应的 `WriteSpec`（`SMS` 是 `smsSpec() != null`），
  `PluginContractTest` **单向**断言这一条。
- **1 个纯读侧域**：`BATTERY`（这个型号**出厂带不带电池**）。
  它没有写 route、将来也不会有（电池是硬件，不存在「开关电池」），
  所以进 `CapabilityGateTest.exempt` 的永久豁免与 `PluginContractTest.READ_ONLY_CAPABILITIES` 白名单。

⚠ **不声明 `BATTERY` 时：读数照常上报，可信度不作保证**：
`percent` 就是系统报的值（F50 上恒为 50），battery map 里多一个 `supported=false`；
变的是 `DataScheduler` **不入库、不判电池告警**。
F50 即不声明该能力：它**能读到**一个恒为 50 的无效值，任何「读取一次以判断是否可用」的设计在该设备上都会得出错误结论。

不添加反向断言（「有 `WriteSpec` 就必须有 capability」）：`Capability` 是**功能域**（11），
`SettingKey` 是**写入项**（29），该断言实现后必然失败，且会迫使为每个 key 指定功能域。
未声明 capability 的写操作不受拦截。

**不该进 capabilities 的东西**：任何**运行时会变**的可用性（AT 通道通不通、root 通道在不在）。
这些状态由 `/api/at/status` 的 `connected` 与 shell 的 root 上报实时表达；
放入该静态集合会形成与真实状态不符的开关（集合声明可用，实际已断开）。

### 2.5 填 tuning

5 个字段，**全部为设备实测值**，各字段 KDoc 均说明「哪些名称相近的阈值不归其管理」与消费点：

| 字段 | 管什么 | 谁在读 | F50 |
| --- | --- | --- | --- |
| `downloadThrottleWarnC` | 下载限速一档（砍并发 + 全局限速） | `DownloadManager` | 75f |
| `downloadThrottleCriticalC` | 下载限速二档（更严格的档位，**尚未暂停**） | `DownloadManager` | 85f |
| `downloadThrottleForcePauseOffsetC` | 相对 critical 的偏移量，越线 = **暂停全部下载**。**必须 > 0**（`PluginContractTest` 守着；填 0 等于删掉第 3 档） | `DownloadManager` | 10f（即 95°C） |
| `thermalJitterC` | **用户告警**的回差（非限速用）。无回差且采用边沿触发时，阈值附近的微小抖动会导致告警风暴 | `AlertEngine(temperatureHysteresisC)` | 3f |
| `bootGraceMs` | 预热期：不入库、不判本地告警（WS 推送照常） | `DataScheduler(bootGraceDeviceDefaultMs)` | 90_000L |

后两个由 `ComponentFactory` 以**单个数值**传入，而非整个 `DeviceTuning`：
`:core:alert` / `:core:scheduler` 不应依赖插件层，且传入整个对象会使三套含义不同的
温度阈值出现在同一作用域。

⚠ **最容易误用的地方**：同一个「温度阈值」概念在仓库中是**三件不同的事** ——
下载限速 75/85（本类，`Float`，摄氏度）、采集降频与熔断 70/80（`AppSettings.monitorThermal*`，
**`Int` 毫摄氏度**，用户可配）、用户告警 65/75（`AlertEngine`，`Double`，带 3°C 回差，用户可配）。
**`downloadThrottleWarnC = 75f` 与 `AlertEngine.temperatureCritical = 75.0` 数值相同、语义相反。**
仅因「两边都是 75」而接线，会静默改变告警行为，且**单元测试不会失败**。

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
- **平台判据不得自行实现**：使用 `CpuInfoPlatform.isSpreadtrum` / `isQualcomm`
  （marker 是 `sprd`/`spreadtrum`/`unisoc` 与 `qualcomm`/`qcom`，大小写不敏感）。
  该类的唯一职责是统一两份不一致的 marker 列表。
- 返回 **0 = 不适用**，越大越匹配。负分同样当不适用。
- **不得推测**：没有实测依据的判据不应写入。`ZteF50Plugin` 刻意**不做** `Build.*` 匹配 ——
  仓库内没有 F50 的真机取值，构造的条件要么永不命中（死代码），要么命中其他设备。
  它的两条判据是 `goformLdReachable`（60 分，**唯一准入条件**）+ 展锐 marker（+20，只加分），
  满分 80，余量保留给后续的 `Build.*` 判据。
- **并列同分**时取 `PluginRegistry.ALL` 中声明顺序靠前者，并记录一条 WARN
  （并列说明判据不足以区分，应补充判据）。
- `probe()` 抛异常按 0 分处理 + WARN，**整轮选型继续**；`CancellationException` 原样抛出去。

`ProbeEnv.goformLdReachable` 是整套自动选型的关键：若依赖读取设备版本字段识别设备，
则**读取这些字段本身就需要 profile**（循环依赖）。`LD` 免登录、免 profile，
使用固定 cmd 名即可获取，从而绕开该循环。采集实现是裸 `HttpURLConnection`、
硬超时 1.5s、**不做** `GoformClient` 那套 localhost 回落（启动路径上只许发一次）。

### 2.7 注册

`PluginRegistry`（**新增一台设备后唯一需要改的公共代码**）：

```kotlin
val ALL: List<DevicePlugin> = listOf(ZteF50Plugin, XxxPlugin)
val DEFAULT: DevicePlugin = ZteF50Plugin    // 认不出设备时的兜底
```

**编译期注册**，不做运行时动态加载（DexClassLoader、反射扫描 dex、ServiceLoader 均不采用：
在单 APK 交付模式下，这些方案会引入签名校验、保留规则、R8 裁剪与崩溃归因等额外成本）。
`DEFAULT` 的存在理由：型号无法识别时**不得**导致整个后端不可用。

### 2.8 选型是怎么发生的

`DeviceRuntime.resolve(plugins, default, configuredId, normalizationEnabled, probeEnv, warn, info)`，
五条互斥路径、顺序即优先级：

1. `field_normalization_enabled = false`（排障开关）→ 先打一条 WARN、`profile` 置 null，
   **但插件照样选、probe 照样跑**（归一化能关，传输层与命令表不能关）。
2. `device_profile_id` 非空且匹配 → `CONFIGURED`。**匹配两种 id，先新口径后旧口径**：
   先 `plugin.id`（`zte-f50`），再 `plugin.profile().id`（`zte-goform`，**老部署填的就是这个**）；
   均按 trim + 大小写不敏感比较。命中旧口径时记录 **INFO 而非 WARN**（配置值有效）。
   ⚠ 这条命中时**一次 `probe()` 都不发** —— 配置永远是最高优先级。
3. 两种 id 都匹配不上 → `FALLBACK`，回落 `DEFAULT` + WARN（「可选」清单两种 id 都列）。
   同样**不发 probe**：配置填写错误时应保留该 WARN。
4. `device_profile_id` 空白 → **probe 打分**。最高分 > 0 → `PROBED` + INFO；
   全部 ≤ 0 → `DEFAULT`（**不是** `FALLBACK`：不存在配置错误），且**这条路径刻意不记录日志**
   （LD 不可达时 `ProbeEnvCollector` 已记录一条 WARN，再记录一条属于重复信息）。
5. `profile` 是否为 null 只由排障开关决定，与选中谁无关。

选型**只在组件图构造时执行一次**，修改配置需重启后端服务。**不提供运行时热切换**
（因此 `DeviceRuntime` 不包含 `switchPlugin()`；组件图重建时各级缓存均为新实例）。

---

## 3. 六个域接口逐个说

方法数与返回类型口径：

| 域 | 接口 | 成员数 | 返回类型口径 |
| --- | --- | --- | --- |
| sim | `SimControl` | 1 方法 | `switchSimSlot(slot): WriteOutcome` |
| device | `DeviceControl` | 13 方法（全是写） | 10 个 `Boolean` + 3 个 `WriteOutcome`（`setRestartSchedule` / `setDhcpSetting` / `cellLock`） |
| network | `NetworkControl` | 11 方法（全是写） | 6 个 `Boolean` + 5 个 `WriteOutcome`（`setBearerPreference` / `lockLteBands` / `lockNrBands` / `setDataLimit` / `calibrateFlow`） |
| wifi | `WifiControl` | 15 方法 + 1 属性 | 写 8：4 `Boolean` + 4 `WriteOutcome`；读 7：2 裸 `JsonObject?`、1 `NormalizedFields`（**非空**）、1 `NormalizedFields?`、1 `AclSnapshot?`、1 `Map<String,String>`、1 `Pair<ByteArray,String>?`；属性 `lastQrCodeFailure: String` |
| signal | `SignalSource` | 16 方法 + 1 属性 | **只有读**：8 个 `NormalizedFields?`、7 个 `JsonObject` / `JsonObject?`、1 个 `JsonArray?`；属性 `profileId: String?` |
| sms | `SmsControl` | 5 方法 | `getSmsList → JsonObject?`、`sendSms → SendOutcome`、`deleteSms` / `markSmsRead → Boolean`、`getSmsMeta → SmsMeta?` |

六个域按增量方式引入：`sim`、`device`、`network` / `wifi`、`signal`、`sms`，
以及 wifi 读侧与 `setAccessControlList`。每次新增一个域，连同实现与调用点一并落地，
**不预先声明空成员**：已声明但无人实现的成员属于死代码。新增第七个域时沿用该规则。

### 返回类型不统一的原因

这是**刻意保留的既有形态**，各处判据一致：统一返回类型会改变 route 的响应形状，
属于行为变更，而非纯接口迁移。

- **`Boolean` 与 `WriteOutcome`**：返回 `WriteOutcome` 的是**已接入值域校验**的实现
  （`Rejected` = 参数没过校验、根本没发请求 → route 回 400 `OUT_OF_RANGE` 而不是 500）。
  其余仍是两态 `Boolean`，因为它们对外就只有两态。`WriteOutcome` 的四态是
  `Ok` / `Rejected(reason)` / `Failed` / `Unavailable(reason)`，分界是
  **「固件有没有收下这条命令」**：`Failed` 不可重试（设备表过态了），
  `Unavailable` 可重试 → route 该回 503 而不是 500。
- **`NormalizedFields` vs 裸 `JsonObject`**：前者 = **过了归一化闸门**（第 4 节），
  后者 = **原样透传、形状不同、或本身就是一份报告**，每一个都在自己的 KDoc 里写明是哪一种。
  将透传项改为 `NormalizedFields` 会**错误声明**数据已归一化（其中确实包含设备原名）；
  将 8 个中的任意一个改为裸 `JsonObject` 会失去该类型保证。
  三种典型情况：
  - `getTrafficStats()` 是**混合体**（设备原始响应 + 归一化结果覆盖上去，为了留住没登记的
    `realtime_*`），所以是 `JsonObject`；需要纯归一化出口的是 `getDataUsage()`。
  - `getNeighborCellInfo()` **归一化过**，但它从结果里取出**一个成员**再返回 ——
    出去的是数组本身，套 `NormalizedFields`（「一份字段对象」）名不副实，所以是 `JsonArray?`。
  - `getFullStatusMasked()` 只做**脱敏**：脱敏不等于归一化，`maskDump` 将敏感值替换为 `***`、
    **字段名保持不变**，且不经过归一化闸门（profile 为 null 时原样返回）。
- **`getWifiSettingsMerged()` 是唯一的非空契约**：两个查询都失败时给 `NormalizedFields.EMPTY`
  （内容就是 `{}`）。这并非出口限制的例外：该方法不接受入参，空对象中不存在字段名。

### 三个协议无关的结论类型位于契约层的原因

`SendVerdict` / `SendOutcome` / `SmsMeta` 原本嵌在 `GoformSmsClient` 里，`AclEntry` / `AclSnapshot`
原本在 goform 模块 —— 它们的语义是协议无关的，而且出现在域接口的签名上，所以搬到了
`:core:device-spi/adapter/`（字段名、枚举值名与 KDoc 保持不变）。

`SendVerdict` 的五档里 **`REJECTED` 与 `NO_RESPONSE` 必须分开**，判据只有一个：
**是否确认设备未收到该发送请求**。确认未收到 → 重试安全；无法确认 → 重试可能产生第二条短信与
第二次计费。因此 `REJECTED` 是唯一**可重试**且**不计配额**的档位，`NO_RESPONSE` 相反
（不可重试，但计入配额）。

`BandSelection`（`All` / `Only(bands)`）收的是**意图**而不是掩码串：
「下发给设备的频段全集掩码」是 goform 的实现细节，放到协议无关的契约上等于规定
「别人家的设备也得用掩码串表达全部频段」。翻译只在 adapter 中一行完成
（`All -> allBands()`；折叠与 WARN 的唯一归属仍为 `GoformNetworkClient.lteAllBands()`）。
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

该约束的目的：若无此类型，「字段名已归一化」仅由方法体中的
`fields.normalize(GROUP, raw)` 保证，依赖约定而非类型。具体后果：
接入第二台设备时，实现一个同样返回 `JsonObject` 但未调用归一化的方法，编译可通过、接口视为已实现，
route 得到的字段名全部错误、前端静默显示空值，且**没有测试会失败**。

（一处 JVM 细节：`private constructor` + 伴生工厂会生成一个合成桥
`public NormalizedFields(JsonObject, DefaultConstructorMarker)`，末参是 Kotlin 运行时内部标记类型，
**Kotlin 侧看不到也调不了**。`FieldNormalizerTest` 有一条反射断言钉住「除了这个合成桥没有别的公开构造」。）

### ⚠ 语义是「经过归一化层」，**而非**「字段名一定为 canonical」

**该语义容易被误解。** 判据在 `FieldNormalizer.normalizeToFields`：

```kotlin
if (raw == null) return null
if (profile == null) return NormalizedFields.of(raw)      // 闸门关着，原样包起来
return NormalizedFields.of(normalize(raw, profile, group, legacy))
```

`profile == null` = 排障开关 `field_normalization_enabled=false`，这时**不调用 `normalize`**，
把设备原始响应**原样**包起来返回（同一个实例，**字段名还是设备原名**）。所以：

- 本类型保证的是「这份数据**流经过**归一化闸门」，也就是「没有绕过闸门的旁路」；
- 「字段名是 canonical」**仅在归一化开启时成立**（默认配置下开启）；
- 唯一无条件 canonical 的是 `GoformFieldMapper.NORMALIZE_ALWAYS` 里的组
  （今天只有 `FieldGroup.TRAFFIC_LIMIT`）—— 那些组连排障模式也归一化。

另外两条边界：**`null` 输入对应 `null` 输出**（上层用 `null` 区分「查询失败」
与「查询成功但字段为空」，归一化不得将失败转换为空对象）；消费点用 `.values` 解包为 `JsonObject`，
**解包内容与输入一致**。

---

## 5. 已知契约泄漏清单（接第二种协议前必须先在这里立中立别名）

这 5 处的共同点：**协议无关的域接口签名上带有 goform 词汇**，且均为对外入参或对外键名，
修改需同时改动 route 与两端客户端。因此在此**明确记录，暂不修改**。

| # | 位置 | 泄漏的是什么 | 接第二种协议前要做的事 |
| --- | --- | --- | --- |
| 1 | `NetworkControl.setConnectionMode(mode)` | 值域是**设备侧原值** `"auto_dial"` / `"manual_dial"` | 立中立枚举（自动 / 手动），把设备原值的映射搬进各自实现侧。⚠ 两端各自发送一套别名，由 route 统一映射为这两个设备原值，归一化位于 route 层 |
| 2 | `NetworkControl.setBearerPreference(preference)` | 值域是**两者的并集**：contract 的 `NetworkMode` 别名（大小写不敏感）**或**设备侧 `BearerPreference` 原值（如 `Only_5G`） | 把设备原值那一路收进各自实现侧，签名只留 `NetworkMode`。这是兼容期设计（旧客户端直接发送设备原值，映射对 Bearer 取值幂等，因此两种均被接受） |
| 3 | `NetworkControl.calibrateFlow(target, value)` | `target` 的 `"data"` / `"time"` 是设备侧词汇，且 route 原样透传 | 同 1 |
| 4 | `WifiControl.setWifiBand(chip)` | `chip` 是 goform 词汇（`"chip1"` = 2.4G / `"chip2"` = 5G），且是对外入参（`POST /api/wifi/band` 原样透传、route 刻意只认这两个值） | 建立中立别名（2.4G / 5G）。该参数不属于协议无关入参 |
| 5 | `WifiControl.getCurrentWifiConfig()` | map 的**键名是设备侧 CamelCase 原名**（`AuthMode` / `EncrypType` / `SSID` / `Password` / `ChipIndex` / `ApMaxStationNumber` / `ApBroadcastDisabled`） | 它**没有上层消费点**（三个写入口在实现侧用它读回当前值）；纳入接口仅为满足「域接口覆盖面 = 客户端 public 成员」的完整性要求。若收敛，应连同三个写入口一并处理 |

第 5 条还存在**读明文 / 写 base64 的不对称**：`Password` 返回的是**明文**
（设备侧是 base64，实现侧已经解过一次），而回写设备时要的是 base64 ——
任何拿这个值回写的地方**必须自己编一次**，既不能原样发，也不能再解一次
（明文不是合法 base64 时会拿到空串）。

另外两处不属于契约泄漏，但同样体现设备侧形态，列出以免被误判为缺陷：
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

这是**刻意保留**的，理由三条（出处：`SignalSource` 的类 KDoc）：

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

相关设计：三个排障 / 诊断方法（`getFullStatus` / `getFullStatusMasked` /
`diagnoseFieldCoverage`）也在 signal 域上，因为它们是**按设备**的排障出口而不是 goform 专有 ——
且**适配新设备时这两份输出可直接作为待办清单**，对第二台设备的价值高于第一台。

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
| `atTransports()` | AT 下发通道候选列表，按序 probe，第一条成功即选中。一条都不可用时返回**空列表**，不得返回「probe 恒为 false 的占位实现」 |
| `readTemperature()` | 原始热区读数（摄氏度）。**读不到时返回 null，不得返回 0**（0 会被当作真实读数，导致热保护静默失效）。它**不承载任何阈值判断** |
| `restartNetworkStack(at)` | 「这台设备怎么重启网络栈」的知识（发哪几条、什么顺序、等多久、怎么判成功）。**执行通道由调用方注入** |

**刻意省略的两个成员**（不应添加）：

- `privilegeEscalation()` —— 实测提权只有一条有效路径（ADB 自连 `localhost:5555`，uid 2000），
  Samba `root preexec` 路径的执行入口为**死代码**。不为不存在的路径设计抽象。
  另外：`hasRootAccess()` 的真实语义是「ADB 通道可用」，**不是 uid=0**。
- `readBattery()` —— 电量主路径是 Android `BatteryManager`（通用 framework API，不是平台知识），
  而且它需要 `Context`，会给 adapter 引入第一个 Android 依赖。
  `Capability.BATTERY` 因此改由**插件声明的设备事实**驱动，不靠一次真实读取。

两条与 adapter 同名相关的约束（`DeviceAdapter` 与 `PlatformAdapter` 含义不同，
需区分）：

- **`PlatformAdapter` 必须无状态** —— `DevicePlugin.platform(ctx)` 的契约是
  **每次调用都创建新实例**。装配层在整个组件图内共享一份实例（`ComponentFactory.build()` 中的
  `val platform`），但实现本身仍需无状态：`SprdPlatform` 的网络栈重启互斥锁因此保留在
  `NetworkController`（锁放在实现中会使每个实例各自加锁，等同于无锁）。
- **`DeviceAdapter`（六个域）则是有状态的**：它持有六个客户端实例，每个只允许 new 一次，
  生命周期与组件图一致。这两类 adapter 的约束相反。

`SprdPlatform` 的三个成员均已实现：`atTransports()` 返回唯一实现 `ServiceCallAtExecutor`；
`restartNetworkStack` 是 `AT+SFUN=5` → `delay(500)` → `AT+SFUN=4` → `delay(2000)`，
判定成功的依据是**回显串包含 `OK`**；`readTemperature()` **委托 `ThermalZones.readMax()`**
（`:core:common`，仓库内唯一的热区遍历实现），本方法只做毫度到摄氏度换算。

### AT 通道

- `AtTransport` 在 `core/device-spi`（零 import、零 Android 类型）：`name` / `probe()` /
  `sendCommand(command, slot, timeoutMs)` / `reset()`。
- 具体实现在 `platform/<平台>/`（展锐为 `ServiceCallAtExecutor`，fork `/system/bin/service` 调用 HAL）。
- `ATChannel`（`core/collector`）是**策略层**：全局互斥、500ms 最小间隔、指数退避、20 次失败熔断。
  它保留在 collector，不随插件迁移；实现类**不得**各自实现限流，两层叠加会使
  「最小命令间隔」的取值无法确定。
- `AtTransport.probe()` **不得实际发送 AT 命令**：`init()` 在服务启动路径上，一次 modem 往返会拖慢整个 core 起步。
- ⚠ `AtTransport.name` 是对外可见的（`/api/at/status` 的 `method` 字段），**不得修改**。

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
**选型结果以 `selection` 为准**（`HttpServer` 中的注释说明这是刻意保留的既有行为）。
另外 `selection` 隐含「探测通不通」：零配置下 `probed` = 后台可达且有插件认领，`default` = 没人认领。
它**不是探测结果的正式出口**，细节以启动日志为准。

### `GET /api/device/capabilities`

```json
{ "plugin_id": "zte-f50", "capabilities": ["sms", "sim_slot_switch", "..."] }
```

**数组不是 map**（map 形态下旧客户端分不出「新增能力」与「不支持」）。按枚举声明序。

### 缺能力时的写请求

**501 + `ErrorCode.NOT_SUPPORTED`**，由 `HttpServer` 的 `StatusPages` 统一出口抛出
（`exception<CapabilityMissing>`，必须排在 `exception<Throwable>` 之前）。
门禁当前 **12 处 `requireCapability(...)` 覆盖 10 个域**（SimRoutes 1 / RootSmsRoutes 1 /
NetworkRoutes 3 / DeviceRoutes 7）—— 同一个域的**所有写入口**都要拦截，放行任一入口都会留下绕过门禁的路径。

三种「不可用」**不得合并为同一状态码**：设备不支持 → 501 `NOT_SUPPORTED`；
通道不可用 → 503 `UNAVAILABLE`；参数越界 → 400 `OUT_OF_RANGE`。

### `/api/at/platform` 与 `/api/at/status` 的 `platform`

取值是 `ATChannel.Platform` 的**大写枚举名**：`SPREADTRUM` / `QUALCOMM` / `UNKNOWN`。
判据是 `/proc/cpuinfo` **全文** contains（marker 见 `CpuInfoPlatform`，大小写不敏感）。
它表示「这台机器看起来属于什么平台」，**与 `PlatformAdapter.name` 刻意分离**：
例如配置中指定了 F50 插件、实际运行在高通机器上时，前者仍为 `SPREADTRUM`，后者为高通；
「实际并非展锐」正是该字段的排障价值。⚠ `connected = false` 时整个 map 里**没有** `platform` 键。

### battery map（8 键）

`supported` / `level` / `scale` / `percent` / `temperature` / `voltage` / `is_charging` / `plugged`。
`supported = false`（未声明 `Capability.BATTERY`）时**不改任何读数**；变的是 core 不入库、不做电池告警。
`supported` 在 `try` 之外先填 —— 它不依赖任何一次读取，而恰恰是「读数可信吗」这个答案。

---

## 9. 守门测试的拦截范围

| 测试（模块） | 条数 | 拦什么 |
| --- | --- | --- |
| `PluginContractTest`（device-plugins） | **13** | 注册表非空；id / displayName 非空且 id 全局唯一；`DEFAULT` 是 `ALL` 里那个对象；`byId` 返回同一对象、未登记返 null；`profile()` 多次返回同一对象；`tuning()` 值域自洽（含 `forcePauseOffset > 0`）；`capabilities` 非空且多次读返回同一集合；**声明了 capability 必须有对应 WriteSpec / smsSpec**；对照表覆盖 `Capability` 全部取值且**两张白名单交集为空**；`probe` 在后台不可达时必返 0、对同一 env 幂等；**`createAdapter` 交出的 adapter 的 id / capabilities 与插件一致**；**六个域字段非空**（防 `lateinit`） |
| `UpperLayerGoformFreeGuardTest`（device-plugins） | 2 | 上层五处（api / collector / scheduler / controller / core-src）的 main 源码里含 `Goform` 的标识符必须与 allowlist **逐文件精确相等**（多一个 → 红；登记了但代码里没有了 → 也红）。判据只看真代码：注释、字符串字面量、字符字面量全剥掉，**但保留字符串模板里的表达式**。第 2 条测「剥注释与字面量的实现本身可用」 |
| `GoformTransportVisibilityGuardTest`（goform） | 2 | `GoformTransport` 不得出现在 `core/goform` 之外（扫 core 下全部 `.kt`、**剥注释后**再判，注释里提名字是允许的）。第 2 条反向钉「core/goform 内部确实在用它」（≥ 9 个文件），防止符号改名后第 1 条空转 |
| `GoformCommandTableGuardTest`（goform） | 9 | 读命令表**内容冻结**（10 组逐组逐字、**含顺序**）；`soloCmds` 只有 `WIFI_CLIENTS` 的 `station_list`；`fullStatusCmds` 三批内容冻结；命令表中不得出现小写 `lte_snr`（该名为 canonical，设备侧原名为 `Lte_snr`）；`cmds` 取 `commandProfile` 而不是 `normalizeProfile`；关归一化后 cmds 仍非空且等于命令表；关归一化时 `coverageReport` 不向设备发查询 |
| `CapabilityWireTest`（contract） | 4 | 11 个 wire 取值**逐个写死** + 值域大小 == 11。**改它就是改 API** |
| `CapabilityGateTest`（api） | 4 | 每个 `Capability` 至少被一处 route 门禁引用（防「定了不用」）；豁免表有上限且理由要分「还没有 route」与「本质上不该有」两类 |
| `DeviceRuntimeTest`（device-spi） | 18 | 选型五条路径与它们的日志、`Selection.wire` 写死、存在配置时不得调用 probe |
| `DeviceHubTest`（device-spi） | 10 | `DeviceHub` 的每个成员都真的转发到 adapter（六个域 + id + capabilities） |
| `CpuInfoPlatformTest`（device-spi） | 9 | 平台 marker 判据（含 `unisoc` 单独出现时也判展锐） |
| `FieldNormalizerTest`（device-schema） | 35 | 归一化行为 + **`NormalizedFields` 除合成桥外没有公开构造函数**（反射断言） |
| `ZteGoformProfileTest`（device-schema） | 127 | F50 命令表与参数键逐字冻结、频段全集掩码、二维码候选去重、`writeSpec` 与 `SettingKey` 逐项对齐、retry 策略逐项 |
| `SprdPlatformTest`（device-plugins） | 10 | `AT+SFUN=5/4` 的命令序列、500/2000ms 的等待（虚拟时钟断到精确值）、失败路径 |
| `ZteGoformAdapterBandSelectionTest`（device-plugins） | 3 | `BandSelection.All` 最终传给客户端的取值就是 `lteAllBands()` / `nrAllBands()`（mock 客户端，**不触达传输层**） |
| `GoformNormalizeAlwaysTest`（goform） | 11 | 排障开关的豁免**没有扩散**到其它字段组（除 `TRAFFIC_LIMIT` 外每组 `assertSame(入参, 出参)`） |

### ⚠ 两个跨模块扫源码的守门测试必须 `--rerun-tasks`

`UpperLayerGoformFreeGuardTest` 与 `GoformTransportVisibilityGuardTest` 扫的是**别的 module 的源码**，
Gradle 不将这些文件视为本任务的输入。因此当 `:core:api` 中出现越界引用时，
若守门测试所在模块未变更，该任务会被标记为 `UP-TO-DATE`，**实际未执行且不报错**。
此情况下测试结果可能为假绿。验证时必须执行：

```
gradlew :core:device-plugins:testDebugUnitTest --rerun-tasks
gradlew :core:goform:test --rerun-tasks
```

两者都使用 `assumeTrue` 定位仓库根；找不到时**跳过**而非判失败：始终失败的守门测试最终会被 `@Ignore` 禁用。

---

## 10. 反模式

1. **把域字段写成可空**（`val sim: SimControl?`）—— 「该设备是否支持切卡」的**唯一**判据是
   `capabilities`，route 门禁在请求到达 adapter 之前就回 501。再用一个可空字段表达同一件事就是
   **两份判据**，最终会分叉（能力集声明支持、字段为 null，或相反），无法确定以哪份为准。
   同理不得使用 `lateinit` / `Delegates.notNull()`：编译可通过，但在取域时抛出异常，而取域发生在线上请求路径。
2. **读侧返回裸 `JsonObject` 绕过 `NormalizedFields`** —— 实现返回 `JsonObject`、
   但未调用归一化时，**编译通过、接口视为已实现、字段名全部错误，且没有测试会失败**。
   需要归一化保证的出口一律用 `NormalizedFields`（那个类型在实现侧的模块里造不出来）；
   确属原样透传的，在 KDoc 中写明**属于哪一种透传**。
3. **在 route / collector / controller 中加入设备知识** —— 命令名、字段原名、值域、文件名模板，
   一律放入 profile 或对应 SPI 接口。该约束由 `UpperLayerGoformFreeGuardTest` 守护。
4. **把设备不支持的功能写成注释** —— 用 `Capability` 表达，让 UI 能灰掉、让 route 能回 501。
5. **向 `NORMALIZE_ALWAYS` 增加字段组** —— 每增加一组，排障开关的可观察范围就减少一块。
   正确做法是由消费端兼容多种形态（例：`station_list` 可能是数组，也可能是数组的 JSON 字符串，
   用三态返回处理，**解析失败 ≠ 空列表**）。
6. **在 `profile()` / `capabilities` 中创建对象** —— 守门测试会失败。
7. **在 `createAdapter` 中重复创建 transport 或客户端** —— 会话会互相使对方下线；
   `GoformWifiClient` 的 `lastQrCodeFailure` 与 mapper 的 warn-once 集合都是可变状态。
8. **修改 wire 名或枚举对外取值** —— `Capability.wire`、`Selection.wire`、`AtTransport.name`、
   `PlatformAdapter.name`、`ATChannel.Platform` 名都是对外契约。映射要写在枚举上，
   **不得在下发处调用 `name.lowercase()`**（等价于把 Kotlin 标识符变为 API）。
9. **在日志文案中拼接变动内容** —— `AppLogger.repeatGate` 按「级别+tag+完整消息」60s 折叠，
   拼接 `e.message` / IP / 设备数据会使基数发散、折叠失效、日志量激增。
   `ProbeEnvCollector` 与 `DeviceRuntime` 的 WARN 都是固定文案（带插件 id，**不带异常 message**）。
10. **把「读取失败」当作「读取成功」** —— 温度读失败返回 0 被判定为温度最低、
    无电池设备恒报 50% 被作为真实曲线入库、`station_list` 解析失败被当作所有客户端已离线。
11. **为无调用点的代码设计抽象** —— Samba preexec 提权路径的执行入口没有调用方，
    因此 `PlatformAdapter` 不含 `privilegeEscalation()`。同理**不预先声明空的域成员**。
12. **依赖配置热生效** —— 选型只在组件图构造时计算一次，修改配置需重启后端服务。
13. **顺带统一不一致的返回类型** —— 见第 3 节。这属于修改 route 的响应形状，而非重构。

---

## 11. 验证清单

### 静态检查（必须全部通过）

```
gradlew :core:device-spi:test :core:contract:test
gradlew :core:device-plugins:testDebugUnitTest --rerun-tasks   # 必须，见第 9 节
gradlew :core:goform:test --rerun-tasks                        # 必须，见第 9 节
gradlew :core:device-schema:test :core:common:test :core:controller:test :core:api:test
gradlew :core:assembleBenchmark                                # 出包默认 benchmark 变体
```

⚠ 库模块**没有** benchmark 变体（回落 release），所以单模块编译检查用 `compileDebugKotlin`。

### 真机验证（框架改动**仅在真机上可验证行为不变**）

1. **接口快照**：变更前后各抓取一份 `/api/dashboard` `/api/device` `/api/network` `/api/monitor`，
   比对键集与形状（**允许值变，不允许键集变**）。
2. **`field_coverage`**（`/api/diagnose?fields=1`）：10 个组全部 `queried=true`；
   每组 `missing` 的每一项都要能解释（驻网制式 / 功能未开启 / 字段名不对）；解释不了的一律登记。
   ⚠ `missing` 列的是**设备侧字段名**，与 `hit_source` 的 value 同口径。
3. **15 条写操作**逐条验证：重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 / 手动拨号与挂断 /
   切连接模式 / 改 SSID / 改密码 / 改功率 / 开关 WiFi / 切 WiFi 频段 / 发短信 / 删短信 / 标已读。
   （拨号与「开关移动数据」是**不同的 key、不同的失败路径**，必须分别验证：
   前者没有兜底命令，后者在主命令失败时还会发送 `SET_DATA_ENABLED`。）
   ⚠ **切 WiFi 频段会重启 WiFi 模块，正连着 WiFi 的客户端（包括发起请求的那台）会掉线**。
4. **排障开关**：关掉 `field_normalization_enabled` 重启后，字段应变回设备原名、
   `/api/diagnose` 的 `normalization_enabled` 变 false、
   而**写操作照旧能用**（命令表不受它影响）、`selection` 与 `plugin_id` 不变。
5. **日志**：日志总开关**关着**时，排障 WARN 仍应落地（WARN/ERROR 无视总开关），INFO/DEBUG 不落地。
6. **`createTransport()` 仅在真机上可验证**：`PluginContractTest` 刻意不调用它（`new GoformClient`
   会起 Ktor client、`AppLogger` 又依赖 `android.util.Log`，JVM 单测里只会拿到
   `RuntimeException: Stub!`）。装配层创建完成后即连接设备，该项由真机冒烟测试覆盖。

---

## 12. 已知问题与未完成事项

| # | 事实 | 影响 |
| --- | --- | --- |
| 1 | `TRAFFIC_LIMIT` 的 `monthly_rx_bytes` ← 设备 `monthly_tx_bytes`（**刻意交叉**；实测 ZTE 以「模块看 PC」的视角命名，与 canonical 相反） | 上下行交叉**不应作为缺陷修复**，纠正位置仅在 `ZteGoformProfile` 一处 |
| 2 | `SIGNAL` 组有一批 `lte_*` 注册项与 `CELL_INFO` 组的 `Lte_*` 是同一物理量、大小写/命名不同 | 需要在 **LTE 驻网**下再抓一份 `field_coverage` 才能分清「死注册项」与「NR 驻网所以没返」 |
| 3 | `UpgMode` / `sleep_sysIdleTimeToSleep` / `BearerPreference` 读不回状态，但有 `WriteSpec`（三者都在 `DEVICE_SETTINGS` 的命令表中，即**查询了但设备不返回**；此结论来自真机观察，**尚未核实**） | **可写入、读不出**。UI 应按「读不到 = 未知」显示，不得显示为已关闭 |
| 4 | `DownloadManager.migrateConfig()` 的**地板判据**仍是硬编码（`LEGACY_TEMP_WARN_FLOOR_C = 70f` / `LEGACY_TEMP_CRITICAL_FLOOR_C = 80f`，已提成常量但**没接 tuning**），只有**抬升目标值**接了 `DeviceTuning` | 更换设备时「低于多少才抬升」仍为 F50 的经验值 |
| 5 | `DownloadManager` 有**第二份**电量读法（硬编码 `/sys/class/power_supply/battery/capacity`），服务下载限速第 4 档 | 未接 `Capability.BATTERY` 的声明结果 |
| 6 | 安装器（`scripts/UFI-AXIS-Core-install-Android/goform/`）有**独立的第二套 goform 实现** | **刻意不纳入插件化**。接入新设备时需另写一套。共享持久化键与对外报文格式仍以 `core/contract` 与 API 参考为权威来源 |
| 7 | `ROOT_SHELL` / `AT_CHANNEL` 刻意不做成 `Capability` | 它们是运行时状态，看 `/api/at/status` 的 `connected` 与 shell 的 root 上报 |
| 8 | `PlatformAdapter.restartNetworkStack` 的 `false` **无法区分「本平台不支持」与「本次调用失败」** | 保持现状：唯一调用点将其原样放入 `/api/network/band` 的 `network_restarted`（布尔仅两态）。不支持该动作的平台返回 `false`，**不得抛出异常** |
| 9 | `SprdPlatform.restartNetworkStack` 的 `catch (e: Exception)` 会把 `CancellationException` 也吞掉 | 既有缺陷，保持现状（`ServiceCallAtExecutor` 为先捕获再抛出的实现）。修改它会改变取消语义 |
| 10 | `DeviceProfiles`（`:core:device-schema`）已 `@Deprecated`，但**尚不能删除** | 它位于纯 JVM 模块且被所有模块依赖，委托 `PluginRegistry` 会形成反向环。⚠ 其 KDoc 中「等 `SignalCollector` / `DataScheduler` 的默认参数一并修改」一句**已过期**：这两处已改为无默认值；当前阻止删除的原因仅是「本模块测试仍以注册表作为断言对象」 |

**以下三项已完成**：
① 六个客户端与 writer 收进插件；
② `SignalCollector` / `DataScheduler` 的 `= ZteGoformProfile` 默认参数已改为**非空、无默认值**；
③ `GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST` 已改为 `profile.aclDefaultMode()`。

---

## 13. 速查：接入新设备

```
1. core/device-schema/.../profile/XxxProfile.kt          ← 命令表 + 字段表 + 写命令 + ACL 默认档 + SMS
2. core/device-plugins/.../<厂>/<型号>/XxxPlugin.kt       ← 9 个成员：id / displayName / capabilities /
                                                            profile / createTransport / createAdapter /
                                                            platform / tuning / probe
3. core/device-plugins/.../<厂>/XxxAdapter.kt             ← DeviceAdapter：id + capabilities + 六个域
                                                            （公开构造收 transport + 两份 profile，
                                                             主构造收客户端并降成 internal）
4. 非 goform 协议：实现自有传输层（实现 DeviceTransport 的 14 个方法）
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

**装配层无需修改**：`ComponentFactory.buildNetworkGraph` 只调用
`plugin.createTransport(...)` + `plugin.createAdapter(...)`，两个返回类型都在 `:core:device-spi`。
这是该框架的收益所在，也是 `UpperLayerGoformFreeGuardTest` 守护的边界。
