<template>
  <div class="input-bar">
    <n-input
      :value="modelValue"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 5 }"
      :placeholder="placeholder"
      :disabled="loading"
      class="mono-input"
      @update:value="(v: string) => emit('update:modelValue', v)"
      @keydown="onKeydown"
    />
    <n-button type="primary" :loading="loading" :disabled="!modelValue.trim()" @click="emit('send')"> 发送 </n-button>
  </div>
</template>

<script setup lang="ts">
import { NInput, NButton } from 'naive-ui';

const props = defineProps<{
  modelValue: string;
  loading?: boolean;
  placeholder?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void;
  (e: 'send'): void;
}>();

function onKeydown(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    if (props.modelValue.trim()) emit('send');
  }
}
</script>

<style scoped>
.input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  flex: 0 0 auto;
}
.input-bar :deep(.mono-input .n-input__textarea-el) {
  font-family: 'JetBrains Mono', 'Fira Code', monospace !important;
}
</style>
