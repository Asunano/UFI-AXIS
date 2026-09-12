/**
 * 监控页的共享类型、常量与格式化函数。
 *
 * 拆出来的理由：这些东西被 MonitorView（取数 / 状态）、MonitorControlsCard（渲染选项按钮）
 * 和 monitorCharts（轴与 tooltip 格式化）三处同时需要。留在 SFC 里的话，
 * 子组件要么重复一份常量（下次改指标列表必然漏改），要么反过来 import 父组件。
 */

/** 降采样后的单个数据点。core 的 DownsampledPoint.t 是 epoch 毫秒 */
export interface Pt {
  t: number;
  avg: number;
  min: number;
  max: number;
}

export interface MonitorPrefs {
  enabledTypes: string[];
  defaultHours: number;
  refreshIntervalSec: number;
  fixedYAxis: boolean;
  fillAlpha: number;
  /** app 专有项：web 不展示，但要保留在本地副本里，避免字段级 PUT 之外的整体回传把它覆盖 */
  exportZip: boolean;
}

/** GET /api/monitor/storage 的响应形状 */
export interface StorageData {
  tables: { name: string; count: number; size_kb: number }[];
  total_kb: number;
  total_display: string;
}

/** 指标 key 的完整集合与顺序。enabledTypes 是「集合语义」，回传时按这个顺序过滤 */
export const METRIC_KEYS = [
  'cpu',
  'memory',
  'traffic_rx',
  'traffic_tx',
  'signal_rsrp',
  'signal_sinr',
  'battery',
  'temperature',
];

/** 默认值必须与 core 的 MonitorPreferences 一致，否则回读失败时两端显示不同 */
export const DEFAULT_PREFS: MonitorPrefs = {
  enabledTypes: [...METRIC_KEYS],
  defaultHours: 24,
  refreshIntervalSec: 30,
  fixedYAxis: false,
  fillAlpha: 1,
  exportZip: false,
};

export const TIME_RANGES = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 168 },
];

export const REFRESH_CHOICES = [10, 30, 60, 300];

export const METRIC_CHOICES = [
  { key: 'cpu', label: 'CPU' },
  { key: 'memory', label: '内存' },
  { key: 'traffic_rx', label: '下行' },
  { key: 'traffic_tx', label: '上行' },
  { key: 'signal_rsrp', label: 'RSRP' },
  { key: 'signal_sinr', label: 'SINR' },
  { key: 'battery', label: '电池' },
  { key: 'temperature', label: '温度' },
];

/** 24h 以内只显示时分，更长的范围带上月日，否则刻度全是重复的时间 */
export function xAxisLabelFormat(hours: number): string {
  return hours <= 24 ? '{HH}:{mm}' : '{MM}-{dd} {HH}:{mm}';
}

export function formatTime(ms: number, hours: number): string {
  const d = new Date(ms);
  const time = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  if (hours <= 24) return time;
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' + time;
}

// core 存的是 rxSpeed/txSpeed，单位 bytes/s（Entities.kt: TrafficRecord）
export function formatSpeedAxis(val: number): string {
  if (val == null || isNaN(val)) return '';
  if (val >= 1024 * 1024) return (val / 1024 / 1024).toFixed(1) + ' MB/s';
  if (val >= 1024) return (val / 1024).toFixed(0) + ' KB/s';
  return val.toFixed(0) + ' B/s';
}

export function formatSpeedValue(val: number): string {
  if (val == null || isNaN(val)) return '--';
  if (val >= 1024 * 1024) return (val / 1024 / 1024).toFixed(2) + ' MB/s';
  if (val >= 1024) return (val / 1024).toFixed(1) + ' KB/s';
  return val.toFixed(0) + ' B/s';
}
