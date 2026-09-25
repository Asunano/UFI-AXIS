# 首屏预加载 + 切页不等待 落地计划

> 目标形态：冷启动时**一次性、分批、静默**把三个主 Tab（仪表盘 / 网络 / 监控）需要的数据拉齐；
> 切 Tab 时若数据够新则直接渲染、不再发请求；回到页面时走 **stale-while-revalidate**
>（保留旧数据 + 静默刷新，不清空、不转圈）。
>
> 已确认的三个口径（2026-09-22）：
> - **不做常驻后台轮询**。"后台加载"实现为 SWR，视觉等同但不多花电、不抢 goform 槽位。
> - **只做进程内新鲜度**，不给网络 / 监控加持久缓存。同一次运行切 Tab 不等；冷启动仍需预加载那几秒。
> - **预加载等仪表盘首屏渲染完再开始后续批次**，避免和首屏抢连接槽。
>
> **2026-09-22 第二轮口径变更（用户反馈驱动）**：
> - 新增**启动加载页**（参考 `D:\AndroidStudioProjects\new\UFITOOLS-Widget` 的加载页）。
>   原来"不转圈、直接进仪表盘"的做法在用户侧等于这个功能不存在 —— 原话「完全没有首屏预加载相关的界面」。
> - **监控中心接入预加载**：原方案只备了监控的设置 / 窗口 / 告警，没拉指标序列，
>   所以切过去总览那 6 格还是要等。现在按总览实际用到的 6 类预热。
> - 「首帧后开始」这条被启动页取代：启动页本身就是首帧，闸门改成「确证 ONLINE」+ 启动页收尾判据。


---

## 0. 文档用法

- 每个任务前的方括号就是状态，改状态**只改这个文件**：
  - `[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成（必须同时满足该阶段「验收」全部条目）
  - `[!]` 受阻（后追一行 `→ 阻塞原因`） · `[-]` 已放弃（后追一行 `→ 放弃理由`）
- 阶段之间是硬依赖：前一阶段没到 `[x]` 不要开下一阶段。
- 本文件里的 `文件:行号` 是撰写时（2026-09-22）的位置，代码动过之后按符号名找。

---

## 1. 目标与非目标

### 目标

1. 冷启动进入仪表盘后，切到网络 / 监控**不再看到空白或等待**（同一次运行内）。
2. 预加载失败**绝不**产生用户可见后果：不弹 toast、不写 `globalError`、不触发刚合并的掉线弹窗。
3. 回到已加载过的页面时，旧数据留在屏幕上，新数据到了再替换。
4. 请求并发受控，不把首屏拖慢。

### 非目标

- **不做常驻后台轮询**（app 前台时后台 Tab 轮询，或 app 切后台继续轮询）。与项目既定后台策略冲突，
  且 goform 查询信号量只有 4 permits，三条轮询并行会互相排队、反而拖慢当前可见页。
- **不给网络 / 监控加 Room 或 SharedPreferences 持久缓存**。`CacheManager` 现在是单行全局、不按设备分桶，
  新增表要同步接进 `prepareDeviceSwitch()` / `onServerEndpointChanged()` 的清理链路，本轮不值得。
- **不动 `UfiPageSwitcher` 的 `keepPagesAlive`**。它已经是 `true`（`UfiPageSwitcherDefaults.kt:51`），
  Tab 组合根本不会销毁 —— 切 Tab 慢不是重组导致的，是每次 `pageForeground` 翻 true 就重新发请求。
- **不改 core 端任何接口与缓存 TTL**。

---

## 2. 现状盘点（as-is）

### 2.1 五个 Tab 的加载触发点

`MainNavGraph.kt:99-107` 定义 5 个底部 Tab，全部是**同一个** NavHost 目标下 `UfiPageSwitcher` 的页，
`keepPagesAlive` 默认 `true` → `beyondViewportPageCount = 1`（`UfiPageSwitcherHost.kt:897`）。
因此 `onDispose` 不能当"离开页面"用，门控统一靠 `isUfiPageForeground()`。

| Tab | 触发点 | 发什么 |
| --- | --- | --- |
| 仪表盘 | `DashboardScreen.kt:118` / `:130-137` / `:146-151` | `network.loadServiceStatus()`；`dashboard.startAutoRefresh(10s)`；`tools.loadTrafficLimit()` + `network.refreshWifi()` |
| 网络 | `NetworkScreen.kt:51-55`（`delay(200)`）· `:64-70`（错误重试 5s×3）· `:78-81`（ON_RESUME） | `loadNetworkAll()` = **6 个请求**（`NetworkScreen.kt:528-535`） |
| 监控 | `MonitorScreen.kt:117-136` / `:144-152` / `:167-187` / `:352-355` / `:451-456` | 设置回读 + 选窗口 + `loadStartupTime()`；`refreshDashboard()`；按可见图块拉 `loadMonitorTypes` |
| 工具 | 无 | 静态入口网格（`ToolsScreen.kt:38-70` 说明了为什么删掉 loading） |
| 我的 | `SettingsScreen.kt:41-42` | `network.loadServiceStatus()`（未做 foreground 门控） |

→ **"全部信息"实际只有仪表盘 + 网络 + 监控三份。** 工具无数据，我的只有一个本地读的
`/api/service/status`（`ServiceRoutes.kt:57`，纯读本机设置，无设备 I/O）。

### 2.2 哪里已经不用等

- 仪表盘有 Room 缓存：`CacheManager.kt:21-74`，`DashboardModule.loadCachedData():192-207` 在 init 时恢复
  cpu / memory / traffic / signal / deviceInfo / battery / storage / uptime + `lastUpdated`。
  所以它的 shimmer（`DashboardScreen.kt:185-188`，条件 `isLoading && deviceInfo == null`）
  只在首次安装或换设备后出现。
- 轮询不闪空：`refreshDashboardInternal` 只在 `deviceInfo == null` 时写 `isLoading`（`DashboardModule.kt:514-520`）。
- 已有新鲜度戳模式可直接复用：`dashboardSuccessElapsed`（`DashboardModule.kt:79`）、
  `NetworkModule.wifiSuccessElapsed:267`、`ToolsModule.trafficLimitSuccessElapsed:3320`。

### 2.3 哪里必须等

- **网络页零持久化**，6 个字段全在内存，进程重启必重取；`pageForeground` 每次翻 true 都重发。
- 监控页刻意惰性：首屏只设窗口，每个可见图块自己拉指标。

### 2.4 并发上限（决定"能不能一把梭"）

- `OkHttpClientProvider.kt:17-24` **没有配置 `Dispatcher`**，走默认 `maxRequestsPerHost = 5`。
  `/api` 全打同一 host ⇒ 并发超过 5 只会排队。`readTimeout = 120s`，一个挂住的请求占一个槽两分钟。
- 鉴权重试在**拦截器里 `Thread.sleep`**（`RetrofitClient.kt:174-182`，300/600ms），突发时会占着槽位。
- `DashboardModule.refreshSemaphore = Semaphore(1)`（`:82`，`:498` withPermit），仪表盘刷新严格串行；
  `refreshDashboard()` 会 **cancel 掉在飞的 job**（`:483-484`）。
- core 端**故意不限流**：`HttpServer.kt:480-481` —— "已鉴权接口一律不限流：web 打开一个页面会并发发十几个请求"。
  真正的节流在 goform：`GoformQoS.kt:36-46` 查询信号量 4（1–8 自适应）/ 写 2（1–4）/ TTL 缓存 2s。
- AT 通道**只有 `/api/device/qos` 一条**在这些页面的路径上（`DeviceRoutes.kt:269-296`），
  且 app 侧已经做了"只取一次"（`DashboardModule.kt:580-582`）。AT 互斥不是本轮风险。

### 2.5 最大的坑（必须在阶段 1 就防住）

`HealthModule` 的掉线判据是 `CONFIRM_FAILURE_COUNT = 2` / `CONFIRM_FAILURE_SPAN_MS = 3_000`
（`HealthModule.kt:195-211`），而每个传输层失败都会驱动一次探活（`RetrofitClient.kt:159-168`，
1.5s 去抖）。**冷启动瞬间十来个请求同时失败，正好在 3s 内凑够 2 次确证** →
刚刚合并的「无法连接后端服务」弹窗会在开机时误弹。

---

## 3. 阶段 1 — 预加载协调器（静默、分批）✅

- [x] 1.1 新增 `app/viewmodel/.../viewmodel/PreloadCoordinator.kt`。`start()` 用 `AtomicBoolean`
      保证整个进程只跑一次；`reset()` 供换设备复位。
- [x] 1.2 分三批，批间 `delay(BATCH_GAP_MS = 1_500)`：
      - 批 1：`network.loadServiceStatus(silent = true)`
      - 批 2：`network.loadNetworkAll(silent = true)` + `tools.loadTrafficLimit()`
      - 批 3：`refreshMonitorSettingsFromBackend()` + `selectMonitorRange(today())` +
        `loadStartupTime()` + `loadAlerts(silent = true)`
      **偏差**：计划里写"批间等上一批完成或超时"，实际只能 `delay`。module 里那些 `loadXxx()`
      都是 fire-and-forget（内部 `scope.launch`），要真等就得把它们全改成 suspend。
      已在类注释里写明这是明知故犯的近似，代价上限是两批重叠，而重叠也不会超过
      OkHttp 那 5 个 per-host 槽位。

      **偏差**：批 2 里计划列的 `network.refreshWifi()` 已去掉。`loadNetworkAll` 内部的
      `refreshNetwork()` 本身就并发拉了 `getWifiSettings()` + `getWifiClients()`（就是
      `refreshWifi` 的那两个请求），且成功时一并写 `wifiSuccessElapsed`。再调一次是
      纯重复，只会多占一个 per-host 槽位。

- [x] 1.3 静默契约走**方案 (b)**：给 4 个会写进 `globalError` 链路的方法加 `silent` 形参
      （`refreshNetwork` / `loadBandStatus` / `loadCellInfo` / `loadServiceStatus`），
      `loadNetworkAll(force, silent)` 透传。没用方案 (a)（全局静默期）—— 那会把同期的真错误也吞掉。
      每一步再套一层 `step()` 吞异常，`CancellationException` 原样上抛。
- [x] 1.4 触发时机：`MainActivity` 导航图之后一条 `LaunchedEffect(Unit) { viewModel.onFirstFrameRendered() }`。
- [x] 1.5 两个闸门都满足才启动：`firstFrameReady` **且** `connectivity == ONLINE`。
      两侧谁后到都能触发（`start()` 幂等）。离线时压根不发这一批请求 —— 这就是 §2.5 的对策。

**验收**：`ForegroundRefreshFreshnessTest.preload_mustBeBatchedSilentAndOnlineGated` 钉住了
分批 / 静默 / ONLINE 闸门 / 幂等 / `CancellationException` 上抛 / 两处 `reset()`。
真机冷启动观感与请求并发数待手测。

---

## 4. 阶段 2 — 页面侧"够新就不发请求" ✅

- [x] 2.1 `DataFreshness.kt` 新增 `NETWORK_ALL_FRESH_MS = 30_000`；`NetworkModule` 新增
      `networkAllSuccessElapsed` 与 `loadNetworkAll(force, silent)`。
      **偏差**：戳不是在 `loadNetworkAll` 里打，而是在 `refreshNetwork()` 成功且
      `failures == null` 时打 —— 那 6 个请求各自 fire-and-forget，没有统一的"全部完成"时刻；
      `refreshNetwork` 是主请求，它全成才算这一页拉齐了。部分失败不打戳，
      避免把缺字段固化成"新鲜"。
- [x] 2.2 `NetworkScreen` 三个调用点改走 module：首次加载不 force、出错重试 `force = true`、
      ON_RESUME 不 force。页面里的私有 `loadNetworkAll(viewModel)` 已删除。
- [x] 2.3 `DashboardModule.refreshDashboardIfStale()`：`refreshJob?.isActive` 时直接返回（不 cancel），
      数据在窗口内也直接返回。
      **额外**：`MonitorScreen.kt:144-152` 也从 `refreshDashboard()` 换成了它 —— 仪表盘 Tab
      与监控 Tab 读同一份 `dashboardState`、都保活，用取消版就是互相掐掉对方的聚合请求。
- [x] 2.4 `loadServiceStatus(force, silent)` 加新鲜度闸门（默认 10s 窗口）。
      两处写后回读改 `force = true`；`ServiceGate` 的「刷新状态」按钮改 `force = true`。
      Dashboard / Settings 那两条 `LaunchedEffect(Unit)` 不必改 —— 闸门会挡住重复。

**验收**：`networkAll_fanOutMustLiveInModuleWithFreshnessGate` /
`serviceStatus_mustBeGatedButWriteBackMustForce` / `dashboardPreloadPath_mustNotCancelInFlightRefresh`
/ `networkAllFreshWindow_mustBeLongerThanPollingWindow` 全部通过。

---

## 5. 阶段 3 — SWR 语义（回页面静默刷新）✅

- [x] 3.1 网络页：`refreshNetwork()` 的 `isLoading` 从无条件写改成
      "只有 `signalInfo == null` 才写"，与 `DashboardModule` 同一判据。
      其余 5 个 loader 本来就用 `?: it.xxx` 兜底、失败不清空字段。
- [x] 3.2 监控页：节奏不动（`trafficSummary == null` 立即拉、否则 `delay(1500)`），
      只把入口换成不取消的 `refreshDashboardIfStale()`。
- [x] 3.3 仪表盘：本来就是 SWR；预加载不经过 `isLoading`（`refreshDashboardIfStale` 里
      有数据就直接返回）。

**验收**：`refreshNetwork_mustNotWriteLoadingWhenDataExists` 通过。
"来回切 10 次不出现空白"待手测。


---

## 6. 阶段 4 — 启动加载页 + 监控接入（2026-09-22 第二轮）✅

- [x] 4.1 `PreloadCoordinator` 加进度态 `PreloadProgress(issued, total, label, finished)`，
      7 步各带中文步骤名。`issued` 刻意不叫 `done` —— module 那些 `loadXxx()` 是 fire-and-forget，
      协调器只知道"已发起"，拿不到"回包已落地"。
- [x] 4.2 批次重排：批 1 服务状态 + 仪表盘；批 2 网络页 + 流量配置；
      批 3 监控设置 → 监控曲线 → 告警。批间隔从 1500ms 降到 800ms（启动页期间用户在等）。
- [x] 4.3 **监控中心接入**：新增 `OVERVIEW_METRIC_KEYS`（cpu / memory / traffic_rx / traffic_tx /
      signal_rsrp / temperature），走 `loadMonitorTypes`。
      **关键坑**：range 必须复用 `dashboard.monitorState.value.selectedRange`，
      不能自己 new 一个 `MonitorTimeRange.today()` —— 它的 `endMs` 按分钟向下取整，
      跨分钟就是另一个记账 key（`monitorRangeKey`），`syncMonitorRangeKey` 会清掉记账、
      `MonitorScreen` 进页面时把这 6 类重拉一遍，预热等于白做。
      顺序也是硬的：`refreshMonitorSettingsFromBackend()`（suspend）必须先跑，
      `enabledTypes` 决定拉哪几类、`collectEnabled = false` 时 `loadMonitorTypes` 直接空转。
- [x] 4.4 新增 `app/src/main/java/com/ufi_axis/app/startup/UfiStartupOverlay.kt`：
      复用现成的 `UfiLoadingIndicator`（呼吸弧，与参考实现同款观感）+ 步骤文案 + `x / 7` 计数。
      **叠在导航图之上**而不是替换它 —— 替换的话导航图要等浮层消失才开始组合取数，
      预加载省下的时间又还回去了。
- [x] 4.5 `MainViewModel.startupGate()`：三条终局 —— ①预加载步骤发完且 `deviceInfo != null`；
      ②确证不可达（直接放行，让掉线弹窗接手）；③`STARTUP_GATE_TIMEOUT_MS = 8s` 兜底。
      外加 `STARTUP_GATE_MIN_MS = 600ms` 最短展示（局域网上几百毫秒就跑完，不设下限会"闪一下"）。
      **`_startupOverlayVisible.value = false` 必须在 `withTimeoutOrNull` 之外** ——
      写进块里就意味着超时路径不放行，用户被永久锁在加载页。
- [x] 4.6 「首帧闸门」删除：`onFirstFrameRendered()` / `firstFrameReady` 一并移除，
      `preload.start()` 现在只由「confirmed ONLINE」触发（两个调用点，`start()` 幂等）。
- [x] 4.7 掉线弹窗的「重试」补 Toast「正在重新连接设备…」。它刻意不关窗（探活结果才是答案），
      于是点下去屏幕上什么都不动，而 `/health` 最长 15s 才出结论 —— 没反馈时按钮看起来就是坏的。

**验收**：新增 4 条护栏 —— `preload_mustWarmMonitorOverviewWithTheSameRangeInstance`
/ `startupGate_mustAlwaysHaveATerminalExit` / `connectivityRetry_mustGiveVisibleFeedback`，
以及 `TOTAL_STEPS` 与实际 `step(` 调用数的一致性断言（防止进度停在 6/7 之类的假数）。全绿。

**仍未做的手测**：启动页在真机上的观感与耗时、飞行模式下启动页是否按第②条终局及时放行、
切到监控中心是否零请求（记账 key 那条坑只在跨分钟才暴露）。


| 风险 | 表现 | 回退 |
| --- | --- | --- |
| 预加载拖慢首屏 | 仪表盘出现变慢 | 把批 2/3 的间隔从 3s 拉到 5s，或只保留批 2 |
| 静默契约漏了一条路径 | 冷启动弹莫名 toast | 该条改回 `silent = true` |
| 新鲜度窗口太长 | 切回去看到过期数据 | 调小 `NETWORK_FRESH_MS` |
| 换设备后残留 | 显示上一台数据 | 检查 `prepareDeviceSwitch()` / `onServerEndpointChanged()` 是否清了新鲜度戳 |

整体回退：`PreloadCoordinator.start()` 不调用即恢复改动前行为（阶段 2/3 的新鲜度判断是幂等的，
最坏情况只是多发请求）。

---

## 7. 变更记录

| 日期 | 阶段 | 说明 |
| --- | --- | --- |
| 2026-09-22 | — | 文档创建，三个口径已确认（SWR / 仅进程内 / 首帧后开始） |
| 2026-09-22 | 1 / 2 / 3 | 三阶段一次落地。新增 `PreloadCoordinator.kt`；`DataFreshness.kt` 加 `NETWORK_ALL_FRESH_MS`；`NetworkModule` 加 `loadNetworkAll(force, silent)` / `resetFreshness()` / 4 处 `silent` 形参 / `loadServiceStatus` 闸门；`DashboardModule` 加 `refreshDashboardIfStale()` / `resetFreshness()`；`MainViewModel` 接入协调器与两条闸门、换设备复位；`NetworkScreen` 扇出下沉；`MonitorScreen` 换用 IfStale 入口。`ForegroundRefreshFreshnessTest` 新增 6 条护栏，全绿。 |
| 2026-09-22 | 4 | 用户反馈驱动的第二轮：新增启动加载页（`UfiStartupOverlay` + `MainViewModel.startupGate`）、监控中心接入预加载（`OVERVIEW_METRIC_KEYS` + 复用 range 实例）、批次重排与间隔降到 800ms、删除首帧闸门、掉线弹窗「重试」补 Toast。护栏 +4 条，全绿；`:app:compileBenchmarkKotlin` 通过。**手测项仍未做**：启动页真机观感与耗时、飞行模式下是否按"不可达"那条终局及时放行、切监控中心是否零请求。 |


