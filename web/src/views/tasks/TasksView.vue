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

          <PresetChips @apply="applyPreset" />

          <n-spin :show="loading">
            <n-empty v-if="tasks.length === 0 && !loading" description="暂无定时任务" class="empty-state">
              <template #extra>
                <n-button size="small" type="primary" @click="openCreate">创建第一个任务</n-button>
              </template>
            </n-empty>

            <div v-else class="task-list">
              <TaskCard
                v-for="task in tasks"
                :key="task.id"
                :task="task"
                @toggle="(v) => toggleTask(task, v)"
                @edit="editTask(task)"
                @logs="viewLogs(task, 'task')"
                @delete="deleteTask(task)"
              />
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
              <RuleCard
                v-for="rule in rules"
                :key="rule.id"
                :rule="rule"
                @toggle="(v) => toggleRule(rule, v)"
                @edit="editRule(rule)"
                @logs="viewLogs(rule, 'rule')"
                @delete="deleteRule(rule)"
              />
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
    <TaskLogModal :id="logId" v-model:show="showLogModal" :title="logTitle" :kind="logKind" />
  </div>
</template>

<script setup lang="ts">
/**
 * 定时任务 / 自动化规则页：只留状态、取数与写操作，列表行与预设条拆到 components/。
 *
 * 两类数据的取数是**各自独立**的（`/api/tasks` 与 `/api/rules`），但轮询放在一起：
 * 执行日志和「一次性任务触发后被后端置为 disabled」都只能靠轮询同步回来。
 */
import { ref, computed, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import PresetChips from './components/PresetChips.vue';
import TaskCard from './components/TaskCard.vue';
import RuleCard from './components/RuleCard.vue';
import TaskFormModal from './components/TaskFormModal.vue';
import TaskLogModal from './components/TaskLogModal.vue';
import {
  actionLabel,
  actionOptions,
  bearerOptions,
  normalizeRule,
  normalizeTask,
  triggerLabel,
  triggerOptions,
  type Rule,
  type Task,
  type TaskPreset,
} from './tasksShared';
import { Endpoints } from '@/api/contract';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

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
    rules.value = (data.rules || []).map(normalizeRule);
  } catch {
    /* 静默 */
  } finally {
    rulesLoading.value = false;
  }
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
function applyPreset(preset: TaskPreset) {
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
  // 路径走契约常量而不是 `/api/${kind}s`：后者两段全是插值，契约校验器只能把它归一成
  // `api/:p/:p`（没有一个字面量段可比对），于是被报成「web 声明了 core 不存在的端点」。
  const base = kind === 'task' ? Endpoints.tasks.root : Endpoints.tasks.rules;
  try {
    if (editingId) {
      await api.put(`${base}/${editingId}`, body);
    } else {
      await api.post(base, body);
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
// 开关成功后就地改本地字段，不整表重取：轮询 30s 才刷一次，等它会让开关看着卡住
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

// ── 初始化 + 自动刷新 ──
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
.top-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.empty-state {
  padding: 32px 0;
}
.task-list {
  display: flex;
  flex-direction: column;
}
</style>
