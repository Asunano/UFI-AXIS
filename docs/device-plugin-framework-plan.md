# 设备插件化框架落地计划

> 目标形态：**中间控制层 + 分层 SPI + 插件实现**。`ZteGoformProfile` 这类文件成为插件的一部分，
> 上层（route / collector / controller / scheduler / app / web）不再持有任何设备知识。

---

## 0. 文档用法

> **这份文档是推断，不是事实。** §2 的现状盘点、每一处 `文件:行号`、每一条任务的拆法，
> 都是基于 2026-09-21 当日代码的**阅读结论**，其中有一部分（§2 E 类平台层）连逐个打开核对都没做。
> 实际动手时对不上是常态，不是例外 —— 所以先读 **§13（偏差处理）**，
> 知道「发现对不上之后做什么」再开始改。改完怎么证明没改坏，见 **§14（校验体系）**。

- 每个任务前的方括号就是状态，改状态**只改这个文件**，不要另建进度表：

  - `[ ]` 未开始
  - `[~] ` 进行中
  - `[x]` 已完成（必须同时满足该阶段「验收」全部条目）
  - `[!]` 受阻（后面追一行 `→ 阻塞原因`）
  - `[-]` 已放弃（后面追一行 `→ 放弃理由`）
- 阶段之间是**硬依赖**：前一阶段没到 `[x]`，不要开下一阶段。唯一例外是阶段 3（能力集）可以与阶段 4 并行。
- 每完成一个阶段，在「9. 变更记录」追一行。
- 本文件里的 `文件:行号` 是撰写时（2026-09-21）的位置，代码动过之后按符号名找，不要盲信行号。

---

## 1. 目标与非目标

### 目标

1. 新增一台**同协议、字段有差异**的设备 = 新增 1 个文件（profile）+ 注册表一行。
2. 新增一台**异协议**设备（登录/签名/路径都不同）= 新增 2 个文件（profile + transport）。
3. 新增一台**异平台**设备（非展锐）= 新增 3 个文件（+ platform adapter）。
4. 设备不支持的功能，**在请求发出前**就被挡住，并且前端能提前灰掉入口。
5. 插件写漏一项 → **编译期或单测失败**，不是运行时静默发错命令。

### 非目标（明确不做，避免被「插件」这个词带偏）

- **不做运行时动态加载**（DexClassLoader / ServiceLoader 反射扫 dex / 外部 apk）。
  设备类型不会由用户在运行时新增；动态加载换来的是签名校验、ProGuard keep、混淆后反射失效、
  热更新合规一堆问题，收益为零。这里的「插件式」= 编译期注册 + 单点选型 + 强制契约。
- **不把 profile 降级成 JSON / YAML / DSL 数据文件**。现有 `decode` / `validate` /
  `structuralDecoder` 是真代码（base64+GBK 解码、带谓词的数组元素选择、按 `ChipIndex` 生成输出键），
  换成数据格式会退化成自己发明一门查询语言 —— `DeviceProfile.kt:39` 的注释已经记录了这个结论。
- **不改对外 HTTP 契约**（`core/contract` 的 `DeviceFields` / `Endpoints` / `ErrorCode` 冻结）。
  唯一新增是一个只读的能力集端点。
- **不动 app / web 的既有页面结构**，阶段 3 只在已有开关上加「不支持 → 灰掉 + 说明」。

---

## 2. 现状盘点（as-is）

### 2.1 已经存在的抽象（好的部分）

| 抽象 | 位置 | 覆盖范围 |
| --- | --- | --- |
| `DeviceProfile` | `core/device-schema/.../DeviceProfile.kt:14` | 读字段映射 / 查询命令表 / 写命令表 |
| 注册表 `DeviceProfiles` | `core/device-schema/.../profile/DeviceProfiles.kt:23` | `DEFAULT` / `ALL` / `byId()` |
| 选型 | `core/src/.../service/ComponentFactory.kt:794` `resolveDeviceProfile()` | 配置优先 + 未知回落 + WARN |
| 传输契约 `GoformGateway` | `core/goform/.../GoformGateway.kt:27` | 登录 / 查询 / POST / QoS（14 个方法） |
| AT 通道契约 `AtTransport` | `core/collector/.../at/AtTransport.kt:10` | `probe()` / `sendCommand()` / `reset()` |
| 归一化引擎 + 覆盖率诊断 | `core/device-schema/.../FieldNormalizer.kt` | `normalize()` / `coverage()` / 脱敏 |
| F50 实现 | `core/device-schema/.../profile/ZteGoformProfile.kt`（撰写时 1099 行；2026-09-22 已 1396 行） | 10 个 FieldGroup、`SettingKey` 撰写时 18 个 → **现 28 个**、5 个结构解码器 |

### 2.2 欠账清单（这就是阶段 0~4 要清的）

**A. 写命令绕过 profile —— 19 个调用点**（去重后约 15 个命令）

> **2026-09-22 阶段 0 执行完一轮后的实际归属**（条目一条不删 —— 决策痕迹要留着）。
> 「19」这个数字本身没数错，错的是它隐含的假设「19 个调用点 = 19 个 `writer.write`」：
>
> - **11 处改走 writer**（`36fa526`，Device / Network / Wifi 三个客户端）→ **已完成**
> - **WiFi 的 3 处 `setAccessPointInfo` 合并成一个 `WIFI_AP_CONFIG`**（`090fcad` 登记 + `118ed84` 接线）
>   → **已完成**。它们是**同一条设备命令的三种调用意图**，不是三个命令
> - **短信 3 处走 `profile.smsSpec()`**（`2d92e05` 立契约）→ **profile 侧已完成，客户端接线进行中**
> - **`SET_USB_NETWORK_PROTOCAL` 整条删除**（`0f3f504`）→ **已完成（删除，不是迁移）**
> - **登录 / 登出 3 处**留给阶段 1（传输层握手）→ **未开始，按原计划不属阶段 0**

- `GoformDeviceClient.kt:36` `REBOOT_DEVICE` → 已完成（`36fa526`，`SettingKey.REBOOT`）
- `GoformDeviceClient.kt:42` `FACTORY_RESET` → 已完成（`36fa526`）
- `GoformDeviceClient.kt:48` `SHUTDOWN_DEVICE` → 已完成（`36fa526`）
- `GoformDeviceClient.kt:56` `SET_USB_NETWORK_PROTOCAL`（参数 `usb_network_protocal`）
  → **2026-09-22 批 3 已删除**：`setUsbMode` 全仓零调用（无 route、无 app 入口），
  按「没用了就彻底删」的纪律连 `SettingKey.USB_MODE` + WriteSpec 一起删掉，见 §15。

- `GoformDeviceClient.kt:85` `CHANGE_PASSWORD`（`oldPassword`/`newPassword` = SHA256 大写）→ 已完成（`36fa526`）
- `GoformNetworkClient.kt:38` `CONNECT_NETWORK` / `DISCONNECT_NETWORK`（变量 `primaryId` 拼出来的）
  → 已完成（`36fa526`，`MOBILE_DATA` 的 `commandOf`）
- `GoformNetworkClient.kt:40` `SET_DATA_ENABLED`（`data`=1/0，是上一条的兜底）
  → 已完成（`36fa526`，`MOBILE_DATA.fallback`）
- `GoformNetworkClient.kt:63` `CONNECT_NETWORK` → 已完成（`36fa526`，`PPP_DIAL`）
- `GoformNetworkClient.kt:69` `DISCONNECT_NETWORK` → 已完成（同上）
- `GoformNetworkClient.kt:75` `SET_CONNECTION_MODE`（参数 `ConnectionMode`）→ 已完成（`36fa526`）
- `GoformWifiClient.kt:310 / 359 / 391` `setAccessPointInfo`（三个调用点，参数集不同）
  → **已完成（合并，不是三份搬迁）**：`SettingKey.WIFI_AP_CONFIG` 一份 `WriteSpec`
  + 客户端三个 `internal` 纯函数只管「往 params 里放哪几个键」（`090fcad` / `118ed84`）。
  合并的前提是先修掉 `Password` 管线的 bug，见 §15 的 P0-2「已解除」。
- `GoformWifiClient.kt:347` `SET_WIFI_POWER`（`wifiPowerLevel`）→ 已完成（`36fa526`）
- `GoformWifiClient.kt:374` `switchWiFiChip`（`ChipEnum` / `GuestEnable`）
  → 已完成（`36fa526`，与下一条合并成 `WIFI_ENABLED` 的 `commandOf`）
- `GoformWifiClient.kt:379` `switchWiFiModule`（`SwitchOption`）→ 同上
- `GoformSmsClient.kt:200` `DELETE_SMS` → `SmsSpec.deleteParams`（`2d92e05`），客户端接线进行中
- `GoformSmsClient.kt:206` `SET_MSG_READ` → `SmsSpec.markReadParams`（`2d92e05`），同上
- `GoformSmsClient.kt:264` `SEND_SMS` → `SmsSpec.sendParams`（`2d92e05`），同上
  ⚠ 这三处的行号已经漂了（`GoformSmsClient` 正在接线中，现为 `:251` / `:257` / `:317`）——
  按符号名找，别信行号（§0 的纪律）。

登录/登出三处（`GoformClient.kt:275 / 290 / 786`）**不进 `SettingKey`** —— 它们属于传输层握手，归阶段 1。

**B. 读命令表只搬了一半**

> **状态（2026-09-22 批 13 之后）**：**0.4a（`9fa0473`）与 0.4b（`680fbae`）均已落地** ——
> 0.4a 做的是**结构准备**：mapper 改双 profile、6 处 fallback 提成具名常量、加守门测试，
> **取值行为一字未变**；0.4b 才是「删并行路径」那一半 —— `cmds()` 切到 `commandProfile`、
> **6 个 fallback 常量与汇总表全部删除**、`getSignalInfo()` 的 16 项与 `getFullStatus()` 的 96 项
> 收进 profile。**这一类欠账在代码层面已清完**，剩下的 7 处硬编码查询是**刻意保留**的
> 轻量/分批查询（各处已加注释说明），2 处 `querySingle` 未动。
> 0.4b 标 `[~]` 而不是 `[x]`，卡的是第 3 层（无真机，要重抓 `field_coverage`），见 §4 的 0.4b。
> 本节标题的「只搬了一半」这个说法仍不准确（工作性质是删并行路径，不是搬），见下面那段 2026-09-21 的修正。

> **2026-09-21 按真机基线修正**：这个标题不准确。§16 显示 10 个 `FieldGroup` 的
> `queried` **全部为 true**，说明 `cmdsFor()` 每一组都已经登记了命令 ——
> 客户端里那些硬编码 `client.query(listOf(...))` 不是「profile 缺失时的补位」，
> 而是一条**与 profile 并行存在的第二条查询路径**（业务查询走它，覆盖率诊断走 profile）。
>
> 所以 0.4 的工作性质不是「搬」，而是「**删掉并行路径并确认两边 cmd 集合一致**」。
> 一致性没确认就删 = 业务查询的字段集悄悄变了 = hit 变 missing（§14.3 第 4 条判据会抓到）。
> 唯一真正没有 profile 对应物的是 `getFullStatus()` 那 **96** 个字段名
> （**2026-09-22 实测，原文写的 88 是错的**：三批分别 30 / 29 / 37。
> 源码里的注释本身也不准 —— KDoc 写「75+ 字段」、批内注释写 `(30 字段)` / `(28 字段)` / `(30+ 字段)`，
> 只有第一批对得上。改这一项时按实际数字走，别照抄任何一处注释）。

- `GoformSignalClient.kt:59` 信号 16 个 cmd（→ **0.4b 已收进 `cmdsFor(SIGNAL)`**）；
  `:90` `network_information,Lte_ca_status`；`:96` 身份 5 个；`:128` 版本 3 个；`:146` 流量；
  `:168/180/193` `getFullStatus()` 三批共 **96** 个字段名（30 / 29 / 37，
  → **0.4b 已收进 `DeviceProfile.fullStatusCmds()`**）；`:270` `neighbor_cell_info`
  （未标注的几处是**刻意保留**的轻量查询，见 §4 的 0.4b）
- 兜底列表（profile 没登记时用）：0.4a 曾提成 `internal companion object` 里的 6 个具名常量
  （`IDENTITY_FALLBACK_CMDS` / `CELL_INFO_FALLBACK_CMDS` / `LAN_SETTINGS_FALLBACK_CMDS` /
  `DEVICE_SETTINGS_FALLBACK_CMDS` / `BAND_STATUS_FALLBACK_CMDS` / `TRAFFIC_LIMIT_FALLBACK_CMDS`）
  + 一张 `FALLBACK_CMDS: Map<FieldGroup, List<String>>` 汇总表。
  → **2026-09-22 批 13（`680fbae`）全部删除**（companion object 整块移除，6 处传参一并去掉）。
  删除前用脚本逐组按 token 序列比对过，六组逐字含顺序一致，所以**删掉不改变任何实际发出的 cmd**。
  现在命令表只有一份（profile 的 `cmdsFor()`），由 `GoformCommandTableGuardTest` 的**内容冻结**守门
- `GoformWifiClient.kt:39` `queryWiFiModuleSwitch,queryAccessPointInfo`；`:205` 12 个 `wifi_*`；
  `:252` `station_list`（走 `client.querySingle`）；`:278` `queryDeviceAccessControlList`（同）
- `GoformSmsClient.kt:68 / 217` 短信列表的全部查询参数（`mem_store=1&tags=10&order_by=...`）


**C. 设备值域写死在客户端**

> **状态（2026-09-22）：部分完成。** 四项里两项已搬、一项已裁决推后、一项需要新 API 面。

- `GoformNetworkClient.kt:28-31` `LTE_ALL_BANDS` / `NR_ALL_BANDS`（注释写的是 **ZTE MU300** 的频段表）
  → **2026-09-22 批 3 实测：这两个常量被跨模块引用，不是简单搬迁**（`NetworkController.kt:122/127`），
  而且 `core/contract/Enums.kt:145-146` 还有第三份同值拷贝。升 P0-1，见 §15。
  → **已裁决：推到阶段 2**（用户裁决，与 `NetworkController` 的设备知识一起清）。
  2026-09-22 复核仍然成立：常量在 `GoformNetworkClient.kt:29/31`、本文件用它的地方在 `:92/:93`
  （`unlockAllBands`），`NetworkController.kt:122/127` 跨模块直读，`Enums.kt:145-146` 第三份拷贝仍零引用。

- `GoformWifiClient.kt:310-341` `AuthMode=WPA2PSK` / `EncrypType=CCMP` / `ApIsolate=0` / `AccessPointIndex=0`
  → **已完成**（`090fcad`）：四个缺省值成了 `ZteGoformProfile` 的 `AP_AUTH_DEFAULT` /
  `AP_ENCRYP_DEFAULT` / `AP_CHIP_INDEX_DEFAULT` 与 encode 里恒发的 `ApIsolate=0` /
  `AccessPointIndex=0`，客户端里已经没有这些字面量。
- WiFi 密码：写 base64(UTF-8)、读 base64(GBK)（`GoformClient.kt:865 / 868`）
  → **已完成**（写侧 `090fcad` 进 `WIFI_AP_CONFIG.encode` 的 `base64Utf8`；
  读侧的字符集不对称由 `858a9c9` 一并修掉，两边判据都改成「UTF-8 优先、GBK 回落」）。
  ⚠ 但**两份解码器仍各写一份**（`GoformClient.decodeDeviceText` 与
  `ZteGoformProfile.WIFI_PASSWORD_DECODER`），合并是另一件事，见 §15。
- 二维码文件名模板 `{chip}_ssid{n}_qrcode_wifikey`（`GoformWifiClient.kt:69`）
  → **未搬**：`DeviceProfile` 没有「文件路径模板」这个 API 面，临时加一个方法违反
  「不要自己发明 API」的纪律。**归阶段 2**，见 §15 的 P1-5。

**D. 传输层无法替换**

- `GoformSettingWriter.kt:27` 构造参数是**具体类** `GoformClient`，不是 `GoformGateway`
- 6 个业务客户端（Signal/Wifi/Network/Device/Sms/Sim）同样吃具体类（`ComponentFactory.kt:761-766`）
- 成功判据是字符串子串匹配（`GoformClient.kt:801-820`：`"result":"success"` / `"code":0` / …）
- 登录哈希与 AD 签名算法写死在 `GoformClient`（`:283` / `:753-778`）

**E. 平台层零抽象**（以下位置来自子代理检索，我本人未逐个打开核对，动手前先确认）

- `core/collector/.../at/ServiceCallAtExecutor.kt` 整个文件是展锐 HIDL 硬编码
  （`vendor.sprd.hardware.tool.IToolControl` 事务码 3 / `vendor.sprd.hardware.log.ILogControl` + `miscserver`），
  分支判据是 `Build.VERSION.SDK_INT` 而不是平台
- `core/collector/.../at/ATChannel.kt:28` 已有 `enum Platform { SPREADTRUM, QUALCOMM, UNKNOWN }`
  + `/proc/cpuinfo` 探测，但**结果只用于诊断上报**，不参与选实现
- `core/common/.../util/SambaRootShell.kt` 提权机制 = F50 固件的 Samba root preexec 特性
  （含写死 `192.168.0.1` + 共享名候选表）
- `core/controller/.../network/NetworkController.kt:183` 网络栈重启 = `AT+SFUN=5/4`
- 实测调参散在四处：`DownloadManager.kt:113`（温度 75/85）、`AlertEngine.kt:279`（温度阈值 + 3°C 抖动）、
  `DataScheduler.kt:1867`（boot grace 90s）、`ShellQoS.kt:27`（root 许可数 5）

**F. 没有能力集概念**

- 全仓没有 capability / supported 的表达。`NetworkRoutes.kt:186` 那句「AT+ZPREFMOD 在此设备不支持」只是注释。
- `/api/diagnose` 下发 `device_profile` 四态（configured / default / fallback / disabled，
- `/api/diagnose` 下发 `device_profile` 四态（configured / default / fallback / disabled，
  `HttpServer.kt:566`，该文件在 **`core/network`**（`core/network/.../core/server/HttpServer.kt`）
  **不是** `core/api`），但不下发能力集，所以 app / web 无法提前灰掉不支持项 ——
  这与既定的「开关不许是假开关」口径直接冲突。

---

## 3. 目标架构

```
上层：route / collector / controller / scheduler
      只认 canonical 字段 + SettingKey + Capability
                    │
                    ▼
中间控制层  DeviceRuntime                    ← 新增，唯一知道「当前是哪台设备」
  · 选型：配置指定 → probe 打分 → 默认回落 + WARN
  · 能力门禁：require(Capability) → NOT_SUPPORTED
  · 生命周期：随组件图重建整体换插件（缓存天然不跨设备）
                    │  只依赖 SPI，不 import 任何具体插件
                    ▼
SPI 层（分层接口）
  1 DeviceProfile    字段/命令映射      【已有，需补齐】
  2 DeviceTransport  协议/会话/签名/成功判据  【已有=GoformGateway，需改名+补齐】
  3 AtTransport      AT 下发通道        【已有，接进插件选型】
  4 PlatformAdapter  提权/传感器/网络栈重启   【新增】
  5 DeviceTuning     实测阈值调参       【新增】
  6 Capability       能力集             【新增】
                    ▲
插件实现层  DevicePlugin（聚合根）
  ZteF50Plugin = ZteGoformProfile + GoformTransport + SprdPlatform + F50Tuning + caps
  XxxPlugin    = …
```

### 3.1 模块划分

- `core/device-schema`（现有，纯 JVM）：`DeviceProfile` / `FieldSpec` / `FieldNormalizer` / `SettingKey` / `Capability`。
  保持纯 JVM —— 它的单测跑得快，是整套东西的语义基线。
- `core/device-spi`（**新增**，Android library）：`DevicePlugin` / `DeviceTransport` / `PlatformAdapter` /
  `DeviceTuning` / `TransportConfig` / `ProbeEnv`。需要 `Context` 与 Ktor，所以不能放在纯 JVM 模块。
- `core/device-plugins`（**新增**，Android library）：**一个 module、每设备一个 package**。
  不做「一个设备一个 Gradle module」：构建图膨胀换不来任何隔离收益，隔离靠守门测试。

### 3.2 SPI 骨架（阶段 2 落地时以此为准）

```kotlin
// core/device-spi/.../DevicePlugin.kt
interface DevicePlugin {
    val id: String                      // "zte-f50"，稳定标识，进日志与配置
    val displayName: String
    val capabilities: Set<Capability>
    fun profile(): DeviceProfile
    fun createTransport(cfg: TransportConfig): DeviceTransport
    fun platform(ctx: Context): PlatformAdapter
    fun tuning(): DeviceTuning
    /**
     * 匹配置信度：0 = 不适用，越大越匹配。
     * 硬约束：只读 [ProbeEnv] 里已备好的廉价指纹，禁止发 AT、禁止登录设备、禁止 I/O。
     */
    suspend fun probe(env: ProbeEnv): Int
}

/** 廉价指纹。由 DeviceRuntime 采一次后共享给所有插件 —— 不让每个插件各自去读。 */
class ProbeEnv(
    val cpuInfoPlatform: String?,   // /proc/cpuinfo 里的平台串（已小写）
    val androidBuild: BuildInfo,    // BRAND / MODEL / DEVICE / MANUFACTURER / SDK_INT
    val goformLdReachable: Boolean, // GET /goform/..?cmd=LD 是否 200（免登录、免 profile）
)
```

`goformLdReachable` 是绕开 `DeviceProfiles.kt:14` 记的「鸡生蛋」问题的关键：`LD` 免登录、
不需要 profile 就能取，足以区分「这是不是一台 goform 后台的设备」。

```kotlin
// core/device-spi/.../PlatformAdapter.kt
interface PlatformAdapter {
    val name: String
    /** 本平台可用的 AT 通道，按优先级返回；ATChannel 仍负责限流/退避/熔断。 */
    fun atTransports(): List<AtTransport>
    /** 提权策略（Samba preexec / su / adb-only）。不可用时返回 null，不抛。 */
    fun privilegeEscalation(): PrivilegeStrategy?
    /** 热区读法：返回摄氏度，读不到返回 null（不要返回 0 —— 0 会被当成真实读数）。 */
    suspend fun readTemperature(): Float?
    /** 电池：无电池设备返回 null，由此驱动 Capability.BATTERY。 */
    suspend fun readBattery(): BatteryReading?
    /** 网络栈重启（F50 是 AT+SFUN=5/4）。不支持返回 false。 */
    suspend fun restartNetworkStack(): Boolean
}

// core/device-spi/.../DeviceTuning.kt
data class DeviceTuning(
    val thermalWarnC: Float,        // F50: 75
    val thermalCriticalC: Float,    // F50: 85
    val thermalJitterC: Float,      // F50: 3
    val bootGraceMs: Long,          // F50: 90_000
    val rootShellPermits: Int,      // F50: 5
)
```

### 3.3 中间控制层骨架

```kotlin
// core/device-spi/.../DeviceRuntime.kt
class DeviceRuntime private constructor(
    val plugin: DevicePlugin,
    val profile: DeviceProfile?,     // null = 排障开关关掉了归一化（保留现有语义）
    val transport: DeviceTransport,
    val platform: PlatformAdapter,
    val tuning: DeviceTuning,
    val selection: Selection,        // CONFIGURED / PROBED / DEFAULT / FALLBACK，进 /api/diagnose
) {
    fun has(c: Capability): Boolean = c in plugin.capabilities
    /** route 的统一门禁：不支持直接抛，由 route 层统一翻成 NOT_SUPPORTED。 */
    fun require(c: Capability) { if (!has(c)) throw CapabilityMissing(c) }

    companion object {
        suspend fun resolve(settings: AppSettings, ctx: Context): DeviceRuntime
    }
}
```

`resolve()` 吸收现在 `resolveDeviceProfile()`（`ComponentFactory.kt:794`）的三条规则并补 probe：

1. `fieldNormalizationEnabled = false` → 仍然选插件（传输与写命令不能关），但 `profile = null`；
2. `deviceProfileId` 非空 → 按 id 取；取不到 → 默认插件 + WARN（**认不出不能导致整个不工作**）；
3. `deviceProfileId` 为空 → 采一次 `ProbeEnv`，取 `probe()` 最高分；全 0 → 默认插件 + WARN。

### 3.4 阶段 2 的设计方向：跨模块收不动的，允许各设备独立持有（2026-09-22 用户裁决）

用户原话：

> **如果某些功能无法做到跨模块的话就独立吧，独立不同的设备**

这条话定的是**抽象的边界**：不强求所有设备知识都收进一套统一抽象。判据是「收敛的代价」——
当一处设备知识为了进统一抽象必须把依赖拉到别的模块（典型例子：频段全集 `LTE_ALL_BANDS` /
`NR_ALL_BANDS` 被 `core/controller` 的 `NetworkController` 直读，见 §15 的 P0-1），
那就允许它**由各设备插件各自独立持有一份**，而不是为了「统一」制造一条新的跨模块耦合。

- 允许：同一类设备知识在两个插件里各写一份（重复是显式的、每份都只服务自己那台设备）。
- 不允许：为了让两个插件共用一份，把 `core/controller` / `core/collector` 拉进
  `device-spi` 的依赖里（那是 §11.7 已经判过死刑的成环方向）。
- 判据落地成一条问题：**「这处知识换设备时会一起换吗？」** 会 → 进插件（哪怕重复）；
  不会（是我们自己的策略）→ 留在上层，别往插件里塞。

这条不是「以后再说」的备注，而是阶段 2 拆 `DevicePlugin` 时的取舍依据：
遇到收不动的，按本条独立，不记 P0、不停手。

---

## 4. 阶段 0 — 补齐命令表（无新概念，纯搬运）

**为什么先做**：只要还有命令绕过 profile，插件化就是假的 —— 换设备时那些命令会**静默发错**。
这一阶段不引入任何新类型，风险最低，且有 `ZteGoformProfileTest` 兜底
（撰写时 1325 行；2026-09-22 已 **1883 行 / 117 条 `@Test`**）。

### 任务

> 状态口径（**2026-09-22 批 13（0.4b，`680fbae`）执行完后复核**）：
> 每一条都按当天的代码现查现写，不照抄上一轮。行号一律以符号名为准。
> 阶段 0 的**代码工作已全部落地**，但**没有一条可以标 `[x]`**：0.4b 的第 1、2 层已过、
> **第 3 层（接口快照）未验**（无真机）→ 按 §14.3 最后一段与 §14.6 的纪律标 `[~]`。
> 剩下的是 **0.5 的两项（已裁决推阶段 2）**、**0.8 的装配层与真机那一半**，
> 以及一份**真机验证待办清单** —— 见 §9 末尾新增的「**阶段 0 收尾盘点**」。

- `[x]` 0.1 `SettingKey` 补齐写命令（`core/device-schema/.../DeviceProfile.kt`）
  → 实测 `SettingKey` 现为 **28 项**（18 原有 + 10 新增；`USB_MODE` 已删、`WIFI_SSID`/`WIFI_PASSPHRASE`
  最终合并为一个 `WIFI_AP_CONFIG`，理由写在该 key 的 KDoc 里）
- `[x]` 0.2 `ZteGoformProfile` 为新增 key 登记 `WriteSpec`
  → 实测 `writeSpecs` 与 `SettingKey` 逐项对齐（28 : 28），无孤立 key
- `[x]` 0.3 4 个客户端的硬编码调用点改走 `writer.write(Checked)(...)`
  → **11 处**（`36fa526`）+ WiFi 的 3 处 `setAccessPointInfo`（`118ed84`，走 `WIFI_AP_CONFIG`）。
  短信 3 处按 §11.2 走 `smsSpec`、不进 `SettingKey`（归 0.7）。
  实测 `core/goform/src/main` 里 `"goformId" to` 只剩 **1** 处：
  `GoformClient` 的 `LOGOUT`（归阶段 1）。另有 2 处字符串形态的登录命令
  （`GoformClient.kt:275/290` 的 `setBody("isTest=false&goformId=LOGIN…")`），该 pattern 抓不到
- `[x]` 0.4a **mapper 改双 profile + 把两份命令表钉在一起**（`9fa0473`，纯结构准备、零行为变化）

  **做了什么**（每条都已回代码核过）：
  - `GoformFieldMapper` 的构造参数拆成 `normalizeProfile: DeviceProfile?`（可空）
    + `commandProfile: DeviceProfile`（非空），文件头写明「为什么必须是两个」并把
    `normalization_enabled` 那条链（`mapper.profileId → GoformSignalClient.profileId →
    DataHub.deviceProfileId → HttpServer 的 activeProfile != null`）整段记进注释
  - `enabled` / `profileId` / `normalize()` / `maskDump()` / `coverageReport()` **全部继续只读
    `normalizeProfile`**；`cmds()` 的取值行为**一字未变**
    （仍是 `normalizeProfile?.cmdsFor(group)?.takeIf{…} ?: fallback`，`GoformFieldMapper.kt:117-118`）
  - `GoformSignalClient` 的 6 处 fallback 列表提成 `internal companion object` 里的具名常量
    （内容逐字符原样）+ 一张 `FALLBACK_CMDS` 汇总表，供守门测试引用真身
  - 新增 `GoformCommandTableGuardTest`（**8** 条）：客户端 fallback 与 `cmdsFor()` 逐组比对，
    断言形状是「**实测不一致的组 == 已登记的例外集合**」双向相等，
    且**例外集合恰好等于 `{CELL_INFO}`**；另有「关掉归一化后 `cmds()` 仍返回 fallback」
    与「`coverageReport` 短路时一条查询都不发」两条把排障开关钉住
  - `ComponentFactory` 的两行（`:766` `GoformSignalClient(goform, profile)` / `:767`
    `GoformWifiClient(goform, profile)`）**代码未变，只加了 5 行注释**（commit 里该文件 +5/-0）；
    非空回落 `?: DeviceProfiles.DEFAULT` 放在两个客户端内部
    （`GoformSignalClient.kt:38` / `GoformWifiClient.kt:33`），口径同 `GoformSettingWriter`

- `[~]` 0.4b **读命令表已真正切到 `commandProfile`，fallback 全部删除**（`680fbae`，2026-09-22 批 13）
  —— **代码层面完成，但第 3 层（接口快照）未验，所以是 `[~]` 不是 `[x]`**（§14.3 最后一段）。

  **做了什么**（每条都已回代码核过，按符号名找）：
  - `GoformFieldMapper.cmds(group)` **删掉 `fallback` 参数**，实现改成
    `commandProfile.cmdsFor(group)`（`GoformFieldMapper.kt:115`）；
    新增 `fullStatusCmds(): List<List<String>>` 同样只读 `commandProfile`（`:123`）
  - `GoformSignalClient` 的 6 个 `*_FALLBACK_CMDS` 常量 + `FALLBACK_CMDS` 汇总表 + 6 处传参
    **全部删除**（`internal companion object` 整块移除；现在全仓 `core/**` 里再 grep `FALLBACK`
    只剩三处无关命名 + 守门测试注释里的历史说明）。删之前用脚本逐组按 token 序列比对过：
    IDENTITY 20 / CELL_INFO 10 / LAN_SETTINGS 9 / DEVICE_SETTINGS 16 / BAND_STATUS 2 /
    TRAFFIC_LIMIT 10 —— **六组全部逐字含顺序一致**，所以删掉不改变任何实际发出的 cmd
  - `getSignalInfo()` 的 16 个字面量 → `fields.cmds(FieldGroup.SIGNAL)`
    （`GoformSignalClient.kt:62`，16 项逐字同序核对过）
  - `DeviceProfile` 新增 `fun fullStatusCmds(): List<List<String>> = emptyList()`
    （`DeviceProfile.kt:63`），`ZteGoformProfile` 用私有 `FULL_STATUS_CMD_BATCHES` 实现
    （`ZteGoformProfile.kt:501` / `:510`）
  - `getFullStatus()` 改成 `for (batch in fields.fullStatusCmds())` 逐批发
    （`GoformSignalClient.kt:180-186`），三批实测 **30 / 29 / 37 = 96**

  **两个设计裁决（理由要留着，别下次又想改）**：
  - **签名用外层列表表达批次边界**（`List<List<String>>` 而不是扁平 `List<String>`）：
    批次边界是**设备事实** —— 一次发 96 项会被设备截断/返回空，本仓已有 `station_list`
    因合并查询被设备吞掉的先例（见 `DeviceProfile.soloCmds`）。用扁平列表调用点就得自己切片
    = 把刚搬走的设备知识又搬回客户端。
  - **不做成新的 `FieldGroup`**：`coverageReport()` 遍历 `FieldGroup.entries`
    （`GoformFieldMapper.kt:158`），加一个枚举值会让 `/api/diagnose?fields=1` 的 `field_coverage`
    **多一个块**，直接冲掉 §16 的 2026-09-22 基线与 §14.3 的判据 1（`registered` 逐组不变）
    / 判据 2（`queried` 全 true —— 全量批次没有登记 canonical，新组只会是 `registered=0`）。
    理由整段写进了 `DeviceProfile.fullStatusCmds` 的 KDoc。

  **顺手更正的两处过期注释**：搬运前源码写 `30 / 28 / 30+`、KDoc 写「75+ 字段」，**两个都不准**，
  实测就是 30 / 29 / 37 = 96（现已由守门测试冻结住数量与内容）。

  **其余 7 处硬编码查询刻意不动**，各加了一行注释说明「刻意的轻量/分批查询，不走 profile 命令表；
  字段名核对依据是 §16 的真机基线（2026-09-22）」——
  `getNetworkInformation`（2 项，无对应组）、`getDeviceInfo`（5 项，IDENTITY 有 20 项）、
  `getDeviceVersion`（3 项）、`getTrafficStats`（6 项，含 TRAFFIC_LIMIT 不管的 `realtime_*`）、
  `getNeighborCellInfo`（1 项）、`GoformWifiClient.getWifiSettings`（12 项）与
  `getWifiModuleInfo`（2 项）。后两者**合起来**才等于 `cmdsFor(WIFI_SETTINGS)` 的 14 项，
  但线上是**两次独立请求**，合成一次会改变设备侧请求形状（见 §15 的 P1-20 修订）。

  **诊断路径刻意仍走 `normalizeProfile`**：`coverageReport()` 回答的是「**当前生效的那份 profile**
  登记了什么、命中了什么」，产出要与 §16 的 `registered` / `hit_source` 逐字比对，
  所以必须是生效那份；`normalizeProfile == null` 时它整段短路（一条查询都不发）。
  **0.4a 担心的「命令表来自 A、分批规则来自 B」在这个结构下不成立** ——
  `queryGroup()` 的 `cmds` 与 `solo` 都来自同一个入参 `p`（`GoformFieldMapper.kt:205-215`），
  内部同源。理由整段写进了 `queryGroup` 的 KDoc。

  **守门测试转型**：`GoformCommandTableGuardTest` 从「客户端 fallback vs `cmdsFor`」的一致性比对
  改成**命令表内容冻结** —— 比对对象随 fallback 删除而消失，只把 fallback 换成 `cmdsFor()`
  会变成 `cmdsFor(g) == cmdsFor(g)` 的恒真式空转。现有 **9 条**（实测 `@Test` 计数 = 9）：
  ① 冻结表覆盖全部 `FieldGroup`；② `cmdsFor` 10 组逐字含顺序；③ `soloCmds` 只有 `WIFI_CLIENTS`
  的 `station_list`；④ `fullStatusCmds` 三批逐字（30/29/37，合计 96）；⑤ **任何组都不许出现小写
  `lte_snr`**（防「两侧同时抄错成同一个错名」——整组逐字冻结拦不住这种）；
  ⑥ `cmds` 取 `commandProfile` 而非 `normalizeProfile`（用 marker profile 正面证明）；
  ⑦ 关掉归一化后 `cmds` 仍非空且等于 `commandProfile` 的登记表；
  ⑧ 关掉归一化时 `coverageReport` 一条查询都不发；
  ⑨ `coverageReport` 走 `normalizeProfile` 的命令表（断言发出的 cmd 里没有 marker，
  且 `groups` 块数 == `FieldGroup.entries.size` —— 这条同时把 §16 的组数钉住了）。

  **`ComponentFactory` 未动**（两个客户端的构造签名没变，`:766` / `:767` 不在本 commit 的文件清单里）。

  **校验**：第 1 层 ✓（`:core:api:compileDebugKotlin` / `:core:compileDebugKotlin`）/
  第 2 层 ✓（`:core:device-schema:test` / `:core:goform:test`，守门测试 9 条真实执行、
  不是 up-to-date 跳过）/ **第 3 层 ✗、第 4 层 ✗（无真机）**。

  **解锁条件（把 `[~]` 变成 `[x]` 要做的事）**：**重抓一份 `field_coverage`**，
  按 §14.3 的四条判据与 §16 的 2026-09-22 基线比对 ——
  `registered` **逐组不变**（合计 **85**）、`queried` **全 true**、
  `hit_source` **逐字不变**、`hit` **只增不减**（≥ **67**）。
  任何一条不满足且解释不清 → P0，先回滚再查。
  **本轮没有新增 `FieldGroup`**（这是上面第二个裁决的直接目的），所以 `registered` 的组数与合计
  **按设计不变**，§16 也**不需要追加第二份基线**。
  判据 1 / 2 在代码层面已由守门测试第 ① 条（冻结表键集 == `FieldGroup.entries`）与第 ⑨ 条
  （`groups` 块数 == `FieldGroup.entries.size`，且发的是真 profile 的 cmd）**间接钉住**；
  但**判据 3 / 4 只能真机验** —— `hit_source` 与 `hit` 是设备返回值的函数，单测替身给不出来。

- `[~]` 0.5 设备值域（频段全集 / WiFi 固定枚举 / base64 编码方向 / 二维码文件名）搬进 profile
  → **部分完成**：WiFi 固定枚举（`WPA2PSK` / `CCMP` / `ApIsolate=0` / `AccessPointIndex=0` /
  `ChipIndex` 缺省）与 base64 写侧编码已进 `WIFI_AP_CONFIG`（`090fcad` + `118ed84`），
  读侧字符集不对称一并修掉（`858a9c9`）。
  **未搬**：① 频段全集 `LTE_ALL_BANDS` / `NR_ALL_BANDS` —— 被 `core/controller` 的
  `NetworkController.kt:122/127` 跨模块引用、`core/contract/Enums.kt:145-146` 还有第三份零引用拷贝，
  **已裁决推阶段 2**（§15 的 P0-1，按 §3.4 的口径处理）；
  ② 二维码文件名模板 —— 需要 `DeviceProfile` 上新开一个读侧 API 面，**归阶段 2**（§15 的 P1-5）
- `[x]` 0.6 `WriteSpec` 加 `retry: RetryPolicy`，现有 18 项显式标 `RETRY_ON_SESSION_LOSS`
  → 实测原 18 项全部显式标注；新增 10 项里 `REBOOT` / `SHUTDOWN` / `FACTORY_RESET` /
  `BACKEND_PASSWORD` 为 `NEVER`，其余（含 `MOBILE_DATA.fallback` 独立判定）为 `RETRY_ON_SESSION_LOSS`
- `[x]` 0.7 短信三项走 `smsSpec()`，**不进** `SettingKey`（见 §11.2）
  → **profile 侧**（`2d92e05`：`SmsSpec` 接口 + `ZteSmsSpec` + `ZteGoformProfile.smsSpec()`）
  与**客户端接线**均已完成：`GoformSmsClient` 收非空 `profile` 构造参数（无默认值，口径同 §11.12），
  `sendSms` / `getSmsList` / `getSmsMeta` / `deleteSms` / `markSmsRead` / `verifySend` 六处走 spec，
  goform 侧的 `buildSendParams` / `toUcs2Hex` / `formatSmsTime` / `TAG_SENT` / `TAG_SEND_FAILED` 已删，
  `GoformSmsSendParamsTest` 5 → 1 条（只留 `maskNumber` 那条，脱敏是日志规范不属 profile）。
  读时钟上移到调用点（`System.currentTimeMillis()` / `TimeZone.getDefault()` 在 `sendSms` 里）
- `[~]` 0.8 补测试：新增 key 的 encode/validate 逐条断言；`SettingKey` 全覆盖断言
  → 已有：`ZteGoformProfileTest`（**117** 条 `@Test`，批 10 新增 2 条）、`ProfileContractTest`（8）、
  `ZteSmsSpecTest`（23）、`FieldNormalizerTest`（30）、`ZteGoformRawCaptureTest`（2）、
  `GoformSettingWriterDecisionTest`（20）、`GoformWifiApParamsTest`（19）、
  `GoformWritePolicyTest`（15）、`GoformCommandTableGuardTest`（**9**，批 11 新增 8 条、批 13 转型后 9 条）、
  `GoformBase64CharsetTest`（7）、`GoformCodecFormBodyTest`（8）、`GoformSmsSendParamsTest`（1）。
  差的是**装配层**（writer × 真实 `GoformClient`，要等阶段 1 接口化）
  与**真机**那一半（§14.3 / §14.4，无真机）



### 怎么做

**0.1 新增的 key**（命名沿用「动作」而不是设备命令名）：

```kotlin
enum class SettingKey {
    /* …现有 18 项… */
    REBOOT,              // REBOOT_DEVICE，无参
    SHUTDOWN,            // SHUTDOWN_DEVICE，无参
    FACTORY_RESET,       // FACTORY_RESET，无参
    BACKEND_PASSWORD,    // CHANGE_PASSWORD，params: old + new（哈希由调用点做，见 §9 批 1 裁决）

    MOBILE_DATA,         // CONNECT_NETWORK / DISCONNECT_NETWORK + SET_DATA_ENABLED 兜底
    CONNECTION_MODE,     // SET_CONNECTION_MODE，value: String
    WIFI_AP_CONFIG,      // setAccessPointInfo 的整份参数集（2026-09-22 实际落地的形态）
                         // 原方案写的是 WIFI_SSID + WIFI_PASSPHRASE 两个 key，实际合成了一个：
                         // 设备侧这条命令是整表替换，拆成两个 key 等于让「哪些键必须一起发」
                         // 这条设备事实散在两处。理由见该 key 的 KDoc 与 §11.3。
    WIFI_POWER,          // SET_WIFI_POWER，value: Int
    WIFI_ENABLED,        // 开→switchWiFiChip(ChipEnum=chip1,GuestEnable=0)，关→switchWiFiModule(SwitchOption=0)
    PPP_DIAL,            // connectNetwork / disconnectNetwork，与 MOBILE_DATA 的区别是**无兜底**
}
```

短信三项（`SEND_SMS` / `DELETE_SMS` / `SET_MSG_READ`）**不在这里** —— 走 `profile.smsSpec()`，
理由见 §11.2（encode 会不纯、有回读确认、编码是短信专有的）。

`USB_MODE` 也**不在这里**（2026-09-22 批 3 按用户裁决删除）：`setUsbMode` 全仓零调用，
既没有 route 也没有 app 入口，登记它等于留一条只有 profile 认识、谁都触达不到的命令。


两处需要先定判据，别顺手写：

- **`MOBILE_DATA` 是「按取值选命令」+「主备兜底」两件事**（`GoformNetworkClient.kt:36-43`：
  开发 `CONNECT_NETWORK`、关发 `DISCONNECT_NETWORK`，**主命令不成功**才发 `SET_DATA_ENABLED&data=1/0`）。
  **2026-09-22 裁决（方案 b）**：给 `WriteSpec` 加两个可选字段 ——
  `commandOf: ((Map)->String)? = null`（按取值选命令，`command` 保留作默认名与日志/断言标识）
  与 `fallback: WriteSpec? = null`。
  不采用「拆成 `MOBILE_DATA` + `MOBILE_DATA_OFF`」：那会把「按取值选命令」这段设备知识挪到调用点，
  而且 `WIFI_ENABLED` 是同一个形状的第二例 —— 拆下去 `SettingKey` 会长出一整个 `*_OFF` 系列。
  fallback 的 `retry` **独立判定**，不继承主命令。
  **fallback 的触发条件必须覆盖「非成功」的全部分支**（现状是「第一条 `goformPost` 返回不成功或
  null 就发第二条」，null 含传输错误 / 会话失效 / AD 算不出）。writer 里只在 `Failed` 时兜底
  就会漏掉 `SessionLost` / `Unreachable` 两种今天会发第二条的情形 —— 那是行为变更。
- **`notCallback=true`** 这个约定参数（Network 3 处、Sms 2 处）进 `encode` 的输出，
  不要塞进 `GoformSettingWriter` 的公共 body。
  注意 `isTest=false` **不要**进 `encode`：`GoformCodec.buildSetFormBody`（`GoformCodec.kt:60-67`）
  恒发它并显式跳过调用方自带的那份，现有 18 项也一律不发 —— 发了是重复，不发才是逐字等价。



**0.3 改法示例**（`GoformDeviceClient.kt:34-38`）：

```kotlin
// 改前
suspend fun rebootDevice(): Boolean = client.isGoformSuccess(client.goformPost(mapOf(
    "isTest" to "false", "goformId" to "REBOOT_DEVICE")))
// 改后
suspend fun rebootDevice(): Boolean = writer.write(SettingKey.REBOOT, null)
```

`GoformSmsClient` 当前**不接 profile**（`ComponentFactory.kt:765` 只传了 `goform`），
0.3 要顺手给它加 `profile` 参数并在工厂里传入。

**0.4 读命令**：`GoformFieldMapper` 已有 `fields.cmds(group, fallback)` 的形状
（见 `GoformSignalClient.kt:116/260/285/303/315/329` 的 6 处用法）。把各处 `client.query(listOf(...))`
的字面量搬进 `ZteGoformProfile.cmdsFor()` 对应分组，调用点改成 `fields.cmds(group)`。

> **以下三段是 0.4a/0.4b 之前的过程记录，`fallback` 参数已于 `680fbae`（批 13）删除。**
> 保留原文是为了留住「为什么当初不许直接删」这条决策链 —— 删的前提是双 profile 先落地
> 且两份表被证明逐字一致，不是「后来发现可以删」。最终形态见上面 0.4b 那一条。

> **不要直接删 fallback 参数**（2026-09-21 修正，原方案在这里是错的）。
> `GoformFieldMapper.cmds()` 是 `normalizeProfile?.cmdsFor(group)?.takeIf{…} ?: fallback`，
> 而 `normalizeProfile` 在 `fieldNormalizationEnabled=false` 时是 null —— 此时
> **fallback 是唯一的命令来源**，删掉它等于「关掉归一化 → 一条查询都发不出去 → 整个只读面瘫掉」。
>
> 正确做法：照抄写侧已有的结论（`GoformSettingWriter.kt:14`「字段归一化可以关，写命令表不能关」），
> 把 mapper 改成**双 profile** —— **这一步 2026-09-22 已落地（0.4a，`9fa0473`）**，
> 实际签名如下（`fallback` 参数 0.4a 当轮**保留**，`cmds()` 的取值行为一字未变）：
> ```kotlin
> internal class GoformFieldMapper(
>     private val normalizeProfile: DeviceProfile?,   // 可空：归一化/脱敏/覆盖率，决定 enabled 与 profileId
>     private val commandProfile: DeviceProfile,      // 非空：命令表的最终归宿，0.4b 才接上
>     private val legacy: FieldNormalizer.LegacyAliases = FieldNormalizer.LegacyAliases.DROP,
> )
> // 0.4a 现状（未切）：
> fun cmds(group: FieldGroup, fallback: List<String>): List<String> =
>     normalizeProfile?.cmdsFor(group)?.takeIf { it.isNotEmpty() } ?: fallback
> // 0.4b 目标 —— 2026-09-22 `680fbae` 已落地，现在就是这一行（`GoformFieldMapper.kt:115`）：
> fun cmds(group: FieldGroup): List<String> = commandProfile.cmdsFor(group)
> ```
> **`enabled` / `profileId` 的语义必须保持不变**：`/api/diagnose` 的 `normalization_enabled`
> 是从 `activeProfile != null` 推出来的（`HttpServer.kt:570`（**该文件在 `core/network`**）
> ← `DataHub.kt:225`（`core/api`）← `GoformSignalClient.kt:41` ← `mapper.profileId`）。顺手把 `profileId`
> 改成非空，排障开关的可观测性就没了 —— 它会永远报 `true`。
> 这条纪律 0.4a 已经写进 `GoformFieldMapper` 的类注释；0.4b 之后由
> `GoformCommandTableGuardTest.关掉归一化后 cmds 仍然非空且等于 commandProfile 的登记表`
> 钉住（该测试 0.4a 时叫「关掉归一化时 cmds 仍然返回 fallback」，
> **保护的不变量一字未变**，只是比对对象从 fallback 换成了 `commandProfile` 的登记表）。
>
> **0.4b 必须连 `soloCmds` 一起切**（2026-09-22 修正，原方案这句写错了）：
> mapper 上**没有** `soloCmds()` 方法。唯一使用点是私有的 `queryGroup()`
> （`GoformFieldMapper.kt:188-199`），它取的是 `coverageReport()` 传进来的那份 profile
> （也就是 `normalizeProfile`）。切 `cmds()` 时不同步切它，就会出现
> **「命令表来自 `commandProfile`、分批规则来自 `normalizeProfile`」**的错配 ——
> 「发哪些 cmd」与「哪些 cmd 不能合并发」是同一件设备事实。
> 实测 `ZteGoformProfile.soloCmds()`（`:480-481`）只对 `WIFI_CLIENTS` 返回 `["station_list"]`，
> 其余分组一律空。
>
> **⚠ 上面这条「必须一起切」的判断在 0.4b 落地时被推翻了（2026-09-22 批 13，按语义重新判定）**：
> 两条路径回答的是两个不同的问题 —— 业务查询（`cmds()`）问「现在要向设备发哪些 cmd」，
> 命令表不能关 → 取非空的 `commandProfile`；覆盖率诊断（`coverageReport()` → `queryGroup()`）
> 问「**当前生效的那份 profile** 登记了什么、命中了什么」，产出要与 §16 的 `registered` /
> `hit_source` 逐字比对 → 必须取生效那份，也就是 `normalizeProfile`。
> **原文担心的错配也不成立**：`queryGroup()` 的 `cmds` 与 `solo` 都来自同一个入参 `p`
> （`GoformFieldMapper.kt:205-215`），内部同源；错配只会出现在「一边取 `p`、一边取 `commandProfile`」
> 的写法上，那种写法现在没有、也不要加。理由整段写进了 `queryGroup` 的 KDoc。

`getFullStatus()` 那 **96** 个字段名（三批 30 / 29 / 37，**实测**；原文写的 88 与源码注释写的
`30 / 28 / 30+`、KDoc 的「75+ 字段」都不准）建议新增一个 `FieldGroup.FULL_STATUS`
（或按现有三批分成三个组），
不要塞进已有分组 —— 它是「一次拉全量」的专用批次，与按需查询的分组语义不同。
注意加枚举值会影响穷举逻辑：`GoformFieldMapper.coverageReport()` 遍历 `FieldGroup.entries`
（`:153`），新组没登记 cmd 时会多出一个 `queried=false` 的块，`ProfileContractTest` 的相关断言要跟着改。

> **⚠ 这条建议在 0.4b 落地时被否了（2026-09-22 批 13 裁决，理由就是上面那句「注意」）**：
> 加 `FieldGroup` 会让 `field_coverage` 多一个块，`registered` 的组数与合计必然变，
> 直接冲掉 §16 的基线与 §14.3 的判据 1/2 —— 而 0.4b 的验收**恰恰是拿那份基线比对**。
> 实际做法是在 `DeviceProfile` 上新开一个 `fullStatusCmds(): List<List<String>>`
> （默认 `emptyList()` = 该设备不提供全量 dump），外层列表表达**批次边界**。
> 全量 dump 与「按需查询的分组」语义本来也不同：它**不参与归一化**，只是把设备后台有什么原样捞一份看。
> 完整理由在 `DeviceProfile.fullStatusCmds` 的 KDoc 与上面 0.4b 那一条。


**0.5** `LTE_ALL_BANDS` / `NR_ALL_BANDS` 移到 profile 的 `BAND_LOCK_*` WriteSpec 内部（解锁时用），
`GoformNetworkClient.companion` 整个删掉。

> **2026-09-22 批 3 实测：这条原方案做不通，已升 P0-1（§15）。**
> 原方案假设「空值 = 解锁 = encode 里下发全频段」，实际语义不是这样：
> 全频段串是**调用方传进来的取值**（`unlockAllBands()` → `lockLteBands(LTE_ALL_BANDS)`），
> 而 `BAND_LOCK_*.encode` 只做 `value → lte_band_lock` 的字段名映射、`validate` 把空串当解锁放过。
> 更关键的是这两个常量**跨模块被引用**：`NetworkController.kt:122/127`（`core/controller`，
> 阶段 0 范围外）直接读 `GoformNetworkClient.LTE_ALL_BANDS`，
> 且 `core/contract/Enums.kt:145-146` 还有第三份同值拷贝。
> 删 companion 会连带改 `core/controller`，那超出「纯搬运」的风险边界 —— 等裁决。
>
> **2026-09-22 裁决：推到阶段 2，阶段 0 不动。** 理由有两层：
> ①「空串 = 不发限制」与「空串 = 下发全频段」是两种不同的对外语义，换过来是行为变更而不是搬运；
> ② 常量被 `core/controller` 直读，在阶段 0 改它就等于把阶段 0 的影响面扩到 controller 层。
> 阶段 2 收 `NetworkController` 的设备知识时一起清，并按 §3.4 的口径 ——
> 如果那时发现「收进统一抽象」仍然要拉跨模块依赖，就允许各插件独立持有一份频段全集。


### 验收

- `:core:device-schema:test` 与 `:core:goform:test` 全绿
- 写命令：`core/goform/src/main` 里 `"goformId" to` 的剩余数量
  **（2026-09-22 批 2/批 3 实测修正，原判据「只剩 GoformClient 的登录/登出 3 处」是错的）**：
  - 批 3 当时 = 7：`GoformWifiClient`（`setAccessPointInfo` ×3）
    + `GoformSmsClient`（`DELETE_SMS` / `SET_MSG_READ` / `SEND_SMS`）+ `GoformClient`（`LOGOUT`）
  - **2026-09-22 阶段 0 执行完后实测 = 4**：WiFi 3 处已并入 `WIFI_AP_CONFIG`（`118ed84`），
    只剩短信 3 处（`GoformSmsClient`，0.7 接线中）+ `LOGOUT`（归阶段 1）
  - 0.7 的客户端接线做完 → **1**（只剩 `LOGOUT`）
  - ⚠ **`"goformId" to` 这个 pattern 抓不到字符串形态的登录命令**：
    `GoformClient.kt:275` 与 `:290` 是 `setBody("isTest=false&goformId=LOGIN_MULTI_USER&…")`
    / `setBody("isTest=false&goformId=LOGIN&…")`，整条 body 是一个字符串字面量。
    所以真实的「绕过 profile 的写命令」总数 = grep 数字 **+ 2**（现在是 4 + 2 = 6）。
    要一次抓全就同时 grep `goformId`（不带 ` to`）—— 别再按前一个数字下结论。

- 读命令（**0.4b 的判据 —— 2026-09-22 批 13 按实测修正，原先写的「应从 11 降到 9」是错的**）：

  **两个口径不要再混，分别写清**（两个数字都对，只是分母不同）：

  | 口径 | 0.4b 之前 | 0.4b 之后（`680fbae` 实测） | 说明 |
  | --- | --- | --- | --- |
  | A. `client.query(listOf(` 的处数 | 11 | **7** | = SignalClient 9 + WifiClient 2，本轮拿掉 `getSignalInfo` 1 处 + `getFullStatus` 3 处 = 4 |
  | B. **绕过 profile 的读入口总数** | 13 | **9** | = 口径 A 的 7 处 + 2 处 `client.querySingle(`（`station_list` / `queryDeviceAccessControlList`，本轮未动） |

  **我在 0.4b 的 brief 里把这两个口径算混了**（写成「`client.query(listOf(` 从 11 降到 9」）：
  把 WiFi 那 2 处算了两遍 —— 11 里本来就含 WifiClient 的 2 处，不该在减完 4 处之后又补回来。
  **口径 A 的正确答案是 7，口径 B 的正确答案是 9。** 下次核这个数字时**先说清问的是哪个口径**。

  **剩下这 7 + 2 处都是刻意保留的**，不是漏改（各处方法体上都有一行注释写明理由与核对依据）：
  刻意的轻量查询（`getNetworkInformation` / `getDeviceInfo` / `getDeviceVersion` /
  `getTrafficStats` / `getNeighborCellInfo`）、刻意的分批查询
  （`getWifiSettings` 12 项 + `getWifiModuleInfo` 2 项 = 两次独立请求）、
  以及两处 `querySingle`。所以**「该 pattern 归零」这个原判据已经不适用** ——
  归零意味着把这些轻量/分批查询强行换成整组命令表，那是设备侧请求形状的变更。
  **新判据：口径 A 恰好 7 处、口径 B 恰好 9 处，且每一处都有注释说明为什么不走 profile。**

  **2026-09-22 早前实测已修正的两点（保留）**：
  - 该 pattern **不会**命中 `GoformFieldMapper` —— 它的分批查询走的是构造进来的 lambda
    （`queryGroup()` 里的 `query(it)` / `query(listOf(cmd))`），不是 `client.query`。
    原方案那句「判据要排除 `GoformFieldMapper.kt`」已经不必要，但**结论仍然成立**：
    那两行是引擎自身按 solo / 非 solo 分批，是正确代码，不是硬编码命令表
  - **光看这个 pattern 会漏两处**：`GoformWifiClient` 的 `client.querySingle("station_list")`
    与 `client.querySingle("queryDeviceAccessControlList")`。
    要一次抓全就同时 grep `client.querySingle(` —— 那就是口径 B
- **route 层的两个裸命令端点是刻意的例外，不许动**：`DeviceRoutes.kt:147`
  `POST /api/device/goform/query` 与 `:178` `POST /api/device/goform/set` 的 cmd / goformId
  来自 HTTP 请求体，由 `goform_command_enabled` 开关守门（`AppSettings.kt:32`）。
  它们是排障通道，**不进 profile**；阶段 3 再给它们加能力门禁（见 §11.5）
- `ZteGoformProfileTest` 新增断言：`SettingKey.values()` 全部有 `writeSpec`（现有测试已有这条，
  新增 key 会自动被它覆盖 —— 先跑一次确认它真的会失败，再补 spec）
- **排障开关回归**（**0.4b 之后这条从「预防性检查」升级为必做项** —— `cmds()` 现在真的走
  `commandProfile` 了）：把 `field_normalization_enabled` 设成 false 重启后台服务，
  仪表盘/网络/WiFi 三个页面仍有数据，且 `/api/diagnose` 的
  `device_profile.normalization_enabled` 为 `false`。
  **单测替身（0.4a 起有，0.4b 已随 fallback 删除同步改名）**：`GoformCommandTableGuardTest` 的
  「关掉归一化后 `cmds` 仍然非空且等于 `commandProfile` 的登记表」
  +「关掉归一化时 `coverageReport` 不向设备发查询」两条，把「关掉归一化不会打瘫只读面」
  与「关掉归一化不会开始真打设备」都钉住了（0.4a 时前者叫「…仍然返回 fallback」，
  **保护的不变量一字未变**）。真机那一遍仍要做，单测替身只覆盖取值不覆盖端到端
- 真机回归（无真机则标 `[!]`）：重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 /
  **手动拨号与挂断**（`PPP_DIAL`，与「开关移动数据」是不同的 key、不同的失败路径，必须分开点）/
  切连接模式 / 改 SSID / 改密码 / 改功率 / **开关 WiFi**（`WIFI_ENABLED`，开与关走两条不同命令，
  两个方向都要点）/ 发短信 / 删短信 / 标已读，逐条点一遍



### 影响面

`core/device-schema` 2 个文件、`core/goform` 5 个文件、`ComponentFactory.kt` 1 处（给 SmsClient 传 profile）。
**不碰** route / app / web。

---

## 5. 阶段 1 — 传输层接口化

**为什么**：`GoformSettingWriter.kt:27` 和 6 个客户端吃具体类 `GoformClient`，
这是「第二个协议实现进不来」的直接原因。

### 任务

- `[ ]` 1.1 `GoformGateway` → `DeviceTransport`，方法名去 goform 味
- `[ ]` 1.2 成功判据从 `GoformClient` 移到 `DeviceTransport.isSuccess(body)`
- `[ ]` 1.3 `GoformSettingWriter` + 6 个客户端的构造参数换成接口
- `[ ]` 1.4 登录握手（LOGIN_MULTI_USER / LOGIN / LOGOUT / LD / RD / AD 签名）收敛为 transport 内部实现细节
- `[ ]` 1.5 `ComponentFactory.buildNetworkGraph` 改为「从插件拿 transport」的形状（此时插件还没有，先留一个工厂函数）

### 怎么做

1.1 的改名映射（语义不变，只是去掉协议专有名词）：

- `baseUrl()` → 保留
- `base64Decode(input)` → `decodeDeviceText(input)`（GBK/UTF-8 兼容是 ZTE 的事实，接口上只说「解码设备文本」）
- `query(cmds)` / `querySingle(cmd)` / `goformPost(params)` → `read(keys)` / `readOne(key)` / `write(params)`
- `isGoformSuccess(body)`（现在是 `GoformClient` 的公开方法，**不在接口上**）→ 上接口，名字 `isSuccess(body)`
- QoS 三个方法（`adjustQoS` / `getQosStatus` / `setQosEnabled`）保留原名，它们是我们自己的概念
- **`updateGoformPassword(newPwd)` 不改名**：`GoformGateway.kt:48` 已经写明「符号名沿用 `goform*`：
  那是配置键的一部分」。持久化键 `goform_password`（`AppSettings.kt:28`）不能改（改了丢用户配置），
  符号名跟着键名走是刻意的。原方案想把它改成 `updateCredential` —— 撤销该项。


1.4 注意：`ensureLogin` / `invalidateSession` / `resetLogin` 的**语义注释必须整段搬过去** ——
`GoformGateway.kt:35-41` 记录了「invalidateSession 不能递增退避计数」这个踩过的坑，
换实现的人看不到这段注释就会重新踩一次。

1.5 先加一个临时工厂函数（阶段 2 会被 `DevicePlugin.createTransport` 取代）：

```kotlin
// ComponentFactory.kt
private fun createTransport(settings: AppSettings, gatewayIp: String): DeviceTransport =
    GoformClient(deviceIp = settings.goformIp.ifBlank { gatewayIp },
                 port = settings.goformPort, password = settings.goformPassword)
```

### 验收

- `:core` 全量编译通过（`gradlew :core:assembleBenchmark`）
- `grep -rn 'GoformClient' core --include=*.kt` 在 `core/goform` 之外只剩 `ComponentFactory.kt` 一处
- `:core:goform:test` 全绿
- 行为零变化：这一阶段**不许**改任何请求内容，diff 里不该出现新的 URL / 参数 / 判据

### 影响面

约 9 个文件的签名 + `ComponentFactory` 1 处。不碰 route / app / web。

---

## 6. 阶段 2 — 插件聚合根与中间控制层

### 任务

- `[ ]` 2.1 新建 module `core/device-spi`（Android library）+ 6 个 SPI 文件
- `[ ]` 2.2 新建 module `core/device-plugins`，package `zte/f50/`
- `[ ]` 2.3 写 `ZteF50Plugin`：组装现有零件（profile / transport / platform 暂时直接 new 现有实现）
- `[ ]` 2.4 写 `PluginRegistry`（编译期 `listOf(ZteF50Plugin)`）+ `DeviceRuntime.resolve()`
- `[ ]` 2.5 `ComponentFactory` 只留 `DeviceRuntime.resolve()` 一处，删掉 `resolveDeviceProfile()`
- `[ ]` 2.6 `DeviceProfiles`（旧注册表）标为 `@Deprecated` 并让它委托给 `PluginRegistry`，
  避免 `SignalCollector` 等直接 import `ZteGoformProfile` 的地方一次性全改
- `[ ]` 2.7 守门测试 `PluginContractTest`
- `[ ]` 2.8 `/api/diagnose` 的 `device_profile` 块补 `plugin_id` 与 `selection`

### 怎么做

**2.1 Gradle**：`core/device-spi/build.gradle.kts` 依赖 `:core:contract` + `:core:device-schema` +
`:core:collector`（要 `AtTransport`）。注意别让 `device-spi` 依赖 `:core:goform` ——
SPI 不能知道任何具体协议。`settings.gradle.kts` 加两行 include。

**2.6 的过渡策略**（重要，否则这一步会变成一次大爆炸，且**有一个依赖方向陷阱**）：

`SignalCollector` 现在直接 import `ZteGoformProfile`。原方案写的是「让 `DeviceProfiles.DEFAULT`
委托 `PluginRegistry`」—— **这条行不通**：`DeviceProfiles` 在 `core/device-schema`（纯 JVM、被所有人依赖），
而 `PluginRegistry` 在 `core/device-plugins`（依赖 device-schema），委托就成了反向依赖 → 编译不过。

正确做法：
- `ZteGoformProfile` **留在原处不动**（`core/device-schema/.../profile/`），插件只是持有它的引用；
- `DeviceProfiles` 标 `@Deprecated("改用 PluginRegistry / DeviceRuntime")` 但**保持原实现**；
- 等阶段 3/4 顺路把 `SignalCollector` 改成从 `DeviceRuntime` 取 profile 之后，再删 `DeviceProfiles`。

模块依赖方向（定下来别反）：
```
contract  ←  device-schema  ←  device-spi  ←  device-plugins  ←  core（ComponentFactory）
                                   ↑
                               collector（AtTransport 上移后由 device-spi 提供，见 §11.7）
```


**2.7 守门测试**（放 `core/device-plugins/src/test`）：

- 每个 plugin 的 `id` 唯一且非空
- 声明了 `Capability.X` → 必须能找到对应的 `WriteSpec`；反之，有 `WriteSpec` 但没声明 capability 也算失败
  （这条防的是「后端支持但前端永远灰着」）
- `probe()` 不做 I/O：用一个禁止文件访问的 `ProbeEnv` 假对象调用，并断言不抛
- `capabilities` 里的每一项在 `Capability` 枚举里都有文档注释（否则前端不知道怎么翻译成文案）

### 验收

- `gradlew :core:assembleBenchmark` 通过
- `grep -rn 'ZteGoformProfile\|DeviceProfiles' core --include=*.kt` 只出现在
  `core/device-plugins` 与 `core/device-schema`（过渡期允许 `SignalCollector` 一处，记在这里）
- `PluginContractTest` 全绿
- `GET /api/diagnose` 返回里能看到 `plugin_id=zte-f50` 与 `selection=default`
- 真机冒烟：启动后台服务，仪表盘 / 网络 / WiFi / 短信四个页面数据与改造前一致

---

## 7. 阶段 3 — 能力集

**与阶段 4 的关系**（2026-09-21 修正）：原方案写「可与阶段 4 并行」，那是错的 ——
`BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 这三项的取值来自 `PlatformAdapter`（阶段 4 才有）。
拆法：**3A（设备侧能力，不依赖阶段 4）可以先做**，**3B（平台侧能力）必须等阶段 4**。

**这一阶段收益最直接**：现在设备不支持的功能要等请求打到设备才失败。

### 任务

- `[ ]` 3.1 `Capability` 定在 **`core/contract`**（冻结区，理由见 §11.4）
- `[ ]` 3.2 `ZteF50Plugin.capabilities` 按真机实测填写（3A 部分）
- `[ ]` 3.3 route 层统一门禁：`CapabilityMissing` → 501 / `ErrorCode.NOT_SUPPORTED`
- `[ ]` 3.4 新端点 `GET /api/device/capabilities`（或并入 `/api/diagnose`，二选一后记在这里）
- `[ ]` 3.5 app 侧消费：不支持的开关置灰 + 一句原因
- `[ ]` 3.6 web 侧消费：同上（`web/src/api/contract.ts` 加镜像类型）
- `[ ]` 3.7 守门测试：`Capability` 每一项都至少被一处 route 或一处 UI 消费（防止定了不用）
- `[ ]` 3.8 两端镜像一致性测试：contract 的枚举 wire 名与 `contract.ts` 的字符串联合类型逐项对齐
- `[ ]` 3.9 **3B**（阶段 4 之后）：`BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 由 `PlatformAdapter` 推导

### 怎么做

**3.1 第一批只定这 10 个**（都能在 F50 上明确验证，且已有对应 route；其余等有第二台设备再加 ——
冻结区只增不改，宁可晚定）：

```
SMS, SIM_SLOT_SWITCH, BAND_LOCK, CELL_LOCK, NETWORK_MODE,
SAMBA, USB_DEBUG, FOTA, PERFORMANCE_MODE, TRAFFIC_LIMIT
```

**3.3 落点**：在 route 的统一异常处理里捕 `CapabilityMissing`，不要在每个 handler 里写 if。
`NetworkRoutes.kt:186` 那处注释同时删掉（它描述的事实由 capability 表达了）。

**三种「不可用」不许混成一个码**（见 §11.6）：

- `NOT_SUPPORTED`（501）= 设备不支持，**不可恢复**，前端不要重试、直接灰掉
- `UNAVAILABLE`（503）= 设备离线 / 会话失效 / 提权失败，**可重试**
- `OUT_OF_RANGE`（400）= 值域非法，已有

**3.5 / 3.6 的口径**（对齐既定的「开关不许是假开关」）：
显示状态 = 本地开关 **AND** 设备能力。不支持时开关置灰**且**在旁边写明
「当前设备不支持」，不要静默隐藏 —— 隐藏会让用户以为功能丢了。

### 验收

- 把 `ZteF50Plugin.capabilities` 临时删掉一项（如 `SAMBA`），对应 route 返回 501、app/web 的开关置灰。
  **验完必须还原** —— 这是排查性删除，不是永久删除
- `/api/device/capabilities` 的返回能被 app 与 web 同时消费（两端各自的类型定义对得上）
- 旧客户端兼容：用改造前的 app 连改造后的 core，功能不受影响（未知字段被忽略）
- 守门测试全绿


---

## 8. 阶段 4 / 5

### 阶段 4 — 平台适配层

- `[ ]` 4.1 `PlatformAdapter` 实现 `SprdPlatform`：收 `ServiceCallAtExecutor` 的选型逻辑
- `[ ]` 4.2 提权策略 `PrivilegeStrategy`：`SambaPreexecStrategy`（F50）+ `AdbOnlyStrategy`（兜底）
- `[ ]` 4.3 传感器读法（温度 / 电池 / CPU）从 `SystemCollector` / `DownloadManager` 抽到 adapter
- `[ ]` 4.4 `restartNetworkStack()` 收 `AT+SFUN=5/4`（`NetworkController.kt:183`）
- `[ ]` 4.5 `DeviceTuning` 替换四处散落的实测常量
- `[ ]` 4.6 `ATChannel.detectPlatform()` 的结果接到 `DevicePlugin.probe()` 上（它现在只用于上报）

注意：4.3 要保留 `SystemCollector.kt:232` 记录的结论（**不要硬编码**
`/sys/class/power_supply/battery`，要遍历），同时修掉 `DownloadManager.kt:566` 违背该结论的那处硬编码。

**验收**：温度 / 电池 / CPU 读数与改造前逐项一致（同一台机器对比 `/api/system/*` 的返回）；
root shell 仍可用；`AT+SFUN` 重启网络栈仍生效。

### 阶段 5 — probe 选型上线

- `[ ]` 5.1 `ProbeEnv` 采集实现（一次，缓存）
- `[ ]` 5.2 各插件实现 `probe()`
- `[ ]` 5.3 `deviceProfileId` 配置项从「决定性」降级为「覆盖」，设置页文案改为「自动识别（可手动指定）」
- `[ ]` 5.4 `/api/diagnose` 的 `selection` 能区分 CONFIGURED / PROBED / DEFAULT / FALLBACK

**验收**：清空 `deviceProfileId` 后仍然选中 `zte-f50`，日志写明 `selection=PROBED`；
把 `ProbeEnv` 里的 `cpuInfoPlatform` 造成未知值时回落默认 + WARN，功能不中断。

---

## 9. 变更记录

- 2026-09-21 创建本计划。现状盘点基于当日代码；阶段 0~5 全部 `[ ]` 未开始。
- 2026-09-21 第一轮边缘情况复审：修正 2 处会直接改坏功能的错误（0.4 删 fallback、验收 grep 自相矛盾），
  撤销 `updateGoformPassword` 改名，修正 2.6 的模块依赖陷阱与阶段 3/4 的并行声明，
  新增 §11（边缘情况与已决事项）、§12（回滚）。
- 2026-09-21 第二轮复审：新增 §13（偏差处理：计划与实际不符 / 严重错误时怎么办）、
  §14（校验体系：改完怎么证明没改坏）、§15（阶段待办池）。
- 2026-09-21 拿到真机 `field_coverage` 输出后第三轮修正：
  §14.3 的判据从「逐项一致」改成四条抗状态干扰的判据（registered 不变 / queried 全 true /
  hit_source 逐字不变 / hit 只增不减 + 状态可解释），原判据在实测下会每次都不一致因而等于没有；
  新增 §16（真机基线：85 registered / 66 hit / 19 missing，逐组列明）；
  §15 登记 P1-1（SIGNAL 与 CELL_INFO 的 lte_* 命中不一致）、P1-2（nr_band_width 驻 NR 仍 missing）；
  §14.3 加一条纪律：coverage 里看起来像 bug 的映射先读 profile 注释
  （`monthly_rx/tx` 的交叉绑定是刻意的，掰回去就是把功能改坏）。
- 2026-09-22 批 3 按实测修正本文档 5 处：§2.2 A 类去掉 `SET_USB_NETWORK_PROTOCAL`（已删）、
  C 类补记「频段全集被跨模块引用」；§4 任务清单补状态标记、`SettingKey` 清单删 `USB_MODE`、
  0.5 补一段「原方案做不通」的说明、**验收的 grep 判据由「只剩 3 处」改成「现状 7 处 + 2 处
  字符串形态抓不到」**；§11.1 的 retry 建议表删 `USB_MODE`；§11.3 补 WiFi 三处 `Password`
  管线不一致的事实。原 §4 验收那条数字是撰写时的推断，与实测不符（见 §9 执行记录批 2/批 3）。
- 2026-09-22 阶段 0 跑到批 8 之后按实测同步本文档：§2.2 三类欠账逐条标注
  「已完成 / 部分完成 / 已裁决推后」并附 commit 号（**条目一条不删** —— 决策痕迹要留着）；
  新增 §3.4（跨模块收不动的允许各设备独立持有，用户裁决原话）；
  §4 任务清单与验收 grep 判据按实测重写（`"goformId" to` 由 7 改成 **4**，真实绕过数 = 4 + 2）；
  §9 追加批 4~8 的执行记录；§11.2 的 `SmsSpec` 代码块改成**与实现逐字一致**
  （`markReadParams` 多一个 `read` 参数、`SET_MSG_READ` 无 `notCallback`、`sendParams` 含 `isTest=false`）；
  §11.3 的 WiFi 段落整段改写（原文描述的三条 `Password` 管线已经不存在了）；
  §15 的 P0-2 结案为「已解除」、P0-1 改为「已裁决推阶段 2」，并新增 P1-6~P1-19；
  §16 补一条「阶段 0 完成后必须重抓基线」。
- 2026-09-22 批 10/11（`d435fa4` / `9fa0473`）之后按实测同步本文档：
  §2.2 B 类整段按实测重写（行号刷新、fallback 已提成具名常量、`getFullStatus()` **88 → 96**）、
  F 类补标 `HttpServer.kt` 所属模块；**§4 的 0.4 拆成 0.4a `[x]` 与 0.4b `[ ]` 两条**
  并各写清做了什么 / 还剩什么 / 前置条件；§4「怎么做」的双 profile 代码块改成与实现逐字一致，
  并**更正原方案「切 `cmds()`/`soloCmds()`」那句** —— mapper 上没有 `soloCmds` 方法，
  唯一使用点是私有 `queryGroup()`；§4 验收的读命令 grep 判据按实测修正
  （mapper 不再命中该 pattern，但会漏两处 `client.querySingle`）；
  §9 追加执行记录批 10、批 11；§15 新增 **P0-3**（`lte_snr` 大小写分叉，
  **用户裁决「不改大小写」**）与 P1-20 ~ P1-22、P2-1 ~ P2-2；
  §16 的 WIFI_SETTINGS 一行改成 `8 / 7 / 1`、总计改成 **85 / 67 / 18**、
  删掉 `module_switch` 那句的「待核」（已核清：OCR 是对的、文档的猜测错了），
  并加一条「重抓基线时 `WiFiModuleSwitch` 可能仍然 missing」的预判。
  本轮**不改任何代码**、**没有重跑 Gradle**（用户正在并行改 `scripts/**`，见 §13.3 最后一条）。
- 2026-09-22 **拿到真机「字段覆盖率」界面基线后按实测同步本文档（批 12 文档轮）**：
  **§16 整节重写** —— 用可逐字比对的真基线（85 / **67** / **18**）取代 2026-09-21 的 OCR 转录版，
  写明来源与废弃理由、保留「抓取时设备状态」、**18 个 missing 逐条归因且结论是「无一是适配缺陷」**、
  新增「两条不许动的映射」与「结构解码器已验证」两节、
  **撤销**「合并查询可能让 `WiFiModuleSwitch` 仍 missing」那条预判（基线证明它命中且 source 是自己，
  容器命令与扁平命令合并成一次请求没被设备吞掉）；
  **§4 的 0.4b 由「受阻」改成「就绪」**（前置条件 P0-3 已解除）并把任务清单与验收写实
  （验收 = 重抓一份 `field_coverage` 按 §14.3 四条判据比对本基线）；
  §14.3 的 missing 数 19 → **18**、判据 3 标注「本轮起真的可用」、判据 4 补两类「任何状态都 missing」的项；
  §15 **P0-3 结案**（`d15c368`，并写清「不是为大小写统一而改，是修一个查不到值的字段名」）、
  **P1-2 结案**（固件不给 5G 带宽）、**P1-1 改写**（原猜测「大小写」被排除，真因是 CELL_INFO 那几条
  没有 decoder、空值也算命中）、新增 **P1-23**（web 复制按钮失效，推断根因 + 另一个代理正在修）
  与 **P1-24**（约 30 个清单未收录字段待多态 dump 验证，附取证方法）；
  §9 追加执行记录批 12。
  本轮**不改任何代码**、**没有重跑 Gradle**（另一个代理在改 `web/**`、用户在改 `scripts/**` 与 `app/**`）。
- 2026-09-22 **0.4b（`680fbae`）落地后按实测同步本文档（批 13 文档轮）**：
  §2.2 B 类的状态块与 fallback 那一条按实测重写（6 个常量 + 汇总表**已全部删除**，
  命令表现在只有一份）；**§4 的 0.4b 由 `[ ]` 改成 `[~]`**（不是 `[x]` —— 第 3 层无真机未验）
  并写清做了什么 / 两个设计裁决 / 7 处刻意保留 / 解锁条件；
  §4 任务清单的状态口径块重写；**§4 验收的读命令判据按两个口径分别写清**
  （`client.query(listOf(` = **7**；绕过 profile 的读入口总数 = **9**）并记下「我把两个口径算混了」
  这个错误；§4 验收的排障开关那条更新守门测试名（随 fallback 删除改名，不变量未变）；
  §4「怎么做」的 0.4 段把「不要直接删 fallback」「必须连 soloCmds 一起切」「建议新增 FieldGroup」
  三段**保留原文并各加一段落地注记**（后两条在 0.4b 落地时按语义被推翻/被否，理由写清）；
  §4 0.8 的守门测试条数 8 → **9**；§9 追加执行记录批 13 与**新增「阶段 0 收尾盘点」一节**；
  §14.4 的真机清单条数 12 → **14**（逐项数过）；
  §15 **P1-21 结案**（三组已纳入冻结表，10 组全覆盖）、**P1-22 结案**（`fullStatusCmds()` 已落地）、
  **P1-20 修订**（不变量现在只由注释维系，彻底解决要给 `DeviceProfile` 加「分批读」表达 → 阶段 2）、
  **P2-1 修订**（本轮只核了「fallback vs cmdsFor」的一致性，**没有**逐个验 cmd 名在设备上真可发）、
  新增 **P1-25**（`getFullStatus()` 的 96 项里相当一部分没有登记 canonical）；
  §16 末尾补一条「0.4b 已落地且**未新增 `FieldGroup`**，所以不需要追加第二份基线」。
  本轮**不改任何代码**、**没有重跑 Gradle**（用户正在并行改 `scripts/**` / `app/**` / `web/src/**`）。


### 执行记录

- 2026-09-22 **改造前全量本地备份** commit `4f9b3e1`（196 个变更文件，含进行中的 web/app WIP）。仅本地，未推送。
- 2026-09-22 **阶段 0 批 1**（device-schema 类型与登记）完成。
  `WriteSpec` 加 `retry: RetryPolicy = NEVER` 与 `fallback: WriteSpec? = null`；
  现有 **18** 项全部显式标 `RETRY_ON_SESSION_LOSS`；新登记 9 项
  （REBOOT / FACTORY_RESET / SHUTDOWN / USB_MODE / BACKEND_PASSWORD / CONNECTION_MODE /
  WIFI_POWER / WIFI_CHIP / WIFI_MODULE）。
  第1层 ✓（`:core:goform:compileDebugKotlin` 绿，证明下游未破）/ 第2层 ✓（138 条全绿）/
  第3、4层 未跑（本批不改行为，且无真机）。
  裁决：`CHANGE_PASSWORD` 的**哈希由调用点做**，params 传 `old_hash`/`new_hash`，
  encode 只做字段名映射 —— 算法与登录握手共用 `GoformClient.sha256Hex`，不在 profile 复制第二份，
  且明文口令不进 profile 少一处泄露面。**§4 与 §11.3 原先写的「哈希在 encode 里做」已按此更正。**
  追认：`已登记的 command 不重复` 这条现有断言收紧成白名单式「重复必须是刻意的复用」——
  `CONNECTION_MODE` 与 `ROAM` 必然共用 `SET_CONNECTION_MODE`（profile 里 ROAM 的注释写明了），
  不变量本身不正确，按 §13.1 第 3 条以代码注释为准。
- 2026-09-22 **阶段 0 批 1b**（按取值选命令）完成。用户裁决采用**方案 b**：
  `WriteSpec` 加 `commandOf: ((Map)->String)? = null`。登记 `MOBILE_DATA`（主命令 commandOf 选
  CONNECT/DISCONNECT + `notCallback=true`，fallback = `SET_DATA_ENABLED&data=1/0`）、
  `PPP_DIAL`（同命令但**无兜底**，对应 `connectNetwork`/`disconnectNetwork`）、
  `WIFI_ENABLED`（合并批 1 的 `WIFI_CHIP`+`WIFI_MODULE`，两者已删 —— grep 确认
  `switchWiFiChip`/`switchWiFiModule` 在生产代码里只有 `setWifiEnabled` 一个调用点）。
  第1层 ✓ / 第2层 ✓（142 条全绿）。`SettingKey` 共 28 项。
- 2026-09-22 **阶段 0 批 2**（写调用点迁移）完成，commit `36fa526`。
  `GoformDeviceClient` / `GoformNetworkClient` / `GoformWifiClient` 的 **11 个**硬编码调用点
  改走 `writer.write` / `writeChecked`：重启 / 关机 / 恢复出厂 / USB 档位 / 改后台口令 /
  移动数据 / 手动拨号 / 挂断 / 连接模式 / WiFi 功率 / WiFi 总开关。
  第1层 ✓ / 第2层 ✓（**190 条**全绿，新增 `GoformSettingWriterDecisionTest`：
  以假发送器覆盖「选命令 / 拼 body / 选重试路径 / 三态收敛 / 兜底」整条写路径）。
  三条实测结论：
  - `changePassword` 的副作用**不是缺失**：`settings.goformPassword` + `updateGoformPassword()`
    （内部 `resetLogin()`）+ `cache.invalidate("device:*")` 三件事在 route 层是一组，
    位置 `DeviceRoutes.kt:348-354`。搬一件进客户端只会让真源变两份，故逐字保持现状。
  - `isTest=false` 一律不进 `encode`（`GoformCodec.buildSetFormBody` 恒发并跳过调用方自带的那份）。
  - **测试覆盖缺口**：`GoformSettingWriter` 与真实 `GoformClient` 之间的**装配**无单测 ——
    构造参数是具体类 `GoformClient`，注入不了假对象，依赖阶段 1 接口化后才能补。
- 2026-09-22 **阶段 0 批 3**（死代码清理 + WiFi 合并尝试）**部分完成，命中两个 P0**。
  - 完成：**删除 `setUsbMode`**（用户裁决）。`GoformDeviceClient.setUsbMode` +
    `SettingKey.USB_MODE` + 其 `WriteSpec` + 2 条测试用例 + retry 全量对照表那一行一起删。
    删前 grep 复核：`core/**`、`app/**`、`web/**`（排除 `build*` 与 `core/src/main/assets/web`）
    **零生产引用** —— 只有声明本身、profile 登记与两处测试。
    `web/src/api/contract.ts:439` 的 `'device/usb-mode'` 是**已登记为「core 无实现」的端点名**
    （`Endpoints.kt:21` 与 API Reference 都写明了），不是对这个方法的引用。
    `SettingKey.USB_PORT`（`setDebugMode`，USB 调试开关）是另一件事，保留未动。
    `SettingKey` 由 28 项变 **27 项**；测试 190 → **189** 条全绿（删掉的就是 USB 那一条）。
  - **未做：任务 B（频段全集搬 profile）→ P0-1**；**任务 C（WiFi 三处合并）→ P0-2**。
    两条都按 §13.2 停手，事实与猜测分开记在 §15，等裁决后再动。
  - 第1层 ✓（`:core:goform:compileDebugKotlin` / `:core:api:compileDebugKotlin` /
    `:core:compileDebugKotlin` 全绿，证明删除没有打断任何下游）/ 第2层 ✓（189 条）/
    第3、4层 未跑（本批不改任何下发报文，且无真机）。
  - 落地 commit：`0f3f504`（含本文档 10 处同步；批 3 的两个 P0 就是在这一轮记进 §15 的）。
- 2026-09-22 **阶段 0 批 4**（先修 WiFi 口令 bug）完成，commit `44dec16`。
  **裁决（用户）：先单独一个 commit 修 bug，再做三处合并** —— 不允许把 bug 修复夹在搬运 commit 里，
  否则出问题时分不清是「搬错了」还是「修错了」（§13.1 第 1 条）。
  改了什么：`getCurrentWifiConfig()` 返回的 `Password` 是**已解码的明文**，而设备侧
  `setAccessPointInfo` 的 `Password` 要 base64 —— 两处误用一起修：
  `setWifiSSID` 明文直发（会把设备口令写成「明文当 base64 解出来的字节」）、
  `setWifiConfig` 对已是明文的值又 `base64Decode` 一次（解码失败返回空串 →
  「只改加密方式不传口令」把口令**清空**）。统一成「读明文 / 写 base64」单向管线，
  并把这条契约写进 `getCurrentWifiConfig` 的 KDoc（下一个人不读代码也能看到）。
  第1层 ✓ / **第2层：本轮没有新单测** —— commit 说明里写明「回归保障待下一步把 Password 管线
  收进 `WriteSpec.encode` 后补」，那是批 6/7 的 `GoformWifiApParamsTest`；
  第3、4层 未跑（**这一批是真正的行为变更，commit 说明已标注「需真机验证」**）。
  这一批同时解开了 P0-2 的阻塞事实（见 §15 P0-2「已解除」）。
- 2026-09-22 **阶段 0 批 5**（设备文本解码字符集）完成，commit `858a9c9`。
  **裁决（用户）：字符集不对称现在就修（选项 b）**，不推后 —— 因为批 4 修完之后
  「读回明文 → base64 写回」变成了**常规路径**，不对称会让每一个非 ASCII 口令在一次
  「读回再写回」里被改坏。
  改了什么：`GoformClient.decodeDeviceText` 从无条件 GBK 改成「**UTF-8 优先、GBK 回落**」，
  判据是**无损往返**（UTF-8 解出来再编回去与原字节逐一相等）而不是「解出来含不含 U+FFFD」——
  JDK 对非法 UTF-8 不抛异常只替换，且口令本身可以真的含 U+FFFD，两种情况只有往返判据都覆盖。
  顺带发现原实现里那层「再做一次 UTF-8 往返」是**死代码**（在已解好的 `String` 上再编再解 = 恒等变换），
  一并删除。解码彻底失败**仍返回空串**（`/api/wifi/settings` 的读路径依赖该语义，刻意不动）。
  第1层 ✓ / 第2层 ✓（新增 `GoformBase64CharsetTest` **7** 条）/ 第3、4层 未跑（无真机）。
- 2026-09-22 **阶段 0 批 6**（WiFi AP 配置进 profile）完成，commit `090fcad`。
  新增 `SettingKey.WIFI_AP_CONFIG`（`command = "setAccessPointInfo"`）：7 个 canonical 入参
  （`ssid` / `auth_mode` / `encrypt_type` / `passphrase` / `max_sta_num` / `broadcast_disabled` /
  `chip_index`）+ 恒发的 `ApIsolate=0` / `AccessPointIndex=0`，`Password` = base64(明文, UTF-8)。
  **裁决：`Password` 的发送条件只看 `passphrase` 键在不在**，不在 encode 里判
  `auth != OPEN && encryp != NONE` —— 三个调用方的条件本来就不同（「只改口令」连 OPEN 也发），
  那是**调用方意图**不是设备事实；写进 encode 就再也共用不了一份实现。
  同一个 commit 里把 `WIFI_PASSWORD_DECODER` 也改成 UTF-8 优先（与批 5 的判据一致）——
  代价是**回滚本 commit 会同时撤掉 profile 侧的字符集修复**，这一点记在 commit 说明里。
  第1层 ✓ / 第2层 ✓（`ZteGoformProfileTest` 101 → **115** 条）/ 第3、4层 未跑（无真机）。
  注意：这一步之后 `WIFI_AP_CONFIG` 暂时**没有消费方**（接线在批 7），这是刻意的中间状态。
- 2026-09-22 **阶段 0 批 7**（三处 `setAccessPointInfo` 接线）完成，commit `118ed84`。
  `setWifiConfig` / `setWifiSSID` / `setWifiPassword` 的方法体收成「读回当前值 → 合并纯函数 →
  一次 `writer.write(SettingKey.WIFI_AP_CONFIG, …)`」，**签名与返回类型一字未动**。
  合并逻辑抽成三个 `internal` 纯函数（`mergeApConfigParams` / `mergeApSsidParams` /
  `mergeApPasswordParams`）放在 companion：本类持有的是具体类 `GoformClient`，端到端注入不了假对象，
  抽出来才能逐字断言「三个入口各放哪几个键」—— 整表替换命令多一个键 / 少一个键都会静默改掉设备配置。
  **唯一刻意的行为变更（用户裁决）**：`setWifiSSID` 在 `AuthMode == "OPEN"` 时原先照发
  非 `NONE` 的 `EncrypType`，现由 profile 强制 `NONE`。裁决理由：`OPEN` + 有加密算法在设备侧
  本身是矛盾组合，另两个入口改造前就都强制 `NONE`，**只有这里漏了，属笔误** ——
  统一成 profile 的一份判断，而不是把笔误抄进 profile。
  第1层 ✓ / 第2层 ✓（新增 `GoformWifiApParamsTest` **19** 条）/ 第3、4层 未跑（无真机）。
- 2026-09-22 **阶段 0 批 8**（短信规则立契约）完成，commit `2d92e05`。
  新增 `SmsSpec` 接口 + `ZteSmsSpec` 实现 + `DeviceProfile.smsSpec()`（默认 `null` = 不支持短信，
  不是「忘了实现」）。短信不进 `SettingKey` 的三条理由见 §11.2，三条都在这一轮复核过仍然成立。
  与 §11.2 原先列的签名有**两处偏差，以实现为准**（§13.2 的 P1，已在 §11.2 更正）：
  `markReadParams` 多一个 `read: Boolean = true`、`SET_MSG_READ` 的参数集**不含** `notCallback`。
  时间戳与时区改成**由调用方传入**，实现里不读时钟 —— 否则整张参数表没法断言，
  而「缺 `sms_time` / 格式不对」恰恰是「短信能收不能发」的历史根因。
  第1层 ✓ / 第2层 ✓（新增 `ZteSmsSpecTest` **23** 条，其中 `sendParams` 的期望值与 goform 侧
  `GoformSmsSendParamsTest` 逐字对齐 —— 这是下一轮删旧实现的依据）/ 第3、4层 未跑（无真机）。
  **本轮是刻意的暂时重复**：`GoformSmsClient` 里那份实现（`buildSendParams` / `toUcs2Hex` /
  `formatSmsTime` + 两个 tag 常量）下一轮接线时删，两份并存是预期状态、不是漏改。
- 2026-09-22 **文档同步（本轮，不改代码）**：按上面 8 个 commit 的实际结果核对并更新
  §2.2 / §3.4 / §4 / §9 / §11.2 / §11.3 / §15 / §16。
  核对方式是逐条回代码里查（`SettingKey` 28 项、`writeSpecs` 28 项逐项对齐、
  `"goformId" to` 剩 4 处、`markReadParams` 的 `read` 参数在 `app/viewmodel/.../ToolsModule.kt`
  确有 `read=false` 的调用点），**不照抄上一轮的数字**。
  两层校验的说明：第1、2 层的 ✓ 沿用各轮 commit 说明里的自报结果 ——
  本轮**没有重跑 Gradle**，因为另一个代理正在并行改 `core/goform/**` 与 `core/src/**`，
  抢构建锁会既拖慢他也让我拿到一份混合状态的结果（§13.3 最后一条）。
- 2026-09-22 **阶段 0 批 10**（修 `cmdsFor(WIFI_SETTINGS)` 的误登记）完成，commit `d435fa4`。
  改了什么：`cmdsFor(WIFI_SETTINGS)` 从 **10 项**（第 2 项是 `"WiFiModuleSwitch"`）改成 **14 项**
  = 客户端 `getWifiSettings()` 的 12 个扁平 cmd（`GoformWifiClient.kt:205-211`）
  + `getWifiModuleInfo()` 的 2 个容器命令（`:39`），**顺序与客户端逐字逐序一致**。
  `"WiFiModuleSwitch"` 是**设备响应键**、是 `MODULE_SWITCH` 别名链的首项，**不是可发的 cmd**。
  **成因**：`core/contract/.../DeviceFields.kt:168` 的 `const val MODULE_SWITCH = "WiFiModuleSwitch"`
  —— canonical 名、别名链首项、错抄进命令表的那个串，**三者是同一个字符串**，
  所以有人看到别名链首项就当成了命令名。（这也解释了 §16 为什么把它写成 `module_switch`「待核」：
  覆盖率报告输出的是 canonical，而这个 canonical 本身就是驼峰串。）
  **影响面只有诊断**：`cmdsFor(WIFI_SETTINGS)` 的唯一消费方是 `/api/diagnose?fields=1` 的
  `coverageReport()`；`GoformWifiClient` 那两处查询是硬编码列表、**不走 mapper**。
  线上活路径一直能命中 → **是诊断误报，不是功能缺陷**。
  **客户端的 12+2 项一个字未动**（用户裁决：不改变原有 goform 命令）。
  第1层 ✓ / 第2层 ✓（`ZteGoformProfileTest` 115 → **117** 条，新增 2 条：
  ① 14 项逐字 + 顺序冻结；② `WiFiModuleSwitch` 不许回来 —— 后者配了一条
  `containsAll(wifi_enable, wifi_onoff_state)`，因为单独一条 `assertFalse` 挡不住
  「删了但没补真 cmd」那种改法）/ 第3、4层 未跑（无真机；这一项本来就只影响诊断输出）。
- 2026-09-22 **阶段 0 批 11**（0.4a：mapper 双 profile + 命令表守门测试）完成，commit `9fa0473`。
  做了什么见 §4 的 0.4a 那一条（逐条已回代码核过），要点是**零行为变化**：
  `cmds()` 仍读 `normalizeProfile`，`ComponentFactory` 那两行代码未变、只加了 5 行注释。
  第1层 ✓ / 第2层 ✓（新增 `GoformCommandTableGuardTest` **8** 条）/ 第3、4层 未跑（无真机）。
  **本轮出现了一次两个并行代理之间的真实交叉验证**（值得记下来）：
  守门测试第一次跑是**红的** —— 我按「实测不一致的组」写进例外集合时 `WIFI_SETTINGS` 还在里面，
  而另一个代理已经在 `core/device-schema` 侧把它修好落盘（批 10），
  于是「实测不一致的组」少了一个、那条**双向相等**断言按设计报红；把 `WIFI_SETTINGS`
  从例外集合里移除后转绿。这证明那条断言不是「检查一个已知问题」，
  而是「**任一侧变动都会立刻暴露**」—— 也正是 0.4b 判断「能不能安全删 fallback」的依据。
  **用户裁决（原话）**：「**不要自己为了大小写而改动**」「按照原来的就行了」——
  `CELL_INFO` 的 `lte_snr` / `Lte_snr` 两侧字面量**都保持现状，不统一**，登记为 P0-3。
- 2026-09-22 **阶段 0 批 12**（字段名全仓核对与修复 + 覆盖率入口补齐）完成，
  三个 commit：`d15c368`（core）+ `913ff27`（web）+ `190691a`（app）。
  - **`d15c368`：`CELL_INFO` fallback 的 `lte_snr` 改成设备真名 `Lte_snr`。**
    改了 3 个文件（`ZteGoformProfile.kt` +6 注释 / `GoformSignalClient.kt` / 守门测试），
    客户端那份列表除末项外一个字符未动。**这是 P0-3 的结案**，与批 11 的「不要为大小写而改动」
    不冲突：真机 dump 证明 `lte_snr` 这个键**在设备上不存在**（4G 驻网回 `Lte_snr`），
    所以改的不是「大小写风格」，是**一个查不到值的字段名** —— 排障模式下该项此前永远查不到东西；
    正常模式走 `cmdsFor(CELL_INFO)` 本来就发 `Lte_snr`，**对外行为不变**。
    守门测试的 `knownDivergentGroups` 随之清空为 `emptySet()` 且仍绿
    （那条双向相等断言对**新增**分叉依然有效），另加一条字符级断言钉住两侧末项。
  - **全仓核对范围（这一轮的主要工作量，不是那一行改动）**：`cmdsFor()` 的 **10 个分组**、
    `readSpecs()` 的**全部别名链**、客户端**所有硬编码 cmd 列表**（含 `getFullStatus()` 的
    **96** 项：三批 30 / 29 / 37，`GoformSignalClient.kt:168/180/193`）。
    结论：**凡在真机 dump 里出现过的字段名，全部逐字一致，只有 `lte_snr` 一处错。**
  - **约 30 个「清单未收录」的字段一律未动**（真机 dump 里没出现过，无从判断对错），
    **列入待真机验证** → §15 的 **P1-24**，那一条写明了取证方法
    （在不同设备状态下多抓几份 dump：开启定时重启、锁一次频段 / 切 4G 驻网 等，
    每份都用裸命令抓不过归一化的原始响应）。
    **用户裁决：「不允许漏修埋坑」** —— 这批不是放过，是登记为待验，拿到新 dump 就接着核。
  - `913ff27`（web）给「设置 › 关于 › 诊断信息」加「字段覆盖率」入口 + 弹窗 + 复制 JSON；
    `190691a`（app）给诊断页那张覆盖率卡补复制按钮、逐组 `命中/登记` + missing + `hit_source`、
    并把 `queried=false` 单独标成「未登记命令」（它与「查了 0 命中」是两件事）。
    **这两个入口就是 §14.3 判据的取数工具** —— 有了它们，§16 才拿到第一份**可逐字比对**的基线。
  - 副产物：web 的「复制 JSON」按钮实测**点了没反应**（基线是手动复制出来的）→ §15 **P1-23**。
  - 第1层 / 第2层：沿用三个 commit 说明里的自报结果（`d15c368` 自报守门测试全绿）；
    **本文档轮没有重跑 Gradle** —— 另一个代理正在改 `web/**`、用户在并行改 `scripts/**` 与 `app/**`，
    抢构建锁只会拿到一份混合状态的结果（§13.3 最后一条）。
    第3层：**这一轮第一次真正做了** —— 拿到真机 `field_coverage` 并写进 §16 作为后续比对基准；
    第4层（写操作真机回归）仍未做。
- 2026-09-22 **阶段 0 批 13**（0.4b：命令表收敛到 `commandProfile`，删掉全部 fallback）完成，
  commit `680fbae`。改了 6 个文件（`DeviceProfile.kt` / `ZteGoformProfile.kt` /
  `GoformFieldMapper.kt` / `GoformSignalClient.kt` / `GoformWifiClient.kt` / 守门测试），
  `+428 / -323`。**`ComponentFactory` 不在改动清单里**（两个客户端的构造签名没变）。
  1. **`GoformFieldMapper.cmds(group)` 删掉 `fallback` 参数**，实现改成
     `commandProfile.cmdsFor(group)`；新增 `fullStatusCmds()` 同样只读 `commandProfile`。
  2. **`GoformSignalClient` 的 6 个 `*_FALLBACK_CMDS` 常量 + `FALLBACK_CMDS` 汇总表 + 6 处传参
     全部删除**（`internal companion object` 整块移除）。**删之前用脚本逐组按 token 序列比对过**：
     IDENTITY 20 / CELL_INFO 10 / LAN_SETTINGS 9 / DEVICE_SETTINGS 16 / BAND_STATUS 2 /
     TRAFFIC_LIMIT 10，**六组全部逐字含顺序一致** → 删除不改变任何实际发出的 cmd。
  3. `getSignalInfo()` 的 16 个字面量 → `fields.cmds(FieldGroup.SIGNAL)`（16 项逐字同序核对过）。
  4. `DeviceProfile` 新增 `fun fullStatusCmds(): List<List<String>> = emptyList()`，
     `ZteGoformProfile` 用私有 `FULL_STATUS_CMD_BATCHES` 实现。
     **签名用外层列表表达批次边界**，理由：批次边界是**设备事实** —— 一次发 96 项会被截断，
     本仓有 `station_list` 因合并查询被设备吞掉的先例；用扁平 `List<String>` 调用点就得自己切片
     = 把刚搬走的设备知识搬回客户端。
     **不做成 `FieldGroup`** 的理由：`coverageReport()` 遍历 `FieldGroup.entries`，
     加枚举值会让 `field_coverage` 多一个块，直接冲掉 §16 基线与 §14.3 的判据 1/2 ——
     而 0.4b 的验收恰恰是拿那份基线比对。
  5. `getFullStatus()` 三批实测 **30 / 29 / 37 = 96**，搬运做了**机器验证**（序列逐字相同）。
     **顺手更正了两处过期注释**：源码原写 `30/28/30+`、KDoc 写「75+ 字段」，两个都不准。
  6. **其余 7 处硬编码查询刻意不动**，各加一行注释「刻意的轻量/分批查询，不走 profile 命令表；
     字段名核对依据是 §16 的真机基线（2026-09-22）」：`getNetworkInformation`（2 项，无对应组）、
     `getDeviceInfo`（5 项，IDENTITY 有 20 项）、`getDeviceVersion`（3 项，注释明写「刻意的轻量查询」）、
     `getTrafficStats`（6 项，含 realtime_*）、`getNeighborCellInfo`（1 项）、
     `GoformWifiClient.getWifiSettings`（12 项）与 `getWifiModuleInfo`（2 项）——
     后两者**合起来**才等于 `cmdsFor(WIFI_SETTINGS)` 的 14 项，但它们是**两次独立请求**，
     合成一次会改变请求形状。
  7. **`coverageReport()` 路径继续用 `normalizeProfile`**（诊断语义：报告「**当前生效的那份** profile
     登记了什么」，产出要与 §16 的 `registered` / `hit_source` 逐字比对）；
     `queryGroup()` 的 `cmds` 与 `solo` 都来自**同一个入参**，**内部同源** ——
     **0.4a 担心的「命令表来自 A、分批规则来自 B」在这个结构下不成立**（原判断已按语义推翻，
     理由写进 `queryGroup` 的 KDoc 与 §4「怎么做」的注记）。
  8. **守门测试 `GoformCommandTableGuardTest` 转型**：从「客户端 fallback vs `cmdsFor`」的一致性比对
     改成**命令表内容冻结** —— 比对对象随 fallback 删除而消失，只换成 `cmdsFor()` 会变成恒真式空转。
     现有 **9 条**：① 冻结表覆盖全部 `FieldGroup`；② `cmdsFor` 10 组逐字含顺序；
     ③ `soloCmds` 只有 `WIFI_CLIENTS` 的 `station_list`；④ `fullStatusCmds` 三批逐字（30/29/37/合计 96）；
     ⑤ **任何组都不许出现小写 `lte_snr`**（防两侧同时抄错成同一个错名）；
     ⑥ `cmds` 取 `commandProfile` 而非 `normalizeProfile`（marker profile 正面证明）；
     ⑦ 关掉归一化后 `cmds` 仍非空且等于 `commandProfile` 的登记表；
     ⑧ 关掉归一化时 `coverageReport` 不发查询；
     ⑨ `coverageReport` 走 `normalizeProfile` 的命令表（断言发出的 cmd 里没有 marker，
     且 `groups` 块数 == `FieldGroup.entries.size`）。
  9. 第1层 ✓ / 第2层 ✓ —— 四条 gradle 全绿（`:core:device-schema:test` / `:core:goform:test` /
     `:core:api:compileDebugKotlin` / `:core:compileDebugKotlin`），**守门测试 9 条真实执行**
     （不是 up-to-date 跳过）。**第3层 ✗ / 第4层 ✗（无真机）** → 0.4b 标 `[~]`。
  - **本轮在 brief 里给错的一个数字（写下来，避免下次再错）**：brief 写「`client.query(listOf(`
    应从 11 降到 9」，**实际降到 7**。错因是**把 WiFi 那 2 处算了两遍** ——
    11 = SignalClient 9 + WifiClient 2，本轮拿掉 `getSignalInfo` 1 处 + `getFullStatus` 3 处 = 4 → **7**。
    另一种能凑出 9 的算法是「**绕过 profile 的读入口总数**」= 7 处 `client.query(listOf(`
    + 2 处 `client.querySingle(`（`station_list` / `queryDeviceAccessControlList`，本轮未动）= **9**，
    这个数字本身是对的，只是分母不同。§4 的验收判据已按这两个口径分别写清。
- 2026-09-22 **文档同步（批 13 文档轮，不改代码）**：按 `680fbae` 的实际结果核对并更新
  §2.2 / §4 / §9 / §14.4 / §15 / §16，并新增下面的「阶段 0 收尾盘点」。
  核对方式是逐条回代码里查（`cmds()` 的实现、`FALLBACK` 全仓 grep、守门测试 `@Test` 计数 = 9、
  三批数量逐项数过 = 30/29/37、7 处注释逐处打开确认、`ComponentFactory` 用 `git show --stat` 确认未动），
  **不照抄 brief 里的数字** —— 也正因此抓到了上面那个 11→9 的算错。
  第1、2 层的 ✓ 沿用 `680fbae` 的自报结果，本文档轮**没有重跑 Gradle**（用户正在并行改
  `scripts/**` / `app/**` / `web/src/**`，抢构建锁只会拿到一份混合状态的结果，见 §13.3 最后一条）。


### 阶段 0 收尾盘点（2026-09-22，批 13 之后）

**结论先写**：阶段 0 的**代码工作已经全部落地**，但**阶段 0 还不能算完成** ——
四项真机验证一项都没做（§14.3 第 3 层与 §14.4 第 4 层），按 §14.6 的纪律
「四层里有任何一层没过就不能把阶段标 `[x]`」，整个阶段 0 只能标 `[~]`。

#### 0.1 ~ 0.8 的最终状态与对应 commit

| 子项 | 状态 | commit | 备注 |
| --- | --- | --- | --- |
| 0.1 `SettingKey` 补齐写命令 | `[x]` | 批 1 / 批 1b（`36fa526` 之前的登记轮） | 最终 **28 项**（`USB_MODE` 已删，SSID/口令合并为 `WIFI_AP_CONFIG`） |
| 0.2 新增 key 登记 `WriteSpec` | `[x]` | 同上 | `writeSpecs` 与 `SettingKey` 逐项对齐 28 : 28，无孤立 key |
| 0.3 写调用点改走 writer | `[x]` | `36fa526`（11 处）+ `118ed84`（WiFi 3 处） | `core/goform/src/main` 里 `"goformId" to` 只剩 `LOGOUT` 1 处（归阶段 1）；另有 2 处字符串形态的登录命令该 pattern 抓不到 |
| 0.4a mapper 双 profile + 守门测试 | `[x]` | `9fa0473` | 纯结构准备、**零行为变化**；守门测试当轮 8 条 |
| 0.4b 命令表真正切过去 + 删 fallback | **`[~]`** | `680fbae` | 第1、2 层 ✓；**第 3 层未验（无真机）** → 解锁条件见 §4 的 0.4b |
| 0.5 设备值域搬进 profile | `[~]` | `090fcad` + `118ed84` + `858a9c9` | WiFi 固定枚举与 base64 方向已搬；**频段全集（P0-1）与二维码文件名模板（P1-5）已裁决推阶段 2** |
| 0.6 `WriteSpec.retry` | `[x]` | 批 1 | 原 18 项显式标 `RETRY_ON_SESSION_LOSS`；4 项破坏性动作为 `NEVER` |
| 0.7 短信走 `smsSpec()` | `[x]` | `2d92e05`（立契约）+ 客户端接线 | 六处走 spec，goform 侧旧实现已删；短信**不进** `SettingKey`（§11.2） |
| 0.8 补测试 | `[~]` | 跨多轮 | 单测已全面（见 §4 的 0.8 清单，守门测试现 **9** 条）；差**装配层**（等阶段 1 接口化）与**真机**那一半 |

顺带产出的、不在原任务清单里但属于阶段 0 的修复：
`44dec16`（WiFi 口令管线两个 bug）、`858a9c9`（设备文本解码字符集）、
`d435fa4`（`cmdsFor(WIFI_SETTINGS)` 的响应键误登记）、`d15c368`（`lte_snr` → `Lte_snr`）、
`913ff27` + `190691a`（web / app 的字段覆盖率入口 —— §14.3 判据的取数工具）。

#### 阶段 0 尚未完成的真机验证清单（**交给用户的待办**）

四项都需要真机，我做不了。每项后面写清「不验的后果」，因为这四项**都不是形式主义** ——
其中两项对应的是已经发生的行为变更。

1. **`44dec16` 的 WiFi 口令修复 6 步回归**（第 4 层）
   - ① 改 SSID **不带密码** → 改完用**原密码**能连上；
     ② 改配置**不传 `passphrase`** → 口令不变（原来这条会把口令清空）；
     ③ 单独改密码；④ 同时改 SSID + 密码；⑤ 扫码直连（二维码是设备按当前 SSID/口令实时生成的）；
     ⑥ 中文/非 ASCII 口令（可选，验 `858a9c9` 的 UTF-8 优先判据）。
   - **不验的后果**：这一批是**真正的行为变更**（`44dec16` 的 commit 说明就标了「需真机验证」），
     且改的是「写口令」这条路径 —— 错了的表现是**用户连不上自己的 WiFi**，
     而单测只能证明参数表拼对了，证明不了设备接受。
2. **重抓一份 `field_coverage` 与 §16 基线比对**（第 3 层，**0.4b 的解锁条件**）
   - 入口：真机 web「设置 › 关于 › 诊断信息 › 字段覆盖率」或 `GET /api/diagnose?fields=1`；
     **先记设备状态**（驻网制式 / `ppp_status` / WiFi 接入数 / 定时重启开关），再看数字。
   - 四条判据（§14.3）：`registered` 逐组不变（合计 **85**）、`queried` 全 true、
     `hit_source` 逐字不变、`hit` 只增不减（≥ **67**）。
     **本轮没有新增 `FieldGroup`**，所以组数与 `registered` 合计按设计不变，
     §16 **不需要**追加第二份基线。
   - **不验的后果**：0.4b 删掉了「排障模式下唯一的命令来源」并把 96 项 dump 搬进了 profile。
     脚本级的逐字比对能证明**搬运没抄错**，但证明不了**设备照旧应答** ——
     真出问题的表现是某些字段悄悄变 missing（不报错、不崩溃），这正是 §14.3 判据 3/4 的用途。
3. **`field_normalization_enabled=false` 的端到端回归**（第 4 层）
   - 把开关设成 false、**重启后台服务**（该值只在构造组件图时读一次），然后：
     仪表盘 / 网络 / WiFi **三个页面仍有数据**，且 `/api/diagnose` 的
     `device_profile.normalization_enabled` 为 **false**。
   - **不验的后果**：这是 0.4b 风险最集中的一处 —— 排障模式下 `normalizeProfile = null`，
     命令表全靠 `commandProfile` 顶着。切错了的表现是**关掉归一化就整个只读面变空**，
     而那正是排障时最需要它工作的时刻。单测替身（守门测试第 ⑦⑧ 条）只覆盖取值、不覆盖端到端。
4. **写操作真机回归清单**（第 4 层，**14 条**，见 §4 验收最后一条）
   - 重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 / **手动拨号**与**挂断** /
     切连接模式 / 改 SSID / 改密码 / 改功率 / **开 WiFi** 与**关 WiFi** / 发短信 / 删短信 / 标已读。
     每条按 §14.4 做三次观察（点之前记状态 → 点并看返回码与文案 → 点之后确认设备状态真的变了）；
     异常路径另见 §14.4（会话失效重试、值域拒绝、设备离线、短信只发一条数收到几条）。
   - **不验的后果**：0.3 把 **14 个**写调用点的报文构造全换成了 `writeSpec.encode` ——
     整表替换命令多一个键 / 少一个键都会**静默改掉设备配置**；破坏性动作
     （重启 / 关机 / 恢复出厂）更是只有真机能验。

> **阶段 0 在这四项验完之前不能算完成。**
> 第 1、2 层证明的是「代码自洽」，第 3、4 层证明的是「设备照旧」——
> 这一阶段的全部目标恰恰是**行为不变**，而「行为」只在真机上存在。
> 另外按 §14.3 最后一句：**阶段 0 的写操作在没有真机验证的情况下不允许合进主线**
> —— 现在这些 commit 都只在本地，没有推送（也符合「未经指令不得自动推送」的口径）。






---

## 10. 附：每阶段完成后「接一台新设备」的成本

- 阶段 0~1 完成：同协议异型号 = 改 1 个 profile 文件（或加别名链）
- 阶段 2 完成：异协议 = 2 个文件（profile + transport），公共代码只动注册表一行
- 阶段 3 完成：新设备缺的功能，前端自动灰掉，不需要为它改 UI
- 阶段 4 完成：异平台 = 3 个文件（+ platform adapter + tuning）
- 阶段 5 完成：用户换设备零配置

---

## 11. 边缘情况与已决事项

这一节是「动手前必须读」的部分。每条都对应一个**会把功能改坏**的具体场景。

### 11.1 写路径的重试语义：`WriteSpec.retry`（已决）

**问题**：现有 19 个硬编码调用点走 `client.goformPost`（**不重试**），
而 `GoformSettingWriter.kt:65` 走 `goformPostIdempotent`（会话失效时重登并**重试一次**）。
直接搬进 writer 等于给这些命令**悄悄加上自动重试**。对 `SEND_SMS` 这类非幂等命令，
重试一次就可能发两条短信。

**决定**：给 `WriteSpec` 加显式策略，默认**不重试**：

```kotlin
enum class RetryPolicy {
    /** 会话失效时重登并重试一次。只给「同一取值幂等」的设置类命令。 */
    RETRY_ON_SESSION_LOSS,
    /** 永不重试。动作类 / 计费类 / 有副作用的命令。 */
    NEVER,
}
data class WriteSpec(
    val command: String,
    val encode: (Map<String, Any?>) -> Map<String, String>,
    val validate: (Map<String, Any?>) -> String? = { null },
    val retry: RetryPolicy = RetryPolicy.NEVER,   // ← 安全侧默认
)
```

为什么默认 `NEVER` 而不是 `RETRY_ON_SESSION_LOSS`：
- 漏标的代价不对称。默认重试时漏标 → 重复发短信 / 重复计费，**用户看不见、不可撤销**；
  默认不重试时漏标 → 少一次自动重试，用户手动再点一次即可，**可观测、可恢复**。
- 但**阶段 0 必须一次性给现有 18 个 `SettingKey` 显式标 `RETRY_ON_SESSION_LOSS`**，
  否则会复发一个已修的 bug：`GoformSettingWriter.kt:48-50` 记录了
  「切换网络制式第一次必定失败、再点一次才成」—— 那就是写路径缺重试的症状。
  **这一步漏掉 = 功能回归**，验收里要逐条对照。
- 命名用 `retry` 而不是 `idempotent: Boolean`：「幂等」是数学性质，我们真正要表达的是
  「会话失效时能不能重发」；而且将来要加「重试几次 / 退避多久」时有地方放。

新增项的建议取值（动手时逐条复核，不要照抄）：
- `RETRY_ON_SESSION_LOSS`：`CONNECTION_MODE` / `WIFI_ENABLED` / `WIFI_POWER` /
  `WIFI_AP_CONFIG`（原方案写的 `WIFI_SSID` / `WIFI_PASSPHRASE` 最终合成了这一个 key）/
  `MOBILE_DATA` / `PPP_DIAL`（设置类，同值幂等）。
  `MOBILE_DATA.fallback`（`SET_DATA_ENABLED`）亦为 `RETRY_ON_SESSION_LOSS`，**独立判定**
  （`USB_MODE` 原也在这张表里，2026-09-22 批 3 已随 `setUsbMode` 一起删除）
  → **2026-09-22 实测：全部按此落地**，且原有 18 项一项没漏（`writeSpecs` 28 项逐条核过）

- `NEVER`：`REBOOT` / `SHUTDOWN` / `FACTORY_RESET` / `BACKEND_PASSWORD`（改完密码旧会话必然失效，
  重试会用新密码登不上）/ 短信三项（走 §11.2 的 `smsSpec`，本来就不经 writer）

**一个还没解决的行为差异（2026-09-22 记）**：`setMobileData` / `connectNetwork` /
`disconnectNetwork` / `setWifiEnabled` 这几个调用点**现在走的是 `goformPost`（不重试）**，
接进 writer 后会走 `goformPostIdempotent`（会话失效重登重试一次）。
标 `RETRY_ON_SESSION_LOSS` 是「与其余 18 项一致、更健壮」，标 `NEVER` 才是「逐字保持现状」。
这不是能两全的事，取舍见 §9 的裁决记录。


### 11.2 短信不进 `SettingKey`，改走 `profile.smsSpec()`（已决）

**问题**（三个，任一都足以否掉「塞进通用写入表」）：
1. `encode` 会**不纯**：`GoformSmsClient.formatSmsTime()` 默认参数是 `System.currentTimeMillis()`
   + `TimeZone.getDefault()`（`:283`）。藏进 `WriteSpec.encode` 就没法断言 ——
   现有 `GoformSmsSendParamsTest` 之所以能测，正是因为 `buildSendParams(number, message, smsTime)`
   把时间作为**入参**（`:261`）。
2. 下发之后还有**回读确认**：3 次 × 1.2s 轮询信箱看 tag（`TAG_SENT="2"` / `TAG_SEND_FAILED="3"`，
   `:247-253`）。`WriteSpec` 只能表达「发一条命令」，表达不了「发完再查」。
3. 编码是短信专有的：`MessageBody` 要 UCS2（UTF-16BE 小写 hex，无 BOM）、`encode_type=UNICODE`、
   `ID=-1`、`sms_time` 是 `yy;MM;dd;HH;mm;ss;+TZ` 且 TZ 是**小时**偏移（半小时制给 `+5.5`）。

**决定**：在 `DeviceProfile` 上开一组短信专用契约，每设备各自实现。
**以下代码块是 2026-09-22（`2d92e05`）落地后的实际签名**，与原方案有三处差异，逐条写在下面：

```kotlin
interface DeviceProfile {
    /* …现有成员… */
    /** 短信规则。不支持短信的设备返回 null（对应 Capability.SMS 缺失）。 */
    fun smsSpec(): SmsSpec? = null
}

interface SmsSpec {
    /** 下发参数。时间戳与时区由调用方传入 —— 不许在实现里读时钟（否则没法断言）。 */
    fun sendParams(number: String, message: String, atMillis: Long, zone: java.util.TimeZone): Map<String, String>
    /** 列表查询的 cmd 与分页参数（只放短信专有键，通用键由客户端补）。 */
    fun listQuery(page: Int, perPage: Int): Map<String, String>
    /** 删除。 */
    fun deleteParams(ids: List<String>): Map<String, String>
    /** 标记已读 / 未读。`read` 有默认值，但**必须存在** —— 见下面第 1 条。 */
    fun markReadParams(ids: List<String>, read: Boolean = true): Map<String, String>
    /** 信箱行的 tag 语义 —— 「已发送 / 发送失败」的判据按设备而异。 */
    fun sentTag(): String
    fun failedTag(): String
    /** 正文编码（UCS2 / GSM7 / 明文…）。 */
    fun encodeBody(message: String): String
}
```

**与原方案的三处差异**（都是「以代码为准、回头改计划」，§13.1 第 3 条）：

1. **`markReadParams` 多一个 `read: Boolean = true`**。原方案漏了这个参数，只表达「标已读」——
   那会**静默丢掉一半既有行为**：对外 `POST /api/sms/read` 支持 `read=false`，
   `GoformSmsClient.markSmsRead(msgId, read)` 的两个分支都在被调用。
   已核实的调用点：`app/viewmodel/.../ToolsModule.kt` 会发
   `api.markSmsRead(mapOf("id" to …, "read" to "false"))`（把消息标回未读的 UI 动作），
   设备侧对应 `tag=1`。ZTE 的取值是 `tag`：`0` = 已读、`1` = 未读
   （与信箱行 tag 的 `2`=已发送 / `3`=发送失败**不是同一套值域**，只是共用了 `tag` 这个键名）。
2. **`SET_MSG_READ` 的参数集不含 `notCallback`**。原方案（§4 0.1 那段「Network 3 处、Sms 2 处」）
   假定它有 —— 实测 `GoformSmsClient.markSmsRead` 就没发：`DELETE_SMS` 有 `notCallback=true`、
   `SET_MSG_READ` 没有。看着像漏了，但**没有实测依据说明补上是安全的**，所以照抄现状，
   要不要补进 §15 单独记一条（真机验一次再决定）。
3. **`sendParams` 的输出含 `isTest=false`**，与写侧 `WriteSpec.encode` 一律不发 `isTest` 的口径不同。
   这**不是漏改**：短信走 `GoformSmsClient` 直接 `goformPost`，**不经 writer / `GoformCodec`**，
   而 `isTest` 是 `GoformCodec.buildSetFormBody` 统一补的 —— 两条路径补通用参数的位置不同而已。
   将来若把短信也收进 codec，这一项要与去重逻辑一起处理（§15 已记）。

回读确认的**循环**留在 `GoformSmsClient`（它是流程，不是设备事实），
但循环里用的**判据**（`sentTag` / `failedTag`）来自 spec。
轮询次数与间隔（3 × 1.2s）属于实测调参，进 §3.2 的 `DeviceTuning`。

### 11.3 其它「不能搬进 profile」的东西（已决）

`WriteSpec.encode` 是纯函数 `(Map)->Map`，下面这些都**不满足**，必须留在客户端：

- **读-改-写**：`GoformWifiClient.setWifiConfig` / `setWifiSSID` / `setWifiPassword`
  （都要先 `getCurrentWifiConfig()`）、`WIFI_ACL`（整表替换，
  `DeviceProfile` 的 `WIFI_ACL` KDoc 已写明「读-改-写收敛在 core」）。
  → **这条结论保留：profile 只收合并后的完整参数集，「先读回」是客户端的职责。**
  理由不是「不好写」，而是 `WriteSpec.encode` 的类型就是纯函数 `(Map)->Map` ——
  读回要发一次查询（`suspend` + 可能失败 + 会话失效），塞进 encode 等于让 profile 变成能做 I/O 的东西，
  「没有设备也能断言」这条底线就没了。

  > **2026-09-22 批 6/7 后的实际形态（原先这里写的已过期）**：
  > 三个入口现在**共用同一份 `encode`**（`SettingKey.WIFI_AP_CONFIG`）。
  > 原文说的「三条不同的 `Password` 管线、`setWifiSSID` 发明文所以共用不了」**已经不成立** ——
  > 那个「发明文」是 bug，`44dec16` 修掉了；现在三处都是「客户端给明文 `passphrase`，
  > profile 的 encode 做唯一一次 `base64(UTF-8)`」。
  >
  > 读-改-写留在客户端的那部分是 `GoformWifiClient` companion 里的**三个 `internal` 纯函数**：
  > `mergeApConfigParams` / `mergeApSsidParams` / `mergeApPasswordParams`。
  > 三个入口的差异**全部收敛成「往 params 里放哪几个键」**，没有一处再判设备侧细节：
  >
  > - 键不放（或放 null）→ 该项不发；`auth_mode` / `encrypt_type` / `broadcast_disabled` /
  >   `chip_index` 不发就走 profile 的缺省档（`WPA2PSK` / `CCMP` / `0` / `0`）
  > - `passphrase` **只要键在就发** `Password`（空串也发）→ 「不想动口令」必须不放这个键。
  >   这个条件不进 encode 是刻意的：「只改口令」那个入口对 `OPEN` 也发口令，
  >   那是**调用方意图**而不是设备事实
  > - `ApIsolate=0` / `AccessPointIndex=0` / base64 编码 / `OPEN` 强制 `NONE`：全在 profile
  >
  > 为什么把合并抽成纯函数而不是留在 `suspend` 方法体里：本类持有的是具体类 `GoformClient`
  > （阶段 1 才接口化），端到端注入不了假对象，抽出来才有 `GoformWifiApParamsTest` 这 19 条断言。
  > 设备侧 `setAccessPointInfo` 是**整表替换**，多一个键 / 少一个键都会静默改掉 AP 的某一项 ——
  > 这是「读取毫无影响、写入静默改坏设备」的一类错误，必须有逐字断言兜着。

- **有副作用的动作**：`CHANGE_PASSWORD` 成功后必须 `updateGoformPassword()` + `resetLogin()`，
  否则下一次请求还在用旧密码登录。副作用留在调用点，spec 只管参数与哈希。
- **多命令编排**：`setMobileData` 是「先 `CONNECT_NETWORK`，失败再 `SET_DATA_ENABLED`」。
  用 `WriteSpec.fallback: WriteSpec?`（默认 null）表达，由 writer 在主命令返回 `Failed` 时试一次；
  **不要**把 if 留在客户端。注意 fallback 的 `retry` 独立判定。

另外：`GoformSettingWriter.kt:59` 会打 `"$key 参数被拒绝：$reason"`。
`validate` 的返回值**不许包含参数值**（密码 / PIN / APN 凭据都会走这里），只说违反了哪条规则。

### 11.4 `Capability` 为什么进 `core/contract`（冻结区）（已决）

**`core/contract` 是什么**：纯 JVM 模块，5 个文件 —— `DeviceFields.kt` / `Endpoints.kt` /
`Enums.kt` / `ErrorCode.kt` / `Units.kt`。它是 **core 与 app / web 之间的共享词汇表**：
HTTP JSON 里出现的字段名、端点路径、错误码、单位，都在这里定义一次。
`device-schema` 依赖它，且 `DeviceProfile.kt:9` 明确要求 profile 的 canonical key
**必须取自 `DeviceFields` 常量、不许写字面量**（守门脚本会扫 route 里的字段字面量）。

**「冻结区」是什么意思**：这些名字一旦发布就变成对外协议的一部分，同一个名字有三份镜像 ——
core 的 Kotlin 常量、app 的 Kotlin 常量、web 的 `web/src/api/contract.ts`。
所以纪律是：

- **只增不改不删**。改名 = 三端同时改 + 旧版 app 连新 core 会读不到字段（静默丢数据，不报错）。
- 要废弃先加 `@Deprecated` 并保留一个版本，等三端都不读了再删。
- 新增对旧客户端是安全的：旧 app 遇到不认识的 capability 直接忽略。

**为什么 `Capability` 属于这里而不是 `device-schema`**：
能力集要下发给 app / web 做灰显，**枚举名就是 JSON 里的字符串** —— 它是对外协议。
`device-schema` 的定位是「设备侧说什么、怎么翻译成我们的话」（它依赖 contract，不是被依赖）；
把对外协议放进翻译层，等于让「设备适配」这件事反过来决定对外契约的形状。

**具体怎么写**（wire 名与符号名解耦，这样以后想改 Kotlin 符号名不会破协议）：

```kotlin
// core/contract/.../Capabilities.kt
enum class Capability(val wire: String) {
    SMS("sms"),
    SIM_SLOT_SWITCH("sim_slot_switch"),
    BAND_LOCK("band_lock"),
    CELL_LOCK("cell_lock"),
    NETWORK_MODE("network_mode"),
    SAMBA("samba"),
    USB_DEBUG("usb_debug"),
    FOTA("fota"),
    PERFORMANCE_MODE("performance_mode"),
    TRAFFIC_LIMIT("traffic_limit"),
    ;
    companion object { fun fromWire(w: String): Capability? = entries.firstOrNull { it.wire == w } }
}
```

web 侧在 `contract.ts` 加同名字符串联合类型，并加一个守门测试断言两端逐项对齐（任务 3.8）。
**第一批只定 10 个**（§7 的清单）：冻结区宁可晚定，也不要定一个将来要改名的。

### 11.5 两个裸命令端点是刻意的例外（已决）

`POST /api/device/goform/query`（`DeviceRoutes.kt:129-156`）与
`POST /api/device/goform/set`（`:161-196`）的 cmd / goformId **来自 HTTP 请求体**，
由 `goform_command_enabled` 开关守门（`AppSettings.kt:32`），日志只记键名不记值（`:173-177`）。

- 阶段 0 **不许**把它们搬进 profile —— 它们是排障通道，存在意义就是「绕过所有映射」。
- 阶段 0 的验收 grep 只扫 `core/goform/src/main`，天然不会命中它们；但执行的人看到
  `goformClient.goformPost(mapOf("goformId" to ...))` 容易顺手改，所以在这里写明。
- 阶段 3 要给它们加能力门禁：异协议设备上「goform 裸命令」无意义，
  应该回 501 而不是把 goform 请求发给一台不说 goform 的设备。
  建议新增 `Capability.RAW_GOFORM`（第二批再进冻结区，第一批不急）。

### 11.6 三种「不可用」必须分开（已决）

现在只有 `UNAVAILABLE` 一个码在承担三件事。阶段 3 之后：

- 设备不支持 → 501 `NOT_SUPPORTED`，**不可恢复**，前端灰掉、不重试
- 设备离线 / 会话失效 → 503 `UNAVAILABLE`，可重试（`GoformSettingWriter.kt:71-76` 已经这么分了）
- 提权失败（root 不可用）→ 503 `UNAVAILABLE` + 明确文案，不要报成「不支持」
  （同一台设备换个时机可能就成了）

**capability 的语义按「用户可见动作是否可达」定，不按「某个通道是否支持」定**。
反例：`NetworkRoutes.kt:186` 现在的判据是「goform 不可用 → 无法设置网络模式」，
但在别的设备上这个动作可能通过 AT 通道完成。定成 `NETWORK_MODE` 而不是 `GOFORM_NETWORK_MODE`。

### 11.7 模块依赖方向与 `AtTransport` 的位置（已决）

`PlatformAdapter.atTransports()` 返回 `List<AtTransport>`，而 `AtTransport` 现在在
`core/collector`（`at/AtTransport.kt:10`）。若 `device-spi` 依赖 `:core:collector`，
而将来 collector 又要依赖 `device-spi`（为了从 `DeviceRuntime` 取 profile）→ **成环**。

**决定**：阶段 4 把 `AtTransport` 上移到 `core/device-spi`，collector 改为依赖它。
`ATChannel`（限流/退避/熔断）留在 collector —— 它是我们的策略，不是设备事实。
注意 `AtTransport.name` 会出现在 `/api/at/status` 的 `method` 字段（接口注释里写了），
**上移时不许改 name 的取值**，否则就是一次静默的对外 JSON 变更。

### 11.8 probe 的时间预算（已决）

`ProbeEnv.goformLdReachable` 要发一次 HTTP，而 probe 在 `ComponentFactory.build()`
（`:106`，是 `suspend`，所以 `DeviceRuntime.resolve()` 用 suspend 没有结构问题）的启动路径上。

- 硬超时 **1.5s**，超时/异常一律当 `false`，不阻塞启动
- `/proc/cpuinfo` 与 `Build.*` 是本地读，可忽略耗时
- 整个 `ProbeEnv` **采一次后共享**给所有插件，不让每个插件各自去读
- 对照：`ATChannel.init()` 现在用 `withTimeoutOrNull(10_000L)` 包着
  （`ComponentFactory.kt:725`），probe 不能比它还慢

### 11.9 热区 / 电池读法统一必须单独一步（风险提示）

温度现在有**多个口径**：`DataScheduler.kt:596` 记录了「这里取全热区最大值、那边只读 zone0，
Unisoc 上稳定差数度」；`QoSRoutes.kt:32` 直接读 `thermal_zone0`；`DownloadManager.kt:113`
的限速阈值是 75/85；`AlertEngine.kt:279` 的告警阈值另有一套 + 3°C 抖动。

把它们统一到 `PlatformAdapter.readTemperature()` 会**改变告警与限速的实际触发点** ——
这是典型的「重构顺手统一，结果告警行为变了」。所以：

- 4.3 拆成两步：先只把**读法**收进 adapter 并**保持各调用点原有口径**（zone0 的继续 zone0、
  取最大值的继续取最大值，adapter 提供两个方法），再单独一个 PR 讨论要不要统一口径
- 保留 `SystemCollector.kt:232` 的结论（**不要硬编码** `/sys/class/power_supply/battery`，要遍历），
  同时修掉 `DownloadManager.kt:566` 违背该结论的那处硬编码
- 验收要对比同一台机器改造前后的 `/api/system/*` 读数**逐项一致**

### 11.10 运行期不允许热换插件（已决）

`ComponentFactory.kt:791` 已经记了这个结论：profile 只在构造组件图时读一次，
改配置要重启后台服务 —— 这样「切换后缓存里还躺着上一种形状的数据」在设计上就不成立。
`DeviceRuntime` 沿用同一条纪律：**不提供 `switchPlugin()`**，不做运行期热换。
设置页改完 `deviceProfileId` 要提示「重启后台服务后生效」。

### 11.11 历史数据跨设备（待决，不阻塞）

DB 里的 signal / traffic 历史按 canonical 字段存，换设备后它们混在一条时间线上
（前半段是上一台机器的）。三个选项：不管（现状）／按 `plugin_id` 打标并在图表上断开／
换设备时清历史。**先记下来，等真有第二台设备再决定**，不在阶段 0~5 范围内。

### 11.12 其它小项（动手时顺手带上）

- 插件 id 进崩溃报告：`CrashHandler.kt:86` 现在只写 `Build.*`，换设备后看崩溃报告
  得知道当时用的哪个插件
- `GoformSmsClient` 现在没有 profile 参数（`ComponentFactory.kt:765`）。0.7 加参数时
  **不许给默认值** —— `ComponentFactory.kt:759` 的注释记录了教训：
  「每个客户端的构造参数各带一个 `= ZteGoformProfile` 默认值，等于选型逻辑散在 6 个签名里，
  换设备要改 6 处且漏一处不会报错」
- `GoformClientGateway.kt`（19 行，`GoformGateway by client` 适配器）目前**无人使用**。
  阶段 1 要么用它做委托点，要么删掉 —— 不留一个没人调的空壳
- `GoformQoS` 是 `core/common` 的全局单例，缓存 key 是 cmd 名。异协议插件的 key 空间
  若与 goform 重名会互相命中。阶段 2 给 key 加 `plugin_id` 前缀
- 数量型能力（WiFi 有几个 chip、几个卡槽）用布尔集合表达不了。需要时加
  `DevicePlugin.limits: Map<String, Int>`，**不要**用 `WIFI_CHIP2` 这种带序号的布尔堆

---

## 12. 回滚

每个阶段都要能独立回滚，且回滚不依赖「记得改回某个配置」：

- **阶段 0**：纯搬运，`git revert` 即可。风险点是真机行为，所以验收里的 12 条手工回归是硬要求。
- **阶段 1**：只改签名与改名，`git revert`。若已进入阶段 2 才发现问题，先 revert 阶段 2。
- **阶段 2**：新增两个 module。回滚 = revert + 从 `settings.gradle.kts` 去掉 include。
  `DeviceProfiles` 保持可用（见 2.6），所以阶段 2 回滚后老路径立刻恢复。
- **阶段 3**：能力门禁是**新增拦截**，回滚后回到「打到设备才失败」的旧行为，不会数据不一致。
  前端那半可以独立回滚（拿不到 capabilities 时按「全支持」渲染 —— 这个降级要写进 3.5/3.6）。
- **阶段 4**：风险最高（见 §11.9）。要求每个子项一个独立 commit，且温度/电池/root 三类各自可单独 revert。
- **阶段 5**：probe 出问题时，用户填上 `deviceProfileId` 就能绕过（配置覆盖优先于 probe），
  不需要回滚代码 —— 这是把「配置覆盖」保留下来的主要理由。

排查过程中临时删掉的东西（如 §7 验收里临时删 `SAMBA` capability）**必须还原**，
不要留墓碑注释、不要留反向守卫。

---

## 13. 偏差处理 —— 计划与实际不符 / 要出严重错误时怎么办

本计划是**根据当日代码写的推断**，不是事实。实际动手时必然有对不上的地方。
这一节定的是「发现对不上之后做什么」，目的只有一个：**别在一次改动里同时改架构和修 bug**。

### 13.1 三条硬纪律

1. **一个阶段一个分支，一个子项一个 commit。**
   子项编号写进 commit 标题（如 `stage0.3: 设备/网络客户端写命令改走 writer`）。
   这样任何一条子项出问题都能单独 revert，不用回滚整个阶段（§12 的前提）。
2. **范围外的问题一律记进 §15，不当场修。**
   搬命令表的过程中一定会看到别的毛病（重复代码、错误处理不一致、注释过期）。
   顺手修的代价是：出问题时分不清是「搬运搬错了」还是「顺手那一下改坏了」。
   例外只有一种：**不修就没法编译/没法跑**的（如 §11.12 的 `GoformSmsClient` 缺 profile 参数）。
3. **计划与代码注释冲突时，以代码注释为准，然后改计划。**
   代码里的注释多半记着一次真实事故（`GoformSettingWriter.kt:48`「切换网络制式第一次必定失败」、
   `ComponentFactory.kt:759`「选型散在 6 个签名里漏一处不会报错」、
   `GoformGateway.kt:48`「符号名沿用 goform*：那是配置键的一部分」）。
   本计划第一轮复审就撤销了一条与注释冲突的改名 —— 这不是例外，是常态。

### 13.2 偏差分级与处理

**P0 — 立刻停手（stop the line）**

触发条件（命中任一）：

- 发现某个改动会**静默改变对外行为**且无法用现有测试发现（如 §11.1 的重试语义、
  §11.9 的温度口径、对外 JSON 字段名/取值变化）
- 发现**模块依赖成环**（如 §11.7、原 2.6 的委托方案）
- 发现某处「设备知识」在计划的清单**之外**，且它参与了写操作（可能改坏设备状态）
- 真机回归里任何一条**写操作**失败或行为与改造前不同
- `/api/diagnose` 的 `device_profile` 或 `field_coverage` 与基线不一致（见 §14.3）

处理：

1. 当前子项**就地 `git stash` 或提交到临时分支**，不要继续往下改；
2. 在 §15 写一条「P0-<编号> 现象 / 已确认的事实 / 未确认的猜测」，
   **事实与猜测分开写**（行号、命令输出、测试名算事实；「应该是因为」算猜测）；
3. 回到本文档改计划：把该子项拆细或改方案，并在 §9 变更记录追一行；
4. 计划改完再继续。**不允许「先改完再回来补文档」** —— 那就是本计划存在的理由消失了。

**P1 — 记录并继续（当前子项可以做完）**

触发条件：

- 行号漂移（符号还在、位置变了）→ 直接按符号名找，顺手更新文档里的行号
- 数量对不上（计划说 19 个调用点，实际 18 或 21）→ 以实际为准，更新文档并写清多/少的是哪几个
- 计划里某个命名不合适 → 改名可以，但要在文档同步，**不许文档一套代码一套**
- 发现一个范围外的**只读**缺陷 → 记 §15

处理：做完当前子项，在 §9 追一行，继续下一子项。

**P2 — 直接改，不必记录**

拼写错误、注释里的错别字、明显的格式问题。

### 13.3 四种「计划与实际不符」的典型情形

- **符号不存在了**：计划提到的类/方法被重命名或删除。
  → 先 `grep` 确认是否有等价物；有则按等价物做并更新文档；没有则升级为 P0（说明现状盘点已过期，
  整段任务清单需要重新核对，而不是只改这一条）。
- **实际耦合点比清单多**：例如在 `core/api` 或 `core/scheduler` 里又发现一处写死的设备命令。
  → 写操作 = P0（停手、扩清单、改计划）；只读 = P1（记 §15，阶段 0 不扩范围）。
- **测试断言与计划假设冲突**：例如 `ProfileContractTest` 断言「每个 FieldGroup 都必须有 cmd」，
  而计划要新增一个暂时没有 cmd 的 `FULL_STATUS` 组。
  → **不许为了让测试过而改断言**。先判断断言表达的不变量是否仍然正确：
  正确则改方案（换个分组策略），不正确则单独一个 commit 改断言并在 commit 说明里写清为什么。
- **用户在并行改同一批文件**：我没碰过的文件报编译错误时，先当成用户正在写，
  只编译自己改过的 module 隔离验证（见 §14.1），不要替他修也不要回滚。

### 13.4 「会出严重错误」的事前拦截

下面这些动作在**做之前**就要先写一条验证方案，不要做完再想怎么验：

- 改动任何**写路径**（19 个调用点、`GoformSettingWriter`、`WriteSpec.retry`）
  → 先按 §14.4 把真机 12 条写操作清单列出来，一条一条点，改前改后各点一遍
- 改动**登录 / 签名 / 会话**（阶段 1.4）
  → 先确认 `ensureLogin` / `invalidateSession` / `resetLogin` 三段语义注释整段搬过去了（§5 1.4），
  再验「连续 30 分钟轮询不掉线」（退避计数被误递增的症状是长会话后前端全被拦）
- 删除任何东西（`DeviceProfiles`、`GoformClientGateway`、`cmds` 的 fallback 参数）
  → 先 `grep` 全仓引用（含 `core/src/main/assets/web/**` 的打包产物不算），确认零引用再删；
  **删不掉就先标废弃，不要为了「干净」强行删**
- 动 `core/contract`（阶段 3.1）
  → 只增不改不删；新增后先跑一遍「旧 app + 新 core」的组合（§14.5）

---

## 14. 校验体系 —— 改完怎么证明没改坏

分四层，从快到慢。**每完成一个子项至少跑第 1~2 层；每完成一个阶段跑全部四层。**

### 14.1 第 1 层：编译与静态检查（秒级~分钟级）

```
# 只编译自己改过的 module —— 与用户并行改仓库时用它隔离
gradlew.bat :core:device-schema:compileKotlin
gradlew.bat :core:goform:compileDebugKotlin

# 阶段收尾时的全量出包（本项目默认 benchmark 档，debug 只在要抓日志时用）
gradlew.bat :core:assembleBenchmark
```

静态判据（阶段 0 的那三条 grep 见 §4 验收）另外加两条全局的：

- **UTF-8 守卫**：CI 会对所有跟踪的文本文件跑 `iconv -f UTF-8 -t UTF-8`
  （`.github/workflows/build.yml:57`）。新建 `.kt` 文件时注意编辑器编码 ——
  这个仓库有过「整文件 GBK 入库、Kotlin 编译器静默容忍」的事故。
- **版本源一致性**：不要碰 `version.json` / `gradle.properties` 的版本号，CI 会校验二者一致
  （`build.yml:111`）。本次改造不涉及发版。

### 14.2 第 2 层：单元测试（分钟级）

```
gradlew.bat :core:device-schema:test     # FieldNormalizerTest / ZteGoformProfileTest / ProfileContractTest
gradlew.bat :core:goform:test            # GoformSmsSendParamsTest 等
```

这两个套件就是本次改造的**语义基线**，尤其：

- `ZteGoformProfileTest`（撰写时 1325 行；2026-09-22 已 **1883 行 / 117 条 `@Test`**）冻结了
  F50 每个分组的字段名、别名回退顺序、
  结构解码器行为、以及全部写入项的 encode + validate。**阶段 0 搬命令表时它必须全绿** ——
  搬运不该改变任何映射结果。
- `ProfileContractTest` 是多 profile 契约测试，阶段 2 新增插件后它应当自动覆盖新插件
  （如果没有，说明它是按具体 profile 写的，要先改成遍历 `PluginRegistry.ALL`）。
- 新增测试的落点：`WriteSpec.retry` 的取值（每个 key 一条断言）、`SmsSpec` 的参数拼装
  （照 `GoformSmsSendParamsTest` 的写法，时间戳作为入参传固定值）。

注意：`core` 根模块与 `core/collector` / `core/controller` 的测试覆盖很薄，
**不要以「测试全绿」作为阶段 4 的验收依据** —— 那一层只能靠 §14.3/§14.4。

### 14.3 第 3 层：接口响应快照对比（这是本次改造最有力的校验手段）

原理：阶段 0~2 的目标是**行为不变**，所以「改造前后同一台设备的接口响应逐字节相同」
就是最强的证明。这个仓库刚好有两个专门为此设计的诊断出口：

- `GET /api/diagnose` → `device_profile` 块（`active` / `configured` / `normalization_enabled`，
  `HttpServer.kt:566-580`；**该文件在 `core/network`**，`core/network/.../core/server/HttpServer.kt`）
- `GET /api/diagnose?fields=1` → `field_coverage`：**逐个 FieldGroup 向设备查一次，
  输出「登记了几个 / 命中了几个 / 哪些没命中 / 命中的是哪个 source」，只含字段名不含值**
  （`HttpServer.kt:584`（`core/network`）→ `DataHub.kt:222` → `GoformSignalClient.kt:242`
  `diagnoseFieldCoverage()` → `GoformFieldMapper.coverageReport()`）

**做法（改代码之前先抓基线）**：

1. 在当前代码上出一个包、装上、启动后台服务，抓基线（存到 `.comate/`，那里被 gitignore）：
   - `GET /api/diagnose?fields=1`
   - **同时记录设备当时的状态**：`rat` / `network_type`（驻 NR 还是 LTE）、`ppp_status`、
     有没有 WiFi 客户端接入、定时重启开没开。**没有这些上下文，覆盖率差异无法解释**（见下）
   - 一组只读端点：`/api/system/uptime`、`/api/device/status`、`/api/network/info`、
     `/api/wifi/settings`、`/api/sms/list`（按实际路径以 `docs/UFI-AXIS-Core-API-Reference.md` 为准）
   - `POST /api/device/goform/query`（裸命令，`goform_command_enabled` 打开）抓一份设备原始响应 ——
     它**不过归一化**，因此可以用来区分「设备变了」还是「我们的映射变了」
2. 改完之后同样抓一遍，`diff` 两份。

**判据（2026-09-21 按真机基线重写）**：

原方案写的是「`field_coverage` 必须逐项一致」—— **那是错的**。实测基线（§16）有 18 项 missing，
而这些 missing 会随设备状态、固件版本、功能开关状态变化：驻 NR 时 `lte_*` 全 missing、
驻 LTE 时反过来 `nr_*` 全 missing；定时重启没开就没有 `restart_time`；
老固件没有 `BearerPreference`（`ZteGoformProfile.kt:189` 的注释早写了）。
按「逐项一致」判，每次都不一致 → 这条判据会被忽略，等于没有。

改成四条，从硬到软：

1. **`registered` 逐组完全不变**（基线：10 组共 85，见 §16）。
   阶段 0~1 只动 `cmdsFor()` / `writeSpec()`，**不动 `readSpecs()`** ——
   所以 `registered` 变了就是改错了地方。这条**不受设备状态影响**，是最硬的判据。
2. **`queried` 全为 true**（基线：10 组全 true）。某组变成 false = 该组 `cmdsFor()` 返回空，
   意味着命令表搬丢了一整组。
3. **`hit_source` 的映射关系不变**：对同时出现在两份快照里的 canonical，
   `hit_source` 的值必须逐字相同。这才是「映射没变」的直接证明，且与设备状态无关
   （状态只影响某个字段有没有值，不影响它命中的是哪个 source）。
   **2026-09-22 起这条判据真的可用了** —— §16 换成了从界面原文复制的基线；
   在那之前基准是 OCR 转录稿，逐字比对无从谈起。
4. **hit 集合只许增不许减**（基线合计 67）。少掉的每一项都必须能用第 1 步记录的设备状态解释；
   解释不了 → P0。
   注意两类**不受状态影响、任何时候都 missing** 的项（§16 已逐条归因）：
   `dhcpLease` / `lan_station_list` / `Language` 是「刻意不在覆盖率命令表里、由别的查询提供」，
   `nr_band_width` 是「这台固件不填」—— 它们不是回归。

另外两条与 coverage 无关但同样要比：

- 只读端点的响应**除时间戳/计数器类字段外**必须一致
- 裸命令的原始响应一致 → 说明设备侧没变，差异都是我们引入的

**看 coverage 报告时的一条纪律**：`hit_source` 里看起来像 bug 的映射，**先去读 profile 的注释**。
基线里 `monthly_rx_bytes ← monthly_tx_bytes`、`monthly_tx_bytes ← monthly_rx_bytes` 是上下行**交叉**的，
看着像接反 —— 实际是 `ZteGoformProfile.kt:255-266` 刻意为之：ZTE 固件的 `monthly_rx/tx` 是
「从模块看 PC」的视角，2026-09-01 真机实测 `monthly_tx_bytes`=7.0GB 才是下载，
所以在唯一的适配层一次性掰正。**把它「修正」回来就是把功能改坏。**
§16 末尾把这类「不许动的映射」单列了一节，改之前先读那一节。

**差异不为空且解释不清 → P0。**

**没有真机时**：这一层做不了，在对应任务后标 `[!] → 无真机，接口快照未验证`，
**不要把子项标成 `[x]`**。阶段 0 的写操作在没有真机验证的情况下不允许合进主线。


### 14.4 第 4 层：真机手工回归

写操作没有自动化替代品。阶段 0 的清单（**14 条**，见 §4 验收最后一条；
**2026-09-22 批 13 逐项数过 —— 原文写「12 条」与另一处口述的「13 条」都不对**）
每条要做三次观察：

1. 点之前：记下当前状态（从 app 或 `/api/diagnose` 读）
2. 点：观察返回码与提示文案（区分 200 / 400 `OUT_OF_RANGE` / 501 `NOT_SUPPORTED` / 503 `UNAVAILABLE`）
3. 点之后：确认设备状态真的变了（不要只看「提示成功」）

特别要试的**异常路径**（正常路径过了不代表没改坏）：

- **会话失效重试**：改造前后各做一次「把后台密码改错 → 触发一次写 → 改回」，
  确认 `RETRY_ON_SESSION_LOSS` 的项会自动重登重试、`NEVER` 的项不会
- **值域拒绝**：故意传非法值（频段 999、端口 70000、SSID 超长），确认回 400 而不是 500，
  且**请求没有发到设备**（看日志里有没有出站请求）
- **设备离线**：拔掉/关掉设备后触发一次写，确认回 503 且文案是「请稍后重试」而不是「不支持」
- **短信**：只发一条短信，然后**数一下手机收到几条**。这是 §11.1 重试语义的唯一可靠验证

### 14.5 阶段 3 额外：跨端兼容校验

- **旧客户端 + 新 core**：用改造前的 app（旧 APK 留一份在 `.comate/`）连改造后的 core，
  确认功能不受影响（新增字段被忽略）
- **新客户端 + 旧 core**：新 app 连旧 core（拿不到 `capabilities`）时，
  必须按「全支持」渲染而不是「全灰」—— 这个降级分支要专门点一遍
- **web 与 app 一致**：同一台设备上，两端对同一个不支持的功能表现一致（都灰、文案同义）

### 14.6 阶段验收登记

每个阶段做完，在该阶段的「验收」小节逐条打勾，并在 §9 追一行，格式：

```
- 2026-MM-DD 阶段 N 完成。第1层 ✓ / 第2层 ✓ / 第3层 ✓（或 [!] 无真机）/ 第4层 ✓。
  偏差：P0-1（已改计划）、P1-3（记入 §15）。
```

**四层里有任何一层没过就不能把阶段标 `[x]`**，标 `[~]` 并写明卡在哪一层。

---

## 15. 阶段待办池（范围外问题登记处）

动手过程中发现但**刻意不在当前阶段修**的东西记在这里。格式：
`P<级别>-<序号> 现象 / 事实 / 猜测 / 建议归属阶段`。

**P1-1 同一类数据在 SIGNAL 与 CELL_INFO 两组的命中结果不一致
（2026-09-22 按新基线重写：**原猜测「大小写」已被排除**，真因是 decoder 不同）**

- 事实（来自 §16 的 2026-09-22 基线）：CELL_INFO 组 `lte_rsrq ← lte_rsrq`、`lte_snr ← Lte_snr`、
  `Lte_pci ← Lte_pci`、`Lte_fcn ← Lte_fcn`、`Lte_bands ← Lte_bands` **全部命中**；
  而 SIGNAL 组的 `lte_snr` / `lte_pci` / `lte_arfcn` / `lte_band` **全部 missing**。
- 事实：两组查的是**同一批设备键** —— `cmdsFor(CELL_INFO)`（`ZteGoformProfile.kt:466-470`）与
  `cmdsFor(SIGNAL)`（`:477-483`）都含 `Lte_pci` / `Lte_snr` / `lte_rsrp` / `lte_rsrq`，
  且两组的 cmd 名**大小写完全相同**（`Lte_snr` 两处都是大写 L）。
- 事实：**差别在 decoder**。CELL_INFO 那几条 spec 没写 `decode`，走默认 `Decoders.AS_IS`
  （`:336-347`，`FieldSpec.kt:96`），**空串照样算命中**；SIGNAL 的同名量是
  `Decoders.NUMERIC` / `NON_BLANK`（`:386-392`），空值一律返回 null → `resolve()` 跳过 → missing
  （`FieldNormalizer.kt:100-108`）。
- 推论（由基线 + 代码逐字推出，未看到 dump）：设备在**驻 5G** 时仍然返回这些 LTE 键，
  但**值是空的**。所以 CELL_INFO 的「命中」是**空值命中**，SIGNAL 的 missing 才是实情。
- 结论：**这不是缺陷，也不需要补别名链** —— 原猜测「SIGNAL 查的是小写 `lte_snr`、
  设备只回 `Lte_snr`」已被 `d15c368` 的全仓核对与本基线双重排除（两份表现在都是 `Lte_snr`）。
  唯一值得记的是**诊断口径**：CELL_INFO 组的 hit 数在驻 5G 时偏高，
  比对基线时要看 `hit_source` 而不是 hit 数（已写进 §16「这份基线怎么用」第 2 条）。
- 归属：**降级为口径问题，不需要改代码**。若将来要让两组口径一致
  （给 CELL_INFO 那几条也加 `NON_BLANK`），那是**对外行为变更**（响应里会少几个空串字段），
  要与 web 的 `parseCellArray` / `mapNeighbors` 一起定，归阶段 2。

**P1-2 `nr_band_width` 在驻留 NR 时仍然 missing（2026-09-22 **已结案：固件不给，不改**）**

- 事实（§16 的 2026-09-22 基线）：SIGNAL 组驻 5G，`nr_*` 系列全部命中，唯独 `nr_band_width` missing。
- 事实：source 只有一个 `Nr_band_widths`，decoder 是 `Decoders.NUMERIC`
  （`ZteGoformProfile.kt:379`）—— 空串解析不出 Long 就返回 null，算缺失（`FieldSpec.kt:114-117`）。
- 事实（用户提供的真机 dump）：5G 小区信息里 `"Nr_band_widths": ""` 是**空串**。
  → 所以是「**固件不填这个值**」，不是别名没登记、也不是名字抄错。
  ⚠ 该 dump 不在本仓，**我未能逐字复核**；但仓内有两处独立佐证，结论不依赖 dump：
  - `core/contract/.../DeviceFields.kt:360`：「服务小区带宽 kHz。**设备经常不填**
    （`Nr_band_widths` / `Lte_bands_widths` 多为空），缺失即省略该 key。」
  - `ZteGoformProfileTest:1023-1034` 有专门用例「带宽为空时不输出 `band_width`」，
    夹具就是 `"Nr_band_widths" to ""`；`ServingCell.kt:27` 的注释也写着同一件事。
- **结论：结案，不改。** 名字与 profile 注释里的固件原文一致（`:355`：NR 侧是
  `Nr_fcn` / `Nr_band_widths`，LTE 侧多一个 s 是 `Lte_bands_widths`），
  「缺失即省略 key」是刻意语义。重抓基线时它仍然 missing 属**预期**，不要当回归。

**P0-1 `LTE_ALL_BANDS` / `NR_ALL_BANDS` 被跨模块引用，且全仓有三份同值拷贝
（批 3 停手项 → 2026-09-22 已裁决：推阶段 2）**

**裁决（用户）**：不在阶段 0 动，**推到阶段 2** 与 `NetworkController` 的设备知识一起清，
并适用 §3.4 的口径（那时若仍然收不动，允许各插件独立持有一份频段全集，不为了统一制造跨模块耦合）。
下面的事实 2026-09-22 复核**全部仍然成立**，一条没变，保留原文：

- 事实：`GoformNetworkClient.kt:28-31` 声明这两个 `const`，本文件内 `unlockAllBands()`（`:91-95`）用它们。
- 事实：`core/controller/.../NetworkController.kt:122` 与 `:127` 直接读
  `GoformNetworkClient.LTE_ALL_BANDS` / `NR_ALL_BANDS`（`unlockAll` 分支）。`core/controller`
  在阶段 0 范围外 —— 删 companion 就必须同时改它。
- 事实：`core/contract/.../Enums.kt:145-146` 有**第三份**同值拷贝
  （`"1,3,5,8,34,38,39,40,41"` / `"1,5,8,28,41,78"`），注释写着「来自 `GoformNetworkClient`」。
  全仓 grep 未发现任何地方引用 contract 这一份。
- 事实：语义**不是**计划书 §4 0.5 假设的「空值 = 解锁 = encode 下发全频段」。
  `BAND_LOCK_LTE/NR` 的 `encode` 只做 `value → lte_band_lock` / `nr_band_lock` 字段名映射，
  `validateBandList` 把空串当「解锁」直接放过（`ZteGoformProfile` 的 `validateBandList`）。
  「解锁 = 下发全频段串」这个决定在**调用方**（`unlockAllBands` / `NetworkController.lockBands`）。
- 猜测（未验证）：正确的收敛形态是在 profile 开一个「解锁时用的全频段值域」读侧入口
  （`BAND_LOCK_*` 的 encode 遇空串时自己填全集），然后 `unlockAllBands()` 改成传空串、
  `NetworkController` 的两处改成不传值。但那会把「空串 = 解锁」从「不发限制」变成
  「下发全频段」**在 profile 内部**生效，`lockBands` 里「未选 LTE → 空串清空该 RAT 限制」
  （`:124` 注释）这条语义会跟着变 —— 那是行为变更，不是搬运。
- 归属：**已裁决 → 阶段 2**（原有两个选项里取（b））。阶段 0 不擅自改 `core/controller`。
  裁决理由：「空串 = 不发限制」改成「空串 = encode 里下发全频段」是**对外语义变更**，
  不是搬运；而常量被 `core/controller` 直读，在阶段 0 改它等于把阶段 0 的影响面扩到 controller 层
  （§4「影响面」写的是不碰 controller）。`Enums.kt` 那份零引用拷贝一并留到阶段 2 处理 ——
  现在删它虽然安全，但 `core/contract` 是冻结区，删东西要按 §11.4 的纪律先标废弃。

**P0-2 三个 `setAccessPointInfo` 调用点无法用一份 `encode` 表达
（批 3 停手项 → 2026-09-22 **已解除**）**

**结论：阻塞已解除，三处已按方案 (b) 合并。** 经过：

1. **阻塞事实被证明是两个 bug，不是设备约束**。原判定「共用不了一份 encode」建立在
   「`setWifiSSID` 发明文 `Password`」这条事实上，而 base64 编码是单射的 ——
   一份统一编码的 encode 表达不出「明文」。批 4（`44dec16`）确认这就是 **bug**：
   设备侧要的是 base64，发明文等于让设备把「明文按 base64 解出来的字节」当新口令；
   同一批还修了 `setWifiConfig` 对已解码明文再 `base64Decode` 一次（失败返回空串 → **清空口令**）。
2. **按用户裁决取方案 (b)**：先单独一个 commit 修 bug（`44dec16`），再做合并 ——
   不把 bug 修复夹在搬运里，否则出问题时分不清是搬错还是修错（§13.1 第 1 条）。
3. **修完之后三条管线自动合并成一条**：客户端一律给**明文** `passphrase`，
   `WIFI_AP_CONFIG.encode` 做唯一一次 `base64(UTF-8)`（`090fcad`），
   三个入口只剩「放哪几个键」的差异（`118ed84` 的三个 `internal` 纯函数）。
4. **连带修掉的第三件事**：读侧 `base64Decode` 是 GBK、写侧是 UTF-8 —— bug 修完后
   「读回明文 → 编码写回」成了常规路径，这个不对称会改坏非 ASCII 口令，
   于是按用户裁决「现在就修（选项 b）」，`858a9c9` 把两边都改成「UTF-8 优先、GBK 回落」。

**下面这张表是批 3 当时的现状基线，保留作历史对照** —— 它描述的是 `44dec16` **之前**的行为，
`Password` 那一行的三种管线今天已经不存在了。表里 `current` = `getCurrentWifiConfig()` 的返回，
其中 `current["Password"]` 已经是 `base64Decode` 过的**明文**（行号也是当时的，早已漂移）：

| 设备侧参数 | `setWifiConfig(...)`（:301） | `setWifiSSID(ssid)`（:347） | `setWifiPassword(pass)`（:376） |
| --- | --- | --- | --- |
| 何时读回 current | **仅当** `authMode == null \|\| ssid == null` | 总是 | 总是 |
| `SSID` | `ssid?.trim() ?: current["SSID"]?.trim()`，两者都无则**不发** | `ssid.trim()`（必发） | `current["SSID"]`，无则**不发** |
| `AuthMode` | `authMode ?: current["AuthMode"]`，都无 → `"WPA2PSK"` | `current["AuthMode"] ?: "WPA2PSK"` | `current["AuthMode"] ?: "WPA2PSK"` |
| `EncrypType` | Auth=="OPEN" → `"NONE"`；否则 `encrypType ?: current["EncrypType"] ?: "CCMP"`；effectiveAuth 为 null 时 → `encrypType ?: "CCMP"` | `current["EncrypType"] ?: "CCMP"`（**即使 Auth=="OPEN" 也不改成 NONE**） | Auth=="OPEN" → `"NONE"`，否则 `current["EncrypType"] ?: "CCMP"` |
| `Password` | 仅当 `Auth != "OPEN" && Encryp != "NONE"`；值 = `base64Encode(passphrase ?: base64Decode(current["Password"]))` | 仅当 `Auth != "OPEN" && Encryp != "NONE"`；值 = `current["Password"]` —— **明文，未 base64 编码** | **无条件发**（连 OPEN 也发）；值 = `base64Encode(pass)` |
| `ApMaxStationNumber` | `maxStaNum?.toString()`，null 则**不发** | **从不发** | **从不发** |
| `ApBroadcastDisabled` | `(broadcastDisabled ?: current[...]?.toIntOrNull() ?: 0).toString()` | `current[...] ?: "0"` | `current[...] ?: "0"` |
| `ApIsolate` | `"0"` | `"0"` | `"0"` |
| `AccessPointIndex` | `"0"` | `"0"` | `"0"` |
| `ChipIndex` | `chipIndex ?: current["ChipIndex"] ?: "0"` | `current["ChipIndex"] ?: "0"` | `current["ChipIndex"] ?: "0"` |

- 事实：**大部分差异都能收敛**（缺省值、是否读回、要不要发某个 key，都可以由调用方在合并阶段
  决定，profile 只收合并后的完整参数集，这与 §11.3 的口径一致）。
- 事实：**`Password` 这一格不能**。`setWifiSSID` 发的是明文，另外两个发的是
  `base64Encode(...)`。base64 编码是单射的，不存在一个 `passphrase` 能让
  「统一 base64 编码」的 encode 输出明文。要么给 encode 加第二个 canonical 入参
  （形如 `passphrase_encoded`，调用方给已编码值、encode 原样透传），要么把 `setWifiSSID`
  也改成编码 —— 后者是**行为变更**，违反「发到设备上的请求逐字不变」。
- 事实（顺带查到的，两条既有缺陷，本批不修）：
  1. `setWifiSSID` 用明文当 `Password` 下发 —— 改 SSID 会把设备侧口令写成明文串。
  2. `setWifiConfig` 在 `passphrase == null` 时算的是
     `base64Encode(base64Decode(明文))`：`current["Password"]` 早已解码过，这里又解一次，
     得到的是垃圾字节再编码。也就是「只改 SSID/加密方式、不传密码」这条路径会把口令改坏。
  两条都是**既有行为**，按总纲不在本批动；但它们正是「无法用一份 encode 表达」的根因。
- 事实：`base64Encode`（`GoformClient.kt:865`）= `java.util.Base64.getEncoder()` +
  `Charsets.UTF_8`，无换行、标准字母表、带 `=` 填充；`base64Decode`（`:868`）=
  标准 decoder + **GBK** 解码（再做一次 UTF-8 往返）。**写 UTF-8 / 读 GBK 的不对称是既有事实**。
  搬进 device-schema 时用 `java.util.Base64.getEncoder().encodeToString(s.toByteArray(UTF_8))`
  即逐字等价（纯 JVM，device-schema 可用）；`base64Decode` 属读侧，不搬。
- 归属：**已裁决取（b）并已执行完**（`44dec16` → `090fcad` → `118ed84`）。三条候选路留在这里
  是为了记住为什么没选另两条：
  （a）encode 收两个入参（`passphrase` 明文 + `passphrase_encoded` 已编码值），逐字保住现状
  —— 会把一个 bug 固化成契约的一部分，且第二台设备要继承这个畸形入参；
  （b）承认 `setWifiSSID` 是 bug，单独一个 commit 修，然后三处共用一份 encode ← **选这条**；
  （c）本阶段只收敛 `setWifiConfig` + `setWifiPassword`，`setWifiSSID` 留到修 bug 那一批
  —— 会留下「三处里两处走 profile、一处还在硬编码」的中间态，下一个人看不出那是刻意的。
- **未做完的部分（新事实，接着往下看 P1-6 ~ P1-11）**：合并本身完成了，但这一轮顺带查清了
  6 条**没在这一轮修**的东西，各自单列一条，别再埋在 P0-2 里。

**P0-3 `CELL_INFO` 的命令表两份差一个大小写（`lte_snr` / `Lte_snr`）—— 0.4b 的前置条件
（批 11 登记 → 2026-09-22 批 12 **已结案：客户端改成设备真名 `Lte_snr`**，commit `d15c368`）**

**最终结论（与批 11 的裁决不冲突，务必连起来读）**：
用户批 11 裁决过「**不要自己为了大小写而改动**」「按照原来的就行了」。
批 12 改了，**不是推翻那条裁决，而是因为那条裁决的前提被真机 dump 否掉了**：
`lte_snr` 这个键**在设备上不存在** —— 4G 驻网时设备返回的是 `Lte_snr`（大写 L）。
所以这不是「为了大小写统一而改」，是**修一个查不到值的字段名**：
排障模式（归一化关掉走 fallback）下那一项此前永远查不到东西。
正常模式走 `cmdsFor(CELL_INFO)`，本来就发 `Lte_snr`，**对外行为不变**。

- **已修**：`GoformSignalClient.CELL_INFO_FALLBACK_CMDS` 末项 `"lte_snr"` → `"Lte_snr"`
  （`:373-378`，其余 9 项一个字符没动），改动理由整段写进了该常量的 KDoc（`:360-372`）。
- **基线确认生效**：§16 的 2026-09-22 基线里 `lte_snr ← Lte_snr` 命中。
- **守门测试**：`knownDivergentGroups` 已清空为 `emptySet<FieldGroup>()`
  （`GoformCommandTableGuardTest.kt:89`），注释写明「空集合**不等于**这条断言空转 ——
  `实测不一致的组必须恰好等于已登记的例外` 是双向相等，任一组新出现分叉都会立刻红」；
  另新增一条**字符级**断言「`CELL_INFO` 两侧末项都必须是设备真名 `Lte_snr`」（`:144-145`）。
  实测该类现有 **8** 条 `@Test`，commit 说明自报全绿（本轮没有重跑 Gradle，见 §13.3 最后一条）。
  ⚠ **2026-09-22 批 13 更新**：`knownDivergentGroups` 与那条双向相等断言**已随 `680fbae` 删除** ——
  fallback 删掉之后比对对象不存在了，守门测试转型成**命令表内容冻结**（现 **9** 条）。
  上面这段描述保留作历史，别照它去找代码。那条字符级断言**仍在**，且扩展成了
  「任何组都不许出现小写 `lte_snr`」的全组黑名单。
- **设备命名规律（记下来，下次别再当笔误）**：小区参数首字母大写
  （`Lte_pci` / `Lte_fcn` / `Lte_bands` / `Lte_cell_id` / `Lte_signal_strength` / `Lte_snr`，
  `Nr_*` 侧对称），信号质量指标全小写（`lte_rsrp` / `lte_rsrq` / `lte_rssi`，`nr_*` 同）。
  小写 `lte_snr` 是 core 自有 canonical（`DeviceFields.kt:242`），
  `ZteGoformProfile.kt:341-347` 的注释写明它**只作读侧容错、不许抄进任何 cmd 列表**。
- **「设备对 cmd 名是否大小写敏感」这个猜测不必再验**：两份表现在发的是同一个串，
  敏不敏感都不改变实际报文。原先写在这里的验证步骤（裸命令各发一次 `lte_snr` / `Lte_snr`）
  **保留在下面**，因为它对**排查别的字段**仍然是有效手段。
- **为什么一直没被发现**（原始记录保留）：`ZteGoformProfileTest` 里那条测试名叫
  「`cmdsFor CELL_INFO 与 getCellInfo 的查询一致`」，但它**只断言了 profile 自己的那份列表、
  从头到尾没碰过客户端** —— 测试名在撒谎。批 11 的 `GoformCommandTableGuardTest` 才是真的两侧比对。
- **对 0.4b 的影响**：前置条件解除，**fallback 可以删**（删了不改变任何实际发出的 cmd）。
  见 §4 的 0.4b。

**验证方法**（保留：将来排查「某个 cmd 名是不是设备真名」时照这个做）：
  1. `PUT /api/config` 把 `goform_command_enabled` 设为 `true`
     （`ConfigRoutes.kt:163`，默认 false；关着时 `POST /api/device/goform/query` 回 **403**，
     见 `DeviceRoutes.kt` 的 `rejectIfCommandDisabled`）；
  2. `POST /api/device/goform/query`，body `{"cmd":"<小写名>"}` 与 `{"cmd":"<大写名>"}` **各发一次**；
  3. 比较返回的**原始 JSON** 里有没有对应键、值是否相同；
  4. **验完把开关关回去** —— 那个通道不脱敏。

- 与 **P1-1** 的关系：P1-1 原先猜测 SIGNAL 组的 missing 也落在大小写上，
  **这条猜测已被排除**（两组 cmd 大小写本来就相同，真因是 decoder），见改写后的 P1-1。
- 归属：**已结案**。保留本条是为了留下「裁决 → 新证据 → 改口径」这条决策链，别删。

**P1-3 `setUsbMode` 已按用户裁决删除（已处理，保留决策痕迹）**

- 事实：2026-09-22 批 3 删除 `GoformDeviceClient.setUsbMode` +`SettingKey.USB_MODE` +
  其 `WriteSpec` + 2 条测试。删前 grep 全仓零生产引用（详见 §9 批 3）。
- 决策依据：本项目纪律是「没用了就彻底删」，不留只有 profile 认识、没有入口能触达的命令；
  要找回来 git 历史里有（`fbce991`）。
- 注意：`SettingKey.USB_PORT`（`setDebugMode` → USB 调试开关，`/api/device/debug`）是**另一件事**，保留。

**P1-4 `level must be 0-2` 与 `WIFI_POWER.validate` 是两份同义判据**

- 事实：`WifiRoutes.kt:83-87` 自己判 `level !in 0..2` 并回 400 `BAD_REQUEST`；
  `ZteGoformProfile` 的 `WIFI_POWER.validate` 也判 `0..2`。
- 事实：`setWifiPower` 返回 `Boolean`（`writer.write`），profile 的 `Rejected` 会被压成
  `false` → route 回 **500**。所以现在删掉 route 那一份 **会把 400 变成 500**，是对外行为变更。
- 2026-09-22 复核仍然成立：`WifiRoutes.kt:84-87` 判 `level !in 0..2` 回 400、`:90` 在
  `success == false` 时回 `InternalServerError`；`ZteGoformProfile` 的 `WIFI_POWER` 注释也写明
  「0~2 不是这里发明的，与 WifiRoutes 是同一份事实」。与 P1-8 是同一件事的两个入口。
- 归属：范围外。要清就得先把 `setWifiPower` 改成返回 `WriteOutcome`（签名变更），
  连同 route 一起改 —— 属阶段 2「route 只认 SettingKey + 三态」那一步。

**P1-5 二维码文件名模板需要新的 profile 读侧 API 面（0.5 的一部分，未做）**

- 事实：`GoformWifiClient.kt:69-72` 写死两个候选文件名
  `"${chip}_ssid${ssidIndex}_qrcode_wifikey"` 与兜底 `"chip1_ssid1_qrcode_wifikey"`，
  拼进 `/goform/goform_get_file_process/{name}`。这是设备侧事实。
- 事实：`DeviceProfile` 现在**没有**任何「文件端点 / 路径模板」的契约面 ——
  `readSpecs` / `cmdsFor` / `writeSpec` / `structuralDecoder` 都表达不了它。
- 归属：需要新增 SPI 面（形如 `fun qrCodeFileNames(chip, index): List<String>` 或更通用的
  「文件资源」契约），按 §13.4「不要自己发明 API」的纪律，**等裁决**；建议归阶段 2
  与插件聚合根一起设计，不在阶段 0 临时加一个方法。

#### 批 4~8 顺带查清、刻意不在阶段 0 修的（P1-6 ~ P1-19）

> 每条按「现象 / 事实 / 猜测 / 建议归属」写，**事实与猜测分开**。
> 行号是 2026-09-22 当日的位置；`GoformSmsClient` 正在接线中，那几条按符号名找。

**P1-6 `base64Decode` 解码失败返回空串 —— 「口令为空」与「解码失败」不可分**

- 现象：设备侧存了一个非法 base64 的 `Password` 时，「只改 SSID」仍然会把口令写成空串。
- 事实：`GoformClient.base64Decode`（`:884` → 纯函数 `base64DecodeOrEmpty` `:958`）
  非法输入时打一条 ERROR 后**返回空串**，不抛、不返回 null；KDoc（`:873-878`）写明这是
  **刻意保留**的语义，`/api/wifi/settings` 的读路径依赖它。
- 事实：`GoformWifiClient.getCurrentWifiConfig` 的 KDoc（`:162-164`）也记了这条 ——
  非法 base64 走的是「`config["Password"] = ""`」这条路，所以后面 `?:` 那种 fallback 基本不会命中。
- 事实：`mergeApSsidParams` 会把 `current["Password"]`（此时是空串）原样当 `passphrase` 发出去，
  于是设备口令被写成空。
- 猜测（未验证）：真机上出现非法 base64 的概率很低（口令都是我们自己写进去的），
  所以这条一直没暴露。
- 建议归属：**不属阶段 0**（要改的是「空 vs 失败」的表达，牵动 `getCurrentWifiConfig`、
  `/api/wifi/settings` 的返回、以及前端对「空口令」的渲染，三处一起改）。
  改法方向：读侧返回 `String?` 或带一个显式的 `decode_failed` 标记，而不是用空串兼表两义。

**P1-7 `setWifiConfig` 在 `authMode != null && ssid != null` 时不读回 current**

- 现象：走「设置页把 SSID 和认证方式都填了」这条路径时，下发的参数表里**永远没有**
  `Password` / `ChipIndex` / 广播位 —— 全靠设备自己保留原值。
- 事实：`GoformWifiClient.kt:335` 是
  `val current = if (authMode == null || ssid == null) getCurrentWifiConfig() else emptyMap()`；
  KDoc（`:318-321`）写明「两个都给了就不读，省一次查询」是**刻意保持原样**的。
- 事实：`setAccessPointInfo` 是**整表替换**命令，`mergeApConfigParams` 在 `current` 为空时
  `passphrase` / `chip_index` 都不会进 params（`broadcast_disabled` 会落到 `0`）。
- 猜测（未验证）：ZTE 固件对**没发**的键保留原值（否则这条路径早就把口令清空了，而它是设置页的主路径）——
  但「整表替换命令依赖设备保留未发键」这件事本身没有文档依据，换固件/换设备就未必成立。
- 建议归属：**不属阶段 0**。改成「一律读回」是**行为变更**（多一次查询、且会把设备当前值显式回写），
  要么阶段 2 连 route 的入参语义一起想清楚，要么等真机能验「不发某个键会怎样」。

**P1-8 三个 WiFi 写入口返回 `Boolean`，`Failed` 与 `Unavailable` 不可分**

- 现象：改 WiFi 配置失败时，前端分不出「设备拒绝了这个值」和「会话失效 / 连不上」。
- 事实：`setWifiConfig`（`:326`）/ `setWifiSSID`（`:360`）/ `setWifiPassword`（`:378`）
  都返回 `Boolean`（走 `writer.write`），而 `WIFI_ACL` 那条（`:294`）已经用 `writer.writeChecked`
  返回 `WriteOutcome`（三态）。
- 事实：`GoformSettingWriter` 内部本来就有三态，`write` 只是把它压成 `Boolean`。
- 建议归属：阶段 2「route 只认 `SettingKey` + 三态」那一步，与 P1-4 是同一件事的不同入口 ——
  改签名会连带改 route 的状态码（现在 false → 500，三态后应是 503 / 400 分开）。

**P1-9 残缺响应下 `encrypt_type` 的取值变了（已发生的行为差异，P1 记录）**

- 现象：设备只回了 `EncrypType` 但**没回** `AuthMode` 时，改前发 `CCMP`、改后沿用设备原值。
- 事实（改后）：`mergeApConfigParams`（`:444-451`）`auth = authMode ?: current["AuthMode"]` 为 null
  时不放 `auth_mode`，但 `encryp = encrypType ?: current["EncrypType"]` 仍会放 `encrypt_type`；
  profile 侧 `auth` 缺省成 `WPA2PSK`（非 OPEN），于是 `EncrypType` = 设备原值。
- 事实（改前）：已用 `git show 44dec16^:…/GoformWifiClient.kt` 逐字核对 ——
  `if (effectiveAuth != null) { … } else { params["AuthMode"] = "WPA2PSK";
  params["EncrypType"] = encrypType ?: "CCMP" }`，即 `AuthMode` 读不到时发 `CCMP`，
  **完全不看设备回的 `EncrypType`**。
- 判断：新行为更保守（沿用设备原值而不是硬塞 `CCMP`），且只在「设备返回残缺」这种本来就不该发生的
  响应下才有差异，所以**不回退**，只在这里留痕。
- 建议归属：无需单独处理；真机回归时如果遇到 WiFi 加密方式莫名变化，先回头看这条。

**P1-10 `broadcast_disabled` 改走 Int 通道，非数字字符串会落到 profile 缺省**

- 事实：三个合并函数都用 `current["ApBroadcastDisabled"]?.toIntOrNull()`
  （`:457-458` / `:492` / `:517`），非数字时该键不进 params；
  profile 的 encode 再 `?: "0"`（`ZteGoformProfile.kt:1078`）。
- 事实：改前是 `current[...] ?: "0"`（字符串直传 + 空则 `"0"`）——
  所以「非数字字符串」这一种输入的结果**与改前同档**（都成 `"0"`），只是路径不同。
- 猜测（未验证）：设备不会返回非数字的 `ApBroadcastDisabled`（真机一直是 `0` / `1`）。
- 建议归属：不用改。留这条是为了下次有人看到「Int 通道」时不要以为漏了字符串分支。

**P1-11 `WIFI_AP_CONFIG` 没有 `validate`（SSID / 口令原样进表单）**

- 事实：`ZteGoformProfile.kt:1059-1061` 的注释写明**刻意不加** ——
  SSID 与口令允许任意字符（含 `&` 和 `=`），body 由 `GoformCodec` 统一 URL 编码；
  在这里加值域校验会把「现在能设的 SSID」变成 `Rejected`。
- 事实：其它 17 项里有 validate 的（如 `WIFI_POWER` / `LAN_DHCP` / `TRAFFIC_LIMIT`）
  都是**本来就有** route 层同义校验的项，加 validate 不改变对外可接受的取值集合。
- 建议归属：阶段 2「route 只认 `SettingKey` + 三态」那一步。补校验是**对外行为变更**
  （原来能设的名字变成 400），要与 route 的入参校验一起定，并且要先想清楚「WPA2 口令 8~63 位」
  这类规则是设备事实还是标准 —— 前者进 profile，后者进 route。

**P1-12 批量 `msg_id`（`"5;6;7;"`）从未经真机验证**

- 事实：`ZteSmsSpec.joinIds` 的 KDoc（`:133-141`）写明：单条 `"5;"` 与现状逐字相同，
  **多条形态未经真机验证**，签名收 `List<String>` 是为将来批量操作留的。
- 事实：现在**没有**批量路径 —— `SmsController.kt:343` 与 `:397` 都是循环里一条一条删
  （`gc.deleteSms(id)`），`markSmsRead` 同样只收单个 id。
- 建议归属：第一次真做批量删除 / 批量标记之前，先抓一次包确认固件接受 `"5;6;7;"`。
  在那之前不要写「批量」入口 —— 参数拼得出来不等于设备认。

**P1-13 `order_by=order+by+id+desc` 里的 `+` 是空格，不许再 urlEncode**

- 事实：`ZteSmsSpec.listQuery`（`:93`）输出的值是字面量 `order+by+id+desc`；
  客户端拼信箱查询 URL 时是**直接字符串拼接**（`GoformSmsClient` 拼 URL 那处的注释也写明「故意不过 encoder」）。
- 事实：query string 里的 `+` 就是空格，urlEncode 会把它编成 `%2B` → 设备收到字面加号
  → **排序静默失效**（不报错，只是顺序不对，前端看起来像「新短信没排在最前」）。
- 建议归属：任何「参数 map → 逐个 urlEncode → 拼 URL」的统一构造器（阶段 1 传输层接口化时很可能会写）
  **必须带一条针对这个键的编码断言**。要改成真空格就得先真机验一次。

**P1-14 `SET_MSG_READ` 该不该补 `notCallback`**

- 事实：`DELETE_SMS` 发 `notCallback=true`，`SET_MSG_READ` **不发**
  （`ZteSmsSpec.markReadParams` `:117-122` 照抄 `GoformSmsClient.markSmsRead` 的现状）。
- 猜测（未验证）：`notCallback` 的作用是让固件不回调 web 前端刷新，补上应该是安全的、
  甚至更一致 —— 但**没有实测依据**，而「看着像漏了就顺手对齐」正是 §13.1 第 2 条禁止的。
- 建议归属：真机验一次（发一条、标已读、看前端与设备侧行为有无差异）再决定。不验就不动。

**P1-15 `verifySend` 的号码比对规则是设备事实，现在留在客户端**

- 事实：`GoformSmsClient.verifySend` 用 `number.takeLast(6)` 比对信箱行回填的号码，
  注释写明理由是「设备回填的号码可能带 `+86` 前缀，全等比对会漏」。
- 判断：这是**设备事实**（回填格式按固件而异），不是流程 —— 但它现在在客户端。
- 建议归属：第二台设备接入时若回填格式不同（如带国家码但格式不一样、或返回 E.164），
  就该进 `SmsSpec`，形如 `fun matchesNumber(rowNumber: String, sentTo: String): Boolean`。
  现在只有一台设备，加抽象无从验证，所以只登记。

**P1-16 `getSmsMeta` 读的是**读字段**，却硬编码在客户端**

- 事实：`GoformSmsClient.getSmsMeta` 直接取 `json["sms_nv_rev_total"]` 与 `json["sms_unread_num"]`
  两个设备侧字段名。
- 判断：按 profile 的分工，读字段应该走 `readSpecs()` + 一个 `FieldGroup`（如 `SMS_META`），
  由 `FieldNormalizer` 归一化并进 coverage 诊断 —— 现在它们完全不在覆盖率视野里。
- 建议归属：与 0.4（读命令表）同批做，或阶段 2。注意加 `FieldGroup` 会影响
  `coverageReport()` 的穷举与 §16 的 `registered` 基线（85 会变），所以**必须和重抓基线一起做**。

**P1-17 `mem_store=1` / `tags=10` 的语义无文档依据**

- 事实：`ZteSmsSpec.listQuery` 的 KDoc（`:73-74`）写明这两项是抄下来的固定值，
  「真机上一直这么发」，`getSmsList` 与 `getSmsMeta` 两处 URL 除分页外完全一致。
- 猜测（未验证）：`mem_store=1` 大概是「存储位 = 模块 NV」，`tags=10` 大概是 tag 过滤掩码 ——
  **都没有证据**，别把猜测写进代码注释。
- 建议归属：第二台设备接入时考证（同型号不同固件先对比一次）。在那之前照抄。

**P1-18 两份设备文本解码器判据已一致，但仍是两份实现**

- 事实：`GoformClient.decodeDeviceText`（`:944-948`）与
  `ZteGoformProfile.WIFI_PASSWORD_DECODER`（`:127` → `utf8OrGbk`，`:140` 起）
  现在判据相同（UTF-8 无损往返优先、否则 GBK），是 `858a9c9` + `090fcad` 两轮分别改的。
- 事实：**契约不同** —— 客户端那份解码彻底失败返回**空串**（`/api/wifi/settings` 依赖），
  profile 那份返回 **null（省略该 key）**（`:132` 的注释写明「保持现有语义」）。
- 建议归属：合并前先把「空串 vs null」这条差异定下来（它就是 P1-6 那件事的另一半）。
  合并的落点应该是 device-schema（纯 JVM，客户端可以依赖过去），但**不要在 P1-6 之前合** ——
  否则会把两种失败语义强行并成一种。

**P1-19 安装器仓有一份同样的解码实现（只登记，本轮不改）**

- 事实：`scripts/UFI-AXIS-Core-install-Android/goform/src/main/java/com/ufi_axis/installer/goform/GoformClient.kt:614-627`
  的 `base64Decode` 是 **`858a9c9` 之前**的形态：无条件 `String(bytes, GBK)`，
  外面套着那层「再做一次 UTF-8 往返」的死代码，失败同样返回空串。
- 事实（与主仓不同，别照抄结论）：这份文件里**没有** `base64Encode`、也没有 `setAccessPointInfo`
  —— 安装器不写 WiFi 配置。所以那里**不存在**「写 UTF-8 / 读 GBK」的不对称，
  只是读侧实现停留在旧版；非 ASCII 的设备文本会被 GBK 解错。
- 事实：那份代码**仍在维护** —— 本次改造期间用户正在并行修改整个
  `scripts/UFI-AXIS-Core-install-Android/**`（含这个文件），所以不能按「废弃代码」处理。
- 建议归属：**本轮只登记，不许动**（那是另一个仓的范围，且有人正在改）。
  等用户那一轮改完后单独确认：要不要把主仓 `decodeDeviceText` 的判据同步过去。

#### 批 10/11 顺带查清、刻意不在 0.4a 修的（P1-20 ~ P2-2）

**P1-20 `GoformWifiClient` 的两处 cmd 字面量没提成常量，守门测试里是手抄拷贝
（2026-09-22 批 13 **修订：0.4b 没做这一条，原因与新形态见下**）**

- 事实（原文，仍成立）：`getWifiSettings()`（12 项）与 `getWifiModuleInfo()`（2 个容器命令）的 cmd
  仍是方法体里的字面量。
- **2026-09-22 批 13 实测的新状态**：
  - 0.4b **没有**把这两处提成 companion 常量 —— `GoformSignalClient` 那 6 组是**删掉**了
    （命令表收进 profile），不是提成常量；WiFi 这两处是**刻意保留**的分批查询，
    本轮只给它们各加了一行注释（`GoformWifiClient.kt:39-42` / `:209-211`）。
  - 守门测试里那份**手抄拷贝已经不存在了**：`wifiSettingsClientCmds` 随转型删除，
    现在是 `frozenCmdTables[WIFI_SETTINGS]` 的 14 项冻结值（前 12 项 = `getWifiSettings()`，
    后 2 项 = `getWifiModuleInfo()`），并由「冻结表覆盖全部 `FieldGroup`」与
    「`cmdsFor` 逐组逐字」两条钉住。
- **所以这一条的形态变了**：不再是「三处事实（客户端 / profile / 测试）」，而是**两处** ——
  客户端那两个字面量，与 profile 的 `cmdsFor(WIFI_SETTINGS)`。
  维系「**两次请求的并集 == `cmdsFor(WIFI_SETTINGS)`**」这条不变量的**只有注释**
  （两个方法上各写了一句），**没有任何测试能断言它** ——
  测试冻结的是 profile 那一份，客户端改了 12 项里的任何一项都不会红。
- **为什么 0.4b 不顺手收掉**：收掉就得让客户端走 `fields.cmds(WIFI_SETTINGS)`，
  那会把**两次独立请求合成一次** —— 改变设备侧请求形状，本仓有 `station_list`
  因合并查询被设备吞掉的先例，无真机不许赌。
- **彻底解决的形态（新结论）**：要让这条不变量可断言，得给 `DeviceProfile` 加一个
  「**分批读**」的表达，形状同 `fullStatusCmds()`（外层列表 = 批次边界），
  让 `WIFI_SETTINGS` 的 12 + 2 由 profile 表达成两批，客户端逐批发。
  这是**新开一个 SPI 面**，按「不要自己发明 API」的纪律不在阶段 0 临时加。
- 归属：**阶段 2**（与插件聚合根一起设计那个分批读 API 面）。

**P1-21 `SIGNAL` / `CONNECTION` / `WIFI_CLIENTS` 三组的客户端 cmd 仍是裸字面量，未进守门比对
（2026-09-22 批 13 **已结案**：三组已纳入命令表冻结，`680fbae`）**

- **结案依据**：`GoformCommandTableGuardTest` 转型后的 `frozenCmdTables` **覆盖全部 10 个
  `FieldGroup`**（含这三组），并由「冻结表必须覆盖全部 `FieldGroup`」那条断言**双向**钉住键集 ——
  以后新增一个组却忘了登记期望值会直接红。`SIGNAL` 的 16 项现在既在冻结表里、
  也是 `getSignalInfo()` 实际发出的那一份（客户端已改走 `fields.cmds(SIGNAL)`），
  「今天的一致是巧合级别的保障」这个问题不存在了。
- 附带收益：新增的「**任何组都不许出现小写 `lte_snr`**」黑名单断言覆盖 10 组，
  拦的是「整组逐字冻结拦不住的那一类」—— 期望值与实现**同时**被抄成同一个错名。
- 下面是原文事实，保留作历史：

- 事实：`getSignalInfo()`（`:59-65`，16 项）、`getConnectionInfo()`（复用 `getSignalInfo` 那一次查询，
  自己没有 cmd 列表）、`getStationList()`（`:252` `client.querySingle("station_list")`）
  都不走 `fields.cmds(group, fallback)`，因此**不在 `FALLBACK_CMDS` 里、也不在守门测试的比对范围内**。
- 事实：`SIGNAL` 那 16 项经**人工**逐字核对与 `cmdsFor(SIGNAL)`（`ZteGoformProfile.kt:471-477`）
  当前一致（含 `Lte_snr` / `Lte_pci` / `Lte_ca_status` 的大小写），
  但**没有任何测试断言这件事** —— 今天的一致是巧合级别的保障。
- 建议归属：0.4b。把这三组一起纳入守门比对，比「先切 `cmds()` 再说」安全得多。

**P1-22 `getFullStatus()` 的三批查询在守门比对之外
（2026-09-22 批 13 **已结案**：收进 `DeviceProfile.fullStatusCmds()`，`680fbae`）**

- **结案依据**：三批 96 项已搬进 `ZteGoformProfile.FULL_STATUS_CMD_BATCHES`
  （由 `fullStatusCmds()` 暴露），守门测试第 ④ 条冻结了**批次数（3）、逐批数量（30/29/37）、
  合计（96）与逐字内容**；第 ⑥⑦ 条另外钉住「它也来自 `commandProfile`」。
- **建议里「或新 `FieldGroup`」那半句被否了**（理由见 §4 的 0.4b 与 `DeviceProfile.fullStatusCmds`
  的 KDoc）：加枚举值会让 `field_coverage` 多一个块、`registered` 组数与合计必变，
  而 0.4b 的验收恰恰是拿 §16 的基线比对。**正因为没加，所以 §16 不需要追加第二份基线。**
- 顺手把两处过期注释更正了（源码 `30/28/30+`、KDoc「75+ 字段」都不准）。
- 下面是原文事实，保留作历史：

- 事实：`:168` / `:180` / `:193` 三批共 **96** 个字段名（30 / 29 / 37），
  既没有 `FieldGroup`、也没有 `cmdsFor()` 对应物，自然不在任何比对里。
- 事实：源码注释的数字不准（KDoc「75+ 字段」、批内 `(30 字段)` / `(28 字段)` / `(30+ 字段)`，
  只有第一批对得上）—— 别照抄注释。
- 建议归属：与 0.4b 的 `fullStatusCmds()` / 新 `FieldGroup` 一起做；
  注意加 `FieldGroup` 会改 §16 的 `registered` 基线（85 会变），**必须和重抓基线一起做**。

**P2-1 `cmdsFor()` 里可能还有同类误登记 —— 本次只查了 `WIFI_SETTINGS`
（2026-09-22 批 13 **修订：仍未做，把范围说准**）**

> **0.4b 没有完成这一条，不要当它已经清了。** 本轮做的是「**fallback 与 `cmdsFor` 的一致性**」
> 逐组核对（六组按 token 序列逐字含顺序比对，全部一致）—— 那证明的是
> **「两份表没有分叉」**，**不是**「表里每个 cmd 在设备上真的是可发的 cmd 名」。
> 这两件事不同：两份表**同时**把一个响应键当成 cmd，一致性比对照样全绿。
> 已知的两例都已修（`WiFiModuleSwitch` → `d435fa4`、`lte_snr` → `d15c368`），
> **其余需要真机 dump 才能定性**，与 P1-24 是同一批工作。
> 唯一的新增保护是守门测试第 ⑤ 条（小写 `lte_snr` 全组黑名单），那只挡住了已知的那一个名字。

- 下面是原文事实，全部仍然成立：

- 事实：批 10 修的是「把只作为**响应键**存在的名字抄进命令表」这一类错误。
  其它分组**没有逐项核过**是否也混进了同类名字。
- 事实：风险最高的是**别名链首项恰好是驼峰 ZTE 原名**的那些 canonical ——
  如 SIGNAL 的 `Nr_*` 系列、CELL_INFO 的 `Lte_*` 系列：它们与 `WiFiModuleSwitch` 是同一个形状
  （canonical 名 = 设备响应键 = 看起来像 cmd 名）。
- 事实（部分已被覆盖）：SIGNAL 组有一条 `容器字段清单不含已是顶层 cmd 的名字`
  （`ZteGoformProfileTest:141-145`）部分覆盖了这件事，**其它组没有**。
- 事实（**为什么现有守门测试拦不住这一类**，重要）：`每个字段都能被 cmdsFor 查到`
  （`ZteGoformProfileTest:114-138`）看起来是通用守门 —— 它断言每个登记字段至少有一个 source
  落在 `cmdsFor(group)` 里。但 `WiFiModuleSwitch` 这次**恰好既是别名链首项又是那个假 cmd**，
  于是 `spec.sources.none { it in cmds }` 为 false、测试**照样绿**。
  也就是说：**只要误登记的那个串本身就是某个 source 名，这条断言就是系统性失明的** ——
  而「别名链首项是驼峰 ZTE 原名」的每一组都满足这个条件。
  批 10 新增的两条断言是逐字冻结 + 黑名单，才真正拦住了它。

- 判断：这一类错误的后果与 `WIFI_SETTINGS` 那次相同 —— **只影响覆盖率诊断**
  （白发一次无效查询 + 报告里那一项永远 missing），线上活路径走客户端硬编码列表，
  所以是 P2 不是 P0。
  ⚠ **2026-09-22 批 13 起这句话要改口径**：0.4b 之后 `cmdsFor()` **就是线上活路径的命令表**
  （`getSignalInfo` / `getDeviceIdentity` / `getCellInfo` / `getLanSettings` /
  `queryDeviceSettings` / `getBandLockStatus` / `getDataUsage` 都走它）。
  所以同类误登记的后果**不再只限于诊断** —— 误登记的那一项会让**业务查询**白发一次无效 cmd、
  对应字段悄悄没值。级别仍留 P2 是因为**没有证据表明还存在第二例**，
  但一旦发现就按 P0 处理，不要再套用「只影响诊断」这句旧结论。
- 建议归属：**0.4b 未做**（0.4b 只核了两份表的一致性）。真正要做的是**逐个 cmd 拿真机 dump
  定性**「这是可发的 cmd 名，不是响应键」—— 那需要真机，与 **P1-24** 是同一批工作
  （做法见 P1-24 的「怎么才能验完」）。在拿到 dump 之前**不要单独去「顺手核对」**——§13.1 第 2 条。

**P2-2 API Reference 里「已不再输出」容易被误读成「这两个名字全仓都不该出现」**

- 事实：`docs/UFI-AXIS-Core-API-Reference.md:129`（表格行「旧字段（已不再输出）」）与
  **`:3667`**（「**旧字段 `wifi_enable` / `wifi_onoff_state` 已不再输出**，两者都归一到
  `WiFiModuleSwitch`」）说的是**响应输出**。
- 事实：这与它们作为**查询 cmd** 必须发出去**不矛盾** —— `cmdsFor(WIFI_SETTINGS)` 与
  客户端 `getWifiSettings()` 里都必须有这两个名字，否则 `MODULE_SWITCH` 一个 source 都命中不了
  （批 10 修的正是这件事）。
- 判断：两处用词太近，容易被下一个人读成「这两个名字全仓都不该出现」，然后把命令表里的
  `wifi_enable` / `wifi_onoff_state` 删掉 —— 那会把批 10 的修复直接撤回去。
- 建议：在那一行加半句区分「**不再输出 ≠ 不再查询**」。
  **本轮只登记，不改那个文件**（它不在本轮的独占文件范围内）。

#### 批 12 新登记（P1-23 ~ P1-24）

**P1-23 web「复制 JSON」按钮点了没反应（用户实测，只能手动选中复制）**

- 现象（**用户实测**）：「设置 › 关于 › 诊断信息 › 字段覆盖率」弹窗里的「复制 JSON」按钮无效，
  §16 这份基线是**手动选中复制**出来的。
- **推断的根因（未实测，标明是推断）**：`copyToClipboard` 的 `execCommand` 回退把临时 textarea
  插到 `document.body`，而覆盖率结果显示在 `n-modal` 里。naive-ui 的 modal 默认 `trap-focus`，
  焦点陷阱在捕获阶段监听 `document` 的 `focus`，只要拿焦点的元素不在弹窗子树里就立刻把焦点抢回去 ——
  弹窗内容是 teleport 到 body 的**兄弟**节点，所以 body 上那个 textarea 每次都被判成「外面」，
  `select()` 的选区在同一拍内被夺走，`execCommand('copy')` 抄到空。
- 事实：局域网 HTTP（`http://<局域网IP>:8088`）下 `navigator.clipboard` 是 **undefined**，
  所以**必然**走回退路径 —— 这解释了为什么在 https/localhost 下试不出来。
- 事实（**另一个代理正在修，我读到的是进行中的状态，别按这个下结论**）：
  `web/src/composables/utils.ts` 的 `copyToClipboard` 已加第二个参数
  `container?: HTMLElement | null`（缺省仍是 `document.body`），KDoc 把上面那条焦点陷阱的机理写全了；
  `AboutPanel.vue` 的弹窗里已挂了 `coverageBodyRef`。
  我核对时**调用点还没把 `container` 传进去**（`copyCoverageJson()` 仍是 `copyToClipboard(json)`）。
- 双保险方向（他那轮在做）：① 修焦点处理（textarea 插到弹窗子树里）；
  ② 弹窗里加一个只读文本框保底 —— 回退再失败也能手动全选。
- 归属：**web，不在本轮范围**。本条只登记，等他那轮落地后确认一次
  （判据：局域网 HTTP 下从弹窗点一次「复制 JSON」，粘贴出来与屏幕上的 JSON 逐字相同）。

**P1-24 约 30 个「清单未收录」的字段名无法判断对错，待真机多态 dump**

- 事实：批 12 全仓核对字段名时，凡在真机 dump 里**出现过**的名字都逐字核过（只有 `lte_snr`
  一处错，已修）；但真机 dump 里**没出现过**的名字（约 **30** 个，数量以 commit `d15c368`
  的说明为准 —— 该 dump 不在本仓，**我未能逐一点数复核**）**无从判断**：
  它们可能名字就是对的（只是当时设备不返回），也可能像 `lte_snr` 那样从来查不到值。
- 事实：这批字段一律**未动** —— 按 §13.1 第 2 条，没有证据就不改。
- 事实：典型分布是「只在某个功能开着 / 某个制式下才返回」的那些：
  `restart_time`（定时重启开着才有）、LTE 侧的 `Lte_*`（驻 4G 才有）、
  `Nr_band_widths`（这台固件从不给，已由 P1-2 结案）、`UpgMode` / `sleep_sysIdleTimeToSleep` 等。
- **怎么才能验完**（这就是获取判据的方法）：在**不同设备状态下多抓几份 dump**，
  每份都记状态，然后与命令表逐字对：
  1. **开启定时重启** → 再抓一份，看 `restart_time` 有没有值（验 DEVICE_SETTINGS 那几项）；
  2. **锁一次频段 / 切到 4G 驻网** → 抓一份，验 LTE 侧的 `Lte_*` 与 SIGNAL 组的 7 个 `lte_*`；
  3. **开启 FOTA 自动更新 / WiFi 休眠** → 验 `UpgMode` / `sleep_sysIdleTimeToSleep`；
  4. 每份都用裸命令 `POST /api/device/goform/query`（`goform_command_enabled` 打开，**验完关回去**）
     抓**原始响应**——它不过归一化，才能区分「设备没这个键」与「我们的映射没命中」。
- **用户裁决（2026-09-22，原话口径）**：「**不允许漏修埋坑**」——
  所以这批不是「算了不管」，是**登记为待验**，拿到新状态的 dump 就要接着核。
- 归属：真机可用时逐条验；与 0.4b 的「两份表合成一份」那一步天然是同一批工作（见 P2-1）。

#### 批 13 新登记（P1-25）

**P1-25 `getFullStatus()` 的 96 项里相当一部分没有登记 canonical，`readSpecs()` 覆盖不到**

- 事实：0.4b 把三批 96 个字段名收进了 `ZteGoformProfile.FULL_STATUS_CMD_BATCHES`
  （`fullStatusCmds()` 暴露），但这份 dump 的调用点是 `getFullStatus()` / `getFullStatusMasked()`，
  **不过归一化** —— 它的用途就是「看设备后台到底有什么字段」（`GET /api/device/goform`，§9.2）。
- 事实：所以这 96 项里**相当一部分没有登记 canonical**（`readSpecs()` 里没有对应 `FieldSpec`），
  典型如 `battery_*` / `sms_*` / `wifi_chip2_*` / `m_SSID2` / `station_ip_addr` / `loginfo` 等。
  这是**刻意的**，`fullStatusCmds` 的 KDoc 已写明「不要拿 `readSpecs()` 去对它」。
  ⚠ **我没有逐项点数「没登记的到底有多少个」** —— 那要把 96 项与 `readSpecs()` 的全部 canonical
  与别名链交叉比一遍，属于新的核对工作量，本轮未做。**结论只到「相当一部分」，别写具体数字。**
- 事实：这批字段的值**不过 allowlist**，脱敏靠 `FieldNormalizer.maskDump`
  （登记过的按 `Sensitivity`、没登记的按字段名兜底），所以**今天不存在泄露口**。
- 判断：这一条不是缺陷，是一个**将来会挡路的决定点** —— 若将来想让诊断 dump 也走 allowlist
  （只输出登记过的字段），就**必须先决定这批未登记字段的去向**：
  ① 逐个补 `FieldSpec`（会改 §16 的 `registered` 基线，得先抓基线）；
  ② 承认 dump 是「不过 allowlist 的例外出口」，把这条写成显式契约（现状，只是没写成契约）；
  ③ 给 dump 单独一张「允许原样透出」的白名单（第三份事实，不推荐）。
- 归属：**阶段 2 或更晚**，与「诊断出口要不要统一走 allowlist」一起定。
  在那之前**不要顺手给它们补 `FieldSpec`** —— 那会动 `registered`，
  而 `registered` 逐组不变是 §14.3 判据 1 的基石。



---

## 16. 真机基线（2026-09-22，逐字可比对）

**这是可逐字比对的真基线。** 来源：真机 web 界面「**设置 › 关于 › 诊断信息 › 字段覆盖率**」入口
（`GET /api/diagnose?fields=1` 的 `field_coverage`），**2026-09-22** 抓取，
由用户从界面**手动复制**（当时「复制 JSON」按钮点了没反应，见 §15 的 **P1-23**）。

> **2026-09-21 那份 OCR 转录版本已废弃，本节整体取代它** —— 不保留两份数字互相打架。
> 废弃理由不是数字错：旧版的组数、逐组 `registered` 与合计 85 与本节一致，
> 差异只在 hit（66 → 67，`d435fa4` 的预期效果）。真正的问题是旧版的 `hit_source`
> 是 OCR 转录的、**不可逐字比对**，而 §14.3 的判据 3 恰恰要求 `hit_source` 逐字不变 ——
> 拿一份转录稿当基准，等于这条判据从来没法用。
> 本节的 `hit_source` 是从界面原文复制的，判据 3 从现在起可用。

数字自洽性（本轮逐组相加核过）：`registered` 合计 **85**、`hit` 合计 **67**、`missing` 合计 **18**，
10 个组 `queried` 全为 true。逐组 `registered` 与 `ZteGoformProfile.readSpecs()` 的登记数逐组相符
（DEVICE_SETTINGS 13 / LAN 10 / WIFI_SETTINGS 8 / WIFI_CLIENTS 2 / BAND_STATUS 2 / CELL_INFO 8 /
IDENTITY 7 / TRAFFIC_LIMIT 10 / SIGNAL 22 / CONNECTION 3，`ZteGoformProfile.kt:172-398`）。

抓取时的设备状态（重要，覆盖率差异只能靠它解释）：
- `profile_id` = `zte-goform`（ZTE goform（F50 等）），`normalization_enabled` = true
- 驻网制式：**5G**（`network_type: "5G"`，`nr_*` 系列全命中）
- 老固件：有 `net_select`、无 `BearerPreference`（见 `ZteGoformProfile.kt:189` 的注释）
- 定时重启：**未开启**
- WiFi：有 **1 个**客户端接入（`wifi_access_sta_num: "1"`）

### 基线全文（逐字照录，不要改写）

```
profile：zte-goform（ZTE goform（F50 等））
合计命中 67 / 登记 85

DEVICE_SETTINGS  命中 8 / 登记 13
  missing: restart_time、sleep_sysIdleTimeToSleep、UpgMode、BearerPreference、dial_roam_setting_option
  hit_source:
    indicator_light_switch ← indicator_light_switch
    performance_mode ← performance_mode
    samba_switch ← samba_switch
    usb_port_switch ← usb_port_switch
    restart_schedule_switch ← restart_schedule_switch
    net_select ← net_select
    connection_mode ← dial_mode
    roam_setting_option ← roam_setting_option

LAN_SETTINGS  命中 9 / 登记 10
  missing: dhcpLease
  hit_source:
    lan_ipaddr ← lan_ipaddr / lan_netmask ← lan_netmask / mac_address ← mac_address
    dhcpEnabled ← dhcpEnabled / dhcpStart ← dhcpStart / dhcpEnd ← dhcpEnd
    dhcpLease_hour ← dhcpLease_hour / mtu ← mtu / tcp_mss ← tcp_mss

WIFI_SETTINGS  命中 7 / 登记 8
  missing: wifi_chip1_ssid1_encryp_type
  hit_source:
    wifi_chip ← ChipIndex
    wifi_chip1_ssid1_ssid ← SSID
    wifi_chip1_ssid1_passphrase ← Password
    wifi_chip1_ssid1_auth_mode ← AuthMode
    wifi_chip1_ssid1_broadcast_ssid ← ApBroadcastDisabled
    wifi_chip1_ssid1_max_sta_num ← ApMaxStationNumber
    WiFiModuleSwitch ← WiFiModuleSwitch

WIFI_CLIENTS  命中 1 / 登记 2
  missing: lan_station_list
  hit_source: station_list ← station_list

BAND_STATUS  命中 2 / 登记 2
  hit_source: lte_band_lock ← lte_band_lock / nr_band_lock ← nr_band_lock

CELL_INFO  命中 7 / 登记 8
  missing: lte_rsrp
  hit_source:
    neighbor_cell_info ← neighbor_cell_info / locked_cell_info ← locked_cell_info
    Lte_pci ← Lte_pci / Lte_fcn ← Lte_fcn / Lte_bands ← Lte_bands
    lte_rsrq ← lte_rsrq / lte_snr ← Lte_snr

IDENTITY  命中 6 / 登记 7
  missing: Language
  hit_source:
    msisdn ← msisdn / imei ← imei / imsi ← imsi / iccid ← iccid
    cr_version ← cr_version / wa_inner_version ← wa_inner_version

TRAFFIC_LIMIT  命中 10 / 登记 10
  hit_source:
    enabled ← data_volume_limit_switch
    limit_value ← limit_value
    limit_unit_display ← limit_unit_display
    limit_bytes ← limit_bytes
    alert_percent ← data_volume_alert_percent
    auto_clear ← wan_auto_clear_flow_data_switch
    clear_date ← traffic_clear_date
    monthly_rx_bytes ← monthly_tx_bytes
    monthly_tx_bytes ← monthly_rx_bytes
    monthly_time ← monthly_time

SIGNAL  命中 14 / 登记 22
  missing: nr_band_width、lte_arfcn、lte_band、lte_band_width、lte_signal_strength、lte_snr、lte_pci、lte_cell_id
  hit_source:
    rsrp ← nr_rsrp / sinr ← Nr_snr / rsrq ← nr_rsrq / rssi ← Nr_signal_strength
    rat ← network_type / cell_id ← Nr_cell_id / operator ← network_provider
    nr_arfcn ← Nr_fcn / nr_band ← Nr_bands / nr_signal_strength ← Nr_signal_strength
    nr_snr ← Nr_snr / nr_pci ← Nr_pci / nr_cell_id ← Nr_cell_id
    lte_ca_status ← Lte_ca_status

CONNECTION  命中 3 / 登记 3
  hit_source: ppp_status ← ppp_status / network_type ← network_type / network_provider ← network_provider
```

### 18 个 missing 全部归因 —— **无一是适配缺陷**

判定口径先说清：覆盖率的「命中」= `FieldNormalizer.resolve()` 在别名链上找到一个
**存在、非 JSON null、且 decode 不返回 null** 的 source（`FieldNormalizer.kt:100-108`）。
所以 missing 有三种成因，都不是「映射写错了」：
① 设备根本没返回这个键；② 设备返回了**空串**而该字段的 decoder 把空值当缺失
（`Decoders.NON_BLANK` / `NUMERIC`，`FieldSpec.kt:99-117`）；
③ 该字段**刻意不在覆盖率的命令表里**（由别的查询提供，线上活路径照常有值）。

**DEVICE_SETTINGS 的 5 项 —— 全是「查了，设备没给」（成因 ①/②）。**
这 5 个 cmd **确实都在 `cmdsFor(DEVICE_SETTINGS)` 里**（`ZteGoformProfile.kt:404-413`：
`restart_time` `:409`、`sleep_sysIdleTimeToSleep` `:410`、`BearerPreference` `:411`、
`UpgMode` `:412`、`dial_roam_setting_option` `:406`），客户端 fallback 逐字相同
（`GoformSignalClient.DEVICE_SETTINGS_FALLBACK_CMDS`，`:387-396`）——
**命令表没问题，是设备没返回键或返回了空值**（`BOOL_01` 对空串与不认识的值同样返回 null，
`FieldSpec.kt:125-133`）。

- `restart_time`：定时重启未开启（`restart_schedule_switch` 命中，说明这条查询本身是通的）
- `sleep_sysIdleTimeToSleep`：这台固件不填（decoder 是 `NON_BLANK`，`:185`，空串也算缺失）
- `UpgMode`：同上（`:188`，`BOOL_01`）
- `BearerPreference`：**老固件**。`ZteGoformProfile.kt:189` 的注释写明「新固件填
  `BearerPreference`，老固件只有 `net_select`」，而基线里 `net_select ← net_select` **命中了** ——
  两条都登记就是为了这件事（`:191-194`），一台设备命中其中一条即正常
- `dial_roam_setting_option`：这台固件只填 `roam_setting_option`（它命中了，`:198-201` 两个
  canonical 各自独立登记）

**`dhcpLease` —— 成因 ③，不是设备缺字段。**
`cmdsFor(LAN_SETTINGS)`（`:415-418`）与客户端 fallback（`GoformSignalClient.kt:381-384`）
都**只发 `dhcpLease_hour`**，没有 `dhcpLease`。这台固件给的就是小时制那份（`dhcpLease_hour` 命中），
秒级值由客户端 ×3600（`ZteGoformProfile.kt:220-223` 的注释写明「不在归一化里换算」）。
`ZteGoformProfileTest` 的「每个字段都能被 `cmdsFor` 查到」把它列进 `knownGaps` 白名单并写明理由
（`:109`、`:116`）—— 也就是说**它在覆盖率报告里必然 missing，这是登记态，不是缺陷**。

**`wifi_chip1_ssid1_encryp_type` —— 成因 ②：设备返回空串。**
真机 `queryAccessPointInfo` 的两个 AP 都是 `"EncrypType": ""`（**依据来自用户提供的真机 dump，
该 dump 不在本仓，我未能逐字复核**），别名链是 `"EncrypType", "wifi_chip1_ssid1_encryp_type"`
+ `Decoders.NON_BLANK`（`ZteGoformProfile.kt:315-316`），空串 → 返回 null → 两个 source 都不算命中。
同组另外 6 项命中的 source 全是容器里提上来的驼峰名，说明容器本身解析正常。

**`lan_station_list` —— 成因 ③。**
`cmdsFor(WIFI_CLIENTS)` 只有 `station_list`（`:461`，且它是唯一的 `soloCmds` 项，`:486-487`），
`lan_station_list` 由 DataHub 合并的另一份响应提供 —— 同样在测试的 `knownGaps` 白名单里
（`ZteGoformProfileTest:111`、`:118`）。抓取时确有 1 个客户端接入，`station_list` 命中。

**CELL_INFO 的 `lte_rsrp` + SIGNAL 的 7 个 `lte_*` —— 成因 ①/②：驻 5G。**
profile 注释写明这是设计：「5G(NR) 专属：只在 NR 有值时出现，缺失即表示当前不在 5G」
（`:376`），LTE 侧「语义与上面完全对称」（`:385`）。抓取时 `network_type: "5G"`、
`nr_*` 全命中，所以 LTE 侧缺失**正是预期**。

> ⚠ 但这两组的**表现不对称，原因不是大小写**（这条推翻了 §15 P1-1 的原猜测，详见那一条）：
> CELL_INFO 的 `Lte_pci` / `Lte_fcn` / `Lte_bands` / `lte_rsrq` / `lte_snr` **命中了**，
> 而 SIGNAL 的 `lte_pci` / `lte_arfcn` / `lte_band` / `lte_snr` **missing**，
> 两组查的是同一批设备键（`cmdsFor(CELL_INFO)` `:466-470` 与 `cmdsFor(SIGNAL)` `:477-483`
> 都含 `Lte_pci` / `Lte_snr` / `lte_rsrp`…）。差别在 **decoder**：
> CELL_INFO 那几条**没有 decoder**（默认 `AS_IS`，`:336-347`），空串照样算命中；
> SIGNAL 同名字段是 `NUMERIC` / `NON_BLANK`（`:386-392`），空串一律算缺失。
> 推论（由基线 + 代码逐字推出，未看到 dump）：设备在驻 5G 时**返回了这些 LTE 键但值是空的**。
> 副作用：**驻 5G 时 CELL_INFO 的 `hit` 数偏高**（有 4~5 项是「空值命中」），
> 比对基线时别把它当成「LTE 侧有数据」。

**SIGNAL 的 `nr_band_width` —— 成因 ②：这台固件不给 5G 带宽。原 P1-2 据此结案。**
source 是 `Nr_band_widths` + `Decoders.NUMERIC`（`:379`），空串解析不出 Long → 缺失。
真机 5G 小区信息里 `"Nr_band_widths": ""`（**依据同样来自用户的真机 dump，本仓无该文件**），
但仓内有两处独立佐证：`DeviceFields.kt:360` 写着「**设备经常不填**（`Nr_band_widths` /
`Lte_bands_widths` 多为空），缺失即省略该 key」，`ZteGoformProfileTest:1023-1034`
有一条专门的用例「带宽为空时不输出 `band_width`」，夹具就是 `"Nr_band_widths" to ""`。
**结论：固件不给，不是别名没登记 → 不改。**

**`Language` —— 成因 ③。**
`cmdsFor(IDENTITY)`（`:430-435`）里**没有** `Language`；它由 `getDeviceVersion()` 那组查询提供
（`GoformSignalClient.kt:128`：`client.query(listOf("Language", "cr_version", "wa_inner_version"))`），
而覆盖率报告只发 `cmdsFor()`。同组的 `cr_version` / `wa_inner_version` 命中是因为它们
**同时**在 `cmdsFor(IDENTITY)` 里。也在测试的 `knownGaps` 白名单里（`ZteGoformProfileTest:110`、`:117`）。
线上 `/api/device/info` 的 `language` 字段照常有值（`DeviceRoutes.kt:240` 直接读 `Language`）。

**小结**：18 项里 ①「设备没返回 / 返回空」= 15 项（DEVICE_SETTINGS 5 + encryp_type 1 +
`lte_rsrp` 1 + SIGNAL 8），③「刻意不在覆盖率命令表里、由别的查询提供」= 3 项
（`dhcpLease` / `lan_station_list` / `Language`，三项都已在单测白名单里登记过理由）。
**没有一项是映射写错、别名漏登记或命令表搬丢。**

### ~~预判：`coverageReport` 的合并查询可能让 `WiFiModuleSwitch` 仍然 missing~~ —— **已撤销，预判不成立**

上一版 §16 留了一条预判：`coverageReport()` 的 `queryGroup()` 把 WIFI_SETTINGS 的 14 项
**合并成一次请求**发（`soloCmds()` 只对 `WIFI_CLIENTS` 返回 `station_list`，
`ZteGoformProfile.kt:486-487`），而线上是两次独立请求（`getWifiSettings()` 12 项
+ `getWifiModuleInfo()` 2 项，`GoformWifiClient.kt:38-40`）；因为仓里有 `station_list`
被合并查询吞掉的先例，所以担心 `WiFiModuleSwitch` 重抓时仍然 missing。

**基线证明这条不成立，明确划掉**：

- 基线里 `WiFiModuleSwitch ← WiFiModuleSwitch` **命中了**，而且 source 就是它自己。
  它是 `MODULE_SWITCH` 别名链的首项、也是设备的**响应键**
  （`ZteGoformProfile.kt:322-323`，canonical 本身就是驼峰串 `WiFiModuleSwitch`，
  `DeviceFields.kt:168`）—— 它只可能来自容器命令 `queryWiFiModuleSwitch` / `queryAccessPointInfo`
  的**响应顶层**，别的路径给不出这个键。
- 也就是说：**容器命令与 12 个扁平 cmd 合并成一次请求，没有被设备吞掉**。
  `station_list` 那个先例不具普适性，不要再据此给 WIFI_SETTINGS 加 `soloCmds`。
- 同组另外 6 项的 source（`ChipIndex` / `SSID` / `Password` / `AuthMode` /
  `ApBroadcastDisabled` / `ApMaxStationNumber`）全是 `liftActiveAccessPoint`
  从 `queryAccessPointInfo` 的 `ResponseList` 提到顶层的键（`:305-321`），
  同样证明容器命令在合并请求里正常返回。
- 顺带确认批 10（`d435fa4`）的修复生效：WIFI_SETTINGS 由 6 命中变 7，
  与当时的推算一致（判据 4「hit 只增不减」方向正确、原因可解释）。

### 两条「不许动」的映射（看着像 bug，掰回去就是把功能改坏）

1. **`monthly_rx_bytes ← monthly_tx_bytes` 与 `monthly_tx_bytes ← monthly_rx_bytes` 是刻意交叉。**
   `ZteGoformProfile.kt:255-266` 有整段注释 + 2026-09-01 真机实测依据：ZTE 固件的
   `monthly_rx/tx` 是「从模块看 PC」的视角，实测同一台 F50 `monthly_tx_bytes` = 7.0 GB 才是**下载**。
   在唯一的适配层一次性掰正，上层拿到的 canonical `rx` 就是下载。
   `ZteGoformRawCaptureTest:61-65` 用真机夹具把这个交叉钉住了。
   **基线里看到它像接反 —— 掰回去就是把流量统计改坏。**
2. **`lte_snr ← Lte_snr`：canonical 小写、设备 source 大写，这是设备命名规律，不是笔误。**
   ZTE 的规律是**小区参数首字母大写**（`Lte_pci` / `Lte_fcn` / `Lte_bands` / `Lte_snr`，`Nr_*` 对称）、
   **信号质量指标全小写**（`lte_rsrp` / `lte_rsrq` / `lte_rssi`）。
   小写的 `lte_snr` 是 core 自有的 canonical（`DeviceFields.CellInfo.LTE_SNR` == `"lte_snr"`，
   `DeviceFields.kt:242`），长得像设备原名而已 —— `ZteGoformProfile.kt:341-347` 的注释写明
   「**不许把它抄进任何 cmd 列表**」，P0-3 就是这么来的。

### 结构解码器已验证在真机上工作

TRAFFIC_LIMIT 的 `limit_value ← limit_value`、`limit_unit_display ← limit_unit_display`、
`limit_bytes ← limit_bytes` 三项，**source 名等于 canonical 名** —— 这不是巧合也不是自反映射：
它们是结构解码器 `splitDataVolumeLimit`（`ZteGoformProfile.kt:1301-1318`）从复合串
`data_volume_limit_size`（如 `"470_1024"` = 470 GB）拆出来的派生键，**派生键刻意直接用 canonical 名**
（`:1293-1296` 的注释：这样过渡期 `KEEP_PRESENT` 不会把复合串当别名再透出一份）。
`ZteGoformProfileTest` 的 `derivedByDecoder` 白名单（`:123-127`）登记的正是这三项。
**基线里三项全部命中 → `structuralDecoder` 这条机制在真机上工作正常**，
TRAFFIC_LIMIT 也因此拿到 10 / 10 满分。

### 这份基线怎么用

> **阶段 0 完成后（尤其是 0.4b 落地后）必须重抓一份**，按 §14.3 的四条判据与本节这份
> **85 / 67 / 18** 逐条对比。阶段 0 改的是写路径与命令表，按设计**不动 `readSpecs()`**，
> 所以 `registered` 逐组与合计都应不变；变了就说明改到了不该改的地方。

- `registered` 逐组不变、合计仍是 **85** → 证明没动 `readSpecs()`
- `queried` 仍是 10 组全 true → 证明没搬丢整组命令
- `hit_source` 的每一条映射**逐字不变** → 证明映射没变（本节起这条真的可比对了）
- `hit` 只增不减（≥ **67**）；少掉的每一项都要能用当时的设备状态解释，解释不清 → P0

重抓时特别注意四件事：

1. **先记设备状态再看数字**：本节这份是**驻 5G**、定时重启关、1 个 WiFi 客户端、老固件。
   换成驻 4G 会整体翻面（`lte_*` 命中、`nr_*` missing），那不是改坏了。
2. **CELL_INFO 的 hit 数在驻 5G 下偏高**（那几条 spec 没有 decoder，空值也算命中，见上面的归因）。
   跨制式比对时用 `hit_source` 逐字比，不要只看 hit 数。
3. **上面那 3 项「命令表里本来就没有」的 missing**（`dhcpLease` / `lan_station_list` / `Language`）
   在任何设备状态下都会 missing。它们是登记态，别在重抓后当成回归。
4. **0.4b 若新增 `FieldGroup`**（`FULL_STATUS`，或 P1-16 的 `SMS_META`），
   `registered` 合计与组数**必然**变 —— 那一步要**先抓基线、再改**，
   并在 §16 追加一份新基线而不是覆盖这一份（两份对照才能说明差异来自新增组）。
   ✅ **2026-09-22 批 13 更新：0.4b（`680fbae`）已落地，并且刻意**没有**新增 `FieldGroup`** ——
   `getFullStatus()` 的 96 项走的是新开的 `DeviceProfile.fullStatusCmds()`（不是枚举值），
   正是为了不动这份基线。所以：**组数仍是 10、`registered` 合计仍应是 85，
   本节不需要追加第二份基线**，重抓的那一份直接按上面四条判据与这一份逐字比。
   `SMS_META`（P1-16）仍未做，那一条的警告继续有效。



