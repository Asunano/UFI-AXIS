# UFI-AXIS

<p align="center">
  ⭐ 如果这个项目对你有帮助，请给它一个 Star！⭐
</p>

<p align="center">
  <img src="https://github.com/Asunano/UFI-AXIS/actions/workflows/build.yml/badge.svg" alt="Build Status">
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform: Android">
  <img src="https://img.shields.io/badge/API-31+-blue.svg" alt="API: 31+">
  <img src="https://img.shields.io/badge/Written%20in-Kotlin-orange.svg" alt="Written in: Kotlin">
  <img src="https://img.shields.io/badge/Web-Vue%203%20%2B%20Vite-brightgreen.svg" alt="Web: Vue 3 + Vite">
</p>

<p align="center">
  <strong>把随身 WiFi / CPE 变成一台可远程管理的设备：设备侧后端 + Compose 手机端 + 浏览器面板</strong>
</p>

<p align="center">
  <a href="#功能特性">功能</a> •
  <a href="#技术架构">架构</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#技术栈">技术栈</a> •
  <a href="#常见问题-faq">FAQ</a>
</p>

---

## 项目简介

**UFI-AXIS** 不是「App 直连设备网页」的工具，而是一套 **上位机系统**：把一个 Ktor 后端服务（`:core`）装进随身 WiFi 设备自身的 Android 系统里常驻运行，由它统一对接 goform 协议、AT 指令与本机 `/proc`、`/sys`，再通过 HTTP + WebSocket 把统一后的数据与控制能力交给两个客户端。

- **`:core`（`com.ufi_axis_core`）** — 运行在设备上的后端 APK。Ktor Netty 监听 `0.0.0.0:8088`，负责采集、告警、调度、设备控制、文件/下载/内网穿透，并内置浏览器面板静态资源。
- **`:app`（`com.ufi_axis`）** — 手机端 App，100% Jetpack Compose，5 个主 Tab + 约 55 条二级路由，自带完整设计系统与自研页面切换器。
- **`web/`** — Vue 3 单页面板，构建产物随 `:core` APK 打包进 `assets/web`，也可脱离 APK 单独 OTA 更新。

三端共享一份冻结契约（`core/contract`），并由 CI 脚本守门，避免「改了一侧忘了另一侧」。

### 它解决了什么

| | |
|------|------|
| **后端在设备上** | 断开手机不停采集，告警、限额断网、短信转发邮件、下载、隧道全部继续跑 |
| **协议全在一处** | goform 登录/签名、AT 透传、shell 控制收敛进 `:core`，两个客户端只认统一 JSON |
| **零共享密钥** | 每台客户端一对不可导出私钥，服务端只存 token 的 SHA-256，删记录即吊销 |
| **实时不烧电** | 有客户端连着才提速到 3s，没人连退化到 60s；浏览器面板切后台自动暂停全部轮询 |
| **数据在本地** | Room 库落在设备本地，不经任何云端中转 |
| **契约冻结** | `core/contract` + `web/src/api/contract.ts` 双侧镜像，由 CI 脚本校验一致性 |


---

## 核心亮点

### 1. 设备协议防腐层

把厂商接口的所有脏活关在一个模块里，上层只看统一模型。

- **goform 双层签名**：登录口令 `SHA256(SHA256(password) + LD)`，写操作签名 `AD = SHA256(SHA256(wa_inner_version + cr_version) + RD)`
- **会话自治**：session 缓存 90s、定期校验、登录失败指数退避（上限 10s）；检测到官方后台占用 session 时主动让位 30s
- **批量读取与限流**：`multi_data=1` 一次取多字段，外加并发许可 + 短时查询快照，避免把设备 CPU 打满；响应编码错误自动回退 GBK，截断响应会被识别并失效缓存
- **AT 透传**：`sendat` 二进制经 Binder IPC 下发（展锐平台），带最小指令间隔与连续失败熔断
- **设备适配层**：`core/device-schema` 用 `DeviceProfile` + `FieldSpec` 声明字段组、解码器与敏感级别，新机型只加一份 profile


### 2. 连接感知的采集调度

`DataScheduler` 用 8 条并发协程分别负责 CPU/内存、信号+流量、月流量、短信、电池、设备事件、本地告警扫描、旧数据清理。

| 采集项 | 有客户端连接 | 无连接 |
|--------|------------|--------|
| CPU / 内存 | 自适应 ~1.5–4.5s（基准 3s） | 60s |
| 信号 + 流量（合并为 1 次 goform 查询） | 自适应 ~1.5–4.5s | 60s |
| 月流量 | 15s | 15s |
| 短信 | 5s | 15s |
| 电池 | 30s | 30s |
| 在线设备变更 | 60s | 60s |
| 本地告警扫描（温度/电量/网络，零 goform 请求） | 15s 恒定 | 15s 恒定 |

- **自适应间隔**：按 shell / goform 实时负载在基准值的 0.5–1.5 倍之间浮动
- **冷热解耦**：采集是否运行只看监控总开关；是否广播只看 WebSocket 连接数——前端断开只停推送、不停记录
- **写入合并**：5 个内存缓冲每 30s 合并为**一个** Room 事务落盘（WAL 模式）
- **两级响应缓存**：设备读取与最终 JSON 各有一层 LRU + TTL 缓存，同 key 并发只打设备一次；服务停止后只回旧值、不再打设备


### 3. 不会变成通知风暴的告警引擎

`AlertEngine` 支持 8 类告警，全部走「边沿触发 + 聚合累加」。

| 类型 | 触发条件 | 默认阈值（warning / critical） |
|------|---------|------------------------------|
| `temperature` | 设备温度 | 45°C / 55°C |
| `battery` | 电量（充电中不报） | 20% / 10% |
| `traffic` | 累计用量 | 1024 MB / 2048 MB |
| `traffic_limit` | 套餐限额占比 | 80%（可配） / 100% |
| `signal` | RSRP | -100 dBm / -115 dBm |
| `connectivity` | 外网可达性变化 | 状态机，无阈值 |
| `device_online` / `device_offline` | WiFi 客户端上下线 | 无 |

- **边沿触发**：级别未跃迁不产生新记录；跌回正常标记 `resolved`，不再插行
- **连通性确认窗**：新状态需连续保持 **60s** 才判定，杜绝 WiFi 抖动刷屏；判据是外网真实可达（`NET_CAPABILITY_VALIDATED`），不是 modem 注册态
- **聚合**：同 `(type, level)` 的未确认记录只累加 `count` 并标记 `aggregated`，环形上限 2000 条
- **配置乐观锁**：`configVersion` 不匹配直接返回 **HTTP 409** + 当前配置，多端同改不会互相覆盖；局部更新走顶层键级合并，少传字段不会清空别端开关
- **邮件只在新告警插入时发**：聚合累加不发信，避免稳态超标变成邮件轰炸

### 4. 邮件通知（SMTP 在设备侧）

告警、短信、验证码、流量预警、下载完成、隧道异常等 **8 个场景**各有独立开关与模板，由设备侧直发 SMTP：465 端口走 SSL、587 走 STARTTLS，投递期间只短暂持有带超时的 WakeLock。场景开关的唯一真源在 `:core`，App 只负责展示与上报。

- 邮件模板 HTML + 纯文本双份，不引用任何外部字体与图片（兼容 Outlook / Gmail，也不泄露阅读行为）
- 验证码提取需先命中提示词（验证码/校验码/动态码/口令/code/OTP）才抓 4–8 位数字，避免把「余额 123456」当成验证码


### 5. 设备身份认证

客户端与 core 之间用 ECDSA P-256 签名，**没有任何共享密钥**：私钥在配对时生成且不可导出（App 在 Android Keystore，Web 在 WebCrypto + IndexedDB），服务端只存令牌的 SHA-256。令牌本身不足以通过鉴权——每个请求还要带该设备私钥对「方法 + URI + 时间戳 + Nonce」的签名。删掉配对记录即吊销一台设备。细节见 [安全说明](#安全说明)。

### 6. 实时推送与后台通知

- **WebSocket**：`/ws/realtime`，握手必须带签名参数；按频道订阅，未订阅的不推。**最大连接数 4**（App 与 Web 共享）——每多一个连接设备侧采集就会提速，不设上限等于给低端设备无限加压；超限以 1013 关闭并允许退避重连
- **独立通知进程**：App 内 `:ufi_notify` 第二进程只跑通知分发与保活，不加载 Compose 与 ViewModel。去重游标的单写者在此进程，不会一条告警响两次
- **场景化通知**：`NotifyScene` 枚举统一管开关 / 限频 / 免打扰 / channel（告警可突破免打扰、短信与验证码静默、下载与流量预警走各自 channel）
- **兜底而非常驻**：后台守护是 WorkManager 周期任务（15/30/60 分钟，带联网约束，开关全关时零网络开销）；及时性依赖设备侧事件驱动，不靠周期闹钟或常驻 WakeLock 硬扛

### 7. 一套自建设计系统

App 的视觉与交互收敛在 `:app:ui`：6 套预设皮肤 + Android 12+ 动态取色，主题与动效令牌各有单一真源，54 个 `Ufi*` 共享组件，以及一个自研页面切换器（支持横滑手势、可打断、可反向，6 种内置转场）。

两个用来防腐的机制：**组件画廊**（设置 → 外观 → 组件画廊，可切明暗预览，专门暴露「没跟随主题变」和「同类组件不一致」）；**字面量基线**（Gradle 任务 `checkLiteralBaseline` 统计非 theme 源码里的裸 `dp` / `Color(0x…)` / `sp` / `tween(数字)`，与 `config/literal-baseline.properties` 比对，只允许降不允许升）。

改主题去哪改，见 `docs/app-theme-token-index.md`。


---

## 功能特性

### 手机端（5 个主 Tab）

| Tab | 主要能力 |
|-----|---------|
| **仪表盘** | 连接状态卡、设备信息、实时指标卡与指标详情、网络明细图表 |
| **网络** | 网络模式切换、频段锁定、小区锁定、蜂窝高级参数、在线设备列表、WiFi / DHCP 设置 |
| **监控** | 实时曲线与概览、事件中心、聚合事件视图、数据导出，以及 6 组监控设置（采集 / 指标 / 图表 / 行为 / 调度 / 存储） |
| **工具** | 高级控制台、测速、流量管理与限额、定时任务与自动化规则、内网穿透（frp / Cloudflare）、调试日志 |
| **我的** | 服务端与配对配置、通知与守护、告警设置、每日报告、邮件通知、外观设置、设备控制、数据管理、诊断、关于 |

另有文件管理（含文本编辑器 / 图片查看 / Media3 播放）、下载管理（任务 + 限速 + Tracker + 高级设置）、短信会话、应用管理等页面，从 Tab 内部进入。

### 设备侧能力（`:core`）

- **网络控制**：网络模式（别名 → goform `BearerPreference` 映射统一走契约层）、LTE/NR 频段锁定、WiFi 设置、DHCP、数据开关
- **流量治理**：套餐限额、用量统计、到量自动断网守卫
- **短信**：读取 / 发送 / 会话缓存 / 已读状态 / 验证码提取与定期清理，转发邮件由设备侧事件驱动
- **定时任务与自动化**：Cron 解析 + 条件引擎 + 10 种动作（`data_toggle` `wifi_toggle` `airplane_toggle` `reboot` `shutdown` `led_toggle` `performance_mode` `roaming_toggle` `network_mode` `custom_shell`）
- **下载器**：Aria2 引擎 + 任务管理 + Tracker 列表维护
- **内网穿透**：frpc 与 cloudflared 双引擎，二进制按 `version.json` 声明的 URL + SHA-256 下发校验安装（当前 frpc `0.71.0`、cloudflared `2026.8.3`）
- **应用管理 / ADB / Shell**：包列表与安装卸载、AT 终端、受 QoS 限流的 shell 执行
- **文件服务**：浏览 / 上传 / 下载 / 编辑
- **测速与 QoS 配置**、**OTA**：前端 APK、后端 APK 与 Web 面板三条独立更新通道（Web 面板走 ZIP + SHA-256 校验 + 原子覆盖，失败回滚）

### 浏览器面板（`web/`）

登录、仪表盘、网络、设备、短信、任务、监控、终端、告警、应用、文件、下载、隧道、设置（8 个子面板）共 14 条路由，全部动态加载。

- **请求可取消**：`useRequestScope` 收集组件内全部 `AbortController`，卸载时统一 abort；被取消的请求不抛错，而是返回 `{ __canceled: true }`，不会弹误报 toast
- **切后台自动停轮询**：`visibilitychange` 统一暂停所有活跃轮询，回到前台再恢复——面板留在手机后台标签页时不再持续吃蜂窝流量
- **轮询防重入**：慢接口不会堆积并发请求；间隔支持跟随用户设置动态变化
- **WebSocket 单例**：连接生命周期只由布局层管理，视图只能订阅；指数退避重连（上限 9 次 / 30s），1008 直接放弃并展示原因
- **首屏瘦身**：签名用的椭圆曲线库动态加载、Naive UI 按需引入、echarts 与 vendor 单独分包
- **严格类型**：`strict` + `noUncheckedIndexedAccess` + `verbatimModuleSyntax`，`vue-tsc` 类型错误即断构建

---

## 技术架构

### 部署形态

```
┌──────────────────────┐          ┌──────────────────────┐
│   手机端 App          │          │   浏览器面板          │
│  com.ufi_axis        │          │  Vue 3 SPA           │
│  Compose + :ufi_notify│         │ （内置于 core APK）    │
└──────────┬───────────┘          └──────────┬───────────┘
           │   HTTP + WebSocket（ECDSA P-256 签名）        │
           └────────────────┬─────────────────────────────┘
                            ▼
            ┌───────────────────────────────────┐
            │  随身 WiFi / CPE 设备（Android）     │
            │  com.ufi_axis_core                │
            │  Ktor Netty  0.0.0.0:8088         │
            └───────────────┬───────────────────┘
                            ▼
          goform HTTP  ·  AT 指令  ·  shell  ·  /proc  /sys
```

### `:core` 分层（依赖方向自上而下）

```
┌──────────────────────────────────────────────────────────┐
│ core (application)   BackendService / ComponentFactory     │
├──────────────────────────────────────────────────────────┤
│ core:network         Ktor HttpServer + 静态资源 + SPA 兜底   │
├──────────────────────────────────────────────────────────┤
│ core:api             40 组 Routes + DataHub + 鉴权 + 配对    │
├──────────────────────────────────────────────────────────┤
│ core:alert   core:scheduler   core:controller  ← 稳定门面   │
├──────────────────────────────────────────────────────────┤
│ core:collector  core:goform  core:cache                   │
│ core:database   core:websocket                            │
├──────────────────────────────────────────────────────────┤
│ core:common        工具 / QoS / 日志 / 签名                 │
│ core:contract      跨端冻结契约（纯 JVM）                    │
│ core:device-schema 设备字段与命令规则（纯 JVM）               │
└──────────────────────────────────────────────────────────┘
```

### `:app` 分层

`:app` 是入口壳，实际内容在 5 层：`app:feature-*`（9 个页面模块）→ `app:viewmodel`（`MainViewModel` + module/state 拆分）→ `app:data`（api / repository / model / 通知 / 日志）→ `app:ui`（主题令牌 / 动效 / 共享组件 / 导航骨架）。feature 模块统一依赖 `:app:viewmodel + :app:data + :app:ui`，**不依赖 `:app`**，以避免与入口模块形成循环依赖。5 个主 Tab 也不是 5 个导航目的地，而是收敛进单一宿主目的地内 `UfiPageSwitcher` 的 5 页。


### 数据存储

设备侧 Room 库 `ufi_axis_core.db`（**v8**，WAL 模式），优先落在 `/data/ufiaxis/db`（可写时），否则回退应用私有目录。9 张表：`traffic_records`、`signal_history`、`alert_records`、`sms_records`、`sms_read_state`、`sms_verification_codes`、`cpu_history`、`memory_history`、`battery_history`。5 个显式迁移（3→8）全部保留，不使用破坏性回退。

---

## 技术栈

- **Kotlin 2.2.10** + Java 17 目标；AGP 9.2.1 + KSP；26 个 Gradle 模块；`minSdk 31` / `targetSdk 36`，release 仅打 `arm64-v8a`
- **设备侧后端**：Ktor 2.3.12（Netty / WebSockets / CORS）+ Ktor Client CIO + Room 2.8.4 + JavaMail
- **手机端**：Jetpack Compose（BOM 2026.02.01）+ Material 3 + Navigation Compose + 自研 `UfiPageSwitcher`；Retrofit / OkHttp、Coroutines + Flow、WorkManager、Media3、Coil
- **浏览器面板**：Vue 3.5 + Vite 6 + TypeScript 5.6 + Pinia + Naive UI + ECharts + Tailwind
- **测试**：JUnit + MockK / Mockito + Robolectric + `kotlinx-coroutines-test`；Room 迁移有 androidTest 覆盖
- **CI/CD**：GitHub Actions（JDK 21 + SDK 36；push/PR 构建双端 debug 并校验版本源，tag 触发签名发版与 Web 更新包）

精确版本号以 `gradle/libs.versions.toml` 与 `web/package.json` 为准，此处不再逐条复制。


---

## 项目结构

```
UFI-AXIS/
├── core/                        # 设备侧后端（com.ufi_axis_core）
│   ├── src/                     # application：BackendService / ComponentFactory / 保活看门狗
│   ├── contract/                # 跨端冻结契约：Endpoints / Enums / DeviceFields / ErrorCode
│   ├── device-schema/           # DeviceProfile / FieldSpec / ZteGoformProfile
│   ├── common/                  # DeviceAuth 签名、AppLogger、Shell/Goform QoS、AppSettings、CronParser
│   ├── goform/                  # goform 协议防腐层：GoformClient + 6 个领域客户端 + Codec
│   ├── collector/               # 本机采集：system / telephony / signal / AT 通道
│   ├── cache/                   # ResponseCache + JsonResponseCache
│   ├── database/                # Room v8：9 Entity / 9 DAO / 5 迁移
│   ├── websocket/               # WebSocketManager + WebSocketPushService
│   ├── alert/                   # AlertEngine（8 类告警 / 边沿触发 / 聚合 / 版本锁）
│   ├── scheduler/               # DataScheduler / TaskScheduler / ConditionEngine / ActionExecutor
│   ├── controller/              # 网络 / 短信 / 系统 / 应用 / Aria2 下载 / frp & cloudflared / ADB
│   ├── api/                     # 40 组 Routes + DataHub + AuthMiddleware + PairingManager + OTA
│   └── network/                 # HttpServer（Netty 8088，静态资源 + SPA 兜底 + web 构建挂钩）
├── app/                         # 手机端（com.ufi_axis）
│   ├── src/                     # MainActivity / CrashHandler / NotifyService(:ufi_notify)
│   ├── ui/                      # 主题令牌 / 动效 / 54 个 Ufi* 组件 / 导航骨架 / 组件画廊
│   ├── data/                    # api / model / repository / NotificationCenter / DebugLog
│   ├── viewmodel/               # MainViewModel + module/* + state/* + BackgroundManager
│   └── feature-*/               # dashboard network monitor tools settings files download sms apps
├── web/                         # 浏览器面板（Vue 3 + Vite），产物内置进 core APK
│   ├── src/api/contract.ts      # core:contract 的 TS 镜像（改一侧必须同步）
│   ├── src/composables/         # useApi / useCancellableApi / useRequestScope / useRealtime
│   ├── src/stores/              # app / dashboard / service / websocket
│   └── src/views/               # 14 个页面 + settings 的 8 个子面板
├── config/literal-baseline.properties   # UI 字面量基线（只降不升）
├── scripts/verify-api-contract.mjs      # 双端契约一致性校验
├── docs/                        # 全部项目文档：plans/（计划书）· design/（设计方案）· deliverables/（交付记录）· 主题令牌索引等
├── version.json                 # 单一版本源：frontend / backend / web / 二进制组件
├── package-web.sh · package-web.bat     # 独立 Web 更新包打包
└── .github/workflows/           # build.yml · release.yml · component-versions.yml
```

---

## 使用方法

### 快速开始

需要一台可安装第三方 APK 的随身 WiFi / CPE（Android 系统，API 31+，`arm64-v8a`）。基础功能以普通应用身份运行；shell 相关的高级能力（应用管理、部分系统控制、数据库落 `/data/ufiaxis`）在拿到更高权限时才完整可用。

1. 从 [Releases](https://github.com/Asunano/UFI-AXIS/releases) 下载最新的 **core** 与 **app** 两个 APK
2. 在设备上安装 core 并启动后端服务（常驻前台服务，默认端口 `8088`）
3. 在手机上安装 app
4. 手机连上该设备的 WiFi，打开 App 走首次配对：填写设备地址与端口 → 完成三步握手（首次会下发配对码并要求设置管理密码）
5. 配对成功后 App 生成设备私钥（存于 Android Keystore，不可导出），此后所有请求自动签名
6. 也可以直接用浏览器访问 `http://<设备IP>:8088` 打开内置面板，同样需要配对与登录

> 服务默认监听 `0.0.0.0`，同一局域网内均可访问。**请设置强管理密码**，不要把 `8088` 端口直接映射到公网。

### 从源码构建

```bash
# Web 面板会随 :core 一起构建，需要 Node 20+
cd web && npm ci && cd ..

# 出包：默认用 benchmark 变体（R8 + 资源压缩，debug 签名，可直接 adb install）
./gradlew :core:assembleBenchmark :app:assembleBenchmark

# 需要抓日志排查时才用 debug 包
./gradlew :core:assembleDebug :app:assembleDebug

# 只调后端、跳过 Web 构建
./gradlew :core:assembleBenchmark -PskipWeb

# 静态检查（含 UI 字面量基线守门）
./gradlew check
```

两端 `benchmark` 变体的代码优化程度与 release 完全一致，但用 debug 签名，两端签名相同，signature 级权限与跨进程调用不受影响。**不要用 debug 包测性能**：Compose 首次执行走解释执行 + 后台 JIT，会产生随机掉帧尖峰。

### 版本与发布

根目录 `version.json` 是**唯一版本源**（`frontend` / `backend` / `web` 三个对象 + 二进制组件声明）。CI 会校验它与 `gradle.properties` 一致，不一致直接失败。发版流程：改 `version.json` → CI 通过 → 打 `v<backend.version>` tag → `release.yml` 产出签名 APK 与 `web-update-<version>.zip`。

### 打包独立 Web 更新包

```bash
./package-web.sh          # Linux / macOS
package-web.bat           # Windows（不要用 Compress-Archive，见下方 FAQ）
```

---

## 常见问题 FAQ

### 支持哪些设备？

目前只有一份设备 profile（ZTE goform 系、运行 Android 的随身 WiFi，如 F50 / U30 Air 等）。设备适配层已把「字段名 / 解码规则 / 写命令」抽成 `DeviceProfile`，新增机型只需加一份 profile 并在配置里指定 `deviceProfileId`——**不做自动探测**，因为探测本身就需要先知道用哪套字段。

### 一定要 root 吗？

不必。core 以普通应用身份安装即可运行，监控、告警、短信、网络控制这些走 goform / AT 的能力都不依赖 root。需要更高权限的只有 shell 类功能：应用管理、部分系统控制，以及把数据库落到 `/data/ufiaxis/db`（没权限时自动回退到应用私有目录）。

### 装了它，设备原来的官方后台还能用吗？

能用，但两者会抢同一个 goform 登录会话。core 检测到官方后台正在占用 session 时会主动**让位 30 秒**再重试，所以你在官方页面操作期间 App 侧可能短暂读不到新数据，等你退出后会自动恢复。不要长时间挂着官方后台不关。

### 手机不开 App，设备还会记录数据、发通知吗？

会。采集、告警判定、限额断网、短信转发邮件全部跑在设备侧的 core 里，与客户端是否在线无关。区别只在于**采集频率**：没有客户端连接时 CPU / 内存 / 信号等高频项从约 3 秒退化到 60 秒，本地告警扫描仍固定 15 秒。

### 为什么通知有时候会延迟几分钟？

前台或有 WebSocket 连接时是实时推送。App 完全被后台冻结时，兜底路径是 WorkManager 周期任务，而它的平台下限是 15 分钟——这就是延迟的来源。短信与告警的及时性主要靠设备侧事件驱动（`ContentObserver` + 落库处直接推送并发邮件），所以即使 App 没被唤醒，邮件通常也已经发出去了。

### 能不能从外网访问？

不要直接把 `8088` 映射出去：服务当前只有 HTTP，且 CORS 为 `anyHost()`，设计前提是「设备在可信局域网内」。需要远程访问就用项目内置的内网穿透（frp 或 Cloudflare Tunnel），由隧道侧提供 TLS 与接入控制。

### 自己打的 Web 更新包装上去白屏（Windows）

别用 `Compress-Archive`。PowerShell 5.1 写出的 ZIP 条目名使用反斜杠，违反 ZIP 规范；设备端解包时会把它当成「含反斜杠的扁平文件名」，`assets/` 目录不会生成，`/assets/*.js` 落到 SPA 兜底拿回 `index.html`，于是白屏。用仓库里的 `package-web.bat`，它逐条写入并强制正斜杠，打完还会自检条目名与根目录 `index.html`。


---

## 安全说明

### 认证与授权

- ECDSA P-256 非对称签名，私钥不可导出；服务端零共享密钥
- 令牌仅以 SHA-256 存储，按设备签发、按设备吊销
- 时间戳窗口 ±5 分钟 + Nonce 缓存防重放
- 配对码仅在设备未初始化时下发；密码错误由每 IP + 全局双层限速器节流
- 免鉴权白名单严格限定为 `/health`、`/ws/*`、`/pairing/*`、`/pair`、`/`
- **已知残余风险**：请求体不参与签名（Ktor 需全量缓冲请求体，与大文件上传冲突）

### 数据与隐私

- 所有数据存于设备本地 Room 库，不上传任何云端
- 敏感字段分级（`PUBLIC` / `MASKED` / `SECRET`），日志自动脱敏（IP / IMEI / Token / Authorization）
- 配置读取时 `goform_password` 等字段返回脱敏值，回写脱敏值会被拒绝
- 邮件模板不引用任何外部资源，避免泄露阅读行为

### 部署注意

- 服务监听 `0.0.0.0:8088`、仅 HTTP 无 TLS、CORS 为 `anyHost()`——设计前提是「设备处于可信局域网」。**请勿把该端口暴露到公网**，跨网访问走隧道
- 权限型开关在 UI 上显示的状态 = 本地开关 **AND** 系统实际放行，不做「假开关」


---

## 调试与诊断

- **调试日志**：App 内「工具 → 调试日志」实时查看，分类过滤、脱敏输出；release 构建只保留 WARN/ERROR 与崩溃 dump 缓冲
- **网络日志通道**：设备侧 `AppLogger.net()` 单独记录 goform / AT 交互；SMTP 会话原样重定向进日志，发信失败可直接看协议对话
- **诊断页**：「我的 → 诊断」汇总服务状态、连通性、配对与权限检查
- **崩溃处理**：全局 `UncaughtExceptionHandler` 捕获后落盘，下次启动展示
- **契约校验**：`node scripts/verify-api-contract.mjs` 比对 `core/contract` 与 `web/src/api/contract.ts`，防止端点 / 频道 / 字段名单侧漂移

---

## 相关文档

- `docs/UFI-AXIS-Core-API-Reference.md` — 设备侧 REST / WebSocket 接口参考
- `docs/app-theme-token-index.md` — 主题与动效令牌索引（改 X 去改哪个文件）


---

## 贡献指南

- 提 Issue 到 [Issues](https://github.com/Asunano/UFI-AXIS/issues)，附上设备型号、固件版本、权限状态与调试日志（日志已脱敏，可直接粘贴）
- 遵循 Kotlin 官方规范；Web 侧 `npm run lint` 必须通过（`vue-tsc` 类型错误即断构建）
- 改动跨端接口时，`core/contract` 与 `web/src/api/contract.ts` 必须同步，并跑 `node scripts/verify-api-contract.mjs`
- UI 代码不要新增裸字面量（`dp` / `Color(0x…)` / `sp` / `tween(数字)`），走 `:app:ui` 的令牌；`./gradlew check` 会拦
- 提交信息遵循 Conventional Commits


---

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

---

## 更新日志

各版本的变更说明见 [Releases](https://github.com/Asunano/UFI-AXIS/releases)。版本号本身以根目录 [version.json](version.json) 为唯一源（当前 frontend / backend / web 均为 `0.0.1`，首个公开测试版本）。

---

## 感谢

- [frp](https://github.com/fatedier/frp) · [cloudflared](https://github.com/cloudflare/cloudflared) — 内网穿透能力
- [Ktor](https://ktor.io/) · [Jetpack Compose](https://developer.android.com/compose) · [Vue](https://vuejs.org/) · [Naive UI](https://www.naiveui.com/)
