# 设备适配指南

> 回答一个问题：**接一台新的随身 WiFi / UFI 设备，要改哪些地方。**
>
> 这份文档描述**代码现状**（2026-09-24），不是计划。
> 改造的过程与决策痕迹在 `docs/device-plugin-framework-plan.md`，两份文档的分工是：
> 那份记「为什么这么设计、哪些方案被否掉了」，这份记「照着做」。
> 冲突时**以代码为准**，其次这份，最后那份。

---

## 0. 先看能力边界（避免白干）

**当前框架只能接「goform 系」设备** —— 也就是后台协议是
`POST /goform/goform_set_cmd_process` + `GET /goform/goform_get_cmd_process` 那一套（ZTE 及其同源固件）。

判据在装配层，`ComponentFactory.buildNetworkGraph()`：

```kotlin
val transport = runtime.plugin.createTransport(TransportConfig(...))
val goform = transport as? GoformClient ?: error(
    "插件 ${runtime.plugin.id} 造出的传输层不是 GoformClient —— " +
        "6 个 goform 客户端要到阶段 5/6 才收进插件，在那之前装配层只支持 goform 系插件"
)
```

原因：6 个读写客户端（`GoformSignalClient` / `GoformWifiClient` / `GoformNetworkClient` /
`GoformDeviceClient` / `GoformSimClient` / `GoformSmsClient`）与 `GoformSettingWriter`
的构造参数是 `GoformTransport`（goform 协议专有那一层，比通用的 `DeviceTransport` 多 7 个成员），
它们还没被收进插件。

所以：

- **goform 系新设备（另一个型号 / 另一家 OEM 但同协议）** → 照第 2 节走，工作量是一个 profile + 一个 plugin。
- **非 goform 协议设备** → 框架**还不支持**，先看第 4 节的缺口清单。不要试图绕开那个 `error(...)`。

---

## 1. 分层地图：什么东西该放在哪一层

| 模块 | 放什么 | 硬约束 |
| --- | --- | --- |
| `core/contract` | 对外**冻结区**：canonical 字段名、错误码、`Capability` 枚举、端点常量 | 只增不改。删要先 `@Deprecated` 一版（**零引用项例外**：全仓 grep 确认无消费者可直接删） |
| `core/device-schema` | `DeviceProfile` 接口与**各设备的 profile 实现**（命令表、字段映射、写命令、结构解码、SMS 规格） | 纯 JVM，不依赖 Android |
| `core/device-spi` | 插件**契约层**：`DevicePlugin` / `DeviceTransport` / `PlatformAdapter` / `DeviceRuntime` / `DeviceTuning` / `ProbeEnv` / `AtTransport` / `CpuInfoPlatform` / `TransportConfig` | **不得依赖任何具体协议实现**（`goform` / `collector` / `controller` 一个都不许） |
| `core/device-plugins` | **插件实现**：`PluginRegistry` + 每设备一个 package（`zte/f50/`）+ 平台实现（`platform/sprd/`）+ `probe/` 采集器 | 一个 module、每设备一个 package。**不做「一设备一 Gradle module」** |
| `core/goform` | goform 协议实现：`GoformClient`（实现 `GoformTransport`）+ 6 个客户端 + writer | 对 `device-spi` 必须是 `api(...)` 不是 `implementation` |
| `core/collector` `core/controller` `core/scheduler` `core/api` | 采集 / 控制 / 调度 / route。**不该有设备知识** | 这些层里出现设备字段名、命令名、值域，就是欠账 |
| `core/src`（`ComponentFactory`） | **唯一**允许 `import com.ufi_axis_core.deviceplugins.*` 的地方 | 装配层是唯一知道「有哪些插件」「具体实现是谁」的地方 |

一句话：**设备知识往 `device-schema` 与 `device-plugins` 走，平台知识往 `PlatformAdapter` 走，
其余各层只认接口。**

---

## 2. 接一台 goform 系新设备：最小路径

### 2.1 写 profile（`core/device-schema/.../profile/`）

新建 `XxxProfile.kt`，实现 `DeviceProfile`（参考 `ZteGoformProfile`，它有 1600+ 行，
但**大部分是命令表与字段表，不是逻辑**）。九个成员：

| 成员 | 作用 | 可省？ |
| --- | --- | --- |
| `id` | profile 标识（如 `"zte-goform"`），会出现在 `/api/diagnose` 的 `active` 上 | 否 |
| `displayName` | 人读名 | 否 |
| `readSpecs()` | 字段表：canonical 名 ← 设备原名（+ 单位换算、解码器） | 否 |
| `cmdsFor(group)` | 每个 `FieldGroup` 要发哪几条读命令 | 否 |
| `soloCmds()` | 必须单发的命令（不能与别的合批） | 可（默认空） |
| `fullStatusCmds()` | 全量状态的分批命令表 | 否 |
| `structuralDecoder(group)` | 结构解码器（双重编码、嵌套 JSON 之类） | 可（默认 null） |
| `writeSpec(key)` | `SettingKey` → 写命令（goformId + 参数 + 校验 + 重试策略 + 兜底） | 可（返回 null = 该设备不支持这个写动作） |
| `smsSpec()` | 短信规格（发送参数、列表查询、删除、标已读、编码） | 可（返回 null = 不支持短信） |

外加两个读侧 API 面（都有默认实现、可不覆写）：

- `lteAllBandsMask()` / `nrAllBandsMask()` —— 「解锁全部频段」时下发的掩码。
  **返回 `null` = 该设备不需要显式下发全集**，调用方会折叠成空串（= 不发限制）并打一条 WARN。
- `qrCodeFileNames(chip, ssidIndex)` —— WiFi 二维码图的候选文件名（按尝试顺序，**实现方自己去重**）。
  返回空列表 = 该设备不支持从后台取图。

⚠ `SettingKey` 是**冻结区里的枚举**（29 项）。新设备如果有独有的写动作，
先问「这是不是已有 key 的变体」，确实是新动作才加 key —— 加了就是所有 profile 都要面对的新成员。

### 2.2 写 plugin（`core/device-plugins/.../<厂>/<型号>/`）

```kotlin
object XxxPlugin : DevicePlugin {
    override val id = "vendor-model"            // 稳定标识，进日志、进 /api/diagnose 的 plugin_id
    override val displayName = "Vendor Model"
    override val capabilities = setOf(/* 见 2.3 */)
    override fun profile(): DeviceProfile = XxxProfile
    override fun createTransport(cfg: TransportConfig): DeviceTransport =
        GoformClient(deviceIp = cfg.deviceIp, port = cfg.port, password = cfg.password)
    override fun platform(ctx: Context): PlatformAdapter = SprdPlatform   // 或你自己的
    override fun tuning(): DeviceTuning = DeviceTuning(/* 见 2.4 */)
    override suspend fun probe(env: ProbeEnv): Int { /* 见 2.5 */ }
}
```

`profile()` 与 `capabilities` **不许在里面 new**（`PluginContractTest` 会断言多次调用返回同一个对象）。

### 2.3 声明 capabilities

`Capability` 当前 11 项。**只声明这台设备真正支持的**：

- 10 个「写侧域」：`SMS` / `SIM_SLOT_SWITCH` / `BAND_LOCK` / `CELL_LOCK` / `NETWORK_MODE` /
  `SAMBA` / `USB_DEBUG` / `FOTA` / `PERFORMANCE_MODE` / `TRAFFIC_LIMIT`
  → 声明了就必须在 profile 里有对应的 `WriteSpec`（或 `smsSpec`），
  `PluginContractTest` 单向断言这一条。
- 1 个「纯读侧域」：`BATTERY`（这台设备**有没有电池**）。
  它没有写 route，进 `CapabilityGateTest` 的豁免表。
  ⚠ 不声明它的后果：core 不入库电量、不做电池告警，app 弹窗显示「可能没有电池」提示。
  F50 就是不声明的（它无电池但系统恒报 50%）。

**不该进 capabilities 的东西**：任何**运行时会变**的可用性（AT 通道通不通、root 通道在不在）。
那些由 `/api/at/status` 的 `connected` 与 shell 的 root 上报实时表达 ——
塞进这个静态集合就是「假开关」（集合说能用、实际早断了）。

### 2.4 填 tuning

5 个字段，**全部是设备实测值**，每个的 KDoc 都写明了「哪些近名阈值不归它管」与消费点：

| 字段 | 管什么 | 谁在读 |
| --- | --- | --- |
| `downloadThrottleWarnC` | 下载限速一档（越线砍并发+限速）。F50 = 75f | `DownloadManager` |
| `downloadThrottleCriticalC` | 下载限速二档。F50 = 85f | `DownloadManager` |
| `downloadThrottleForcePauseOffsetC` | 再加这个偏移量 = 暂停全部下载。F50 = 10f（即 95°C） | `DownloadManager` |
| `thermalJitterC` | **用户告警**的回差（不是限速的）。F50 = 3f | `AlertEngine(temperatureHysteresisC)` |
| `bootGraceMs` | 预热期：不入库、不判本地告警。F50 = 90_000L | `DataScheduler(bootGraceDeviceDefaultMs)` |

后两个由 `ComponentFactory` 递成**单个数值**而不是整个 `DeviceTuning`，这是刻意的：
`:core:alert` / `:core:scheduler` 不该认识插件层，而且把整包递进去就等于把三套同名不同义的
温度阈值摆进同一个作用域（见下面那条坑）。


⚠ **这里有个最容易踩的坑**：同一个「温度阈值」概念在仓里是**三件不同的事** ——
下载限速 75/85（本字段）、采集降频/熔断 70/80（`AppSettings.monitorThermal*`，用户可配）、
用户告警 65/75（`AlertEngine`，用户可配，带回差）。
**`downloadThrottleWarnC = 75f` 与 `AlertEngine.temperatureCritical = 75.0` 数值相同、语义相反。**
看到「两边都是 75」就接线 = 静默改掉告警行为，**单测不会红**。

`downloadThrottle*` 只作为**首次创建配置时的默认值**与 `migrateConfig()` 的**抬升目标值**；
用户一旦写过 `PUT /api/download/config`，以用户配置为准。

### 2.5 写 probe()

```kotlin
override suspend fun probe(env: ProbeEnv): Int
```

给这台设备打分，**分数最高者被选中**。约束：

- **只读 `ProbeEnv` 里已备好的廉价指纹**：`cpuInfoPlatform`（已小写的 `/proc/cpuinfo` 全文）、
  `androidBuild`（BRAND/MODEL/DEVICE/MANUFACTURER/SDK_INT）、`goformLdReachable`。
- **禁止 I/O**：不发 AT、不登录设备、不读文件、不发请求。采集已经在 `ProbeEnvCollector` 里做过了。
- 返回 **0 = 不适用**。越大越匹配。
- **不许猜**：没有实测依据的判据不要写。`ZteF50Plugin` 刻意**不做** `Build.*` 匹配，
  因为全仓拿不到 F50 的真机取值 —— 编一个只会得到恒不命中的死代码，或者更糟：命中别家设备。
  F50 的两条判据是 `goformLdReachable`（60 分，唯一准入条件）+ cpuinfo 展锐 marker（+20，只加分）。
- **并列同分**会被按 `PluginRegistry.ALL` 的声明顺序取靠前者 + 打一条 WARN
  （并列意味着判据不足以区分，该补判据）。
- `probe()` 抛异常按 0 分处理、不中断其余插件。

### 2.6 注册

`PluginRegistry`：

```kotlin
val ALL: List<DevicePlugin> = listOf(ZteF50Plugin, XxxPlugin)
val DEFAULT: DevicePlugin = ZteF50Plugin    // 认不出设备时的兜底
```

**编译期注册**，不做运行时动态加载（DexClassLoader / 反射扫 dex 都被明确否掉了）。
`DEFAULT` 的存在理由：认不出型号**不能**导致整个后端不工作。

### 2.7 选型是怎么发生的

`DeviceRuntime.resolve()`，优先级从高到低：

1. `field_normalization_enabled = false`（排障开关）→ 先打一条 WARN，**但插件照样选**
   （归一化能关，传输层与命令表不能关）。
2. `device_profile_id` 非空且匹配 → `CONFIGURED`。**配置永远最高优先级，probe 压根不调。**
   匹配顺序：先 plugin id，再 profile id（**两种写法都认** —— 老部署填的是 profile id）。
3. `device_profile_id` 非空但两种 id 都匹配不上 → `FALLBACK`，回落 `DEFAULT` + WARN。
4. `device_profile_id` 空白 → **probe 打分**。最高分 > 0 → `PROBED`；全部 ≤ 0 → 回落 `DEFAULT`，
   `selection = DEFAULT`（**不是** `FALLBACK` —— 没人配错）。

选型**只在组件图构造时发生一次**，改配置要重启后台服务。**不做运行时热切换。**

---

## 3. 平台适配（`PlatformAdapter`）

什么时候需要写新的：**新设备的 SoC 平台与现有实现不同**（现有只有展锐 `SprdPlatform`）。
与「设备型号」正交 —— 同平台的不同型号可以共用一个 adapter，所以它放在
`platform/<平台>/` 而不是 `<厂>/<型号>/`。

4 个成员：

| 成员 | 说明 |
| --- | --- |
| `name` | 平台名。⚠ `SprdPlatform` 用的是**大写 `"SPREADTRUM"`** —— 它与 `/api/at/platform` 下发的取值同源，改它是对外变更 |
| `atTransports()` | AT 下发通道候选列表，按序 probe，第一条成功即选中 |
| `readTemperature()` | 原始热区读数（摄氏度）。**读不到返 null，不许返 0**（0 会被当成真实读数）。它**不承载任何阈值判断** |
| `restartNetworkStack(at)` | 「这台设备怎么重启网络栈」的知识（发哪几条、什么顺序、等多久、怎么判成功）。**执行通道由调用方注入** —— 策略层（限流/退避/熔断）不能倒置进平台实现 |

**刻意没有的两个成员**（别加回来）：

- `privilegeEscalation()` —— 实测提权只有一条实际路径（ADB 自连 `localhost:5555`，uid 2000），
  Samba `root preexec` 那条的执行入口是**死代码**。不为不存在的路径造抽象。
  另外：`hasRootAccess()` 的真实语义是「ADB 通道可用」，**不是 uid=0**。
- `readBattery()` —— 电量主路径是 Android `BatteryManager`（通用能力，不是平台知识），
  而且它需要 `Context`，会给 adapter 引入第一个 Android 依赖。

⚠ `DevicePlugin.platform(ctx)` **每次调用都新建实例**，所以 **adapter 不能持有状态**。
装配层已经改成整个组件图只造一份（`ComponentFactory.build()` 里的 `val platform`），
但 adapter 本身仍要写成无状态的。

### AT 通道

- `AtTransport` 在 `core/device-spi`（零 import、零 Android 类型）。
- 具体实现在 `platform/<平台>/`（展锐是 `ServiceCallAtExecutor`，fork `/system/bin/service` 打 HAL）。
- `ATChannel`（`core/collector`）是**策略层**：全局互斥、500ms 最小间隔、指数退避、20 次失败熔断。
  它留在 collector，不跟着插件走。
- ⚠ `AtTransport.name` 是对外可见的（`/api/at/status` 的 `method` 字段），**不许改**。

---

## 4. 接非 goform 设备：还缺什么

按当前代码，要做这几件事（**都还没做**）：

1. **6 个读写客户端 + writer 收进插件**，或者为它们抽出协议无关的接口。
   现在它们吃 `GoformTransport`（含 `writeIdempotent` / `isSuccess` / `isAuthFailure` /
   `sha256Hex` 等 goform 事实），装配层那个 `as? GoformClient ?: error(...)` 就是这条缺口的哨兵。
2. `GoformFieldMapper` 那条「双 profile」链路（可空的归一化 profile + 非空的命令表 profile）
   目前也在 goform 模块内。
3. `core/goform` 里仍有两处**直读具体 profile 常量**：`GoformWifiClient` 的
   `ZteGoformProfile.ACL_MODE_BLACKLIST`（两处）。它绕过了 `commandProfile` 那条唯一通道。
4. `SignalCollector` 与 `DataScheduler` 各有一个 `= ZteGoformProfile` 的**默认参数**。
   新设备接上来后这两处仍会用 F50 的 profile —— 必须改成从 `DeviceRuntime` 注入。
   （`DataScheduler` 那处尤其要注意：它驱动全部周期采集。）

在第 1 条完成之前，**非 goform 设备接不进来**，这不是配置问题。

---

## 5. 对外契约（改设备时会被观察到的地方）

### `GET /api/diagnose` → `device_profile`（6 键）

| 键 | 取值 |
| --- | --- |
| `active` | 当前生效的**可空** profile 的 id；关归一化时是**空串** |
| `configured` | `device_profile_id` 的**原值**（可能是 plugin id 也可能是 profile id，不做归一） |
| `normalization_enabled` | 归一化 profile 是否非空 |
| `status` | 四态：`disabled` / `default` / `configured` / `fallback`（**由 configured 与 active 比出来的既有判据**） |
| `plugin_id` | 选中插件的 id，**恒非空**，不受排障开关影响 |
| `selection` | 四态：`configured` / `probed` / `default` / `fallback` |

⚠ **`status` 与 `selection` 可以互相矛盾**：填了 plugin id 时选型是成功的（`selection=configured`），
但 `active` 是 profile id、`configured != active` → `status` 判成 `fallback`。
**看选型结果以 `selection` 为准。**
另外 `selection` 现在隐含「探测通不通」：零配置下 `probed` = 后台可达且有插件认领，`default` = 没人认领。

### `GET /api/device/capabilities`

```json
{ "plugin_id": "zte-f50", "capabilities": ["sms", "sim_slot_switch", "..."] }
```

**数组不是 map**（map 形态下旧客户端分不出「新增能力」与「不支持」）。按枚举声明序。

### 缺能力时的写请求

**501 + `ErrorCode.NOT_SUPPORTED`**，由 `HttpServer` 的 `StatusPages` 统一出口抛出
（`exception<CapabilityMissing>`，必须排在 `exception<Throwable>` 之前）。
门禁当前 **12 处覆盖 10 个域**（同一个域的**所有写入口**都要拦 —— 放行任一入口等于留了绕过门禁的路）。

三种「不可用」**不许混成一个码**：设备不支持 → 501 `NOT_SUPPORTED`；
通道不可用 → 503 `UNAVAILABLE`；参数越界 → 400 `OUT_OF_RANGE`。

### `/api/at/platform` 与 `/api/at/status` 的 `platform`

取值是 `ATChannel.Platform` 的**大写枚举名**：`SPREADTRUM` / `QUALCOMM` / `UNKNOWN`。
判据是 `/proc/cpuinfo` 全文 contains（marker：`sprd`/`spreadtrum`/`unisoc`、`qualcomm`/`qcom`，大小写不敏感）。
它回答「这台机器看起来是什么平台」，**与 `PlatformAdapter.name` 刻意分开**
（否则高通机器会被报成展锐，丢掉「这台其实不是展锐」的信息）。
⚠ `connected = false` 时整个 map 里**没有** `platform` 键。

### battery map（8 键）

`supported` / `level` / `scale` / `percent` / `temperature` / `voltage` / `is_charging` / `plugged`。

`supported = false`（未声明 `Capability.BATTERY`）时**不改任何读数** —— 系统报什么就下发什么
（F50 上 `percent` 仍是 50）。变的是：core **不入库、不做电池告警**，
app 弹窗显示提示、把「状态」与「充电中」两行显示成破折号。

---

## 6. 守门测试会拦你什么

改设备相关代码时，这些会红（都是刻意设的，不要绕）：

| 测试 | 拦什么 |
| --- | --- |
| `PluginContractTest`（11） | id 唯一非空、`DEFAULT ∈ ALL`、`profile()` 多次返回同一对象、`tuning()` 值域自洽、声明了 capability 必须有对应 WriteSpec、`probe` 在后台不可达时必返 0 且对同一 env 幂等、读侧白名单与写侧对照表**交集为空** |
| `CapabilityWireTest`（4） | 11 个 wire 取值**逐个写死** + 值域大小 == 11。**改它就是改 API**，失败消息会告诉你两端要同步 |
| `CapabilityGateTest`（4） | 每个 Capability 至少被一处 route 门禁引用（防「定了不用」）；豁免表有上限且理由要分「还没有 route」与「本质上不该有」两类 |
| `GoformTransportVisibilityGuardTest`（2） | `GoformTransport` 不许出现在 `core/goform` 之外（扫源码、**剥注释后**再判，注释里提名字是允许的）。⚠ 它扫的是别 module 的源码，Gradle 不把那些文件当输入 —— **收尾时要 `--rerun-tasks` 强制跑**，否则会 UP-TO-DATE 假绿 |
| `DeviceRuntimeTest`（18） | 选型的每条路径与日志、`Selection.wire` 写死、有配置时 probe 一次都不许被调用 |
| `SprdPlatformTest`（10） | `AT+SFUN=5/4` 的命令序列、500/2000ms 的等待（虚拟时钟断到精确值）、失败路径 |
| `CpuInfoPlatformTest`（9） | 平台 marker 判据（含 `unisoc` 单独出现时也判展锐） |
| `ZteGoformProfileTest`（125+） | F50 命令表与参数键逐字冻结、频段全集掩码、二维码候选、`writeSpecs` 与 `SettingKey` 逐项对齐 |
| `GoformNormalizeAlwaysTest`（9） | 排障开关的豁免**没有扩散**到其它字段组（除 TRAFFIC_LIMIT 外每组 `assertSame(入参, 出参)`） |

模块用例总数（2026-09-24）：`goform` 103 · `device-spi` 27 · `device-plugins` 21 ·
`contract` 11 · `api` 235 · `common` 180 · `controller` 169 · `device-schema` 190。

---

## 7. 反模式（这些做法会被拦，或者会咬人）

1. **往 route / collector / controller 塞设备知识** —— 命令名、字段原名、值域、文件名模板，
   一律进 profile 或对应 SPI 面。
2. **把设备不支持的功能写成注释** —— 用 `Capability` 表达，让 UI 能灰掉、让 route 能回 501。
3. **往 `NORMALIZE_ALWAYS` 加字段组** —— 每加一组，排障开关就少一块可观察面。
   消费端容忍多形态才是正解（例：`station_list` 可能是真数组、也可能是数组的 JSON 字符串，
   用 `parseStationList()` 的三态返回处理，**解析失败 ≠ 空列表**）。
4. **在 `profile()` / `capabilities` 里 new 对象** —— 守门测试会红。
5. **改 wire 名或枚举对外取值** —— `Capability.wire`、`Selection.wire`、`AtTransport.name`、
   `ATChannel.Platform` 名都是对外契约。映射要写在枚举上，**不要在下发处 `name.lowercase()`**
   （那等于把 Kotlin 标识符变成 API）。
6. **日志文案里拼变动内容** —— `AppLogger.repeatGate` 按「级别+tag+完整消息」60s 折叠，
   拼了 `e.message` / IP / 设备数据会让基数发散、折叠失效、日志刷爆。
7. **把「拿不到」当成「拿到了」** —— 这条坑过三次：
   温度读失败返 0 被判成「最凉」（温控在部分机型上一直失效）、
   无电池设备恒报 50% 被入库成真实曲线、
   `station_list` 解析失败被当成「所有设备都离开了」。
8. **给死代码造抽象** —— Samba preexec 那条提权路径的执行入口没人调，所以 `PlatformAdapter`
   里没有 `privilegeEscalation()`。
9. **指望配置能救** —— 选型只在组件图构造时算一次，改配置要重启后台服务。

---

## 8. 验证清单

### 静态（必须全过）

```
gradlew :core:device-spi:test :core:device-plugins:test :core:contract:test
gradlew :core:goform:test --rerun-tasks        # 必须 --rerun-tasks，见第 6 节
gradlew :core:device-schema:test :core:common:test :core:controller:test :core:api:test
gradlew :core:assembleBenchmark                # 出包默认 benchmark 变体
```

⚠ 库模块**没有** benchmark 变体（回落 release），所以单模块编译检查用 `compileDebugKotlin`。

### 真机（框架改动**只有真机能证明行为不变**）

1. **接口快照**：改造前后各抓一份 `/api/dashboard` `/api/device` `/api/network` `/api/monitor`，
   比对键集与形状（**允许值变，不允许键集变**）。
2. **`field_coverage`**：10 个组全部 `queried=true`；每组 `missing` 的每一项都要能解释
   （驻网制式 / 功能未开启 / 字段名不对）；解释不了的一律登记。
   ⚠ `missing` 列的是**设备侧字段名**，与 `hit_source` 的 value 同口径。
3. **15 条写操作**逐条点：重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 / 手动拨号与挂断 /
   切连接模式 / 改 SSID / 改密码 / 改功率 / 开关 WiFi / 切 WiFi 频段 / 发短信 / 删短信 / 标已读。
   （拨号与「开关移动数据」是**不同的 key、不同的失败路径**，必须分开点。）
4. **排障开关**：关掉 `field_normalization_enabled` 重启后，
   字段应变回设备原名、`/api/diagnose` 的 `normalization_enabled` 变 false、
   而**写操作照旧能用**（命令表不受它影响）。
5. **日志**：日志总开关**关着**时，排障 WARN 仍应落地（WARN/ERROR 无视总开关），
   INFO/DEBUG 不落地。

---

## 9. 已知陷阱与未结欠账（会咬人的部分）

| # | 事实 | 影响 |
| --- | --- | --- |
| 1 | `TRAFFIC_LIMIT` 的 `monthly_rx_bytes` ← 设备 `monthly_tx_bytes`（**刻意交叉**，2026-09-01 实测：ZTE 从「模块看 PC」的视角命名，与 canonical 相反） | 看到上下行交叉**不要当 bug 修** |
| 2 | `SignalCollector` / `DataScheduler` 的 `= ZteGoformProfile` 默认参数 | **接第二台设备时这两处会用 F50 的 profile**。必须改成注入 |
| 3 | `GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST` | 同上，绕过了 `commandProfile` 通道 |
| 4 | `SIGNAL` 组有一批 `lte_*` 注册项与 `CELL_INFO` 组的 `Lte_*` 是同一物理量、大小写/命名不同 | 需要在 **LTE 驻网**下再抓一份 `field_coverage` 才能分清「死注册项」与「NR 驻网所以没返」 |
| 5 | `UpgMode` / `sleep_sysIdleTimeToSleep` / `BearerPreference` 读不回状态，但有 `WriteSpec` | **写得进、读不出**。UI 该按「读不到 = 不知道」显示，不许假装是关 |
| 6 | `migrateConfig()` 的**地板判据**（70/80）仍是字面量，只有**抬升目标值**接了 tuning | 换设备时「低于多少才抬」还是 F50 的经验值 |
| 7 | `DownloadManager` 有**第二份**电量读法（硬编码 `/sys/class/power_supply/battery/capacity`），服务下载限速第 4 档 | 未接 `batteryDeclared` |
| 8 | 安装器（`scripts/UFI-AXIS-Core-install-Android/`）有**独立的第二套 goform 实现**，零 profile 抽象 | **刻意不纳入插件化**（一次性装机流程、不共享组件图）。接新设备时它要**另写一套**。共享持久化键与对外报文格式仍以 `core/contract` + API 手册为真源 |
| 9 | `ROOT_SHELL` / `AT_CHANNEL` 刻意不做成 Capability | 它们是运行时状态，看 `/api/at/status` 的 `connected` 与 shell 的 root 上报 |
| 10 | `DataScheduler` 的行内注释写 `85°C+` / `75°C`，实际默认阈值是 80 / 70 | 排障时注释会误导 |

---

## 10. 一页速查：接一台 goform 系新设备

```
1. core/device-schema/.../profile/XxxProfile.kt     ← 命令表 + 字段表 + 写命令 + SMS
2. core/device-plugins/.../<厂>/<型号>/XxxPlugin.kt  ← 组装：profile / transport / platform / tuning / probe / capabilities
3. core/device-plugins/.../PluginRegistry.kt         ← ALL 加一项（DEFAULT 通常不动）
4. 如果 SoC 平台也是新的：core/device-plugins/.../platform/<平台>/  ← 新 PlatformAdapter + AtTransport 实现
5. 如果有新的写动作：core/device-schema/.../DeviceProfile.kt 的 SettingKey ← 加枚举项（冻结区，慎重）
6. 如果有新的功能域：core/contract/.../Capabilities.kt ← 加枚举项 + wire 名 + route 门禁 + 两端镜像
7. 跑第 8 节的静态清单（goform 那条记得 --rerun-tasks）
8. 真机过第 8 节的五项
9. 第 9 节的 #2 #3 是接第二台设备**必须先清**的欠账
```

**不需要改**：`core/api` 的 route（除非有新功能域）、`core/collector`、`core/controller`、
`core/scheduler`、`HttpServer`、`DataHub`、app / web 的业务代码
（除了新功能域的 capability 镜像与置灰）。

**装配层 `ComponentFactory` 只需要改一处**：`PluginRegistry.ALL` 已经覆盖了，
它本身不需要为新设备改动 —— 这是这套框架的收益所在。
