<template>
  <div class="preset-section">
    <span class="preset-label">规则模板：</span>
    <div class="preset-chips">
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
  </div>
</template>

<script setup lang="ts">
/** 自动化规则的模板条：预设表在 tasksShared.ruleTemplates，点一下交给页面预填表单。视觉与 PresetChips（定时任务）同一套样式（见 chipStrip.css）。 */
import { ruleTemplates, getTriggerIconComp, type RuleTemplate } from '../tasksShared';

const emit = defineEmits<{ (e: 'apply', t: RuleTemplate): void }>();
</script>

<style scoped src="./chipStrip.css"></style>
