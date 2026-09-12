<template>
  <!--
    日志开关（log_enabled / core_log_enabled / app_log_enabled / debug_mode）不在这里：
    它们和其它通用配置共用一次 PUT /api/config 的差量提交（buildChangedPayload 只发改过的
    字段），拆到本面板会变成第二次独立提交、且与「通用」表单的 original 快照互不同步，
    所以留在「通用」分栏的表单里。本面板只负责日志的查看与清空。
  -->
  <div class="settings-panel">
    <!-- Card 7: 调试日志 -->
    <GridCard title="调试日志">
      <template #extra>
        <div class="log-header-actions">
          <n-switch v-model:value="autoRefreshLogs" size="small" style="--n-width: 60px">
            <template #checked>自动</template>
            <template #unchecked>手动</template>
          </n-switch>
          <n-button size="tiny" quaternary @click="loadDebugLogs">刷新</n-button>
          <n-button size="tiny" type="error" quaternary @click="clearLogs">清空</n-button>
        </div>
      </template>
      <div class="log-toolbar">
        <n-select
          v-model:value="logLevelFilter"
          :options="logLevelOptions"
          size="small"
          style="width: 140px"
          @update:value="onLogLevelChange"
        />
        <span class="log-count">共 {{ logTotal }} 条</span>
      </div>
      <div class="log-list">
        <div v-if="displayLogs.length === 0" class="text-muted" style="text-align: center; padding: 24px 0">
          暂无日志
        </div>
        <div v-for="(log, i) in displayLogs" :key="i" class="log-entry">
          <span class="log-level" :class="'level-' + log.level.toLowerCase()">{{ log.level }}</span>
          <span class="log-tag">{{ log.tag }}</span>
          <span class="log-message">{{ log.message }}</span>
          <span class="log-time">{{ log.timeStr }}</span>
        </div>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// ── 调试日志 ──
const logLevelFilter = ref('ALL');
const logLevelOptions = [
  { label: '全部 (ALL)', value: 'ALL' },
  { label: 'INFO', value: 'INFO' },
  { label: 'DEBUG', value: 'DEBUG' },
  { label: 'WARN', value: 'WARN' },
  { label: 'ERROR', value: 'ERROR' },
];
const logs = ref<any[]>([]);
const logTotal = ref(0);
const autoRefreshLogs = ref(false);
// 调试日志自动刷新；由 watch(autoRefreshLogs) 开关
const logPoll = useInterval(() => loadDebugLogs(), 3000, { autoStart: false });

const displayLogs = computed(() => [...logs.value].reverse().map(parseLogLine));

// core 的 /api/debug-logs 返回的是字符串数组，格式 "HH:mm:ss.SSS [LEVEL] [TAG] message"，
// 不是对象数组 —— 旧代码读 log.level.toLowerCase() 会直接抛异常把整张卡片渲染中断
const LOG_LINE_RE = /^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+\[([A-Z]+)\]\s+\[([^\]]*)\]\s*([\s\S]*)$/;

function parseLogLine(raw: any): { level: string; tag: string; message: string; timeStr: string } {
  const line = typeof raw === 'string' ? raw : String(raw?.message ?? raw ?? '');
  const m = LOG_LINE_RE.exec(line);
  if (!m) return { level: 'INFO', tag: '', message: line, timeStr: '' };
  return { timeStr: m[1] || '', level: m[2] || 'INFO', tag: m[3] || '', message: m[4] || '' };
}

async function loadDebugLogs() {
  try {
    const params: Record<string, any> = { limit: 200 };
    if (logLevelFilter.value !== 'ALL') params.level = logLevelFilter.value;
    const { data } = await api.get('/api/debug-logs', { params });
    logs.value = data.logs || [];
    logTotal.value = data.total ?? logs.value.length;
  } catch {
    /* silent */
  }
}

// ── Logs ──
function onLogLevelChange() {
  loadDebugLogs();
}

function clearLogs() {
  dialog.warning({
    title: '清空日志',
    content: '确定清空所有调试日志？',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete('/api/debug-logs');
        message.success('日志已清空');
        loadDebugLogs();
      } catch {
        message.error('清空失败');
      }
    },
  });
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════

watch(autoRefreshLogs, (val) => {
  if (val) logPoll.start();
  else logPoll.stop();
});

onMounted(() => {
  loadDebugLogs();
});

onUnmounted(() => {
  // 原 SettingsView 只清了 serviceRestartTimer，没有显式停这个 3s 轮询。
  // 显式 stop 保证「开着自动刷新时切走分栏」不会留下后台请求。
  logPoll.stop();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── Debug logs ── */
.log-header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}
.log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.log-count {
  font-size: 12px;
  color: var(--text-muted);
}
.log-list {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
}
.log-entry {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 5px 10px;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.5;
  border-bottom: 1px solid var(--border-subtle);
}
.log-entry:last-child {
  border-bottom: none;
}
.log-entry:hover {
  background: var(--surface-hover);
}
.log-level {
  flex-shrink: 0;
  font-weight: 600;
  min-width: 48px;
  text-align: center;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 1.7;
}
.level-info {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
/* debug 这档刻意保留写死值：它是这组语义色里唯一的「中性」成员，
   `--cat-neutral-*` 是不透明色块（浅色 #f5f5f5）、`--surface-hover` 又太淡，
   都会让这一档明显变样。等真要收时得先补一个 `--neutral-light` 档。 */
.level-debug {
  color: #606060;
  background: rgba(96, 96, 96, 0.1);
}
.level-warn {
  color: var(--warning);
  background: var(--warning-light);
}
.level-error {
  color: var(--error);
  background: var(--error-light);
}
.log-tag {
  flex-shrink: 0;
  color: var(--text-secondary);
  min-width: 60px;
  font-weight: 500;
}
.log-message {
  flex: 1;
  color: var(--text-primary);
  word-break: break-all;
  white-space: pre-wrap;
}
.log-time {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 11px;
}

/* ── Text helpers ── */
.text-muted {
  color: var(--text-muted);
  font-size: 13px;
}
</style>
