<template>
  <div class="task-card">
    <ActionIconBadge :action-type="task.actionType" :size="40" />
    <div class="task-card-body">
      <div class="task-card-top">
        <span class="task-name" :title="nameTitle">{{ task.name || actionLabel(task.actionType) }}</span>
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
      <!-- ui-baseline-allow: 卡片行内的启停开关（无标签、无说明），套 ToggleRow 会把一条任务撑成三行 -->
      <n-switch :value="task.enabled" size="medium" @update:value="(v: boolean) => emit('toggle', v)" />
      <n-button size="tiny" quaternary @click="emit('edit')"
        ><n-icon :size="15"><CreateOutline /></n-icon
      ></n-button>
      <n-button size="tiny" quaternary @click="emit('logs')"
        ><n-icon :size="15"><ListOutline /></n-icon
      ></n-button>
      <n-button size="tiny" quaternary type="error" @click="emit('delete')"
        ><n-icon :size="15"><TrashOutline /></n-icon
      ></n-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 一条定时任务。纯展示 + 意图上报：开关的乐观更新与接口写入都在页面里
 * （开关成功后页面直接改 task.enabled，失败不改 —— 那份时序不该拆两处）。
 */
import { computed } from 'vue';
import { TimeOutline, CreateOutline, ListOutline, TrashOutline } from '@vicons/ionicons5';
import ActionIconBadge from './ActionIconBadge.vue';
import {
  actionLabel,
  formatTimestampLocal,
  isKnownAction,
  isSpentOnce,
  scheduleLabel,
  scheduleSummaryText,
  type Task,
} from '../tasksShared';

const props = defineProps<{ task: Task }>();

/**
 * 标题兜底：没有名称时显示动作类型的中文 label；动作类型也认不出时显示「未知动作」，
 * 并把原始键放进 title —— 直接把 `data_toggle` 这样的键当标题上屏，用户既读不懂也查不到。
 */
const nameTitle = computed(() => {
  const known = !!props.task.name || isKnownAction(props.task.actionType);
  return known ? undefined : `未知动作：${props.task.actionType}`;
});

const emit = defineEmits<{
  (e: 'toggle', enabled: boolean): void;
  (e: 'edit'): void;
  (e: 'logs'): void;
  (e: 'delete'): void;
}>();
</script>

<style scoped>
.task-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
}
/* 组件根节点就是 .task-card，仍然是 .task-list 的直接子元素，:last-child 照样命中 */
.task-card:last-child {
  border-bottom: none;
}

.task-card-body {
  flex: 1;
  min-width: 0;
}
/* 与 RuleCard 里的同名规则逐字相同，是**故意**保留的两份：
   提到 main.css 需要用 `.task-name` 这个名字，而 DownloadsView 已经有自己的
   scoped `.task-name`（取值不同）—— 为 8 行样式去动用户正在改的文件不划算。 */
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
.task-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .task-card {
    flex-wrap: wrap;
  }
  .task-card-actions {
    width: 100%;
    justify-content: flex-end;
    padding-top: 4px;
  }
}
</style>
