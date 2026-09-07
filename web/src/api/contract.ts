/**
 * core:contract 的 web 镜像 —— 与 `core/contract/src/main/kotlin/com/ufi_axis_core/contract/` 一一对应。
 *
 * **改动这里必须同步改 Kotlin 侧，反之亦然。**
 * 校验器：`node scripts/verify-api-contract.mjs`（端点差集 + WS 频道差集，P0 必须为空）。
 *
 * 为什么是手抄而不是代码生成：当前没有 exportContract → gen-types 链路（T03 第 4 步，可延后）。
 * 手抄的代价由校验器兜住 —— 端点写错会被报成 P0。
 */

/**
 * 端点路径（**带前导斜杠**，与 axios 调用现状一致）。
 *
 * 只列**真实存在的端点**，不列模块前缀（形如 api + 模块名 的前缀不是端点，
 * 写进来会被校验器当成"客户端声明了不存在的端点"报 P0）。
 */
export const Endpoints = {
  alerts: {
    list: '/api/alerts/list',
    config: '/api/alerts/config',
    ack: '/api/alerts/ack',
    ackAll: '/api/alerts/ack-all',
    ackResolved: '/api/alerts/ack-resolved',
    delete: '/api/alerts/delete',
  },
  config: {
    root: '/api/config',
    version: '/api/config/version',
    reset: '/api/config/reset',
  },
  monitor: {
    history: '/api/monitor/history',
    storage: '/api/monitor/storage',
    clean: '/api/monitor/clean',
    control: '/api/monitor/control',
  },
  /**
   * 后台服务控制（2026-08-26 新增）。
   * stop 只停数据采集与告警检测，HTTP 服务不停 → start 永远可达；
   * restart 重启后端服务（Service 组件重建，非进程级），接口中断约 10 秒。
   */
  service: {
    status: '/api/service/status',
    start: '/api/service/start',
    stop: '/api/service/stop',
    restart: '/api/service/restart',
    autostart: '/api/service/autostart',
  },
  network: {
    status: '/api/network/status',
    /** 入参走 NetworkMode 的别名集，core 会映射成 BearerPreference 再下发 */
    mode: '/api/network/mode',
    band: '/api/network/band',
    bandStatus: '/api/network/band-status',
    connectionMode: '/api/network/connection-mode',
  },
  shell: {
    /** { root, uid, method } —— 替代不存在的 adb 状态端点 */
    root: '/api/shell/root',
  },
  tasks: {
    root: '/api/tasks',
    rules: '/api/rules',
  },
  /**
   * 邮件通知（2026-08-27 web 接入；2026-08-29 由「短信转发」改名，只剩 SMTP 一种通道）。
   * 路径**保持 `sms-forward` 不变**：app 与 API 手册都按它引用，改名只会破坏跨端契约。
   * 写操作是 **POST 而非 PUT**；`POST /config` 是字段级合并，凭据字段传空串等于不传
   * （保留原值，无法用该端点清空）。
   * `POST /test` 用的是已持久化的配置，未保存的改动不生效；且失败也是 HTTP 200 + `{success:false,error}`。
   * 另有 app 专用的 `POST /api/sms-forward/notify`（通知转邮件），web 不使用，故不在此登记。
   */
  smsForward: {
    config: '/api/sms-forward/config',
    diagnose: '/api/sms-forward/diagnose',
    test: '/api/sms-forward/test',
  },
  /**
   * SIM 卡（2026-08-27 web 接入）。
   * 本组只有 switch 一条：core 原先那个「SIM 信息」查询端点与 dashboard summary 的
   * device_info.identity 同源却多 15 分钟缓存、phone_type 恒为 GSM、sim_state 更粗糙，
   * 两端都不该用，T40-15 已在 core 侧删除。
   * PIN/PUK 状态查询端点同样已在 core 侧删除，web 不再展示 PIN 信息。
   */
  sim: {
    switch: '/api/sim/switch',
  },
  /**
   * 后端 APK 自更新（2026-08-27 web 接入）与前端 ZIP 更新是**两套独立通道**：
   * update 组管 core APK，web 组管 web 资源，各有自己的 status/state 机。
   *
   * 注意：本文件里描述端点时不要用反引号包 api 路径通配写法（形如 update 斜杠星号），
   * 契约校验器会把带引号的 api 路径字面量当成「客户端声明了该端点」，通配路径必然报 P0。
   *
   * 两处坑：①update 组的失败信封是真实 HTTP 码 + `{success,ok,error,message,code}`，
   * 而 web 组只回 `{error}`；②安装 core 时进程会自杀重启，轮询失败属预期，不能当错误。
   * frontendInfo 是「手机 App 安装包」信息（读清单，缓存 5 分钟）；core 另有一个带鉴权的
   * APK 代理下载端点，web 用不了（`<a download>` 带不上 Bearer 头），所以 web 直接给
   * 清单里的 `apk_url` 上游直链。
   *
   * 第三处坑（2026-09-06）：**check 不是「检查」**，它是「检查+下载+校验+安装+重启」一条龙，
   * 点下去就直接开装。要"先看看有没有新版"必须打 backendInfo（只读清单 + 版本比对，
   * 不碰 core 的更新状态机），确认有新版并让用户二次确认后才允许打 check。
   */
  update: {
    check: '/api/update/check',
    status: '/api/update/status',
    upload: '/api/update/upload',
    installLocal: '/api/update/install-local',
    reset: '/api/update/reset',
    frontendInfo: '/api/update/frontend-info',
    /** 只检查 core 自身版本：{ current_version, latest_version, has_update, changelog, apk_url, apk_size, sha256 } */
    backendInfo: '/api/update/backend-info',
  },
  webAssets: {
    check: '/api/web/check',
    status: '/api/web/status',
    version: '/api/web/version',
    update: '/api/web/update',
    rollback: '/api/web/rollback',
    clear: '/api/web/clear',
  },
  dashboard: {
    summary: '/api/dashboard/summary',
  },
  diagnose: '/api/diagnose',
  wsRealtime: '/ws/realtime',
  /**
   * 存活探测（**免鉴权**，不在 /api 鉴权块内）：`{ status: 'ok', timestamp, ws_* , cache_stale }`。
   * 用途：core 自更新会重启进程，用它判断「重启后是否已就绪」——此期间连接失败属预期。
   */
  health: '/health',
} as const;

/**
 * 负面清单：**core 从不存在的端点**，任何一端都禁止声明。
 * 详见 `docs/UFI-AXIS-Core-API-Reference.md` 末尾「文档幻影端点」小节。
 *
 * 这里故意**不写完整路径**（不带 api 前缀）：契约校验器会把源码里的 api 路径
 * 字面量当成"客户端声明了该端点"，负面清单自己写全路径反而会被报成 P0。
 */
export const PHANTOM_ENDPOINT_SUFFIXES = [
  'adb/status',
  'adb/ping',
  'adb/auto-start',
  'adb/start',
  'adb/stop',
  'device/usb-mode',
] as const;

/** 定时任务 / 自动化规则动作类型（权威来源：core ActionExecutor.VALID_ACTION_TYPES）。 */
export const ActionType = {
  DATA_TOGGLE: 'data_toggle',
  WIFI_TOGGLE: 'wifi_toggle',
  AIRPLANE_TOGGLE: 'airplane_toggle',
  REBOOT: 'reboot',
  SHUTDOWN: 'shutdown',
  LED_TOGGLE: 'led_toggle',
  PERFORMANCE_MODE: 'performance_mode',
  ROAMING_TOGGLE: 'roaming_toggle',
  NETWORK_MODE: 'network_mode',
  CUSTOM_SHELL: 'custom_shell',
} as const;

export const ACTION_TYPES_ALL: string[] = Object.values(ActionType);

/**
 * WebSocket 频道。
 * 订阅报文 `{ subscribe: [...] }` 是**替换语义**；core 按频道严格过滤，没订阅就收不到。
 * 最大连接数 4（App + Web 共享），超限以 1013 关闭。
 */
export const WsChannel = {
  TRAFFIC: 'traffic',
  SIGNAL: 'signal',
  CPU: 'cpu',
  MEMORY: 'memory',
  ALERT: 'alert',
  NOTIFICATION: 'notification',
  DATA_CHANGED: 'data_changed',
  CONFIG_CHANGED: 'config_changed',
  UPDATE: 'update',
} as const;

export const WS_CHANNELS_ALL: string[] = Object.values(WsChannel);

/**
 * 网络模式（T15）：与 Kotlin `NetworkMode` 对齐。
 *
 * 两个取值域必须分清：
 * - **别名集**（客户端提交给 `POST /api/network/mode` 与定时任务 `network_mode.mode`，大小写不敏感）；
 * - **BearerPreference**（设备实际取值，大小写敏感，如 `Only_5G`）—— 只出现在
 *   `GET /api/device/settings` 的回读与 `POST /api/network/mode` 响应的 `bearer` 字段里。
 * 客户端**不要**直接提交 BearerPreference。
 */
export const NetworkMode = {
  AUTO: 'AUTO',
  ONLY_5G: '5G_ONLY',
  LTE_AND_5G: 'LTE_AND_5G',
  ONLY_LTE: 'ONLY_LTE',
  WCDMA_AND_LTE: 'WCDMA_AND_LTE',
  ONLY_WCDMA: 'WCDMA_ONLY',
} as const;

/** UI 档位（顺序即展示顺序），与 Kotlin `NetworkMode.UI_OPTIONS` 一致。 */
export const NetworkModeOptions: ReadonlyArray<{ label: string; value: string }> = [
  { label: '自动', value: NetworkMode.AUTO },
  { label: '仅 5G', value: NetworkMode.ONLY_5G },
  { label: '5G 优先', value: NetworkMode.LTE_AND_5G },
  { label: '仅 4G', value: NetworkMode.ONLY_LTE },
  { label: '4G / 3G', value: NetworkMode.WCDMA_AND_LTE },
  { label: '仅 3G', value: NetworkMode.ONLY_WCDMA },
];

/** 设备回读的 BearerPreference → 别名（上面映射的反向）。 */
export const BearerToNetworkMode: Record<string, string> = {
  WL_AND_5G: NetworkMode.AUTO,
  Only_5G: NetworkMode.ONLY_5G,
  LTE_AND_5G: NetworkMode.LTE_AND_5G,
  Only_LTE: NetworkMode.ONLY_LTE,
  WCDMA_AND_LTE: NetworkMode.WCDMA_AND_LTE,
  Only_WCDMA: NetworkMode.ONLY_WCDMA,
};

/**
 * 前台 UI 订阅集合 = 全集去掉 `notification`。

 * core 对同一条告警会 `notification` + `alert` 双发（兼容旧客户端），
 * 两个都订会让同一告警被处理两次。与 Kotlin 侧 `WsChannel.UI_TOPICS` 对齐。
 */
export const WS_UI_TOPICS: string[] = WS_CHANNELS_ALL.filter((c) => c !== WsChannel.NOTIFICATION);

/** **禁止订阅**：core 从不广播。电量走 /api/dashboard/summary REST。 */
export const WS_NEVER_BROADCAST = ['battery', 'sms_contacts'] as const;

export const WS_MAX_CONNECTIONS = 4;

/** 告警类型 / 级别（注意：core 侧目前**无白名单校验**，这是约定而非强制）。 */
export const AlertType = ['temperature', 'battery', 'traffic', 'signal', 'connectivity'] as const;
export const AlertLevel = ['info', 'warning', 'critical'] as const;

/** /api/alerts/list 的钳制：limit 1..200，默认 50；cursor 是 base64 `ts.id`，向更早翻页。 */
export const AlertListLimits = { min: 1, max: 200, default: 50 } as const;
export const clampAlertLimit = (n: number): number =>
  Math.min(AlertListLimits.max, Math.max(AlertListLimits.min, Math.trunc(n)));

/**
 * /api/config 取值范围。C03 之后 core 会把越界字段回报到 `rejected_fields`（带 min/max），
 * 但客户端仍应先钳制输入框，避免用户提交注定被拒的值。
 */
export const ConfigLimits = {
  port: [1024, 65535],
  goformPort: [1, 65535],
  qosShellMaxConcurrent: [1, 10],
  qosCacheTtlMs: [500, 30000],
  qosGoformQueryMax: [1, 8],
  qosGoformSetMax: [1, 4],
  smsCodeCleanupHours: [0, 720],
} as const;

/** `PUT /api/config` 响应 `rejected_fields[].reason`，与 Kotlin `ErrorCode` 同名同值。 */
export const ConfigRejectReason = {
  OUT_OF_RANGE: 'OUT_OF_RANGE',
  MASKED_VALUE: 'MASKED_VALUE',
  BLANK_VALUE: 'BLANK_VALUE',
  WRONG_TYPE: 'WRONG_TYPE',
} as const;

export interface ConfigRejectedField {
  field: string;
  reason: string;
  min?: number;
  max?: number;
}

/** 把 rejected_fields 条目翻译成可直接展示的中文原因。 */
export function describeConfigReject(r: ConfigRejectedField): string {
  switch (r.reason) {
    case ConfigRejectReason.OUT_OF_RANGE:
      return `${r.field}：取值需在 ${r.min} ~ ${r.max} 之间`;
    case ConfigRejectReason.MASKED_VALUE:
      return `${r.field}：不能回写脱敏值（含 ***）`;
    case ConfigRejectReason.BLANK_VALUE:
      return `${r.field}：不能为空`;
    case ConfigRejectReason.WRONG_TYPE:
      return `${r.field}：值类型不正确`;
    default:
      return `${r.field}：${r.reason}`;
  }
}

/** 单位与哨兵值约定，与 Kotlin 侧 Units.kt 对齐。 */
export const Units = {
  /** 时间戳全链路 ms，**不要再 * 1000** */
  timestamp: 'ms',
  /** rx_speed / tx_speed 是 bytes/s；Mbps = *8/1e6 */
  trafficSpeed: 'bytes/s',
  /** 下载进度 0..1（不是 0..100），-1 = 元数据阶段进度未知 */
  downloadProgressUnknown: -1,
  /** totalSize -1 = 未知 */
  downloadTotalSizeUnknown: -1,
  /** 温度已由 core 换算为 °C；电池电压已换算为 V；电量 -1 = 未知 */
  batteryPercentUnknown: -1,
  /** 测速 ckSize = 1MiB 块数，默认 10，钳制 1..4096 */
  speedtestChunks: { default: 10, min: 1, max: 4096 },
  /** /api/debug-logs 返回字符串数组 */
  debugLogsShape: 'string[]',
} as const;

/**
 * 失败信封：**HTTP 200 也可能是失败**（`{ success: false }` / `{ ok: false, error }`）。
 * core 尚未统一（C01 会做 ok()/fail()），客户端两种键都要认。
 */
export const isFailure = (body: any): boolean =>
  body != null && typeof body === 'object' && (body.success === false || body.ok === false);

/**
 * 设备数据字段契约 —— 与 Kotlin 侧 `DeviceFields.kt` 一一对应的手抄镜像。
 *
 * 这是「不管设备（goform）侧字段怎么变，core 对外始终返回这些 key」的冻结清单。
 * 设备侧的可变性由 core 的 `:core:device-schema` / `DeviceProfile` 吸收，
 * **前端只跟 core 打交道，永远不直接请求 goform**。
 *
 * 三条约定（与 Kotlin 侧同源）：
 * 1. 既有 key 一律不改名（命名混乱是从透传时代继承的历史债，冻结即接受）
 * 2. 新增 key 必须 snake_case
 * 3. 字段缺失 = 该 key 不出现，而不是 `null`
 *
 * 每个分组的 `all` 数组会被 `scripts/verify-api-contract.mjs` 与 Kotlin 侧的
 * `ALL` 列表逐项比对，漂移会报 P0。
 */
export const DeviceFields = {
  version: 'v1',

  /** GET /api/device/settings —— 值域全是字符串，布尔见 bool */
  deviceSettings: {
    indicatorLight: 'indicator_light_switch',
    performanceMode: 'performance_mode',
    samba: 'samba_switch',
    usbPort: 'usb_port_switch',
    restartSchedule: 'restart_schedule_switch',
    restartTime: 'restart_time',
    wifiSleepIdleMinutes: 'sleep_sysIdleTimeToSleep',
    bearerPreference: 'BearerPreference',
    netSelect: 'net_select',
    connectionMode: 'connection_mode',
    roam: 'roam_setting_option',
    dialRoam: 'dial_roam_setting_option',
    /** FOTA 自动检查更新：'1' = 开，'0' = 关（与写侧 auto_update 同向） */
    fotaAutoUpdate: 'UpgMode',
    all: [
      'indicator_light_switch',
      'performance_mode',
      'samba_switch',
      'usb_port_switch',
      'restart_schedule_switch',
      'restart_time',
      'sleep_sysIdleTimeToSleep',
      'BearerPreference',
      'net_select',
      'connection_mode',
      'roam_setting_option',
      'dial_roam_setting_option',
      'UpgMode',
    ],
  },

  /** GET /api/device/lan-settings —— dhcpLease_hour 单位是小时，需 *3600 */
  lanSettings: {
    lanIp: 'lan_ipaddr',
    lanNetmask: 'lan_netmask',
    macAddress: 'mac_address',
    dhcpEnabled: 'dhcpEnabled',
    dhcpStart: 'dhcpStart',
    dhcpEnd: 'dhcpEnd',
    dhcpLease: 'dhcpLease',
    dhcpLeaseHour: 'dhcpLease_hour',
    mtu: 'mtu',
    tcpMss: 'tcp_mss',
    all: [
      'lan_ipaddr',
      'lan_netmask',
      'mac_address',
      'dhcpEnabled',
      'dhcpStart',
      'dhcpEnd',
      'dhcpLease',
      'dhcpLease_hour',
      'mtu',
      'tcp_mss',
    ],
  },

  /** GET /api/wifi/settings —— broadcastSsid 的 '1' 表示**隐藏** */
  wifiSettings: {
    chip: 'wifi_chip',
    ssid: 'wifi_chip1_ssid1_ssid',
    passphrase: 'wifi_chip1_ssid1_passphrase',
    authMode: 'wifi_chip1_ssid1_auth_mode',
    encryptType: 'wifi_chip1_ssid1_encryp_type',
    broadcastSsid: 'wifi_chip1_ssid1_broadcast_ssid',
    maxStaNum: 'wifi_chip1_ssid1_max_sta_num',
    moduleSwitch: 'WiFiModuleSwitch',
    /** @deprecated core 不再输出（已归一到 moduleSwitch），阶段 4.1 起响应里没有它 */
    enable: 'wifi_enable',
    /** @deprecated 同 enable */
    onoffState: 'wifi_onoff_state',
    all: [
      'wifi_chip',
      'wifi_chip1_ssid1_ssid',
      'wifi_chip1_ssid1_passphrase',
      'wifi_chip1_ssid1_auth_mode',
      'wifi_chip1_ssid1_encryp_type',
      'wifi_chip1_ssid1_broadcast_ssid',
      'wifi_chip1_ssid1_max_sta_num',
      'WiFiModuleSwitch',
    ],
  },

  /** GET /api/wifi/clients —— 值可能是数组，也可能是数组的 JSON 字符串，两种都要解 */
  wifiClients: {
    stationList: 'station_list',
    lanStationList: 'lan_station_list',
    itemHostname: 'hostname',
    itemIp: 'ip_addr',
    itemMac: 'mac_addr',
    all: ['station_list', 'lan_station_list'],
    itemAll: ['hostname', 'ip_addr', 'mac_addr'],
  },

  /** GET /api/network/band-status —— 值是纯数字逗号串，'0'/'all' = 未锁定 */
  bandStatus: {
    lteBandLock: 'lte_band_lock',
    nrBandLock: 'nr_band_lock',
    unlockedValues: ['0', 'all'],
    all: ['lte_band_lock', 'nr_band_lock'],
  },

  /** GET /api/network/cell-info · /api/network/neighbor-cells */
  cellInfo: {
    neighborCellInfo: 'neighbor_cell_info',
    lockedCellInfo: 'locked_cell_info',
    ltePci: 'Lte_pci',
    lteEarfcn: 'Lte_fcn',
    lteBands: 'Lte_bands',
    lteRsrp: 'lte_rsrp',
    lteRsrq: 'lte_rsrq',
    lteSnr: 'lte_snr',
    itemPci: 'pci',
    itemEarfcn: 'earfcn',
    itemRsrp: 'rsrp',
    itemRsrq: 'rsrq',
    itemSinr: 'sinr',
    itemRat: 'rat',
    all: [
      'neighbor_cell_info',
      'locked_cell_info',
      'Lte_pci',
      'Lte_fcn',
      'Lte_bands',
      'lte_rsrp',
      'lte_rsrq',
      'lte_snr',
    ],
    itemAll: ['pci', 'earfcn', 'rsrp', 'rsrq', 'sinr', 'rat'],
  },

  /**
   * GET /api/device/traffic-limit
   * 设备侧的复合串（'470_1024' = 470 GB）与恒为 'MB' 的 unit 字段已被 core 吃掉（core 2.8）：
   * 读用 limit_value / limit_unit_display / limit_bytes，写用 limit_value + limit_unit。
   */
  trafficLimit: {
    enabled: 'enabled',
    limitValue: 'limit_value',
    limitUnitDisplay: 'limit_unit_display',
    limitBytes: 'limit_bytes',
    alertPercent: 'alert_percent',
    autoClear: 'auto_clear',
    clearDate: 'clear_date',
    usedBytes: 'used_bytes',
    monthlyRxBytes: 'monthly_rx_bytes',
    monthlyTxBytes: 'monthly_tx_bytes',
    monthlyTime: 'monthly_time',
    all: [
      'enabled',
      'limit_value',
      'limit_unit_display',
      'limit_bytes',
      'alert_percent',
      'auto_clear',
      'clear_date',
      'used_bytes',
      'monthly_rx_bytes',
      'monthly_tx_bytes',
      'monthly_time',
    ],
  },

  /** GET /api/device/identity —— 含 PII，core 侧按 Sensitivity 决定是否脱敏 */
  identity: {
    msisdn: 'msisdn',
    imei: 'imei',
    imsi: 'imsi',
    iccid: 'iccid',
    language: 'Language',
    crVersion: 'cr_version',
    innerVersion: 'wa_inner_version',
    all: ['msisdn', 'imei', 'imsi', 'iccid', 'Language', 'cr_version', 'wa_inner_version'],
  },

  /**
   * 信号字段（REST 与 WS `signal` 频道共用）。
   * 注意 lte_snr / nr_snr / lte_band 是 core 自有的小写归一名，**不是**设备原名
   * （设备侧是 Lte_snr / Nr_snr / Lte_bands），一个都不能改。
   *
   * band / band_label / arfcn / band_width / signal_strength / pci 是 core 派生的
   * **服务小区统一字段**（NR 优先、LTE 兜底，按字段存在性判定而不是看 rat 字符串）。
   * 前端读这几个即可，不需要再按制式 if；band_label 已由 core 拼成 'n78' / 'B3'。
   * band_width 设备经常不填，缺失就没有这个 key。
   */
  signal: {
    band: 'band',
    bandLabel: 'band_label',
    arfcn: 'arfcn',
    bandWidth: 'band_width',
    signalStrength: 'signal_strength',
    pci: 'pci',
    all: [
      'rsrp',
      'rsrq',
      'sinr',
      'rssi',
      'rat',
      'operator',
      'cell_id',
      'network_registered',
      'band',
      'band_label',
      'arfcn',
      'band_width',
      'signal_strength',
      'pci',
      'nr_arfcn',
      'nr_band',
      'nr_band_width',
      'nr_signal_strength',
      'nr_snr',
      'nr_pci',
      'nr_cell_id',
      'lte_arfcn',
      'lte_band',
      'lte_band_width',
      'lte_signal_strength',
      'lte_snr',
      'lte_pci',
      'lte_cell_id',
      'lte_ca_status',
    ],
  },

  /** 连接状态（多个端点共用）。network_type 已过 core 的值映射（'5G' 而不是 '20'） */
  connection: {
    pppStatus: 'ppp_status',
    networkType: 'network_type',
    networkProvider: 'network_provider',
    all: ['ppp_status', 'network_type', 'network_provider'],
  },

  /** 设备侧布尔有两套编码，读取时两套都要认 */
  bool: {
    truthy: ['1', 'on', 'true', 'SERVER'],
    falsy: ['0', 'off', 'false'],
  },

  /**
   * 不属于稳定契约的端点：原始 dump / 无白名单平铺 / 裸 goform 命令通道，
   * 客户端不应依赖其字段名。
   * 两个 device/goform 子路径是 POST 命令通道（默认关，开关 `goform_command_enabled`），
   * 返回值不归一化也不脱敏 —— 只供排障，不要接到界面上。
   */
  unstableEndpoints: ['api/device/goform', 'api/device/goform/query', 'api/device/goform/set', 'api/wifi/module-info'],
} as const;

/** 设备侧布尔判定，与 Kotlin 侧 DeviceFields.Bool.isTrue 同语义。 */
export const isDeviceTrue = (raw: unknown): boolean =>
  raw != null && DeviceFields.bool.truthy.some((t) => t.toLowerCase() === String(raw).toLowerCase());
