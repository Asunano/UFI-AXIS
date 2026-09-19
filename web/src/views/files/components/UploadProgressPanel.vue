<template>
  <!-- 固定在右下角的浮层，不进入列表滚动区：上传时用户还要继续浏览目录 -->
  <div v-if="open && tasks.length" class="up-panel">
    <div class="up-head">
      <span class="up-title">{{ headText }}</span>
      <div class="up-head-actions">
        <n-button v-if="uploading" size="tiny" quaternary @click="emit('cancel-all')">全部取消</n-button>
        <n-button v-else size="tiny" quaternary @click="emit('clear')">清空</n-button>
        <n-button size="tiny" quaternary @click="emit('close')">收起</n-button>
      </div>
    </div>

    <!-- 整批进度按字节加权（见 useFileUpload.overallPercent）：按文件数算会长时间卡在同一格 -->
    <n-progress
      v-if="uploading"
      class="up-overall"
      type="line"
      :percentage="overallPercent"
      :height="4"
      :show-indicator="false"
      processing
    />

    <div class="up-list">
      <div v-for="t in tasks" :key="t.id" class="up-row">
        <div class="up-row-top">
          <span class="up-name" :title="t.name">{{ t.name }}</span>
          <span class="up-state" :class="stateClass(t)">{{ stateText(t) }}</span>
          <n-button
            v-if="t.status === 'pending' || t.status === 'uploading'"
            size="tiny"
            quaternary
            @click="emit('cancel', t.id)"
          >
            取消
          </n-button>
        </div>
        <n-progress
          type="line"
          :percentage="percentOf(t)"
          :height="3"
          :show-indicator="false"
          :status="t.status === 'error' ? 'error' : t.status === 'done' ? 'success' : 'default'"
        />
        <div class="up-row-sub">{{ subText(t) }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 上传进度面板。
 *
 * 纯展示 + 意图上抛：队列、XHR、重试全在 `useFileUpload` 里，这里只读 tasks。
 * 之所以做成常驻浮层而不是 `message.loading` 转圈：一次可以选多个文件，
 * 而 message 只能显示一行、也说不清"哪一个失败了"。
 */
import { computed } from 'vue';
import { formatBytes } from '@/composables/utils';
import type { UploadTask } from '../useFileUpload';

const props = defineProps<{
  open: boolean;
  tasks: UploadTask[];
  uploading: boolean;
  overallPercent: number;
  doneCount: number;
  failedCount: number;
  percentOf: (t: UploadTask) => number;
}>();

const emit = defineEmits<{
  (e: 'cancel', id: number): void;
  (e: 'cancel-all'): void;
  (e: 'clear'): void;
  (e: 'close'): void;
}>();

const headText = computed(() => {
  const total = props.tasks.length;
  if (props.uploading) return `上传中 ${props.doneCount} / ${total}`;
  if (props.failedCount) return `上传结束：成功 ${props.doneCount}，失败 ${props.failedCount}`;
  return `上传完成 ${props.doneCount} / ${total}`;
});

function stateText(t: UploadTask): string {
  switch (t.status) {
    case 'pending':
      return '排队中';
    case 'uploading':
      return `${props.percentOf(t)}%`;
    case 'done':
      return '完成';
    case 'canceled':
      return '已取消';
    default:
      return '失败';
  }
}

function stateClass(t: UploadTask): string {
  if (t.status === 'error') return 'is-error';
  if (t.status === 'done') return 'is-done';
  if (t.status === 'canceled') return 'is-muted';
  return '';
}

/** 副行：传输量 + 速率；失败时换成原因（用户此刻要的是原因，不是又传了多少） */
function subText(t: UploadTask): string {
  if (t.status === 'error') return t.error || '上传失败';
  if (t.status === 'canceled') return '已取消';
  const size = formatBytes(t.size);
  if (t.status === 'done') return size;
  const sent = formatBytes(t.loaded);
  // 速率还没采到样时不显示 "0 B/s"：那会被读成"卡住了"
  const speed = t.speed > 0 ? ` · ${formatBytes(t.speed)}/s` : '';
  return `${sent} / ${size}${speed}`;
}
</script>

<style scoped>
/* 配色/圆角/间距一律走 main.css 的令牌，不写死取值（check-ui-baseline.mjs 卡增量） */
.up-panel {
  position: fixed;
  right: var(--space-4);
  bottom: var(--space-4);
  z-index: 1200;
  width: 340px;
  max-width: calc(100vw - var(--space-6));
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  box-shadow: 0 10px 30px var(--shadow-color-strong);
  overflow: hidden;
}

.up-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--surface-elevated);
  border-bottom: 1px solid var(--border-subtle);
}

.up-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.up-head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
}

.up-overall {
  padding: var(--space-2) var(--space-3) 0;
}

/* 队列可能几十项：自身滚动，不把整页撑长 */
.up-list {
  max-height: 260px;
  overflow-y: auto;
  padding: var(--space-2) var(--space-3) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.up-row-top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

.up-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.up-state {
  font-size: var(--font-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.up-state.is-done {
  color: var(--success);
}
.up-state.is-error {
  color: var(--error);
}
.up-state.is-muted {
  color: var(--text-muted);
}

.up-row-sub {
  margin-top: var(--space-1);
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 768px) {
  .up-panel {
    left: var(--space-3);
    right: var(--space-3);
    width: auto;
  }
  .up-list {
    max-height: 200px;
  }
}
</style>
