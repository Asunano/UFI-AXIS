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
| F50 实现 | `core/device-schema/.../profile/ZteGoformProfile.kt`（1099 行） | 10 个 FieldGroup、18 个 `SettingKey`、5 个结构解码器 |

### 2.2 欠账清单（这就是阶段 0~4 要清的）

**A. 写命令绕过 profile —— 19 个调用点**（去重后约 15 个命令）

- `GoformDeviceClient.kt:36` `REBOOT_DEVICE`
- `GoformDeviceClient.kt:42` `FACTORY_RESET`
- `GoformDeviceClient.kt:48` `SHUTDOWN_DEVICE`
- `GoformDeviceClient.kt:56` `SET_USB_NETWORK_PROTOCAL`（参数 `usb_network_protocal`）
  → **2026-09-22 批 3 已删除**：`setUsbMode` 全仓零调用（无 route、无 app 入口），
  按「没用了就彻底删」的纪律连 `SettingKey.USB_MODE` + WriteSpec 一起删掉，见 §15。

- `GoformDeviceClient.kt:85` `CHANGE_PASSWORD`（`oldPassword`/`newPassword` = SHA256 大写）
- `GoformNetworkClient.kt:38` `CONNECT_NETWORK` / `DISCONNECT_NETWORK`（变量 `primaryId` 拼出来的）
- `GoformNetworkClient.kt:40` `SET_DATA_ENABLED`（`data`=1/0，是上一条的兜底）
- `GoformNetworkClient.kt:63` `CONNECT_NETWORK`
- `GoformNetworkClient.kt:69` `DISCONNECT_NETWORK`
- `GoformNetworkClient.kt:75` `SET_CONNECTION_MODE`（参数 `ConnectionMode`）
- `GoformWifiClient.kt:310 / 359 / 391` `setAccessPointInfo`（三个调用点，参数集不同）
- `GoformWifiClient.kt:347` `SET_WIFI_POWER`（`wifiPowerLevel`）
- `GoformWifiClient.kt:374` `switchWiFiChip`（`ChipEnum` / `GuestEnable`）
- `GoformWifiClient.kt:379` `switchWiFiModule`（`SwitchOption`）
- `GoformSmsClient.kt:200` `DELETE_SMS`
- `GoformSmsClient.kt:206` `SET_MSG_READ`
- `GoformSmsClient.kt:264` `SEND_SMS`

登录/登出三处（`GoformClient.kt:275 / 290 / 786`）**不进 `SettingKey`** —— 它们属于传输层握手，归阶段 1。

**B. 读命令表只搬了一半**

> **2026-09-21 按真机基线修正**：这个标题不准确。§16 显示 10 个 `FieldGroup` 的
> `queried` **全部为 true**，说明 `cmdsFor()` 每一组都已经登记了命令 ——
> 客户端里那些硬编码 `client.query(listOf(...))` 不是「profile 缺失时的补位」，
> 而是一条**与 profile 并行存在的第二条查询路径**（业务查询走它，覆盖率诊断走 profile）。
>
> 所以 0.4 的工作性质不是「搬」，而是「**删掉并行路径并确认两边 cmd 集合一致**」。
> 一致性没确认就删 = 业务查询的字段集悄悄变了 = hit 变 missing（§14.3 第 4 条判据会抓到）。
> 唯一真正没有 profile 对应物的是 `getFullStatus()` 那 88 个字段名。

- `GoformSignalClient.kt:55` 信号 16 个 cmd；`:86` `network_information,Lte_ca_status`；
  `:92` 身份 5 个；`:129` 版本 3 个；`:147` 流量；`:169/181/194` `getFullStatus()` 三批共 88 个字段名；
  `:276` `neighbor_cell_info`
- 兜底列表（profile 没登记时用）：`:112 / :261 / :291 / :312 / :333 / :347`。
  实测 profile 都登记了 → 这些 fallback 在归一化开启时**已是死代码**，但**不能直接删**（见 §4 的 0.4）
- `GoformWifiClient.kt:36` `queryWiFiModuleSwitch,queryAccessPointInfo`；`:188` 12 个 `wifi_*`；
  `:235` `station_list`；`:261` `queryDeviceAccessControlList`
- `GoformSmsClient.kt:68 / 217` 短信列表的全部查询参数（`mem_store=1&tags=10&order_by=...`）


**C. 设备值域写死在客户端**

- `GoformNetworkClient.kt:28-31` `LTE_ALL_BANDS` / `NR_ALL_BANDS`（注释写的是 **ZTE MU300** 的频段表）
  → **2026-09-22 批 3 实测：这两个常量被跨模块引用，不是简单搬迁**（`NetworkController.kt:122/127`），
  而且 `core/contract/Enums.kt:145-146` 还有第三份同值拷贝。升 P0-1，见 §15。

- `GoformWifiClient.kt:310-341` `AuthMode=WPA2PSK` / `EncrypType=CCMP` / `ApIsolate=0` / `AccessPointIndex=0`
- WiFi 密码：写 base64(UTF-8)、读 base64(GBK)（`GoformClient.kt:865 / 868`）
- 二维码文件名模板 `{chip}_ssid{n}_qrcode_wifikey`（`GoformWifiClient.kt:69`）

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
  `HttpServer.kt:566`），但不下发能力集，所以 app / web 无法提前灰掉不支持项 ——
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

---

## 4. 阶段 0 — 补齐命令表（无新概念，纯搬运）

**为什么先做**：只要还有命令绕过 profile，插件化就是假的 —— 换设备时那些命令会**静默发错**。
这一阶段不引入任何新类型，风险最低，且有 1325 行的 `ZteGoformProfileTest` 兜底。

### 任务

> 状态口径（2026-09-22 批 3 更新）：批 1 / 1b / 2 已完成的子项标 `[x]`，
> 还差一部分的标 `[~]`，被 P0 拦住的标 `[!]`。

- `[x]` 0.1 `SettingKey` 补齐写命令（`core/device-schema/.../DeviceProfile.kt:70`）
- `[x]` 0.2 `ZteGoformProfile` 为新增 key 登记 `WriteSpec`
- `[~]` 0.3 4 个客户端的硬编码调用点改走 `writer.writeChecked(...)`
  → 批 2 完成 11 处；**剩 `GoformWifiClient` 的 3 处 `setAccessPointInfo`**（批 3 受 P0-2 阻塞）
  与短信 3 处（归 0.7）
- `[ ]` 0.4 读命令表收进 `cmdsFor()` / `soloCmds()`，**mapper 改双 profile**（见下）
- `[!]` 0.5 设备值域（频段全集 / WiFi 固定枚举 / base64 编码方向 / 二维码文件名）搬进 profile
  → 阻塞原因：频段全集被跨模块引用（P0-1）、WiFi 三处的合并规则不等价（P0-2）、
  二维码文件名需要新的 profile 读侧 API 面（待裁决，见 §15）
- `[x]` 0.6 `WriteSpec` 加 `retry: RetryPolicy`，现有 18 项显式标 `RETRY_ON_SESSION_LOSS`
- `[ ]` 0.7 短信三项走 `smsSpec()`，**不进** `SettingKey`（见 §11.2）
- `[~]` 0.8 补测试：新增 key 的 encode/validate 逐条断言；`SettingKey` 全覆盖断言
  → 已有的部分：`ZteGoformProfileTest` + `GoformSettingWriterDecisionTest`（189 条全绿）；
  差 WiFi 整份 AP 配置与短信两块



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
    WIFI_SSID,           // setAccessPointInfo 的一组参数
    WIFI_PASSPHRASE,     // setAccessPointInfo（密码 base64 方向在 encode 里）
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
（见 `GoformSignalClient.kt:333` 的用法）。把各处 `client.query(listOf(...))` 的字面量搬进
`ZteGoformProfile.cmdsFor()` 对应分组，调用点改成 `fields.cmds(group)`。

> **不要直接删 fallback 参数**（2026-09-21 修正，原方案在这里是错的）。
> `GoformFieldMapper.kt:65` 是 `profile?.cmdsFor(group) ?: fallback`，而 `profile` 在
> `fieldNormalizationEnabled=false` 时是 null —— 此时 **fallback 是唯一的命令来源**，
> 删掉它等于「关掉归一化 → 一条查询都发不出去 → 整个只读面瘫掉」。
>
> 正确做法：照抄写侧已有的结论（`GoformSettingWriter.kt:14`「字段归一化可以关，写命令表不能关」），
> 把 mapper 改成**双 profile**：
> ```kotlin
> internal class GoformFieldMapper(
>     private val normalizeProfile: DeviceProfile?,   // 可空：归一化/脱敏/覆盖率，决定 enabled 与 profileId
>     private val commandProfile: DeviceProfile,      // 非空：只供 cmds()/soloCmds()
> )
> fun cmds(group: FieldGroup): List<String> = commandProfile.cmdsFor(group)
> ```
> **`enabled` / `profileId` 的语义必须保持不变**：`/api/diagnose` 的 `normalization_enabled`
> 是从 `activeProfile != null` 推出来的（`HttpServer.kt:574` ← `DataHub.kt:225`
> ← `GoformSignalClient.kt:37` ← `mapper.profileId`）。顺手把 `profileId` 改成非空，
> 排障开关的可观测性就没了 —— 它会永远报 `true`。

`getFullStatus()` 那 88 个字段名建议新增一个 `FieldGroup.FULL_STATUS`（或按现有三批分成三个组），
不要塞进已有分组 —— 它是「一次拉全量」的专用批次，与按需查询的分组语义不同。
注意加枚举值会影响穷举逻辑：`GoformFieldMapper.coverageReport()` 遍历 `FieldGroup.entries`
（`:96`），新组没登记 cmd 时会多出一个 `queried=false` 的块，`ProfileContractTest` 的相关断言要跟着改。


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


### 验收

- `:core:device-schema:test` 与 `:core:goform:test` 全绿
- 写命令：`core/goform/src/main` 里 `"goformId" to` 的剩余数量
  **（2026-09-22 批 2/批 3 实测修正，原判据「只剩 GoformClient 的登录/登出 3 处」是错的）**：
  - **现状 = 7**：`GoformWifiClient.kt:310/354/382`（`setAccessPointInfo` ×3）
    + `GoformSmsClient.kt:200/206/264`（`DELETE_SMS` / `SET_MSG_READ` / `SEND_SMS`）
    + `GoformClient.kt:786`（`LOGOUT`）
  - 0.3 的 WiFi 3 处做完 → **4**；0.7 短信三项做完 → **1**（只剩 LOGOUT，归阶段 1）
  - ⚠ **`"goformId" to` 这个 pattern 抓不到字符串形态的登录命令**：
    `GoformClient.kt:275` 与 `:290` 是 `setBody("isTest=false&goformId=LOGIN_MULTI_USER&…")`
    / `setBody("isTest=false&goformId=LOGIN&…")`，整条 body 是一个字符串字面量。
    所以真实的「绕过 profile 的写命令」总数 = grep 数字 **+ 2**。
    要一次抓全就同时 grep `goformId`（不带 ` to`）—— 别再按前一个数字下结论。

- 读命令：`core/goform/src/main` 里 `client.query(listOf(` 为 0。
  **判据要排除 `GoformFieldMapper.kt`** —— 它的 `:131/:132` 是引擎自身按 solo/非 solo 分批发查询，
  那是正确代码，不是硬编码命令表（原方案这条判据会自己打自己）
- **route 层的两个裸命令端点是刻意的例外，不许动**：`DeviceRoutes.kt:147`
  `POST /api/device/goform/query` 与 `:178` `POST /api/device/goform/set` 的 cmd / goformId
  来自 HTTP 请求体，由 `goform_command_enabled` 开关守门（`AppSettings.kt:32`）。
  它们是排障通道，**不进 profile**；阶段 3 再给它们加能力门禁（见 §11.5）
- `ZteGoformProfileTest` 新增断言：`SettingKey.values()` 全部有 `writeSpec`（现有测试已有这条，
  新增 key 会自动被它覆盖 —— 先跑一次确认它真的会失败，再补 spec）
- **排障开关回归**：把 `field_normalization_enabled` 设成 false 重启后台服务，
  仪表盘/网络/WiFi 三个页面仍有数据（验证 0.4 的双 profile 改法），
  且 `/api/diagnose` 的 `device_profile.normalization_enabled` 为 `false`
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
  `WIFI_SSID` / `WIFI_PASSPHRASE` / `MOBILE_DATA` / `PPP_DIAL`（设置类，同值幂等）。
  `MOBILE_DATA.fallback`（`SET_DATA_ENABLED`）亦为 `RETRY_ON_SESSION_LOSS`，**独立判定**
  （`USB_MODE` 原也在这张表里，2026-09-22 批 3 已随 `setUsbMode` 一起删除）

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

**决定**：在 `DeviceProfile` 上开一组短信专用契约，每设备各自实现：

```kotlin
interface DeviceProfile {
    /* …现有成员… */
    /** 短信规则。不支持短信的设备返回 null（对应 Capability.SMS 缺失）。 */
    fun smsSpec(): SmsSpec? = null
}

interface SmsSpec {
    /** 下发参数。时间戳由调用方传入 —— 不许在实现里读时钟（否则没法断言）。 */
    fun sendParams(number: String, message: String, atMillis: Long, zone: TimeZone): Map<String, String>
    /** 列表查询的 cmd 与分页参数。 */
    fun listQuery(page: Int, perPage: Int): Map<String, String>
    /** 删除 / 标已读。 */
    fun deleteParams(ids: List<String>): Map<String, String>
    fun markReadParams(ids: List<String>): Map<String, String>
    /** 信箱行的 tag 语义 —— 「已发送 / 发送失败」的判据按设备而异。 */
    fun sentTag(): String
    fun failedTag(): String
    /** 正文编码（UCS2 / GSM7 / 明文…）。 */
    fun encodeBody(message: String): String
}
```

回读确认的**循环**留在 `GoformSmsClient`（它是流程，不是设备事实），
但循环里用的**判据**（`sentTag` / `failedTag`）来自 spec。
轮询次数与间隔（3 × 1.2s）属于实测调参，进 §3.2 的 `DeviceTuning`。

### 11.3 其它「不能搬进 profile」的东西（已决）

`WriteSpec.encode` 是纯函数 `(Map)->Map`，下面这些都**不满足**，必须留在客户端：

- **读-改-写**：`GoformWifiClient.setWifiConfig`（`:311` 先 `getCurrentWifiConfig()`，
  `:331` 还要 `base64Decode` 解出当前密码再重新编码）、`setWifiSSID`（`:354`）、
  `WIFI_ACL`（整表替换，`DeviceProfile.kt:84` 已写明「读-改-写收敛在 core」）。
  → profile 只收**合并后的完整参数集**，「先读回」这一步是客户端的职责。

  > **2026-09-22 批 3 补充（P0-2，见 §15）**：「先读回留客户端 + profile 只收完整参数集」
  > 这条口径本身没问题，但三个方法**不能共用一份 `encode`** —— 它们对 `Password`
  > 这一个字段有三条不同的管线，其中 `setWifiSSID` 发的是**明文**（没有 base64 编码）。
  > 一份把 `passphrase` 统一 base64 编码的 encode 会改变 `setWifiSSID` 实际发出的报文。

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

- `ZteGoformProfileTest`（1325 行）冻结了 F50 每个分组的字段名、别名回退顺序、
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
  `HttpServer.kt:566-580`）
- `GET /api/diagnose?fields=1` → `field_coverage`：**逐个 FieldGroup 向设备查一次，
  输出「登记了几个 / 命中了几个 / 哪些没命中 / 命中的是哪个 source」，只含字段名不含值**
  （`HttpServer.kt:584` → `DataHub.kt:222` → `GoformSignalClient.kt:243`）

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

原方案写的是「`field_coverage` 必须逐项一致」—— **那是错的**。实测基线（§16）有 19 项 missing，
而这些 missing 会随设备状态、固件版本、功能开关状态变化：驻 NR 时 `lte_*` 全 missing、
驻 LTE 时反过来 `nr_*` 全 missing；定时重启没开就没有 `restart_time`；
老固件没有 `BearerPreference`（`ZteGoformProfile.kt:149` 的注释早写了）。
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
4. **hit 集合只许增不许减**。少掉的每一项都必须能用第 1 步记录的设备状态解释；
   解释不了 → P0。

另外两条与 coverage 无关但同样要比：

- 只读端点的响应**除时间戳/计数器类字段外**必须一致
- 裸命令的原始响应一致 → 说明设备侧没变，差异都是我们引入的

**看 coverage 报告时的一条纪律**：`hit_source` 里看起来像 bug 的映射，**先去读 profile 的注释**。
基线里 `monthly_rx_bytes → monthly_tx_bytes`、`monthly_tx_bytes → monthly_rx_bytes` 是上下行**交叉**的，
看着像接反 —— 实际是 `ZteGoformProfile.kt:215-226` 刻意为之：ZTE 固件的 `monthly_rx/tx` 是
「从模块看 PC」的视角，2026-09-01 真机实测 `monthly_tx_bytes`=7.0GB 才是下载，
所以在唯一的适配层一次性掰正。**把它「修正」回来就是把功能改坏。**

**差异不为空且解释不清 → P0。**

**没有真机时**：这一层做不了，在对应任务后标 `[!] → 无真机，接口快照未验证`，
**不要把子项标成 `[x]`**。阶段 0 的写操作在没有真机验证的情况下不允许合进主线。


### 14.4 第 4 层：真机手工回归

写操作没有自动化替代品。阶段 0 的清单（12 条，见 §4 验收）每条要做三次观察：

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

**P1-1 同一类数据在 SIGNAL 与 CELL_INFO 两组的命中结果不一致**

- 事实（来自 §16 基线）：CELL_INFO 组的 `lte_rsrq` → `lte_rsrq`、`lte_snr` → `Lte_snr` **命中**；
  而 SIGNAL 组的 `lte_snr` **missing**。两组 `cmdsFor()` 的列表不同。
- 事实：CELL_INFO 的 missing 只有 `lte_rsrp`，SIGNAL 的 missing 含 7 个 `lte_*`。
- 猜测（未验证）：SIGNAL 组查的是 `lte_snr` 这个小写名，而设备只返回 `Lte_snr`（首字母大写），
  CELL_INFO 组恰好登记了大写别名 → 补一条别名链就能修。
- 归属：**不属于阶段 0**（阶段 0 只搬运、不改映射结果）。建议在阶段 0 完成、
  基线对齐之后单独一个 commit 处理，并用 §14.3 的判据验证「只增不减」。

**P1-2 `nr_band_width` 在驻留 NR 时仍然 missing**

- 事实（§16）：SIGNAL 组驻 NR 状态下 `nr_*` 系列全部命中，唯独 `nr_band_width` missing。
- 猜测（未验证）：这台固件不返回该字段，或它的设备侧名字不是登记的那个。
- 归属：同 P1-1。先确认是「固件没有」还是「别名没登记」—— 前者不该改，
  后者补别名。判据是裸命令 `POST /api/device/goform/query` 抓一份原始响应看有没有这个 key。

**P0-1 `LTE_ALL_BANDS` / `NR_ALL_BANDS` 被跨模块引用，且全仓有三份同值拷贝（批 3 停手项）**

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
- 归属：需要裁决。要么（a）扩范围到 `core/controller` 单独一个 commit，
  要么（b）推到阶段 2 与 `NetworkController` 的设备知识一起清。阶段 0 不擅自改 `core/controller`。

**P0-2 三个 `setAccessPointInfo` 调用点无法用一份 `encode` 表达（批 3 停手项）**

现状基线（`GoformWifiClient.kt`，`current` = `getCurrentWifiConfig()` 的返回，
其中 `current["Password"]` 已经是 `base64Decode` 过的**明文**）：

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
- 归属：需要裁决三条路中的哪一条 ——
  （a）encode 收两个入参（`passphrase` 明文 + `passphrase_encoded` 已编码值），逐字保住现状；
  （b）承认 `setWifiSSID` 是 bug，单独一个 commit 修，然后三处共用一份 encode；
  （c）本阶段只收敛 `setWifiConfig` + `setWifiPassword`，`setWifiSSID` 留到修 bug 那一批。

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


---

## 16. 真机基线（2026-09-21）

来源：真机 `GET /api/diagnose?fields=1` 的 `field_coverage`。
**注意**：这份数字是从一段无法复制、经 OCR 转录的输出里整理的，
各组的 `registered` / `hit` 数可信（相互能对上），**逐个 missing 字段名可能有转录误差** ——
有复制按钮时应重新抓一份逐字覆盖本节。

抓取时的设备状态（重要，覆盖率差异只能靠它解释）：
- `profile_id` = `zte-goform`，`normalization_enabled` = true
- 驻网制式：**NR**（`nr_*` 系列命中、`lte_*` 系列大面积 missing 可反推）
- 老固件：有 `net_select`、无 `BearerPreference`（见 `ZteGoformProfile.kt:149` 的注释）
- 定时重启：未开启（`restart_time` missing）
- 其余状态（WiFi 客户端数、`ppp_status`）**未记录** —— 下次抓基线时补上

### 总计

- 10 个组全部 `queried: true`
- `registered` 合计 **85**，`hit` 合计 **66**，`missing` 合计 **19**

### 逐组

- **DEVICE_SETTINGS**：13 registered / 8 hit / 5 missing
  - missing：`restart_time`、`sleep_sysIdleTimeToSleep`、`UpgMode`、`BearerPreference`、`dial_roam_setting_option`
  - 命中里有一条**别名链生效的实例**：`connection_mode` → `dial_mode`（这台固件用 `dial_mode`）
  - 这 5 项的 cmd **确实在 `cmdsFor()` 里**（`ZteGoformProfile.kt:358-367`），是设备没返回 → 不是命令表缺失
- **LAN_SETTINGS**：10 / 9 / 1
  - missing：`dhcpLease`（而 `dhcpLease_hour` 命中 → 这台固件只给小时制那个）
- **WIFI_SETTINGS**：8 / 6 / 2
  - missing：`wifi_chip1_ssid1_encryp_type`、`module_switch`（OCR 显示为 `WiFiModuleSwitch`，待核）
  - 命中的 6 项来自结构解码器 `liftActiveAccessPoint` 摊平后的键
- **WIFI_CLIENTS**：2 / 1 / 1
  - missing：`lan_station_list`（`station_list` 命中）
- **BAND_STATUS**：2 / 2 / 0 ✓ 满分
- **CELL_INFO**：8 / 7 / 1
  - missing：`lte_rsrp`
- **IDENTITY**：7 / 6 / 1
  - missing：`Language`（`cr_version` / `wa_inner_version` 均命中）
- **TRAFFIC_LIMIT**：10 / 10 / 0 ✓ 满分
  - 含刻意交叉：`monthly_rx_bytes` → `monthly_tx_bytes`、`monthly_tx_bytes` → `monthly_rx_bytes`
    （`ZteGoformProfile.kt:215-226`，**不要「修正」**）
- **SIGNAL**：22 / 14 / 8
  - missing：`nr_band_width`、`lte_arfcn`、`lte_band`、`lte_band_width`、`lte_signal_strength`、
    `lte_snr`、`lte_pci`、`lte_cell_id`
  - 其中 7 项是 `lte_*`，可用「驻 NR」解释；**`nr_band_width` 解释不了** → 记为 P1-2
- **CONNECTION**：3 / 3 / 0 ✓ 满分

### 这份基线怎么用

- 阶段 0 / 1 做完后重新抓一份，按 §14.3 的四条判据比对
- `registered` 合计必须还是 **85**、逐组数字不变 → 证明没动 `readSpecs()`
- `queried` 必须还是 10 组全 true → 证明没搬丢整组命令
- `hit_source` 的每一条映射逐字不变 → 证明映射没变
- hit 合计 ≥ 66；少掉的每一项都要能用当时的设备状态解释



