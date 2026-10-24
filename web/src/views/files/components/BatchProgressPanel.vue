<template>
  <!--
    批量操作进度条（2026-10-14 新建）。
    位置刻意与上传面板不同：上传队列固定在**右下角**，验证码浮层也在右下角（z-index 3000），
    两个都固定的浮层在窄屏上必然互相盖住。这里改成列表上方的一条 **sticky 常驻条**：
    它进入文档流、跟着列表滚动区吸顶，和右下角那两个浮层可以同时看见，谁也不挡谁。
  -->
  <div v-if="jobs.length" class="bp-bar" :class="{ 'is-collapsed': collapsed }">
    <div class="bp-head">
      <span class="bp-title">{{ headText }}</span>
      <span class="bp-sub">切页/关弹窗都不影响，刷新页面才会丢</span>
      <div class="bp-head-actions">
        <n-button v-if="hasUnfinished" size="tiny" quaternary @click="emit('cancel-all')">取消剩余</n-button>
        <n-button size="tiny" quaternary @click="emit('toggle')">{{ collapsed ? '展开' : '收起' }}</n-button>
        <n-button v-if="!hasUnfinished" size="tiny" quaternary @click="emit('clear')">清空</n-button>
      </div>
    </div>

    <div v-if="!collapsed" class="bp-list">
      <div v-for="job in jobs" :key="job.id" class="bp-job">
        <div class="bp-job-head">
          <span class="bp-job-title">
            {{ job.verb }} {{ doneCount(job) }}/{{ job.items.length }}
            <span v-if="failedCount(job)" class="bp-job-failed">失败 {{ failedCount(job) }}</span>
            <span v-else-if="canceledCount(job)" class="bp-job-muted">已取消 {{ canceledCount(job) }}</span>
          </span>
          <span class="bp-job-dir" :title="job.dir">{{ job.dir }}</span>
          <n-button
            v-if="failedCount(job) && !job.running"
            size="tiny"
            quaternary
            @click="emit('retry-failed', job.id)"
          >
            重试失败项
          </n-button>
        </div>

        <!-- 整批细进度：原来只有一个「3/10」的文字计数，扫一眼看不出还剩多少 -->
        <n-progress
          class="bp-progress"
          type="line"
          :percentage="jobPercent(job)"
          :height="3"
          :show-indicator="false"
          :status="jobProgressStatus(job)"
        />

        <div v-for="item in job.items" :key="item.id" class="bp-row">
          <div class="bp-row-top">
            <span class="bp-name" :title="item.path">{{ item.name }}</span>
            <span class="bp-state" :class="progressStateClass(item.status)">{{ progressStateText(item.status) }}</span>
            <!-- 只有还没轮到的项能取消：正在跑的那一项已经打到设备上了，标"已取消"就是谎报 -->
            <n-button v-if="item.status === 'pending'" size="tiny" quaternary @click="emit('cancel-item', item.id)">
              取消
            </n-button>
            <n-button
              v-else-if="item.status === 'error' || item.status === 'canceled'"
              size="tiny"
              quaternary
              @click="emit('retry-item', item.id)"
            >
              重试
            </n-button>
          </div>
          <div v-if="item.error" class="bp-row-sub">{{ item.error }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 批量操作进度条。
 *
 * 纯展示 + 意图上抛：队列、串行循环、重试闭包全在 `useBatchOperation` 里，这里只读 jobs。
 * 之所以做成常驻条而不是 `message.loading` 转圈：一次可以选中几十项，而 message 只能显示一行、
 * 也说不清"哪一个失败了、失败原因是什么"，更给不出"只重试这一个"的入口。
 */
import { computed } from 'vue';
import { progressStateClass, progressStateText } from '../filesShared';
import type { BatchJob } from '../useBatchOperation';

const props = defineProps<{
  jobs: BatchJob[];
  collapsed: boolean;
}>();

const emit = defineEmits<{
  (e: 'cancel-all'): void;
  (e: 'cancel-item', id: number): void;
  (e: 'retry-item', id: number): void;
  (e: 'retry-failed', id: number): void;
  (e: 'clear'): void;
  (e: 'toggle'): void;
}>();

/** 已落定的项数（成功/失败/取消都算"轮到过了"），用于 "删除中 3/10" 这类计数 */
function settledCount(job: BatchJob): number {
  return job.items.filter((i) => i.status !== 'pending' && i.status !== 'running').length;
}
function doneCount(job: BatchJob): number {
  return job.items.filter((i) => i.status === 'done').length;
}
function failedCount(job: BatchJob): number {
  return job.items.filter((i) => i.status === 'error').length;
}
function canceledCount(job: BatchJob): number {
  return job.items.filter((i) => i.status === 'canceled').length;
}

/** 整批进度：落定（成功/失败/取消都算）占全部的比例 */
function jobPercent(job: BatchJob): number {
  if (!job.items.length) return 0;
  return Math.round((settledCount(job) / job.items.length) * 100);
}

/** 细进度条的状态：跑完且无失败才是 success；有失败项恒为 error（一眼能挑出这一批要处理） */
function jobProgressStatus(job: BatchJob): 'default' | 'success' | 'error' {
  if (job.running) return 'default';
  return failedCount(job) ? 'error' : 'success';
}

const hasUnfinished = computed(() => props.jobs.some((j) => j.running));

const headText = computed(() => {
  const active = props.jobs.find((j) => j.running);
  if (active) return `批量${active.verb}中 ${settledCount(active)} / ${active.items.length}`;
  const total = props.jobs.reduce((s, j) => s + j.items.length, 0);
  const failed = props.jobs.reduce((s, j) => s + failedCount(j), 0);
  return failed ? `批量操作结束：失败 ${failed} / 共 ${total} 项` : `批量操作完成：${total} 项`;
});
</script>

<style scoped>
/* 配色/圆角/间距一律走 main.css 的令牌，不写死取值（check-ui-baseline.mjs 卡增量） */
.bp-bar {
  /* 吸顶：列表在 .files-body 里滚，本条始终停在工具栏下方，滚多远都看得见进度与取消按钮 */
  position: sticky;
  top: 0;
  z-index: 3;
  flex-shrink: 0;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  box-shadow: 0 2px 8px var(--shadow-color-soft);
  overflow: hidden;
}

.bp-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--surface-elevated);
  /* 窄屏上"标题 + 三个按钮"一行放不下：允许换行，别让按钮被挤出可视区 */
  flex-wrap: wrap;
}

.bp-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

/* 说明"这份状态活多久"：用户据此决定要不要现在把失败项处理掉 */
.bp-sub {
  flex: 1;
  min-width: 0;
  font-size: var(--font-xs);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bp-head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
}

/* 队列可能几十项：自身滚动，不把整页撑长 */
.bp-list {
  max-height: 220px;
  overflow-y: auto;
  padding: var(--space-2) var(--space-3) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.bp-job-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

/* 整批细进度：贴在批次标题下方、逐项行上方，用来一眼看出还剩多少 */
.bp-progress {
  margin-bottom: var(--space-2);
}

.bp-job-title {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.bp-job-failed {
  margin-left: var(--space-1);
  color: var(--error);
}
.bp-job-muted {
  margin-left: var(--space-1);
  color: var(--text-muted);
}

.bp-job-dir {
  flex: 1;
  min-width: 0;
  font-size: var(--font-xs);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}

.bp-row-top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.bp-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bp-state {
  font-size: var(--font-xs);
  color: var(--text-secondary);
  flex-shrink: 0;
}
.bp-state.is-done {
  color: var(--success);
}
.bp-state.is-error {
  color: var(--error);
}
.bp-state.is-muted {
  color: var(--text-muted);
}

.bp-row-sub {
  margin-top: var(--space-1);
  font-size: var(--font-xs);
  color: var(--error);
  word-break: break-all;
}

@media (max-width: 768px) {
  /* 手机上那行"状态能活多久"的说明先让位给按钮：它只是解释，按钮是操作 */
  .bp-sub {
    display: none;
  }
  .bp-list {
    max-height: 180px;
  }
}
</style>
