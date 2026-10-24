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
/* ↑ 上面两条「首行去上内距 / 末行去底边框」只在**一列堆叠**的整行设置项里成立。
   两列栅格里它们会错位（例如设备页 `.ctl-grid`）：第 1 行右格多出 12px 上内距，
   最后一格丢了底边框而邻居还留着。那种版式请由栅格容器自己控制边框与间距 ——
   父组件的 scoped 选择器作用不到子组件内部，但**子组件根元素会带上父组件的 scope 标记**，
   所以父组件写 `.ctl-grid :deep(.toggle-row)` 能命中；它和上面两条同为 (0,3,0)，
   胜负有赖样式顺序，稳妥写法是把类名叠一次提高特异性：
     .ctl-grid :deep(.toggle-row.toggle-row) { padding: 0; border-bottom: none; } */
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
