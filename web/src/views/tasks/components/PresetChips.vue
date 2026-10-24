<template>
  <div class="preset-section">
    <span class="preset-label">快捷创建：</span>
    <div class="preset-chips">
      <n-tag
        v-for="preset in presets"
        :key="preset.label"
        size="small"
        round
        :bordered="false"
        class="preset-chip"
        @click="emit('apply', preset)"
      >
        <n-icon :size="14" class="preset-icon"><component :is="preset.iconComp" /></n-icon>
        {{ preset.label }}
        <span class="preset-time">{{ formatHM(preset.hour, preset.minute) }}</span>
      </n-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 快捷创建定时任务的预设条。预设表在 tasksShared，点一下把它交给页面去开表单弹窗。 */
import { formatHM, presets, type TaskPreset } from '../tasksShared';

const emit = defineEmits<{ (e: 'apply', preset: TaskPreset): void }>();
</script>

<style scoped src="./chipStrip.css"></style>
