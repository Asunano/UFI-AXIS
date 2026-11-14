<template>
  <div class="preset-section">
    <span class="preset-label">规则模板：</span>
    <n-tooltip v-for="t in ruleTemplates" :key="t.label" trigger="hover">
      <template #trigger>
        <n-tag size="small" round :bordered="false" class="preset-chip" @click="emit('apply', t)">
          <n-icon :size="14" class="preset-icon"><component :is="getTriggerIconComp(t.triggerType)" /></n-icon>
          {{ t.label }}
        </n-tag>
      </template>
      {{ t.desc }}
    </n-tooltip>
  </div>
</template>

<script setup lang="ts">
/** 自动化规则的模板条：预设表在 tasksShared.ruleTemplates，点一下交给页面预填表单。视觉与 PresetChips（定时任务）同一套。 */
import { ruleTemplates, getTriggerIconComp, type RuleTemplate } from '../tasksShared';

const emit = defineEmits<{ (e: 'apply', t: RuleTemplate): void }>();
</script>

<style scoped>
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

@media (max-width: 768px) {
  .preset-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
