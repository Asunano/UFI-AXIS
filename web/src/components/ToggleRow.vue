<template>
  <div class="toggle-row" :class="{ 'toggle-disabled': disabled }">
    <div class="toggle-info">
      <span class="toggle-label">{{ label }}</span>
      <span v-if="description" class="toggle-desc">{{ description }}</span>
    </div>
    <!-- #control 供「同一种设置行、但右侧不是开关」的场景（如隧道页的重连间隔数字框）。
         不传时就是默认的 n-switch —— 让这类行的标签/说明排版只有一套。 -->
    <slot name="control">
      <n-switch
        :value="modelValue"
        :loading="loading"
        :disabled="disabled"
        @update:value="$emit('update:modelValue', $event)"
      />
    </slot>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  description?: string;
  /** 用 #control 自定义右侧控件时不需要传 */
  modelValue?: boolean;
  loading?: boolean;
  disabled?: boolean;
}>();
defineEmits<{ 'update:modelValue': [value: boolean] }>();
defineSlots<{ control?(): any }>();
</script>

<style scoped>
.toggle-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
  gap: 16px;
}
.toggle-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.toggle-row:first-child {
  padding-top: 0;
}
.toggle-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.toggle-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}
.toggle-desc {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.4;
}
.toggle-disabled {
  opacity: 0.5;
}
</style>
