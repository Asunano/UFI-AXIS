<template>
  <div class="info-row">
    <span class="info-label">{{ label }}</span>
    <span class="info-value">
      <slot>{{ value }}</slot>
    </span>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  value?: string | number;
}>();
</script>

<style scoped>
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.info-row:last-child {
  border-bottom: none;
}
.info-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.info-value {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
  text-align: right;
  /* 长值（如 SSID）不撑爆列宽：溢出省略号 + 限最大占比 */
  max-width: 65%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
