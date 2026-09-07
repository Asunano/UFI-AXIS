<template>
  <div class="tasks-view">
    <GridCard :title="activeTab === 'scheduled' ? '定时任务' : '自动化规则'">
      <template #extra>
        <div class="top-actions">
          <n-button size="small" quaternary type="error" :disabled="currentList.length === 0" @click="clearAll">
            清空全部
          </n-button>
          <n-button size="small" type="primary" @click="openCreate">
            {{ activeTab === 'scheduled' ? '新建定时任务' : '新建规则' }}
          </n-button>
        </div>
      </template>

      <n-tabs v-model:value="activeTab" type="segment" class="task-tabs">
        <!-- ════════ 定时任务（时间触发） ════════ -->
        <n-tab-pane name="scheduled" tab="定时任务 · 时间触发">
          <p class="tab-hint">按设定的时间周期执行动作（如每天凌晨重启、夜间断网）。</p>

          <!-- 快捷预设 -->
          <div class="preset-section">
            <span class="preset-label">快捷创建：</span>
            <div class="preset-chips">
              <n-tag
                v-for="preset in presets"
                :key="preset.label"
                size="small"
                round
                :bordered="false"
                class="preset-chip"
                @click="applyPreset(preset)"
              >
                <n-icon :size="14" class="preset-icon"><component :is="preset.iconComp" /></n-icon>
                {{ preset.label }}
                <span class="preset-time">{{ formatHM(preset.hour, preset.minute) }}</span>
              </n-tag>
            </div>
          </div>

          <n-spin :show="loading">
            <n-empty v-if="tasks.length === 0 && !loading" description="暂无定时任务" class="empty-state">
              <template #extra>
                <n-button size="small" type="primary" @click="openCreate">创建第一个任务</n-button>
              </template>
            </n-empty>

            <div v-else class="task-list">
              <div v-for="task in tasks" :key="task.id" class="task-card">
                <div class="task-card-left">
                  <div class="task-action-icon" :class="'action-' + task.actionType">
                    <n-icon :size="20"><component :is="getActionIconComp(task.actionType)" /></n-icon>
                  </div>
                </div>
                <div class="task-card-body">
                  <div class="task-card-top">
                    <span class="task-name">{{ task.name || actionLabel(task.actionType) }}</span>
                    <n-tag
                      :type="task.scheduleType && task.scheduleType !== 'once' ? 'info' : 'default'"
                      size="tiny"
                      round
                      :bordered="false"
                    >
                      {{ scheduleLabel(task) }}
                    </n-tag>
                    <n-tag v-if="isSpentOnce(task)" type="success" size="tiny" round :bordered="false"> 已执行 </n-tag>
                  </div>
                  <div class="task-card-meta">
                    <span class="task-time">
                      <n-icon :size="13"><TimeOutline /></n-icon>
                      {{ scheduleSummaryText(task) }}
                    </span>
                    <span v-if="task.actionType === 'custom_shell'" class="task-cmd-preview">
                      {{ task.command?.slice(0, 40) }}{{ (task.command?.length || 0) > 40 ? '...' : '' }}
                    </span>
                    <span v-if="task.createdAt" class="task-created">创建于 {{ formatTimestampLocal(task.createdAt) }}</span>
                  </div>
                </div>
                <div class="task-card-actions">
                  <n-switch
                    :value="task.enabled"
                    size="medium"
                    @update:value="(val: boolean) => toggleTask(task, val)"
                  />
                  <n-button size="tiny" quaternary @click="editTask(task)"
                    ><n-icon :size="15"><CreateOutline /></n-icon
                  ></n-button>
                  <n-button size="tiny" quaternary @click="viewLogs(task, 'task')"
                    ><n-icon :size="15"><ListOutline /></n-icon
                  ></n-button>
                  <n-button size="tiny" quaternary type="error" @click="deleteTask(task)"
                    ><n-icon :size="15"><TrashOutline /></n-icon
                  ></n-button>
                </div>
              </div>
            </div>
          </n-spin>
        </n-tab-pane>

        <!-- ════════ 自动化规则（条件触发） ════════ -->
        <n-tab-pane name="rules" tab="自动化规则 · 条件触发">
          <p class="tab-hint">
            当满足网络/流量/电量等条件时自动执行动作（如锁回 5G、低电量关数据）。条件持续监测，触发后带冷却时间。
          </p>

          <n-spin :show="rulesLoading">
            <n-empty v-if="rules.length === 0 && !rulesLoading" description="暂无自动化规则" class="empty-state">
              <template #extra>
                <n-button size="small" type="primary" @click="openCreate">创建第一条规则</n-button>
              </template>
            </n-empty>

            <div v-else class="task-list">
              <div v-for="rule in rules" :key="rule.id" class="rule-card">
                <div class="rule-visual">
                  <div class="rule-node rule-trigger">
                    <n-icon :size="20"><component :is="getTriggerIconComp(rule.triggerType)" /></n-icon>
                  </div>
                  <div class="rule-arrow"><ArrowForwardOutline /></div>
                  <div class="rule-node rule-action" :class="'action-' + rule.actionType">
                    <n-icon :size="20"><component :is="getActionIconComp(rule.actionType)" /></n-icon>
                  </div>
                </div>
                <div class="rule-body">
                  <div class="task-card-top">
                    <span class="task-name">{{ rule.name || triggerLabel(rule.triggerType) }}</span>
                    <n-tag type="warning" size="tiny" round :bordered="false">条件触发</n-tag>
                  </div>
                  <div class="rule-meta">
                    <span class="rule-cond"><b>当</b> {{ triggerSummary(rule) }}</span>
                    <span class="rule-do"><b>执行</b> {{ actionSummary(rule) }}</span>
                    <span class="rule-cooldown">冷却 {{ (rule.cooldownMs || 60000) / 1000 }}s</span>
                  </div>
                </div>
                <div class="rule-actions">
                  <n-switch
                    :value="rule.enabled"
                    size="medium"
                    @update:value="(val: boolean) => toggleRule(rule, val)"
                  />
                  <n-button size="tiny" quaternary @click="editRule(rule)"
                    ><n-icon :size="15"><CreateOutline /></n-icon
                  ></n-button>
                  <n-button size="tiny" quaternary @click="viewLogs(rule, 'rule')"
                    ><n-icon :size="15"><ListOutline /></n-icon
                  ></n-button>
                  <n-button size="tiny" quaternary type="error" @click="deleteRule(rule)"
                    ><n-icon :size="15"><TrashOutline /></n-icon
                  ></n-button>
                </div>
              </div>
            </div>
          </n-spin>
        </n-tab-pane>
      </n-tabs>
    </GridCard>

    <!-- 新建/编辑 弹窗（任务 / 规则 共用，受控子组件：表单状态与构造逻辑在 TaskFormModal 内，
         父组件只负责把 editing / preset 通过 prop 传进去，并在 @submit 里执行真正的接口写入） -->
    <TaskFormModal
      v-model:show="showFormModal"
      :kind="formKind"
      :editing="formEditing"
      :preset="formPreset"
      :action-options="actionOptions"
      :trigger-options="triggerOptions"
      :bearer-options="bearerOptions"
      @submit="onTaskFormSubmit"
    />

    <!-- 日志弹窗（受控子组件：日志拉取与渲染都在 TaskLogModal 内） -->
    <TaskLogModal v-model:show="showLogModal" :title="logTitle" :kind="logKind" :id="logId" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, type Component } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
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
  TimeOutline,
  CreateOutline,
  ListOutline,
  TrashOutline,
  CogOutline,
  ArrowForwardOutline,
  PulseOutline,
  CellularOutline as CellularIcon,
  BatteryHalfOutline,
  LinkOutline,
  CellularOutline as SignalIcon,
} from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { NetworkModeOptions } from '@/api/contract';

import GridCard from '@/components/GridCard.vue';
import TaskFormModal from './components/TaskFormModal.vue';
import TaskLogModal from './components/TaskLogModal.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// ── 类型定义 ──
interface Task {
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
interface Rule {
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

// ── 状态 ──
const activeTab = ref<'scheduled' | 'rules'>('scheduled');
const loading = ref(false);
const tasks = ref<Task[]>([]);
const rulesLoading = ref(false);
const rules = ref<Rule[]>([]);
const showFormModal = ref(false);
const showLogModal = ref(false);
const currentList = computed(() => (activeTab.value === 'scheduled' ? tasks.value : rules.value));

// 受控子组件的输入：把要编辑的对象 / 预设通过 prop 传进去，弹窗自行回填
const formKind = ref<'task' | 'rule'>('task');
const formEditing = ref<Record<string, any> | null>(null);
const formPreset = ref<Record<string, any> | null>(null);

// 日志弹窗的输入
const logKind = ref<'task' | 'rule'>('task');
const logId = ref('');
const logTitle = ref('');

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
};
const triggerIconMap: Record<string, Component> = {
  traffic_total_reached: PulseOutline,
  network_type_changed: CellularIcon,
  signal_below: SignalIcon,
  battery_below: BatteryHalfOutline,
  disconnect: LinkOutline,
};
function getActionIconComp(actionType: string): Component {
  return actionIconMap[actionType] || CogOutline;
}
function getTriggerIconComp(triggerType: string): Component {
  return triggerIconMap[triggerType] || CogOutline;
}

// ── 预设（定时任务）──
const presets = [
  { label: '夜间断网', actionType: 'data_toggle', enabled: false, hour: 23, minute: 0, iconComp: PhonePortraitOutline },
  { label: '早晨恢复', actionType: 'data_toggle', enabled: true, hour: 7, minute: 0, iconComp: PhonePortrait },
  { label: '夜间关WiFi', actionType: 'wifi_toggle', enabled: false, hour: 23, minute: 30, iconComp: WifiOutline },
  { label: '凌晨重启', actionType: 'reboot', enabled: true, hour: 4, minute: 0, iconComp: RefreshOutline },
];

// ── 动作类型（列表 label 映射 + 表单 select 共用，故留父并下传 TaskFormModal）──
const actionOptions = [
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
];
const toggleActions = ['data_toggle', 'wifi_toggle', 'airplane_toggle', 'led_toggle', 'roaming_toggle'];

// T15：定时任务的 network_mode 参数必须提交**别名**（core ActionExecutorImpl 会经
// NetworkMode.toBearer 映射成设备值）。旧列表直接给 BearerPreference，且含 `LTE`/`WCDMA`/`GSM`
// 这类设备根本不认的值 —— 选中后静默不生效。选项与 contract 同源。
const bearerOptions = [...NetworkModeOptions];

const triggerOptions = [
  { label: '当月流量达到', value: 'traffic_total_reached' },
  { label: '网络类型变为', value: 'network_type_changed' },
  { label: '信号低于', value: 'signal_below' },
  { label: '电量低于', value: 'battery_below' },
  { label: '蜂窝网络断开', value: 'disconnect' },
];

// ── 文案辅助 ──
function actionLabel(actionType: string): string {
  return actionOptions.find((o) => o.value === actionType)?.label || actionType;
}
function triggerLabel(triggerType: string): string {
  return triggerOptions.find((o) => o.value === triggerType)?.label || triggerType;
}
function bearerLabel(mode: string): string {
  return bearerOptions.find((o) => o.value === mode)?.label || mode;
}
function scheduleLabel(task: Task): string {
  const t = task.scheduleType || (task.repeatDaily ? 'daily' : 'once');
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
  return map[t] || '每天';
}
// 一次性任务触发后由后端自动置 enabled=false（TaskScheduler「One-shot task disabled after trigger」）
function isSpentOnce(task: Task): boolean {
  const t = task.scheduleType || (task.repeatDaily ? 'daily' : 'once');
  return t === 'once' && !task.enabled && (task.logs?.length || 0) > 0;
}
function scheduleSummaryText(task: Task): string {
  const hm = `${String(task.hour ?? 0).padStart(2, '0')}:${String(task.minute ?? 0).padStart(2, '0')}`;
  const t = task.scheduleType || (task.repeatDaily ? 'daily' : 'once');
  const sp = (task.scheduleParams || {}) as Record<string, any>;
  const toArr = (v: any) => (Array.isArray(v) ? v : v != null && v !== '' ? String(v).split(',').map(Number) : []);
  switch (t) {
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
function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
  if (mb >= 1) return Math.round(mb) + ' MB';
  return Math.round(bytes / 1024) + ' KB';
}
function triggerSummary(rule: Rule): string {
  const tp = rule.triggerParams || {};
  switch (rule.triggerType) {
    case 'traffic_total_reached':
      return `月流量 ≥ ${formatBytes(Number(tp.thresholdBytes) || 0)}`;
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
function actionSummary(rule: Rule): string {
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

// ── 数据加载 ──
async function loadTasks() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/tasks');
    tasks.value = (data.tasks || []).map(normalizeTask);
  } catch {
    /* 静默 */
  } finally {
    loading.value = false;
  }
}
async function loadRules() {
  rulesLoading.value = true;
  try {
    const { data } = await api.get('/api/rules');
    rules.value = (data.rules || []).map((r: any) => ({
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
    }));
  } catch {
    /* 静默 */
  } finally {
    rulesLoading.value = false;
  }
}
function normalizeTask(t: any): Task {
  const knownActions = [
    'data_toggle',
    'wifi_toggle',
    'airplane_toggle',
    'reboot',
    'shutdown',
    'led_toggle',
    'performance_mode',
    'roaming_toggle',
    'network_mode',
  ];
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
    scheduleType: t.scheduleType || (t.repeat_daily ?? t.repeatDaily ? 'daily' : 'once'),
    cron: t.cron ?? null,
    scheduleParams: t.scheduleParams || {},
  };
}

// ── 表单开关（仅配置受控子组件的输入 prop）──
function openCreate() {
  formKind.value = activeTab.value === 'rules' ? 'rule' : 'task';
  formEditing.value = null;
  formPreset.value = null;
  showFormModal.value = true;
}
function editTask(task: Task) {
  formKind.value = 'task';
  formEditing.value = task as any;
  formPreset.value = null;
  showFormModal.value = true;
}
function editRule(rule: Rule) {
  formKind.value = 'rule';
  formEditing.value = rule as any;
  formPreset.value = null;
  showFormModal.value = true;
}
function applyPreset(preset: (typeof presets)[number]) {
  formKind.value = 'task';
  formEditing.value = null;
  formPreset.value = preset as any;
  showFormModal.value = true;
}

// 子组件 @submit：真正的接口写入与刷新留在父组件（与 4.1/4.2 受控范式一致）。
// 仅接口成功才关闭弹窗；失败保持打开以便用户修正（对齐原 submitForm 行为）。
async function onTaskFormSubmit(payload: {
  kind: 'task' | 'rule';
  editingId: string | null;
  body: Record<string, any>;
}) {
  const { kind, editingId, body } = payload;
  try {
    if (editingId) {
      await api.put(`/api/${kind}s/${editingId}`, body);
    } else {
      await api.post(`/api/${kind}s`, body);
    }
    message.success(editingId ? '更新成功' : '创建成功');
    if (kind === 'task') loadTasks();
    else loadRules();
    showFormModal.value = false;
  } catch {
    message.error(editingId ? '更新失败' : '创建失败');
  }
}

// ── 任务操作 ──
async function toggleTask(task: Task, val: boolean) {
  try {
    await api.put(`/api/tasks/${task.id}`, { enabled: val });
    task.enabled = val;
    message.success(val ? '已启用' : '已禁用');
  } catch {
    message.error('操作失败');
  }
}
function deleteTask(task: Task) {
  dialog.warning({
    title: '删除任务',
    content: `确定删除 "${task.name || actionLabel(task.actionType)}" ？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(`/api/tasks/${task.id}`);
        message.success('已删除');
        loadTasks();
      } catch {
        message.error('删除失败');
      }
    },
  });
}

// ── 规则操作 ──
async function toggleRule(rule: Rule, val: boolean) {
  try {
    await api.put(`/api/rules/${rule.id}`, { enabled: val });
    rule.enabled = val;
    message.success(val ? '已启用' : '已禁用');
  } catch {
    message.error('操作失败');
  }
}
function deleteRule(rule: Rule) {
  dialog.warning({
    title: '删除规则',
    content: `确定删除 "${rule.name || triggerLabel(rule.triggerType)}" ？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(`/api/rules/${rule.id}`);
        message.success('已删除');
        loadRules();
      } catch {
        message.error('删除失败');
      }
    },
  });
}

// ── 清空（按当前标签）──
function clearAll() {
  const isTask = activeTab.value === 'scheduled';
  dialog.warning({
    title: isTask ? '清空全部定时任务' : '清空全部自动化规则',
    content: `确定清空所有 ${currentList.value.length} 个${isTask ? '定时任务' : '自动化规则'}？此操作不可恢复。`,
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.post(isTask ? '/api/tasks/clear' : '/api/rules/clear');
        message.success('已清空');
        if (isTask) {
          loadTasks();
        } else {
          loadRules();
        }
      } catch {
        message.error('操作失败');
      }
    },
  });
}

// ── 日志（配置 TaskLogModal 的输入 prop；拉取与渲染在子组件内）──
function viewLogs(item: Task | Rule, kind: 'task' | 'rule') {
  logKind.value = kind;
  logId.value = item.id;
  logTitle.value =
    item.name || (kind === 'task' ? actionLabel(item.actionType) : triggerLabel((item as Rule).triggerType));
  showLogModal.value = true;
}

// ── 辅助函数 ──
function formatHM(hour: number, minute: number): string {
  return `${String(hour ?? 0).padStart(2, '0')}:${String(minute ?? 0).padStart(2, '0')}`;
}
// 列表里用的时间戳格式化（与 TaskLogModal 内那份同源，保持显示一致）
function formatTimestampLocal(ts: string | number): string {
  if (!ts) return '--';
  const d = typeof ts === 'number' ? new Date(ts) : new Date(ts);
  if (isNaN(d.getTime())) return String(ts);
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

// ── 初始化 + 自动刷新（执行日志、一次性任务触发后的禁用状态都靠轮询同步）──
onMounted(() => {
  loadTasks();
  loadRules();
});

useInterval(() => {
  loadTasks();
  loadRules();
}, 30_000);
</script>

<style scoped>
.tasks-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.tab-hint {
  margin: 4px 0 14px;
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 顶部操作区 ── */
.top-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.task-count {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 预设区 ── */
.preset-section {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.preset-label {
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.preset-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.preset-chip {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.preset-time {
  font-size: 11px;
  opacity: 0.7;
  margin-left: 2px;
}

/* ── 任务列表 ── */
.empty-state {
  padding: 32px 0;
}
.task-list {
  display: flex;
  flex-direction: column;
}
.task-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.task-card:last-child {
  border-bottom: none;
}

/* ── 动作图标 ── */
.task-card-left {
  flex-shrink: 0;
}
.task-action-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--border-subtle);
  color: var(--text-secondary);
}
.action-data_toggle {
  background: #e8f5e9;
  color: #2e7d32;
}
.action-wifi_toggle {
  background: #e3f2fd;
  color: #1565c0;
}
.action-airplane_toggle {
  background: #fce4ec;
  color: #c62828;
}
.action-reboot {
  background: #fff3e0;
  color: #e65100;
}
.action-shutdown {
  background: #efebe9;
  color: #4e342e;
}
.action-led_toggle {
  background: #fff9c4;
  color: #f57f17;
}
.action-performance_mode {
  background: #f3e5f5;
  color: #6a1b9a;
}
.action-roaming_toggle {
  background: #e0f7fa;
  color: #00695c;
}
.action-network_mode {
  background: #e8eaf6;
  color: #283593;
}
.action-custom_shell {
  background: #f5f5f5;
  color: #424242;
}

/* ── 卡片内容 ── */
.task-card-body {
  flex: 1;
  min-width: 0;
}
.task-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.task-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-card-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
  flex-wrap: wrap;
}
.task-time {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.task-cmd-preview {
  font-family: monospace;
  font-size: 11px;
  opacity: 0.8;
}
.task-created {
  opacity: 0.7;
}

/* ── 任务操作 ── */
.task-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* ── 规则卡片 ── */
.rule-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.rule-card:last-child {
  border-bottom: none;
}
.rule-visual {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.rule-node {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rule-trigger {
  background: #fff3e0;
  color: #e65100;
}
.rule-action {
  background: var(--border-subtle);
  color: var(--text-secondary);
}
.rule-action.action-data_toggle {
  background: #e8f5e9;
  color: #2e7d32;
}
.rule-action.action-wifi_toggle {
  background: #e3f2fd;
  color: #1565c0;
}
.rule-action.action-airplane_toggle {
  background: #fce4ec;
  color: #c62828;
}
.rule-action.action-reboot {
  background: #fff3e0;
  color: #e65100;
}
.rule-action.action-shutdown {
  background: #efebe9;
  color: #4e342e;
}
.rule-action.action-led_toggle {
  background: #fff9c4;
  color: #f57f17;
}
.rule-action.action-performance_mode {
  background: #f3e5f5;
  color: #6a1b9a;
}
.rule-action.action-roaming_toggle {
  background: #e0f7fa;
  color: #00695c;
}
.rule-action.action-network_mode {
  background: #e8eaf6;
  color: #283593;
}
.rule-action.action-custom_shell {
  background: #f5f5f5;
  color: #424242;
}
.rule-arrow {
  color: var(--text-muted);
  font-size: 18px;
}
.rule-body {
  flex: 1;
  min-width: 0;
}
.rule-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
  flex-wrap: wrap;
}
.rule-meta b {
  color: var(--text-secondary);
  font-weight: 600;
}
.rule-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .preset-section {
    flex-direction: column;
    align-items: flex-start;
  }
  .task-card,
  .rule-card {
    flex-wrap: wrap;
  }
  .task-card-actions,
  .rule-actions {
    width: 100%;
    justify-content: flex-end;
    padding-top: 4px;
  }
}
</style>
