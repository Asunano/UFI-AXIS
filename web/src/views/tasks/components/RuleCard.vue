<template>
  <div class="rule-card">
    <div class="rule-visual">
      <div class="rule-node rule-trigger">
        <n-icon :size="20"><component :is="getTriggerIconComp(rule.triggerType)" /></n-icon>
      </div>
      <div class="rule-arrow"><ArrowForwardOutline /></div>
      <ActionIconBadge :action-type="rule.actionType" :size="38" />
    </div>
    <div class="rule-body">
      <div class="task-card-top">
        <span class="task-name" :title="nameTitle">{{ rule.name || triggerLabel(rule.triggerType) }}</span>
        <n-tag type="warning" size="tiny" round :bordered="false">条件触发</n-tag>
      </div>
      <div class="rule-meta">
        <span class="rule-cond" :title="triggerTitle"><b>当</b> {{ triggerSummary(rule) }}</span>
        <span class="rule-do" :title="actionTitle"><b>执行</b> {{ actionSummary(rule) }}</span>
        <span class="rule-cooldown">{{ cooldownText }}</span>
      </div>
    </div>
    <div class="rule-actions">
      <!-- ui-baseline-allow: 卡片行内的启停开关（无标签、无说明），套 ToggleRow 会把一条规则撑成三行 -->
      <n-switch :value="rule.enabled" size="medium" @update:value="(v: boolean) => emit('toggle', v)" />
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
 * 一条自动化规则。左侧是「触发 → 动作」的可视化：触发节点自己一套橙色，
 * 动作节点复用 ActionIconBadge（与定时任务卡同一份配色，见该组件的文件头）。
 */
import { computed } from 'vue';
import { ArrowForwardOutline, CreateOutline, ListOutline, TrashOutline } from '@vicons/ionicons5';
import ActionIconBadge from './ActionIconBadge.vue';
import {
  actionSummary,
  getTriggerIconComp,
  isKnownAction,
  isKnownTrigger,
  triggerLabel,
  triggerSummary,
  type Rule,
} from '../tasksShared';

const props = defineProps<{ rule: Rule }>();

const nameTitle = computed(() => {
  const known = !!props.rule.name || isKnownTrigger(props.rule.triggerType);
  return known ? undefined : `未知触发条件：${props.rule.triggerType}`;
});
/** 认不出的条件 / 动作：正文显示中文兜底，原始键放 title（不直出英文枚举） */
const triggerTitle = computed(() =>
  isKnownTrigger(props.rule.triggerType) ? undefined : `未知触发条件：${props.rule.triggerType}`
);
const actionTitle = computed(() =>
  isKnownAction(props.rule.actionType) ? undefined : `未知动作：${props.rule.actionType}`
);

/**
 * 冷却文案。`cooldownMs` 为 0 是**合法值**（不冷却），旧写法 `(x || 60000)` 会把它顶成 60s。
 * `null/undefined` 才表示"老数据没这个字段"，此时按后端默认 60s 显示。
 */
const cooldownText = computed(() => {
  const ms = props.rule.cooldownMs;
  if (ms == null) return '冷却 60s';
  if (ms <= 0) return '不冷却';
  return `冷却 ${Math.round(ms / 1000)}s`;
});

const emit = defineEmits<{
  (e: 'toggle', enabled: boolean): void;
  (e: 'edit'): void;
  (e: 'logs'): void;
  (e: 'delete'): void;
}>();
</script>

<style scoped>
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
/* 只剩触发节点用它了 —— 动作节点已经收进 ActionIconBadge，
   连同原来那条「.rule-action 兜底背景会盖掉动作配色」的特异性陷阱一起消失 */
.rule-node {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rule-trigger {
  background: var(--cat-orange-bg);
  color: var(--cat-orange-fg);
}
.rule-arrow {
  color: var(--text-muted);
  font-size: 18px;
}
.rule-body {
  flex: 1;
  min-width: 0;
}
/* 与 TaskCard 里的同名规则逐字相同，见该文件注释：故意保留两份，
   不为 8 行样式去动 DownloadsView 已占用的 `.task-name` 全局名字 */
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

@media (max-width: 768px) {
  .rule-card {
    flex-wrap: wrap;
  }
  .rule-actions {
    width: 100%;
    justify-content: flex-end;
    padding-top: 4px;
  }
}
</style>
