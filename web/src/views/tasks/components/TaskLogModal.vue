<template>
  <n-modal
    :show="show"
    class="modal-fit"
    preset="dialog"
    :show-icon="false"
    style="--modal-w: 600px; --modal-h: 720px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <template #header>
      <div class="log-header">
        <span>执行日志</span>
        <span class="log-task-name">{{ title }}</span>
      </div>
    </template>

    <n-spin :show="logsLoading">
      <!-- 读取失败不再落成「暂无执行记录」（那是「没有日志」而不是「没读到」） -->
      <div v-if="loadError" class="log-empty log-failed" role="alert">
        <span class="log-failed-text">{{ loadError }}</span>
        <n-button size="tiny" :loading="logsLoading" @click="loadLogs">重试</n-button>
      </div>
      <div v-else-if="taskLogs.length === 0 && !logsLoading" class="log-empty">
        <n-empty description="暂无执行记录" />
      </div>
      <div v-else class="log-list">
        <div
          v-for="(log, idx) in taskLogs"
          :key="idx"
          class="log-item"
          :class="log.success ? 'log-success' : 'log-fail'"
        >
          <div class="log-item-header">
            <n-icon :size="15" class="log-status-icon">
              <CheckmarkCircle v-if="log.success" />
              <CloseCircle v-else />
            </n-icon>
            <span class="log-time">{{ formatTimestamp(log.timestamp) }}</span>
            <n-tag :type="log.success ? 'success' : 'error'" size="tiny" round :bordered="false">
              {{ log.success ? '成功' : '失败' }}
            </n-tag>
          </div>
          <div v-if="log.output" class="log-output">{{ log.output }}</div>
        </div>
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { CheckmarkCircle, CloseCircle } from '@vicons/ionicons5';
import { getApiClient } from '@/composables/useApi';
import { errText } from '../tasksShared';

const props = defineProps<{ show: boolean; title: string; kind: 'task' | 'rule'; id: string }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const api = getApiClient();

interface TaskLog {
  timestamp: string | number;
  success: boolean;
  output?: string;
}

const taskLogs = ref<TaskLog[]>([]);
const logsLoading = ref(false);
/** 读取失败的原因（空串 = 读成功）。失败与「没有日志」要分开显示 */
const loadError = ref('');

/** 请求序号：只有最后发出的那一次请求才有资格写 logs / 复位 loading
 *  （见 loadLogs；与 DeliveryHistoryModal 同一套机制） */
let logsSeq = 0;

function formatTimestamp(ts: string | number): string {
  if (!ts) return '--';
  // core 的 createdAt / ExecutionLog.timestamp 均为 epoch 毫秒
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

async function loadLogs() {
  // 记下这次请求的目标：回来时目标变了就丢弃结果（useLazyModal 的 350ms 保活窗口里
  // 「关 A → 开 B」时，A 的迟到响应会把 A 的日志填进标题写着 B 的弹窗）
  const id = props.id;
  const kind = props.kind;
  // 序号比 id/kind 更强：id 没变但用户又点了一次重试时，前一次请求的迟到响应
  // 同样不该覆盖新结果（也不能在 finally 里把新请求的 loading 提前复位）
  const seq = ++logsSeq;
  taskLogs.value = [];
  loadError.value = '';
  logsLoading.value = true;
  try {
    const { data } = await api.get(`/api/${kind === 'task' ? 'tasks' : 'rules'}/${id}/logs`);
    if (seq !== logsSeq || id !== props.id || kind !== props.kind) {
      return;
    }
    // core 按追加顺序返回（最旧在前），倒序展示让最新记录在最上
    taskLogs.value = (data.logs || [])
      .map((l: any) => ({
        timestamp: l.timestamp || l.time || '',
        success: l.success ?? l.ok ?? false,
        output: l.output || l.message || '',
      }))
      .reverse();
  } catch (e: any) {
    if (seq !== logsSeq || id !== props.id || kind !== props.kind) {
      return;
    }
    loadError.value = errText(e, '加载日志失败');
  } finally {
    // 过期请求不碰 loading：新请求的骨架屏会被这一下提前关掉、闪一下再回来
    if (seq === logsSeq) {
      logsLoading.value = false;
    }
  }
}

// 打开时拉取日志：watch 依赖从只看 show 改为 [show, id, kind]（与 CfTokenModal 同一处理）
watch(
  () => [props.show, props.id, props.kind] as const,
  ([v]) => {
    if (!v) {
      return;
    }
    void loadLogs();
  },
  { immediate: true }
);
</script>

<style scoped>
.log-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.log-task-name {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 400;
}
.log-empty {
  padding: var(--space-6) 0;
}
/* 失败态：一行原因 + 重试（与「暂无执行记录」区分开） */
.log-failed {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) 0;
  color: var(--error);
  font-size: var(--font-sm);
}
.log-failed-text {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
/* 列表不再自设 max-height：滚动交给 .modal-fit 的内容区（否则会出现里外两条滚动条，
   矮屏下用户要先滚外层再滚内层才发现底下还有记录） */
.log-list {
  min-height: 0;
}
.log-item {
  padding: 8px 10px;
  border-radius: 6px;
  margin-bottom: 6px;
  border-left: 3px solid transparent;
}
.log-item.log-success {
  background: var(--success-light);
  border-left-color: var(--success);
}
.log-item.log-fail {
  background: var(--error-light);
  border-left-color: var(--error);
}
.log-item-header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.log-success .log-status-icon {
  color: var(--success);
}
.log-fail .log-status-icon {
  color: var(--error);
}
.log-time {
  font-size: 12px;
  color: var(--text-muted);
}
.log-output {
  margin-top: 4px;
  font-size: 12px;
  font-family: monospace;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}
</style>
