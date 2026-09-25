# 设备插件化框架落地计划

> **先说本文现在是什么：改造过程的「决策与执行记录」，不是照着做的手册。**
> 阶段 1~5 的代码工作**已经全部落地并提交**（最后一批是 `ba08293`）。
> ⚠ **2026-09-25 更新**：`ba08293` 之后又做了一轮**设备适配接口铺路**（§9 的批 40 ~ 批 50，
> 最后一批是 `25c4419`）—— 六个域接口 + `DeviceHub` / `DeviceAdapter` 立起来了，
> 上层不再持有任何 goform 具体类。所以「最后一批是 `ba08293`」这句只对阶段 1~5 的**任务清单**成立。
> 下面的任务清单、§9 的批次记录、§15 的待办池，记的是**当时为什么这么决定、哪些方案被推翻、
> 每一批验到了第几层** —— 这是它剩下的唯一价值，所以决策痕迹一条不删。
>
> - **想知道「现在接一台新设备要改哪些地方」，去看 `docs/device-adaptation-guide.md`。**
>   那份按代码现状写、只写现状，是照着做的文档；本文不再承担这个职责。
> - **本文里的代码片段是当时的设计草案，与最终实现可能不同。** §3.2 的 SPI 骨架就有多处
>   被后来的实测推翻（那一节上方有标废框），照它写会得到编译不过、或者行为不对的代码。
> - 任何一处对不上，**以代码为准**：先按符号名在代码里确认真实形状，再回来改本文，
>   **不要改代码去迁就本文**（§13 的偏差处理讲的就是这件事）。

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
- 阶段之间是**硬依赖**：前一阶段没到 `[x]`，不要开下一阶段。**两条例外**：
  1. 阶段 3 的 **3A（设备侧能力）可以与阶段 4 并行**，**3B（平台侧能力）必须等阶段 4**
     —— `BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 的取值来自 `PlatformAdapter`。
     （本行原文是「唯一例外是阶段 3 可以与阶段 4 并行」，§7 已按这条拆开更正，此处同步。）
  2. **「无真机」例外（2026-09-23 用户裁决）**：前一阶段**只卡在 §14 的第 3/4 层、且原因确实是
     「没有真机」**（第 1、2 层全绿、该阶段的代码工作已全部落地）时，允许开下一阶段。三条前提：
     - 该阶段仍标 `[~]` 并写明卡在第几层，**不许标 `[x]`**（§14.6 未变）；
     - **涉及写操作的 commit 一律不合主线**（§14.3 最后一句原本只是纪律，这里升成「开下一阶段」的前置条件）；
     - 真机待办留在 §9「阶段 0 收尾盘点」那类清单里，拿到真机后**按原判据**补验；
       补验不过 → 按 §13.2 的 P0 停手，**先回滚再查**，不许就着已经堆上去的下一阶段改。
     这条**只对「无真机」生效**：卡在第 1/2 层（编译 / 单测不过），或第 3/4 层**已经验出差异且解释不清**，
     仍然不许开下一阶段。
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
- **安装器不纳入插件化**（`scripts/UFI-AXIS-Core-install-Android/**`，**2026-09-23 用户裁决**）。
  那里有一份**独立的第二套 goform 实现**（自己的 `GoformClient` / `GoformGateway` /
  `GoformWritePolicy` / `GoformCodec` / `GoformWriteResult`，包名 `com.ufi_axis.installer.goform`，
  另有 `RemoteAdbEngine` 直发 goform），**零 profile 抽象**。裁决是**整份独立维护、不做任何共享** ——
  不上 SPI、不共用 profile、连「设备文本解码」「写成功判据」这两处也不抽公共实现。
  - 判据：安装器是**一次性装机流程**，与后台服务不共享组件图、不共享生命周期、不参与归一化与覆盖率诊断；
    为了「少一份重复」把它拉进 `device-spi` 的依赖，换来的是装机路径被插件选型的失败模式拖累。
  - **明确接受的后果**：① 接一台新设备时，安装器要**另写一套**，§1 目标里「1~3 个文件」的成本
    **只对后台服务成立**，不覆盖装机链路；② 解码/判据这类逻辑会长期存在两份甚至三份，
    **这是刻意的重复，不再当欠账登记**（§15 的 P1-19 据此结案）。
  - 例外只有一种：安装器与 core **共享持久化键或对外报文格式**时（如 `persist.adb.tcp.port`、
    配对协议），那属于契约，仍以 `core/contract` 与 API 手册为唯一真源 —— 不许各写一份。

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
| F50 实现 | `core/device-schema/.../profile/ZteGoformProfile.kt`（撰写时 1099 行，之后一直在长） | 10 个 FieldGroup、`SettingKey` 撰写时 18 个 → **现 29 个**（2026-09-23 实测；批 14/15 新增 `WIFI_BAND` 之前一直写 28）、5 个结构解码器 |

> **关于行数与测试条数**：这类数字每批都会变，逐轮回填必漏（批 17 就是这么出的错）。
> **本文档不再在正文里写死它们** —— 要用的时候现场数：
> 行数看文件本身，测试条数看 `build/test-results/**/*.xml` 里的 `tests=` 属性。
> 需要被**冻结**的不是数字，而是判据：`writeSpecs` 与 `SettingKey` **逐项对齐**（0.2）、
> 既有断言**一条不许改**（阶段 1 起的纪律）、新增只许是守门测试。
> 2026-09-23 收工时的实测值（仅作参考，不作判据）：`:core:goform:test` **103**、
> `:core:device-schema:test` **190**、`:core:device-spi:test` **11**、`:core:device-plugins:test` **8**。

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
  ⚠ **2026-09-22 真机抓包后重新定性**：`switchWiFiChip` 不是「开 WiFi」，而是「**在频段 X 上启用 WiFi**」，
  与「切频段」是同一条命令。据此新增 `SettingKey.WIFI_BAND`，`WIFI_ENABLED` 的「开」分支改成收可选
  `chip` 参数（见 §15 的 **P1-26**：profile + 客户端 + route + 两端 UI 均已接线，只剩真机验证）。
- `GoformWifiClient.kt:379` `switchWiFiModule`（`SwitchOption`）→ 同上
  ⚠ 只用于「关」（`SwitchOption=0`）。`SwitchOption=1` 本项目从未发过、抓包里也没有 ——
  **无实测依据，不许写进代码**（P1-26 记了这条判断的来龙去脉）。
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

### 3.2 SPI 骨架（~~阶段 2 落地时以此为准~~ → **设计草案，已作废，不要照抄**）

> ⛔ **这一节整段是 2026-09-21 的设计草案，已作废。** 阶段 2~5 落地时它被实测推翻了多处，
> 照这里写出来的代码要么**编译不过**（方法根本不存在），要么**签名不对**。
> 草案**刻意保留不删** —— 删了就看不出原来打算怎么做、以及为什么没这么做。
>
> **真实签名只看代码**：`core/device-spi/src/main/java/com/ufi_axis_core/devicespi/` 下的
> `DevicePlugin.kt` / `PlatformAdapter.kt` / `DeviceTuning.kt`（外加同目录的 `ProbeEnv.kt`），
> 以及 **2026-09-25 批 A1 ~ A3 长出来的 `adapter/` 子包 9 个文件**（草案里没有这一层，见差异第 8 条）。
> 那几个文件的 KDoc 逐成员写了「为什么是这个形状」「刻意没有什么」，比本节详细得多。
> 要照着做请看 `docs/device-adaptation-guide.md`。
>
> **逐条差异（2026-09-25 打开上面四个文件逐成员核对；第 7 ~ 9 条是同日晚间按批 40 ~ 批 50 补的）：**
>
> 1. **`PlatformAdapter.privilegeEscalation(): PrivilegeStrategy?` —— 最终不存在。**
>    实测提权只有一条实际路径（ADB 自连 `localhost:5555`，`ShellExecutor.executeAsRoot()` 全走它），
>    Samba `root preexec` 那条的执行入口是死代码。一个永远只有单一实现的策略接口，
>    只会让读代码的人以为这里真有选择 —— 见 §8 裁决 ④ 与 `PlatformAdapter` 文件头。
> 2. **`PlatformAdapter.readBattery(): BatteryReading?` —— 最终不存在。**
>    电量的主取值来源是 Android 自己的 `BatteryManager`，那是 framework 通用 API、不是平台知识；
>    而且会给平台实现引入第一个 `Context` 依赖（`SprdPlatform` 至今零参构造）。
>    `Capability.BATTERY` 因此改成**采集侧按能力跳过入库与告警**（批 L / 批 M），
>    不靠「读一次看能不能读到」—— F50 恰恰读得到一个恒为 50 的假值，那种设计在它上面会得出错误结论。
> 3. **`restartNetworkStack()` 的签名多了一个执行器参数。** 实际是
>    `suspend fun restartNetworkStack(at: suspend (String) -> String?): Boolean`。
>    唯一的 AT 执行通道 `ATChannel` 住在 `:core:collector`，让平台实现反过来持有它会把策略层
>    （全局互斥 / 500ms 最小间隔 / 指数退避 / 20 次熔断）塞进平台实现里、方向倒置，
>    还要给 `:core:device-plugins` 加一条对 collector 的依赖。所以 adapter 只回答「发什么」，
>    「怎么发」由调用方（`NetworkController`）注入 —— 附带收益是这个函数变得可单测（§8 裁决 ⑤，批 G）。
> 4. **`DeviceTuning` 的字段名与成员集都变了。** 草案的 `thermalWarnC` / `thermalCriticalC`
>    撞了三套完全不同的阈值（下载限速 75/85 `Float` / 采集降频 70/80 **`Int` 毫摄氏度** /
>    用户告警 65/75 `Double` 带回差），所以改名成**只说自己管什么**的
>    `downloadThrottleWarnC` / `downloadThrottleCriticalC`，并新增
>    `downloadThrottleForcePauseOffsetC`（承载原本裸写在条件里的 `+10`，即 95°C 全部暂停）。
>    `thermalJitterC` / `bootGraceMs` 名字未变。**`rootShellPermits` 被删掉** ——
>    实测生效默认值是 3（`ShellQoS.DEFAULT_QOS_SHELL_MAX`），它是 QoS 配置默认值、不是设备事实
>    （§8 裁决 ②③，批 F）。
> 5. **`ProbeEnv` 的采集点不是 `DeviceRuntime`。** 草案注释写「由 `DeviceRuntime` 采一次」，
>    实际由**装配层** `ComponentFactory.build()` 采好再递进来（采集要 `Context` / `AppSettings` / 网络，
>    纯契约层拿不到这些）；采集器 `ProbeEnvCollector` 住在 `:core:device-plugins` 的 `probe/`。
> 6. **`cpuInfoPlatform` 存的是 `/proc/cpuinfo` 全文**（trim + 小写），不是草案说的「平台串」——
>    判据要与改造前 `ATChannel.detectPlatform()` 的「对全文 contains」逐位等价。
>    marker 列表也不许自己写，统一在 `CpuInfoPlatform`（4.6 之前这里有两份不一致的列表）。
>
> 7. **`DevicePlugin` 多了第 9 个成员 `createAdapter`（2026-09-25 批 A3，`25c4419`）。** 实测签名：
>    `fun createAdapter(transport: DeviceTransport, commandProfile: DeviceProfile,
>    normalizeProfile: DeviceProfile?): DeviceAdapter`。草案里压根没有这个成员 ——
>    当时的设想是「插件交付零件、装配层拼」，而那条路会让装配层为了 new 六个协议客户端
>    把传输层**向下转型**成具体实现类。现在插件自己交付 adapter，装配层只认 `DeviceTransport`
>    与 `DeviceAdapter` 两个契约类型。三个参数各有硬口径（逐字见该方法的 KDoc）：
>    `transport` 是**入参不是自造**（整图只许一份传输层实例，实现里不许再调一次 `createTransport()`）、
>    `commandProfile` **非空**（字段归一化可以关、命令表不能关）、`normalizeProfile` 可空且
>    **不许用非空兜底填上**（那个排障开关会永远报 `true`）。
> 8. **多了一整套 `adapter/` 子包（草案里完全没有这一层）。** 实测
>    `core/device-spi/src/main/java/com/ufi_axis_core/devicespi/adapter/` 下 **9 个文件**
>    （2026-09-25 逐个打开数过成员）：
>    - `DeviceAdapter`（8 个成员：`id` / `capabilities` + 六个**非空**域字段）；
>    - `DeviceHub`（`class DeviceHub(private val adapter: DeviceAdapter)`，8 个成员全是 `get()` 转发：
>      `adapterId` / `capabilities` / `sim` / `device` / `network` / `wifi` / `signal` / `sms`）；
>    - 六个域接口：`SimControl`（1 个方法）、`DeviceControl`（13）、`NetworkControl`（11）、
>      `WifiControl`（16 个成员 = 15 个方法 + `val lastQrCodeFailure`）、
>      `SignalSource`（`val profileId` + 16 个查询）、`SmsControl`（5 个方法 + `SendVerdict` /
>      `SendOutcome` / `SmsMeta` 三个顶层类型）；
>    - `BandSelection`（`sealed interface`，`data object All` + `data class Only(val bands: String)`）。
>    另外 `WifiControl.kt` 里还住着 `AclEntry` / `AclSnapshot`（批 C2 从 `GoformWifiClient` 搬来）。
>    **返回类型刻意不齐整**（`WriteOutcome` / `Boolean` / `NormalizedFields` / 裸 `JsonObject?` /
>    `Pair<ByteArray, String>?` 混着），理由写在各接口 KDoc 里：统一它就会改 route 的响应形状。
> 9. **`DeviceProfile` 多了 `aclDefaultMode()`（2026-09-25 批 40，`a48e1b9`）。** 实测
>    `core/device-schema/.../DeviceProfile.kt:181` 是 `fun aclDefaultMode(): String? = null`
>    （默认 `null` = 「这台设备没有这个概念」），`ZteGoformProfile.kt:825` 覆写成
>    `ACL_MODE_BLACKLIST`。它不在 device-spi 里，但 `DevicePlugin.profile()` 的返回类型就是
>    `DeviceProfile`，所以照 §3.2 草案理解 profile 的 API 面会少这一项。
>
> `DevicePlugin` 的**成员集**反而与草案一致（`id` / `displayName` / `capabilities` / `profile()` /
> `createTransport()` / `platform()` / `tuning()` / `probe()`）—— 只是 `capabilities` 与 `platform()`
> 分别推到了阶段 3 与阶段 4 才补齐，不是一次落地的。
> ⚠ **上面这句是 2026-09-25 批 O 那一轮写的，批 A3 之后只对了一半**：那 8 个成员确实与草案一一对应，
> 但成员集**已经不是 8 个而是 9 个**（多了 `createAdapter`，见差异第 7 条）。原句保留，按这条读。



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

### 3.3 中间控制层骨架（~~阶段 2 落地时以此为准~~ → **设计草案，已作废，不要照抄**）

> ⛔ **同 §3.2：这一节是 2026-09-21 的草案，已作废。** 构造参数、成员、`resolve()` 签名、
> 以及「全 0 时打 WARN」这条行为**全都与实现不同**。草案保留作决策痕迹。
>
> **真实形状只看代码**：`core/device-spi/src/main/java/com/ufi_axis_core/devicespi/DeviceRuntime.kt`
> （2026-09-25 逐成员读过）。
>
> 逐条差异：
>
> 1. **构造参数只有三个**：`plugin: DevicePlugin` / `profile: DeviceProfile?` / `selection: Selection`。
>    草案里的 `transport` / `platform` / `tuning` **都不在这个类上** ——
>    它们由装配层 `ComponentFactory` 直接从 `plugin` 现造（`plugin.createTransport(cfg)` /
>    `plugin.platform(ctx)` / `plugin.tuning()`）。把持有 HTTP 连接池、进程句柄的东西挂在选型对象上，
>    会让它的生命周期与组件图纠缠。
> 2. **多一个成员 `commandProfile: DeviceProfile`（非空）**，实现是 `profile ?: plugin.profile()`。
>    这是草案没有的：字段归一化可以关、**命令表不能关**。兜底刻意用 `plugin.profile()`
>    而不是 `default.profile()` —— 否则打开排障开关会顺带把命令表换成**另一台设备**的。
> 3. **没有 `has()` / `require()`，`CapabilityMissing` 也不在这里。** 草案把能力门禁放在本类上，
>    落地时门禁做在 route 层：能力集经 `DataHub` 的一个只读 `Set<Capability>` 快照进 route，
>    `CapabilityMissing` 定在 `:core:api`、由 `HttpServer` 的 `StatusPages` 统一翻成
>    501 / `NOT_SUPPORTED`（口径见 §7 的 3.3；`:core:network` 看不见 `:core:contract`，
>    所以那个异常必须自带 status / errorCode / 文案）。
> 4. **`resolve()` 的签名完全不同**，实测是：
>    `suspend fun resolve(plugins: List<DevicePlugin>, default: DevicePlugin, configuredId: String,
>    normalizationEnabled: Boolean, probeEnv: ProbeEnv, warn: (String) -> Unit = {},
>    info: (String) -> Unit = {}): DeviceRuntime`。
>    - **`suspend` 是真的**（5.2 起），因为 `DevicePlugin.probe()` 是 `suspend` 且选型真的会调它；
>    - **没有 `AppSettings` / `Context`**：那会让纯契约层依赖 `:core:common` 与 Android，
>      单测就得起 Android。配置取值由装配层取好**传值**进来；
>    - **`probeEnv` 是入参、必填无默认值**：采集点在装配层（`ProbeEnvCollector`，见 5.1），
>      不是草案说的「`resolve()` 里采一次」。一份对象贯穿全程，否则各插件打分没有可比性；
>      给默认值就等于允许「忘了传 → 谁都探不到」静默发生；
>    - **日志出口是两个回调 `warn` / `info`**（默认空实现只为单测）：契约层没有 `AppLogger`。
> 5. **`Selection` 是四态枚举、每个值上挂显式 `wire`**（`configured` / `probed` / `default` /
>    `fallback`），下发时用 `wire` 而不是 `name` —— 否则 Kotlin 标识符就成了对外契约。
> 6. **草案下面那三条规则实际是五条互斥路径**，其中两处与草案不同：
>    - 配置填了但两种 id 都匹配不上 → `FALLBACK` + WARN（草案只说「默认插件 + WARN」，
>      没区分 `FALLBACK` 与 `DEFAULT`）；这条路径**刻意不发 probe** —— 填错了就该看见那条 WARN；
>    - probe 全部 ≤ 0 → `DEFAULT` 且**刻意一行日志都不打**（草案写「默认插件 + WARN」）。
>      三条判据写在 `resolve()` 的 KDoc 里，并有三条单测钉住「不打日志」。
>    - 另外两条草案没写的细则：**并列同分取 `plugins` 声明顺序靠前者 + 一条 WARN**
>      （并列意味着判据不足）、**某插件 `probe()` 抛异常按 0 分 + WARN 且整轮继续**
>      （`CancellationException` 例外，原样抛出）。
> 7. **`configuredId` 同时认两种 id**：先 plugin id（`zte-f50`）、再 profile id（`zte-goform`），
>    命中后者打 **INFO 而不是 WARN**（用户填的值是对的）。草案只写了「按 id 取」。
> 8. **「中间控制层」实际是两个类，草案只画了一个（2026-09-25 批 A1 ~ A3 补的）。**
>    `DeviceRuntime` 只管**选型**（选哪个插件 / 哪两份 profile / selection 四态），
>    「上层怎么消费设备能力」由新增的 `DeviceHub` + `DeviceAdapter` 承担
>    （`:core:device-spi` 的 `adapter/` 子包，成员清单见 §3.2 标废框第 8 条）。
>    - 草案把 `transport` 挂在 `DeviceRuntime` 上、上层各自持有具体客户端；现在是
>      **装配层造一份 transport → `plugin.createAdapter(transport, commandProfile, profile)` →
>      `DeviceHub(adapter)`**，route / collector / scheduler / controller 只认 `DeviceHub`
>      或**某一个具体域接口**（`SystemController` / `ActionExecutorImpl` / `NetworkController`
>      收的就是域接口而不是整个 hub —— 权限不该比需要的大）。
>    - `DeviceHub` 现在**只有转发**，它自己的 KDoc 明写这一点，免得有人以为它还干了别的；
>      仍要它的理由是「上层只依赖这一个类型，换 adapter 时改装配层一处、上层零改动」。
>    - 与草案第 3 条不冲突：能力门禁**仍在 route 层**，`DeviceAdapter.capabilities` 是同源快照，
>      六个域字段**一律非空**，「不支持」只由 capabilities 表达（两份判据早晚分叉）。
>    - `DeviceRuntime` 本身在批 40 ~ 批 50 里**一个成员都没动**（2026-09-25 复核：
>      三个构造参数 + `commandProfile` + `Selection`、`resolve()` 的 7 个参数与上面第 4 条逐字一致）。



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
（撰写时 1325 行；**2026-09-23 实测 2083 行 / 125 条 `@Test`**）。

### 任务

> 状态口径（**2026-09-22 批 13（0.4b，`680fbae`）执行完后复核**）：
> 每一条都按当天的代码现查现写，不照抄上一轮。行号一律以符号名为准。
> 阶段 0 的**代码工作已全部落地**，但**没有一条可以标 `[x]`**：0.4b 的第 1、2 层已过、
> **第 3 层（接口快照）未验**（无真机）→ 按 §14.3 最后一段与 §14.6 的纪律标 `[~]`。
> 剩下的是 **0.5 的两项（已裁决推阶段 2）**、**0.8 的装配层与真机那一半**，
> 以及一份**真机验证待办清单** —— 见 §9 末尾新增的「**阶段 0 收尾盘点**」。

- `[x]` 0.1 `SettingKey` 补齐写命令（`core/device-schema/.../DeviceProfile.kt`）
  → **2026-09-23 实测 `SettingKey` 现为 29 项**（18 原有 + 11 新增；`USB_MODE` 已删、`WIFI_SSID`/`WIFI_PASSPHRASE`
  最终合并为一个 `WIFI_AP_CONFIG`，理由写在该 key 的 KDoc 里）。
  ⚠ 本文档此前一直写 **28** —— 那是批 14/15 新增 `WIFI_BAND`（§15 的 P1-26）**之前**的数字，
  P1-26 结案时漏回填到这里。按 28 去核会得出「多了一个孤立 key」的错误结论
- `[x]` 0.2 `ZteGoformProfile` 为新增 key 登记 `WriteSpec`
  → 实测 `writeSpecs` 与 `SettingKey` 逐项对齐（**29 : 29**），无孤立 key
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
  **已裁决推阶段 2 = 任务 2.9**（§15 的 P0-1，按 §3.4 的口径处理）；
  ② 二维码文件名模板 —— 需要 `DeviceProfile` 上新开一个读侧 API 面，
  **归阶段 2 = 任务 2.10**（§15 的 P1-5）
- `[x]` 0.6 `WriteSpec` 加 `retry: RetryPolicy`，现有 18 项显式标 `RETRY_ON_SESSION_LOSS`
  → 实测原 18 项全部显式标注；新增 **11** 项里 `REBOOT` / `SHUTDOWN` / `FACTORY_RESET` /
  `BACKEND_PASSWORD` 为 `NEVER`，其余（含 `MOBILE_DATA.fallback` 独立判定）为 `RETRY_ON_SESSION_LOSS`
- `[x]` 0.7 短信三项走 `smsSpec()`，**不进** `SettingKey`（见 §11.2）
  → **profile 侧**（`2d92e05`：`SmsSpec` 接口 + `ZteSmsSpec` + `ZteGoformProfile.smsSpec()`）
  与**客户端接线**均已完成：`GoformSmsClient` 收非空 `profile` 构造参数（无默认值，口径同 §11.12），
  `sendSms` / `getSmsList` / `getSmsMeta` / `deleteSms` / `markSmsRead` / `verifySend` 六处走 spec，
  goform 侧的 `buildSendParams` / `toUcs2Hex` / `formatSmsTime` / `TAG_SENT` / `TAG_SEND_FAILED` 已删，
  `GoformSmsSendParamsTest` 5 → 1 条（只留 `maskNumber` 那条，脱敏是日志规范不属 profile）。
  读时钟上移到调用点（`System.currentTimeMillis()` / `TimeZone.getDefault()` 在 `sendSms` 里）
- `[~]` 0.8 补测试：新增 key 的 encode/validate 逐条断言；`SettingKey` 全覆盖断言
  → 已有：`ZteGoformProfileTest`（**2026-09-23 实测 125 条 `@Test`**；批 10 之后又随 `WIFI_BAND`
  等新增了若干条，此前写的 117 已过期）、`ProfileContractTest`（8）、
  `ZteSmsSpecTest`（23）、`FieldNormalizerTest`（30）、`ZteGoformRawCaptureTest`（2）、
  `GoformSettingWriterDecisionTest`（20）、`GoformWifiApParamsTest`（19）、
  `GoformWritePolicyTest`（15）、`GoformCommandTableGuardTest`（**9**，批 11 新增 8 条、批 13 转型后 9 条）、
  `GoformBase64CharsetTest`（7）、`GoformCodecFormBodyTest`（8）、`GoformSmsSendParamsTest`（1）、
  `GoformWifiBandParamsTest`（7，批 15 新增 —— 批 15 漏记在这张表里，2026-09-22 批 16 补上）、
  `GoformNormalizeAlwaysTest`（**9**，批 16 新增，见 §11.13）。
  `:core:goform:test` 的 `@Test` 合计 **95**（79 → 86 → 95，逐文件数过）。
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
    WIFI_BAND,           // 批 14 真机抓包后才拆出来的第 11 项（原方案没有它）：
                         // switchWiFiChip 不是「开 WiFi」而是「在频段 X 上启用 WiFi」，
                         // 「切频段」与「开 WiFi」是同一条设备命令。来龙去脉见 §15 的 P1-26
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
- **排障开关回归 —— 2026-09-22 已实机执行，结论「通过」**（**0.4b 之后这条从「预防性检查」
  升级为必做项** —— `cmds()` 现在真的走 `commandProfile` 了）：把 `field_normalization_enabled`
  设成 false 重启后台服务，**仪表盘 / 网络 / WiFi 三页仍有数据**，`/api/diagnose` 的
  `device_profile` 报「归一化已关闭」+「生效 profile：无」，app 诊断页的「运行时实际状态」
  显示「已关闭 · 原样透传设备字段」——「关掉归一化不会打瘫只读面」这条不变量在真机上成立。
  **但同一次验证暴露了一个 P0**：唯一失效的功能是**流量限额**（不是显示不对，是静默错数据 +
  不可回滚的落库），已由 `45b86ff` 修掉 —— 详见下面 §11.13 与 §15 的 P1-28。
  **单测替身（0.4a 起有，0.4b 已随 fallback 删除同步改名）**：`GoformCommandTableGuardTest` 的
  「关掉归一化后 `cmds` 仍然非空且等于 `commandProfile` 的登记表」
  +「关掉归一化时 `coverageReport` 不向设备发查询」两条，把「关掉归一化不会打瘫只读面」
  与「关掉归一化不会开始真打设备」都钉住了（0.4a 时前者叫「…仍然返回 fallback」，
  **保护的不变量一字未变**）。这两条替身**没有也不可能**发现上面那个 P0 ——
  它们钉的是「命令还发得出去」，而坏掉的是「发回来的值怎么解」，
  所以 `45b86ff` 另补了 `GoformNormalizeAlwaysTest`（9 例，见 §11.13）
- 真机回归（无真机则标 `[!]`）**共 15 条**（条数口径见 §14.4）：重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 /
  **手动拨号与挂断**（`PPP_DIAL`，与「开关移动数据」是不同的 key、不同的失败路径，必须分开点）/
  切连接模式 / 改 SSID / 改密码 / 改功率 / **开关 WiFi**（`WIFI_ENABLED`，开与关走两条不同命令，
  两个方向都要点）/ **切 WiFi 频段**（`WIFI_BAND`，批 15 新增，连带要验的三件事见 §9 收尾盘点第 4 项）/
  发短信 / 删短信 / 标已读，逐条点一遍



### 影响面

`core/device-schema` 2 个文件、`core/goform` 5 个文件、`ComponentFactory.kt` 1 处（给 SmsClient 传 profile）。
**不碰** route / app / web。

---

## 5. 阶段 1 — 传输层接口化

**为什么**：`GoformSettingWriter.kt:45` 与 6 个客户端吃具体类 `GoformClient`，
这是「第二个协议实现进不来」的直接原因。

### 开工前的实测更正（2026-09-23，必读）

原任务清单假设「1.3 = 把 7 处构造参数的类型换掉」。**实测不成立** ——
6 个客户端 + writer 用到的 `GoformClient` 成员**比现有 `GoformGateway` 接口多 7 个，且全是 `internal`**：

- `ensureBaseUrlResolved()`（`GoformClient.kt:136`）← `GoformWifiClient:71`
- `httpGet(url): HttpResponse`（`:183`）← `GoformWifiClient:107`、`GoformSmsClient:122/280`
- `parseJson(body): JsonObject?`（`:191`）← `GoformSmsClient:131/283`
- `isAuthFailure(body)`（`:416`）← `GoformSmsClient:130/282`
- `goformPostIdempotent(params): GoformWriteResult`（`:469`）← `GoformSettingWriter:83`
- `isGoformSuccess(body)`（`:801`）← `GoformSettingWriter:85`、`GoformSmsClient:198/263/268`
- `sha256Hex(input)`（`:859`）← `GoformDeviceClient:85/86`

Kotlin 的接口成员**不能标 `internal`**，所以「把这 7 个塞进公开的 `DeviceTransport`」等于
**把 7 个 module 内部成员一次性提成跨模块公开 API**（还会把 Ktor `HttpResponse` 与
`GoformWriteResult` 拖进公开契约）。这不是行为变更，但是一次不小的对外面扩大，
而且跨模块**没有任何人需要它们**。

**2026-09-23 裁决过程（两轮，第一轮被 Kotlin 的可见性规则推翻，两轮都留着）。**

**第一轮（方案 A）**：`DeviceTransport` public 保持 14 个方法不变，
新增 **`internal interface GoformTransport : DeviceTransport`** 容纳那 7 个成员，客户端与 writer 吃它 ——
公开面零扩大。**落地时编译不过**，逐字原文 6 条：

```
e: GoformDeviceClient.kt:26:5 'public' function exposes its 'internal' parameter type 'GoformTransport'.
（GoformNetworkClient / GoformSignalClient / GoformSimClient / GoformSmsClient / GoformWifiClient 同）
```

- 挡住方案 A 的**不是**「internal 接口继承 public 接口」（那是合法的，别把结论记错），
  而是「**public 构造函数的参数类型不能是 internal**」这条检查。
- 这 6 个客户端必须是 public：`ComponentGraph.NetworkGraph` 与 `RouteContext` 跨模块持有它们，
  构造点在 `:core` 的 `ComponentFactory.buildNetworkGraph`。所以类、构造函数都不能标 `internal`。
- `GoformSettingWriter` 是 `internal class`，它**没有**这个问题。
- 唯一能编过的绕法是 `@Suppress("EXPOSED_PARAMETER_TYPE")`，而编译器对它明确不背书：
  `This code uses error suppression ... the compiler behavior is UNSPECIFIED and WILL NOT BE PRESERVED`。
  **这种东西不许留在主线上**（子代理第一版就是这么过的编译，已撤掉）。

**第二轮（方案 B，最终形态，用户裁决）**：

- **`DeviceTransport`（public）** = 现有 `GoformGateway` 的 **14 个方法**按下面的映射改名。
  跨模块面（`RouteContext.goformClient` / `NetworkDeps` / `ComponentGraph.NetworkGraph`）**一个不多一个不少**。
- **`GoformTransport`（public，但只许 `core/goform` 内部用）`: DeviceTransport`** = 追加那 7 个成员。
  6 个客户端与 writer 的构造参数吃**这个**类型。
- 「不对外」从**语言约束**降级成**纪律**，纪律由守门测试 `GoformTransportVisibilityGuardTest` 钉住：
  扫 `core` 目录下所有 `.kt`，断言 **`core/goform` 之外零引用 `GoformTransport`**
  （另有一条反向自检，防止符号改名后上一条变成恒真式空转）。**挡不住编译器的，就挡在 CI 上。**
- **退出条件**：阶段 2 把这 6 个客户端连同传输层收进插件 module、它们整体变 `internal` 之后，
  `GoformTransport` 才能真正收窄成 `internal` —— 那时删掉守门测试与它 KDoc 里的这段说明。
- 命名诚实：那 7 个成员里 `isAuthFailure` / `isSuccess` / `writeIdempotent` / `sha256Hex`
  **本来就是 goform 协议事实**，所以第二层叫 `GoformTransport` 而不是硬起一个中立名字。

### 任务

- `[~]` 1.1 `GoformGateway` → `DeviceTransport`（public，14 个方法按映射改名）
  → **代码已落地**（文件改名 `GoformGateway.kt` → `DeviceTransport.kt`，方法数实测 14）
- `[~]` 1.2 新增 `interface GoformTransport : DeviceTransport`，把上面 7 个 `internal` 成员
  提成接口成员（含 `isSuccess`）
  → **代码已落地**，但**不是原定的 `internal`**：按第二轮裁决改成 public + 守门测试（理由见上一节）
- `[~]` 1.3 `GoformSettingWriter` + 6 个客户端的构造参数由 `GoformClient` 换成 `GoformTransport`
  → **代码已落地**（7 处，`core/goform/src/main` 里作为类型标注的 `GoformClient` 实测 0 次）
- `[x]` 1.4 登录握手（LOGIN_MULTI_USER / LOGIN / LOGOUT / LD / RD / AD 签名）收敛为 transport 内部实现细节
  → **2026-09-23 核查：代码层面本来就成立，没有一行需要搬**。
  `ensureSession`（`GoformClient.kt:220`）/ `validateSession`（`:345`）/ `computeAd`（`:753`）/
  `storeCookie` / `markLoggedOut` / `isReachable` / `AUTH_FAILURE_RESULTS` **全是 `private`**；
  LD / LOGIN / LOGIN_MULTI_USER / RD 的字面量只出现在这些私有方法体里。
  接口上只有**会话生命周期动作**（`ensureLogin` / `invalidateSession` / `resetLogin` /
  `logout` / `updateGoformPassword`）——换协议时这五个动作还在、取值方式全变。
  本项实际做的是**把这条边界写进 `DeviceTransport` 的 KDoc**（原来没写，下一个人看不出这是刻意的），
  并写明唯一的刻意例外：`sha256Hex` 上了 `GoformTransport` 那一层，
  因为改后台密码要与登录握手共用同一份哈希真源（`DeviceProfile.kt` 的 `BACKEND_PASSWORD` 注释）。
  **无代码行为改动，所以不受第 3/4 层约束，直接标 `[x]`。**
- `[~]` 1.5 `ComponentFactory.buildNetworkGraph` 改为「从工厂函数拿 transport」的形状
  → **已落地**：新增 `private fun createTransport(settings, gatewayIp)`，
  `buildNetworkGraph` 里那 6 行 `GoformClient(...)` 收成一行调用（`val goformIp` 临时变量一并消掉）。
  ⚠ **返回类型按实测改成具体类 `GoformClient`，不是原方案写的 `DeviceTransport`**：
  6 个客户端的构造参数是 `GoformTransport`（多 7 个协议成员），用 `DeviceTransport` 接编译不过；
  而 `ComponentFactory` 在 `:core`，**不许**直接写 `GoformTransport` 这个类型名（守门测试拦）。
  所以这个文件保持它既有的角色 —— **唯一知道具体实现类型的地方**。
  阶段 2 换成 `plugin.createTransport()` 时只改这一个函数体。
- `[~]` 1.6 **删掉 `GoformClientGateway`**（19 行的 `GoformGateway by client` 空壳）。
  §11.12 定的是「阶段 1 要么用它做委托点、要么删掉」——`GoformClient` 直接实现接口，
  它就是死代码。**2026-09-23 已 grep 确认全仓零引用**（只有它自己与几处注释提到），按 §13.4 的纪律可以删
  → **已删除**

> **为什么四个 `[~]` 不是 `[x]`**：第 1、2 层已过（见下面「验收」），
> **第 3、4 层无真机未验**。按 §0 的例外 2 与 §14.6，这不阻塞继续做 1.4 / 1.5，
> 但阶段 1 在真机接口快照对比之前不能标 `[x]`，相关 commit 也不合主线。

### 怎么做

1.1 的改名映射（语义不变，只是去掉协议专有名词）：

- `baseUrl()` → 保留
- `base64Decode(input)` → `decodeDeviceText(input)`（GBK/UTF-8 兼容是 ZTE 的事实，接口上只说「解码设备文本」）
- `query(cmds)` / `querySingle(cmd)` / `goformPost(params)` → `read(keys)` / `readOne(key)` / `write(params)`
- ~~`isGoformSuccess(body)`（现在是 `GoformClient` 的公开方法，**不在接口上**）→ 上接口，名字 `isSuccess(body)`~~
  → **两处更正（2026-09-23 实测 + 裁决）**：① 它是 **`internal`** 不是公开方法（`GoformClient.kt:801`）；
  ② 按上面的方案 A，它上的是 **`GoformTransport`（internal 那层）**，不是 public 的 `DeviceTransport`
  —— 跨模块没人用它，上公开面是白扩大。名字仍叫 `isSuccess(body)`
- QoS 三个方法（`adjustQoS` / `getQosStatus` / `setQosEnabled`）保留原名，它们是我们自己的概念
- **`updateGoformPassword(newPwd)` 不改名**：`GoformGateway.kt:48` 已经写明「符号名沿用 `goform*`：
  那是配置键的一部分」。持久化键 `goform_password`（`AppSettings.kt:28`）不能改（改了丢用户配置），
  符号名跟着键名走是刻意的。原方案想把它改成 `updateCredential` —— 撤销该项。

1.2 那 7 个成员的命名（**只有一个改名，其余保留** —— 保留是因为它们表达的动作本身不带协议味，
改名只会让「这一行原来是哪个方法」变难核对）：

- `goformPostIdempotent(params)` → **`writeIdempotent(params)`**（与 `write` 对齐，返回类型 `GoformWriteResult` 不动）
- `isGoformSuccess(body)` → **`isSuccess(body)`**
- `ensureBaseUrlResolved()` / `httpGet(url)` / `parseJson(body)` / `isAuthFailure(body)` / `sha256Hex(input)`
  → **一律保留原名**。特别是 `sha256Hex` 不许改名也不许在别处复制：
  `DeviceProfile.kt:333` 与 `GoformDeviceClient.kt:71` 都记了「与登录握手共用同一份真源」这条结论



1.4 注意：`ensureLogin` / `invalidateSession` / `resetLogin` 的**语义注释必须整段搬过去** ——
`GoformGateway.kt:35-41` 记录了「invalidateSession 不能递增退避计数」这个踩过的坑，
换实现的人看不到这段注释就会重新踩一次。

1.5 先加一个临时工厂函数（阶段 2 会被 `DevicePlugin.createTransport` 取代）：

```kotlin
// ComponentFactory.kt —— 2026-09-23 实际落地形态（返回类型按实测改成具体类，理由见任务 1.5）
private fun createTransport(settings: AppSettings, gatewayIp: String): GoformClient =
    GoformClient(deviceIp = settings.goformIp.ifBlank { gatewayIp },
                 port = settings.goformPort, password = settings.goformPassword)
```

### 验收

- 第 1 层：`gradlew :core:goform:compileDebugKotlin` + `:core:api:compileDebugKotlin` +
  `:core:compileDebugKotlin`（与用户并行改仓库时只编自己动过的 module，见 §14.1）；
  阶段收尾再 `gradlew :core:assembleBenchmark`
- 第 2 层：`:core:goform:test` 与 `:core:device-schema:test` 全绿。
  这一阶段**不许改任何既有测试断言** —— 断言变了就说明不是纯改名。
  **唯一允许新增的是守门测试**（本轮加了 `GoformTransportVisibilityGuardTest` 2 条，
  `:core:goform:test` 95 → **97**；它不断言任何设备行为，只钉「`GoformTransport` 不跨模块」这条纪律）
- **跨模块面零扩大**：`DeviceTransport` 的方法数 **== 14**（与改名前的 `GoformGateway` 逐个对应）；
  `GoformTransport` 虽然是 public，但 **`core/goform` 之外零引用**，由守门测试钉住
  （原判据写的是「那 7 个成员只出现在 internal 接口上」，随方案 B 作废）
- **具体类彻底退出客户端**：`core/goform/src/main` 里 `: GoformClient`（作为类型标注）出现 **0 次**
  —— 只剩 `GoformClient.kt` 自己的 `class GoformClient` 声明与 `ComponentFactory` 的 `new`
  - ⚠ **原判据「`grep GoformClient` 在 `core/goform` 之外只剩 `ComponentFactory.kt` 一处」现在就已经成立**
    （跨模块早在 2026-08-29 走完接口了，见 `GoformGateway.kt:19-21`），所以它验不出任何东西，作废
- **不许留 `@Suppress("EXPOSED_PARAMETER_TYPE")`**（或任何编译器声明「行为不保证」的抑制）
- `GoformClientGateway.kt` 已删除，全仓 `grep GoformClientGateway` 只剩 §5 / §11.12 的文档说明
- 行为零变化：diff 里不该出现新的 URL / 参数 / 判据；`git diff` 里除改名与类型标注外**不应有逻辑行变动**
- **第 3 / 4 层（真机）**：接口快照按 §14.3 的四条判据比对 §16 基线；写操作按 §14.4 的 15 条点一遍。
  **与阶段 0 的四项真机待办合并成一次窗口做**（§0 例外 2 的前提：不验完不标 `[x]`、不合主线）

### 实测结论（2026-09-23，1.1 / 1.2 / 1.3 / 1.6 落地后）

- 第 1 层 ✓：`:core:goform:compileDebugKotlin` / `:core:api:compileDebugKotlin` /
  `:core:compileDebugKotlin` 全通过（未跑全量 `assembleBenchmark` —— 用户正在并行改
  `core/api` 的媒体与更新相关文件，见 §13.3 最后一条）
- 第 2 层 ✓：`:core:goform:test` **97/97**（含守门测试 2 条，`skipped=0` —— 逐个 XML 核过，
  不是 up-to-date 跳过）、`:core:device-schema:test` **188/188**
- 第 3、4 层 ✗（无真机）
- **`ComponentFactory.kt` 的那行注释改动没有进本轮 commit**：该文件同时有用户并行改的
  `FileRoutes` / `MediaExclusionStore` 装配代码，按「只 stage 自己改过的文件」的纪律整文件跳过 ——
  注释留在工作区，由用户那一轮带走。所以 1.5 做的时候要顺手确认这一行是否还在

### 影响面

- `core/goform`：`GoformGateway.kt`（改名 → `DeviceTransport.kt`）、新增 `GoformTransport`、
  `GoformClient.kt`（`override` 修饰符 + 7 个成员从 `internal` 变接口实现）、
  6 个客户端 + `GoformSettingWriter` 的构造参数、删 `GoformClientGateway.kt` —— 约 10 个文件
- `core/src` `ComponentFactory.kt`：1 处（`createTransport()`）
- **`core/api` 会碰 2 行**（原文写「不碰 route」，**2026-09-23 实测更正**）：
  跨模块调用点实测只有 6 处，其中 `DeviceRoutes.kt:168` `goformClient.query(cmds)` →`read`、
  `:199` `goformClient.goformPost(...)` → `write` 必须跟着改名；
  另外 4 处是 `updateGoformPassword`（3 处，不改名）与 `BackendService.kt:1134` 的 `close()`（不改名）。
  这 2 行正是 §11.5 的裸命令端点，**只改方法名，不动语义与守门开关**
- 不碰 app / web

---

## 6. 阶段 2 — 插件聚合根与中间控制层

### 开工前的三个决定（2026-09-23，动手前先读）

**① `DeviceTransport` 必须上移到 `core/device-spi`。**
它现在在 `core/goform`（阶段 1 落地的位置）。但 `DevicePlugin.createTransport(cfg): DeviceTransport`
在 device-spi 上 —— 如果接口留在 goform，device-spi 就得依赖 `:core:goform`，
那正是 2.1 明令禁止的「SPI 不能知道任何具体协议」。所以：
`DeviceTransport.kt` 移到 device-spi（包名换 `com.ufi_axis_core.devicespi`），
`core/goform` 改为依赖 device-spi 并**用 `api(...)` 而不是 `implementation(...)`**
（`ComponentGraph` / `RouteContext` 的字段类型就是它，`implementation` 会让跨模块看不见）。
`GoformTransport` 留在 `core/goform` —— 它是协议专有的那一层。

**② `PlatformAdapter` 不在阶段 2 落地，连同 `AtTransport` 上移一起归阶段 4。**
原 2.1 写的是「device-spi 依赖 `:core:collector`（要 `AtTransport`）」——**这条会成环**：
`collector` 依赖 `goform`（`settings.gradle.kts:33`），而决定 ① 之后 `goform` 依赖 `device-spi`，
再让 `device-spi` 依赖 `collector` 就是 `goform → device-spi → collector → goform`。
§11.7 本来就定了「阶段 4 把 `AtTransport` 上移到 device-spi」，那一步做完环才不存在。
所以阶段 2 的 SPI **只落不需要 `AtTransport` 的部分**；`DevicePlugin` 到阶段 4 再加 `platform()`
（那时只有 1 个插件，加方法的成本可以忽略）。

**③ `capabilities` 字段也不在阶段 2 加。**
`Capability` 定在 `core/contract` 是阶段 3 的 3.1，阶段 2 提前引它等于把冻结区的定义提前，
违反「冻结区宁可晚定」。阶段 2 的 `DevicePlugin` 先不带这个字段，阶段 3 再加。

### 批次划分（阶段 2 不要一口气做完）

- **批 A（2.1 + 2.2 + 2.3 + 2.4 的一半）**：建两个 module、`DeviceTransport` 上移、
  落 `DevicePlugin` / `TransportConfig` / `DeviceTuning` / `ProbeEnv`、写 `ZteF50Plugin` 与
  `PluginRegistry`。**不接线**（`ComponentFactory` 一行不动），可编译、可单测。
- **批 B（2.4 的另一半 + 2.5 + 2.8）**：`DeviceRuntime.resolve()` 接进 `ComponentFactory`，
  删 `resolveDeviceProfile()`，`/api/diagnose` 补 `plugin_id` / `selection`。这一批才动装配。
- **批 C（2.6 + 2.7）**：`DeviceProfiles` 标废弃 + `PluginContractTest`。
- **批 D（2.9 / 2.10 / 2.11）**：三条从阶段 0 推过来的欠账，各自独立 commit。

### 任务


- `[~]` 2.1 新建 module `core/device-spi`（Android library）+ SPI 文件
  → **批 A 已落地**：`DeviceTransport`（从 `core/goform` 上移）+ `DevicePlugin` / `TransportConfig` /
  `DeviceTuning` / `ProbeEnv`（含 `BuildInfo`）。`PlatformAdapter` 按决定 ② 归阶段 4。
  依赖实测形态：`api(:core:contract)` + `api(:core:device-schema)`（`profile()` 的返回类型）
  + `api(libs.kotlinx.serialization.json)`（`read()` / `readOne()` 的返回类型）
  + `implementation(libs.kotlinx.coroutines.android)`；**`ktor.client.core` 整行不要**
  —— 最终签名里没有任何 Ktor 类型（`HttpResponse` 留在 `core/goform` 的 `GoformTransport`）。
  判据一句话：**只有公开签名里出现的类型才需要进依赖、才需要是 `api`**。
  `core/goform` 加 `api(project(":core:device-spi"))` —— 必须 `api`，
  因为 `ComponentGraph.NetworkGraph.goformClient` / `RouteContext.goformClient` 的字段类型就是它
- `[~]` 2.2 新建 module `core/device-plugins`，package `zte/f50/`
  → **批 A 已落地**（`api(:core:device-spi)` + `implementation(:core:device-schema)`
  + `implementation(:core:goform)`）
- `[~]` 2.3 写 `ZteF50Plugin`：组装现有零件（profile / transport / platform 暂时直接 new 现有实现）
  → **批 A 已落地**，**没有新增任何设备知识**：`profile()` → `ZteGoformProfile`、
  `createTransport(cfg)` → `GoformClient`、`tuning()` → 四处实测常量原值（75/85/3/90_000/5）。
  `probe()` 的判据只用两项**有实测依据**的：`goformLdReachable`（60 分，唯一准入条件）、
  `cpuInfoPlatform` 含 `sprd`/`spreadtrum`/`unisoc`（+20，只加分 —— 同平台别家设备也会命中）。
  **`Build.*` 特征匹配刻意不写**：全仓找不到 F50 的 `BRAND`/`MODEL`/`DEVICE`/`MANUFACTURER`
  实测取值（`SystemController` 只是原样下发、`CrashHandler` 只是打进日志，没有一处记过真机取值），
  没依据就编字符串匹配只会得到一条恒不命中的死代码、或者更糟——命中别家设备
- `[~]` 2.4 写 `PluginRegistry`（编译期 `listOf(ZteF50Plugin)`）+ `DeviceRuntime.resolve()`
  → **批 A 落 `PluginRegistry`、批 B 落 `DeviceRuntime`**（`plugin` / `profile`（可空）/
  `commandProfile`（非空）/ `selection` 四态 + 10 条单测）。
  三处与原方案不同、**动手前必须知道**：
  - **签名里没有 `AppSettings` / `Context`**（§3.3 原写 `resolve(settings, ctx)`）：那会让 device-spi
    依赖 `:core:common` 与 Android，单测就得起 Android。取值由 `ComponentFactory` 取好传进来。
  - **不是 `suspend`**：本批不做 probe、零 I/O。阶段 5 接 probe 时再改签名（`build()` 本身是 suspend，
    不构成结构问题），§11.8 的时间预算那时才用得上。
  - **`configuredId` 必须同时认 plugin id 与 profile id**：实测 `ZteF50Plugin.id = "zte-f50"`，
    而 `ZteGoformProfile.id = "zte-goform"`，配置键 `device_profile_id` 存的是**后者**、还会原样
    下发到 `/api/diagnose` 的 `configured`。只按 plugin id 匹配 = 已填 `zte-goform` 的部署突然
    「认不出 → 回落」，那是行为变更。匹配顺序：先 plugin id，再 profile id（命中后者打 INFO 说明）
- `[~]` 2.5 `ComponentFactory` 只留 `DeviceRuntime.resolve()` 一处，删掉 `resolveDeviceProfile()`
  → **批 B 已落地**：`resolveDeviceProfile()` 与阶段 1.5 那个临时 `createTransport()` 双双删除，
  传输层改由 `runtime.plugin.createTransport(TransportConfig(...))` 造（三个取值逐字一致）；
  6 个客户端继续吃 `runtime.profile`（**可空**，排障开关语义不变），
  `GoformSmsClient` 与 `DataScheduler` 那两处 `?: DeviceProfiles.DEFAULT` 改成 `runtime.commandProfile`。
  ⚠ 装配处多了一次**向下转型**（`transport as? GoformClient ?: error(...)`）：
  `createTransport()` 返回 `DeviceTransport`（14 个方法），而 6 个客户端要的是 goform 内部那一层
  （多 7 个成员），且本文件不许写那个类型名。用 `as?` + `error()` 而不是硬 `as` ——
  失败的真实含义是「选中的插件不是 goform 系」，写成一句话比 ClassCastException 有用。
  **阶段 5/6 把 6 个客户端也收进插件后这次转型才能消掉。**
  → **2026-09-25 批 A3（`25c4419`）：这次转型已经消失，但不是在「阶段 5/6」消的。**
  六个客户端的 `new` 与那句 `as? GoformClient` 一起移进了插件
  （`ZteF50Plugin.createAdapter` → `ZteGoformAdapter` 的 5 参数公开次构造函数），
  装配层现在只认 `DeviceTransport` 与 `DeviceAdapter` 两个契约类型
  （`ComponentFactory` 的非注释代码里 `Goform` 出现 0 次，由 `UpperLayerGoformFreeGuardTest` 钉住）。
  transport 仍由装配层造并**只造一份**递进去。
  ⚠ 本条**仍是 `[~]` 不是 `[x]`**：阶段 2 验收最后一条「真机冒烟（四个页面数据与改造前一致）」未做。
- `[x]` 2.6 `DeviceProfiles`（旧注册表）标为 `@Deprecated` 并让它委托给 `PluginRegistry`，
  避免 `SignalCollector` 等直接 import `ZteGoformProfile` 的地方一次性全改
  → **批 C 已落地**（`6e5e377`）：只标了 `@Deprecated(WARNING)`，**实现体一行未动** ——
  「委托 `PluginRegistry`」在实测里是**反向依赖**（`DeviceProfiles` 在 device-schema，
  `PluginRegistry` 在 device-plugins）、编译不过，理由与「什么时候才能真正删」写进了它的 KDoc。
  ⚠ 顺带查清一件事：标废弃**并不能**提醒这条欠账的收尾 —— `SignalCollector` / `DataScheduler`
  import 的是 `ZteGoformProfile` 而不是 `DeviceProfiles`，零 warning、没有任何编译期提醒盯着
  （这两处现在登记在 `docs/device-adaptation-guide.md`「接第二台设备前必须先清」那一节）。
  → **2026-09-25 批 40（`a48e1b9`）：那两处 `= ZteGoformProfile` 默认参数已删**，
  两个构造参数都成了非空无默认值，main 源集里这个符号只剩 `ZteF50Plugin` 一处 import
  （见 §15「批 40 ~ 批 50 已还完的欠账」第 1 条）。**`DeviceProfiles` 本身仍在、仍只标 `@Deprecated`**，
  所以本条的正文（为什么当时只标废弃、什么时候才能真正删）一字未改、仍然适用。
- `[x]` 2.7 守门测试 `PluginContractTest`
  → **批 C 已落地**（`6e5e377`）：`core/device-plugins/src/test/.../PluginContractTest.kt`，
  落地时 8 条（id/displayName 唯一非空、`DEFAULT` 是 `ALL` 里那个对象、`byId` 往返、
  `profile()` 多次调用同一对象、`tuning()` 值域自洽、`goformLdReachable=false` 必返 0、probe 纯函数）。
  之后随阶段长过：阶段 3 补了 capability 侧断言，批 L 加了 `READ_ONLY_CAPABILITIES` 白名单
  （`BATTERY` 是纯读侧能力）+「两表交集必须为空」。§7 裁决 ① 作废了原方案里那条**双向**断言
  （29 个 key 不可能都归到 10 个域里），现在是单向 —— 防的是「定了不用」。
  → **2026-09-25 批 A3（`25c4419`）又补 2 条**，判据都是「插件交付的 adapter 与插件自身同源」：
  `createAdapter` 交出的 adapter 的 `id` / `capabilities` 必须与插件一致、六个域字段逐个非空
  （防 lateinit 之类），后者**传 `normalizeProfile = null`**，顺带钉住「归一化关掉时 adapter 照样造得出来」。
  2026-09-25 数过：`PluginContractTest` 现在 **13 个 `@Test`**。
  → **同一轮另起了一个守门测试、不在本条里**：批 A4（`29682c2`）的 `UpperLayerGoformFreeGuardTest`
  钉的是「上层五处 main 源码里不许出现含 `Goform` 的标识符」（逐文件精确相等 + 自检 ≥100 文件），
  与 `PluginContractTest` 是两件事。它的 allowlist 随 C1 / C2 / A3 收缩，2026-09-25 数过剩 **13 个文件**
  （全是 A ~ D 组的命名遗留，E 组「真正的跨模块耦合」已清零）。

- `[~]` 2.8 `/api/diagnose` 的 `device_profile` 块补 `plugin_id` 与 `selection`
  → **批 B2 已落地（只新增、不修正）**。`plugin_id` = 选中插件 id（**恒非空** —— 与 `active` 不同源：
  关掉归一化时 `active` 会空、`plugin_id` 照样有值）；`selection` 值域**一次定稳**四个：
  `configured` / `probed` / `default` / `fallback`（`probed` 当前不会出现，但值域现在就是最终值域，
  免得阶段 5 再扩一次对外值域）。
  - 枚举 → 对外字符串的映射做在 `Selection.wire` 上，**不在下发处 `name.lowercase()`** ——
    那等于把 Kotlin 标识符变成对外契约，改名即静默改线上值域。`DeviceRuntimeTest` 加了
    一条守门断言把四个 wire 取值写死 + 值域大小 == 4（10 条 → **11 条**）
  - 递到 `DataHub` 的是**两个不可变 String**，不是整个 `DeviceRuntime` 对象
    （否则 `core/api` 要依赖 `:core:device-spi`，还把选型对象的生命周期扩散到数据层）
  - ⚠ **已知不一致，用户裁决刻意不修**：填了 plugin id（`zte-f50`）时选型是成功的
    （`selection=configured`），但 `active` 是 profile id、`configured != active` → `status` 仍判 `fallback`。
    两端 UI 现在都按 `status` 显示「型号填错」。**看选型结果以 `selection` 为准**；
    `status` 的显示口径连同 UI 一起归**阶段 3**
- `[x]` 2.9 **清 `NetworkController` 的设备知识 + 频段全集三份拷贝**（阶段 0 的 0.5 裁决推过来的，§15 的 **P0-1**）
  → `GoformNetworkClient.kt:29/31` 的 `LTE_ALL_BANDS` / `NR_ALL_BANDS`、
  `NetworkController.kt:122/127` 的跨模块直读、`core/contract/Enums.kt:145-146` 的第三份零引用拷贝。
  按 §3.4 判：若收进统一抽象仍要拉跨模块依赖，就允许各插件独立持有一份，**不为「统一」造新耦合**。
  ⚠ 「空串 = 不发限制」与「空串 = 下发全频段」是两种对外语义，换过来是**行为变更**，不是搬运
  → **批 D1 已落地**（`d85096b`）：`DeviceProfile` 新增 `lteAllBandsMask()` / `nrAllBandsMask()`
  （默认 `null`，两个方法而不是带 RAT 枚举的一个 —— 不为这件小事往冻结区加类型），
  `ZteGoformProfile` 返回原常量的逐字取值；`GoformNetworkClient` 的两个 companion 常量删除、
  改从非空 `commandProfile` 取，`lteAllBands()` / `nrAllBands()` 是**唯一**的 null 折叠 + WARN 归属地；
  `NetworkController` 改调这两个方法（跨模块直读消失，只剩 `NetworkController.kt:132` 一行注释记着旧形态）；
  `core/contract/Enums.kt` 那第三份零引用拷贝**直接删、不先标废弃**（没有消费者就没有迁移窗口要给）。
  **对外语义一字未变**：`unlockAllBands()` 发的还是 `lte_band_lock=1,3,5,8,34,38,39,40,41` /
  `nr_band_lock=1,5,8,28,41,78`，空串仍在调用方那侧表示「不发限制」。
  ⚠ 验收的**真机那一半（频段锁回归 + 值域拒绝）仍未做**，见 §9 批 25 的第 3/4 层。

- `[~]` 2.10 **二维码文件名模板进 profile 读侧 API 面**（0.5 未做的那一半，§15 的 **P1-5**）
  → **批 D2 已落地**：`DeviceProfile` 新增 `qrCodeFileNames(chip, ssidIndex): List<String> = emptyList()`，
  `ZteGoformProfile` 实现（两个候选：`{chip}_ssid{n}_qrcode_wifikey` 与兜底 `chip1_ssid1_qrcode_wifikey`，
  仍用同一个 `linkedSetOf` 去重），`GoformWifiClient` 改从非空 `commandProfile` 取。
  `_qrcode_wifikey` 字面量在 `core/goform` **实测 0 次**（唯一真源是 `ZteGoformProfile.QR_CODE_FILE_SUFFIX`）。
  - `ssidIndex` 是 **1-based**（三处代码交叉确认：`WifiRoutes` 的 `?: 1`、客户端缺省值 `= 1`、
    设备扁平字段 `wifi_chip1_ssid1_*`，设备侧不存在 `ssid0`）
  - 契约写明**去重责任在实现方**：调用方按顺序原样逐个发、不做过滤
    （客户端不再加 `distinct()` —— 那等于把设备事实的一部分又留回客户端）；
    device-schema 侧有一条 `distinct` 断言防这个
  - 空列表 = **该设备不支持从后台取二维码图**，行为与「所有候选都取不到」逐行相同
    （一条文件请求都不发、`lastQrCodeFailure` 为空串、原 WARN 照打、返回 null），**不抛异常**
  - **代码已全部落地（第 1、2 层 ✓）**；仍标 `[~]` 而不是 `[x]` 的唯一原因是第 3/4 层未验 ——
    解锁条件是下面「验收」里那条真机项：`GoformWifiClient` 里不再出现 `_qrcode_wifikey`（已成立），
    **扫码直连在真机上仍能连上**（未验）。

- `[~]` 2.11 **`checkDeviceEvents()` 容忍 `station_list` 的两种形态**（§15 的 **P1-29**，2026-09-23 裁决）
  → **2026-09-24 已落地**。容错解析放在 `core/common` 的 `StationListShape.kt`
  （`parseStationList()` + `Available` / `Missing` / `Malformed` 三态），`DataScheduler` 改用它。
  - **放 `core/common` 而不是 `DataScheduler` 的 private 函数**：`core/scheduler` **没有测试源集**，
    放进去就测不到，而新建 `src/test` 要动它的 `build.gradle.kts`；
    `core/scheduler` 已经依赖 `core/common`、后者已有 kotlinx-serialization 与 junit，
    **零新增依赖边、零构建改动**。这条容错也不是设备知识（是消费端的取值纪律），所以不进 device-schema。
  - **返回值刻意不是 `JsonArray?`**：`null` 既能表示「缺失」又容易被下游手滑写成 `?: emptyList()`，
    而 `Missing` / `Malformed` **不携带列表** —— 调用方无从构造「当前 MAC 集合」，
    「解析失败被当成所有设备都离开了」这条误报路径**在类型上不可表达**。
  - **解析失败时保留基线**（不再像原来的 catch 那样 `knownStations = null`）：
    清基线虽然也不误报离开，但「归一化关着 + 固件双重编码」是**每轮都失败**的场景，
    清了就等于设备事件功能**永久静默停摆**（P1-29 的原始症状）；保留基线则解析一恢复
    就能和失败前的集合做差、把这段时间真实的上下线补报出来。
  - **`Missing` 静默、只有 `Malformed` 打 WARN**：字段缺失/空串是正常形态，
    打 WARN 会在默认部署下每分钟刷一条无用日志（批 27 之后 WARN 始终落地）。
  - WARN 文案**逐字固定、不拼任何设备数据**，好让 `AppLogger.repeatGate`
    按「级别+tag+完整消息」折叠成 1 条/分钟。顺带治掉一个日志膨胀隐患：
    原来那条 `checkDeviceEvents failed: ${e.message}` 在这个场景下的 message 是
    `Element <整个双重编码字符串> is not a JsonArray`，把设备数据拼进了消息、基数无界、折叠完全失效。
  - 归一化**开着**时逐路不变：真数组 → `Available(原数组)` → 走原路径，
    `current` 构造、首轮建基线、两个 for 的判定全部未改；字段缺失原来是 `?: return`（静默、保留基线），
    现在是 `Missing → return`（同）。**唯一变的是原本「抛异常 → catch → 清基线」那条失败路径。**
  - 校验：`:core:common:test` **176/176**（171 + 新增 5）、`:core:scheduler` 与 `:core` 编译通过。
  - **代码已全部落地（第 1、2 层 ✓）**；仍标 `[~]` 的唯一原因是第 4 层未验 ——
    解锁条件是下面「验收」里那条：关掉 `field_normalization_enabled` 后接入/断开一台 WiFi 设备
    仍能产生事件、解不出来时日志里必须有 WARN（批 27 之后 WARN 才会落地，所以这条现在才验得了）。


### 怎么做

> ⛔ **下面这段「2.1 Gradle」里「`device-spi` 依赖 `:core:collector`（要 `AtTransport`）」那句已作废，
> 照它写会直接成环。** 原文保留（它是当时的判断，批 A 的决定 ② 正是从这里被推翻的），
> 真实情况如下 —— 2026-09-25 读 `core/device-spi/build.gradle.kts` 与
> `core/collector/build.gradle.kts` 核过：
>
> - **`AtTransport` 已在阶段 4 批 F 从 `:core:collector` 的 `at/` 包上移到 `:core:device-spi`。**
>   所以不存在「device-spi 为了 `AtTransport` 去依赖 collector」这回事，方向恰好相反。
> - **`device-spi` 不许依赖 `:core:goform` / `:core:collector` / `:core:controller`** ——
>   这条已经写成 `build.gradle.kts` 的「硬性约束 1」：`goform` 反过来依赖 device-spi
>   （`DeviceTransport` 在这里），`collector` 又依赖 `goform`，把它们加进来立刻成环。
>   需要具体协议的东西一律放 `:core:device-plugins`。
> - **`device-spi` 的实测依赖只有四条**：`api(project(":core:contract"))`、
>   `api(project(":core:device-schema"))`、`api(libs.kotlinx.serialization.json)`、
>   `implementation(libs.kotlinx.coroutines.android)`（另有两条 `testImplementation`：
>   `junit` 与 `kotlinx.coroutines.android` —— 后者因为上面那条是 `implementation`、不传到测试 classpath，
>   而 `resolve()` 自 5.2 起是 `suspend`，单测要 `runBlocking`）。
>   **刻意不依赖 `libs.ktor.client.core`**：最终签名里没有任何 Ktor 类型。
>   判据一句话：**只有公开签名里出现的类型才需要进依赖、才需要是 `api`**。
> - **反过来是 `collector` 依赖 `device-spi`**：`core/collector/build.gradle.kts` 里有
>   `implementation(project(":core:device-spi"))`，注释写明这一行**不加也能编**
>   （`goform` 是用 `api` 声明 device-spi 的，传递过来就看得见 `AtTransport`），
>   显式写是为了有约束力 —— 直接使用者不该把依赖寄生在「goform 恰好用了 api」这个事实上。
> - 下面那张「模块依赖方向」图里的那条 `↑ collector` 现在要读成
>   **collector → device-spi**（collector 是消费方），不是「collector 提供 `AtTransport`」。

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
  ⚠ **后半句（「有 `WriteSpec` 但没声明 capability 也算失败」）已作废，见 §7 开头的裁决 ①**：
  Capability 是**功能域**，与 `SettingKey` 不要求一一对应（实测 11 个域 vs 29 个 key），
  照这条写出来的双向断言一上线必红。落地的 `PluginContractTest` 是**单向**的 ——
  只断言「声明了就必须找得到写侧证据（`WriteSpec` 或 `smsSpec`）」，防的是「定了不用」；
  纯读侧能力（`BATTERY`）走 `READ_ONLY_CAPABILITIES` 白名单显式豁免。
  前半句仍然有效。
- `probe()` 不做 I/O：用一个禁止文件访问的 `ProbeEnv` 假对象调用，并断言不抛
- `capabilities` 里的每一项在 `Capability` 枚举里都有文档注释（否则前端不知道怎么翻译成文案）

> 2026-09-25 补：这四条的判据**没有被批 40 ~ 批 50 推翻**（第 2 条后半句作废是 §7 裁决 ① 的事，与本轮无关）。
> 本轮只是在 `PluginContractTest` 上**加了第 5 类断言**（批 A3：adapter 与插件同源 + 六个域字段非空，
> 见上面 2.7 的任务注记），并**另建**了一个管辖范围不同的守门测试
> （批 A4 的 `UpperLayerGoformFreeGuardTest`，扫的是上层五处 main 源码的标识符，不在本清单里）。

### 验收

- `gradlew :core:assembleBenchmark` 通过
- `grep -rn 'ZteGoformProfile\|DeviceProfiles' core --include=*.kt` 只出现在
  `core/device-plugins` 与 `core/device-schema`（过渡期允许 `SignalCollector` 一处，记在这里）
  → **2026-09-25 实测已满足**：main 源集里 `import ... ZteGoformProfile` 只剩
  `core/device-plugins/.../ZteF50Plugin.kt` 一处，`DeviceProfiles` 的 import 在
  `core/device-schema` 之外为 0；`SignalCollector` 那处**例外已不需要**（批 40 删掉了
  `= ZteGoformProfile` 默认参数，它现在只在 KDoc 里提这个名字）。其余命中全在测试源集。
- `PluginContractTest` 全绿
- **2.9**：`core/contract/Enums.kt:145-146` 的零引用拷贝已删，`NetworkController` 不再直读
  `GoformNetworkClient` 的常量；`unlockAllBands` / `lockBands` 的**对外语义未变**
  （空串仍是「不发限制」，除非另有裁决）—— 这条按 §14.4 的「值域拒绝」与真机频段锁回归各点一遍
- **2.10**：`GoformWifiClient` 里不再出现 `_qrcode_wifikey` 字面量，扫码直连在真机上仍能连上
- **2.11**：`device_events_enabled` 打开、`field_normalization_enabled` **设成 false** 并重启后台服务，
  接入 / 断开一台 WiFi 设备仍能产生事件；若仍解不出来，日志里**必须有一行 WARN**（不许静默）。
  验完把两个开关都改回原值并再重启一次（排查性改动要还原）
- `GET /api/diagnose` 返回里能看到 `plugin_id=zte-f50` 与 `selection=default`
- 真机冒烟：启动后台服务，仪表盘 / 网络 / WiFi / 短信四个页面数据与改造前一致

---

## 7. 阶段 3 — 能力集

**与阶段 4 的关系**（2026-09-21 修正）：原方案写「可与阶段 4 并行」，那是错的 ——
`BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 这三项的取值来自 `PlatformAdapter`（阶段 4 才有）。
拆法：**3A（设备侧能力，不依赖阶段 4）可以先做**，**3B（平台侧能力）必须等阶段 4**。

**这一阶段收益最直接**：现在设备不支持的功能要等请求打到设备才失败。

### 开工前的三个裁决（2026-09-24，用户拍板）

**① Capability 是「功能域」，与 `SettingKey` 不要求一一对应。**
10 个 Capability vs 29 个 `SettingKey` —— **只对这 10 个域做门禁，其余写操作照旧**（没有 capability 就不拦）。
所以 §6 的 2.7 原来那句「有 `WriteSpec` 但没声明 capability 也算失败」**作废**：
照它写出来的双向断言一上线必红（29 个 key 不可能都归到 10 个域里）。
守门测试改成**单向**：声明了某个 Capability，就必须能找到对应的 `WriteSpec` 或 route —— 防的是「定了不用」。

**② 能力集走新端点 `GET /api/device/capabilities`**，不并入 `/api/diagnose`：
能力集是 **UI 渲染开关时要读的业务数据**，不该让前端为了画一个开关去拉排障端点。

**③ 本阶段只做 core 侧（3.1 / 3.2 / 3.3 / 3.4 / 3.7）。**
3.5 / 3.6（app / web 置灰）与 3.8（两端镜像一致性）**等用户那批 UI 改动落地后单独一批** ——
他正在并行改 `app/**` 与 `web/**`，现在动同一批文件必然撞车。
3.9（3B）仍然等阶段 4。

### 任务


- `[~]` 3.1 `Capability` 定在 **`core/contract`**（冻结区，理由见 §11.4）
  → **2026-09-24 已落地**：`Capabilities.kt`，10 项 + `wire`（映射写在枚举上，不在下发处 `name.lowercase()`）
  + `fromWire()`。同批给 `ErrorCode` 补了 **`NOT_SUPPORTED`** —— **实测它此前根本不存在**
  （全仓只有两处注释提到「route 应回 NOT_SUPPORTED」），所以 501 这条出口是这次才真正建起来的
- `[~]` 3.2 `ZteF50Plugin.capabilities` 按真机实测填写（3A 部分）
  → `DevicePlugin` 加 `val capabilities: Set<Capability>`（批 A 刻意没加的两个成员之一，现在只剩 `platform()`），
  `ZteF50Plugin` 填满 10 项 —— **每一项都有「WriteSpec（或 smsSpec）+ 写 route」双证据**，见 §9 批 29 的对照表
- `[~]` 3.3 route 层统一门禁：`CapabilityMissing` → 501 / `ErrorCode.NOT_SUPPORTED`
  → 落在 `HttpServer` 的 `StatusPages`（全站唯一异常出口），**没有在 handler 里撒 if**；
  **12 处门禁 / 10 个域 / 4 个文件**。能力集经 `DataHub` 的一个只读 `Set<Capability>` 快照进 route，
  口径同批 B2（**不把 `DeviceRuntime` / `DevicePlugin` 塞进 `DataHub` 或 `RouteContext`**）
- `[~]` 3.4 新端点 `GET /api/device/capabilities`（**二选一已裁决：新端点，不并入 `/api/diagnose`**）
  → 返回 `{"plugin_id": "...", "capabilities": ["sms", ...]}`。**数组而不是 map** ——
  map 形态下旧客户端分不出「这是新增的能力」还是「不支持」。`plugin_id` 与 `/api/diagnose` 同源同值
- `[~]` 3.5 app 侧消费：不支持的开关置灰 + 一句原因 —— **等用户那批 UI 改动落地后单独一批**
  → **批 O（`ba08293`）落了机制与大部分入口，还没打完**：判定收在
  `app/viewmodel/.../state/DeviceCapabilityState.kt` 的 `supports()`，
  实现是 `!loaded || capability in supported` —— **`loaded=false` 时恒为 true**，
  也就是拉不到能力集时**降级成「全部支持」**。方向只能是这一个：反过来做的话一次网络抖动
  就能把整页灰掉，等于把「通道不可用」（503，可重试）冒充成「设备不支持」（501，不可恢复）。
  拉取失败只记日志、不写错误 state；换设备 / 换地址时复位成 UNKNOWN 而不是空集合
  （空集合会被判成「全部不支持」，重连那一瞬间整页全灰）。
  置灰**不改 `checked`**（开关仍显示设备真实状态），文案只说「这台设备不支持 X」——
  不写「未开启」「权限不足」，那两句会把用户引向一场没有出口的寻找。
  已灰：`fota` / `samba`（网络功能页）、`performance_mode`（设备控制页）、`network_mode`（网络制式页）、
  `cell_lock`（锁站页三个写按钮 + 页顶说明卡）= **4 个域 5 处控件**；「刷新」这类读侧入口不动。
  **未完：`sms` / `traffic_limit` / `band_lock` 三个域**（commit 说明写的原因是与并行改动撞文件）——
  所以这条是 `[~]` 不是 `[x]`。校验：`DeviceCapabilityStateTest` 7 条；
  置灰分支在 F50 上**永远不会触发**（它声明全部 10 项），真机只能验「全部支持时 UI 与改动前一致」。
  → **2026-09-25 文档回填轮复核**：git log 里有一条**上一轮漏记的** commit `dcc64f9`
  （「批 O 补完 sms / traffic_limit / band_lock 三域的置灰」），所以上面「未完」那三个域
  **很可能已经补完了**。本轮范围是批 40 ~ 批 50（全在 core 侧），**没有核 app 侧代码、
  因此不动这条的状态标记**，留给下一轮按 `dcc64f9` 的实际内容回填。
  已知仍未做的一处：首页 hero 卡那个限额入口没前置置灰（`DashboardScreen` 的 `UfiDataLimitDialog`），
  已登记为 §15 的 **P1-44**。
- `[ ]` 3.6 web 侧消费：同上（`web/src/api/contract.ts` 加镜像类型）—— 同上
  → 2026-09-25 实测**仍未做**：`web/**` 全仓搜不到 `/api/device/capabilities` 的任何消费
  （`AboutPanel.vue` 只读 `device_profile` 块里的 `profile_id`）。
  → 文档回填轮复核（同日）：`web/src` 下 `capabilities` 这个词**一次都没出现**，结论不变；
  已登记为 §15 的 **P1-45**（连带 3.8 只剩 web 那一半）。
- `[~]` 3.7 守门测试：`Capability` 每一项都至少被一处 route 或一处 UI 消费（防止定了不用）
  → 判据是「**每个 Capability 至少被一处 route 门禁引用**」+「引用总数 ≥ 项数 − 豁免数」，
  **刻意不设上限**（域内可以有多个写入口，计数管不住语义、还会在加入口时逼人改测试）；
  豁免表当前为空、且自带上限断言（防它变垃圾桶）。另有 `Capability.wire` 取值写死的断言
- `[ ]` 3.8 两端镜像一致性测试：contract 的枚举 wire 名与 `contract.ts` 的字符串联合类型逐项对齐
  —— **跟 3.5 / 3.6 同批**（要动 web）
  → 2026-09-25 实测**仍未做**。批 O 走的是另一条路：app 侧**直接复用 core 的 `Capability` 枚举**
  （`app:data` 已 `api(:core:contract)`），不在 app 再抄一份 wire 名镜像 ——
  所以「app 与 contract 对齐」这一半**结构上不可能错**，这条任务剩下的只有 web 那一半。
- `[~]` 3.9 **3B**（阶段 4 之后）：`BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 由 `PlatformAdapter` 推导
  → **三项的结论各不相同，整条按「一项落地、两项裁决不做」收尾**：
  - `BATTERY` **已落地**（批 L 加枚举项 + 批 M 定最终形态），但**不由 `PlatformAdapter` 推导** ——
    `readBattery()` 确定不进那个接口（理由见 §3.2 标废框第 2 条）。驱动方式是**插件静态声明**
    （`ZteF50Plugin` 不声明 `BATTERY`）+ 装配层算一次布尔递给采集侧：`battery` map 多一个
    `supported` 键、无电池机型跳过入库与本地告警。判据是「这台设备有没有电池」这条**设备事实**，
    不是一次真实读取 —— F50 读得到恒为 50 的假值，任何「读一次看能不能读到」的设计在它上面都会判错。
  - `ROOT_SHELL` / `AT_CHANNEL` **裁决不做**（批 L）：它们是**运行时状态**不是设备事实，
    塞进静态能力集就是又一个假开关；现状已由 `/api/at/status` 的 `connected` 与 shell 的 root 上报
    实时表达。经过记在 `Capability` 的文件头。
  - 所以 `Capability` 实测是 **11 项**（10 个写侧域 + `BATTERY` 这个纯读侧域），
    `/api/device/capabilities` 在 F50 上仍返回 10 项。


### 怎么做

**3.1 第一批只定这 10 个**（都能在 F50 上明确验证，且已有对应 route；其余等有第二台设备再加 ——
冻结区只增不改，宁可晚定）：

```
SMS, SIM_SLOT_SWITCH, BAND_LOCK, CELL_LOCK, NETWORK_MODE,
SAMBA, USB_DEBUG, FOTA, PERFORMANCE_MODE, TRAFFIC_LIMIT
```

> ⚠ **上面这 10 个是当时的草案清单；实际落地为 11 项。** 2026-09-25 数过
> `core/contract/.../Capabilities.kt` 的枚举值 = **11**（`CapabilityWireTest` 有一条
> 「值域大小固定为 11」把它写死）。第 11 项是 **`BATTERY("battery")`**，批 L 追加、批 M 定型：
> 它是**纯读侧能力**，**不参与 route 门禁**（没有对应的写 route，`CapabilityGateTest` 把它列进
> exempt、`PluginContractTest` 把它列进 `READ_ONLY_CAPABILITIES` 白名单），
> 驱动的是「无电池机型跳过入库与本地告警 + `battery` map 下发 `supported`」。
> ⚠ 另外**值域 11 项 ≠ 端点下发 11 个**：`/api/device/capabilities` 只含当前插件**声明了**的项，
> F50 不声明 `BATTERY`，所以那个数组在 F50 上仍是 **10** 个。
> 上面的清单不改（它是草案），要看当前值域请看代码。


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

### 阶段 4 开工前的实测与三条裁决（2026-09-24）

**开工前做了一次 very thorough 盘点，三个发现直接改了这一阶段的做法。**

**① `AtTransport` 上移是零成本的**：接口 36 行、**零 import、零 Android 类型、零 `Context`**
（`suspend` 只要 stdlib），搬到 `core/device-spi` **不需要新增任何依赖**，也不成环
（`collector` 其实已经能看到 device-spi —— 经 `goform` 的 `api` 传递）。
`ATChannel`（策略层：全局互斥、500ms 最小间隔、指数退避、20 次熔断）**留在 collector**，
只有 `ServiceCallAtExecutor`（唯一实现）跟着 `SprdPlatform` 走。
另核实：`detectPlatform()` 的结果**确实只用于上报**（4 个读取点全是 `/api/at/*` 与仪表盘），
不参与任何选型 —— 所以 4.6 不是「把枚举接过去」，而是「把读 `/proc/cpuinfo` 这一步上移、枚举退化成 adapter 内部细节」。
⚠ `ZteF50Plugin.probe()` 已经自己抄了一份 marker 列表（还多了 `unisoc`），**这是第二份判据**，4.6 要合掉。

**② 4.5 不是搬运，`DeviceTuning` 的字段名本身有问题（裁决 A）。**
实测 `thermalWarnC/CriticalC` 撞了**三套完全不同**的阈值：

- **下载限速** 75/85（`DownloadManager.Config.throttleTempWarn/Critical`，Float，改 aria2 并发与限速，
  还有一个 `+10` 的第 4 档 95°C `forcePauseAll`）
- **采集降频/熔断** 70/80（`AppSettings.monitorThermalWarnC/CriticalC`，**Int 毫摄氏度**，
  调 ShellQoS/GoformQoS 许可、拉长 cache TTL、`delay` 暂停采集）
- **用户告警** 65/75（`AlertEngine.AlertConfig.temperatureWarning/Critical`，Double，**带 3°C 回差**，
  写 alert_records + 推送）

三者的动作、类型、温度单位、可配路径、有无回差全不同 —— **是三件事，不是一件事的三套阈值**。
⚠ **最容易踩的坑**：`DeviceTuning.thermalWarnC = 75f` 与 `AlertEngine.temperatureCritical = 75.0`
**数值相同、语义相反**。谁看到「两边都是 75」就接线，会静默改掉告警行为，**单测不会红**。

裁决：**字段改名说实话** —— `downloadThrottleWarnC` / `downloadThrottleCriticalC`，
并补上 `downloadThrottleForcePauseOffsetC = 10`（那个第 4 档也是实测出来的设备知识，原来没有任何常量承载它）。
**采集降频与用户告警两套不进 `DeviceTuning`**：它们是**用户可配的策略**，不是设备事实。

**③ `rootShellPermits` 从 `DeviceTuning` 删掉（裁决 A）。**
实测它记错了适用范围：用户默认是 **3**（`ShellQoS.DEFAULT_QOS_SHELL_MAX`），
一旦用户写过任何一次配置，生效值就是 3 —— 5 只在「从没写过」时成立。
它是 **QoS 配置默认值，不是设备事实**，放在插件里只会让人以为它说算。

**④ 4.2 降级为文档任务（裁决 A）。**
实测提权只有**一条**实际路径：**ADB 自连 localhost:5555（uid 2000）**，
`ShellExecutor.executeAsRoot()` 全走它，回落是普通 `sh -c`（无特权）。
`su -c` 已彻底移除（只剩几处过期注释）。**Samba `root preexec` 那条：部署与 60s 保活都在跑，
但执行入口没人调用 —— 是死代码。**
所以「`SambaPreexecStrategy` + `AdbOnlyStrategy`」是**为一条不存在的路径造抽象**，不做。
改为：把「`hasRootAccess()` 的真实语义是『ADB 通道可用』而不是 uid=0」写进文档，
Samba 那条登记成待定项（见 §15 的 P1-38）。

**⑤ 4.4 的环怎么破（2026-09-24 定，批 G 执行）：`restartNetworkStack` 收一个执行器参数。**
批 F 落地后发现：`SprdPlatform.restartNetworkStack()` 要真干活就得能发 AT 命令，
而唯一的执行通道 `ATChannel` 在 `core/collector`，方向是 `ATChannel → SprdPlatform` ——
让 adapter 反过来持有 `ATChannel` 是把**策略层**（限流 / 退避 / 熔断）塞进**平台实现**，倒置。
所以把 §3.2 骨架里的无参签名改成：

```kotlin
suspend fun restartNetworkStack(at: suspend (String) -> String?): Boolean
```

adapter 只提供「**这台设备怎么重启网络栈**」的知识（发哪几条命令、什么顺序、中间等多久、怎么判成功），
**执行通道由调用方注入**（`NetworkController` 本来就持有 `ATChannel`）。
收益：`device-plugins` 不需要依赖 `collector`，也不引入可变状态或 setter；
这个函数还因此变得可单测（传一个假执行器即可）。

### 阶段 4 任务



### 阶段 4 — 平台适配层

- `[x]` 4.1 `PlatformAdapter` 实现 `SprdPlatform`：收 `ServiceCallAtExecutor` 的选型逻辑
  → **批 F（`1c04048`）已落地**：`AtTransport` 整文件上移到 `:core:device-spi`（零 import、零 Android
  类型，不新增依赖也不成环），`SprdPlatform` + `ServiceCallAtExecutor` 落在
  `core/device-plugins/.../platform/sprd/`（**不放 `zte/f50/`** —— 类里没有一个字节是 ZTE 知识，
  别家 Unisoc 设备要原样复用）。`ATChannel` 留在 collector（策略层），`init()` 改成接收注入的
  `List<AtTransport>`。`name = "SPREADTRUM"` 而不是 `"sprd"`：那个取值现在就在 `/api/at/platform`
  下发，填小写等于一次静默的对外 JSON 变更。
- `[-]` 4.2 提权策略 `PrivilegeStrategy`：`SambaPreexecStrategy`（F50）+ `AdbOnlyStrategy`（兜底）
  → **放弃（§8 裁决 ④，降级为文档任务）**：实测提权只有一条实际路径 —— ADB 自连
  `localhost:5555`（uid 2000），`ShellExecutor.executeAsRoot()` 全走它，回落是无特权的 `sh -c`，
  `su -c` 已彻底移除；Samba `root preexec` 那条部署与保活都在跑但**执行入口没人调用，是死代码**。
  为一条不存在的路径造策略接口，只会让人以为这里真有选择。`PlatformAdapter` 因此没有
  `privilegeEscalation()`；Samba 那条登记为 §15 的 **P1-38**，真接上了再谈。
- `[~]` 4.3 传感器读法（温度 / 电池 / CPU）从 `SystemCollector` / `DownloadManager` 抽到 adapter
  → **三类读数的结论不同，只有温度真的进了 adapter**：
  - **温度 ✓**：批 H（`6c22710`）把 `DownloadManager.readMaxTemp()` 的读法逐字搬进
    `SprdPlatform.readTemperature()`（唯一差别是读不到返 `null` 而非 `0f`，契约要求）；
    批 I（`0384f83`）`DataScheduler.readMaxCpuTemp()` 也改成调它 + `celsiusToMilliC` 换算。
    **判据侧（下载限速 / 采集降频熔断 / 温度告警）统一到「全热区最大值」一个口径**；
    **上报侧刻意不动**（`SystemCollector` 的 zone0 + 全热区列表、`QoSRoutes` 的 zone0）——
    那是给人看的读数，语义不同（4.3 结论已写进 `PlatformAdapter.readTemperature` 的 KDoc）。
    批 I 顺带修掉一个**既存缺陷**：原实现只有一层外层 try，任一热区 `readText` 抛异常就整轮退化成
    0、误判「最凉」→ 高温时不降频、不清缓存、不告警；Unisoc 上确有 0400 root:root 的热区。
  - **电池 ✗（裁决不做）**：不进 `PlatformAdapter`，改走能力声明 + 采集侧跳过，见 3.9 与 §3.2 标废框。
  - **CPU ✗（未做，也没有裁决）**：2026-09-25 实测 `SystemCollector.readCpuUsage()` 与
    `DownloadManager.readCpuUsage()` **仍各自读一份 `/proc/stat`**，`PlatformAdapter` 里没有任何
    CPU 成员。要么补一批、要么照电池那样给它一个明确裁决 —— 现在是悬空状态，所以这条是 `[~]`。
- `[x]` 4.4 `restartNetworkStack()` 收 `AT+SFUN=5/4`（`NetworkController.kt:183`）
  → **批 G（`c637785`）已落地**：签名按 §8 的 ⑤ 收执行器
  （`suspend fun restartNetworkStack(at: suspend (String) -> String?): Boolean`），
  `SprdPlatform` 里那段**逐字搬迁**（命令、`delay(500)`、`delay(2000)`、`contains("OK")` 判据、
  三条 INFO 与两条 WARN 文案全未改），`NetworkController` 退化成一行委派。
  **两样刻意没搬**：互斥锁留在 `NetworkController`（`platform(ctx)` 每次调用新建实例，
  锁放 adapter 等于没锁）、5000ms 超时留在调用方（超时属通道策略）。
  单测 `SprdPlatformTest` 10 条用假执行器 + `runTest` 虚拟时钟把 500/2000 断到精确值。
  真机判据（`POST /api/network/band` 锁频段后 `network_restarted` 那一位）**未验**。
- `[x]` 4.5 `DeviceTuning` 替换四处散落的实测常量
  → **五个字段全部有消费点了**（另两处按裁决 ② 不进 tuning）：
  - **`DownloadManager` ✓**（批 H）：首次创建配置时两个阈值取自 tuning、`migrateConfig()` 的抬升
    **目标值**改成 tuning 的值、第 4 档的 `+10` 换成 `downloadThrottleForcePauseOffsetC`；
    **已有配置一个字节都不被 tuning 覆盖**。地板判据 70f/80f 仍是字面量（tuning 没有承载它，§15 的 **P1-40**）。
    新增 `DownloadConfigTuningTest` 8 例，tuning 故意填 77/88/12 而不是 75/85/10 ——
    否则「接线没接上」也能通过。
  - **`AppSettings.monitorThermal*`（采集降频）与 `AlertEngine` 的阈值 65/75（用户告警）✗**：
    按 §8 裁决 ② **不进** `DeviceTuning` —— 它们是**用户可配的策略**，不是设备事实。
    `rootShellPermits` 也已按裁决 ③ 从 `DeviceTuning` 删除。
    注意这条说的是**阈值**；下面那条回差带宽是另一回事。
  - **`thermalJitterC` ✓**（2026-09-25 收尾轮）：`AlertEngine` 新增构造参数
    `temperatureHysteresisC`（`Double`，默认 `DEFAULT_TEMP_HYSTERESIS_C = 3.0`），
    `ComponentFactory` 从 `tuning.thermalJitterC.toDouble()` 递进去。
    **刻意只递一个 Double 而不是整个 `DeviceTuning`**：`:core:alert` 不该认识插件层，
    更要紧的是 tuning 里还躺着 `downloadThrottleWarnC = 75f` 而本类
    `temperatureCritical` 默认也是 75.0 —— 把整包递进去就给「看到两边都是 75 就接线」留了口子。
    验证：`AlertEngineDedupTest` 新增 1 例（宽带宽 20°C + 默认带宽 3°C 的阴性对照），
    并做过**反向对照**：把接线改回读常量，这一例立刻红（8 tests / 1 failure）。
  - **`bootGraceMs` ✓**（同轮）：`DataScheduler` 新增构造参数 `bootGraceDeviceDefaultMs`，
    优先级变成「用户配置 > 插件实测值 > `BOOT_GRACE_DEFAULT_MS`」。
    同样只递一个 `Long`（那个类里还有采集降频的 70/80，与 tuning 的 75/85 撞名）。
    ⚠ **这一条没有单测**：`DataScheduler` 要真造得起来得凑齐十几个依赖 + `SystemClock`，
    收益不抵成本。风险面是一行属性读取，但「没测」这件事要明写在这里。
  - F50 的两个值（3f / 90_000L）与被替换的常量数值完全相同，**所以本次接线对现有行为零影响** ——
    这既是安全性，也是隐患：不写上面那条阴性对照，接线被删掉也不会有任何测试变红。
- `[x]` 4.6 `ATChannel.detectPlatform()` 的结果接到 `DevicePlugin.probe()` 上（它现在只用于上报）
  → **批 J（`a3d3658`）已落地，但形状与原方案不同**：不是「把枚举接过去」，而是
  **把读 `/proc/cpuinfo` 这一步上移**（改从注入的 `ProbeEnv.cpuInfoPlatform` 派生），
  marker 判据合成一份放在 `:core:device-spi` 的 `CpuInfoPlatform`（纯常量 + 纯函数、零 I/O）——
  **不放 `platform/sprd/`**：第二个消费者 `ATChannel`（`:core:collector`）看不见 device-plugins，
  放那儿就只能继续留两份判据，而那正是 4.6 要消掉的。`Platform` 枚举三个取值与映射顺序一字未动
  （**没有**改成直接取 `adapter.name` —— 那会让高通设备也被报成 SPREADTRUM）。
  ⚠ **一处对外取值变化**：取并集后 marker 是 `[sprd, spreadtrum, unisoc]`，
  所以 cpuinfo 里只有 `unisoc` 的机型上 `/api/at/platform` 从 `UNKNOWN` 变 `SPREADTRUM`
  （在用的 F50 不受影响，已在 §16 基线加了比对提示）。


注意：4.3 要保留 `SystemCollector.kt:232` 记录的结论（**不要硬编码**
`/sys/class/power_supply/battery`，要遍历），同时修掉 `DownloadManager.kt:566` 违背该结论的那处硬编码。

**验收**（2026-09-25 按批 H / 批 I / 批 L / 批 M 的实际结果改写 —— 原文写的是
「温度 / 电池 / CPU 读数与改造前逐项一致（同一台机器对比 `/api/system/*` 的返回）」，
**那三项里有两项已经不成立**）：

- **温度**：判据侧（下载限速 / 采集降频熔断 / 温度告警）改成调 `PlatformAdapter.readTemperature()`，
  口径统一为**全热区最大值**；上报侧（`SystemCollector` 的 zone0 + 全热区列表、`QoSRoutes` 的 zone0）
  **未动**。所以「逐项一致」对上报侧成立；判据侧是**刻意的行为变更**（批 I 的四点语义差异 +
  修掉一个既存的温控失效缺陷），比对时不要期望毫秒级逐位相同，要按批 I 记的四点逐条核。
- **电池**：**对外 JSON 不再逐项一致** —— 批 M 给 `battery` map **新增了 `supported` 键**
  （放在首位，F50 = false），`percent` 等读值本身回到了批 L 之前的系统值。
  新增键对旧客户端安全（app 走 `ignoreUnknownKeys`），但「逐项一致」这个判据本身要改成
  「**除新增 `supported` 外其余键逐项一致**」。另外无电池机型现在**跳过入库与本地告警**，
  所以监控页的电池曲线会从某个时间点断掉 —— 那是预期，不是回归。
- **CPU**：**压根没搬**。`SystemCollector.readCpuUsage()` 与 `DownloadManager.readCpuUsage()`
  仍各自读一份 `/proc/stat`，`PlatformAdapter` 里没有任何 CPU 成员 —— 这一项没有 adapter 可验，
  已登记为 §15 的 **P1-42**。
- root shell 仍可用；`AT+SFUN` 重启网络栈仍生效（判据落在 `POST /api/network/band` 的
  `network_restarted`，见 4.4 —— **仍未真机验**）。

### 阶段 5 — probe 选型上线

- `[x]` 5.1 `ProbeEnv` 采集实现（一次，缓存）
  → **批 J（`a3d3658`）已落地**：`ProbeEnvCollector`（`core/device-plugins/.../probe/`）——
  **不放 `:core:device-spi`**：那三件事全是 I/O（其中两件要 Android 与网络），而 device-spi 是纯契约层、
  单测必须不起 Android 就能跑。采集点在**装配层** `ComponentFactory.build()`（选型之前采一次、
  `ATChannel` 吃的是同一个对象），不是 §3.3 原写的 `DeviceRuntime`。
  裸 HTTP 探 `cmd=LD` 照 `ensureLogin` 的实测形状发（不带 Cookie、不带密码 —— 此刻 transport 还没造，
  这正是「鸡生蛋」）；1.5s 用在三处（connect / read / `withTimeoutOrNull`，因为 `HttpURLConnection`
  的阻塞读不响应协程取消，最坏 3s）；超时 / 异常 / 非 200 一律 false，整个采集不抛异常，
  只放 `CancellationException` 过去 —— **绝不影响组件图构造**。
- `[x]` 5.2 各插件实现 `probe()`
  → **批 J + 批 K 已落地**：`ZteF50Plugin.probe()` 的判据是
  `goformLdReachable`（60 分，唯一准入条件，不可达直接返 0）+ 展锐平台（+20，只加分 ——
  同平台别家设备也会命中），`Build.*` 特征匹配**刻意不写**（全仓没有 F50 的实测取值，
  没依据编字符串只会得到死代码或误命中）。批 K 起 `resolve()` 真的调它打分。
- `[x]` 5.3 `deviceProfileId` 配置项从「决定性」降级为「覆盖」，设置页文案改为「自动识别（可手动指定）」
  → **core 侧已落地（批 K，`2ead061`）**：选型顺序是 排障 WARN 最前 → **配置命中 = CONFIGURED
  （配置永远最高优先级，probe 压根不调）** → 配置认不出 = FALLBACK → **配置为空才 probe 打分**，
  最高分 > 0 得 PROBED、全 ≤ 0 回落 default（是 DEFAULT 不是 FALLBACK —— 没人配错）。
  并列同分取 `plugins` 声明顺序靠前那个 + 一条 WARN 说明「并列意味着判据不足」；
  `probe` 抛异常按 0 分、其余插件照常参与；`CancellationException` 单独先 catch 原样抛出。
  ⚠ **「设置页文案」那一半没有对象**：2026-09-25 全仓 grep，`app/**` 与 `web/**` 都**没有**
  `device_profile_id` 的输入项（app 侧零命中，web 只在 `AboutPanel.vue` 读诊断块里的 `profile_id`），
  这个配置只能改配置文件。所以这条剩下的就是 core 侧语义降级，已落地。
- `[x]` 5.4 `/api/diagnose` 的 `selection` 能区分 CONFIGURED / PROBED / DEFAULT / FALLBACK
  → **四态在批 B2 就一次定稳了值域**（`configured` / `probed` / `default` / `fallback`，
  映射写在 `Selection.wire` 上、不在下发处 `name.lowercase()`），**批 K 起 `probed` 真的会出现** ——
  现网零配置部署走 probe、真机上 LD 可达得 60 分，所以 `selection` 从 `default` 变成 `probed`，
  选中的插件与改造前完全相同。反过来说：**`default` 现在意味着「没人认领、正在用兜底插件」**，
  而 `probed` 隐含「探测通得过」（设备离线时 probe 返 0 → 仍是 `default`）。
  ⚠ 顺带修的一处误导：`HttpServer` 里 `/api/diagnose` 旁边那条「probed 在当前版本不会出现」的注释
  已在 `cd67804` 删掉 —— 它离 API 最近、最可能被抄进客户端实现。


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
- 2026-09-22 **批 14 同步**（真机 WiFi 测试后的三轮修复 + 抓包定性）：
  §2 命令清单里 `switchWiFiChip` / `switchWiFiModule` 两条**重新定性**（带频段启用 / 仅用于关）；
  §4 阶段 0 实机验证第 3 项补「入口 2026-09-22 起才存在」与「验完要改回 true 并再重启」；
  §15 **P1-11 部分结案**（`WIFI_AP_CONFIG` 现在有 `validate`，SSID / 口令仍刻意不校验）、
  新增 **P1-26**（`WIFI_BAND` 已在 profile 拆完、三层未接线，附抓包原文与阻塞原因）
  与 **P1-27**（`needs_restart` 不覆盖 `field_normalization_enabled`）。
  对应代码：`86be1f3`（AuthMode 白名单 + `switchWiFiChip` 语义误用注释）、
  `06e7bfa`（web WiFi 表单补加密方式与最大连接数）、`ee145ae`（app WiFi 弹窗同上），
  以及本轮未提交的四批改动（device-schema 的 `WIFI_BAND` 与 `1..10`、
  core 的 `field_normalization_enabled` 读写登记、web / app 的归一化开关与上限收紧）。
- 2026-09-22 **批 15 同步**（频段接线完成）：§2 的 `switchWiFiChip` 条目改成「已接线」；
  §14.4 写操作真机清单 14 → **15 条**（新增「切 WiFi 频段」，并写明要连带验的三件事）；
  §15 **P1-26 结案**（只剩真机验证）。
  API 手册新增 `POST /api/wifi/band` 一节，重写 `/api/wifi/enable` 的警告
  （不再是「固定开在 2.4G」，而是「开在设备当前频段，读不到才退回 chip1」），
  `/api/wifi/config` 的 `chip_index` 标注为「切不了频段，别再发」，
  §「负面清单」里 `POST /api/wifi/chip` 那条改成指向频段端点。
  `node scripts/verify-api-contract.mjs` 五项 P0 全空、P1「负面清单已过期」由 1 → 0。
  对应代码：`0892412`（device-schema）、`6033f91`（core 接线）、`78692b7`（web）、`1c71fa9`（app）。
- 2026-09-22 **批 16 同步**（排障开关实机验证 + 验出的 P0 修复 + WiFi 三个选择控件统一下拉）：
  §4 验收的「排障开关回归」那条标**已实机执行 / 通过**并写清「同一次验证暴露一个 P0」；
  §4 0.8 的测试清单补 `GoformWifiBandParamsTest`（批 15 漏记）与 `GoformNormalizeAlwaysTest`，
  `:core:goform:test` 合计写实为 **95**；§9 追加执行记录批 16；
  §9「阶段 0 收尾盘点」的真机验证清单第 3 项改成**已执行 / 通过**（原文保留 + 结论与 P0 写在下面），
  开头那句「四项一项都没做」改成「第 3 项已过，剩三项」；
  **新增 §11.13**（「按 canonical 重组的派生出口」这个新认识 + 排障开关作用域的准确口径 +
  两处我之前说错的更正）；§15 新增 **P1-28**（那个 P0 的**剩余风险**：真机库里那段时间的
  小时级流量行上下行是反的，**是否清理待用户裁决**，本轮没有动数据库）。
  对应代码：`45b86ff`（core：`GoformFieldMapper` 的 `NORMALIZE_ALWAYS` 豁免 + `DataScheduler`
  两处注释 + 新增 `GoformNormalizeAlwaysTest`）、`b5df249`（app WiFi 弹窗三控件统一 `UfiDropdown`）、
  `8421a19`（web WiFi 弹窗改 `n-select`，并改掉 `GeneralPanel.vue` 里一句写错的影响面文案）。
  `node scripts/verify-api-contract.mjs` 五项 P0 全空、P1「负面清单已过期」= 0（未新增）。
  本轮**不改代码**（上面三个 commit 是前序，本轮只同步文档）、**没有重跑 Gradle**
  （用户在并行改 `app/**` / `web/**`，见 §13.3 最后一条）；`45b86ff` 的
  「`:core:goform:test` 86 → 95 全绿、守门测试仍 9 例」沿用该 commit 的自报结果，
  本轮只核了 `@Test` 计数与代码逻辑。
- 2026-09-23 **批 17 同步（纯文档复审轮，只修「过期数字 / 重复段落 / 清单条数不一致 / 裁决没落成任务」四类）**：
  - **`SettingKey` 28 → 29**（§2.1 表、§4 的 0.1 / 0.2 / 0.6、§9 收尾盘点表）。
    根因是 §15 的 **P1-26 结案时只改了 P1-26 自己，没回填计数处** ——
    批 14/15 新增的 `WIFI_BAND` 一直没进这几个数字。29 : 29 逐项对齐**已按 2026-09-23 实测核过**，
    并在 §4 的 0.1 代码块里把 `WIFI_BAND` 补成第 11 个新增 key（原方案没有它）。
    按 28 去核 0.2 的验收判据会得出「多了一个孤立 key」的错误结论，所以这条不是排版问题。
  - **`ZteGoformProfile` 1396 → 1623 行**、**`ZteGoformProfileTest` 1883 行 / 117 条 → 2083 行 / 125 条**
    （§2.1 表、§4 开头、§4 的 0.8）。同轮复核 `:core:goform:test` 合计 **95** 与
    `:core:device-schema:test` 其余四个文件的条数（8 / 23 / 30 / 2）**均与文档一致，未改**。
  - **§2.2 F 类删掉一行重复**（`/api/diagnose` 四态那句原本连写了两遍）。
  - **真机写操作清单条数统一为 15 条**，并把**唯一真源定在 §14.4**：
    §12 的「12 条」、§13.4 的「12 条」、§14.4 的「14 条」三处改齐，
    §4 验收那条补上批 15 新增的「切 WiFi 频段」并标明总数（此前它只列了 14 项、与 §9 收尾盘点第 4 项对不上）。
  - **阶段 2 任务清单新增 2.9 / 2.10**：把 0.5 裁决推过来的 **P0-1**（频段全集三份拷贝 +
    `NetworkController` 跨模块直读）与 **P1-5**（二维码文件名模板需要新的读侧 API 面）落成正式任务，
    并在 §4 的 0.5、§15 的 P0-1 / P1-5 三处加上双向指引。此前这两项只有裁决、任务清单里没有条目，
    按 2.1~2.8 执行阶段 2 会整条漏掉。
  本轮**不改任何代码**、**没有重跑 Gradle**；所有数字均为 2026-09-23 对当日代码的实测（grep / 逐文件计数）。
  **未处理**（等裁决，不在本轮授权范围）：安装器 `scripts/UFI-AXIS-Core-install-Android/` 那份
  **第二套 goform 实现**（独立 `GoformClient` / `GoformGateway` / `GoformWritePolicy` / `GoformCodec`，
  零 profile 抽象，`RemoteAdbEngine` 也直发 goform）既不在 §1 非目标里、也没有阶段归属 ——
  §15 只用 P1-19 登记了「解码器有第三份」，覆盖不住整个客户端；
  以及 §0「前一阶段没到 `[x]` 不开下一阶段」与「阶段 0 卡点全是无真机」之间的死锁口径；
  以及 §11.13 末尾 `checkDeviceEvents()` / `station_list` 那条**无编号、无阶段归属**的隐患。
- 2026-09-23 **批 18 同步（三项口径裁决落地，纯文档）**：批 17 末尾列的三项「未处理」全部有了裁决，逐条写进正文：
  - **安装器不纳入插件化**（裁决：**整份独立维护、不做任何共享**）→ §1 非目标新增一条，
    写明判据（一次性装机流程、不共享组件图/生命周期）与**明确接受的后果**
    （接新设备时安装器要另写一套，§1 目标的「1~3 个文件」只对后台服务成立；
    解码/判据长期两三份是**刻意重复**）。唯一例外：共享持久化键与对外报文格式仍以 `core/contract`
    与 API 手册为唯一真源。据此 **§15 的 P1-19 结案**（不同步、不合并、不再跟踪），
    并给 **P1-18** 补一句「两份都在主仓 core 内，与 P1-19 无关，仍是欠账」以防两条被混成一件。
  - **「无真机」死锁开例外** → §0 的纪律从「唯一例外是阶段 3 与阶段 4 并行」改成**两条例外**：
    ① 顺手更正了那句与 §7 冲突的旧话（正确口径是 **3A 可并行、3B 必须等阶段 4**）；
    ② 新增「无真机」例外 —— 只卡第 3/4 层且第 1、2 层全绿时允许开下一阶段，
    前提是**该阶段保持 `[~]`、写操作 commit 不合主线、真机待办一条不销**，补验不过按 P0 回滚。
    §14.6 与 §9 收尾盘点的结语同步写上这条，并明确**阶段 1 现在可以开始**。
  - **§11.13 那条尾注登记为 P1-29，归阶段 2** → §15 新增「批 18 新登记（P1-29）」一节
    （事实 / 为什么不走 `NORMALIZE_ALWAYS` / 修法 / 为什么不许在阶段 0/1 顺手改），
    §6 任务清单新增 **2.11** 与对应验收条目，§11.13 的尾注改成指向 P1-29。
  本轮**不改任何代码**、**没有重跑 Gradle**。
  **下一步**：开阶段 1（传输层接口化，§5 的 1.1~1.5）。
- 2026-09-23 **批 19：阶段 1 的 1.1 / 1.2 / 1.3 / 1.6 落地**（子代理实现 + 我复核与收口，未 push）：
  - **开工前先改了 §5**：实测发现 1.3 不是「换 7 处类型」那么简单 ——
    6 个客户端与 writer 用到的 `GoformClient` 成员比 `GoformGateway` **多 7 个且全是 `internal`**
    （`ensureBaseUrlResolved` / `httpGet` / `parseJson` / `isAuthFailure` /
    `goformPostIdempotent` / `isGoformSuccess` / `sha256Hex`）。§5 补了这份清单、两层接口的形状、
    7 个成员的命名（只有 `writeIdempotent` 与 `isSuccess` 改名）、以及新的验收判据
    （原判据「`GoformClient` 在 `core/goform` 之外只剩一处」**现在就已成立**，验不出东西，作废）。
  - **方案 A 落地时被 Kotlin 推翻**：`internal interface` + public 客户端构造函数 →
    `'public' function exposes its 'internal' parameter type`（6 条）。子代理第一版用
    `@Suppress("EXPOSED_PARAMETER_TYPE")` 压掉，而编译器明确声明该抑制**行为不保证**，
    我把它全部撤掉并升级为裁决项。**用户裁决方案 B**：`GoformTransport` 改 public，
    「不对外」由新增守门测试 `GoformTransportVisibilityGuardTest`（2 条）钉住 ——
    扫 `core` 下所有 `.kt`，断言 `core/goform` 之外零引用，外加一条反向自检防恒真式空转。
    退出条件写进了它的 KDoc（阶段 2 客户端整体 `internal` 后收窄并删掉守门测试）。
  - 代码：`GoformGateway.kt` → `DeviceTransport.kt`（14 个方法，`query`/`querySingle`/`goformPost`/
    `base64Decode` → `read`/`readOne`/`write`/`decodeDeviceText`）、新增 `GoformTransport.kt`、
    7 处构造参数换接口、删掉零引用空壳 `GoformClientGateway.kt`、
    跨模块 6 处跟着改名（`RouteContext` 2 处类型 + `ComponentGraph` 1 处 + `DeviceRoutes` 的
    裸命令端点 2 行 —— 只改方法名，守门开关与语义未动）。
    `invalidateSession` 那段「不递增退避计数」的坑注释整段保留。
  - 校验：第 1 层 ✓（三个 module 编译）、第 2 层 ✓（`:core:goform:test` 95 → **97/97**、
    `:core:device-schema:test` **188/188**，逐个 XML 核过 `skipped=0`）、第 3/4 层 ✗（无真机）。
    所以 1.1/1.2/1.3/1.6 标 `[~]` 不是 `[x]`；按 §0 例外 2 继续做 1.4 / 1.5。
  - **`ComponentFactory.kt` 未纳入 commit**：它同时有用户并行改的媒体/文件装配代码，
    按「只 stage 自己改过的文件」整文件跳过（本轮只该文件有一行注释改动，留在工作区）。
  - 登记给后续：`base64Encode` 现在是事实上的死代码（只有注释引用）；
    `GoformClient` 里 `decodeDeviceText` 出现了「实例方法(String) + companion(ByteArray)」同名重载
    （合法重载、行为零变化，已在 KDoc 消歧）；一批注释里仍写着旧符号名（不影响编译）。
    这三项都不在阶段 1 的范围里，谁要动谁单独一轮。
- 2026-09-23 **批 20：阶段 1 的 1.4 / 1.5 收尾**（我自己改，未 push）：
  - **1.4 核查结论是「本来就成立」** —— 握手全链（`ensureSession` / `validateSession` / `computeAd` /
    `storeCookie` / `markLoggedOut` / `AUTH_FAILURE_RESULTS`）**全是 `private`**，
    LD / LOGIN / LOGIN_MULTI_USER / RD 的字面量只在私有方法体里；接口上只有会话生命周期动作。
    所以本项**没搬一行代码**，只把这条边界与唯一例外（`sha256Hex` 与登录握手共用哈希真源）
    写进 `DeviceTransport` 的 KDoc。无行为改动 → 标 `[x]`。
  - **复核抓到一处过期描述**（批 19 留下的）：`DeviceTransport` 的 KDoc 还写着
    「goform 协议成员在 `GoformTransport`（**internal 那一层**）」—— 方案 B 之后它已是 public，
    这句话变成错的。已改成「靠纪律 + 守门测试维持，不是靠语言约束」。
  - **1.5 落地**：`ComponentFactory` 新增 `private fun createTransport(settings, gatewayIp)`，
    `buildNetworkGraph` 里 6 行构造收成一行。**返回类型按实测改成具体类 `GoformClient`**
    （原方案写 `DeviceTransport`，但客户端要的是 `GoformTransport`，用前者接编译不过；
    而本文件又不许写 `GoformTransport` —— 守门测试拦着）。理由整段写进了该函数的 KDoc。
  - 校验：第 1 层 ✓（`:core:goform` / `:core` 编译）、第 2 层 ✓（`:core:goform:test` 97/97，
    守门测试仍绿 —— 反证了 `ComponentFactory` 没有引用 `GoformTransport`）、第 3/4 层 ✗（无真机）。
  - ⚠ **1.5 的代码改动留在工作区、没有进 commit**：`ComponentFactory.kt` 同时有用户并行改的
    媒体 / 文件装配代码（`FileRoutes` 新参数、`MediaExclusionStore`），整文件 add 就等于替他提交
    半成品。本轮 commit 只含 `DeviceTransport.kt` 的 KDoc 与本文档。
    **`createTransport()` 那段在用户那批改动落地后再补一个 commit**（或由他一起带走）。
  - **阶段 1 的代码工作到此全部落地**，整阶段仍是 `[~]`：1.1/1.2/1.3/1.5/1.6 卡第 3/4 层（无真机），
    与阶段 0 的四项真机待办**合并成一次窗口**做。下一步按 §0 例外 2 可以开阶段 2。
- 2026-09-23 **批 21：阶段 2 的批 A 落地**（子代理实现 + 我复核收口，未 push）：
  - **开工前先给 §6 补了三个决定**：① `DeviceTransport` 必须上移到 device-spi
    （否则 SPI 要依赖 `:core:goform`，违反「SPI 不能知道任何具体协议」）；
    ② `PlatformAdapter` 连同 `AtTransport` 上移一起归阶段 4 ——
    **原 2.1 写的「device-spi 依赖 `:core:collector`」会成环**
    （`collector → goform → device-spi → collector`），计划原文没意识到这点；
    ③ `capabilities` 不在阶段 2 加（冻结区宁可晚定）。另加了批 A~D 的划分。
  - 代码：新建 `:core:device-spi` 与 `:core:device-plugins` 两个 module、
    `DeviceTransport.kt` 上移（package → `com.ufi_axis_core.devicespi`，**14 个方法与 KDoc 一字未动**）、
    新增 `DevicePlugin` / `TransportConfig` / `DeviceTuning` / `ProbeEnv`、
    `ZteF50Plugin` + `PluginRegistry`；`core/goform` 加 `api(:core:device-spi)`；
    `RouteContext` 与 `ComponentGraph` 各改一处 import（后者原来靠通配 import 拿到该类型）。
    **`ComponentFactory` 一行未动**（接线归批 B）。
  - **守门测试暴露了它自己的两个缺陷（我造成的，已修）**：
    ① 判据太粗 —— 原本是 `readText().contains("GoformTransport")`，于是
    `ComponentFactory.createTransport()` 的 KDoc 里那句「本文件**不许**直接写 `GoformTransport`」
    被当成了「用了」。注释里提符号名不仅正常、而且必要（不写下来别人不知道这条边界是刻意的），
    判据要盯的是**代码引用**。已改成**剥掉注释后再扫**（块注释按深度计数，字符串字面量保留）。
    ② **Gradle 不把被扫的别 module 源码当输入** —— 阶段 1.5 那轮 `:core:goform:test` 报全绿，
    其实是 `UP-TO-DATE` 没重跑（`ComponentFactory` 不在输入里）。
    已把这条局限写进测试 KDoc，纪律是**阶段收尾用 `--rerun-tasks` 强制跑一次**。
  - 校验：第 1 层 ✓（`:core:device-spi` / `:core:device-plugins` / `:core:goform` / `:core:api` /
    `:core` 五处编译）、第 2 层 ✓（`--rerun-tasks` 下 `:core:goform:test` **97/97**，
    守门测试 2 条 `skipped=0`、`:core:device-schema:test` 188/188）、第 3/4 层 ✗（无真机）。
  - 顺带修掉子代理的一处笔误：`DevicePlugin.createTransport` 的 KDoc 写「阶段 6 才接线」，
    实际是阶段 2 的批 B。
- 2026-09-23 **批 22：阶段 2 的批 B 落地（第一次真正动装配）**（子代理实现 + 我复核收口，未 push）：
  - 新增 `DeviceRuntime`（device-spi）+ `DeviceRuntimeTest`（**10 条**）；
    `ComponentFactory` 接线、删 `resolveDeviceProfile()` 与阶段 1.5 那个临时 `createTransport()`；
    `core/build.gradle.kts` 加 `implementation(:core:device-plugins)`（装配层是唯一知道「有哪些插件」的地方）。
  - **行为等价性逐条核过**（子代理给了对照表，我复核了每条）：排障 WARN 文案逐字一致且仍在最前面、
    空 id 不打日志、命中时的 INFO 前缀保留原文案、回落 WARN 前半句逐字一致。
    `/api/diagnose` 的 `configured` / `active` / `normalization_enabled` / `status` **取值全不变**
    —— 本批没碰 `HttpServer` / `DataHub` / 任何 route。
  - **唯一的结构差异**：排障模式（关归一化）下**现在也照样选插件**（原实现直接 `return null`、
    根本没有选型这回事）。后果只有一个：那条 WARN 之后会多一行选型日志（INFO 或回落 WARN），
    **只进 logcat，不进任何对外 JSON**。这是「传输层与写命令表不能关」的必然产物。
  - **`configuredId` 兼容旧口径**：先按 plugin id（`zte-f50`）、再按 profile id（`zte-goform`）匹配，
    命中后者打 INFO 说明「两种写法都支持」。没有这一条，已填 `zte-goform` 的部署会变「认不出 → 回落」。
    回落 WARN 的「可选」清单现在同时列两种 id。
  - **我改了子代理的一处实现**：装配处的硬 `as GoformClient` 换成 `as? GoformClient ?: error(...)`
    —— 硬转型失败只留一行没有上下文的 ClassCastException，而这里失败的真实含义是
    「选中的插件不是 goform 系」，值得写成一句话。
  - 校验：`:core:device-spi:compileDebugKotlin` ✓ / `:core:device-spi:test` **10/10** ✓ /
    `:core:compileDebugKotlin` ✓ / `:core:goform:test --rerun-tasks` **97/97** ✓ /
    `:core:device-schema:test` 188/188 ✓；第 3/4 层 ✗（无真机 —— 这一批**改了装配**，
    真机冒烟（仪表盘/网络/WiFi/短信四页数据与改造前一致）是它的解锁条件，见 §6 验收）。
  - **新登记 P1-30**（子代理发现的潜伏问题）：`GoformSignalClient` / `GoformWifiClient` 内部的
    命令表兜底仍是 `GoformFieldMapper(profile, profile ?: DeviceProfiles.DEFAULT)` ——
    装配层这一侧已经用 `runtime.commandProfile` 修正，但这两个客户端**内部**仍会在关归一化时
    把命令表退回「默认设备」。当前只有一个插件、两者同值，属潜伏。见 §15。
- 2026-09-23 **批 23：阶段 2 的批 C（2.6 + 2.7 + P1-30）**（子代理实现 + 我复核，未 push）：
  - **P1-30 已修**：`GoformSignalClient` / `GoformWifiClient` 各加一个**非空、无默认值**的
    `commandProfile` 参数（口径照抄 `GoformSmsClient`），内部 `?: DeviceProfiles.DEFAULT` 删除，
    装配层传 `runtime.commandProfile`。**可空那份 `profile` 的名字、位置、语义一律没动** ——
    `/api/diagnose` 的 `active` / `normalization_enabled` 取值链路一行未碰，今天取值逐值相同。
  - **2.6 已落**：`DeviceProfiles` 标 `@Deprecated(WARNING)`，**实现体一行未动**
    （委托 `PluginRegistry` 是反向依赖，编译不过 —— 理由与「什么时候能真正删」写进了它的 KDoc）。
    全仓确认没有 `allWarningsAsErrors`，warning 不会变 error。
    剩余引用只有 device-schema 自己测试里的两处，加了 `@Suppress("DEPRECATION")` + 保留理由。
  - **2.7 已落**：`PluginContractTest`（**8 条**）：id/displayName 非空且唯一、`DEFAULT` 是 `ALL` 里
    那个对象（identity）、`byId` 往返同一对象、`profile()` 多次调用返回同一对象（钉住「别在里面 new」）、
    `tuning()` 值域自洽（`warn < critical` 等，防换设备时把阈值填反）、
    `goformLdReachable=false` 时 `probe` 必返 0、同一 env 多次 probe 结果相同（纯函数）。
    刻意不测两条并写明理由：`capabilities`（阶段 3 才有）、`createTransport()`
    （会 `new GoformClient` → 起 Ktor + `android.util.Log`，单测里只会拿到 `Stub!`，靠真机冒烟覆盖）。
  - **子代理更正了我契约里的一处错**：我让它给 `SignalCollector` / `DataScheduler` 的过渡引用加
    `@Suppress("DEPRECATION")`，但那两处 import 的是 **`ZteGoformProfile` 而不是 `DeviceProfiles`**
    —— 没标废弃、零 warning、不需要 `@Suppress`。所以「标废弃能提醒这两处欠账」这个预期是错的：
    2.6 的收尾（把它们改成从 `DeviceRuntime` 取）**没有任何编译期提醒**盯着，
    要靠 `DeviceProfiles` KDoc 里那段文字或将来另加一条守门测试。
  - 顺手加了 `testImplementation(libs.kotlinx.coroutines.android)` 到 device-plugins
    （`runBlocking` 调 suspend 的 `probe`；选 `coroutines.android` 而非 `coroutines.test`，只要 `runBlocking`）。
  - 校验：8 条命令全绿 —— 四处编译 + `:core:device-plugins:test` **8/8**、
    `:core:goform:test --rerun-tasks` **97/97**、`:core:device-schema:test` 188/188、
    `:core:device-spi:test` 10/10。第 3/4 层 ✗（无真机）。
  - **新登记 P1-31**：`GoformSettingWriter.kt:48` 的 `profile ?: ZteGoformProfile` 是同一形态的
    **写侧**兜底（比读侧更危险）。同批顺手 grep 确认 `GoformNetworkClient` / `GoformDeviceClient` /
    `GoformSimClient` **没有**同类兜底，所以这一形态全仓只剩 writer 一处。见 §15。
- 2026-09-23 **批 24：阶段 2 的批 B2（2.8）**（子代理实现 + 我复核，未 push）：
  - `DeviceRuntime.Selection` 加 `wire`（四个对外取值）、`DataHub` 加两个只读 String 属性
    （**不给默认值**，逼唯一装配点显式接线）、`ComponentFactory` 把
    `runtime.plugin.id` / `runtime.selection.wire` 递进去、`HttpServer` 的 `device_profile` 块加两个键。
    `:core:api` **没有**新增对 `:core:device-spi` 的依赖。
  - **现有四键取值未变**，逐键核过：`active` / `normalization_enabled` 的链路
    （`GoformSignalClient.profileId` → `GoformFieldMapper.normalizeProfile?.id`）一个字符没碰；
    `configured` 仍是 `settings.deviceProfileId` 原值；`status` 那个 `when` 四个分支逐字未改
    （唯一字符变化是 `mapOf` 要继续加项、`}` 后面多了个逗号）。
  - **两端兼容性核过**：app 走 `AppJson`（`ignoreUnknownKeys = true`，`AppJsonTest` 里本来就有一条
    「容忍服务端多余字段」在守）；web 的 `contract.ts` 只登记路径、无 schema，
    `AboutPanel.vue` 是逐字段挑（连 `device_profile` 都没读），全仓无 zod/ajv/yup。
    所以新增键对旧客户端安全 —— 代价是**两端现在都不会显示新字段**，归阶段 3。
  - **新字段没有自动化断言**：`core/network` 没有 test 源集、`core/api/src/test` 是空目录
    （按「没有测试基础设施就不新建」的口径没动）。所以这两个键**只能靠真机 `GET /api/diagnose` 验**：
    零配置应得 `selection=default`、填 `zte-goform` 或 `zte-f50` 应得 `configured`、填错应得 `fallback`。
  - ⚠ **待补进 API 手册**（`docs/UFI-AXIS-Core-API-Reference.md` 的 `GET /api/diagnose` 一节）：
    响应示例加这两个键；正文补一段说明 —— `plugin_id` 恒非空且与 `active` / 配置键 `device_profile_id`
    都**不一定相等**（那个键存 profile id，两种写法 core 都认）；`selection` 值域固定四个、
    客户端请按四个实现（`probed` 当前不出现但值域已定稳）；以及上面那条
    「`selection` 与 `status` 可能互相矛盾，看选型以 `selection` 为准」的已知不一致。
    本轮没动手册**是因为用户正在并行改它**。
- 2026-09-23 **批 25：阶段 2 的批 D1（2.9 + P1-31）**（子代理实现 + 我裁决收口，未 push）：
  - **2.9 频段全集三份 → 一份**：`DeviceProfile` 新增两个读侧 API 面
    `lteAllBandsMask(): String? = null` / `nrAllBandsMask(): String? = null`（刻意用两个方法而不是
    带 RAT 枚举的一个方法 —— 不为这件小事往冻结区加类型）；`ZteGoformProfile` 实现并返回原常量的
    逐字取值；`GoformNetworkClient` 的两个 companion 常量删除、改从 profile 取，并新增
    `lteAllBands()` / `nrAllBands()` 作为**唯一**的 null 折叠 + WARN 归属地；
    `NetworkController` 改调这两个方法（**跨模块直读彻底消失**）；
    `core/contract/Enums.kt` 的第三份零引用拷贝删除。
  - **对外语义未变**（逐字对照过）：`unlockAllBands()` 发的还是
    `lte_band_lock=1,3,5,8,34,38,39,40,41` / `nr_band_lock=1,5,8,28,41,78`；
    `unlockAll == false` 的两条分支（`lteBands ?: ""`）**完全没碰** ——
    「空串 = 不发限制」仍在调用方那一侧，**没有**把全集塞进 `encode`
    （那正是 P0-1 裁决禁止的「把空串从『不发限制』改成『下发全频段』」）。
  - **mask == null 的行为**：折叠成空串（与「不发限制」同侧）+ 一行 WARN，**不抛异常、不把空串当全集**。
    该分支今天不可达（唯一插件两个掩码都非空），已用假 profile 的 3 条单测覆盖。
  - **P1-31 已修**：`GoformSettingWriter` 由 `profile ?: ZteGoformProfile` 改成收非空
    `commandProfile`（它只用 profile 做两件事：`writeSpec(key)` 与日志里的 `id`，不参与归一化判断）。
    构造链查清了：writer 的 4 个 new 点全在 `core/goform`（Wifi / Network / Device / Sim 四个客户端），
    装配层只有 `ComponentFactory` 一处。**同形态的第三处**也一起修了 ——
    `GoformWifiClient` 批 C 已拿到非空 `commandProfile`，但它 new writer 时还在传可空那份。
  - **我裁决的三件事**：
    1. `core/contract` 那两行**直接删、不先标废弃**。§11.4 的纪律针对「有人在用的冻结区符号」，
       而它是全仓（含 app / web / scripts、两种命名风格）确认过的零引用项 ——
       标废弃是给消费者迁移窗口，没有消费者就没有窗口要给。**也不留墓碑注释。**
    2. `GoformNetworkClient` / `GoformSimClient` / `GoformDeviceClient` 的**可空 `profile` 参数删掉**
       （它们不持有 `GoformFieldMapper`，原来只是拿去喂 writer，现在成了死参数）。
       死参数会让下一个人以为「字段归一化在这三个类里生效」，那是错的。
       三处 KDoc 各写明「将来长出读侧字段时按 `GoformSignalClient` 的形状加回」。
       `GoformWifiClient` / `GoformSignalClient` 的可空 `profile` **必须留**（`active` /
       `normalization_enabled` 靠它）。
    3. 补 `mask == null` 分支的测试 + 在 `ZteGoformProfileTest` 冻结两个掩码的逐字取值
       （并顺带断言「全集串本身过得了 `validateBandList`」—— 否则「解锁」会变成 `Rejected`、
       连请求都不发，那种失败在真机上极难归因）。
  - 校验：六处编译 + `:core:goform:test --rerun-tasks` **100/100**（97 + 新增 3）、
    `:core:device-schema:test` **189/189**（188 + 1）、`:core:device-spi:test` 11/11、
    `:core:device-plugins:test` 8/8。**点名的两套没改一条断言**：
    `GoformWifiBandParamsTest` 7/7、`GoformSettingWriterDecisionTest` 20/20。第 3/4 层 ✗（无真机）。
  - **新登记 P1-32（重要）**：子代理查 `AppLogger` 时发现**日志总开关默认关**，
    导致所有 WARN 在默认部署下都不落地 —— 它直接卡住 2.11 的验收判据。见 §15。
- 2026-09-23 **批 26：阶段 2 的批 D2（2.10）**（子代理实现 + 我验收，未 push）：
  - `DeviceProfile` 新增 `qrCodeFileNames(chip, ssidIndex)`，`ZteGoformProfile` 实现，
    `GoformWifiClient` 改从非空 `commandProfile` 取。请求的文件名序列逐入参对照过、**逐字相同**
    （含 `chip1`+ 第 1 个 SSID 时两候选重名、`linkedSetOf` 去重后只发 1 次这个细节）。
  - 我复验：`_qrcode_wifikey` 在 `core/goform` **0 次**、文件头无残留垃圾、无 BOM、
    `:core:goform:test` 与 `:core:device-schema:test` 在 `--rerun-tasks` 下全绿。
  - ⚠ **过程事故（必须登记）**：该子代理会话里 `edit_file` / `write_file`
    **报成功但一个字节都没落盘**（它做了四路交叉验证：`read_file` / `Select-String` /
    `git status` / 文件 mtime 全部显示未变，`write_file` 写探针文件后 `Test-Path` 为 `False`）。
    它改用 `run_command` + `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` 落盘，
    过程中一度把诊断字符串写进了 `GoformWifiClient.kt` 文件头（`names anchor_count=1 …`），
    同轮自查后剥除。**我事后复验过该文件首行是 `package ...`、全仓无 `anchor_count` 残留、
    编码 UTF-8 无 BOM、行尾仍是 CRLF。**
    → 教训写进纪律：**子代理改完文件后，验收方必须自己 grep 一次文件头与关键字面量**，
    不能只看子代理的自述（它自述「写成功」的那四次其实一个字节都没写）。
  - 三处刻意不做：profile 侧不校验 `chip` 取值域（校验在 `WifiRoutes`，再加一道会把
    「取不到那张图」变成「该设备不支持」）、`QR_CODE_FALLBACK_STEM` 不与写侧的 `WIFI_CHIP_FALLBACK`
    合并（都是 `chip1` 但语义无关，合并会让两条判据被同一次修改牵动）、客户端不加 `distinct()`。
  - **2.11 仍未做**（卡 P1-32 的日志口径，批 E 解锁后再做）。
- 2026-09-24 **批 27：P1-32 落地（全局日志行为变更，不属于设备插件化的任何一步）**
  （子代理调查 + 实现，我复核收口，未 push）：
  - `AppLogger` 新增纯函数 `shouldEmit(level, loggingEnabled, coreLogEnabled)`，
    `log()` 与 `e()` 的第一行由 `if (!active) return` 换成它。
    **只无视总开关 `log_enabled`（默认 false），保留 `core_log_enabled`（默认 true）作为硬闸** ——
    两个开关语义不同（前者全局、后者按侧静音），详见 §15 的 P1-32。
  - 三条落地路（logcat / 文件 / 内存缓冲）**全放行**；`GoformSessionLog` 那条全量会话诊断
    **刻意仍受总开关管**。成本估算 ≈3.4MB/天，被 `repeatGate` 60s 折叠 + 5MB/40MB/7 天预算夹住。
  - 连带修：logcat 的 `when` 块包 `try/catch`（WARN 放行后，任何不用 Robolectric 的单测
    路过一条 WARN 都会炸 `android.util.Log` 的桩）。我复核了实现：只包 logcat、
    catch 后静默且不再调 `Log`，文件与缓冲两条路在它**之后**、照常执行。
  - **改了一条既有断言**（`总闸关掉后一条不记` → 断言反转）。这违反「既有断言一条不许改」，
    但它断言的正是被裁决推翻的旧口径，属于 §13 里「断言本来就在断言一个错误行为」那一类。
    **显式登记在此**，不许当成「测试都绿了」一带而过。
  - 校验：`:core:common:compileDebugKotlin` / `:core:compileDebugKotlin` 通过；
    `:core:common:test` **171/171**（170 + 净 1）、`:core:goform:test --rerun-tasks` **103/103**、
    device-schema 190、device-spi 11、device-plugins 8。我复跑了 common 与 goform 两套。
    文件头、无 BOM、UTF-8、`grep '/\*[^*]'` 零命中都自查过（上一批出过写盘事故，这批逐项核）。
  - **落盘那一段只能真机验**（单测不落盘）：总开关关、core 日志开、详细日志关 → 重启 core →
    触发一条排障 WARN（最省事是把 `field_normalization_enabled` 关掉再读一次设备）→
    看 `log/core/<今天>/app.log` 里有那行 WARN、且没有新的 `[INFO]` / `[DEBUG]`；
    web 日志面板同样只看到 WARN。反向再验一次：把 core 日志也关掉 → 应当一行都不新增。
- 2026-09-24 **批 28：2.11 落地（阶段 2 的任务清单到此全部打完）**（子代理出方案、我落盘与验收，未 push）：
  - 细节见 §6 的 2.11 条目。要点：容错解析进 `core/common`（可测 + 零新增依赖边）、
    三态类型让「解析失败被当成所有设备都离开了」**在类型上不可表达**、
    解析失败**保留基线**（清了会永久静默停摆）、只有 `Malformed` 打 WARN 且文案逐字固定。
  - ⚠ **写入通道事故第二次发生**：这个子代理的 `write_file` / `edit_file` **报成功但一个字节都没落盘**
    （它做了四路交叉验证：`read_file` 回 `File does not exist`、`glob_path` 全仓 0 命中、
    `git status` 的 `??` 列表里没有它、同一批的另一个 edit 反被判 `Stale context`）。
    **它按纪律停手、没有自己绕**（上一个子代理是绕了、用 PowerShell 写盘并一度写坏文件头），
    只把成品代码贴回来。**我自己落盘**（我这一侧的写入正常），并按自己的口径精简了类型
    （`Unavailable(Reason)` 两层 → `Missing` / `Malformed` 两个 object，when 分支更直接）。
    它给的根因线索：会话 env 里的工作区路径显示成 `d:\AndroidStudioProjects` + 换行 + `ew\UFI-AXIS`
    —— `\new\` 的 `\n` 被当转义吃了；读侧传绝对路径没事，写侧若内部拼过一次工作区根就会落到错的根上。
    → **纪律追加**：子代理报「写成功」不等于落盘，验收方必须自己 `git status` / `grep` 复核；
    子代理遇到这种情况**就该停手报告**，不要自己找旁路。
  - **新登记 P1-35**：`checkDeviceEvents` 的兜底 catch 仍把 `e.message` 拼进日志（基数无界、
    绕过 `repeatGate`），且会吞掉普通 `CancellationException`（与同文件其它多处的写法不一致）。见 §15。
  - 校验：`:core:common:test` **176/176**（171 + 5）、`:core:scheduler` 与 `:core` 编译通过。
- 2026-09-24 **批 29：阶段 3 的 core 侧（3.1 / 3.2 / 3.3 / 3.4 / 3.7）**（子代理实现、我裁决与验收，未 push）：
  - **三条开工裁决**见 §7 开头（Capability 是功能域不与 SettingKey 一一对应 / 新端点 / 本批只碰 core）。
  - **10 个域的「双证据」对照表**（子代理逐项查的，没有一项需要豁免、没有硬造 route）：
    `sms` ← `smsSpec`（不走 SettingKey）+ `POST /api/sms/send`；
    `sim_slot_switch` ← `SIM_SLOT` + `/api/sim/switch`；
    `band_lock` ← `BAND_LOCK_LTE`+`BAND_LOCK_NR` + `/api/network/band`；
    `cell_lock` ← `CELL_LOCK`+`CELL_UNLOCK` + `/api/device/cell-lock` 与 `/cell-unlock`；
    `network_mode` ← `NETWORK_MODE` + `/api/network/mode` 与 `/bearer`；
    `samba` ← `SAMBA` + `/api/device/samba`；
    `usb_debug` ← **`USB_PORT`**（该 key 的 KDoc 与 `GoformDeviceClient.setDebugMode` 两处一致证据，不是猜的）
    + `/api/device/debug`；`fota` ← `FOTA_AUTO_UPDATE` + `/api/device/fota`；
    `performance_mode` ← `PERFORMANCE_MODE` + `/api/device/performance`；
    `traffic_limit` ← `TRAFFIC_LIMIT` + `/api/device/data-limit`。
  - **两条实测事实值得记住**：① `ErrorCode.NOT_SUPPORTED` **此前根本不存在**，
    全仓只有两处注释说「route 应回 NOT_SUPPORTED」—— 501 这条出口是这次才建起来的；
    ② **`:core:network` 看不见 `:core:contract`**（`:core:api` 对 contract 是 `implementation`，不传递），
    所以 `CapabilityMissing` 必须**自带** status / errorCode / 文案，
    才能在 `HttpServer` 的 `StatusPages` 里被捕（第一次编译就是被这条打回来的）。
    异常类因此定在 `:core:api`：它不是对外契约（跨 HTTP 的是 501 与 `NOT_SUPPORTED`，那两个已在冻结区）。
  - **我的一处裁决：域内所有写入口都要拦**。子代理原本放行了 `/bearer` 与 `/cell-unlock`
    （理由是「同一条设备命令只拦一次」）—— 那不成立：**拦的不是命令，是这台设备支不支持这个功能域**，
    放行任一入口就等于给前端留了一条绕过门禁、把请求打到设备再失败的路。补完是 **12 处门禁 / 10 个域**，
    守门测试的「恰好一处」同时改成「至少一处」+ 总数下限、**不设上限**
    （计数管不住语义，还会在加写入口时逼人改测试）。
  - 校验：`:core:contract:test` **11/11**、`:core:api:test` **235/235**（首次整模块跑，记为新基线）、
    `:core:device-plugins:test` **11/11**、`:core:device-spi:test` 11/11、
    `:core:goform:test --rerun-tasks` 103/103；六处编译通过。我复核了门禁调用点实测 12 处
    （Sim 1 / RootSms 1 / Network 3 / Device 7）。`/api/diagnose` 的 `device_profile` 块一个字未改。
  - **新登记 P1-36**：`TaskRoutes.kt:176` 是全仓唯一的旧 501，配的却是 `ErrorCode.UNAVAILABLE`
    —— 正是 §11.6 说的「三种不可用混成一个码」。它与能力集无关（core 自己的组件没装配、可恢复），
    本批没动。见 §15。
- 2026-09-24 **批 30：阶段 4 的批 F（`AtTransport` 上移 + `PlatformAdapter` / `SprdPlatform` +
  `DeviceTuning` 字段改名）**（子代理实现，我复核，未 push）。三条开工裁决与实测见 §8 开头。要点：
  - `AtTransport` 整文件移到 device-spi（零 import、零 Android 类型，**不需要新增任何依赖、不成环**）；
    `ATChannel` 留在 collector（策略层），`init()` 改成接收注入的 `List<AtTransport>`。
  - `ServiceCallAtExecutor` 搬到 `device-plugins` 的 **`platform/sprd/`**（不放 `zte/f50/` ——
    类里没有一个字节是 ZTE 知识，塞进 zte 包会逼第二台 Unisoc 设备 `import zte.f50.SprdPlatform`）。
    带走 `AppLogger` 与 `decodeServiceCallText`，所以 device-plugins 加了 `:core:common` 依赖
    （后者绕不过去：它是 service call 协议解码器、有自己的单测，复制一份就是两份各自演化）。
  - `SprdPlatform.name = "SPREADTRUM"` 而不是 `"sprd"`：4.6 会用它替掉 `detectPlatform()` 的结果，
    而那个结果**现在就在** `/api/at/platform` 下发、取值是大写 —— 填 `sprd` 等于让对外取值在 4.6 那天静默变化。
  - `PlatformAdapter` **刻意只有四个成员**，不设 `privilegeEscalation()`（裁决 ④）与 `readBattery()`（归 3B）。
  - `DeviceTuning` 改名 `downloadThrottle*` + 新增 `downloadThrottleForcePauseOffsetC`（承载原本裸字面量的
    `+10` 第 4 档 95°C）+ **删掉 `rootShellPermits`**。每个字段 KDoc 写明「哪些近名阈值不归它管」。
    **`DownloadManager` / `AppSettings` / `AlertEngine` / `ShellQoS` 一行未动** —— 接线是后续批次。
  - 一处结构影响：`buildCollectorGraph` 现在需要 runtime，`resolve()` 因此上移到它之前，
    **启动日志顺序变了**（设备 profile 那几行现在打在 `[2] AT channel:` 之前），对外 API 零变化。
  - 校验：五处编译 + device-spi 11 + device-plugins 11 + contract 11 + goform 103 + api 235 + common 176，
    各套与基线逐条一致。
- 2026-09-24 **批 31：阶段 4 的 4.4（`restartNetworkStack` 收进 adapter）**（子代理实现，我复核 + 一处收口，未 push）：
  - 签名按 §8 的 ⑤ 改成收执行器（`at: suspend (String) -> String?`）；`SprdPlatform` 里那段**逐字搬迁**
    （命令、`delay(500)`、`delay(2000)`、`contains("OK")` 判据、三条 INFO 与两条 WARN 文案全未改）；
    `NetworkController` 退化成一行委派，加第 5 个构造参数 `platform`。
  - **两样刻意没搬**：① **互斥锁留在 `NetworkController`** ——
    `DevicePlugin.platform(ctx)` 每次调用新建实例，锁放 adapter 就是「每个实例各锁自己」= 等于没锁；
    ② **5000ms 超时留在调用方** —— `at` 签名里没有超时参数，超时属于通道策略。
  - **实测澄清两件事**：① 全仓**没有** `/api/network/restart` 端点，`restartNetworkStack` 唯一调用点是
    `lockBands()`，对外判据落在 `POST /api/network/band` 的 `network_restarted`；
    ② `ATRoutes.DANGEROUS_AT_PATTERNS` 拉黑 `AT+SFUN=` 那道闸**只在 route 层**
    （`POST /api/at/command` 命中即审计 + 403，根本不碰 `atChannel`），内部路径从不经过 `ATRoutes`
    —— 所以「**对外禁止、内部照发**」这组契约搬迁后原样成立。
  - `false` 的两义（「不支持」还是「这次失败」）**现状分不开**，按现状实现 + KDoc 写明局限：
    唯一调用点把它原样塞进 `network_restarted`（布尔两态），要分开就得改端点响应形状 + 两端解析。
  - 单测 `SprdPlatformTest` **10 条**，假执行器 + `runTest` 虚拟时钟把 500/2000 断到精确值
    （成功 `currentTime == 2500`、第二条失败 `== 500`、第一条就失败 `== 0`），
    **没有为了测试动生产代码的时序**。
  - **我的一处收口**：装配层原来调了 `platform(context)` **两次**（造两个实例）。
    改成 `build()` 里造**一份**、`buildCollectorGraph` 与 `buildNetworkGraph` 共享。
    今天 `SprdPlatform` 无状态所以无害，但那是定时炸弹 —— 哪天 adapter 缓存了热区路径或探测结果，
    两个实例会各探一次、各缓存一份、行为还不一致。共享一份也让「锁能不能放 adapter」这个问题彻底消失。
  - 校验：四处编译 + `:core:device-plugins:test` **21**（11 + 10）、device-spi 11、api 235、
    goform 103（`--rerun-tasks`）。第 3/4 层 ✗ —— **这一批改的是写路径**，
    真机判据是 `POST /api/network/band` 锁频段后 `network_restarted` 那一位。
- 2026-09-24 **批 32（commit 里叫「阶段 4 的 4.3 一半 + 4.5 接线」，`SprdPlatform` KDoc 里叫批 H）**，
  commit `6c22710`（子代理实现，我复核）。
  **编号口径**：批 30 = 批 F、批 31 = 批 G，往后字母与序号一一对应（H=32、I=33、J=34、K=35、
  L=36、M=37、N=38、O=39）；§15 的 P1-40 / P1-41 用的就是这套数字编号。
  - **4.3 的 `DownloadManager` 那一半**：`SprdPlatform.readTemperature()` **逐字搬** `readMaxTemp`
    的读法（遍历 `/sys/class/thermal` 全热区、每热区独立 try、`canRead` 守卫、负数夹地板），
    **唯一不同是读不到返 `null` 而不是 `0f`**（契约要求）。`DownloadManager` 改调 adapter 并 `?: 0f`
    —— 五种情形逐路对照过、分档结果与改造前完全一致，**没有**顺手改成「读不到按最高档保护」
    （那是行为变更，不是搬运）。
  - **4.5 接线**：`DownloadManager` 注入 `tuning`。首次创建配置时两个阈值取自 tuning；
    **已有配置一个字节都不被 tuning 覆盖**；`migrateConfig` 的抬升**目标值**改成 tuning 的值，
    而地板判据 `70f` / `80f` **保持字面量** —— 它与目标值不是同一个数，`DeviceTuning` 没有承载它
    （登记为 §15 的 **P1-40**）。第 4 档的 `+10` 改成 `tuning.downloadThrottleForcePauseOffsetC`。
  - **刻意没碰**：采集降频那套（`AppSettings.monitorThermal*` 70/80）与用户告警那套
    （`AlertEngine` 65/75 + 3°C 回差）一个字未改，**也没有因为 `AlertEngine.temperatureCritical`
    与 `downloadThrottleWarnC` 都是 75 就接线**（§8 裁决 ② 里那个坑）。
  - 验证：四处编译 + `DownloadConfigTuningTest` 新增 8 例 —— **tuning 故意填 77/88/12 而不是
    75/85/10**，否则「接线没接上」也能通过；覆盖首次取 tuning、已有配置不被覆盖（含走真实反序列化
    入口）、迁移抬到 tuning 值、地板判据没被误接成 tuning、第 4 档用 tuning 偏移量。
    controller / device-plugins / api 测试全绿。
  - `DataScheduler` 那一侧子代理**按纪律停手未改**：它直读毫摄氏度原值，`Float?` 往返实测 80001 个值
    里 555 个少 1；更硬的阻塞是两份实现有四点语义差（目录守卫 / 异常粒度 / 负数地板 /
    不可解析内容）。用户裁决方案 B（接受四点变更、用 `roundToInt` 换算），单独一批做 → 批 33。
- 2026-09-24 **批 33（「阶段 4 的 4.3 收尾」，即批 I）**，commit `0384f83`（子代理实现，我复核）。
  - `DataScheduler` 注入**共享的** `PlatformAdapter`，`readMaxCpuTemp` 改成调
    `platform.readTemperature()` + `celsiusToMilliC` 换算。换算抽成 `core/common` 的纯函数并补 4 条单测，
    **其中一条故意断言 `toInt()` 算错**（32002 毫度 → `toInt` 得 32001、`roundToInt` 得 32002），
    把「为什么必须 `roundToInt`」钉死在测试里，防将来被顺手简化成截断。
    实测 20~100°C 区间 80001 个值：`toInt` 有 555 个少 1，`roundToInt` 零不匹配。
  - **这一批是行为变更不是搬运**，四点语义差异逐条核过影响面：目录/文件守卫（取值不变，多一条 WARN）、
    负数夹地板（下游三条降频档与熔断 `when` 对负数与 0 判定相同，真机不可观测）、
    不可解析内容（max 从 0 起、0 不抬升结果，运行时等价）。
  - **唯一会改变判定的是异常粒度，而那修掉了一个既存缺陷**：原实现只有一层外层 try，
    任一热区 `readText` 抛异常就整轮退化成 0、误判「最凉」→ 高温时不降频、不清缓存、不告警。
    Unisoc 上部分 `thermal_zone*/temp` 对非 root app 是 `0400 root:root` ——
    **有这么一个热区，那类机型的温控一直是失效的**。改成每热区独立 try 后坏热区被跳过、其余照算。
  - `null` 时的下游路径逐条保住：三条降频档落最凉、熔断 `when` 两个分支都不进、
    `scanLocalAlerts` 的 `if (milli > 0)` 不成立所以 `AlertEngine` 的 65/75 回差状态机不被驱动
    —— 与改造前逐位相同，**阈值一个字未碰**。
  - WARN 文案去掉 `e.message` 插值（现在是编译期常量、能被 `repeatGate` 折叠成 1 条/分钟），
    顺带修掉 **P1-35** 登记的那个坑。子代理还纠正了上一批报告里的一处事实错误：
    `listFiles()` 对不存在的目录返回 `null`、`?:` 短路，所以「目录不存在」原本并不触发外层 catch 的 WARN。
  - 验证：四处编译 + common **180**（176+4）+ controller 169 + device-plugins 21 + api 235 + goform 103。
    新登记 **P1-41**（`DataScheduler` 行内注释写 85/75，实际阈值是 80/70 —— 那一批的硬约束是
    「阈值一个字都不许碰」，所以刻意没动）。上报侧两处热区读法本批不碰。
- 2026-09-24 **批 34（批 J）：4.6 + 5.1**，commit `a3d3658`（子代理实现，我复核）。
  - **5.1**：新增 `ProbeEnvCollector`（`core/device-plugins` 的 `probe/` 包）。位置的四条排除理由都记下了：
    不放 device-spi（纯契约层，单测不许起 Android）、不放 `core/common`（底座不该反向依赖设备 SPI）、
    不放装配层（采集口径是会继续长的设备探测知识）。采 `cpuInfoPlatform`（`/proc/cpuinfo` 转小写原样给出）、
    `androidBuild`、`goformLdReachable`；**整个采集不抛异常**，任何一项失败用 `null` / `false` 兜。
    裸 HTTP 探测照 `ensureLogin` 的实测形状发 `cmd=LD`（不带 Cookie 不带密码 —— 此刻 transport 还没造，
    这正是 `DeviceProfiles` 记的「鸡生蛋」）。1.5s 用在三处（connect / read / `withTimeoutOrNull`）：
    `HttpURLConnection` 的阻塞读不响应协程取消，只靠 socket 超时最坏是 3s。
    超时 / 异常 / 非 200 一律 `false`，只放 `CancellationException` 过去，**绝不影响组件图构造**。
  - **4.6**：`ATChannel.detectPlatform` 删掉读文件那一步，改从注入的 `ProbeEnv.cpuInfoPlatform` 派生；
    `Platform` 枚举三个取值与映射顺序一字未动，**没有**改成直接取 `adapter.name`
    —— 那会让高通设备也被报成 `SPREADTRUM`、丢掉「这台其实不是展锐」的信息。
    `ZteF50Plugin.probe` 不再自带 marker 列表。
  - marker 合成一份放 `:core:device-spi` 的 `CpuInfoPlatform`（纯常量 + 纯函数，零 I/O 零 Android）。
    这偏离了「平台知识放 `platform/sprd/`」的原指令，但理由成立：`:core:collector` 不许依赖
    `:core:device-plugins`，放 `platform/sprd/` 的话 `ATChannel` 拿不到、只能继续留两份判据，
    而那正是 4.6 要消掉的；另外 QUALCOMM 的两个 marker 在 `platform/` 下根本没有归属。
  - ⚠ **一处对外取值变化**：并集后 marker 是 `[sprd, spreadtrum, unisoc]`（原 `ATChannel` 侧只有前两个），
    所以 cpuinfo 里只有 `unisoc` 的机型上 `/api/at/platform` 等四处同源端点的 `platform`
    从 `UNKNOWN` 变成 `SPREADTRUM`。在用的 F50 不受影响（cpuinfo 带 `Spreadtrum`）。
    已在两处 KDoc 写明、一条单测钉住、§16 基线加了比对提示。
  - `resolve()` **刻意不加 `probeEnv` 也不改 `suspend`**：本批范围是采集；加参数会把 `DeviceRuntime`
    的 diff 从「只有 KDoc」变成「签名 + 11 条守选型规则的测试调用点」，而那 11 条正是本批唯一的回归防线。
    验证：五处编译 + device-spi / device-plugins / api 测试全绿，新增 `CpuInfoPlatformTest` 9 条。
- 2026-09-24 **批 35（批 K）：5.2 ~ 5.4 —— probe 真正参与选型**，commit `2ead061`（子代理实现，我复核）。
  - `resolve()` 改 `suspend` 并收 `probeEnv`（批 34 已在它之前采好一份，`ATChannel` 吃的是同一个对象）。
    选型顺序：排障 WARN 仍在最前 → 配置命中 `CONFIGURED`（**配置永远最高优先级，probe 压根不调**）→
    配置认不出 `FALLBACK` → 配置为空才 probe 打分，最高分 > 0 则 `PROBED`、全部 ≤ 0 则回落 default
    且是 `DEFAULT` 不是 `FALLBACK`（没人配错，走的是「认不出设备不能导致整个不工作」那条纪律）。
  - 并列同分取 `plugins` 声明顺序靠前那个（`score > bestScore` 才换人）+ 一条 WARN 说明
    「并列意味着判据不足」；probe 抛异常按 0 分处理、其余插件照常参与，
    WARN 带插件 id 但**刻意不带异常 message**（`repeatGate` 按完整消息折叠，带 message 会让基数发散，
    有测试硬钉这条）；`CancellationException` 单独先 catch 原样抛出 —— 调用方取消不是打分失败。
  - **对现有部署零影响**：F50 的 `deviceProfileId` 是空 → 走 probe → 唯一插件在真机上 LD 可达得 60 分
    → 选中的插件与改造前完全相同，只有 `/api/diagnose` 的 `selection` 从 `default` 变 `probed`
    （`probed` 早在批 B2 一次定稳的值域里，**两端不用改**）。设备离线时 probe 返 0、`selection` 仍是
    `default` —— 所以 `selection` 现在隐含了「探测通不通」，已写进 KDoc。
  - 既有 11 条 `DeviceRuntimeTest` 断言**零改动**（`FakePlugin.probe` 原本就返 0，改成带默认值 0 的参数
    + 调用计数）。新增 7 条：probe 命中 `PROBED`、多插件取最高分（两种声明顺序各跑一遍，证明是最高分
    而不是第一个正分）、并列取靠前者（顺序倒过来再跑一遍，证明依据是列表顺序不是 id 字典序）、
    抛异常者排最前也不中断后面的、全 0 回落 `DEFAULT` 且 `assertNotEquals FALLBACK`、
    有配置时 `probeCalls == 0`、排障开关下 probe 照样参与且那条 WARN 仍在最前。
  - 一处裁决：**probe 全 0 那条路径刻意不打日志**。我给的两条指令在这里硬冲突（要打日志 vs
    既有断言不许改），选了不打，三条判据成立 —— 与改造前逐字一致、LD 探不通时采集器已经打过 WARN、
    信息在 `/api/diagnose` 的 `selection` 里可见。三条断言「不打日志」的既有用例现在同时钉住了这个决定。
    验证：三处编译 + device-spi **27**（20+7）+ api 235 + common 180 + goform 103。
- 2026-09-24 **批 36（批 L）：无电池设备不再记录与告警假电量**，commit `4d9ea93`。
  - 用户实测：**F50 没有电池、系统一直报 50%**。核过采集链后确认这不是「读不到」而是「读到假值」——
    `level=50` / `scale=100` 满足 `SystemCollector` 第一级判据，于是 `percent=50`、那条「读不到」的 WARN
    压根不打、`DataScheduler.collectBattery` 的 `level<0` 也不成立 → **数据库存着一条永远 50% 的假曲线、
    告警引擎按假值在判**。`SystemCollector` 的 KDoc 原写「percent 恒为 -1」在这台机器上不成立
    （那描述的是另一种机型），已修。
  - 落地的裁决 C：`SystemCollector` 注入 `batterySupported`（装配层从 `runtime.plugin.capabilities`
    算出的不可变布尔，**不递选型对象**），不支持时整段短路、`percent` / `level` 给 -1。
    **键集 / 键序完全不变** —— 少一个键就是 JSON 形状变更。
  - `Capability` 加 `BATTERY`（放末尾不影响已有声明序），但 `ZteF50Plugin` **不声明它**，
    所以 `/api/device/capabilities` 的数组仍是 10 项。文件头补两段口径：为什么 3B 只落一项
    （`ROOT_SHELL` / `AT_CHANNEL` 是运行时状态，塞进静态集合就是又一个假开关）、以及「纯读侧能力」这一类。
    `readBattery()` 确定**不进** `PlatformAdapter`（主路径是 Android `BatteryManager`、不是平台知识，
    还会给 `SprdPlatform` 加第一个 `Context` 依赖）。
  - 三处测试口径同步：`CapabilityWireTest` 写死断言 10 → 11（值域一变必须有人显式改测试）、
    `CapabilityGateTest` 的 exempt 空集 → `{BATTERY}` 并标明属「本质上不该有写 route」那类，
    `PluginContractTest` 加 `READ_ONLY_CAPABILITIES` **显式白名单**而不是「找不到就跳过」，
    并加一条「两表交集必须为空」——否则写侧判据会因例外分支先返回而静默失效。
  - ⚠ 登记 **P0-4 发版阻塞**：core 侧改完后 app 三处会显示 `-1%` 且卡片被涂成 critical 红，
    比原来的假 50% 更像故障。验证：五处编译 + contract 11 + device-plugins 21 + api 235 + device-spi 27。
- 2026-09-24 **批 37（批 M）：电量回到系统值 + 新增 `supported` 字段，入库与告警按能力跳过**，
  commit `9c0cbdd`。
  - **用户推翻批 L 的裁决 C**（原话大意：F50 这种机型还是按系统值，别改成 -1，可以在电池详情弹窗提示）。
    `getBatteryInfo` 的读值逻辑**整段回退到批 L 之前**（与 `4d9ea93^` 逐字节相同），F50 上 `percent` 仍是 50，
    批 L 引入的 `-1` / `-1.0` / `false` / `None` 全部撤销。**P0-4 随之解除。**
  - 新增 `battery` map 的 `supported` 字段（F50 = false），取值来自装配层**只算一次**的
    `Capability.BATTERY in runtime.plugin.capabilities`，一路递到 `SystemCollector` 与 `DataScheduler`
    —— 同一个 `val` 保证「填字段的口径」与「跳过入库的口径」**不可能漂移**。放在 map 首位是为了
    异常路径（catch 后只剩半张表）也带着它。为什么不让客户端去读 `/api/device/capabilities`：
    渲染电量卡片时手里已经有这张 map，为一个布尔再发一次请求不划算，
    而且「读数」与「读数可信吗」在同一个响应里不会有不同步的窗口。
  - 未声明电池能力时**跳过入库与本地告警**：`percent` 恢复系统值只解决显示问题，
    「数据库存一条永远 50% 的假曲线」「告警引擎按假值判」还在。`collectBattery` 里
    **先给 `_latestBattery` 赋值再判断跳过**（两个读端点靠它下发系统值，不赋值会让每次请求都在
    Netty worker 上现场采集）；`scanLocalAlerts` 的门放在读取之前。
    两条 WARN **刻意不同文** —— `repeatGate` 按「级别+tag+完整消息」折叠，同文案会互相顶掉，
    就看不出另一道门也在生效。
  - `SystemCollector` 的 KDoc 把**无电池机型的两种表现**都记下来了：F50 是 sticky intent 带
    `level`/`scale` 但值是假的（恒 50，**没有任何「读不到」的信号**）；另一种是不带 `level`/`scale`（恒 -1）。
    原注释只写了后者 —— 看到「恒为 -1」就以为无电池一定报 -1，会漏掉第一种，
    那条 50% 假曲线就是这么进数据库的。
  - 批 L 的其余部分全部保留（`Capability.BATTERY`、文件头两段口径、`ZteF50Plugin` 不声明、
    `PlatformAdapter` 的 `readBattery` 不做、三处测试口径）；另有四处文件的 KDoc 跟着改了事实描述
    （原文写「抹成 -1」，批 M 之后那是假话）。
    验证：三处编译 + contract 11 + device-plugins 21 + api 235 + device-spi 27 + common 180。
- 2026-09-25 **批 38（app 侧收尾，commit 标题未标批号，按字母序是批 N）：电池详情弹窗提示无电池机型**，


  - commit `a249555`（app 三个文件 + 一条 `AppJsonTest` 用例）。

  - `BatteryInfo` 加 `supported: Boolean = true`。**默认 true 是兼容性判据不是产品取舍** ——
    旧版 core 的响应里没有这个键，默认 false 会让所有旧部署都被判成「可能无电池」并弹提示。
  - `HomeMetricsDialog` 在 `supported=false` 时用 `UfiDialogNote`（中性说明档）加一行：
    本机型可能没有电池、数值仅供参考、不做记录与告警。末尾那半句是解释「电池历史图为什么是空的」
    的唯一线索（core 侧确实不入库不告警）。**没选 `UfiDialogWarning`**：那是橙底警告档，
    它自己的 KDoc 就写着「别用它说普通的话」，而这不是故障。
  - 充电状态弱化：`supported=false` 时「状态」与「充电中」两行的值给 `null`，`UfiInfoRow` 既有实现
    渲染成长破折号。**零新增文案、零新增样式**；不隐藏行是为了不让弹窗行数随机型跳变；
    破折号既不声称充电也不声称没充电，而「否」对一台没有电池的设备同样是假话。
    `supported=true` 时两行渲染逐字节不变。
  - 补的一条测试：`AppJsonTest` 加「core 省略该键时 `supported` 默认 true」——
    用真实反序列化验旧 core 响应解出 true、显式 false 时不被默认值吃掉（核过 XML：5 条、`skipped=0`）。
  - 首页卡片 `HomeMetricsCard` 按要求未动（它显示系统值，与 core 口径一致）。
    登记未做：温度 / 电压在 F50 上大概率也是假值（本批只授权充电状态）；
    `plugged` 字段全站无 UI 消费、是最适合表达「插着电但没电池」的死字段。
- 2026-09-25 **文档轮（不标批号）：新增 `docs/device-adaptation-guide.md`**，commit `cd67804`。
  - 分工从这一轮起固定：**计划书记「为什么这么设计、哪些方案被否掉」，适配指南记「照着做」。**
    指南开头就写明能力边界（当前只支持 goform 系设备，非 goform 协议接不进来 ——
    装配层那个 `as? GoformClient ?: error(...)` 就是哨兵），缺口清单单列一节。
    内容按代码现状核对，不照抄计划书。
  - 顺带修掉一条会误导客户端实现的注释：`HttpServer` 里 `/api/diagnose` 旁边写着
    「`probed` 在当前版本不会出现（probe 选型是阶段 5）」—— **5.2 已落地，`probed` 现在是零配置部署的
    常态取值**，而 `default` 反而意味着「没人认领、正在用兜底插件」。那条注释离 API 最近、
    最可能被抄进文档，而 `DeviceRuntime` 的 KDoc 写的是对的，两处互相矛盾。
  - 指南里诚实登记了**接第二台设备前必须先清的两处欠账**：`SignalCollector` 与 `DataScheduler` 的
    `= ZteGoformProfile` 默认参数（新设备接上来后这两处仍会用 F50 的 profile）、
    `GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST`（绕过 `commandProfile` 唯一通道）。
    **后者此前任何清单都没登记。**
- 2026-09-25 **批 39（批 O）：阶段 3 的 3.5 —— app 侧按能力集置灰**，commit `ba08293`。
  - 此前 core 已按能力集回 501 / `NOT_SUPPORTED`，但 app 上的开关照样能点、点了才收到错误。
    本批置灰 **4 个域 5 处控件**：`fota` / `samba`（网络功能页）、`performance_mode`（设备控制页）、
    `network_mode`（网络制式页）、`cell_lock`（锁站页三个写按钮 + 页顶一张说明卡）。
    **「刷新」这类读侧入口不动** —— 不支持锁站不等于不能看基站信息。
  - 判定收在 `DeviceCapabilityState.supports()`：**`loaded=false` 时恒为 true**。拉取失败只记日志、
    不写错误 state，于是降级方向只有一个 —— **不确定时按全部支持**。反过来做的话一次网络抖动就能把
    整页灰掉，等于把「通道不可用」（503，可重试）冒充成「设备不支持」（501，不可恢复）。
    换设备 / 换地址时跟 `resetFreshness` 一起复位成 UNKNOWN，**而不是清成空集合**
    —— 后者会被判成「全部不支持」，重连那一瞬间整页全灰。
  - **置灰不改 `checked`**（开关仍显示设备真实状态）；说明文案只说「这台设备不支持 X」，
    不写「未开启」「权限不足」—— 那两句会把用户引向一场没有出口的寻找。
  - 能力集**直接复用 core 的 `Capability` 枚举**（`app:data` 已 `api(:core:contract)`），
    不在 app 侧再抄一份 wire 名镜像（所以 3.8 只剩 web 那一半）。
    顺带给 `UfiSettingsToggle` 补 `enabled` 参数（同文件的 `UfiSettingsItem` / `UfiSettingsValue` 早就有）。
  - 验证：`DeviceCapabilityStateTest` 7 条全绿。**置灰分支在 F50 上永远不会触发**（它声明全部 10 项），
    所以只能靠单测验；真机能验的是「全部支持时 UI 与改动前一字不差」。
  - **未完**：`sms` / `traffic_limit` / `band_lock` 三个域因与并行改动文件重叠未动 → 3.5 仍是 `[~]`。
- 2026-09-25 **批 40（基础工作第一批）：拆掉三处「漏传就按 F50 处理」的隐式回退**，commit `a48e1b9`。
  - **编号口径（本轮起）**：批 40 ~ 批 50 是「设备适配接口（`DeviceAdapter`）铺路」这一轮，
    commit 标题里的字母是**这一轮自己的编号**（A1 / A2a / A2b / A4 / A3 / B1 / B2 / C1 / C2），
    与批 F ~ 批 O 那一轮的字母**不连续**，不要混着读；序号仍按 §9 既有口径往后排。
  - 删掉 `SignalCollector.profile` 与 `DataScheduler.deviceProfile` 的 `= ZteGoformProfile` 默认参数。
    默认值的语义是「漏传就按 ZTE F50 处理」，而选型的唯一出口是 `DeviceRuntime`、两个调用点本来就在
    用命名参数显式传 —— 默认值只服务于「将来某人漏传」这一种情况，而那一种情况恰恰最该编译报错。
    两处删完**零行为变化**。
  - `GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST` 拆掉：
    读侧（`getAccessControlList`）改走新增的 `DeviceProfile.aclDefaultMode()`
    —— 默认实现返回 `null` = 「这台设备没有这个概念」，此时空值原样上传、**不许自己编一个档位**；
    `ZteGoformProfile` 覆写成 `ACL_MODE_BLACKLIST`（取值与抓包判据一字未动）。
    写侧（`setAccessControlList`）那段兜底**整段删掉** —— `SettingKey.WIFI_ACL` 的 encode 本来就做
    同一件事，两处都兜的后果是「改了 profile 却被客户端这层的硬编码盖住」，判据只留 profile 一份。
  - 这三处的危害是同一形态：不是编译错误，而是**拿别家设备的取值去解析 / 下发本机命令**，
    界面照常显示、测试照常全绿。ACL 那处的具体表现是「界面显示黑名单生效、设备其实在放行所有人」。
  - 顺带：`DataScheduler` 的 `ZteGoformProfile` import 变成未使用、删掉 ——
    该模块从此不再引用任何具体设备的 profile。
  - 验证（沿用 commit 自报）：core 侧 **77 suite / 966 例 / 0 failure**，246 个 task 全部实际执行
    （`--rerun-tasks`）。⚠ 全仓 `testDebugUnitTest` 跑不通、**原因不在本批**
    （`app/ui/UfiBottomDock.kt` 未提交且当前编译不过），所以只验到 core 侧。
- 2026-09-25 **批 41：`WriteOutcome` 从 `:core:goform` 搬到 `:core:device-spi`**，commit `9833920`。
  - 纯搬家、零逻辑变化：四个子类型（`Ok` / `Rejected` / `Failed` / `Unavailable`）与 `val ok: Boolean`
    逐字照搬。**为什么必须先搬**：下一步要在 device-spi 立域接口，而写侧方法的返回类型就是它；
    `core/device-spi/build.gradle.kts` 的硬性约束写明 device-spi 不得依赖 `:core:goform`
    （goform 反过来依赖 device-spi，加进来立刻成环）—— 类型留在 goform，接口签名根本写不出来。
  - 原 KDoc 里「为什么放在 `:core:goform`」那段已作废（理由是「上层只依赖 goform」，而上层现在照样
    看得见 device-spi），照惯例**不删旧结论**，替换成新理由并注明搬迁日期。
    跨模块 KDoc 链接按「看不见就别用方括号」处理：`WriteSpec` 保留（device-schema 是 `api` 依赖），
    `GoformWritePolicy` / `GoformWriteResult` 改成纯文本并注明在 `:core:goform`（否则是死链）。
  - 顺带补两处**早就该显式声明**的依赖：`:core:api` 与 `:core` 都没写 `:core:device-spi`，
    靠 goform 的 `api(...)` 传递可见 —— 而 `:core:api` 的 `CallJsonExt` 公开签名上就有 `WriteOutcome`、
    `:core` 的 `ActionExecutorImpl` 直接用它，都是直接依赖。口径早在
    `core/controller/build.gradle.kts:21-25` 写下（「goform 哪天把 api 改成 implementation，
    本模块就会莫名编译不过」），这两个模块只是一直没人补。
  - 验证（沿用 commit 自报）：core 侧 **77 suite / 966 例 / 0 failure**，246 个 task 全部实际执行。
    引用点全仓 grep 过：11 个 Kotlin 文件各改 1 行 import，没有靠通配 import 带进来的。
    ⚠ 全仓 `testDebugUnitTest` 仍跑不通，原因同批 40。
- 2026-09-25 **批 42（批 A1）：立 `DeviceHub` + `DeviceAdapter`，SIM 域端到端接通**，commit `d5bda4f`。
  - 要解决的问题是**插件接缝与消费接缝不重合**：插件能换传输层（`DeviceTransport`），
    但上层消费的是 `core/goform` 里七个具体类 —— 换传输层没人用得上。本批把「上层只认一个类型」
    这个接缝立起来，并用 SIM 域走通全链路。
  - 新增 `:core:device-spi` 的 `adapter/` 子包：`SimControl`（一个方法，签名照抄
    `GoformSimClient.switchSimSlot`）、`DeviceAdapter`（`id` / `capabilities` / `sim`）、
    `DeviceHub`（三个成员纯转发）。实现 `ZteGoformAdapter` 落在 `:core:device-plugins`
    （唯一允许认识具体协议的模块），方法体一行委派，校验 / 会话重登 / 三态判定全留在原处。
  - **两条设计口径写进了 KDoc，后续每一批都沿用**：
    - **域字段非空**。「这台设备支不支持切卡」的唯一判据是 `capabilities` —— route 的能力门禁在请求
      到达 adapter 之前就回 501。再用一个可空的 `sim` 字段表达同一件事就是**两份判据**，
      早晚分叉到没人说得清哪份是真的。
    - **域接口一批长一个，不预先声明空成员**。声明了没人实现的成员就是死代码：实现方要写一堆
      `TODO()`，调用方看不出哪个域真能用。后续 network / device / wifi / signal / sms 各自一批，
      连实现与调用点一起落。
  - `DeviceHub` 的 KDoc 明写「它现在只有转发，不要以为还干了别的」，以及为什么仍要它：
    上层只依赖这一个类型，换 adapter 时改装配层一处、上层零改动。
  - 接缝：`NetworkDeps` / `NetworkGraph` 去掉 `simClient` 字段换成 `deviceHub`，
    `SimRoutes` 改走 `deviceHub.sim.switchSimSlot`。
  - **顺带清掉一处死分支**：`SimRoutes` 原来有 `val client = simClient` + `if (client == null)`，
    而 `simClient` 的声明类型是**非空**的 —— 那是编译期就永不成立的分支，从来没执行过。删掉之后，
    分支里那句把协议名直接吐给客户端的 `"Goform client not available"` 随之消失。
  - `core/api` 对 device-spi 从 `implementation` 升成 `api`，判据是该文件自己写的那条「公开签名里
    出现的类型要让依赖方看得见」（`DeviceHub` 现在在 `NetworkDeps` / `RouteContext` 的公开签名上，
    而装配层要构造 `NetworkDeps`）。此前能编译是因为 `:core` 自己也声明了 device-spi —— 那是巧合。
  - 验证（沿用 commit 自报）：core 侧 **78 suite / 969 例 / 0 failure**（+1 suite +3 例 = 新增
    `DeviceHubTest`），251 个 task 全部实际执行。⚠ 全仓单测仍卡在 `UfiBottomDock.kt`，只验到 core 侧。
  - **本批刻意没做**：`DevicePlugin` 仍交付四样零件、adapter 由装配层拼（归批 A3）；
    没有守门测试拦「上层不许直接 new `Goform*Client`」（归批 A4）。
- 2026-09-25 **批 43（批 A2a）：device 域 13 个写操作迁进 `DeviceControl`**，commit `aa199cf`。
  - `GoformDeviceClient` 的 13 个 public 方法全部搬成接口方法（grep 核过：public 成员就是这 13 个，
    另两个是 private 的 client 与 writer）。参数名 / 参数类型 / 返回类型逐字照抄，带值域的 KDoc 一并搬
    （`setDhcpSetting` 的 `dhcpType` 是 `"SERVER"` / `"DISABLE"`、`cellLock` 的 `networkType` 是制式名
    而数字 RAT 码只存在于 profile 里）。
  - **返回类型的不齐整刻意保留**：13 个里只有 3 个回 `WriteOutcome`
    （`setRestartSchedule` / `setDhcpSetting` / `cellLock`，它们已经接了值域校验三态），其余 10 个回
    `Boolean`。在这里统一它就不是「纯接缝迁移」了 —— **route 的响应形状会跟着变**。
    这件事写进了接口 KDoc。
  - `changePassword` 的 KDoc 改了一处措辞：原文写「设备要的是 SHA256 大写十六进制」，
    搬到协议无关接口上就变成在契约里规定 goform 的算法 —— 改成「哈希留在实现侧，本接口收明文」，
    其余文字保留。
  - **`SystemController` 与 `ActionExecutorImpl` 收的是具体域接口 `DeviceControl`，不是整个
    `DeviceHub`**：它们只需要这一个域，递整个 hub 等于让它们看见所有域，权限比需要的大。
  - 其余接缝：`DeviceAdapter` 加 `device` + `DeviceHub` 加转发；`ZteGoformAdapter` 加私有内部类
    `GoformDevice`，13 个方法各一行委派；`NetworkDeps` / `NetworkGraph` 去掉 `deviceClient` 字段
    （`GoformDeviceClient` 没有读方法，整体迁走无残留）；`DeviceRoutes` 11 处调用点改走
    `deviceHub.device` —— `rebootDevice` / `shutdownDevice` **不在其中**，route 里那两个走
    `systemController`（有 shell 兜底），原样保留。能力门禁、参数解析、rat/enabled 兼容分支、
    `respondRejected` 一字未动。
  - 验证（沿用 commit 自报）：六个目标（device-spi / device-plugins / api / goform / controller / core）
    编译与单测全绿、239 个 task 全部实际执行；`DeviceHubTest` 5 例（新增 2 例：device 域转发 +
    入参返回值原样透出）、`DeviceRoutesTest` 4 例、71 个 testsuite 全部 0 failure / 0 error。
    ⚠ 提交时 `:core:collector` 已被并行改动破坏（`SystemCollector.kt` 的 KDoc 里
    `GET /api/system/` 后面那个星号斜杠开了嵌套块注释，把 70 行之后整个文件吞成注释），
    所以本批落盘后**没能再跑一遍下游模块**，上面的绿是破坏发生之前那一轮的实测结果。
  - 留给后续：`setPerformanceMode(mode: Int)` 的 0/1 编码只在 route 与本接口之间存在
    （profile 里没有第二份定义），真要干净是 `PerformanceMode` 枚举 —— 那是形状变更；
    `ZteGoformAdapter` 构造参数已 4 个，六域全进来会是 8 个，那时值得换成 clients bundle。
- 2026-09-25 **批 44（批 A2b）：network / wifi 写侧迁入接口，频段「全部」改用意图表达**，commit `198bbd4`。
  - `NetworkControl` 收 `GoformNetworkClient` 的 **11 个写方法**，一个不缺。该类没有任何设备读方法，
    所以 `networkClient` 字段整体去掉（形态同 A2a 的 `deviceClient`）。
  - **`lteAllBands()` / `nrAllBands()` 刻意不上接口**：它们返回的是「下发给设备的频段全集掩码」，
    是 goform 的实现细节 —— 放到协议无关契约上等于规定别人家设备也得用掩码串表达「全部频段」。
    改法是新增 `BandSelection`（`All` / `Only(bands)`），`NetworkControl` 收**意图**；
    `NetworkController` 原来那句 `unlockAll -> networkClient.lteAllBands()` 搬进了 adapter
    （`All` 分支仍旧去问 `lteAllBands()` 拿掩码再下发，取值逐字未变）。
    null 折叠 + WARN **仍留在** `GoformNetworkClient.allBandsOf` —— 那是唯一的折叠归属地，
    没有在别处重新实现第二份。
  - **`lockLteBands` / `lockNrBands` 保持两个方法、不合并**：现有调用点分别取两个 `WriteOutcome`、
    分别判 `ok`、分别看是不是 `Rejected`。合并会把错误处理粒度从「两个 RAT 各自一份结论」压成一份，
    那是行为变更。
  - `WifiControl` 本批只收 **7 个写方法**。`setAccessControlList` **没迁**：签名用到 `AclEntry`，
    那个类型定义在 `:core:goform`，搬进契约层会连带牵动读侧（`getAccessControlList` 返回的
    `AclSnapshot` 用同一个类型），而读侧本批不动 —— `WifiRoutes` 的 acl 端点继续直接用 `wifiClient`。
    `wifiClient` 字段**保留**（与 `deviceClient` 不同）：那个类一半是读方法，返回裸 `JsonObject` /
    `Pair<ByteArray, String>` / goform 自己的快照类型，搬上契约等于冻结 goform 的响应形状。
  - **四处已知契约泄漏：签名保留 + KDoc 标注** —— `setBearerPreference`（值域是中立别名 ∪ 设备原值）、
    `setConnectionMode`（`"auto_dial"`）、`calibrateFlow` 的 `target`（`"data"` / `"time"`）、
    `setWifiBand` 的 `chip`（`"chip1"` / `"chip2"`）。全是设备侧词汇，而且都是对外入参
    （route 原样透传或在 route 里归一化），改成中立枚举会动对外契约，不在纯接缝批次里做。
    每处 KDoc 写明「这是已知泄漏、接第二种协议前必须先在这里立中立别名」。
  - `NetworkController` / `ActionExecutorImpl` 的参数换成具体域接口（`NetworkControl` / `WifiControl`），
    不是整个 `DeviceHub` —— 口径同 A2a。
  - 新增 `ZteGoformAdapterBandSelectionTest`：断言 `All` 最终传给客户端的取值就是
    `lteAllBands()` / `nrAllBands()`。**刻意 mock `GoformNetworkClient` 而不是自己写假
    `GoformTransport`** —— 后者有守门测试钉着「不许跨出 `core/goform`」，测试源码也算越界。
  - 验证（沿用 commit 自报）：core 侧 **80 suite / 987 例 / 0 failure**（较 A2a 的 78 / 971：
    +2 suite +16 例），249 个 task 全部实际执行（`--rerun-tasks`）。
- 2026-09-25 **批 45（批 B1）：读侧「已过归一化」钉到类型上，绕过归一化编译不过**，commit `68606ce`。
  - 要解决的是什么：`GoformSignalClient` 的 16 个查询方法全返回裸 `JsonObject?`，
    「字段名必须归一化成 canonical 名」靠方法体里那句 `fields.normalize(GROUP, raw)` 保证，
    **不靠类型**。接飞猫时写一个同样返回 `JsonObject?`、忘了调 `normalize` 的实现：编译通过、
    接口「实现」了、route 拿到的字段名全错、前端静默空值、**没有任何测试会红**。
  - 新类型 `NormalizedFields`（`:core:device-schema`），唯一构造途径是归一化本身，
    **三层保证、没有一层靠纪律**：① 构造函数 `private`（同包的 `FieldNormalizer` 都碰不到）；
    ② 唯一入口 `internal fun of`，作用域是 device-schema 这一个模块 —— 所有消费方
    （goform / api / scheduler / core）都在模块外，「接新设备时 new 一个交差」不是「不该写」，
    是**写了编译不过**；③ 模块内唯一调用点就是新增的 `FieldNormalizer.normalizeToFields`。
    实测到一个 JVM 细节并写进注释：private 构造 + 伴生工厂会让 Kotlin 生成合成桥构造函数
    （字节码里 public、Kotlin 侧调不了），所以断言钉的是「不许存在一参公开构造函数」；
    局限（Java 侧硬传 null marker 理论可绕）也写清了 —— 本仓没有 Java 生产代码，不为此再加一层。
  - **语义是「过了归一化层」，不是「字段名一定是 canonical」**：查清了排障开关的实际行为 ——
    `field_normalization_enabled=false` 且该组不在 `NORMALIZE_ALWAYS` 里时，
    `GoformFieldMapper.normalize` 直接 `return raw`，**同一个实例、字段名是设备原名、
    归一化器一次都没跑**。所以这个类型承诺的是「没有绕过闸门的旁路」，不是 canonical。
    区别写在三处（类 KDoc、`normalizeToFields` 的边界说明、`GoformSignalClient` 类 KDoc），
    并用两条测试钉住：透传分支解包后必须是**同一个对象**、设备原名必须还在。
    类型仍然静态可确定 —— 开关只改「包着的内容」，不改「有没有过闸门」。
  - 分类判据是方法体里有没有 `fields.normalize`：**8 个换类型、8 个保持 `JsonObject?`**。
    我给的名单被核出两处错：`getDeviceVersion` 与 `getNeighborCellInfo` **都调了 normalize**
    （它们符合的是另一条判据 —— 硬编码 cmd 名、不走 profile 命令表，那和「不归一化」是两件事）；
    「不走命令表」的实际是 5 个，但成员里还有 `getTrafficStats`。
    保持原类型的 8 个各有理由并都写进 KDoc，其中两条值得记：`getTrafficStats` 的返回值是
    「设备原始响应 + 归一化结果覆盖」的**混合体**（为留住未登记的 `realtime_*`），
    套上这个类型是谎报 —— 纯出口是 `getDataUsage`；`getNeighborCellInfo` 调了 normalize 但
    从结果里**取出一个成员**返回，出去的是数组。
  - **对外 JSON 零变化**：`DataHub` 那 4 个公开方法的返回类型全部保持 `JsonObject` 不变
    → route 无连带改动，只在绑定处加 `?.values`。`GoformNormalizeAlwaysTest` 那 11 条字面量断言
    **一字未改**（它本身就是「对外 JSON 没变」的判据），另加 2 条端到端：用真机夹具比 `equals`（内容）
    与 `toString`（键序 = 序列化字节序列）。
  - 触点：生产代码 35 行级改动 + 1 新文件 + 2 个 build 文件，共 12 个文件。
    `:core:goform` 的 device-schema 从 `implementation` 升 `api`（新类型进了公开返回签名）、
    `:core:api` 显式声明 device-schema。`DataScheduler` 与 `SignalCollector` **一个字符都没动**
    —— 它们调的 `getTrafficStats` / `getSignalInfo` 按上表都保持原类型。
  - 验证（沿用 commit 自报）：core 侧 **80 suite / 994 例 / 0 failure**（较 A2b 的 987：+7），
    249 个 task 全部实际执行。
- 2026-09-25 **批 46（批 B2）：signal 域进接口，`:core:collector` 不再依赖 `:core:goform`**，commit `428f889`。
  - **结构性里程碑**：`core/collector/build.gradle.kts` 里 `implementation(project(":core:goform"))`
    **已移除**。此前它对 goform 的唯一类型引用是 `SignalCollector` 的构造参数
    `signalClient: GoformSignalClient?`，换成域接口 `SignalSource?` 之后这根绑带就断了。
    移除前核过全模块 main 源集：4 个 `.kt` 文件不再 import 任何 goform 符号，
    剩下 37 处 "goform" 命中全是注释与局部变量名。
  - 新增 `SignalSource`（16 个查询 + `profileId`）。返回类型**完全沿用 B1 之后的现状**，
    一个都没「顺手统一」：8 个 `NormalizedFields?`、5 个 `JsonObject?`、1 个 `JsonArray?`、
    1 个非空 `JsonObject`；B1 加的「原样透传，未归一化」标注全部保留。
    三个诊断方法（`getFullStatus` / `getFullStatusMasked` / `diagnoseFieldCoverage`）**进了接口**，
    判据是「它们是按设备的排障出口而不是 goform 专有」—— 任何设备都能回答「后台有哪些字段」
    「profile 命中了哪些 source」。不迁的代价是 `/api/diagnose` 的 `field_coverage` 与
    `GET /api/device/goform` 两条路各留一根具体类绑带。
  - **刻意不做：signal 的第 1 层字段映射没有搬走。** 原本想让它「与其它五个域对齐」（归一化搬到
    客户端层），重新判断后否掉，三条理由：① `SignalCollector` 的第 2 层（服务小区 PCI 去邻区数组匹配）
    与第 3 层（Telephony 兜底）**需要原始响应与归一化结果同时在场**，搬走第 1 层会把三层编排拆开；
    ② profile 本来就按设备一份，「消费方拿 profile 做第 1 层映射」对飞猫同样成立，它不是「只有 goform
    能用」的设计；③ 搬迁的风险（对外 signal 字段名静默变化）远大于「看起来整齐」的收益。
    这处不对称**显式记了两处**：`SignalSource` 类 KDoc 写明理由，`DataHub.kt:54-55` 那条
    「归一化在设备客户端层」的纪律旁补了指向 `SignalSource` 的例外说明（纪律本身没改）。
  - 风险点怎么验的：这批唯一的风险是「对外 signal 字段名静默变化」。新增
    `core/collector/src/test/.../SignalCollectorTest.kt` 5 条，用新铺的真机形状夹具
    `zte_f50_goform_signal_raw.json` —— 输出 key 集合与 28 个硬编码 `DeviceFields.Signal` 常量做
    **精确集合相等**（不是包含）、四个抽查点（`Nr_snr` / `Lte_pci` / `network_information` /
    `realtime_rx_thrpt` 这些设备原名不许出现）、取值形态逐项核（rsrp 是 Int、cell_id 是 String、
    `band_label = "n78"`）、断言真的走了域接口（`source.calls == 1`）。
    `SignalSource` 的测试替身是**手写**的而不是 mock —— 顺带证明「域接口在 goform 之外也能实现」。
  - 接缝：`DataHub` 的 `signalQuery` receiver 从 `GoformSignalClient` 换成 `SignalSource`，
    16 处 `dataHub.signalQuery { xxx() }` 调用点**零改动**（方法名逐个相同）；
    `NetworkDeps` / `NetworkGraph` / `RouteContext` 去掉 `signalClient` 字段、route 从 hub 取；
    `DataScheduler` 只改 import 与字段类型两处、注释一行没删。触点 18 个文件约 38 处
    （含 3 个新建文件 + 1 个夹具）。
  - 验证（沿用 commit 自报）：core 侧 **81 suite / 1000 例 / 0 failure**（较 B1 的 994：+6），
    252 个 task 全部实际执行。遗留：`ZteGoformAdapter` 构造参数已 7 个，收成 bundle 是批 A3 的事。
- 2026-09-25 **批 47（批 A4）：守门测试锁住「上层不许出现 Goform」；A3 核后停手并记录原因**，commit `29682c2`。
  - 新增 `UpperLayerGoformFreeGuardTest`。判据：`core/api` / `core/collector` / `core/scheduler` /
    `core/controller` / `core/src` 五处的 main 源码里，**剥掉注释与字符串字面量之后**不许出现含
    `Goform` 的标识符。实测扫到 129 个文件（api 77 / collector 5 / scheduler 5 / controller 30 /
    core 12），自检断言 `size >= 100` 防「一个文件没扫到却全绿」。
  - 剥法照抄既有的 `GoformTransportVisibilityGuardTest` 再加几步，每一步都对应踩过的坑：
    - 块注释**按深度计数**剥（Kotlin 块注释可嵌套）；
    - 字符串字面量整段剥，但**保留模板里的表达式**（`DataScheduler` 的性能日志里就有真引用
      `GoformQoS.queryTotalPermits`）；
    - 字符字面量单独跳过 —— 代码里有单引号包双引号的写法，不认它会把后面整段代码当字符串，
      **主断言会静默变绿**；
    - 大小写敏感：小写 `goform`（包名段、配置键、`/api/device/goform` 路径）不在判据内。
  - 断言是**逐文件精确相等**：多出未登记的 → 红；**登记了但代码里已经没有 → 也红**
    （欠账还完必须把它从清单里删掉，否则清单会慢慢变成谎话）。
  - allowlist 19 个文件分 5 组，每组写明「为什么现在还不能消」：A/B `GoformQoS` /
    `GoformSessionLog` / `qosGoform*`（住 `:core:common` 的公共设施，消它要改配置键 `qos_goform_*`
    与 `/api/config` 对外字段，是独立一批）；C `updateGoformPassword`（**`DeviceTransport` 自己的
    方法名**，契约层改名，独立一批）；D 各模块局部命名（DTO / 私有函数 / Activity 控件字段，
    零跨模块耦合）；E **真正的债**：`GoformWifiClient`（WiFi 读侧）、`GoformSmsClient`（sms 域）、
    `ComponentFactory` 的 8 个 —— 就是 A3 卡住的那两条。
  - Gradle 输入追踪的坑写进类 KDoc：本类扫别 module 的源码，增量构建会 `UP-TO-DATE` 一声不响地不跑，
    收尾必须 `--rerun-tasks`（点名本仓「97/97 假绿」的先例）。
  - **做过阴性对照**：在不在 allowlist 里的 `SimRoutes.kt` 临时加一行 `GoformClient` 类型的 private
    字段 → `2 tests completed, 1 failed`，报错文案直接点出文件与越界标识符；自检用例同轮保持绿
    （证明红的是判据命中、不是扫描器崩了）。撤回后 `git diff` 无差异。
    另做了反向验证：在**注释**里写 `GoformWifiClient` 等符号名，测试**没有**变红 ——
    剥注释确实生效，正是旧守门测试第一版栽的那个坑。
  - **同轮 A3 核后停手，只落了注释。** 原判断「那句 `as? GoformClient` 不是类型需求」是对的
    （装配层用到 `GoformClient` 独有成员 0 个），但**把 7 个 new 搬进插件这条路当时走不通**，
    卡点不在插件接口：`NetworkGraph` 的产物里还有两个具体类型 —— `wifiClient: GoformWifiClient`
    （WiFi 读侧与 `DataHub.wifiQuery` 的 receiver）与 `smsClient: GoformSmsClient`
    （`SmsController` / `LocalSmsChannel` / `DataScheduler` / `LocalSmsDelivery.classify` 四个消费点）。
    要让插件把它们交回装配层，`DevicePlugin`（住 device-spi）需要一个能承载它们的返回类型 ——
    而 device-spi 不能依赖 `:core:goform`（成环），星投影退化成 `Any` 再 cast 等于把转型换个地方写。
    唯一剩下的变体（adapter 内部自己 new、装配层另 new 一个读侧 `GoformWifiClient`）**会改行为**：
    `GoformWifiClient` 带实例级可变状态（`@Volatile var lastQrCodeFailure`、mapper 的
    `warnedNormalizeAlwaysGroups`），而写侧 `setWifiEnabled` → `readCurrentWifiChip` →
    `getWifiSettingsMerged` → `fields.normalize` 确实会走进 mapper；仓里对 platform / tuning 反复
    强调「整图只造一份」，这里造两份是同一类定时炸弹。
    **于是定下正确顺序：先迁 WiFi 读侧（含 `setAccessControlList` + `AclEntry` 类型搬家）与 sms 域，
    再回来做 A3** —— 那时 `NetworkGraph` 只剩 `goformClient: DeviceTransport` + `deviceHub`，
    转型自然消失。`profile()` / `createTransport()` 两个都**保留**：`profile()` 有 7 处消费者全在
    选型逻辑里（commandProfile 兜底、configuredId 旧口径匹配、四个 selection 取值的日志），
    动它就是动选型；`createTransport()` 的产物仍要进 `NetworkGraph.goformClient` 给
    `PairingRoutes` / `DeviceRoutes` 的 `updateGoformPassword` 用。
    代码改动只有注释：`ComponentFactory` 的原转型注释**一行没删**（它还没作废，转型还在），
    在后面追加复核结论；`ZteGoformAdapter` 的 KDoc 补「批 A3 复核：仍是 7 个参数，收束推迟」+ 理由。
  - 验证（沿用 commit 自报）：守门测试 2 例全绿；全量 core 侧 **83 suite / 1002 例 / 0 failure**，
    `--rerun-tasks` 全部实际执行。
- 2026-09-25 **批 48（批 C1）：sms 域整体迁进 `SmsControl`，六个域全部到齐**，commit `76b0ebd`。
  - sms 是最后一个没有域接口的功能域。本批迁完之后 `DeviceAdapter` 的六个域
    （`sim` / `device` / `network` / `wifi` / `signal` / `sms`）全部到齐。
  - 新增 `SmsControl` 5 个方法：`getSmsList` / `sendSms` / `deleteSms` / `markSmsRead` / `getSmsMeta`。
    `SendVerdict` / `SendOutcome` / `SmsMeta` 三个嵌套类型从 `GoformSmsClient` 搬到 device-spi 顶层
    （语义是协议无关的，**直接删旧 + 改 import、不留 typealias**）。
  - 消费点全部换成 `SmsControl`：`SmsController` / `LocalSmsChannel` / `LocalSmsDelivery` /
    `DataScheduler` / `BackendService`。其中 `LocalSmsDelivery` 的签名原来直接吃
    `GoformSmsClient.SendOutcome` —— 类型搬家之后改 import 就行，它没引用 `GoformSmsClient`
    的任何其它成员。
  - 守门测试 allowlist 同步更新：sms 相关的 3 处欠账已还完（`SmsController` 只剩私有方法名
    `resolveGoformSmsId`、`DataScheduler` 剩 `GoformQoS` / `collectGoformTraffic`、
    `ComponentGraph` 去掉 `GoformSmsClient`）。
  - 验证（沿用 commit 自报）：core 侧 **82 suite / 1003 例 / 0 failure**，252 个 task 全部实际执行。
- 2026-09-25 **批 49（批 C2）：WiFi 读侧迁进 `WifiControl`，上层再无 `GoformWifiClient`**，commit `afb94d0`。
  - 批 A2b 只迁了写侧（读方法返回裸 `JsonObject` / 自定义类型，且 `AclEntry` 定义在 `:core:goform`）；
    B1 把读侧类型收紧之后前提具备，本批把读侧全部迁完。
  - 类型搬家：`AclEntry` / `AclSnapshot` 从 `GoformWifiClient` 搬到 device-spi 顶层（语义是协议无关的
    MAC 黑白名单），直接删旧 + 改 import、不留 typealias，口径同 C1。全仓引用 4 处逐个核过；
    app 侧的 `WifiAclEntry` 是客户端自己的 model，**同名不同物、未动**。
  - `WifiControl` 补齐到 **16 个成员**：`setAccessControlList`（之前因类型依赖跳过的那个写方法）+
    7 个读方法（`getAccessControlList` / `getWifiSettings` / `getWifiSettingsMerged` /
    `getConnectedClients` / `getWifiModuleInfo` / `getCurrentWifiConfig` / `getWifiQrCode`）+
    `lastQrCodeFailure`（**我的任务书漏了这个，子代理核出来的**：`WifiRoutes` 的 `/qrcode` 端点要用它
    填 503 的真因，不上接口这批编不过）。返回类型逐字照搬现状、一个都没「顺手统一」。
    `getCurrentWifiConfig` 原来是 `internal`，为了让另一个模块的 adapter 能委派放开成 `public`
    （纯可见性、无行为变化）；它零上层消费点、键名是设备 CamelCase 原名 ——
    这条已知泄漏写进了接口 KDoc。
  - 上层彻底清干净：`DataHub` 的 `wifiClient` 与 `wifiQuery` receiver 换成 `WifiControl`；
    `RouteContext` / `NetworkDeps` / `ComponentGraph.NetworkGraph` 去掉 `wifiClient` 字段；
    `WifiRoutes` 12 处调用点改走 hub；`ComponentFactory` 的 `attachDeviceEventWatcher` provider 同步。
    核过：`core/api` / `core/collector` / `core/scheduler` / `core/controller` 里 `GoformWifiClient`
    **零引用**，`core/src` 只剩 `ComponentFactory:952` 那一行构造（A3 才搬）。
  - 守门测试 allowlist 相应收缩：`RouteContext` 与 `ComponentGraph` 两条整条删掉、`DataHub` 从两项收成
    一项。`ComponentFactory` 的 8 项**一个没动** —— 核实后确认 `GoformWifiClient` 仍出现在那行构造里，
    删了守门测试会因「登记了但代码里没有」变红。
  - **行为等价的关键一点**：`GoformWifiClient` 全仓仍只有**一个**实例（装配层造、只递给 adapter），
    `lastQrCodeFailure` 那个 `@Volatile` 状态与 mapper 的 warn-once 状态没有被复制成两份 ——
    这正是 A3 第一次核时判定「不能让 adapter 自己 new」的那个理由。
  - 验证（沿用 commit 自报）：core 侧 **82 suite / 1004 例 / 0 failure**（较 C1 的 1003：+1，
    即 `DeviceHubTest` 新增的 wifi 读侧转发断言），252 个 task 全部实际执行。
- 2026-09-25 **批 50（批 A3）：插件自己交付 `DeviceAdapter`，装配层删掉向下转型，铺路收尾**，commit `25c4419`。
  - 架构铺路最后一批。两轮前（批 47）核过一次并停手，当时卡在 `NetworkGraph` 还要把
    `GoformWifiClient` 与 `GoformSmsClient` 交给上层；批 C1 与 C2 把这两个域迁完之后前提具备。
  - `DevicePlugin` 新增 `createAdapter(transport: DeviceTransport, commandProfile: DeviceProfile,
    normalizeProfile: DeviceProfile?): DeviceAdapter`。**transport 由装配层传入而不是插件自己再造一份**
    —— 整图只许一份传输层实例（会话 cookie / 版本号 / 登录退避 / 连接池），
    `NetworkGraph.goformClient` 与 adapter 必须是同一个对象（`PairingRoutes` / `DeviceRoutes` 的
    `updateGoformPassword` 走的就是它）；KDoc 明写「实现里不许再调一次 `createTransport()`」。
    两份 profile 就是 `ZteGoformAdapter` 真正需要的全部：signal / wifi 吃双 profile，
    network / device / sim / sms 只吃非空 `commandProfile`。
    `createTransport()` / `profile()` / `platform()` / `tuning()` 四个成员一行未动。
  - **向下转型移进插件**：装配层那句 `transport as? GoformClient ?: error(...)` 删掉了，
    转型移到 `ZteF50Plugin.createAdapter` —— 这是合法位置：**插件自己造的 transport，它知道是什么类型**。
    仍用 `as?` + `error` 而不是硬 `as`，文案写清「本插件的 `createTransport()` 造的就是 `GoformClient`，
    走到这里说明有人换掉了 transport 实现」。装配层现在只有两行：造 transport、
    `DeviceHub(runtime.plugin.createAdapter(...))`。
  - 六个客户端的 new 搬进 adapter：`ZteGoformAdapter` 加了一个 **5 参数公开次构造函数**
    （id / capabilities / transport / 两份 profile），六个 `Goform*Client` 在委派实参里各 new 一次、
    全部共享同一份 transport。参数值域与搬家前装配层那六行逐字一致，所以 `lastQrCodeFailure` 与
    mapper 的 warn-once 状态仍然只有一份。
  - **一处与我设计的偏差，经核可接受**：原 8 参数构造**没删**，降成 `internal` 主构造。
    理由是批 A2b 的 `ZteGoformAdapterBandSelectionTest` 要 mock `GoformNetworkClient` 才能把
    「adapter ↔ 客户端」那条边界单独暴露出来；只留 5 参数就得改成 mock 传输层，
    而 `GoformTransport` 有可见性守门测试钉着 —— 那等于把一条有效的行为守门测试降级。
    `internal` 让装配层跨模块拿不到它，「造客户端不出 device-plugins」仍由编译器保证。
  - 守门测试 E 组清零：`UpperLayerGoformFreeGuardTest` 的 `ComponentFactory` 那条 8 项整条删除，
    E 组 KDoc 改写成「已全部还完」并留下历史（原来欠什么、A3 第一次核为什么停手、C1 还 sms、
    C2 还 WiFi 读侧、本批还最后 8 项）。**做过阴性对照**：在 `buildNetworkGraph` 临时加一行
    `GoformClient` 类型的 val → 守门测试 1 failed 并点名文件与越界标识符；撤回后 `git diff` 无残留。
    `ComponentFactory` 剩下的 8 处 "Goform" 全是小写配置键（`settings.goformIp` / `goformPort`）、
    字段名 `goformClient` 与一条日志字符串 —— 没有一个是 goform 的类型；日志那句刻意不动，
    改它就是改输出。
  - `PluginContractTest` 补 2 条：对 `PluginRegistry.ALL` 每个插件跑「`createAdapter` 交出的 adapter 的
    `id` / `capabilities` 必须与插件自身一致」、「六个域字段逐个非空」（防 lateinit 之类），
    且后者**传 `normalizeProfile = null`**，顺带钉住「归一化关掉时 adapter 照样造得出来」。
  - 验证（沿用 commit 自报）：core 侧 **82 suite / 1006 例 / 0 failure**（较 C2 的 1004：+2），
    252 个 task 全部实际执行（`--rerun-tasks`）。
- 2026-09-25 **文档轮（本轮，不标批号，不改任何代码）**：按批 40 ~ 批 50 的 commit 正文与**现场读代码**
  回填 §3.2 / §3.3 的标废框、§6 的 2.5 / 2.7 注记、§9 执行记录（即上面 11 条）、§15 待办池
  （5 条欠账标「已还」+ 新登记 P1-43 ~ P1-46）。
  - 本轮**没有重跑 Gradle**：上面各批的测试数字沿用各自 commit 的自报结果，本轮核的是
    **代码形状与计数**（成员数、参数个数、allowlist 条数、依赖声明有无），逐项在代码里数过。
  - ⚠ 本轮核出一条**上一轮漏记的批次**：`dcc64f9`（「批 O 补完 sms / traffic_limit / band_lock
    三域的置灰」）在 §9 里没有任何记录，所以 §7 的 3.5 与上面批 39 条目里「未完」那句
    **可能已经不成立**。本轮范围只覆盖批 40 ~ 批 50，**没有动 3.5 的状态标记**，
    留给下一轮 app 侧回填时按 `dcc64f9` 的实际内容补记。



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
- 2026-09-22 **阶段 0 批 16**（排障开关实机验证 → 验出 P0 → `45b86ff` 修复）。
  1. **实机验证（用户做的，第 4 层）**：`field_normalization_enabled=false` + 重启后台服务，
     仪表盘 / 网络 / WiFi 三页**仍有数据**，`/api/diagnose` 的 `device_profile` 报「归一化已关闭」
     +「生效 profile：无」，app 诊断页「运行时实际状态」显示「已关闭 · 原样透传设备字段」。
     → 收尾盘点第 3 项**通过**。**唯一失效的功能是流量限额**（用户原话：
     「关闭后目前只发现流量限额相关的功能失效异常」）。
  2. **那个 P0 的真实爆炸半径**（三条都回代码核过，比「显示不对」严重得多）：
     - `monthly_rx_bytes ← monthly_tx_bytes` 是 `ZteGoformProfile.kt:255-266` 的**刻意交叉绑定**
       （ZTE 固件把上下行报反，2026-09-01 实测值写在那段注释里）。豁免之前关掉开关，
       `DataScheduler.collectGoformTraffic()`（`:1122-1148`）拿到的是设备原值，
       随后 `recordHourlyUsage(rx, tx)`（`:1224`）→ `trafficHourlyDao().addUsage(...)`（`:1287`）
       **把颠倒的上下行落库** —— 开关改回 true 也修不回来。
     - `limit_bytes` / `limit_value` / `limit_unit_display` 是 `splitDataVolumeLimit`
       （`ZteGoformProfile.kt:1513-1530`）从复合串 `data_volume_limit_size`（形如 `"470_1024"`）
       拆出来的**派生键，设备上不存在任何同名字段**，所以关掉归一化后哪一层都兜不到。
     - `TrafficLimitMapper`（`core/api/.../TrafficLimitMapper.kt:32-45`）取不到就填默认值
       （`enabled=false` / `limit_value=""` / `limit_unit_display="GB"` / `limit_bytes=0` /
       `alert_percent="80"` / `auto_clear=false` / `clear_date="1"`）→ 症状是
       「**字段一个不缺、没有报错、值全错**」；`DataScheduler.checkTrafficLimitThrottled()`
       （`:868-890`）拿到 `limit_bytes=0` 后 `if (limitBytes <= 0L) return`（`:881`）
       → **流量预警与到阈值自动关网静默失效，一行日志都不打**。
  3. **修法（`45b86ff`，改 3 个文件）**：`GoformFieldMapper.normalize()` 的闸门
     （`:112-116`）加 `NORMALIZE_ALWAYS` 清单（`:331`，当前只有 `FieldGroup.TRAFFIC_LIMIT`），
     命中时经 `normalizeAlwaysProfile()`（`:137-150`）回落到**非空的 `commandProfile`** 继续归一化，
     其余 9 组照旧 `return raw`。豁免生效时**按组打一次 WARN**（`ConcurrentHashMap.newKeySet()`
     的 `add` 返回值当原子判据；这条路在 15s 流量循环上，每次都打会灌满 `app.log` 并挤干
     `AppLogger` 那 500 条崩溃现场缓冲）。WARN 出口做成可注入参数，理由与
     `GoformClient.base64DecodeOrEmpty` 的 `onError` 同一条（`AppLogger` 在 JVM 单测里直接抛）。
     判断依据与被否决的两个替代方案见 **§11.13**。
  4. **测试**：新增 `GoformNormalizeAlwaysTest` **9 例**（豁免组真的归一化 3 条 / 非豁免组逐字透传
     2 条 / WARN 打且只打一次 2 条 / `enabled`·`profileId`·`coverageReport`·`maskDump` 不受影响 2 条），
     夹具复用 `core/device-schema/src/test/resources/zte_f50_goform_traffic_raw.json`（跨模块按路径找，
     刻意不复制第二份）。`:core:goform:test` **86 → 95 全绿**，`GoformCommandTableGuardTest`
     仍 **9 例**未动（本轮 `@Test` 计数逐文件数过 = 95）。
  5. **同一批的 UI 轮**（与 P0 无关，用户裁决）：app `b5df249` 把 WiFi 弹窗的频段
     （原 `UfiScrollableTabRow`）、加密方式（原 `UfiDialogChipSelector`）、最大连接数
     （原 `UfiDigitField`）**统一换成公共下拉 `UfiDropdown`**；最大连接数选项 =
     `listOf<Int?>(null) + 1..10`，`null` 显示「保持不变」，草稿类型 `String → Int?`，
     随之删掉 `maxStaInvalid` / `maxLength=2` / 越界文案。
     **原来「二选一用胶囊滑块」那条设计决策被推翻**，`WifiSettingsDialog.kt:345-350`
     保留了原判断 + 推翻事实 + 接受的取舍。
     web `8421a19` 把频段 `n-radio-group → n-select`、最大连接数 `n-input-number → n-select`，
     「留空 = 不修改」改成显式「保持不变」档与 app 对齐；因为 naive-ui 对 `value: null`
     **不会高亮选中项**，控件层用哨兵 `0`、在报文边界由 `wifiMaxStaNumForPayload`
     （`web/src/api/contract.ts:782`）折成 `null`，`0` 进不了请求体。
     **发出去的值仍是 `chip1`/`chip2` 与数字，界面文案不参与传输；两端基线均未增。**
  6. 本轮文档同步的核对方式：逐条回代码查（闸门实现、`NORMALIZE_ALWAYS` 的内容、
     `FieldGroup` 共 10 组、`@Test` 逐文件计数、`TrafficLimitMapper` 的七个默认值、
     `checkTrafficLimitThrottled` 的 `return`、两端 WiFi 控件的实际组件名），
     **不照抄 brief** —— 也正因此抓到了下面两处措辞要改（见 §11.13 第 4 节）。


### 阶段 0 收尾盘点（2026-09-22，批 13 之后）

**结论先写**：阶段 0 的**代码工作已经全部落地**，但**阶段 0 还不能算完成** ——
四项真机验证**只过了第 3 项**（2026-09-22 实机执行，结论「通过」，但同时验出一个 P0，
已由 `45b86ff` 修掉，剩余风险见 §15 的 P1-28），另外三项一项都没做（§14.3 第 3 层与 §14.4 第 4 层），
按 §14.6 的纪律「四层里有任何一层没过就不能把阶段标 `[x]`」，整个阶段 0 只能标 `[~]`。

#### 0.1 ~ 0.8 的最终状态与对应 commit

| 子项 | 状态 | commit | 备注 |
| --- | --- | --- | --- |
| 0.1 `SettingKey` 补齐写命令 | `[x]` | 批 1 / 批 1b（`36fa526` 之前的登记轮）+ 批 14/15（`WIFI_BAND`） | 最终 **29 项**（2026-09-23 实测；`USB_MODE` 已删，SSID/口令合并为 `WIFI_AP_CONFIG`，`WIFI_BAND` 由 P1-26 新增） |
| 0.2 新增 key 登记 `WriteSpec` | `[x]` | 同上 | `writeSpecs` 与 `SettingKey` 逐项对齐 **29 : 29**，无孤立 key |
| 0.3 写调用点改走 writer | `[x]` | `36fa526`（11 处）+ `118ed84`（WiFi 3 处） | `core/goform/src/main` 里 `"goformId" to` 只剩 `LOGOUT` 1 处（归阶段 1）；另有 2 处字符串形态的登录命令该 pattern 抓不到 |
| 0.4a mapper 双 profile + 守门测试 | `[x]` | `9fa0473` | 纯结构准备、**零行为变化**；守门测试当轮 8 条 |
| 0.4b 命令表真正切过去 + 删 fallback | **`[~]`** | `680fbae` | 第1、2 层 ✓；**第 3 层未验（无真机）** → 解锁条件见 §4 的 0.4b |
| 0.5 设备值域搬进 profile | `[~]` | `090fcad` + `118ed84` + `858a9c9` | WiFi 固定枚举与 base64 方向已搬；**频段全集（P0-1）与二维码文件名模板（P1-5）已裁决推阶段 2，对应任务 2.9 / 2.10** |
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
**第 3 项 2026-09-22 已执行并通过，而且当场验出一个 P0**（见该项下的结论）——
这本身就是「不验的后果」那一栏最好的证据：单测四条替身全绿，坏的那块一条都没碰到。

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
   —— **`[x]` 2026-09-22 已执行，结论「通过」**（唯一失效项是流量限额，已修，见下面「实测结论」）
   - **入口 2026-09-22 起才存在**：此前这个键没进 `AppSettings.toMap()`、`PUT /api/config` 也没登记，
     只有「导入备份」改得动，所以这一项一直做不了。现在两处都补齐了，
     web 在「设置 › 通用」第三个排障开关、app 在「诊断信息 › 字段覆盖率」卡上方。
   - 把开关设成 false、**重启后台服务**（该值只在构造组件图时读一次；
     `needs_restart` 不会提示这一点，见 §15 的 **P1-27** —— 漏了重启这一步测出来的结果是假的），然后：
     仪表盘 / 网络 / WiFi **三个页面仍有数据**，且 `/api/diagnose` 的
     `device_profile.normalization_enabled` 为 **false**（app 的卡片会把这个值作为
     「运行时实际状态」直接显示，不必手动调接口）。
   - 验完记得改回 true 并再重启一次 —— 否则后续所有只读面都停在排障形态。
   - **实测结论（2026-09-22）**：上面三条判据**全部满足** —— 三页有数据、`device_profile` 报
     「归一化已关闭」+「生效 profile：无」、app 的「运行时实际状态」显示
     「已关闭 · 原样透传设备字段」。所以 0.4b 最担心的那个后果（关掉归一化 → 只读面全空）
     **没有发生**，`commandProfile` 顶命令表这条设计在真机上成立。
   - **但验出一个 P0（已修，`45b86ff`）**：唯一失效的功能是**流量限额** ——
     不是「显示不对」，而是①字段一个不缺、没有报错、值全错（派生键在设备上不存在，
     `TrafficLimitMapper` 全填默认值）；②流量预警与到阈值自动关网**静默失效**
     （`limit_bytes=0` → `checkTrafficLimitThrottled` 直接 `return`，一行日志都不打）；
     ③最严重的一条：上下行交叉绑定失效后，颠倒的月累计被 `recordHourlyUsage()` **落库**，
     **开关改回 true 也修不回来**。完整判断依据、被否决的替代方案与「往豁免清单加组的门槛」
     见 **§11.13**；库里那段历史数据怎么处理见 **§15 的 P1-28（待用户裁决）**。
   - **不验的后果**：这是 0.4b 风险最集中的一处 —— 排障模式下 `normalizeProfile = null`，
     命令表全靠 `commandProfile` 顶着。切错了的表现是**关掉归一化就整个只读面变空**，
     而那正是排障时最需要它工作的时刻。单测替身（守门测试第 ⑦⑧ 条）只覆盖取值、不覆盖端到端
     —— 这一项实测过后可以补一句**更硬的教训**：那两条替身钉的是「命令还发得出去」，
     真正坏掉的是「发回来的值怎么解」，所以它们全绿也拦不住上面那个 P0。
4. **写操作真机回归清单**（第 4 层，**15 条**，见 §4 验收最后一条）
   - 重启 / 关机 / 恢复出厂 / 改后台密码 / 开关移动数据 / **手动拨号**与**挂断** /
     切连接模式 / 改 SSID / 改密码 / 改功率 / **开 WiFi** 与**关 WiFi** / 发短信 / 删短信 / 标已读。
   - 第 15 条是新增的 **切 WiFi 频段**（`POST /api/wifi/band`，P1-26）。这一条要连带验三件事：
     ① 设备真的换到了目标频段（回读 `wifi_chip`）；② WiFi 是**开着**的（这条命令带「打开」语义）；
     ③ 在 5G 下点「打开 WiFi」不会被切回 2.4G（`setWifiEnabled` 现在会先读当前频段）。
     每条按 §14.4 做三次观察（点之前记状态 → 点并看返回码与文案 → 点之后确认设备状态真的变了）；
     异常路径另见 §14.4（会话失效重试、值域拒绝、设备离线、短信只发一条数收到几条）。
   - **不验的后果**：0.3 把 **14 个**写调用点的报文构造全换成了 `writeSpec.encode` ——
     整表替换命令多一个键 / 少一个键都会**静默改掉设备配置**；破坏性动作
     （重启 / 关机 / 恢复出厂）更是只有真机能验。

> **阶段 0 在这四项验完之前不能算完成。**

> **2026-09-24 第一份真机证据到手（装的是带阶段 0~2 全部改动的最新 app + core）** ——
> 用户给了一份 `field_coverage`：`normalization_enabled=true`、`profile_id=zte-goform`，
> **10 个组全部 `queried=true`**，`TRAFFIC_LIMIT` 10/10、`CONNECTION` 3/3、`BAND_STATUS` 2/2 满命中，
> `LAN_SETTINGS` 9/10、`WIFI_SETTINGS` 7/8、`IDENTITY` 6/7、`CELL_INFO` 7/8、
> `DEVICE_SETTINGS` 8/13、`SIGNAL` 14/22（NR 驻网，`lte_*` 大面积 missing 属正常）、`WIFI_CLIENTS` 1/2。
> **结论：归一化链路在真机上是工作的**，没有大面积失效 —— 这是阶段 0~2 第 4 层的第一块实证。
>
> ⚠ **但 §14.4 原来那条判据「每组 hit 数与改造前一致」按原样执行不了**：
> **没有人在改造前抓过 `field_coverage`**，没有对照物。所以这条判据**就地改成**：
> ① 10 个组全部 `queried=true`（没有整组失联）；
> ② 每组 missing 的每一项都能给出解释（驻网制式 / 功能未开启 / 字段名不对）；
> ③ 解释不了的一律登记成待办，不许当噪音放过。
> 按这条口径，本次抓取的产物就是 **P1-33**（死注册项）与 **P1-34**（三个设置项读不回状态）。
>
> **仍未验的**：接口快照那四条判据（`/api/dashboard` 等的键集与形状）、§14.4 的 **15 条写操作**。
> 所以阶段 0 / 1 / 2 **仍然是 `[~]`** —— 读侧有实证了，写侧一条都没点过。
> 第 1、2 层证明的是「代码自洽」，第 3、4 层证明的是「设备照旧」——
> 这一阶段的全部目标恰恰是**行为不变**，而「行为」只在真机上存在。
> 另外按 §14.3 最后一句：**阶段 0 的写操作在没有真机验证的情况下不允许合进主线**
> —— 现在这些 commit 都只在本地，没有推送（也符合「未经指令不得自动推送」的口径）。
>
> **但阶段 1 可以开始（2026-09-23 用户裁决，口径见 §0 的例外 2 与 §14.6）**：
> 阶段 0 卡的是「没有真机」这个物理条件，不是代码问题（第 1、2 层全绿）。
> 所以阶段 0 保持 `[~]`、上面四项真机待办**一条不销**、写操作 commit 继续留本地不合主线，
> 同时开阶段 1。等真机窗口到了，按原判据一次补验阶段 0 的四项；
> 补验不过就按 §13.2 的 P0 停手回滚 —— **不许因为阶段 1 已经堆在上面而将就**。






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

> ⚠ **上面那个代码块是当时的草案，现在实际是 11 项**（2026-09-25 数过
> `core/contract/.../Capabilities.kt`，`CapabilityWireTest` 的「值域大小固定为 11」写死了它）。
> 追加的是 **`BATTERY("battery")`**（批 L 加、批 M 定型），它是**纯读侧能力、不参与 route 门禁** ——
> 声明序放在末尾，所以**没有动已有 10 项的顺序或 wire 名**，对旧客户端是一次安全的「只增」。
> 代码块不改（它是草案），但读这一节时要知道：
> 「第一批只定 10 个」这句话现在只描述**历史**，不描述当前值域。
> 另外「值域 11 项」与「端点下发几项」是两件事：`/api/device/capabilities` 只含插件声明了的项，
> F50 不声明 `BATTERY` → 数组仍是 10 个（这一点 `CapabilityWireTest` 的注释里也写着）。


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

### 11.13 「按 canonical 重组的派生出口」不跟排障开关降级（已决，2026-09-22 批 16）

这一条是第 3 项实机验证（见 §9 收尾盘点）当场验出来的，代码修在 `45b86ff`。
它是一个**新认识**，不只是一个 bug：**将来任何一组 canonical 开始做重命名，都会掉进同一个坑。**

#### 1. 出口分两类，排障开关只对一类有意义

`field_normalization_enabled` 的语义是「读侧**原样透传**设备字段名，供排障时对照设备后台」。
但读侧出口实际有两类：

- **透传出口**：canonical 名基本就是设备原名，归一化只做 allowlist + 值解码。
  关掉开关后响应里变回设备原名 —— 这正是排障想看的。
  WiFi / 小区 / LAN / 身份 / 连接 / 频段 / 设备设置这几组都是。
- **按 canonical 重组的派生出口**：值被拆过、方向被掰过、或者**键在设备上根本不存在**。
  关掉开关它不会变成「设备原样」，只会变成「**错的**」——
  字段一个不缺、没有任何报错、值全错，最难排的那一类。
  今天只有 `FieldGroup.TRAFFIC_LIMIT`（`GET /api/device/traffic-limit`、
  `/api/dashboard/summary` 的 `traffic_limit`、以及 `DataScheduler` 的落库路径）。

所以豁免落在闸门本体：`GoformFieldMapper.normalize()` 的 `normalizeProfile ?: return raw`
那一行前面先查一张 `NORMALIZE_ALWAYS` 清单，命中就回落到**非空的** `commandProfile`
继续归一化（口径同 `cmds()`：字段归一化可以关，命令表不能关），其余 9 组照旧 `return raw`。
豁免生效时**按组打一次 WARN**，指明组名、开关名、理由，并告诉排障的人真正该走哪个出口。

#### 2. 为什么是「闸门本体」而不是 `TrafficLimitMapper`

TRAFFIC_LIMIT 有**两个**调用点：`GoformSignalClient.getDataUsage()`（REST 出口，经
`DataHub.getTrafficLimit()` 的 10s 缓存，也是 `DataScheduler` 的限额供给器）与
`getTrafficStats()`（`DataScheduler` 的 15s 落库路径）。
插在 mapper 里只能救 REST 那一条，**修不到落库那条路** —— 而落库才是不可逆的后果
（`recordHourlyUsage()` → `trafficHourlyDao().addUsage()`，把上下行颠倒的增量写进小时桶，
开关改回 true 也修不回来）。落在闸门上一处同时覆盖两个调用点。

#### 3. 明确否决：给 `TrafficLimitMapper` 加「原始名兜底 + 复合串兜底解析」

那等于把 `splitDataVolumeLimit` 这份**设备知识**（复合串怎么拆、乘数怎么映射单位、
上下行是反的）在 `:core:api` 复制第二份，直接违反「设备知识只在 profile 里有一份」。
两份迟早漂移，而漂移的表现又是静默错数字 —— 比原来的 bug 更难查。
`TrafficLimitMapper` 的文件头写着「本文件不认识任何设备字段名」，那句话要继续成立。

**往 `NORMALIZE_ALWAYS` 加组的门槛**：只有「按 canonical 重组的派生出口」才有资格。
**纯透传出口一律不许加** —— 每加一个，这个排障开关就少一块可观察面，加满了它本身就废了。
这条门槛由 `GoformNormalizeAlwaysTest`「豁免没有扩散到其它组」逐组守住
（除 TRAFFIC_LIMIT 外每一组都必须 `assertSame(入参, 出参)`）。

豁免后开关仍然覆盖 **9 组**：WIFI_SETTINGS / WIFI_CLIENTS / CELL_INFO / CONNECTION /
IDENTITY / LAN_SETTINGS / DEVICE_SETTINGS / BAND_STATUS，加上 SIGNAL（它本来就与这个开关无关，
见下面第 4 节）。`maskDump()`（原始 dump 只脱敏不归一化）与 `/api/diagnose` 的
`enabled` / `profileId` / `coverageReport()` **一律不在豁免范围内**，
§4 验收里那两条判据（`normalization_enabled` 为 false、`coverageReport` 短路不发查询）未动。

#### 4. 两处以前说错 / 说窄了的措辞（更正）

- **「`TrafficLimitMapper` 是 core 里唯一按 canonical 重组的服务端 mapper」—— 不对。**
  至少还有两处：`NetworkRoutes.kt:120-129`（`/api/network/band-status` 按
  `DeviceFields.BandStatus.*` 重组，缺失补 `""`）与 `DeviceRoutes.kt:458-475`
  （`/api/device/settings` 注入派生键 `network_mode_label`）。
  准确表述是：**它是唯一一个「按 canonical 重组」且「该组的 canonical 名与设备原名大面积不同」
  的出口**。前两处之所以关掉开关也不坏，是因为它们读的 canonical 名恰好等于设备原名
  （`lte_band_lock` / `nr_band_lock` / `net_select`），或者派生键只是在原值旁边**追加**一个标签。
  → 这条要记住：**哪一组开始做重命名，这一组的重组出口就会掉进同一个坑。**
- **`DataScheduler.kt:121-123` 那句「字段归一化的回退开关不覆盖这里」的作用域比字面窄。**
  它只对注入的 `deviceProfile`（非空，默认 `ZteGoformProfile` → `SignalCollector`）成立，
  所以**信号**确实不吃这个开关（`getSignalInfo()` 根本不调 `normalize()`，归一化在
  `SignalCollector` 里用那份非空 profile 做）。但 scheduler 还有几条路是**吃**这个开关的：
  经注入的 `signalClient` 走 `getTrafficStats()`（TRAFFIC_LIMIT，本条豁免后已不受影响）、
  经注入 lambda 走 `dataHub.getTrafficLimit()`（同组）与
  `wifiClient.getConnectedClients()`（WIFI_CLIENTS，**仍然吃**）。
  本计划书此前没有引用过那句话，所以没有可改的正文；写在这里是为了下次别再照着那句话推结论。
  ⚠ 顺带一个**尚未处置**的观察：`checkDeviceEvents()` 直接取 `json["station_list"]?.jsonArray`，
  而 WIFI_CLIENTS 的 `structuralDecoder`（`normalizeStationLists`）才负责把设备的
  「数组的 JSON 字符串」这种双重编码拉平成真数组。关掉归一化时这一步没了，
  那行取值会抛 → 被 catch 吞掉、基线清空，**设备接入/离开事件静默停摆**。
  它没在实机验证里暴露，因为 `device_events_enabled` 默认关着。
  **不按本条处理**：WIFI_CLIENTS 是透传出口（键名就是设备原名），加进 `NORMALIZE_ALWAYS`
  正是第 3 节禁止的事；真要修就是让消费端容忍两种形态（API 手册已经这么要求客户端了）。
  → **已登记为 §15 的 P1-29，归阶段 2（任务 2.11）**（2026-09-23 裁决）。
  这里不再留无编号尾注 —— 无编号的观察在待办池里搜不到，是最容易丢的一类。

---

## 12. 回滚

每个阶段都要能独立回滚，且回滚不依赖「记得改回某个配置」：

- **阶段 0**：纯搬运，`git revert` 即可。风险点是真机行为，所以验收里的 **15 条**手工回归是硬要求
  （条数以 §14.4 为准；本文档此前写的 12 / 13 / 14 都是旧数字）。
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
  → 先按 §14.4 把真机 **15 条**写操作清单列出来，一条一条点，改前改后各点一遍
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

- `ZteGoformProfileTest`（撰写时 1325 行；**2026-09-23 实测 2083 行 / 125 条 `@Test`**）冻结了
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

写操作没有自动化替代品。阶段 0 的清单（**15 条 —— 本文档的唯一真源就是这里**，
明细见 §4 验收最后一条与 §9 收尾盘点第 4 项；
**批 13 逐项数过是 14 条，批 15 新增「切 WiFi 频段」后为 15 条；此前写过的 12 / 13 都不对**）
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

**但「不能标 `[x]`」不等于「不能往下做」**（2026-09-23 用户裁决，完整口径见 §0 的例外 2）：
只卡在第 3/4 层、且原因是**没有真机**时，允许开下一阶段 —— 代价是该阶段一直挂 `[~]`、
**写操作相关 commit 不合主线**、真机待办按原判据补验。
反过来说，第 1/2 层不过，或第 3/4 层**已验出差异且解释不清**，就是 §13.2 的 P0：停手，不许往下做。

---

## 15. 阶段待办池（范围外问题登记处）

**P0-4 ~~⚠ 发版阻塞：批 L 上线前必须先改 app 侧三处~~ → 2026-09-24 批 M 已解除**

- **解除原因**：用户推翻裁决 C，改成方案 D（批 M）——`percent` **恢复系统值**（F50 上仍是 50），
  不再抹成 -1，所以下面那三处 app 代码**不会**显示 `-1%`、也不会被涂成 critical 红。
  原话：「还是按照系统值吧，别改成 -1 了，起码没那么膈应，
  可以在电池详情弹窗中显示提示可能无电池或者检测不到之类的」。
- **但 app 侧仍有一件要做的事**（不是阻塞，是新功能，归 3.5）：
  battery map 现在多了一个 **`supported: Boolean`** 字段（F50 = false），
  app 的电池详情弹窗要据此显示一句「本机型可能无电池 / 检测不到」之类的提示。
- 下面这些定位结论保留，供 3.5 落地时参考（**它们现在不是 bug，是将来加提示的落点**）：
  - `HomeMetricsCard.kt:104` `valueText = state.batteryInfo?.let { "${it.percent}%" } ?: "--"`
  - `HomeMetricsCard.kt:107` 取色兜底 `val p = state.batteryInfo?.percent ?: 100`
  - `HomeMetricsDialog.kt:130` `UfiInfoRow("当前电量", "${b.percent}%")` ← **提示加在这个弹窗里**
- web 侧本来就安全（`percent >= 0` 才拼 `%`，`contract.ts:1349` 登记了 `batteryPercentUnknown: -1`），
  但它也可以顺带读 `supported` 加同样的提示 —— 归 3.6。




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
- 归属：**已裁决 → 阶段 2，已落成任务 2.9**（原有两个选项里取（b））。阶段 0 不擅自改 `core/controller`。
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
  「文件资源」契约），按 §13.4「不要自己发明 API」的纪律，**等裁决**；
  **已落成阶段 2 的任务 2.10**，与插件聚合根一起设计，不在阶段 0 临时加一个方法。

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

**P1-11 `WIFI_AP_CONFIG` 没有 `validate`（SSID / 口令原样进表单）** —— **部分结案（2026-09-22）**

- 已补（`86be1f3` + 本轮）：`WIFI_AP_CONFIG` 现在有 `validate = ::validateApConfig`，管两件事 ——
  ① `auth_mode` 白名单，只收真机实测到的 4 个值（`OPEN` / `WPA2PSK` / `WPA3PSK` /
  `WPA2PSKWPA3PSK`，**大小写敏感、不 trim**）；② `max_sta_num` 闭区间 `1..10`
  （`AP_MAX_STA_NUM_RANGE`，依据是用户对中兴 F50 的规格结论，2026-09-22）。
  此前「不设上限，真机见过 7 与 10，固件真实上限未知」的措辞已作废，三层（core / web / app）
  同步收紧到同一个区间。
- **仍然刻意不校验 SSID 与口令**，理由不变（见下）—— 这一条只结掉「完全没有 validate」那半，
  「SSID / 口令的值域」那半仍在阶段 2。
- 事实：`ZteGoformProfile.kt` 的注释写明 SSID 与口令**刻意不校验** ——
  允许任意字符（含 `&` 和 `=`），body 由 `GoformCodec` 统一 URL 编码；
  在这里加值域校验会把「现在能设的 SSID」变成 `Rejected`。
- 事实：其它项里有 validate 的（如 `WIFI_POWER` / `LAN_DHCP` / `TRAFFIC_LIMIT`）
  都是**本来就有** route 层同义校验的项，加 validate 不改变对外可接受的取值集合。
  `auth_mode` / `max_sta_num` 这两项属于同一类：web/app 两端本来就只让用户从固定选项里选。
- 剩余部分的归属：阶段 2「route 只认 `SettingKey` + 三态」那一步。补 SSID / 口令校验是**对外行为变更**
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

**P1-18 两份设备文本解码器判据已一致，但仍是两份实现**（**两份都在主仓 `core` 内，与 P1-19 无关** ——
P1-19 是安装器那份，已按裁决结案为「刻意重复」；本条仍是欠账）

- 事实：`GoformClient.decodeDeviceText`（`:944-948`）与
  `ZteGoformProfile.WIFI_PASSWORD_DECODER`（`:127` → `utf8OrGbk`，`:140` 起）
  现在判据相同（UTF-8 无损往返优先、否则 GBK），是 `858a9c9` + `090fcad` 两轮分别改的。
- 事实：**契约不同** —— 客户端那份解码彻底失败返回**空串**（`/api/wifi/settings` 依赖），
  profile 那份返回 **null（省略该 key）**（`:132` 的注释写明「保持现有语义」）。
- 建议归属：合并前先把「空串 vs null」这条差异定下来（它就是 P1-6 那件事的另一半）。
  合并的落点应该是 device-schema（纯 JVM，客户端可以依赖过去），但**不要在 P1-6 之前合** ——
  否则会把两种失败语义强行并成一种。

**P1-19 安装器仓有一份同样的解码实现** —— **2026-09-23 结案：按裁决不合并，这份重复是刻意的**

> **裁决（用户，2026-09-23）**：安装器**整份独立维护、不做任何共享**（口径写进 §1 非目标）。
> 所以本条**不是欠账**，不再等「用户那一轮改完后同步判据」—— 主仓改解码判据时**不需要**同步安装器，
> 安装器改了也不需要回主仓。下面的事实保留，作为「那边现在是什么形态」的记录，不作为待办。
> ⚠ 唯一例外见 §1 非目标最后一条：共享持久化键 / 对外报文格式仍以 contract 与 API 手册为真源。

- 事实：`scripts/UFI-AXIS-Core-install-Android/goform/src/main/java/com/ufi_axis/installer/goform/GoformClient.kt:614-627`
  的 `base64Decode` 是 **`858a9c9` 之前**的形态：无条件 `String(bytes, GBK)`，
  外面套着那层「再做一次 UTF-8 往返」的死代码，失败同样返回空串。
- 事实（与主仓不同，别照抄结论）：这份文件里**没有** `base64Encode`、也没有 `setAccessPointInfo`
  —— 安装器不写 WiFi 配置。所以那里**不存在**「写 UTF-8 / 读 GBK」的不对称，
  只是读侧实现停留在旧版；非 ASCII 的设备文本会被 GBK 解错。
- 事实：那份代码**仍在维护** —— 本次改造期间用户正在并行修改整个
  `scripts/UFI-AXIS-Core-install-Android/**`（含这个文件），所以不能按「废弃代码」处理。
- ~~建议归属：**本轮只登记，不许动**（那是另一个仓的范围，且有人正在改）。
  等用户那一轮改完后单独确认：要不要把主仓 `decodeDeviceText` 的判据同步过去。~~
  → **已作废（2026-09-23 裁决）**：不同步、不合并、不再跟踪。那边的形态由安装器自己负责；
  真要改也只是安装器自己的 bug 修复，与本计划无依赖关系。

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

#### 批 14 新登记（P1-26 ~ P1-27）

**P1-26 `WIFI_BAND` 拆分 + 三层接线** —— **已完成（2026-09-22），只剩真机验证**


- 抓包事实（2026-09-22，用户提供，逐字）：

  ```
  关闭 WiFi：goformId=switchWiFiModule&isTest=false&SwitchOption=0&AD=…
  开启 WiFi：goformId=switchWiFiChip&isTest=false&ChipEnum=chip2&GuestEnable=0&AD=…
  ```

  由此定下的语义模型：`switchWiFiChip&ChipEnum=X&GuestEnable=0` = **在频段 X 上启用 WiFi**
  （「开」与「切频段」是同一条命令，chip1=2.4G / chip2=5G）；
  `switchWiFiModule&SwitchOption=0` = 关闭 WiFi 模块。
- **原拆分方案第 2 步已被这份抓包否定**：那一步写的是「`WIFI_ENABLED` 只保留 `switchWiFiModule`，
  `SwitchOption=0|1`，删掉 `commandOf`」。真机证明「开」走的是 `switchWiFiChip`，
  `SwitchOption=1` 本项目从未发过、这次抓包里也没有 —— **至今无实测依据，不许写进代码**。
  当时刻意不按对称性猜、坚持等抓包，事后证明是对的；这段判断过程本身留档，不要只留结论。
- 已落地（device-schema，`0892412`）：`SettingKey` 28 → 29，新增 `WIFI_BAND`（`value` 只收设备词汇
  `"chip1"` / `"chip2"`，`2.4G` / `5G` / `0` / `1` 一律拒）；`WIFI_ENABLED` 的「开」分支改成读
  可选参数 `chip`，缺失时退回 `chip1`。`:core:device-schema:test` 185 → 188 全绿。
  除「带 `chip` 时的 `ChipEnum`」一格外，四种情况（开+chip2 / 开+chip1 / 开+无 chip / 关）
  的报文逐字未变。
- 已落地（core 接线，`6033f91`）：`setWifiEnabled(true)` 先复用 `getWifiSettingsMerged()` 读
  `wifi_chip` 再下发（**不新增 cmd**，否则 `GoformCommandTableGuardTest` 会红），读不到或读到域外值
  才退回 `chip1` 并打 WARN；关分支一次读都不做。新增 `setWifiBand(chip): WriteOutcome` 与
  `POST /api/wifi/band`（`{"chip":"chip1"|"chip2"}`，取值域判定只在 profile 的 validate 里，
  route 走 `respondRejected` 回 400 + 原因）。新增 `GoformWifiBandParamsTest` 7 例，
  `:core:goform:test` 79 → 86 全绿，守门测试 9 例未动。
- 已落地（UI 接线，`78692b7` web / `1c71fa9` app）：两端频段选择器都从 `POST /api/wifi/config` 的
  `chip_index` 改走 `/api/wifi/band`，写侧 `chip_index` 摘掉（读侧展示映射保持不动）。
  频段是独立动作，**只有真变了才下发**，顺序固定为「配置先落 → 频段最后」；
  切换前弹确认（web `useDialog().warning`、app 既有 `UfiConfirmDialog(destructive = true)`），
  写明会重启 WiFi 模块并在该频段上打开 WiFi、本机会断开，留「暂不执行」出口；取消一个字段都不发。
  两端基线均未增（web 26/40，app 五类字面量逐位相同）。
- **剩余：真机验证**。命令本身有抓包依据，但「切频段 → 设备真的换到该频段且 WiFi 是开的」
  只有真机能证。已加进 §14.4 的写操作清单。
- 接线时定下的两件事（留档）：① 当前频段来源是已登记字段 `wifi_chip`，已在
  `cmdsFor(WIFI_SETTINGS)` 里，不必新增查询；② 切频段属于破坏性动作，确认弹窗与后果文案是硬要求。


**P1-27 `needs_restart` 不覆盖 `field_normalization_enabled`（要重启但接口不说）**

- 事实：`field_normalization_enabled` 2026-09-22 起可读可写（`AppSettings.toMap()` +
  `ConfigRoutes` 的 `boolField`），但唯一读取点在 `ComponentFactory.resolveDeviceProfile()`
  （`core/src/.../service/ComponentFactory.kt:802`，调用点 `:152`），只在构造组件图时读一次
  —— **不重启后台服务不生效**，与 §11.10「运行期不允许热换插件」一致。
- 事实：`ConfigRoutes` 的 `needs_restart` 清单只有 `port` / `goform_ip` / `goform_port` /
  `goform_password`，`hint` 文案也写死成「修改了认证或端口配置」。
  所以 PUT 这个键会回 `needs_restart:false`，客户端**无法**靠接口判断要重启。
- 现状的补法：web（`GeneralPanel.vue`）与 app（`DiagnoseScreen.kt`）各自在 UI 文案里硬编码
  「需重启后台服务生效」；app 另外把 `/api/diagnose` 的 `device_profile.normalization_enabled`
  作为「运行时实际值」显示出来，与配置值不一致时给提示 —— 这是两端都能自证的做法，不是假开关。
- 归属：要把它算进 `needs_restart` 就得同时改 `hint` 的语义（现在是「认证或端口」一句话），
  属于对外响应语义变更，与 route 层的其它整理一起做。**在那之前不许把 UI 文案改成依赖 `needs_restart`。**

#### 批 16 新登记（P1-28）

**P1-28 排障开关那次实测期间，库里的小时级流量行上下行是反的（`45b86ff` 的**剩余风险**，
清不清**待用户裁决**）**

- 前提：根因已修（§11.13 / `45b86ff`），**这条只讲已经写进数据库的历史数据**。
  本轮**没有动数据库**，一行都没删没改。
- 事实：`field_normalization_enabled=false` 且尚未打豁免的那段时间里，
  `DataScheduler.collectGoformTraffic()` 拿到的是设备原值（ZTE 把上下行报反），
  `recordHourlyUsage()` → `trafficHourlyDao().addUsage(hourStart, rxDelta, txDelta)`
  按那个方向落库。所以那段时间的小时行**每行的 rx / tx 互换了**。
- 事实：**总量是对的**，错的只有方向 —— `rxDelta + txDelta` 不受交换影响。
  所以「今日 / 本月用量」「限额百分比」这些看合计的地方没受影响，
  受影响的只有**按上下行分开看**的图表与明细。
- 事实（自愈边界，已按代码核过）：基准存在 `AppSettings.trafficHourlySamplerJson` 里，
  切回正确方向后的第一次采样会命中 `TrafficHourlyAccumulator.decide` 的
  `counter-reset`（`monthTx < prev.tx`，因为原 tx 是大的那个）或 `offline-gap`（重启超过 10 分钟）
  → **丢弃这一次增量**。也就是说切换瞬间**不会**在图上长出一根假柱，
  代价是丢一段（一个采样间隔）。所以污染范围严格限于「开关关着的那段时间」的小时行。
- 三个选项（**要用户定**，我不动数据）：
  ① 不管 —— 只有分方向的图会看出异常，且只有那几个小时；
  ② 把那段时间的行 `rx`/`tx` 互换回来 —— 需要**准确的起止时刻**（开关关掉那一刻到改回并重启那一刻），
     起止判断错了会把正确的行也掰反，比现在更糟；
  ③ 删掉那段时间的小时行 —— 与「宁可少一段，不能凭空多一段」的既有取舍一致，最不容易做错。
- 归属：**不属于阶段 0~5 的任何一步**，是一次性的数据处置。裁决前不要顺手写迁移脚本。

**P1-41 `DataScheduler` 的行内注释写 85°C / 75°C，而实际默认阈值是 80 / 70**

- 事实：`adaptiveRootPermits` 与温度熔断 `when` 的行内注释还写着 `85°C+` / `75°C`，
  而 2026-09-03 改成读 `AppSettings.monitorThermalCriticalC/WarnC`（默认 **80 / 70**）之后没跟着改注释。
- 批 33 刻意没动 —— 改它要碰阈值那几行的上下文，而那一批的硬约束是「阈值一个字都不许碰」。
- 低优先，但排障时会误导人（注释比代码显眼）。

**P1-40 `migrateConfig()` 的「地板判据」是设备知识，但 `DeviceTuning` 没有承载它**

- 事实：`DownloadManager.migrateConfig()` 是
  `if (throttleTempWarn < 70f) { = 75f }` / `if (throttleTempCritical < 80f) { = 85f }`
  —— **地板判据（70/80）与抬升目标（75/85）不是同一个数**。
- 批 32 只把**目标值**接到了 `tuning.downloadThrottle*`，**地板判据仍是字面量** 70f / 80f。
  所以换设备时：目标值跟着插件走，而「低于多少才抬」还是 F50 的经验值。
- 为什么没顺手加字段：地板判据的语义是「**旧配置低到什么程度就认为它是历史遗留、该被抬**」，
  这与「这台设备的合适阈值是多少」是两件事；给 `DeviceTuning` 加第 6、7 个字段
  会让本来就容易被误接的这组阈值更难读（见 P1-37）。
- 待裁决：① 加两个字段（`downloadThrottleWarnFloorC` / `CriticalFloorC`）；
  ② 按「目标值 − 5」派生；③ 维持字面量并在 KDoc 写明它只对 F50 这一代配置有意义。

#### 2026-09-25 文档回填轮新登记（P1-42）

**P1-42 4.3 的「CPU 读法」从未落地，`/proc/stat` 仍有两份独立实现（阶段 4 的遗留缺口）**

- 事实（2026-09-25 grep 核过）：`SystemCollector.readCpuUsage()`（`:core:collector`，`Double`，
  两次 `/proc/stat` 差值）与 `DownloadManager.readCpuUsage()`（`:core:controller`，`Int`，同样两次差值）
  **各读一份**；`PlatformAdapter` 里**没有任何 CPU 成员**（实测四个成员：`name` /
  `atTransports()` / `readTemperature()` / `restartNetworkStack(at)`）。
- 事实：任务 4.3 的原文是「传感器读法（温度 / **电池** / **CPU**）从 `SystemCollector` /
  `DownloadManager` 抽到 adapter」。三项里**温度**在批 H / 批 I 落地，**电池**在批 L / 批 M
  被明确裁决**不进** adapter（理由见 `PlatformAdapter` 文件头与 §3.2 标废框第 2 条），
  **只有 CPU 既没做、也没有任何裁决** —— 它是三项里唯一的悬空项，所以 4.3 标 `[~]`。
- 为什么它不像温度那样「顺手就能搬」：两份实现的**类型与口径不同**（`Double` 百分比 vs `Int` 百分比），
  且消费侧的判据阈值也不同（`DataScheduler` 的 `> 80f` / `> 50f` 分档 vs `DownloadManager` 自己那套），
  统一成一个 adapter 方法就必须先定「返回什么类型、谁负责取整」——
  这与批 I 踩过的 `toInt()` / `roundToInt()` 是同一类坑（那次靠一条故意断言算错的单测钉住）。
  所以这是一次**行为变更**，不是搬运，必须单独一批、单独验。
- 另一个必须一起想清的事实：`/proc/cpuinfo` 的平台判据在 4.6 里被收进了 `:core:device-spi` 的
  `CpuInfoPlatform`（纯常量 + 纯函数），而 CPU **使用率**是运行时读数、属于平台 I/O ——
  两者别混成一件事：前者是「这台机器是什么平台」，后者是「它现在有多忙」。
- 待裁决（三条，与电池那次同构）：① 给 `PlatformAdapter` 加 `suspend fun readCpuUsage(): Float?`
  并统一两处调用点（读不到返 `null`，口径同 `readTemperature`）；
  ② 判定它**不是平台知识**（`/proc/stat` 是 Linux 通用接口，不按机型分支）→ 照电池那样明确不做、
  把 4.3 的措辞改窄；③ 只合并成 `core/common` 的一个纯函数（两处共用实现但不进 SPI）。
  **裁决之前不要顺手改任何一处** —— 它们各自挂在采集循环与下载限速两条不同的判据链上。

#### 2026-09-25 批 40 ~ 批 50 已还完的欠账（**登记原文都留着，只在这里标「已还」**）

按 §9 的纪律，欠账条目一条不删 —— 下面五条各自的原始登记处（§6 的 2.5 / 2.6、§9 批 39 之前的
文档轮条目、批 47 的守门测试 allowlist E 组）也都保留原文，只是都已经不再成立。
本轮**逐条回代码核过**（2026-09-25，只读、没改代码）：

- **`= ZteGoformProfile` 默认参数（`SignalCollector.profile` / `DataScheduler.deviceProfile`）
  → 已还，批 40（`a48e1b9`）。** 核实：两处都成了非空无默认值参数，
  `SignalCollector.kt:60` 与 `DataScheduler.kt:153` 的 KDoc 各写了「2026-09-25 去掉了原来的
  `= ZteGoformProfile`」；main 源集里 `import ... ZteGoformProfile` 只剩 `ZteF50Plugin.kt` 一处。
- **`GoformWifiClient` 两处直读 `ZteGoformProfile.ACL_MODE_BLACKLIST` → 已还，批 40（`a48e1b9`）。**
  核实：`GoformWifiClient.kt:363` 只剩一条「此前这里直读……绕过了 commandProfile」的注释；
  判据搬进 `DeviceProfile.aclDefaultMode()`（默认 `null`）+ `ZteGoformProfile` 的覆写，
  写侧那份兜底整段删除，`ACL_MODE_BLACKLIST` 这个常量现在只被 device-schema 自己引用。
- **`LocalSmsDelivery` 的签名吃 `GoformSmsClient.SendOutcome` → 已还，批 48（`76b0ebd`）。**
  核实：`LocalSmsDelivery.kt:3-4` 现在 import 的是 `devicespi.adapter.SendOutcome` /
  `SendVerdict`（类型搬到契约层顶层、不留 typealias），`classify` / `countsTowardQuota`
  两个签名都吃契约层类型。
- **6 个 `Goform*Client` 没收进插件 + 装配层那句向下转型 → 已还，批 50（`25c4419`）。**
  核实：`ComponentFactory.kt` 里只剩「造一份 transport」与 `DeviceHub(runtime.plugin.createAdapter(...))`
  两步，`as? GoformClient` 移进 `ZteF50Plugin.createAdapter`（`:125`），六个客户端在
  `ZteGoformAdapter` 的 5 参数公开次构造函数里各 new 一次、共享同一份 transport；
  守门测试 allowlist 的 E 组整组删除。
- **上层直接消费 goform 具体类 → 已还，批 42 ~ 批 50（`d5bda4f` … `25c4419`）。**
  核实：`UpperLayerGoformFreeGuardTest` 的 allowlist 现在 **13 个文件**，逐条都是 A ~ D 组的
  命名遗留（`GoformQoS` / `GoformSessionLog` / `qosGoform*` / `updateGoformPassword` /
  局部 DTO 与控件字段），**没有一项是 goform 的类型**；`:core:collector` 的
  `implementation(project(":core:goform"))` 也已移除（`core/collector/build.gradle.kts:21`
  留了一行写明是批 B2 移的）。
  ⚠ 剩下的三类**改名**欠账仍在，已单独登记为下面的 **P1-46**。

#### 2026-09-25 文档回填轮新登记（P1-43 ~ P1-46）

**P1-43 `DataScheduler` 的调度循环用 `catch (e: Exception)` 兜底，被 `Error` 打死之后那条循环永不重启**

- 事实（2026-09-25 读代码核过）：`schedulerScope` 是
  `CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { ... })`
  （`DataScheduler.kt:175`）。`start()` 里起了 **10 条** `while (isActive)` 循环
  （`:511` flush / `:523` WakeLock 续期 / `:535` CPU+内存 / `:545` 信号+吞吐 / `:554` 月流量 /
  `:568` SMS 缓存 / `:579` 电池+温度 / `:589` 设备事件 / `:605` 本地告警扫描 / `:613` 数据清理），
  `startPerformanceMonitor()` 里还有第 11 条（`:741`）。
- 事实：循环体本身**没有** try/catch，兜底都在被调的 `collectXxx()` 里，而那些兜底一律是
  `catch (e: Exception)`。所以 `Error`（`OutOfMemoryError` / `StackOverflowError` /
  `NoClassDefFoundError` 这类）会穿过兜底、终止**这一条**协程。
- 后果：`SupervisorJob` 只保证**兄弟协程**不被连坐 —— 它不会重启死掉的那一条。
  `CoroutineExceptionHandler` 只打一行 ERROR。所以现象是**部分指标静默消失**、其余照常：
  例如 `:535` 那条死掉就只有 CPU / 内存没了，`:579` 死掉就只有电池 / 温度没了，
  **重启 core 才恢复**。排障时最容易误判成「采集打不到设备」。
- 修法（待裁决）：① 循环体外层包 `catch (t: Throwable)` + 记 ERROR + 退避后继续下一轮
  （注意 `CancellationException` 必须原样重抛，否则停不掉服务）；
  ② 给每条循环一个「守护重启」包装函数，统一退避与日志；③ 只补监控（`/api/service/status`
  暴露每条循环的最后一次成功时刻），不改控制流。
- ⚠ **动手前必读**：曾有子代理「顺手」重写这 10 条循环，并把带日期的判据注释（例如
  `:560-566` 那段「2026-08-29 从固定 30s 提频」「2026-09-08 更正：验证码提取不在短路之后了」、
  `:595-603` 那段「告警扫描为什么固定 15s、为什么不搭在采集循环上」）一起删掉，
  **整段被 revert**。真要做这件事：**那些注释一个字都不许删**，
  它们是「这个间隔为什么是这个值」的唯一记录。
- 归属：**不属于阶段 0 ~ 5 的任何一步**，是既有属性（不是插件化引入的）。单独一批，且要有
  「注入一个抛 `Error` 的采集函数、断言循环仍在转」的单测 —— 没有这条测试就没法证明修好了。

**P1-44 首页 hero 卡的流量限额入口没有前置置灰**

- 事实（2026-09-25 grep 核过）：`DashboardScreen.kt:339` 会打开 `UfiDataLimitDialog`，
  而该文件里**没有任何 `Capability` / `supports()` 的引用** —— 也就是这个入口不看能力集。
- 事实：core 侧的门禁在 `DeviceRoutes.kt` 的 `POST /api/device/data-limit`
  （`Capability.TRAFFIC_LIMIT`），所以**不是正确性漏洞**：点下去照样回 501 / `NOT_SUPPORTED`，
  只是用户要点进弹窗、改完提交才收到错误。
- 背景：批 O 的第二轮（`dcc64f9`）已经把 `TrafficManagementScreen` 那一路的四个入口灰掉了，
  hero 卡这个入口因为「是当天新加的功能、与并行改动重叠」**刻意留到后面**。
- 归属：§7 的 3.5（app 侧按能力集置灰）的收尾项。改法照既有口径：
  `DeviceCapabilityState.supports()` 判定 + 置灰不改 `checked` + 文案只说「这台设备不支持 X」。

**P1-45 web 侧至今没有消费 `GET /api/device/capabilities`**

- 事实（2026-09-25 全仓 grep `web/src`）：**`capabilities` 一次都没出现**，
  `/api/device/capabilities` 零消费；`AboutPanel.vue` 只读 `/api/diagnose` 的 `device_profile.profile_id`。
- 后果：web 上所有「设备不支持」的开关**照样能点**，点了才收到 501 ——
  这正是阶段 3 收益最直接的那一半，目前只在 app 侧兑现了。
- 连带：§7 的 **3.6 与 3.8 都还是 `[ ]`**。3.8（两端镜像一致性）现在只剩 web 这一半 ——
  app 侧直接复用 core 的 `Capability` 枚举，结构上不可能错。
- 归属：§7 的 3.6 + 3.8，一批做完（要动 `web/src/api/contract.ts` 的镜像类型 + 各面板的禁用态）。
  ⚠ 值域是 11 项但 F50 只下发 10 项（不含 `BATTERY`），web 侧**不要按「数组长度」判**任何事情。

**P1-46 三类「名字带 Goform」的改名欠账（配置键与对外字段是双端契约，必须单独一批）**

- 事实（2026-09-25 读 `UpperLayerGoformFreeGuardTest` 的 allowlist A / B / C 组核过）：
  - `GoformQoS`（并发许可 + 传输层缓存）与 `GoformSessionLog`（会话日志）住在 **`:core:common`**，
    是「后端到设备的 HTTP 出向通道」的公共设施，任何协议的设备都要用，**与插件化无关**；
  - `AppSettings` 的 `qosGoformQueryMax` / `qosGoformSetMax` 等配置项名；
  - `DeviceTransport.updateGoformPassword` —— 这是**契约层 `:core:device-spi` 自己**的方法名
    （`DeviceTransport` 14 个方法之一），不是 goform 模块的类型，上层调它没有任何耦合问题。
- 为什么不能顺手改：前两类要动**配置键** `qos_goform_*` 与 `/api/config` 的**对外字段**
  （app / web 都在读），第三类要动契约层的公开方法名 —— 三者都是双端契约变更，
  按「对外 API 只做加法」的口径要单独一批、单独过 `scripts/verify-api-contract.mjs`。
- 后果（当前**只有认知成本**，没有功能问题）：读代码的人会以为上层还绑着 goform。
  守门测试已经把这三类与「真正的跨模块耦合」分开登记（A / B / C 组 vs 已清零的 E 组），
  所以不会再被混为一谈。
- 归属：独立一批（对外契约变更），不阻塞接第二台设备。

**P1-37 三套温度阈值撞名，其中一对「数值相同、语义相反」**

- 事实（2026-09-24 阶段 4 盘点）：同一个「温度阈值」概念在三个模块里是**三件不同的事** ——
  下载限速 75/85（`DownloadManager.Config`，Float，改 aria2 并发与限速，另有 `+10` 的第 4 档 95°C
  `forcePauseAll`）、采集降频/熔断 70/80（`AppSettings.monitorThermal*`，**Int 毫摄氏度**，
  调 QoS 许可 / 拉长 cache TTL / `delay` 暂停采集）、用户告警 65/75
  （`AlertEngine.AlertConfig`，Double，**带 3°C 回差**，写 alert_records + 推送）。
- ⚠ **陷阱**：`DeviceTuning.downloadThrottleWarnC = 75f` 与 `AlertEngine.temperatureCritical = 75.0`
  **数值相同、语义相反**（一个是限速的下沿，一个是告警的上沿）。
  看到「两边都是 75」就接线 = 静默改掉告警行为，**单测不会红**。
- 已做（批 30）：`DeviceTuning` 字段改名成 `downloadThrottle*` 说实话，
  每个字段的 KDoc 写明「哪些近名阈值不归它管」，并新增 `downloadThrottleForcePauseOffsetC`
  承载那个原本裸字面量的 `+10`。
- 未做：三套阈值仍散在三处，**这是刻意的**（裁决 A：采集降频与用户告警是用户可配策略，不是设备事实）。
  真正要防的是「有人顺手统一」——靠 KDoc 与本条登记。

**P1-38 Samba `root preexec` 提权链路的执行入口是死代码**

- 事实：`SambaRootShell` 的 `deploy()`（写 `smb.conf` 的 `root preexec` + 提取 socat）与
  `BackendService` 的 60s/300s 保活循环**都在跑**，但**没有任何代码通过那个 socket 执行命令** ——
  全仓 `ShellExecutor.executeAsRoot()` 都走 **ADB 自连 localhost:5555（uid 2000）**，
  回落是普通 `sh -c`（无特权）。`su -c` 已彻底移除（只剩几处过期注释）。
- 所以 `hasRootAccess()` 的真实语义是「**ADB 通道可用**」，不是 uid=0。
  `SystemController` 与 `ShellRoutes` 两处上报的 else 分支文案还不一致（`"none"` vs `"shell"`）。
- 待裁决：① 把 Samba 那条接上（它能给到真 uid=0，是 ADB 不可用时的唯一兜底）；
  ② 删掉部署与保活（省一条常驻链路与一次 `smb.conf` 改写）；③ 维持现状并在文档写明它是预留。
  ⚠ 这条涉及提权，**不许顺手删** —— 它可能是刻意留的伏兵。
- 已做（批 30）：`PlatformAdapter` **刻意不设 `privilegeEscalation()`**，不为不存在的路径造抽象。

**P1-39 app 侧下载限速阈值是一份陈旧镜像（55f / 70f）**

- 事实：`DownloadStates.kt` 的默认值与 `DownloadModule.kt` 的解析 fallback 都是 **55f / 70f**，
  而 core 侧 2026-09-02 已上调到 **75f / 85f**（理由：F50 空载就 64~66°C，55 等于开机即限速且永不退出）。
- 现在看不出问题，因为 core 每次都下发真值；但「core 拿不到配置时 app 显示 55/70」这条路径一直存在。
- 归属：app 侧单独一批（本轮全程没碰 `app/**`）。



- 事实（2026-09-24 批 29 调查时核出，**本批没动**）：`TaskRoutes.kt:176` 是全仓**唯一**的旧 501
  出口（条件引擎未装配），配的错误码却是 `ErrorCode.UNAVAILABLE`。
- 为什么不能照搬批 29 新建的 `NOT_SUPPORTED`：它的语义是「**core 自己的组件没装配**」，
  **可恢复**（装上就好了），而 `NOT_SUPPORTED` 的约定是「设备不支持、不可恢复、前端别重试直接灰掉」。
  两者混用会让前端把「core 少装了个组件」当成「这台设备没这功能」。
- 待裁决：① 改成 503 + `UNAVAILABLE`（与「可重试」一致，但丢掉「这是未实现」这层信息）；
  ② 新增一个 `NOT_IMPLEMENTED` 错误码（冻结区又多一项，要两端同步）；
  ③ 维持现状并在 API 手册写明这一处的特殊口径。
- 归属：与「三种不可用不许混码」一起单独一批，别混进设备插件化。

**P1-35 `checkDeviceEvents()` 的兜底 catch 仍有两处隐患**

- 事实（2026-09-24 做 2.11 时顺手核出，**本轮刻意不改**）：
  - `catch (e: Exception)` 的文案是 `checkDeviceEvents failed: ${e.message}` ——
    **把异常消息拼进了日志**。2.11 把 `station_list` 形态这一类从它手里拿走了，
    但别的异常（尤其元素循环里的）仍可能带设备数据，基数无界 → 绕过 `repeatGate` 的折叠。
  - 它会**吞掉普通 `CancellationException`**（`TimeoutCancellationException` 先被上一个 catch 接走，
    但 scope 取消时的 Cancellation 会落到这里被当普通异常处理）。
    同一文件里其它多处（`:848` / `:1002` / `:1041` / `:1081` / `:1115` / `:1294` / `:1557` / `:1643` 等）
    都是 `catch (e: CancellationException)` 先行重抛 —— **本函数与它们写法不一致**。
- 另一处相关事实：元素循环里 `element.jsonObject` / `row["mac_addr"]?.jsonPrimitive`
  对非对象 / 非原始值**仍会抛**，落到那个兜底 catch → 清基线。
  改成 `as? JsonObject ?: continue` 会把「某个元素坏了」变成「跳过它继续算差异」——
  那会改变**归一化开着时**的失败路径行为，超出 2.11 的范围。
- 归属：与「日志文案不许拼无界内容」一起单独一批；别混进设备插件化。

**P1-33 `field_coverage` 实测暴露出「永远命中不了」的重复注册项**

- 证据：2026-09-23 用户给的一份真机 `field_coverage`（NR 驻网、`normalization_enabled=true`、
  `profile_id=zte-goform`）。`missing` 列出的是**设备侧字段名**（与 `hit_source` 的 value 同口径）。
- `SIGNAL` 组 22 注册 / 14 命中。8 个 missing 里 **`lte_snr`（小写）** 与
  `CELL_INFO` 组命中的 **`Lte_snr`（大写 L）** 是**同一个物理量** —— 设备只返回大写那个，
  所以 `SIGNAL` 那条是死注册项。同组 `lte_arfcn` / `lte_band` / `lte_pci` / `lte_cell_id`
  与 `CELL_INFO` 命中的 `Lte_fcn` / `Lte_bands` / `Lte_pci` 也是**名字不同的同一物理量**
  （`arfcn` vs `fcn`、`band` vs `bands`）。
- `DEVICE_SETTINGS` 组同型：missing 的 `dial_roam_setting_option` 与命中的 `roam_setting_option`
  是同一物理量，设备用后者。
- ⚠ **不能只看这一份就删**：这份是 **NR 驻网**，`lte_*` 大面积 missing 本身正常；
  而 `CELL_INFO` 里**同时**出现小写 `lte_rsrq` 与大写 `Lte_snr`，说明 ZTE 的混合命名确实存在。
- 下一步（**需要真机**）：在 **LTE 驻网**下再抓一份做对比 —— **两份都 missing 的才是真死注册项**，
  才可以删或改名。归属阶段 5（`ZteF50Plugin` 字段表清理）；**在拿到第二份抓取前不要动 `readSpecs()`**。

**P1-34 三个设置项读不到当前状态 —— 与「开关不许是假开关」直接冲突**

- 证据：同一份 `field_coverage` 的 `DEVICE_SETTINGS` 组，missing 里有
  **`UpgMode`**（FOTA 自动更新）、**`sleep_sysIdleTimeToSleep`**（WiFi 休眠空闲分钟）、
  **`BearerPreference`**，以及 `restart_time`（定时重启的时间 —— 注意
  `restart_schedule_switch` 本身是**命中**的）。
- 后果：这几项对应的 UI 开关/输入框**拿不到设备当前值**，只能显示本地缓存或默认值 ——
  正是「开关不许是假开关」要防的形态。`SettingKey` 里 `FOTA_AUTO_UPDATE` /
  `WIFI_SLEEP_IDLE_MINUTES` 有写侧 `WriteSpec` 却读不回状态，属于**写得进、读不出**。
- 待查（**需要真机**），三种可能要分清：
  ① 只在**对应功能开启后**才出现在设备返回里（`restart_time` 很可能是这种）；
  ② 注册的字段名本身不对（同 P1-33）；
  ③ 要用**另一条命令**查（`cmdsFor(DEVICE_SETTINGS)` 没带上它）。
- 按哪一种定归属：①→ UI 侧按「读不到 = 不知道」显示，**不许假装是关**；
  ②→ 归阶段 5；③→ 改 `cmdsFor` 是低风险修复，可以单独一批。

#### 批 25 新登记（P1-32）

**P1-32 `AppLogger` 的日志总开关默认关 —— 所有 WARN 在默认部署下都不落地**

- 事实（2026-09-23 批 D1 查 `AppLogger` 时发现）：`log()` 第一行是 `if (!active) return`，
  `active = loggingEnabled && coreLogEnabled`，而 `loggingEnabled` 的**持久化默认值是 false**
  （`AppSettings.DEFAULT_LOG_ENABLED`，`AppSettings.kt:297` 一带；`coreLogEnabled` 默认 true）。
- 后果：**用户没主动打开日志总开关时，WARN 一条都不落地**。受影响的至少有：
  - 批 D1 新加的「profile 未提供 X 频段全集掩码」；
  - 早就存在的「未登记写入项 $key，忽略本次写入」「主命令未成功，改发备用命令」；
  - `resolveDeviceProfile` / `DeviceRuntime` 的「未知 deviceProfileId，回落」与排障开关那条；
  - §6 验收 **2.11** 明确要求的「若仍解不出来，日志里**必须有一行 WARN**（不许静默）」。
- 所以「不许静默失败」这条纪律在**默认部署下不成立**：排障时用户得先打开日志开关、重启服务、
  再复现一次 —— 而「掩码缺失」「写入项未登记」这类事件往往就在复现前已经过去了。
- **这是既有属性，不是插件化引入的**，批 D1 没有改任何日志方式。
- 待裁决的口径（**用户拍板**）：① WARN/ERROR 是否应当无视总开关始终落地
  （与「release 只留 WARN/ERROR + 崩溃 dump 缓冲」的既定口径一致）；
  ② 还是把这类「判据级」事件改走 `AlertBus` / `/api/diagnose` 而不是日志；
  ③ 还是维持现状、只在文档里写明「排障前先开日志」。
- **2026-09-23 用户裁决：方案 ①** —— **WARN/ERROR 无视日志总开关始终落地，INFO/DEBUG 继续受开关管**。
  → **2026-09-24 批 27 已落地**，最终形态与两条实测结论：
  - **只无视 `loggingEnabled`（总开关），不无视 `coreLogEnabled`**。
    调查发现两个开关语义不同：`log_enabled` 是**全局硬开关**（默认 **false**，两端一起管），
    而 `core_log_enabled` 是**按侧静音**（默认 true，「只关后端日志、保留手机端」，
    因为 core 常驻写盘是体积增长的那一侧）。裁决原文与验收判据说的都是「总开关」，
    默认部署里 `core_log_enabled = true`，所以只无视前者就完整满足验收，
    而且保住了「真要静音后端」这个出口。判定抽成纯函数 `shouldEmit(level, logging, core)`。
  - **三条路全放行**（logcat + 文件 + 内存缓冲），不是只放行 logcat。
    理由：这台 F50 没 root、拿不到 logcat，只放行 logcat 等于线索仍然取不回来。
    成本实测：WARN 频率被三层既有闸门夹住（`repeatGate` 同文本 60s 折叠 1 条、
    `e()` 的 5 分钟错误去重、5MB 单文件 + 40MB 目录 + 7 天清理），
    离线场景下高频 WARN 文本逐 tick 一字不差、正好被折叠，估算 ≈3.4MB/天，在预算内。
  - **连带修（超出「只做分流」）**：logcat 那个 `when` 块包了 `try/catch`。
    WARN 不再被总闸拦在门外之后，**任何不用 Robolectric 的单测只要路过一条 WARN，
    就会从被测业务代码里炸出 `android.util.Log` 的 `not mocked` 桩异常**
    （实测 `NotificationDispatcherTest` 6 个用例栈顶是 `AppLogger.log`）。
    取舍：日志设施不该把调用方搞崩；真机上 `Log.*` 不抛、这个 catch 生产里永不触发；
    即使触发，文件与缓冲两条路在它之后、照样收到这一行。
    没选「给多个模块加 `isReturnDefaultValues`」是因为那会让所有 Android API 静默返回默认值，
    掩盖面比吞掉一次 logcat 调用大得多。
  - ⚠ **改了一条既有断言**（阶段 1 起的纪律是「既有断言一条不许改」）：
    `AppLoggerSwitchTest.总闸关掉后一条不记` 断言的正是**被这次裁决推翻的旧口径**
    （`assertTrue("log_enabled=false 必须连 ERROR 都不记", ...)`）。
    按 §13 的口径这属于「断言本来就在断言一个错误行为」，所以改名 + 改断言是对的，
    **但必须显式记在这里**，不能混在「测试都绿了」里一带而过。
  - `GoformSessionLog` 那条路（全量会话诊断）**刻意仍完整受总开关管** ——
    它不是按级别的排障日志，放行它等于默认部署下常驻写会话 dump。
- ⚠ 它原本卡住 2.11 的验收（判据是「必须有一行 WARN」）—— **现在已解锁**。

#### 批 23 新登记（P1-31）

- 事实：`private val profile: DeviceProfile = profile ?: ZteGoformProfile` —— 与 P1-30 同形态，
  只不过兜的是**写命令表**（`writeSpec()`），比读侧更危险：排障模式下会向 B 设备发 A 设备的写命令。
- 事实（2026-09-23 批 C 顺手 grep 到）：`GoformNetworkClient` / `GoformDeviceClient` / `GoformSimClient`
  **没有**同类兜底（它们不持有 `GoformFieldMapper`），所以这一形态全仓只剩 writer 这一处。
- 修法：同 P1-30 —— 收非空 `commandProfile`（无默认值），装配层传 `runtime.commandProfile`。
  ⚠ 它是 `internal class`，构造点不在 `ComponentFactory` 而在 `core/goform` 内部，
  改之前要先把「谁 new 它」查清（别顺手把非空性推给一个自己也在兜底的调用方）。
- 归属：与 P1-30 同批做最省事，但批 C 的契约只点了两个读侧客户端，所以**留到下一批**。

#### 批 22 新登记（P1-30）

**P1-30 `GoformSignalClient` / `GoformWifiClient` 内部的命令表兜底仍是「默认设备」**

- 事实：两个客户端内部都是 `GoformFieldMapper(profile, profile ?: DeviceProfiles.DEFAULT)`
  （非空那份由客户端自己补，口径是阶段 0.4a 定的）。
- 事实：批 B 之后装配层这一侧已经用 `runtime.commandProfile`（= **选中插件**的 profile）修正了
  `GoformSmsClient` 与 `DataScheduler` 两处，但这两个客户端**内部**那句 `?: DeviceProfiles.DEFAULT`
  仍然指向注册表默认值。
- 后果（**当前无害、将来有害**）：关掉字段归一化时，这两个客户端的**命令表**会退回
  「默认设备的命令表」而不是「选中插件的命令表」。现在只有一个插件、两者同值，看不出来；
  接第二台设备后就是「排障模式下向 B 设备发 A 设备的 cmd」。
- 修法：给这两个客户端加第二个构造参数（非空 `commandProfile`），口径同 `GoformSmsClient`
  —— 装配层传 `runtime.commandProfile`，客户端内部不再自己兜底。
- 归属：**2.6 或阶段 3**（它改的是 `core/goform` 两个公开构造签名，不属批 B 的「只接线」范围）。
  ⚠ 改的时候注意：可空那份 `profile` 的语义**不许动**
  （`/api/diagnose` 的 `normalization_enabled` 就是从它的 null 推出来的）。

#### 批 18 新登记（P1-29）

**P1-29 关掉归一化时「设备接入 / 离开」事件静默停摆（`checkDeviceEvents()` 直读 `station_list`）**
—— **2026-09-23 裁决：登记并归阶段 2（任务 2.11），按「消费端容忍两种形态」修，不进 `NORMALIZE_ALWAYS`**

- 事实：`DataScheduler.checkDeviceEvents()` 直接取 `json["station_list"]?.jsonArray`，
  而把设备那种「数组的 JSON 字符串」双重编码拉平成真数组的是 WIFI_CLIENTS 的 `structuralDecoder`
  （`normalizeStationLists`）。`field_normalization_enabled=false` 时这一步没了，
  那行取值会抛 → 被 catch 吞掉 → 基线清空，**设备接入/离开事件不报也不报错**。
- 事实：它**没有**在 2026-09-22 的实机验证里暴露，因为 `device_events_enabled` 默认是关的。
  所以这条是「读代码读出来的」，不是「实测出来的」—— 真机上还没验过它到底抛在哪一行。
- 为什么**不**按 §11.13 的豁免处理：WIFI_CLIENTS 是**透传出口**（canonical 名就是设备原名），
  往 `NORMALIZE_ALWAYS` 里加它正是 §11.13 第 3 节禁止的事 ——
  每加一组，排障开关就少一块可观察面。`GoformNormalizeAlwaysTest`
  「豁免没有扩散到其它组」那条会直接拦下这种改法（它断言除 TRAFFIC_LIMIT 外每组 `assertSame(入参, 出参)`）。
- 修法（归阶段 2 任务 2.11）：让**消费端容忍两种形态** —— `station_list` 可能是真数组、
  也可能是「数组的 JSON 字符串」。API 手册对外部客户端已经是这个要求，我们自己的 scheduler 没照做。
  顺带把那个 catch 改成**至少打一行 WARN**：现在它把解析失败和「没有接入设备」压成了同一种表现。
- 归属：阶段 2（任务 2.11）。**不要在阶段 0/1 顺手改** —— 它改的是 scheduler 的行为，
  不属于「搬命令表」与「换签名」这两件行为不变的事（§13.1 第 2 条）。



---

## 16. 真机基线（2026-09-22，逐字可比对）

> ⚠ **2026-09-24 批 34 起有一处对外取值变化，比对基线时要知道**：
> `/api/at/platform`、`/api/at/status`、`/api/dashboard` 的 at 块、`/api/device` 的 at 块
> （四处同源于 `ATChannel.getPlatformInfo()`）——
> 展锐判据的 marker 从 `[sprd, spreadtrum]` 合并成并集 `[sprd, spreadtrum, unisoc]`。
> 影响面：`/proc/cpuinfo` 里**只有 `unisoc`、没有 `sprd` / `spreadtrum`** 的机型上，
> `platform` 由 `"UNKNOWN"` 变成 `"SPREADTRUM"`。
> **在用的 F50 不受影响**（它的 cpuinfo 带 `Spreadtrum`），高通机型不受影响，
> `connected=false` 时本来就不下发 `platform`。
> 合并的目的是消掉第二份判据（`ZteF50Plugin.probe()` 原本自带 `unisoc`、`ATChannel` 没带），
> 方向是「认得更准」，但它**是一次对外取值变化，不是等价搬迁**。

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



