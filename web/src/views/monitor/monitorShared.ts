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
  /**
   * 告警扫描 / 套餐限额检查 / 设备事件比对间隔（2026-10-07 审计修复：core+app 早有，
   * web 无 UI 属半成品缺口）。web 端只展示后两项（告警扫描偏专业，维持 app 专有展示）；
   * 回传副本保留全部三项防字段级 PUT 之外的整体覆盖。
   */
  alertScanSec: number;
  trafficLimitCheckSec: number;
  deviceEventCheckSec: number;
  /**
   * 智能采集节拍（SmartLoop，2026-10-14 接线）。
   *
   * core 侧真源是 AppSettings 的 `smart_loop_*` 原始键（`SmartLoopTuning`），
   * DataScheduler 的 goform-traffic / battery 两条循环每轮现读 —— 改完下一轮生效。
   * 三个阈值单位都是 **bytes/s**（不是 bit/s、也不是 KB/s），与 core 的常量同口径。
   * `smartLoopEnabled` 是故障回退开关而不是调优项，所以它在 UI 上排在这一组第一位。
   */
  smartLoopEnabled: boolean;
  smartLoopIdleBps: number;
  smartLoopActiveBps: number;
  smartLoopHeavyBps: number;
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
  alertScanSec: 15,
  trafficLimitCheckSec: 300,
  deviceEventCheckSec: 60,
  // 智能节拍：与 core `SmartLoopTuning.defaults()` 逐字一致
  //（= SmartLoop 伴生对象的 IDLE/ACTIVE/HEAVY 三个常量 + enabled=true）。
  // 抄错一个数就意味着"升级后设备行为变了"，所以这里刻意写成常量表达式而不是字面量。
  smartLoopEnabled: true,
  smartLoopIdleBps: 16 * 1024,
  smartLoopActiveBps: 1 * 1024 * 1024,
  smartLoopHeavyBps: 20 * 1024 * 1024,
};

export const TIME_RANGES = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 168 },
];

export const REFRESH_CHOICES = [10, 30, 60, 300];

/**
 * 智能节拍三个阈值的可选档位（bytes/s），按量级**分带**：
 * idle 全部 < 1MB ≤ active 全部 < 20MB ≤ heavy 全部。
 *
 * 分带而不是三份相同的档位表，是为了让"随手点一个"都不会违反 idle < active < heavy ——
 * core 的 PUT 对序关系是硬校验（反序直接 400），可选值本身反序时用户根本没有合法组合可点。
 */
export const SMART_LOOP_IDLE_CHOICES = [4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024];
export const SMART_LOOP_ACTIVE_CHOICES = [1 * 1024 * 1024, 4 * 1024 * 1024, 10 * 1024 * 1024];
export const SMART_LOOP_HEAVY_CHOICES = [20 * 1024 * 1024, 50 * 1024 * 1024, 100 * 1024 * 1024];

/** 智能节拍的三个阈值键（与 core `MonitorPreferences` 同名字段一一对应） */
export type SmartLoopThresholdKey = 'smartLoopIdleBps' | 'smartLoopActiveBps' | 'smartLoopHeavyBps';

/**
 * 某个阈值当前可选的档位：本带档位里再滤掉会破坏 idle < active < heavy 的那些。
 *
 * core 的 PUT 对序关系是硬校验，"点了却报错、值又弹回去"正是本仓明令避免的假设置交互
 *（app 侧 `withThermalOrder` 的 KDoc 写了同一个坑），所以直接在**选项层面**堵掉，
 * 而不是等用户点完再纠正他。一次只改一个键，因此只需拿另外两个**现值**做过滤。
 *
 * 当前值永远保留在列表里：它可能是设备上已有的非档位值（直接 PUT 或旧版本留下的），
 * 剔掉的话下拉框就显示不出这个值了。
 */
export function smartLoopThresholdOptions(
  key: SmartLoopThresholdKey,
  prefs: MonitorPrefs
): { label: string; value: number }[] {
  const band =
    key === 'smartLoopIdleBps'
      ? SMART_LOOP_IDLE_CHOICES
      : key === 'smartLoopActiveBps'
        ? SMART_LOOP_ACTIVE_CHOICES
        : SMART_LOOP_HEAVY_CHOICES;
  const allowed = band.filter((v) => {
    if (key === 'smartLoopIdleBps') return v < prefs.smartLoopActiveBps;
    if (key === 'smartLoopActiveBps') return v > prefs.smartLoopIdleBps && v < prefs.smartLoopHeavyBps;
    return v > prefs.smartLoopActiveBps;
  });
  const current = prefs[key];
  if (!allowed.includes(current)) allowed.push(current);
  // 档位表本身是升序的，只有"补进来的当前值"可能乱序，所以统一排一次
  return allowed.sort((a, b) => a - b).map((v) => ({ label: formatSpeedValue(v), value: v }));
}

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
