// API 响应类型定义 — 与后端 JSON 结构对齐

export interface DashboardData {
  device_info: DeviceInfo | null;
  battery: BatteryInfo | null;
  storage: StorageInfo | null;
  uptime: UptimeInfo | null;
  traffic_summary: TrafficSummary | null;
  traffic_limit: TrafficLimit | null;
  network_status: NetworkStatus | null;
}

export interface DeviceInfo {
  device: Record<string, any>;
  sim: Record<string, any>;
  storage: StorageInfo;
  uptime: UptimeInfo;
  at_channel: Record<string, any>;
  kernel: string;
  network: {
    operator: string;
    type: string;
    connected: boolean;
  };
  identity: Record<string, any> | null;
}

export interface BatteryInfo {
  level: number;
  isCharging: boolean;
  temperature: number;
  voltage: number;
  technology: string;
  [key: string]: any;
}

export interface StorageInfo {
  total: number;
  available: number;
  used: number;
  [key: string]: any;
}

/**
 * 运行时间。键名以 core 的 SystemCollector 为准（`uptime_seconds` / `uptime_display`）。
 * 这里原先写的是 `seconds` / `display`，与 core 对不上 —— 唯一的消费方 DeviceTopBar
 * 因此永远显示 `--`，而 `[key: string]: any` 让这个错误编译期完全没报出来。
 */
export interface UptimeInfo {
  uptime_seconds: number;
  uptime_display: string;
  [key: string]: any;
}

export interface TrafficSummary {
  total_rx_bytes: number;
  total_tx_bytes: number;
  total_bytes: number;
  total_rx_display: string;
  total_tx_display: string;
  record_count: number;
  // 今日已用：core 算「当前月累计 − 今天第一条采样」，基线落在 traffic_records
  today_rx_bytes?: number;
  today_tx_bytes?: number;
  today_rx_display?: string;
  today_tx_display?: string;
  /** 今日上下行合计（core 算好；监控中心「今日流量」读这个） */
  today_total_bytes?: number;
  today_total_display?: string;

  month_rx_display: string;
  month_tx_display: string;
}

export interface TrafficLimit {
  enabled: boolean;
  /** 限额数值（单位见 limit_unit_display）；设备侧复合串已由 core 拆好 */
  limit_value: string;
  /** MB / GB / TB */
  limit_unit_display: string;
  /** 限额字节数；0 = 未设限额 */
  limit_bytes: number;
  alert_percent: string;
  auto_clear: boolean;
  clear_date: string;
  monthly_rx_bytes: number;
  monthly_tx_bytes: number;
  monthly_time: number;
  used_bytes: number;
  /** core 自制：到达 alert_percent 后自动关闭移动数据（不是设备字段） */
  auto_off?: {
    enabled: boolean;
    restore_on_reset: boolean;
    /** 本计费周期是否已经触发过（只读） */
    triggered: boolean;
  };
}

export interface NetworkStatus {
  network: {
    is_connected: boolean;
    has_internet: boolean;
    has_cellular: boolean;
    has_wifi: boolean;
    [key: string]: any;
  };
  mobile_data: boolean;
  ppp_status: string;
  operator: string;
  network_type: string;
}

export interface SignalInfo {
  rsrp: number;
  sinr: number;
  rsrq: number;
  rssi: number;
  rat: string;
  // 服务小区统一字段：core 从 nr_*/lte_* 派生（NR 优先、LTE 兜底，按字段存在性判定
  // 而不是看 rat/network_type 字符串）。**展示优先读这几个**，不要自己按制式分支。
  // band_label 的前缀已由 core 拼好（'n78' / 'B3'）。
  band?: string;
  band_label?: string;
  arfcn?: number;
  band_width?: number;
  signal_strength?: number;
  pci?: number;
  // 制式专属字段：core 只在对应制式驻网时下发，缺失即表示当前不在该制式。
  // 只在需要看 NSA 双连接两侧数据时才读它们（旧的 lte_bands / nr_bands 是错的字段名，已删）。
  nr_band?: string;
  nr_arfcn?: number;
  lte_band?: string;
  lte_arfcn?: number;
  [key: string]: any;
}

export interface CpuInfo {
  usage_percent: number;
  core_count: number;
  cores: Array<{ core: number; freq_mhz: number; freq_display: string }>;
  temperature: number;
}

export interface TrafficRealtime {
  rx_speed: number;
  tx_speed: number;
  rx_bytes: number;
  tx_bytes: number;
  rx_speed_display: string;
  tx_speed_display: string;
  timestamp: number;
}

/** `/api/system/memory` 实时响应（与 WS `memory` 推送同源）。core 归一定义，前端只读这几个字段。 */
export interface MemoryInfo {
  total: number;
  used: number;
  free?: number;
  available?: number;
  usage_percent: number;
}

export interface WifiSettings {
  ssid: string;
  password: string;
  auth_mode: string;
  encryp_type: string;
  max_sta_num: number;
  broadcast_disabled: number;
  chip_index: string;
  /** WiFi 模块开关；由 normalizeWifiSettings 从 WiFiModuleSwitch / wifi_enable / wifi_onoff_state 归一 */
  enabled: boolean;
  [key: string]: any;
}

export interface BandStatus {
  lte_band_lock: string;
  nr_band_lock: string;
}

export interface WifiClient {
  hostname: string;
  ip_addr: string;
  mac: string;
  [key: string]: any;
}

/** WiFi 接入控制名单里的一台设备（`/api/wifi/acl`）。`name` 可能是空串。 */
export interface WifiAclEntry {
  mac: string;
  name: string;
}

/**
 * 接入控制名单（拉黑）。三个写端点（block/unblock/clear）回的是同一形状 + `success`，
 * 且是 core 写完**回读设备**的真实名单 —— 直接覆盖本地状态，不要自己增删推算
 * （设备侧是整表替换，本地推算会和 app / 设备自带 UI 的操作互相覆盖）。
 *
 * `mode` 是设备档位原样字符串（`'2'` = 黑名单生效）。清空名单后它仍是 `'2'`，
 * 所以"有没有拉黑"只看 `black_list` 是否为空。
 */
export interface WifiAcl {
  mode: string;
  black_list: WifiAclEntry[];
  white_list: WifiAclEntry[];
  success?: boolean;
}

export interface CellInfo {
  serving_cell?: Record<string, any>;
  neighbor_cells?: NeighborCell[];
  locked_cells?: Record<string, any>[];
  [key: string]: any;
}

export interface NeighborCell {
  pci: number;
  earfcn: number;
  rsrp: number;
  rsrq: number;
  sinr: number;
  rat: string;
  [key: string]: any;
}

export interface PairingInfo {
  device_id: string;
  device_name: string;
  pairing_code: string;
  storage_status: {
    has_root: boolean;
    is_external_storage_manager: boolean;
  };
  has_default_password: boolean;
}

export interface PairingDevice {
  fingerprint: string;
  device_name: string;
  last_seen: number;
  created_at: number;
}

export interface PairingStatus {
  paired: boolean;
  device_id: string;
  fingerprints: string[];
  pairing_code: string;
  pairing_enabled: boolean;
  pairing_max_devices: number;
}

export interface VerificationCode {
  msgId: number;
  code: string;
  source: string;
  snippet: string;
  timestamp: number;
  keyword: string;
}
