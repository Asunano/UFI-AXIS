# UFI-AXIS Core Backend API Reference

> 本文档覆盖 UFI-AXIS 后端 core 模块的全部 API 接口，供 Web 前端开发对接使用。
> 默认端口 `8088`，内容格式 `application/json; charset=UTF-8`。

---

## 目录

- [全局说明](#全局说明)
- [认证与授权](#认证与授权)
- [通用响应格式](#通用响应格式)
- [免认证接口](#免认证接口)
  - [健康检查](#健康检查)
  - [设备配对（免认证部分）](#设备配对免认证部分)
  - [Web 静态资源](#web-静态资源)
- [配对管理 /api/pairing](#配对管理-apipairing)
- [配对设备管理 /api/pairing/devices](#配对设备管理-apipairingdevices)
- [仪表盘 /api/dashboard](#仪表盘-apidashboard)
- [设备信息与控制 /api/device](#设备信息与控制-apidevice)
- [网络控制 /api/network](#网络控制-apinetwork)
- [系统资源 /api/system](#系统资源-apisystem)
- [流量统计 /api/traffic](#流量统计-apitraffic)
- [SIM 卡 /api/sim](#sim-卡-apisim)
- [AT 命令 /api/at](#at-命令-apiat)
- [告警管理 /api/alerts](#告警管理-apialerts)
- [通知配置 /api/notifications](#通知配置-apinotifications)
- [WiFi 控制 /api/wifi](#wifi-控制-apiwifi)
- [短信管理 /api/sms](#短信管理-apisms)
- [文件管理 /api/files](#文件管理-apifiles)
- [应用管理 /api/apps](#应用管理-apiapps)
- [Shell 执行 /api/shell](#shell-执行-apishell)
- [服务器配置 /api/config](#服务器配置-apiconfig)
- [更新管理 /api/update](#更新管理-apiupdate)
- [Web 前端资源 /api/web](#web-前端资源-apiweb)
- [内网穿透 /api/tunnel](#内网穿透-apitunnel)
- [邮件通知 /api/sms-forward](#邮件通知-apisms-forward)
- [定时任务 /api/tasks](#定时任务-apitasks)
- [自动化规则 /api/rules](#自动化规则-apirules)
- [下载管理 /api/downloads](#下载管理-apidownloads)
- [测速 /api/speedtest](#测速-apispeedtest)
- [监控中心 /api/monitor](#监控中心-apimonitor)
- [服务控制 /api/service](#服务控制-apiservice)
- [调试日志 /api/debug-logs](#调试日志-apidebug-logs)
- [QoS 诊断 /api/qos](#qos-诊断-apiqos)
- [缓存管理 /api/cache](#缓存管理-apicache)
- [诊断 /api/diagnose](#诊断-apidiagnose)
- [WebSocket /ws/realtime](#websocket-wsrealtime)

---

## 全局说明

### 服务器配置

| 项目 | 值 |
|------|-----|
| 框架 | Ktor (Netty 引擎，嵌入式) |
| 默认端口 | 8088 |
| 序列化 | kotlinx.serialization JSON (UTF-8) |
| 最大请求体 | 默认 512 KB；`/api/files/upload` 200 MB、`/api/web/update` 50 MB、`/api/update/upload` 按版本清单动态（未知回落 100 MB） |
| CORS | 允许任意来源；GET/POST/PUT/DELETE；允许 Authorization, Content-Type, X-Timestamp, X-Nonce, X-Signature 头 |

### 缓存机制

读接口普遍使用 `ResponseCache` 做内存缓存，写接口执行后按模式失效缓存（如 `device:*` 或 `*` 全局）。主要 TTL 值：

| 缓存键 | TTL |
|--------|-----|
| device:info | 10 分钟 |
| device:settings | 5 分钟 |
| device:goform | 5 分钟 |
| device:identity | 30 分钟 |
| device:version | 30 分钟 |
| device:traffic-limit | 5 分钟 |
| device:lan | 10 分钟 |
| wifi:settings | 2 分钟 |
| wifi:clients | 15 秒 |
| network:band-status | 5 分钟 |
| network:cell-info | 20 秒 |

`CacheTTL` 里还有个 `SIM_INFO`（15 分钟）常量，但它服务的 `/api/sim/info` 已删除，
现在没有任何路由写 `sim:info` 这个键 —— 换卡后失效的是 `device:*`（见 `POST /api/sim/switch`）。

### 设备字段契约

设备侧（ZTE goform 等）的字段名不稳定：换固件改名、不同型号叫法不同、同一个值分散在几个字段里。
**core 不再透传设备字段**，对外只输出登记在 `core/contract/.../DeviceFields.kt` 里的 canonical key
（`web/src/api/contract.ts` 是手抄镜像，两侧由 `scripts/verify-api-contract.mjs` 比对）。
设备侧的可变性由 `:core:device-schema` 的 `DeviceProfile` 吸收，适配新设备 = 加一个 profile 文件，
本手册和客户端都不用动。

三条硬性约定：

- **既有 key 不改名。** 命名是混的（`lte_band_lock` snake、`BearerPreference` Pascal、
  `dhcpLease_hour` camel+snake），这是透传时代的历史债，冻结即接受。
- **新增 key 一律 `snake_case`。**
- **字段缺失 = 省略该 key，不输出 `null`。** 客户端普遍写 `pick(...) ?? ''` / `?: default`，
  省略与 null 行为一致；数值型"无数据"用 `-1` 哨兵（如 `pci` / `arfcn`）。

未登记的设备字段**不会出现在响应里**（allowlist 默认拒绝）。四个例外端点保持原样透传，仅供诊断，
客户端不应依赖其字段名：`GET /api/device/goform`、`GET /api/wifi/module-info`、
`POST /api/device/goform/query`、`POST /api/device/goform/set`。
`GET /api/device/goform` 默认关闭（配置项 `goform_dump_enabled`，关着回 403），打开后 PII 与凭据类字段值也会打成 `***`；
**`/api/wifi/module-info` 既没有开关也没有脱敏**，`Password` 等字段是设备原始明文，别把它当"安全的诊断端点"往界面上摆；
两个 `POST /api/device/goform/*` 是裸命令通道，字段名完全由调用方传入的 cmd 决定，**不归一化也不脱敏**，
默认关闭（配置项 `goform_command_enabled`）。

**排障开关：** `field_normalization_enabled` 置 false，读侧会退回原样透传设备字段
（写入命令表与 `/api/network/signal` 不受影响）。此时容器字段可能是"数组的 JSON 字符串"而不是数组 ——
客户端解容器时两种形态都要能吃（见 `/api/wifi/clients`）。
两个注意点：它**不在 `/api/config` 的可读写字段里**（`GET /api/config` 看不到、`PUT /api/config` 也改不了，
只能改 core 进程的 SharedPreferences），而且**只在构造组件图时读一次，改完要重启后台服务才生效**。
同一开关族的 `device_profile_id` 同理。

#### 旧字段 → 新字段对照（照这张表改客户端）

| 端点 | 旧字段（已不再输出） | 现在读 |
|------|---------------------|--------|
| `/api/wifi/settings` | `wifi_enable`、`wifi_onoff_state` | `WiFiModuleSwitch`（`"1"` = 开） |
| `/api/wifi/settings` | `wifi_chip1_ssid`、`wifi_chip1_passphrase` 等短名 | `wifi_chip1_ssid1_ssid`、`wifi_chip1_ssid1_passphrase` |
| `/api/device/traffic-limit`、`/api/dashboard/summary` | `limit_size`（`"470_1024"` 复合串）、`limit_unit`（恒为 `"MB"`，无意义） | `limit_value` + `limit_unit_display` + `limit_bytes` |
| `/api/device/lan-settings` | `lan_ip`、`dhcp_type`、`dhcp_start`、`dhcp_end` | `lan_ipaddr`、`dhcpEnabled`、`dhcpStart`、`dhcpEnd` |
| `/api/network/cell-info` | `network_type`、`Nr_pci`、`Nr_fcn`、`Nr_bands` | 服务小区统一字段在 `/api/network/signal`：`band` / `band_label` / `arfcn` / `pci` / `signal_strength` |
| `/api/network/cell-info` | `serving_cell` / `neighbor_cells` / `locked_cell` 三段嵌套结构 | 平铺的 `neighbor_cell_info` / `locked_cell_info` + `Lte_*` |
| 各端点的布尔 | 自己比较 `"1"`/`"on"`/`"true"`/`"SERVER"` | 仍是这几种编码，但**只保留一个 key**；判定用统一 helper（app `DeviceFields.Bool.isTrue`） |
| 写入侧 | `way` / `data` / `time` / `rat` 等设备风格入参 | 规范入参（如 `lte_bands` / `nr_bands` / `limit_value` + `limit_unit`），旧格式仍兼容一版 |

**制式判断不要看 `rat` 文案**：它是 44 项映射表的输出（含 `"NSA"` / `"未知(xx)"`）。判 5G 用
`/api/network/signal` 的 `band_label` 是否以 `n` 开头 —— 这个字段由 core 按"NR 优先、LTE 兜底"派生。

#### 写操作的值域校验：400 `OUT_OF_RANGE`

设备写入项的合法值集合 / 区间 / 字符集写在 profile 的 `WriteSpec.validate` 里。**参数不合法时
core 不会向设备发任何请求**，直接回 `400` + `OUT_OF_RANGE` + 具体原因：

```json
{ "success": false, "ok": false, "code": "OUT_OF_RANGE",
  "error": "频段号必须是 1..255 的纯数字", "message": "频段号必须是 1..255 的纯数字" }
```

这与"命令发出去了但设备没写成"是**两种不同的结果**，客户端要分开处理：`OUT_OF_RANGE` 是自己传错了、
重试无用；设备侧失败可以重试。

**但别用 HTTP 状态码来区分这两者** —— 设备失败时各端点回的码历史上就不一致，至今没统一
（统一会改掉客户端在看的响应形状）：

- 回 **200** + `{"success": false}`：`/api/device/cell-lock`、`/api/device/restart-schedule`、
  `/api/device/dhcp`、`/api/device/data-limit`、`/api/device/flow-calibration`
- 回 **500** + `{"success": false}`：`/api/network/mode`、`/api/network/bearer`、
  `/api/wifi/sleep`、`/api/sim/switch`
- 回 **400** + `{"success": false}`：`/api/network/band`（**设备失败也是 400**，
  和值域被拒同码不同体）

可靠的判定方式只有两条，**别看状态码**：

1. 值域被拒 ⟺ 响应体里 `code == "OUT_OF_RANGE"`（失败体统一由 `ResponseHelper.fail` 生成，
   固定含 `success:false` / `ok:false` / `error` / `message` / `code`，`error` 与 `message` 都是拒绝原因）；
2. 其余情况看 `success` 布尔 —— 200 也可能是失败，而失败体里没有 `code` 就说明是设备侧没写成。


覆盖 `OUT_OF_RANGE` 的端点：`/api/network/band`、`/api/network/mode`、`/api/network/bearer`、
`/api/device/cell-lock`、`/api/device/restart-schedule`、`/api/device/dhcp`、
`/api/device/data-limit`、`/api/device/flow-calibration`、`/api/wifi/sleep`、`/api/sim/switch`。


### QoS 安全阀

QoS 限的是 **core 自己往下打的量**，不是客户端往 core 打的量 —— 它保护的是设备后台和这台机器的
CPU/温度，不是防刷。两个独立的自适应信号量（`GET /api/qos/status` 可看实时状态）：

- **`GoformQoS`**（后端 → 设备 goform HTTP）：查询 4 并发（动态 1~8）、写入 2 并发（动态 1~4），
  外加 2 秒 TTL 的查询缓存。防的是"多个页面同时刷 + 后台采集"把设备后台打满 —— goform 并发一高
  就会超时或返回空。
- **`ShellQoS`**（后端 → 本机 shell）：root 5 并发（动态 2~6）、普通 5 并发（2~8）+ 2 秒缓存 + 批量合并。
  防的是频繁 fork shell 进程导致 CPU 占用与温度飙升。

两者的并发数由 `DataScheduler` 的性能监控循环按 **CPU 温度 + CPU 使用率**动态收缩：
75°C 或 CPU >80% 时收紧，85°C 时收到最低档并清空两个缓存、暂停一轮（温度熔断）。
`qos_enabled` / `qos_shell_max_concurrent` / `qos_cache_ttl_ms` / `qos_goform_query_max` /
`qos_goform_set_max` 这几个 `PUT /api/config` 字段调的就是这里。

**HTTP 层没有全局限流。** 早期有个 `QoSMiddleware` 会对请求数限流并回 429，已于 2026-08-25
整个移除（`/api/*` 依赖鉴权拒绝，不再限频）。现在只有两处会因频率回 `429`：
`/pairing/*`（每 IP 500ms 一次 + 密码错误锁定）与 `/api/speedtest/*`（并发位耗尽）。
客户端**不需要**为 `/api/*` 的 429 写重试逻辑。


---

## 认证与授权

> 2026-08-28 起是**严格设备独立性**方案：每台设备一对 ECDSA P-256 密钥 + 一个独占 token。
> 权威实现 `core/common/.../DeviceAuth.kt`（验签方）、`app` 的 `DeviceKeyStore`、
> `web/src/composables/deviceIdentity.ts`。三端必须逐字节一致。
>
> **没有共享 secret，也没有全局 token**。旧文档里的「可选 HMAC 签名」「默认 Token 拒绝 403」
> 已随该方案一并废除，照旧文档实现的客户端一个请求都过不去。

### 每个请求必须同时满足四条

`/api/**` 的每一个请求都要带这四样（缺一即拒，见 `AuthMiddleware`）：

| 位置 | 内容 |
| --- | --- |
| `Authorization` | `Bearer <token>` —— `/pairing/confirm` 下发的**该设备**独占 token |
| `X-Timestamp` | 毫秒 Unix 时间戳，与服务端时钟差必须 ≤ 5 分钟 |
| `X-Nonce` | 客户端随机串（建议 16 字节 base64url），窗口内不可重复 |
| `X-Signature` | 用设备私钥对下面的规范串做 ECDSA-SHA256 签名 |

**签名不是可选项。** 旧实现是「客户端不发头就跳过」，等于形同虚设；现在缺头直接拒。

### 规范签名串

```
METHOD \n URI \n TIMESTAMP_MS \n NONCE
```

- `METHOD` 大写；
- `URI` 是**服务端看到的 path + query**（Ktor `call.request.uri`），保留原始百分号编码。
  传完整 URL（带 scheme/host）必然验签失败；
- `TIMESTAMP_MS` 与 `X-Timestamp` 同值；`NONCE` 与 `X-Nonce` 同值。

签名编码两端不同、由 core 归一化：Android Keystore 输出 **DER**（68-72 字节），
WebCrypto 输出 **raw r||s**（固定 64 字节），core 按长度判定后统一转 DER。
客户端**不要**自己做 DER 包装。base64 标准字母表与 base64url 都接受。

请求体**不在**签名内（Ktor 不缓存 body，为覆盖 body 而把 200MB 上传缓冲进内存不可接受）。
签名保证的是「请求来自持有私钥的设备且非重放」。

### 设备指纹

`fingerprint = base64url(SHA-256(SPKI DER))`，无填充，43 字符。
**由服务端据上报公钥计算，绝不采信客户端自报的指纹。**
客户端拿到的指纹来自 `/pairing/confirm` 响应回显，仅用于「在设备列表里高亮本机」。

### 鉴权失败的响应

失败一律回**自定义状态码 `444`** + `{ "error": "...", "code": "..." }`（不是 401）——
客户端按 444 判「需要重新配对/登录」。`code` 取值：

| code | 触发条件 |
| --- | --- |
| `UNAUTHORIZED` | 没带 `Authorization`，或 token 哈希不匹配任何已配对设备 |
| `INVALID_DEVICE_KEY` | 设备记录里没有公钥（旧记录）→ 必须重新配对 |
| `INVALID_SIGNATURE` | 缺签名头 / 时间戳超窗 / 验签失败 / nonce 重放 |

### 免鉴权范围

`AuthMiddleware` **只装在 `route("/api")` 里**，所以：

- `/api/**` 之外的一切（`/health`、`/pairing/**`、`/ws/realtime`、`/` 与 SPA 静态资源）
  本来就不过鉴权中间件；
- 中间件内部另有一份 `isPublic` 白名单（`/health`、`/ws/`、`/pairing/`、`/pair`、`/`），
  那是纵深防御 —— 将来若把配对端点搬进 `/api` 也不会死锁。

WebSocket 有自己的一套握手鉴权（query 参数，见 [WebSocket /ws/realtime](#websocket-wsrealtime)）。

---

## 通用响应格式

所有接口响应均为 JSON 对象，通过 `ResponseHelper.toJsonElement()` 将 `Map<String, Any>` 递归转换。成功响应通常包含 `success: true`，错误响应包含 `error` 字段。

**成功示例：**
```json
{ "success": true, "data": { ... } }
```

**错误示例：** 走 `ResponseHelper.fail(code, message)` 的失败体形状固定（`error` 与 `message` 同值，
是给人看的原因；`ok` 是 `success` 的别名，为兼容旧客户端保留）：
```json
{ "success": false, "ok": false, "error": "描述信息", "message": "描述信息", "code": "OUT_OF_RANGE" }
```

注意**不是所有失败都走这个形状**：设备写操作失败时不少端点只回 `{"success": false}`（无 `code`），
详见「全局说明 › 写操作的值域校验」。

**HTTP 状态码：**

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 201 | 创建成功 |
| 400 | 请求参数错误 |
| 401 | 业务级凭据校验失败（**配对/设备密码**：`INVALID_CODE` / `INVALID_PASSWORD` / `INVALID_DEVICE_KEY` 等） |
| 403 | 禁止访问（如非本地子网访问 `/pairing/*`、诊断端点开关关闭） |
| 404 | 资源不存在 |
| 409 | 冲突（如重复下载） |
| 413 | 请求体过大（默认 >512KB，上传类端点见「服务器配置」） |
| 429 | 请求过于频繁 / 密码错误锁定 / 测速并发位耗尽 |
| 444 | **`/api/**` 鉴权失败**（自定义码：token 无效 / 缺签名头 / 验签失败 / 重放），客户端据此判「需重新配对」 |
| 500 | 服务器内部错误 |

`/api/**` 的鉴权失败**不会**回 401 —— 401 只出现在配对与密码相关的业务校验里。

---

## 免认证接口

### 健康检查

#### `GET /health`

服务器健康状态，包含 WebSocket 连接数和缓存状态。

**响应：**
```json
{
  "status": "ok",
  "timestamp": 1234567890,
  "ws_connections": 2,
  "ws_parse_failures": 0,
  "ws_stale": false,
  "cache_stale": false
}
```

---

### 设备配对（免认证部分）

> 全部挂在**根路径**（不是 `/api/pairing`），仅响应本地子网（回环 / RFC1918 / 链路本地），
> 非本网段回 `403 FORBIDDEN`。
>
> 速率限制：**每 IP 每 500ms 放行一次**，`info` / `challenge` / `confirm` / `change-password`
> **各自独立计数**（否则「先 info 再 confirm」的正常握手会被同一个计数器误杀）。超频回 `429 TOO_MANY_REQUESTS`。
> 密码错误另有锁定：每 IP 15min 5 次、全局 15min 20 次 → `429 PASSWORD_LOCKED`。

#### 配对三步握手

客户端必须先有一对**本地不可导出**的 ECDSA P-256 密钥（App=Android Keystore，Web=WebCrypto
`extractable:false`），然后：

1. `GET /pairing/info` —— 取 `pairing_code`（仅未初始化设备下发）与设备信息；
2. `POST /pairing/challenge` —— 取一次性 nonce；
3. `POST /pairing/confirm` —— 带 `device_pubkey` + `challenge` + 对 challenge 的签名 + 密码，
   换回该设备独占的 `token` 与服务端计算的 `fingerprint`。

**指纹由服务端据公钥算，不接受客户端自报。** 旧版 Web 上报常量指纹，导致所有浏览器在服务端
看来是同一台设备、配对上限完全失效 —— 这是本方案要修的根因，不要退回去。

#### 从零到能调 API 的最短路径

```
# ① 取配对码（设备未初始化时才有值）
GET  /pairing/info
     → { "pairing_code": "123456", "has_default_password": true, ... }

# ② 取一次性挑战（2 分钟有效）
POST /pairing/challenge   {}
     → { "challenge": "Zm9vYmFy..." }

# ③ 用本地私钥对 challenge 原文签名后换 token
POST /pairing/confirm
     { "pairing_code": "123456",
       "device_pubkey": "<SPKI DER base64>",
       "challenge": "Zm9vYmFy...",
       "signature": "<sign(challenge)>",
       "password": "mypass123",          # 首次配对：这就是新设备密码
       "device_name": "My Browser" }
     → { "token": "abc...", "fingerprint": "Xy9..." }

# ④ 之后每个 /api 请求都要四件套（缺一即 444）
GET /api/dashboard/summary
    Authorization: Bearer abc...
    X-Timestamp: 1767225600000
    X-Nonce: 8fK2...
    X-Signature: <sign("GET\n/api/dashboard/summary\n1767225600000\n8fK2...")>
```

已配对过的设备重新登录时，`pairing_code` 可传空串 —— 一次性码只在服务重启重新进入配对模式时
才会重新生成，已初始化设备凭**设备密码**登录即可（`loginByPassword` 分支）。

#### `GET /pairing/info`

**响应：**
```json
{
  "device_id": "abc123",
  "device_name": "ZTE MU300",
  "pairing_code": "123456",
  "storage_status": {
    "hasRoot": true,
    "isExternalStorageManager": true
  },
  "has_default_password": false,
  "expires_at": null
}
```

- `storage_status` 的两个键是 **camelCase**（`hasRoot` / `isExternalStorageManager`），
  不是 snake_case；
- `pairing_code` **只在 `has_default_password = true`（设备尚未初始化）时才有值**，
  否则是空串 `""` —— 设备密码已设置后走「密码登录」分支，继续对免鉴权请求回显配对码
  等于白送凭据；
- `expires_at` 恒为 `null`（配对码不过期，只在服务重启重新进入配对模式时更换）。

**错误：** `403 FORBIDDEN`（非本地子网）、`429 TOO_MANY_REQUESTS`。

#### `POST /pairing/challenge`

无请求体（发 `{}` 即可）。返回一次性挑战，**有效期 2 分钟、只能消费一次**；
未消费的挑战最多缓存 512 条，超限整体清空。

**响应：** `{ "challenge": "<32 字节随机数的 base64url>" }`

**错误：** `403 FORBIDDEN`、`429 TOO_MANY_REQUESTS`。

#### `POST /pairing/confirm`

**请求体：**
```json
{
  "pairing_code": "123456",
  "device_pubkey": "<X.509 SPKI DER 的 base64>",
  "challenge": "<上一步拿到的挑战原文>",
  "signature": "<用设备私钥对 challenge 原文的 ECDSA-SHA256 签名>",
  "password": "device-password",
  "device_name": "My Phone",
  "device_hwid": "<硬件派生稳定标识，可选>",
  "goform_ip": "192.168.0.1",
  "goform_port": 80,
  "goform_password": "admin"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `pairing_code` | 视情况 | **未初始化设备（`has_default_password=true`）必填**且必须匹配；已初始化设备可传空串，凭密码登录 |
| `device_pubkey` | 是 | X.509 SPKI DER 的 base64（`exportKey('spki')` / `PublicKey.encoded`）。缺失回 400 |
| `challenge` | 是 | `/pairing/challenge` 下发的原文。缺失回 400 |
| `signature` | 是 | 对 **challenge 原文**签名（不做任何额外拼装），DER 或 raw `r\|\|s` 均可。缺失回 400 |
| `password` | 是 | 首次配对时**该值即被设为设备密码**（4-64 字符）；之后是校验用的设备密码。缺失回 400 `PASSWORD_REQUIRED` |
| `device_name` | 否 | 配对设备显示名（1-32 字符）；留空则用指纹当名字 |
| `device_hwid` | 否 | 仅用于合并「同一台设备换了密钥」的重复记录，**不参与任何安全判定**（明文可伪造），也不再旁路配额 |
| `goform_ip` / `goform_port` / `goform_password` | 否 | 初次配对可一并写入设备后台连接配置；格式非法回 400 `INVALID_GOFORM_CONFIG` |

**成功响应 (200)：**
```json
{
  "token": "<该设备独占 token，32 字节 base64url>",
  "fingerprint": "<服务端据 device_pubkey 计算的指纹>"
}
```

明文 token **只在这一次响应里出现**（服务端只存 SHA-256）。重新配对（含同指纹刷新）会轮换
该设备 token，旧 token 立即失效。**没有 `secret` 字段** —— HMAC 共享密钥已随非对称签名一并废除。

**校验顺序**（任何一步失败都不产生副作用）：公钥解析 → 消费挑战 → 验签 → 配对码 → 配额 →
Goform 配置格式 → 密码 → 落库。

**错误响应：**
- `400` — `{ "error": "pairing_code required", "code": "BAD_REQUEST" }`（未初始化设备缺配对码）
- `400` — `{ "error": "device_pubkey, challenge and signature required", "code": "BAD_REQUEST" }`
- `400` — `{ "error": "Device password required", "code": "PASSWORD_REQUIRED" }`
- `400` — `{ "error": "Goform 后台配置无效（IP/端口/密码格式错误）", "code": "INVALID_GOFORM_CONFIG" }`
- `401` — `{ "error": "device_pubkey 无效或挑战签名校验失败", "code": "INVALID_DEVICE_KEY" }`
- `401` — `{ "error": "挑战不存在或已过期，请重新获取", "code": "INVALID_CHALLENGE" }`（重新取挑战再试）
- `401` — `{ "error": "Invalid or expired pairing code", "code": "INVALID_CODE" }`
- `401` — `{ "error": "Invalid device password", "code": "INVALID_PASSWORD" }`（首次配对时密码长度不合法也回这个）
- `409` — `{ "error": "Device already paired", "code": "ALREADY_PAIRED" }`（**语义是配对数已达 `pairing_max_devices`**，不是"这台已配对"；已配对指纹允许刷新）
- `429` — `{ "error": "Too many failed password attempts, retry later", "code": "PASSWORD_LOCKED" }`

#### `POST /pairing/change-password`

设置或修改设备配对密码（免认证；**旧密码即管理权限门禁**）。

**请求体：**
```json
{
  "old_password": "old-pass",
  "new_password": "new-pass",
  "goform_ip": "192.168.0.1",
  "goform_port": 80,
  "goform_password": "admin"
}
```

- `old_password` — 首次设置时可为空；
- `new_password` — 必填，4-64 字符；
- `goform_*` — 可选，同时更新设备后台连接配置（密码会热更新到运行中的客户端，IP/端口需重启生效）。

**成功响应 (200)：** `{ "success": true, "has_default_password": false }`

**错误响应：**
- `400` — `{ "error": "new_password must be 4-64 characters", "code": "INVALID_NEW_PASSWORD" }`
- `400` — `{ "error": "Goform 后台配置无效（IP/端口/密码格式错误）", "code": "INVALID_GOFORM_CONFIG" }`
- `401` — `{ "error": "Wrong old password", "code": "WRONG_OLD_PASSWORD", "has_default_password": false }`
- `429` — `{ "error": "Too many failed password attempts, retry later", "code": "PASSWORD_LOCKED" }`

#### `POST /pairing/unpair`

解除**所有**设备的配对（免认证，仅限本地子网），清除配对状态并重新进入配对模式，
同时清掉本 IP 的速率限制记录以便立即重新配对。

**响应：**
```json
{
  "success": true,
  "message": "Device unpaired, re-entered pairing mode"
}
```

**错误：** `403 FORBIDDEN`（非本地子网）。本端点**不做**速率限制。

---

### Web 静态资源

Web 面板直接挂在**根路径**，没有 `/web` 前缀。

#### `GET /`

返回 SPA 的 `index.html`（`WebResourceManager`：override 目录优先，回退 APK assets），
带 `Cache-Control: no-store`。

#### `GET /{path...}`

返回静态资源，未匹配路径按 SPA fallback 回 `index.html`（所以 `/pair`、`/login` 这类前端路由
都由它兜住）。带 hash 的 `assets/*` 回 `Cache-Control: public, max-age=31536000, immutable`；
`*.html` 与 `version.json` 回 `no-store`。

---

## 配对管理 /api/pairing

> 需要认证。管理多设备配对状态。

#### `GET /api/pairing/status`

获取当前配对状态详情。

**响应：**
```json
{
  "paired": true,
  "device_id": "abc123",
  "device_name": "ZTE MU300",
  "has_default_password": false,
  "paired_fingerprints": ["fp1", "fp2"],
  "paired_count": 2,
  "paired_at": 1234567890,
  "pairing_code": "123456",
  "pairing_enabled": true,
  "pairing_max_devices": 5,
  "devices": [
    {
      "fingerprint": "fp1",
      "device_name": "My Phone",
      "last_seen": 1234567890,
      "created_at": 1234567890
    }
  ]
}
```

指纹数组的键名是 `paired_fingerprints`（**不是** `fingerprints`）。`devices` 与
`GET /api/pairing/devices` 返回的是同一份数据，只需要设备列表时用哪个都行。
注意这里的 `pairing_code` 是**原始值**（不像 `/pairing/info` 那样在初始化后置空）——
它是已鉴权端点，回显不构成泄漏。

#### `POST /api/pairing/unpair`

解除所有设备的配对，并清掉调用方 IP 的配对速率限制。

**响应：** `{ "success": true, "message": "All devices unpaired, re-entered pairing mode" }`

#### `POST /api/pairing/unpair/{fingerprint}`

解除指定指纹的设备配对（**不校验设备密码**；需要双因素的版本见
`DELETE /api/pairing/devices/{fingerprint}`）。

**路径参数：**
- `fingerprint` — 要解除配对的设备指纹

**响应：** `{ "success": true, "message": "Device unpaired" }`

#### `PUT /api/pairing/config`

更新配对配置。两个字段都是可选的，只有出现且能解析的才会写入。

**请求体：**
```json
{
  "pairing_enabled": true,
  "pairing_max_devices": 5
}
```

**响应：**
```json
{
  "success": true,
  "pairing_enabled": true,
  "pairing_max_devices": 5
}
```

响应回显的是写入后的实际值。非法值（如 `pairing_max_devices: "abc"`）被**静默忽略**，
不报错 —— 客户端应比对回显值确认是否生效。

---

## 配对设备管理 /api/pairing/devices

> 需要认证。管理已配对设备的详细信息。

#### `GET /api/pairing/devices`

获取所有已配对设备列表。

**响应：**
```json
{
  "devices": [
    {
      "fingerprint": "sha256-fingerprint-string",
      "device_name": "My Phone",
      "last_seen": 1234567890,
      "created_at": 1234567890
    }
  ]
}
```

> **没有 `count` 字段**，也没有分页；无配对设备时是 `{"devices": []}`。
> 记录里**不含 token**（只存哈希），所以这个列表拿不到任何凭据。

#### `PATCH /api/pairing/devices/{fingerprint}`

重命名已配对设备。

**路径参数：**
- `fingerprint` — 要重命名的设备指纹。**允许包含 `/`**：路由把多段路径重新用 `/` 拼回完整指纹，无需预先转义

**请求体：**
```json
{
  "device_name": "New Name"
}
```

- `device_name` — 1-32 字符。请求体不是合法 JSON、或缺该字段时按**空串**处理，直接落到 `INVALID_DEVICE_NAME`（不是 415/422）

**响应：**
```json
{
  "success": true,
  "device_name": "New Name"
}
```

**错误码（respondFail 信封，含 `success:false` / `ok:false` / `error` / `message` / `code`）：**
- `400 INVALID_DEVICE_NAME` — `device_name must be 1-32 characters`
- `404 DEVICE_NOT_FOUND` — `Device not found`

#### `DELETE /api/pairing/devices/{fingerprint}`

解除指定设备的配对（**双因素**：已鉴权 + 设备密码）。

**路径参数：**
- `fingerprint` — 要解除配对的设备指纹（同上，可含 `/`）

**请求体：**
```json
{
  "password": "device-password"
}
```

- `password` — 必填，设备配对密码。请求体解析失败等同未传 → `MISSING_PASSWORD`

**响应：**
```json
{
  "success": true
}
```

删除即吊销：每台设备持有独占 token（记录里只存哈希），删掉记录后该 token 立即失效，
其余设备不受影响 —— **不再有 `rotated` 字段**，也不存在「移除最后一台就轮换全局凭据」那种连坐设计。

**错误码：**
- `401 MISSING_PASSWORD` — `Password required`
- `401 INVALID_PASSWORD` — `Invalid device password`
- `404 DEVICE_NOT_FOUND` — `Device not found`
- `429 PASSWORD_LOCKED` — `Too many failed password attempts, retry later`

> 密码失败计数与免认证的 `/pairing/*` **共用同一个全局节流器**：在那边试错被锁，这里同样会先被 `delay` 拖住再返回 —— 换端点绕不开节流。所以本端点的响应时间可能被人为拉长，客户端超时别设得太短。
> 自己删自己也是允许的：删掉当前设备的记录后，本次请求用的 token 立即失效，后续请求会 401。
- `429` — `{ "error": "Too many failed password attempts, retry later", "code": "PASSWORD_LOCKED" }`

---

## 仪表盘 /api/dashboard

#### `GET /api/dashboard/summary`

聚合仪表盘数据，一次请求获取设备概况。自带 3 秒响应缓存。

**响应：**
```json
{
  "device_info": {
    "device": {
      "brand": "ZTE",
      "model": "MU300",
      "device": "mu300",
      "manufacturer": "ZTE",
      "android_version": "12",
      "sdk_version": "31",
      "build_id": "TP1A.220624.014"
    },
    "sim": { "sim_state": "Ready", "sim_operator": "China Mobile", "phone_type": "GSM" },
    "storage": { "total": 8000000000, "available": 3000000000, "used": 5000000000, "usage_percent": 62.5 },
    "uptime": { "uptime_seconds": 86400, "uptime_display": "1天0时0分" },
    "at_channel": { "connected": true },
    "kernel": "5.4.254",
    "network": {
      "operator": "China Mobile",
      "type": "5G",
      "connected": true
    },
    "identity": { "imei": "...", "imsi": "...", "iccid": "...", "msisdn": "..." }
  },
  "battery": {
    "level": 85,
    "scale": 100,
    "percent": 85,
    "temperature": 35.0,
    "voltage": 4.2,
    "is_charging": false,
    "plugged": "None"
  },
  "storage": { "total": 8000000000, "available": 3000000000, "used": 5000000000, "usage_percent": 62.5 },
  "uptime": { "uptime_seconds": 86400, "uptime_display": "1天0时0分" },
  "traffic_summary": {
    "total_rx_bytes": 1073741824,
    "total_tx_bytes": 536870912,
    "total_bytes": 1610612736,
    "total_rx_display": "1.00 GB",
    "total_tx_display": "512.0 MB",
    "record_count": 1000,
    "today_rx_bytes": 524288000,
    "today_tx_bytes": 209715200,
    "today_rx_display": "500.0 MB",
    "today_tx_display": "200.0 MB",
    "today_total_bytes": 734003200,
    "today_total_display": "700.0 MB",
    "month_rx_display": "1.00 GB",
    "month_tx_display": "512.0 MB"
  },
  "traffic_limit": {
    "enabled": true,
    "limit_value": "470",
    "limit_unit_display": "GB",
    "limit_bytes": 504719473049,
    "alert_percent": "80",
    "auto_clear": false,
    "clear_date": "1",
    "used_bytes": 1610612736,
    "monthly_rx_bytes": 1073741824,
    "monthly_tx_bytes": 536870912,
    "monthly_time": 86400
  },
  "network_status": {
    "network": {
      "is_connected": true,
      "has_internet": true,
      "has_cellular": true,
      "has_wifi": true
    },
    "mobile_data": true,
    "ppp_status": "connected",
    "operator": "China Mobile",
    "network_type": "5G"
  }
}
```

读这个端点必须注意的三件事：

- **`device_info.device` 与 `device_info.sim` 都是对象，不是字符串**。`device` 是
  `Build.*` 那一组（`brand`/`model`/`device`/`manufacturer`/`android_version`/`sdk_version`/`build_id`），
  `sim` 是 `{sim_state, sim_operator, phone_type}`（无 `READ_PHONE_STATE` 权限时
  `sim_operator` 整个键不出现）；
- **`uptime` 的键带前缀**：`uptime_seconds` / `uptime_display`，不是 `seconds` / `display`。
  `storage` 还有一个 `usage_percent`（浮点百分比）；
- **顶层字段会是 `null`**：`battery` / `device_info` / `traffic_summary` / `traffic_limit` /
  `network_status` 每段都包在 `runCatching + withTimeout`（device_info 8s，其余 5s）里，
  超时或抛异常那一段就是 `null` 而**整个请求仍回 200**。客户端必须对每段做空判断，
  不能假设字段一定存在。`device_info.identity` 同理（取不到时为 `null`）。

电池字段名是 `is_charging`（snake_case），**不是 `isCharging`**；`level` 是原始电量、
`percent` 才是换算后的百分比（`level*100/scale`，取不到时为 `-1`）。
  "traffic_limit": {
    "enabled": true,
    "limit_value": "470",
    "limit_unit_display": "GB",
    "limit_bytes": 504719473049,
    "alert_percent": "80",
    "auto_clear": false,
    "clear_date": "1",
    "used_bytes": 1610612736,
    "monthly_rx_bytes": 1073741824,
    "monthly_tx_bytes": 536870912,
    "monthly_time": 86400
  },
  "network_status": {
    "network": {
      "is_connected": true,
      "has_internet": true,
      "has_cellular": true,
      "has_wifi": true
    },
    "mobile_data": true,
    "ppp_status": "connected",
    "operator": "China Mobile",
    "network_type": "5G"
  }
}
```

---

## 设备信息与控制 /api/device

> 需要认证。设备信息查询和各种硬件控制。

### 信息查询

#### `GET /api/device/info`

完整设备信息聚合（设备型号、SIM、存储、运行时间、AT 通道、内核版本、网络类型、设备身份）。缓存 10 分钟。

**响应：**
```json
{
  "device": {
    "brand": "ZTE",
    "model": "MU300",
    "device": "mu300",
    "manufacturer": "ZTE",
    "android_version": "12",
    "sdk_version": "31",
    "build_id": "TP1A.220624.014"
  },
  "sim": { "sim_state": "Ready", "sim_operator": "China Mobile", "phone_type": "GSM" },
  "storage": { "total": 8000000000, "available": 3000000000, "used": 5000000000, "usage_percent": 62.5 },
  "uptime": { "uptime_seconds": 86400, "uptime_display": "1天0时0分" },
  "at_channel": { "connected": true, "platform": "..." },
  "kernel": "5.4.254",
  "network": { "operator": "China Mobile", "type": "5G", "connected": true },
  "identity": { "imei": "...", "imsi": "...", "iccid": "...", "msisdn": "..." }
}
```

结构与 `/api/dashboard/summary` 的 `device_info` **完全一致**（同一个 `buildDeviceInfo`）：
`device` / `sim` 是对象不是字符串，`uptime` 的键是 `uptime_seconds` / `uptime_display`，
`storage` 带 `usage_percent`。`identity` 取不到时为 `null`；`sim_operator` 在缺
`READ_PHONE_STATE` 权限时整个键不出现。

#### `GET /api/device/goform`

Goform 协议完整设备状态（75+ 字段，分 3 批查询）。缓存 5 分钟。**默认关闭，且响应经过脱敏。**

- **开关**：配置项 `goform_dump_enabled`（默认 `false`）。关闭时回 `403` `FORBIDDEN`，
  这不是鉴权失败，客户端不要据此跳登录页。
- **脱敏**：打开后手机号 / IMEI / IMSI / ICCID（含 `sim_msisdn`、`sim_imsi` 这类同义字段名）
  以及任何名字里带 `passphrase`/`password`/`passwd`/`pwd`/`secret`/`token` 的字段，值一律是 `***`。
  key 本身保留（这个端点的用途就是看设备到底有哪些字段）。要真值请走对应的业务端点。

**响应：** 包含大量设备状态字段的 JSON 对象（由 goform 协议返回的原始数据，敏感值已打码）。

#### `POST /api/device/goform/query`

裸 goform 查询通道：把任意 cmd 直接转发给设备，返回设备原样的 JSON。
用于读取 profile 白名单之外的字段，**不属于稳定契约**。

- **开关**：配置项 `goform_command_enabled`（默认 `false`）。关闭时回 `403` `FORBIDDEN`
  （不是鉴权失败，别跳登录页）。
- **鉴权**：路由在 `/api` 下，与其它 `/api` 端点一样要带 Bearer + `X-Timestamp`/`X-Nonce`/`X-Signature`
  （见[认证与授权](#认证与授权)）；缺任一项回 444。
- **不脱敏**：返回值是设备真值 —— 密码、IMEI、ICCID 都不打码。这是它必须默认关的原因。
  需要"安全的诊断视图"请用 `GET /api/device/goform`（脱敏版）。
- **限流**：占 `GoformQoS` 的查询许可，并享用 2s 传输层缓存（同一组 cmd 在 2s 内只打设备一次）。

**入参**（`cmd` 支持数组或逗号分隔字符串，二者等价）：
```json
{ "cmd": ["signalbar", "network_type"] }
```
```json
{ "cmd": "signalbar,network_type" }
```

**响应：** 设备原始 JSON，**没有 `success` 信封**：
```json
{ "signalbar": "4", "network_type": "LTE" }
```

**失败：**
- `cmd` 缺失或全为空 → `400` `BAD_REQUEST`
- 设备无响应 / 登录失败 → `503` `UNAVAILABLE`

#### `POST /api/device/goform/set`

裸 goform 写通道：把任意 `goformId` 加任意参数直接发给设备。**危险接口。**

- **开关 / 鉴权 / 脱敏**：同 `/goform/query`（同一个 `goform_command_enabled`）。
- **绕过校验**：**不过** profile 的 `WriteSpec.validate` 值域校验，**不过** `SettingKey` 白名单。
  正常写接口（如 `POST /api/network/band`）会先校验取值再下发，这个端点不会 ——
  参数正确性由调用方自负，写坏设备配置的后果也由调用方承担。
- **审计**：每次调用记 WARN 日志，只记 `goformId` 与参数**键名**（不记值，避免密码进日志）。
- **缓存**：写成功后**清空整个 `ResponseCache`**（裸命令可能改任何东西，无法精确失效）。

**入参：**
```json
{
  "goformId": "SET_WIFI_INFO",
  "params": { "SSID1": "MyWiFi", "AuthMode": "WPA2PSK" }
}
```
`params` 的值统一按字符串发出（goform 表单只接受字符串）。

**响应：**
```json
{
  "success": true,
  "goformId": "SET_WIFI_INFO",
  "response": { "result": "success" }
}
```
`response` 是设备的原样回包：能解析成 JSON 就给对象，否则是字符串。
**注意 `success: true` 只表示"命令送到了设备并拿到回包"**，设备是否真的接受了这条命令
要看 `response` 里的内容（多数机型是 `{"result":"success"}` 或 `{"result":"failure"}`）。

**失败：**
- `goformId` 缺失或为空串 → `400` `BAD_REQUEST`
- 设备无响应 / 登录失败 → `503` `UNAVAILABLE`（此时命令**未执行**）

#### `GET /api/device/identity`

设备身份信息。缓存 30 分钟（取不到时写一条短期负缓存，不会反复重打设备）。
**含 PII**（手机号、IMEI/IMSI/ICCID），日志里需脱敏。

**响应字段：** `msisdn`、`imei`、`imsi`、`iccid`、`cr_version`、`wa_inner_version`
（`Language` 只在 `/api/device/version` 出现）。缺失即省略该 key。

**响应：**
```json
{
  "msisdn": "",
  "imei": "860000000000000",
  "imsi": "460000000000000",
  "iccid": "89860000000000000000",
  "cr_version": "CR_001",
  "wa_inner_version": "V1.0.0"
}
```

> 旧文档里的 `versions` / `ips` 两个嵌套块已不存在：IP / MAC 归 LAN 分组，
> 读 `/api/device/lan-settings`；固件版本读 `/api/device/version`。

#### `GET /api/device/version`

固件版本信息。缓存 30 分钟。

**响应：**
```json
{
  "language": "zh-CN",
  "cr_version": "CR_001",
  "wa_inner_version": "V1.0.0"
}
```

#### `GET /api/device/model`

设备型号信息（无缓存）。全部取自 `android.os.Build`，**不发设备查询、不跑 shell**，是本组最便宜的端点。

**响应：**
```json
{
  "brand": "ZTE",
  "model": "MU300",
  "device": "mu300",
  "manufacturer": "ZTE",
  "android_version": "13",
  "sdk_version": "33",
  "build_id": "MU300V1.0.0B01"
}
```

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| brand / model / device / manufacturer | `Build.BRAND` / `MODEL` / `DEVICE` / `MANUFACTURER` | 取不到时是字符串 `"Unknown"` |
| android_version | `Build.VERSION.RELEASE` | 如 `"13"`；取不到为 `"Unknown"` |
| sdk_version | `Build.VERSION.SDK_INT` | **键名是 `sdk_version`，不是 `sdk`**；值是**字符串** |
| build_id | `Build.DISPLAY` | 完整构建号 |

> 全部 7 个字段都是 **string**，没有数字类型 —— `sdk_version` 要比较大小得先自己转 int。
> 这里报告的是**运行 core 的 Android 系统**信息，与 goform 侧的设备型号（`/api/device/info`）不是同一来源，两者可能不一致。

#### `GET /api/device/magisk`

特权 shell 状态（无缓存）。**端点名是历史遗留** —— 早期检测 Magisk，现在实现换成了
`SystemController.getPrivilegedShellStatus()`，报告的是 ADB shell 可用性与当前 uid，
**与 Magisk 无关**。

**响应：**
```json
{
  "adb_shell": true,
  "method": "adb_shell",
  "uid": "uid=0(root) gid=0(root)"
}
```

- `method` 只有两个值：`"adb_shell"`（可用）/ `"none"`；
- `uid` 是 `id` 命令的**原始 stdout**，执行失败时是字符串 `"unavailable"`；
- **没有 `hasRoot` / `magiskVersion` 字段**，别按它们判 root。要判有没有特权，
  看 `adb_shell` 或 `GET /api/diagnose` 的 `root` 字段。

#### `GET /api/device/thermal`

温度传感器区域数据（无缓存）。遍历 `/sys/class/thermal/thermal_zone*`，按编号升序。

**响应：**
```json
{
  "zones": [
    { "name": "cpu-0-0-0", "temperature": 45.0 },
    { "name": "battery", "temperature": 35.0 }
  ],
  "count": 2
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| name | string | 取自各 zone 的 `type` 文件；读不到时回落成目录名（如 `"thermal_zone3"`） |
| temperature | double | 摄氏度（内核给的是毫摄氏度，core 已 `/1000`）。**读不到或解析失败是 `0.0`，不是 null** —— 别把 0 当"0℃"展示 |
| count | int | `zones` 长度 |

> `/sys/class/thermal` 不存在时返回 `{"zones": [], "count": 0}`（不报错）。zone 的数量与命名完全由内核决定，同型号不同固件都可能变，客户端别按固定下标取值。

#### `GET /api/device/connections`

`/proc/net/*` 连接数统计（无缓存）。

**响应：**
```json
{
  "tcp": 42,
  "tcp6": 8,
  "udp": 15,
  "udp6": 3,
  "unix": 120
}
```

**五个键，IPv4 与 IPv6 是分开的**：`tcp` / `tcp6` / `udp` / `udp6` / `unix`。
想要 TCP 总数得自己 `tcp + tcp6`，core 不做合并。

> 每个值是对应 `/proc/net/` 文件的**行数减去表头**（下限 0），所以它统计的是 socket 条目数，包含 `TIME_WAIT` 等非活跃连接，不等于"活跃连接数"。
> 单个文件读不到时该项为 `0`；整体抛异常时返回**部分填充甚至空对象** `{}` —— 客户端取值要容忍键缺失。

#### `GET /api/device/data-usage`

网口累计收发字节（从 `/sys/class/net/*/statistics/` 读取，无缓存）。

**响应：**
```json
{
  "today": {
    "rx_bytes": 1073741824,
    "tx_bytes": 536870912,
    "total_bytes": 1610612736
  },
  "month": {
    "rx_bytes": 1073741824,
    "tx_bytes": 536870912,
    "total_bytes": 1610612736
  }
}
```

**别拿它当"今日/本月用量"用**，实现有两个硬限制：

- **不按时间切分**：`getCellularDataUsage(start, end)` 收了时间参数但**根本没用**，读的是
  sysfs 累计计数器（自上次网络栈重置以来）。所以 `today` 与 `month` 恒等，都是"至今累计"；
- **不只统计蜂窝**：遍历 `/sys/class/net` 下所有接口（只排除 `lo`）后求和，
  wlan/usb/bridge 的流量都算进去了。

三个键都是数字，**没有 `rx_display` / `tx_display` 这类预格式化字段**，单位换算在客户端做。
要真正的月度蜂窝用量请用 `GET /api/device/traffic-limit`（设备侧 `monthly_rx/tx_bytes`）
或 `/api/traffic` 系列端点。

失败形态分两种，**顶层永远有 `today` / `month` 两个键**：
- `/sys/class/net` 不存在或全部接口读不出数 → 三个键都在，值为 `0`；
- 遍历过程抛异常 → 对应的值退化成**空对象** `{}`（异常被吞掉，HTTP 仍 200），即 `{"today": {}, "month": {}}`。客户端取 `rx_bytes` 等键时要容忍缺失。

#### `GET /api/device/traffic-limit`

流量限额配置 + 本月用量。配置部分缓存 5 分钟，`monthly_*` / `used_bytes` 每次实时合并
（用量跟着缓存走会滞后一个 TTL，见 `TrafficLimitMapper.withFreshUsage`）。

**响应字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `enabled` | boolean | 限额开关 |
| `limit_value` | string | 限额数值，如 `"470"`。未设置时为 `""` |
| `limit_unit_display` | string | 单位显示名，`MB` / `GB` / `TB`。缺省 `GB` |
| `limit_bytes` | number | 限额换算成字节；**`0` = 未设置限额** |
| `alert_percent` | string | 告警百分比，缺省 `"80"` |
| `auto_clear` | boolean | 是否按月自动清零 |
| `clear_date` | string | 清零日，缺省 `"1"` |
| `used_bytes` | number | 本月已用 = `monthly_rx_bytes + monthly_tx_bytes`（core 算好，客户端别再加一遍） |
| `monthly_rx_bytes` | number | 本月下行（下载）。⚠️ ZTE 固件的同名字段其实是上行，方向已在设备适配层（`ZteGoformProfile`）掰正，客户端拿到的就是下载 |
| `monthly_tx_bytes` | number | 本月上行（上传）。同上，对应固件的 `monthly_rx_bytes` |
| `monthly_time` | number | 本月在线时长（秒） |
| `auto_off` | object | **core 自制**（非设备字段）：到达 `alert_percent` 后自动关闭移动数据。`enabled` / `restore_on_reset` 是用户开关，`triggered` 只读（本计费周期是否已触发）。写入走 `POST /api/device/data-limit` 的 `auto_off_enabled` / `auto_off_restore`。流程：达阈值 → 发邮件（场景 `traffic80`）→ **发信成功**才等 1 分钟 → 关闭移动数据；发信失败不关网 |

**响应：**
```json
{
  "enabled": true,
  "limit_value": "470",
  "limit_unit_display": "GB",
  "limit_bytes": 504719473049,
  "alert_percent": "80",
  "auto_clear": false,
  "clear_date": "1",
  "used_bytes": 1610612736,
  "monthly_rx_bytes": 1073741824,
  "monthly_tx_bytes": 536870912,
  "monthly_time": 86400
}
```

> **旧字段 `limit_size` / `limit_unit` 已不再输出。** 设备侧把限额存成 `"470_1024"` 复合串
> （乘数 `1=MB / 1024=GB / 1048576=TB`），而 `data_volume_limit_unit` 恒为 `"MB"` 不代表真实单位 ——
> 这两个坑现在完全被 core 吃掉，复合串只存在于 profile 的 `WriteSpec.encode`。
> 写入侧（`POST /api/device/data-limit`）收 `limit_value` + `limit_unit`。

#### `GET /api/device/settings`

设备开关类设置。缓存 5 分钟。**值域全是字符串**（goform 时代遗留），布尔用 `"1"`/`"0"`，
个别字段额外接受 `"on"`/`"off"`。字段缺失即省略该 key。

**响应字段：**

| 字段 | 说明 |
|------|------|
| `indicator_light_switch` | LED 指示灯，`"1"` = 开 |
| `performance_mode` | 性能模式，`"1"` = 性能，`"0"` = 均衡 |
| `samba_switch` | Samba 共享，`"1"` = 开 |
| `usb_port_switch` | USB 端口，`"1"` = 开 |
| `restart_schedule_switch` | 定时重启开关，`"1"` = 开 |
| `restart_time` | 定时重启时间，`"HH:mm"` |
| `sleep_sysIdleTimeToSleep` | WiFi 休眠空闲分钟数，`"0"` = 不休眠 |
| `BearerPreference` | 网络模式（承载偏好），设备侧枚举串 |
| `net_select` | 老固件的网络模式字段，`BearerPreference` 缺失时的回退 |
| `connection_mode` | 连接模式，`"auto"` / `"manual"`；部分固件填 `"1"` / `"hand"` 表示手动 |
| `roam_setting_option` | 数据漫游，`"1"` 或 `"on"` = 开 |
| `dial_roam_setting_option` | 拨号漫游，`roam_setting_option` 的同义字段（部分固件只填这个） |
| `UpgMode` | FOTA 自动检查更新，`"1"` = 开（与写侧 `auto_update` 同向） |

> `BearerPreference`/`net_select` 与 `roam_setting_option`/`dial_roam_setting_option` 是
> **两组各两个不同的 canonical 字段**（不是别名探测）：前者取到就用，取不到读后者。
> 频段锁定不在这里，读 `/api/network/band-status`。


#### `GET /api/device/selinux`

SELinux 状态（无缓存，通过 `getenforce` 命令）。

**响应：**
```json
{
  "selinux": "Enforcing"
}
```

#### `GET /api/device/lan-settings`

LAN / DHCP 设置。缓存 10 分钟。值域全是字符串，缺失即省略该 key。

**响应字段：**

| 字段 | 说明 |
|------|------|
| `lan_ipaddr` | LAN IP |
| `lan_netmask` | 子网掩码 |
| `mac_address` | LAN MAC |
| `dhcpEnabled` | DHCP 服务开关。`"1"` / `"true"` / `"SERVER"` 均视为开 |
| `dhcpStart` | 地址池起始 |
| `dhcpEnd` | 地址池结束 |
| `dhcpLease` | 租约时长，**秒** |
| `dhcpLease_hour` | 租约时长的**小时**版本。部分固件只填这个，读到后需 `× 3600` |
| `mtu` | MTU |
| `tcp_mss` | TCP MSS（设备会返回，当前无客户端消费） |

**响应：**
```json
{
  "lan_ipaddr": "192.168.0.1",
  "lan_netmask": "255.255.255.0",
  "mac_address": "AA:BB:CC:DD:EE:FF",
  "dhcpEnabled": "1",
  "dhcpStart": "192.168.0.100",
  "dhcpEnd": "192.168.0.200",
  "dhcpLease": "43200",
  "mtu": "1500"
}
```

> 旧字段 `lan_ip` / `dhcp_type` 已不存在。`dhcpLease` 与 `dhcpLease_hour` 是**两个不同字段**
> （秒 / 小时）而不是别名，取到前者用前者，否则后者 ×3600。命名不规范但已被两端固化，不改。
> 写入侧是 `POST /api/device/dhcp`。

---

### 设备控制

> **本节有三种失败形态，客户端都要认：**
> 1. **HTTP 500 + `{success:false}`**（没有 `error`/`code`/`message`）—— `reboot`、`factory-reset`、`shutdown`、`debug`、`password` 这 5 个：失败时状态码变 500，但 body 只有一个 `success` 字段，拿不到原因。
> 2. **HTTP 200 + `success:false`** —— `performance`、`led`、`roaming`、`samba`、`fota`、`cell-lock`、`cell-unlock`、`restart-schedule`、`dhcp`、`data-limit`、`flow-calibration`：只看状态码会把失败当成功。
> 3. **respondFail 信封**（真实状态码 + `{success:false, ok:false, error, message, code}`）—— 仅用于**参数校验**（`password` 缺字段、`cell-lock` 缺字段）与「设备侧明确拒绝」（`respondRejected`，见下）。
>
> 带 `respondRejected` 的端点（`cell-lock`、`restart-schedule`、`dhcp`、`data-limit`、`flow-calibration`）在设备明确拒绝时会直接返回 respondFail 信封并**不再**走 `success` 分支 —— 也就是同一个端点可能给你三种 body 之一。
>
> 另外这些写操作**只在成功时**失效相关缓存（`device:settings` / `device:lan` / `device:traffic-limit` / `network:cell-info`）。失败时缓存保留旧值，所以「改失败了但读接口还显示新值」不会发生，反之「改成功但读到旧值」也不会 —— 除非你打的是 `success:false` 那条路。

#### `POST /api/device/reboot`

重启设备。成功后失效 `device:*` 缓存（注意：**先失效缓存再执行**，所以即使重启失败缓存也已被清）。

**请求体：** 无

**响应：** `{ "success": true }`；失败 `500 { "success": false }`

#### `POST /api/device/factory-reset`

恢复出厂设置。**先失效全部缓存**（`invalidate("*")`，含非 device 前缀），再下发命令。

**请求体：** 无

**响应：** `{ "success": true }`；失败 `500 { "success": false }`

#### `POST /api/device/shutdown`

关机。同样是**先**失效全部缓存再下发。

**请求体：** 无

**响应：** `{ "success": true }`；失败 `500 { "success": false }`

#### `POST /api/device/debug`

切换 ADB 调试模式。

**请求体：**
```json
{ "enabled": true }
```

`enabled` 缺省或非布尔时按 **`false`** 处理（即默认关闭调试），不会报错。

**响应：** `{ "success": true, "enabled": true }`（`enabled` 回显请求值）；失败 `500 { "success": false, "enabled": ... }`

#### `POST /api/device/password`

修改管理员密码。

**请求体：**
```json
{
  "old_password": "old123",
  "new_password": "new456"
}
```

**校验：** `old_password` 和 `new_password` 均不能为空，缺任一 → `400 BAD_REQUEST`（`old_password and new_password are required`）。core **不校验新密码强度**，也不校验两者是否相同。

**响应：** `{ "success": true }`；失败 `500 { "success": false }`

> 成功后 core 会把新密码**同步写入自己的 goform 凭据**并热更新 HTTP 客户端，因此后续请求无需重启服务即可继续工作。反过来说：如果设备侧改成功但 core 这一步出问题，后续所有 goform 查询都会鉴权失败 —— 表现为大量端点同时返回空数据。

#### `POST /api/device/performance`

性能模式切换。失效 `device:settings` 缓存。

**请求体（二选一）：**
```json
{ "mode": "performance" }
```
或
```json
{ "enabled": true }
```

`mode` 可选值：`"performance"` / `"balanced"`。`enabled: true` 等价于 `mode: "performance"`。

**响应：**
```json
{
  "success": true,
  "performance_mode": 1
}
```

`performance_mode` 为 `1` 表示性能模式，`0` 表示均衡模式。

#### `POST /api/device/led`

LED 指示灯开关。失效 `device:settings` 缓存。

**请求体：**
```json
{ "enabled": true }
```

**响应：** `{ "success": true, "enabled": true }`

#### `POST /api/device/roaming`

网络漫游开关。失效 `device:settings` 缓存。

**请求体：**
```json
{ "enabled": false }
```

**响应：** `{ "success": true, "enabled": false }`

#### `POST /api/device/fota`

FOTA 自动检查更新开关。失效 `device:settings` 缓存。

设备侧命令是 `goformId=SetUpgAutoSetting&UpgMode=0|1`（`0` = 关闭自动检查更新，`1` = 开启），
`UpgIntervalDay=1` / `UpgRoamPermission=0` 由 core 补齐。
当前状态从 `GET /api/device/settings` 的 `UpgMode` 读取。

**请求体：**
```json
{ "auto_update": true }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `auto_update` | Boolean | **正向**语义：`true` = 允许自动升级（UpgMode=1）。缺省为 `true` |

> 旧入参 `enabled` 是**反向**的（`true` = 禁用），仍兼容一版但会打 WARN，请改用 `auto_update`。

**响应：**
```json
{
  "success": true,
  "auto_update": true,
  "fota_disabled": false
}
```

> `fota_disabled` 是给旧客户端的兼容字段（等于 `!auto_update`），保留一版。

#### `POST /api/device/samba`

Samba 文件共享开关。失效 `device:settings` 缓存。

**请求体：**
```json
{ "enabled": true }
```

**响应：** `{ "success": true, "enabled": true }`

#### `POST /api/device/cell-lock`

锁定到指定基站。失效 `network:cell-info` 和 `device:settings` 缓存。

**请求体：**
```json
{
  "pci": "411",
  "earfcn": "1650",
  "network_type": "LTE"
}
```

**校验：** 三个字段必填；`pci` / `earfcn` 只能是数字；`network_type` 取 `LTE` / `NR`
（也收设备侧数字码 `12` / `16`）。不合法回 400 `OUT_OF_RANGE`，**不向设备发请求**。

> 兼容一版：旧入参用设备风格的 `rat`（如 `"lte"`），收到会当 `network_type` 用并打 warn。新代码别用。


**响应：** `{ "success": true }`

#### `POST /api/device/cell-unlock`

解锁所有基站。失效 `network:cell-info` 和 `device:settings` 缓存。

**请求体：** 无

**响应：** `{ "success": true }`

#### `POST /api/device/restart-schedule`

定时重启设置。失效 `device:settings` 缓存。

**请求体：**
```json
{
  "enabled": true,
  "time": "03:00"
}
```

`time` 默认 `"00:00"`，`enabled` 缺省按 `false`。core **不校验 `time` 格式**，写坏了要靠设备侧拒绝（那时才走 respondFail 信封）。

**响应：**
```json
{
  "success": true,
  "enabled": true,
  "time": "03:00"
}
```

`enabled` / `time` 都是**请求值回显**，不代表设备侧最终存下的值。

#### `POST /api/device/dhcp`

DHCP 设置。成功后失效 `device:lan` 缓存。

**请求体：**
```json
{
  "lan_ip": "192.168.0.1",
  "lan_netmask": "255.255.255.0",
  "dhcp_type": "SERVER",
  "dhcp_start": "192.168.0.100",
  "dhcp_end": "192.168.0.200",
  "dhcp_lease": "86400"
}
```

> **这是全量覆盖，不是字段级合并。** 每个缺失字段都会被硬编码默认值顶上：
> `lan_ip` → `"192.168.0.1"`、`lan_netmask` → `"255.255.255.0"`、`dhcp_type` → `"SERVER"`、
> `dhcp_start`/`dhcp_end` → **空串**、`dhcp_lease` → `"86400"`。
> 所以「只想改租期」而只发 `{"dhcp_lease":"7200"}` 会把 LAN IP 改回 192.168.0.1 并清空地址池 —— **每次都必须发全 6 个字段**（先 `GET /api/device/lan-settings` 读回当前值，按下面的键名映射改写）。

> **请求参数名与读接口的响应字段名不同**：这里是 `lan_ip` / `dhcp_type` / `dhcp_start` /
> `dhcp_end` / `dhcp_lease`，而 `GET /api/device/lan-settings` 回的是 `lan_ipaddr` /
> `dhcpEnabled` / `dhcpStart` / `dhcpEnd` / `dhcpLease`。读回来的值不能原样回填成请求体。

**响应：** `{ "success": true }`

#### `POST /api/device/data-limit`

流量限额配置。成功后失效 `device:traffic-limit` 缓存。

**请求体：**
```json
{
  "enabled": true,
  "limit_value": "470",
  "limit_unit": "GB",
  "alert_percent": "80",
  "auto_clear": false,
  "clear_date": "1"
}
```

| 字段 | 类型 | 缺省行为 |
| --- | --- | --- |
| enabled | boolean | 缺省按 **`false`**（即关闭限额）—— 想只改数值也必须显式带上 `true` |
| limit_value | string | 按整数解析，**解析不出（含小数、带单位）就当未提供**，不会报错 |
| limit_unit | string | `MB` / `GB` / `TB`，大小写不敏感；其他值按未提供处理 |
| alert_percent / clear_date | string | 未提供则保持设备侧现值 |
| auto_clear | boolean | 未提供则保持设备侧现值 |
| auto_off_enabled | boolean | **core 自制开关**（不下发设备）：到达 `alert_percent` 后自动关闭移动数据。未提供则保持现值 |
| auto_off_restore | boolean | 同上：流量清零后自动重新打开；`false` = 只关一次。未提供则保持现值 |

这两个 `auto_off_*` 先落盘再下发设备，因此设备侧限额写失败也不会丢掉用户刚改的开关。

设备侧的 `"470_1024"` 复合串由 profile 的 `WriteSpec` 拼，**客户端不该看见它**。

> 兼容一版：仍收旧的复合串 `limit_size: "470_1024"`，收到会**覆盖**同请求里的 `limit_value`/`limit_unit` 并打 warn 日志。新代码别用；也别两种一起发。

**响应：** `{ "success": true }`

#### `POST /api/device/flow-calibration`

流量校准。成功后失效 `device:traffic-limit` 缓存。

**请求体：**
```json
{
  "target": "data",
  "value": "1024"
}
```

`target` 取 `"data"`（默认，其他任意值也按 data 走）或 `"time"`；`value` 是要校准成的值，**缺省是 `"0"`** —— 忘传就等于把计数器清零，别靠默认值。

> 兼容一版：旧入参是设备侧三件套 `way` / `data` / `time`（未校准的那个要自己填 `"0"`），
> 收到 `way` 会打 warn。补零规则现在在 profile 的 `WriteSpec` 里。

**响应：** `{ "success": true }`

---

## 网络控制 /api/network

> 需要认证。蜂窝网络状态查询和控制。

### 状态查询

#### `GET /api/network/signal`

当前信号信息（优先从 DataScheduler 缓存获取，回退到 TelephonyCollector）。

**响应：**
```json
{
  "rsrp": -85,
  "sinr": 150,
  "rsrq": -100,
  "rssi": -70,
  "rat": "5G",
  "cell_id": "35234033920",
  "operator": "中国联通",
  "network_registered": true,
  "band": "78",
  "band_label": "n78",
  "arfcn": 633984,
  "band_width": 0,
  "signal_strength": -114,
  "pci": 347,
  "nr_arfcn": 633984,
  "nr_band": "78",
  "nr_band_width": 0,
  "nr_signal_strength": -114,
  "nr_snr": 7,
  "nr_pci": 347,
  "nr_cell_id": "35234033920",
  "lte_arfcn": 1650,
  "lte_band": "3",
  "lte_band_width": 20000,
  "lte_signal_strength": -99,
  "lte_snr": 8,
  "lte_pci": 430,
  "lte_cell_id": "107648257",
  "lte_ca_status": "off"
}
```

**服务小区统一字段**（`band` / `band_label` / `arfcn` / `band_width` / `signal_strength` / `pci`）：

| 字段 | 说明 |
|------|------|
| `band` | 频段号，纯数字（`"78"` / `"3"`） |
| `band_label` | 频段显示名，core 拼好（`"n78"` / `"B3"`）。**前端直接显示，不要自己拼前缀** |
| `arfcn` | 服务小区频点（NR-ARFCN 或 EARFCN） |
| `band_width` | 带宽 kHz。设备经常不填，缺失即省略该 key |
| `signal_strength` | 服务小区信号强度 dBm |
| `pci` | 服务小区物理小区标识 |

这几个是 core **派生**的字段，选择规则是「**按字段是否存在判定，NR 优先、LTE 兜底**」。
NSA 双连接下两侧都有值时取 NR。判 5G 用 `band_label` 是否以 `n` 开头，**不要解析 `rat` 文案**
（它是 44 项映射表的输出，含 `"NSA"` / `"未知(xx)"`，固件加新值时按它分支会静默走错）。
`nr_*` / `lte_*` 原字段全部保留，需要看双连接的场景仍读那两组。

> sinr 和 rsrq 值为原始值（部分固件需除以 10 得到 dB；本机固件的 `Lte_snr` / `Nr_snr`
> 直接就是 dB，客户端不要无条件除 10）。
>
> `rsrp` / `sinr` / `rsrq` / `rssi` / `cell_id` 是**当前服务小区的合并值**，4G 驻网时装的是
> `lte_*` 的数据。`nr_*` 与 `lte_*` 两组是制式专属字段（源自 goform `network_information`），
> **只在对应制式有值时才出现**，字段缺失即表示当前不在该制式，客户端按"缺失 → 显示 —"处理即可。
> 频段为编号（`nr_band: "78"` = n78，`lte_band: "3"` = B3）；带宽单位是 kHz（`20000` = 20MHz，
> 部分固件不上报则字段缺失）；小区 ID 值域可能超出 Int，按字符串返回；
> `lte_ca_status` 是固件原始枚举（`"off"` / `"on"`），文案由展示层翻译。
>
> **键集合与缓存新鲜度无关**：命中 10 秒缓存时返回的是采集器的完整输出（只实时补
> `network_registered`），不是从 6 列信号记录重建的窄版本。


#### `GET /api/network/signal/history`

信号历史记录。

**查询参数：**
- `hours` — 查询时长，默认 24（非数字静默回退 24）

**响应：**
```json
{
  "records": [
    { "timestamp": 1234567890, "rsrp": -85, "sinr": 15 }
  ],
  "count": 100,
  "period_hours": 24
}
```

> `records` 元素只有 `timestamp` / `rsrp` / `sinr` **三个字段**（DAO 的投影查询，不是完整历史行）。
> `rsrp = 0` 是「无信号」哨兵值，画曲线时要剔除（`/api/monitor/history` 的 signal 系列已代为过滤，这里**没有**）。
> `count` 是本次返回条数；无上限，`hours` 开大会一次拉全量。

#### `GET /api/network/status`

网络状态（Android 连通性 + 设备侧 PPP / 运营商 / 网络制式）。goform 部分由 DataHub 缓存 30 秒（单次查询合并 `network_type` + `provider` + `ppp_status`，5s 超时）。

**响应：**
```json
{
  "network": {
    "is_connected": true,
    "has_internet": true,
    "has_cellular": true,
    "has_wifi": false
  },
  "mobile_data": true,
  "ppp_status": "ppp_connected",
  "operator": "China Mobile",
  "network_type": "5G"
}
```

| 字段 | 说明 |
|------|------|
| `network` | **嵌套对象**（不是字符串），来自 Android `ConnectivityManager`：4 个布尔键 `is_connected` / `has_internet` / `has_cellular` / `has_wifi` |
| `mobile_data` | 优先取设备侧 PPP 连通状态；DataHub 不可用时退回 Android `isMobileDataEnabled()` |
| `ppp_status` | 设备原值，取不到时是**空串**（不是 null） |
| `operator` | 设备侧 provider，为空时退回 Android `getOperatorName()` |
| `network_type` | **已经是可读文案**（`"5G"` / `"4G"`…），设备数字码在 core 的 profile 解码器里翻译完了 |

> 客户端**不要再做一次数字码→文案的映射**：core 侧 `NETWORK_TYPE_DECODER` 已经翻译过，
> 原来两端各有一份 `mapNetworkType()` 已删。参考对应关系（仅供排查设备原始响应用）：
> `20`→5G、`19`→5G NSA、`13`→4G、`9`→3G、`4`→2G。

#### `GET /api/network/band-status`

频段锁定状态。缓存 5 分钟。

**响应字段：** `lte_band_lock`、`nr_band_lock`（两者恒存在，取不到时为 `""`）。

**值格式也是契约：** 纯数字逗号串（`"1,3,41"`），**不带 `B` / `n` 前缀**。
`"0"` 或 `"all"` 表示未锁定。

**响应：**
```json
{
  "lte_band_lock": "1,3,41",
  "nr_band_lock": "41,78"
}
```

> 写入是 `/api/network/band`（POST），规范入参 `{ "lte_bands": "1,3", "nr_bands": "41,78" }`
> 或 `{ "action": "unlock" }`；旧格式 `{ "rat": "lte", "bands": "1,3" }` 仍兼容一版。
> 不存在 `/api/network/band-lock` 这个端点。

#### `GET /api/network/cell-info`

基站信息。缓存 20 秒。响应是**平铺的** canonical 字段，不是嵌套结构。

**响应字段：**

| 字段 | 说明 |
|------|------|
| `neighbor_cell_info` | 邻区数组 |
| `locked_cell_info` | 已锁定小区数组 |
| `Lte_pci` | LTE 服务小区 PCI |
| `Lte_fcn` | LTE EARFCN |
| `Lte_bands` | LTE 频段号（纯数字，`"3"` 即 B3） |
| `lte_rsrp` / `lte_rsrq` / `lte_snr` | LTE 侧信号值 |

数组元素的字段：`pci`、`earfcn`、`rsrp`、`rsrq`、`sinr`、`rat`（元素键集合开放）。
与 `station_list` 同理，数组值可能是数组也可能是数组的 JSON 字符串，两种都要能解。

> **本端点只带 LTE 侧字段。** `network_type` 与 `Nr_*` 归 SIGNAL 分组，
> 从 2026-08-29 的分组白名单起**不再出现在这里** —— 当前服务小区（NR 优先）读
> `/api/network/signal` 的 `band` / `band_label` / `arfcn` / `pci` / `signal_strength`。
> 少拉一个接口的后果是：5G 驻网时基站页会静默显示 4G 的 PCI/频点。
> 旧文档写的 `serving_cell` / `neighbor_cells` / `locked_cell` 三段嵌套结构从来不是实际形状。


#### `GET /api/network/neighbor-cells`

邻区基站列表（无缓存，实时查询）。

**响应：**
```json
{
  "neighbor_cell_info": [
    { "pci": 411, "earfcn": 1650, "rsrp": -95, ... }
  ]
}
```

---

### 网络控制

#### `POST /api/network/data`

移动数据开关。成功后失效 DataHub 网络缓存。

**请求体：**
```json
{ "enabled": true }
```

**响应：** `{ "success": true, "enabled": true }`

#### `POST /api/network/airplane`

飞行模式开关。

**请求体：**
```json
{ "enabled": false }
```

**响应：** `{ "success": true, "airplane_mode": false }`

#### `POST /api/network/band`

频段锁定/解锁。成功后失效 `network:band-status` 缓存。通过 goform 写入 + AT+SFUN 网络栈重启（不需要设备重启）。

**请求体（新格式）：**
```json
{
  "action": "lock",
  "lte_bands": "1,3",
  "nr_bands": "41,78"
}
```

**请求体（兼容旧格式）：**
```json
{
  "rat": "lte",
  "bands": "1,3"
}
```

`action` 可选值：`"lock"` / `"unlock"`。频段号必须是 `1..255` 的纯数字列表，否则回 400
`OUT_OF_RANGE` 且不下发。**注意本端点设备写失败时也回 400**（体里 `success:false` 但没有 `code`），
按状态码区分不出"参数错"与"设备没写成"，判定口径见「全局说明 › 写操作的值域校验」。

**LTE 全频段：** `1,3,5,8,34,38,39,40,41`
**NR 全频段：** `1,5,8,28,41,78`

**响应：**
```json
{
  "success": true,
  "action": "lock",
  "mode": "lte",
  "needs_reboot": false,
  "network_restarted": true
}
```

#### `POST /api/network/mode`

设置网络模式。失效 `network:band-status` 缓存。

**请求体：**
```json
{ "mode": "AUTO" }
```

**可选值：** 入参是**别名**，core 用 `NetworkMode.toBearer()`（`core:contract`）映射成设备实际取值 BearerPreference（**大小写敏感**）。

| 别名（客户端提交，大小写不敏感） | BearerPreference（实际下发） |
|--------|----------|
| AUTO、WL_AND_5G | WL_AND_5G |
| 5G_ONLY、ONLY_5G、5G_SA | Only_5G |
| LTE_AND_5G、5G_NSA | LTE_AND_5G |
| ONLY_LTE、LTE_ONLY、4G_ONLY | Only_LTE |
| WCDMA_ONLY、ONLY_WCDMA | Only_WCDMA |
| WCDMA_AND_LTE、LTE_WCDMA | WCDMA_AND_LTE |

未识别的取值**会被拒绝**：profile 的 `WriteSpec.validate` 先算 `toBearer()`，映射不出设备支持的
`BearerPreference` 就回 400 `OUT_OF_RANGE`（`网络模式 xxx 无法映射到设备支持的 BearerPreference`），
**不向设备发请求**。旧行为「原样透传给设备 → 表现为保存成功但档位没变」已经取消。
客户端只提交上表左列的值；UI 档位清单见 `NetworkMode.UI_OPTIONS`（web 侧镜像 `NetworkModeOptions`）。


**响应：** `{ "success": true, "mode": "5G_ONLY", "bearer": "Only_5G" }`

- `mode` 回显入参；`bearer` 是实际下发给设备的值（T15 新增），客户端可据此确认映射结果。
- 定时任务 / 自动化规则的 `network_mode` 动作走同一套映射（`ActionExecutorImpl`），参数同样填**别名**。

#### `POST /api/network/bearer`

设置 Bearer Preference。与 `/api/network/mode` **走的是同一条写通道和同一套别名映射**，
区别只有回显字段名 —— `preference` 也要填上表左列的别名，不是设备侧的 `BearerPreference` 原值，
映射不出来同样回 400 `OUT_OF_RANGE`（早期版本这里是直通设备的，已收掉）。

**请求体：**
```json
{ "preference": "WL_AND_5G" }
```

**响应：** `{ "success": true, "preference": "WL_AND_5G", "bearer": "WL_AND_5G" }`

`preference` 回显入参，`bearer` 是映射后实际下发给设备的值（与 `/api/network/mode` 的 `bearer` 同义）。
goform 客户端不可用 → `503 UNAVAILABLE`；设备写失败 → `500` + `success:false`。

#### `POST /api/network/connect`

拨号连接网络。成功后失效 DataHub 网络缓存。

**请求体：** 无

**响应：** `{ "success": true }`

#### `POST /api/network/disconnect`

断开网络连接。成功后失效 DataHub 网络缓存。

**请求体：** 无

**响应：** `{ "success": true }`

#### `POST /api/network/connection-mode`

设置连接模式。

**请求体：**
```json
{ "mode": "AUTO" }
```

**响应：** `{ "success": true, "mode": "AUTO" }`

---

## 系统资源 /api/system

> 需要认证。系统硬件资源监控。

#### `GET /api/system/cpu`

CPU 使用率、各核心频率和温度。

**响应：**
```json
{
  "usage_percent": 25.5,
  "core_count": 4,
  "cores": [
    { "core": 0, "freq_mhz": 1800.0, "freq_display": "1.8 GHz" },
    { "core": 1, "freq_mhz": 1800.0, "freq_display": "1.8 GHz" },
    { "core": 2, "freq_mhz": 1200.0, "freq_display": "1.2 GHz" },
    { "core": 3, "freq_mhz": 1200.0, "freq_display": "1.2 GHz" }
  ],
  "temperature": 45.0
}
```

#### `GET /api/system/cpu/history`

CPU 历史记录。

**查询参数：**
- `hours` — 查询时长，默认 24

**响应：**
```json
{
  "records": [
    { "timestamp": 1234567890, "usage_percent": 25.5, "temperature": 45.0 }
  ],
  "count": 100,
  "period_hours": 24
}
```

#### `GET /api/system/memory`

内存信息。

**响应：**
```json
{
  "total": 1536000000,
  "used": 1024000000,
  "available": 512000000,
  "free": 256000000,
  "buffers": 64000000,
  "cached": 192000000,
  "usage_percent": 66.7
}
```

#### `GET /api/system/battery`

电池信息（来自 DataScheduler 30s 缓存，缓存为空时现采）。

**响应：**
```json
{
  "level": 85,
  "scale": 100,
  "percent": 85,
  "temperature": 35.0,
  "voltage": 4.2,
  "is_charging": false,
  "plugged": "None"
}
```

- 字段名是 `is_charging`（snake_case），**不是 `isCharging`**；
- `level` 是原始电量、`scale` 是量程，**要显示百分比用 `percent`**（`level*100/scale`；
  取不到时 `level`/`scale` 为 `-1`、`percent` 也为 `-1`）；
- `temperature` 已从 0.1℃ 换算成 ℃、`voltage` 已从 mV 换算成 V；取不到时是 `-0.1` / `-0.001`；
- `plugged` 四值：`AC` / `USB` / `Wireless` / `None`；
- 读取抛异常时返回**空对象** `{}`。

#### `GET /api/system/storage`

存储信息（`StatFs("/data")`）。

**响应：**
```json
{
  "total": 8000000000,
  "available": 3000000000,
  "used": 5000000000,
  "usage_percent": 62.5
}
```

`usage_percent` 是 0-100 的浮点数（未四舍五入）。读取抛异常时返回空对象 `{}`。

#### `GET /api/system/uptime`

系统运行时间（`SystemClock.elapsedRealtime()`，即**开机至今**，不是 core 进程运行时长）。

**响应：**
```json
{
  "uptime_seconds": 86400,
  "uptime_display": "1天0时0分"
}
```

键名带 `uptime_` 前缀，**不是 `seconds` / `display`**。`/api/device/info` 与
`/api/dashboard/summary` 里嵌套的 `uptime` 对象是同一份数据、同一套键名。
core 进程自身的启动时刻走 `GET /api/system/startup-time`。

#### `GET /api/system/startup-time`

服务启动时间（Unix 时间戳，毫秒）。

**响应：**
```json
{
  "startupTimeMs": 1234567890000
}
```

#### `GET /api/system/root-check`

Root 权限检查。

**响应：**
```json
{ "hasRoot": true }
```

---

## 流量统计 /api/traffic

> 需要认证。实时和历史流量数据。

#### `GET /api/traffic/realtime`

实时网速。

**响应：**
```json
{
  "rx_speed": 1048576,
  "tx_speed": 524288,
  "rx_bytes": 1073741824,
  "tx_bytes": 536870912,
  "rx_speed_display": "1.0 MB/s",
  "tx_speed_display": "512.0 KB/s",
  "timestamp": 1234567890
}
```

无数据时返回：`{ "error": "No data yet" }`

#### `GET /api/traffic/history`

流量历史记录。

**查询参数：**
- `hours` — 查询时长，默认 24

**响应：**
```json
{
  "records": [
    { "timestamp": 1234567890, "rx_speed": 1048576, "tx_speed": 524288 }
  ],
  "count": 100,
  "period_hours": 24
}
```

#### `GET /api/traffic/summary`

流量汇总统计。与 `/api/dashboard/summary` 的 `traffic_summary` **由同一个
`TrafficSummaryMapper` 产出，字段完全一致**（历史上两边各写一份，仪表盘那份漏了
`today_*`，导致「今日已用」永远是 `--`）。

**响应：**
```json
{
  "total_rx_bytes": 10737418240,
  "total_tx_bytes": 5368709120,
  "total_bytes": 16106127360,
  "total_rx_display": "10.00 GB",
  "total_tx_display": "5.00 GB",
  "record_count": 5000,
  "today_rx_bytes": 524288000,
  "today_tx_bytes": 209715200,
  "today_rx_display": "500.0 MB",
  "today_tx_display": "200.0 MB",
  "today_total_bytes": 734003200,
  "today_total_display": "700.0 MB",
  "month_rx_display": "10.00 GB",
  "month_tx_display": "5.00 GB"
}
```

- `total_*` 与 `month_*` 是**同一个值的两种命名**（都取设备当月累计），保留双份是为兼容旧客户端；
- `today_*_bytes` 是数值字段，优先用它做换算/百分比，不要反解 `*_display` 字符串；
- 「今日」不是设备字段（goform 只给当月累计），由 `DataScheduler` 用「当月累计 − 当日基线」
  自算，基线持久化，所以重启不归零、跨天自动重置；
- `*_display` 是 1024 进制、GB 两位小数 / MB·KB 一位小数（`TrafficSummaryMapper.formatBytes`）。

---

## SIM 卡 /api/sim

> 需要认证。仅卡槽切换。
>
> 2026-08-27（T40-15）：原 SIM 信息查询端点已删除 —— imei/imsi/iccid 与设备信息里的
> identity 同源却带 15 分钟缓存，`phone_type` 恒为 `"GSM"`，`sim_state` 也比设备信息里
> `sim.sim_state`（Android TelephonyManager，可区分 Absent / PIN Required / PUK Required）
> 更粗糙；app 侧虽有 Retrofit 方法但从不渲染，web 从未引用。要 SIM 身份信息请读
> 设备信息的 identity / dashboard 汇总的 sim。**PIN/PUK 状态没有专用端点**
> （`/api/device/sim-pin` 从未实现，见文末「文档幻影端点」），只能看
> `/api/device/info` 的 `sim.sim_state`。

#### `POST /api/sim/switch`

切换 SIM 卡槽。

**请求体：**
```json
{ "slot": "1" }
```

`slot` = **1 起的卡槽序号**（`1` / `2` / `3`）或 `"external"`（外置卡）。序号 → 设备预置位的映射在
profile 的 `WriteSpec` 里，客户端看不到设备值。非法值回 400 `OUT_OF_RANGE`
（`卡槽只支持 1~3 或 "external"`）；缺失回 400 `BAD_REQUEST`。

> 兼容一版：旧入参 `goformSlot` 是设备侧运营商预置位（`"0"`=移动 `"1"`=电信 `"2"`=联通 `"11"`=外置），
> 收到会**先换算成序号**再下发并打 warn。注意两者取值域重叠（旧 `"1"` 是电信 = 新的槽位 `2`），
> 别把读到的设备值直接当 `slot` 回填。

**响应：** `{ "success": true, "slot": "1" }`

> `slot` 回显的是**规范化后的序号字符串**（不是设备预置位）。切换成功后 core 会失效 `device:*`
> 缓存并广播对应的 data_changed，客户端据此重拉设备信息 / dashboard 汇总。


---

## AT 命令 /api/at

> 需要认证。直接发送 AT 命令到调制解调器。内置危险命令黑名单。

#### `POST /api/at/command`

发送 AT 命令。危险命令（AT+CFUN=、AT+SFUN=、AT+RESET、AT+POF、AT+*）被拦截。

**请求体：**
```json
{ "command": "AT+CSQ" }
```

**响应：**
```json
{
  "command": "AT+CSQ",
  "response": "+CSQ: 25,99\n\nOK",
  "success": true
}
```

#### `GET /api/at/status`

AT 通道状态。

**响应（已连接）：**
```json
{
  "connected": true,
  "platform": { "platform": "SPREADTRUM", "connected": true, "method": "service-call" }
}
```

**未连接时** `platform` 只有一个键：`{ "connected": false, "platform": { "connected": false } }`。

> `platform` 是**嵌套对象**，不是字符串。内层 `platform` 取值 `SPREADTRUM` / `QUALCOMM` / `UNKNOWN`
> （从 `/proc/cpuinfo` 探测），`method` 恒为 `"service-call"`（外部 `sendat` 回落通道已移除）。
> 通道未连接时 `POST /api/at/command` 返回 `503 UNAVAILABLE`，所以下发前先看这里。

#### `GET /api/at/platform`

平台信息。

**响应：** 与 `/api/at/status` 里的 `platform` 对象**完全相同**（顶层直接就是那个对象，没有额外包装）。

---

## 告警管理 /api/alerts

> 需要认证。告警规则配置和历史告警查询。

#### `GET /api/alerts/config`

获取告警配置。响应**始终包含全部字段**（含与默认值相等的字段与 `configVersion`），可直接用作 `PUT` 的基线。

**响应：**
```json
{
  "enabled": false,
  "notifyEnabled": true,
  "perType": {},
  "minIntervalSec": 1800,
  "edgeTriggeredOnly": true,
  "maxRows": 2000,
  "configVersion": 1,
  "temperatureWarning": 45.0,
  "temperatureCritical": 55.0,
  "batteryWarning": 20,
  "batteryCritical": 10,
  "trafficWarningMb": 1024,
  "trafficCriticalMb": 2048,
  "signalWarningRsrp": -100,
  "signalCriticalRsrp": -115
}
```

#### `PUT /api/alerts/config`

更新告警配置。**请求体是补丁（C02）**：只有显式出现的顶层键会被写入，未出现的键保持服务端当前值。

- 值为 JSON `null` 的键视为"未提供"（不会把字段清空）；
- `perType` 是**整体替换**（不做深合并），传 `{}` 即清空所有分类开关；
- `perType` 的语义（2026-08-27 起引擎真的生效）：`type -> false` 该类型**不检测、不入库、不广播**；
  **键缺省视为关闭**（2026-09-07 起：清空 `perType` = 全部关闭；用户须显式打开每个类型）；总开关 `enabled` 默认 `false`，为 `false` 时一律不检测。
  可用的 type：`temperature` / `battery` / `traffic` / `signal` / `connectivity`；
- 与 `PUT /api/notifications/config` 的通知开关是两件事：这里控制**服务端要不要产生告警**，那里控制**客户端要不要投递通知**；
- 必须带 `configVersion` 且与服务端当前值一致，否则返回 `409`（缺省 `configVersion` 同样按陈旧写处理）；
- 字段类型不合法（如 `"enabled": "yes"`）返回 `400`，`code = BAD_REQUEST`。

**请求体：** `AlertConfig` 的任意子集 + `configVersion`。

```json
{ "enabled": false, "configVersion": 7 }
```

**响应：**
```json
{
  "success": true,
  "config": { ... }
}
```

`config` 是写入后的完整配置，`configVersion` 已自增，客户端必须回写本地副本。

**版本冲突（409）：**
```json
{
  "success": false,
  "ok": false,
  "error": "config_version_conflict",
  "code": "config_version_conflict",
  "message": "配置版本过期，请重新拉取最新配置后再保存",
  "currentVersion": 8,
  "config": { ... }
}
```

#### `GET /api/alerts/list`

告警记录列表。**游标分页（keyset）+ 多维过滤 + 聚合计数**。

**查询参数：**

| 参数 | 默认 | 说明 |
|------|------|------|
| `limit` | `50` | 每页条数，**钳制到 1–200** |
| `level` | — | 按级别过滤（`info` / `warning` / `critical`），空串视为不过滤 |
| `type` | — | 按类型过滤（`temperature` / `battery` / `traffic` / `signal` / `connectivity`），空串视为不过滤 |
| `unread` | `false` | `true` 时只返回未确认的。**必须是严格的 `true`/`false`**（其它值按 `false`） |
| `start_time` | — | 起始时间（epoch 毫秒） |
| `end_time` | — | 结束时间（epoch 毫秒） |
| `cursor` | — | 上一页返回的 `nextCursor`，**向更早的记录翻页** |

不传 `cursor` 时返回最新 `limit` 条。增量拉取用 `start_time`（**闭区间**，边界那条会重复返回一次，客户端要按 `id` 去重）。

**响应：**
```json
{
  "alerts": [
    {
      "id": 1,
      "type": "temperature",
      "level": "warning",
      "message": "CPU temperature exceeded warning threshold",
      "value": "48.5",
      "threshold": "45.0",
      "acknowledged": false,
      "timestamp": 1234567890,
      "count": 3,
      "firstSeenAt": 1234560000,
      "resolvedAt": null
    }
  ],
  "count": 1,
  "total": 137,
  "counts": {
    "total": 137,
    "unread": 12,
    "byType": { "temperature": 90, "signal": 47 },
    "byLevel": { "warning": 120, "critical": 17 }
  },
  "nextCursor": "MTIzNDU2Nzg5MC4x",
  "hasMore": false
}
```

| 字段 | 说明 |
|------|------|
| `alerts[].count` | **同类告警的合并次数**（去重后累计），不是列表长度 |
| `alerts[].firstSeenAt` | 该条首次出现时间；`timestamp` 是最近一次 |
| `alerts[].resolvedAt` | 已恢复时间，未恢复为 `null` |
| `count` | 本页返回条数 |
| `total` | 满足过滤条件的总条数（= `counts.total`） |
| `counts` | 聚合计数，四个键：`total` / `unread` / `byType` / `byLevel` |
| `nextCursor` | 下一页游标（base64 的 `"timestamp.id"`）。本页为空时是 `null` |
| `hasMore` | `本页条数 >= limit`。**是估算**，最后一页刚好占满时会为 `true`，再翻一次才拿到空页 |

#### `POST /api/alerts/ack`

确认（已读）一条告警。

**请求体：**
```json
{ "id": 1 }
```

**响应：** `{ "success": true, "id": 1 }`
缺 `id` 或 `id` 不是整数 → `400 { "error": "id is required" }`（**这两个端点的 400 是裸 `error` 键，不是统一失败信封**）。

#### `POST /api/alerts/ack-all`

批量确认。用于「全部已读」/ 分类已读，替代逐条 POST 的 N+1 请求。

**请求体（全部字段可选）：**
```json
{ "ids": [1, 2, 3], "type": "temperature", "level": "warning", "unreadOnly": true }
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `ids` | 全部 | 指定 id 列表。`null` / 缺省 / 非数组一律视为「按过滤条件全部确认」 |
| `type` / `level` | — | 过滤维度，空串视为不过滤 |
| `unreadOnly` | `true` | 只处理未确认的 |

**响应：** `{ "success": true, "updated": 12 }`（`updated` = 实际改动行数）

#### `POST /api/alerts/ack-resolved`

一键确认已恢复的旧告警。

**请求体：** `{ "minAgeSec": 3600 }` —— 可选，只处理恢复时长超过该秒数的；缺省由引擎取默认。

**响应：** `{ "success": true, "updated": 8 }`

#### `POST /api/alerts/delete`

物理删除一条告警记录。

**请求体：**
```json
{ "id": 1 }
```

**响应：**
```json
{
  "success": true,
  "id": 1,
  "deleted": 1
}
```

`deleted` 为实际删除的记录数（0 表示记录已不存在）。

---

## 通知配置 /api/notifications

> 需要认证。通知场景开关、免打扰、后台守护参数。**app 与 web 共用这一份真源**，任一端修改另一端回读即可见。

#### `GET /api/notifications/config`

返回完整通知配置（`encodeDefaults = true`，全新设备也会返回全部字段而非 `{}`）。

**响应：**
```json
{
  "alert_enabled": false,
  "connectivity_enabled": false,
  "sms_enabled": false,
  "verification_enabled": false,
  "download_enabled": false,
  "traffic_80_enabled": false,
  "device_events_enabled": false,
  "dnd_enabled": false,
  "dnd_start_hour": 23,
  "dnd_end_hour": 7,
  "guard_enabled": false,
  "guard_interval_minutes": 30,
  "guard_foreground_keepalive_enabled": false
}
```

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| alert_enabled | boolean | false | 告警族总闸门：阈值告警 / 设备离线上线 / 流量 80% / 隧道失败，关掉则一条都不投递 |
| connectivity_enabled | boolean | false | 设备离线/上线通知（受 `alert_enabled` 总闸约束）。客户端按「离线只报一次、报过离线才报恢复」成对去重 |
| sms_enabled | boolean | false | 新短信通知 |
| verification_enabled | boolean | false | 验证码提取通知（依附 `sms_enabled`，短信关则一并不发） |
| download_enabled | boolean | false | 下载完成/失败通知 |
| traffic_80_enabled | boolean | false | 流量达限额 80% 预警（受 `alert_enabled` 二级闸门约束） |
| device_events_enabled | boolean | false | 设备事件（WiFi 客户端上下线等） |
| dnd_enabled | boolean | false | 免打扰时段开关，仅 critical 可突破。时段由下面两项决定 |
| dnd_start_hour | int | 23 | 免打扰起始小时，0-23。`start > end` 表示跨零点（23→7 = 当晚 23:00 至次日 07:00）；与 `dnd_end_hour` 相等视为零长度窗口 = 不静默 |
| dnd_end_hour | int | 7 | 免打扰结束小时，0-23 |
| guard_enabled | boolean | false | 后台守护轮询开关 |
| guard_interval_minutes | int | 30 | 守护轮询间隔，取值 15-60 |
| guard_foreground_keepalive_enabled | boolean | false | 前台服务保活（常驻通知） |

#### `PUT /api/notifications/config`

**字段级增量更新**：只覆盖请求体里出现的键，未出现的键保持原值；值为 `null` 的键视为未出现。因此客户端可以只回传变化的那一个开关，不需要「先 GET 全量再整体回传」。

**请求体（示例：只改一个开关）：**
```json
{ "sms_enabled": false }
```

**响应：**
```json
{
  "success": true,
  "config": { "...": "合并后的完整配置" }
}
```

**错误：**

| 状态码 | 场景 |
| --- | --- |
| 400 | 请求体不是合法 JSON 对象，或字段类型不匹配（如 `guard_interval_minutes` 传字符串） |
| 400 | `guard_interval_minutes` 超出 15-60 |

**边界说明：**

- **不含隧道失败通知**。它的真源是 `AppSettings.tunnelNotifyOnFailure`，由 `PUT /api/tunnel/settings` 的 `notify_on_failure` 管理；在此再放一个字段会让同一个概念出现两个真源。
- 本组只管「是否发通知」，**告警阈值本身在 `/api/alerts/config`**。`alert_enabled` 与 `AlertConfig.notifyEnabled` 是两个不同的闸门：前者是客户端系统通知总开关，后者是 core 告警引擎自身的通知标记。

---

## WiFi 控制 /api/wifi

> 需要认证。WiFi 热点配置和控制。

### 状态查询

#### `GET /api/wifi/settings`

合并后的 WiFi 设置（来自 DataHub）。缓存 2 分钟。值域全是字符串，缺失即省略该 key。

**响应字段：**

| 字段 | 说明 |
|------|------|
| `wifi_chip` | 当前芯片，`"chip1"` / `"chip2"` |
| `wifi_chip1_ssid1_ssid` | SSID |
| `wifi_chip1_ssid1_passphrase` | 明文密码（core 已做 base64 解码）。**敏感字段** |
| `wifi_chip1_ssid1_auth_mode` | 认证方式 |
| `wifi_chip1_ssid1_encryp_type` | 加密方式 |
| `wifi_chip1_ssid1_broadcast_ssid` | `"1"` = **隐藏** SSID（注意语义是隐藏而不是广播） |
| `wifi_chip1_ssid1_max_sta_num` | 最大连接数 |
| `WiFiModuleSwitch` | WiFi 模块总开关，`"1"` = 开 |

> **旧字段 `wifi_enable` / `wifi_onoff_state` 已不再输出**，两者都归一到 `WiFiModuleSwitch`；
> 客户端不要再写"三个键任取一个"的探测。字段名带 `chip1_ssid1` 是设备扁平命名的历史债，
> 当前芯片由 `wifi_chip` 指示（`"chip2"` 即 5G 频段那一路）。

#### `GET /api/wifi/module-info`

WiFi 模块原始信息（AuthMode、EncrypType、Password 等设备原始值）。

**响应：** 原样透传，**不属于稳定契约**（见「全局说明 › 设备字段契约」的例外端点），仅供诊断。
**没有开关也没有脱敏**：`Password` 是明文，与默认关闭 + 打码的 `/api/device/goform` 不同，
别按"诊断端点都安全"的印象来用它。


#### `GET /api/wifi/clients`

已连接客户端列表。缓存 15 秒。

**响应字段：**

| 字段 | 说明 |
|------|------|
| `station_list` | 客户端数组。**唯一稳定存在的容器字段** |
| `lan_station_list` | LAN 侧客户端数组。core 当前**只主动查 `station_list`**（与其它 cmd 组合时设备会返回空，所以是单独查的），这个键仅在设备把它塞进同一份响应时才出现 |

数组元素的字段：`hostname`、`ip_addr`、`mac_addr`（元素键集合是**开放**的，固件可能多给几个，例如 `band`）。

**响应：**
```json
{
  "station_list": [
    { "hostname": "phone", "ip_addr": "192.168.0.101", "mac_addr": "AA:BB:CC:DD:EE:FF" }
  ]
}
```

> **两个容器都要读。** `lan_station_list` 不保证出现，但出现时里面是另一批客户端 ——
> 只读 `station_list` 会漏显示。参考做法：合并两个数组后按 `mac_addr` 去重。
>
> **形状本身是契约：** 容器字段的值可能是 JSON 数组，也可能是"数组的 JSON 字符串"（双重编码），
> 设备甚至可能直接返回裸数组。正常路径下 core 已统一成 `{"station_list":[...]}` 的真数组、
> 并把元素键 `mac`→`mac_addr`、`ip`→`ip_addr`、`host_name`→`hostname` 归一，
> 但关掉 `field_normalization_enabled` 排障时会原样透出，**两种形态都要能解**。
> 查询失败时返回 `{}`（不是错误码）。`hostname` 是只读的（没有改主机名的端点）。

#### `GET /api/wifi/qrcode`

WiFi 连接二维码。**这是本手册里少数不返回 JSON 的端点** —— 响应体是**图片二进制**。

**查询参数：**

| 参数 | 默认 | 说明 |
|------|------|------|
| `chip` | `chip1` | 只接受 `chip1` / `chip2`，其它值 → `400 BAD_REQUEST` |
| `ssid_index` | `1` | SSID 序号，非数字时静默回退为 `1` |

**响应：** 图片字节流，`Content-Type` 由设备返回（通常 `image/png`），并强制 `Cache-Control: no-store`。
读不到时 → `503 UNAVAILABLE` + JSON 失败信封（`无法从设备读取 WiFi 二维码`）。

> 二维码由**设备**生成，core 只是转发；所以它反映的是设备当前的 SSID/密码，
> 改完 WiFi 配置后要重新拉取。别按 JSON 解析响应体。


---

### WiFi 控制

#### `POST /api/wifi/enable`

WiFi 开关。

**请求体：**
```json
{ "enabled": true }
```

**响应：** `{ "success": true, "enabled": true }`

#### `POST /api/wifi/ssid`

设置 WiFi 名称和密码。

**请求体：**
```json
{
  "ssid": "MyWiFi",
  "password": "12345678"
}
```

`ssid` 必填。

**响应：** `{ "success": true, "ssid": "MyWiFi" }`

#### `POST /api/wifi/password`

修改 WiFi 密码。

**请求体：**
```json
{ "password": "newpassword123" }
```

`password` 必填。

**响应：** `{ "success": true }`

#### `POST /api/wifi/config`

完整 WiFi 配置。

**请求体：**
```json
{
  "ssid": "MyWiFi",
  "auth_mode": "WPA2-PSK",
  "encryp_type": "AES",
  "passphrase": "12345678",
  "max_sta_num": 32,
  "broadcast_disabled": false,
  "chip_index": "chip1"
}
```

所有字段可选。

**响应：** `{ "success": true }`

#### `POST /api/wifi/power`

WiFi 发射功率。

**请求体：**
```json
{ "level": 2 }
```

`level` 取值 0-2（0=低, 1=中, 2=高）。

**响应：** `{ "success": true, "level": 2 }`

#### `POST /api/wifi/sleep`

WiFi 休眠定时器。

**请求体：**
```json
{ "time": "10" }
```

`time` 是**空闲多少分钟后休眠**（不是秒），默认 `"0"` = 不休眠。必须是非负整数，
否则回 400 `OUT_OF_RANGE`（`休眠时间必须是非负整数（分钟）`）且不下发。

**响应：** `{ "success": true, "time": "10" }`（设备写失败时回 500 + `success:false`）


---

### WiFi 接入控制（拉黑）

> 设备侧只有**整表替换**一条命令（`setDeviceAccessControlList`：四条名单每次都要发全，
> 漏发等于清空）。core 因此把「读-改-写」收在自己这一层：**客户端只发单台设备的 `mac`/`name`**，
> 不需要、也不应该自己拼完整名单 —— 两个客户端同时操作才不会互相覆盖。
>
> 名单**不缓存**（条数极少，且拉黑后要立刻看到结果）。三个写端点写完都会**回读设备**，
> 响应里的名单是设备的真实状态，不是我们以为写进去的那份 —— 前端直接拿它覆盖本地列表即可。

#### `GET /api/wifi/acl`

读接入控制名单。

**响应：**
```json
{
  "mode": "2",
  "black_list": [ { "mac": "2a:ed:87:b3:e8:29", "name": "OPPO-Find-X7" } ],
  "white_list": []
}
```

| 字段 | 说明 |
|------|------|
| `mode` | 设备的 ACL 档位原样字符串。`"2"` = 黑名单生效。**清空黑名单后它仍是 `"2"`**，所以别用 `mode` 判断"有没有拉黑"，要看 `black_list` 是否为空 |
| `black_list` / `white_list` | `{mac, name}` 数组。`mac` 是设备回读的小写形式；`name` 可能是空串（设备没记住主机名时） |

读不到 → `503 UNAVAILABLE`（`无法从设备读取接入控制名单`）。

#### `POST /api/wifi/acl/block`

拉黑一台设备。

**请求体：**
```json
{ "mac": "2a:ed:87:b3:e8:29", "name": "OPPO-Find-X7" }
```

`mac` 必填（缺失 → `400 BAD_REQUEST`），必须是 `xx:xx:xx:xx:xx:xx` 形式，否则
`400 OUT_OF_RANGE`。`name` 可选，一般传 `/api/wifi/clients` 里的 `hostname`；
不能含 `;` `&` `=`（那是 goform 的分隔符）。

**响应：** `GET /api/wifi/acl` 的同构响应 + `success`。
**已在黑名单里 → 幂等成功**（不重复下发，也不会改掉已有的 `name`）。

#### `POST /api/wifi/acl/unblock`

解除拉黑。请求体只需 `mac`（大小写不敏感）。**不在名单里 → 幂等成功。**

**响应：** 同 `/acl/block`。

#### `POST /api/wifi/acl/clear`

清空黑名单（白名单原样保留）。无请求体。

**响应：** 同 `/acl/block`。

> 拉黑**只挡 WiFi 接入**，已连接的设备会被踢下线；对 LAN（USB/网线）侧客户端无效。


---

## 短信管理 /api/sms

> 需要认证。短信收发管理。

#### `GET /api/sms/contacts`

按电话号码聚合的会话列表（服务端分组，前端直接渲染）。

**响应：**
```json
{
  "contacts": [
    {
      "phoneNumber": "+8613800138000",
      "total": 5,
      "unread": 2,
      "latestMsg": "您的验证码是 1234",
      "latestTimestamp": 1735689600000,
      "latestDirection": "received"
    }
  ],
  "count": 1
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| phoneNumber | string | 号码；取不到时是字符串 `"unknown"` |
| total | int | 该号码在**采样范围内**的消息条数 |
| unread | int | 未读数，只统计 `direction == "received"` 且未读的 |
| latestMsg | string | 最新一条正文（不截断） |
| latestTimestamp | long | 最新一条时间戳（ms），用于排序，取不到时为 `0` |
| latestDirection | string | `received` / `sent` |

**`total` 不是该号码的历史总数**：聚合前只读**最近 100 条**短信（`readSms(null, 100, 0, null)`），
所以老会话会被截断、甚至完全不出现在列表里。要完整历史请带 `phone` 参数走
`GET /api/sms/list`。列表按 `latestTimestamp` 倒序；读取抛异常时返回空数组（不报错）。

#### `GET /api/sms/list`

短信列表。

**查询参数：**
- `limit` — 每页数量，钳制 1-200，默认 50（非法值回落 50）
- `offset` — 偏移量，负数钳到 0，默认 0
- `folder` — `inbox`（只要 `direction == "received"`）/ `sent`（只要 `direction == "sent"`）/ 其他任意值（含缺省）都等同 `all`
- `phone` — 按号码精确过滤（空串视为未传）

**响应：**
```json
{
  "messages": [
    {
      "id": 1,
      "phoneNumber": "+8613800138000",
      "content": "Hello",
      "direction": "received",
      "timestamp": 1234567890000,
      "read": true
    }
  ],
  "count": 50,
  "total": 200
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| direction | string | **只有两个值**：`received` / `sent`。`inbox`/`sent` 是 `folder` 的**查询值**，别把 `inbox` 当 direction 匹配 |
| timestamp | long | 毫秒级时间戳 |
| read | boolean | 已读状态由本地 DB merge 得来，不是设备侧字段 |
| count | int | 本批实际条数（≤ `limit`） |
| total | int | **语义随参数变**：见下 |

> **`total` 只有传了 `phone` 时才是真总数**（走 `getFilteredCount(phone)`）。
> 不传 `phone` 时 `total` 直接等于 `count`，也就是本批大小 —— 此时**不能用它做分页总数**，只能靠「返回条数 < limit」判断到底。
> 另外 `total` 的过滤口径只看号码，**不受 `folder` 影响**：`folder=sent&phone=X` 拿到的 `total` 是该号码的收发合计。
> 列表按时间倒序。

#### `GET /api/sms/count`

短信计数。**这才是全局总数/未读数的正确来源**（与 `/list` 的 `total` 无关）。

**响应：**
```json
{ "total": 200, "unread": 5 }
```

#### `GET /api/sms/{id}`

获取单条短信。

**路径参数：**
- `id` — 短信 ID，必须是**正整数**

**响应：**
```json
{
  "id": 1,
  "phoneNumber": "+8613800138000",
  "content": "Hello",
  "direction": "received",
  "timestamp": 1234567890000,
  "read": true
}
```

**错误码（统一 respondFail 信封 `{success:false, ok:false, error, message, code}`）：**
- `400 BAD_REQUEST` — `invalid id`（非数字、0 或负数；注意**不是** 404）
- `404 NOT_FOUND` — `not found`

#### `POST /api/sms/send`

发送短信。

**请求体：**
```json
{
  "phone": "+8613800138000",
  "message": "Hello World"
}
```

`phone` 和 `message` 均必填且非空串，否则 `400 BAD_REQUEST`（`phone and message required`）。

**响应 (200)：** `{ "success": true, "message": "<发送结果说明>", "phone": "+8613800138000" }`

> **失败是 `500` + 同结构 body**（`{success:false, message:"<失败原因>", phone}`），**不是** respondFail 信封 —— 没有 `ok`/`code`/`error` 字段。客户端要单独认这一种。
> 响应里的 `message` 是**发送结果说明**，不是你发出去的正文，别拿它做回显。

#### `POST /api/sms/delete`

删除短信。

**请求体：**
```json
{ "id": 1 }
```

`id` 必填（数字或数字字符串都收），解析不出正整数则 `400 BAD_REQUEST`（`id required`）。

**响应：** `{ "success": true, "id": 1 }`

> 删除失败（记录不存在、设备侧拒绝）返回的是 **`200` + `success: false`**，HTTP 状态码看不出来，必须读 `success`。下面的 `read` / `read-conversation` / `mark-all-read` 同理。

#### `POST /api/sms/read`

标记短信已读/未读。

**请求体：**
```json
{
  "id": 1,
  "read": true
}
```

`id` 必填（同 `delete`，数字或字符串皆可），`read` 默认 `true`（`false` 表示标记为未读）。

**响应：** `{ "success": true, "id": 1, "read": true }`（`read` 回显的是**请求值**，不是操作后实际状态）

#### `POST /api/sms/read-conversation`

标记与指定号码的所有短信为已读。

**请求体：**
```json
{ "phone": "+8613800138000" }
```

`phone` 必填非空白，否则 `400 BAD_REQUEST`（`phone required`）。

**响应：** `{ "success": true, "phone": "+8613800138000" }`

#### `POST /api/sms/mark-all-read`

标记所有短信为已读。

**请求体：** 无

**响应：** `{ "success": true }`

#### `GET /api/sms/verification-codes`

获取从短信中提取的验证码列表。**只读缓存表**：内容由后台调度器实时扫描新短信时写入，本端点不触发解析 —— 刚到的短信可能还没进表。

**响应：**
```json
{
  "codes": [
    {
      "msgId": 1,
      "code": "123456",
      "source": "+8610000000",
      "snippet": "您的验证码是 123456...",
      "body": "您的验证码是 123456，5 分钟内有效，请勿转发他人。",
      "timestamp": 1234567890000,
      "keyword": "验证码"
    }
  ],
  "count": 1
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| msgId | long | 来源短信 ID，可与 `GET /api/sms/{id}` 对应 |
| code | string | 提取到的 4 位或 6 位数字，**字符串**（前导 0 会保留，别当整数解析） |
| source | string | 发送方号码 |
| snippet | string | 正文预览，**超过 80 字会被截断并补 `...`** —— 拿它当完整正文会丢内容，要全文用 `body` |
| body | string | 来源短信**全文**，不截断。DB v8 起入库；v8 之前的旧记录首次被读取时，core 会按 `msgId` 回查原短信自动回填并写回 DB。仅当原短信已被删除时才会是空串，此时读取方回退到 `snippet` |
| timestamp | long | 来源短信时间（ms） |
| keyword | string | 命中的关键词，取值来自固定词表：`验证码` / `校验码` / `动态码` / `确认码` / `密码` |

> 解析规则：命中关键词后在**附近 20 字符**内找不与其他数字相连的 4 位或 6 位数字，命中即入表。5 位或 7 位数字不会被提取。
> 缓存表读取失败时返回 `{"codes": [], "count": 0}`（不报错），所以空数组既可能是「没有验证码」也可能是「DAO 不可用」。

---

## 文件管理 /api/files

> 需要认证。设备存储上的文件操作。所有操作限制在用户存储范围内（`/storage/emulated/0`、SD 卡），系统目录被阻止。

### 路径安全规则

仅允许 `/sdcard`、`/storage/`、`/mnt/media_rw/` 前缀。阻止 `..`、shell 元字符和系统目录。

### 状态与列表

#### `GET /api/files/status`

存储权限状态。

**响应：**
```json
{ "isExternalStorageManager": true }
```

#### `GET /api/files/list`

列出目录内容。

**查询参数：**
- `path` — 目录路径，默认 `/storage/emulated/0`

**响应：**
```json
{
  "files": [
    {
      "name": "Documents",
      "path": "/storage/emulated/0/Documents",
      "isDirectory": true,
      "size": 0,
      "lastModified": 1234567890,
      "permissions": "rwxrwx---",
      "isSymlink": false
    }
  ],
  "path": "/storage/emulated/0",
  "parent": null
}
```

排序固定为「目录在前，同类按文件名小写升序」。`parent` 到达根目录时是 `null`。

> **目录不存在时仍返回 200**，体是 `{ "files": [], "path": …, "parent": …, "error": "目录不存在" }`
> —— 多一个 `error` 键，客户端要按它判空而不是只看 `files.length`。
> 路径非法（越界 / 解析失败）才是 `400 BAD_REQUEST`。

#### `GET /api/files/info`

获取文件/目录详细信息。

**查询参数：**
- `path` — 文件路径

**响应：**
```json
{
  "name": "test.txt",
  "path": "/storage/emulated/0/test.txt",
  "isDirectory": false,
  "size": 1024,
  "lastModified": 1234567890,
  "permissions": "rw-rw----",
  "owner": "",
  "group": "",
  "isSymlink": false
}
```

> **`owner` / `group` 恒为空串** —— 字段保留着但没有实现（没有走 `stat`），别拿来展示。
> 文件不存在 → `404 NOT_FOUND`（`文件不存在`）；路径非法 → `400 BAD_REQUEST`。

#### `GET /api/files/search`

搜索文件。

**查询参数：**
- `path` — 搜索起始路径，默认内部存储
- `query` — 搜索关键词（必填，空白 → `400 BAD_REQUEST`）
- `depth` — 搜索深度，默认 3

**响应：**
```json
{
  "files": [
    { "name": "test.txt", "path": "/storage/emulated/0/test.txt", "isDirectory": false }
  ],
  "query": "test"
}
```

按文件名**子串匹配、忽略大小写**（不是通配符也不是正则），**最多返回 50 条**且没有分页 ——
超出部分直接丢弃，客户端要靠缩小 `path` / 加长关键词收敛。

> 起始路径不存在时返回 200 + 空 `files`（不是 404）。
> 遍历途中撞到无读权限的子目录会**静默返回已收集的部分**（甚至可能是空数组），
> 所以「搜不到」不代表「不存在」。元素只有 `name` / `path` / `isDirectory` 三个键。

#### `GET /api/files/disk-usage`

磁盘使用情况。枚举内部存储 + `/storage/` 下所有非 `emulated`/`self` 的挂载点。

**响应：**
```json
{
  "disks": [
    {
      "filesystem": "/storage/emulated/0",
      "size": "7.5G",
      "used": "4.7G",
      "available": "2.8G",
      "usePercent": "63%",
      "mount": "/storage/emulated/0",
      "label": "内部存储"
    }
  ]
}
```

> **`size` / `used` / `available` 是预格式化字符串**（`"7.5G"` / `"480M"` / `"12K"` / `"0"`），
> **不是字节数** —— 想算百分比只能用 `usePercent`（同样是带 `%` 的字符串），
> 要字节请改用 `/api/system/storage`。
> `filesystem` 与 `mount` 是**同一个值**（都是挂载路径，不是块设备名）。
> `label` 取值只有 `"内部存储"` / `"SD卡"`。一个盘都枚举不到时返回一条全 `"0"` 的内部存储占位。

---

### 文件操作

#### `POST /api/files/read`

读取文本文件（最大 512KB，自动检测二进制）。

**请求体：**
```json
{ "path": "/storage/emulated/0/test.txt" }
```

**响应：**
```json
{
  "content": "file content here...",
  "encoding": "utf-8",
  "size": 1024
}
```

`encoding` 恒为 `"utf-8"`（不做编码探测，非 UTF-8 文本会出现替换字符）。

> **失败也走 200，靠 `content` 的占位文案判别**（三种，都不是错误码）：
> - `"[不是文件或不存在]"` —— 此时 `size` 是 `0`；
> - `"[文件过大: N bytes，超过 512KB 限制，不支持在线查看]"` —— `size` 是**真实文件字节数**；
> - `"[二进制文件，不支持在线查看]"` —— 只看前 4096 字节里有没有 `0x00`，`size` 是真实字节数。
>
> 正常读取时 `size` 是**返回内容的字符数**（`content.length`），**不是文件字节数** ——
> 中文文本里两者必然不等，别拿它跟 `/api/files/info` 的 `size` 对比。
> 路径非法才回 `400 BAD_REQUEST`。

#### `POST /api/files/write`

写入文本文件。

**请求体：**
```json
{
  "path": "/storage/emulated/0/test.txt",
  "content": "new content"
}
```

**响应：** `{ "success": true }`

#### `POST /api/files/delete`

删除文件或目录。

**请求体：**
```json
{ "path": "/storage/emulated/0/test.txt" }
```

**响应：** `{ "success": true, "deleted": true }`

`deleted` 与 `success` **恒等**（同一个布尔），保留是为了兼容旧客户端。递归删除，目录也能删。
文件不存在时返回 `success:false`（200，不是 404）。命中危险路径黑名单 → `400 DANGEROUS_PATH`。

#### `POST /api/files/rename`

重命名文件。

**请求体：**
```json
{
  "old_path": "/storage/emulated/0/old.txt",
  "new_path": "/storage/emulated/0/new.txt"
}
```

**响应：** `{ "success": true }`

#### `POST /api/files/move`

移动文件（带完整性校验）。

**请求体：**
```json
{
  "source": "/storage/emulated/0/a.txt",
  "destination": "/storage/emulated/0/dir/b.txt"
}
```

**响应：**
```json
{
  "success": true,
  "dest_exists": true,
  "source_deleted": true,
  "fallback_copy": false,
  "integrity_ok": true
}
```

`success = 移动成功 && integrity_ok`；`integrity_ok` = 源已消失 + 目标存在 + （文件时）大小一致。
`fallback_copy` 恒为 `false`（跨设备回退复制尚未实现，跨挂载点移动会直接失败）。

> **前置校验失败时响应只有两个键**：`{ "success": false, "error": "源文件或目录不存在" }`
> 或 `{ "success": false, "error": "目标已存在" }` —— **没有** `dest_exists` / `integrity_ok`，
> 客户端读这些键前要先判存在。**目标已存在就直接拒绝**，本端点不覆盖
> （要覆盖用 `/api/files/rename`，它带 `REPLACE_EXISTING`）。

#### `POST /api/files/copy`

复制文件或目录。

**请求体：**
```json
{
  "source": "/storage/emulated/0/a.txt",
  "destination": "/storage/emulated/0/b.txt"
}
```

**响应：** `{ "success": true }`

#### `POST /api/files/mkdir`

创建目录。

**请求体：**
```json
{ "path": "/storage/emulated/0/newdir" }
```

**响应：** `{ "success": true }`

#### `POST /api/files/touch`

创建或更新文件时间戳。

**请求体：**
```json
{ "path": "/storage/emulated/0/newfile.txt" }
```

**响应：** `{ "success": true }`

---

### 文件传输

#### `GET /api/files/download`

下载文件（最大 50MB）。

**查询参数：**
- `path` — 文件路径

**响应：** 二进制文件流，`Content-Type` 按扩展名推断（推不出来用 `application/octet-stream`），
带 `Content-Disposition: attachment` 与 `Content-Length`。

错误：路径非法 → `400`；不是文件 → `404 NOT_FOUND`；**空文件（0 字节）或超过 50MB → `400`**
（同一句错误文案 `文件不存在或超过50MB限制`，区分不出是哪种）。大文件请改用 `/api/files/stream`。

#### `GET /api/files/stream`

流式传输文件，支持 `Range` 头（206 Partial Content）。**没有大小上限**，`Content-Disposition` 是 `inline`。

**查询参数：**
- `path` — 文件路径

**响应：** 二进制流，恒带 `Accept-Ranges: bytes`。带 `Range: bytes=…` 时回 `206` +
`Content-Range: bytes start-end/total`；支持后缀式 `bytes=-500`（末尾 500 字节）；
**多区间只取第一段**。`start > end` 或越界 → `416`。

#### `POST /api/files/upload`

上传文件（multipart/form-data）。

**请求：** multipart 表单，表单字段 `path`（**目标目录**）+ 文件部分。

**响应：**
```json
{
  "success": true,
  "path": "/storage/emulated/0/uploaded.txt",
  "size": 1024
}
```

| 字段 | 说明 |
|------|------|
| `path` | 最终落盘的**完整文件路径**（目标目录 + 文件名） |
| `size` | 实际写入字节数；**多个文件部分会累加到同一个 `size`**，而 `path` 只保留最后一个 |

> 文件名取 `originalFileName` 的**末段**（`../../x.txt` / `sub/a.txt` 都会被削成 `x.txt` / `a.txt`），
> 拿不到名字时兜底 `uploaded_file`；拼接后的路径再做一次越界校验。
> **同名文件直接覆盖**（`REPLACE_EXISTING`），没有确认也没有备份。
> 目标目录不存在会自动创建。表单里没有文件部分 → `400 BAD_REQUEST`（`No file received`）；
> 目标路径非法 → `400`。请求体上限 200MB（见「全局说明」）。

---

## 应用管理 /api/apps

> 需要认证。设备上应用的管理操作。

#### `GET /api/apps`

应用列表。**开销很大**：内部要跑 `pm list packages -f` / `-d` / `--suspended` 与 `dumpsys package packages`，并为每个包取 label + 图标，几百个包时是秒级响应。别放进轮询。

**查询参数：**
- `filter` — `all`（默认）/ `user` / `system`；**其他任意值等同 `all`**。分类**不信 `pm` 的 `-s/-3` 标记**，统一按 APK 路径前缀在内存里二次判定（root 模拟器/部分 ROM 上 pm 标记会错）

**响应：**
```json
{
  "apps": [
    {
      "packageName": "com.example.app",
      "appName": "Example",
      "apkPath": "/data/app/~~abc==/com.example.app-xyz==/base.apk",
      "isSystem": false,
      "versionName": "1.0.0",
      "enabled": true,
      "isFrozen": false,
      "iconBase64": "iVBORw0KGgo..."
    }
  ],
  "count": 50,
  "root": true
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| packageName | string | 包名 |
| appName | string | 应用 label；取不到时**回落成包名**（不会是空串） |
| apkPath | string | base.apk 绝对路径，也是 `isSystem` 的判定依据 |
| isSystem | boolean | 按 `apkPath` 前缀判定，不看 pm 标记 |
| versionName | string | 优先取 `dumpsys` 解析值，失败回落 PackageManager |
| enabled | boolean | 不在 `pm list packages -d` 里即为 true |
| isFrozen | boolean | 取自 `pm list packages --suspended`，**仅 Android 13+ 有效**；13 以下恒为 `false` |
| iconBase64 | string | 应用图标的 base64（不带 data URI 前缀），取不到为空串 |

> **列表元素里没有 `versionCode`** —— 需要版本号请单独取 `GET /api/apps/{packageName}`。
> `iconBase64` 逐条内联，是响应体积的主要来源；`root` 为 false 时上游 shell 基本全失败，`apps` 会是空数组（HTTP 仍 200）。
> `pm list packages -f` 失败或输出为空时同样返回空数组，不报错。

#### `GET /api/apps/{packageName}`

应用详情。走 PackageManager（binder，非 shell），带 `MATCH_UNINSTALLED_PACKAGES` 以对齐列表口径。

**路径参数：**
- `packageName` — 应用包名

**响应：**
```json
{
  "packageName": "com.example.app",
  "appName": "Example",
  "applicationLabel": "Example",
  "versionName": "1.0.0",
  "versionCode": "1",
  "firstInstallTime": "2026-01-01 12:00:00",
  "lastUpdateTime": "2026-02-01 12:00:00",
  "installer": "com.android.vending",
  "isSystem": false,
  "enabled": true,
  "isEnabled": true,
  "apkPath": "/data/app/~~abc==/com.example.app-xyz==/base.apk"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| versionCode | **string** | 是 `longVersionCode` 转的**字符串**（取不到为 `"0"`），不是数字 —— 别直接 `int` 解析 |
| firstInstallTime / lastUpdateTime | string | **已格式化的时间串**，不是时间戳 |
| appName / applicationLabel | string | 同一个值的两个键（历史兼容），这里取不到时是**空串**（与列表回落包名的行为不同） |
| enabled / isEnabled | boolean | 同一个值的两个键 |
| installer | string | 安装来源包名，取不到为空串 |

**错误码：**
- `404 NOT_FOUND` — `App not found`（respondFail 信封 `{success:false, ok:false, error, message, code}`，**不是** `{error}` 单字段）
- 包名不合法（含非法字符）会在校验层抛异常 → `500`，不是 400

#### `POST /api/apps/install`

从设备路径安装 APK。

**请求体：**
```json
{ "path": "/storage/emulated/0/app.apk" }
```

`path` 必填非空白，否则 `400 BAD_REQUEST`（`path is required`）。

**响应 (200)：** `{ "success": true, "message": "<pm install 输出>" }`

**错误码：** `500 OPERATION_FAILED` —— 安装失败，`message`/`error` 是 pm 的原始报错。**不存在 `200 + success:false`**，本端点失败一定是 500。

#### `POST /api/apps/install-url`

从 URL 下载安装 APK。core 先下载到临时文件再走本地安装，**同步等待**，大包会长时间挂住连接。

**请求体：**
```json
{ "url": "https://example.com/app.apk" }
```

`url` 必填非空白，否则 `400 BAD_REQUEST`（`url is required`）。

**响应 (200)：** `{ "success": true, "message": "<安装输出>" }`

**错误码：** `500 OPERATION_FAILED` —— 下载或安装失败（两者不区分，只能看 `message`）。

#### `POST /api/apps/uninstall`

卸载应用。

**请求体：**
```json
{ "packageName": "com.example.app" }
```

`packageName` 必填非空白，否则 `400 BAD_REQUEST`（`packageName is required`）。

**响应 (200)：** `{ "success": true, "message": "<pm uninstall 输出>" }`

**错误码：** `500 OPERATION_FAILED`。

#### `POST /api/apps/permission`

授予/撤销**单个**运行时权限。

**请求体：**
```json
{
  "packageName": "com.example.app",
  "permission": "android.permission.READ_SMS",
  "grant": true
}
```

`packageName` 和 `permission` 必填非空白（缺任一 → `400 BAD_REQUEST`）；`grant` 默认 `true`，`false` 表示撤销。

**响应：** `{ "success": true, "grant": true }`

> 与上面三个端点不同：**权限操作失败是 `200` + `success: false`**，没有 500。`grant` 回显的是请求值。响应里**不带 `packageName`/`permission`**，并发多个请求时无法从响应对应回具体权限。

#### `POST /api/apps/grant-all-permissions`

一次性授予某应用**清单里声明的全部**运行时权限（走 adb/root shell 批量 `pm grant`）。

**请求体：**
```json
{ "packageName": "com.example.app" }
```

`packageName` 必填，空串 → `400 BAD_REQUEST`。

**响应：** `{ "success": true, "message": "已授予 12 项权限" }`
失败 → `500 OPERATION_FAILED`，`message` 是 shell 的原始报错。

> 与 `/api/apps/permission` 的区别：那个逐条授予/撤销、只改一项；这个只能**授予**、
> 无法撤销，且要求 core 拿到 adb/root 通道（无通道时按失败返回，不是 501）。

#### `POST /api/apps/freeze`

冻结应用（`pm suspend` 语义，**不是** `disable`）。

**请求体：**
```json
{ "packageName": "com.example.app" }
```

`packageName` 必填非空白，否则 `400 BAD_REQUEST`。

**响应：** `{ "success": true, "action": "freeze", "packageName": "com.example.app" }`

#### `POST /api/apps/unfreeze`

解冻已冻结的应用。

**请求体：**
```json
{ "packageName": "com.example.app" }
```

**响应：** `{ "success": true, "action": "unfreeze", "packageName": "com.example.app" }`

#### `POST /api/apps/{action}`

通用应用操作。`action` **只接受** `disable` / `enable` / `clear` / `force-stop`。

**请求体：**
```json
{ "packageName": "com.example.app" }
```

**响应：** `{ "success": true, "action": "force-stop", "packageName": "com.example.app" }`

**错误码：**
- `400 BAD_REQUEST` — `packageName is required`
- `400 INVALID_ACTION_TYPE` — `Unknown action: <action>`（注意错误码不是 `BAD_REQUEST`）

> **freeze / unfreeze / {action} 这五个操作失败都是 `200` + `success: false`**，HTTP 状态码看不出来。
> 路由注册顺序上字面路由（`/install`、`/permission`、`/freeze` …）在 `/{action}` 之前，所以
> `POST /api/apps/install` 不会被参数路由吃掉；但反过来，任何未列出的 POST 子路径都会落到
> `/{action}` 并返回 `INVALID_ACTION_TYPE`，**不是 404**。

---

## Shell 执行 /api/shell

> 需要认证。在设备上执行 Shell 命令。内置危险命令黑名单。

#### `POST /api/shell/exec`

执行 Shell 命令。危险命令被拦截（insmod、modprobe、rmmod、service call *sprd、reboot、poweroff、shutdown、echo 到 /sys/class/sblock|smem|smsg|mem、kill -9 rild|modem|netd|servicemanager）。

**请求体：**
```json
{
  "command": "ls -la /sdcard/",
  "as_root": true,
  "timeout": 10
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| command | string | 必填 | 要执行的命令 |
| as_root | bool | true | 是否以 root 执行 |
| timeout | int | 10 | 超时时间（秒） |

**响应：**
```json
{
  "exit_code": 0,
  "stdout": "total 5\n...",
  "stderr": "",
  "success": true
}
```

#### `GET /api/shell/root`

检查 root/特权 Shell 可用性。

**响应：**
```json
{
  "root": true,
  "uid": "0",
  "method": "adb_shell"
}
```

#### `GET /api/shell/prop/{key}`

获取系统属性（key 仅限 `^[a-zA-Z0-9._]+$` 模式）。

**路径参数：**
- `key` — 属性名

**响应：**
```json
{
  "key": "ro.build.display.id",
  "value": "TP1A.220624.014"
}
```

---

## 服务器配置 /api/config

> 需要认证。UFI-AXIS 后端自身的配置管理。

#### `GET /api/config/version`

应用版本信息。`version` 取 core 的 `BuildConfig.VERSION_NAME`（与更新比对基准同源），
`min_client_version` 是硬编码的 `"1.0"`。

**响应：**
```json
{
  "version": "1.0.0",
  "min_client_version": "1.0",
  "update_url": ""
}
```

#### `GET /api/config`

获取全部配置。**唯一被脱敏的是 `goform_password`**（首尾各留 2 位，其余打 `*`）。

**响应字段**（`AppSettings.toMap()` 的全部键）：`port`、`auto_start_on_boot`、`goform_ip`、
`goform_port`、`goform_password`、`debug_mode`、`log_enabled`、`core_log_enabled`、
`app_log_enabled`、`goform_dump_enabled`、`goform_command_enabled`、`qos_enabled`、
`qos_shell_max_concurrent`、`qos_cache_ttl_ms`、`qos_goform_query_max`、`qos_goform_set_max`、
`adb_auto_start_on_boot`、`sms_code_enabled`、`sms_code_cleanup_hours`、`update_url`、
`update_mirror_base`。

**这里没有 `token` / `secret`。** 随「严格设备独立性」改造（2026-08-28），全局 token 与 HMAC
共享 secret 已整体删除 —— 凭据现在是每设备一份、由配对流程签发，只能在
`/api/pairing/devices` 里增删，不能通过配置端点读写。

#### `PUT /api/config`

更新配置字段。所有字段可选，仅更新传入的字段。

**请求体：**
```json
{
  "port": 8088,
  "auto_start_on_boot": true,
  "goform_ip": "192.168.0.1",
  "goform_port": 8080,
  "goform_password": "admin",
  "log_enabled": true,
  "core_log_enabled": true,
  "app_log_enabled": true,
  "debug_mode": false,
  "goform_dump_enabled": false,
  "goform_command_enabled": false,
  "qos_enabled": true,
  "qos_shell_max_concurrent": 5,
  "qos_cache_ttl_ms": 5000,
  "qos_goform_query_max": 10,
  "qos_goform_set_max": 5,
  "sms_code_enabled": true,
  "sms_code_cleanup_hours": 24,
  "update_url": "https://example.com/update.json",
  "update_mirror_base": "https://mirror.example.com"
}
```

所有字段可选。**上面没列出的键会被静默忽略**（不进 `updated_fields` 也不进 `rejected_fields`）——
`token` / `secret` 已不再是配置项，传了也是被忽略；`GET /api/config` 回的
`adb_auto_start_on_boot` 属于"只读不可写"；`device_profile_id` / `field_normalization_enabled`
连 GET 都不返回（见「设备字段契约 › 排障开关」）。
字段被拒时**不会**写入，且会出现在响应的 `rejected_fields` 里（C03）：


- `OUT_OF_RANGE` —— 越界，附带 `min` / `max`：`port` 1024..65535、`goform_port` 1..65535、`qos_shell_max_concurrent` 1..10、`qos_cache_ttl_ms` 500..30000、`qos_goform_query_max` 1..8、`qos_goform_set_max` 1..4、`sms_code_cleanup_hours` 0..720；
- `MASKED_VALUE` —— 回写了 `GET` 返回的脱敏值（含 `***`）；
- `BLANK_VALUE` —— `goform_password` / `goform_ip` / `update_url` 不允许空串（`update_mirror_base` 允许空串 = 直连）；
- `WRONG_TYPE` —— 键存在但值类型不对（如 `"port": "8088"`）。

`needs_restart` 为 true 的字段：`port` / `goform_ip` / `goform_port` / `goform_password`。

日志相关开关是**分三层**的，客户端不要当成同一个（都是「与」关系）：

- `log_enabled`（默认 `true`）—— **总闸**。置 `false` 后 core 立刻清空内存缓冲、关闭日志文件写入器，之后所有级别（含 WARN/ERROR）、logcat、`<日期>/{app|at|error}.log` 落盘与 goform 会话日志**全部停写**，`GET /api/debug-logs` 会一直返回空数组。
- `core_log_enabled`（默认 `true`）—— **core 侧子开关**，效果同上但只作用于 core。core 常驻写盘，这是控制后端日志体积的开关。
- `app_log_enabled`（默认 `true`）—— **app 侧子开关**。core **自己不使用这个值**，只替手机端保管真源（放这里 web 才能一起控制、多台手机连同一设备时开关一致）；app 通过 `GET /api/config` 回读后作用于自己的 `DebugLog`。
- `debug_mode`（默认 `false`）—— **详细开关**。只在上面几项为 true 时有意义，控制 DEBUG/INFO 是否记录**以及是否落盘**；关闭时仍会记 WARN/ERROR。

> 日志文件：所有类型单文件上限 5MB，超出后轮转为 `<prefix>_<date>.log.1`（只保留 1 个历史文件）；跨天写入时自动清理 7 天前的文件。

`goform_dump_enabled`（默认 `false`）不是日志开关，而是 `GET /api/device/goform`（设备原始字段 dump）
的准入开关：关着时该端点回 403，打开后响应里的 PII 与凭据仍然是打码值。排障看完建议关回去。

`goform_command_enabled`（默认 `false`）是另一个开关，管 `POST /api/device/goform/query|set`
（裸命令通道）：关着时这两个端点回 403。**它比 `goform_dump_enabled` 危险得多** ——
返回值不脱敏（真密码、真 IMEI），且 `set` 绕过所有值域校验直接写设备。只在排障时临时打开，用完必须关回去。

**响应：**
```json
{
  "success": true,
  "updated_fields": ["goform_ip", "port"],
  "rejected_fields": [
    { "field": "qos_goform_set_max", "reason": "OUT_OF_RANGE", "min": 1, "max": 4 }
  ],
  "needs_restart": false,
  "hint": ""
}
```

注意：**部分成功仍返回 `success: true`**，客户端必须同时看 `updated_fields` 与 `rejected_fields`，不能只看 HTTP 状态码。

#### `POST /api/config/reset`

恢复默认配置。

**请求体：** 无

**响应：** `{ "success": true, "message": "Configuration reset to defaults" }`

---

## 更新管理 /api/update

> 需要认证。**core 后端 APK 的自更新**（不是 web 前端资源 —— 那一套在 `/api/web/*`，两者状态机独立）。
> `frontend-info` / `frontend-apk` 两个端点例外：它们提供的是**手机 App 安装包**的信息与代理下载。
>
> 失败信封统一为「真实 HTTP 状态码 + `{success:false, ok:false, error, message, code}`」，
> 与 `/api/web/*` 的 `{error}` 单字段信封不同，客户端要两种都认。

#### `POST /api/update/check`

检查并触发更新。**异步**：立即返回当前状态快照，下载/校验/安装在后台进行，进度靠 `GET /api/update/status` 轮询（core 无独立进度端点、无缓存、无限频）。

**重复触发是静默忽略**：当状态处于 `downloading`/`verifying`/`installing`/`uploading` 或互斥锁未拿到时，core 直接返回 200 + 当前状态，**响应里区分不出「已接受」和「被忽略」**，客户端只能看 `state`。

**请求体：** 无

**响应：**
```json
{
  "state": "idle",
  "progress": 0,
  "message": "",
  "current_version": "1.0.0",
  "latest_version": "1.1.0",
  "apk_path": null
}
```

`state` 取值（与 Kotlin `UpdateManager.State` 一一对应）：`idle`、`downloading`、`verifying`、`installing`、`uploading`、`done`、`failed`、`need_push`。

- `need_push`：更新源不可达，需客户端手动上传 APK 再走 `install-local`。
- `installing`：core 会通过 watchdog 脚本重启**自身**，此期间 HTTP 服务短暂不可达，客户端轮询失败属预期，不应报错终止。
- `progress` 是下载百分比 0..100。
- `latest_version` 与 `apk_path` **可为 `null`**（未拉过清单 / 尚无本地 APK 时）；`current_version` 与 `message` 恒为字符串（读不到版本名时是空串）。
- `apk_path` 在 `POST /api/update/upload` 成功后会被写入状态（2026-09-06 修：此前只改 `state`/`message`，
  导致上传完回读 `status` 拿到的仍是 `null`，客户端据此渲染的「安装已上传的包」入口会立刻消失）。
  该值随 `POST /api/update/reset` 清空，也随进程重启丢失 —— **core 重启后 `apk_path` 会回到 `null`**，
  且上传的文件本身也可能已被启动期清理（`cleanupInstallArtifacts` 会扫掉 update 目录下所有
  `*.apk` / `*.part`），所以重启后一律按"需要重新上传"处理，不要缓存路径直接调 `install-local`。

#### `GET /api/update/status`

获取当前更新状态。**有副作用**：处于 `installing` 时 core 会顺带读 watchdog 日志刷新安装结果，因此客户端必须轮询本端点，不能只依赖 WebSocket 的 `update` 频道推送。

**响应：** 同 `POST /api/update/check`。

#### `POST /api/update/upload`

上传 APK 文件进行更新（multipart/form-data）。

**请求：** multipart 表单，字段名固定为 `file`（会校验 APK 的 PK magic header）。请求体上限是**动态**的：按更新清单里的 `apkSize × 1.2 + 10MB` 计算，清单未知时回落 100MB，下限 20MB。

**响应 (200)：**
```json
{ "ok": true, "apk_path": "/path/to/uploaded.apk" }
```

**错误码：**
- `400 BAD_REQUEST` — 未收到 APK 文件 / 上传的不是 APK（magic 头不符）/ 文件提交失败
- `409 UPDATE_IN_PROGRESS` — 更新进行中（下载/安装/其他上传），请稍后重试

> 副作用：上传成功后 `state` 被置为 `idle`、`message` 为「APK 上传完成，可执行安装」；失败则置为 `failed` 且 `message` 是失败原因 —— 所以上传失败会**污染**后续 `GET /api/update/status`，需要 `POST /api/update/reset` 清掉。
> 落盘路径固定 `/sdcard/Download/UFI-AXIS/update/ufi-core-uploaded.apk`（先写 `.part` 再 rename 原子提交），与自动通道的 `ufi-core.apk` 是**两个不同文件**。

#### `POST /api/update/install-local`

安装已上传的本地 APK。**fire-and-forget**：core 起 watchdog 脚本后台安装并重启自身，接口会中断十几秒。

**请求体：**
```json
{ "apk_path": "/path/to/apk" }
```

`apk_path` 必填（取 `upload` 响应或 `status` 里的 `apk_path`），缺失返回 `400 BAD_REQUEST`。

**响应：**
```json
{
  "ok": true,
  "status": { "state": "installing", "progress": 0, "message": "..." }
}
```

`ok: false` 表示未能启动安装（此时 HTTP 仍是 200，失败原因在 `status.message`）。两种成因要区分：APK 路径不存在会把 `state` 置成 `failed` 并写明原因；而**重复触发**（已有更新在跑）只是静默返回 `ok:false`，`status` 保持原样、`message` 不会提到这次调用 —— 客户端得靠 `status.state` 是否属于忙碌态自行判断。

#### `POST /api/update/reset`

重置更新状态。

**请求体：** 无

**响应：** 同 `statusToMap()` 格式。

#### `GET /api/update/frontend-info`

获取**手机 App 安装包**的版本信息（从远程更新清单的 `frontend` 对象读取）。缓存 5 分钟。

**响应：**
```json
{
  "version": "1.1.0",
  "versionCode": 11,
  "changelog": "Bug fixes and improvements",
  "apk_url": "https://example.com/app.apk",
  "sha256": "abc123..."
}
```

**错误码：**
- `502 UPSTREAM_FAILED` — 更新源不可用

#### `GET /api/update/frontend-apk`

通过服务端代理下载手机 App 的 APK（SSRF 域名白名单保护）。**需要 `Authorization` 头**，所以浏览器里的 `<a download>` 用不了它（web 端改为直接给 `frontend-info` 返回的 `apk_url` 上游直链）。

**查询参数：**
- `url` — APK 下载 URL（必填）。白名单为 `github.com`、`objects.githubusercontent.com`，**外加**与最近一次 `frontend-info` 缓存里 `apk_url` **同 host** 的地址（自建 CDN/镜像因此可放行）。

**响应：** APK 二进制流。`Content-Type` 透传上游（缺失时回落 `application/vnd.android.package-archive`，无法解析时回落 `application/octet-stream`）；上游给了 `Content-Length` 才会带该头。

**错误码（respondFail 信封 `{success:false, ok:false, error, message, code}`）：**
- `400 BAD_REQUEST` — 缺少 url 参数
- `403 FORBIDDEN` — URL 不在白名单
- `502 UPSTREAM_FAILED` — 上游连接失败 / 上游返回非 2xx

> **转发中断不会变成错误码**：响应头一旦提交，后续读上游异常只记日志，客户端拿到的是 `200` + **被截断的字节流**。想确认完整性必须自己比对 `Content-Length` 或校验 `frontend-info` 给的 `sha256`。

---

## Web 前端资源 /api/web

> 需要认证。**web 前端资源（ZIP）的更新**，与 `/api/update` 的 core APK 自更新是两套独立状态机。
>
> 失败信封是**单字段** `{ "error": "..." }`（没有 `success`/`code`），与 `/api/update` 不同。
> **唯一例外是 `POST /api/web/clear` 的 500**，它多带一个 `success: false` 和一个与 `error` 内容相同的 `message`。
> 本组**不经 WebSocket 推送**（`WebUpdateManager` 没有 broadcaster），进度只能轮询。

#### `POST /api/web/check`

从更新清单的 `web` 对象自拉取 ZIP 并覆盖安装。**异步**，立即返回状态快照；无缓存，靠互斥防并发。

**响应：**
```json
{
  "state": "idle",
  "progress": 0,
  "message": "",
  "current_version": "1.0.0",
  "latest_version": "1.1.0"
}
```

`state` 取值：`idle`、`downloading`、`verifying`、`installing`、`done`、`failed`、`need_push`。
比 `/api/update` **少一个 `uploading`**，也**没有 `apk_path` 字段** —— 手动通道走 `POST /api/web/update` 直接上传安装，不存在「先上传再安装」的两步式。

> `current_version` / `latest_version` **可为 `null`**：前者读不到 `web/version.json` 时为 null，后者在从未成功拉取过清单前一直为 null。
> `need_push` 表示**更新源不可用**（不可达 / 返回非 JSON / ZIP 下载失败），提示前端改走手动上传兜底；真正的安装类错误落在 `failed`。
> 并发保护是 CAS 互斥：已有更新在跑时**本端点不报错**，只是把当前快照原样回给你（不会启动第二次更新）。

#### `GET /api/web/status`

查询自动更新状态。与 `/api/update/status` 不同，本端点**无副作用**。

**响应：** 同 `POST /api/web/check`。

#### `GET /api/web/version`

当前前端资源版本信息。

**响应（override 模式）：**
```json
{
  "mode": "override",
  "version": "1.1.0",
  "buildTime": "2026-08-27T00:00:00Z",
  "bundledVersion": "1.0.0",
  "bundledBuildTime": "2026-08-01T00:00:00Z",
  "hasBackup": true
}
```

`mode` 为 `bundled` 时只返回 `mode` / `version` / `buildTime` / `hasBackup`（**没有** `bundledVersion`、`bundledBuildTime` 两个字段），且 `hasBackup` 恒为 `false`。

`version` / `buildTime` / `bundledVersion` / `bundledBuildTime` 都**可为 `null`**（对应的 `version.json` 读不到或缺字段时）。
`hasBackup` 只看 `backup/version.json` 是否存在，决定 `POST /api/web/rollback` 是否可用。

#### `POST /api/web/update`

上传 ZIP 覆盖安装前端资源（multipart/form-data）。

**请求：** multipart 表单，字段名固定为 `file`（其他字段名一律忽略）；ZIP **上限硬编码 50MB**，是流式累计判定，超限即中止并返回 400。

**响应：** `{ "success": true, "...": "同 GET /api/web/version 的字段" }`（安装后的版本信息 + `success`）

**错误码（均为 `{ "error": "..." }` 单字段）：**
- `400` — 未收到文件数据 / ZIP 超过 50MB / 解压失败 / **ZIP 缺少 `index.html`** / **ZIP 缺少 `version.json`**
- `500` — 其他未捕获异常（`"更新失败: ..."`）

> ZIP 必须在**根目录**同时含 `index.html` 与 `version.json`，否则校验不过。
> 安装是原子的：先解压到 staging，校验通过后把现役 override 挪成 backup、staging 顶上；任一步失败会把 backup 还原回去，不会留下半装状态。

#### `POST /api/web/rollback`

回滚到上一版本。

**响应：** `{ "success": true, "...": "回滚后的版本信息" }`

**错误码：** `400 { "error": "没有可回滚的备份版本" }` —— 判据是 `backup/` 下**同时**存在 `version.json` 和 `index.html`，只有其中一个也算无备份（与 `hasBackup` 只看 `version.json` 的口径略宽严不同，所以 `hasBackup=true` 仍可能回滚失败）。

> 回滚是**一次性的**：backup 目录被移走当作现役，之后 `hasBackup` 变 false，不能连续回滚两级。

#### `POST /api/web/clear`

清除 override，恢复 APK 内置版本（同时删掉 web / backup / staging 三个目录）。

**响应：** `{ "success": true, "message": "已恢复内置版本", "...": "清除后的版本信息（mode 应为 bundled）" }`

**错误码：**
```json
{
  "success": false,
  "error": "清除未完成：override 目录仍存在，请重启服务后重试",
  "message": "清除未完成：override 目录仍存在，请重启服务后重试"
}
```
`500`，本组唯一带 `success` 字段的失败信封。触发条件是删除后复核 `hasOverride()` 仍为 true（文件被占用导致删不干净）—— 此时**不能**按成功处理，否则用户以为已回内置版本、实际还在跑坏掉的 override。

---

## 内网穿透 /api/tunnel

> 需要认证。FRP 通道与 Cloudflare Tunnel 的多实例管理 + 看护（自动重连）。
>
> **本组有三种失败形态，客户端必须都认：**
> 1. `respondFail`：真实 HTTP 码（400/404）+ `{success:false, ok:false, error, message, code}`；本组只用 `BAD_REQUEST` / `NOT_FOUND`。
> 2. **业务失败：HTTP 200 + `{success:false, message:"<中文原因>"}`**（没有 `ok`/`code`/`error`）。`start`/`stop`/`delete`/`stop-all` 全是这种 —— 只看 HTTP 状态码会把失败当成功。
> 3. 裸异常：`{error:"..."}` 单字段（请求体不是 JSON 对象 → 400；未捕获异常 → 500）。
>
> **实例状态（instance）结构**：`{name, running, status, last_error}`；FRP 的 `items` 还多 `server_addr`/`server_port`/`proxy_count`，CF 的 `items` 多 `token_set`。
> `status` 值域：`Running` / `Stopped` / `Error`。
>
> **desired（看护期望）语义**：`start` 成功会把名字加入 desired，`stop` 会移出 —— 用户主动停的通道不会被自动重连拉起。

### 通用 / 看护

#### `GET /api/tunnel/status`

两个引擎的总览。**会 fork 进程探测版本号**（`--version`，最长 5s），不适合高频轮询。

**`version` 的三种取值**（探测结果缓存在引擎实例里，进程存活期只探一次）：
- 探到 → 版本串；
- **首次探测失败 → 空串 `""`**（探测函数回 null，route 层兜成 `""`）；
- **之后再问 → `"unknown"`**（失败也缓存，避免每次 `/status` 都 fork）；
- 二进制文件不存在时**不缓存**，所以永远是 `""`（每次都会再判一次文件存在性）。

**响应（顶层无 `success`）：**
```json
{
  "frp": { "version": "0.58.1", "running_count": 1, "instances": [ { "name": "home", "running": true, "status": "Running", "last_error": "" } ] },
  "cf_tunnel": { "version": "2024.8.2", "running_count": 0, "instances": [] },
  "local_port": 8088,
  "auto_reconnect": true,
  "notify_on_failure": true
}
```

注意引擎键名是 `frp` 与 **`cf_tunnel`**（不是 `cf`，尽管路由前缀是 `/api/tunnel/cf`）。
`instances[].status` 是枚举名（`Running` / `Stopped` / …），`local_port` 就是 core 自身的监听端口。

#### `POST /api/tunnel/stop`

停止全部 FRP + CF 实例，**并清空 `frp_desired` / `cf_desired`**（即一并取消看护）。

**响应：** `200 { "success": bool, "message": "..." }`；`success=false` 时 message 为 `"部分隧道进程强制停止失败，可能仍在运行"`。

#### `POST /api/tunnel/logs/clear`

清空两个引擎所有实例的内存缓冲并截断运行期日志文件。**响应恒为** `{ "success": true }`。

#### `GET /api/tunnel/settings`

**响应（裸对象，无 `success` 包裹）：**
```json
{
  "auto_reconnect": true,
  "reconnect_interval_sec": 30,
  "notify_on_failure": true,
  "max_reconnect_attempts": 3,
  "frp_desired": ["home"],
  "cf_desired": []
}
```

`max_reconnect_attempts` 是**只读常量**（3）。`notify_on_failure` 是**隧道失败通知的唯一真源**（通知配置端点 `/api/notifications/config` 故意不含该字段）。开关入口在 app 的「内网穿透设置」页；实际投递仍受 `/api/notifications/config` 的 `alert_enabled` 总闸约束，总闸未开时 app 会 toast 引导用户去打开。

#### `PUT /api/tunnel/settings`

**严格字段级更新**：只覆盖请求体里出现的字段，未出现的绝不动。

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| auto_reconnect | boolean | 传了必须能解析为布尔，否则 400 |
| notify_on_failure | boolean | 同上 |
| reconnect_interval_sec | int | 必须整数且在 10..120，越界 400 |

**不静默回落**：「传了但解析不出」会明确报 400（例如 `{"auto_reconnect": 1}`），不会被当作未传。

**响应：** `{ "success": true, "settings": { …全量设置… } }` —— 注意与 GET 的裸对象形态不同。

**副作用：** `auto_reconnect=true` 会清零失败计数；开启自动重连或**把间隔改小**会重排看护协程。

### FRP

#### `GET /api/tunnel/frp/configs`

**响应：**
```json
{
  "configs": ["home", "office"],
  "active": "home",
  "running": ["home"],
  "items": [
    { "name": "home", "server_addr": "1.2.3.4", "server_port": 7000, "proxy_count": 2,
      "running": true, "status": "Running", "last_error": "" }
  ]
}
```

`server_addr`/`server_port`/`proxy_count` 由 core **行级解析** TOML 得到（不引 TOML 库，`#` 开头跳过），解析不到时分别是 `""` / `0` / `0`。`active` 会自愈（指向已删除项时置空；只剩一条时自动选中）；`running` 来自真实进程槽位而非偏好。

#### `GET /api/tunnel/frp/config/{name}`

**响应：** `{ "name": "home", "toml": "..." }`；名字非法或文件不存在 → `404 NOT_FOUND`。

#### `PUT /api/tunnel/frp/config/{name}`

| 字段 | 类型 | 必填 | 校验 |
| --- | --- | --- | --- |
| toml | string | 是 | 缺失 / null / **全空白** → `400 BAD_REQUEST`（`"toml is required"`） |

- 空白拒绝是 T40-2 修的：此前漏传会把通道配置**静默写成空文件并回 `success:true`**。
- `name` 空 → 400；名字被 sanitize 会改动即视为非法（禁止 `\ / : * ? " < > |`、**逗号**、控制字符、`..`，长度 ≤ 64），保存失败 → 400（`"invalid config name or save failed"`）。**不会静默改名**。
- `toml` 传成对象/数组会走到未捕获异常 → **500 + `{error}`**（形态 3），不是 400。
- 写盘是 tmp + rename 原子提交；首条配置保存后若无选中项会自动选中它。

**响应：** `{ "success": true, "message": "FRP config [home] saved" }`

#### `DELETE /api/tunnel/frp/config/{name}`

**响应：** `200 { "success": bool }`（**无 message**）。不存在、或正在运行且强杀失败 → `success:false`（引擎在实例锁内「停不掉就不删」）。成功后修正 `active` 与 desired。

#### `POST /api/tunnel/frp/config/{name}/activate`

只写选中项（**UI 记忆，与运行状态无关**）。成功 `{success:true}`；不存在 → `404 NOT_FOUND`。

#### `POST /api/tunnel/frp/config/{name}/start`

**同步**：core 起进程后等 1.5s 存活探测，秒退即判失败，返回时状态已确定，**不需要轮询**（单次调用最长约 1.7s）。

- 通道不存在 → `404 NOT_FOUND`。
- 其余 → `200 { "success": bool, "message": "..." }`；失败 message 形如 `"FRP tunnel [home] failed to start: <原因>"`，原因优先取引擎记录的 `last_error`，否则取输出末 3 行（`" | "` 连接、截 300 字符，空则 `no output`）。
- 常见失败原因（会写进 `last_error`）：`frpc 可执行文件缺失`、`配置文件不存在`、`配置文件为空`、`配置缺少 serverAddr（或为空），frpc 无法连接服务端`。
- 幂等：已在跑直接返回 `success:true`。成功后加入 `frp_desired`。

#### `POST /api/tunnel/frp/config/{name}/stop`

`200 { "success": bool, "message": "..." }`；失败 message `"frpc [name] 强制停止失败，进程可能仍在运行"`。成功会**移出 desired**。

#### `GET /api/tunnel/frp/config/{name}/log`

**查询参数：** `full=1` 时读运行期日志文件尾部（文件只在运行期存在，为空则自动回落内存缓冲）；不传只读内存最近 200 行。

**响应：** `{ "name": "home", "log": "..." }`

#### `POST /api/tunnel/frp/config/{name}/log/clear`

清空该实例日志。**响应恒为** `{ "success": true }`。

#### `POST /api/tunnel/frp/stop`

停止全部 FRP 通道。`200 { "success": bool, "message": "..." }`。

### Cloudflare Tunnel

> 仅 token 模式（多隧道），**没有临时隧道与本地 ingress 配置**。

#### `GET /api/tunnel/cf/tunnels`

**响应：**
```json
{
  "tunnels": ["prod"],
  "active": "prod",
  "running": [],
  "items": [ { "name": "prod", "token_set": true, "running": false, "status": "Stopped", "last_error": "" } ]
}
```

列表**不含 token 本体**，只有 `token_set` 布尔。该端点会自愈 active 并可能迁移旧 token（有写文件副作用）。

#### `GET /api/tunnel/cf/tunnel/{name}`

**响应：** `{ "name": "prod", "token": "<明文 token>", "running": false }` —— **本端点明文返回 token**（因为 PUT 要求 token 非空、没有「留空即保留」语义，客户端编辑时必须先回读）。不存在 → `404 NOT_FOUND`。

#### `PUT /api/tunnel/cf/tunnel/{name}`

| 字段 | 类型 | 必填 | 校验 |
| --- | --- | --- | --- |
| token | string | 是 | 空白 → `400 BAD_REQUEST`（`"token is required"`） |

`name` 空 → 400；名字规则同 FRP，非法或保存失败 → 400（`"invalid tunnel name or save failed"`）。首条保存后若无选中项会自动选中。

**响应：** `{ "success": true, "message": "CF tunnel [prod] saved" }`

#### `DELETE /api/tunnel/cf/tunnel/{name}`

不存在 → `404 NOT_FOUND`；否则 `200 { "success": bool }`（运行中会先停，停不掉则不删）。

#### `POST /api/tunnel/cf/tunnel/{name}/activate`

`{success:true}`；不存在 → `404 NOT_FOUND`。

#### `POST /api/tunnel/cf/tunnel/{name}/start`

不存在 → `404 NOT_FOUND`；否则 `200 { "success": bool, "message": "..." }`。失败多为 token 无效导致进程秒退，message 里带引擎记录的原因（与 FRP 同一套 `failReason`）。

#### `POST /api/tunnel/cf/tunnel/{name}/stop`

`200 { "success": bool, "message": "..." }`；失败 message `"cloudflared [name] 强制停止失败，进程可能仍在运行"`。

#### `GET /api/tunnel/cf/tunnel/{name}/log`

同 FRP 的日志端点（支持 `full=1`）。**响应：** `{ "name": "...", "log": "..." }`

#### `POST /api/tunnel/cf/tunnel/{name}/log/clear`

清空该隧道日志。**响应恒为** `{ "success": true }`。

#### `POST /api/tunnel/cf/stop`

停止全部 CF 隧道。`200 { "success": bool, "message": "..." }`。

---

## 邮件通知 /api/sms-forward

> 需要认证。邮件通知配置（可选模块）。
>
> 2026-08-29：原「短信转发」改名「邮件通知」，通道只剩 SMTP —— `method` 字段与
> curl 回调 / 钉钉机器人的全部字段（`curl_url` / `curl_template` / `dingtalk_token` /
> `dingtalk_secret`）已从 core 删除，传了也会被忽略。
> **路径未改名**（仍是 `/api/sms-forward/*`）：app 与 web 都按它引用，改名只会破坏跨端契约。
> 新增 `scenes`（通知场景白名单）与 `POST /notify`（app 通知转邮件）。

#### `GET /api/sms-forward/config`

获取邮件通知配置。

**响应：**
```json
{
  "enabled": false,
  "smtp_host": "smtp.example.com",
  "smtp_port": 465,
  "smtp_user": "user@example.com",
  "smtp_pass_set": true,
  "smtp_from": "user@example.com",
  "smtp_to": "dest@example.com",
  "forward_dev_info": true,
  "blacklist": [],
  "scenes": ["alert", "verification"]
}
```

`scenes` 是需要同时发邮件的**通知场景 id** 白名单，取值与 app 的 `NotifyScene.sceneId` 一致：
`alert` / `connectivity` / `sms` / `verification` / `download` / `traffic80` / `events` / `tunnel`。
底层是 `StringSet`，**顺序不保证**。

#### `GET /api/sms-forward/diagnose`

诊断检查。

**响应：**
```json
{
  "config_enabled": true,
  "smtp_host_set": true,
  "smtp_port": 465,
  "smtp_user_set": true,
  "smtp_pass_set": true,
  "smtp_to_set": true,
  "sendable": true,
  "scene_count": 2,
  "sent_total": 42,
  "sent_success": 40,
  "sent_failed": 2,
  "last_sent_at": 1787049393000,
  "last_error": ""
}
```

`sendable` = `enabled` 且服务器/用户名/密码/收件地址四项齐全，是 core 内部真正的发信闸门。

发信计数（`sent_*`）跨重启保留，**只统计真正发起过 SMTP 投递的次数** —— 黑名单拦截、场景未勾选、配置不全都不计入 `sent_failed`，那些不是"发送失败"。`sent_total` 由 `sent_success + sent_failed` 派生。`last_sent_at` 是最近一次**成功**的毫秒时间戳（0 = 从未成功）；`last_error` 为最近一次失败原因（`异常类名: message`，截断到 200 字），成功后会被清空。

#### `POST /api/sms-forward/config`

保存邮件通知配置。**字段级合并**（T40-1）：只覆盖请求体里出现的字段，未出现的字段保留 core 当前值，因此可以只提交 `{"enabled": true}`。

**请求体：**
```json
{
  "enabled": true,
  "smtp_host": "smtp.example.com",
  "smtp_port": 465,
  "smtp_user": "user@example.com",
  "smtp_pass": "password",
  "smtp_from": "user@example.com",
  "smtp_to": "dest@example.com",
  "forward_dev_info": true,
  "blacklist": ["+8610000000"],
  "scenes": ["alert", "verification"]
}
```

**响应：** `{ "success": true }`（恒为 true，且**不回传新配置** —— 保存后需要重新 `GET /config` 才能刷新 `*_set` 屏蔽位）

**需要客户端自己兜住的边界（core 侧无校验）：**

- 凭据字段 `smtp_pass`：**不传 == 传空串 == 保留原值**。因此该端点无法清空已设置的密码，只能覆盖成新的非空值。响应/GET 里只有派生的 `smtp_pass_set`，回传这个布尔不产生任何效果。
- `blacklist` / `scenes`：传数组即整体替换（传 `[]` 即清空），不传则保留。底层都是 `StringSet`，**顺序不保证**。
- `scenes` 里的场景 id **不做白名单校验**：拼错的 id 会被原样存下，然后永远匹配不到任何通知（静默不发信）。
- 类型不匹配不会报错而是**静默回落原值**（例如 `smtp_port` 传字符串 `"465"` 会被当作缺失，端口保持不变）。客户端必须按类型发送。
- 无必填校验、无邮箱格式校验、无端口范围校验 —— 缺字段也会「保存成功」，但发信永远不会成功。app 与 web 都在前端做了必填与端口范围校验。

#### `POST /api/sms-forward/test`

发送测试邮件。**用的是已持久化的配置**，未保存的改动不生效。

**请求体：** 无（body 被忽略）

**响应：** HTTP 恒为 200，成败看 body
- 成功：`{ "success": true }`
- 失败：`{ "success": false, "error": "邮件通知未启用" | "SMTP 配置不完整（服务器/用户名/密码/收件地址）" | "发送失败，请检查日志" | "<异常类>: <消息>" }`

#### `POST /api/sms-forward/notify`

把一条**已经发出的 app 系统通知**转投邮件。app 在 `NotificationCenter.notify` 成功后 fire-and-forget 调用；SMTP 凭据与场景白名单都只存在 core，所以是否真发由 core 判定，app 不做判断。

**请求体：**
```json
{
  "scene": "alert",
  "title": "设备温度过高",
  "body": "当前 62℃，阈值 60℃"
}
```

`scene` / `title` 必填（缺失或空串 → `400 BAD_REQUEST`）；`body` 可选，默认空串。

**响应：** `{ "success": true, "sent": true }`

`sent == false` 是**正常结果**而非错误：邮件通知未启用、SMTP 配置不完整、或该 `scene` 不在 `scenes` 白名单里，都返回 200 + `sent: false`。

> app **不会**用这个端点上报 `sms` / `verification` 两个场景（2026-08-30）。core 自己收到短信时（ContentObserver / 兜底轮询 → `SmsForwardController.forwardSms`）已经按同样的 scene 发过一封带全文与验证码高亮的邮件，app 再报一次就是同一条短信两封邮件，且两条路径互不知情、无法互相去重。短信族邮件的唯一生产者是 core 的短信路径；`scenes` 里勾选 `sms` / `verification` 仍然生效，管的就是那条路径。

---

## 定时任务 /api/tasks

> 需要认证。设备上的定时任务管理（可选模块）。

#### `GET /api/tasks`

获取所有任务。

**响应：**
```json
{
  "tasks": [
    {
      "id": "a1b2c3d4",
      "name": "Daily Reboot",
      "actionType": "custom_shell",
      "params": {},
      "command": "reboot",
      "hour": 3,
      "minute": 0,
      "repeatDaily": true,
      "triggerMode": null,
      "scheduleType": null,
      "cron": null,
      "scheduleParams": {},
      "enabled": true,
      "createdAt": 1234567890,
      "logs": []
    }
  ],
  "count": 1
}
```

**字段名是驼峰**（与 `/api/downloads` 的蛇形不同，别混）。`id` 是 8 位随机串。
`logs` 直接内嵌在任务对象里（与 `/api/tasks/{id}/logs` 同一份数据）。
`triggerMode` / `scheduleType` / `cron` 是可空扩展字段，旧的「每天 hour:minute」任务里都是 `null`。

#### `GET /api/tasks/{id}`

获取单个任务详情。

**路径参数：**
- `id` — 任务 ID

**响应：** 任务对象。404 时返回错误。

#### `POST /api/tasks`

创建定时任务。

**请求体：**
```json
{
  "name": "Daily Reboot",
  "hour": 3,
  "minute": 0,
  "actionType": "custom_shell",
  "params": {},
  "command": "reboot",
  "repeatDaily": true,
  "enabled": true
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| name | string | `""` | 任务名称 |
| hour | int | 0 | 小时 (0-23) |
| minute | int | 0 | 分钟 (0-59) |
| actionType | string | `"custom_shell"` | 动作类型 |
| params | object | `{}` | 动作参数，**值只收 JSON 基元**（不能嵌套） |
| command | string | `""` | Shell 命令（`actionType` 为 `custom_shell` 时必填，空 → `400 BAD_REQUEST`） |
| repeatDaily / repeat_daily | bool | true | 是否每日重复（两种写法都收，驼峰优先） |
| triggerMode | string? | `null` | 时间触发模式扩展 |
| scheduleType | string? | `null` | 调度类型（8 个 preset 之一或自定义） |
| cron | string? | `null` | 自定义 cron 表达式 |
| scheduleParams | object | `{}` | 调度参数，同样只收基元 |
| enabled | bool | true | 是否启用 |

`actionType` 可选值（权威来源 `ActionType` 枚举，**共 10 个**）：`data_toggle`、`wifi_toggle`、
`airplane_toggle`、`reboot`、`shutdown`、`led_toggle`、`performance_mode`、`roaming_toggle`、
`network_mode`、`custom_shell`。越界 → `400 INVALID_ACTION_TYPE`。

> 注意默认值差异（**不是笔误**）：本端点缺省 `actionType` 是 `custom_shell`，
> 而 `/api/rules` 缺省是 `data_toggle`。
> `network_mode` 的参数填**别名**（见 `/api/network/mode` 的映射表），不是设备侧 BearerPreference。

**响应：** `{ "success": true, "id": "a1b2c3d4" }`；写入失败 → `400 OPERATION_FAILED`。

#### `PUT /api/tasks/{id}`

更新任务。字段可选，未传入的保留原值。

**路径参数：**
- `id` — 任务 ID

**请求体：** 同创建接口。

**响应：** `{ "success": true }` 或 404。

#### `DELETE /api/tasks/{id}`

删除任务。

**路径参数：**
- `id` — 任务 ID

**响应：** `{ "success": true }` 或 404。

#### `GET /api/tasks/{id}/logs`

获取任务执行日志。

**路径参数：**
- `id` — 任务 ID

**响应：**
```json
{
  "logs": [
    {
      "id": "9f8e7d6c",
      "taskId": "a1b2c3d4",
      "timestamp": 1234567890,
      "success": true,
      "output": "Command executed successfully"
    }
  ],
  "count": 1
}
```

元素有 5 个字段（`id` / `taskId` / `timestamp` / `success` / `output`），`output` 可能是空串。
id 不存在时返回**空列表**（200），不是 404。`/api/rules/{id}/logs` 结构完全相同。

#### `POST /api/tasks/clear`

清除所有任务。

**请求体：** 无

**响应：** `{ "success": true }`

---

## 自动化规则 /api/rules

> 需要认证。「当…就…」条件触发规则。与 `/api/tasks`（**时间**触发）是两套独立存储，
> 但**共用同一套 `actionType`**（`ActionExecutor.VALID_ACTION_TYPES`）。
>
> 触发判定由 `DataScheduler` 每轮采集时并联调用 `ConditionEngine.evaluate*`，
> **不额外发请求**——所以规则的最小反应粒度 = 采集轮询间隔。
>
> 条件引擎不可用（未初始化）时：**`POST` 返回 501 `UNAVAILABLE`**，
> 而 `GET` 静默返回空列表、`PUT`/`DELETE` 返回 404、`POST /clear` 仍返回 `success:true`。

#### `GET /api/rules`

**响应：** `{ "rules": [ …AutomationRule… ], "count": 2 }`

AutomationRule 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 8 位随机串，服务端生成 |
| `name` | string | 规则名，默认空串 |
| `enabled` | boolean | 默认 `true` |
| `triggerType` | string | 触发条件，默认 `traffic_total_reached` |
| `triggerParams` | object | 触发参数，**值必须是 JSON 基元**（不能嵌套对象/数组） |
| `actionType` | string | 动作，**默认 `data_toggle`**（注意 `/api/tasks` 默认是 `custom_shell`，不是笔误） |
| `params` | object | 动作参数，同样只收基元 |
| `cooldownMs` | number | 冷却毫秒，默认 `60000` |
| `createdAt` | number | 创建时间（epoch 毫秒） |
| `logs` | array | 执行日志，元素 `{ id, taskId, timestamp, success, output }` |

`triggerType` 取值（v1 共 5 种）：

| 值 | 类别 | `triggerParams` | 含义 |
|------|------|------|------|
| `traffic_total_reached` | 电平 | `thresholdBytes`（Long） | 当月累计流量（rx+tx）≥ 阈值 |
| `signal_below` | 电平 | `rsrp`（Int，负值） | RSRP ≤ 阈值 |
| `battery_below` | 电平 | `levelPercent`（Int） | 电量 ≤ 阈值 **且未充电** |
| `network_type_changed` | 边沿 | `targetType`（如 `"5G"`） | 网络类型跳变为目标值 |
| `disconnect` | 边沿 | 无 | 蜂窝网络由连通变断开 |

> **电平类带武装位（armed）**：条件满足触发一次后置位，必须先恢复到不满足才会再次触发，
> 避免阈值附近反复刷。改规则参数（`PUT`）会**重置武装位**。
> 缺少必需的 `triggerParams` 键时该条规则被**静默跳过**（不报错、永不触发）。

#### `GET /api/rules/{id}`

**响应：** 单个 AutomationRule 对象，不存在时 404 `NOT_FOUND` / `Rule not found`。

#### `GET /api/rules/{id}/logs`

**响应：** `{ "logs": [ { "id": "…", "taskId": "…", "timestamp": 1234567890, "success": true, "output": "" } ], "count": 1 }`

id 不存在时返回**空列表**（不是 404）。

#### `POST /api/rules`

**请求体（全部可选，缺省走默认值）：**
```json
{
  "name": "断网自动重启",
  "enabled": true,
  "triggerType": "disconnect",
  "triggerParams": {},
  "actionType": "reboot",
  "params": {},
  "cooldownMs": 300000
}
```

**响应：** `{ "success": true, "id": "a1b2c3d4" }`

错误分支：
- `actionType` 不在白名单 → `400 INVALID_ACTION_TYPE`；
- 条件引擎未就绪 → `501 UNAVAILABLE`；
- 引擎拒绝写入 → `400 OPERATION_FAILED`。

> **`triggerType` 不做白名单校验**：写入非法值会成功返回，但这条规则永远不会被任何
> `evaluate*` 匹配到（静默失效）。校验只覆盖 `actionType`。

#### `PUT /api/rules/{id}`

**部分更新**：只有显式出现的键会覆盖，其余保留原值（`triggerParams` / `params` 是**整体替换**，不是逐键合并）。

**响应：** `{ "success": true }`；id 不存在 → `404 NOT_FOUND`；`actionType` 非法 → `400 INVALID_ACTION_TYPE`。

#### `DELETE /api/rules/{id}`

**响应：** `{ "success": true }`，不存在时 HTTP 404 + `{ "success": false }`（**不是统一失败信封**）。

#### `POST /api/rules/clear`

清空所有规则。**请求体：** 无。**响应：** `{ "success": true }`（无二次确认，前端自己拦）。

---

## 下载管理 /api/downloads

> 需要认证。下载任务管理，支持 aria2 后端（可选模块）。

### 任务管理

#### `GET /api/downloads`

获取所有下载任务及系统状态。

**响应：**
```json
{
  "tasks": [
    {
      "id": "dl-uuid",
      "url": "https://example.com/file.zip",
      "file_name": "file.zip",
      "save_path": "/storage/emulated/0/Download",
      "total_size": 104857600,
      "downloaded_bytes": 52428800,
      "progress": 50.0,
      "speed": 1048576,
      "upload_speed": 0,
      "status": "downloading",
      "error": null,
      "created_at": 1234567890,
      "completed_at": null,
      "engine": "aria2",
      "protocol": "https",
      "connections": 4,
      "seeders": 0
    }
  ],
  "count": 1,
  "active": 1,
  "aria2_running": true,
  "aria2_version": "1.37.0",
  "config": { ... },
  "tracker_count": 1000,
  "tracker_status": "ok",
  "tracker_last_updated": 1234567890,
  "throttle_state": "normal",
  "throttle_temp": 35.0,
  "throttle_cpu": 25,
  "throttle_battery": 80,
  "throttle_memory": 50,
  "throttle_charging": true,
  "throttle_was_stopped": false
}
```

#### `GET /api/downloads/{id}`

获取单个下载任务。

**响应：** 任务对象。404 时返回错误。

#### `POST /api/downloads`

创建下载任务。

**查询参数：**
- `force` — 是否跳过重复检查，默认 false

**请求体：**
```json
{
  "url": "https://example.com/file.zip",
  "file_name": "file.zip",
  "save_path": "/storage/emulated/0/Download",
  "speed_limit": 0,
  "connections": 4
}
```

`url` 必填。

**响应 (201)：**
```json
{
  "success": true,
  "task": { ... }
}
```

**重复检测 (409)：**
```json
{
  "duplicate": true,
  "existing_task": { ... },
  "suggested_filename": "file (1).zip"
}
```

#### `POST /api/downloads/{id}/pause`

暂停下载。

**响应：** `{ "success": true }`

#### `POST /api/downloads/{id}/resume`

恢复下载。

**响应：** `{ "success": true }`

#### `POST /api/downloads/{id}/retry`

重试失败的下载。

**响应：** `{ "success": true }` 或 404。

#### `POST /api/downloads/{id}/rename`

重命名下载任务。

**请求体：**
```json
{ "name": "new-filename.zip" }
```

`name` 必填。

**响应：** `{ "success": true }`

**错误码：**
- `400` — `{ "error": "重命名失败" }`

#### `DELETE /api/downloads/{id}`

删除下载任务。

**查询参数：**
- `delete_file` — 是否同时删除已下载文件，默认 false

**响应：** `{ "success": true }`

#### `POST /api/downloads/clear-completed`

清除已完成的下载。

**查询参数：**
- `delete_file` — 是否同时删除文件，默认 false

**响应：** `{ "success": true, "cleared": 5 }`

`cleared` = 被清掉的 `status == "completed"` 任务数。只清「已完成」，失败 / 暂停的不动。

---

### 配置管理

#### `GET /api/downloads/config`

获取下载配置。

**响应：** 包含以下字段的 JSON 对象：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| max_concurrent | 3 | 最大并发下载 |
| max_connections_per_server | 4 | 每服务器最大连接 |
| global_speed_limit | 0 | 全局速度限制 (0=无限) |
| per_task_speed_limit | 0 | 单任务速度限制 |
| save_dir | /storage/emulated/0/Download/UFI-AXIS/Download | 默认保存路径 |
| split_count | 4 | 分片数 |
| max_overall_upload_limit | 0 | 最大上传限制 |
| bt_seed_ratio | 1.0 | BT 做种比例 |
| bt_max_peers | 50 | BT 最大节点 |
| bt_enable_dht | true | 启用 DHT |
| bt_enable_lpd | true | 启用本地节点发现 |
| disable_ipv6 | false | 禁用 IPv6 |
| check_certificate | true | 检查证书 |
| max_tries | 5 | 最大重试次数 |
| retry_wait | 3 | 重试间隔（秒） |
| bt_tracker_auto_update | true | 自动更新 Tracker |
| bt_tracker_update_interval_hours | 24 | Tracker 更新间隔 |
| bt_tracker_source_url | — | Tracker 源 URL |
| bt_tracker_custom_list | — | 自定义 Tracker 列表 |
| smart_throttle | false | 智能节流 |
| throttle_temp_warn | 55 | 温度警告阈值 |
| throttle_temp_critical | 70 | 温度严重阈值 |
| throttle_cpu_warn | 60 | CPU 警告 |
| throttle_cpu_critical | 85 | CPU 严重 |
| throttle_battery_warn | 30 | 电池警告 |
| throttle_battery_critical | 15 | 电池严重 |
| throttle_memory_warn | 75 | 内存警告 |
| throttle_memory_critical | 90 | 内存严重 |
| only_download_when_charging | false | 仅充电时下载 |

#### `PUT /api/downloads/config`

更新下载配置。所有字段可选。

**请求体：** 同上方配置字段。

**响应：** `{ "success": true, "config": { ... } }`

---

### BT Tracker

#### `GET /api/downloads/trackers`

获取 BT Tracker 列表和元数据。

**响应：**
```json
{
  "trackers": "udp://tracker1.example.com\nudp://tracker2.example.com",
  "tracker_count": 1000,
  "last_updated": 1234567890,
  "source_url": "https://example.com/trackers.txt",
  "status": "ok",
  "auto_update": true,
  "interval_hours": 24
}
```

#### `GET /api/downloads/validate-path`

验证下载保存路径。

**查询参数：**
- `path` — 要验证的路径（必填）

**响应：**
```json
{
  "valid": true,
  "exists": true,
  "is_directory": true,
  "writable": true,
  "free_space": 3000000000,
  "absolute_path": "/storage/emulated/0/Download"
}
```

`valid` = 存在 && 是目录 && 可写（三者的与）。
路径**不存在**时 `is_directory` / `writable` 是 `null`，`free_space` 是 `-1`。
`path` 为空 → `400 BAD_REQUEST`，体里带 `"valid": false`。

> 本端点**不走 `safeResolve` 沙箱**（直接 `File(path)`），能探到用户存储之外的路径 ——
> 但真正下载时保存目录仍受下载器自身约束。

#### `POST /api/downloads/trackers/refresh`

从源刷新 BT Tracker 列表。

**请求体：** 无

**响应：** `{ "success": true, "tracker_count": 1000, "status": "ok" }`

#### `POST /api/downloads/trackers/save`

保存自定义 Tracker 列表。

**请求体：**
```json
{ "trackers": "udp://tracker1.example.com\nudp://tracker2.example.com" }
```

**响应：** `{ "success": true, "tracker_count": 2 }`

---

## 测速 /api/speedtest

> 需要认证。上下行速度测试与零负载延迟探针（可选模块）。三个端点共用一个 12 并发的信号量，
> 超出返回 `429` + 纯文本 `请求频率过多`；单次传输另有 60s 服务端时间预算。

#### `GET /api/speedtest`

下行测速，返回填充数据的二进制流。

**查询参数：**
- `ckSize` — 1MiB 块的个数，1-4096，默认 10

**响应：** `application/octet-stream` 二进制流，`Content-Disposition: attachment; filename=random.dat`。

客户端跑够时长后会直接断开连接，这属于正常收尾，服务端只记一行 debug 日志。

#### `HEAD /api/speedtest`

零负载延迟探针：只回响应头、不产生任何字节，用于测延迟与抖动。不占用测速并发位。

**响应：** `200 OK`，无响应体。

#### `POST /api/speedtest/upload`

上行测速丢弃汇：读完请求体即丢弃，只回报收到的字节数。请求体可用 chunked（无需
`Content-Length`），服务端以「读到 EOF / 撞 2 GiB 上限 / 撞 60s 时间预算」三者之一为终止条件。

**请求体：** 任意二进制流，建议 `Content-Type: application/octet-stream`。

**响应：** `{ "success": true, "bytes": 268435456 }`

---

## 监控中心 /api/monitor

> 需要认证。历史数据查询和存储管理（可选模块）。

#### `GET /api/monitor/history`

降采样历史数据。支持两种查询模式：基于时长和基于时间范围。

**查询参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| type | 数据类型：cpu / memory / traffic_rx / traffic_tx / signal_rsrp / signal_sinr / battery / temperature | cpu |
| hours | 查询时长：1 / 6 / 24 / 168（与 start_time/end_time 互斥） | 24 |
| start_time | 起始时间（epoch 毫秒），与 end_time 配合使用 | — |
| end_time | 结束时间（epoch 毫秒），与 start_time 配合使用 | — |
| points | 返回点数：10-720 | 360 |

**时长模式响应：**
```json
{
  "type": "cpu",
  "points": [
    { "t": 1234567890, "avg": 25.5, "min": 10.0, "max": 45.0 }
  ],
  "count": 360,
  "raw_count": 1440,
  "period_hours": 24,
  "bucket_seconds": 60
}
```

**时间范围模式响应：** 键完全相同，但 `period_hours` 变成「小时跨度」（`(end-start)/3600000`，整数除法），
且 **`raw_count` 恒等于 `count`**（SQL 层已经聚合成桶，原始行数不再可知）——
只有时长模式的 `raw_count` 是真正的原始采样点数。

**约束：**
- `hours` 和 `start_time`/`end_time` 互斥
- `start_time` 和 `end_time` 必须同时提供
- 时间范围最大 30 天
- 时间范围模式使用 SQL 桶聚合，时长模式使用内存降采样

每个点包含 `t`（桶中间时间戳）、`avg`（平均值）、`min`（最小值）、`max`（最大值）。
`signal_rsrp` / `signal_sinr` 会**剔除 0 值**（无信号哨兵），所以点数可能明显少于其它指标。

#### `GET /api/monitor/storage`

监控数据表存储统计。**7 张表固定列出**（cpu_history / memory_history / traffic_records /
signal_history / battery_history / alert_records / sms_records）。

**响应：**
```json
{
  "tables": [
    { "name": "cpu_history", "count": 10000, "size_kb": 512.0 }
  ],
  "total_kb": 2048.0,
  "total_display": "2.0 MB"
}
```

> **`size_kb` 是估算值，不是真实占盘**：`count × 每行字节常数 / 1024`
> （cpu/signal 按 60B，memory/traffic 按 50B，battery 45B，alert 120B，sms 200B），
> 不含索引与 SQLite 页开销。
>
> 两个 `count` 目前不可信：
> - **`sms_records` 的 `count` 硬编码为 `0`**；
> - **`alert_records` 的 `count` 最大只会是 `1`**（实现取的是 `getRecentAlerts(1).size`）。
>
> 要真实告警条数请读 `/api/alerts/list` 的 `counts.total`。

#### `POST /api/monitor/clean`

清理旧数据。

**请求体：**
```json
{
  "type": "all",
  "days": 7
}
```

`type` 取值 `"all"` 或具体表名。`days` 默认 7。**请求体可以整个省略**（解析失败按 `{}` 处理，走默认值）。

**响应：**
```json
{
  "deleted": { "cpu_history": 5000, "traffic_records": 3000 },
  "cutoff_days": 7
}
```

> `deleted` 只包含**本次实际操作过的表**：`type="all"` 时是 6 个键，指定单表时只有 1 个键，
> **表名拼错时是空对象 `{}` 且仍返回 200**（没有校验，不会报错）。
> `sms_records` 永久保留 —— 既不在 `"all"` 的清理范围内，显式传 `"sms_records"` 也**什么都不做**（返回 `{}`）。

#### `POST /api/monitor/control`

启动或停止数据采集器（DataScheduler）。

**请求体：**
```json
{ "enabled": true }
```

`enabled` 必填（缺失或不是布尔 → 400）。响应键是 **`ok`**，不是 `success`。

**响应：**
```json
{ "ok": true, "enabled": true }
```

**错误码：**
- `400 BAD_REQUEST` — 统一失败信封，`message` = `"enabled (boolean) required"`

> 2026-08-26：该开关已持久化到 `AppSettings.backgroundServiceEnabled`（此前只写内存，服务重启后
> 被重置为开启）。它与 `/api/service/start|stop` 是**同一份状态**，任意一端修改都互相可见。

---

#### `GET /api/monitor/preferences`

监控中心个性化偏好（设备级配置，app 与 web 共享同一份真源）。

**响应：**
```json
{
  "enabledTypes": ["cpu", "memory", "traffic_rx", "traffic_tx", "signal_rsrp", "signal_sinr", "battery", "temperature"],
  "defaultHours": 24,
  "refreshIntervalSec": 30,
  "fixedYAxis": false,
  "fillAlpha": 1.0,
  "exportZip": false
}
```

字段：
- `enabledTypes` — 启用的指标 apiKey 集合，取值范围同 `GET /api/monitor/history` 的 `type`
- `defaultHours` — 默认时间范围，只能是 `1` / `6` / `24` / `168`
- `refreshIntervalSec` — 自动刷新间隔秒，`10..3600`
- `fixedYAxis` — Y 轴固定（`false` = 自适应）
- `fillAlpha` — 图表填充透明度 `0..1`
- `exportZip` — 导出偏好，`true` = 打包 zip

未写入过时返回上面这份默认值（不会返回 `{}`），因此客户端不需要自己兜默认值。

> **边界**：采集总开关 `collectEnabled` **不在本端点**。它的真源是
> `AppSettings.backgroundServiceEnabled`，读用 `GET /api/service/status`，写用
> `POST /api/monitor/control` 或 `POST /api/service/start|stop`。

---

#### `PUT /api/monitor/preferences`

字段级合并更新，请求体为上述字段的**任意子集**；未出现的键保持服务端现值。

**请求体：**
```json
{ "refreshIntervalSec": 60, "fixedYAxis": true }
```

**响应：**
```json
{ "success": true, "preferences": { "...": "合并并校验后的完整值" } }
```

客户端应以响应里的 `preferences` 为准（服务端会做取值域校验）。

**错误码：**
- `400` `BAD_REQUEST` — 字段类型不合法，或越界：
  - `defaultHours 必须是 1/6/24/168 之一`
  - `refreshIntervalSec 必须在 10..3600 秒之间`
  - `fillAlpha 必须在 0..1 之间`
  - `enabledTypes 含未知指标：xxx`

> `enabledTypes` 是**完整集合语义**（传了就整体替换），客户端要改单个指标开关必须先 GET 再整体回传，
> 与 `PUT /api/alerts/config` 的 `perType` 同一约定。本端点**不做** `configVersion` 版本守门 ——
> 纯展示偏好，最后写入者生效即可。

---

## 服务控制 /api/service

> 需要认证。2026-08-26 新增。

**语义边界（重要）：**
- `stop` 只停**数据采集与告警检测循环**（CPU/内存/信号/流量/SMS/电池/清理），**HTTP 服务不停** ——
  因此停止后 `/api/service/start` 仍然可达，不会把客户端锁在门外。
- `restart` 会**重启后端服务**：销毁 Service 组件，再由 AlarmManager 以前台服务语义拉起，
  所有接口中断约 10 秒，期间进行中的下载/上传任务会被中断。
  注意口径：**这不是进程级重启** —— 进程通常不会退出，`object` 单例与静态状态不会被清空；
  也因此 shell 看门狗（判的是进程存活）在这条路径上不会介入，恢复依赖闹钟（另有一枚 30s 的备份闹钟）。
- 开关值持久化在 `AppSettings.backgroundServiceEnabled`，服务重启后仍然生效。

#### `GET /api/service/status`

**响应：**
```json
{
  "success": true,
  "ok": true,
  "enabled": true,
  "collecting": true,
  "monitor_switch_on": true,
  "http_running": true,
  "uptime_ms": 3600000,
  "auto_start_on_boot": true,
  "native_exec": {
    "probed": true,
    "executable": true,
    "selinux": "permissive",
    "detail": "exec 正常（jq: jq-1.7.1）"
  }
}
```

- `enabled` — 用户可见的服务开关（持久化真源）。
- `collecting` — 采集循环的实际运行状态；与 `enabled` 可能短暂不一致（停止时的收尾是异步的）。
- `http_running` — 恒为 `true`（能收到响应就说明 HTTP 在跑），保留字段让客户端不必特判。
- `uptime_ms` — 后端服务已运行时长；服务未在运行时为 `0`。
- `native_exec` — 自带原生二进制（`aria2c` / `ttyd` / `socat` / `curl` / `jq` / `adb`）能否执行。
  这些文件由 `AssetExtractor` 释放到 `filesDir/shell/`，SELinux 标签是 `app_data_file`，
  而 Android 10 起 `untrusted_app` 对它的 `execute_no_trans` 已被 neverallow —— 只有
  **SELinux 为 permissive** 的设备跑得起来（目标 UFI 机型出厂多为 permissive）。
  - `probed` — 本次进程是否已跑过自检（组件图构建时探一次）。`false` 时其余字段无意义，
    **不代表不可用**。
  - `executable` — 探针进程能否起来（退出码不参与判定）；`probed=false` 时为 `null`。
  - `selinux` — `enforcing` / `permissive` / `unknown`（读 `/sys/fs/selinux/enforce`）。
  - `detail` — 给人看的一句话；失败时带原始异常（enforcing 下通常是
    `error=13, Permission denied`）。
  
  `executable=false` 时**下载引擎、网页终端、Samba 均不可用**，且各调用点历史上都是静默
  `catch`，只能靠这个字段判定，不要再去猜网络或配置问题。

#### `POST /api/service/start`

启动后台采集（幂等）。无请求体。响应体同 `GET /api/service/status`。

#### `POST /api/service/stop`

停止后台采集（幂等），HTTP 服务继续运行。无请求体。响应体同 `GET /api/service/status`。

#### `POST /api/service/restart`

重启后端服务。**先回响应再重启**，因此客户端能拿到结果，随后连接会断开。

**响应：**
```json
{
  "success": true,
  "ok": true,
  "restarting": true,
  "estimated_downtime_ms": 10000,
  "hint": "后端服务正在完全重启，约 10 秒后自动恢复，请稍后重连"
}
```

> 客户端应把重启之后的请求失败视为预期行为：等待 `estimated_downtime_ms` 再重连，
> 并回读 `/api/service/status` 确认恢复。

#### `POST /api/service/autostart`

**请求体：**
```json
{ "enabled": true }
```

`enabled` 必填。响应体同 `GET /api/service/status`。

**错误码：**
- `400` `BAD_REQUEST` — `enabled (boolean) required`

---

## 调试日志 /api/debug-logs

> 需要认证。运行时日志查看（可选模块）。

#### `GET /api/debug-logs`

获取缓冲的日志。

**查询参数：**
- `level` — 日志级别过滤（可选）
- `limit` — 返回数量，默认 200

**响应：**
```json
{
  "logs": [
    { "level": "INFO", "tag": "HttpServer", "message": "Server started on port 8088", "timestamp": 1234567890 }
  ],
  "total": 200
}
```

#### `DELETE /api/debug-logs`

清空日志缓冲（**只清内存**，落盘文件用下面的 `/files` 删）。

**响应：** `{ "success": true }`

### 落盘日志文件 /api/debug-logs/files

> 2026-08-28 新增。core 的日志目录已从 app 私有目录搬到用户可直接浏览的
> `/sdcard/Download/UFI-AXIS/log/core/<yyyy-MM-dd>/`（先按日期分目录、再按类型分文件：
> `app.log` / `at.log` / `error.log`，单文件超 5MB 轮转为 `*.log.1`，目录总量上限 40MB，
> 保留最近 7 天）。外部存储不可用时才回退到 `<dataDir>/logs`。
> 这些接口是给 app / web **远程**列目录和读尾部用的；在设备上用文件管理器直接看同一路径即可。

#### `GET /api/debug-logs/files`

**响应：**
```json
{
  "files": [
    { "name": "2026-08-28/app.log", "size": 20480, "modified": 1787900000000 }
  ],
  "total": 1,
  "total_bytes": 20480,
  "dir": "/sdcard/Download/UFI-AXIS/log/core"
}
```

#### `DELETE /api/debug-logs/files`

删除**全部落盘日志文件**（含正在写的，会先关 writer；内存缓冲用 `DELETE /api/debug-logs` 清）。
**只动 core 这一侧** —— 手机 APP 自己的日志在 `/sdcard/Download/UFI-AXIS/log/app/`，由 app 端
单独清，两侧互不牵连。

**响应：** `{ "success": true, "freed_bytes": 20480 }` —— `freed_bytes` 是释放的字节数，
一个文件都没有时是 `0`，`success` 恒为 `true`。

#### `GET /api/debug-logs/files/{name...}`

只返回文件**尾部**（`text/plain`）。`max_bytes` 可选，默认 256KB，取值被夹在 1KB~2MB ——
单文件可达几十 MB，整读会 OOM。路径按 `^\d{4}-\d{2}-\d{2}/(app|at|error)\.log(\.1)?$` 白名单
校验，防目录穿越；从中间截断时会丢掉首个半行。文件不存在返回 404。

> **路径形状只有一种。** core 是**单条尾通配路由** `get("/{name...}")`，把捕获的各段用 `/`
> 拼回一个相对路径再做白名单校验；`{name...}` 里的 `name` = `GET /api/debug-logs/files` 返回的
> `name` 字段原值（如 `2026-08-28/app.log`），**不是** `date` + `file` 两个独立参数。
> 2026-08-30 前本节写成 `{date}/{file}`，容易被读成两个参数、进而在客户端拼错。
>
> 客户端必须让斜杠**原样出现在 URL 里**，不要转义成 `%2F`：Ktor 解码后虽仍能匹配，但 `%2F`
> 会被很多反向代理规范化或拒绝，经内网穿透（frp / cloudflared）远程访问时就会单独坏掉。
> app 侧对应 `@Path(value = "name", encoded = true)`。

---

## QoS 诊断 /api/qos

> 需要认证。QoS 系统状态诊断（可选模块）。此接口豁免 QoS 限流。

#### `GET /api/qos/status`

完整 QoS 状态。

**响应：**
```json
{
  "enabled": true,
  "shell_qos": {
    "root": { "available": 5, "total": 5, "target": 5 },
    "normal": { "available": 10, "total": 10 },
    "cache": { "entries": 50, "ttl_ms": 5000 },
    "batch": { "total_batches": 12, "commands_saved": 30 }
  },
  "goform_qos": {
    "query_available": 10,
    "query_total": 10,
    "set_available": 5,
    "set_total": 5,
    "cache_size": 6,
    "cache_ttl_ms": 5000
  },
  "cpu_temp": 45000,
  "dynamic_pool": {
    "core": 4,
    "current": 4,
    "max": 8
  }
}
```

几个容易读错的地方：

- `enabled` 是**硬编码 `true`**，不反映配置项 `qos_enabled`。想知道开关真值读 `GET /api/config`；
- `shell_qos.batch` 是**累计计数**（`total_batches` / `commands_saved`），不是信号量余额，
  没有 `available` / `total`；
- `goform_qos` 来自 `GoformQoS.getStatus()`，除四个信号量字段外还有 `cache_size` / `cache_ttl_ms`；
- `cpu_temp` 是 `/sys/class/thermal/thermal_zone0/temp` 的**原始值**（该设备上是毫摄氏度，
  如 `45000`），读失败为 `0`。**别直接当摄氏度显示**，也别用 0 判"温度正常"；
- **没有 `frontend_safety` 字段**（前端并发闸早已移除）。

---

## 缓存管理 /api/cache

> 需要认证。服务端响应缓存管理。

#### `GET /api/cache/stats`

缓存统计信息。

**响应：**
```json
{
  "count": 12,
  "max_entries": 100,
  "any_cache_count": 5,
  "total_bytes_estimate": 34567,
  "stale": false,
  "entries": [
    { "key": "device:status", "age_ms": 1200, "ttl_ms": 3000, "expired": false, "size_bytes": 512 }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| count | int | 主缓存（JSON 响应）条目数 |
| max_entries | int | 主缓存容量上限，超出按最旧淘汰 |
| any_cache_count | int | 第二个缓存（`getOrPutAny` 存任意对象）的条目数，**不计入 `count`** |
| total_bytes_estimate | long | 只统计主缓存的 `size_bytes` 之和，是**估算**，且不含 `any_cache` |
| stale | boolean | 缓存被标记为可能过期（如 WS 客户端全部断开、内部刷新失败） |
| entries | array | 按 `age_ms` 倒序，**只回前 50 条**（`count > 50` 时被截断）。仅含主缓存，不含 `any_cache` |

#### `POST /api/cache/clear`

清空所有缓存。

**请求体：** 无

**响应：** `{ "success": true, "message": "Cache cleared" }`

#### `POST /api/cache/invalidate`

按 glob 模式失效缓存。

**请求体：**
```json
{ "pattern": "device:*" }
```

`pattern` 是 **glob**（`*` 通配），不是正则；缺省或非字符串时回落 `"*"`，也就是**等同 clear**（清空两个缓存）—— 传错字段名会误清全部。
非 `*` 模式会同时扫主缓存与 `any_cache`，命中即删。

**响应：** `{ "success": true, "pattern": "device:*" }`（`pattern` 回显的是生效值，可用来确认是否被回落成 `*`）

> 无论删掉几条都返回 `success: true`，响应里**不含删除条数**，模式写错不会有任何提示。

---

## 诊断 /api/diagnose

> 需要认证。服务器诊断信息。

#### `GET /api/diagnose`

综合诊断。

**查询参数：** `fields=1` —— 额外返回字段覆盖率（`field_coverage`）。**会逐分组向设备发查询**
（最多 10 组），只在排障时带，别放进轮询。

**响应：**
```json
{
  "server_time": 1234567890,
  "app_version": "0.1",
  "root": true,
  "adbd": "running",
  "mobile_data": "1",
  "gateway": "192.168.0.1",
  "device_profile": {
    "active": "zte-goform",
    "configured": "",
    "normalization_enabled": true,
    "status": "default"
  }
}
```

字段类型别照直觉猜：只有 `root` 是布尔，`adbd`（`getprop init.svc.adbd`）与 `mobile_data`
（`settings get global mobile_data`）都是**shell 原始输出字符串**，取不到时是 `"unknown"`；
`gateway` 有两级兜底 —— 解析不出默认路由时依次试 `dhcp.wlan0.gateway` / `dhcp.wlan.gateway`，
仍为空则**硬编码** `"192.168.0.1"`（所以这个值可能纯属猜测），整段抛异常才是 `"unknown"`；
`app_version` 目前是硬编码的 `"0.1"`，别拿它做版本判断（真版本走 `GET /api/update/status`）。

> 本端点会 fork 若干 root shell（`id` / `getprop` / `settings get` / `ip route`），不适合高频轮询。

`device_profile.status` 四态：
- `configured` —— 配了 `device_profile_id` 且注册表里有，生效的就是它；
- `default` —— 没配，用注册表默认 profile；
- `fallback` —— **配了但注册表里没有**，core 已静默回落默认（型号填错时看这里，不必翻启动日志）；
- `disabled` —— `field_normalization_enabled=false`，读侧原样透传设备字段。

带 `fields=1` 时追加（每个分组一段，只有字段名没有字段值，因此无需脱敏）：
```json
{
  "field_coverage": {
    "normalization_enabled": true,
    "profile_id": "zte-goform",
    "profile_name": "ZTE goform（F50 等）",
    "groups": {
      "TRAFFIC_LIMIT": {
        "queried": true,
        "registered": 10,
        "hit": 9,
        "missing": ["used_bytes"],
        "hit_source": { "enabled": "data_volume_limit_switch", "limit_value": "limit_value" }
      }
    }
  }
}
```

`missing` 就是适配新设备时的 TODO 清单：这些 canonical 字段登记了，但本机一个 source 都没命中。

> 覆盖率统计自身失败时，`field_coverage` 会退化成 `{ "error": "<原因>" }` —— 整个请求仍是 `200`，别按对象结构直接解 `groups`。

---

## WebSocket /ws/realtime

> 实时数据推送通道。

### 连接

**URL：** `ws://<host>:8088/ws/realtime`

**认证：握手参数全部走 query**（浏览器的 WebSocket API 不能自定义请求头，App 侧为保持
单一代码路径也走 query，**不支持 `Authorization` 头**）：

```
ws://<host>:8088/ws/realtime?token=<设备token>&ts=<毫秒时间戳>&nonce=<随机串>&sig=<签名>
```

签名对象是 `canonicalString("GET", <path>, ts, nonce)`，即：

```
GET \n /ws/realtime \n <ts> \n <nonce>
```

**只含 path，不含 query** —— 签名没法覆盖自己，把 query 纳入就得约定「去掉 sig 后再拼」，
两端各实现一遍这种剪裁规则是典型踩坑点；而 token / ts / nonce 本身都被服务端独立校验。

其余规则与 `/api/**` 一致：ts 容差 5 分钟、nonce 窗口内不可复用、签名可为 DER 或 raw r||s。

**连接限制：** 最多 4 个并发连接（`WsChannel.MAX_CONNECTIONS`）。超出以 `TRY_AGAIN_LATER`(1013)
关闭；鉴权失败以 `VIOLATED_POLICY`(1008) 关闭。web 端据这两个码区分「不要重连」与「稍后重试」。

**心跳：** pingPeriod=15s，timeout=30s，maxFrameSize=64KB。

### 消息格式

#### 连接成功（服务端 → 客户端）

```json
{
  "type": "connected",
  "data": { "message": "UFI-AXIS-Core WebSocket connected" },
  "timestamp": 1234567890
}
```

#### 订阅（客户端 → 服务端）

```json
{
  "subscribe": ["signal", "cpu", "traffic", "memory", "alert", "data_changed", "config_changed", "update"]
}
```

**替换语义**（收到即清空旧订阅再加）；增量移除用 `{"unsubscribe": [...]}`。
core 按频道严格过滤 —— **没订阅就永远收不到**。

可用频道（`WsChannel.ALL`）：`traffic`、`signal`、`cpu`、`memory`、`alert`、`notification`、
`data_changed`、`config_changed`、`update`。

- **前台 UI 不要订 `notification`**：core 对同一条告警先发 `notification` 再发 `alert`
  （同 payload 双发，为兼容旧客户端），两个都订会让一条告警响两次。UI 只认 `alert`；
  `notification` 留给 app 的 `:ufi_notify` 守护进程。
- **`battery` / `sms_contacts` 不是频道**，core 从不广播，订了只是白占位
  （守门脚本 `verify-api-contract.mjs` 会把订阅它们报成 P0）。电量走
  `GET /api/dashboard/summary`。

#### 取消订阅（客户端 → 服务端）

```json
{
  "unsubscribe": ["signal", "cpu"]
}
```

#### 数据推送（服务端 → 客户端）

```json
{
  "type": "<channel>",
  "data": { ... },
  "timestamp": 1234567890
}
```

### 推送频道说明

| 频道 | 数据内容 | 说明 |
|------|----------|------|
| `signal` | `{ rsrp, sinr, rsrq, rat, ... }` | 信号质量 |
| `cpu` | `{ usage_percent, core_count, cores, temperature }` | CPU 状态 |
| `traffic` | `{ rx_speed, tx_speed }` | 实时网速 |
| `battery` | `{ level, isCharging, temperature, voltage }` | 电池状态 |
| `memory` | `{ total, used, available, usage_percent }` | 内存状态 |
| `alert` | `{ type, level, message, value, threshold, timestamp }` | 告警通知 |
| `data_changed` | `{ changed: "wifi" / "device" / "sim" / "network" / "lan" / ... }` | 数据变更通知，前端应刷新对应模块 |

### 广播缓存

序列化 JSON 按类型缓存 500ms，避免高频广播时重复序列化。

### 陈旧检测

当所有 WebSocket 客户端断开时，数据标记为 `stale`（通过 `/health` 接口暴露）。新连接建立时清除陈旧标记。

---

## 接口统计

> 下面的数字会过期。**权威口径跑 `node scripts/verify-api-contract.mjs`**：
> 它从 core 路由、app Retrofit 注解、web 调用、本手册四个来源各自抽端点集合再交叉比对，
> 「本手册声明但 core 不存在」的必须为空。

守门脚本最近一次统计（2026-08-29，退出 0）：

| 来源 | 端点数 |
|------|--------|
| core 路由声明 | 209 |
| 本手册 | 198 |
| Android app | 180 |
| web 面板 | 184 |

（三侧比 core 少是正常的：core 有不少端点只被单侧消费，手册也不逐条列举静态资源与通配路由。）

### 按模块分布

下面是**人工估算**，加总与上表对不上，只用来快速定位模块规模，别当口径：

| 模块 | 端点数 |
|------|--------|
| /api/device | 30 |
| /api/network | 14 |
| /api/wifi | 10 |
| /api/files | 16 |
| /api/downloads | 15 |
| /api/apps | 10 |
| /api/sms | 10 |
| /api/system | 8 |
| /api/tasks | 7 |
| /api/rules | 7 |
| /api/update | 7 |
| /api/web | 6 |
| /api/tunnel | 25 |
| /api/alerts | 7 |
| /api/notifications | 2 |
| /api/sms-forward | 5 |
| /api/config | 4 |
| /api/pairing (认证) | 4 |
| /api/pairing/devices | 3 |
| /pairing (免认证) | 4 |
| /api/traffic | 3 |
| /api/cache | 3 |
| /api/monitor | 6 |
| /api/at | 3 |
| /api/shell | 3 |
| /api/sim | 1 |
| /api/debug-logs | 2 |
| /api/dashboard | 1 |
| /api/diagnose | 1 |
| /api/qos | 1 |
| /api/speedtest | 3 |
| /health | 1 |
| /, /{path...} | 2 |
| /ws/realtime | 1 |

---

## 文档幻影端点（已从本手册移除）

以下端点曾出现在本手册中，但 `core/` 从未实现过。由 `node scripts/verify-api-contract.mjs` 于 2026-08-26 检出并删除，登记在此避免再次被"补写"进文档或客户端：

- `GET /api/adb/status`、`GET /api/adb/ping`、`GET /api/adb/auto-start`、`POST /api/adb/start`、`POST /api/adb/stop`、`POST /api/adb/auto-start`
  - 替代方案：ADB / 特权 shell 的只读状态由 `/api/shell/root`（GET，返回 `root`/`uid`/`method`）与 `/api/diagnose`（GET，返回 `adbd`）提供，不新增路由。详见 `docs/APP-WEB-FIX-TASKS.md` §C07。
- `POST /api/device/usb-mode`
  - core 全仓无 `usb-mode` / `usb_mode` 实现；app 侧 `UfiAxisApi.kt` 也未声明该方法。USB 模式切换目前无后端能力。

以下 10 个端点由 2026-08-29 的阶段 4.1b 复核检出并删除（`core/api/.../DeviceRoutes.kt`
与 `WifiRoutes.kt` 的路由声明里都没有它们，app / web 也从未调用）。
**写替代方案时把动词写在路径后面**（如 “`/api/wifi/config`（POST）”）：
本小节里 `POST /api/x` 这种「动词+路径」写法会被守门脚本当成负面清单条目，
指向真实端点就会误报「负面清单已过期」。

- `GET /api/device/apn`、`POST /api/device/apn`、`POST /api/device/apn/switch`、`DELETE /api/device/apn/{index}`
  - APN 读写目前**无后端能力**。设备侧确实有 APN goform 字段（`GoformSignalClient` 的
    batch 3 会捎带 `apn_interface_version` 等），但没有任何 route 暴露它，也没有 `SettingKey` 写通道。
- `GET /api/device/sim-pin`
  - SIM/PIN 无专用端点。SIM 状态从 `/api/device/info`（GET）的 identity 段或
    `/api/dashboard/summary`（GET）的 `sim` 段读；`/api/sim` 下只有卡槽切换 `/api/sim/switch`（POST）。
- `GET /api/device/access-control`、`POST /api/device/access-control`
  - 这两个**路径**不存在。MAC 访问控制（拉黑）在 2026-08-30 落地了，但挂在 WiFi 段：
    读 `/api/wifi/acl`（GET），写 `/api/wifi/acl/block`、`/api/wifi/acl/unblock`、
    `/api/wifi/acl/clear`（POST）。已连设备列表读 `/api/wifi/clients`（GET）。
- `POST /api/device/tr069`
  - TR-069 无后端能力，也不在规划内（core 是本地网关，不接运营商 ACS）。
- `POST /api/device/hostname`
  - 改设备/客户端主机名无后端能力。`/api/wifi/clients`（GET）返回的 `hostname` 是**只读**的。
- `POST /api/device/telephony-reset`
  - core 无此路由。要执行 `pm clear com.android.providers.telephony` 走通用的 `/api/shell/exec`（POST）。
- `POST /api/wifi/adv-config`、`POST /api/wifi/guest`
  - 信道/模式/国家码、访客网络均无后端能力。
- `POST /api/wifi/chip`
  - 没有独立的切芯片端点；用 `/api/wifi/config`（POST）的 `chip_index`（`"chip1"` / `"chip2"`）。

**约束：** 新增端点必须先落到 `core/`，再更新本手册与客户端；`scripts/verify-api-contract.mjs` 的 P0 列表必须为空。
