<template>
  <n-modal
    :show="show"
    preset="dialog"
    :show-icon="false"
    style="width: 600px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <template #header>
      <div class="log-header">
        <span>执行日志</span>
        <span class="log-task-name">{{ title }}</span>
      </div>
    </template>

    <n-spin :show="logsLoading">
      <div v-if="taskLogs.length === 0 && !logsLoading" class="log-empty">
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
import { useMessage } from 'naive-ui';
import { CheckmarkCircle, CloseCircle } from '@vicons/ionicons5';
import { getApiClient } from '@/composables/useApi';

const props = defineProps<{ show: boolean; title: string; kind: 'task' | 'rule'; id: string }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const api = getApiClient();

interface TaskLog {
  timestamp: string | number;
  success: boolean;
  output?: string;
}

const taskLogs = ref<TaskLog[]>([]);
const logsLoading = ref(false);

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

// 打开时拉取日志：core 按追加顺序返回（最旧在前），倒序展示让最新记录在最上
watch(
  () => props.show,
  async (v) => {
    if (!v) return;
    taskLogs.value = [];
    logsLoading.value = true;
    try {
      const { data } = await api.get(`/api/${props.kind === 'task' ? 'tasks' : 'rules'}/${props.id}/logs`);
      taskLogs.value = (data.logs || [])
        .map((l: any) => ({
          timestamp: l.timestamp || l.time || '',
          success: l.success ?? l.ok ?? false,
          output: l.output || l.message || '',
        }))
        .reverse();
    } catch {
      message.error('加载日志失败');
    } finally {
      logsLoading.value = false;
    }
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
  padding: 24px 0;
}
.log-list {
  max-height: 400px;
  overflow-y: auto;
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
