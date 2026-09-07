<template>
  <n-modal
    :show="show"
    preset="dialog"
    :title="formTitle"
    :positive-text="editingId ? '保存' : '创建'"
    negative-text="取消"
    style="width: 520px"
    @update:show="(v: boolean) => emit('update:show', v)"
    @positive-click="onPositiveClick"
  >
    <n-form label-placement="left" label-width="92" class="task-form">
      <n-form-item label="名称">
        <n-input v-model:value="form.name" placeholder="可选，留空则使用动作类型" />
      </n-form-item>

      <!-- 共用：动作类型 -->
      <n-form-item label="动作类型" required>
        <n-select v-model:value="form.actionType" :options="actionOptions" placeholder="选择动作类型" />
      </n-form-item>
      <n-form-item v-if="isToggleAction" label="目标状态">
        <div class="toggle-param">
          <n-switch v-model:value="form.enabled" />
          <span class="toggle-label">{{ form.enabled ? '启用' : '禁用' }}</span>
        </div>
      </n-form-item>
      <n-form-item v-else-if="form.actionType === 'network_mode'" label="网络模式">
        <n-select v-model:value="form.mode" :options="bearerOptions" placeholder="选择承载偏好" />
      </n-form-item>
      <n-form-item v-else-if="form.actionType === 'performance_mode'" label="性能档位">
        <n-select v-model:value="form.perfMode" :options="perfModeOptions" placeholder="选择性能模式" />
      </n-form-item>
      <n-form-item v-else-if="form.actionType === 'custom_shell'" label="命令内容" required>
        <n-input v-model:value="form.command" type="textarea" :rows="4" placeholder="输入要执行的 Shell 命令" />
      </n-form-item>

      <!-- 定时任务：执行周期 -->
      <template v-if="form.kind === 'task'">
        <n-form-item label="执行周期" required>
          <div class="schedule-editor">
            <n-select
              v-model:value="form.scheduleType"
              :options="scheduleTypeOptions"
              placeholder="选择周期类型"
              style="width: 100%"
            />

            <div
              v-if="form.scheduleType === 'daily' || form.scheduleType === 'once'"
              class="schedule-extra schedule-time"
            >
              <n-input-number
                v-model:value="form.hour"
                :min="0"
                :max="23"
                size="small"
                style="width: 100px"
                placeholder="时"
              />
              <span class="time-sep">:</span>
              <n-input-number
                v-model:value="form.minute"
                :min="0"
                :max="59"
                size="small"
                style="width: 100px"
                placeholder="分"
              />
            </div>

            <div v-else-if="form.scheduleType === 'weekly'" class="schedule-extra">
              <n-checkbox-group v-model:value="form.weekDays">
                <n-space>
                  <n-checkbox v-for="d in weekdayOptions" :key="d.value" :value="d.value">{{ d.label }}</n-checkbox>
                </n-space>
              </n-checkbox-group>
              <div class="schedule-time">
                <n-input-number
                  v-model:value="form.hour"
                  :min="0"
                  :max="23"
                  size="small"
                  style="width: 100px"
                  placeholder="时"
                />
                <span class="time-sep">:</span>
                <n-input-number
                  v-model:value="form.minute"
                  :min="0"
                  :max="59"
                  size="small"
                  style="width: 100px"
                  placeholder="分"
                />
              </div>
            </div>

            <div v-else-if="form.scheduleType === 'monthly'" class="schedule-extra">
              <n-checkbox-group v-model:value="form.monthDays">
                <n-space>
                  <n-checkbox v-for="d in 31" :key="d" :value="d">{{ d }}</n-checkbox>
                </n-space>
              </n-checkbox-group>
              <div class="schedule-time">
                <n-input-number
                  v-model:value="form.hour"
                  :min="0"
                  :max="23"
                  size="small"
                  style="width: 100px"
                  placeholder="时"
                />
                <span class="time-sep">:</span>
                <n-input-number
                  v-model:value="form.minute"
                  :min="0"
                  :max="59"
                  size="small"
                  style="width: 100px"
                  placeholder="分"
                />
              </div>
            </div>

            <div v-else-if="form.scheduleType?.startsWith('every_n_')" class="schedule-extra">
              <span>每</span>
              <n-input-number
                v-model:value="form.intervalN"
                :min="1"
                :max="form.scheduleType === 'every_n_days' ? 30 : 59"
                size="small"
                style="width: 90px"
              />
              <span>{{ intervalUnit }}</span>
              <div v-if="form.scheduleType === 'every_n_days'" class="schedule-time">
                <n-input-number
                  v-model:value="form.hour"
                  :min="0"
                  :max="23"
                  size="small"
                  style="width: 100px"
                  placeholder="时"
                />
                <span class="time-sep">:</span>
                <n-input-number
                  v-model:value="form.minute"
                  :min="0"
                  :max="59"
                  size="small"
                  style="width: 100px"
                  placeholder="分"
                />
              </div>
              <div v-else-if="form.scheduleType === 'every_n_hours'" class="schedule-time">
                <span class="time-sep">第</span>
                <n-input-number
                  v-model:value="form.minute"
                  :min="0"
                  :max="59"
                  size="small"
                  style="width: 100px"
                  placeholder="分"
                />
                <span class="time-sep">分执行</span>
              </div>
            </div>

            <div v-else-if="form.scheduleType === 'custom'" class="schedule-extra">
              <n-input v-model:value="form.customCron" placeholder="分 时 日 月 周（如 30 8 * * 1-5）" />
              <n-text depth="3" style="font-size: 12px"
                >语法：分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-7)。支持 * , - / 与 */N。</n-text
              >
            </div>

            <div class="schedule-preview">
              <n-icon :size="14"><TimeOutline /></n-icon>
              <span>下次执行：{{ schedulePreview }}</span>
            </div>
          </div>
        </n-form-item>
      </template>

      <!-- 自动化规则：触发条件 + 冷却 -->
      <template v-else>
        <n-form-item label="触发条件" required>
          <n-select v-model:value="form.triggerType" :options="triggerOptions" placeholder="选择触发条件" />
        </n-form-item>

        <n-form-item v-if="form.triggerType === 'traffic_total_reached'" label="流量阈值">
          <div class="schedule-extra">
            <n-input-number v-model:value="form.thresholdMb" :min="1" size="small" style="width: 120px" />
            <span>MB（1 GB = 1024 MB）</span>
          </div>
        </n-form-item>
        <n-form-item v-else-if="form.triggerType === 'network_type_changed'" label="目标网络">
          <n-select
            v-model:value="form.targetType"
            :options="networkTypeOptions"
            placeholder="变为该网络类型时触发"
            style="width: 100%"
          />
        </n-form-item>
        <n-form-item v-else-if="form.triggerType === 'signal_below'" label="RSRP 阈值">
          <div class="schedule-extra">
            <n-input-number v-model:value="form.rsrp" :min="-140" :max="-40" size="small" style="width: 120px" />
            <span>dBm（数值越小信号越差，如 -110）</span>
          </div>
        </n-form-item>
        <n-form-item v-else-if="form.triggerType === 'battery_below'" label="电量阈值">
          <div class="schedule-extra">
            <n-input-number v-model:value="form.levelPercent" :min="1" :max="100" size="small" style="width: 120px" />
            <span>%（未充电时触发）</span>
          </div>
        </n-form-item>
        <n-alert v-if="form.triggerType === 'disconnect'" type="info" :show-icon="false">
          蜂窝网络由联网跳变为断网时触发，无需额外参数。
        </n-alert>

        <n-form-item label="冷却时间">
          <div class="schedule-extra">
            <n-input-number v-model:value="form.cooldownSec" :min="0" :max="3600" size="small" style="width: 120px" />
            <span>秒（冷却内不重复执行，防抖动刷屏）</span>
          </div>
        </n-form-item>
      </template>
    </n-form>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue';
import { useMessage, type SelectOption } from 'naive-ui';
import { TimeOutline } from '@vicons/ionicons5';
import { NetworkMode } from '@/api/contract';

const props = defineProps<{
  show: boolean;
  kind: 'task' | 'rule';
  editing: Record<string, any> | null;
  preset: Record<string, any> | null;
  actionOptions: SelectOption[];
  triggerOptions: SelectOption[];
  bearerOptions: SelectOption[];
}>();
const emit = defineEmits<{
  'update:show': [boolean];
  submit: [payload: { kind: 'task' | 'rule'; editingId: string | null; body: Record<string, any> }];
}>();

const message = useMessage();

// ── 表单状态（自组件内持有，父组件只负责把 editing / preset 通过 prop 传进来）──
const editingId = ref<string | null>(null);
const form = reactive({
  kind: 'task' as 'task' | 'rule',
  name: '',
  actionType: 'data_toggle' as string,
  enabled: true,
  command: '',
  mode: NetworkMode.LTE_AND_5G as string,
  perfMode: 0,
  // 定时任务：执行周期
  scheduleType: 'daily' as string,
  hour: 0,
  minute: 0,
  weekDays: [] as number[],
  monthDays: [] as number[],
  intervalN: 1,
  customCron: '',
  // 自动化规则：触发条件
  triggerType: 'traffic_total_reached' as string,
  thresholdMb: 100,
  targetType: '5G',
  rsrp: -110,
  levelPercent: 20,
  cooldownSec: 60,
});

const formTitle = computed(() => (editingId.value ? '编辑' : '新建') + (form.kind === 'task' ? '任务' : '规则'));

// ── 动作 / 触发 选项（与父列表的 label 映射同源，这里为表单 select 自备一份）──
const toggleActions = ['data_toggle', 'wifi_toggle', 'airplane_toggle', 'led_toggle', 'roaming_toggle'];
const isToggleAction = computed(() => toggleActions.includes(form.actionType));
const perfModeOptions = [
  { label: '均衡 (0)', value: 0 },
  { label: '高性能 (1)', value: 1 },
];
const networkTypeOptions = [
  { label: '5G', value: '5G' },
  { label: '4G', value: '4G' },
  { label: '3G+', value: '3G+' },
  { label: '3G', value: '3G' },
  { label: '2G', value: '2G' },
];
const scheduleTypeOptions = [
  { label: '仅一次', value: 'once' },
  { label: '每天', value: 'daily' },
  { label: '每周', value: 'weekly' },
  { label: '每月', value: 'monthly' },
  { label: '每 N 日', value: 'every_n_days' },
  { label: '每 N 时', value: 'every_n_hours' },
  { label: '每 N 分', value: 'every_n_minutes' },
  { label: '自定义', value: 'custom' },
];
const weekdayOptions = [
  { label: '一', value: 1 },
  { label: '二', value: 2 },
  { label: '三', value: 3 },
  { label: '四', value: 4 },
  { label: '五', value: 5 },
  { label: '六', value: 6 },
  { label: '日', value: 7 },
];
const intervalUnit = computed(() => {
  switch (form.scheduleType) {
    case 'every_n_days':
      return '日';
    case 'every_n_hours':
      return '时';
    case 'every_n_minutes':
      return '分';
    default:
      return '';
  }
});

function buildCron(): string | null {
  const t = form.scheduleType;
  const h = Number(form.hour) || 0;
  const m = Number(form.minute) || 0;
  const n = Number(form.intervalN) || 1;
  switch (t) {
    case 'daily':
      return `${m} ${h} * * *`;
    case 'weekly': {
      const wd = form.weekDays.length
        ? form.weekDays
            .map((d: number) => d % 7)
            .sort((a: number, b: number) => a - b)
            .join(',')
        : '1';
      return `${m} ${h} * * ${wd}`;
    }
    case 'monthly': {
      const md = form.monthDays.length
        ? form.monthDays
            .slice()
            .sort((a: number, b: number) => a - b)
            .join(',')
        : '1';
      return `${m} ${h} ${md} * *`;
    }
    case 'every_n_days':
      return `${m} ${h} */${n} * *`;
    case 'every_n_hours':
      return `${m} */${n} * * *`;
    case 'every_n_minutes':
      return `*/${n} * * * *`;
    case 'custom':
      return form.customCron.trim() || null;
    default:
      return null;
  }
}
const schedulePreview = computed(() => {
  const hm = `${String(form.hour ?? 0).padStart(2, '0')}:${String(form.minute ?? 0).padStart(2, '0')}`;
  switch (form.scheduleType) {
    case 'once':
      return `${hm}（仅一次）`;
    case 'daily':
      return `每天 ${hm}`;
    case 'weekly': {
      const names = ['一', '二', '三', '四', '五', '六', '日'];
      const w = form.weekDays
        .slice()
        .sort((a: number, b: number) => a - b)
        .map((d: number) => names[(((d - 1) % 7) + 7) % 7])
        .join('');
      return `每周${w || '一'} ${hm}`;
    }
    case 'monthly': {
      const md = form.monthDays.slice().sort((a: number, b: number) => a - b);
      return `每月${md.length ? md.join(',') : '1'}日 ${hm}`;
    }
    case 'every_n_days':
      return `每 ${form.intervalN} 日 ${hm}`;
    case 'every_n_hours':
      return `每 ${form.intervalN} 时`;
    case 'every_n_minutes':
      return `每 ${form.intervalN} 分`;
    case 'custom':
      return form.customCron ? `自定义 cron: ${form.customCron}` : '请输入 cron';
    default:
      return hm;
  }
});

// ── 动作 / 触发参数构造（任务 / 规则 共用）──
function buildActionPayload() {
  const at = form.actionType;
  const p: Record<string, any> = {};
  let command = at;
  if (toggleActions.includes(at)) p.enabled = form.enabled;
  else if (at === 'network_mode') p.mode = form.mode;
  else if (at === 'performance_mode') p.mode = form.perfMode;
  else if (at === 'custom_shell') command = form.command.trim();
  return { actionType: at, params: p, command };
}
function buildTriggerParams(): Record<string, any> {
  const tp: Record<string, any> = {};
  switch (form.triggerType) {
    case 'traffic_total_reached':
      tp.thresholdBytes = Math.round(form.thresholdMb * 1024 * 1024);
      break;
    case 'network_type_changed':
      tp.targetType = form.targetType;
      break;
    case 'signal_below':
      tp.rsrp = form.rsrp;
      break;
    case 'battery_below':
      tp.levelPercent = form.levelPercent;
      break;
    case 'disconnect':
      break;
  }
  return tp;
}

// ── 表单回填（打开时由父传进来的 editing / preset 决定）──
function resetForm(kind: 'task' | 'rule') {
  form.kind = kind;
  form.name = '';
  form.actionType = 'data_toggle';
  form.enabled = true;
  form.command = '';
  form.mode = NetworkMode.LTE_AND_5G;
  form.perfMode = 0;
  form.scheduleType = 'daily';
  form.hour = 0;
  form.minute = 0;
  form.weekDays = [];
  form.monthDays = [];
  form.intervalN = 1;
  form.customCron = '';
  form.triggerType = 'traffic_total_reached';
  form.thresholdMb = 100;
  form.targetType = '5G';
  form.rsrp = -110;
  form.levelPercent = 20;
  form.cooldownSec = 60;
}
function prefillFromTask(t: Record<string, any>) {
  editingId.value = t.id ?? null;
  form.kind = 'task';
  form.name = t.name || '';
  form.actionType = t.actionType;
  // 目标状态只来自 params.enabled；task.enabled 是「任务是否启用」，两者语义不同
  form.enabled = toggleActions.includes(t.actionType) ? (t.params?.enabled ?? true) : true;
  form.command = t.command || '';
  form.mode = t.params?.mode || NetworkMode.LTE_AND_5G;
  form.perfMode = Number(t.params?.mode ?? 0);
  form.hour = t.hour;
  form.minute = t.minute;
  const sp = (t.scheduleParams || {}) as Record<string, any>;
  const toArr = (v: any) => (Array.isArray(v) ? v : v != null && v !== '' ? String(v).split(',').map(Number) : []);
  form.scheduleType = t.scheduleType || (t.repeatDaily ? 'daily' : 'once');
  form.weekDays = toArr(sp.weekDays);
  form.monthDays = toArr(sp.monthDays);
  form.intervalN = Number(sp.intervalN) || 1;
  form.customCron = t.cron && t.scheduleType === 'custom' ? t.cron : '';
}
function prefillFromRule(r: Record<string, any>) {
  editingId.value = r.id ?? null;
  form.kind = 'rule';
  form.name = r.name || '';
  form.actionType = r.actionType;
  form.enabled = toggleActions.includes(r.actionType) ? (r.params?.enabled ?? true) : true;
  // 规则的自定义命令存在 params.command 里
  form.command = r.actionType === 'custom_shell' ? String(r.params?.command ?? '') : '';
  form.mode = r.params?.mode || NetworkMode.LTE_AND_5G;
  form.perfMode = Number(r.params?.mode ?? 0);
  const tp = r.triggerParams || {};
  form.triggerType = r.triggerType;
  form.thresholdMb = tp.thresholdBytes ? Math.round(Number(tp.thresholdBytes) / 1024 / 1024) : 100;
  form.targetType = tp.targetType || '5G';
  form.rsrp = Number(tp.rsrp ?? -110);
  form.levelPercent = Number(tp.levelPercent ?? 20);
  form.cooldownSec = Math.max(0, Math.round((r.cooldownMs || 60000) / 1000));
}
function prefillFromPreset(p: Record<string, any>) {
  editingId.value = null;
  resetForm('task');
  form.actionType = p.actionType;
  form.enabled = p.enabled;
  form.hour = p.hour;
  form.minute = p.minute;
  form.name = p.label;
}

// 弹窗提交：仅做校验 + 构造请求体，真正的接口写入留在父组件（onTaskFormSubmit），
// 与 4.1/4.2 受控子组件范式一致。返回 false 阻止 n-modal 在接口完成前自行关闭。
function onPositiveClick() {
  if (!form.actionType) {
    message.error('请选择动作类型');
    return false;
  }
  if (form.actionType === 'custom_shell' && !form.command?.trim()) {
    message.error('请输入命令内容');
    return false;
  }
  if (props.kind === 'task' && form.scheduleType === 'custom' && !form.customCron.trim()) {
    message.error('请输入 cron 表达式');
    return false;
  }
  const action = buildActionPayload();
  if (props.kind === 'task') {
    const cron = buildCron();
    const scheduleType = form.scheduleType || 'daily';
    const body: Record<string, any> = {
      name: form.name || undefined,
      actionType: action.actionType,
      command: action.command,
      hour: form.hour,
      minute: form.minute,
      repeatDaily: scheduleType === 'once' ? false : true,
      triggerMode: 'schedule',
      scheduleType,
      cron,
      scheduleParams: {
        weekDays: (form.weekDays || []).join(','),
        monthDays: (form.monthDays || []).join(','),
        intervalN: form.intervalN,
      },
    };
    if (action.actionType !== 'custom_shell') body.params = action.params;
    // enabled 是「任务是否启用」，不是动作目标状态（目标状态在 params.enabled）。
    // 编辑时不下发，交由后端保留原值，避免把用户手动禁用的任务重新启用。
    if (!editingId.value) body.enabled = true;
    emit('submit', { kind: 'task', editingId: editingId.value, body });
    return false;
  } else {
    // 规则没有独立的 command 字段（AutomationRule 只有 params），
    // ActionExecutorImpl 的 custom_shell 读的是 params["command"]
    const ruleParams: Record<string, any> = { ...action.params };
    if (action.actionType === 'custom_shell') ruleParams.command = action.command;
    const body: Record<string, any> = {
      name: form.name || undefined,
      triggerType: form.triggerType,
      triggerParams: buildTriggerParams(),
      actionType: action.actionType,
      params: ruleParams,
      cooldownMs: Math.max(0, form.cooldownSec) * 1000,
    };
    if (!editingId.value) body.enabled = true;
    emit('submit', { kind: 'rule', editingId: editingId.value, body });
    return false;
  }
}

// 打开时按 prop 回填；关闭时清空 editingId（避免下次新建时残留旧 id）
watch(
  () => props.show,
  (v) => {
    if (!v) {
      editingId.value = null;
      return;
    }
    if (props.editing) {
      if (props.kind === 'task') prefillFromTask(props.editing);
      else prefillFromRule(props.editing);
    } else if (props.preset) {
      prefillFromPreset(props.preset);
    } else {
      resetForm(props.kind);
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.task-form {
  padding-top: 8px;
}
.toggle-param {
  display: flex;
  align-items: center;
  gap: 8px;
}
.toggle-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.schedule-extra {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.schedule-time {
  display: flex;
  align-items: center;
  gap: 4px;
}
.schedule-preview {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--border-subtle);
  color: var(--text-secondary);
  font-size: 12px;
}
.time-sep {
  font-size: 18px;
  font-weight: 600;
  line-height: 1;
}
</style>
