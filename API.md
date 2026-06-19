# UFI-AXIS-Core API 文档

**版本:** v0.1  
**Base URL:** `http://<设备IP>:8088`  
**协议:** HTTP REST + WebSocket

---

## 认证机制

除 `/health` 和 `/ws/realtime` 外，所有接口均需要 Bearer Token 认证。

### 请求头

| Header | 必填 | 说明 |
|--------|------|------|
| `Authorization` | 是 | `Bearer ufi-axis-default-token` |
| `Content-Type` | POST/PUT 必填 | `application/json` |
| `X-Timestamp` | 否 | 毫秒时间戳，若提供则需在服务端时间 ±5 分钟内 |
| `X-Signature` | 否 | HMAC-SHA256 签名（配合 X-Timestamp 使用） |

### 认证失败响应

```json
// 缺少 Authorization 头
{"error": "Missing Authorization"}          // HTTP 401

// Token 无效
{"error": "Invalid token"}                  // HTTP 401

// 时间戳超出范围
{"error": "Timestamp out of range"}         // HTTP 401

// 频率限制（100次/分钟/IP）
{"error": "Rate limit exceeded"}            // HTTP 429
```

---

## 通用错误响应

```json
// 服务端异常
{"error": "Internal Server Error"}          // HTTP 500
{"error": "<具体异常信息>"}                   // HTTP 500
```

---

## 健康检查

### `GET /health`

无需认证。用于检测服务是否运行。

**响应:**
```json
{
  "status": "ok",
  "timestamp": "1781179601766"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | 固定 `"ok"` |
| `timestamp` | string | 服务端毫秒时间戳 |

---

## 设备信息

### `GET /api/device/info`

获取完整设备信息。

**响应:**
```json
{
  "device": {
    "brand": "REDMI",
    "model": "24122RKC7C",
    "device": "graceltexx",
    "manufacturer": "REDMI",
    "android_version": "14",
    "sdk_version": "34",
    "build_id": "UQ1A.240205.05262019 release-keys"
  },
  "sim": {
    "sim_state": "Ready",
    "phone_type": "GSM"
  },
  "storage": {
    "total": 26352496640,
    "available": 23331237888,
    "used": 3021258752,
    "usage_percent": 11.46
  },
  "uptime": {
    "uptime_seconds": 4141,
    "uptime_display": "0d 1h 9m"
  },
  "at_channel": {
    "connected": false
  },
  "kernel": "5.15.178+",
  "network": {
    "operator": "CHINA MOBILE",
    "type": "WiFi",
    "connected": true
  }
}
```

### `GET /api/device/model`

仅获取设备型号信息。

**响应:**
```json
{
  "brand": "REDMI",
  "model": "24122RKC7C",
  "device": "graceltexx",
  "manufacturer": "REDMI",
  "android_version": "14",
  "sdk_version": "34",
  "build_id": "UQ1A.240205.05262019 release-keys"
}
```

### `GET /api/device/magisk`

获取 Magisk 安装状态。

**响应:**
```json
{
  "installed": false,
  "version": "Not installed"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `installed` | boolean | 是否已安装 Magisk |
| `version` | string | Magisk 版本号，未安装时为 `"Not installed"` |

---

## 系统资源

### `GET /api/system/cpu`

获取 CPU 使用率和各核心频率。

**响应:**
```json
{
  "usage_percent": 2.42,
  "core_count": 6,
  "cores": [
    {"core": 0, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"},
    {"core": 1, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"},
    {"core": 2, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"},
    {"core": 3, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"},
    {"core": 4, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"},
    {"core": 5, "freq_mhz": 2865.6, "freq_display": "2.87 GHz"}
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `usage_percent` | number | CPU 总使用率（%） |
| `core_count` | number | CPU 核心数 |
| `cores` | array | 各核心信息 |
| `cores[].core` | number | 核心编号（从 0 开始） |
| `cores[].freq_mhz` | number | 当前频率（MHz） |
| `cores[].freq_display` | string | 频率显示文本 |

### `GET /api/system/memory`

获取内存信息。

**响应:**
```json
{
  "total": 6232883200,
  "available": 5108764672,
  "free": 4908195840,
  "buffers": 14827520,
  "cached": 312541184,
  "used": 1124118528,
  "usage_percent": 18.04
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `total` | number | 总内存（字节） |
| `available` | number | 可用内存（字节） |
| `free` | number | 空闲内存（字节） |
| `buffers` | number | Buffers（字节） |
| `cached` | number | Cached（字节） |
| `used` | number | 已用内存（字节） |
| `usage_percent` | number | 内存使用率（%） |

### `GET /api/system/battery`

获取电池信息。

**响应:**
```json
{
  "level": 85,
  "scale": 100,
  "percent": 85,
  "temperature": 35.0,
  "voltage": 3.6,
  "is_charging": true,
  "plugged": "AC"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `level` | number | 当前电量 |
| `scale` | number | 满电量值 |
| `percent` | number | 电量百分比（0-100） |
| `temperature` | number | 电池温度（°C） |
| `voltage` | number | 电池电压（V） |
| `is_charging` | boolean | 是否正在充电 |
| `plugged` | string | 充电方式：`"AC"` / `"USB"` / `"Wireless"` / `""` (未充电) |

### `GET /api/system/storage`

获取存储信息。

**响应:**
```json
{
  "total": 26352496640,
  "available": 23331237888,
  "used": 3021258752,
  "usage_percent": 11.46
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `total` | number | 总存储（字节） |
| `available` | number | 可用存储（字节） |
| `used` | number | 已用存储（字节） |
| `usage_percent` | number | 存储使用率（%） |

### `GET /api/system/uptime`

获取系统运行时间。

**响应:**
```json
{
  "uptime_seconds": 4141,
  "uptime_display": "0d 1h 9m"
}
```

---

## 流量统计

### `GET /api/traffic/realtime`

获取实时网速。

**响应:**
```json
{
  "rx_speed": 0,
  "tx_speed": 119,
  "rx_bytes": 87392710,
  "tx_bytes": 1103478,
  "rx_speed_display": "0 B/s",
  "tx_speed_display": "119 B/s",
  "timestamp": 1781179437739
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `rx_speed` | number | 下载速度（字节/秒） |
| `tx_speed` | number | 上传速度（字节/秒） |
| `rx_bytes` | number | 累计接收字节数 |
| `tx_bytes` | number | 累计发送字节数 |
| `rx_speed_display` | string | 下载速度（人类可读，如 `"1.5 MB/s"`） |
| `tx_speed_display` | string | 上传速度（人类可读） |
| `timestamp` | number | 采样时间戳（毫秒） |

> 注意：如果调度器尚未采集到数据，返回 `{"error": "No data yet"}`。

### `GET /api/traffic/history`

获取流量历史记录。

**查询参数:**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `hours` | number | `24` | 查询最近 N 小时的数据 |

**响应:**
```json
{
  "records": [
    {
      "id": 1,
      "rxBytes": 1024000,
      "txBytes": 512000,
      "rxSpeed": 1024,
      "txSpeed": 512,
      "timestamp": 1781179437739
    }
  ],
  "count": 1375,
  "period_hours": 24
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `records` | array | TrafficRecord 数组（按时间正序） |
| `records[].id` | number | 记录 ID |
| `records[].rxBytes` | number | 接收字节数 |
| `records[].txBytes` | number | 发送字节数 |
| `records[].rxSpeed` | number | 接收速度（字节/秒） |
| `records[].txSpeed` | number | 发送速度（字节/秒） |
| `records[].timestamp` | number | 记录时间戳 |
| `count` | number | 记录总数 |
| `period_hours` | number | 查询的时间范围（小时） |

### `GET /api/traffic/summary`

获取流量汇总。

**响应:**
```json
{
  "total_rx_bytes": 87392710,
  "total_tx_bytes": 1103478,
  "total_bytes": 88496188,
  "total_rx_display": "83.3 MB",
  "total_tx_display": "1.1 MB",
  "record_count": 1375
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `total_rx_bytes` | number | 总接收字节数 |
| `total_tx_bytes` | number | 总发送字节数 |
| `total_bytes` | number | 总流量字节数 |
| `total_rx_display` | string | 总接收（人类可读） |
| `total_tx_display` | string | 总发送（人类可读） |
| `record_count` | number | 数据库中记录总数 |

---

## 网络控制

### `GET /api/network/signal`

获取信号质量详情。优先从 AT 通道读取，AT 不可用时回退到 Android API。

**响应（AT 通道可用时）:**
```json
{
  "rsrp": -85,
  "sinr": 10,
  "rsrq": -8,
  "rssi": -65,
  "rat": "LTE"
}
```

**响应（AT 不可用，回退 Android API）:**
```json
{
  "operator": "CHINA MOBILE",
  "rat": "WiFi",
  "network_registered": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `rsrp` | number | 参考信号接收功率（dBm），越小信号越差 |
| `sinr` | number | 信噪比（dB） |
| `rsrq` | number | 参考信号接收质量（dB） |
| `rssi` | number | 接收信号强度（dBm） |
| `rat` | string | 网络制式：`"LTE"` / `"NR"` / `"WiFi"` 等 |
| `operator` | string | 运营商名称 |
| `network_registered` | boolean | 是否已注册网络 |

### `GET /api/network/status`

获取网络状态概览。

**响应:**
```json
{
  "network": {
    "is_connected": true,
    "has_internet": true,
    "has_cellular": false,
    "has_wifi": true
  },
  "mobile_data": false,
  "operator": "CHINA MOBILE",
  "network_type": "WiFi"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `network.is_connected` | boolean | 是否有网络连接 |
| `network.has_internet` | boolean | 是否可访问互联网 |
| `network.has_cellular` | boolean | 是否有蜂窝网络 |
| `network.has_wifi` | boolean | 是否有 WiFi |
| `mobile_data` | boolean | 移动数据是否开启 |
| `operator` | string | 运营商名称 |
| `network_type` | string | 当前网络类型：`"WiFi"` / `"4G"` / `"5G"` 等 |

### `POST /api/network/data`

开关移动数据。

**请求体:**
```json
{
  "enabled": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `enabled` | boolean | 是 | `true` 开启 / `false` 关闭 |

**响应:**
```json
{
  "success": true,
  "enabled": true
}
```

### `POST /api/network/airplane`

开关飞行模式。

**请求体:**
```json
{
  "enabled": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `enabled` | boolean | 是 | `true` 开启 / `false` 关闭 |

**响应:**
```json
{
  "success": true,
  "airplane_mode": true
}
```

### `POST /api/network/band`

锁定/解锁频段。

**请求体:**
```json
{
  "rat": "LTE",
  "earfcn": 100,
  "action": "lock"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `rat` | string | 是 | 无线接入类型：`"LTE"` / `"NR"` |
| `earfcn` | number | 是 | 频点号 |
| `action` | string | 否 | `"lock"`（默认）或 `"unlock"` |

**响应:**
```json
{
  "success": true,
  "action": "lock"
}
```

### `POST /api/network/mode`

设置首选网络模式。

**请求体:**
```json
{
  "mode": "AUTO"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `mode` | string | 是 | 网络模式，如 `"AUTO"` / `"LTE_ONLY"` / `"NR_ONLY"` 等 |

**响应:**
```json
{
  "success": true,
  "mode": "AUTO"
}
```

---

## SIM / 短信

### `GET /api/sim/info`

获取 SIM 卡信息。

**响应:**
```json
{
  "sim_state": "Ready",
  "phone_type": "GSM",
  "imei": "...",
  "imsi": "..."
}
```

> 具体字段取决于 AT 通道和 Android TelephonyManager 的可用性。

### `POST /api/sms/send`

发送短信。

**请求体:**
```json
{
  "phone": "10010",
  "message": "查询流量"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `phone` | string | 是 | 接收方手机号 |
| `message` | string | 是 | 短信内容 |

**响应:**
```json
// 成功
{"success": true, "phone": "10010"}           // HTTP 200

// 参数缺失
{"error": "phone and message are required"}   // HTTP 400

// 发送失败
{"success": false, "phone": "10010"}          // HTTP 500
```

### `GET /api/sms/list`

获取短信记录列表。

**查询参数:**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `limit` | number | `20` | 返回的最大记录数 |

**响应:**
```json
{
  "messages": [
    {
      "id": 1,
      "direction": "received",
      "phoneNumber": "10010",
      "content": "您的流量已使用...",
      "timestamp": 1781179437739
    }
  ],
  "count": 1
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `messages` | array | SmsRecord 数组 |
| `messages[].id` | number | 记录 ID |
| `messages[].direction` | string | 方向：`"sent"` / `"received"` |
| `messages[].phoneNumber` | string | 对方号码 |
| `messages[].content` | string | 短信内容 |
| `messages[].timestamp` | number | 时间戳 |
| `count` | number | 本次返回的记录数 |

### `POST /api/sim/ussd`

发送 USSD 指令。

**请求体:**
```json
{
  "code": "*100#"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `code` | string | 是 | USSD 代码 |

**响应:**
```json
{
  "code": "*100#",
  "response": "No response"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | string | 发送的 USSD 代码 |
| `response` | string | 运营商返回内容，无响应时为 `"No response"` |

---

## AT 指令

### `POST /api/at/command`

发送任意 AT 指令。

**请求体:**
```json
{
  "command": "ATI"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | string | 是 | AT 指令（含 `AT` 前缀） |

**响应:**
```json
// 成功
{
  "command": "ATI",
  "response": "Manufacturer: ...",
  "success": true
}

// AT 通道未连接
{"error": "AT channel not connected"}         // HTTP 503

// 参数缺失
{"error": "command is required"}              // HTTP 400
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | string | 发送的指令 |
| `response` | string | 模块返回内容，无响应时为 `"No response"` |
| `success` | boolean | 是否收到响应 |

### `GET /api/at/status`

获取 AT 通道状态。

**响应:**
```json
{
  "connected": false,
  "platform": {
    "connected": false
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `connected` | boolean | AT 通道是否已连接 |
| `platform.connected` | boolean | 同上 |

### `GET /api/at/platform`

获取平台信息。

**响应:** 同 `GET /api/at/status` 中 `platform` 字段的内容。

---

## 告警管理

### `GET /api/alerts/config`

获取当前告警配置。

**响应:**
```json
{
  "enabled": true,
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

| 字段 | 类型 | 说明 |
|------|------|------|
| `enabled` | boolean | 告警总开关 |
| `temperatureWarning` | number | 温度警告阈值（°C） |
| `temperatureCritical` | number | 温度严重告警阈值（°C） |
| `batteryWarning` | number | 电量警告阈值（%） |
| `batteryCritical` | number | 电量严重告警阈值（%） |
| `trafficWarningMb` | number | 流量警告阈值（MB） |
| `trafficCriticalMb` | number | 流量严重告警阈值（MB） |
| `signalWarningRsrp` | number | 信号警告阈值（dBm） |
| `signalCriticalRsrp` | number | 信号严重告警阈值（dBm） |

### `PUT /api/alerts/config`

更新告警配置。

**请求体:**
```json
{
  "enabled": true,
  "temperatureWarning": 50.0,
  "temperatureCritical": 60.0,
  "batteryWarning": 25,
  "batteryCritical": 15,
  "trafficWarningMb": 2048,
  "trafficCriticalMb": 4096,
  "signalWarningRsrp": -95,
  "signalCriticalRsrp": -110
}
```

**响应:**
```json
{
  "success": true,
  "config": {
    "enabled": true,
    "temperatureWarning": 50.0,
    "temperatureCritical": 60.0,
    "batteryWarning": 25,
    "batteryCritical": 15,
    "trafficWarningMb": 2048,
    "trafficCriticalMb": 4096,
    "signalWarningRsrp": -95,
    "signalCriticalRsrp": -110
  }
}
```

### `GET /api/alerts/list`

获取告警记录列表。

**查询参数:**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `limit` | number | `20` | 返回的最大记录数 |

**响应:**
```json
{
  "alerts": [
    {
      "id": 1,
      "type": "temperature",
      "level": "warning",
      "message": "设备温度偏高: 47.5°C",
      "value": "47.5",
      "threshold": "45.0",
      "acknowledged": false,
      "timestamp": 1781179437739
    }
  ],
  "count": 1
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `alerts` | array | AlertRecord 数组 |
| `alerts[].id` | number | 记录 ID |
| `alerts[].type` | string | 告警类型（见下表） |
| `alerts[].level` | string | 告警级别：`"info"` / `"warning"` / `"critical"` |
| `alerts[].message` | string | 告警描述 |
| `alerts[].value` | string | 触发告警的实际值 |
| `alerts[].threshold` | string | 告警阈值 |
| `alerts[].acknowledged` | boolean | 是否已确认 |
| `alerts[].timestamp` | number | 时间戳 |
| `count` | number | 本次返回的记录数 |

**告警类型:**

| type | 说明 |
|------|------|
| `temperature` | 设备温度过高 |
| `battery` | 电池电量低 |
| `traffic` | 流量超额 |
| `signal` | 信号质量差 |
| `connectivity` | 网络断连 |

### `POST /api/alerts/ack`

确认（Acknowledged）一条告警记录。

**请求体:**
```json
{
  "id": 1
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | number | 是 | 告警记录 ID |

**响应:**
```json
{"success": true, "id": 1}
```

---

## WiFi 管理

### `POST /api/wifi/ssid`

修改热点 SSID。

**请求体:**
```json
{
  "ssid": "MyHotspot",
  "password": "newpassword"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ssid` | string | 是 | 新的 SSID 名称 |
| `password` | string | 否 | 新的密码（不传则不修改密码） |

**响应:**
```json
{"success": true, "ssid": "MyHotspot"}
```

### `POST /api/wifi/password`

修改 WiFi 密码。

**请求体:**
```json
{
  "password": "newpassword123",
  "encryption": "WPA2-PSK"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `password` | string | 是 | 新密码 |
| `encryption` | string | 否 | 加密方式，默认 `"WPA2-PSK"` |

**响应:**
```json
{"success": true}
```

### `GET /api/wifi/settings`

获取当前 WiFi 设置。

**响应:**
```json
{
  "settings": "{}"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `settings` | string | WiFi 设置 JSON 字符串（Goform 接口返回） |

### `GET /api/wifi/clients`

获取已连接的客户端列表。

**响应:**
```json
{
  "clients": "[]"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `clients` | string | 客户端列表 JSON 字符串（Goform 接口返回） |

---

## 配置管理

后端核心配置项可通过 API 读取和修改。认证类配置（Token/Secret/端口）修改后即时生效（下次请求即使用新值），但需重启 HTTP 服务才能让端口变更生效。

### `GET /api/config`

获取当前全部配置（Secret 脱敏显示）。

**响应:**
```json
{
  "token": "ufi-axis-default-token",
  "secret": "uf*******************et",
  "port": 8088,
  "auto_start_on_boot": true,
  "rate_limit_max": 100,
  "rate_limit_window_sec": 60
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `token` | string | 当前 Bearer Token |
| `secret` | string | HMAC Secret（脱敏，仅首尾各 2 字符可见） |
| `port` | number | HTTP 服务监听端口 |
| `auto_start_on_boot` | boolean | 是否开机自动启动服务 |
| `rate_limit_max` | number | 频率限制：窗口内最大请求数 |
| `rate_limit_window_sec` | number | 频率限制：窗口时长（秒） |

### `PUT /api/config`

更新配置（支持部分更新，仅传需要修改的字段）。

**请求体:**
```json
{
  "token": "my-new-token",
  "secret": "my-new-secret",
  "port": 9090,
  "auto_start_on_boot": false,
  "rate_limit_max": 200,
  "rate_limit_window_sec": 120
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `token` | string | 否 | 新 Token |
| `secret` | string | 否 | 新 Secret |
| `port` | number | 否 | 新端口号（1024-65535） |
| `auto_start_on_boot` | boolean | 否 | 开机自启 |
| `rate_limit_max` | number | 否 | 频率限制上限 |
| `rate_limit_window_sec` | number | 否 | 频率限制窗口 |

**响应:**
```json
{
  "success": true,
  "updated_fields": ["token", "rate_limit_max"],
  "needs_restart": true,
  "hint": "修改了认证或端口配置，需重启服务生效"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 是否成功 |
| `updated_fields` | array | 本次实际更新的字段列表 |
| `needs_restart` | boolean | 是否需要重启服务 |
| `hint` | string | 提示信息 |

> **注意：** 如果修改了 Token，当前请求返回后，后续请求需立即使用新 Token。

### `POST /api/config/reset`

恢复全部配置为默认值。

**响应:**
```json
{
  "success": true,
  "message": "已恢复默认配置，需重启服务生效"
}
```

**默认值:**

| 配置项 | 默认值 |
|--------|--------|
| `token` | `ufi-axis-default-token` |
| `secret` | `ufi-axis-default-secret` |
| `port` | `8088` |
| `auto_start_on_boot` | `true` |
| `rate_limit_max` | `100` |
| `rate_limit_window_sec` | `60` |

---

## WebSocket 实时推送

### `ws://<设备IP>:8088/ws/realtime`

无需认证。连接后服务端自动推送实时数据。

**连接后可发送订阅消息:**
```json
{
  "action": "subscribe",
  "topics": ["signal", "cpu", "traffic", "battery", "alert"]
}
```

**推送数据格式:**
```json
{
  "topic": "signal",
  "data": { ... },
  "timestamp": 1781179437739
}
```

**推送主题:**

| topic | 推送频率 | 数据内容 |
|-------|----------|----------|
| `signal` | 3 秒 | 信号强度（RSRP/SINR 等） |
| `cpu` | 3 秒 | CPU 使用率和核心频率 |
| `traffic` | 1 秒 | 实时网速 |
| `battery` | 5 秒 | 电池状态 |
| `alert` | 实时 | 告警事件触发时立即推送 |

---

## 端点速查表

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/health` | 否 | 健康检查 |
| WS | `/ws/realtime` | 否 | WebSocket 实时推送 |
| GET | `/api/device/info` | 是 | 完整设备信息 |
| GET | `/api/device/model` | 是 | 设备型号 |
| GET | `/api/device/magisk` | 是 | Magisk 状态 |
| GET | `/api/system/cpu` | 是 | CPU 信息 |
| GET | `/api/system/memory` | 是 | 内存信息 |
| GET | `/api/system/battery` | 是 | 电池信息 |
| GET | `/api/system/storage` | 是 | 存储信息 |
| GET | `/api/system/uptime` | 是 | 运行时间 |
| GET | `/api/traffic/realtime` | 是 | 实时网速 |
| GET | `/api/traffic/history` | 是 | 流量历史 |
| GET | `/api/traffic/summary` | 是 | 流量汇总 |
| GET | `/api/network/signal` | 是 | 信号详情 |
| GET | `/api/network/status` | 是 | 网络状态 |
| POST | `/api/network/data` | 是 | 开关移动数据 |
| POST | `/api/network/airplane` | 是 | 开关飞行模式 |
| POST | `/api/network/band` | 是 | 锁频/解锁 |
| POST | `/api/network/mode` | 是 | 网络模式 |
| GET | `/api/sim/info` | 是 | SIM 卡信息 |
| POST | `/api/sms/send` | 是 | 发送短信 |
| GET | `/api/sms/list` | 是 | 短信列表 |
| POST | `/api/sim/ussd` | 是 | USSD 查询 |
| POST | `/api/at/command` | 是 | 发送 AT 指令 |
| GET | `/api/at/status` | 是 | AT 通道状态 |
| GET | `/api/at/platform` | 是 | 平台信息 |
| GET | `/api/alerts/config` | 是 | 获取告警配置 |
| PUT | `/api/alerts/config` | 是 | 更新告警配置 |
| GET | `/api/alerts/list` | 是 | 告警记录列表 |
| POST | `/api/alerts/ack` | 是 | 确认告警 |
| POST | `/api/wifi/ssid` | 是 | 修改热点 SSID |
| POST | `/api/wifi/password` | 是 | 修改 WiFi 密码 |
| GET | `/api/wifi/settings` | 是 | WiFi 设置 |
| GET | `/api/wifi/clients` | 是 | 已连接客户端 |
| GET | `/api/config` | 是 | 获取全部配置 |
| PUT | `/api/config` | 是 | 更新配置 |
| POST | `/api/config/reset` | 是 | 恢复默认配置 |
