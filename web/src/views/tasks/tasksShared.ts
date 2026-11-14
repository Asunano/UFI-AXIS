/**
 * 定时任务 / 自动化规则的共享类型、选项与文案。
 *
 * 拆出来的理由：动作类型这一组数据被四处消费 —— 列表卡要拿它显示 label 与图标，
 * 表单弹窗要拿它当 select 选项，规则卡的动作摘要也要，`normalizeTask` 还要用它
 * 判断旧数据里 command 是不是其实是个动作名。留在 SFC 里，拆完就得复制。
 */
import type { Component } from 'vue';
import {
  PhonePortraitOutline,
  PhonePortrait,
  WifiOutline,
  AirplaneOutline,
  RefreshOutline,
  PowerOutline,
  BulbOutline,
  SpeedometerOutline,
  GlobeOutline,
  CellularOutline,
  TerminalOutline,
  CogOutline,
  PulseOutline,
  BatteryHalfOutline,
  LinkOutline,
} from '@vicons/ionicons5';
import { NetworkModeOptions } from '@/api/contract';

export interface Task {
  id: string;
  name: string;
  actionType: string;
  params?: Record<string, any>;
  command?: string;
  hour: number;
  minute: number;
  repeatDaily: boolean;
  enabled: boolean;
  createdAt?: string;
  logs?: any[];
  triggerMode?: string | null;
  scheduleType?: string | null;
  cron?: string | null;
  scheduleParams?: Record<string, any>;
}

export interface Rule {
  id: string;
  name: string;
  enabled: boolean;
  triggerType: string;
  triggerParams?: Record<string, any>;
  actionType: string;
  params?: Record<string, any>;
  cooldownMs: number;
  createdAt?: string;
  logs?: any[];
}

// ── 动作类型（列表 label 映射 + 表单 select 共用）──
export const actionOptions = [
  { label: '移动数据', value: 'data_toggle' },
  { label: 'WiFi', value: 'wifi_toggle' },
  { label: '飞行模式', value: 'airplane_toggle' },
  { label: '重启', value: 'reboot' },
  { label: '关机', value: 'shutdown' },
  { label: '指示灯', value: 'led_toggle' },
  { label: '性能模式', value: 'performance_mode' },
  { label: '漫游', value: 'roaming_toggle' },
  { label: '网络模式', value: 'network_mode' },
  { label: '自定义命令', value: 'custom_shell' },
  // 定时测速（2026-10-06）：跑一轮测速并入库，结果走通知渠道（speedtestResult 场景）
  { label: '定时测速', value: 'speedtest' },
  // 远端备份（2026-10-06）：加密配置包推送到远端存储源，滚动保留 7 份
  { label: '远端备份', value: 'remote_backup' },
  // 流量报告（2026-10-06）：生成日/周/月报并推送，参数 period
  { label: '流量报告', value: 'traffic_report' },
];

/** 这些动作的参数是一个开关（params.enabled），摘要文案要说「开启/关闭」 */
export const toggleActions = ['data_toggle', 'wifi_toggle', 'airplane_toggle', 'led_toggle', 'roaming_toggle'];

/**
 * T15：定时任务的 network_mode 参数必须提交**别名**（core ActionExecutorImpl 会经
 * NetworkMode.toBearer 映射成设备值）。旧列表直接给 BearerPreference，且含 `LTE`/`WCDMA`/`GSM`
 * 这类设备根本不认的值 —— 选中后静默不生效。选项与 contract 同源。
 */
export const bearerOptions = [...NetworkModeOptions];

export const triggerOptions = [
  { label: '当月流量达到', value: 'traffic_total_reached' },
  { label: '网络类型变为', value: 'network_type_changed' },
  { label: '信号低于', value: 'signal_below' },
  { label: '电量低于', value: 'battery_below' },
  { label: '蜂窝网络断开', value: 'disconnect' },
];

/** 快捷创建的定时任务预设 */
export const presets = [
  { label: '夜间断网', actionType: 'data_toggle', enabled: false, hour: 23, minute: 0, iconComp: PhonePortraitOutline },
  { label: '早晨恢复', actionType: 'data_toggle', enabled: true, hour: 7, minute: 0, iconComp: PhonePortrait },
  { label: '夜间关WiFi', actionType: 'wifi_toggle', enabled: false, hour: 23, minute: 30, iconComp: WifiOutline },
  { label: '凌晨重启', actionType: 'reboot', enabled: true, hour: 4, minute: 0, iconComp: RefreshOutline },
];

export type TaskPreset = (typeof presets)[number];

/**
 * 自动化规则模板（2026-10-05）。与 presets（定时任务预设）同思路：
 * 点一下把触发+动作预填进表单，用户只需微调阈值。字段名与 core
 * ConditionEngine 的 triggerType/triggerParams、ActionType 的 params 契约严格对应，
 * 提交路径与手工创建完全一致（POST /api/rules），core 侧零改动。
 */
export interface RuleTemplate {
  label: string;
  /** 模板说明（chips 的 tooltip / aria） */
  desc: string;
  triggerType: string;
  triggerParams: Record<string, any>;
  actionType: string;
  params: Record<string, any>;
}

export const ruleTemplates: RuleTemplate[] = [
  {
    label: '流量超限关数据',
    desc: '当月累计流量达到 30GB 时自动关闭移动数据',
    triggerType: 'traffic_total_reached',
    triggerParams: { thresholdBytes: 30 * 1024 * 1024 * 1024 },
    actionType: 'data_toggle',
    params: { enabled: false },
  },
  {
    label: '低电量省电',
    desc: '电量低于 20%（未充电）时关数据、关指示灯',
    triggerType: 'battery_below',
    triggerParams: { levelPercent: 20 },
    actionType: 'data_toggle',
    params: { enabled: false },
  },
  {
    label: '回落 4G 锁回 5G',
    desc: '网络从 5G 跳变到 4G 时自动把网络模式切回 5G/4G',
    triggerType: 'network_type_changed',
    triggerParams: { targetType: '4G' },
    actionType: 'network_mode',
    params: { mode: 'LTE_AND_5G' },
  },
  {
    label: '断网自动重启',
    desc: '蜂窝网络断开并确认后自动重启设备（冷却 10 分钟）',
    triggerType: 'disconnect',
    triggerParams: {},
    actionType: 'reboot',
    params: {},
  },
];

// ── 图标映射 ──
const actionIconMap: Record<string, Component> = {
  data_toggle: PhonePortraitOutline,
  wifi_toggle: WifiOutline,
  airplane_toggle: AirplaneOutline,
  reboot: RefreshOutline,
  shutdown: PowerOutline,
  led_toggle: BulbOutline,
  performance_mode: SpeedometerOutline,
  roaming_toggle: GlobeOutline,
  network_mode: CellularOutline,
  custom_shell: TerminalOutline,
  speedtest: SpeedometerOutline,
};

const triggerIconMap: Record<string, Component> = {
  traffic_total_reached: PulseOutline,
  network_type_changed: CellularOutline,
  signal_below: CellularOutline,
  battery_below: BatteryHalfOutline,
  disconnect: LinkOutline,
};

export function getActionIconComp(actionType: string): Component {
  return actionIconMap[actionType] || CogOutline;
}

export function getTriggerIconComp(triggerType: string): Component {
  return triggerIconMap[triggerType] || CogOutline;
}

// ── 文案辅助 ──
export function actionLabel(actionType: string): string {
  return actionOptions.find((o) => o.value === actionType)?.label || actionType;
}

export function triggerLabel(triggerType: string): string {
  return triggerOptions.find((o) => o.value === triggerType)?.label || triggerType;
}

export function bearerLabel(mode: string): string {
  return bearerOptions.find((o) => o.value === mode)?.label || mode;
}

/** 没有 scheduleType 的旧数据靠 repeatDaily 推断 —— 下面几处判定都遵循这个回退 */
function scheduleTypeOf(task: Task): string {
  return task.scheduleType || (task.repeatDaily ? 'daily' : 'once');
}

export function scheduleLabel(task: Task): string {
  const map: Record<string, string> = {
    once: '仅一次',
    daily: '每天',
    weekly: '每周',
    monthly: '每月',
    every_n_days: '每N日',
    every_n_hours: '每N时',
    every_n_minutes: '每N分',
    custom: '自定义',
  };
  return map[scheduleTypeOf(task)] || '每天';
}

/** 一次性任务触发后由后端自动置 enabled=false（TaskScheduler「One-shot task disabled after trigger」） */
export function isSpentOnce(task: Task): boolean {
  return scheduleTypeOf(task) === 'once' && !task.enabled && (task.logs?.length || 0) > 0;
}

export function formatHM(hour: number, minute: number): string {
  return `${String(hour ?? 0).padStart(2, '0')}:${String(minute ?? 0).padStart(2, '0')}`;
}

export function scheduleSummaryText(task: Task): string {
  const hm = formatHM(task.hour, task.minute);
  const sp = (task.scheduleParams || {}) as Record<string, any>;
  const toArr = (v: any) => (Array.isArray(v) ? v : v != null && v !== '' ? String(v).split(',').map(Number) : []);
  switch (scheduleTypeOf(task)) {
    case 'once':
      return `仅一次 ${hm}`;
    case 'daily':
      return `每天 ${hm}`;
    case 'weekly': {
      const names = ['一', '二', '三', '四', '五', '六', '日'];
      const w = toArr(sp.weekDays)
        .sort((a: number, b: number) => a - b)
        .map((d: number) => names[(((d - 1) % 7) + 7) % 7])
        .join('');
      return `每周${w || '一'} ${hm}`;
    }
    case 'monthly': {
      const md = toArr(sp.monthDays).sort((a: number, b: number) => a - b);
      return `每月${md.length ? md.join(',') : '1'}日 ${hm}`;
    }
    case 'every_n_days':
      return `每 ${sp.intervalN ?? 1} 日 ${hm}`;
    case 'every_n_hours':
      return `每 ${sp.intervalN ?? 1} 时`;
    case 'every_n_minutes':
      return `每 ${sp.intervalN ?? 1} 分`;
    case 'custom':
      return `自定义 ${task.cron || hm}`;
    default:
      return hm;
  }
}

/** 规则阈值用的粗粒度体积文案（与 composables/utils 的 formatBytes 不同：这里只到 KB） */
function formatThresholdBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
  if (mb >= 1) return Math.round(mb) + ' MB';
  return Math.round(bytes / 1024) + ' KB';
}

export function triggerSummary(rule: Rule): string {
  const tp = rule.triggerParams || {};
  switch (rule.triggerType) {
    case 'traffic_total_reached':
      return `月流量 ≥ ${formatThresholdBytes(Number(tp.thresholdBytes) || 0)}`;
    case 'network_type_changed':
      return `网络变为 ${tp.targetType || '?'}`;
    case 'signal_below':
      return `信号 ≤ ${tp.rsrp ?? '?'} dBm`;
    case 'battery_below':
      return `电量 ≤ ${tp.levelPercent ?? '?'}%`;
    case 'disconnect':
      return '蜂窝网络断开';
    default:
      return rule.triggerType;
  }
}

export function actionSummary(rule: Rule): string {
  const base = actionLabel(rule.actionType);
  const p = rule.params || {};
  if (toggleActions.includes(rule.actionType)) return `${p.enabled ? '开启' : '关闭'} ${base}`;
  if (rule.actionType === 'network_mode') return `切换网络 → ${bearerLabel(p.mode)}`;
  // params 来自后端 JSON，mode 可能是数字 1 也可能是字符串 "1"：
  // 先归一成数字再严格比较，避免 == 的隐式转换。
  if (rule.actionType === 'performance_mode') return `性能模式 → ${Number(p.mode) === 1 ? '高性能' : '均衡'}`;
  if (rule.actionType === 'custom_shell') {
    const cmd = String(p.command ?? '');
    return cmd ? `执行命令 ${cmd.slice(0, 30)}${cmd.length > 30 ? '...' : ''}` : '执行自定义命令';
  }
  return base;
}

/** 列表里用的时间戳格式化（与 TaskLogModal 内那份同源，保持显示一致） */
export function formatTimestampLocal(ts: string | number): string {
  if (!ts) return '--';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return String(ts);
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * 归一化 core 返回的任务。两处历史包袱：
 *   · 早期版本把动作名直接塞在 `command` 里、actionType 一律是 custom_shell
 *   · 字段名同时存在下划线与驼峰两种（repeat_daily / repeatDaily）
 */
export function normalizeTask(t: any): Task {
  const knownActions = actionOptions.map((o) => o.value).filter((v) => v !== 'custom_shell');
  const actionType =
    t.actionType && t.actionType !== 'custom_shell'
      ? t.actionType
      : knownActions.includes(t.command)
        ? t.command
        : 'custom_shell';
  return {
    id: t.id,
    name: t.name || '',
    actionType,
    params: t.params || (actionType !== 'custom_shell' ? { enabled: t.enabled } : {}),
    command: actionType === 'custom_shell' ? t.command || '' : '',
    hour: t.hour ?? 0,
    minute: t.minute ?? 0,
    repeatDaily: t.repeat_daily ?? t.repeatDaily ?? false,
    enabled: t.enabled ?? true,
    createdAt: t.created_at || t.createdAt || '',
    logs: t.logs || [],
    scheduleType: t.scheduleType || ((t.repeat_daily ?? t.repeatDaily) ? 'daily' : 'once'),
    cron: t.cron ?? null,
    scheduleParams: t.scheduleParams || {},
  };
}

/** 规则的归一化：只补默认值，没有历史包袱 */
export function normalizeRule(r: any): Rule {
  return {
    id: r.id,
    name: r.name || '',
    enabled: r.enabled ?? true,
    triggerType: r.triggerType || 'traffic_total_reached',
    triggerParams: r.triggerParams || {},
    actionType: r.actionType || 'data_toggle',
    params: r.params || {},
    cooldownMs: r.cooldownMs ?? 60000,
    createdAt: r.createdAt || '',
    logs: r.logs || [],
  };
}
